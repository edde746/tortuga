package com.edde746.tortuga

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log as AndroidLog
import androidx.compose.ui.geometry.Rect
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.RootScreen
import com.edde746.tortuga.app.RootSession
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.mpv.MpvPlayerEngine
import com.edde746.tortuga.output.DeviceOutput
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.player.PlayerUiState
import com.edde746.tortuga.report.Report
import com.edde746.tortuga.report.toJson
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.StoreProbe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Test-only report (`report` extra): what the test harness asserts on instead of a human watching. JSON lines under
 * the [TAG] logcat tag; one longer than logcat's 4 KB entry limit, which would cut it (a Detail in Arabic or German:
 * two or three bytes a letter), goes in `chunk` lines instead ([log]), which the harness joins. The screens', the
 * engine's and the downloads' fields are the shared [Report]'s (the desktop harness writes the same), its lists cut to
 * [LIST_LIMIT]:
 * - `event`: the launch's `start` (device, form factor, display), activity events, app warnings and errors (redacted by
 *   [Log]), and a dev session's start failure;
 * - `sample`: every [SAMPLE_MS] while a playback runs: the player's status, the engine's state ([Report.engine]) and a
 *   set of mpv stats (video output, decoder, drops, current subtitle text);
 * - `tracks`: the engine's tracks ([Report.tracks]), whenever they change;
 * - `downloads`: whenever they change, every download on the device (the graph's manager, [Report.download]);
 * - `ui`: whenever it changes, its `t` the moment the main thread read it, where `focus` lines are logged (so a `ui`
 *   line newer than the last `focus` line has the focus since then): the screen on top and the back stack (or the
 *   account shell's screen), the focused control, the account shell ([Report.account]), the app's overlay, and what
 *   the screen on top shows (Board, Discover, Detail with what the library and the stream history hold for its title,
 *   Streams, Player with whether it plays a download, Library with its Downloads section, Search, Settings with
 *   Addons, Watch together);
 * - `probes`: whenever they change, where the named controls are ([LayoutProbe], screen pixels: what
 *   `adb shell input tap` takes, menus included), in parts of one `seq`;
 * - `focus`: the moment the focused control changes;
 * - `chunk`: a part (`part` of `parts`, in order, under one `seq`) of the `text` of a line of any of these that is over
 *   [LINE_BYTES].
 */
internal class TestReport(private val context: Context) {
    /** A dev session's app; the product's running app comes from [root]. */
    @Volatile var app: AppSession? = null

    @Volatile var root: RootSession? = null

    private val t0 = SystemClock.elapsedRealtime()
    private val main = Handler(Looper.getMainLooper())
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "test-report").apply { isDaemon = true } }
    private val report = Report(LIST_LIMIT)
    private val rects = ConcurrentHashMap<String, Rect>()
    @Volatile private var focused: String? = null
    private var lastTracks: JsonArray? = null
    private var lastUi: JsonObject? = null
    private var lastDownloads: JsonArray? = null
    private var lastProbes: String? = null
    private var probeSeq = 0
    /** The last `chunk` lines' `seq`: lines are logged from the main thread and the report's own. */
    private val chunkSeq = AtomicInteger()
    private var versionsLogged = false
    private var previousSink: Log.Sink? = null

    /** Where the named controls are and which one has focus. Called on the main thread. */
    val probe: LayoutProbe = object : LayoutProbe {
        override fun place(name: String, bounds: Rect?) {
            if (bounds == null || bounds.isEmpty) rects.remove(name) else rects[name] = bounds
        }

        override val onScreen: Boolean get() = true

        override fun focus(name: String, focused: Boolean) {
            when {
                focused && this@TestReport.focused != name -> this@TestReport.focused = name
                !focused && this@TestReport.focused == name -> this@TestReport.focused = null
                else -> return
            }
            log("focus", "focus" to this@TestReport.focused)
        }
    }

    /** The account screens' stores on screen, by name; main thread only. */
    private val shown = HashMap<String, Any>()

    /** Lists the account screens' stores in [shown] while they show. Called on the main thread. */
    val stores = StoreProbe { name, store ->
        if (store == null) {
            shown.remove(name)
        } else {
            shown[name] = store
        }
    }

    fun start(extras: LaunchExtras) {
        val display = context.resources.displayMetrics
        val features = (context.applicationContext as TortugaApplication).platform.deviceFeatures()
        val dev = extras.dev
        event(
            "start",
            mapOf(
                // The dev entry's media URL (the harness's media server); addon links stay out.
                "url" to dev?.url,
                "dev" to (dev?.dev == true),
                "devAddons" to (dev?.addons?.size ?: 0),
                "devKeep" to (dev?.data != null),
                "devKids" to (dev?.kids != null),
                "devOpen" to (dev?.open?.size ?: 0),
                "devProgress" to (dev?.progress?.size ?: 0),
                "mpvOptions" to extras.mpvOptions,
                "settings" to dev?.settings.orEmpty().associate { it.key to it.value },
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "sdk" to Build.VERSION.SDK_INT,
                "soc" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else null,
                "television" to isTelevision(context),
                "deviceFeatures" to mapOf("hdr" to features.hdr, "matchFrameRate" to features.matchFrameRate, "audioPassthrough" to features.audioPassthrough),
                // The movie route's own encodings (AudioDeviceInfo.getEncodings) beside what passthrough sends it.
                "sinkEncodings" to DeviceOutput.sinkEncodings(context).map { it.toList() },
                "passthroughCodecs" to DeviceOutput.passthroughCodecs(context),
                "display" to mapOf("w" to display.widthPixels, "h" to display.heightPixels, "density" to display.density),
            ),
        )
        val sink = Log.sink
        previousSink = sink
        Log.sink = Log.Sink { level, tag, message, error ->
            sink.write(level, tag, message, error)
            if (level >= Log.Level.WARN) event("log", mapOf("level" to level.name, "tag" to tag, "message" to message))
        }
        executor.scheduleWithFixedDelay({ runCatching(::sample).onFailure { AndroidLog.w(TAG, "sample failed", it) } }, 0, SAMPLE_MS, TimeUnit.MILLISECONDS)
    }

    /** An app or lifecycle event, logged at once with the playback position at that moment. */
    fun event(name: String, detail: Map<String, Any?> = emptyMap()) {
        log("event", "event" to name, *detail.toList().toTypedArray())
    }

    fun stop() {
        executor.shutdownNow()
        previousSink?.let { Log.sink = it }
        event("stop")
    }

    private fun ms(): Long = SystemClock.elapsedRealtime() - t0

    /**
     * What the app shows, read on the main thread at [at] ([ms]); [downloads] the device's downloads, null without;
     * [detailId] the title of the Detail screen on top, whose saved state is read off the main thread ([saved]).
     */
    private class Ui(
        val at: Long,
        val fields: Map<String, JsonElement>,
        val engine: MpvPlayerEngine?,
        val player: PlayerUiState?,
        val downloads: JsonArray?,
        val app: AppSession?,
        val detailId: String?,
    )

    private fun readUi(): Ui? {
        val task = FutureTask { ui() }
        main.post(task)
        return runCatching { task.get(2, TimeUnit.SECONDS) }.getOrNull()
    }

    private fun ui(): Ui {
        val tortuga = context.applicationContext as TortugaApplication
        val root = root
        val rootScreen = root?.screen?.value
        val app = if (rootScreen == null || rootScreen is RootScreen.Main) root?.app ?: app else null
        val navigator = app?.navigator
        val route = navigator?.current
        val playback = app?.playback?.active
        val store = playback?.store
        val player = store?.state?.value
        val fields = linkedMapOf(
            "screen" to toJson(route?.let { report.name(it) } ?: rootScreen?.let { report.name(it) } ?: "starting"),
            "backStack" to toJson(navigator?.backStack?.value.orEmpty().map { report.name(it) }),
            "sheet" to toJson(navigator?.layout?.sheet?.let { report.name(it) }),
            "focus" to toJson(focused),
            // The splash's status line while the product starts (null before half a second and once it is done).
            "startup" to toJson(root?.startup?.state?.value?.status),
            "pictureInPicture" to toJson(tortuga.platform.inPictureInPicture),
            "nowPlaying" to toJson(tortuga.platform.mediaControls.nowPlaying?.let(report::nowPlaying)),
            "account" to toJson(report.account(root, shown::get)),
            "overlay" to toJson(app?.state?.value?.overlay?.let(report::overlay)),
            "keepSyncedNotice" to toJson(app?.state?.value?.keepSyncedNotice),
            "menuNotice" to toJson(app?.state?.value?.menuNotice),
        )
        if (navigator != null && route != null) fields += report.screen(navigator, route)
        // The player, and whether it plays a download.
        if (store != null && player != null) {
            fields["player"] = JsonObject(report.player(player, store.timeline.value) + ("downloaded" to JsonPrimitive(playback?.session?.info?.value?.stream?.downloaded == true)))
        }
        val downloads = app?.graph?.downloads?.all?.value?.let { all -> JsonArray(all.map(report::download)) }
        return Ui(ms(), fields, playback?.engine as? MpvPlayerEngine, player, downloads, app, (route as? Route.Detail)?.id)
    }

    private fun sample() {
        val ui = readUi() ?: return
        val fields = if (ui.app != null && ui.detailId != null) ui.fields + ("saved" to saved(ui.app, ui.detailId)) else ui.fields
        val line = JsonObject(fields)
        if (line != lastUi) {
            lastUi = line
            log("ui", *line.toList().toTypedArray(), t = ui.at)
        }
        if (ui.downloads != null && ui.downloads != lastDownloads) {
            lastDownloads = ui.downloads
            log("downloads", "downloads" to ui.downloads)
        }
        logProbes()
        val engine = ui.engine ?: return
        if (!versionsLogged) {
            val versions = engine.properties(listOf("mpv-version", "ffmpeg-version"))
            if (versions.isNotEmpty()) {
                versionsLogged = true
                event("versions", versions)
            }
        }
        val s = engine.currentState
        val tracks = report.tracks(s)
        if (tracks != lastTracks) {
            lastTracks = tracks
            log("tracks", "tracks" to tracks)
        }
        val mpv = engine.properties(MPV_STATS) +
            // The value the engine observes: mpv's own read waits for the decoder thread, a second at a time while a
            // hardware decoder drains at the end of a paused file, and holds the engine's thread (and a closing player's
            // surface handoff) meanwhile.
            ("hwdec-current" to s.hwdec)
        log("sample", "status" to ui.player?.status?.name, *report.engine(s).toList().toTypedArray(), "mpv" to mpv)
    }

    /**
     * What the library and the stream history hold for Detail's title [id]: its watch state (the resume position and
     * duration in ms, the time watched, the watched marks, Continue Watching) and the stream last played for any of its
     * videos, by name (its URL stays out); the read's failure as `error`.
     */
    private fun saved(app: AppSession, id: String): JsonElement = runCatching {
        runBlocking {
            val item = app.graph.data.library.item(app.profileId, id)
            val stream = app.graph.data.streamHistory.latestForMeta(app.profileId, id)
            toJson(
                mapOf(
                    "id" to id,
                    "library" to item?.let { i ->
                        mapOf(
                            "type" to i.type, "removed" to i.removed, "temp" to i.temp, "videoId" to i.state.videoId,
                            "timeOffset" to i.state.timeOffset, "duration" to i.state.duration, "timeWatched" to i.state.timeWatched,
                            "timesWatched" to i.state.timesWatched, "flaggedWatched" to i.state.flaggedWatched,
                            "continueWatching" to i.isInContinueWatching,
                        )
                    },
                    "stream" to stream?.let { s -> mapOf("videoId" to s.videoId, "name" to s.stream?.name) },
                ),
            )
        }
    }.getOrElse { toJson(mapOf("id" to id, "error" to it.toString())) }

    /** The probes as `name: [left, top, width, height]`, in parts of [PROBES_PER_LINE] under one `seq`, when changed. */
    private fun logProbes() {
        val entries = rects.entries.sortedBy { it.key }.map { (name, r) ->
            name to listOf(r.left.roundToInt(), r.top.roundToInt(), r.width.roundToInt(), r.height.roundToInt())
        }
        val text = entries.joinToString { "${it.first}=${it.second}" }
        if (text == lastProbes) return
        lastProbes = text
        probeSeq++
        val parts = entries.chunked(PROBES_PER_LINE).ifEmpty { listOf(emptyList()) }
        parts.forEachIndexed { index, part ->
            log("probes", "seq" to probeSeq, "part" to index, "parts" to parts.size, "probes" to part.toMap())
        }
    }

    /**
     * One line of [type] at [t] (now by default) with [fields]; over [LINE_BYTES] (in UTF-8), its text in `chunk` lines
     * of one `seq` instead, each piece at most [PIECE_BYTES] once escaped ([pieces]).
     */
    private fun log(type: String, vararg fields: Pair<String, Any?>, t: Long = ms()) {
        val line = toJson(linkedMapOf("type" to type, "t" to t, *fields)).toString()
        if (line.encodeToByteArray().size <= LINE_BYTES) {
            AndroidLog.i(TAG, line)
            return
        }
        val seq = chunkSeq.incrementAndGet()
        val pieces = pieces(line)
        pieces.forEachIndexed { index, piece ->
            AndroidLog.i(TAG, toJson(linkedMapOf("type" to "chunk", "seq" to seq, "part" to index, "parts" to pieces.size, "text" to piece)).toString())
        }
    }

    /** [line] in pieces of at most [PIECE_BYTES] in UTF-8 once escaped as a JSON string, no surrogate pair split. */
    private fun pieces(line: String): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        var size = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            val pair = c.isHighSurrogate() && i + 1 < line.length
            val bytes = when {
                pair -> 4
                c == '"' || c == '\\' -> 2
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            if (size + bytes > PIECE_BYTES) {
                pieces += line.substring(start, i)
                start = i
                size = 0
            }
            size += bytes
            i += if (pair) 2 else 1
        }
        pieces += line.substring(start)
        return pieces
    }

    private companion object {
        const val TAG = "TortugaReport"
        const val SAMPLE_MS = 500L
        const val PROBES_PER_LINE = 40

        /** The longest line logged whole: logcat cuts an entry's message at about 4,050 bytes (a Detail). */
        const val LINE_BYTES = 3900

        /** The longest `chunk` text, escaped, leaving room for the line's other fields. */
        const val PIECE_BYTES = 3700

        /** How many entries of a screen's list a `ui` line carries. */
        const val LIST_LIMIT = 8

        val MPV_STATS = listOf(
            "current-vo", "hwdec", "demuxer-max-bytes", "demuxer-max-back-bytes", "video-codec",
            "video-params/pixelformat", "video-params/hw-pixelformat",
            "video-params/gamma", "video-params/primaries", "video-params/colormatrix", "video-target-params/gamma",
            "container-fps", "estimated-vf-fps", "display-fps", "frame-drop-count", "decoder-frame-drop-count",
            "vo-delayed-frame-count", "mistimed-frame-count", "sid", "secondary-sid", "aid", "current-tracks/sub/codec",
            "sub-text", "secondary-sub-text",
            "current-ao", "audio-spdif", "audio-codec-name", "audio-params/channel-count", "audio-params/samplerate",
            "osd-dimensions/w", "osd-dimensions/h", "osd-dimensions/mt", "osd-dimensions/mb", "sub-pos",
            "dwidth", "dheight", "panscan", "video-zoom", "video-aspect-override", "sub-use-margins", "sub-ass-force-margins",
            "vo-mediacodec-video-rect", "af", "audio-channels", "seekable", "partially-seekable", "file-format",
            "demuxer-via-network",
        )
    }
}
