package com.edde746.tortuga.trackers.mdblist

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
import com.edde746.tortuga.trackers.client.flexibleLong
import com.edde746.tortuga.trackers.client.obj
import com.edde746.tortuga.trackers.client.objects
import com.edde746.tortuga.trackers.client.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MDBList's rows: the watchlist, which is also
 * writable, and each list the user made (`GET /lists/user`), movies and shows together in the list's order.
 */
internal class MdblistCatalog(private val api: MdblistApi) : TrackerCatalogSource, TrackerWatchlist {
    override val service: TrackerService get() = TrackerService.MDBLIST

    /** `POST /watchlist/items/add` and `/remove` with the documented `{"movies": [{"ids": …}]}` shape. */
    override suspend fun addToWatchlist(target: WatchlistTarget) {
        api.post("/watchlist/items/add", watchlistBody(target))
    }

    override suspend fun removeFromWatchlist(target: WatchlistTarget) {
        api.post("/watchlist/items/remove", watchlistBody(target))
    }

    private fun watchlistBody(target: WatchlistTarget): JsonObject {
        val ids = mdblistIds(target.ids) ?: throw TrackerException.Unsupported(service, "watchlist item without an IMDb or TMDB id")
        return buildJsonObject {
            put(if (target.isMovie) "movies" else "shows", buildJsonArray { add(buildJsonObject { put("ids", ids) }) })
        }
    }

    override suspend fun catalogs(): List<TrackerCatalog> {
        val lists = api.get("/lists/user").jsonArray().objects.mapNotNull { list ->
            val id = list["id"].flexibleLong ?: return@mapNotNull null
            TrackerCatalog(service, "$LIST_PREFIX$id", TrackerCatalog.Kind.LIST, type = null, name = list["name"].string)
        }
        return listOf(TrackerCatalog(service, WATCHLIST, TrackerCatalog.Kind.WATCHLIST, type = null)) + lists
    }

    /**
     * The public list [input] names ([mdblistPublicListId]: a list's page, `user/slug` or a numeric id), looked up with
     * `GET /lists/{listid}` or `GET /lists/{user}/{slug}` (an array of one list) for its name. Null when [input] names
     * no list; [TrackerException.Api] 404 when MDBList has none there.
     */
    override suspend fun publicList(input: String): TrackerCatalog? {
        val id = mdblistPublicListId(input) ?: return null
        val list = api.get("/lists/$id").jsonArray().objects.firstOrNull() ?: throw TrackerException.Api(service, 404)
        return TrackerCatalog(service, id, TrackerCatalog.Kind.PUBLIC_LIST, type = null, name = list["name"].string)
    }

    /**
     * One page of list items (api.mdblist.com/docs/, `GET /lists/{listid}/items`, a public list's also
     * `GET /lists/{user}/{slug}/items`), by offset. A list of one [TrackerCatalog.type] keeps that type's.
     */
    override suspend fun page(catalog: TrackerCatalog, cursor: String?): TrackerCatalogPage {
        val path = when (catalog.kind) {
            TrackerCatalog.Kind.WATCHLIST -> "/watchlist/items"
            TrackerCatalog.Kind.LIST -> "/lists/${catalog.id.removePrefix(LIST_PREFIX)}/items"
            TrackerCatalog.Kind.PUBLIC_LIST -> "/lists/${mdblistPublicListId(catalog.id) ?: throw TrackerException.Unsupported(service, "list id")}/items"
            else -> throw TrackerException.Unsupported(service, "${catalog.kind} row")
        }
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val response = api.get("$path?limit=$PAGE_SIZE&offset=$offset&append_to_response=poster")
        val body = response.jsonObject()
        val rows = body["movies"].objects.map { it to CatalogItems.MOVIE } + body["shows"].objects.map { it to CatalogItems.SERIES }
        val items = rows.mapNotNull { (entry, type) -> if (catalog.type == null || catalog.type == type) preview(entry, type) else null }
        return TrackerCatalogPage(items, next = if (hasMore(response, body, rows.size)) (offset + PAGE_SIZE).toString() else null)
    }

    private companion object {
        const val WATCHLIST = "watchlist"
        const val LIST_PREFIX = "list."
        const val PAGE_SIZE = 100

        /** `pagination.has_more`, else the `X-Has-More` header, else whether the page came back full. */
        fun hasMore(response: TrackerHttp.Response, body: JsonObject, rows: Int): Boolean =
            (body["pagination"].obj?.get("has_more") as? JsonPrimitive)?.booleanOrNull
                ?: response.headers["X-Has-More"]?.toBooleanStrictOrNull()
                ?: (rows >= PAGE_SIZE)

        /** A list item; `id` is the TMDB id when `ids` doesn't say. */
        fun preview(entry: JsonObject, type: String): MetaItemPreview? {
            val ids = entry["ids"].obj
            val imdb = ids?.get("imdb").string ?: entry["imdb_id"].string
            val tmdb = ids?.get("tmdb").flexibleLong ?: entry["id"].flexibleLong
            val id = CatalogItems.stremioId(imdb, tmdb) ?: return null
            return MetaItemPreview(
                id = id,
                type = type,
                name = entry["title"].string.orEmpty(),
                poster = entry["poster"].string?.takeIf { it.startsWith("http") },
                description = entry["description"].string,
                releaseInfo = CatalogItems.year(entry["release_year"].flexibleLong ?: entry["year"].flexibleLong),
            )
        }
    }
}

/**
 * The id of the public MDBList list [input] names, as a [TrackerCatalog.Kind.PUBLIC_LIST]'s id: `user/slug` from a
 * list's page (`https://mdblist.com/lists/<user>/<slug>`) or as written, or a numeric list id; null for anything else.
 */
internal fun mdblistPublicListId(input: String): String? {
    val text = input.trim()
    if (text.isNotEmpty() && text.all { it in '0'..'9' }) return text
    val path = MDBLIST_LIST_URL.find(text)?.groupValues?.get(1) ?: text
    return path.trimEnd('/').takeIf { MDBLIST_USER_SLUG.matches(it) }
}

private val MDBLIST_LIST_URL = Regex("""mdblist\.com/lists/([^?#]+)""", RegexOption.IGNORE_CASE)
private val MDBLIST_USER_SLUG = Regex("""[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*""")
