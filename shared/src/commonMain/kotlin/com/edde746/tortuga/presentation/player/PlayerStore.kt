package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.data.settings.SkipMode
import com.edde746.tortuga.input.InputContext
import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.input.InputKey
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.Keymap
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.input.RoomControl
import com.edde746.tortuga.input.isRepeatable
import com.edde746.tortuga.input.roomControl
import com.edde746.tortuga.input.seeks
import com.edde746.tortuga.input.seeksLive
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.Chapter
import com.edde746.tortuga.playback.CustomShader
import com.edde746.tortuga.playback.DynamicRange
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.playback.ShaderFile
import com.edde746.tortuga.playback.ShaderFiles
import com.edde746.tortuga.playback.ShaderPreset
import com.edde746.tortuga.playback.files
import com.edde746.tortuga.playback.runsCustomShaders
import com.edde746.tortuga.playback.runsOn
import com.edde746.tortuga.playback.SkipSegment
import com.edde746.tortuga.playback.SkipSegments
import com.edde746.tortuga.playback.Track
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.VideoFit
import com.edde746.tortuga.playback.VideoLayout
import com.edde746.tortuga.playback.session.LiveWindow
import com.edde746.tortuga.playback.session.NextEpisode
import com.edde746.tortuga.playback.session.PlayerSession
import com.edde746.tortuga.playback.session.SessionInfo
import com.edde746.tortuga.playback.subtitles.ExternalSubtitle
import com.edde746.tortuga.presentation.watch.PlayerWatchUi
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.watch.RoomPlayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import party.openwatch.PlayerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The player screen's store: its [state], and apart from it the [timeline], which moves with the position several
 * times a second while the video plays, so that only the views drawing it follow it; both driven by the [session]'s
 * engine state and info, and intents from the views ([dispatch]) and the platform ([onInput],
 * [onTimelineFocusChanged], [onFullscreenChanged]).
 *
 * - Controls hide after [PlayerConfig.autoHideDelayMillis] without input while playing. They stay up while paused,
 *   scrubbing, loading, ended, failed, with a menu open or with the mouse on them, and while an assistive technology
 *   that moves through them one at a time runs ([onAssistiveTechnologyChanged]).
 * - Scrubbing moves a preview without seeking; releasing commits it with one exact seek. Remote scrubbing accelerates
 *   while the key is held and commits after a pause ([ScrubConfig]).
 * - A held seek key (an arrow, a keyboard's or a remote's fast forward and rewind) seeks exactly as it goes down; its
 *   auto-repeat only moves the target the timeline shows, and one exact seek goes there as it comes up ([holdSeek]).
 * - A click waits for the double-click interval when the keymap gives double clicks a meaning, so a double click
 *   toggles fullscreen without pausing twice. With a mouse a click pauses or shows and hides the controls, as the
 *   profile has it ([SessionInfo.videoClick]).
 * - On a touch screen a swipe up or down the video changes the screen's brightness or the system's volume through
 *   [PlayerHost.levels], whose new level shows in the OSD's place ([PlayerUiState.level]), while this device has swipes
 *   on; a finger held on the video plays at 2× until it lifts, which gives back the speed it had ([holdSpeed]): not live,
 *   and in a room only where this device controls playback, the room's speed changing for everyone meanwhile.
 * - On a touch screen Lock screen in the options menu ([PlayerCommand.LockScreen]) locks the screen against stray
 *   touches ([PlayerUiState.screenLock]): the menu and the controls hide and stay hidden, and no tap,
 *   double tap, swipe, pinch or hold on the video acts. A tap shows the unlock button for [UNLOCK_PROMPT], or hides
 *   it; a tap on it arms it and a tap on the armed button unlocks and shows the controls ([PlayerCommand.UnlockPress]);
 *   a second finger hides it. Keys, the system's media controls and picture in picture work as ever, Back leaves the
 *   player, and the lock lasts through the next episodes until the player closes.
 * - Up next ([UpNextPrompt]) comes up where the video's credits start (from its chapters, [SkipSegments]) and otherwise
 *   the profile's countdown ([NextEpisode.countdownSeconds]) before the end, as one button with the next episode's code
 *   and name; with the session's [NextEpisode.autoplay] on it counts down played time and then plays the next episode,
 *   and it only offers it otherwise.
 * - An episode of a series has previous and next episode buttons ([PlayerUiState.episodes]), both always there, each
 *   disabled without an episode that way; in a room only the host changes the episode.
 * - Intros, recaps and credits from the chapters get a skip button ([SkipPrompt]) as the profile has them
 *   ([SessionInfo.skipping]): shown for a few seconds as they start and with the controls, or counting down to an
 *   automatic skip, at most once per segment and playback. The skip seeks exactly to the segment's end. Credits to the
 *   end of an episode with a next one are up next's button: counting down the skip's 3 s when they skip automatically
 *   (with auto-play on), else as up next does. In a room, only those who may control the playback see the skip button,
 *   and only the host skips on its own.
 * - The skip button and up next are one prompt button ([PromptButton], [PlayerUiState.prompt]), one at a time. Any input
 *   on the player stops its countdown and leaves the plain button: every [onInput] event (taps, clicks, pointer moves,
 *   wheel, pinches, keys, a remote's presses, focus moves) and every command from the views but [PlayerCommand.CloseMenu]
 *   ([dispatch]: buttons, menus, the seek bar's scrubs, the system's media controls). For up next, and for credits
 *   counting down to the next episode, that cancels the auto-play for the rest of the file.
 * - Play at the end of the file (ended, or the end of the video reached by the sleep timer) starts it over.
 * - Failures come from the session; the error card's primary button is the next stream for a link that is gone and
 *   after a retry that failed too, until the stream plays, and Retry otherwise ([PlayerStates.error]).
 *   Over it the controls are the title and Back alone ([PlayerUiState.showsPlaybackControls]): a failure closes the
 *   menu and the info, and neither opens again until a retry or the next stream takes the error away.
 * - While the controls show, subtitles sit above them: the view reports how much of the player they cover
 *   ([onControlsHeightChanged]) and the engine lifts subtitles by as much.
 * - The options ([PlayerUiState.options]): the picture's fit and zoom ([VideoLayout]; a pinch zooms on touch screens,
 *   and a picture-in-picture window always shows the whole picture, [onPictureInPictureChanged]), the shaders
 *   ([ShaderPreset], where the platform has [shaderFiles]), the sleep timer ([SleepTimer]), auto-play of the next
 *   episode, the audio output, loudness normalization and the audio channels. The fit, the shader preset, the imported
 *   shaders, normalization and channels are this device's [preferences] and carry over to the next video;
 *   the zoom, the sleep timer and the shader toggle's off ([PlayerCommand.ToggleShaders]) last as long as the player,
 *   through its next episodes. NVScaler doesn't run on HDR video ([files]).
 * - The display stays awake ([PlayerUiState.keepAwake]) while the video plays, or opens or buffers unpaused, on screen
 *   or in picture in picture ([onVisibleChanged], [onPictureInPictureChanged]). Pausing, the end, a failure and
 *   closing let it sleep at once: the OS's idle timer counts from the viewer's last input, so a pause by hand doesn't
 *   darken the screen before the OS's usual delay.
 *
 * Engine commands go straight to [engine] (the session's engine; the store never loads or releases it). The timers run
 * on the scope's dispatcher and measure time with the time source, so tests drive them with virtual time. Call
 * everything on the main thread.
 */
class PlayerStore(
    private val engine: PlayerEngine,
    private val session: PlayerSession,
    private val host: PlayerHost,
    private val preferences: PlayerPreferences,
    private val config: PlayerConfig,
    parentScope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** The shaders' files on this device; null where the player offers no shaders. */
    private val shaderFiles: ShaderFiles? = null,
    /** The Shaders page imports a shader through the system's open dialog ([PlayerOption.importLabel]). */
    private val importsShaders: Boolean = false,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val keymap = Keymap.forProfile(config.profile, config.steps)

    private var engineState: EngineState = session.engineState.value
    private var info: SessionInfo = session.info.value

    private var controlsShown = true
    private var pointerOverControls = false
    /** An assistive technology that moves through the controls one at a time runs ([onAssistiveTechnologyChanged]). */
    private var assistiveTechnology = false
    private var autoHideJob: Job? = null
    private var menu: PlayerMenu? = null
    /** The language of [PlayerMenu.SUBTITLE_LANGUAGE]'s page ([SubtitleLanguage.key]). */
    private var subtitleLanguage: String? = null
    private var infoVisible = false
    private var timelineFocused = false
    private var fullscreen = false
    /** How much of the player's height the bottom controls cover (the view measures it), and the lift last applied. */
    private var controlsHeight = 0.0
    private var subtitleLift = 0.0

    private val scrubber = Scrubber(scope, timeSource, config.scrub, ::change, ::onScrubCommitted)

    private var pendingSeek: Double? = null
    private var pendingSeekJob: Job? = null
    /** A held seek key's target, while its repeats move it, and the key ([holdSeek]). */
    private var heldSeek: Double? = null
    private var heldSeekKey: InputKey? = null
    private var heldSeekJob: Job? = null
    /** The timeline's [TimelineState.motion], and the position it moves on from ([playheadMotion]). */
    private var motion: PlayheadMotion? = null
    private var motionFrom = 0.0
    private val paused = Pending<Boolean>()
    private val muted = Pending<Boolean>()
    private val volume = Pending.ofDouble()
    private val speed = Pending.ofDouble()
    private val subtitleDelay = Pending.ofDouble()
    private val audioDelay = Pending.ofDouble()
    private val audioTrack = Pending<TrackChoice>()
    private val subtitleTrack = Pending<TrackChoice>()
    private val secondSubtitleTrack = Pending<TrackChoice>()
    private val audioDevice = Pending<String>()

    // The options. The engine starts at the defaults; [applyOptions] sends what differs from what it has.
    private var fit = VideoFit.CONTAIN
    private var zoom = 1.0
    private val mutablePictureInPicture = MutableStateFlow(false)
    private val pictureInPicture: Boolean get() = mutablePictureInPicture.value
    /** The player is on screen: not minimized, hidden or in the background ([onVisibleChanged]). */
    private var visible = true
    private var appliedLayout = VideoLayout.DEFAULT
    private var normalization = false
    private var appliedNormalization = false
    private var channels = AudioChannels.ORIGINAL
    private var appliedChannels = AudioChannels.ORIGINAL
    /** The shader preset stored ([PlayerChoices.shaderPreset]), the imported shaders, and the preset running. */
    private var savedShader = ShaderPreset.Off.id
    private var customShaders = emptyList<CustomShader>()
    private var shader: ShaderPreset = ShaderPreset.Off
    /** The files the engine was last given, and the job resolving their paths. */
    private var appliedShaderFiles = emptyList<ShaderFile>()
    private var shaderJob: Job? = null
    /** Whether the engine reads the info's stats ([PlayerEngine.setStatsWanted]): while the info shows. */
    private var statsWanted = false

    /** The zoom a pinch started from, while one lasts; it zooms once it has clearly moved ([onPinch]). */
    private var pinchStart: Double? = null
    private var pinchMoves = 0
    private var pinchZooming = false
    /** Swipes change the brightness and the volume ([PlayerChoices.swipeGestures]). */
    private var swipeGestures = true

    /** The speed a finger held on the video replaced with [HOLD_SPEED], while it is held ([holdSpeed]). */
    private var heldSpeedFrom: Double? = null

    /** The screen lock's unlock button while the lock is on ([lockScreen]); null unlocked. */
    private var lock: UnlockStage? = null
    /** Hides the unlock button [UNLOCK_PROMPT] after it showed or was armed. */
    private var lockJob: Job? = null

    private val sleep = SleepTimer(scope, timeSource, config.sleepTimerMinuteSeconds, ::onSleepSecond)

    private val clicks = ClickArbiter(scope, config.doubleClickInterval, ::execute)

    /** Keys whose key-down the store handled: their key-up is consumed too. */
    private val consumedKeys = mutableSetOf<InputKey>()

    private val upNext = UpNextPrompt(scope, timeSource, onTimer = { update {} }, onStep = ::onUpNextStep)
    private val skip = SkipPrompt(scope, timeSource, onTimer = { update {} }, onTick = ::onSkipTick)

    /** Retry was pressed on this stream's failure, and the stream hasn't opened since ([PlayerStates.error]). */
    private var retriedStream = false

    /** A live playback's time played here ([TimelineState.liveLabel]): [liveWatched] so far, and since [livePlaying] while it plays. */
    private var liveWatched = Duration.ZERO
    private var livePlaying: TimeMark? = null

    /** Rebuilds the timeline every [LIVE_TICK] while a live window shows ([rewindWindow]): the edge moves on. */
    private var liveTick: Job? = null

    private var osd: String? = null
    /** The level a swipe changed, shown in the OSD's place for as long ([showLevel]). */
    private var level: LevelIndicator? = null
    private var osdJob: Job? = null

    // Watch together ([attachWatch]).
    private var watch: PlayerWatch? = null
    private var watchUi: PlayerWatchUi? = null
    private var watchJobs: List<Job> = emptyList()

    /** The speed the room plays at while the room nudges the player's own ([PlayerAdapter.setRate]). */
    private var roomSpeed: Double? = null
    private var released = false

    /**
     * The player as a watch-together room drives it (the Open Watch Party SDK's adapter): the store's own commands,
     * without the OSD.
     */
    internal val watchPlayer: RoomPlayer get() = watchAdapter
    private val watchAdapter = StoreAdapter()

    /** The player shows in a picture-in-picture window ([onPictureInPictureChanged]): a watch-together room's player stays in the room. */
    internal val inPictureInPicture: StateFlow<Boolean> = mutablePictureInPicture.asStateFlow()

    private val audioItems = Memo<TrackKey, List<TrackItem>> { TrackLabels.items(it.tracks, TrackKind.AUDIO, it.selected, it.second, it.locale) }
    private val subtitleItems = Memo<TrackKey, List<TrackItem>> { TrackLabels.items(it.tracks, TrackKind.SUBTITLE, it.selected, it.second, it.locale) }
    private val moreSubtitles = Memo<MoreKey, List<SubtitleLanguage>> { MoreSubtitles.languages(it.subtitles, it.tracks, it.locale) }
    private val speedItems = Memo<Pair<Double, String>, List<SpeedOption>> { PlayerStates.speeds(config.speeds, it.first) }
    private val chapterMarks = Memo<Pair<List<Chapter>, Double>, List<Float>> { PlayerStates.chapterMarks(it.first, it.second) }
    private val skipSegments = Memo<Pair<List<Chapter>, Double>, List<SkipSegment>> { SkipSegments.of(it.first, it.second) }
    private val options = Memo<PlayerOptions.Values, List<PlayerOption>> { PlayerOptions.build(it) }

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    private val mutableTimeline = MutableStateFlow(buildTimeline())

    /**
     * The timeline as the controls draw it, a scrub's preview included: a new value each time the engine reports the
     * position or the cache moved, several times a second while the video plays, where [state] stays as it is.
     */
    val timeline: StateFlow<TimelineState> = mutableTimeline.asStateFlow()

    private val mutableUserPauses = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Each pause the viewer gives (Pause, a TogglePause that pauses: the player's buttons, keys and the OS's controls),
     * never the system's ([PlayerCommand.SystemPause]): the OS media controls hear them ([MediaControlsHost.userPaused]).
     */
    internal val userPauses: SharedFlow<Unit> = mutableUserPauses.asSharedFlow()

    init {
        scope.launch { session.engineState.collect(::onEngineState) }
        scope.launch { session.info.collect(::onSessionInfo) }
        scope.launch { preferences.choices.collect(::onChoices) }
        restartAutoHide()
    }

    /**
     * Shows watch together ([PlayerWatch]): its state in [PlayerUiState.watch], its toasts as the OSD; the viewer's
     * seeks and speed changes go to it, and while it says the viewer may not control playback (a guest in a host-only
     * room) play, pause, every seek and the speed are refused, as is the next episode for anyone but the host
     * ([allowedInRoom]); a scrub or a held seek under way when it stops letting the viewer control playback ends without
     * seeking. A stop on this device rather than the viewer's ([PlayerCommand.SystemPause], the sleep timer) pauses the
     * room for whoever controls playback, and for anyone else pauses this device alone, its player out of the room's
     * playback, which plays on ([PlayerWatch.stopHere]), until its Play or the system's resume catches it up
     * ([PlayerWatch.resumeHere]). Null detaches it.
     */
    fun attachWatch(watch: PlayerWatch?) {
        watchJobs.forEach(Job::cancel)
        this.watch = watch
        update { onWatchUi(watch?.state?.value) }
        watchJobs = if (watch == null) {
            emptyList()
        } else {
            listOf(
                scope.launch { watch.state.collect { ui -> update { onWatchUi(ui) } } },
                scope.launch { watch.notices.collect { text -> update { showOsd(text) } } },
            )
        }
    }

    /**
     * Runs a command from the views: buttons, menus, sliders, the prompt button, the error screen, the system's media
     * controls. Each is the viewer's doing and stops a prompt's countdown, but for [PlayerCommand.CloseMenu], which views
     * also send when a menu goes by itself (a room that ended takes its panel along).
     */
    fun dispatch(command: PlayerCommand) {
        if (command != PlayerCommand.CloseMenu) interruptPrompt()
        execute(command)
    }

    /**
     * Handles a key or pointer event from the player surface through the platform's keymap. False means the store
     * left it alone and the platform should handle it (focus navigation, activating the focused control).
     */
    fun onInput(event: InputEvent): Boolean {
        if (lock != null && event.touchesVideo()) return onLockedTouch(event)
        interruptPrompt()
        return handleInput(event)
    }

    /** The pointer and touch events: the ones a locked screen shields ([onLockedTouch]). Keys and focus moves pass. */
    private fun InputEvent.touchesVideo(): Boolean = when (this) {
        is InputEvent.KeyDown, is InputEvent.KeyUp, InputEvent.FocusMove -> false
        is InputEvent.Click, is InputEvent.PointerMove, InputEvent.PointerExit, is InputEvent.Wheel,
        is InputEvent.Pinch, InputEvent.PinchEnd, is InputEvent.Swipe, is InputEvent.Hold -> true
    }

    /**
     * A touch on the locked screen: a tap shows or hides the unlock button and a second finger hides it; nothing else
     * acts, nor stops a prompt's countdown. A finger held before the lock still gives back its speed as it lifts.
     */
    private fun onLockedTouch(event: InputEvent): Boolean {
        when (event) {
            is InputEvent.Click -> setUnlock(if (lock == UnlockStage.HIDDEN) UnlockStage.SHOWN else UnlockStage.HIDDEN)
            is InputEvent.Pinch -> if (lock != UnlockStage.HIDDEN) setUnlock(UnlockStage.HIDDEN)
            is InputEvent.Hold -> if (!event.held) releaseSpeed()
            else -> Unit
        }
        return true
    }

    private fun lockScreen() {
        if (config.profile != InputProfile.TOUCH || lock != null) return
        update {
            menu = null
            scrubber.end()
            controlsShown = false
            autoHideJob?.cancel()
            autoHideJob = null
        }
        setUnlock(UnlockStage.SHOWN)
    }

    private fun unlockPress() {
        when (lock) {
            null, UnlockStage.HIDDEN -> Unit
            UnlockStage.SHOWN -> setUnlock(UnlockStage.ARMED)
            UnlockStage.ARMED -> update {
                lockJob?.cancel()
                lockJob = null
                lock = null
                show()
            }
        }
    }

    private fun setUnlock(stage: UnlockStage) = update {
        lock = stage
        lockJob?.cancel()
        lockJob = if (stage == UnlockStage.HIDDEN) {
            null
        } else {
            scope.launch {
                delay(UNLOCK_PROMPT)
                update { if (lock != null) lock = UnlockStage.HIDDEN }
            }
        }
    }

    /**
     * Anything the viewer does on the player stops a prompt's countdown: an automatic skip's, and up next's for the rest
     * of the file, as for credits counting down to the next episode in its place.
     */
    private fun interruptPrompt() {
        val nextEpisodeCountdown = upNext.counting || skip.counting && skip.segment?.let(::playsNextEpisode) == true
        if (!skip.counting && !nextEpisodeCountdown) return
        update {
            skip.interrupt()
            if (nextEpisodeCountdown) upNext.interrupt()
        }
    }

    private fun handleInput(event: InputEvent): Boolean = when (event) {
        is InputEvent.KeyDown -> onKeyDown(event)
        is InputEvent.KeyUp -> onKeyUp(event)
        is InputEvent.Click -> clicks.onClick(event, keymap, inputContext())
        is InputEvent.PointerMove -> {
            if (event.overControls == pointerOverControls && controlsVisible()) {
                restartAutoHide()
            } else {
                update {
                    pointerOverControls = event.overControls
                    show()
                }
            }
            true
        }
        InputEvent.PointerExit -> {
            update { pointerOverControls = false }
            hideControls()
            true
        }
        is InputEvent.Wheel -> keymap.wheel(event.steps, inputContext())?.let { execute(it); true } ?: false
        is InputEvent.Pinch -> {
            onPinch(event.scale)
            true
        }
        InputEvent.PinchEnd -> {
            onPinchEnd()
            true
        }
        is InputEvent.Swipe -> keymap.swipe(event, inputContext())?.let { execute(it); true } ?: false
        is InputEvent.Hold -> keymap.hold(event.held, inputContext())?.let { execute(it); true } ?: false
        InputEvent.FocusMove -> {
            if (controlsVisible()) restartAutoHide()
            true
        }
    }

    /** The seek bar gained or lost focus (TV): left/right scrub while it has it. */
    fun onTimelineFocusChanged(focused: Boolean) {
        timelineFocused = focused
    }

    /** The window entered or left fullscreen, whoever asked. */
    fun onFullscreenChanged(fullscreen: Boolean) = update { this.fullscreen = fullscreen }

    /**
     * The bottom controls (seek bar and buttons) cover [fraction] of the player's height, from its bottom edge, as the
     * view lays them out; 0 while it shows none (picture-in-picture). While the controls show, subtitles move up by as
     * much ([PlayerEngine.setSubtitleLift]), so they never sit under them.
     */
    fun onControlsHeightChanged(fraction: Double) {
        if (!fraction.isFinite()) return
        // To a thousandth: sub-pixel layout changes don't reach the engine.
        val height = (fraction.coerceIn(0.0, MAX_SUBTITLE_LIFT) * 1000).roundToLong() / 1000.0
        if (height != controlsHeight) update { controlsHeight = height }
    }

    /**
     * The player went into a picture-in-picture window, or came back from it: the window shows the whole picture
     * ([VideoLayout.DEFAULT]), and the fit and zoom come back with the player.
     */
    fun onPictureInPictureChanged(active: Boolean) {
        if (active != pictureInPicture) update { mutablePictureInPicture.value = active }
    }

    /**
     * The player's window went out of sight (minimized, hidden, the app in the background) or came back. Out of sight,
     * the display may sleep unless the player is in picture in picture ([PlayerUiState.keepAwake]).
     */
    fun onVisibleChanged(visible: Boolean) {
        if (visible != this.visible) update { this.visible = visible }
    }

    /**
     * An assistive technology that moves through the controls one at a time started or stopped: VoiceOver or Switch
     * Control on Apple's platforms, TalkBack's touch exploration on Android. While one runs the controls stay up, and
     * the prompt button with them past its first seconds: no timer takes away the control the viewer is reaching.
     */
    fun onAssistiveTechnologyChanged(running: Boolean) {
        if (running != assistiveTechnology) update { assistiveTechnology = running }
    }

    /**
     * Stops the store's timers and collection; the sync player stops taking commands, the engine reading stats. The
     * last state lets the display sleep.
     */
    fun release() {
        released = true
        watchAdapter.onChanged = null
        scope.cancel()
        if (statsWanted) engine.setStatsWanted(false)
        mutableState.value = mutableState.value.copy(keepAwake = false)
    }

    // Input

    private fun onKeyDown(event: InputEvent.KeyDown): Boolean {
        val held = scrubber.heldKey
        if (held == event.key) return true // The hold moves the preview; auto-repeat adds nothing.
        if (held != null) scrubber.release()
        if (heldSeekKey != null && heldSeekKey != event.key) commitHeldSeek()
        if (controlsVisible()) restartAutoHide()
        val command = keymap.key(event.key, event.modifiers, inputContext()) ?: return false
        consumedKeys += event.key
        if (event.repeat && !command.isRepeatable) return true
        execute(command, event)
        return true
    }

    private fun onKeyUp(event: InputEvent.KeyUp): Boolean {
        if (scrubber.heldKey == event.key) scrubber.release()
        if (heldSeekKey == event.key) commitHeldSeek()
        return consumedKeys.remove(event.key)
    }

    /**
     * A pinch zooms from the zoom it started at by its [scale]; only once it has clearly moved
     * ([PINCH_THRESHOLD] for [PINCH_MOVES] changes in a row), so two fingers resting on the video change nothing, and
     * snapping to 100 % within [PINCH_SNAP_PERCENT], so pinching back undoes a zoom exactly. The zoom shows once it
     * ends.
     */
    private fun onPinch(scale: Double) {
        if (!scale.isFinite()) return
        val start = pinchStart ?: zoom.also {
            pinchStart = it
            pinchMoves = 0
            pinchZooming = false
        }
        val target = VideoLayout.normalizeZoom(snapPinch(start * scale))
        if (!pinchZooming) {
            if (abs(scale - 1.0) <= PINCH_THRESHOLD) {
                pinchMoves = 0
                return
            }
            pinchMoves += 1
            if (pinchMoves < PINCH_MOVES || target == zoom) return
            pinchZooming = true
        }
        if (target != zoom) update { zoom = target }
    }

    private fun onPinchEnd() {
        if (pinchZooming) update { showOsd(T.player.osd.zoom(PlayerOptions.zoomLabel(zoom))) }
        pinchStart = null
        pinchMoves = 0
        pinchZooming = false
    }

    private fun snapPinch(zoom: Double): Double =
        if (abs(VideoLayout.percent(zoom) - 100) <= PINCH_SNAP_PERCENT) 1.0 else zoom

    private fun inputContext() = InputContext(
        controlsVisible = controlsVisible(),
        menuOpen = menu != null,
        timelineFocused = timelineFocused,
        scrubbing = scrubber.active,
        prompt = prompt()?.command,
        stillWatchingShown = sleep.asking != null,
        errorShown = info.failure != null,
        fullscreen = fullscreen,
        nextEpisodeAvailable = info.nextEpisode != null,
        previousEpisodeAvailable = info.hasPreviousEpisode,
        videoClick = info.videoClick,
        live = info.live,
        swipes = swipeGestures,
    )

    // Commands

    /**
     * Runs a command from the views, the system's controls or the keymap ([press]: the key that gave it, whose hold
     * makes a held seek or a remote's scrub): the one way the viewer's commands reach the engine. One a watch-together
     * room doesn't let this device give ([allowedInRoom]) goes no further, and the room's reason shows. [system] is a
     * pause the system gave ([PlayerCommand.SystemPause]) rather than the viewer.
     */
    private fun execute(command: PlayerCommand, press: InputEvent.KeyDown? = null, system: Boolean = false) {
        if (!allowedInRoom(command)) {
            watchUi?.controlsLocked?.let { text -> update { showOsd(text) } }
            return
        }
        if (info.live && command.seeks && (rewindWindow() == null || !command.seeksLive)) return
        val pausing = command == PlayerCommand.Pause ||
            command == PlayerCommand.TogglePause && !engineState.eof && !paused.resolve(engineState.paused)
        if (pausing && !system) mutableUserPauses.tryEmit(Unit)
        // A stop on this device for one who doesn't control the room's playback is this device's alone, and so is the
        // Play that ends it.
        val stopped = watch?.takeIf { it.stoppedHere }
        if (stopped != null && command.resumes()) return resumeHere(stopped)
        if (stopsHereAlone() && command.stopsHere()) return stopHere()
        // Play and pause in a room go to the room, which commands this player ([watchPlayer]).
        val room = watch?.takeIf { it.routesControls }
        if (room != null) {
            when (command) {
                PlayerCommand.TogglePause ->
                    return if (engineState.eof || paused.resolve(engineState.paused)) playInRoom(room) else room.pause()
                PlayerCommand.Play -> return playInRoom(room)
                PlayerCommand.Pause -> return room.pause()
                else -> Unit
            }
        }
        when (command) {
            PlayerCommand.TogglePause -> if (engineState.eof || paused.resolve(engineState.paused)) playOrStartOver() else setPaused(true)
            PlayerCommand.Play -> playOrStartOver()
            PlayerCommand.Pause -> setPaused(true)
            // Whoever controls the room's playback, or anyone outside a room: the system's pause is Pause, its resume
            // Play.
            PlayerCommand.SystemPause -> execute(PlayerCommand.Pause, system = true)
            PlayerCommand.SystemResume -> if (!stopsHereAlone()) execute(PlayerCommand.Play)
            is PlayerCommand.SeekBy ->
                if (press?.repeat == true) holdSeek(command.seconds, press.key) else seekFromControls(displayPosition() + command.seconds)
            is PlayerCommand.SeekTo -> seekFromControls(command.seconds)
            is PlayerCommand.SeekToFraction ->
                if (seekDuration() > 0) seekFromControls(command.fraction.coerceIn(0.0, 1.0) * seekDuration())
            is PlayerCommand.ScrubTo -> scrubTo(command.fraction)
            is PlayerCommand.DpadScrub -> dpadScrub(command.forward, press?.key)
            PlayerCommand.CommitScrub -> commitScrub()
            PlayerCommand.CancelScrub -> update { scrubber.end() }
            is PlayerCommand.AdjustVolume -> setVolume(volume.resolve(engineState.volume) + command.delta)
            is PlayerCommand.SetVolume -> setVolume(command.percent)
            PlayerCommand.ToggleMute -> toggleMute()
            is PlayerCommand.AdjustBrightness -> host.levels?.let { levels ->
                update { showLevel(LevelKind.BRIGHTNESS, levels.adjustBrightness(command.delta)) }
            }
            is PlayerCommand.AdjustSystemVolume -> host.levels?.let { levels ->
                update { showLevel(LevelKind.VOLUME, levels.adjustVolume(command.delta)) }
            }
            PlayerCommand.ToggleFullscreen -> setFullscreen(!fullscreen)
            PlayerCommand.ExitFullscreen -> if (fullscreen) setFullscreen(false)
            is PlayerCommand.SelectTrack -> selectTrack(command.kind, command.id, announce = false)
            is PlayerCommand.ToggleSecondSubtitle -> toggleSecondSubtitle(command.id)
            is PlayerCommand.OpenSubtitleLanguage -> if (info.failure == null) update {
                subtitleLanguage = command.key
                menu = PlayerMenu.SUBTITLE_LANGUAGE
                show()
            }
            is PlayerCommand.AddSubtitle -> update {
                session.addSubtitle(command.url)
                if (menu?.parent == PlayerMenu.SUBTITLES) menu = null
            }
            PlayerCommand.CycleAudio -> cycleAudio()
            PlayerCommand.CycleSubtitles -> cycleSubtitles()
            is PlayerCommand.SetSpeed -> update {
                applySpeed(command.speed)
                if (menu == PlayerMenu.SPEED) menu = null
            }
            PlayerCommand.SpeedUp -> stepSpeed(up = true)
            PlayerCommand.SpeedDown -> stepSpeed(up = false)
            PlayerCommand.ResetSpeed -> update { applySpeed(1.0) }
            PlayerCommand.HoldSpeed -> holdSpeed()
            PlayerCommand.ReleaseSpeed -> releaseSpeed()
            is PlayerCommand.AdjustSubtitleDelay ->
                setDelay(TrackKind.SUBTITLE, subtitleDelay.resolve(engineState.subtitleDelaySeconds) + command.deltaSeconds)
            is PlayerCommand.SetSubtitleDelay -> setDelay(TrackKind.SUBTITLE, command.seconds)
            is PlayerCommand.AdjustAudioDelay ->
                setDelay(TrackKind.AUDIO, audioDelay.resolve(engineState.audioDelaySeconds) + command.deltaSeconds)
            is PlayerCommand.SetAudioDelay -> setDelay(TrackKind.AUDIO, command.seconds)
            PlayerCommand.NextChapter -> nextChapter()
            PlayerCommand.PreviousChapter -> previousChapter()
            PlayerCommand.NextEpisode -> if (info.nextEpisode != null) update { playNextEpisode() }
            PlayerCommand.PreviousEpisode -> if (info.hasPreviousEpisode) update { playPreviousEpisode() }
            PlayerCommand.SkipSegment -> update { skipSegment() }
            PlayerCommand.GoToLive -> rewindWindow()?.let { window -> seekFromControls(window.windowSeconds) }
            PlayerCommand.Retry -> if (info.failure != null) update {
                retriedStream = true
                session.retry()
            }
            PlayerCommand.NextStream -> if (info.alternativeStreams > 0) update {
                retriedStream = false
                session.playNextStream()
            }
            // The list opens: whatever is picked is a new stream.
            PlayerCommand.ChooseStream -> if (info.errorVideo != null) update {
                retriedStream = false
                session.chooseStream()
            }
            PlayerCommand.WatchErrorVideo -> if (info.errorVideo != null && info.failure != null) update { session.watchErrorVideo() }
            is PlayerCommand.SetVideoFit -> update {
                fit = command.fit
                preferences.setVideoFit(command.fit)
                closePage(PlayerMenu.VIDEO_FIT)
            }
            is PlayerCommand.SetZoom -> setZoom(command.zoom)
            PlayerCommand.ZoomIn -> setZoom(zoom + VideoLayout.ZOOM_STEP)
            PlayerCommand.ZoomOut -> setZoom(zoom - VideoLayout.ZOOM_STEP)
            PlayerCommand.ResetZoom -> setZoom(1.0)
            is PlayerCommand.SetShaderPreset -> if (shaderFiles != null) update {
                shader = runnable(ShaderPreset.of(command.id, customShaders))
                preferences.setShaderPreset(shader.id)
                closePage(PlayerMenu.SHADERS)
            }
            PlayerCommand.ToggleShaders -> if (shaderFiles != null) update {
                shader = ShaderPreset.toggled(shader, runnable(ShaderPreset.of(savedShader, customShaders)), ShaderPreset.available(shaderFiles.renderer, customShaders))
                if (shader != ShaderPreset.Off) preferences.setShaderPreset(shader.id)
                if (menu == null) {
                    showOsd(if (shader == ShaderPreset.Off) T.player.osd.shadersOff else T.player.osd.shader(PlayerOptions.shaderLabel(shader)))
                }
            }
            is PlayerCommand.ImportShader -> if (shaderFiles != null && importsCustomShaders()) importShader(shaderFiles, command.fileName, command.source)
            PlayerCommand.ShaderImportFailed -> if (shaderFiles != null && importsCustomShaders()) update { showOsd(T.player.options.shader.importFailed) }
            is PlayerCommand.DeleteShader -> if (shaderFiles != null) deleteShader(shaderFiles, command.id)
            is PlayerCommand.SetSleepTimer -> update {
                sleep.set(command.minutes)
                closePage(PlayerMenu.SLEEP_TIMER)
            }
            PlayerCommand.SleepAtEndOfVideo -> update {
                sleep.setAtEndOfVideo()
                closePage(PlayerMenu.SLEEP_TIMER)
            }
            PlayerCommand.CancelSleepTimer -> update {
                sleep.cancel()
                closePage(PlayerMenu.SLEEP_TIMER)
            }
            PlayerCommand.KeepWatching -> update { sleep.keepWatching() }
            is PlayerCommand.SetAutoPlayNext -> if (info.nextEpisode != null) session.setAutoPlayNext(command.enabled)
            is PlayerCommand.SetAudioNormalization -> update {
                normalization = command.enabled
                preferences.setAudioNormalization(command.enabled)
            }
            is PlayerCommand.SetAudioChannels -> update {
                channels = command.channels
                preferences.setAudioChannels(command.channels)
                closePage(PlayerMenu.AUDIO_CHANNELS)
            }
            is PlayerCommand.SetAudioDevice -> update {
                audioDevice.request(command.name)
                engine.setAudioDevice(command.name)
                closePage(PlayerMenu.AUDIO_OUTPUT)
            }
            PlayerCommand.ToggleInfo -> if (info.failure == null) update { infoVisible = !infoVisible }
            PlayerCommand.ShowControls -> update { show() }
            PlayerCommand.HideControls -> hideControls()
            PlayerCommand.ToggleControls -> if (controlsVisible() && !pinned()) hideControls() else update { show() }
            PlayerCommand.LockScreen -> lockScreen()
            PlayerCommand.UnlockPress -> unlockPress()
            is PlayerCommand.OpenMenu -> if (info.failure == null && lock == null) update {
                menu = command.menu
                show()
            }
            PlayerCommand.CloseMenu -> update { menu = null }
            PlayerCommand.Back -> back()
            PlayerCommand.Close -> host.close()
            is PlayerCommand.Watch -> update { watch?.execute(command.command) }
        }
    }

    /**
     * In a room ([PlayerCommand.roomControl]): play, pause, every seek and the speed when the room lets this device
     * control them; the episode the host's. A guest's player paused here alone ([stopHere]) takes Play, which catches
     * it up with the room; Pause while the sleep timer asks is the timer's stop. The system's pause and resume are
     * always taken: in a room they pause or play this device alone for one who doesn't control playback
     * ([stopsHereAlone]).
     */
    private fun allowedInRoom(command: PlayerCommand): Boolean {
        val ui = watchUi ?: return true
        return when (command.roomControl) {
            RoomControl.EPISODE -> ui.canChangeEpisode
            RoomControl.PLAY -> ui.canControl || watch?.stoppedHere == true
            RoomControl.PAUSE -> ui.canControl || sleep.asking != null
            RoomControl.SPEED, RoomControl.SEEK -> ui.canControl
            RoomControl.FREE -> true
        }
    }

    /** A stop on this device pauses it alone ([stopHere]): in a room whose playback this device doesn't control. */
    private fun stopsHereAlone(): Boolean = watchUi?.canControl == false

    /** The system's pause, and Pause as the answer to the sleep timer's question: a stop on this device. */
    private fun PlayerCommand.stopsHere(): Boolean = this == PlayerCommand.SystemPause || this == PlayerCommand.Pause && sleep.asking != null

    /** The commands that end a stop on this device ([stopHere]): Play (TogglePause, paused as it is) and the system's resume. */
    private fun PlayerCommand.resumes(): Boolean =
        this == PlayerCommand.Play || this == PlayerCommand.TogglePause || this == PlayerCommand.SystemResume

    /**
     * Pauses this device alone, for one who doesn't control the room's playback: its player leaves the room's playback
     * first ([PlayerWatch.stopHere]), so the room neither waits for it nor plays it again, and plays on without it.
     */
    private fun stopHere() {
        watch?.stopHere()
        setPaused(true)
    }

    /**
     * Ends [stopHere]: the player joins the room's playback again, and the room catches it up, playing it if the room
     * plays. Made one who controls playback meanwhile, this device plays the room too.
     */
    private fun resumeHere(watch: PlayerWatch) {
        watch.resumeHere()
        if (watchUi?.canControl == true && watch.routesControls) playInRoom(watch)
    }

    /**
     * Takes the room's [ui]: once it stops letting this device control playback, a scrub or a held seek under way ends
     * without seeking.
     */
    private fun onWatchUi(ui: PlayerWatchUi?) {
        watchUi = ui
        if (ui?.canControl == false) {
            scrubber.end()
            endHeldSeek()
            // The room's speed is no longer this device's to give back: the hold ends where it is.
            heldSpeedFrom = null
        }
    }

    /**
     * The live window this playback seeks in ([SessionInfo.liveWindow]), unless the player follows a watch-together
     * room ([PlayerWatch.routesControls]: the room's positions are the files'); null plays a live playback without
     * seeking, as layout C.
     */
    private fun rewindWindow(): LiveWindow? =
        info.liveWindow?.takeIf { info.live && watch?.routesControls != true }

    private fun back() {
        when {
            lock != null -> host.close()
            scrubber.active -> update { scrubber.end() }
            menu?.back != null -> update { menu = menu?.back }
            menu != null -> update { menu = null }
            sleep.asking != null -> update { sleep.keepWatching() }
            prompt() != null -> update { dismissPrompt() }
            infoVisible -> update { infoVisible = false }
            controlsVisible() && !pinned() -> hideControls()
            else -> host.close()
        }
    }

    /** A pick on an option's [page] closes the menu, as a pick of a speed does. */
    private fun closePage(page: PlayerMenu) {
        if (menu == page) menu = null
    }

    private fun setPaused(paused: Boolean) = update {
        this.paused.request(paused)
        engine.setPaused(paused)
        // Paused while the sleep timer asks: that is the answer, and the timer is done.
        if (paused && sleep.asking != null) sleep.cancel()
    }

    /**
     * Plays; at the end of the file it starts over. There mpv keeps the last frame and pauses again at once (keep-open),
     * so a bare unpause would leave the request to play pending and the button reading Pause while nothing plays. A live
     * playback paused until its window slid past the position plays from the window's start.
     */
    private fun playOrStartOver() = update {
        // A live playback that ended has nothing to start over: its stream is gone.
        if (engineState.loaded && engineState.eof && !info.live) {
            val target = seekTo(0.0)
            watch?.onLocalSeek(target)
        }
        val window = rewindWindow()
        if (window != null && pendingSeek == null && window.behind(engineState.positionSeconds) > window.windowSeconds) seekTo(0.0)
        setPaused(false)
    }

    /**
     * Plays [room]; at the end of the file it starts the room over, as [playOrStartOver] does alone: the relay ends a
     * room played from its end at once, so a bare play would only show "Finished" again.
     */
    private fun playInRoom(room: PlayerWatch) {
        if (engineState.loaded && engineState.eof && !info.live) room.onLocalSeek(0.0)
        room.play()
    }

    /** The zoom shows as a message when no menu shows it (keys; a pinch shows it once it ends). */
    private fun setZoom(target: Double) = update {
        zoom = VideoLayout.normalizeZoom(target)
        if (menu == null) showOsd(T.player.osd.zoom(PlayerOptions.zoomLabel(zoom)))
        closePage(PlayerMenu.ZOOM)
    }

    /**
     * Keeps a copy of the `.glsl` file [fileName]'s [source] and runs it, named after the file; anything else, or a
     * copy that fails, says it couldn't import the shader.
     */
    private fun importShader(files: ShaderFiles, fileName: String, source: String) {
        val name = fileName.substringAfterLast('/').substringAfterLast('\\')
        if (!name.endsWith(GLSL, ignoreCase = true) || name.length == GLSL.length) {
            update { showOsd(T.player.options.shader.importFailed) }
            return
        }
        scope.launch {
            val stored = try {
                files.import(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "Couldn't keep an imported shader" }
                update { showOsd(T.player.options.shader.importFailed) }
                return@launch
            }
            val custom = CustomShader("custom_$stored", name.dropLast(GLSL.length), stored)
            update {
                customShaders = customShaders + custom
                shader = ShaderPreset.Custom(custom)
                preferences.addCustomShader(custom)
                closePage(PlayerMenu.SHADERS)
                showOsd(T.player.options.shader.imported)
            }
        }
    }

    /** [preset], or off where this device's renderer doesn't run it ([ShaderPreset.runsOn]). */
    private fun runnable(preset: ShaderPreset): ShaderPreset {
        val renderer = shaderFiles?.renderer ?: return ShaderPreset.Off
        return if (preset.runsOn(renderer)) preset else ShaderPreset.Off
    }

    /** The Shaders page imports: the device has file dialogs and its renderer runs imported shaders. */
    private fun importsCustomShaders(): Boolean {
        val renderer = shaderFiles?.renderer ?: return false
        return importsShaders && renderer.runsCustomShaders
    }

    /** Forgets the imported shader [id] and deletes its copy; the shaders go off if it was running. */
    private fun deleteShader(files: ShaderFiles, id: String) {
        val custom = customShaders.firstOrNull { it.id == id } ?: return
        update {
            customShaders = customShaders - custom
            if (shader.id == id) shader = ShaderPreset.Off
            preferences.removeCustomShader(custom)
        }
        scope.launch {
            try {
                files.delete(custom.fileName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, e) { "Couldn't delete an imported shader's file" }
            }
        }
    }

    private fun setVolume(percent: Double) = update {
        val current = volume.resolve(engineState.volume)
        val target = percent.coerceIn(0.0, PlayerStates.FULL_VOLUME)
        volume.request(target)
        engine.setVolume(target)
        // Turning the volume up while muted means "I want to hear it".
        if (target > current && muted.resolve(engineState.muted)) {
            muted.request(false)
            engine.setMuted(false)
        }
        if (menu == null) showOsd(T.player.osd.volume(PlayerText.volume(target.roundToInt())))
    }

    private fun toggleMute() = update {
        val mute = !muted.resolve(engineState.muted)
        muted.request(mute)
        engine.setMuted(mute)
        if (menu == null) {
            showOsd(if (mute) T.player.volume.muted else T.player.osd.volume(PlayerText.volume(volume.resolve(engineState.volume).roundToInt())))
        }
    }

    private fun setFullscreen(fullscreen: Boolean) {
        update { this.fullscreen = fullscreen }
        host.setFullscreen(fullscreen)
    }

    /** The viewer's speed: the room's too, in a room. */
    private fun applySpeed(value: Double) {
        setSpeed(value)
        if (menu == null) showOsd(T.player.osd.speed(PlayerText.speed(value)))
    }

    private fun setSpeed(value: Double) {
        speed.request(value)
        roomSpeed = null
        engine.setSpeed(value)
        watch?.onLocalRate(value)
    }

    /**
     * A finger held on the video: [HOLD_SPEED] until it lifts ([releaseSpeed]), which gives back the speed shown before
     * (the room's, in a room, whose speed the hold changes for everyone). The held speed shows instead of the OSD.
     */
    private fun holdSpeed() {
        if (heldSpeedFrom != null) return
        update {
            heldSpeedFrom = roomSpeed ?: speed.resolve(engineState.speed)
            setSpeed(HOLD_SPEED)
        }
    }

    private fun releaseSpeed() {
        val from = heldSpeedFrom ?: return
        update {
            heldSpeedFrom = null
            setSpeed(from)
        }
    }

    private fun stepSpeed(up: Boolean) = update {
        val current = roomSpeed ?: speed.resolve(engineState.speed)
        val next = if (up) config.speeds.firstOrNull { it > current + PlayerText.SPEED_EPSILON }
        else config.speeds.lastOrNull { it < current - PlayerText.SPEED_EPSILON }
        if (next != null) applySpeed(next)
    }

    private fun setDelay(kind: TrackKind, seconds: Double) = update {
        // Kept to the millisecond, so steps of 0.1 add up to 0.3 and not 0.30000000000000004.
        val target = ((seconds * 1000).roundToLong() / 1000.0).coerceIn(-config.maxDelaySeconds, config.maxDelaySeconds)
        if (kind == TrackKind.SUBTITLE) {
            subtitleDelay.request(target)
            engine.setSubtitleDelay(target)
            if (menu == null) showOsd(T.player.osd.subtitleDelay(PlayerText.delay(target)))
        } else {
            audioDelay.request(target)
            engine.setAudioDelay(target)
            if (menu == null) showOsd(T.player.osd.audioDelay(PlayerText.delay(target)))
        }
    }

    /**
     * Selects a track; [announce] shows the choice as a message (a cycling key: no menu shows it). Subtitles off turn
     * the second subtitle off too, and the second's track becoming the subtitle turns it off first, as mpv shows a
     * track once.
     */
    private fun selectTrack(kind: TrackKind, id: Long?, announce: Boolean) = update {
        val second = selectedSecondSubtitle()
        if (kind == TrackKind.SUBTITLE && second != null && (id == null || id == second)) selectSecondSubtitle(null)
        session.onTrackSelected(kind, id)
        engine.selectTrack(kind, id)
        when (kind) {
            TrackKind.AUDIO -> {
                audioTrack.request(TrackChoice(id))
                if (menu == PlayerMenu.AUDIO) menu = null
            }
            TrackKind.SUBTITLE -> {
                subtitleTrack.request(TrackChoice(id))
                if (menu == PlayerMenu.SUBTITLES) menu = null
            }
            TrackKind.VIDEO -> {}
        }
        if (announce && menu == null) {
            val track = engineState.tracks.firstOrNull { it.kind == kind && it.id == id }
            val locale = T.locale
            showOsd(
                when {
                    kind == TrackKind.AUDIO && track != null -> T.player.osd.audioTrack(TrackLabels.label(track, engineState.tracks, locale))
                    track != null -> T.player.osd.subtitleTrack(TrackLabels.label(track, engineState.tracks, locale))
                    else -> T.player.osd.subtitlesOff
                },
            )
        }
    }

    /** [PlayerCommand.ToggleSecondSubtitle]: the menu stays open, its rows showing the change. */
    private fun toggleSecondSubtitle(id: Long?) = update {
        val second = selectedSecondSubtitle()
        when {
            id == null || id == second -> if (second != null) selectSecondSubtitle(null)
            id != selectedTrack(TrackKind.SUBTITLE) -> selectSecondSubtitle(id)
        }
    }

    private fun selectSecondSubtitle(id: Long?) {
        session.onSecondSubtitleSelected(id)
        engine.selectSecondSubtitle(id)
        secondSubtitleTrack.request(TrackChoice(id))
    }

    private fun cycleAudio() {
        val ids = engineState.tracks.filter { it.kind == TrackKind.AUDIO }.map { it.id }
        if (ids.isEmpty()) return
        val current = selectedTrack(TrackKind.AUDIO)
        selectTrack(TrackKind.AUDIO, ids[(ids.indexOf(current) + 1) % ids.size], announce = true)
    }

    private fun cycleSubtitles() {
        val ids: List<Long?> = engineState.tracks.filter { it.kind == TrackKind.SUBTITLE }.map { it.id } + null
        if (ids.size == 1) return
        val current = selectedTrack(TrackKind.SUBTITLE)
        selectTrack(TrackKind.SUBTITLE, ids[(ids.indexOf(current) + 1) % ids.size], announce = true)
    }

    private fun selectedTrack(kind: TrackKind): Long? {
        val reported = TrackChoice(engineState.tracks.firstOrNull { it.kind == kind && it.selected }?.id)
        return (if (kind == TrackKind.AUDIO) audioTrack else subtitleTrack).resolve(reported).id
    }

    private fun selectedSecondSubtitle(): Long? =
        secondSubtitleTrack.resolve(TrackChoice(engineState.tracks.firstOrNull { it.kind == TrackKind.SUBTITLE && it.secondary }?.id)).id

    private fun nextChapter() {
        val position = displayPosition()
        val index = engineState.chapters.indexOfFirst { it.startSeconds > position + CHAPTER_TOLERANCE_SECONDS }
        if (index >= 0) seekToChapter(index)
    }

    private fun previousChapter() {
        val chapters = engineState.chapters
        if (chapters.isEmpty()) return
        val position = displayPosition()
        val current = chapters.indexOfLast { it.startSeconds <= position + CHAPTER_TOLERANCE_SECONDS }
        when {
            current < 0 -> seekFromControls(0.0)
            // Just past a chapter start, "previous" means the chapter before; later, this chapter's start.
            current > 0 && position - chapters[current].startSeconds < PREVIOUS_CHAPTER_GRACE_SECONDS -> seekToChapter(current - 1)
            else -> seekToChapter(current)
        }
    }

    private fun seekToChapter(index: Int) = update {
        if (!engineState.loaded) return@update
        val chapter = engineState.chapters[index]
        val target = seekTo(chapter.startSeconds)
        watch?.onLocalSeek(target)
        show()
        if (menu == null) {
            val title = chapter.title?.takeIf { it.isNotBlank() }
            showOsd(if (title != null) T.player.osd.chapter(isolate(title)) else T.player.osd.chapterNumbered((index + 1).toString()))
        }
    }

    // Seeking and scrubbing

    /**
     * The position the controls show and seek from: the engine's in the file; live with a window ([rewindWindow]),
     * its place in the window, from 0 at the window's start to [LiveWindow.windowSeconds] at the live edge.
     */
    private fun displayPosition(): Double = heldSeek ?: pendingSeek ?: playedPosition()

    private fun playedPosition(): Double {
        val window = rewindWindow() ?: return engineState.positionSeconds
        return (window.windowSeconds - window.behind(engineState.positionSeconds)).coerceIn(0.0, window.windowSeconds)
    }

    /** What the controls seek within ([displayPosition]): the file's duration, or the live window's length. */
    private fun seekDuration(): Double = rewindWindow()?.windowSeconds ?: engineState.durationSeconds

    private fun seekFromControls(seconds: Double) = update {
        if (!engineState.loaded) return@update
        val target = seekTo(seconds)
        watch?.onLocalSeek(target)
        show()
    }

    /**
     * Seeks exactly to [seconds] (within the file) and returns where. mpv lands a keyframe seek on the keyframe at or
     * before its target: with keyframes further apart than the seek step (250 frames, x264's and x265's default, are
     * 10.4 s at 23.976 fps) a step forward would land anywhere from the step on to back where it started, or before.
     * Live with a window, [seconds] is a place in it ([displayPosition]), which the session reaches
     * ([PlayerSession.seekLive]); the live edge itself within [LiveWindow.atEdge].
     */
    private fun seekTo(seconds: Double): Double {
        val duration = seekDuration()
        val target = seekTarget(seconds)
        endHeldSeek()
        pendingSeek = target
        pendingSeekJob?.cancel()
        pendingSeekJob = scope.launch {
            delay(SEEK_SETTLE_TIMEOUT)
            update { pendingSeek = null }
        }
        val window = rewindWindow()
        if (window != null) {
            val behind = duration - target
            session.seekLive(if (window.atEdge(behind)) 0.0 else behind)
            return target
        }
        session.onSeek(target)
        engine.seek(target, exact = true)
        return target
    }

    /** [seconds] within what the controls seek in ([seekDuration]). */
    private fun seekTarget(seconds: Double): Double {
        val duration = seekDuration()
        return if (duration > 0) seconds.coerceIn(0.0, duration) else seconds.coerceAtLeast(0.0)
    }

    /**
     * A held seek key's repeat: the target the timeline shows moves on by [seconds], and nothing seeks. An exact seek
     * decodes from the keyframe before its target, over a second for 4K with keyframes 10 s apart on a low-end TV box,
     * and mpv starts a seek over when the next one comes: a seek per repeat (30 to 100 ms apart) showed no picture
     * until the key came up. One exact seek goes to the target as [key] comes up or another key goes down, or
     * [HELD_SEEK_COMMIT] after the last repeat should the key's release not reach the store.
     */
    private fun holdSeek(seconds: Double, key: InputKey) = update {
        if (!engineState.loaded) return@update
        heldSeek = seekTarget(displayPosition() + seconds)
        heldSeekKey = key
        heldSeekJob?.cancel()
        heldSeekJob = scope.launch {
            delay(HELD_SEEK_COMMIT)
            commitHeldSeek()
        }
        show()
    }

    private fun commitHeldSeek() {
        val target = heldSeek ?: return
        endHeldSeek()
        seekFromControls(target)
    }

    private fun endHeldSeek() {
        heldSeek = null
        heldSeekKey = null
        heldSeekJob?.cancel()
        heldSeekJob = null
    }

    private fun scrubTo(fraction: Double) = update {
        val duration = seekDuration()
        if (!engineState.loaded || duration <= 0) return@update
        scrubber.dragTo(fraction.coerceIn(0.0, 1.0) * duration, from = displayPosition())
    }

    private fun dpadScrub(forward: Boolean, key: InputKey?) = update {
        val duration = seekDuration()
        if (!engineState.loaded || duration <= 0) return@update
        scrubber.step(forward, from = displayPosition(), duration = duration, key = key)
    }

    private fun commitScrub() = update { scrubber.commitNow() }

    private fun onScrubCommitted(seconds: Double) {
        val target = seekTo(seconds)
        watch?.onLocalSeek(target)
        show()
    }

    // Controls visibility

    private fun status(): PlaybackStatus = when {
        info.failure != null -> PlaybackStatus.FAILED
        info.resolving -> PlaybackStatus.RESOLVING
        !engineState.loaded -> PlaybackStatus.OPENING
        engineState.eof && !upNext.counting -> PlaybackStatus.ENDED
        paused.resolve(engineState.paused) -> PlaybackStatus.PAUSED
        engineState.buffering -> PlaybackStatus.BUFFERING
        else -> PlaybackStatus.PLAYING
    }

    /** Whatever keeps the controls up regardless of the auto-hide timer. */
    private fun pinned(): Boolean {
        if (assistiveTechnology || scrubber.active || menu != null || pointerOverControls) return true
        return when (status()) {
            PlaybackStatus.PLAYING, PlaybackStatus.BUFFERING -> false
            else -> true
        }
    }

    /** [PlayerUiState.keepAwake]: playing, or on the way to it, unpaused and in sight. */
    private fun keepAwake(status: PlaybackStatus, paused: Boolean): Boolean =
        !released && !paused && (visible || pictureInPicture) && (status == PlaybackStatus.PLAYING || status.isLoading)

    private fun controlsVisible(): Boolean = lock == null && (controlsShown || pinned())

    /** Shows the controls and restarts the auto-hide timer. */
    private fun show() {
        controlsShown = true
        restartAutoHide()
    }

    private fun hideControls() = update {
        if (!pinned()) {
            controlsShown = false
            autoHideJob?.cancel()
            autoHideJob = null
        }
    }

    private fun restartAutoHide() {
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(config.autoHideDelay)
            // Pinned now: the controls stay, and the timer restarts when the pin goes.
            update { if (!pinned()) controlsShown = false }
        }
    }

    private fun showOsd(text: String) {
        osd = text
        level = null
        hideOsdLater()
    }

    /** [kind] at [fraction], as a swipe left it, shown in the OSD's place for as long as a message. */
    private fun showLevel(kind: LevelKind, fraction: Double) {
        val shown = fraction.coerceIn(0.0, 1.0)
        val percent = PlayerText.volume((shown * 100).roundToInt())
        val label = when (kind) {
            LevelKind.BRIGHTNESS -> T.player.osd.brightness(percent)
            LevelKind.VOLUME -> T.player.osd.volume(percent)
        }
        level = LevelIndicator(kind, shown.toFloat(), percent, label)
        osd = null
        hideOsdLater()
    }

    private fun hideOsdLater() {
        osdJob?.cancel()
        osdJob = scope.launch {
            delay(config.osdDuration)
            update {
                osd = null
                level = null
            }
        }
    }

    // The prompt button: skipping and up next

    /**
     * The intros, recaps and credits of the file playing; none until it's loaded, so no other file's chapters count, and
     * none live.
     */
    private fun segments(): List<SkipSegment> =
        if (engineState.loaded && !info.live) skipSegments(engineState.chapters to engineState.durationSeconds) else emptyList()

    /**
     * Up next may come up: a next episode, a loaded file the session took as the video (not still [SessionInfo.judging])
     * that didn't fail and isn't an error video's message or live, no sleep timer for the end of the video (it stops
     * there), and in a room only for the host, whose next episode it is.
     */
    private fun upNextEligible(): Boolean = info.nextEpisode != null && !sleep.atEndOfVideo && engineState.loaded &&
        !info.judging && info.failure == null && info.errorVideo == null && !info.live &&
        watchUi?.canChangeEpisode != false

    /** Skipping [segment] plays the next episode: credits to the end, with up next in their place. */
    private fun playsNextEpisode(segment: SkipSegment): Boolean = segment.toEnd && upNextEligible()

    /**
     * A segment may skip on its own here: on the room's host only (guests follow its seek), and credits that play the
     * next episode only with auto-play of it on.
     */
    private fun autoSkipAllowed(segment: SkipSegment): Boolean =
        watchUi?.canChangeEpisode != false && (!playsNextEpisode(segment) || info.nextEpisode?.autoplay == true)

    private fun evaluateSkip() {
        skip.follow(displayPosition(), segments(), info.skipping, ::autoSkipAllowed)
        skip.tickWhile(playing = !scrubber.active && status() == PlaybackStatus.PLAYING)
    }

    private fun evaluateUpNext() {
        val next = info.nextEpisode
        val duration = engineState.durationSeconds
        // From the credits when the chapters have them (unless the profile turned credits off), else the countdown's
        // length before the end; without auto-play only from the credits, as the skip they replace.
        val credits = if (info.skipping.credits != SkipMode.OFF) SkipSegments.creditsStart(segments()) else null
        val start = when {
            next == null || !upNextEligible() -> null
            credits != null -> credits
            next.autoplay -> duration - next.countdownSeconds
            else -> null
        }
        val countdown = next?.countdownSeconds?.takeIf { next.autoplay }
        // While the skip button counts down to the next episode, it is up next.
        val held = skip.counting && skip.segment?.toEnd == true
        if (upNext.follow(displayPosition(), duration, engineState.eof, start, countdown, held)) playNextEpisode()
        upNext.tickWhile(playing = !scrubber.active && status() == PlaybackStatus.PLAYING)
    }

    private fun onUpNextStep(): Unit = update { if (upNext.step()) playNextEpisode() }

    private fun onSkipTick(): Unit = update { if (skip.tick()) skipSegment() }

    private fun playNextEpisode() {
        upNext.finish()
        retriedStream = false
        session.playNextEpisode()
    }

    private fun playPreviousEpisode() {
        upNext.finish()
        retriedStream = false
        session.playPreviousEpisode()
    }

    /** The prompt button as it shows ([PlayerUiState.prompt]): the skip button, else up next; null while neither shows. */
    private fun prompt(): PlayerPrompt? = skipPrompt() ?: upNextPrompt()

    /**
     * The skip button as it shows, or null: none in picture in picture (automatic skips still happen), for a guest who
     * may not control the playback, over the error screen, the sleep timer's question or up next. Credits to the end
     * with a next episode are up next's: its button while they count down to it, else up next itself shows. Either
     * says whether it shows only with the controls ([PlayerPrompt.withControls]).
     */
    private fun skipPrompt(): PlayerPrompt? {
        val segment = skip.segment ?: return null
        if (pictureInPicture || watchUi?.canControl == false || info.failure != null || sleep.asking != null || upNext.shown) return null
        if (!skip.visible(controlsVisible())) return null
        val withControls = !skip.visible(controlsVisible = false)
        if (!playsNextEpisode(segment)) return PlayerStates.skip(segment, skip.progress, skip.secondsLeft, withControls)
        val next = info.nextEpisode
        return if (skip.counting && next != null) PlayerStates.upNext(next, skip.progress, skip.secondsLeft, withControls) else null
    }

    /** Up next as it shows, or null: none over the error screen. */
    private fun upNextPrompt(): PlayerPrompt? {
        val next = info.nextEpisode ?: return null
        if (info.failure != null || !upNext.visible(controlsVisible())) return null
        return PlayerStates.upNext(next, upNext.progress, upNext.secondsLeft, withControls = !upNext.visible(controlsVisible = false))
    }

    /**
     * Skips the segment playing: seeks exactly to its end (no keyframe landing back inside it, and no message), or plays
     * the next episode when its credits run to the end and up next stands for them.
     */
    private fun skipSegment() {
        val segment = skip.segment ?: return
        if (!engineState.loaded) return
        skip.markSkipped(segment)
        if (playsNextEpisode(segment)) {
            playNextEpisode()
            return
        }
        val target = seekTo(segment.endSeconds)
        watch?.onLocalSeek(target)
    }

    /**
     * Back on the prompt button: up next stays away until the position is back before where it came up, a skip button
     * until the playback leaves the segment (for credits to the end, up next with it).
     */
    private fun dismissPrompt() {
        if (upNext.shown) {
            upNext.finish()
            return
        }
        val segment = skip.segment ?: return
        skip.dismiss()
        if (segment.toEnd) upNext.finish()
    }

    // Sleep timer

    private fun onSleepSecond(): Unit = update {
        if (sleep.tick()) pauseForSleep()
    }

    /**
     * The sleep timer stops the playback, a stop on this device as the system's are ([PlayerCommand.SystemPause]): in a
     * room whoever controls playback pauses the room, and anyone else pauses here alone ([stopHere]), the room playing
     * on.
     */
    private fun pauseForSleep() {
        val room = watch?.takeIf { it.routesControls }
        when {
            stopsHereAlone() -> stopHere()
            room != null -> room.pause()
            else -> {
                paused.request(true)
                engine.setPaused(true)
            }
        }
    }

    // Upstream

    private fun onChoices(choices: PlayerChoices) = update {
        fit = choices.videoFit
        normalization = choices.audioNormalization
        channels = choices.audioChannels
        swipeGestures = choices.swipeGestures
        customShaders = choices.customShaders
        // Another stored preset runs; the same one stored again leaves the toggle's off for this player as it is.
        if (choices.shaderPreset != savedShader) {
            savedShader = choices.shaderPreset
            shader = runnable(ShaderPreset.of(savedShader, customShaders))
        }
    }

    private fun onEngineState(new: EngineState) {
        val old = engineState
        applyEngineState(old, new)
        // After the state is published: the room may answer with commands.
        watchAdapter.onEngineState(old, new)
    }

    private fun applyEngineState(old: EngineState, new: EngineState) = update {
        engineState = new
        paused.onReport(old.paused, new.paused)
        muted.onReport(old.muted, new.muted)
        volume.onReport(old.volume, new.volume)
        speed.onReport(old.speed, new.speed)
        subtitleDelay.onReport(old.subtitleDelaySeconds, new.subtitleDelaySeconds)
        audioDelay.onReport(old.audioDelaySeconds, new.audioDelaySeconds)
        audioTrack.onReport(old.selection(TrackKind.AUDIO), new.selection(TrackKind.AUDIO))
        subtitleTrack.onReport(old.selection(TrackKind.SUBTITLE), new.selection(TrackKind.SUBTITLE))
        secondSubtitleTrack.onReport(old.secondSelection(), new.secondSelection())
        audioDevice.onReport(old.audioDevice, new.audioDevice)
        pendingSeek?.let { target ->
            if (abs(playedPosition() - target) < settledSeconds()) clearPendingSeek()
        }
        // A new file: nothing about the old one carries over, not even a track choice still on its way (the new file's
        // ids are other tracks). A live rewind's reopen is the same file, its window kept.
        if (old.loaded && !new.loaded && rewindWindow() == null) {
            scrubber.end()
            endHeldSeek()
            clearPendingSeek()
            audioTrack.clear()
            subtitleTrack.clear()
            secondSubtitleTrack.clear()
            roomSpeed = null
            skip.reset()
            upNext.reset()
        }
        // The stream opened: a retry worked, and the next failure offers Retry first again.
        if (!old.loaded && new.loaded) retriedStream = false
        if (!old.eof && new.eof && sleep.onVideoEnded()) {
            // The sleep timer was set for the end of this video: it stops here, without moving on.
            upNext.finish()
            pauseForSleep()
        }
    }

    private fun onSessionInfo(new: SessionInfo) = update {
        val failed = info.failure == null && new.failure != null
        if (new.live && !info.live) {
            liveWatched = Duration.ZERO
            livePlaying = null
        }
        info = new
        // The timeline goes while live without a window, its focus with it: Left and Right move focus along the buttons
        // again.
        if (info.live && rewindWindow() == null) {
            timelineFocused = false
            scrubber.end()
            endHeldSeek()
        }
        // The edge moves on with the clock, paused or not: the time behind shows it every second.
        if (rewindWindow() == null) {
            liveTick?.cancel()
            liveTick = null
        } else if (liveTick == null) {
            liveTick = scope.launch {
                while (true) {
                    delay(LIVE_TICK)
                    update {}
                }
            }
        }
        if (failed) {
            scrubber.end()
            endHeldSeek()
            menu = null
            infoVisible = false
        }
    }

    /**
     * How close the position must come to a seek's target for the seek to count as landed: live with a window, a
     * segment more (a reopen starts at a segment's start, and the edge moves on).
     */
    private fun settledSeconds(): Double = SEEK_SETTLED_SECONDS + (rewindWindow()?.segmentSeconds ?: 0.0)

    /** The live playback's time played here in whole seconds, its clock running while [status] is playing. */
    private fun liveWatchedSeconds(status: PlaybackStatus): Double {
        val since = livePlaying
        val playing = status == PlaybackStatus.PLAYING
        if (playing && since == null) livePlaying = timeSource.markNow()
        if (!playing && since != null) {
            liveWatched += since.elapsedNow()
            livePlaying = null
        }
        return (liveWatched + (livePlaying?.elapsedNow() ?: Duration.ZERO)).inWholeSeconds.toDouble()
    }

    private fun clearPendingSeek() {
        pendingSeek = null
        pendingSeekJob?.cancel()
        pendingSeekJob = null
    }

    private fun EngineState.selection(kind: TrackKind) = TrackChoice(tracks.firstOrNull { it.kind == kind && it.selected }?.id)

    private fun EngineState.secondSelection() = TrackChoice(tracks.firstOrNull { it.kind == TrackKind.SUBTITLE && it.secondary }?.id)

    // State

    /**
     * Applies [block] and publishes the new state and timeline. A pin that goes away (resumed, menu closed, loaded)
     * shows the controls with a fresh auto-hide timer, so they don't vanish the moment the user acts.
     */
    private inline fun update(block: () -> Unit) {
        val wasPinned = pinned()
        block()
        if (wasPinned && !pinned()) show()
        evaluateSkip()
        evaluateUpNext()
        liftSubtitles()
        applyOptions()
        mutableState.value = buildState()
        mutableTimeline.value = buildTimeline()
    }

    /** [update] for the helpers' timers. */
    private fun change(block: () -> Unit): Unit = update(block)

    /** Subtitles above the bottom controls while they show, back in place once they hide or the error takes them. */
    private fun liftSubtitles() {
        val lift = if (controlsVisible() && info.failure == null) controlsHeight else 0.0
        if (lift == subtitleLift) return
        subtitleLift = lift
        engine.setSubtitleLift(lift)
    }

    /**
     * The picture's layout (the whole picture in a picture-in-picture window), the audio options and whether the engine
     * reads the info's stats, as they change.
     */
    private fun applyOptions() {
        val layout = if (pictureInPicture) VideoLayout.DEFAULT else VideoLayout(fit, zoom)
        if (layout != appliedLayout) {
            appliedLayout = layout
            engine.setVideoLayout(layout)
        }
        if (normalization != appliedNormalization) {
            appliedNormalization = normalization
            engine.setAudioNormalization(normalization)
        }
        if (channels != appliedChannels) {
            appliedChannels = channels
            engine.setAudioChannels(channels)
        }
        if (infoVisible != statsWanted) {
            statsWanted = infoVisible
            engine.setStatsWanted(infoVisible)
        }
        applyShaders()
    }

    /**
     * The shader preset's files for the video playing ([files]: NVScaler's goes on HDR video), to the engine once their
     * paths are known, whenever they change. A file that isn't there turns the shaders off for this player.
     */
    private fun applyShaders() {
        val files = shaderFiles ?: return
        val wanted = shader.files(engineState.video?.dynamicRange)
        if (wanted == appliedShaderFiles) return
        appliedShaderFiles = wanted
        shaderJob?.cancel()
        shaderJob = scope.launch {
            val paths = try {
                if (wanted.isEmpty()) emptyList() else files.paths(wanted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, e) { "Couldn't find the shader preset ${shader.id}'s files" }
                update { shader = ShaderPreset.Off }
                return@launch
            }
            engine.setShaders(paths)
        }
    }

    private fun buildState(): PlayerUiState {
        val locale = T.locale
        val status = status()
        val duration = engineState.durationSeconds
        val isPaused = paused.resolve(engineState.paused)
        // During a drift nudge the player plays a little off the room's speed; it shows the room's.
        val currentSpeed = roomSpeed ?: speed.resolve(engineState.speed)
        val next = info.nextEpisode
        val more = moreSubtitles(MoreKey(info.moreSubtitles, engineState.tracks, locale))
        val window = rewindWindow()
        return PlayerUiState(
            title = info.title,
            subtitle = info.subtitle,
            status = status,
            loadingText = when (status) {
                PlaybackStatus.RESOLVING -> T.player.resolving
                PlaybackStatus.OPENING -> T.player.opening
                PlaybackStatus.BUFFERING -> T.player.buffering
                else -> null
            },
            paused = isPaused,
            playPauseLabel = if (isPaused) T.player.play else T.player.pause,
            controlsVisible = controlsVisible(),
            mode = when {
                window != null -> PlaybackMode.LIVE_WINDOW
                info.live -> PlaybackMode.LIVE
                else -> PlaybackMode.VIDEO
            },
            seekable = when {
                window != null -> true
                info.live -> false
                else -> engineState.loaded && duration > 0
            },
            scrubbing = scrubber.preview != null,
            menu = menu,
            audioTracks = audioItems(TrackKey(engineState.tracks, selectedTrack(TrackKind.AUDIO), null, locale)),
            subtitleTracks = subtitleItems(TrackKey(engineState.tracks, selectedTrack(TrackKind.SUBTITLE), selectedSecondSubtitle(), locale)),
            moreSubtitles = more,
            subtitleLanguage = if (menu == PlayerMenu.SUBTITLE_LANGUAGE) more.firstOrNull { it.key == subtitleLanguage } else null,
            speeds = speedItems(currentSpeed to locale),
            speedLabel = PlayerText.speed(currentSpeed),
            subtitleDelay = PlayerStates.delay(TrackKind.SUBTITLE, subtitleDelay.resolve(engineState.subtitleDelaySeconds), config),
            audioDelay = PlayerStates.delay(TrackKind.AUDIO, audioDelay.resolve(engineState.audioDelaySeconds), config),
            volume = PlayerStates.volume(volume.resolve(engineState.volume), muted.resolve(engineState.muted)),
            episodes = if (info.episode) {
                val canChange = watchUi?.canChangeEpisode != false
                EpisodeButtons(previous = canChange && info.hasPreviousEpisode, next = canChange && next != null)
            } else {
                null
            },
            infoVisible = infoVisible,
            info = if (infoVisible) PlaybackInfo.sections(engineState, info.stream, locale) else emptyList(),
            options = options(
                PlayerOptions.Values(
                    fit = fit,
                    zoom = zoom,
                    sleepMinutes = sleep.minutes,
                    sleepAtEndOfVideo = sleep.atEndOfVideo,
                    sleepSecondsLeft = sleep.secondsLeft,
                    autoPlayNext = next?.autoplay,
                    audioDevices = engineState.audioDevices,
                    audioDevice = audioDevice.resolve(engineState.audioDevice),
                    audioNormalization = normalization,
                    audioChannels = channels,
                    locale = locale,
                    watch = watchUi,
                    shader = shader.takeIf { shaderFiles != null },
                    shaders = shaderFiles?.let { ShaderPreset.available(it.renderer, customShaders) }.orEmpty(),
                    importsShaders = importsCustomShaders(),
                ),
            ),
            prompt = prompt(),
            stillWatching = sleep.asking?.let(PlayerStates::stillWatching),
            error = info.failure?.let { PlayerStates.error(it, info.alternativeStreams, retriedStream) },
            osd = osd,
            level = level,
            heldSpeed = heldSpeedFrom?.let { PlayerText.speed(HOLD_SPEED) },
            lockLabel = if (config.profile == InputProfile.TOUCH) T.player.lock.button else null,
            screenLock = lock?.let { stage ->
                ScreenLockState(
                    when (stage) {
                        UnlockStage.HIDDEN -> null
                        UnlockStage.SHOWN -> UnlockButton(armed = false, label = T.player.lock.tapToUnlock)
                        UnlockStage.ARMED -> UnlockButton(armed = true, label = T.player.lock.tapAgainToUnlock)
                    },
                )
            },
            fullscreen = fullscreen,
            watch = watchUi,
            keepAwake = keepAwake(status, isPaused),
            hdrPicture = engineState.video?.dynamicRange.isHdr() && engineState.outputDynamicRange.isHdr(),
        )
    }

    private fun buildTimeline(): TimelineState {
        val position = displayPosition()
        motion = playheadMotion(position)
        val preview = scrubber.preview
        val window = rewindWindow()
        if (window != null) {
            val scrub = preview?.let { PlayerStates.rewindScrub(it, scrubber.origin, window.windowSeconds) }
            return PlayerStates.rewindTimeline(position, window, liveWatchedSeconds(status()), scrub)
        }
        if (info.live) return PlayerStates.liveTimeline(position, liveWatchedSeconds(status()))
        val duration = engineState.durationSeconds
        val chapters = engineState.chapters
        return PlayerStates.timeline(
            position = position,
            duration = duration,
            cacheEnd = engineState.cacheEndSeconds,
            chapters = chapterMarks(chapters to duration),
            scrub = preview?.let { PlayerStates.scrub(it, scrubber.origin, duration, chapters.lastOrNull { chapter -> chapter.startSeconds <= it }) },
            motion = motion,
        )
    }

    /**
     * [TimelineState.motion] at [position]: while the engine plays a video (not live) with no seek or scrub under way,
     * the position moves on from where the engine reported it, timed from when that report came; null while it stands,
     * so the bar shows the position as it is. The engine's own pause and speed count, not ones it hasn't reported yet:
     * the played part stops where the engine did.
     */
    private fun playheadMotion(position: Double): PlayheadMotion? {
        val moving = !info.live && info.failure == null && scrubber.preview == null && heldSeek == null && pendingSeek == null &&
            engineState.loaded && !engineState.paused && !engineState.buffering && !engineState.eof && engineState.speed > 0
        if (!moving) return null
        val current = motion
        if (current != null && current.speed == engineState.speed && motionFrom == position) return current
        motionFrom = position
        return PlayheadMotion(engineState.speed, timeSource.markNow())
    }

    /** HDR (HDR10, HLG, Dolby Vision): known and not SDR. */
    private fun DynamicRange?.isHdr(): Boolean = this != null && this != DynamicRange.SDR

    private data class TrackKey(val tracks: List<Track>, val selected: Long?, val second: Long?, val locale: String)

    private data class MoreKey(val subtitles: List<ExternalSubtitle>, val tracks: List<Track>, val locale: String)

    /**
     * [watchPlayer]: the room's play, pause and seek go the way the viewer's do (the session saves the seek, the
     * trackers hear it) without the controls or the OSD, each returning once the engine reports it done; the speed shows
     * as the room's ([roomSpeed]) while the room nudges it. A seek waits for the file to load first; one the viewer
     * just made here (the room repeating it) isn't made twice.
     */
    private inner class StoreAdapter : RoomPlayer {
        override var onChanged: (() -> Unit)? = null

        override val positionMs: Long get() = (engineState.positionSeconds * 1000).roundToLong()
        override val durationMs: Long?
            get() = engineState.durationSeconds.takeIf { engineState.loaded && it > 0 }?.let { (it * 1000).roundToLong() }
        override val paused: Boolean get() = this@PlayerStore.paused.resolve(engineState.paused) || engineState.eof
        override val buffering: Boolean get() = engineState.buffering
        override val firstFrameShown: Boolean get() = engineState.loaded && engineState.playbackRestarts > 0
        override val canSetRate: Boolean get() = !engineState.audioPassthrough

        /**
         * The engines' absolute seeks land where asked, so 0 while the file can seek. A file that shows frames but can't
         * seek (a stream without an index) is followed by its speed alone ([CANNOT_SEEK_ACCURACY_MS]), rather than sought
         * every couple of seconds by a room whose seek it refuses.
         */
        override val seekAccuracyMs: Long get() = if (firstFrameShown && !engineState.seekable) CANNOT_SEEK_ACCURACY_MS else 0

        override suspend fun play() {
            if (released) return
            setPaused(false)
            awaitEngine { !it.paused }
        }

        override suspend fun pause() {
            if (released) return
            setPaused(true)
            awaitEngine { it.paused }
        }

        override suspend fun seek(positionMs: Long) {
            if (released || info.live) return
            awaitEngine { it.loaded }
            if (released || !engineState.loaded) return
            val target = positionMs / 1000.0
            val pending = pendingSeek
            if (pending != null && abs(pending - target) <= SAME_SEEK_SECONDS) {
                // The viewer's own seek there, which the room repeats: it lands once.
                awaitEngine { abs(it.positionSeconds - pending) < SEEK_SETTLED_SECONDS }
                return
            }
            val restarts = engineState.playbackRestarts
            update { seekTo(target) }
            awaitEngine { it.playbackRestarts > restarts }
        }

        override suspend fun setRate(rate: Double) {
            if (released) return
            update {
                roomSpeed = watch?.roomRate
                engine.setSpeed(rate)
            }
        }

        fun onEngineState(old: EngineState, new: EngineState) {
            val changed = old.paused != new.paused || old.buffering != new.buffering || old.loaded != new.loaded ||
                old.eof != new.eof || old.durationSeconds != new.durationSeconds || old.playbackRestarts != new.playbackRestarts ||
                old.seekable != new.seekable
            if (changed) onChanged?.invoke()
        }

        private suspend fun awaitEngine(done: (EngineState) -> Boolean) {
            withTimeoutOrNull(COMMAND_TIMEOUT) { session.engineState.first(done) }
        }
    }

    private companion object {
        const val TAG = "PlayerStore"

        /** An imported shader's file ends so: mpv's user shaders are GLSL. */
        const val GLSL = ".glsl"

        /** A seek's target shows until the engine reports a position this close, or [SEEK_SETTLE_TIMEOUT] passes. */
        const val SEEK_SETTLED_SECONDS = 1.0
        val SEEK_SETTLE_TIMEOUT = 1500.milliseconds

        /** A held seek key's target is sought this long after its last repeat when its release hasn't come. */
        val HELD_SEEK_COMMIT = 300.milliseconds

        /** A live window's time behind is a whole-second timecode. */
        val LIVE_TICK = 1000.milliseconds

        /** How long a room's command waits for the engine to report it done before it returns anyway. */
        val COMMAND_TIMEOUT = 10_000.milliseconds

        /** The seek accuracy of a file that can't seek: longer than any file (Open Watch Party's 7-day cap on positions). */
        const val CANNOT_SEEK_ACCURACY_MS = 604_800_000L

        /** A room's seek this close to the viewer's own pending one is that seek, not another. */
        const val SAME_SEEK_SECONDS = 0.25

        /** mpv lands a chapter seek a little before the chapter start. */
        const val CHAPTER_TOLERANCE_SECONDS = 0.5
        const val PREVIOUS_CHAPTER_GRACE_SECONDS = 3.0

        /** Subtitles never move up by more than half the player: taller controls would push them off the picture. */
        const val MAX_SUBTITLE_LIFT = 0.5

        /** A finger held on the video plays at this speed ([PlayerCommand.HoldSpeed]). */
        const val HOLD_SPEED = 2.0

        /** The unlock button hides this long after it showed, and an armed one waits as long for its second tap. */
        val UNLOCK_PROMPT = 3.seconds

        /** A pinch zooms once its scale has moved this far from 1 on [PINCH_MOVES] changes in a row. */
        const val PINCH_THRESHOLD = 0.06
        const val PINCH_MOVES = 3

        /** A pinch ending this close to 100 % (in whole percent) lands on 100 % exactly. */
        const val PINCH_SNAP_PERCENT = 3
    }
}

/** The screen lock's unlock button ([PlayerStore.lockScreen]): hidden, shown, or armed for the tap that unlocks. */
private enum class UnlockStage { HIDDEN, SHOWN, ARMED }
