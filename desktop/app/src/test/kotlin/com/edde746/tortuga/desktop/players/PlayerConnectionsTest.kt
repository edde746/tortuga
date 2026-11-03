package com.edde746.tortuga.desktop.players

import com.edde746.tortuga.playback.players.MpvIpcSession
import com.edde746.tortuga.playback.players.PlayerAppEvent
import com.edde746.tortuga.playback.players.VlcHttpSession
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
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
}
