package com.edde746.tortuga.desktop.players

import com.edde746.tortuga.playback.players.MpvIpcSession
import com.edde746.tortuga.playback.players.PlayerAppEvent
import com.edde746.tortuga.playback.players.VlcHttpSession
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlayerConnectionsTest {
    @Test
    fun mpvIpcWritesTheCommandsThenReadsUntilMpvHangsUp() {
        val channel = FakeMpvChannel("\\\\.\\pipe\\test", busyTries = 0)
        val session = MpvIpcSession()
        val events = mutableListOf<PlayerAppEvent>()
        val reader = thread { MpvIpc.read(assertNotNull(channel.connect()), session, events::add) }
        channel.say(
            """{"request_id":0,"error":"success"}""",
            """{"event":"file-loaded"}""",
            """{"event":"property-change","id":2,"name":"duration","data":30.0}""",
            """{"event":"property-change","id":1,"name":"time-pos","data":4.5}""",
        )
        channel.hangUp()
        reader.join(5000)
        assertFalse(reader.isAlive, "read returns once mpv closes the connection")
        assertEquals(session.commands.joinToString("") { "$it\n" }, channel.written.toString(Charsets.UTF_8))
        assertEquals(listOf(PlayerAppEvent.Started, PlayerAppEvent.Position(4_500, 30_000, paused = false)), events)
    }

    @Test
    fun mpvIpcStopsFollowingAtALineTooLongToHold() {
        val stream = "x".repeat(5 * 1024 * 1024) + "\n" + """{"event":"file-loaded"}""" + "\n"
        val connection = MpvIpcConnection(stream.byteInputStream(), java.io.ByteArrayOutputStream()) {}
        val events = mutableListOf<PlayerAppEvent>()
        MpvIpc.read(connection, MpvIpcSession(), events::add)
        assertEquals(emptyList(), events, "nothing after the overlong line is read")
    }

    @Test
    fun mpvIpcConnectsOnceMpvListensAndGivesUpWhenItQuit() {
        val process = FakeProcess()
        val channel = FakeMpvChannel("p", busyTries = 3)
        assertNotNull(MpvIpc.connect(channel, process))
        process.quit()
        assertNull(MpvIpc.connect(FakeMpvChannel("p", busyTries = Int.MAX_VALUE), process))
    }

    @Test
    fun aUnixSocketChannelConnectsOnceListenedOnAndCleansUp() {
        val folder = Files.createTempDirectory("tortuga-mpv-test")
        val socket = folder.resolve("mpv.sock")
        val channel = UnixSocketChannel(socket, folder)
        assertEquals(socket.toString(), channel.path)
        assertNull(channel.connect(), "nothing listens yet")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))
            val accepted = thread {
                server.accept().use { mpv ->
                    val buffer = ByteBuffer.allocate(64)
                    mpv.read(buffer)
                    mpv.write(ByteBuffer.wrap(String(buffer.array(), 0, buffer.position()).uppercase().encodeToByteArray()))
                }
            }
            assertNotNull(channel.connect()).use { connection ->
                connection.output.write("ping\n".encodeToByteArray())
                connection.output.flush()
                assertEquals("PING", connection.input.bufferedReader().readLine())
            }
            accepted.join(5000)
        }
        channel.close()
        assertFalse(Files.exists(socket))
        assertFalse(Files.exists(folder))
    }

    @Test
    fun aWindowsPipeNoOneServesIsNotThereYet() {
        // Elsewhere the name is an ordinary file name: it would be created.
        if (!System.getProperty("os.name").startsWith("Windows")) return
        assertNull(WindowsPipeChannel("\\\\.\\pipe\\tortuga-test-${System.nanoTime()}").connect())
    }

    @Test
    fun theVlcPollerAsksWithThePasswordAndReadsTheStatus() {
        val status = """{"state":"playing","length":30,"time":5,"position":0.17}"""
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext(VlcHttpSession.STATUS_PATH) { exchange ->
            val allowed = exchange.requestHeaders.getFirst("Authorization") == VlcHttpSession.authorization("right")
            val body = (if (allowed) status else "<h1>401</h1>").encodeToByteArray()
            exchange.sendResponseHeaders(if (allowed) 200 else 401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            assertEquals(status, VlcStatusPoller(port, "right").status())
            assertNull(VlcStatusPoller(port, "wrong").status())
        } finally {
            server.stop(0)
        }
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertNull(VlcStatusPoller(closed, "right").status(), "nothing listens: VLC starting or gone")
    }

    /**
     * The port was free when VLC got it, so another program may answer there: a body far bigger than a status, or one
     * that comes a byte at a time and never ends, is no status, within the poller's time, and the watch goes on.
     */
    @Test
    fun theVlcPollerGivesUpOnAStatusTooBigOrTooSlow() {
        val big = answering { body -> body.write(ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() }) }
        val dripping = answering { body ->
            while (true) {
                body.write('x'.code)
                body.flush()
                Thread.sleep(100)
            }
        }
        try {
            for ((what, server) in listOf("a big" to big, "a dripping" to dripping)) {
                val asked = CompletableFuture.supplyAsync { VlcStatusPoller(server.address.port, "right").status() }
                assertNull(asked.get(10, TimeUnit.SECONDS), "$what body")
            }
        } finally {
            big.stop(0)
            dripping.stop(0)
        }
    }

    /** A server on 127.0.0.1 that answers the status with 200 and what [write] writes, until the poller hangs up. */
    private fun answering(write: (OutputStream) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        server.createContext(VlcHttpSession.STATUS_PATH) { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.use(write)
            } catch (_: IOException) {
                // The poller hung up.
            }
        }
        server.start()
        return server
    }
}
