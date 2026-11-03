package com.edde746.tortuga.desktop

import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.desktop.linux.inFlatpak
import com.edde746.tortuga.desktop.windows.WindowsKnownFolders
import com.edde746.tortuga.downloads.DownloadLocation
import com.edde746.tortuga.downloads.DownloadPlatform
import com.edde746.tortuga.downloads.DownloadTransport
import com.edde746.tortuga.downloads.KtorTransport
import com.edde746.tortuga.log.Log
import io.ktor.client.HttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Downloads on Windows and Linux: the shared engine's [KtorTransport] in this process, while the app runs (queued and
 * interrupted downloads go on at the next launch). One default folder, [root] (the user's Videos folder/Tortuga for
 * the device, [videosFolder]); Settings changes it to any folder the user picks ([DownloadLocation.folder]). The
 * folder's id is its path, so downloads stay where they were saved when the Videos folder moves. The desktop can't
 * tell a metered network: no Wi-Fi only setting.
 */
internal class DesktopDownloads(http: HttpClient, private val root: Path) : DownloadPlatform {
    override fun locations(): List<DownloadLocation> {
        val path = root.toAbsolutePath().toString()
        return listOf(DownloadLocation.folder(path).copy(removable = false))
    }

    override val choosesFolder: Boolean = true

    /** The free bytes for this user on the volume holding [path] (its nearest directory that exists yet). */
    override fun freeBytes(path: String): Long? =
        generateSequence(File(path).absoluteFile) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace

    override val transport: DownloadTransport = KtorTransport(http)

    override val wifiOnlyDefault: Boolean? = null

    companion object {
        private const val TAG = "DesktopDownloads"
        private val isWindows = System.getProperty("os.name").startsWith("Windows")

        /**
         * `Tortuga` in the user's Videos folder: Windows' Known Folder (wherever the user or OneDrive moved it), else
         * `%USERPROFILE%\Videos`; on Linux [linuxFolder] over `xdg-user-dir VIDEOS` (the XDG user directory, localized).
         */
        fun videosFolder(): Path {
            val home = Path.of(System.getProperty("user.home"))
            if (!isWindows) return linuxFolder(xdgVideos(home), home, inFlatpak, DatabaseFactory.defaultDirectory())
            val videos = windowsVideos() ?: (System.getenv("USERPROFILE")?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: home).resolve("Videos")
            return videos.resolve("Tortuga")
        }

        /**
         * Linux's default folder: `Tortuga` in [videos] (the XDG Videos directory; null when none is set), else in
         * `~/Videos`. Inside the Flatpak ([flatpak]) the sandbox's home is in memory and gone when the app exits: there
         * the folder is `Tortuga` in [videos] only as the Flatpak shares it (the manifest's
         * `--filesystem=xdg-videos/Tortuga:create`, made on the host at each start, so it exists), else `downloads` in
         * the app's data folder [dataDirectory] (`~/.var/app/com.edde746.tortuga/data/tortuga`, which persists), logged;
         * never a folder made in the sandbox.
         */
        internal fun linuxFolder(videos: Path?, home: Path, flatpak: Boolean, dataDirectory: Path): Path {
            if (!flatpak) return (videos ?: home.resolve("Videos")).resolve("Tortuga")
            videos?.resolve("Tortuga")?.takeIf { Files.isDirectory(it) }?.let { return it }
            Log.w(TAG) { "the Flatpak shares no Videos folder of the host's: downloads go to the app's data folder" }
            return dataDirectory.resolve("downloads")
        }

        private fun windowsVideos(): Path? = try {
            WindowsKnownFolders.videos()
        } catch (e: Exception) {
            Log.w(TAG) { "No Known Folder for Videos: ${e::class.simpleName}" }
            null
        }

        /** The XDG Videos directory; null without `xdg-user-dir` or without one set (it answers the home directory). */
        private fun xdgVideos(home: Path): Path? = try {
            val process = ProcessBuilder("xdg-user-dir", "VIDEOS").redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy()
                null
            } else {
                val answer = process.inputStream.bufferedReader().readText().trim()
                answer.takeIf { process.exitValue() == 0 && it.isNotEmpty() }?.let { Path.of(it) }?.takeIf { it != home }
            }
        } catch (_: java.io.IOException) {
            null
        }
    }
}
