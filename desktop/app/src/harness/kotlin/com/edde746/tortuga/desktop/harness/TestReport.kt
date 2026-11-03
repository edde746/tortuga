package com.edde746.tortuga.desktop.harness

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import com.edde746.tortuga.app.AppOverlay
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.RootScreen
import com.edde746.tortuga.app.RootSession
import com.edde746.tortuga.desktop.players.PlayerAppsObserver
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.mpv.DesktopWindow
import com.edde746.tortuga.mpv.MpvPlayerEngine
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.players.PlayerAppBanner
import com.edde746.tortuga.playback.players.PlayerAppEvent
import com.edde746.tortuga.playback.players.PlayerAppLaunch
import com.edde746.tortuga.playback.session.PlaybackSession
import com.edde746.tortuga.presentation.board.BoardBody
import com.edde746.tortuga.presentation.board.BoardStore
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.player.NowPlaying
import com.edde746.tortuga.presentation.player.PlaybackStatus
import com.edde746.tortuga.presentation.player.PlayerUiState
import com.edde746.tortuga.presentation.player.TimelineState
import com.edde746.tortuga.presentation.streams.StreamsStore
import com.edde746.tortuga.report.Report
import com.edde746.tortuga.report.toJson
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.StoreProbe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.Dialog
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.JFileChooser
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Test-only report (the harness launcher's `--report`): what the remote harness asserts on instead of a human watching.
 *
 * Every [SAMPLE_MS] it records the screen on top and the back stack (or the account shell's screen and its stores:
 * sign-in, the profiles), the Streams, Board, Discover, Search, Detail, Library (with its Downloads section), Settings,
 * Addons and TV guide screens' states, the device's network and download queue, the player's state (as the Compose
 * player shows it), what the OS media controls show (NowPlaying) and the player engine's [EngineState] (tracks,
 * settings, video/audio info, hwdec, chapters), a set of mpv stats (video output, decoder asked for and in use, cache
 * limits, colour spaces, drops, display rate, current subtitle), the window geometry, the app's own dialogs on screen
 * (Swing's file choosers: their kind, the text in their name field, their filters and look) and where the named
 * controls are ([probe], window pixels), so the harness can click them. App actions are logged as [event]s (on Windows
 * also the display switches and the media controls' commands; the player apps' launches and what they report, a
 * position every five seconds or on a pause change), and so are the app's warnings and errors (redacted by [Log]).
 * Each sample, event, startup milestone and the final status is a record of the journal ([ReportJournal]), appended
 * and flushed as it happens, so a crash still leaves every record behind; the latest sample alone also goes to
 * `report.last.json` beside it (replaced atomically), for a harness that polls the screen during a long run. A sample
 * the UI thread can't answer within [UI_WAIT_MS] is a `sampleTimedOut` event instead. Stream URLs never appear in it;
 * stream rows are listed by name.
 */
internal class TestReport(private val path: Path) : PlayerAppsObserver {
    @Volatile var app: AppSession? = null

    /** The product's account shell; its running app, when there is one, is sampled like [app]. */
    @Volatile var root: RootSession? = null

    /** The account screens' stores on screen, by name ([stores]); the UI thread's only. */
    private val shown = HashMap<String, Any>()

    /** Lists the account screens' stores in [shown] while they show (the window's LocalStoreProbe). */
    val stores = StoreProbe { name, store ->
        if (store == null) {
            shown.remove(name)
        } else {
            shown[name] = store
        }
    }

    @Volatile var window: DesktopWindow? = null
    @Volatile var isFocused: () -> Boolean = { false }

    /** The window is the active one (its own popups can hold the keyboard focus meanwhile). */
    @Volatile var isActive: () -> Boolean = { false }

    /** The window is minimized: it presents nothing, and mpv's output reads as SDR meanwhile. */
    @Volatile var isMinimized: () -> Boolean = { false }

    /** The engine of the latest playback. */
    @Volatile private var engine: MpvPlayerEngine? = null
    private var engines = 0
    private val probes = ConcurrentHashMap<String, Rect>()

    /** The named control that has keyboard focus, as [probe] last heard; null while none has. */
    @Volatile private var focused: String? = null

    /**
     * Collects the positions of the controls the UI names ([com.edde746.tortuga.ui.probe.layoutProbe]) and which of them
     * has focus. An empty rect is a control that isn't placed (a lazy list keeps items it scrolled away composed, at zero
     * size): not on screen.
     */
    val probe = object : LayoutProbe {
        override fun place(name: String, bounds: Rect?) {
            if (bounds == null || bounds.isEmpty) probes.remove(name) else probes[name] = bounds
        }

        override fun focus(name: String, focused: Boolean) {
            if (focused) this@TestReport.focused = name else if (this@TestReport.focused == name) this@TestReport.focused = null
        }
    }

    private val t0 = System.nanoTime()
    private val marks = linkedMapOf<String, Long>()
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "test-report").apply { isDaemon = true } }

    /** The journal from [start] to [finish]; records outside them are dropped. */
    private var journal: ReportJournal? = null
    private val latest: Path = path.resolveSibling(path.fileName.toString().removeSuffix(".jsonl") + ".last.json")

    /** When the OS started the process (the launcher), in epoch ms: the startup milestones count from it. */
    private val processStart = ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }
        .orElse(ManagementFactory.getRuntimeMXBean().startTime)

    /**
     * Starts the journal at [path] (replacing an earlier run's), with the JVM's start and the milestones marked so far,
     * and the sampling. Called once this process is the app ([com.edde746.tortuga.desktop.SingleInstance]): a later
     * start that hands its link over must not touch the running app's journal.
     */
    @Synchronized fun start() {
        journal = ReportJournal(path)
        append("mark", markRecord("jvm", ManagementFactory.getRuntimeMXBean().startTime - processStart))
        marks.forEach { (name, nanos) -> append("mark", markRecord(name, sinceProcessStart(nanos))) }
        val sink = Log.sink
        Log.sink = Log.Sink { level, tag, message, error ->
            sink.write(level, tag, message, error)
            if (level >= Log.Level.WARN) event("log", mapOf("level" to level.name, "tag" to tag, "message" to message))
        }
        // On the report's thread, as each sample: reading the mpv library's path loads it.
        executor.execute { append("env", env()) }
        executor.scheduleAtFixedRate({ runCatching { sample() }.onFailure { it.printStackTrace() } }, 0, SAMPLE_MS, TimeUnit.MILLISECONDS)
        // Where each press arrives as AWT sees it (the component, its point, the screen point; a key's code and its
        // focus owner): a harness's input can be checked against what it aimed at.
        Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
            if (e is MouseEvent && e.id == MouseEvent.MOUSE_PRESSED) {
                val detail = mapOf(
                    "button" to e.button,
                    "component" to e.component?.javaClass?.simpleName,
                    "at" to listOf(e.x, e.y),
                    "screen" to listOf(e.xOnScreen, e.yOnScreen),
                )
                event("mousePressed", detail)
            } else if (e is KeyEvent && e.id == KeyEvent.KEY_PRESSED) {
                event("keyPressed", mapOf("key" to KeyEvent.getKeyText(e.keyCode), "code" to e.keyCode, "component" to e.component?.javaClass?.simpleName))
            }
        }, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.KEY_EVENT_MASK)
    }

    /** Milliseconds since the report started. */
    fun ms(): Long = (System.nanoTime() - t0) / 1_000_000

    /** First occurrence of a startup milestone, at [nanos] ([System.nanoTime]; now by default). */
    @Synchronized fun mark(name: String, nanos: Long = System.nanoTime()) {
        if (marks.putIfAbsent(name, nanos) == null && journal != null) append("mark", markRecord(name, sinceProcessStart(nanos)))
    }

    /** [nanos] ([System.nanoTime]) in ms since the OS started the process. */
    private fun sinceProcessStart(nanos: Long): Long = System.currentTimeMillis() - (System.nanoTime() - nanos) / 1_000_000 - processStart

    private fun markRecord(name: String, ms: Long) = linkedMapOf("name" to name, "ms" to ms)

    /**
     * The startup milestones a drawn frame shows, each marked at the next frame's start (within a frame interval of its
     * presentation): `firstFrame` (the window's first; the splash marks it when it draws before the shell replaces it),
     * `firstScreen` (past the account gate's start: sign-in, the profiles or the app), `board` (the app on the Board)
     * and `boardContent` (the Board with a Continue Watching card or a row's item).
     * Runs in the window's composition, on the UI thread, until the Board has content or [STARTUP_WATCH_NANOS] pass.
     */
    suspend fun watchStartup() {
        var seen = emptyList<String>()
        val until = System.nanoTime() + STARTUP_WATCH_NANOS
        while (System.nanoTime() < until) {
            withFrameNanos { }
            seen.forEach { mark(it) }
            if ("boardContent" in seen) return
            seen = shownMilestones()
        }
    }

    private fun shownMilestones(): List<String> {
        val root = root
        val rootScreen = root?.screen?.value
        val app = if (rootScreen == null) app else (rootScreen as? RootScreen.Main)?.app
        val shown = mutableListOf("firstFrame")
        if ((rootScreen != null && rootScreen != RootScreen.Starting) || app != null) shown += "firstScreen"
        val navigator = app?.navigator
        if (navigator?.current == Route.Board) {
            shown += "board"
            val board = (navigator.retained(Route.Board, BoardStore.KEY) as? BoardStore)?.state?.value
            if (board != null && (board.continueWatching.isNotEmpty() || (board.body as? BoardBody.Rows)?.rows.orEmpty().any { it.items.isNotEmpty() })) shown += "boardContent"
        }
        return shown
    }

    /** A playback started on [engine]. */
    fun onEngine(engine: MpvPlayerEngine) {
        this.engine = engine
        event("playback", mapOf("engine" to ++engines))
    }

    /** An app action, with the playback state at that moment and any extra detail. */
    @Synchronized fun event(name: String, detail: Map<String, Any?> = emptyMap()) {
        val s = engine?.currentState
        append(
            "event",
            linkedMapOf<String, Any?>(
                "t" to ms(), "wall" to System.currentTimeMillis(), "event" to name,
                "positionSeconds" to (s?.positionSeconds ?: 0.0), "paused" to (s?.paused ?: false),
            ) + detail,
        )
    }

    /** A player app's launch (`playerAppLaunch`): the player's id, `launched` with its progress, or why it failed. */
    override fun playerAppLaunched(playerId: String, launch: PlayerAppLaunch) {
        val result = when (launch) {
            is PlayerAppLaunch.Launched -> mapOf("result" to "launched", "progress" to launch.progress.name)
            is PlayerAppLaunch.Failed -> mapOf("result" to launch.failure.name)
        }
        event("playerAppLaunch", mapOf("player" to playerId) + result)
    }

    /** The last position logged per player app, so a live player's positions log every five seconds or on a pause change. */
    private val playerAppPositions = ConcurrentHashMap<String, PlayerAppEvent.Position>()

    /** What a player app reported (`playerApp`): `started`, a `position`, or how it `ended`. */
    override fun playerAppEvent(playerId: String, reported: PlayerAppEvent) {
        val detail: Map<String, Any?> = when (reported) {
            PlayerAppEvent.Started -> mapOf("kind" to "started")
            is PlayerAppEvent.Position -> {
                val last = playerAppPositions[playerId]
                if (last != null && last.paused == reported.paused && abs(reported.positionMillis - last.positionMillis) < PLAYER_APP_POSITION_STEP_MS) return
                playerAppPositions[playerId] = reported
                mapOf("kind" to "position", "positionMillis" to reported.positionMillis, "durationMillis" to reported.durationMillis, "playerPaused" to reported.paused)
            }
            is PlayerAppEvent.Ended -> {
                playerAppPositions.remove(playerId)
                mapOf("kind" to "ended", "positionMillis" to reported.positionMillis, "durationMillis" to reported.durationMillis, "completed" to reported.completed)
            }
        }
        event("playerApp", mapOf("player" to playerId) + detail)
    }

    /** Stops sampling and ends the journal with the run's [status]; the first status stands. */
    fun finish(status: String) {
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
        synchronized(this) {
            append("status", status)
            journal?.close()
            journal = null
        }
    }

    /** One journal record: `{"<kind>": <value>}`. */
    @Synchronized private fun append(kind: String, value: Any?) {
        journal?.append(JsonObject(mapOf(kind to toJson(value))))
    }

    /** What the UI shows, read on the UI thread. */
    private class UiState(
        val screen: String,
        val backStack: List<String>,
        /** The top entry when it is drawn as a sheet over the screen below it (`BackStackLayout`), else null. */
        val sheet: String?,
        /** The hand-off banner over the screens. */
        val playerApp: PlayerAppBanner?,
        val player: PlayerUiState?,
        /** The player's timeline ([com.edde746.tortuga.presentation.player.PlayerStore.timeline]): its position. */
        val timeline: TimelineState?,
        val nowPlaying: NowPlaying?,
        /** The player's playback session: the stream it plays and the video. */
        val session: PlaybackSession?,
        /** The Streams screen lists a stream (the `streamsReady` mark). */
        val streamsReady: Boolean,
        val engine: MpvPlayerEngine?,
        /** The account shell and the account screens' stores ([Report.account]). */
        val account: JsonObject,
        /** The app's overlay over its screens. */
        val overlay: AppOverlay?,
        /**
         * The shown screen's fields ([Report.screen]), and Settings' (`settings`, `addons`) wherever its session is
         * kept, on Settings' stack under another destination too.
         */
        val fields: Map<String, JsonElement>,
        /** What Keep synced queued on its own, shown over the screens but the player. */
        val keepSyncedNotice: String?,
        /** What came of a card menu's entry that couldn't be carried out, shown over the screens but the player. */
        val menuNotice: String?,
        /** The app's own dialogs on screen ([dialogFields]), read on the event thread like the stores. */
        val dialogs: List<Map<String, Any?>>,
    )

    private fun ui(): UiState? {
        val root = root
        val app = root?.app ?: app
        if (root == null && app == null) return null
        val task = FutureTask {
            val rootScreen = root?.screen?.value
            val main = rootScreen == null || rootScreen is RootScreen.Main
            val running = if (main) app else null
            val navigator = running?.navigator
            val route = navigator?.current
            val streams = (route as? Route.Streams)?.let { navigator.retained(it, StreamsStore.KEY) as? StreamsStore }?.state?.value
            val fields = navigator?.let {
                report.screen(it, it.current) + if (it.current == Route.Settings) emptyMap() else report.screen(it, Route.Settings)
            }.orEmpty()
            val playback = running?.playback?.active
            UiState(
                screen = if (route != null) report.name(route) else rootScreen?.let { report.name(it) }.orEmpty(),
                backStack = navigator?.backStack?.value?.map { report.name(it) }.orEmpty(),
                sheet = navigator?.layout?.sheet?.let { report.name(it) },
                playerApp = running?.state?.value?.playerApp,
                player = playback?.store?.state?.value,
                timeline = playback?.store?.timeline?.value,
                nowPlaying = playback?.controls?.nowPlaying,
                session = playback?.session,
                streamsReady = streams != null && streams.answered && streams.groups.any { it.streams.isNotEmpty() },
                engine = playback?.engine as? MpvPlayerEngine,
                account = report.account(root, shown::get),
                overlay = running?.state?.value?.overlay,
                fields = fields,
                keepSyncedNotice = running?.state?.value?.keepSyncedNotice,
                menuNotice = running?.state?.value?.menuNotice,
                dialogs = Window.getWindows().filterIsInstance<Dialog>().filter { it.isShowing }.map(::dialogFields),
            )
        }
        SwingUtilities.invokeLater(task)
        return try {
            task.get(UI_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // No sample reads it any more: the UI thread skips it unless it has started.
            task.cancel(false)
            throw e
        }
    }

    private fun sample() {
        val ui = try {
            ui()
        } catch (e: TimeoutException) {
            event("sampleTimedOut", mapOf("waitedMs" to UI_WAIT_MS))
            return
        }
        val engine = ui?.engine
        val s = engine?.currentState
        val player = ui?.player
        val timeline = ui?.timeline
        if (ui?.streamsReady == true) mark("streamsReady")
        if (ui?.screen == "player") mark("player")
        if (s?.loaded == true) mark("loaded")
        if ((s?.positionSeconds ?: 0.0) > 0.5) mark("playing")
        val stats = engine?.properties(MPV_STATS) ?: emptyMap()
        val w = window
        val graph = (root?.app ?: app)?.graph
        val state = s ?: EngineState()
        val sample = linkedMapOf<String, Any?>(
            "t" to ms(),
            "wall" to System.currentTimeMillis(),
            "screen" to ui?.screen,
            // The splash's status line while the product starts (null before half a second and once it is done).
            "startup" to root?.startup?.state?.value?.status,
            "backStack" to ui?.backStack,
            "sheet" to ui?.sheet,
            "playerApp" to ui?.playerApp?.let {
                linkedMapOf("text" to it.text, "busy" to it.busy, "playInTortuga" to it.playInTortugaText, "dismiss" to it.dismissText)
            },
            "status" to player?.status?.name,
            // The engine's state as Android's sample line has it: a stopped engine's before a playback starts.
            *report.engine(state).toList().toTypedArray(),
            "subtitles" to player?.subtitleTracks?.firstOrNull { it.selected }?.label,
            "failed" to (player?.status == PlaybackStatus.FAILED),
            "tracks" to report.tracks(state),
            "mpv" to stats,
            "player" to if (player != null && timeline != null) report.player(player, timeline) else null,
            "nowPlaying" to ui?.nowPlaying?.let(report::nowPlaying),
            // The stream the session plays: auto-play's pick (null when picked by hand or by binge group) and the video.
            "playing" to ui?.session?.let { session ->
                session.info.value.stream?.let {
                    linkedMapOf("name" to it.name, "addon" to it.addonName, "autoPlay" to it.autoPlay?.name, "videoId" to session.openedFile.value?.videoId)
                }
            },
            "streams" to ui?.fields?.get("streams"),
            "account" to ui?.account,
            "overlay" to ui?.overlay?.let(report::overlay),
            "detail" to ui?.fields?.get("detail"),
            "library" to ui?.fields?.get("library"),
            "continueWatching" to ui?.fields?.get("continueWatching"),
            "downloadsSection" to ui?.fields?.get("downloadsSection"),
            // The device's network as the app sees it (a dev session's `offline` switch included) and its downloads.
            "network" to graph?.network?.value?.let { linkedMapOf("online" to it.online, "metered" to it.metered) },
            "queue" to graph?.downloads?.all?.value?.map(report::download),
            "board" to ui?.fields?.get("board"),
            "calendar" to ui?.fields?.get("calendar"),
            "folder" to ui?.fields?.get("folder"),
            "discover" to ui?.fields?.get("discover"),
            "search" to ui?.fields?.get("search"),
            "settings" to ui?.fields?.get("settings"),
            "addons" to ui?.fields?.get("addons"),
            "watch" to ui?.fields?.get("watch"),
            "keepSynced" to ui?.fields?.get("keepSynced"),
            "keepSyncedNotice" to ui?.keepSyncedNotice,
            "menuNotice" to ui?.menuNotice,
            "guide" to ui?.fields?.get("guide"),
            "stored" to ui?.let { if ("settings" in it.fields) storedFields() else null },
            "probes" to probes.entries.sortedBy { it.key }.associate { (name, r) ->
                name to listOf(r.left.roundToInt(), r.top.roundToInt(), r.width.roundToInt(), r.height.roundToInt())
            },
            "focus" to focused,
            "window" to w?.let {
                linkedMapOf(
                    "frame" to it.frameRect(), "content" to runCatching { it.contentRect() }.getOrNull(), "monitor" to it.monitorRect(),
                    "fullscreen" to it.isFullscreen, "focused" to isFocused(), "active" to isActive(), "minimized" to isMinimized(),
                    "darkChrome" to it.hasDarkChrome(),
                )
            },
            "dialogs" to ui?.dialogs,
            "handles" to DesktopWindow.processHandleCounts().ifEmpty { null },
        )
        val element = toJson(sample)
        append("sample", element)
        // The latest sample alone beside the journal: a harness polling it needn't parse every sample so far.
        replace(latest, Json.encodeToString(JsonElement.serializer(), element))
    }

    /**
     * What the database holds for the Settings and Addons screens ([Report.stored]), read on this thread, off the UI;
     * an addon whose manifest doesn't read as `?`.
     */
    private fun storedFields(): JsonObject? {
        val app = root?.app ?: app ?: return null
        val data = app.graph.data
        return runCatching {
            runBlocking {
                report.stored(
                    data.deviceSettings.settings(),
                    data.settings.settings(app.profileId),
                    data.addons.addons(app.profileId).sortedBy { it.position }.map {
                        runCatching { Manifest.decode(it.manifestJson).name }.getOrDefault("?")
                    },
                )
            }
        }.getOrNull()
    }

    /** What the run ran on: the OS, the JVM, the interop and scale settings and the mpv library. */
    private fun env(): Map<String, Any?> = linkedMapOf(
        "os" to System.getProperty("os.name"),
        "java" to System.getProperty("java.runtime.version"),
        "vendor" to System.getProperty("java.vm.vendor"),
        "interopBlending" to System.getProperty("compose.interop.blending"),
        "uiScale" to DesktopWindow.uiScale,
        "mpvLibrary" to runCatching { com.edde746.tortuga.mpv.MpvNatives.library.toString() }.getOrNull(),
    )

    /** Writes [text] to [target] through a temporary file moved over it, so a reader never sees half a file. */
    private fun replace(target: Path, text: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.createDirectories(target.toAbsolutePath().parent)
        Files.writeString(tmp, text)
        // A reader holding the file open without delete sharing makes the replace fail on Windows; retry briefly.
        repeat(20) { attempt ->
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (e: java.io.IOException) {
                if (attempt == 19) throw e
                Thread.sleep(25)
            }
        }
    }

    private companion object {
        const val SAMPLE_MS = 500L

        /** How long a sample waits for the UI thread to read the screens' state. */
        const val UI_WAIT_MS = 2000L

        /** How long [watchStartup] waits for the Board's content. */
        const val STARTUP_WATCH_NANOS = 30_000_000_000L

        /** How far a player app's position moves between two logged ones (a pause change logs at once). */
        const val PLAYER_APP_POSITION_STEP_MS = 5000L

        val MPV_STATS = listOf(
            "mpv-version", "current-vo", "current-gpu-context", "current-ao", "hwdec", "hwdec-current",
            "demuxer-max-bytes", "demuxer-max-back-bytes", "video-codec",
            "video-params/pixelformat", "video-params/hw-pixelformat", "video-params/gamma", "video-params/primaries",
            "video-params/colormatrix", "video-params/sig-peak", "video-params/max-cll", "width", "height", "container-fps",
            "display-fps", "estimated-vf-fps", "frame-drop-count", "decoder-frame-drop-count", "vo-delayed-frame-count",
            "mistimed-frame-count", "audio-codec-name", "current-tracks/audio/codec-profile", "audio-params/channel-count",
            "sid", "secondary-sid", "current-tracks/sub/codec", "sub-text", "secondary-sub-text", "sub-start", "sub-end",
            "video-target-params/gamma", "video-target-params/primaries", "video-target-params/max-luma", "osd-dimensions/w",
            "osd-dimensions/h",
            "osd-dimensions/mt", "osd-dimensions/mb", "osd-dimensions/ml", "osd-dimensions/mr", "dwidth", "dheight", "panscan",
            "video-zoom", "video-aspect-override", "sub-ass-force-margins", "af", "audio-channels", "audio-device",
            "audio-spdif", "audio-exclusive", "audio-out-params/format", "glsl-shaders", "eof-reached",
        )

        /** The screens', the engine's and the downloads' fields and names, as Android's report writes them. */
        val report = Report()

        /**
         * A dialog of the app's own on screen: its title, its frame (AWT's pixels: physical on Linux, scaled on
         * Windows), whether it is the active window and its system chrome dark, and, for a Swing file chooser, its kind
         * (OPEN or SAVE), its look's UI class, the text in its name field (a save's proposed name), its filters (the one
         * in effect first) and the name field's rect, where the harness types a path. Call on the event thread.
         */
        fun dialogFields(d: Dialog): Map<String, Any?> {
            val chooser = descendants(d).filterIsInstance<JFileChooser>().firstOrNull()
            val field = chooser?.let { c -> descendants(c).filterIsInstance<JTextField>().firstOrNull { it.isShowing && it.isEditable } }
            return linkedMapOf(
                "title" to d.title,
                "frame" to listOf(d.x, d.y, d.width, d.height),
                "active" to d.isActive,
                "darkChrome" to DesktopWindow.hasDarkChrome(d),
                "chooser" to chooser?.let { c ->
                    linkedMapOf(
                        "type" to when (c.dialogType) {
                            JFileChooser.OPEN_DIALOG -> "OPEN"
                            JFileChooser.SAVE_DIALOG -> "SAVE"
                            else -> "CUSTOM"
                        },
                        "ui" to c.ui.javaClass.simpleName,
                        "fileName" to field?.text,
                        "filter" to c.fileFilter?.description,
                        "filters" to c.choosableFileFilters.map { it.description },
                        "nameField" to field?.let { f -> f.locationOnScreen.let { listOf(it.x, it.y, f.width, f.height) } },
                    )
                },
            )
        }

        private fun descendants(c: Container): Sequence<Component> =
            c.components.asSequence().flatMap { sequenceOf(it) + if (it is Container) descendants(it) else emptySequence() }
    }
}
