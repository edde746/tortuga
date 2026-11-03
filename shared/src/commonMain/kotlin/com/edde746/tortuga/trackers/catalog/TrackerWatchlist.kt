package com.edde746.tortuga.trackers.catalog

import com.edde746.tortuga.trackers.AnimeIds
import com.edde746.tortuga.trackers.ExternalIds
import kotlinx.serialization.Serializable

/** A whole movie or show to put on or take off a tracker list, by the ids the resolver found for it; stored with a queued write. */
@Serializable
data class WatchlistTarget(
    val isMovie: Boolean,
    val ids: ExternalIds = ExternalIds(),
    val anime: AnimeIds? = null,
)

/**
 * The list a [TrackerCatalogSource] reads that the app can also change: Trakt's and
 * MDBList's watchlist, Simkl's plan-to-watch, MyAnimeList's plan-to-watch and AniList's planning list. Calls throw
 * [com.edde746.tortuga.trackers.TrackerException]; `Unsupported` when the service can't name the item.
 */
interface TrackerWatchlist {
    suspend fun addToWatchlist(target: WatchlistTarget)

    /**
     * Takes [target] off the list. Where removing means deleting the whole list entry (Simkl, MyAnimeList, AniList),
     * an entry that has moved on (watching, completed) is already off the list and is left alone, so a removal never
     * costs the user's progress, score or history.
     */
    suspend fun removeFromWatchlist(target: WatchlistTarget)
}
