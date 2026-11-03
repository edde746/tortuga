package com.edde746.tortuga.desktop

import com.edde746.tortuga.desktop.windows.WindowsKnownFolders
import com.edde746.tortuga.downloads.DownloadLocation
import io.ktor.client.HttpClient
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class DesktopDownloadsTest {
    private val http = HttpClient()
    private val dir = Files.createTempDirectory("downloads")

    @AfterTest
    fun cleanUp() {
        http.close()
        dir.deleteRecursively()
    }

    @Test
    fun theDefaultFolderIsTortugaInTheUsersVideos() {
        val folder = DesktopDownloads.videosFolder()
        assertTrue(folder.isAbsolute, "$folder")
        assertEquals("Tortuga", folder.fileName.toString())
        assertNotNull(folder.parent?.fileName, "$folder")
        // Windows: from the Known Folder itself, not the %USERPROFILE%\Videos fallback.
        if (System.getProperty("os.name").startsWith("Windows")) assertEquals(WindowsKnownFolders.videos()?.resolve("Tortuga"), folder)
    }

    @Test
    fun insideTheFlatpakDownloadsGoOnlyWhereTheyPersist() {
        val home = dir.resolve("home")
        val data = home.resolve(".var/app/com.edde746.tortuga/data/tortuga")
        val videos = home.resolve("Videos")
        // The manifest's xdg-videos/Tortuga:create: Flatpak made the host's folder and shares it.
        val shared = Files.createDirectories(videos.resolve("Tortuga"))
        assertEquals(shared, DesktopDownloads.linuxFolder(videos, home, flatpak = true, data))
        // No Videos folder set on the host, or none shared: the app's data folder, not one in the sandbox's home in memory.
        assertEquals(data.resolve("downloads"), DesktopDownloads.linuxFolder(null, home, flatpak = true, data))
        assertEquals(data.resolve("downloads"), DesktopDownloads.linuxFolder(home.resolve("Vidéos"), home, flatpak = true, data))
        // Outside it the folder is made where the user's Videos are, or would be.
        assertEquals(home.resolve("Vidéos").resolve("Tortuga"), DesktopDownloads.linuxFolder(home.resolve("Vidéos"), home, flatpak = false, data))
        assertEquals(videos.resolve("Tortuga"), DesktopDownloads.linuxFolder(null, home, flatpak = false, data))
    }

    @Test
    fun itsOneLocationIsTheRootFolderByPath() {
        val root = dir.resolve("Tortuga")
        val location = DesktopDownloads(http, root).locations().single()
        assertEquals(root.absolutePathString(), DownloadLocation.folderPath(location.id))
        assertEquals(root.absolutePathString(), location.path)
    }

    @Test
    fun freeSpaceIsTheNearestExistingDirectorysVolume() {
        val platform = DesktopDownloads(http, dir)
        val free = assertNotNull(platform.freeBytes(dir.resolve("Movies").resolve("Title (2000)").toString()))
        assertTrue(free > 0)
    }
}
