package com.edde746.tortuga.playback.session

import com.edde746.tortuga.model.MetaItem

/** What a playback session tells the app beyond the player screen's state. */
sealed interface SessionEvent {
    /**
     * The next episode, [videoId] of [meta], has no stream to continue with (no binge match, nothing in the stream
     * history, nothing auto-play may pick), or the stream auto-play picked for [videoId] didn't start: show its stream
     * list so the user picks one, with [notice] (why the list opened) on top when there is one.
     */
    data class ChooseStream(val meta: MetaItem, val videoId: String, val notice: String? = null) : SessionEvent
}

/**
 * Timing of a playback session. Times are in milliseconds (`kotlin.time.Duration` reaches Swift as its raw encoding).
 *
 * @param userAgent sent to the player when the addon doesn't set one, so the player asks the stream's server as the
 *   resolver did (`Tortuga/<version>`).
 * @param progressIntervalMillis how often progress is saved while playing; pausing, seeking, switching and leaving
 *   save it too.
 * @param expiryResetMillis playback time after a re-resolve after which a later expiry may re-resolve again. A resolved
 *   link lives for hours, so an expiry after ten minutes of healthy playback is a new one, while a link that dies
 *   again at once still fails after one re-resolve instead of looping.
 */
data class SessionConfig(
    val userAgent: String,
    val progressIntervalMillis: Long = 15_000,
    val expiryResetMillis: Long = 600_000,
) {
    init {
        require(progressIntervalMillis > 0) { "progressIntervalMillis must be positive" }
        require(expiryResetMillis > 0) { "expiryResetMillis must be positive" }
    }
}
