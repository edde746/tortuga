package com.edde746.tortuga.input

import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.VideoFit

/**
 * The player's menus. At most one is open; while it is, the controls stay up. [OPTIONS] lists the player's options
 * (`PlayerUiState.options`); the pages after it hold one option's choices each, and Back from one returns to
 * [OPTIONS]. [MORE_SUBTITLES] lists the languages of the subtitles found but not added (`PlayerUiState.moreSubtitles`)
 * and [SUBTITLE_LANGUAGE] one language's (`PlayerUiState.subtitleLanguage`); Back goes from one to the other and on to
 * [SUBTITLES]. [WATCH_TOGETHER] is the room panel (`PlayerUiState.watch`), opened from the options or the session
 * indicator; Back closes it.
 */
enum class PlayerMenu {
    AUDIO,
    SUBTITLES,
    SPEED,
    SUBTITLE_DELAY,
    AUDIO_DELAY,
    MORE_SUBTITLES,
    SUBTITLE_LANGUAGE,
    OPTIONS,
    VIDEO_FIT,
    ZOOM,
    SHADERS,
    SLEEP_TIMER,
    AUDIO_CHANNELS,
    AUDIO_OUTPUT,
    WATCH_TOGETHER,
    ;

    /** One option's page of [OPTIONS]. */
    val isOptionPage: Boolean get() = this in OPTION_PAGES

    /**
     * The menu this one opens from, whose button stands for it: a delay's track menu ([AUDIO], [SUBTITLES]), [SUBTITLES]
     * for the More subtitles pages, [OPTIONS] for an option's page; every other menu is its own.
     */
    val parent: PlayerMenu
        get() = when {
            this == AUDIO_DELAY -> AUDIO
            this == SUBTITLE_DELAY || this == MORE_SUBTITLES || this == SUBTITLE_LANGUAGE -> SUBTITLES
            isOptionPage -> OPTIONS
            else -> this
        }

    /** The menu Back returns to: the one a page was opened from; null where Back closes the menu. */
    val back: PlayerMenu?
        get() = when {
            this == SUBTITLE_LANGUAGE -> MORE_SUBTITLES
            this == MORE_SUBTITLES -> SUBTITLES
            isOptionPage -> OPTIONS
            else -> null
        }

    private companion object {
        val OPTION_PAGES = setOf(VIDEO_FIT, ZOOM, SHADERS, SLEEP_TIMER, AUDIO_CHANNELS, AUDIO_OUTPUT)
    }
}

/**
 * Everything the player can be told to do. Keymaps turn [InputEvent]s into commands; the views' buttons,
 * sliders and menus send them directly through the store's `dispatch`, the platform's audio session its
 * [SystemPause] and [SystemResume].
 */
sealed interface PlayerCommand {
    data object TogglePause : PlayerCommand
    data object Play : PlayerCommand
    data object Pause : PlayerCommand

    /**
     * The system stopped the playback on this device, not the viewer: a call or another app took the audio (Android's
     * audio focus, an iOS or tvOS interruption), the audio output went (headphones unplugged, AirPods or a Bluetooth
     * device gone), the platform's audio restarted, the app's task was removed. It pauses as [Pause] does, but in a
     * watch-together room only whoever controls playback pauses the room; anyone else's player pauses on this device
     * alone and the room plays on (`PlayerStore`'s room rule).
     */
    data object SystemPause : PlayerCommand

    /**
     * What [SystemPause] was for is over and the system says to play on: Android's audio focus back after a transient
     * loss, an iOS or tvOS interruption ended with its resume option.
     */
    data object SystemResume : PlayerCommand

    /** Seek relative to the shown position; negative goes back. */
    data class SeekBy(val seconds: Double) : PlayerCommand

    /** Seek to [seconds] from the start: the OS media controls' seek bar. */
    data class SeekTo(val seconds: Double) : PlayerCommand

    /** Seek to [fraction] (0–1) of the duration: digit keys jump to tenths. */
    data class SeekToFraction(val fraction: Double) : PlayerCommand

    /**
     * Drag scrubbing: move the preview to [fraction] (0–1) of the duration without seeking. The first one starts a
     * scrub; [CommitScrub] seeks to the preview on release, [CancelScrub] drops it.
     */
    data class ScrubTo(val fraction: Double) : PlayerCommand

    /**
     * Remote scrubbing: step the preview one step back or [forward]. From a key press, holding the key keeps it moving,
     * faster the longer it is held, until the key comes up; the preview then commits after a short pause unless
     * another step, [CommitScrub] or [CancelScrub] comes first.
     */
    data class DpadScrub(val forward: Boolean) : PlayerCommand
    data object CommitScrub : PlayerCommand
    data object CancelScrub : PlayerCommand

    /** Change the volume by [delta] percentage points. */
    data class AdjustVolume(val delta: Double) : PlayerCommand

    /** Set the volume, in percent. */
    data class SetVolume(val percent: Double) : PlayerCommand
    data object ToggleMute : PlayerCommand

    /**
     * The screen's brightness by [delta], a share of its range: a swipe up the left half of the video on a touch screen,
     * the player's whole height being the whole range. Where the platform changes it (`DeviceLevels`).
     */
    data class AdjustBrightness(val delta: Double) : PlayerCommand

    /**
     * The system's media volume by [delta], a share of its range: a swipe up the right half of the video on a touch
     * screen, as [AdjustBrightness]. Not the player's own volume ([AdjustVolume]).
     */
    data class AdjustSystemVolume(val delta: Double) : PlayerCommand

    data object ToggleFullscreen : PlayerCommand
    data object ExitFullscreen : PlayerCommand

    /** Select a track; `id == null` turns subtitles off, the second subtitle with them. */
    data class SelectTrack(val kind: TrackKind, val id: Long?) : PlayerCommand

    /**
     * A subtitle row's second action (a long press, a right-click, a held select) on track [id], `null` the Off row: a
     * track that is neither the subtitle nor the second subtitle shows as the second, at the top of the picture; the
     * second's own row or Off turns the second off; the subtitle's row does nothing. The menu stays open.
     */
    data class ToggleSecondSubtitle(val id: Long?) : PlayerCommand

    /** Switch to the next audio track, wrapping around. */
    data object CycleAudio : PlayerCommand

    /** Switch to the next subtitle track, then off, then the first again. */
    data object CycleSubtitles : PlayerCommand

    /** Open [PlayerMenu.SUBTITLE_LANGUAGE] on the language of `PlayerUiState.moreSubtitles` with [key]. */
    data class OpenSubtitleLanguage(val key: String) : PlayerCommand

    /** Add the subtitle at [url] from `PlayerUiState.moreSubtitles` and select it. [url] can carry a key. */
    data class AddSubtitle(val url: String) : PlayerCommand {
        override fun toString(): String = "AddSubtitle(url=<redacted>)"
    }

    data class SetSpeed(val speed: Double) : PlayerCommand

    /** The next faster speed of the speed menu. */
    data object SpeedUp : PlayerCommand

    /** The next slower speed of the speed menu. */
    data object SpeedDown : PlayerCommand
    data object ResetSpeed : PlayerCommand

    /** A finger held on the video: 2× while it is held, until [ReleaseSpeed]. */
    data object HoldSpeed : PlayerCommand

    /** The held finger lifted: the speed [HoldSpeed] replaced comes back. */
    data object ReleaseSpeed : PlayerCommand

    /** Change the subtitle delay by [deltaSeconds]; positive shows subtitles later. */
    data class AdjustSubtitleDelay(val deltaSeconds: Double) : PlayerCommand
    data class SetSubtitleDelay(val seconds: Double) : PlayerCommand

    /** Change the audio delay by [deltaSeconds]; positive plays audio later. */
    data class AdjustAudioDelay(val deltaSeconds: Double) : PlayerCommand
    data class SetAudioDelay(val seconds: Double) : PlayerCommand

    data object NextChapter : PlayerCommand

    /** Back to the start of the current chapter, or to the previous one when just past a chapter start. */
    data object PreviousChapter : PlayerCommand

    /** Play the next episode now (the controls' button, up next's prompt button, a remote's Next key). */
    data object NextEpisode : PlayerCommand

    /** Play the episode before this one (the controls' button, a remote's Previous key). */
    data object PreviousEpisode : PlayerCommand

    /**
     * The skip button's action: seek exactly to the end of the intro, recap or credits playing, or play the next episode
     * for credits to the end of an episode with a next one. In a room it seeks for everyone, as any seek does.
     */
    data object SkipSegment : PlayerCommand

    /** A live playback behind its live edge ([com.edde746.tortuga.playback.session.LiveWindow]): back to the edge. */
    data object GoToLive : PlayerCommand

    /** After an error: try the same stream again. */
    data object Retry : PlayerCommand

    /** After an error: play the next of the session's alternative streams. */
    data object NextStream : PlayerCommand

    /** After an error video: ask for another stream in the video's Streams sheet. */
    data object ChooseStream : PlayerCommand

    /** After an error video: play it for its message, recording nothing. */
    data object WatchErrorVideo : PlayerCommand

    /** How the picture fills the player; this device keeps it for the next video. */
    data class SetVideoFit(val fit: VideoFit) : PlayerCommand

    /** The viewer's zoom on the picture, a factor (1.0 for none), kept in whole percent from 50 % to 200 %. */
    data class SetZoom(val zoom: Double) : PlayerCommand
    data object ZoomIn : PlayerCommand
    data object ZoomOut : PlayerCommand
    data object ResetZoom : PlayerCommand

    /** Run the shader preset [id] (`ShaderPreset.id`); this device keeps it for the next video. */
    data class SetShaderPreset(val id: String) : PlayerCommand

    /**
     * Turn the shaders off for this playback, or on again (`ShaderPreset.toggled`): on is kept for the next video, off
     * only for this playback.
     */
    data object ToggleShaders : PlayerCommand

    /**
     * Import the shader the system's open dialog picked: its file's [fileName] (a `.glsl` file's, named after it in the
     * menu) and [source]. It runs at once, and this device keeps it.
     */
    data class ImportShader(val fileName: String, val source: String) : PlayerCommand

    /** The file the open dialog picked for [ImportShader] couldn't be read as UTF-8 text: the import fails. */
    data object ShaderImportFailed : PlayerCommand

    /** Delete the imported shader [id] (`CustomShader.id`); the shaders go off if it was running. */
    data class DeleteShader(val id: String) : PlayerCommand

    /** Pause the playback [minutes] from now, after asking whether anyone is still watching. */
    data class SetSleepTimer(val minutes: Int) : PlayerCommand

    /** Stop at the end of the video playing: it pauses there instead of moving on to the next episode. */
    data object SleepAtEndOfVideo : PlayerCommand
    data object CancelSleepTimer : PlayerCommand

    /** Answer the sleep timer's "Still watching?": keep playing, with the timer started again. */
    data object KeepWatching : PlayerCommand

    /** Play the next episode on its own at the end of this one (the profile's binge watching setting). */
    data class SetAutoPlayNext(val enabled: Boolean) : PlayerCommand

    /** Even out the audio's loudness; this device keeps it for the next video. */
    data class SetAudioNormalization(val enabled: Boolean) : PlayerCommand

    /** The most channels the audio plays with; this device keeps it for the next video. */
    data class SetAudioChannels(val channels: AudioChannels) : PlayerCommand

    /** Play to the audio output [name] (`EngineState.audioDevices`), for this playback. */
    data class SetAudioDevice(val name: String) : PlayerCommand

    /** Show or hide the playback info panel. */
    data object ToggleInfo : PlayerCommand

    data object ShowControls : PlayerCommand
    data object HideControls : PlayerCommand
    data object ToggleControls : PlayerCommand

    /**
     * Lock the screen against stray touches (Lock screen in a touch screen's options menu): the menu closes, and until
     * the screen unlocks the controls stay hidden and no tap, double tap, swipe, pinch or hold on the video acts; keys,
     * the system's media controls and picture in picture still do.
     */
    data object LockScreen : PlayerCommand

    /** The locked screen's unlock button was tapped: the first tap arms it, a second on the armed button unlocks. */
    data object UnlockPress : PlayerCommand

    data class OpenMenu(val menu: PlayerMenu) : PlayerCommand
    data object CloseMenu : PlayerCommand

    /**
     * Back out of the innermost layer: a scrub, an option's page (back to the options), a menu, the sleep timer's
     * question (as keep watching), the up-next card, the skip button, the info panel, the controls; with none of those,
     * leave the player.
     */
    data object Back : PlayerCommand

    /** Leave the player. */
    data object Close : PlayerCommand

    /** Watch together: the room panel's and a guest's prompt's actions. */
    data class Watch(val command: WatchCommand) : PlayerCommand
}

/**
 * Whether a held key's auto-repeat repeats the command: a seek's repeats move its target, which is sought once the key
 * comes up. Toggles and one-shot actions act on the first press only: a held Space must not flicker between play and
 * pause.
 */
val PlayerCommand.isRepeatable: Boolean
    get() = when (this) {
        is PlayerCommand.SeekBy,
        is PlayerCommand.AdjustVolume,
        is PlayerCommand.AdjustBrightness,
        is PlayerCommand.AdjustSystemVolume,
        PlayerCommand.SpeedUp,
        PlayerCommand.SpeedDown,
        is PlayerCommand.AdjustSubtitleDelay,
        is PlayerCommand.AdjustAudioDelay,
        PlayerCommand.ZoomIn,
        PlayerCommand.ZoomOut,
        -> true
        is PlayerCommand.SetShaderPreset,
        PlayerCommand.ToggleShaders,
        is PlayerCommand.ImportShader,
        PlayerCommand.ShaderImportFailed,
        is PlayerCommand.DeleteShader,
        PlayerCommand.TogglePause,
        PlayerCommand.Play,
        PlayerCommand.Pause,
        PlayerCommand.SystemPause,
        PlayerCommand.SystemResume,
        is PlayerCommand.SeekTo,
        is PlayerCommand.SeekToFraction,
        is PlayerCommand.ScrubTo,
        is PlayerCommand.DpadScrub,
        PlayerCommand.CommitScrub,
        PlayerCommand.CancelScrub,
        is PlayerCommand.SetVolume,
        PlayerCommand.ToggleMute,
        PlayerCommand.ToggleFullscreen,
        PlayerCommand.ExitFullscreen,
        is PlayerCommand.SelectTrack,
        is PlayerCommand.ToggleSecondSubtitle,
        PlayerCommand.CycleAudio,
        PlayerCommand.CycleSubtitles,
        is PlayerCommand.OpenSubtitleLanguage,
        is PlayerCommand.AddSubtitle,
        is PlayerCommand.SetSpeed,
        PlayerCommand.ResetSpeed,
        PlayerCommand.HoldSpeed,
        PlayerCommand.ReleaseSpeed,
        is PlayerCommand.SetSubtitleDelay,
        is PlayerCommand.SetAudioDelay,
        PlayerCommand.NextChapter,
        PlayerCommand.PreviousChapter,
        PlayerCommand.NextEpisode,
        PlayerCommand.PreviousEpisode,
        PlayerCommand.SkipSegment,
        PlayerCommand.GoToLive,
        PlayerCommand.Retry,
        PlayerCommand.NextStream,
        PlayerCommand.ChooseStream,
        PlayerCommand.WatchErrorVideo,
        is PlayerCommand.SetVideoFit,
        is PlayerCommand.SetZoom,
        PlayerCommand.ResetZoom,
        is PlayerCommand.SetSleepTimer,
        PlayerCommand.SleepAtEndOfVideo,
        PlayerCommand.CancelSleepTimer,
        PlayerCommand.KeepWatching,
        is PlayerCommand.SetAutoPlayNext,
        is PlayerCommand.SetAudioNormalization,
        is PlayerCommand.SetAudioChannels,
        is PlayerCommand.SetAudioDevice,
        PlayerCommand.ToggleInfo,
        PlayerCommand.ShowControls,
        PlayerCommand.HideControls,
        PlayerCommand.ToggleControls,
        PlayerCommand.LockScreen,
        PlayerCommand.UnlockPress,
        is PlayerCommand.OpenMenu,
        PlayerCommand.CloseMenu,
        PlayerCommand.Back,
        PlayerCommand.Close,
        is PlayerCommand.Watch,
        -> false
    }

/**
 * The commands that move the position, none of which a live playback takes ([seeksLive] aside), nor a guest in a
 * room that doesn't let it control playback ([roomControl]).
 */
val PlayerCommand.seeks: Boolean
    get() = when (this) {
        is PlayerCommand.SeekBy,
        is PlayerCommand.SeekTo,
        is PlayerCommand.SeekToFraction,
        is PlayerCommand.ScrubTo,
        is PlayerCommand.DpadScrub,
        PlayerCommand.CommitScrub,
        PlayerCommand.NextChapter,
        PlayerCommand.PreviousChapter,
        PlayerCommand.SkipSegment,
        PlayerCommand.GoToLive,
        -> true
        PlayerCommand.TogglePause,
        PlayerCommand.Play,
        PlayerCommand.Pause,
        PlayerCommand.SystemPause,
        PlayerCommand.SystemResume,
        PlayerCommand.CancelScrub,
        is PlayerCommand.AdjustVolume,
        is PlayerCommand.SetVolume,
        PlayerCommand.ToggleMute,
        is PlayerCommand.AdjustBrightness,
        is PlayerCommand.AdjustSystemVolume,
        PlayerCommand.ToggleFullscreen,
        PlayerCommand.ExitFullscreen,
        is PlayerCommand.SelectTrack,
        is PlayerCommand.ToggleSecondSubtitle,
        PlayerCommand.CycleAudio,
        PlayerCommand.CycleSubtitles,
        is PlayerCommand.OpenSubtitleLanguage,
        is PlayerCommand.AddSubtitle,
        is PlayerCommand.SetSpeed,
        PlayerCommand.SpeedUp,
        PlayerCommand.SpeedDown,
        PlayerCommand.ResetSpeed,
        PlayerCommand.HoldSpeed,
        PlayerCommand.ReleaseSpeed,
        is PlayerCommand.AdjustSubtitleDelay,
        is PlayerCommand.SetSubtitleDelay,
        is PlayerCommand.AdjustAudioDelay,
        is PlayerCommand.SetAudioDelay,
        PlayerCommand.NextEpisode,
        PlayerCommand.PreviousEpisode,
        PlayerCommand.Retry,
        PlayerCommand.NextStream,
        PlayerCommand.ChooseStream,
        PlayerCommand.WatchErrorVideo,
        is PlayerCommand.SetVideoFit,
        is PlayerCommand.SetZoom,
        PlayerCommand.ZoomIn,
        PlayerCommand.ZoomOut,
        PlayerCommand.ResetZoom,
        is PlayerCommand.SetShaderPreset,
        PlayerCommand.ToggleShaders,
        is PlayerCommand.ImportShader,
        PlayerCommand.ShaderImportFailed,
        is PlayerCommand.DeleteShader,
        is PlayerCommand.SetSleepTimer,
        PlayerCommand.SleepAtEndOfVideo,
        PlayerCommand.CancelSleepTimer,
        PlayerCommand.KeepWatching,
        is PlayerCommand.SetAutoPlayNext,
        is PlayerCommand.SetAudioNormalization,
        is PlayerCommand.SetAudioChannels,
        is PlayerCommand.SetAudioDevice,
        PlayerCommand.ToggleInfo,
        PlayerCommand.ShowControls,
        PlayerCommand.HideControls,
        PlayerCommand.ToggleControls,
        PlayerCommand.LockScreen,
        PlayerCommand.UnlockPress,
        is PlayerCommand.OpenMenu,
        PlayerCommand.CloseMenu,
        PlayerCommand.Back,
        PlayerCommand.Close,
        is PlayerCommand.Watch,
        -> false
    }

/**
 * The seeks a live playback with a rewind window takes, within the window: those a video's timeline and keys make. The
 * OS media controls' seek bar ([PlayerCommand.SeekTo]) isn't shown live, and a live stream has no chapters or skips.
 */
val PlayerCommand.seeksLive: Boolean
    get() = when (this) {
        is PlayerCommand.SeekBy,
        is PlayerCommand.SeekToFraction,
        is PlayerCommand.ScrubTo,
        is PlayerCommand.DpadScrub,
        PlayerCommand.CommitScrub,
        PlayerCommand.GoToLive,
        -> true
        PlayerCommand.TogglePause,
        PlayerCommand.Play,
        PlayerCommand.Pause,
        PlayerCommand.SystemPause,
        PlayerCommand.SystemResume,
        is PlayerCommand.SeekTo,
        PlayerCommand.CancelScrub,
        is PlayerCommand.AdjustVolume,
        is PlayerCommand.SetVolume,
        PlayerCommand.ToggleMute,
        is PlayerCommand.AdjustBrightness,
        is PlayerCommand.AdjustSystemVolume,
        PlayerCommand.ToggleFullscreen,
        PlayerCommand.ExitFullscreen,
        is PlayerCommand.SelectTrack,
        is PlayerCommand.ToggleSecondSubtitle,
        PlayerCommand.CycleAudio,
        PlayerCommand.CycleSubtitles,
        is PlayerCommand.OpenSubtitleLanguage,
        is PlayerCommand.AddSubtitle,
        is PlayerCommand.SetSpeed,
        PlayerCommand.SpeedUp,
        PlayerCommand.SpeedDown,
        PlayerCommand.ResetSpeed,
        PlayerCommand.HoldSpeed,
        PlayerCommand.ReleaseSpeed,
        is PlayerCommand.AdjustSubtitleDelay,
        is PlayerCommand.SetSubtitleDelay,
        is PlayerCommand.AdjustAudioDelay,
        is PlayerCommand.SetAudioDelay,
        PlayerCommand.NextChapter,
        PlayerCommand.PreviousChapter,
        PlayerCommand.NextEpisode,
        PlayerCommand.PreviousEpisode,
        PlayerCommand.SkipSegment,
        PlayerCommand.Retry,
        PlayerCommand.NextStream,
        PlayerCommand.ChooseStream,
        PlayerCommand.WatchErrorVideo,
        is PlayerCommand.SetVideoFit,
        is PlayerCommand.SetZoom,
        PlayerCommand.ZoomIn,
        PlayerCommand.ZoomOut,
        PlayerCommand.ResetZoom,
        is PlayerCommand.SetShaderPreset,
        PlayerCommand.ToggleShaders,
        is PlayerCommand.ImportShader,
        PlayerCommand.ShaderImportFailed,
        is PlayerCommand.DeleteShader,
        is PlayerCommand.SetSleepTimer,
        PlayerCommand.SleepAtEndOfVideo,
        PlayerCommand.CancelSleepTimer,
        PlayerCommand.KeepWatching,
        is PlayerCommand.SetAutoPlayNext,
        is PlayerCommand.SetAudioNormalization,
        is PlayerCommand.SetAudioChannels,
        is PlayerCommand.SetAudioDevice,
        PlayerCommand.ToggleInfo,
        PlayerCommand.ShowControls,
        PlayerCommand.HideControls,
        PlayerCommand.ToggleControls,
        PlayerCommand.LockScreen,
        PlayerCommand.UnlockPress,
        is PlayerCommand.OpenMenu,
        PlayerCommand.CloseMenu,
        PlayerCommand.Back,
        PlayerCommand.Close,
        is PlayerCommand.Watch,
        -> false
    }

/** What a watch-together room decides of a command: which of its permissions it needs, if any. */
enum class RoomControl {
    /** Another episode: the host's to change. */
    EPISODE,

    /** Play, or play/pause: playback control, or a guest's player paused here alone catching up. */
    PLAY,

    /** Pause: playback control, or the sleep timer's stop. */
    PAUSE,

    /** The speed: playback control. */
    SPEED,

    /** A seek ([PlayerCommand.seeks]): playback control. */
    SEEK,

    /** Nothing the room decides: the system's pause and resume, tracks, menus, volume, the screen's brightness. */
    FREE,
}

/** What a watch-together room decides of this command ([RoomControl]). */
val PlayerCommand.roomControl: RoomControl
    get() = when (this) {
        PlayerCommand.NextEpisode,
        PlayerCommand.PreviousEpisode,
        -> RoomControl.EPISODE
        PlayerCommand.TogglePause,
        PlayerCommand.Play,
        -> RoomControl.PLAY
        PlayerCommand.Pause,
        -> RoomControl.PAUSE
        is PlayerCommand.SetSpeed,
        PlayerCommand.SpeedUp,
        PlayerCommand.SpeedDown,
        PlayerCommand.ResetSpeed,
        PlayerCommand.HoldSpeed,
        -> RoomControl.SPEED
        is PlayerCommand.SeekBy,
        is PlayerCommand.SeekTo,
        is PlayerCommand.SeekToFraction,
        is PlayerCommand.ScrubTo,
        is PlayerCommand.DpadScrub,
        PlayerCommand.CommitScrub,
        PlayerCommand.NextChapter,
        PlayerCommand.PreviousChapter,
        PlayerCommand.SkipSegment,
        PlayerCommand.GoToLive,
        -> RoomControl.SEEK
        PlayerCommand.SystemPause,
        PlayerCommand.SystemResume,
        PlayerCommand.CancelScrub,
        is PlayerCommand.AdjustVolume,
        is PlayerCommand.SetVolume,
        is PlayerCommand.AdjustBrightness,
        is PlayerCommand.AdjustSystemVolume,
        PlayerCommand.ReleaseSpeed,
        PlayerCommand.ToggleMute,
        PlayerCommand.ToggleFullscreen,
        PlayerCommand.ExitFullscreen,
        is PlayerCommand.SelectTrack,
        is PlayerCommand.ToggleSecondSubtitle,
        PlayerCommand.CycleAudio,
        PlayerCommand.CycleSubtitles,
        is PlayerCommand.OpenSubtitleLanguage,
        is PlayerCommand.AddSubtitle,
        is PlayerCommand.AdjustSubtitleDelay,
        is PlayerCommand.SetSubtitleDelay,
        is PlayerCommand.AdjustAudioDelay,
        is PlayerCommand.SetAudioDelay,
        PlayerCommand.Retry,
        PlayerCommand.NextStream,
        PlayerCommand.ChooseStream,
        PlayerCommand.WatchErrorVideo,
        is PlayerCommand.SetVideoFit,
        is PlayerCommand.SetZoom,
        PlayerCommand.ZoomIn,
        PlayerCommand.ZoomOut,
        PlayerCommand.ResetZoom,
        is PlayerCommand.SetShaderPreset,
        PlayerCommand.ToggleShaders,
        is PlayerCommand.ImportShader,
        PlayerCommand.ShaderImportFailed,
        is PlayerCommand.DeleteShader,
        is PlayerCommand.SetSleepTimer,
        PlayerCommand.SleepAtEndOfVideo,
        PlayerCommand.CancelSleepTimer,
        PlayerCommand.KeepWatching,
        is PlayerCommand.SetAutoPlayNext,
        is PlayerCommand.SetAudioNormalization,
        is PlayerCommand.SetAudioChannels,
        is PlayerCommand.SetAudioDevice,
        PlayerCommand.ToggleInfo,
        PlayerCommand.ShowControls,
        PlayerCommand.HideControls,
        PlayerCommand.ToggleControls,
        PlayerCommand.LockScreen,
        PlayerCommand.UnlockPress,
        is PlayerCommand.OpenMenu,
        PlayerCommand.CloseMenu,
        PlayerCommand.Back,
        PlayerCommand.Close,
        is PlayerCommand.Watch,
        -> RoomControl.FREE
    }
