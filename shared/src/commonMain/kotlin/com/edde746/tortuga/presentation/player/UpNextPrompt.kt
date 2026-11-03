package com.edde746.tortuga.presentation.player

import kotlinx.coroutines.CoroutineScope
import kotlin.math.ceil
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Up next as a [PromptButton]. It comes up from a start point the store gives (the credits, or the countdown's length
 * before the end): counting down the profile's seconds of played time to the next episode with auto-play on, plain
 * without. Any input while it counts, or while the skip button counts down to the next episode in its place
 * ([interrupt]), cancels the auto-play for the rest of the file: the countdown stops and from then on it comes up plain.
 * Once dismissed or played ([finish]) it stays quiet until the position is back before the start point: a seek back, or
 * the next file ([reset]).
 *
 * [onTimer] runs when the show window closes and [onStep] at each wake-up of the countdown's ticker; the store publishes
 * from both.
 */
internal class UpNextPrompt(scope: CoroutineScope, timeSource: TimeSource, onTimer: () -> Unit, onStep: () -> Unit) {
    private val button = PromptButton(scope, timeSource, onTimer, onStep)
    private var phase = Phase.ARMED

    /** The viewer stopped a countdown to the next episode in this file. */
    private var cancelled = false

    /** It came up, though it may be out of sight ([visible]). */
    val shown: Boolean get() = phase == Phase.SHOWN

    /** It counts down to the next episode. */
    val counting: Boolean get() = shown && button.counting

    /** The countdown's fill, 0 → 1, while [counting]. */
    val progress: Float? get() = button.progress

    /** The countdown's whole seconds left, while [counting]. */
    val secondsLeft: Int? get() = button.secondsLeft

    /** It shows: [shown], while it counts down, in its first seconds or with [controlsVisible]. */
    fun visible(controlsVisible: Boolean): Boolean = shown && button.visible(controlsVisible)

    /**
     * Follows the playback. [start] is where it comes up, null where it shouldn't (no next episode, nothing to start
     * from); [countdownSeconds] how long it counts down, null for not at all (auto-play of the next episode is off).
     * While [held], it waits: the skip button counts down to the next episode instead. Returns true when the next
     * episode should play at once: the file ended during the countdown, or the countdown is 0.
     */
    fun follow(position: Double, duration: Double, eof: Boolean, start: Double?, countdownSeconds: Int?, held: Boolean): Boolean {
        if (start == null || duration <= 0) {
            if (shown) arm()
            return false
        }
        val countdown = countdownSeconds?.takeIf { !cancelled }
        // Auto-play turned on or off while it shows: it comes up again as it now should.
        if (shown && button.counting != (countdown != null)) arm()
        if (!eof && position < start) {
            arm()
        } else if (phase == Phase.ARMED && !held) {
            phase = Phase.SHOWN
            if (countdown != null && countdown <= 0) return true
            // Started late (a seek into the last seconds): no longer than what is left to play.
            val seconds = countdown?.let { min(it, ceil(duration - position).toInt()).coerceAtLeast(1) }
            button.show(seconds?.seconds)
        }
        return counting && eof
    }

    /** Keeps the countdown's ticker running exactly while counting and [playing]. */
    fun tickWhile(playing: Boolean) = button.tickWhile(playing)

    /** At a wake-up of the countdown's ticker; true when it ran out and the next episode should play now. */
    fun step(): Boolean = button.step()

    /** The viewer did something on the player: no auto-play of the next episode for the rest of the file. */
    fun interrupt() {
        cancelled = true
        button.interrupt()
    }

    /** Dismissed, or the next episode is playing. */
    fun finish() {
        phase = Phase.DONE
        button.hide()
    }

    /** A new file: nothing about the last one carries over. */
    fun reset() {
        cancelled = false
        arm()
    }

    private fun arm() {
        phase = Phase.ARMED
        button.hide()
    }

    private enum class Phase { ARMED, SHOWN, DONE }
}
