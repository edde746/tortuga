package com.edde746.tortuga.server.support

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * A TCP relay to [host]:[port] on a loopback port of its own ([localPort]), which a test [cut]s: every relayed
 * connection drops and new ones are refused, as when the database's host goes away.
 */
class TcpRelay(private val host: String, private val port: Int) : AutoCloseable {
    private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    val localPort: Int = listener.localPort

    init {
        thread(isDaemon = true, name = "tcp-relay-$localPort") {
            while (true) {
                val client = try {
                    listener.accept()
                } catch (_: SocketException) {
                    break
                }
                val upstream = try {
                    Socket(host, port)
                } catch (_: IOException) {
                    // The database refused it: so does the relay.
                    client.close()
                    continue
                }
                sockets += client
                sockets += upstream
                pump(client, upstream)
                pump(upstream, client)
            }
        }
    }

    /** Drops every relayed connection and refuses new ones. */
    fun cut() {
        listener.close()
        sockets.forEach { it.close() }
    }

    override fun close() = cut()

    /** Copies [from] to [to] until either closes, then closes both: an IOException here is the connection's end. */
    private fun pump(from: Socket, to: Socket) = thread(isDaemon = true) {
        runCatching { from.getInputStream().transferTo(to.getOutputStream()) }
        from.close()
        to.close()
    }
}
