package com.edde746.tortuga.desktop.players

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.playback.players.CustomPlayer
import com.edde746.tortuga.playback.players.CustomPlayerKind
import com.edde746.tortuga.playback.players.MpvIpcSession
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.players.PlayerAppCommands
import com.edde746.tortuga.playback.players.PlayerAppEvent
import com.edde746.tortuga.playback.players.PlayerAppEvents
import com.edde746.tortuga.playback.players.PlayerAppFailure
import com.edde746.tortuga.playback.players.PlayerAppKind
import com.edde746.tortuga.playback.players.PlayerAppLaunch
import com.edde746.tortuga.playback.players.PlayerAppLauncher
import com.edde746.tortuga.playback.players.PlayerAppMedia
import com.edde746.tortuga.playback.players.PlayerAppProgress
import com.edde746.tortuga.playback.players.PlayerAppTemplates
import com.edde746.tortuga.playback.players.PlayerAppsFound
import com.edde746.tortuga.playback.players.VlcHttpSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.TimeUnit

/**
 * Player apps on Windows and Linux ([PlayerAppLauncher]): the players [PlayerDetection] finds, and programs as custom
 * players ([CustomPlayerKind.COMMAND]). Every launch starts a process of its own.
 *
 * mpv and VLC report live: mpv through its JSON IPC (a named pipe on Windows, a Unix socket in a private folder on
 * Linux; [MpvIpcSession]), VLC through its HTTP interface on 127.0.0.1 with a free port and a random password per launch,
 * polled once a second ([VlcHttpSession]). Each reports [PlayerAppEvent.Ended] once, when its process exits, after the
 * last of what it said was read (VLC also when its interface never answered). The others, and custom players, report
 * nothing.
 *
 * On Windows a program gets its arguments as one command line, which it splits itself: each argument goes in as
 * [windowsArgument] writes it, so a title or header an addon wrote stays one argument, and a batch file
 * ([PlayerDetection.isBatchFile]) never starts, since `cmd.exe` would run it with a title's `&` or `|` as its own.
 *
 * Nothing here logs a URL, a header or a command line: the stream's URL and headers carry API keys.
 *
 * @param observer the test report (the harness launcher's `--report`; null otherwise): hears each launch and what each
 *   player reports.
 */
internal class DesktopPlayerApps(
    private val host: PlayerHost = PlayerHost.SYSTEM,
    private val runtime: PlayerRuntime = PlayerRuntime.system(host),
    private val observer: PlayerAppsObserver? = null,
) : PlayerAppLauncher {
    override val customPlayers: CustomPlayerKind = CustomPlayerKind.COMMAND
    override val handsOffFiles: Boolean = true

    private val detection = PlayerDetection(host)

    /** What the last [detect] found, so a launch needn't look again. */
    @Volatile private var found: Map<KnownPlayer, FoundPlayer> = emptyMap()

    override fun detect(done: PlayerAppsFound) {
        Thread({
            val players = try {
                detection.findAll()
            } catch (e: Exception) {
                Log.w(TAG) { "detecting players failed: ${e.javaClass.simpleName}" }
                emptyList()
            }
            found = players.associateBy { it.player }
            done.onFound(players.map { it.player.app() })
        }, "player-apps-detect").apply { isDaemon = true }.start()
    }

    override suspend fun launch(app: PlayerApp, media: PlayerAppMedia, events: PlayerAppEvents): PlayerAppLaunch {
        val reported = observer?.let { o -> PlayerAppEvents { event -> o.playerAppEvent(app.id, event); events.onEvent(event) } } ?: events
        val launch = withContext(Dispatchers.IO) {
            when (app.kind) {
                PlayerAppKind.CUSTOM -> app.custom?.let { launchCustom(it, media) }
                PlayerAppKind.DETECTED -> KnownPlayer.of(app.id)?.let { launchKnown(it, media, reported) }
                PlayerAppKind.CHOOSER -> null
            } ?: PlayerAppLaunch.Failed(PlayerAppFailure.NOT_INSTALLED)
        }
        observer?.playerAppLaunched(app.id, launch)
        return launch
    }

    private fun launchCustom(custom: CustomPlayer, media: PlayerAppMedia): PlayerAppLaunch {
        val program = detection.program(custom.command) ?: return PlayerAppLaunch.Failed(PlayerAppFailure.NOT_INSTALLED)
        val process = start(custom.name, listOf(program) + PlayerAppTemplates.arguments(custom.arguments, media)) ?: return failedToStart
        process.outputStream.close()
        return PlayerAppLaunch.Launched(PlayerAppProgress.NONE)
    }

    private fun launchKnown(player: KnownPlayer, media: PlayerAppMedia, events: PlayerAppEvents): PlayerAppLaunch {
        val where = found[player]?.takeIf(detection::stillThere)
            ?: detection.find(player)?.also { found = found + (player to it) }
            ?: return PlayerAppLaunch.Failed(PlayerAppFailure.NOT_INSTALLED)
        return when (player) {
            KnownPlayer.MPV -> launchMpv(where, media, events)
            KnownPlayer.VLC -> launchVlc(where, media, events)
            KnownPlayer.MPC_HC, KnownPlayer.MPC_BE -> launchSilent(where, PlayerAppCommands.mpc(media), media)
            KnownPlayer.POTPLAYER -> launchSilent(where, PlayerAppCommands.potPlayer(media), media)
            KnownPlayer.CELLULOID -> launchSilent(where, PlayerAppCommands.celluloid(media), media)
        }
    }

    /** A player that reports nothing: started, and left to itself. */
    private fun launchSilent(where: FoundPlayer, arguments: List<String>, media: PlayerAppMedia): PlayerAppLaunch {
        val process = start(where.player.id, where.command(arguments, media)) ?: return failedToStart
        process.outputStream.close()
        return PlayerAppLaunch.Launched(PlayerAppProgress.NONE)
    }

    private fun launchMpv(where: FoundPlayer, media: PlayerAppMedia, events: PlayerAppEvents): PlayerAppLaunch {
        val channel = runtime.mpvChannel(where.flatpak)
        val process = start(where.player.id, where.command(PlayerAppCommands.mpv(media, channel?.path), media))
        if (process == null) {
            channel?.close()
            return failedToStart
        }
        process.outputStream.close()
        if (channel == null) return PlayerAppLaunch.Launched(PlayerAppProgress.NONE)
        val session = MpvIpcSession()
        watch("mpv", process, events, session::ended) {
            channel.use { MpvIpc.connect(it, process)?.use { connection -> MpvIpc.read(connection, session, events::onEvent) } }
        }
        return PlayerAppLaunch.Launched(PlayerAppProgress.LIVE)
    }

    private fun launchVlc(where: FoundPlayer, media: PlayerAppMedia, events: PlayerAppEvents): PlayerAppLaunch {
        val port = runtime.freePort()
        val password = randomHex(16)
        val process = start(where.player.id, where.command(PlayerAppCommands.vlc(media, port, password), media)) ?: return failedToStart
        process.outputStream.close()
        val session = VlcHttpSession()
        val poller = VlcStatusPoller(port, password)
        watch("vlc", process, events, session::ended) {
            do {
                poller.status()?.let { status -> session.onStatus(status).forEach(events::onEvent) }
            } while (!process.waitFor(VLC_POLL_MILLIS, TimeUnit.MILLISECONDS))
        }
        return PlayerAppLaunch.Launched(PlayerAppProgress.LIVE)
    }

    /**
     * Follows a live player on a thread of its own: [read] passes what it hears on until the player quit (or fails,
     * logged by [name] only); then, once [process] exited, [ended] goes out, once and last, failed when the process's
     * exit status is a failure (a crash's is too: its exception code on Windows, 128 + the signal on Linux).
     */
    private fun watch(name: String, process: Process, events: PlayerAppEvents, ended: (failed: Boolean) -> PlayerAppEvent.Ended, read: () -> Unit) {
        Thread({
            try {
                read()
            } catch (e: Exception) {
                Log.w(TAG) { "lost $name's progress: ${e.javaClass.simpleName}" }
            }
            process.onExit().join()
            events.onEvent(ended(process.exitValue() != 0))
        }, "player-app-$name").apply { isDaemon = true }.start()
    }

    /**
     * [command] started, or null (logged by [name] only) when it couldn't be, or when, on Windows, its program is a batch
     * file: no quoting keeps `cmd.exe`'s parser, which runs a batch file, from reading a title's `&`, `|` or `%`.
     */
    private fun start(name: String, command: List<String>): Process? {
        if (host.windows && PlayerDetection.isBatchFile(command.first())) {
            Log.w(TAG) { "$name is a batch file, which cmd.exe would run with the title and headers as commands: not started" }
            return null
        }
        return try {
            runtime.start(if (host.windows) listOf(command.first()) + command.drop(1).map(::windowsArgument) else command)
        } catch (e: IOException) {
            Log.w(TAG) { "$name didn't start: ${e.javaClass.simpleName}" }
            null
        }
    }

    private companion object {
        const val TAG = "PlayerApps"

        /** How often VLC's status is asked for. */
        const val VLC_POLL_MILLIS = 1000L

        val failedToStart = PlayerAppLaunch.Failed(PlayerAppFailure.OTHER)
        val random = SecureRandom()

        fun randomHex(bytes: Int): String = HexFormat.of().formatHex(ByteArray(bytes).also(random::nextBytes))
    }
}

/** How players start and where Tortuga reaches the live ones; this computer's in the app ([system]), fakes in tests. */
internal interface PlayerRuntime {
    /** Starts [command], the program then its arguments (on Windows each as [windowsArgument] wrote it), output discarded. */
    @Throws(IOException::class)
    fun start(command: List<String>): Process

    /** A new place for one mpv's IPC, mpv's own or in a Flatpak app's sandbox ([flatpak]); null where there's none. */
    fun mpvChannel(flatpak: String?): MpvIpcChannel?

    /** A TCP port on 127.0.0.1 no one listens on. */
    fun freePort(): Int

    companion object {
        fun system(host: PlayerHost): PlayerRuntime = object : PlayerRuntime {
            override fun start(command: List<String>): Process =
                ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()

            override fun mpvChannel(flatpak: String?): MpvIpcChannel? = try {
                val name = "tortuga-mpv-${HexFormat.of().formatHex(ByteArray(8).also(SecureRandom()::nextBytes))}"
                when {
                    host.windows -> WindowsPipeChannel("\\\\.\\pipe\\$name")
                    // A Flatpak app's $XDG_RUNTIME_DIR/app/<id> is the one folder its sandbox shares with the host.
                    flatpak != null -> host.env("XDG_RUNTIME_DIR")?.let { runtime ->
                        UnixSocketChannel(Files.createDirectories(Path.of(runtime, "app", flatpak)).resolve("$name.sock"), folder = null)
                    }
                    // Only this user can open a temporary folder of its own.
                    else -> Files.createTempDirectory("tortuga-mpv-").let { UnixSocketChannel(it.resolve("mpv.sock"), it) }
                }
            } catch (_: IOException) {
                null
            }

            override fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        }
    }
}

/**
 * [argument] as a Windows program's command line holds it, for the program to split back as it was (by the rules of
 * `CommandLineToArgvW`, which mpv and VLC call, and of the C runtime's start-up code, which agree on all of this): as it
 * is without whitespace or `"`, else in quotes, with each `"` escaped by a backslash and the backslashes before a `"` or
 * the closing quote doubled (`""` when empty). The JDK passes an argument already in quotes on as it is, and one without
 * whitespace too; its own quoting (the default, legacy mode) puts quotes around an argument with whitespace and escapes
 * no `"` in it, so a title holding one would come apart into more arguments, options mpv applies.
 */
internal fun windowsArgument(argument: String): String {
    if (argument.isNotEmpty() && argument.none { it == '"' || it.isWhitespace() }) return argument
    return buildString {
        append('"')
        var backslashes = 0
        for (c in argument) {
            if (c == '\\') {
                backslashes++
                continue
            }
            repeat(if (c == '"') 2 * backslashes + 1 else backslashes) { append('\\') }
            append(c)
            backslashes = 0
        }
        repeat(2 * backslashes) { append('\\') }
        append('"')
    }
}

/** The test report (the harness launcher's `--report`): hears what [DesktopPlayerApps] does, by player id. */
internal interface PlayerAppsObserver {
    fun playerAppLaunched(playerId: String, launch: PlayerAppLaunch)

    fun playerAppEvent(playerId: String, reported: PlayerAppEvent)
}
