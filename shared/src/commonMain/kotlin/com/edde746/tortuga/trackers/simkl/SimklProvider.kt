package com.edde746.tortuga.trackers.simkl

import com.edde746.tortuga.trackers.AnimeIds
import com.edde746.tortuga.trackers.DeviceCodeGrant
import com.edde746.tortuga.trackers.DevicePoll
import com.edde746.tortuga.trackers.ExternalIds
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlin.time.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Simkl: device-code sign-in direct with Simkl, live scrobble, watched history, ratings, and the
 * plan-to-watch list as a catalog row.
 *
 * Sign-in is Simkl's AUTH V2 (released 2026-09-18; api.simkl.org/authentication), not the V1 PIN flow: a new
 * registration only works on the `/oauth2/` endpoints, and V1 retires around April 2027. V2 is standard RFC 8628 with the scope
 * asked for explicitly (read-only otherwise), 7-day access tokens and a sliding, non-rotating 180-day refresh token.
 * Each sign-in is its own grant, so two devices never refresh each other's token away.
 *
 * @param clientId this app's Simkl V2 registration (public by design, api.simkl.org/guides/migrating-v1-to-v2).
 */
class SimklProvider internal constructor(
    private val http: TrackerHttp,
    private val clientId: String,
    private val nowEpochSeconds: () -> Long,
) : TrackerProvider, TrackerCatalogProvider, TrackerRatingsProvider {
    constructor(http: HttpClient, clientId: String, maxBodyBytes: Long) :
        this(TrackerHttp(http, TrackerService.SIMKL, maxBodyBytes = maxBodyBytes), clientId, { Clock.System.now().epochSeconds })

    override val service: TrackerService get() = TrackerService.SIMKL

    override val signIn: TrackerSignIn.DeviceCode = object : TrackerSignIn.DeviceCode {
        /** api.simkl.org/api-reference/oauth2-device, step 1. */
        override suspend fun requestCode(): DeviceCodeGrant {
            val response = http.send(
                HttpMethod.Post,
                "$API/oauth2/device",
                body = TrackerHttp.Body.Form(listOf("client_id" to clientId, "scope" to SCOPE)),
                timeout = TrackerHttp.AUTH_TIMEOUT,
            )
            return TokenAnswers.deviceCodeGrant(service, response, VERIFICATION_URL, DEFAULT_EXPIRES_IN)
        }

        /**
         * RFC 8628 errors in a 400 body. Simkl records no denial (a declined code stays `authorization_pending` until
         * it expires), and `invalid_client` means the registration isn't a V2 one: polling can't fix that.
         */
        override suspend fun probe(grant: DeviceCodeGrant): DevicePoll {
            val response = try {
                http.send(
                    HttpMethod.Post,
                    "$API/oauth2/token",
                    body = TrackerHttp.Body.Form(
                        listOf("grant_type" to TokenAnswers.DEVICE_CODE_GRANT, "client_id" to clientId, "device_code" to grant.deviceCode),
                    ),
                    timeout = TrackerHttp.AUTH_TIMEOUT,
                )
            } catch (e: TrackerException.Network) {
                return DevicePoll.Pending
            }
            if (response.status == 401 || response.fields()["error"].string == "invalid_client") {
                throw TrackerException.SignIn(service, "client not enabled for OAuth 2.0 (HTTP ${response.status})")
            }
            return TokenAnswers.devicePoll(service, response)
        }

        override suspend fun session(token: JsonObject): TrackerSession {
            // An unrecognised scope is silently downgraded to read-only (api.simkl.org/api-reference/oauth2-scopes):
            // such a grant would fail at the first scrobble, so it fails here instead.
            val scope = token["scope"].string
            if (scope != null && "media:write" !in scope.split(' ')) throw TrackerException.SignIn(service, "read-only grant")
            val session = try {
                TokenAnswers.session(service, token, nowEpochSeconds(), refreshable = true)
            } catch (e: TrackerException.Auth) {
                throw TrackerException.SignIn(service, e.message.orEmpty())
            }
            val username = TokenAnswers.username(http, url("/users/settings"), session.accessToken, headers()) { settings ->
                settings.obj?.get("user").obj?.get("name").string
            }
            return session.copy(username = username)
        }
    }

    /** api.simkl.org/api-reference/oauth2-tokens: the refresh token comes back unchanged, its 180 days renewed. */
    override val refresher: TokenRefresher = TokenRefresher { session ->
        val refreshToken = session.refreshToken?.takeIf { it.isNotEmpty() }
            ?: throw TrackerException.Auth(service, "missing refresh token", permanent = true)
        val response = http.send(
            HttpMethod.Post,
            "$API/oauth2/token",
            body = TrackerHttp.Body.Form(listOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refreshToken)),
            timeout = TrackerHttp.REFRESH_TIMEOUT,
        )
        if (response.status != 200) throw TokenAnswers.refreshFailure(service, response.status)
        TokenAnswers.session(
            service,
            response.fields(),
            nowEpochSeconds(),
            refreshable = true,
            keepRefreshToken = refreshToken,
            username = session.username,
        )
    }

    override fun tracker(credentials: TrackerCredentials): Tracker = SimklTracker(api(credentials))

    override fun catalog(credentials: TrackerCredentials): TrackerCatalogSource = SimklCatalog(api(credentials))

    override fun ratings(credentials: TrackerCredentials): TrackerRatingsSource = SimklRatings(api(credentials))

    /** api.simkl.org/api-reference/simkl/oauth2-revoke: either token ends the grant; it always answers 200. */
    override suspend fun revoke(session: TrackerSession) = TokenAnswers.revokeQuietly(service) {
        http.send(
            HttpMethod.Post,
            "$API/oauth2/revoke",
            body = TrackerHttp.Body.Form(listOf("client_id" to clientId, "token" to (session.refreshToken ?: session.accessToken))),
            timeout = TrackerHttp.REVOKE_TIMEOUT,
        )
    }

    private fun api(credentials: TrackerCredentials) = SimklApi(http, credentials, clientId)

    private fun headers(): Map<String, String> = mapOf("simkl-api-key" to clientId)

    private fun url(path: String): String = SimklApi.url(clientId, path)

    internal companion object {
        const val API = "https://api.simkl.com"
        const val SCOPE = "media:read media:write"
        const val VERIFICATION_URL = "https://simkl.com/pin"
        private const val DEFAULT_EXPIRES_IN = 900L
    }
}

/**
 * The account's Simkl calls. Every request carries the app's identity as `client_id`, `app-name` and `app-version`
 * parameters and the `simkl-api-key` header (api.simkl.org/conventions/headers). A V2 client reaches nothing
 * user-scoped without the account's token, `/redirect` included (api.simkl.org/api-reference/auth-v2), while the
 * cached catalog records go without one ([record]). Any 2xx is success; a 401 refreshes once.
 *
 * Simkl's refusals are told apart by their body, each with the wait it asks for (api.simkl.org/resources/rate-limits,
 * "Limit signals"), and all are [TrackerException.RateLimited]: a `429 rate_limit` is the per-second rate and clears
 * in about a second (its `Retry-After` is the daily reset's, not its own); a daily quota's `429` waits its
 * `Retry-After`; a `412` is the throttling block POST overages start and extend; a `400 RATE_LIMIT` is the 20-second
 * per-user write lock, which clears once the write before it ends.
 */
internal class SimklApi(
    private val http: TrackerHttp,
    private val credentials: TrackerCredentials,
    private val clientId: String,
) {
    private val headers = mapOf("simkl-api-key" to clientId)

    suspend fun get(path: String, query: String = ""): TrackerHttp.Response =
        checked(http.authorized(credentials, TrackerHttp.Unauthorized.REFRESH, HttpMethod.Get, url(clientId, path, query), headers, accept = { true }))

    suspend fun post(path: String, body: JsonObject, accept: (Int) -> Boolean = { TrackerHttp.isOk(it) }): TrackerHttp.Response = checked(
        http.authorized(credentials, TrackerHttp.Unauthorized.REFRESH, HttpMethod.Post, url(clientId, path), headers, TrackerHttp.Body.Json(body), accept = { true }),
        accept,
    )

    /**
     * A catalog record (`/movies/{id}`, `/tv/{id}`, `/anime/{id}`) without the account's token: those are served from
     * Cloudflare's edge, which an `Authorization` header bypasses (api.simkl.org/conventions/headers).
     */
    suspend fun record(path: String): TrackerHttp.Response {
        val response = http.send(HttpMethod.Get, url(clientId, path), headers)
        if (!TrackerHttp.isOk(response.status)) throw http.failure(response)
        return response
    }

    /**
     * `GET /redirect` (api.simkl.org/api-reference/simkl/redirect) for [query]: the `Location` it answers, read without
     * following it, as Simkl asks (the page it points at holds no API data); `null` when it answered without one.
     */
    suspend fun redirect(query: String): String? {
        val response = checked(
            http.authorized(
                credentials,
                TrackerHttp.Unauthorized.REFRESH,
                HttpMethod.Get,
                url(clientId, "/redirect", query),
                headers,
                accept = { true },
                followRedirects = false,
            ),
        ) { TrackerHttp.isOk(it) || it in 300..399 }
        return if (response.status in 300..399) response.headers[HttpHeaders.Location] else null
    }

    /** [response] when [accept] takes its status, else its failure. */
    private fun checked(response: TrackerHttp.Response, accept: (Int) -> Boolean = { TrackerHttp.isOk(it) }): TrackerHttp.Response {
        if (accept(response.status)) return response
        val error = response.fields()["error"].string
        throw when {
            response.status == 429 && error == "rate_limit" -> TrackerException.RateLimited(TrackerService.SIMKL, BURST_WAIT_SECONDS)
            response.status == 412 -> TrackerException.RateLimited(TrackerService.SIMKL, THROTTLE_BLOCK_WAIT_SECONDS)
            response.status == 400 && error == "RATE_LIMIT" -> TrackerException.RateLimited(TrackerService.SIMKL, WRITE_LOCK_WAIT_SECONDS)
            else -> http.failure(response)
        }
    }

    companion object {
        const val APP_NAME = "tortuga"
        const val APP_VERSION = "1"

        /** A burst over the per-second rate: "retry in about a second". */
        const val BURST_WAIT_SECONDS = 2L

        /** Simkl names no length for its throttling block. */
        const val THROTTLE_BLOCK_WAIT_SECONDS = 300L

        /** The per-user write lock: "retry shortly". */
        const val WRITE_LOCK_WAIT_SECONDS = 5L

        fun url(clientId: String, path: String, query: String = ""): String =
            SimklProvider.API + path + "?client_id=${clientId.encodeURLParameter()}&app-name=$APP_NAME&app-version=$APP_VERSION" +
                if (query.isEmpty()) "" else "&$query"
    }
}

/** Simkl's own id first (the anime mapping's, stricter for anime), then the external ids; `null` if none. */
internal fun simklIds(external: ExternalIds, anime: AnimeIds?): JsonObject? = buildJsonObject {
    anime?.simkl?.let { put("simkl", it) }
    external.tvdb?.let { put("tvdb", it) }
    external.tmdb?.let { put("tmdb", it) }
    external.imdb?.let { put("imdb", it) }
}.takeIf { it.isNotEmpty() }
