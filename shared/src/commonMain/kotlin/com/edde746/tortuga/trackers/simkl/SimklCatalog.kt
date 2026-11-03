package com.edde746.tortuga.trackers.simkl

import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.CatalogItems
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogPage
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.catalog.TrackerWatchlist
import com.edde746.tortuga.trackers.catalog.WatchlistTarget
import com.edde746.tortuga.trackers.client.RatingMatch
import com.edde746.tortuga.trackers.client.flexibleLong
import com.edde746.tortuga.trackers.client.obj
import com.edde746.tortuga.trackers.client.objects
import com.edde746.tortuga.trackers.client.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Simkl's plan-to-watch as two rows, movies and series (anime included), from `/sync/all-items/<type>/plantowatch`
 * (api.simkl.org/api-reference/simkl/get-all-items), and writable. The list comes whole, in one page.
 */
internal class SimklCatalog(private val api: SimklApi) : TrackerCatalogSource, TrackerWatchlist {
    override val service: TrackerService get() = TrackerService.SIMKL

    /** api.simkl.org/api-reference/simkl/add-to-list. */
    override suspend fun addToWatchlist(target: WatchlistTarget) {
        val ids = ids(target)
        api.post(
            "/sync/add-to-list",
            buildJsonObject {
                put(bucket(target), buildJsonArray {
                    add(buildJsonObject {
                        put("to", "plantowatch")
                        put("ids", ids)
                    })
                })
            },
        )
    }

    /**
     * Simkl has no remove-from-list call: removing the item from history drops it from the library, history included.
     * So plan-to-watch is read again first, and an item that moved on (watching, completed) is left alone.
     */
    override suspend fun removeFromWatchlist(target: WatchlistTarget) {
        val ids = ids(target)
        val planned = if (target.isMovie) {
            entries("movies").map { it["movie"] }
        } else {
            entries("shows").map { it["show"] } + entries("anime").map { it["show"] }
        }
        if (planned.none { RatingMatch.idsMatch(RatingMatch.nestedIds(it), ids) }) return
        api.post("/sync/history/remove", buildJsonObject { put(bucket(target), buildJsonArray { add(buildJsonObject { put("ids", ids) }) }) })
    }

    private fun ids(target: WatchlistTarget): JsonObject =
        simklIds(target.ids, target.anime) ?: throw TrackerException.Unsupported(service, "watchlist item without ids")

    private fun bucket(target: WatchlistTarget): String = if (target.isMovie) "movies" else "shows"

    override suspend fun catalogs(): List<TrackerCatalog> = listOf(
        TrackerCatalog(service, "plantowatch.movie", TrackerCatalog.Kind.PLAN_TO_WATCH, CatalogItems.MOVIE),
        TrackerCatalog(service, "plantowatch.series", TrackerCatalog.Kind.PLAN_TO_WATCH, CatalogItems.SERIES),
    )

    override suspend fun page(catalog: TrackerCatalog, cursor: String?): TrackerCatalogPage {
        if (catalog.kind != TrackerCatalog.Kind.PLAN_TO_WATCH) throw TrackerException.Unsupported(service, "${catalog.kind} row")
        val items = if (catalog.type == CatalogItems.MOVIE) {
            entries("movies").mapNotNull { preview(it["movie"].obj, CatalogItems.MOVIE) }
        } else {
            entries("shows").mapNotNull { preview(it["show"].obj, CatalogItems.SERIES) } +
                entries("anime").mapNotNull { entry ->
                    preview(entry["show"].obj, if (entry["anime_type"].string == "movie") CatalogItems.MOVIE else CatalogItems.SERIES)
                }
        }
        return TrackerCatalogPage(items, next = null)
    }

    private suspend fun entries(type: String): List<JsonObject> =
        api.get("/sync/all-items/$type/plantowatch").json().obj?.get(type).objects

    private companion object {
        /** A Simkl media object; ids are strings except `simkl` (api.simkl.org/conventions/standard-media-objects). */
        fun preview(media: JsonObject?, type: String): MetaItemPreview? {
            if (media == null) return null
            val ids = media["ids"].obj ?: return null
            val id = CatalogItems.stremioId(ids["imdb"].string, ids["tmdb"].flexibleLong)
                ?: CatalogItems.animeId(
                    kitsu = ids["kitsu"].flexibleLong,
                    mal = ids["mal"].flexibleLong,
                    anilist = ids["anilist"].flexibleLong,
                    anidb = ids["anidb"].flexibleLong,
                )
                ?: return null
            return MetaItemPreview(
                id = id,
                type = type,
                name = media["title"].string.orEmpty(),
                poster = media["poster"].string?.takeIf { it.isNotEmpty() }?.let { "https://simkl.in/posters/${it}_m.webp" },
                releaseInfo = CatalogItems.year(media["year"].flexibleLong),
            )
        }
    }
}
