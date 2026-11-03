package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.session.PlayerSession
import com.edde746.tortuga.playback.session.SessionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * What the OS media controls show for the current playback (the platform only renders it): Android's
 * media session and its notification, Windows' SMTC, Apple's `MPNowPlayingInfoCenter`, Linux's MPRIS; and, on the
 * desktop and the Mac, Discord's Rich Presence ([DiscordActivity]).
 *
 * Published on the changes that matter to those controls, never per frame ([MediaControls]): while [status] is
 * [NowPlayingStatus.PLAYING] the OS advances the position itself from [positionSeconds] at [rate], counting from when
 * it received this value; a seek or a drift publishes again.
 *
 * @param subtitle the secondary line: the episode (`S1E2 · Pilot`), or null for a movie.
 * @param artworkUrl an absolute http(s) image: the episode's thumbnail, else the backdrop, else the poster; or null.
 * @param durationSeconds 0 while unknown.
 * @param rate the playback speed (1.0 is normal speed).
 * @param canSeek the file is open and its duration known: the OS may offer a seek bar ([MediaCommand.SeekTo]).
 * @param canSkipNext there is a next episode this device may play ([MediaCommand.Next]).
 * @param canSkipPrevious there is a previous episode this device may play ([MediaCommand.Previous]).
 * @param live the playback is live, with a rewind window or without ([PlayerUiState.mode]): the OS shows a live stream,
 *   with no seek bar.
 * @param posterUrl an absolute http(s) image of the title's poster (an episode's series poster), or null: the image
 *   Discord shows.
 */
data class NowPlaying(
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val durationSeconds: Double,
    val positionSeconds: Double,
    val rate: Double,
    val status: NowPlayingStatus,
    val canSeek: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrevious: Boolean,
    val live: Boolean,
    val posterUrl: String? = null,
) {
    /** The playback plays, or plays as soon as it has the data: the OS shows a pause button. */
    val playing: Boolean get() = status == NowPlayingStatus.PLAYING || status == NowPlayingStatus.BUFFERING

    companion object {
        /**
         * The media controls' view of the player: [ui] (the store's state: title, status, the episode buttons),
         * [timeline] (the position and duration), the session's [info] (the artwork) and [engine] (the speed).
         */
        fun of(ui: PlayerUiState, timeline: TimelineState, info: SessionInfo, engine: EngineState): NowPlaying = NowPlaying(
            title = ui.title,
            subtitle = ui.subtitle,
            artworkUrl = info.artworkUrl?.webImage(),
            durationSeconds = timeline.durationSeconds.coerceAtLeast(0.0),
            positionSeconds = timeline.positionSeconds.coerceAtLeast(0.0),
            rate = engine.speed,
            status = when (ui.status) {
                PlaybackStatus.RESOLVING, PlaybackStatus.OPENING, PlaybackStatus.BUFFERING -> NowPlayingStatus.BUFFERING
                PlaybackStatus.PLAYING -> NowPlayingStatus.PLAYING
                PlaybackStatus.PAUSED -> NowPlayingStatus.PAUSED
                PlaybackStatus.ENDED, PlaybackStatus.FAILED -> NowPlayingStatus.STOPPED
            },
            canSeek = ui.seekable,
            canSkipNext = ui.episodes?.next == true,
            canSkipPrevious = ui.episodes?.previous == true,
            live = ui.mode != PlaybackMode.VIDEO,
            posterUrl = info.posterUrl?.webImage(),
        )

        private fun String.webImage(): String? = takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }
}

enum class NowPlayingStatus {
    /** Waiting for data (the stream resolves, the file opens, the cache refills); it plays once it has it. */
    BUFFERING,
    PLAYING,
    PAUSED,

    /** The video ended or failed: nothing plays until the user acts in the app. */
    STOPPED,
}

/**
 * What the OS media controls can ask of the playback: their buttons, media keys, headsets, the lock screen; and the
 * system's own stops of the playback ([SystemPause], [SystemResume]).
 */
sealed interface MediaCommand {
    data object Play : MediaCommand
    data object Pause : MediaCommand
    data object TogglePause : MediaCommand

    /** The system stopped the playback: Android's audio focus lost, the audio output gone ([PlayerCommand.SystemPause]). */
    data object SystemPause : MediaCommand

    /** Android's audio focus back after a transient loss ([PlayerCommand.SystemResume]). */
    data object SystemResume : MediaCommand

    /** Seek to [seconds] from the start (the OS's seek bar). */
    data class SeekTo(val seconds: Double) : MediaCommand

    /** Seek by [seconds] from the shown position; negative goes back (skip buttons, fast forward and rewind). */
    data class SeekBy(val seconds: Double) : MediaCommand

    /** Play the next episode, when [NowPlaying.canSkipNext]. */
    data object Next : MediaCommand

    /** Play the previous episode, when [NowPlaying.canSkipPrevious]. */
    data object Previous : MediaCommand

    /** Leave the player. */
    data object Stop : MediaCommand
}

/** Takes the OS's [MediaCommand]s for one playback ([MediaControls]). Main thread. */
interface MediaCommandHandler {
    fun perform(command: MediaCommand)
}

/**
 * The OS media controls, implemented once per app by each platform (Android media session, SMTC,
 * `MPNowPlayingInfoCenter` with `MPRemoteCommandCenter`, MPRIS). One playback at a time uses it ([MediaControls]).
 * Called on the main thread.
 */
interface MediaControlsHost {
    /**
     * Shows [nowPlaying]: the first call of a playback puts the controls up, the next ones update them. Until [hide],
     * the OS's commands go to [commands] (the same handler for every call of one playback).
     */
    fun show(nowPlaying: NowPlaying, commands: MediaCommandHandler)

    /** The playback ended: the controls go away and take no more commands. */
    fun hide()
}

/**
 * One playback's link to the OS media controls: publishes [NowPlaying] from the player [store] and the [session] to
 * the [host], and turns the OS's commands into the store's [PlayerCommand]s, so they act exactly like the player's
 * own buttons (pending pause, seek clamping, up-next).
 *
 * Throttled: a new value goes out when anything but the position changes, or when the position leaves where the OS
 * extrapolates it (a seek, a stall) by more than [DRIFT_SECONDS]; normal playback publishes nothing.
 *
 * Main thread; [release] hides the controls.
 */
class MediaControls internal constructor(
    private val store: PlayerStore,
    private val session: PlayerSession,
    private val host: MediaControlsHost,
    parentScope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : MediaCommandHandler {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /** What the controls show now: the last value published, or null before the first. */
    var nowPlaying: NowPlaying? = null
        private set
    private var publishedAt: TimeMark? = null
    private var released = false

    init {
        scope.launch {
            combine(store.state, store.timeline, session.info, session.engineState) { ui, timeline, info, engine -> NowPlaying.of(ui, timeline, info, engine) }
                .collect(::onNowPlaying)
        }
    }

    override fun perform(command: MediaCommand) {
        if (released) return
        store.dispatch(
            when (command) {
                MediaCommand.Play -> PlayerCommand.Play
                MediaCommand.Pause -> PlayerCommand.Pause
                MediaCommand.TogglePause -> PlayerCommand.TogglePause
                MediaCommand.SystemPause -> PlayerCommand.SystemPause
                MediaCommand.SystemResume -> PlayerCommand.SystemResume
                is MediaCommand.SeekTo -> PlayerCommand.SeekTo(command.seconds)
                is MediaCommand.SeekBy -> PlayerCommand.SeekBy(command.seconds)
                MediaCommand.Next -> PlayerCommand.NextEpisode
                MediaCommand.Previous -> PlayerCommand.PreviousEpisode
                MediaCommand.Stop -> PlayerCommand.Close
            },
        )
    }

    /** The playback ended: the controls go away. */
    fun release() {
        if (released) return
        released = true
        scope.cancel()
        if (nowPlaying != null) host.hide()
        nowPlaying = null
    }

    private fun onNowPlaying(next: NowPlaying) {
        val last = nowPlaying
        val elapsed = publishedAt?.elapsedNow()?.inWholeMilliseconds?.div(1000.0) ?: 0.0
        if (last != null && !changed(last, elapsed, next)) return
        nowPlaying = next
        publishedAt = timeSource.markNow()
        host.show(next, this)
    }

    internal companion object {
        /** How far the position may stray from the OS's extrapolation before it is published again. */
        const val DRIFT_SECONDS = 1.0

        /** Whether [next] differs from [last], published [elapsedSeconds] ago, in anything the OS can't work out itself. */
        fun changed(last: NowPlaying, elapsedSeconds: Double, next: NowPlaying): Boolean {
            if (last.copy(positionSeconds = next.positionSeconds) != next) return true
            val advanced = if (last.status == NowPlayingStatus.PLAYING) elapsedSeconds * last.rate else 0.0
            return abs(next.positionSeconds - (last.positionSeconds + advanced)) > DRIFT_SECONDS
        }
    }
}
