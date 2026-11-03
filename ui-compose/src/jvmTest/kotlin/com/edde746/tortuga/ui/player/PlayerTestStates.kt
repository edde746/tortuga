package com.edde746.tortuga.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.VideoFit
import com.edde746.tortuga.presentation.player.DelayState
import com.edde746.tortuga.presentation.player.EpisodeButtons
import com.edde746.tortuga.presentation.player.ErrorAction
import com.edde746.tortuga.presentation.player.ErrorButton
import com.edde746.tortuga.presentation.player.ErrorState
import com.edde746.tortuga.presentation.player.OptionChoice
import com.edde746.tortuga.presentation.player.PlaybackMode
import com.edde746.tortuga.presentation.player.PlaybackStatus
import com.edde746.tortuga.presentation.player.PlayerConfig
import com.edde746.tortuga.presentation.player.PlayerOption
import com.edde746.tortuga.presentation.player.PlayerOptionId
import com.edde746.tortuga.presentation.player.PlayerPrompt
import com.edde746.tortuga.presentation.player.PlayerUiState
import com.edde746.tortuga.presentation.player.StillWatchingState
import com.edde746.tortuga.presentation.player.TimelineState
import com.edde746.tortuga.presentation.player.VolumeState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.app.LocalTortugaPlatform
import com.edde746.tortuga.ui.app.TortugaPlatform
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.test.assertTrue

internal val testPlayerConfig = PlayerConfig(InputProfile.DESKTOP, autoHideDelayMillis = 3000, doubleClickIntervalMillis = 300)

/**
 * The options as the store lists them for an episode with a next one: the picture filled (adjusted), no zoom, the sleep
 * timer off, auto-play next on, normalization off, the audio mixed down to stereo (adjusted).
 */
internal val testPlayerOptions: List<PlayerOption> = run {
    val text = T.player.options
    fun fit(fit: VideoFit, label: String) = OptionChoice(label, null, selected = fit == VideoFit.COVER, PlayerCommand.SetVideoFit(fit))
    fun channels(channels: AudioChannels, label: String, detail: String) =
        OptionChoice(label, detail, selected = channels == AudioChannels.STEREO, PlayerCommand.SetAudioChannels(channels))
    listOf(
        PlayerOption(
            PlayerOptionId.VIDEO_FIT, text.videoFit, text.fit.cover, adjusted = true, PlayerMenu.VIDEO_FIT,
            listOf(fit(VideoFit.CONTAIN, text.fit.contain), fit(VideoFit.COVER, text.fit.cover), fit(VideoFit.FILL, text.fit.fill)),
            on = null, toggle = null,
        ),
        PlayerOption(
            PlayerOptionId.ZOOM, text.zoom, "100%", adjusted = false, PlayerMenu.ZOOM,
            listOf(
                OptionChoice(text.resetZoom, null, selected = false, PlayerCommand.ResetZoom),
                OptionChoice("100%", null, selected = true, PlayerCommand.SetZoom(1.0)),
                OptionChoice("150%", null, selected = false, PlayerCommand.SetZoom(1.5)),
            ),
            on = null, toggle = null,
        ),
        PlayerOption(
            PlayerOptionId.SLEEP_TIMER, text.sleepTimer, text.off, adjusted = false, PlayerMenu.SLEEP_TIMER,
            listOf(
                OptionChoice(text.off, null, selected = true, PlayerCommand.CancelSleepTimer),
                OptionChoice(text.sleepEndOfVideo, null, selected = false, PlayerCommand.SleepAtEndOfVideo),
            ),
            on = null, toggle = null,
        ),
        PlayerOption(
            PlayerOptionId.AUTO_PLAY_NEXT, text.autoPlayNext, text.on, adjusted = false, page = null, choices = emptyList(),
            on = true, toggle = PlayerCommand.SetAutoPlayNext(false),
        ),
        PlayerOption(
            PlayerOptionId.AUDIO_NORMALIZATION, text.audioNormalization, text.off, adjusted = false, page = null, choices = emptyList(),
            on = false, toggle = PlayerCommand.SetAudioNormalization(true),
        ),
        PlayerOption(
            PlayerOptionId.AUDIO_CHANNELS, text.audioChannels, text.channels.stereo, adjusted = true, PlayerMenu.AUDIO_CHANNELS,
            listOf(
                channels(AudioChannels.ORIGINAL, text.channels.original, text.channels.originalDetail),
                channels(AudioChannels.SURROUND_51, text.channels.surround51, text.channels.surround51Detail),
                channels(AudioChannels.STEREO, text.channels.stereo, text.channels.stereoDetail),
            ),
            on = null, toggle = null,
        ),
    )
}

/** [testPlayerState]'s timeline: 15 s into a 20 s episode, all of it buffered. */
internal val testTimeline = TimelineState("0:15", "0:20", "−0:05", "0:15 / 0:20", 0.75f, 1f, emptyList(), positionSeconds = 15.0, durationSeconds = 20.0, liveLabel = "")

/**
 * An error card as the store builds it: [actions]' buttons in order, [primary] the filled and focused one (Retry, the
 * next stream and Close by default; an error video's Choose another stream, Watch the message, Retry and Close).
 */
internal fun testError(
    primary: ErrorAction = ErrorAction.RETRY,
    actions: List<ErrorAction> = listOf(ErrorAction.RETRY, ErrorAction.NEXT, ErrorAction.CLOSE),
    title: String = "Can't play this stream",
    message: String = "The stream is no longer available.",
    alternatives: String? = null,
    errorVideo: Boolean = false,
) = ErrorState(
    title,
    message,
    alternatives,
    buttons = actions.map { action ->
        val (label, command) = when (action) {
            ErrorAction.CHOOSE_STREAM -> T.player.errorVideo.chooseStream to PlayerCommand.ChooseStream
            ErrorAction.WATCH -> T.player.errorVideo.watch to PlayerCommand.WatchErrorVideo
            ErrorAction.RETRY -> T.player.error.retry to PlayerCommand.Retry
            ErrorAction.NEXT -> T.player.error.nextStream to PlayerCommand.NextStream
            ErrorAction.CLOSE -> T.player.error.close to PlayerCommand.Close
        }
        ErrorButton(action, label, command, primary = action == primary)
    },
    errorVideo = errorVideo,
)

/** [testError] for an error video: another stream first and primary, then its message, Retry and Close. */
internal val testErrorVideo = testError(
    primary = ErrorAction.CHOOSE_STREAM,
    actions = listOf(ErrorAction.CHOOSE_STREAM, ErrorAction.WATCH, ErrorAction.RETRY, ErrorAction.CLOSE),
    title = "This isn't the episode",
    message = "The addon sent an error video instead of the stream.",
    errorVideo = true,
)

/** The subtitle (else the audio) delay's control as the store builds it: at zero, else moved a step later ([moved]). */
internal fun testDelay(subtitles: Boolean, moved: Boolean = false): DelayState {
    val text = T.player.menu
    val step = testPlayerConfig.steps.delaySeconds
    return DelayState(
        menu = if (subtitles) PlayerMenu.SUBTITLE_DELAY else PlayerMenu.AUDIO_DELAY,
        title = if (subtitles) text.subtitleDelay else text.audioDelay,
        label = if (moved) "+0.1 s" else "0.0 s",
        decreaseLabel = if (subtitles) text.subtitleDelayEarlier else text.audioDelayEarlier,
        increaseLabel = if (subtitles) text.subtitleDelayLater else text.audioDelayLater,
        decrease = if (subtitles) PlayerCommand.AdjustSubtitleDelay(-step) else PlayerCommand.AdjustAudioDelay(-step),
        increase = if (subtitles) PlayerCommand.AdjustSubtitleDelay(step) else PlayerCommand.AdjustAudioDelay(step),
        reset = if (subtitles) PlayerCommand.SetSubtitleDelay(0.0) else PlayerCommand.SetAudioDelay(0.0),
        canDecrease = true,
        canIncrease = true,
        canReset = moved,
    )
}

/**
 * An episode playing with the controls up (one before it and one after, both buttons on unless [episodes] says
 * otherwise; null: a movie), the prompt button, the sleep timer's question, a menu or an error as given.
 */
internal fun testPlayerState(
    prompt: PlayerPrompt? = null,
    error: ErrorState? = null,
    menu: PlayerMenu? = null,
    stillWatching: StillWatchingState? = null,
    episodes: EpisodeButtons? = EpisodeButtons(previous = true, next = true),
) = PlayerUiState(
    title = "Chernobyl",
    subtitle = "S1E1 · 1:23:45",
    status = if (error != null) PlaybackStatus.FAILED else PlaybackStatus.PLAYING,
    loadingText = null,
    paused = false,
    playPauseLabel = "Pause",
    controlsVisible = true,
    mode = PlaybackMode.VIDEO,
    seekable = true,
    scrubbing = false,
    menu = menu,
    audioTracks = emptyList(),
    subtitleTracks = emptyList(),
    speeds = emptyList(),
    speedLabel = "Normal",
    subtitleDelay = testDelay(subtitles = true),
    audioDelay = testDelay(subtitles = false),
    volume = VolumeState(100, 1f, muted = false, label = "100%"),
    episodes = episodes,
    infoVisible = false,
    info = emptyList(),
    options = testPlayerOptions,
    prompt = prompt,
    stillWatching = stillWatching,
    error = error,
    osd = null,
    fullscreen = false,
    keepAwake = error == null,
)

/** The whole player on a 960 × 540 dp TV, showing [state] and [timeline]; [probe] sees its named elements' bounds. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.tvPlayer(
    probe: LayoutProbe? = null,
    timeline: StateFlow<TimelineState> = MutableStateFlow(testTimeline),
    state: () -> PlayerUiState,
) = player(FormFactor.TV, probe = probe, timeline = timeline, state = state)

/**
 * The whole player on a [size] window (960 × 540 dp) of [formFactor] with the app's overlay host (sheets drawn over
 * it, as in the app) and its arrow-key focus moves, showing [state] and [timeline]: [onCommand] gets what its controls
 * send, [onInput] its keys and pointer events (none taken by default), [dispatcher] carries the system's back, [probe]
 * sees its named elements' bounds, [platform] (where given) is the app's platform the player reaches (its file dialogs).
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.player(
    formFactor: FormFactor,
    dispatcher: NavigationEventDispatcher? = null,
    probe: LayoutProbe? = null,
    onCommand: (PlayerCommand) -> Unit = {},
    onInput: (InputEvent) -> Boolean = { false },
    size: DpSize = DpSize(960.dp, 540.dp),
    timeline: StateFlow<TimelineState> = MutableStateFlow(testTimeline),
    platform: TortugaPlatform? = null,
    state: () -> PlayerUiState,
) = setContent {
    val owner = remember {
        object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher ?: NavigationEventDispatcher()
        }
    }
    val locals = listOfNotNull(
        LocalNavigationEventDispatcherOwner provides owner,
        LocalLayoutProbe provides probe,
        platform?.let { LocalTortugaPlatform provides it },
    )
    CompositionLocalProvider(*locals.toTypedArray()) {
        TortugaTheme {
            Box(Modifier.size(size)) {
                AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                    OverlayHostBox {
                        PlayerScreen(state(), timeline, testPlayerConfig, onCommand = onCommand, onInput = onInput, onTimelineFocusChanged = {}, onControlsHeightChanged = {}) {}
                    }
                }
            }
        }
    }
}

/** Presses [key] (down and up) where focus is, and waits for the UI to settle. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.press(key: Key) {
    onRoot().performKeyInput { pressKey(key) }
    waitForIdle()
}

/** The harness names' bounds (window pixels) and the one holding focus, as the layout probes report them. */
internal class PlayerProbe : LayoutProbe {
    val bounds = HashMap<String, Rect>()
    var focused: String? = null

    override fun place(name: String, bounds: Rect?) {
        if (bounds != null) this.bounds[name] = bounds else this.bounds.remove(name)
    }

    override fun focus(name: String, focused: Boolean) {
        if (focused) this.focused = name else if (this.focused == name) this.focused = null
    }
}

/**
 * Fails where [what], at [bounds], comes nearer than [gap] pixels (less one for rounding) to any of [controls], each
 * compared on its own (not a bar's frame), or where one of them isn't on screen.
 */
internal fun PlayerProbe.requireClear(what: String, bounds: Rect, controls: List<String>, gap: Float) {
    val missing = controls.filter { it !in this.bounds }
    assertTrue(missing.isEmpty(), "$what: $missing not on screen")
    val near = controls.map { it to this.bounds.getValue(it) }.filter { (_, control) ->
        // Their distance along the axis they're apart on; negative where they overlap.
        val apart = max(max(bounds.left - control.right, control.left - bounds.right), max(bounds.top - control.bottom, control.top - bounds.bottom))
        apart < gap - 1f
    }
    assertTrue(near.isEmpty(), "$what ($bounds) less than $gap px from ${near.joinToString { (name, control) -> "$name $control" }}")
}
