package com.edde746.tortuga.trackers.ratings

import com.edde746.tortuga.trackers.AnimeIds
import com.edde746.tortuga.trackers.ExternalIds
import com.edde746.tortuga.trackers.TrackerCredentials
import com.edde746.tortuga.trackers.TrackerService

/**
 * A whole title as the trackers are asked about its scores.
 *
 * @param movie a film; otherwise a show (series, anime series).
 * @param anime the anime-list ids, for MyAnimeList and AniList (and Simkl, which takes them too).
 */
data class RatingTitle(val movie: Boolean, val ids: ExternalIds, val anime: AnimeIds? = null)

/** The public scores one connected tracker account can read for a title, for Detail's ratings row. */
interface TrackerRatingsSource {
    val service: TrackerService

    /** The service knows titles by their anime-list ids only (MyAnimeList, AniList): ask after resolving those. */
    val needsAnimeIds: Boolean get() = false

    /**
     * The scores the service has for [title]: its own, and the other sources' it reports (MDBList: Rotten Tomatoes,
     * IMDb, …; Simkl: IMDb, MyAnimeList). Empty when it doesn't know the title.
     *
     * @throws com.edde746.tortuga.trackers.TrackerException when the request failed (rate limited, unreachable, …).
     */
    suspend fun ratings(title: RatingTitle): List<TitleRating>
}

/** A [com.edde746.tortuga.trackers.TrackerProvider] whose service publishes scores (all five do). */
interface TrackerRatingsProvider {
    /** The scores read as the account behind [credentials]; a new instance per connection. */
    fun ratings(credentials: TrackerCredentials): TrackerRatingsSource
}
