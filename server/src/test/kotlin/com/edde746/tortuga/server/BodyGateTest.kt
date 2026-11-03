package com.edde746.tortuga.server

import com.edde746.tortuga.server.http.ByteBudget
import com.edde746.tortuga.server.http.installByteBudget
import com.edde746.tortuga.server.http.installErrorHandling
import com.edde746.tortuga.server.support.RawHttp
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/**
 * Request bodies over the engine the server runs and real TCP sockets (Ktor's test engine has no socket): a body must
 * arrive in time, a refused call's connection closes rather than read the rest of its body, and a client that stops
 * reading its answer is reset.
 */
class BodyGateTest {
    @Test
    fun aBodySlowerThanItsDeadlineIsCutOff() = gateTest { budget, port ->
        // 64 KiB at 64 KiB/s after 1 s of grace: in by 2 s. Sent in 0.8 s, it is read and answered.
        RawHttp(port).use { paced ->
            paced.post(BODY)
            repeat(4) {
                paced.send(BODY / 4)
                Thread.sleep(200)
            }
            val answer = assertNotNull(paced.answer())
            assertEquals(200, answer.status)
            assertEquals("$BODY", answer.body)
        }

        // 1 KiB, then nothing: the connection closes at the deadline, unanswered, and the call's bytes come back.
        RawHttp(port).use { slow ->
            slow.post(BODY)
            slow.send(1024)
            val closedAfter = assertNotNull(slow.closesWithin(10_000), "The slow body's connection is still open after 10 s")
            assertTrue(closedAfter in 1_500..6_000, "Closed after $closedAfter ms")
        }
        eventually("the budget empties") { budget.held == 0L }
    }

    @Test
    fun aRefusedCallsConnectionClosesWithoutReadingItsBody() = gateTest { budget, port ->
        // Room for a request and what came in with it, not for its call's body: the call is refused, answered 503, and
        // the connection closes instead of reading the 512 KiB.
        val other = budget.total - 100 * 1024
        assertTrue(budget.tryReserve(other))
        RawHttp(port).use { client ->
            client.post(512 * 1024)
            client.send(16 * 1024)
            client.assertBusy()
            assertNotNull(client.closesWithin(6_000), "The refused call's connection is still open")
        }
        eventually("the refused call's bytes come back") { budget.held == other }

        // A body longer than its path takes: 413, and the connection closes too.
        RawHttp(port).use { client ->
            client.post(LIMIT + 1)
            client.send(16 * 1024)
            val answer = assertNotNull(client.answer())
            assertEquals(413, answer.status)
            assertTrue(ApiErrorCode.PAYLOAD_TOO_LARGE in answer.body, answer.body)
            assertNotNull(client.closesWithin(6_000), "The 413's connection is still open")
        }

        // No room even for the request: answered 503 before the server takes it on, and closed.
        budget.release(other)
        val all = budget.total - 1024
        assertTrue(budget.tryReserve(all))
        RawHttp(port).use { client ->
            client.post(BODY)
            client.send(1024)
            client.assertBusy()
            assertNotNull(client.closesWithin(6_000), "The unadmitted request's connection is still open")
        }
        eventually("nothing more stays held") { budget.held == all }

        // With room again, a request on a new connection gets in.
        budget.release(all)
        RawHttp(port).use { client ->
            client.post(BODY)
            client.send(BODY)
            assertEquals(200, client.answer()?.status)
        }
    }

    @Test
    fun aClientThatStopsReadingItsAnswerIsReset() = gateTest { _, port ->
        // A small receive buffer, unread past the engine's write timeout (10 s): the answer stalls, and the server
        // resets the connection rather than closing it with the rest of the answer still queued for the client.
        Socket().use { socket ->
            socket.receiveBufferSize = 4 * 1024
            socket.connect(InetSocketAddress(LOOPBACK, port), 5_000)
            socket.getOutputStream().apply {
                write("GET /big HTTP/1.1\r\nHost: test\r\n\r\n".encodeToByteArray())
                flush()
            }
            Thread.sleep(12_000)
            socket.soTimeout = 5_000
            val input = socket.getInputStream()
            val buffer = ByteArray(64 * 1024)
            var read = 0L
            val reset = try {
                var n = input.read(buffer)
                while (n >= 0) {
                    read += n
                    n = input.read(buffer)
                }
                false
            } catch (_: SocketException) {
                true
            }
            assertTrue(reset, "The connection ended after $read bytes of the answer without a reset")
            assertTrue(read < BIG, "Read the whole answer ($read bytes)")
        }
    }

    private fun RawHttp.assertBusy() {
        val answer = assertNotNull(answer(), "No answer")
        assertEquals(503, answer.status)
        assertEquals("5", answer.headers["retry-after"])
        assertTrue(ApiErrorCode.SERVER_BUSY in answer.body, answer.body)
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Not within 5 s: $what")
            Thread.sleep(20)
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val TOTAL = 4L shl 20
        const val LIMIT = 1L shl 20
        const val BODY = 64 * 1024
        const val BIG = 4 shl 20

        /**
         * The server on Netty with a [TOTAL]-byte budget, bodies up to [LIMIT], and a body's deadline 1 s plus its
         * length at 64 KiB/s: `POST /body` reads its body and answers its length, `GET /big` answers [BIG] bytes.
         */
        fun gateTest(block: (ByteBudget, Int) -> Unit) {
            val budget = ByteBudget(TOTAL)
            val server = tortugaEmbeddedServer(port = 0, host = LOOPBACK, bytes = budget, bodyGrace = 1.seconds, bodyBytesPerSecond = 64 * 1024) {
                install(ContentNegotiation) { json(SyncProtocol.json) }
                installErrorHandling()
                installByteBudget(budget) { LIMIT }
                routing {
                    post("/body") { call.respondText("${call.receiveText().length}") }
                    get("/big") { call.respondBytes(ByteArray(BIG)) }
                }
            }.start(wait = false)
            try {
                block(budget, runBlocking { server.engine.resolvedConnectors().single().port })
            } finally {
                server.stop(0, 1_000)
            }
        }
    }
}
