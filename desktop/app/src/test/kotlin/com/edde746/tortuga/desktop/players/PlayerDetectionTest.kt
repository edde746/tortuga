package com.edde746.tortuga.desktop.players

import com.edde746.tortuga.desktop.windows.WindowsRegistry.Root
import com.edde746.tortuga.playback.players.PlayerAppKind
import com.edde746.tortuga.playback.players.PlayerAppProgress
import com.edde746.tortuga.playback.players.PlayerAppSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerDetectionTest {
    private val windowsEnv = mapOf(
        "PATH" to "C:\\Windows\\system32;\"C:\\Users\\me\\tortuga\\players\\mpv\";;C:\\tools\\",
        "PATHEXT" to ".COM;.EXE;.BAT",
        "ProgramFiles" to "C:\\Program Files",
        "ProgramFiles(x86)" to "C:\\Program Files (x86)",
        "USERPROFILE" to "C:\\Users\\me",
    )

    @Test
    fun windowsFindsEachPlayerWhereItInstalls() {
        val host = FakeHost(
            windows = true,
            environment = windowsEnv,
            programs = setOf(
                "C:\\Users\\me\\tortuga\\players\\mpv\\mpv.exe",
                "D:\\Apps\\VLC\\vlc.exe",
                "C:\\Users\\me\\AppData\\Local\\MPC-HC\\mpc-hc64.exe",
                "C:\\Program Files\\MPC-BE\\mpc-be64.exe",
                "C:\\Program Files\\DAUM\\PotPlayer\\PotPlayerMini64.exe",
            ),
            values = mapOf(
                Triple(Root.LOCAL_MACHINE, "SOFTWARE\\VideoLAN\\VLC", "") to "D:\\Apps\\VLC\\vlc.exe",
                Triple(Root.CURRENT_USER, "SOFTWARE\\MPC-HC\\MPC-HC", "ExePath") to "C:\\Users\\me\\AppData\\Local\\MPC-HC\\mpc-hc64.exe",
                // An uninstalled MPC-BE leaves its ExePath behind: the Program Files copy is the one.
                Triple(Root.LOCAL_MACHINE, "SOFTWARE\\MPC-BE", "ExePath") to "E:\\Gone\\mpc-be64.exe",
                Triple(Root.CLASSES_ROOT, "potplayer\\shell\\open\\command", "") to "\"C:\\Program Files\\DAUM\\PotPlayer\\PotPlayerMini64.exe\" \"%1\"",
            ),
        )
        val found = PlayerDetection(host).findAll()
        assertEquals(
            listOf(
                "mpv" to "C:\\Users\\me\\tortuga\\players\\mpv\\mpv.exe",
                "vlc" to "D:\\Apps\\VLC\\vlc.exe",
                "mpc-hc" to "C:\\Users\\me\\AppData\\Local\\MPC-HC\\mpc-hc64.exe",
                "mpc-be" to "C:\\Program Files\\MPC-BE\\mpc-be64.exe",
                "potplayer" to "C:\\Program Files\\DAUM\\PotPlayer\\PotPlayerMini64.exe",
            ),
            found.map { it.player.id to it.program },
        )
        assertTrue(found.all { it.flatpak == null })
        val apps = found.map { it.player.app() }
        assertEquals(listOf("mpv", "VLC", "MPC-HC", "MPC-BE", "PotPlayer"), apps.map { it.name })
        assertTrue(apps.all { it.kind == PlayerAppKind.DETECTED })
        assertEquals(listOf(PlayerAppProgress.LIVE, PlayerAppProgress.LIVE), apps.filter { it.capabilities.progress != PlayerAppProgress.NONE }.map { it.capabilities.progress })
    }

    @Test
    fun windowsPrefersPathThenTheRegistryThenProgramFiles() {
        val vlcOnPath = "C:\\tools\\vlc.exe"
        val installed = "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe"
        val registry = mapOf(Triple(Root.LOCAL_MACHINE, "SOFTWARE\\WOW6432Node\\VideoLAN\\VLC", "InstallDir") to "C:\\Program Files (x86)\\VideoLAN\\VLC")
        val x86 = "C:\\Program Files (x86)\\VideoLAN\\VLC\\vlc.exe"
        fun vlc(vararg programs: String) = PlayerDetection(FakeHost(true, windowsEnv, programs.toSet(), values = registry)).find(KnownPlayer.VLC)?.program
        assertEquals(vlcOnPath, vlc(vlcOnPath, x86, installed))
        assertEquals(x86, vlc(x86, installed))
        assertEquals(installed, vlc(installed))
        assertNull(vlc())
    }

    @Test
    fun windowsReadsAppPathsAndScoop() {
        val host = FakeHost(
            windows = true,
            environment = windowsEnv,
            programs = setOf("C:\\Users\\me\\scoop\\apps\\mpv\\current\\mpv.exe", "C:\\Apps\\Pot\\PotPlayerMini.exe"),
            values = mapOf(Triple(Root.LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\PotPlayerMini.exe", "") to "C:\\Apps\\Pot\\PotPlayerMini.exe"),
        )
        assertEquals(listOf("mpv", "potplayer"), PlayerDetection(host).findAll().map { it.player.id })
    }

    @Test
    fun linuxFindsPlayersOnPathElseTheirFlatpaks() {
        val host = FakeHost(
            windows = false,
            environment = mapOf("PATH" to "/home/me/.local/bin:/usr/bin", "HOME" to "/home/me"),
            programs = setOf("/usr/bin/mpv", "/usr/bin/flatpak", "/home/me/.local/bin/notes"),
            directories = setOf(
                "/var/lib/flatpak/app/org.videolan.VLC/current/active",
                "/home/me/.local/share/flatpak/app/io.github.celluloid_player.Celluloid/current/active",
                // Installed both ways: the program on PATH wins.
                "/var/lib/flatpak/app/io.mpv.Mpv/current/active",
            ),
        )
        assertEquals(
            listOf(FoundPlayer(KnownPlayer.MPV, "/usr/bin/mpv"), FoundPlayer(KnownPlayer.VLC, "/usr/bin/flatpak", "org.videolan.VLC"), FoundPlayer(KnownPlayer.CELLULOID, "/usr/bin/flatpak", "io.github.celluloid_player.Celluloid")),
            PlayerDetection(host).findAll(),
        )
    }

    @Test
    fun linuxNeedsFlatpakToRunAFlatpakApp() {
        val host = FakeHost(
            windows = false,
            environment = mapOf("PATH" to "/usr/bin", "HOME" to "/home/me", "XDG_DATA_HOME" to "/data"),
            directories = setOf("/data/flatpak/app/io.mpv.Mpv/current/active"),
        )
        assertEquals(emptyList(), PlayerDetection(host).findAll())
        val withFlatpak = FakeHost(false, mapOf("PATH" to "/usr/bin", "XDG_DATA_HOME" to "/data"), setOf("/usr/bin/flatpak"), setOf("/data/flatpak/app/io.mpv.Mpv/current/active"))
        val found = PlayerDetection(withFlatpak).findAll().single()
        assertEquals(FoundPlayer(KnownPlayer.MPV, "/usr/bin/flatpak", "io.mpv.Mpv"), found)
        assertTrue(PlayerDetection(withFlatpak).stillThere(found))
        assertFalse(PlayerDetection(FakeHost(false, mapOf("PATH" to "/usr/bin"), setOf("/usr/bin/flatpak"))).stillThere(found))
    }

    @Test
    fun aCustomProgramIsAPathOrANameOnPath() {
        val windows = PlayerDetection(FakeHost(true, windowsEnv, setOf("C:\\tools\\player.exe", "D:\\My Player\\play.exe")))
        assertEquals("C:\\tools\\player.exe", windows.program("player"))
        assertEquals("C:\\tools\\player.exe", windows.program("player.exe"))
        assertEquals("D:\\My Player\\play.exe", windows.program("\"D:\\My Player\\play.exe\""))
        assertNull(windows.program("D:\\My Player\\gone.exe"))
        assertNull(windows.program("other"))
        assertNull(windows.program("  "))
        val linux = PlayerDetection(FakeHost(false, mapOf("PATH" to "/usr/local/bin:/usr/bin"), setOf("/usr/bin/smplayer", "/opt/p/play")))
        assertEquals("/usr/bin/smplayer", linux.program("smplayer"))
        assertEquals("/opt/p/play", linux.program("/opt/p/play"))
        assertNull(linux.program("./play"))
    }

    @Test
    fun aHandlerCommandNamesItsProgram() {
        assertEquals("C:\\Program Files\\DAUM\\PotPlayer\\PotPlayerMini64.exe", PlayerDetection.handlerProgram("\"C:\\Program Files\\DAUM\\PotPlayer\\PotPlayerMini64.exe\" \"%1\""))
        assertEquals("C:\\Program Files\\DAUM\\PotPlayer\\PotPlayerMini64.exe", PlayerDetection.handlerProgram("C:\\Program Files\\DAUM\\PotPlayer\\PotPlayerMini64.exe %1"))
        assertEquals("player", PlayerDetection.handlerProgram("player %1"))
        assertNull(PlayerDetection.handlerProgram("\"\""))
    }

    @Test
    fun aFlatpakAppReadsTheFoldersOfADownloadAndItsSubtitles() {
        val found = FoundPlayer(KnownPlayer.CELLULOID, "/usr/bin/flatpak", "io.github.celluloid_player.Celluloid")
        val download = playerMedia(
            url = "/home/me/Videos/Tortuga/movie.mkv",
            local = true,
            subtitles = listOf(
                PlayerAppSubtitle("/home/me/Videos/Tortuga/movie.eng.srt", local = true, title = null, language = "eng"),
                PlayerAppSubtitle("/home/me/.cache/subs/fre.srt", local = true, title = null, language = "fre"),
            ),
        )
        assertEquals(
            listOf("/usr/bin/flatpak", "run", "--filesystem=/home/me/Videos/Tortuga:ro", "--filesystem=/home/me/.cache/subs:ro", "io.github.celluloid_player.Celluloid", "--x", "/home/me/Videos/Tortuga/movie.mkv"),
            found.command(listOf("--x", download.url), download),
        )
        assertEquals(listOf("/usr/bin/flatpak", "run", "io.github.celluloid_player.Celluloid", STREAM_URL), found.command(listOf(STREAM_URL), playerMedia()))
        assertEquals(listOf("/usr/bin/mpv", STREAM_URL), FoundPlayer(KnownPlayer.MPV, "/usr/bin/mpv").command(listOf(STREAM_URL), download))
    }
}
