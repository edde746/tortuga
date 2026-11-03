package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.InputSteps
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the player behaves on one platform. Times are in milliseconds: `kotlin.time.Duration` reaches Swift as its raw
 * internal encoding.
 *
 * @param autoHideDelayMillis the controls hide after this long without input while playing.
 * @param doubleClickIntervalMillis the platform's double-click (double-tap) interval: a click waits this long before it
 *   acts, in case it is the first half of a double click (Windows `GetDoubleClickTime`, macOS
 *   `NSEvent.doubleClickInterval`, Android `ViewConfiguration.getDoubleTapTimeout`).
 * @param speeds the speed menu, ascending; 1.0 must be one of them.
 * @param maxDelaySeconds subtitle and audio delays stay within ± this.
 * @param osdMillis how long a transient message (volume, speed, delay, track) stays up.
 * @param sleepTimerMinuteSeconds how many seconds each of the sleep timer's minutes lasts ([SleepTimer]): 60, but a
 *   tenth of that in a development session's fast timers (`player.fastTimers`), so a UI test sees its 5 minutes run out
 *   into "Still watching?" after 30 s.
 */
data class PlayerConfig(
    val profile: InputProfile,
    val autoHideDelayMillis: Long,
    val doubleClickIntervalMillis: Long,
    val steps: InputSteps = InputSteps(),
    val speeds: List<Double> = DEFAULT_SPEEDS,
    val maxDelaySeconds: Double = 60.0,
    val scrub: ScrubConfig = ScrubConfig(),
    val osdMillis: Long = 1500,
    val sleepTimerMinuteSeconds: Int = 60,
) {
    init {
        require(speeds.isNotEmpty() && speeds.zipWithNext().all { (a, b) -> a < b }) { "speeds must ascend: $speeds" }
        require(1.0 in speeds) { "speeds must include 1.0: $speeds" }
        require(sleepTimerMinuteSeconds > 0) { "sleepTimerMinuteSeconds must be positive: $sleepTimerMinuteSeconds" }
    }

    internal val autoHideDelay: Duration get() = autoHideDelayMillis.milliseconds
    internal val doubleClickInterval: Duration get() = doubleClickIntervalMillis.milliseconds
    internal val osdDuration: Duration get() = osdMillis.milliseconds

    companion object {
        val DEFAULT_SPEEDS = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

        /**
         * The defaults for [profile]: controls hide after 3 s with a mouse, 5 s on touch and TV (a remote user needs
         * longer to reach a button). [doubleClickIntervalMillis] is the platform's.
         */
        fun defaults(profile: InputProfile, doubleClickIntervalMillis: Long): PlayerConfig = PlayerConfig(
            profile = profile,
            autoHideDelayMillis = if (profile == InputProfile.DESKTOP) 3000 else 5000,
            doubleClickIntervalMillis = doubleClickIntervalMillis,
        )
    }
}

/**
 * Remote (D-pad) scrubbing. A press steps the preview by [stepSeconds]. A key held past [holdDelayMillis] moves it
 * continuously, every [tickMillis], at [initialRate] media seconds per second, doubling every [rateDoublingMillis], up
 * to the rate that crosses the whole video in [fullScanSeconds]. After the key comes up the preview commits once
 * [commitDelayMillis] pass without another press.
 */
data class ScrubConfig(
    val stepSeconds: Double = 10.0,
    val holdDelayMillis: Long = 400,
    val tickMillis: Long = 100,
    val initialRate: Double = 30.0,
    val rateDoublingMillis: Long = 2000,
    val fullScanSeconds: Double = 10.0,
    val commitDelayMillis: Long = 1000,
) {
    internal val holdDelay: Duration get() = holdDelayMillis.milliseconds
    internal val tick: Duration get() = tickMillis.milliseconds
    internal val rateDoubling: Duration get() = rateDoublingMillis.milliseconds
    internal val commitDelay: Duration get() = commitDelayMillis.milliseconds
}
