package com.edde746.tortuga.data.sync

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * This device's hybrid logical clock (Kulkarni et al., 2014). [send] stamps a local write, [receive] folds in a
 * timestamp read from another device; every timestamp either returns is greater than all this clock has issued or
 * received before, whatever the wall clock does, so a later write here always wins over what this device has seen.
 *
 * Clock-skew guard: [receive] refuses a timestamp more than [maxDriftMillis] ahead of this device's wall clock
 * ([ClockDriftException]). Accepting it would drag this clock, and through it every device syncing with it, that far
 * into the future. Moderate skew is absorbed by design (the counter carries order while the wall clocks disagree),
 * so the guard only needs to stop absurd values such as a device whose clock is set years ahead.
 *
 * The one exception to monotonicity follows from the guard: a clock whose last timestamp is more than [maxDriftMillis]
 * ahead of the wall clock (it issued timestamps while the wall clock was set ahead, which was then put right) starts
 * again from the wall clock, counter 0. Kept, every later write would be stamped that far ahead, and other devices
 * would refuse each until real time caught up.
 *
 * Thread-safe; the data layer additionally serializes writes.
 *
 * @param last the last timestamp issued or received, restored from storage so the clock stays monotonic across
 *   restarts even if the wall clock moved back meanwhile (by up to [maxDriftMillis]).
 * @param wallClock epoch milliseconds.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class HybridClock(
    val node: String,
    last: Hlc?,
    private val wallClock: () -> Long,
    val maxDriftMillis: Long = DEFAULT_MAX_DRIFT_MILLIS,
) {
    private val state = AtomicReference(last?.let { Hlc(it.wallMillis, it.counter, node) } ?: Hlc(0, 0, node))

    /** The last timestamp this clock issued or received (with this clock's node id). */
    val last: Hlc get() = state.load()

    /** A timestamp for a local write. */
    fun send(): Hlc {
        while (true) {
            val prev = state.load()
            val now = wallNow()
            val next = if (ranAhead(prev, now)) {
                Hlc(now, 0, node)
            } else {
                val wall = maxOf(prev.wallMillis, now)
                next(wall, if (wall == prev.wallMillis) prev.counter + 1 else 0)
            }
            if (state.compareAndSet(prev, next)) return next
        }
    }

    /**
     * Folds in [remote], a timestamp from another device, and returns this clock's new timestamp (after both).
     *
     * @throws ClockDriftException when [remote] is more than [maxDriftMillis] ahead of the wall clock; the clock is
     *   left unchanged.
     */
    @Throws(ClockDriftException::class)
    fun receive(remote: Hlc): Hlc {
        val now = wallNow()
        if (remote.wallMillis - now > maxDriftMillis) throw ClockDriftException(remote, now, remote.wallMillis - maxDriftMillis)
        while (true) {
            val prev = state.load()
            val last = if (ranAhead(prev, now)) Hlc(0, 0, node) else prev
            val wall = maxOf(last.wallMillis, remote.wallMillis, now)
            val counter = when {
                wall == last.wallMillis && wall == remote.wallMillis -> maxOf(last.counter, remote.counter) + 1
                wall == last.wallMillis -> last.counter + 1
                wall == remote.wallMillis -> remote.counter + 1
                else -> 0
            }
            val next = next(wall, counter)
            if (state.compareAndSet(prev, next)) return next
        }
    }

    /** [last] is further ahead of the wall clock reading [now] than other devices accept: this clock starts again. */
    private fun ranAhead(last: Hlc, now: Long): Boolean = last.wallMillis - now > maxDriftMillis

    // The counter has 16 bits. 65536 timestamps within one millisecond borrow the next millisecond, which keeps
    // the order; the next wall clock reading catches up with it.
    private fun next(wall: Long, counter: Int): Hlc =
        if (counter > Hlc.MAX_COUNTER) Hlc(wall + 1, 0, node) else Hlc(wall, counter, node)

    private fun wallNow(): Long = wallClock().coerceIn(0, Hlc.MAX_WALL_MILLIS)

    companion object {
        /**
         * One day: wide enough for a device on a wrong time zone or a manually set clock, narrow enough to refuse a
         * clock set months or years ahead.
         */
        const val DEFAULT_MAX_DRIFT_MILLIS: Long = 24 * 60 * 60 * 1000L
    }
}

/**
 * A remote timestamp too far ahead of this device's wall clock; see [HybridClock].
 *
 * @property acceptedFromMillis the wall clock reading from which [remote] is no longer too far ahead.
 */
class ClockDriftException(val remote: Hlc, val wallMillis: Long, val acceptedFromMillis: Long) :
    Exception("Remote clock is ${remote.wallMillis - wallMillis} ms ahead of this device")
