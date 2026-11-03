package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * The seek bar's preview while the user scrubs, dragged to a point or stepped with a remote's D-pad. A held key keeps
 * the preview moving, faster the longer it is held ([ScrubConfig]), by the time [timeSource] measures between its moves
 * rather than the moves counted. Nothing seeks until the preview commits: when a drag is released, on Select, or
 * [ScrubConfig.commitDelayMillis] after the last remote step.
 *
 * [change] applies a timer-driven change through the store, so the new state is published; [commit] seeks to the
 * committed preview.
 */
internal class Scrubber(
    private val scope: CoroutineScope,
    private val timeSource: TimeSource,
    private val config: ScrubConfig,
    private val change: (() -> Unit) -> Unit,
    private val commit: (Double) -> Unit,
) {
    /** The preview, in seconds; null when not scrubbing. */
    var preview: Double? = null
        private set

    /** Where the scrub started. */
    var origin = 0.0
        private set

    /** The key whose hold moves the preview, until it comes up. */
    var heldKey: InputKey? = null
        private set

    private var holdJob: Job? = null
    private var commitJob: Job? = null

    val active: Boolean get() = preview != null

    /** Moves the preview to [seconds]; the first move starts a scrub at [from]. A drag takes over from remote steps. */
    fun dragTo(seconds: Double, from: Double) {
        if (preview == null) origin = from
        stopTimers()
        preview = seconds
    }

    /**
     * One remote step back or [forward] within [duration]; the first starts a scrub at [from]. [key] is the key pressed
     * for it, whose hold keeps the preview moving until [release]. Without a key (a view's button) the step commits
     * after the delay unless another comes first.
     */
    fun step(forward: Boolean, from: Double, duration: Double, key: InputKey?) {
        val current = preview ?: from.also { origin = it }
        stopTimers()
        preview = (current + if (forward) config.stepSeconds else -config.stepSeconds).coerceIn(0.0, duration)
        heldKey = key
        if (key != null) holdJob = scope.launch { hold(forward, duration) } else scheduleCommit()
    }

    /** The held key came up (or another key went down): the preview commits unless the user steps again soon. */
    fun release() {
        holdJob?.cancel()
        holdJob = null
        heldKey = null
        if (active) scheduleCommit()
    }

    fun commitNow() {
        val target = preview ?: return
        end()
        commit(target)
    }

    /** Drops the preview without seeking. */
    fun end() {
        preview = null
        stopTimers()
    }

    /** Moves the preview while the key is held, until it comes up or an end of the video is reached. */
    private suspend fun hold(forward: Boolean, duration: Double) {
        val pressed = timeSource.markNow()
        delay(config.holdDelay)
        val maxRate = maxOf(config.initialRate, duration / config.fullScanSeconds)
        var moved = timeSource.markNow()
        var atEnd = false
        while (!atEnd) {
            delay(config.tick)
            // As far as the time since the last move takes it, however late this wake-up came.
            val elapsed = moved.elapsedNow()
            moved += elapsed
            val seconds = elapsed.toDouble(DurationUnit.SECONDS)
            val rate = min(config.initialRate * 2.0.pow((pressed.elapsedNow() - config.holdDelay) / config.rateDoubling), maxRate)
            change {
                val current = preview
                if (current == null) {
                    atEnd = true
                    return@change
                }
                val next = current + if (forward) rate * seconds else -rate * seconds
                preview = next.coerceIn(0.0, duration)
                atEnd = if (forward) next >= duration else next <= 0.0
            }
        }
    }

    private fun scheduleCommit() {
        commitJob?.cancel()
        commitJob = scope.launch {
            delay(config.commitDelay)
            change(::commitNow)
        }
    }

    private fun stopTimers() {
        holdJob?.cancel()
        holdJob = null
        heldKey = null
        commitJob?.cancel()
        commitJob = null
    }
}
