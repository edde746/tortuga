package com.edde746.tortuga.desktop.players

import com.edde746.tortuga.desktop.windows.WindowsRegistry.Root
import com.edde746.tortuga.playback.players.CustomPlayer
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.players.PlayerApp.Companion.CUSTOM_PREFIX
import com.edde746.tortuga.playback.players.PlayerAppCapabilities
import com.edde746.tortuga.playback.players.PlayerAppCommands
import com.edde746.tortuga.playback.players.PlayerAppFailure
import com.edde746.tortuga.playback.players.PlayerAppKind
import com.edde746.tortuga.playback.players.PlayerAppLaunch
import com.edde746.tortuga.playback.players.PlayerAppMedia
import com.edde746.tortuga.playback.players.PlayerAppProgress
import com.edde746.tortuga.playback.players.PlayerAppSubtitle
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a player on Windows gets of each argument: a Windows program gets one command line, which the JDK writes from
 * the argument list ([jdkCommandLine]) and the program splits back itself ([windowsArgv]).
 */
class WindowsCommandLineTest {
    private val mpvExe = "C:\\Users\\me\\tortuga\\players\\mpv\\mpv.exe"
    private val vlcExe = "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe"
    private val potExe = "C:\\Program Files\\DAUM\\PotPlayer\\PotPlayerMini64.exe"
    private val mpcExe = "C:\\Program Files\\MPC-HC\\mpc-hc64.exe"
    private val playExe = "C:\\My Tools\\play.exe"
    private val pipe = "\\\\.\\pipe\\tortuga-mpv-test"
    private val windows = FakeHost(
        windows = true,
        environment = mapOf("PATH" to "C:\\Users\\me\\tortuga\\players\\mpv", "ProgramFiles" to "C:\\Program Files"),
        programs = setOf(mpvExe, vlcExe, potExe, mpcExe, playExe),
    )

    private fun custom(command: String, arguments: String) =
        PlayerApp("${CUSTOM_PREFIX}1", "Play", PlayerAppKind.CUSTOM, PlayerAppCapabilities.URL_ONLY, CustomPlayer("1", "Play", command, arguments))

    private fun launch(apps: DesktopPlayerApps, app: PlayerApp, media: PlayerAppMedia) = runBlocking { apps.launch(app, media) { } }

    @Test
    fun everyWindowsPlayerGetsEachArgumentWhole() {
        val runtime = FakeRuntime(FakeMpvChannel(pipe, busyTries = Int.MAX_VALUE), port = 9)
        val apps = DesktopPlayerApps(windows, runtime)
        // What an addon can put in a title, a header or a URL: quotes, backslashes before them and at the end, spaces, options.
        val media = PlayerAppMedia(
            url = "https://cdn.example/v/a b.mkv?t=\"k\"\\",
            local = false,
            headers = mapOf("User-Agent" to "UA \"1\" \\", "Referer" to "https://ref.example/\"x\"", "X-Key" to "a\\\"b c\\\\"),
            title = "Say \"Hi\" --script=\\\\evil\\x.lua \"",
            startSeconds = 61.0,
            subtitles = listOf(PlayerAppSubtitle("C:\\My Subs\\a \"b\".srt\\", local = true, title = null, language = "eng")),
            filename = null,
        )
        val known = listOf(
            KnownPlayer.MPV to listOf(mpvExe) + PlayerAppCommands.mpv(media, pipe),
            KnownPlayer.POTPLAYER to listOf(potExe) + PlayerAppCommands.potPlayer(media),
            KnownPlayer.MPC_HC to listOf(mpcExe) + PlayerAppCommands.mpc(media),
        )
        for ((player, arguments) in known) {
            assertEquals(PlayerAppLaunch.Launched(if (player == KnownPlayer.MPV) PlayerAppProgress.LIVE else PlayerAppProgress.NONE), launch(apps, player.app(), media))
            assertEquals(arguments, windowsArgv(jdkCommandLine(runtime.commands.last())), player.id)
        }
        launch(apps, KnownPlayer.VLC.app(), media)
        val vlc = windowsArgv(jdkCommandLine(runtime.commands.last()))
        val password = vlc.first { it.startsWith("--http-password=") }.substringAfter('=')
        assertEquals(listOf(vlcExe) + PlayerAppCommands.vlc(media, 9, password), vlc)

        // A custom player's template: each text as one argument, an empty argument and the URL after it.
        val texts = listOf(
            "", "\"", "\"\"", "a\"b", "\"quoted\"", "Say \"Hi\" now", "\" --script=evil.lua \"", "\\", "\\\\", "C:\\subs\\",
            "C:\\My Subs\\\\", "\\\\nas\\share", "a\\\"b", "a\\\\\"b c", "x\\\\\\\" y", " leading and trailing ", "tab\there\nline",
            "é \"ü\" 中",
        )
        for (text in texts) {
            launch(apps, custom("\"$playExe\"", "{title} '' {url}"), media.copy(title = text))
            assertEquals(listOf(playExe, text, "", media.url), windowsArgv(jdkCommandLine(runtime.commands.last())), text)
        }
        runtime.processes.forEach { it.quit() }
    }

    @Test
    fun aBatchFileIsNeverAPlayerOnWindows() {
        val host = FakeHost(
            windows = true,
            environment = mapOf("PATH" to "C:\\tools;C:\\bin"),
            programs = setOf("C:\\tools\\play.bat", "C:\\tools\\WRAP.CMD", "C:\\tools\\wrap.cmd", "C:\\tools\\play.bat.", "C:\\bin\\play.exe", "C:\\tools\\vlc.cmd"),
            values = mapOf(Triple(Root.LOCAL_MACHINE, "SOFTWARE\\VideoLAN\\VLC", "") to "C:\\tools\\vlc.cmd"),
        )
        val runtime = FakeRuntime()
        val apps = DesktopPlayerApps(host, runtime)
        val media = playerMedia()
        // cmd.exe would run a batch file with a title's & or | as its own commands: it isn't started.
        for (program in listOf("C:\\tools\\play.bat", "C:\\tools\\WRAP.CMD", "C:\\tools\\play.bat.", "play.bat")) {
            assertEquals(PlayerAppLaunch.Failed(PlayerAppFailure.OTHER), launch(apps, custom(program, "{title} {url}"), media), program)
        }
        assertEquals(PlayerAppLaunch.Failed(PlayerAppFailure.OTHER), launch(apps, KnownPlayer.VLC.app(), media))
        // A name alone finds programs only: wrap.cmd is no wrap, and play is the play.exe after play.bat on PATH.
        assertEquals(PlayerAppLaunch.Failed(PlayerAppFailure.NOT_INSTALLED), launch(apps, custom("wrap", "{url}"), media))
        assertEquals(PlayerAppLaunch.Launched(PlayerAppProgress.NONE), launch(apps, custom("play", "{url}"), media))
        assertEquals(listOf(listOf("C:\\bin\\play.exe", STREAM_URL)), runtime.commands)
    }

    /**
     * The command line the JDK's Windows `ProcessImpl` writes for [command] in its default mode
     * (`jdk.lang.Process.allowAmbiguousCommands` unset), from OpenJDK 25's source: the program and each argument with
     * whitespace, or empty, in quotes, an argument's trailing backslashes doubled and nothing else escaped; an argument
     * that starts and ends with a quote, and any other, as it is.
     */
    private fun jdkCommandLine(command: List<String>): String {
        fun quoted(text: String) = text.length >= 2 && text.startsWith('"') && text.endsWith('"')
        fun needsQuotes(text: String) = text.isEmpty() || (!quoted(text) && text.any { Character.isSpaceChar(it) || Character.isWhitespace(it) })
        val program = command.first().let { if (needsQuotes(it)) "\"$it\"" else it }
        val arguments = command.drop(1).map { argument ->
            if (!needsQuotes(argument)) argument else "\"" + argument + "\\".repeat(argument.length - argument.trimEnd('\\').length) + "\""
        }
        return (listOf(program) + arguments).joinToString(" ")
    }

    /**
     * [commandLine] split into arguments as a Windows program's C runtime does (Microsoft's "Parsing C command-line
     * arguments"; `CommandLineToArgvW`, which mpv and VLC call, differs only in a `""` inside quotes): the program up to
     * whitespace, its quotes grouping; then arguments at spaces and tabs outside quotes, where 2n backslashes and a `"`
     * are n backslashes and a quote that opens or closes, 2n+1 backslashes and a `"` are n backslashes and a `"`, other
     * backslashes are themselves, and `""` inside quotes is a `"`.
     */
    private fun windowsArgv(commandLine: String): List<String> {
        fun blank(c: Char) = c == ' ' || c == '\t'
        val argv = mutableListOf<String>()
        var i = 0
        val program = StringBuilder()
        var inQuotes = false
        while (i < commandLine.length && (inQuotes || !blank(commandLine[i]))) {
            if (commandLine[i] == '"') inQuotes = !inQuotes else program.append(commandLine[i])
            i++
        }
        argv += program.toString()
        while (true) {
            while (i < commandLine.length && blank(commandLine[i])) i++
            if (i == commandLine.length) return argv
            val argument = StringBuilder()
            inQuotes = false
            while (i < commandLine.length && (inQuotes || !blank(commandLine[i]))) {
                val c = commandLine[i]
                when {
                    c == '\\' -> {
                        var end = i
                        while (end < commandLine.length && commandLine[end] == '\\') end++
                        val count = end - i
                        if (end < commandLine.length && commandLine[end] == '"') {
                            repeat(count / 2) { argument.append('\\') }
                            // An odd one escapes the quote; after an even number the quote is read next.
                            if (count % 2 == 1) argument.append('"')
                            i = if (count % 2 == 1) end + 1 else end
                        } else {
                            repeat(count) { argument.append('\\') }
                            i = end
                        }
                    }
                    c == '"' && inQuotes && i + 1 < commandLine.length && commandLine[i + 1] == '"' -> {
                        argument.append('"')
                        i += 2
                    }
                    c == '"' -> {
                        inQuotes = !inQuotes
                        i++
                    }
                    else -> {
                        argument.append(c)
                        i++
                    }
                }
            }
            argv += argument.toString()
        }
    }
}
