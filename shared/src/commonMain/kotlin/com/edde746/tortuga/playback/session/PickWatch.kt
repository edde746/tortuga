package com.edde746.tortuga.playback.session

import com.edde746.tortuga.data.streams.PickOutcome
import kotlin.time.Duration.Companion.seconds

/**
 * What came of a stream picked by hand ([PlayRequest.pickId]) as [PlaybackSession] plays it, [report]ed once: the
 * first that applies of
 * - [PickOutcome.GOOD]: it played [GOOD_SECONDS] (playing time, not position: a seek doesn't count), or to its end;
 * - [PickOutcome.BAD]: it failed, or stalled [STALL] at a stretch, before that;
 * - [PickOutcome.BACKED_OUT]: it was left (closed, another stream or video) having played under [BACKED_OUT_SECONDS];
 * left after that, nothing is known.
 *
 * Main thread.
 */
internal class PickWatch(private val report: (PickOutcome) -> Unit) {
    private var played = 0.0
    private var playingFrom: Double? = null

    /** Its outcome is reported, or it was left with none to report. */
    var settled = false
        private set

    /** It plays, at [position] seconds: the time since the last report counts while it moves at a playing pace. */
    fun playing(position: Double) {
        if (settled) return
        val from = playingFrom
        playingFrom = position
        if (from != null && position > from && position - from <= MAX_STEP_SECONDS) played += position - from
        if (played >= GOOD_SECONDS) settle(PickOutcome.GOOD)
    }

    /** Paused, buffering or seeking: the time until it plays again doesn't count. */
    fun held() {
        playingFrom = null
    }

    fun ended() = settle(PickOutcome.GOOD)

    /** It failed, or stalled for [STALL]. */
    fun failed() = settle(PickOutcome.BAD)

    fun left() {
        if (!settled && played < BACKED_OUT_SECONDS) settle(PickOutcome.BACKED_OUT)
        settled = true
    }

    private fun settle(outcome: PickOutcome) {
        if (settled) return
        settled = true
        report(outcome)
    }

    companion object {
        const val GOOD_SECONDS: Double = 120.0
        const val BACKED_OUT_SECONDS: Double = 30.0

        /** A stall at least this long is a bad pick. */
        val STALL = 15.seconds

        /** A longer step between position reports is a seek, not playing time. */
        private const val MAX_STEP_SECONDS = 5.0
    }
}
