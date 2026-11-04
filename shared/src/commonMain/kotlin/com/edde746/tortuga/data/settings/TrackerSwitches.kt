package com.edde746.tortuga.data.settings

import com.edde746.tortuga.trackers.TrackerContentType

/**
 * A switch of a profile's tracker connection: one synced setting per service and switch ([ProfileSetting.Tracker],
 * `trackers.<service id>.<key>`), so switches changed on different devices never overwrite each other.
 */
enum class TrackerSwitch(val key: String) {
    /** "Send what you watch": writes go to the service at all. */
    SENDING("sending"),

    /** The content-type filters: whether the profile's writes about movies, series and anime go to the service. */
    MOVIES("movies"),
    SERIES("series"),
    ANIME("anime"),
    ;

    companion object {
        /** The content-type filter of [type]. */
        fun of(type: TrackerContentType): TrackerSwitch = when (type) {
            TrackerContentType.MOVIE -> MOVIES
            TrackerContentType.SERIES -> SERIES
            TrackerContentType.ANIME -> ANIME
        }
    }
}

/**
 * A profile's switches for one tracker service ([TrackerSwitch]), the same on every device of the profile; all on
 * ([DEFAULT]) until changed.
 */
data class TrackerSwitches(val sending: Boolean, val movies: Boolean, val series: Boolean, val anime: Boolean) {
    /** Writes about [type] go to the service: sending is on and [type]'s filter lets them through. */
    fun takes(type: TrackerContentType): Boolean = sending && when (type) {
        TrackerContentType.MOVIE -> movies
        TrackerContentType.SERIES -> series
        TrackerContentType.ANIME -> anime
    }

    companion object {
        val DEFAULT = TrackerSwitches(sending = true, movies = true, series = true, anime = true)
    }
}
