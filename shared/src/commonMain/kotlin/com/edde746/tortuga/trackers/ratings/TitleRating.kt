package com.edde746.tortuga.trackers.ratings

import com.edde746.tortuga.trackers.TrackerService

/**
 * Where a title's score comes from, in the order Detail shows them.
 *
 * @param key a stable name: test ids (`detail.rating.<key>`) and logs.
 * @param scale the top of the source's own scale, the one [TitleRating.value] is on.
 * @param service the tracker whose own score this is, when one is: its answer wins over the same score seen elsewhere.
 */
enum class RatingSource(val key: String, internal val scale: Double, internal val service: TrackerService? = null) {
    /** The Tomatometer, 0–100 %. */
    ROTTEN_TOMATOES_CRITICS("rt_critics", 100.0),

    /** The Popcornmeter, 0–100 %. */
    ROTTEN_TOMATOES_AUDIENCE("rt_audience", 100.0),
    IMDB("imdb", 10.0),

    /** TMDB's user score, 0–100 %. */
    TMDB("tmdb", 100.0),

    /** The Metascore, 0–100. */
    METACRITIC("metacritic", 100.0),

    /** 0–5 stars. */
    LETTERBOXD("letterboxd", 5.0),

    /** Trakt's community rating as Trakt shows it, 0–100 %. */
    TRAKT("trakt", 100.0, TrackerService.TRAKT),
    SIMKL("simkl", 10.0, TrackerService.SIMKL),

    /** MyAnimeList's mean score. */
    MYANIMELIST("mal", 10.0, TrackerService.MYANIMELIST),

    /** AniList's average score, 0–100 %. */
    ANILIST("anilist", 100.0, TrackerService.ANILIST),
}

/**
 * A title's score from one [source], on that source's own scale ([RatingSource.scale]).
 *
 * @param votes how many ratings or reviews it rests on, where the answer says.
 * @param origin the tracker that answered it; `null` for the addon's meta (its IMDb rating).
 */
data class TitleRating(val source: RatingSource, val value: Double, val votes: Long? = null, val origin: TrackerService? = null) {
    internal companion object {
        /**
         * A score as a service reported it, or `null` when it is no score: missing, outside the source's scale, zero
         * (services write 0 for "not rated yet") or resting on no votes.
         */
        fun of(source: RatingSource, value: Double?, votes: Long?, origin: TrackerService?): TitleRating? {
            if (value == null || value.isNaN() || value <= 0.0 || value > source.scale) return null
            if (votes != null && votes <= 0L) return null
            return TitleRating(source, value, votes, origin)
        }
    }
}

/** The ratings row's merge: one score per source, in [RatingSource] order. */
internal object TitleRatings {
    /**
     * One score per source from every answer: the source's own service first (Trakt's from Trakt, MyAnimeList's from
     * MyAnimeList, …), then MDBList, the ratings aggregator that refreshes them daily, then Simkl, and the addon's
     * meta last (its IMDb rating is whatever the addon cached). Ordered by [RatingSource].
     */
    fun merge(ratings: List<TitleRating>): List<TitleRating> =
        ratings.groupBy { it.source }
            .map { (source, candidates) -> candidates.minBy { precedence(source, it.origin) } }
            .sortedBy { it.source.ordinal }

    private fun precedence(source: RatingSource, origin: TrackerService?): Int = when (origin) {
        null -> 4
        source.service -> 0
        TrackerService.MDBLIST -> 1
        TrackerService.SIMKL -> 2
        else -> 3
    }
}
