package com.edde746.tortuga.server

import com.edde746.tortuga.server.support.ServerTestContext
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.PushRequest
import com.edde746.tortuga.sync.api.PushResponse
import com.edde746.tortuga.sync.api.RecordWrite
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.trackers.connections.TrackerConnectRequest
import com.edde746.tortuga.trackers.connections.TrackerConnection
import com.edde746.tortuga.trackers.connections.TrackerConnectionPaths
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.contentnegotiation.exclude
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The account change notice stream ([SyncPaths.SYNC_EVENTS]): who hears a change, when a stream ends, its refusals.
 * Streams open on Netty over loopback ([onNetty]), sharing the test's services; the rest goes through the test engine.
 */
class SyncEventsTest {
    @Test
    fun aChangeReachesTheAccountsOtherStreamsButNotItsAuthors() = serverTest {
        val phone = signIn("alice", deviceName = "Phone").session.accessToken
        val tv = signIn("alice", deviceName = "TV").session.accessToken
        onNetty { netty ->
            netty.events(phone) { fromPhone ->
                assertEquals(ContentType.Text.EventStream, fromPhone.response.contentType()?.withoutParameters())
                assertEquals("no-store, no-transform", fromPhone.response.headers[HttpHeaders.CacheControl])
                assertEquals("no", fromPhone.response.headers["X-Accel-Buffering"])
                netty.events(tv) { fromTv ->
                    push(phone, "a", 0)
                    assertEquals(Event(SyncPaths.EVENT_CHANGED, "1"), fromTv.next())
                    // The phone's first notice is the TV's push: none came for its own.
                    push(tv, "b", 0)
                    assertEquals(Event(SyncPaths.EVENT_CHANGED, "2"), fromPhone.next())
                    // A tracker connection moves no record: the notice carries the version the account is at.
                    post(TrackerConnectionPaths.CONNECTIONS, aniListSignIn(), tv).ok<TrackerConnection>()
                    assertEquals(Event(SyncPaths.EVENT_CHANGED, "2"), fromPhone.next())
                    // The TV's next notice is the phone's push, not its own connection.
                    push(phone, "a", 1)
                    assertEquals(Event(SyncPaths.EVENT_CHANGED, "3"), fromTv.next())
                }
            }
        }
    }

    @Test
    fun aTrackerChangeOnAnAccountThatNeverPushedIsHeard() = serverTest {
        val phone = signIn("alice", deviceName = "Phone").session.accessToken
        val tv = signIn("alice", deviceName = "TV").session.accessToken
        onNetty { netty ->
            netty.events(phone) { fromPhone ->
                // No record yet: the account is at version 0, and its other devices still hear the change at once.
                post(TrackerConnectionPaths.CONNECTIONS, aniListSignIn(), tv).ok<TrackerConnection>()
                assertEquals(Event(SyncPaths.EVENT_CHANGED, "0"), withTimeout(5.seconds) { fromPhone.next() })
            }
        }
    }

    @Test
    fun aRevokedSessionsStreamEnds() = serverTest {
        val phone = signIn("alice", deviceName = "Phone").session
        val tv = signIn("alice", deviceName = "TV").session
        onNetty { netty ->
            netty.events(tv.accessToken) { fromTv ->
                assertEquals(HttpStatusCode.NoContent, delete("${SyncPaths.DEVICES}/${tv.deviceId}", phone.accessToken).status)
                assertNull(withTimeout(5.seconds) { fromTv.next() })
            }
            netty.get(SyncPaths.SYNC_EVENTS) { asStream(tv.accessToken) }
                .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)
        }
    }

    @Test
    fun aDeletedAccountsStreamsEnd() = serverTest {
        val phone = signIn("alice", deviceName = "Phone").session.accessToken
        val tv = signIn("alice", deviceName = "TV").session.accessToken
        onNetty { netty ->
            netty.events(tv) { fromTv ->
                assertEquals(HttpStatusCode.OK, delete(SyncPaths.ACCOUNT, phone).status)
                assertNull(withTimeout(5.seconds) { fromTv.next() })
            }
        }
    }

    @Test
    fun aStreamEndsOnceTheAccessTokenItOpenedWithExpires() = serverTest {
        val phone = signIn("alice", deviceName = "Phone").session.accessToken
        val tv = signIn("alice", deviceName = "TV").session.accessToken
        onNetty { netty ->
            netty.events(tv) { fromTv ->
                // Within the token's hour the janitor's round leaves it be.
                clock.advance(Duration.ofMinutes(59))
                services.janitor.sweep()
                push(phone, "a", 0)
                assertEquals(Event(SyncPaths.EVENT_CHANGED, "1"), withTimeout(5.seconds) { fromTv.next() })
                // Past it the next round ends the stream, though the session lives on: the app opens another.
                clock.advance(Duration.ofMinutes(1))
                services.janitor.sweep()
                assertNull(withTimeout(5.seconds) { fromTv.next() })
            }
        }
    }

    @Test
    fun aStreamPastTheCapIsRefusedWithRetryAfter() = serverTest(configure = { config, _ -> config.copy(maxEventStreams = 1) }) {
        val phone = signIn("alice", deviceName = "Phone").session.accessToken
        val tv = signIn("alice", deviceName = "TV").session.accessToken
        onNetty { netty ->
            netty.events(phone) {
                val refused = netty.get(SyncPaths.SYNC_EVENTS) { asStream(tv) }
                refused.assertError(HttpStatusCode.ServiceUnavailable, ApiErrorCode.SERVER_BUSY)
                assertEquals("60", refused.headers[HttpHeaders.RetryAfter])
            }
        }
    }

    @Test
    fun aStreamRequestWhileBusyGets503WithRetryAfterNot406() = serverTest {
        val token = signIn("alice").session.accessToken
        onNetty { netty ->
            val taken = CountDownLatch(TestPostgres.POOL_SIZE)
            val release = CountDownLatch(1)
            coroutineScope {
                // Every connection held by a transaction that doesn't end until released.
                val holders = List(TestPostgres.POOL_SIZE) {
                    async(Dispatchers.IO) {
                        TestPostgres.database.transaction {
                            taken.countDown()
                            release.await()
                        }
                    }
                }
                try {
                    withContext(Dispatchers.IO) { taken.await() }
                    val busy = netty.get(SyncPaths.SYNC_EVENTS) { asStream(token) }
                    busy.assertError(HttpStatusCode.ServiceUnavailable, ApiErrorCode.SERVER_BUSY)
                    assertEquals("5", busy.headers[HttpHeaders.RetryAfter])
                } finally {
                    release.countDown()
                }
                holders.awaitAll()
            }
        }
    }
}

/**
 * Runs [block] with a client of the test's services served on Netty on loopback: Ktor's test engine hands a response
 * over only once its body has ended, so a stream never would.
 */
private suspend fun ServerTestContext.onNetty(block: suspend (HttpClient) -> Unit) {
    val server = tortugaEmbeddedServer(port = 0, host = "127.0.0.1", bytes = services.bytes) { tortugaServer(services) }
        .start(wait = false)
    try {
        val port = server.engine.resolvedConnectors().single().port
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) { json(SyncProtocol.json) }
            defaultRequest { url("http://127.0.0.1:$port") }
        }.use { block(it) }
    } finally {
        server.stop(0, 1_000)
    }
}

/** One server-sent event: its `event` and `data` lines. */
private data class Event(val event: String, val data: String)

/** An open notice stream: its response, and its events as they come. */
private class Events(val response: HttpResponse, private val body: ByteReadChannel) {
    /** The next event, past keepalive comments; null once the server ended the stream. */
    suspend fun next(): Event? {
        var event: String? = null
        var data: String? = null
        while (true) {
            val line = body.readLine() ?: return null
            when {
                line.isEmpty() -> if (event != null && data != null) return Event(event, data)
                line.startsWith("event: ") -> event = line.removePrefix("event: ")
                line.startsWith("data: ") -> data = line.removePrefix("data: ")
            }
        }
    }
}

/** A request as the apps open a stream: [token], and `text/event-stream` as the only type it accepts. */
private fun HttpRequestBuilder.asStream(token: String) {
    bearerAuth(token)
    accept(ContentType.Text.EventStream)
    exclude(ContentType.Application.Json)
}

/** Opens [token]'s notice stream (200) and runs [block] while it is open. */
private suspend fun HttpClient.events(token: String, block: suspend (Events) -> Unit) =
    prepareGet(SyncPaths.SYNC_EVENTS) { asStream(token) }.execute { response ->
        assertEquals(HttpStatusCode.OK, response.status)
        block(Events(response, response.bodyAsChannel()))
    }

private suspend fun ServerTestContext.push(token: String, recordId: String, baseVersion: Long) =
    post(
        SyncPaths.SYNC_PUSH,
        PushRequest(listOf(RecordWrite(recordId, baseVersion, false, JsonObject(mapOf("v" to JsonPrimitive(recordId)))))),
        token,
    ).ok<PushResponse>()

/** As the apps sign in to AniList: no client id, no refresh token; the server stores it without asking the service. */
private fun aniListSignIn() = TrackerConnectRequest("profile-1", "anilist", "", "anilist-access-0", null, 31_536_000, "alice-anilist")
