package com.edde746.tortuga.desktop.players

import com.edde746.tortuga.playback.players.CustomPlayer
import com.edde746.tortuga.playback.players.CustomPlayerKind
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.players.PlayerApp.Companion.CUSTOM_PREFIX
import com.edde746.tortuga.playback.players.PlayerAppCapabilities
import com.edde746.tortuga.playback.players.PlayerAppCommands
import com.edde746.tortuga.playback.players.PlayerAppEvent
import com.edde746.tortuga.playback.players.PlayerAppEvent.Ended
import com.edde746.tortuga.playback.players.PlayerAppEvent.Position
import com.edde746.tortuga.playback.players.PlayerAppEvent.Started
import com.edde746.tortuga.playback.players.PlayerAppEvents
import com.edde746.tortuga.playback.players.PlayerAppFailure
import com.edde746.tortuga.playback.players.PlayerAppKind
import com.edde746.tortuga.playback.players.PlayerAppLaunch
import com.edde746.tortuga.playback.players.PlayerAppMedia
import com.edde746.tortuga.playback.players.PlayerAppProgress
import com.edde746.tortuga.playback.players.PlayerAppSubtitle
import com.edde746.tortuga.playback.players.VlcHttpSession
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlayerAppsTest {
    private val mpvExe = "C:\\Users\\me\\tortuga\\players\\mpv\\mpv.exe"
    private val vlcExe = "C:\\Users\\me\\tortuga\\players\\vlc\\vlc.exe"
    private val potExe = "C:\\Program Files\\DAUM\\PotPlayer\\PotPlayerMini64.exe"
    private val windows = FakeHost(
        windows = true,
        environment = mapOf(
            "PATH" to "C:\\Users\\me\\tortuga\\players\\mpv;C:\\Users\\me\\tortuga\\players\\vlc;C:\\tools",
            "PATHEXT" to ".COM;.EXE",
            "ProgramFiles" to "C:\\Program Files",
        ),
        programs = setOf(mpvExe, vlcExe, potExe, "C:\\tools\\play.exe"),
    )

    /** What a launch reported, in order. */
    private class Heard : PlayerAppEvents {
        val events = LinkedBlockingQueue<PlayerAppEvent>()

        override fun onEvent(event: PlayerAppEvent) {
            events.put(event)
        }

        fun next(): PlayerAppEvent? = events.poll(5, TimeUnit.SECONDS)

        fun nothingFor(millis: Long): Boolean = events.poll(millis, TimeUnit.MILLISECONDS) == null
    }

    private class Observed : PlayerAppsObserver {
        val launches = CopyOnWriteArrayList<Pair<String, PlayerAppLaunch>>()
        val events = CopyOnWriteArrayList<Pair<String, PlayerAppEvent>>()

        override fun playerAppLaunched(playerId: String, launch: PlayerAppLaunch) {
            launches += playerId to launch
        }

        override fun playerAppEvent(playerId: String, reported: PlayerAppEvent) {
            events += playerId to reported
        }
    }

    private fun launch(apps: DesktopPlayerApps, app: PlayerApp, media: PlayerAppMedia, heard: PlayerAppEvents) =
        runBlocking { apps.launch(app, media, heard) }

    @Test
    fun customCommandsAreTheirPlatformsKind() {
        val apps = DesktopPlayerApps(windows, FakeRuntime())
        assertEquals(CustomPlayerKind.COMMAND, apps.customPlayers)
        assertTrue(apps.handsOffFiles)
    }

    @Test
    fun detectionAnswersOffTheCallersThread() {
        val found = AtomicReference<Pair<List<PlayerApp>, Thread>>()
        val done = CountDownLatch(1)
        DesktopPlayerApps(windows, FakeRuntime()).detect { players ->
            found.set(players to Thread.currentThread())
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("mpv", "vlc", "potplayer"), found.get().first.map { it.id })
        assertTrue(found.get().second != Thread.currentThread())
    }

    @Test
    fun mpvReportsLiveAndEndsOnceAfterItsProcessExits() {
        val channel = FakeMpvChannel("\\\\.\\pipe\\tortuga-mpv-test")
        val runtime = FakeRuntime(channel)
        val observed = Observed()
        val heard = Heard()
        val media = playerMedia(startSeconds = 61.0)
        val apps = DesktopPlayerApps(windows, runtime, observed)

        assertEquals(PlayerAppLaunch.Launched(PlayerAppProgress.LIVE), launch(apps, KnownPlayer.MPV.app(), media, heard))
        assertEquals(listOf(mpvExe) + PlayerAppCommands.mpv(media, channel.path), runtime.commands.single())

        channel.say("""{"event":"file-loaded"}""", """{"event":"property-change","id":2,"name":"duration","data":120}""")
        channel.say("""{"event":"property-change","id":1,"name":"time-pos","data":61.2}""", """{"event":"property-change","id":3,"name":"pause","data":true}""")
        assertEquals(Started, heard.next())
        assertEquals(Position(61_200, 120_000, paused = false), heard.next())
        assertEquals(Position(61_200, 120_000, paused = true), heard.next())

        // mpv closed the connection as it quit, but its process hasn't exited yet: nothing ends before it does.
        channel.hangUp()
        assertTrue(heard.nothingFor(300))
        runtime.processes.single().quit()
        assertEquals(Ended(61_200, 120_000, completed = false), heard.next())
        assertTrue(heard.nothingFor(300), "Ended goes out once")
        assertTrue(channel.closed, "the IPC's place is cleaned up")
        assertEquals(listOf<Pair<String, PlayerAppLaunch>>("mpv" to PlayerAppLaunch.Launched(PlayerAppProgress.LIVE)), observed.launches.toList())
        assertEquals(
            listOf(Started, Position(61_200, 120_000, false), Position(61_200, 120_000, true), Ended(61_200, 120_000, false)),
            observed.events.map { it.second },
        )
    }

    @Test
    fun mpvThatQuitBeforeListeningStillEnds() {
        val channel = FakeMpvChannel("p", busyTries = Int.MAX_VALUE)
        val runtime = FakeRuntime(channel)
        val heard = Heard()
        launch(DesktopPlayerApps(windows, runtime), KnownPlayer.MPV.app(), playerMedia(), heard)
        runtime.processes.single().quit()
        assertEquals(Ended(null, null, completed = false), heard.next())
        assertTrue(heard.nothingFor(300))
        assertTrue(channel.closed)
    }

    @Test
    fun mpvThatDiedAsItLoadedEndsFailed() {
        val runtime = FakeRuntime(FakeMpvChannel("p", busyTries = Int.MAX_VALUE))
        val heard = Heard()
        launch(DesktopPlayerApps(windows, runtime), KnownPlayer.MPV.app(), playerMedia(), heard)
        // 128 + SIGABRT: a broken install's loader aborts.
        runtime.processes.single().quit(status = 134)
        assertEquals(Ended(null, null, completed = false, failed = true), heard.next())
        assertTrue(heard.nothingFor(300))
    }

    @Test
    fun vlcIsPolledWithItsPasswordUntilItExits() {
        val status = AtomicReference("""{"state":"playing","length":30,"time":14,"position":0.5}""")
        val authorizations = CopyOnWriteArrayList<String?>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext(VlcHttpSession.STATUS_PATH) { exchange ->
            authorizations += exchange.requestHeaders.getFirst("Authorization")
            val body = status.get().encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val runtime = FakeRuntime(port = server.address.port)
            val heard = Heard()
            val media = playerMedia()
            assertEquals(PlayerAppLaunch.Launched(PlayerAppProgress.LIVE), launch(DesktopPlayerApps(windows, runtime), KnownPlayer.VLC.app(), media, heard))
            val command = runtime.commands.single()
            val password = command.single { it.startsWith("--http-password=") }.substringAfter('=')
            assertEquals(32, password.length)
            assertEquals(listOf(vlcExe) + PlayerAppCommands.vlc(media, server.address.port, password), command)

            assertEquals(Started, heard.next())
            assertEquals(Position(15_000, 30_000, paused = false), heard.next())
            status.set("""{"state":"paused","length":30,"time":14,"position":0.5}""")
            assertEquals(Position(15_000, 30_000, paused = true), heard.next())
            assertTrue(authorizations.all { it == VlcHttpSession.authorization(password) })

            runtime.processes.single().quit()
            assertEquals(Ended(15_000, 30_000, completed = false), heard.next())
            assertTrue(heard.nothingFor(1500), "Ended goes out once, and no status after it")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun vlcWhoseInterfaceNeverAnsweredStillEnds() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val runtime = FakeRuntime(port = port)
        val heard = Heard()
        launch(DesktopPlayerApps(windows, runtime), KnownPlayer.VLC.app(), playerMedia(), heard)
        runtime.processes.single().quit()
        assertEquals(Ended(null, null, completed = false), heard.next())
        assertTrue(heard.nothingFor(300))
    }

    @Test
    fun otherPlayersStartAndReportNothing() {
        val runtime = FakeRuntime()
        val heard = Heard()
        val media = playerMedia(subtitles = listOf(PlayerAppSubtitle("https://subs.example/eng.srt", local = false, title = null, language = "eng")))
        assertEquals(PlayerAppLaunch.Launched(PlayerAppProgress.NONE), launch(DesktopPlayerApps(windows, runtime), KnownPlayer.POTPLAYER.app(), media, heard))
        assertEquals(listOf(potExe) + PlayerAppCommands.potPlayer(media), runtime.commands.single())
        runtime.processes.single().quit()
        assertTrue(heard.nothingFor(300))
    }

    @Test
    fun aPlayerThatIsntHereOrDoesntStartFails() {
        val heard = Heard()
        val nothing = FakeHost(windows = true, environment = mapOf("PATH" to "C:\\tools"))
        val runtime = FakeRuntime()
        assertEquals(PlayerAppLaunch.Failed(PlayerAppFailure.NOT_INSTALLED), launch(DesktopPlayerApps(nothing, runtime), KnownPlayer.MPV.app(), playerMedia(), heard))
        assertEquals(PlayerAppLaunch.Failed(PlayerAppFailure.NOT_INSTALLED), launch(DesktopPlayerApps(windows, runtime), KnownPlayer.CELLULOID.app(), playerMedia(), heard))
        val unknown = PlayerApp("org.videolan.vlc", "VLC", PlayerAppKind.DETECTED, PlayerAppCapabilities.URL_ONLY)
        assertEquals(PlayerAppLaunch.Failed(PlayerAppFailure.NOT_INSTALLED), launch(DesktopPlayerApps(windows, runtime), unknown, playerMedia(), heard))
        assertTrue(runtime.commands.isEmpty())

        val failing = FakeRuntime(channel = FakeMpvChannel("p"), failing = true)
        assertEquals(PlayerAppLaunch.Failed(PlayerAppFailure.OTHER), launch(DesktopPlayerApps(windows, failing), KnownPlayer.MPV.app(), playerMedia(), heard))
        assertTrue((failing.channel as FakeMpvChannel).closed)
        assertNull(heard.events.poll())
    }

    @Test
    fun aCustomPlayerRunsItsProgramWithItsTemplate() {
        fun custom(command: String, arguments: String) =
            PlayerApp("${CUSTOM_PREFIX}1", "Play", PlayerAppKind.CUSTOM, PlayerAppCapabilities.URL_ONLY, CustomPlayer("1", "Play", command, arguments))
        val runtime = FakeRuntime()
        val heard = Heard()
        val media = playerMedia(startSeconds = 42.9)
        val apps = DesktopPlayerApps(windows, runtime)
        assertEquals(PlayerAppLaunch.Launched(PlayerAppProgress.NONE), launch(apps, custom("play", "--start={start} \"{url}\" --sub={subtitle}"), media, heard))
        assertEquals(listOf("C:\\tools\\play.exe", "--start=42", STREAM_URL), runtime.commands.single())
        assertEquals(PlayerAppLaunch.Failed(PlayerAppFailure.NOT_INSTALLED), launch(apps, custom("C:\\gone\\play.exe", "{url}"), media, heard))
        assertEquals(PlayerAppLaunch.Failed(PlayerAppFailure.OTHER), launch(DesktopPlayerApps(windows, FakeRuntime(failing = true)), custom("play", "{url}"), media, heard))
        assertTrue(heard.nothingFor(100))
    }
}
