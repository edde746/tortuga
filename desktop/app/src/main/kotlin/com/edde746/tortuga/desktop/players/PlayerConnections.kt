package com.edde746.tortuga.desktop.players

import com.edde746.tortuga.playback.players.MpvIpcSession
import com.edde746.tortuga.playback.players.PlayerAppEvent
import com.edde746.tortuga.playback.players.VlcHttpSession
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.StandardProtocolFamily
import java.net.URI
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/** Where one mpv's JSON IPC listens (`--input-ipc-server`): [path] for mpv, [connect] for Tortuga. Closing cleans up. */
internal interface MpvIpcChannel : Closeable {
    /** What mpv's `--input-ipc-server` gets. */
    val path: String

    /** A connection once mpv listens; null while it doesn't yet. */
    fun connect(): MpvIpcConnection?
}

/** A connection to mpv's IPC: what it writes ([input]) and where commands go ([output]). */
internal class MpvIpcConnection(val input: InputStream, val output: OutputStream, private val onClose: () -> Unit) : Closeable {
    override fun close() = onClose()
}

/**
 * mpv's IPC on Windows: the named pipe [path] (`\\.\pipe\…`), opened as a file. A pipe handle opened that way reads and
 * writes one at a time, which is how [MpvIpc.read] uses it: the commands first, then only reads.
 */
internal class WindowsPipeChannel(override val path: String) : MpvIpcChannel {
    override fun connect(): MpvIpcConnection? {
        val pipe = try {
            RandomAccessFile(path, "rw")
        } catch (_: FileNotFoundException) {
            // Not created yet, or its one instance is taken until mpv makes the next.
            return null
        }
        val input = object : InputStream() {
            override fun read(): Int = pipe.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int = pipe.read(b, off, len)
        }
        val output = object : OutputStream() {
            override fun write(b: Int) = pipe.write(b)

            override fun write(b: ByteArray, off: Int, len: Int) = pipe.write(b, off, len)
        }
        return MpvIpcConnection(input, output, pipe::close)
    }

    override fun close() {}
}

/**
 * mpv's IPC on Linux: the Unix socket [socket], in [folder] when it is a folder of its own (deleted with it), else in a
 * Flatpak app's shared runtime folder.
 */
internal class UnixSocketChannel(private val socket: Path, private val folder: Path?) : MpvIpcChannel {
    override val path: String = socket.toString()

    override fun connect(): MpvIpcConnection? {
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.connect(UnixDomainSocketAddress.of(socket))
        } catch (_: IOException) {
            channel.close()
            return null
        }
        return MpvIpcConnection(Channels.newInputStream(channel), Channels.newOutputStream(channel), channel::close)
    }

    override fun close() {
        runCatching { Files.deleteIfExists(socket) }
        folder?.let { runCatching { Files.deleteIfExists(it) } }
    }
}

/** Talks to one mpv over its IPC for an [MpvIpcSession]. */
internal object MpvIpc {
    /**
     * A connection to [channel] once the mpv of [process] listens, polling while it runs; null when it quit before
     * listening.
     */
    fun connect(channel: MpvIpcChannel, process: Process): MpvIpcConnection? {
        while (true) {
            channel.connect()?.let { return it }
            if (!process.isAlive) return null
            Thread.sleep(CONNECT_RETRY_MILLIS)
        }
    }

    /**
     * Writes [session]'s commands, then hands every line mpv writes to it, and its events to [emit], until mpv closes
     * the connection (it quit).
     */
    fun read(connection: MpvIpcConnection, session: MpvIpcSession, emit: (PlayerAppEvent) -> Unit) {
        connection.output.write(session.commands.joinToString("") { "$it\n" }.encodeToByteArray())
        connection.output.flush()
        connection.input.bufferedReader(Charsets.UTF_8).lineSequence().forEach { line -> session.onLine(line).forEach(emit) }
    }

    private const val CONNECT_RETRY_MILLIS = 100L
}

/** Asks one VLC's HTTP interface on 127.0.0.1:[port] for its status ([VlcHttpSession]). */
internal class VlcStatusPoller(private val port: Int, private val password: String) {
    /** The status' JSON; null while the interface doesn't answer (VLC starting or quitting) or refuses. */
    fun status(): String? = try {
        val connection = URI("http://127.0.0.1:$port${VlcHttpSession.STATUS_PATH}").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("Authorization", VlcHttpSession.authorization(password))
            if (connection.responseCode == HttpURLConnection.HTTP_OK) connection.inputStream.use { it.readBytes().decodeToString() } else null
        } finally {
            connection.disconnect()
        }
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val TIMEOUT_MILLIS = 2000
    }
}
