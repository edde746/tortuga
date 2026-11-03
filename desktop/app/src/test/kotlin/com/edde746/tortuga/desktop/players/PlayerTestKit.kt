package com.edde746.tortuga.desktop.players

import com.edde746.tortuga.desktop.windows.WindowsRegistry
import com.edde746.tortuga.playback.players.PlayerAppMedia
import com.edde746.tortuga.playback.players.PlayerAppSubtitle
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A computer for [PlayerDetection]: its environment, the programs and folders it has, its registry's string values. */
internal class FakeHost(
    override val windows: Boolean,
    private val environment: Map<String, String> = emptyMap(),
    private val programs: Set<String> = emptySet(),
    private val directories: Set<String> = emptySet(),
    private val values: Map<Triple<WindowsRegistry.Root, String, String>, String> = emptyMap(),
) : PlayerHost {
    override fun env(name: String): String? = environment[name]

    override fun isProgram(path: String): Boolean = path in programs

    override fun isDirectory(path: String): Boolean = path in directories

    override fun registry(root: WindowsRegistry.Root, key: String, value: String): String? = values[Triple(root, key, value)]
}

/** A player process that runs until [quit], which ends it with an exit status. */
internal class FakeProcess : Process() {
    private val exit = CompletableFuture<Process>()
    private var status = 0
    val stdin = ByteArrayOutputStream()

    fun quit(status: Int = 0) {
        this.status = status
        exit.complete(this)
    }

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int {
        exit.join()
        return status
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = try {
        exit.get(timeout, unit)
        true
    } catch (_: TimeoutException) {
        false
    }

    override fun exitValue(): Int = if (exit.isDone) status else throw IllegalThreadStateException("running")

    override fun destroy() = quit()

    override fun isAlive(): Boolean = !exit.isDone

    override fun onExit(): CompletableFuture<Process> = exit
}

/**
 * mpv's end of an IPC connection: [connect] answers null [busyTries] times (mpv not listening yet), then connects;
 * what Tortuga writes lands in [written], and [say] writes mpv's lines.
 */
internal class FakeMpvChannel(override val path: String, private var busyTries: Int = 2) : MpvIpcChannel {
    val written = ByteArrayOutputStream()
    private val toTortuga = PipedOutputStream()
    private val fromMpv = PipedInputStream(toTortuga, 64 * 1024)

    @Volatile var connected = false
    @Volatile var closed = false

    fun say(vararg lines: String) {
        toTortuga.write(lines.joinToString("") { "$it\n" }.encodeToByteArray())
        toTortuga.flush()
    }

    /** mpv quit: the connection closes. */
    fun hangUp() = toTortuga.close()

    override fun connect(): MpvIpcConnection? {
        if (busyTries-- > 0) return null
        connected = true
        return MpvIpcConnection(fromMpv, written) {}
    }

    override fun close() {
        closed = true
    }
}

/** How [DesktopPlayerApps] starts players, recorded; [failing] makes every start fail. */
internal class FakeRuntime(var channel: MpvIpcChannel? = null, var port: Int = 0, var failing: Boolean = false) : PlayerRuntime {
    val commands = mutableListOf<List<String>>()
    val processes = mutableListOf<FakeProcess>()

    override fun start(command: List<String>): Process {
        if (failing) throw IOException("CreateProcess error=193")
        commands += command
        return FakeProcess().also { processes += it }
    }

    override fun mpvChannel(flatpak: String?): MpvIpcChannel? = channel

    override fun freePort(): Int = port
}

internal const val STREAM_URL = "https://cdn.example/v/movie.mkv?token=k"

internal fun playerMedia(
    url: String = STREAM_URL,
    local: Boolean = false,
    subtitles: List<PlayerAppSubtitle> = emptyList(),
    startSeconds: Double = 0.0,
) = PlayerAppMedia(url, local, headers = mapOf("User-Agent" to "Tortuga/1.0"), title = "Movie", startSeconds = startSeconds, subtitles = subtitles, filename = null)
