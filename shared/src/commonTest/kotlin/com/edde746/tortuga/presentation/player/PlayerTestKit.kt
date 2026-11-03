package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.input.InputKey
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.KeyModifiers
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.WatchCommand
import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.CustomShader
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.LoadRequest
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.playback.PlayerEngineListener
import com.edde746.tortuga.playback.ShaderFile
import com.edde746.tortuga.playback.ShaderFiles
import com.edde746.tortuga.playback.ShaderPreset
import com.edde746.tortuga.playback.ShaderRenderer
import com.edde746.tortuga.playback.SubtitleStyle
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.VideoFit
import com.edde746.tortuga.playback.VideoLayout
import com.edde746.tortuga.playback.session.PlayerSession
import com.edde746.tortuga.playback.session.SessionInfo
import com.edde746.tortuga.presentation.watch.PlayerWatchUi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * An engine that records the store's commands. With [echo] it applies them to the session's engine state the way
 * mpv reports property changes back; without, reports lag behind until the test sends them. With [keyframeSeconds] a
 * seek that isn't exact lands, as mpv's keyframe seeks do, on the keyframe at or before its target (keyframes that far
 * apart from the file's start).
 */
class FakeEngine(private val state: MutableStateFlow<EngineState>) : PlayerEngine {
    var echo = true
    var keyframeSeconds: Double? = null
    val paused = mutableListOf<Boolean>()
    val seeks = mutableListOf<Pair<Double, Boolean>>()
    val volumes = mutableListOf<Double>()
    val mutes = mutableListOf<Boolean>()
    val speeds = mutableListOf<Double>()
    val subtitleDelays = mutableListOf<Double>()
    val audioDelays = mutableListOf<Double>()
    val selections = mutableListOf<Pair<TrackKind, Long?>>()
    val secondSelections = mutableListOf<Long?>()
    val subtitleLifts = mutableListOf<Double>()
    val layouts = mutableListOf<VideoLayout>()
    val normalizations = mutableListOf<Boolean>()
    val channels = mutableListOf<AudioChannels>()
    val audioDevices = mutableListOf<String>()
    val statsWanted = mutableListOf<Boolean>()
    val shaders = mutableListOf<List<String>>()

    override fun setPaused(paused: Boolean) {
        this.paused += paused
        report { it.copy(paused = paused) }
    }

    override fun seek(seconds: Double, exact: Boolean) {
        seeks += seconds to exact
        val keyframes = keyframeSeconds
        val lands = if (exact || keyframes == null) seconds else floor(seconds / keyframes) * keyframes
        report { it.copy(positionSeconds = lands, eof = false) }
    }

    override fun setSpeed(speed: Double) {
        speeds += speed
        report { it.copy(speed = speed) }
    }

    override fun setSubtitleDelay(seconds: Double) {
        subtitleDelays += seconds
        report { it.copy(subtitleDelaySeconds = seconds) }
    }

    override fun setAudioDelay(seconds: Double) {
        audioDelays += seconds
        report { it.copy(audioDelaySeconds = seconds) }
    }

    override fun setSubtitleLift(fraction: Double) {
        subtitleLifts += fraction
    }

    override fun setSubtitleStyle(style: SubtitleStyle) = Unit

    override fun setVolume(volume: Double) {
        volumes += volume
        report { it.copy(volume = volume) }
    }

    override fun setMuted(muted: Boolean) {
        mutes += muted
        report { it.copy(muted = muted) }
    }

    override fun selectTrack(kind: TrackKind, id: Long?) {
        selections += kind to id
        // As mpv: the second subtitle's track can't be the first too.
        if (kind == TrackKind.SUBTITLE && state.value.tracks.any { it.kind == kind && it.id == id && it.secondary }) return
        report { s -> s.copy(tracks = s.tracks.map { if (it.kind == kind) it.copy(selected = it.id == id) else it }) }
    }

    override fun selectSecondSubtitle(id: Long?) {
        secondSelections += id
        // As mpv: the first subtitle's track can't be the second too.
        if (state.value.tracks.any { it.kind == TrackKind.SUBTITLE && it.id == id && it.selected }) return
        report { s -> s.copy(tracks = s.tracks.map { if (it.kind == TrackKind.SUBTITLE) it.copy(secondary = it.id == id) else it }) }
    }

    override fun setVideoLayout(layout: VideoLayout) {
        layouts += layout
    }

    override fun setAudioNormalization(enabled: Boolean) {
        normalizations += enabled
    }

    override fun setAudioChannels(channels: AudioChannels) {
        this.channels += channels
    }

    override fun setShaders(paths: List<String>) {
        shaders += paths
    }

    override fun setAudioDevice(name: String) {
        audioDevices += name
        report { it.copy(audioDevice = name) }
    }

    override fun setStatsWanted(wanted: Boolean) {
        statsWanted += wanted
    }

    override fun load(request: LoadRequest) = error("the session loads streams, not the store")

    override fun addSubtitle(url: String, title: String?, lang: String?) = error("the session adds subtitles, not the store")

    override fun setListener(listener: PlayerEngineListener?) = error("the session listens to the engine, not the store")

    override fun release() = error("the session releases the engine, not the store")

    private fun report(change: (EngineState) -> EngineState) {
        if (echo) state.update(change)
    }
}

class FakeSession(title: String = "Big Buck Bunny") : PlayerSession {
    override val engineState = MutableStateFlow(EngineState())
    override val info = MutableStateFlow(SessionInfo(title = title))
    var retries = 0
    var nextStreams = 0
    var nextEpisodes = 0
    var streamChoices = 0
    var errorVideoWatches = 0
    var previousEpisodes = 0
    val seeks = mutableListOf<Double>()
    val trackPicks = mutableListOf<Pair<TrackKind, Long?>>()
    val secondSubtitlePicks = mutableListOf<Long?>()
    val autoPlayNext = mutableListOf<Boolean>()
    val addedSubtitles = mutableListOf<String>()

    /** The live seeks asked for ([seekLive]): seconds behind the live edge. */
    val liveSeeks = mutableListOf<Double>()

    override fun retry() {
        retries++
    }

    override fun playNextStream() {
        nextStreams++
    }

    override fun chooseStream() {
        streamChoices++
    }

    override fun watchErrorVideo() {
        errorVideoWatches++
    }

    override fun playNextEpisode() {
        nextEpisodes++
    }

    override fun playPreviousEpisode() {
        previousEpisodes++
    }

    override fun onSeek(seconds: Double) {
        seeks += seconds
    }

    override fun onTrackSelected(kind: TrackKind, id: Long?) {
        trackPicks += kind to id
    }

    override fun onSecondSubtitleSelected(id: Long?) {
        secondSubtitlePicks += id
    }

    override fun addSubtitle(url: String) {
        addedSubtitles += url
    }

    override fun setAutoPlayNext(enabled: Boolean) {
        autoPlayNext += enabled
        info.update { it.copy(nextEpisode = it.nextEpisode?.copy(autoplay = enabled)) }
    }

    override fun seekLive(behindSeconds: Double) {
        liveSeeks += behindSeconds
    }
}

/**
 * A room as the player sees it ([PlayerWatch]): what this device may do in it; [seeks] are the seeks the room heard,
 * [presses] the plays and pauses it was asked for while the player [routesControls], [here] the player's stops on this
 * device alone and their ends ("stop", "resume"). A stop detaches the player (it no longer routes its controls) and
 * shows [PlayerWatchUi.pausedHere]; its end attaches it again, and [onResume] is the room catching it up.
 */
class FakeWatch(canControl: Boolean, canChangeEpisode: Boolean) : PlayerWatch {
    val seeks = mutableListOf<Double>()
    val presses = mutableListOf<String>()
    val rates = mutableListOf<Double>()
    val here = mutableListOf<String>()
    var onResume: () -> Unit = {}
    override var routesControls = false
    override var roomRate: Double? = null
    override var stoppedHere = false
    override val state = MutableStateFlow<PlayerWatchUi?>(
        PlayerWatchUi(
            title = "Watch together",
            room = null,
            startTitle = "Start",
            startChoices = emptyList(),
            indicator = null,
            pill = null,
            matching = null,
            prompt = null,
            offset = null,
            canControl = canControl,
            canChangeEpisode = canChangeEpisode,
            controlsLocked = "Only the host controls playback",
            pausedHere = null,
            busy = null,
            failure = null,
            dismissLabel = "Dismiss",
        ),
    )
    override val notices = MutableSharedFlow<String>()

    override fun stopHere() {
        here += "stop"
        stoppedHere = true
        routesControls = false
        state.update { it?.copy(pausedHere = "Paused on this device") }
    }

    override fun resumeHere() {
        here += "resume"
        stoppedHere = false
        routesControls = true
        state.update { it?.copy(pausedHere = null) }
        onResume()
    }

    override fun onLocalSeek(seconds: Double) {
        seeks += seconds
    }

    override fun onLocalRate(rate: Double) {
        rates += rate
    }

    override fun play() {
        presses += "play"
    }

    override fun pause() {
        presses += "pause"
    }

    override fun execute(command: WatchCommand) {}
}

/** This device's stored player choices, in memory: [choices] emits what the store saves, as the settings would. */
class FakePreferences(stored: PlayerChoices) : PlayerPreferences {
    override val choices = MutableStateFlow(stored)

    override fun setVideoFit(fit: VideoFit) {
        choices.update { it.copy(videoFit = fit) }
    }

    override fun setAudioNormalization(enabled: Boolean) {
        choices.update { it.copy(audioNormalization = enabled) }
    }

    override fun setAudioChannels(channels: AudioChannels) {
        choices.update { it.copy(audioChannels = channels) }
    }

    override fun setShaderPreset(id: String) {
        choices.update { it.copy(shaderPreset = id) }
    }

    override fun addCustomShader(shader: CustomShader) {
        choices.update { it.copy(customShaders = it.customShaders + shader, shaderPreset = shader.id) }
    }

    override fun removeCustomShader(shader: CustomShader) {
        choices.update { choices ->
            choices.copy(
                customShaders = choices.customShaders - shader,
                shaderPreset = if (choices.shaderPreset == shader.id) ShaderPreset.Off.id else choices.shaderPreset,
            )
        }
    }
}

/**
 * The shaders' files of a device: the app's own under `/app/shaders/`, the imported ones under `/custom/`, numbered as
 * imported ([imported] holds their sources, [deleted] their deleted names), run by [renderer]. [missing] files have no
 * path: [paths] fails.
 */
class FakeShaderFiles(override val renderer: ShaderRenderer = ShaderRenderer.LIBPLACEBO) : ShaderFiles {
    val imported = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val missing = mutableSetOf<ShaderFile>()

    override suspend fun paths(files: List<ShaderFile>): List<String> = files.map { file ->
        require(file !in missing) { "No shader $file" }
        when (file) {
            is ShaderFile.Bundled -> "/app/shaders/${file.path}"
            is ShaderFile.Custom -> "/custom/${file.fileName}"
        }
    }

    override suspend fun import(source: String): String {
        imported += source
        return "shader-${imported.size}.glsl"
    }

    override suspend fun delete(fileName: String) {
        deleted += fileName
    }
}

class FakeHost : PlayerHost {
    var closed = 0
    val fullscreenRequests = mutableListOf<Boolean>()
    override var levels: DeviceLevels? = null

    override fun close() {
        closed++
    }

    override fun setFullscreen(fullscreen: Boolean) {
        fullscreenRequests += fullscreen
    }
}

/**
 * A store on the test's virtual clock, with fakes around it; the device had [stored] as its player choices. The store
 * measures time with [timeSource], the virtual clock's unless the test gives its own. Every action runs what it
 * scheduled for "now".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerHarness(
    private val scope: TestScope,
    config: PlayerConfig,
    stored: PlayerChoices = PlayerChoices(),
    val timeSource: TimeSource = scope.testScheduler.timeSource,
    /** The device's shaders' files; null where the player offers no shaders. */
    val shaderFiles: FakeShaderFiles? = null,
    importsShaders: Boolean = false,
) {
    val session = FakeSession()
    val engine = FakeEngine(session.engineState)
    val host = FakeHost()
    val preferences = FakePreferences(stored)
    val store = PlayerStore(engine, session, host, preferences, config, scope.backgroundScope, timeSource, shaderFiles, importsShaders)

    val state: PlayerUiState get() = store.state.value
    val timeline: TimelineState get() = store.timeline.value

    fun engineState(change: (EngineState) -> EngineState) {
        session.engineState.update(change)
        scope.runCurrent()
    }

    fun sessionInfo(change: (SessionInfo) -> SessionInfo) {
        session.info.update(change)
        scope.runCurrent()
    }

    /** The device's stored choices change (another screen, a development setting). */
    fun choices(change: (PlayerChoices) -> PlayerChoices) {
        preferences.choices.update(change)
        scope.runCurrent()
    }

    /** The file is open and playing at [position] of [duration]. */
    fun playing(position: Double = 100.0, duration: Double = 600.0) = engineState {
        it.copy(loaded = true, positionSeconds = position, durationSeconds = duration, paused = false, buffering = false, eof = false)
    }

    fun position(seconds: Double) = engineState { it.copy(positionSeconds = seconds) }

    fun dispatch(command: PlayerCommand) {
        store.dispatch(command)
        scope.runCurrent()
    }

    fun input(event: InputEvent): Boolean = store.onInput(event).also { scope.runCurrent() }

    fun press(key: InputKey, modifiers: KeyModifiers = KeyModifiers.NONE): Boolean {
        val handled = input(InputEvent.KeyDown(key, modifiers))
        input(InputEvent.KeyUp(key))
        return handled
    }

    fun advance(duration: Duration) {
        scope.advanceTimeBy(duration)
        scope.runCurrent()
    }

    /** Runs [block] in the background, the way the watch-together SDK calls the store's adapter, up to its first wait. */
    fun <T> start(block: suspend () -> T): Deferred<T> = scope.backgroundScope.async { block() }.also { scope.runCurrent() }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun playerTest(
    profile: InputProfile = InputProfile.DESKTOP,
    config: PlayerConfig = PlayerConfig.defaults(profile, doubleClickIntervalMillis = 500),
    preferences: PlayerChoices = PlayerChoices(),
    timeSource: TimeSource? = null,
    shaderFiles: FakeShaderFiles? = null,
    importsShaders: Boolean = false,
    body: PlayerHarness.() -> Unit,
) = runTest {
    val harness = PlayerHarness(this, config, preferences, timeSource ?: testScheduler.timeSource, shaderFiles, importsShaders)
    runCurrent()
    harness.body()
}
