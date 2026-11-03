package com.edde746.tortuga.trackers.trakt

import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.CatalogItems
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogPage
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.catalog.TrackerWatchlist
import com.edde746.tortuga.trackers.catalog.WatchlistTarget
import com.edde746.tortuga.trackers.client.TrackerHttp
import com.edde746.tortuga.trackers.client.arr
import com.edde746.tortuga.trackers.client.flexibleLong
import com.edde746.tortuga.trackers.client.obj
import com.edde746.tortuga.trackers.client.objects
import com.edde746.tortuga.trackers.client.string
import com.edde746.tortuga.trackers.client.toJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Trakt's rows: the watchlist, personal
 * recommendations and trending (movies and shows apart), then the user's own lists. The watchlist is also writable.
 */
internal class TraktCatalog(private val api: TraktApi) : TrackerCatalogSource, TrackerWatchlist {
    override val service: TrackerService get() = TrackerService.TRAKT

    /** docs.trakt.tv/reference/getuserslistspersonal for the lists. */
    override suspend fun catalogs(): List<TrackerCatalog> = listOf(
        TrackerCatalog(service, WATCHLIST, TrackerCatalog.Kind.WATCHLIST, type = null),
        TrackerCatalog(service, "recommended.movie", TrackerCatalog.Kind.RECOMMENDED, CatalogItems.MOVIE),
        TrackerCatalog(service, "recommended.series", TrackerCatalog.Kind.RECOMMENDED, CatalogItems.SERIES),
        TrackerCatalog(service, "trending.movie", TrackerCatalog.Kind.TRENDING, CatalogItems.MOVIE),
        TrackerCatalog(service, "trending.series", TrackerCatalog.Kind.TRENDING, CatalogItems.SERIES),
    ) + api.get("/users/me/lists").jsonArray().objects.mapNotNull { list ->
        val id = list["ids"].obj?.get("trakt").flexibleLong ?: return@mapNotNull null
        TrackerCatalog(service, "$LIST_PREFIX$id", TrackerCatalog.Kind.LIST, type = null, name = list["name"].string)
    }

    /** docs.trakt.tv/reference/postsyncwatchlist and its `/remove`. */
    override suspend fun addToWatchlist(target: WatchlistTarget) {
        api.post("/sync/watchlist", watchlistBody(target)) { it == 200 || it == 201 }
    }

    override suspend fun removeFromWatchlist(target: WatchlistTarget) {
        api.post("/sync/watchlist/remove", watchlistBody(target))
    }

    private fun watchlistBody(target: WatchlistTarget): JsonObject {
        if (target.ids.isEmpty) throw TrackerException.Unsupported(service, "watchlist item without external ids")
        return buildJsonObject {
            put(if (target.isMovie) "movies" else "shows", buildJsonArray { add(buildJsonObject { put("ids", target.ids.toJson()) }) })
        }
    }

    override suspend fun page(catalog: TrackerCatalog, cursor: String?): TrackerCatalogPage {
        val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val path = pathOf(catalog.type)
        val type = catalog.type ?: CatalogItems.SERIES
        return when (catalog.kind) {
            // docs.trakt.tv/reference/getsyncwatchlist: `{rank, type, movie|show}` entries in the user's order.
            TrackerCatalog.Kind.WATCHLIST ->
                paged(api.get("/sync/watchlist?$EXTENDED&page=$page&limit=$WATCHLIST_LIMIT"), page) { watchlistEntry(it) }
            // Personal, limit-only (no pages); leaves out what is already on the watchlist.
            TrackerCatalog.Kind.RECOMMENDED -> {
                val response = api.get("/recommendations/$path?$EXTENDED&limit=$ROW_LIMIT&ignore_collected=false&ignore_watchlisted=true")
                TrackerCatalogPage(response.jsonArray().objects.mapNotNull { preview(it, type) }, next = null)
            }
            // `{watchers, movie|show}` entries.
            TrackerCatalog.Kind.TRENDING -> paged(api.get("/$path/trending?$EXTENDED&page=$page&limit=$ROW_LIMIT"), page) { entry ->
                preview(entry[if (type == CatalogItems.MOVIE) "movie" else "show"].obj, type)
            }
            // docs.trakt.tv/reference/getuserslistslistitems: `{rank, type, movie|show}` like the watchlist.
            TrackerCatalog.Kind.LIST -> {
                val id = catalog.id.removePrefix(LIST_PREFIX)
                paged(api.get("/users/me/lists/$id/items/movie,show?$EXTENDED&page=$page&limit=$WATCHLIST_LIMIT"), page) { watchlistEntry(it) }
            }
            else -> throw TrackerException.Unsupported(service, "${catalog.kind} row")
        }
    }

    internal companion object {
        private const val WATCHLIST = "watchlist"
        private const val LIST_PREFIX = "list."
        const val EXTENDED = "extended=full,images"
        private const val ROW_LIMIT = 25
        const val WATCHLIST_LIMIT = 100

        /** A page of entries in Trakt's paging (`X-Pagination-Page-Count`): the next page's number while there is one. */
        inline fun paged(response: TrackerHttp.Response, page: Int, item: (JsonObject) -> MetaItemPreview?): TrackerCatalogPage {
            val pageCount = response.headers["X-Pagination-Page-Count"]?.toIntOrNull()
            return TrackerCatalogPage(
                response.jsonArray().objects.mapNotNull(item),
                next = if (pageCount != null && page < pageCount) (page + 1).toString() else null,
            )
        }

        /** A watchlist or list entry; seasons, episodes and people on it aren't rows. */
        fun watchlistEntry(entry: JsonObject): MetaItemPreview? = when (entry["type"].string) {
            "movie" -> preview(entry["movie"].obj, CatalogItems.MOVIE)
            "show" -> preview(entry["show"].obj, CatalogItems.SERIES)
            else -> null
        }

        fun pathOf(type: String?): String = if (type == CatalogItems.MOVIE) "movies" else "shows"

        /** A Trakt movie or show object with `extended=full,images` as a preview; image URLs come protocol-less. */
        fun preview(media: JsonObject?, type: String): MetaItemPreview? {
            if (media == null) return null
            val ids = media["ids"].obj
            val id = CatalogItems.stremioId(ids?.get("imdb").string, ids?.get("tmdb").flexibleLong) ?: return null
            val images = media["images"].obj
            return MetaItemPreview(
                id = id,
                type = type,
                name = media["title"].string.orEmpty(),
                poster = CatalogItems.absoluteUrl(images?.get("poster").arr?.firstOrNull().string),
                background = CatalogItems.absoluteUrl(images?.get("fanart").arr?.firstOrNull().string),
                description = media["overview"].string,
                releaseInfo = CatalogItems.year(media["year"].flexibleLong),
            )
        }
    }
}
