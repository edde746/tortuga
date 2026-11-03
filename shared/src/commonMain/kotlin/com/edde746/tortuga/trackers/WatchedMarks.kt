package com.edde746.tortuga.trackers

import com.edde746.tortuga.model.MetaItem

/** A watched or unwatched mark the user made by hand (Detail, Library), after the library recorded it. */
internal sealed interface WatchedMark {
    val watched: Boolean

    /** The whole title: a movie, or every episode out so far of a show. [meta] when at hand (a library row has none). */
    data class Title(val metaId: String, val type: String, val meta: MetaItem?, override val watched: Boolean) : WatchedMark

    /** The episodes of [season] out so far. */
    data class Season(val meta: MetaItem, val season: Int, override val watched: Boolean) : WatchedMark

    /** One video of [meta]. */
    data class Video(val meta: MetaItem, val videoId: String, override val watched: Boolean) : WatchedMark
}

/** Takes the user's manual marks to the trackers ([TrackerCoordinator]). Returns at once; never throws. */
internal fun interface WatchedMarkListener {
    fun onMarked(mark: WatchedMark)
}
