package com.edde746.tortuga.playback.session

import com.edde746.tortuga.model.MetaItem

/**
 * Where a video is: milliseconds; [durationMillis] is 0 while unknown, and [creditsStartMillis] is where its credits
 * to the end start when its chapters say (`PlaybackRules.countsAsWatched`; a player app reports none).
 */
internal data class PlaybackPosition(val positionMillis: Long, val durationMillis: Long, val creditsStartMillis: Long? = null)

/**
 * A video's life in a [PlaybackSession], for the tracker coordinator and the addons' watch-activity events. Called on
 * the main thread in this order: started, then any number of playing changes, positions, seeks and an end, then
 * stopped. A stream switch or a reload after an expiry is the same video, not a new start. Implementations return at
 * once; the session logs and ignores what they throw, so an observer can never disturb playback.
 */
internal interface PlaybackObserver {
    /** [videoId] of [meta] opened at [at] (the resume point), [playing] or paused. */
    fun onVideoStarted(meta: MetaItem, videoId: String, at: PlaybackPosition, playing: Boolean)

    /** Playback started or stopped moving (paused, ended, failed). */
    fun onPlayingChanged(playing: Boolean, at: PlaybackPosition)

    /** The position moved by at least a second, or jumped (a seek). */
    fun onPosition(at: PlaybackPosition)

    /** The user moved the position to [at] (the trackers read seeks from [onPosition] and leave this). */
    fun onSeeked(at: PlaybackPosition) {}

    /** The video played to its end, before the playing change that comes with it (the trackers leave this). */
    fun onEnded(at: PlaybackPosition) {}

    /** The video was left: closed, another video started, or the next episode is coming. */
    fun onVideoStopped(at: PlaybackPosition)
}
