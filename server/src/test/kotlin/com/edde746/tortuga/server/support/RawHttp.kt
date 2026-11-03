package com.edde746.tortuga.server.support

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * An HTTP/1.1 client on a plain socket to the server on loopback [port], which sends a body at the pace a test sets;
 * [sendBuffer] fixes its kernel send buffer (Linux's own grows to MiBs).
 */
class RawHttp(port: Int, sendBuffer: Int? = null) : Closeable {
    private val socket = Socket().apply {
        if (sendBuffer != null) sendBufferSize = sendBuffer
        connect(InetSocketAddress("127.0.0.1", port), 5_000)
        soTimeout = 10_000
    }
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()

    class Answer(val status: Int, val headers: Map<String, String>, val body: String)

    /** A `POST` of [length] bytes of text to [path], its body still to come. */
    fun post(length: Long, path: String = "/body") {
        output.write("POST $path HTTP/1.1\r\nHost: test\r\nContent-Type: text/plain\r\nContent-Length: $length\r\n\r\n".encodeToByteArray())
        output.flush()
    }

    fun post(length: Int, path: String = "/body") = post(length.toLong(), path)

    /** A `GET` of [path]. */
    fun get(path: String) {
        output.write("GET $path HTTP/1.1\r\nHost: test\r\n\r\n".encodeToByteArray())
        output.flush()
    }

    fun send(bytes: Int) {
        output.write(ByteArray(bytes) { 'x'.code.toByte() })
        output.flush()
    }

    /** The server's answer, or null if it closed the connection first. */
    fun answer(): Answer? {
        val statusLine = line() ?: return null
        val headers = generateSequence { line()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
        var read = 0
        while (read < body.size) read += input.read(body, read, body.size - read).also { if (it < 0) return null }
        return Answer(statusLine.split(' ')[1].toInt(), headers, body.decodeToString())
    }

    /** Milliseconds until the server closes the connection (an end or a reset), or null if it stays open for [millis]. */
    fun closesWithin(millis: Int): Long? {
        val started = System.nanoTime()
        socket.soTimeout = millis
        return try {
            while (input.read() >= 0) {}
            (System.nanoTime() - started) / 1_000_000
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            (System.nanoTime() - started) / 1_000_000
        }
    }

    private fun line(): String? {
        val bytes = StringBuilder()
        while (true) {
            val b = try {
                input.read()
            } catch (_: IOException) {
                return null
            }
            if (b < 0) return null
            if (b == '\n'.code) return bytes.toString().removeSuffix("\r")
            bytes.append(b.toChar())
        }
    }

    override fun close() = socket.close()
}
