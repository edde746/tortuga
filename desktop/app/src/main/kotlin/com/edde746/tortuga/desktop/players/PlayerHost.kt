package com.edde746.tortuga.desktop.players

import com.edde746.tortuga.desktop.windows.WindowsRegistry
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * What finding the players reads of this computer ([PlayerDetection]): its environment, its files and, on Windows, its
 * registry. Paths are the OS's own strings (`C:\Program Files\VideoLAN\VLC\vlc.exe`, `/usr/bin/mpv`).
 */
internal interface PlayerHost {
    /** Windows (else Linux). */
    val windows: Boolean

    /** The environment variable [name] (on Windows in any case), null when unset or empty. */
    fun env(name: String): String?

    /** [path] is a program: on Windows a file (an app execution alias included), elsewhere an executable file. */
    fun isProgram(path: String): Boolean

    fun isDirectory(path: String): Boolean

    /** The registry's string [value] of [key] (its default value for an empty [value]); null off Windows. */
    fun registry(root: WindowsRegistry.Root, key: String, value: String): String?

    companion object {
        /** This computer. */
        val SYSTEM: PlayerHost = object : PlayerHost {
            override val windows = System.getProperty("os.name").startsWith("Windows")

            override fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }

            override fun isProgram(path: String): Boolean = runCatching {
                val file = Path.of(path)
                // An app execution alias (WindowsApps) is a reparse point Java can't follow: its own entry counts.
                if (windows) Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(file) else Files.isRegularFile(file) && Files.isExecutable(file)
            }.getOrDefault(false)

            override fun isDirectory(path: String): Boolean = runCatching { Files.isDirectory(Path.of(path)) }.getOrDefault(false)

            override fun registry(root: WindowsRegistry.Root, key: String, value: String): String? =
                if (windows) WindowsRegistry.string(root, key, value) else null
        }
    }
}
