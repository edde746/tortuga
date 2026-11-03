package com.edde746.tortuga.trackers

import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ServerSession
import com.edde746.tortuga.sync.account.SyncApi
import com.edde746.tortuga.sync.api.ApiError
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.trackers.connections.TrackerAccessToken
import com.edde746.tortuga.trackers.connections.TrackerConnectRequest
import com.edde746.tortuga.trackers.connections.TrackerConnection
import com.edde746.tortuga.trackers.connections.TrackerConnectionList
import com.edde746.tortuga.trackers.connections.TrackerConnectionPaths
import com.edde746.tortuga.trackers.connections.TrackerConnectionsApi
import com.edde746.tortuga.trackers.connections.TrackerTokenRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.io.IOException
import kotlinx.serialization.KSerializer
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * One account's tracker connections on the account server ([TrackerConnectionPaths]), in memory over Ktor's
 * [MockEngine] on the test's scheduler, and the session its devices call it with ([ServerSession]). Fixtures that
 * share one play devices of the same account.
 *
 * It refreshes as the server does: when the stored token expires within the refresh window, or when a device sends
 * the stored token as rejected, one refresh at a time; a rejected token where tokens don't refresh (AniList) deletes
 * the connection. Tests steer it with [down], [signedIn], [refuseRefresh] and [refreshDelay].
 */
internal class FakeTrackerAccountServer(scope: TestScope, private val clock: Clock) : ServerSession {
    class Stored(val request: TrackerConnectRequest, val connectedAtMillis: Long) {
        var accessToken: String = request.accessToken
        var refreshToken: String? = request.refreshToken
        var expiresAt: Long? = null
    }

    val connections = LinkedHashMap<Pair<String, String>, Stored>()

    /** The account's connections counter: moves with every connection stored or deleted, as the listing reports it. */
    var version = 0L
        private set

    /** Every call: `list`, `connect <service>`, `token <service>` (`... rejected`), `delete <service>`. */
    val calls = mutableListOf<String>()

    /** Refreshes at the services, and the access tokens revoked at disconnect. */
    var refreshes = 0
    val revoked = mutableListOf<String>()

    /** Every request fails as if the network were down. */
    var down = false

    override var signedIn = true

    /** The services refuse refreshes: the connection is deleted and the token route answers 410. */
    var refuseRefresh = false

    /** How long a refresh at the service takes. */
    var refreshDelay: Duration = Duration.ZERO

    private val refreshing = Mutex()
    private var counter = 0
    private val json = SyncProtocol.json

    val api = SyncApi(
        HttpClient(
            MockEngine.create {
                dispatcher = StandardTestDispatcher(scope.testScheduler)
                addHandler { request -> handle(request) }
            },
        ),
    )

    override suspend fun <T> authorized(call: suspend (serverUrl: String, accessToken: String) -> T): T {
        if (!signedIn) throw AccountException(AccountError.NotSignedIn)
        return call(URL, "session-token")
    }

    /** Another device disconnected [service] (or the server dropped it). */
    fun drop(profileId: String, service: TrackerService) {
        connections.remove(profileId to service.id)
        version++
    }

    private fun nowSeconds() = clock.now().epochSeconds

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        if (down) throw IOException("Network is unreachable")
        val path = request.url.encodedPath
        return when {
            path == TrackerConnectionPaths.CONNECTIONS && request.method == HttpMethod.Get -> {
                calls += "list"
                val list = connections.values.sortedBy { it.connectedAtMillis }
                    .map { TrackerConnection(it.request.profileId, it.request.service, it.request.username, it.connectedAtMillis) }
                ok(TrackerConnectionList.serializer(), TrackerConnectionList(list, version))
            }
            path == TrackerConnectionPaths.CONNECTIONS && request.method == HttpMethod.Post -> connect(body(request, TrackerConnectRequest.serializer()))
            path == TrackerConnectionPaths.TOKEN -> token(body(request, TrackerTokenRequest.serializer()))
            path.startsWith(TrackerConnectionPaths.CONNECTIONS + "/") && request.method == HttpMethod.Delete -> {
                val (profileId, service) = path.removePrefix(TrackerConnectionPaths.CONNECTIONS + "/").split('/').map { it.decodeURLPart() }
                calls += "delete $service"
                connections.remove(profileId to service)?.let { revoked += it.accessToken }
                version++
                respond("", HttpStatusCode.NoContent)
            }
            else -> error(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        }
    }

    private fun MockRequestHandleScope.connect(request: TrackerConnectRequest): HttpResponseData {
        calls += "connect ${request.service}"
        if (TrackerService.fromId(request.service) == null || request.accessToken.isBlank()) {
            return error(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        }
        val key = request.profileId to request.service
        val connectedAt = maxOf(clock.now().toEpochMilliseconds(), (connections[key]?.connectedAtMillis ?: 0) + 1)
        connections[key] = Stored(request, connectedAt).apply { expiresAt = request.expiresInSeconds?.let { nowSeconds() + it } }
        version++
        return ok(TrackerConnection.serializer(), TrackerConnection(request.profileId, request.service, request.username, connectedAt))
    }

    private suspend fun MockRequestHandleScope.token(request: TrackerTokenRequest): HttpResponseData {
        calls += "token ${request.service}" + if (request.rejected != null) " rejected" else ""
        val key = request.profileId to request.service
        refreshing.withLock {
            val stored = connections[key]?.takeIf { it.connectedAtMillis == request.connectedAtMillis } ?: return gone()
            val due = stored.expiresAt?.let { nowSeconds() >= it - TrackerConnectionsApi.REFRESH_WINDOW_SECONDS } ?: false
            if (request.rejected == stored.accessToken || due) {
                if (request.service == TrackerService.ANILIST.id) {
                    connections.remove(key)
                    version++
                    return gone()
                }
                delay(refreshDelay)
                refreshes++
                if (refuseRefresh) {
                    connections.remove(key)
                    version++
                    return gone()
                }
                counter++
                stored.accessToken = "${request.service}-access-$counter"
                stored.refreshToken = "${request.service}-refresh-$counter"
                stored.expiresAt = nowSeconds() + REFRESHED_LIFETIME_SECONDS
            }
            return ok(TrackerAccessToken.serializer(), TrackerAccessToken(stored.accessToken, stored.expiresAt?.let { it - nowSeconds() }))
        }
    }

    private fun MockRequestHandleScope.gone() = error(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)

    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String) = respond(
        json.encodeToString(ApiError.serializer(), ApiError(code, code)),
        status,
        headersOf("Content-Type", "application/json"),
    )

    private fun <T> MockRequestHandleScope.ok(serializer: KSerializer<T>, value: T) =
        respond(json.encodeToString(serializer, value), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))

    private fun <T> body(request: HttpRequestData, serializer: KSerializer<T>): T =
        json.decodeFromString(serializer, (request.body as TextContent).text)

    companion object {
        const val URL = "https://account.test"

        /** How long a refreshed token lasts. */
        const val REFRESHED_LIFETIME_SECONDS = 7 * 24 * 3600L
    }
}
