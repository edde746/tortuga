package com.edde746.tortuga.playback.session

/**
 * What playback tells the sync engine, so progress reaches the account's other devices at the moments
 * that matter without a push for every progress write. Implementations return at once and are safe from any thread.
 */
interface SyncTrigger {
    /** While [playing], the sync engine may coalesce its pushes of local changes (progress is written every few seconds). */
    fun setPlaying(playing: Boolean)

    /**
     * Push local changes now: on pause, after a seek, when playback stops or fails, and when a video counts as watched.
     */
    fun flushNow()

    /** No sync (no account yet, tests, tools). */
    object None : SyncTrigger {
        override fun setPlaying(playing: Boolean) = Unit
        override fun flushNow() = Unit
    }
}
