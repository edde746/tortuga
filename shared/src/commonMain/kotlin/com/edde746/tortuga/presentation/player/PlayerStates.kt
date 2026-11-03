package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.format.formatTimecode
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.playback.Chapter
import com.edde746.tortuga.playback.PlaybackFailure
import com.edde746.tortuga.playback.SkipKind
import com.edde746.tortuga.playback.SkipSegment
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.displayText
import com.edde746.tortuga.playback.resolve.ResolveFailure
import com.edde746.tortuga.playback.session.LiveWindow
import com.edde746.tortuga.playback.session.NextEpisode
import com.edde746.tortuga.strings.T
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** The formatted parts of [PlayerUiState] and [TimelineState], each from plain values. */
internal object PlayerStates {
    /**
     * A video's timeline at [position], with [scrub]'s preview while the viewer scrubs and [motion] while it plays; the
     * buffered part ends where the engine's read ahead does ([cacheEnd]), never short of the position.
     */
    fun timeline(position: Double, duration: Double, cacheEnd: Double?, chapters: List<Float>, scrub: ScrubState?, motion: PlayheadMotion?): TimelineState {
        val positionText = formatTimecode(position)
        val durationText = formatTimecode(duration)
        return TimelineState(
            position = positionText,
            duration = durationText,
            // Whole seconds on both sides, so position and remaining always add up to the duration shown.
            remaining = PlayerText.remaining((wholeSeconds(duration) - wholeSeconds(position)).coerceAtLeast(0.0)),
            positionLabel = T.player.position(positionText, durationText),
            progress = fraction(position, duration),
            buffered = fraction(maxOf(position, cacheEnd ?: position), duration),
            chapters = chapters,
            positionSeconds = position,
            durationSeconds = duration,
            liveLabel = "",
            scrub = scrub,
            motion = motion,
        )
    }

    /** A live playback's timeline ([PlaybackMode.LIVE]) at [position], [watchedSeconds] played here by the clock. */
    fun liveTimeline(position: Double, watchedSeconds: Double): TimelineState {
        val watched = formatTimecode(watchedSeconds)
        return TimelineState(
            position = "",
            duration = "",
            remaining = "",
            positionLabel = T.player.live.position(watched),
            progress = 0f,
            buffered = 0f,
            chapters = emptyList(),
            positionSeconds = position,
            durationSeconds = 0.0,
            liveLabel = liveLabel(watched),
        )
    }

    /**
     * A live playback's timeline with its rewind [window] ([PlaybackMode.LIVE_WINDOW]) at [position], its place in the
     * window (0 at its start); [watchedSeconds] played here by the clock; [scrub]'s preview while the viewer scrubs. At
     * the edge the bar is full.
     */
    fun rewindTimeline(position: Double, window: LiveWindow, watchedSeconds: Double, scrub: ScrubState?): TimelineState {
        val behind = (window.windowSeconds - position).coerceAtLeast(0.0)
        val atEdge = window.atEdge(behind)
        val watched = formatTimecode(watchedSeconds)
        val behindText = formatTimecode(behind)
        return TimelineState(
            position = PlayerText.remaining(window.windowSeconds),
            duration = formatTimecode(window.windowSeconds),
            remaining = "",
            positionLabel = if (atEdge) T.player.live.position(watched) else T.player.live.behind(behindText),
            progress = if (atEdge) 1f else fraction(position, window.windowSeconds),
            buffered = 0f,
            chapters = emptyList(),
            positionSeconds = if (atEdge) window.windowSeconds else position,
            durationSeconds = window.windowSeconds,
            liveLabel = liveLabel(if (atEdge) watched else PlayerText.remaining(behind)),
            atLiveEdge = atEdge,
            behind = if (atEdge) "" else PlayerText.remaining(behind),
            scrub = scrub,
        )
    }

    /** A scrub's [preview] in a live window [windowSeconds] long: its time behind the edge, or live at the edge. */
    fun rewindScrub(preview: Double, origin: Double, windowSeconds: Double): ScrubState {
        val behind = (windowSeconds - preview).coerceAtLeast(0.0)
        return ScrubState(
            seconds = preview,
            progress = fraction(preview, windowSeconds),
            position = if (behind < 1.0) T.player.live.badge else PlayerText.remaining(behind),
            offset = PlayerText.offset(preview - origin),
            chapter = null,
        )
    }

    /** Chapter starts as fractions of [duration], for the seek bar's marks. */
    fun chapterMarks(chapters: List<Chapter>, duration: Double): List<Float> =
        if (duration <= 0.0) emptyList()
        else chapters.mapNotNull { chapter -> (chapter.startSeconds / duration).takeIf { it > 0.0 && it < 1.0 }?.toFloat() }

    fun scrub(preview: Double, origin: Double, duration: Double, chapter: Chapter?): ScrubState = ScrubState(
        seconds = preview,
        progress = fraction(preview, duration),
        position = formatTimecode(preview),
        offset = PlayerText.offset(preview - origin),
        chapter = chapter?.title?.takeIf { it.isNotBlank() },
    )

    fun speeds(options: List<Double>, current: Double): List<SpeedOption> = options.map {
        SpeedOption(speed = it, label = PlayerText.speed(it), selected = abs(it - current) < PlayerText.SPEED_EPSILON)
    }

    /**
     * The [kind] ([TrackKind.SUBTITLE] or [TrackKind.AUDIO]) delay's control at [seconds]: [config]'s step, within its
     * range.
     */
    fun delay(kind: TrackKind, seconds: Double, config: PlayerConfig): DelayState {
        val text = T.player.menu
        val subtitles = kind == TrackKind.SUBTITLE
        val step = config.steps.delaySeconds
        val max = config.maxDelaySeconds
        return DelayState(
            menu = if (subtitles) PlayerMenu.SUBTITLE_DELAY else PlayerMenu.AUDIO_DELAY,
            title = if (subtitles) text.subtitleDelay else text.audioDelay,
            label = PlayerText.delay(seconds),
            decreaseLabel = if (subtitles) text.subtitleDelayEarlier else text.audioDelayEarlier,
            increaseLabel = if (subtitles) text.subtitleDelayLater else text.audioDelayLater,
            decrease = if (subtitles) PlayerCommand.AdjustSubtitleDelay(-step) else PlayerCommand.AdjustAudioDelay(-step),
            increase = if (subtitles) PlayerCommand.AdjustSubtitleDelay(step) else PlayerCommand.AdjustAudioDelay(step),
            reset = if (subtitles) PlayerCommand.SetSubtitleDelay(0.0) else PlayerCommand.SetAudioDelay(0.0),
            canDecrease = seconds > -max + DELAY_EPSILON,
            canIncrease = seconds < max - DELAY_EPSILON,
            canReset = abs(seconds) >= DELAY_EPSILON,
        )
    }

    fun volume(volume: Double, muted: Boolean): VolumeState {
        val percent = volume.roundToInt()
        return VolumeState(
            percent = percent,
            fraction = (volume / FULL_VOLUME).coerceIn(0.0, 1.0).toFloat(),
            muted = muted,
            label = if (muted) T.player.volume.muted else PlayerText.volume(percent),
        )
    }

    /**
     * Up next's prompt for [next], counting down while [progress] is set, [secondsLeft] to go, [withControls] when it
     * shows only with the controls: "S1E2 · Please Remain Calm", the code and the separator its [PlayerPrompt.lead]
     * (the session's "episode · title" format), or the code alone without a name.
     */
    fun upNext(next: NextEpisode, progress: Float?, secondsLeft: Int?, withControls: Boolean): PlayerPrompt = PlayerPrompt(
        kind = PromptKind.UP_NEXT,
        lead = next.subtitle?.let { code -> T.session.episodeTitle(code, "") },
        text = next.title,
        progress = progress,
        secondsLeft = secondsLeft,
        command = PlayerCommand.NextEpisode,
        withControls = withControls,
    )

    /**
     * The skip button for [segment], counting down while [progress] is set, [secondsLeft] to go, [withControls] when it
     * shows only with the controls.
     */
    fun skip(segment: SkipSegment, progress: Float?, secondsLeft: Int?, withControls: Boolean): PlayerPrompt {
        val (kind, label) = when (segment.kind) {
            SkipKind.INTRO -> PromptKind.INTRO to T.player.skip.intro
            SkipKind.RECAP -> PromptKind.RECAP to T.player.skip.recap
            SkipKind.CREDITS, SkipKind.PREVIEW -> PromptKind.CREDITS to T.player.skip.credits
        }
        return PlayerPrompt(
            kind = kind,
            lead = null,
            text = label,
            progress = progress,
            secondsLeft = secondsLeft,
            command = PlayerCommand.SkipSegment,
            withControls = withControls,
        )
    }

    fun stillWatching(secondsLeft: Int): StillWatchingState = StillWatchingState(
        countdown = T.player.stillWatching.countdown(secondsLeft),
        secondsLeft = secondsLeft,
        progress = 1f - secondsLeft.toFloat() / SleepTimer.STILL_WATCHING_SECONDS,
    )

    /**
     * The error card for [failure], with [alternatives] other streams left; [retried]: Retry was pressed on this stream's
     * failure and the stream hasn't opened since. Its primary button (focused and filled on every UI) is the one most
     * likely to play: the next stream for a link that is gone ([gone]) or once a retry failed too, Retry otherwise.
     */
    fun error(failure: PlaybackFailure, alternatives: Int, retried: Boolean): ErrorState = when (failure) {
        // Another stream rather than the next one: the next is often the same service's, with the same error.
        is PlaybackFailure.ErrorVideo -> ErrorState(
            title = when {
                failure.isEpisode -> T.player.errorVideo.titleEpisode
                failure.isMovie -> T.player.errorVideo.titleMovie
                else -> T.player.errorVideo.titleVideo
            },
            message = failure.displayText,
            alternatives = null,
            buttons = errorButtons(
                listOf(ErrorAction.CHOOSE_STREAM, ErrorAction.WATCH, ErrorAction.RETRY, ErrorAction.CLOSE),
                primary = ErrorAction.CHOOSE_STREAM,
            ),
            errorVideo = true,
        )
        else -> ErrorState(
            title = T.player.error.title,
            message = failure.displayText,
            alternatives = if (alternatives > 0) T.player.error.alternatives(alternatives) else null,
            buttons = errorButtons(
                listOfNotNull(ErrorAction.RETRY, ErrorAction.NEXT.takeIf { alternatives > 0 }, ErrorAction.CLOSE),
                primary = if (retried || gone(failure)) ErrorAction.NEXT else ErrorAction.RETRY,
            ),
        )
    }

    /** The error card's buttons for [actions], in order: [primary] the primary one, Retry where [primary] has none. */
    fun errorButtons(actions: List<ErrorAction>, primary: ErrorAction): List<ErrorButton> {
        val filled = if (primary in actions) primary else ErrorAction.RETRY
        return actions.map { action ->
            val (label, command) = when (action) {
                ErrorAction.CHOOSE_STREAM -> T.player.errorVideo.chooseStream to PlayerCommand.ChooseStream
                ErrorAction.WATCH -> T.player.errorVideo.watch to PlayerCommand.WatchErrorVideo
                ErrorAction.RETRY -> T.player.error.retry to PlayerCommand.Retry
                ErrorAction.NEXT -> T.player.error.nextStream to PlayerCommand.NextStream
                ErrorAction.CLOSE -> T.player.error.close to PlayerCommand.Close
            }
            ErrorButton(action, label, command, primary = action == filled)
        }
    }

    /**
     * [failure] is a link Retry can't bring back: one that is gone (HTTP 404 or 410, at the resolve or in the engine) or
     * that the player can't open. A timeout, a dropped connection, a server's 5xx or a 403 (a new resolve may renew an
     * expired link) can mend.
     */
    private fun gone(failure: PlaybackFailure): Boolean = when (failure) {
        is PlaybackFailure.Resolve -> when (val resolve = failure.failure) {
            is ResolveFailure.Http -> resolve.status in GONE_STATUSES
            ResolveFailure.UnsupportedUrl -> true
            else -> false
        }
        is PlaybackFailure.Engine -> failure.error.httpStatus in GONE_STATUSES
        is PlaybackFailure.ErrorVideo -> true
    }

    /** The live label's text: LIVE and [time] (`LIVE · 0:07`). */
    private fun liveLabel(time: String): String = T.detail.infoJoin(T.player.live.badge, time)

    private fun fraction(value: Double, total: Double): Float =
        if (total > 0.0) (value / total).coerceIn(0.0, 1.0).toFloat() else 0f

    private fun wholeSeconds(seconds: Double): Double = if (seconds.isFinite() && seconds > 0) floor(seconds) else 0.0

    /** Delays are kept to the millisecond. */
    const val DELAY_EPSILON = 0.0005

    /**
     * The top of the player's volume, in percent: unity gain. The engines could amplify up to
     * [com.edde746.tortuga.playback.VOLUME_MAX]; no control does.
     */
    const val FULL_VOLUME = 100.0

    private val GONE_STATUSES = setOf(404, 410)
}
