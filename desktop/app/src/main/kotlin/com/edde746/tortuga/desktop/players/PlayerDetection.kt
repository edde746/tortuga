package com.edde746.tortuga.desktop.players

import com.edde746.tortuga.desktop.windows.WindowsRegistry.Root
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.players.PlayerAppCapabilities
import com.edde746.tortuga.playback.players.PlayerAppKind
import com.edde746.tortuga.playback.players.PlayerAppMedia
import com.edde746.tortuga.playback.players.PlayerAppProgress

/**
 * The players Tortuga knows on Windows and Linux, and what each takes and reports from this launcher: mpv and VLC
 * report live (mpv's IPC, VLC's HTTP interface); the others take what their command lines do and report nothing.
 */
internal enum class KnownPlayer(val id: String, val displayName: String, val capabilities: PlayerAppCapabilities) {
    MPV("mpv", "mpv", PlayerAppCapabilities(startPosition = true, subtitles = true, progress = PlayerAppProgress.LIVE)),

    /** Headers: VLC sends only the `User-Agent` and `Referer` of an addon's request headers. */
    VLC("vlc", "VLC", PlayerAppCapabilities(startPosition = true, subtitles = true, progress = PlayerAppProgress.LIVE)),

    /** Subtitles: MPC-HC and MPC-BE load files only (a download's). */
    MPC_HC("mpc-hc", "MPC-HC", PlayerAppCapabilities(startPosition = true, subtitles = false, progress = PlayerAppProgress.NONE)),
    MPC_BE("mpc-be", "MPC-BE", PlayerAppCapabilities(startPosition = true, subtitles = false, progress = PlayerAppProgress.NONE)),
    POTPLAYER("potplayer", "PotPlayer", PlayerAppCapabilities(startPosition = true, subtitles = true, progress = PlayerAppProgress.NONE)),
    CELLULOID("celluloid", "Celluloid", PlayerAppCapabilities(startPosition = true, subtitles = true, progress = PlayerAppProgress.NONE)),
    ;

    fun app(): PlayerApp = PlayerApp(id, displayName, PlayerAppKind.DETECTED, capabilities)

    companion object {
        fun of(id: String): KnownPlayer? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Where a known [player] is: its [program], or, for a Flatpak app, `flatpak` as [program] and the app's id as
 * [flatpak].
 */
internal data class FoundPlayer(val player: KnownPlayer, val program: String, val flatpak: String? = null) {
    /**
     * The command line that starts it on [media] with [arguments]. A Flatpak app is run with read access to the folders
     * of a download and its subtitles (its sandbox sees no other files).
     */
    fun command(arguments: List<String>, media: PlayerAppMedia): List<String> {
        if (flatpak == null) return listOf(program) + arguments
        val files = listOfNotNull(media.url.takeIf { media.local }) + media.subtitles.filter { it.local }.map { it.url }
        val grants = files.map { it.substringBeforeLast('/') }.filter { it.isNotEmpty() }.distinct().map { "--filesystem=$it:ro" }
        return listOf(program, "run") + grants + flatpak + arguments
    }
}

/**
 * Finds the known players on this computer ([host]), the way each one installs.
 *
 * Windows: each player's program on `PATH` first (as `where.exe` finds it: portable players), then where
 * its installer records it in the registry (VLC's `HKLM\SOFTWARE\VideoLAN\VLC`, MPC-HC's and MPC-BE's `ExePath`,
 * PotPlayer's `potplayer://` handler), Windows' App Paths, then its default folders under Program Files (mpv also
 * Scoop's). Linux: mpv, VLC and Celluloid on `PATH`, else their Flathub apps when installed (run through
 * `flatpak run`).
 */
internal class PlayerDetection(private val host: PlayerHost) {
    /** Every known player found here, in [KnownPlayer] order. */
    fun findAll(): List<FoundPlayer> = KnownPlayer.entries.mapNotNull(::find)

    fun find(player: KnownPlayer): FoundPlayer? = if (host.windows) findOnWindows(player) else findOnLinux(player)

    /** [found] is still where it was (the program, and a Flatpak app still installed). */
    fun stillThere(found: FoundPlayer): Boolean = host.isProgram(found.program) && (found.flatpak == null || flatpakInstalled(found.flatpak))

    /**
     * A custom player's [command]: a program's path, or a name looked up on `PATH` as the shell would (on Windows with
     * `PATHEXT`'s extensions when it has none, but a batch file's: [isBatchFile]); null when there's no such program.
     */
    fun program(command: String): String? {
        val name = command.trim().removeSurrounding("\"")
        if (name.isEmpty()) return null
        val hasFolder = '/' in name || (host.windows && ('\\' in name || ':' in name))
        if (hasFolder) return name.takeIf(host::isProgram)
        val names = if (host.windows && '.' !in name) extensions().map { name + it } else listOf(name)
        return onPath(names).firstOrNull(host::isProgram)
    }

    private fun findOnWindows(player: KnownPlayer): FoundPlayer? {
        val candidates = when (player) {
            KnownPlayer.MPV -> onPath("mpv.exe") + appPaths("mpv.exe") +
                under("ProgramFiles", "mpv\\mpv.exe") + under("USERPROFILE", "scoop\\apps\\mpv\\current\\mpv.exe")
            KnownPlayer.VLC -> onPath("vlc.exe") +
                listOf("SOFTWARE\\VideoLAN\\VLC", "SOFTWARE\\WOW6432Node\\VideoLAN\\VLC").asSequence().flatMap { key ->
                    // The installer writes the program as the key's default value and its folder as InstallDir.
                    sequenceOf(registry(Root.LOCAL_MACHINE, key, ""), registry(Root.LOCAL_MACHINE, key, "InstallDir")?.let { "$it\\vlc.exe" })
                }.filterNotNull() +
                appPaths("vlc.exe") + under("ProgramFiles", "VideoLAN\\VLC\\vlc.exe") + under("ProgramFiles(x86)", "VideoLAN\\VLC\\vlc.exe")
            KnownPlayer.MPC_HC -> onPath("mpc-hc64.exe", "mpc-hc.exe") +
                // MPC-HC records its program at every start.
                sequenceOf(registry(Root.CURRENT_USER, "SOFTWARE\\MPC-HC\\MPC-HC", "ExePath")).filterNotNull() +
                appPaths("mpc-hc64.exe", "mpc-hc.exe") + under("ProgramFiles", "MPC-HC\\mpc-hc64.exe") + under("ProgramFiles(x86)", "MPC-HC\\mpc-hc.exe")
            KnownPlayer.MPC_BE -> onPath("mpc-be64.exe", "mpc-be.exe") +
                sequenceOf(
                    registry(Root.LOCAL_MACHINE, "SOFTWARE\\MPC-BE", "ExePath"),
                    registry(Root.LOCAL_MACHINE, "SOFTWARE\\WOW6432Node\\MPC-BE", "ExePath"),
                ).filterNotNull() +
                appPaths("mpc-be64.exe", "mpc-be.exe") + under("ProgramFiles", "MPC-BE\\mpc-be64.exe") + under("ProgramFiles(x86)", "MPC-BE\\mpc-be.exe")
            KnownPlayer.POTPLAYER -> onPath("PotPlayerMini64.exe", "PotPlayerMini.exe") +
                sequenceOf(registry(Root.CLASSES_ROOT, "potplayer\\shell\\open\\command", "")?.let(::handlerProgram)).filterNotNull() +
                appPaths("PotPlayerMini64.exe", "PotPlayerMini.exe") +
                under("ProgramFiles", "DAUM\\PotPlayer\\PotPlayerMini64.exe") + under("ProgramFiles(x86)", "DAUM\\PotPlayer\\PotPlayerMini.exe")
            KnownPlayer.CELLULOID -> emptySequence()
        }
        return candidates.firstOrNull(host::isProgram)?.let { FoundPlayer(player, it) }
    }

    private fun findOnLinux(player: KnownPlayer): FoundPlayer? {
        val (name, flatpak) = when (player) {
            KnownPlayer.MPV -> "mpv" to "io.mpv.Mpv"
            KnownPlayer.VLC -> "vlc" to "org.videolan.VLC"
            KnownPlayer.CELLULOID -> "celluloid" to "io.github.celluloid_player.Celluloid"
            KnownPlayer.MPC_HC, KnownPlayer.MPC_BE, KnownPlayer.POTPLAYER -> return null
        }
        onPath(name).firstOrNull(host::isProgram)?.let { return FoundPlayer(player, it) }
        if (!flatpakInstalled(flatpak)) return null
        return onPath("flatpak").firstOrNull(host::isProgram)?.let { FoundPlayer(player, it, flatpak) }
    }

    /** The Flatpak app [id] is installed for this user or system-wide (its deployed `current` branch). */
    private fun flatpakInstalled(id: String): Boolean {
        val userData = host.env("XDG_DATA_HOME") ?: host.env("HOME")?.let { "$it/.local/share" }
        return listOfNotNull(userData?.let { "$it/flatpak" }, "/var/lib/flatpak").any { host.isDirectory("$it/app/$id/current/active") }
    }

    private fun onPath(vararg names: String): Sequence<String> = onPath(names.asList())

    private fun onPath(names: List<String>): Sequence<String> {
        val separator = if (host.windows) ';' else ':'
        val folders = host.env("PATH").orEmpty().split(separator).map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
        return folders.asSequence().flatMap { folder -> names.asSequence().map { join(folder, it) } }
    }

    private fun appPaths(vararg programs: String): Sequence<String> = programs.asSequence().flatMap { program ->
        sequenceOf(Root.CURRENT_USER, Root.LOCAL_MACHINE).mapNotNull { registry(it, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$program", "") }
    }

    private fun under(variable: String, relative: String): Sequence<String> = listOfNotNull(host.env(variable)?.let { join(it, relative) }).asSequence()

    private fun registry(root: Root, key: String, value: String): String? = host.registry(root, key, value)?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }

    private fun extensions(): List<String> =
        (host.env("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD").split(';').filter { it.isNotEmpty() && !isBatchFile(it) }.map { it.lowercase() }

    private fun join(folder: String, name: String): String {
        val separator = if (host.windows) '\\' else '/'
        return if (folder.endsWith(separator) || (host.windows && folder.endsWith('/'))) folder + name else "$folder$separator$name"
    }

    companion object {
        /**
         * [program] is a batch file (`.bat`, `.cmd`), which Windows runs through `cmd.exe`, whose parser reads `&`, `|`
         * and `%` in any argument; Windows drops a name's trailing dots and spaces first.
         */
        fun isBatchFile(program: String): Boolean {
            val name = program.trimEnd('.', ' ')
            return name.endsWith(".bat", ignoreCase = true) || name.endsWith(".cmd", ignoreCase = true)
        }

        /** A URL handler's command (`"C:\…\PotPlayerMini64.exe" "%1"`): its program. */
        fun handlerProgram(command: String): String? {
            val text = command.trim()
            val program = if (text.startsWith('"')) {
                text.drop(1).substringBefore('"')
            } else {
                val exe = text.indexOf(".exe", ignoreCase = true)
                if (exe >= 0) text.substring(0, exe + 4) else text.substringBefore(' ')
            }
            return program.takeIf { it.isNotEmpty() }
        }
    }
}
