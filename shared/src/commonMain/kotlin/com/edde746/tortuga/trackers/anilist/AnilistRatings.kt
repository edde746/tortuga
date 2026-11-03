package com.edde746.tortuga.trackers.anilist

import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.client.flexibleDouble
import com.edde746.tortuga.trackers.client.obj
import com.edde746.tortuga.trackers.ratings.RatingSource
import com.edde746.tortuga.trackers.ratings.RatingTitle
import com.edde746.tortuga.trackers.ratings.TitleRating
import com.edde746.tortuga.trackers.ratings.TrackerRatingsSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AniList's average score of an anime, 0–100 (the `Media` query's `averageScore`, docs.anilist.co/guide/graphql/
 * queries/media), by its AniList id or else its MyAnimeList id (`idMal`). AniList gives no vote count for it.
 */
internal class AnilistRatings(private val api: AnilistApi) : TrackerRatingsSource {
    override val service: TrackerService get() = TrackerService.ANILIST

    override val needsAnimeIds: Boolean get() = true

    override suspend fun ratings(title: RatingTitle): List<TitleRating> {
        val anime = title.anime ?: return emptyList()
        val variables = buildJsonObject {
            when {
                anime.anilist != null -> put("id", anime.anilist)
                anime.mal != null -> put("idMal", anime.mal)
                else -> return emptyList()
            }
        }
        val data = try {
            api.query(QUERY, variables)
        } catch (e: TrackerException.Api) {
            // An id AniList doesn't know answers HTTP 404 with a "Not Found." error.
            if (e.status == 404) return emptyList() else throw e
        }
        return listOfNotNull(parse(data))
    }

    internal companion object {
        const val QUERY = "query (\$id: Int, \$idMal: Int) { Media(id: \$id, idMal: \$idMal, type: ANIME) { id averageScore } }"

        /** `null` while AniList has no average yet. */
        fun parse(data: JsonObject): TitleRating? =
            TitleRating.of(RatingSource.ANILIST, data["Media"].obj?.get("averageScore").flexibleDouble, votes = null, TrackerService.ANILIST)
    }
}
