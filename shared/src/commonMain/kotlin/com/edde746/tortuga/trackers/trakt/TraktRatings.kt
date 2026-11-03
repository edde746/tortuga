package com.edde746.tortuga.trackers.trakt

import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.client.TrackerHttp
import com.edde746.tortuga.trackers.client.flexibleDouble
import com.edde746.tortuga.trackers.client.flexibleLong
import com.edde746.tortuga.trackers.client.obj
import com.edde746.tortuga.trackers.client.objects
import com.edde746.tortuga.trackers.client.string
import com.edde746.tortuga.trackers.ratings.RatingSource
import com.edde746.tortuga.trackers.ratings.RatingTitle
import com.edde746.tortuga.trackers.ratings.TitleRating
import com.edde746.tortuga.trackers.ratings.TrackerRatingsSource
import kotlinx.serialization.json.JsonElement

/**
 * Trakt's community score for a title (docs.trakt.tv, "Get movie ratings" / "Get show ratings": the average rating
 * from 0 to 10 and its votes), shown as Trakt shows it, a percentage. `/movies/{id}/ratings` takes an IMDb id; a
 * title with only a TMDB or TVDB id is first looked up (`/search/{tmdb|tvdb}/{id}`, "ID Lookup") for its Trakt id.
 */
internal class TraktRatings(private val api: TraktApi) : TrackerRatingsSource {
    override val service: TrackerService get() = TrackerService.TRAKT

    override suspend fun ratings(title: RatingTitle): List<TitleRating> {
        val kind = if (title.movie) "movie" else "show"
        val id = title.ids.imdb ?: lookup(title, kind) ?: return emptyList()
        val answer = getOrNull("/${kind}s/$id/ratings")?.jsonObject() ?: return emptyList()
        return listOfNotNull(parse(answer))
    }

    /** The title's Trakt id from its TMDB (or a show's TVDB) id; `null` when Trakt doesn't know it. */
    private suspend fun lookup(title: RatingTitle, kind: String): String? {
        val path = when {
            title.ids.tmdb != null -> "/search/tmdb/${title.ids.tmdb}?type=$kind"
            title.ids.tvdb != null && !title.movie -> "/search/tvdb/${title.ids.tvdb}?type=show"
            else -> return null
        }
        return getOrNull(path)?.let { lookupId(it.jsonArray(), kind) }
    }

    private suspend fun getOrNull(path: String): TrackerHttp.Response? = try {
        api.get(path)
    } catch (e: TrackerException.Api) {
        if (e.status == 404) null else throw e
    }

    internal companion object {
        /** The first `kind` result's Trakt id of an ID Lookup answer. */
        fun lookupId(results: JsonElement, kind: String): String? =
            results.objects.firstOrNull { it["type"].string == kind }?.get(kind).obj?.get("ids").obj?.get("trakt").flexibleLong?.toString()

        /** A ratings answer as a percentage, `null` without votes yet. */
        fun parse(answer: JsonElement): TitleRating? {
            val ratings = answer.obj ?: return null
            val rating = ratings["rating"].flexibleDouble ?: return null
            return TitleRating.of(RatingSource.TRAKT, rating * 10, ratings["votes"].flexibleLong, TrackerService.TRAKT)
        }
    }
}
