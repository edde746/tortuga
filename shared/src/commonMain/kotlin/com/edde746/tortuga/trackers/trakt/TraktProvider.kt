package com.edde746.tortuga.trackers.trakt

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.trackers.DeviceCodeGrant
import com.edde746.tortuga.trackers.DevicePoll
import com.edde746.tortuga.trackers.TokenRefresher
import com.edde746.tortuga.trackers.Tracker
import com.edde746.tortuga.trackers.TrackerCredentials
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerProvider
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.TrackerSession
import com.edde746.tortuga.trackers.TrackerSignIn
import com.edde746.tortuga.trackers.catalog.TrackerCatalogProvider
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.client.TokenAnswers
import com.edde746.tortuga.trackers.client.TrackerHttp
import com.edde746.tortuga.trackers.client.flexibleLong
import com.edde746.tortuga.trackers.client.obj
import com.edde746.tortuga.trackers.client.string
import com.edde746.tortuga.trackers.ratings.TrackerRatingsProvider
import com.edde746.tortuga.trackers.ratings.TrackerRatingsSource
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Trakt: device-code sign-in direct with Trakt, live scrobble, watched history, ratings, and the
 * watchlist, recommendations and trending as catalog rows.
 *
 * Trakt's access tokens last 7 days and its refresh tokens are single-use: every refresh returns a new pair
 * (docs.trakt.tv/docs/authentication-oauth), which is why the account server holds the session and alone refreshes it
 * with [refresher], for every device of the account.
 *
 * @param clientId this app's Trakt registration. Trakt signs users in with the client ID alone: since 2026-10-01 its
 *   apps get no client secret, and the secret is optional and deprecated on every `/oauth` body
 *   (developer.trakt.tv/docs/authentication-oauth).
 */
class TraktProvider internal constructor(
    private val http: TrackerHttp,
    private val clientId: String,
    private val nowEpochSeconds: () -> Long,
) : TrackerProvider, TrackerCatalogProvider, TrackerRatingsProvider {
    constructor(http: HttpClient, clientId: String, maxBodyBytes: Long) :
        this(TrackerHttp(http, TrackerService.TRAKT, maxBodyBytes = maxBodyBytes), clientId, { Clock.System.now().epochSeconds })

    override val service: TrackerService get() = TrackerService.TRAKT

    override val signIn: TrackerSignIn.DeviceCode = object : TrackerSignIn.DeviceCode {
        override suspend fun requestCode(): DeviceCodeGrant {
            val response = http.send(
                HttpMethod.Post,
                "$API/oauth/device/code",
                headers(),
                TrackerHttp.Body.Json(buildJsonObject { put("client_id", clientId) }),
                TrackerHttp.AUTH_TIMEOUT,
            )
            val body = response.fields()
            val deviceCode = body["device_code"].string
            val userCode = body["user_code"].string
            val verificationUrl = body["verification_url"].string
            if (response.status != 200 || deviceCode == null || userCode == null || verificationUrl == null) {
                throw TrackerException.SignIn(service, "device code request answered HTTP ${response.status}")
            }
            return DeviceCodeGrant(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUrl = verificationUrl,
                // Trakt's activation page takes the code as a path segment (`auth.trakt.tv/activate/ABCD1234` carries
                // it through sign-in as `user_code`).
                verificationUrlComplete = "${verificationUrl.trimEnd('/')}/$userCode",
                expiresInSeconds = body["expires_in"].flexibleLong ?: DEFAULT_EXPIRES_IN,
                intervalSeconds = body["interval"].flexibleLong ?: DEFAULT_INTERVAL,
            )
        }

        /** docs.trakt.tv/reference/postoauthdevicetoken: the poll state is the HTTP status. */
        override suspend fun probe(grant: DeviceCodeGrant): DevicePoll {
            val response = try {
                http.send(
                    HttpMethod.Post,
                    "$API/oauth/device/token",
                    headers(),
                    TrackerHttp.Body.Json(
                        buildJsonObject {
                            put("code", grant.deviceCode)
                            put("client_id", clientId)
                        },
                    ),
                    TrackerHttp.AUTH_TIMEOUT,
                )
            } catch (e: TrackerException.Network) {
                // Unreachable for a moment: keep polling until the code expires.
                return DevicePoll.Pending
            }
            return when (response.status) {
                200 -> DevicePoll.Success(response.fields())
                400 -> DevicePoll.Pending
                404, 410 -> DevicePoll.Expired
                409, 418 -> DevicePoll.Denied
                429 -> DevicePoll.SlowDown
                else -> {
                    Log.w(TAG) { "trakt device poll: unexpected HTTP ${response.status}" }
                    DevicePoll.Pending
                }
            }
        }

        override suspend fun session(token: JsonObject): TrackerSession {
            val session = try {
                TokenAnswers.session(service, token, nowEpochSeconds(), refreshable = true)
            } catch (e: TrackerException.Auth) {
                throw TrackerException.SignIn(service, e.message.orEmpty())
            }
            val username = TokenAnswers.username(http, "$API/users/settings", session.accessToken, headers()) { settings ->
                settings.obj?.get("user").obj?.get("username").string
            }
            return session.copy(username = username)
        }
    }

    override val refresher: TokenRefresher = TokenRefresher { session ->
        val refreshToken = session.refreshToken?.takeIf { it.isNotEmpty() }
            ?: throw TrackerException.Auth(service, "missing refresh token", permanent = true)
        val response = http.send(
            HttpMethod.Post,
            "$API/oauth/token",
            headers(),
            TrackerHttp.Body.Json(
                buildJsonObject {
                    put("refresh_token", refreshToken)
                    put("client_id", clientId)
                    put("grant_type", "refresh_token")
                },
            ),
            TrackerHttp.REFRESH_TIMEOUT,
        )
        if (response.status != 200) throw TokenAnswers.refreshFailure(service, response.status)
        TokenAnswers.session(service, response.fields(), nowEpochSeconds(), refreshable = true, username = session.username)
    }

    override fun tracker(credentials: TrackerCredentials): Tracker = TraktTracker(api(credentials))

    override fun catalog(credentials: TrackerCredentials): TrackerCatalogSource = TraktCatalog(api(credentials))

    override fun ratings(credentials: TrackerCredentials): TrackerRatingsSource = TraktRatings(api(credentials))

    /** Trakt's related titles: public, asked with this app's client id alone, without an account. */
    internal fun related(timeSource: TimeSource.WithComparableMarks): TraktRelated = TraktRelated(http, headers(), timeSource)

    /** Trakt's public lists: asked with this app's client id alone, without an account. */
    internal fun lists(timeSource: TimeSource.WithComparableMarks): TraktLists = TraktLists(http, headers(), timeSource)

    /** docs.trakt.tv/reference/postoauthrevoke. */
    override suspend fun revoke(session: TrackerSession) = TokenAnswers.revokeQuietly(service) {
        http.send(
            HttpMethod.Post,
            "$API/oauth/revoke",
            headers(),
            TrackerHttp.Body.Json(
                buildJsonObject {
                    put("token", session.accessToken)
                    put("client_id", clientId)
                },
            ),
            TrackerHttp.REVOKE_TIMEOUT,
        )
    }

    private fun api(credentials: TrackerCredentials) = TraktApi(http, credentials, headers())

    /** The headers every Trakt call needs (docs.trakt.tv/docs/getting-started). */
    private fun headers(): Map<String, String> = mapOf("trakt-api-version" to "2", "trakt-api-key" to clientId)

    internal companion object {
        const val API = "https://api.trakt.tv"
        private const val TAG = "trackers"
        private const val DEFAULT_EXPIRES_IN = 600L
        private const val DEFAULT_INTERVAL = 5L
    }
}

/** The account's authenticated Trakt calls: a 401 refreshes once, a 429 is rate limited. */
internal class TraktApi(
    private val http: TrackerHttp,
    private val credentials: TrackerCredentials,
    private val headers: Map<String, String>,
) {
    suspend fun get(path: String): TrackerHttp.Response =
        http.authorized(credentials, TrackerHttp.Unauthorized.REFRESH, HttpMethod.Get, TraktProvider.API + path, headers)

    suspend fun post(path: String, body: JsonObject, accept: (Int) -> Boolean = { isWriteOk(it) }): TrackerHttp.Response =
        http.authorized(credentials, TrackerHttp.Unauthorized.REFRESH, HttpMethod.Post, TraktProvider.API + path, headers, TrackerHttp.Body.Json(body), accept)

    companion object {
        /** What a write accepts by default. */
        fun isWriteOk(status: Int): Boolean = status == 200 || status == 201 || status == 204

        /**
         * A scrobble also accepts 409: Trakt takes one scrobble per item within its window and answers the rest with
         * a conflict carrying the earlier `watched_at` (docs.trakt.tv/reference/postscrobblestop).
         */
        fun isScrobbleOk(status: Int): Boolean = status == 200 || status == 201 || status == 409
    }
}
