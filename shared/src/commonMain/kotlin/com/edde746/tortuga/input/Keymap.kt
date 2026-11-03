package com.edde746.tortuga.input

/** Which keymap a platform uses. */
enum class InputProfile {
    /** Windows, Linux and macOS: keyboard and mouse. */
    DESKTOP,

    /** Android TV, Fire TV and tvOS: D-pad remote. */
    TV,

    /** Phones and tablets: touch, plus a hardware keyboard when one is attached. */
    TOUCH,
    ;

    /**
     * A click on the video follows the profile's [VideoClickAction] setting: with a mouse. A tap always shows or hides
     * the controls (a mouse on a phone or tablet clicks as a tap), and a remote has no clicks.
     */
    val followsVideoClick: Boolean get() = this == DESKTOP
}

/** The step sizes keymaps use. */
data class InputSteps(
    /** Arrow keys, double taps, a remote's rewind/fast-forward. */
    val seekSeconds: Double = 10.0,
    /** Shift + arrow keys. */
    val longSeekSeconds: Double = 60.0,
    /** Up/down arrows and one wheel notch, in percentage points. */
    val volumePercent: Double = 5.0,
    /** One subtitle- or audio-delay step. */
    val delaySeconds: Double = 0.1,
)

/** The player state a keymap decides by. */
data class InputContext(
    val controlsVisible: Boolean = false,
    val menuOpen: Boolean = false,
    /** The seek bar has focus (TV): left/right then scrub instead of moving focus. */
    val timelineFocused: Boolean = false,
    val scrubbing: Boolean = false,
    /** The prompt button's command while it shows (the skip button, up next: `PlayerUiState.prompt`): Select or Enter presses it. */
    val prompt: PlayerCommand? = null,
    /** The sleep timer's "Still watching?" is up, with its buttons. */
    val stillWatchingShown: Boolean = false,
    val errorShown: Boolean = false,
    val fullscreen: Boolean = false,
    val nextEpisodeAvailable: Boolean = false,
    val previousEpisodeAvailable: Boolean = false,
    /** What a click on the video does with a mouse: the profile's setting. */
    val videoClick: VideoClickAction = VideoClickAction.PAUSE,
    /** A live playback: nothing changes its speed. */
    val live: Boolean = false,
    /** Swipes up and down the video change the brightness and the volume: this device's setting. */
    val swipes: Boolean = true,
)

/**
 * Turns input into [PlayerCommand]s for one [InputProfile]. A null result leaves the input to the platform: focus
 * navigation among the views' controls, activating the focused button, the system's own handling.
 */
interface Keymap {
    fun key(key: InputKey, modifiers: KeyModifiers, context: InputContext): PlayerCommand?

    /** A single click or tap on the video at [x] (0 = left edge, 1 = right edge). */
    fun click(x: Float, context: InputContext): PlayerCommand?

    /** A double click or double tap. When this is non-null, the store holds back [click] until it knows which one. */
    fun doubleClick(x: Float, context: InputContext): PlayerCommand?

    fun wheel(steps: Double, context: InputContext): PlayerCommand?

    /** A vertical swipe on the video on a touch screen ([InputEvent.Swipe]). */
    fun swipe(swipe: InputEvent.Swipe, context: InputContext): PlayerCommand? = null

    /** A finger held on the video on a touch screen, or lifted after the hold ([InputEvent.Hold]). */
    fun hold(held: Boolean, context: InputContext): PlayerCommand? = null

    companion object {
        fun forProfile(profile: InputProfile, steps: InputSteps = InputSteps()): Keymap = when (profile) {
            InputProfile.DESKTOP -> DesktopKeymap(steps)
            InputProfile.TV -> TvKeymap(steps)
            InputProfile.TOUCH -> TouchKeymap(steps)
        }
    }
}

/**
 * Keys that mean the same on every profile: media keys (remotes, keyboards, headsets), a remote's subtitle and
 * audio keys, and Back.
 */
internal fun commonKey(key: InputKey, context: InputContext, steps: InputSteps): PlayerCommand? = when (key) {
    InputKey.MEDIA_PLAY_PAUSE -> PlayerCommand.TogglePause
    InputKey.MEDIA_PLAY -> PlayerCommand.Play
    InputKey.MEDIA_PAUSE -> PlayerCommand.Pause
    InputKey.MEDIA_STOP -> PlayerCommand.Close
    InputKey.MEDIA_FAST_FORWARD -> PlayerCommand.SeekBy(steps.seekSeconds)
    InputKey.MEDIA_REWIND -> PlayerCommand.SeekBy(-steps.seekSeconds)
    InputKey.MEDIA_NEXT -> if (context.nextEpisodeAvailable) PlayerCommand.NextEpisode else PlayerCommand.NextChapter
    InputKey.MEDIA_PREVIOUS -> if (context.previousEpisodeAvailable) PlayerCommand.PreviousEpisode else PlayerCommand.PreviousChapter
    InputKey.CAPTIONS -> PlayerCommand.CycleSubtitles
    InputKey.AUDIO_TRACK -> PlayerCommand.CycleAudio
    InputKey.BACK -> PlayerCommand.Back
    else -> null
}
