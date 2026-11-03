package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputContext
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.playback.mpv.MpvLifecycle
import com.edde746.tortuga.playback.session.LiveWindow
import com.edde746.tortuga.playback.session.SessionInfo
import com.edde746.tortuga.presentation.watch.PlayerWatchUi
import com.edde746.tortuga.presentation.watch.WatchPromptUi
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlin.time.DurationUnit
import kotlin.time.TimeMark

/**
 * Everything the player screen shows, already formatted (views never format), but the timeline: the position, the
 * times, the bar's fill and a scrub's preview change several times a second as the video plays, so they are a flow of
 * their own ([PlayerStore.timeline], [TimelineState]) that only the views drawing them follow, and this changes only as
 * something happens. Static labels (button names) come from `T.player` directly, a menu's title from [menuTitle].
 *
 * @param status what the playback is doing; [loadingText] is the spinner's text while it is loading.
 * @param paused the play/pause button's state; [playPauseLabel] names its action ("Pause" while playing).
 * @param controlsVisible the controls are up: the title and Back, and with [showsPlaybackControls] the rest of them.
 * @param mode a video, live, or live with a rewind window ([PlaybackMode]): what the timeline and the transport show
 *   ([showsTimeline], [showsSeekButtons]).
 * @param seekable the timeline can scrub and seek: a file whose duration is known, or a live window; [canSeek] where
 *   this device may too.
 * @param scrubbing the viewer scrubs: the timeline shows the preview ([TimelineState.scrub]) in place of the position.
 * @param subtitleTracks starts with the "Off" entry; the subtitle's row is [TrackItem.selected], the second subtitle's
 *   [TrackItem.second] ([PlayerCommand.ToggleSecondSubtitle]).
 * @param moreSubtitles the subtitles found for the file but not added to it, by language ([PlayerMenu.MORE_SUBTITLES]):
 *   the languages the subtitle menu already has first, then the others by name, subtitles without a language code
 *   last. The subtitle menu offers them after its tracks while there are any.
 * @param subtitleLanguage the language whose page is open ([PlayerMenu.SUBTITLE_LANGUAGE]); null on any other menu.
 * @param speedLabel the current speed ("Normal", "1.5×").
 * @param episodes the controls' previous and next episode buttons, for an episode of a series; null for a movie.
 * @param info the playback info's sections, while [infoVisible]: Stream, Video, Audio, Subtitles, Network, each with
 *   what is known of it ([InfoSection]); live values (bitrates, frame counts, the cache) change about once a second.
 * @param options the player's options ([PlayerMenu.OPTIONS]), in order: watch together (where the app offers it), the
 *   picture's fit, the zoom, the sleep timer, auto-play of the next episode (with one), the audio output (where the
 *   platform offers several), loudness normalization and the audio channels.
 * @param prompt the prompt button while it shows: the skip button (an intro, a recap, the credits) or up next, one at a
 *   time ([PlayerPrompt]).
 * @param stillWatching the sleep timer's question, once it ran out.
 * @param error the error screen; the controls stay up under it, as the title and Back alone ([showsPlaybackControls]).
 * @param osd a transient message (volume, speed, delay, zoom, track changes by key, the room's toasts), or null.
 * @param level the level a swipe changed, as transient as [osd], on the side it was swiped on: one of the two shows at a
 *   time.
 * @param heldSpeed the speed a finger held on the video plays at ("2×"), for as long as it is held.
 * @param lockLabel the options menu's Lock screen entry ([PlayerCommand.LockScreen]) on a touch screen; null where the
 *   player offers no screen lock (mouse, remote).
 * @param screenLock the screen lock while it is on; null unlocked.
 * @param watch watch together in the player: the session indicator, the room panel ([PlayerMenu.WATCH_TOGETHER]),
 *   a guest's file search and prompt, whether this device may control playback; null where the app offers none.
 * @param keepAwake the display stays awake (no dimming, screen saver or lock): while the video plays, or opens or buffers
 *   unpaused, in the window, fullscreen or picture in picture. Not while it is paused, at its end or failed, while the
 *   player is out of sight outside picture in picture (minimized, hidden, the app in the background), nor once the
 *   player closed. Each platform holds one display-sleep assertion while it is true (`PlatformPlayback.keepDisplayAwake`).
 * @param hdrPicture an HDR video goes out as HDR ([com.edde746.tortuga.playback.EngineState.outputDynamicRange]; not
 *   one tone-mapped to SDR, nor an SDR video in an HDR output, whose white is SDR white): the video behind the controls
 *   can be several times as bright as their white, so what they draw behind themselves goes through [ControlsScrim].
 */
data class PlayerUiState(
    val title: String,
    val subtitle: String?,
    val status: PlaybackStatus,
    val loadingText: String?,
    val paused: Boolean,
    val playPauseLabel: String,
    val controlsVisible: Boolean,
    val mode: PlaybackMode,
    val seekable: Boolean,
    val scrubbing: Boolean,
    val menu: PlayerMenu?,
    val audioTracks: List<TrackItem>,
    val subtitleTracks: List<TrackItem>,
    val moreSubtitles: List<SubtitleLanguage> = emptyList(),
    val subtitleLanguage: SubtitleLanguage? = null,
    val speeds: List<SpeedOption>,
    val speedLabel: String,
    val subtitleDelay: DelayState,
    val audioDelay: DelayState,
    val volume: VolumeState,
    val episodes: EpisodeButtons?,
    val infoVisible: Boolean,
    val info: List<InfoSection>,
    val options: List<PlayerOption>,
    val prompt: PlayerPrompt?,
    val stillWatching: StillWatchingState?,
    val error: ErrorState?,
    val osd: String?,
    val level: LevelIndicator? = null,
    val heldSpeed: String? = null,
    val lockLabel: String? = null,
    val screenLock: ScreenLockState? = null,
    val fullscreen: Boolean,
    val watch: PlayerWatchUi? = null,
    val keepAwake: Boolean,
    val hdrPicture: Boolean = false,
) {
    /** The option whose page [menu] is, if it is one. */
    val openOption: PlayerOption? get() = menu?.let { open -> options.firstOrNull { it.page == open } }

    /**
     * [menu]'s title, the same in every view: a delay's name ([DelayState.title]), the language of a subtitle
     * language's page ([subtitleLanguage]; More subtitles while none is open), an option page's option's name.
     */
    fun menuTitle(menu: PlayerMenu): String = when (menu) {
        PlayerMenu.AUDIO -> T.player.menu.audio
        PlayerMenu.SUBTITLES -> T.player.menu.subtitles
        PlayerMenu.SPEED -> T.player.menu.speed
        PlayerMenu.SUBTITLE_DELAY -> subtitleDelay.title
        PlayerMenu.AUDIO_DELAY -> audioDelay.title
        PlayerMenu.MORE_SUBTITLES -> T.player.menu.moreSubtitles
        PlayerMenu.SUBTITLE_LANGUAGE -> subtitleLanguage?.label ?: T.player.menu.moreSubtitles
        PlayerMenu.OPTIONS -> T.player.options.title
        PlayerMenu.VIDEO_FIT -> T.player.options.videoFit
        PlayerMenu.ZOOM -> T.player.options.zoom
        PlayerMenu.SHADERS -> T.player.options.shaders
        PlayerMenu.SLEEP_TIMER -> T.player.options.sleepTimer
        PlayerMenu.AUDIO_CHANNELS -> T.player.options.audioChannels
        PlayerMenu.AUDIO_OUTPUT -> T.player.options.audioOutput
        PlayerMenu.WATCH_TOGETHER -> T.watch.title
    }

    /**
     * The entry [menu] opens on, by its index in its list: the selected audio or subtitle track, speed, language
     * page's subtitle or option page's choice; 0 (the first) where none is selected, and in the other menus.
     */
    val selectedEntry: Int
        get() = when (menu) {
            PlayerMenu.AUDIO -> audioTracks.indexOfFirst { it.selected }
            PlayerMenu.SUBTITLES -> subtitleTracks.indexOfFirst { it.selected }
            PlayerMenu.SPEED -> speeds.indexOfFirst { it.selected }
            PlayerMenu.SUBTITLE_LANGUAGE -> subtitleLanguage?.subtitles?.indexOfFirst { it.selected } ?: 0
            else -> openOption?.choices?.indexOfFirst { it.selected } ?: 0
        }.coerceAtLeast(0)

    /** Play, pause, seeking and the speed are this device's to change: not a guest's in a host-only room. */
    val canControl: Boolean get() = watch?.canControl ?: true

    /** The timeline shows: for a video and a live window; live without one, the live label in its place. */
    val showsTimeline: Boolean get() = mode != PlaybackMode.LIVE

    /** Back and forward show beside play/pause: for a video; live, the live label after play/pause in their place. */
    val showsSeekButtons: Boolean get() = mode == PlaybackMode.VIDEO

    /** The timeline scrubs and seeks here: it can ([seekable]) and this device may ([canControl]). */
    val canSeek: Boolean get() = seekable && canControl

    /**
     * Play/pause is this device's to press: where it may control playback, and for a guest whose player paused here
     * alone ([PlayerWatchUi.pausedHere]), whose Play catches up with the room.
     */
    val canPlayPause: Boolean get() = canControl || watch?.pausedHere != null

    /** The audio menu's button shows: the video has an audio track. */
    val showsAudioMenu: Boolean get() = audioTracks.isNotEmpty()

    /** The subtitle menu's button shows: there is a track besides Off, or a subtitle to add. */
    val showsSubtitleMenu: Boolean get() = subtitleTracks.size > 1 || moreSubtitles.isNotEmpty()

    /**
     * The controls show more than the title and Back (where the platform's controls have them): the transport, the
     * timeline (live, the live label in its place), the menus, the playback info, picture in picture and a room's
     * indicator. Not over the error: a failed playback has nothing for them to act on, and the error's own buttons are
     * all there is to reach. Retry and the next stream bring them back.
     */
    val showsPlaybackControls: Boolean get() = error == null

    /** How many subtitles [moreSubtitles] holds in all, for the subtitle menu's More subtitles entry. */
    val moreSubtitlesCount: String get() = moreSubtitles.sumOf { it.subtitles.size }.toString()

    /** What covers the middle of the video ([PlayerCentre]); null while nothing does. */
    val centre: PlayerCentre? = when {
        error != null -> PlayerCentre.Error(error)
        watch?.prompt != null -> PlayerCentre.Prompt(watch.prompt)
        watch?.matching != null -> PlayerCentre.Matching(watch.matching)
        status.isLoading -> PlayerCentre.Loading(loadingText)
        else -> null
    }

    /**
     * Something over the video holds a TV remote, with buttons of its own to focus: the open menu or the playback info,
     * the error, the prompt button, the sleep timer's question or a guest's prompt ([PlayerCentre.Prompt]). The video
     * takes no press and a swipe on the remote's touch surface neither scrubs nor opens the info meanwhile.
     */
    val remoteHeld: Boolean
        get() = menu != null || infoVisible || error != null || prompt != null || stillWatching != null || centre is PlayerCentre.Prompt
}

/**
 * What covers the middle of the video, one at a time, the first that applies: the error panel ([PlayerUiState.error]), a
 * watch-together guest's prompt ([PlayerWatchUi.prompt]), its search for its file ([PlayerWatchUi.matching]), the
 * loading spinner with [PlayerUiState.loadingText] while the playback loads ([PlaybackStatus.isLoading]).
 */
sealed interface PlayerCentre {
    data class Error(val error: ErrorState) : PlayerCentre
    data class Prompt(val prompt: WatchPromptUi) : PlayerCentre
    data class Matching(val text: String) : PlayerCentre
    data class Loading(val text: String?) : PlayerCentre
}

/**
 * The previous and next episode buttons beside the transport: both show for every episode of a series, each enabled
 * while there is an episode that way that this device may start (not at the series' ends, and in a watch-together room
 * the host's only, [PlayerWatchUi.canChangeEpisode]).
 */
data class EpisodeButtons(val previous: Boolean, val next: Boolean)

/** What plays, as the timeline and the transport show it. */
enum class PlaybackMode {
    /** A file: the timeline with its times, back and forward. */
    VIDEO,

    /** Live ([SessionInfo.live]) without a rewind window: the live label in the timeline's place, nothing to seek. */
    LIVE,

    /**
     * Live with a rewind window ([LiveWindow]): the timeline is the window, from its start to the live edge, and seeks
     * within it; the live label after play/pause goes to live; no back or forward.
     */
    LIVE_WINDOW,
}

enum class PlaybackStatus {
    /** The session is resolving the stream (the addon's service may be fetching it). */
    RESOLVING,

    /** The engine is opening the file: no frame yet. */
    OPENING,
    BUFFERING,
    PLAYING,
    PAUSED,

    /** The video played to its end and nothing plays next. */
    ENDED,
    FAILED,
    ;

    val isLoading: Boolean get() = this == RESOLVING || this == OPENING || this == BUFFERING
}

/**
 * The timeline as the controls draw it ([PlayerStore.timeline]), for each [PlaybackMode].
 *
 * @param position / [duration] timecodes (`12:34`, `1:02:03`); [remaining] is the time left (`−49:29`).
 * @param positionLabel both together (`12:34 / 1:02:03`); live, the live text (`Live · watched 12:34`).
 * @param progress / [buffered] fractions of the duration (0–1) for the bar and its cache fill: [progress] the position
 *   as last reported, which a bar that moves at each frame draws as [progressNow]; [buffered] where what the engine
 *   read ahead ends, never short of the position, at the end once it read the file's end.
 * @param chapters chapter starts as fractions of the duration, for the bar's marks.
 * @param positionSeconds / [durationSeconds] the raw numbers, for accessibility values and test harnesses.
 * @param liveLabel live, the live label's text: LIVE and the time played here by the clock since it went live
 *   (`LIVE · 12:34`), with a live window behind its edge how far behind instead (`LIVE · −0:40`); else empty. Live
 *   there is no position, duration or remaining time (empty) and no chapters.
 * @param atLiveEdge with a live window, the position is at the live edge ([LiveWindow.atEdge]): the bar full, the live
 *   label's dot red; else it is grey and the label goes to live. A window's bar runs from its start ([position]: how
 *   far before the live edge it starts, `−1:30`) to the edge; [positionSeconds] and [durationSeconds] are the place in
 *   the window and its length, and [remaining] is empty.
 * @param behind with a live window behind the edge, how far (`−0:40`); else empty.
 * @param scrub the preview while the viewer scrubs ([PlayerUiState.scrubbing]): the bar shows it in place of
 *   [progress]; null otherwise.
 * @param motion while a video plays, how the position moves on from [positionSeconds] until the next report; null while
 *   it stands (paused, loading, at the end, a seek or a scrub under way, live), when the bar shows [progress] as it is.
 */
data class TimelineState(
    val position: String,
    val duration: String,
    val remaining: String,
    val positionLabel: String,
    val progress: Float,
    val buffered: Float,
    val chapters: List<Float>,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val liveLabel: String,
    val atLiveEdge: Boolean = false,
    val behind: String = "",
    val scrub: ScrubState? = null,
    val motion: PlayheadMotion? = null,
) {
    /**
     * The played fraction now, for a bar drawn at each frame: [progress] moved on by [motion] from the report to this
     * moment, never past the duration; [progress] while the position stands.
     */
    fun progressNow(): Float {
        val motion = motion ?: return progress
        if (durationSeconds <= 0.0) return progress
        return ((positionSeconds + motion.secondsMoved()) / durationSeconds).coerceIn(0.0, 1.0).toFloat()
    }
}

/**
 * The position moving on between the engine's reports ([TimelineState.motion]), which come every
 * [MpvLifecycle.POSITION_STEP] seconds of video: [speed] seconds of video a second since the report ([since], on the
 * store's clock). It moves on at most [REACH_SECONDS] past the report: a report that late means the playback stalled,
 * and the bar waits for the engine rather than run ahead of it.
 */
class PlayheadMotion internal constructor(val speed: Double, private val since: TimeMark) {
    /** Seconds of video played since the report, as the clock and [speed] say, up to [REACH_SECONDS]. */
    internal fun secondsMoved(): Double = (since.elapsedNow().toDouble(DurationUnit.SECONDS) * speed).coerceIn(0.0, REACH_SECONDS)

    internal companion object {
        /** Two of the engine's reports: one late report still moves smoothly, a stall doesn't run on. */
        const val REACH_SECONDS = 2 * MpvLifecycle.POSITION_STEP
    }
}

/**
 * @param seconds the preview; [progress] the same as a fraction of the duration.
 * @param position the preview's timecode; [offset] its distance from where the scrub started (`+1:20`, `−0:30`).
 * @param chapter the title of the chapter at the preview, when the video has named chapters.
 */
data class ScrubState(
    val seconds: Double,
    val progress: Float,
    val position: String,
    val offset: String,
    val chapter: String?,
)

/**
 * One row of a track menu.
 * @param id the track to select; null for subtitles "Off".
 * @param label the first line: the language, else the track title, else "Track 2".
 * @param detail the second line: the title when the language took the first, flags (Forced, SDH, Default), codec,
 *   channel layout, External; null when there is nothing to add.
 * @param second a subtitle track showing as the second subtitle, at the top of the picture.
 */
data class TrackItem(
    val id: Long?,
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val second: Boolean = false,
)

/**
 * One language of [PlayerUiState.moreSubtitles]: its name ([label]; "Other" for subtitles that name no language by
 * code), how many subtitles it has ([count]) and those subtitles, each with the command that adds and selects it
 * ([PlayerCommand.AddSubtitle]). [key] opens its page ([PlayerCommand.OpenSubtitleLanguage]).
 */
data class SubtitleLanguage(val key: String, val label: String, val count: String, val subtitles: List<OptionChoice>)

data class SpeedOption(val speed: Double, val label: String, val selected: Boolean)

/**
 * A level a swipe changed ([PlayerUiState.level]): the screen's brightness or the system's volume, at [fraction] (0 to
 * 1), [percent] as it shows ("40%"); [label] says it ("Brightness 40%"), for screen readers. The brightness shows at
 * the player's left edge and the volume at its right, the sides they are swiped on.
 */
data class LevelIndicator(val kind: LevelKind, val fraction: Float, val percent: String, val label: String)

enum class LevelKind { BRIGHTNESS, VOLUME }

/**
 * The locked screen ([PlayerUiState.screenLock]): the controls are hidden and a touch shield covers the player, showing
 * the unlock button at its top centre while [unlock] is not null. A tap on the shield shows or hides the button, a tap
 * on the button arms it ([UnlockButton.armed]) and a tap on the armed button unlocks ([PlayerCommand.UnlockPress]);
 * the button hides again after a few seconds.
 */
data class ScreenLockState(val unlock: UnlockButton?)

/** The unlock button: [label] "Tap to unlock", or once [armed] (drawn light on dark text) "Tap again to unlock". */
data class UnlockButton(val armed: Boolean, val label: String)

/**
 * A subtitle- or audio-delay control: its entry at the end of its track menu ([title] and [label]) and its page
 * ([menu]), which shows − the delay + and Reset.
 * @param menu the page: [PlayerMenu.SUBTITLE_DELAY] or [PlayerMenu.AUDIO_DELAY].
 * @param title the control's name ("Subtitle delay"): its entry's and its page's title.
 * @param label the delay (`+0.3 s`).
 * @param decreaseLabel / [increaseLabel] − and + by the way they move the delay ("Subtitle delay: earlier"), the names
 *   screen readers say.
 * @param decrease / [increase] one step ([com.edde746.tortuga.input.InputSteps.delaySeconds]) each way; [canDecrease]
 *   and [canIncrease] are false at the ends of the range.
 * @param reset back to zero. Reset shows at every delay and is enabled while the delay isn't zero ([canReset]): the
 *   control keeps its size as the delay moves, so its first step moves none of its buttons.
 */
data class DelayState(
    val menu: PlayerMenu,
    val title: String,
    val label: String,
    val decreaseLabel: String,
    val increaseLabel: String,
    val decrease: PlayerCommand,
    val increase: PlayerCommand,
    val reset: PlayerCommand,
    val canDecrease: Boolean,
    val canIncrease: Boolean,
    val canReset: Boolean,
)

/**
 * @param fraction the level as a fraction of full volume, 100 % (0–1), for a slider: [PlayerCommand.SetVolume] takes
 *   the slider's fraction times 100.
 * @param label "80%", or "Muted".
 */
data class VolumeState(
    val percent: Int,
    val fraction: Float,
    val muted: Boolean,
    val label: String,
)

/** Which part of the playback a section of the info is about; the views' identifiers (`player.info.<id>`) go by it. */
enum class InfoSectionId { STREAM, VIDEO, AUDIO, SUBTITLES, NETWORK }

/** One section of the playback info: its heading ([title]) and its rows, in order. */
data class InfoSection(val id: InfoSectionId, val title: String, val rows: List<InfoRow>)

/** One line of the playback info: the label ("Bitrate") and its value ("38.6 Mb/s"). */
data class InfoRow(val label: String, val value: String)

/**
 * Lays the playback info's sections out in columns, for a view with room for more than one: each section, in order,
 * goes to the column that is shortest so far (by rows, a heading counting as one), so the columns end about level and
 * each keeps its sections in reading order. Only the last section (Network) gains or loses a row while the info
 * shows, and it goes where it goes by the sections before it, so live values move no section to another column.
 */
object InfoColumns {
    fun split(sections: List<InfoSection>, columns: Int): List<List<InfoSection>> {
        val count = columns.coerceIn(1, sections.size.coerceAtLeast(1))
        val placed = List(count) { mutableListOf<InfoSection>() }
        val heights = IntArray(count)
        for (section in sections) {
            val shortest = heights.indices.minBy { heights[it] }
            placed[shortest] += section
            heights[shortest] += section.rows.size + 1
        }
        return placed
    }
}

/** Which of the player's options a [PlayerOption] is: the views' icons and identifiers (`player.option.<id>`) go by it. */
enum class PlayerOptionId { WATCH_TOGETHER, VIDEO_FIT, ZOOM, SHADERS, SLEEP_TIMER, AUTO_PLAY_NEXT, AUDIO_OUTPUT, AUDIO_NORMALIZATION, AUDIO_CHANNELS }

/**
 * One of the player's options, as the options menu lists it: an option with a page of [choices] (the picture's fit, the
 * zoom, the shaders, the sleep timer, the audio output and channels) or a switch (auto-play next, loudness
 * normalization).
 * @param label its name ("Zoom"); [value] its state ("120%", "Fill screen", "23:41 left", "On").
 * @param adjusted away from its default (zoomed, a sleep timer set, a shader running), so a view can mark it.
 * @param page the menu its [choices] show in, opened with `OpenMenu`; null for a switch.
 * @param on a switch's state; null for an option with a page.
 * @param toggle the command that flips a switch; null for an option with a page.
 * @param importLabel the label of an entry after the [choices] ("Import shader") that opens the system's open dialog
 *   for a `.glsl` file and sends the file's name and text as [PlayerCommand.ImportShader]; null where the page has none
 *   (every other option, and the shaders on a device without file dialogs).
 */
data class PlayerOption(
    val id: PlayerOptionId,
    val label: String,
    val value: String,
    val adjusted: Boolean,
    val page: PlayerMenu?,
    val choices: List<OptionChoice>,
    val on: Boolean?,
    val toggle: PlayerCommand?,
    val importLabel: String? = null,
)

/**
 * One entry of an option's page; [command] picks it (and closes the menu).
 * @param detail a second line: what a channel limit does.
 * @param delete the command that deletes it (an imported shader), which a view sends once the viewer confirms
 *   ("Delete “Shader”?"); null for an entry that can't be deleted.
 */
data class OptionChoice(val label: String, val detail: String?, val selected: Boolean, val command: PlayerCommand, val delete: PlayerCommand? = null)

/**
 * The sleep timer ran out: "Still watching?", with Keep watching and Pause. The playback pauses when the countdown does.
 * @param countdown "Pausing in 25 seconds".
 * @param secondsLeft the countdown's seconds; [progress] goes from 0 to 1 as it runs out.
 */
data class StillWatchingState(
    val countdown: String,
    val secondsLeft: Int,
    val progress: Float,
)

/**
 * The player's prompt button: the skip button or up next, one pill in the bottom trailing corner, above the controls
 * while they show, beneath the sleep timer's question when that shows too. While [progress] is set it counts down to
 * doing [command] on its own and fills from its leading edge as it does, a step every tenth of a second of played time;
 * any input on the player (a tap, a click, a pointer move, a key, a remote's press, a scrub, a menu) stops that and
 * leaves the plain button, which still does [command] when pressed. It shows while it counts down, for 8 seconds as it
 * comes up plain or stops counting, and after that with the controls ([withControls]). Back ([PlayerCommand.Back])
 * hides it: a skip button until the playback leaves the segment, up next until the position is back before where it
 * came up. Select or Enter presses it ([InputContext.prompt]); on TV it takes focus as it comes up by itself, not as it
 * comes back with the controls the viewer brought up.
 * @param kind what it does; the views' icon (fast-forward, skip-next) and identifier (`player.skip`, `player.upNext`)
 *   go by it.
 * @param lead the start of the label that is never cut short: the next episode's code and the separator ("S1E2 · ");
 *   null for the skip button, and for an episode without a name, whose code is the [text].
 * @param text the rest of the label, cut short where the button has no room for it: "Skip intro", the next episode's
 *   name ("Please Remain Calm"), or its code alone ("S1E2").
 * @param progress the countdown, 0 → 1 as it runs out; null while it doesn't count down: a skip in Show button mode,
 *   up next without auto-play, after the viewer did something, or for a segment skipped once already.
 * @param secondsLeft the countdown's whole seconds left, rounded up, while [progress] is set.
 * @param command what pressing it does: [PlayerCommand.SkipSegment] or [PlayerCommand.NextEpisode].
 * @param withControls it shows only because the controls are up: past its 8 seconds and not counting down.
 */
data class PlayerPrompt(
    val kind: PromptKind,
    val lead: String?,
    val text: String,
    val progress: Float?,
    val secondsLeft: Int?,
    val command: PlayerCommand,
    val withControls: Boolean,
) {
    /**
     * The button's accessibility label: the skip button's text ("Skip intro"); up next's whole text after what it does
     * ("Play the next episode: S1E2 · Please Remain Calm", the name isolated after the [lead]), which a visible
     * episode title alone doesn't say.
     */
    val label: String get() = when {
        kind != PromptKind.UP_NEXT -> text
        lead != null -> T.player.prompt.playNext(lead + isolate(text))
        else -> T.player.prompt.playNext(text)
    }

    /** The countdown as its accessibility value ("Automatically in 8 seconds"), null while it doesn't count down. */
    val countdown: String? get() = secondsLeft?.let(T.player.prompt::countdown)
}

/**
 * What a [PlayerPrompt] does: skip an intro, a recap or the credits (previews count as credits), or play the next
 * episode ([UP_NEXT]; credits to the end of an episode with a next one too).
 */
enum class PromptKind { INTRO, RECAP, CREDITS, UP_NEXT }

/**
 * The error card ([PlayerStates.error]).
 * @param alternatives how many other streams the session has ("2 more streams available"); null without any.
 * @param buttons the card's buttons in order, each UI drawing them all as they come: Retry, the next stream while other
 *   streams are left, Close; after an error video ([errorVideo]) Choose another stream, Watch the message, Retry and
 *   Close. Exactly one is [ErrorButton.primary].
 * @param errorVideo the stream sent an error video (`PlaybackFailure.ErrorVideo`).
 */
data class ErrorState(
    val title: String,
    val message: String,
    val alternatives: String?,
    val buttons: List<ErrorButton>,
    val errorVideo: Boolean = false,
) {
    /** The button every UI fills and focuses as the card shows. */
    val primary: ErrorButton get() = buttons.single { it.primary }
}

/**
 * One of the error card's buttons: [label], doing [command].
 * @param primary the card's one primary button, filled and focused: the next stream for a link that is gone (HTTP 404
 *   or 410, a URL the player can't open) and once a retry failed too, while other streams are left; another stream
 *   for an error video; Retry otherwise.
 */
data class ErrorButton(val action: ErrorAction, val label: String, val command: PlayerCommand, val primary: Boolean)

/** What an [ErrorButton] does; the views' identifiers (`player.error.<action>`, `player.error.chooseStream`) go by it. */
enum class ErrorAction { CHOOSE_STREAM, WATCH, RETRY, NEXT, CLOSE }
