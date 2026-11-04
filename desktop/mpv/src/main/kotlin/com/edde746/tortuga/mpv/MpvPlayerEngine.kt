package com.edde746.tortuga.mpv

import androidx.compose.ui.awt.ComposeWindow
import com.edde746.tortuga.log.Redactor
import com.edde746.tortuga.mpv.core.MpvCore
import com.edde746.tortuga.mpv.ffi.MpvFfi
import com.edde746.tortuga.mpv.ffi.mpv_event
import com.edde746.tortuga.mpv.ffi.mpv_event_end_file
import com.edde746.tortuga.mpv.ffi.mpv_event_log_message
import com.edde746.tortuga.mpv.ffi.mpv_event_property
import com.edde746.tortuga.mpv.os.utf8
import com.edde746.tortuga.mpv.video.VideoHost
import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.EngineError
import com.edde746.tortuga.playback.EngineErrorKind
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.HardwareDecoder
import com.edde746.tortuga.playback.LOUDNESS_NORMALIZATION_FILTER
import com.edde746.tortuga.playback.LoadRequest
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.playback.PlayerEngineListener
import com.edde746.tortuga.playback.SubtitleFrame
import com.edde746.tortuga.playback.SubtitleStyle
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.VOLUME_MAX
import com.edde746.tortuga.playback.VideoLayout
import com.edde746.tortuga.playback.mpv.MpvLifecycle
import com.edde746.tortuga.playback.mpv.MpvLoad
import com.edde746.tortuga.playback.mpv.MpvState
import com.edde746.tortuga.playback.passthroughSpdif
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * @param logFile mpv's debug log, redacted, for diagnostics and the remote harness ([MpvLogFile]); each engine starts
 *   it anew.
 * @param shaderCache where mpv keeps the GPU shaders it compiled (`gpu-shader-cache-dir`), created when missing; null
 *   for none (every playback compiles them again).
 * @param extraOptions more mpv options applied last, before mpv_initialize (e.g. `mute=yes` for unattended runs); they
 *   also win over a [LoadRequest]'s options of the same name.
 */
data class MpvEngineOptions(
    val logFile: Path? = null,
    val shaderCache: Path? = null,
    val extraOptions: Map<String, String> = emptyMap(),
)

/**
 * [PlayerEngine] on libmpv for Windows and Linux.
 *
 * One `mpv` thread owns the handle: every call is queued to it and it alternates between running queued commands and
 * `mpv_wait_event` (woken by `mpv_wakeup`). Listener callbacks run on that thread. The core is created once the
 * platform video host is ready, which needs the window: put [MpvVideoSurface] in the UI. Calls made before that (the
 * first [load], [setPaused], track selection, speed/delay/volume/mute, the cache wait, the subtitle style) are kept and
 * applied then. Each file's lifecycle and the state mpv's properties make are the shared [MpvLifecycle]'s, which the
 * thread feeds mpv's events; while the playback info shows ([setStatsWanted]) the thread also reads the info's stats once
 * a second. The engine's own settings (the cache wait, the subtitle style, the picture's layout, the audio options and
 * output) carry across files; [load] also brings the file's `hwdec` and demuxer cache ([LoadRequest.options]). The
 * layout's properties follow the output's shape (`osd-dimensions`), so a stretch keeps
 * filling a resized window. [release] stops the thread and the file, lets the video host go, and runs
 * `mpv_terminate_destroy` on a throwaway thread holding nothing but the handle, since it can block on network teardown
 * (stop the event loop, drop the video host, terminate); that thread first waits for the host to let go
 * of the core ([VideoHost.awaitUnbound]), and leaves the core alive when it never may be destroyed.
 */
class MpvPlayerEngine internal constructor(
    private val options: MpvEngineOptions,
    internal val host: VideoHost,
) : PlayerEngine {
    constructor(options: MpvEngineOptions = MpvEngineOptions()) : this(options, VideoHost.forCurrentOs())

    /** The hardware decoder of this OS's video output, which a file's `hwdec` names while hardware decoding is on. */
    val hardwareDecoder: HardwareDecoder get() = host.hardwareDecoder

    private val commands = LinkedBlockingQueue<() -> Unit>()
    private val coreLock = Any()
    private val terminated = CountDownLatch(1)
    private val log = System.getLogger(MpvPlayerEngine::class.java.name)

    @Volatile private var listener: PlayerEngineListener? = null
    @Volatile private var released = false

    /** The latest state ([MpvLifecycle.state]), readable from any thread. */
    @Volatile var currentState: EngineState = EngineState()
        private set

    // mpv-thread state. [core] is also read under [coreLock] to wake the thread.
    private var core: MpvCore? = null
    private var stopped = false
    private var videoReady = false
    /** Why mpv or its video output can't work (no core, no canvas, a host that failed): each [load] fails with it. */
    private var videoFailure: EngineError? = null
    private var pendingLoad: LoadRequest? = null
    /** The viewer's pause: mpv's, but while the lifecycle holds for a display switch ([holdForDisplay]). */
    private var desiredPause = false
    private val pendingTracks = linkedMapOf<TrackKind, Long?>()
    private val pendingSubtitles = mutableListOf<List<String>>()
    /**
     * Player settings (speed, delays, the cache wait, the subtitle style and lift, volume, mute) set before the core
     * exists, applied as soon as it does.
     */
    private val startupSettings = linkedMapOf<String, String>()

    /**
     * The latest subtitle style and the controls' lift, which `sub-pos` combines. mpv places text subtitles by the
     * window here (its default margins), so the lift is the controls' share of it ([SubtitleFrame.SCREEN]).
     */
    private var subtitleStyle = SubtitleStyle.DEFAULT
    private var controlsLift = 0.0

    /** The picture's layout, the output's shape (`osd-dimensions`) and the layout's properties as last written. */
    private var videoLayout = VideoLayout.DEFAULT
    private var surfaceAspect: Double? = null
    private val appliedLayout = HashMap<String, String>()

    /**
     * The audio options, applied as soon as the core exists; [audioDevice] null for mpv's own choice. Passthrough
     * ([setAudioPassthrough]) waits for the speed to be 1 ([playbackSpeed]); [appliedSpdif] is what mpv was last told.
     */
    private var audioNormalization = false
    private var audioChannels = AudioChannels.ORIGINAL
    private var audioDevice: String? = null
    private var audioPassthrough = false
    private var playbackSpeed = 1.0
    private var appliedSpdif = ""
    private var defaultColorspaceHint: String? = null
    /** The user's shader files, in order, as `glsl-shaders` ([setShaders]); kept for later files. */
    private var shaders = emptyList<String>()
    /** Each file's lifecycle and the state mpv's properties make; its hold is the display switch's ([holdForDisplay]). */
    private val lifecycle = MpvLifecycle()
    /** While true, a change keeps the state for the next publish ([takePendingBehindEndFile]). */
    private var holdingEmits = false
    /** The playback info shows ([setStatsWanted]): its stats are read when [nextStatsNanos] comes. */
    private var statsWanted = false
    private var nextStatsNanos = 0L

    private val thread = Thread(::run, "mpv").apply { isDaemon = true; start() }

    /** Called by [MpvVideoSurface] on the UI thread until it returns true (the window's canvas exists). */
    internal fun attach(window: ComposeWindow): Boolean = host.attach(window) { post(::onHostReady) }

    /** Starts mpv for a [host] that needs no window (the libmpv tests' `vo=null`); [attach] does it for the UI. */
    internal fun startWithoutWindow() {
        post(::onHostReady)
    }

    /**
     * [MpvVideoSurface] found no canvas for the video in its window within [waited] (Skiko's components changed): the
     * playback fails with that error instead of waiting forever.
     */
    internal fun attachTimedOut(waited: Duration) {
        post { if (core == null) failVideo("video host: the window's canvas wasn't found within $waited") }
    }

    override fun load(request: LoadRequest): Long {
        val id = lifecycle.nextLoadId()
        post {
            pendingSubtitles.clear()
            update {
                load(id)
                videoFailure?.let { fail(it) }
            }
            when {
                videoFailure != null -> {}
                videoReady -> startLoad(request)
                else -> pendingLoad = request
            }
        }
        return id
    }

    override fun setPaused(paused: Boolean) {
        post {
            desiredPause = paused
            val c = core
            when {
                // Held for a display switch: the release applies the choice.
                lifecycle.held -> update { hold(paused) }
                c != null -> logIfFailed("pause", c.setFlag("pause", paused))
                // Shown at once: the core starts with it, and reports it then.
                else -> update { flag("pause", paused) }
            }
        }
    }

    /**
     * Holds playback while the display switches mode ([held]), so the picture doesn't go blank under the sound: the
     * display matching's call, from any thread. mpv pauses and the state reports buffering, with [EngineState.paused]
     * the viewer's own; a pause or play asked for meanwhile ([setPaused]) is what the release leaves.
     */
    fun holdForDisplay(held: Boolean) {
        post {
            if (held == lifecycle.held) return@post
            core?.setFlag("pause", held || desiredPause)?.let { logIfFailed("pause", it) }
            update { if (held) hold(desiredPause) else endHold(desiredPause) }
        }
    }

    override fun seek(seconds: Double, exact: Boolean) {
        post {
            val c = core
            val pending = pendingLoad
            when {
                pending != null -> pendingLoad = pending.copy(startSeconds = seconds)
                c != null -> logIfFailed("seek", c.command("seek", num(seconds), if (exact) "absolute+exact" else "absolute+keyframes"))
            }
        }
    }

    /** Passthrough leaves before any speed but 1 and comes back after 1 ([aroundPassthrough]). */
    override fun setSpeed(speed: Double) {
        post {
            playbackSpeed = speed
            val c = core
            if (c != null) {
                aroundPassthrough(c) { logIfFailed("speed", c.setDouble("speed", speed)) }
            } else {
                startupSettings["speed"] = num(speed)
                update { double("speed", speed) }
            }
        }
    }

    override fun setSubtitleDelay(seconds: Double) {
        setting("sub-delay", seconds)
        post { property("secondary-sub-delay", num(seconds)) }
    }

    override fun setAudioDelay(seconds: Double) = setting("audio-delay", seconds)

    override fun setSubtitleLift(fraction: Double) {
        if (!fraction.isFinite()) return
        post {
            controlsLift = fraction
            property("sub-pos", num(subtitleStyle.position(controlsLift, SubtitleFrame.SCREEN)))
        }
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        post {
            subtitleStyle = style
            for ((name, value) in style.properties()) property(name, value)
            property("sub-pos", num(style.position(controlsLift, SubtitleFrame.SCREEN)))
        }
    }

    /** Sets a player property, or keeps it for the core while there is none (mpv thread). */
    private fun property(name: String, value: String) {
        val c = core
        if (c != null) logIfFailed(name, c.setString(name, value)) else startupSettings[name] = value
    }

    override fun setVideoLayout(layout: VideoLayout) {
        post {
            videoLayout = layout
            core?.let(::applyVideoLayout)
        }
    }

    /** The layout's properties for the output's shape, each written when it changes (mpv thread). */
    private fun applyVideoLayout(c: MpvCore) {
        for ((name, value) in videoLayout.properties(surfaceAspect)) {
            if (appliedLayout[name] == value) continue
            val rc = c.setString(name, value)
            logIfFailed(name, rc)
            if (rc >= 0) appliedLayout[name] = value
        }
    }

    override fun setAudioNormalization(enabled: Boolean) {
        post {
            audioNormalization = enabled
            core?.let { c -> aroundPassthrough(c) { applyAudioNormalization(c) } }
        }
    }

    private fun applyAudioNormalization(c: MpvCore) =
        logIfFailed("af", c.setString("af", if (audioNormalization) LOUDNESS_NORMALIZATION_FILTER else ""))

    override fun setShaders(paths: List<String>) {
        post {
            shaders = paths
            core?.let(::applyShaders)
        }
    }

    /**
     * `glsl-shaders` as [shaders]: cleared, then each path appended, so no path needs the list's separator (`;` on
     * Windows, `:` elsewhere) escaped (mpv thread).
     */
    private fun applyShaders(c: MpvCore) {
        logIfFailed("glsl-shaders", c.command("change-list", "glsl-shaders", "clr", ""))
        for (path in shaders) logIfFailed("glsl-shaders", c.command("change-list", "glsl-shaders", "append", path))
    }

    override fun setAudioChannels(channels: AudioChannels) {
        post {
            audioChannels = channels
            core?.let { c -> aroundPassthrough(c) { applyAudioChannels(c) } }
        }
    }

    private fun applyAudioChannels(c: MpvCore) {
        // Through auto-safe: the audio chain is rebuilt whatever mpv held before.
        logIfFailed("audio-channels", c.setString("audio-channels", AudioChannels.ORIGINAL.mpv))
        if (audioChannels != AudioChannels.ORIGINAL) logIfFailed("audio-channels", c.setString("audio-channels", audioChannels.mpv))
    }

    override fun setAudioDevice(name: String) {
        post {
            audioDevice = name
            core?.let { logIfFailed("audio-device", it.setString("audio-device", name)) }
        }
    }

    /**
     * The Passthrough setting (OutputPlan.audioPassthrough). Nothing tells the
     * desktop which formats its output takes undecoded, so mpv offers every one it can wrap for a receiver
     * ([PASSTHROUGH_CODECS]) and decodes the audio when the output doesn't open with the bitstream; `audio-exclusive`
     * gives the bitstream the device to itself. In effect only while the audio is neither normalized, mixed down to
     * stereo nor played at another speed than 1 ([passthroughSpdif]). Whether the playing audio goes out undecoded is
     * [EngineState.audioPassthrough].
     */
    fun setAudioPassthrough(enabled: Boolean) {
        post {
            audioPassthrough = enabled
            core?.let(::applyPassthrough)
        }
    }

    /**
     * Makes an audio change (mpv thread) with passthrough out of its way: it leaves before a change after which the
     * audio must be decoded and comes back only after one that ends that, so no bitstream reaches the loudness filter,
     * a downmix or another speed, even for a moment.
     */
    private fun aroundPassthrough(c: MpvCore, change: () -> Unit) {
        if (spdif().isEmpty()) applyPassthrough(c)
        change()
        applyPassthrough(c)
    }

    /** mpv's `audio-spdif` for the setting and the audio options now: empty while passthrough is off or waits. */
    private fun spdif(): String =
        if (audioPassthrough) passthroughSpdif(PASSTHROUGH_CODECS, audioNormalization, audioChannels, playbackSpeed) else ""

    /**
     * `audio-spdif`, and with it `audio-exclusive` (yes while there is a bitstream to send, mpv's default no
     * otherwise): WASAPI's exclusive mode, a PipeWire stream no other shares. Each write reopens
     * the audio output, so only a change is written (mpv thread).
     */
    private fun applyPassthrough(c: MpvCore) {
        val spdif = spdif()
        if (spdif == appliedSpdif) return
        appliedSpdif = spdif
        val exclusive = yesNo(spdif.isNotEmpty())
        logIfFailed("audio-spdif", c.setString("audio-spdif", spdif))
        logIfFailed("audio-exclusive", c.setString("audio-exclusive", exclusive))
        log.log(System.Logger.Level.INFO, "audio-spdif={0} audio-exclusive={1}", spdif.ifEmpty { "(none)" }, exclusive)
    }

    override fun setStatsWanted(wanted: Boolean) {
        post {
            statsWanted = wanted
            if (wanted) {
                readStats()
            } else {
                update { stats(null) }
            }
        }
    }

    override fun setVolume(volume: Double) = setting("volume", volume.coerceIn(0.0, VOLUME_MAX))

    override fun setMuted(muted: Boolean) {
        post {
            val c = core
            if (c != null) {
                logIfFailed("mute", c.setFlag("mute", muted))
            } else {
                startupSettings["mute"] = yesNo(muted)
                update { flag("mute", muted) }
            }
        }
    }

    /**
     * The display's refresh rate after the app switched the display's mode (display matching), or null for mpv's own
     * reading (`display-fps-override`). mpv reads the rate again only when its window's monitor changes or its window
     * gets WM_DISPLAYCHANGE, which Windows sends to top-level windows only; mpv's window here is a child window.
     */
    fun setDisplayFps(fps: Double?) {
        post {
            val value = String.format(Locale.ROOT, "%.6f", fps ?: 0.0)
            val c = core
            if (c != null) logIfFailed("display-fps-override", c.setString("display-fps-override", value)) else startupSettings["display-fps-override"] = value
        }
    }

    /**
     * Whether mpv may present HDR (OutputPlan.hdrOutput). Off, it keeps an SDR swapchain (`target-colorspace-hint=no`)
     * and tone-maps HDR video to SDR, even where the display's HDR is on; on, mpv's default (HDR passthrough where the
     * display is in HDR mode).
     */
    fun setHdrOutput(enabled: Boolean) {
        post {
            val c = core
            when {
                c != null -> logIfFailed("target-colorspace-hint", c.setString("target-colorspace-hint", if (enabled) defaultColorspaceHint ?: "auto" else "no"))
                enabled -> startupSettings.remove("target-colorspace-hint")
                else -> startupSettings["target-colorspace-hint"] = "no"
            }
        }
    }

    /**
     * Where SDR video's white sits in an HDR (PQ) output, in whole cd/m² (`hdr-reference-white`), or null for mpv's
     * 203. The desktop's own SDR white (Windows' SDR content brightness) shows SDR video as bright as the controls over
     * it and every other window; mpv 0.41 doesn't read it itself (a later mpv's `auto` does).
     */
    fun setSdrWhite(nits: Double?) {
        post {
            val value = nits?.let { Math.round(it).toString() } ?: "auto"
            val c = core
            if (c != null) logIfFailed("hdr-reference-white", c.setString("hdr-reference-white", value)) else startupSettings["hdr-reference-white"] = value
        }
    }

    /**
     * Sets a numeric player property, one of [MpvLifecycle.DOUBLES]. Once the core exists the observed property reports
     * the applied value; before that the value is kept for the new core and shown in the state right away.
     */
    private fun setting(property: String, value: Double) {
        post {
            val c = core
            if (c != null) {
                logIfFailed(property, c.setDouble(property, value))
            } else {
                startupSettings[property] = num(value)
                update { double(property, value) }
            }
        }
    }

    override fun selectTrack(kind: TrackKind, id: Long?) {
        post {
            val c = core
            if (c == null) pendingTracks[kind] = id else applyTrack(c, kind, id)
        }
    }

    override fun selectSecondSubtitle(id: Long?) {
        post { property("secondary-sid", id?.toString() ?: "no") }
    }

    override fun addSubtitle(url: String, title: String?, lang: String?) {
        post {
            // "auto" adds without changing the selection; selectTrack picks it.
            val args = listOf("sub-add", url, "auto", title.orEmpty(), lang.orEmpty())
            val c = core
            if (c == null || !lifecycle.isFileLoaded) pendingSubtitles += args else logIfFailed("sub-add", c.command(*args.toTypedArray()))
        }
    }

    override fun setListener(listener: PlayerEngineListener?) {
        this.listener = listener
        if (listener != null) {
            post {
                lifecycle.republish()
                emit()
            }
        }
    }

    override fun release() {
        listener = null
        post(::shutdown)
    }

    /** Waits until [release] has finished (mpv_terminate_destroy returned, or the core was left alive); false on timeout. */
    fun awaitReleased(timeout: Duration): Boolean = terminated.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    /**
     * Reads mpv properties as strings (null when unavailable), for diagnostics and test reports. Blocks until the
     * mpv thread answers; never call it from a listener callback.
     */
    fun properties(names: List<String>, timeoutMs: Long = 2000): Map<String, String?> {
        val result = CompletableFuture<Map<String, String?>>()
        val none = names.associateWith { null }
        if (!post { result.complete(core?.let { c -> names.associateWith { c.getString(it) } } ?: none) }) return none
        return runCatching { result.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(none)
    }

    // ---- mpv thread ----------------------------------------------------------------------------------------------

    private fun post(block: () -> Unit): Boolean {
        if (released) return false
        commands.add(block)
        synchronized(coreLock) { core?.wakeup() }
        return true
    }

    private fun run() {
        while (!stopped) {
            val c = core
            if (c == null) {
                runCommand(commands.take())
                continue
            }
            while (!stopped) runCommand(commands.poll() ?: break)
            if (stopped || core == null) continue
            handleEvent(c.waitEvent(waitTimeoutSeconds()))
            if (statsWanted && System.nanoTime() >= nextStatsNanos) readStats()
        }
    }

    /**
     * The playback info's stats, read now and next in [STATS_INTERVAL_NANOS] (mpv thread). They are polled rather than
     * observed: mpv re-reads the bitrates and the late-frame count on every tick for an observer.
     */
    private fun readStats() {
        val c = core ?: return
        nextStatsNanos = System.nanoTime() + STATS_INTERVAL_NANOS
        val display = MpvState.DisplayMode(
            width = c.getDouble("display-width")?.toInt(),
            height = c.getDouble("display-height")?.toInt(),
            refreshRate = c.getDouble("display-fps"),
        )
        val reading = MpvState.stats(text = c::getString, number = c::getDouble, display = display)
        update { stats(reading) }
    }

    private fun runCommand(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log.log(System.Logger.Level.ERROR, "mpv command failed", t)
        }
    }

    private fun onHostReady() {
        if (stopped || core != null) return
        val c = try {
            MpvCore.create(baseOptions() + host.options() + options.extraOptions + ("pause" to yesNo(desiredPause || lifecycle.held)), ::setUp)
        } catch (t: Throwable) {
            failVideo(t.message ?: t.toString())
            return
        }
        synchronized(coreLock) { core = c }
        for ((kind, id) in pendingTracks) applyTrack(c, kind, id)
        pendingTracks.clear()
        applyVideoLayout(c)
        if (audioNormalization) applyAudioNormalization(c)
        if (shaders.isNotEmpty()) applyShaders(c)
        if (audioChannels != AudioChannels.ORIGINAL) applyAudioChannels(c)
        applyPassthrough(c)
        audioDevice?.let { logIfFailed("audio-device", c.setString("audio-device", it)) }
        host.bind(c) { error -> post { onVideoReady(error) } }
    }

    /**
     * Readies a new core (mpv thread): its log file, the settings made before it, and the properties ([OBSERVED]) and
     * warnings this engine follows. An observation or a log level mpv refuses throws, which fails the core.
     */
    private fun setUp(c: MpvCore) {
        options.logFile?.let { MpvLogFile.start(c, it) }
        defaultColorspaceHint = c.getString("target-colorspace-hint")
        // As properties, so a value mpv rejects is logged like a live call instead of failing the core. Test and
        // diagnostic extraOptions win over them (a test's mute=yes stays muted).
        for ((name, value) in startupSettings) if (name !in options.extraOptions) logIfFailed(name, c.setString(name, value))
        startupSettings.clear()
        for ((i, p) in OBSERVED.withIndex()) c.observe(i.toLong(), p.first, p.second)
        c.requestLogMessages("warn")
    }

    /** The host's answer to [VideoHost.bind]: null once a file may load, else why video can't work, even after that. */
    private fun onVideoReady(error: String?) {
        if (stopped) return
        if (error != null) {
            failVideo("video output: $error", EngineErrorKind.DECODE)
            return
        }
        videoReady = true
        pendingLoad?.let(::startLoad)
        pendingLoad = null
    }

    /**
     * The file's values as properties ([MpvLoad]), then a plain loadfile: the files mpv opens from this one's playlist
     * keep them. A value or loadfile mpv refuses fails the load with mpv's reason, and nothing opens.
     */
    private fun startLoad(request: LoadRequest) {
        val c = core ?: return
        // The device's decoding and cache options are written only when they change: a `hwdec` write re-creates the
        // playing file's decoder. A test's own --mpv-opt of the same name wins.
        val decoding = MpvLoad.decodingOptions(request.options).filter { (name, value) -> name !in options.extraOptions && c.getString(name) != value }
        val values = decoding + MpvLoad.fileProperties(request) + ("http-header-fields" to MpvLoad.headerFields(request.headers))
        for ((name, value) in values) {
            val rc = c.setString(name, value)
            if (rc < 0) return fail("$name: ${MpvCore.errorString(rc)}", MpvState.errorKind(rc))
        }
        val rc = c.command("loadfile", request.url, "replace")
        if (rc < 0) fail("loadfile: ${MpvCore.errorString(rc)}", MpvState.errorKind(rc))
    }

    private fun applyTrack(c: MpvCore, kind: TrackKind, id: Long?) {
        val property = when (kind) {
            TrackKind.VIDEO -> "vid"
            TrackKind.AUDIO -> "aid"
            TrackKind.SUBTITLE -> "sid"
        }
        logIfFailed(property, c.setString(property, id?.toString() ?: "no"))
    }

    private fun handleEvent(ev: MemorySegment) {
        when (mpv_event.event_id(ev)) {
            MpvFfi.MPV_EVENT_PROPERTY_CHANGE() -> onProperty(mpv_event.reply_userdata(ev), mpv_event.data(ev))
            MpvFfi.MPV_EVENT_START_FILE() -> update { startFile() }
            MpvFfi.MPV_EVENT_FILE_LOADED() -> {
                update { fileLoaded() }
                val c = core
                if (c != null) for (args in pendingSubtitles) logIfFailed("sub-add", c.command(*args.toTypedArray()))
                pendingSubtitles.clear()
            }
            MpvFfi.MPV_EVENT_PLAYBACK_RESTART() -> update { playbackRestart() }
            MpvFfi.MPV_EVENT_END_FILE() -> {
                val end = mpv_event.data(ev)
                val reason = mpv_event_end_file.reason(end)
                val code = mpv_event_end_file.error(end)
                // The log lines that explain a failure wait behind its end-file: taken first, so the error goes out with them.
                val next = if (reason == MpvFfi.MPV_END_FILE_REASON_ERROR()) core?.let(::takePendingBehindEndFile) else null
                update { endFile(reason, code, MpvCore.errorString(code)) }
                next?.let(::handleEvent)
            }
            // mpv names the URL it failed to open ("Failed to open <url>."), and stream URLs carry API keys: the
            // line goes to the log redacted, to the lifecycle as it is (the HTTP status, the error it names).
            MpvFfi.MPV_EVENT_LOG_MESSAGE() -> {
                val m = mpv_event.data(ev)
                val text = mpv_event_log_message.text(m).utf8().orEmpty().trimEnd()
                update { logLine(text, error = mpv_event_log_message.log_level(m) <= LOG_LEVEL_ERROR) }
                log.log(System.Logger.Level.WARNING, "[{0}] {1}", mpv_event_log_message.prefix(m).utf8(), Redactor.redact(text))
            }
            MpvFfi.MPV_EVENT_SHUTDOWN() -> shutdown()
        }
    }

    /**
     * Takes what mpv has pending behind an error end-file, without blocking, up to the first event that starts or ends
     * a file, which comes back unhandled (null once nothing is pending); the state it changes goes out with the error,
     * not before it. mpv buffers a client's log lines apart from its events and hands out queued events and property
     * changes first (`mpv_wait_event`), so the lines explaining a failed open (FFmpeg's "HTTP error 404 Not Found"),
     * logged before the end-file was posted, still wait behind it when this thread was busy as the open failed (a
     * listener callback, a stats read). mpv-build's Android library takes them the same way.
     */
    private fun takePendingBehindEndFile(c: MpvCore): MemorySegment? {
        holdingEmits = true
        try {
            while (true) {
                val ev = c.waitEvent(0.0)
                when (mpv_event.event_id(ev)) {
                    MpvFfi.MPV_EVENT_NONE() -> return null
                    MpvFfi.MPV_EVENT_START_FILE(), MpvFfi.MPV_EVENT_FILE_LOADED(), MpvFfi.MPV_EVENT_PLAYBACK_RESTART(),
                    MpvFfi.MPV_EVENT_END_FILE(), MpvFfi.MPV_EVENT_SHUTDOWN() -> return ev
                    else -> handleEvent(ev)
                }
            }
        } finally {
            holdingEmits = false
        }
    }

    private fun onProperty(id: Long, p: MemorySegment) {
        val (name, _) = OBSERVED.getOrNull(id.toInt()) ?: return
        val data = mpv_event_property.data(p)
        val format = mpv_event_property.format(p)
        val string = if (format == MpvFfi.MPV_FORMAT_STRING() && data.address() != 0L) data.get(ValueLayout.ADDRESS, 0).utf8() else null
        when (name) {
            "osd-dimensions" -> {
                val aspect = MpvState.surfaceAspect(string)
                if (aspect != surfaceAspect) {
                    surfaceAspect = aspect
                    core?.let(::applyVideoLayout)
                }
            }
            "video-target-params" -> update { outputRange(MpvState.outputRange(string)) }
            else -> update {
                when {
                    format == MpvFfi.MPV_FORMAT_DOUBLE() && data.address() != 0L -> double(name, data.get(ValueLayout.JAVA_DOUBLE, 0))
                    format == MpvFfi.MPV_FORMAT_FLAG() && data.address() != 0L -> flag(name, data.get(ValueLayout.JAVA_INT, 0) != 0)
                    string != null -> text(name, string)
                    else -> unavailable(name)
                }
            }
        }
    }

    /** Applies [change] to the lifecycle (mpv thread) and publishes what is due ([emit]). */
    private inline fun update(change: MpvLifecycle.() -> Unit) {
        lifecycle.change()
        currentState = lifecycle.state
        emit()
    }

    /** Hands the listener what is due ([MpvLifecycle.publishable]), unless held behind an end-file ([holdingEmits]). */
    private fun emit() {
        if (holdingEmits) return
        lifecycle.publishable()?.let { listener?.onState(it) }
    }

    /** How long mpv_wait_event may block: until the next stats reading is due, else until woken. */
    private fun waitTimeoutSeconds(): Double =
        if (statsWanted) (nextStatsNanos - System.nanoTime()).coerceAtLeast(0) / 1e9 else -1.0

    private fun fail(message: String, kind: EngineErrorKind = EngineErrorKind.OTHER) {
        update { fail(EngineError(message, kind = kind)) }
    }

    /** Fails the playback for good: with no mpv or no video output, this load and every later one ([videoFailure]). */
    private fun failVideo(message: String, kind: EngineErrorKind = EngineErrorKind.OTHER) {
        val error = EngineError(message, kind = kind)
        videoFailure = error
        pendingLoad = null
        update { fail(error) }
    }

    private fun logIfFailed(what: String, rc: Int) {
        if (rc < 0) log.log(System.Logger.Level.WARNING, "mpv {0}: {1}", what, MpvCore.errorString(rc))
    }

    private fun shutdown() {
        if (stopped) return
        stopped = true
        released = true
        listener = null
        val c = core
        synchronized(coreLock) { core = null }
        if (c == null) {
            host.close()
            terminated.countDown()
            return
        }
        // Nothing plays on while the host lets go, however long that takes (a Linux window that doesn't draw).
        logIfFailed("stop", c.command("stop"))
        host.unbind(c)
        host.close()
        Thread({
            try {
                // Never under a render context still on the core: libmpv needs it freed first.
                if (host.awaitUnbound()) c.terminateDestroy()
            } finally {
                terminated.countDown()
            }
        }, "mpv-terminate").apply { isDaemon = true; start() }
    }

    /** The desktop's own startup options; [MpvCore.create] sets every engine's (`COMMON_MPV_OPTIONS`) before them. */
    private fun baseOptions(): Map<String, String> = linkedMapOf(
        // With no audio device that opens, mpv plays on as with ao=null.
        "audio-fallback-to-null" to "yes",
    ).apply {
        options.shaderCache?.let { put("gpu-shader-cache-dir", Files.createDirectories(it).toString()) }
    }

    private companion object {
        /** How often the playback info's stats are read while it shows. */
        const val STATS_INTERVAL_NANOS = 1_000_000_000L

        /** client.h's `MPV_LOG_LEVEL_ERROR`: a line at it or below (fatal) is an error. */
        const val LOG_LEVEL_ERROR = 20

        /**
         * Observed properties, [MpvLifecycle]'s and the desktop's own; the index is the reply_userdata. Node properties
         * come as their JSON text (MPV_FORMAT_STRING), which [MpvState] reads.
         */
        val OBSERVED: List<Pair<String, Int>> = MpvLifecycle.DOUBLES.map { it to MpvFfi.MPV_FORMAT_DOUBLE() } +
            MpvLifecycle.FLAGS.map { it to MpvFfi.MPV_FORMAT_FLAG() } +
            (MpvLifecycle.TEXTS + MpvLifecycle.AUDIO_DEVICE_TEXTS).map { it to MpvFfi.MPV_FORMAT_STRING() } +
            listOf(
                // The output's shape, which a stretched picture takes.
                "osd-dimensions" to MpvFfi.MPV_FORMAT_STRING(),
                // The colour space mpv renders to for the display: what the display gets (HDR or SDR).
                "video-target-params" to MpvFfi.MPV_FORMAT_STRING(),
            )

        fun num(v: Double) = String.format(Locale.ROOT, "%.3f", v)
        fun yesNo(v: Boolean) = if (v) "yes" else "no"

        /** Every format mpv wraps for a receiver, by its `audio-spdif` names: what passthrough offers here. */
        val PASSTHROUGH_CODECS = listOf("ac3", "eac3", "dts", "dts-hd", "truehd")
    }
}
