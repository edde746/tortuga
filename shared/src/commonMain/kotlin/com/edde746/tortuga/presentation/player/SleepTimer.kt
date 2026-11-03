package com.edde746.tortuga.presentation.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The player's sleep timer: off, set for a number of [minutes], or set for the end of the
 * video ([atEndOfVideo]).
 *
 * Minutes count down in real time ([timeSource]'s), whether or not the video plays, and then the timer asks whether
 * anyone is still watching: [asking] counts [STILL_WATCHING_SECONDS] down from when it comes up, and [tick] says when it
 * ran out, for the store to pause. The answer "keep watching" ([keepWatching]) starts the same minutes again; a pause
 * turns the timer off ([cancel]). Set for the end of the video, the timer goes off when the video ends ([onVideoEnded]).
 *
 * A minute lasts [minuteSeconds] ([PlayerConfig.sleepTimerMinuteSeconds]: 60, fewer in a development session's fast
 * timers); the question's countdown is the same either way. While minutes are set, [onSecond] runs as each second of
 * what is left runs out; the store calls [tick] from it. Both countdowns follow the time measured, so they end on time
 * however late each wake-up comes.
 */
internal class SleepTimer(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource,
    private val minuteSeconds: Int,
    private val onSecond: () -> Unit,
) {
    var minutes: Int? = null
        private set
    var atEndOfVideo = false
        private set

    /** When the timer asks, while [minutes] are set and it isn't asking yet. */
    private var asksAt: TimeMark? = null

    /** When the playback pauses, while the question is up. */
    private var pausesAt: TimeMark? = null

    private var ticker: Job? = null

    /** Seconds until the timer asks, while [minutes] are set and it isn't asking yet. */
    val secondsLeft: Int get() = asksAt?.let(::secondsUntil) ?: 0

    /** The question's countdown while it is up, else null. */
    val asking: Int? get() = pausesAt?.let { secondsUntil(it).coerceAtLeast(1) }

    fun set(minutes: Int) {
        cancel()
        this.minutes = minutes
        asksAt = timeSource.markNow() + (minutes * minuteSeconds).seconds
        ticker = scope.launch {
            while (true) {
                delay(untilNextSecond())
                onSecond()
            }
        }
    }

    fun setAtEndOfVideo() {
        cancel()
        atEndOfVideo = true
    }

    fun cancel() {
        ticker?.cancel()
        ticker = null
        minutes = null
        atEndOfVideo = false
        asksAt = null
        pausesAt = null
    }

    /** "Keep watching": the same minutes again, from now. False when the timer wasn't asking. */
    fun keepWatching(): Boolean {
        val minutes = minutes ?: return false
        if (pausesAt == null) return false
        pausesAt = null
        asksAt = timeSource.markNow() + (minutes * minuteSeconds).seconds
        return true
    }

    /** A second ran out. True when the question went unanswered: the playback pauses now, and the timer is off. */
    fun tick(): Boolean {
        if (pausesAt?.hasPassedNow() == true) {
            cancel()
            return true
        }
        if (asksAt?.hasPassedNow() == true) {
            asksAt = null
            pausesAt = timeSource.markNow() + STILL_WATCHING_SECONDS.seconds
        }
        return false
    }

    /** The video reached its end. True when the timer was set for it: it goes off, and the playback stops there. */
    fun onVideoEnded(): Boolean {
        if (!atEndOfVideo) return false
        cancel()
        return true
    }

    /** Whole seconds until [mark], rounded up: what the timer shows. */
    private fun secondsUntil(mark: TimeMark): Int = ceil(-mark.elapsedNow().toDouble(DurationUnit.SECONDS)).toInt().coerceAtLeast(0)

    /** Until the shown seconds change: the fraction of a second left over, else a whole one. */
    private fun untilNextSecond(): Duration {
        val left = -(pausesAt ?: asksAt ?: return 1.seconds).elapsedNow()
        val fraction = left - left.inWholeSeconds.seconds
        return if (fraction.isPositive()) fraction else 1.seconds
    }

    companion object {
        /** The timer's choices, in minutes. */
        val MINUTES: List<Int> = listOf(5, 10, 15, 30, 45, 60, 90, 120)

        /** How long "Still watching?" waits for an answer before the playback pauses. */
        const val STILL_WATCHING_SECONDS = 30
    }
}
