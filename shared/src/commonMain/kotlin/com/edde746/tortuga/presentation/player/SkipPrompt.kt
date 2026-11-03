package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.data.settings.SkipMode
import com.edde746.tortuga.playback.SkipKind
import com.edde746.tortuga.playback.SkipSegment
import com.edde746.tortuga.playback.session.SkipModes
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The skip button for the segment the playback is in ([SkipSegment], from the video's chapters), a [PromptButton]:
 *
 * - Entering a segment whose mode isn't Off ([follow]) brings the button up plain (Show button), shown for
 *   [PromptButton.SHOW_MILLIS] and then with the controls only, while the position is still inside ([visible]).
 * - Skip automatically: the button counts down [COUNTDOWN_MILLIS] instead ([tickWhile]); [tick] says when it ran out,
 *   for the store to skip. Any input ([interrupt]) stops it and leaves the button as in Show button. A segment skipped
 *   once in this playback ([markSkipped]) never counts down again: going back into it was on purpose.
 * - [dismiss] hides the button until the playback leaves the segment; [reset] forgets everything for a new file.
 *
 * [onTimer] runs when the show window closes and [onTick] at each wake-up of the countdown's ticker; the store publishes
 * from both.
 */
internal class SkipPrompt(scope: CoroutineScope, timeSource: TimeSource, onTimer: () -> Unit, onTick: () -> Unit) {
    /** The segment the position is in, of a kind whose mode isn't Off; null outside one. */
    var segment: SkipSegment? = null
        private set

    private val button = PromptButton(scope, timeSource, onTimer, onTick)

    /** The segments skipped in this playback, by kind and start: a duration mpv refines moves an end, not the segment. */
    private val skipped = mutableSetOf<Pair<SkipKind, Double>>()

    /** The automatic skip counts down. */
    val counting: Boolean get() = button.counting

    /** The countdown's fill, 0 → 1, while [counting]. */
    val progress: Float? get() = button.progress

    /** The countdown's whole seconds left, while [counting]. */
    val secondsLeft: Int? get() = button.secondsLeft

    /**
     * Follows the playback at [position] through [segments], with the profile's [modes]. [autoAllowed] says whether a
     * segment may skip on its own on this device (the room's host, or no room; credits that play the next episode only
     * with auto-play of it on); it is asked as the playback enters the segment and again while it counts down.
     */
    fun follow(position: Double, segments: List<SkipSegment>, modes: SkipModes, autoAllowed: (SkipSegment) -> Boolean) {
        val inside = segments.firstOrNull {
            modes.of(it.kind) != SkipMode.OFF && position >= it.startSeconds && position < it.endSeconds - END_TOLERANCE_SECONDS
        }
        if (inside?.id != segment?.id) enter(inside, modes, autoAllowed) else segment = inside
        val current = segment
        // Changed to Show button meanwhile, or no longer this device's to skip: the countdown stops, the button stays.
        if (counting && (current == null || modes.of(current.kind) != SkipMode.AUTO || !autoAllowed(current))) button.interrupt()
    }

    /** The button shows: inside a segment not dismissed, while it counts down, in its first seconds or with [controlsVisible]. */
    fun visible(controlsVisible: Boolean): Boolean = segment != null && button.visible(controlsVisible)

    /** Keeps the countdown's ticker running exactly while counting and [playing]. */
    fun tickWhile(playing: Boolean) = button.tickWhile(playing)

    /** At a wake-up of the countdown's ticker; true when it ran out and the segment should be skipped now. */
    fun tick(): Boolean = button.step()

    /** The viewer did something on the player: a countdown stops, and the button stays as in Show button. */
    fun interrupt(): Boolean = button.interrupt()

    /** Hidden, and its countdown stopped, until the playback leaves the segment. */
    fun dismiss() = button.dismiss()

    /** [segment] was skipped (by the button or on its own): it never counts down again in this playback. */
    fun markSkipped(segment: SkipSegment) {
        skipped += segment.id
        button.stop()
    }

    /** A new file: nothing about the last one carries over. */
    fun reset() {
        skipped.clear()
        segment = null
        button.hide()
    }

    private fun enter(new: SkipSegment?, modes: SkipModes, autoAllowed: (SkipSegment) -> Boolean) {
        segment = new
        when {
            new == null -> button.hide()
            modes.of(new.kind) == SkipMode.AUTO && new.id !in skipped && autoAllowed(new) -> button.show(COUNTDOWN_MILLIS.milliseconds)
            else -> button.show(null)
        }
    }

    private val SkipSegment.id: Pair<SkipKind, Double> get() = kind to startSeconds

    companion object {
        /** The automatic skip's countdown, on the button. */
        const val COUNTDOWN_MILLIS = 3000L

        /**
         * The last moment of a segment that still offers to skip it: mpv may land an exact seek to a segment's end a
         * frame before it, which must not count as inside again.
         */
        const val END_TOLERANCE_SECONDS = 0.5
    }
}
