package com.edde746.tortuga.server

import com.edde746.tortuga.server.http.ByteBudget
import com.edde746.tortuga.server.http.Connections
import com.edde746.tortuga.server.http.installByteBudget
import com.edde746.tortuga.server.support.RawHttp
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/**
 * The cap on the connections the server holds and their kernel buffers, over the engine the server runs and real TCP
 * sockets: a connection past it is reset as soon as it is accepted, unanswered, while the ones the server holds are
 * answered as before, and a connection that closes makes room for the next. While the server reads nothing, a client
 * gets only a small buffer's worth ahead of it, more once its body's call is admitted.
 */
class ConnectionsTest {
    @Test
    fun aConnectionPastTheCapIsResetWhileTheHeldOnesAreAnswered() {
        val server = tortugaEmbeddedServer(port = 0, host = "127.0.0.1", bytes = ByteBudget(4L shl 20), connections = Connections(max = 2, backlog = 8, raisable = 0)) {
            routing { get("/ping") { call.respondText("pong") } }
        }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            RawHttp(port).use { first ->
                RawHttp(port).use { second ->
                    // Answered: the server holds both.
                    assertEquals(200, first.ping())
                    assertEquals(200, second.ping())

                    // Reset at once, before the connect returned, or soon after it.
                    connectOrReset(port)?.use { third ->
                        val closedAfter = assertNotNull(third.closesWithin(5_000), "A connection past the cap stayed open")
                        assertTrue(closedAfter < 2_000, "The connection past the cap closed after $closedAfter ms")
                    }
                    assertEquals(200, first.ping())
                    assertEquals(200, second.ping())

                    // One closes: a new connection gets in once the server has seen it go.
                    first.close()
                    val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
                    while (connectOrReset(port)?.use { it.ping() } != 200) {
                        if (System.nanoTime() > deadline) fail("No connection let in within 5 s of one closing")
                        Thread.sleep(20)
                    }
                    assertEquals(200, second.ping())
                }
            }
        } finally {
            server.stop(0, 1_000)
        }
    }

    @Test
    fun anAdmittedBodyGetsFurtherAheadOfTheServersReadsThanARequestNotYetRead() {
        val budget = ByteBudget(16L shl 20)
        val server = tortugaEmbeddedServer(port = 0, host = "127.0.0.1", bytes = budget) {
            installByteBudget(budget) { BODY.toLong() }
            routing {
                // Answered after the connection's event loop has slept: it reads nothing meanwhile.
                get("/stall") {
                    call.eventLoop().execute { Thread.sleep(STALL_MILLIS) }
                    call.respondText("stalled")
                }
                post("/body") { call.respondText("${call.receiveText().length}") }
                // Admitted by now: the connection reads none of the body until its event loop wakes.
                post("/stalled") {
                    call.eventLoop().execute { Thread.sleep(STALL_MILLIS) }
                    call.respondText("${call.receiveText().length}")
                }
            }
        }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            val notRead = RawHttp(port, CLIENT_SEND_BUFFER).use { client ->
                client.get("/stall")
                Thread.sleep(START_MILLIS)
                client.post(BODY)
                val ahead = client.sendAhead()
                assertEquals("stalled", client.answer()?.body)
                assertEquals("$BODY", client.answer()?.body)
                ahead
            }
            val admitted = RawHttp(port, CLIENT_SEND_BUFFER).use { client ->
                client.post(BODY, "/stalled")
                Thread.sleep(START_MILLIS)
                val ahead = client.sendAhead()
                assertEquals("$BODY", client.answer()?.body)
                ahead
            }
            println("Sent ahead of the server's reads: $notRead bytes of a request not yet read, $admitted of an admitted body")
            assertTrue(notRead <= SMALL_AHEAD, "A request not yet read got $notRead bytes ahead of the server's reads")
            assertTrue(admitted > SMALL_AHEAD, "An admitted body got only $admitted bytes ahead of the server's reads")
        } finally {
            server.stop(0, 1_000)
        }
    }

    /**
     * A connection to [port], or null when the server reset it before `connect` returned: the server resets a connection
     * past the cap as soon as it accepts it, which on loopback can reach the client before its connect finishes. A
     * server that isn't listening still fails, with [ConnectException].
     */
    private fun connectOrReset(port: Int): RawHttp? = try {
        RawHttp(port)
    } catch (e: ConnectException) {
        throw e
    } catch (_: SocketException) {
        null
    }

    /** `GET /ping`'s status, or null when the server closed or reset the connection first. */
    private fun RawHttp.ping(): Int? = try {
        get("/ping")
        answer()?.status
    } catch (_: IOException) {
        null
    }

    /**
     * Sends a [BODY]-byte body a KiB at a time: what of it was sent after [MEASURE_MILLIS], while the server reads none,
     * which the kernel holds in this client's send buffer and the server's receive buffer.
     */
    private fun RawHttp.sendAhead(): Long {
        val sent = AtomicLong()
        val writer = thread {
            repeat(BODY / 1024) {
                send(1024)
                sent.addAndGet(1024)
            }
        }
        Thread.sleep(MEASURE_MILLIS)
        val ahead = sent.get()
        writer.join(10_000)
        return ahead
    }

    private fun RoutingCall.eventLoop() = (pipelineCall.engineCall as NettyApplicationCall).context.executor()

    private companion object {
        const val BODY = 256 * 1024
        const val CLIENT_SEND_BUFFER = 4 * 1024

        /** The server's event loop sleeps this long; the client starts sending after the first, measures after both. */
        const val STALL_MILLIS = 2_000L
        const val START_MILLIS = 300L
        const val MEASURE_MILLIS = 1_000L

        /**
         * The most a client gets ahead while the server's reads wait: the server's small receive buffer and the client's
         * send buffer, each doubled by Linux, and a KiB of slack.
         */
        const val SMALL_AHEAD = 2L * (Connections.RECEIVE_BUFFER_BYTES + CLIENT_SEND_BUFFER) + 1024
    }
}
