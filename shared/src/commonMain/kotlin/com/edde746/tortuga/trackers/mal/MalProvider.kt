package com.edde746.tortuga.trackers.mal

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.trackers.RelayResult
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
import com.edde746.tortuga.trackers.client.obj
import com.edde746.tortuga.trackers.client.string
import com.edde746.tortuga.trackers.ratings.TrackerRatingsProvider
import com.edde746.tortuga.trackers.ratings.TrackerRatingsSource
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import kotlin.time.Clock

/**
 * MyAnimeList: sign-in through the OAuth relay (MyAnimeList's authorization-code flow with PKCE,
 * run by the relay, which holds the client secret), series progress, ratings, and the plan-to-watch list and the
 * airing ranking as catalog rows.
 *
 * Refreshes go straight to MyAnimeList as a public client (no redirect is involved), which is why the client id ships
 * in the app. MyAnimeList documents no token revocation, so a sign-out only forgets the tokens.
 *
 * @param clientId this app's MyAnimeList registration, the same one the relay uses.
 */
class MalProvider internal constructor(
    private val http: TrackerHttp,
    private val clientId: String,
    private val nowEpochSeconds: () -> Long,
) : TrackerProvider, TrackerCatalogProvider, TrackerRatingsProvider {
    constructor(http: HttpClient, clientId: String, maxBodyBytes: Long) :
        this(TrackerHttp(http, TrackerService.MYANIMELIST, maxBodyBytes = maxBodyBytes), clientId, { Clock.System.now().epochSeconds })

    override val service: TrackerService get() = TrackerService.MYANIMELIST

    override val signIn: TrackerSignIn.Relay = object : TrackerSignIn.Relay {
        override suspend fun session(result: RelayResult): TrackerSession {
            val refreshToken = result.refreshToken?.takeIf { it.isNotEmpty() }
                ?: throw TrackerException.SignIn(service, "missing refresh token")
            val now = nowEpochSeconds()
            val session = TrackerSession(
                accessToken = result.accessToken,
                refreshToken = refreshToken,
                expiresAt = now + (result.expiresInSeconds ?: DEFAULT_EXPIRES_IN),
                createdAt = now,
            )
            val username = TokenAnswers.username(http, "$API/users/@me", session.accessToken) { user -> user.obj?.get("name").string }
            return session.copy(username = username)
        }
    }

    /** myanimelist.net/apiconfig/references/authorization, "Refresh an access token". */
    override val refresher: TokenRefresher = TokenRefresher { session ->
        val refreshToken = session.refreshToken?.takeIf { it.isNotEmpty() }
            ?: throw TrackerException.Auth(service, "missing refresh token", permanent = true)
        val response = http.send(
            HttpMethod.Post,
            TOKEN_URL,
            body = TrackerHttp.Body.Form(listOf("client_id" to clientId, "grant_type" to "refresh_token", "refresh_token" to refreshToken)),
            timeout = TrackerHttp.REFRESH_TIMEOUT,
        )
        if (response.status != 200) throw TokenAnswers.refreshFailure(service, response.status)
        TokenAnswers.session(service, response.fields(), nowEpochSeconds(), refreshable = true, username = session.username)
    }

    override fun tracker(credentials: TrackerCredentials): Tracker = MalTracker(api(credentials))

    override fun catalog(credentials: TrackerCredentials): TrackerCatalogSource = MalCatalog(api(credentials))

    override fun ratings(credentials: TrackerCredentials): TrackerRatingsSource = MalRatings(api(credentials))

    override suspend fun revoke(session: TrackerSession) {
        Log.d(TAG) { "mal: no revocation endpoint, the tokens are only forgotten" }
    }

    private fun api(credentials: TrackerCredentials) = MalApi(http, credentials)

    internal companion object {
        const val API = "https://api.myanimelist.net/v2"
        const val TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"
        private const val TAG = "trackers"

        /** MyAnimeList's access tokens last 31 days (the fallback when the relay omits `expiresIn`). */
        private const val DEFAULT_EXPIRES_IN = 31L * 24 * 60 * 60
    }
}

/** The account's authenticated MyAnimeList calls: any 2xx is success, a 401 refreshes once. */
internal class MalApi(private val http: TrackerHttp, private val credentials: TrackerCredentials) {
    suspend fun get(pathAndQuery: String): TrackerHttp.Response =
        http.authorized(credentials, TrackerHttp.Unauthorized.REFRESH, HttpMethod.Get, MalProvider.API + pathAndQuery)

    /** `GET` that answers `null` for a 404 (an id MyAnimeList doesn't know). */
    suspend fun getOrNull(pathAndQuery: String): TrackerHttp.Response? = try {
        get(pathAndQuery)
    } catch (e: TrackerException.Api) {
        if (e.status == 404) null else throw e
    }

    suspend fun delete(path: String): TrackerHttp.Response =
        http.authorized(credentials, TrackerHttp.Unauthorized.REFRESH, HttpMethod.Delete, MalProvider.API + path)

    /** The list-status endpoint takes a form, not JSON. */
    suspend fun put(path: String, fields: List<Pair<String, String>>): TrackerHttp.Response =
        http.authorized(credentials, TrackerHttp.Unauthorized.REFRESH, HttpMethod.Put, MalProvider.API + path, body = TrackerHttp.Body.Form(fields))
}
