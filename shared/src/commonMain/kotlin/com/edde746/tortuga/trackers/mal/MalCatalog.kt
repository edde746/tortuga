package com.edde746.tortuga.trackers.mal

import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.CatalogItems
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogPage
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.catalog.TrackerWatchlist
import com.edde746.tortuga.trackers.catalog.WatchlistTarget
import com.edde746.tortuga.trackers.client.flexibleLong
import com.edde746.tortuga.trackers.client.obj
import com.edde746.tortuga.trackers.client.objects
import com.edde746.tortuga.trackers.client.string
import kotlinx.serialization.json.JsonObject

/**
 * MyAnimeList's rows: the user's plan-to-watch list,
 * which is also writable, and the airing ranking. Items are `mal:<id>`, which the Anime Kitsu addon resolves.
 */
internal class MalCatalog(private val api: MalApi) : TrackerCatalogSource, TrackerWatchlist {
    override val service: TrackerService get() = TrackerService.MYANIMELIST

    override suspend fun addToWatchlist(target: WatchlistTarget) {
        api.put("/anime/${malId(target)}/my_list_status", listOf("status" to PLAN_TO_WATCH))
    }

    /**
     * MyAnimeList only deletes whole list entries, with their progress, score and dates, so the entry goes only while
     * it is still plan-to-watch. An entry already gone is success.
     */
    override suspend fun removeFromWatchlist(target: WatchlistTarget) {
        val id = malId(target)
        val status = api.getOrNull("/anime/$id?fields=my_list_status")?.json().obj?.get("my_list_status").obj?.get("status").string
        if (status != PLAN_TO_WATCH) return
        try {
            api.delete("/anime/$id/my_list_status")
        } catch (e: TrackerException.Api) {
            if (e.status != 404) throw e
        }
    }

    private fun malId(target: WatchlistTarget): Int =
        target.anime?.mal ?: throw TrackerException.Unsupported(service, "watchlist item without a MyAnimeList id")

    override suspend fun catalogs(): List<TrackerCatalog> = listOf(
        TrackerCatalog(service, "planning", TrackerCatalog.Kind.PLANNING, type = null),
        TrackerCatalog(service, "airing", TrackerCatalog.Kind.AIRING, type = null),
    )

    /** `{data: [{node}], paging: {next}}`, paged by offset (myanimelist.net/apiconfig/references/api/v2). */
    override suspend fun page(catalog: TrackerCatalog, cursor: String?): TrackerCatalogPage {
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val query = when (catalog.kind) {
            // The user's own list, so NSFW entries stay.
            TrackerCatalog.Kind.PLANNING -> "/users/@me/animelist?status=plan_to_watch&sort=list_updated_at&nsfw=true"
            TrackerCatalog.Kind.AIRING -> "/anime/ranking?ranking_type=airing"
            else -> throw TrackerException.Unsupported(service, "${catalog.kind} row")
        }
        val body = api.get("$query&limit=$PAGE_SIZE&offset=$offset&fields=$FIELDS").jsonObject()
        val items = body["data"].objects.mapNotNull { preview(it["node"].obj) }
        val hasNext = body["paging"].obj?.get("next").string != null
        return TrackerCatalogPage(items, next = if (hasNext) (offset + PAGE_SIZE).toString() else null)
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val PLAN_TO_WATCH = "plan_to_watch"
        const val FIELDS = "id,title,main_picture,start_season,synopsis,media_type"

        fun preview(node: JsonObject?): MetaItemPreview? {
            val id = CatalogItems.animeId(mal = node?.get("id").flexibleLong) ?: return null
            val picture = node?.get("main_picture").obj
            return MetaItemPreview(
                id = id,
                type = if (node?.get("media_type").string == "movie") CatalogItems.MOVIE else CatalogItems.SERIES,
                name = node?.get("title").string.orEmpty(),
                poster = picture?.get("large").string ?: picture?.get("medium").string,
                description = node?.get("synopsis").string,
                releaseInfo = CatalogItems.year(node?.get("start_season").obj?.get("year").flexibleLong),
            )
        }
    }
}
