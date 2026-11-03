package com.edde746.tortuga.trackers.mdblist

import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.client.flexibleDouble
import com.edde746.tortuga.trackers.client.flexibleLong
import com.edde746.tortuga.trackers.client.objects
import com.edde746.tortuga.trackers.client.string
import com.edde746.tortuga.trackers.ratings.RatingSource
import com.edde746.tortuga.trackers.ratings.RatingTitle
import com.edde746.tortuga.trackers.ratings.TitleRating
import com.edde746.tortuga.trackers.ratings.TrackerRatingsSource
import kotlinx.serialization.json.JsonObject

/**
 * MDBList's scores for a title: its media lookup (`GET /{provider}/{movie|show}/{id}`, the OpenAPI schema behind
 * api.mdblist.com/docs/, "Get Media Info") answers every source it aggregates in one `ratings` array, each `value` on
 * the source's own scale. MDBList's own `score` isn't shown.
 */
internal class MdblistRatings(private val api: MdblistApi) : TrackerRatingsSource {
    override val service: TrackerService get() = TrackerService.MDBLIST

    override suspend fun ratings(title: RatingTitle): List<TitleRating> {
        val path = lookupPath(title) ?: return emptyList()
        val response = try {
            api.get(path)
        } catch (e: TrackerException.Api) {
            if (e.status == 404) return emptyList() else throw e
        }
        return parse(response.jsonObject())
    }

    internal companion object {
        /** The IMDb id first, then TMDB, TVDB (shows) and MyAnimeList (anime MDBList maps to either type). */
        fun lookupPath(title: RatingTitle): String? {
            val type = if (title.movie) "movie" else "show"
            val ids = title.ids
            return when {
                ids.imdb != null -> "/imdb/$type/${ids.imdb}"
                ids.tmdb != null -> "/tmdb/$type/${ids.tmdb}"
                ids.tvdb != null && !title.movie -> "/tvdb/show/${ids.tvdb}"
                title.anime?.mal != null -> "/mal/any/${title.anime.mal}"
                else -> null
            }
        }

        /** The scores of a media answer; its `null` values (sources without a score for the title) are left out. */
        fun parse(media: JsonObject): List<TitleRating> = media["ratings"].objects.mapNotNull { rating ->
            val source = when (rating["source"].string) {
                "imdb" -> RatingSource.IMDB
                "tmdb" -> RatingSource.TMDB
                "tomatoes" -> RatingSource.ROTTEN_TOMATOES_CRITICS
                // The Popcornmeter; MDBList's older API called it `tomatoesaudience`.
                "popcorn", "tomatoesaudience" -> RatingSource.ROTTEN_TOMATOES_AUDIENCE
                "metacritic" -> RatingSource.METACRITIC
                "letterboxd" -> RatingSource.LETTERBOXD
                "trakt" -> RatingSource.TRAKT
                "myanimelist" -> RatingSource.MYANIMELIST
                else -> return@mapNotNull null
            }
            TitleRating.of(source, rating["value"].flexibleDouble, rating["votes"].flexibleLong, TrackerService.MDBLIST)
        }
    }
}
