package com.edde746.tortuga.trackers.mal

import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.client.flexibleDouble
import com.edde746.tortuga.trackers.client.flexibleLong
import com.edde746.tortuga.trackers.ratings.RatingSource
import com.edde746.tortuga.trackers.ratings.RatingTitle
import com.edde746.tortuga.trackers.ratings.TitleRating
import com.edde746.tortuga.trackers.ratings.TrackerRatingsSource
import kotlinx.serialization.json.JsonObject

/**
 * MyAnimeList's mean score of an anime and the users who scored it (API v2 `GET /anime/{id}` with
 * `fields=mean,num_scoring_users`, myanimelist.net/apiconfig/references/api/v2). Titles without a MyAnimeList id
 * aren't asked.
 */
internal class MalRatings(private val api: MalApi) : TrackerRatingsSource {
    override val service: TrackerService get() = TrackerService.MYANIMELIST

    override val needsAnimeIds: Boolean get() = true

    override suspend fun ratings(title: RatingTitle): List<TitleRating> {
        val id = title.anime?.mal ?: return emptyList()
        val answer = api.getOrNull("/anime/$id?fields=$FIELDS") ?: return emptyList()
        return listOfNotNull(parse(answer.jsonObject()))
    }

    internal companion object {
        const val FIELDS = "mean,num_scoring_users"

        /** `null` for an anime nobody scored yet (MyAnimeList leaves `mean` out). */
        fun parse(anime: JsonObject): TitleRating? =
            TitleRating.of(RatingSource.MYANIMELIST, anime["mean"].flexibleDouble, anime["num_scoring_users"].flexibleLong, TrackerService.MYANIMELIST)
    }
}
