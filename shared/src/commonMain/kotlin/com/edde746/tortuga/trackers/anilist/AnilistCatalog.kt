package com.edde746.tortuga.trackers.anilist

import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.CatalogItems
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogPage
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.catalog.TrackerWatchlist
import com.edde746.tortuga.trackers.catalog.WatchlistTarget
import com.edde746.tortuga.trackers.client.flexibleBool
import com.edde746.tortuga.trackers.client.flexibleLong
import com.edde746.tortuga.trackers.client.obj
import com.edde746.tortuga.trackers.client.objects
import com.edde746.tortuga.trackers.client.string
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AniList's rows: the viewer's planning list, which
 * is also writable, and this season's anime by popularity. Items are `anilist:<id>`, which the Anime Kitsu addon
 * resolves.
 */
internal class AnilistCatalog(private val api: AnilistApi, private val nowEpochSeconds: () -> Long) : TrackerCatalogSource, TrackerWatchlist {
    override val service: TrackerService get() = TrackerService.ANILIST

    /** Sets the status only: saving with a progress would reset an entry in progress. */
    override suspend fun addToWatchlist(target: WatchlistTarget) {
        api.query(SET_PLANNING, buildJsonObject { put("mediaId", anilistId(target)) })
    }

    /**
     * `DeleteMediaListEntry` removes the whole entry (progress, score, dates, rewatches), so only an entry still on
     * Planning is deleted.
     */
    override suspend fun removeFromWatchlist(target: WatchlistTarget) {
        val entry = api.query(LIST_ENTRY, buildJsonObject { put("mediaId", anilistId(target)) })["Media"].obj?.get("mediaListEntry").obj
        if (entry?.get("status").string != "PLANNING") return
        val entryId = entry?.get("id").flexibleLong ?: return
        api.query(DELETE_ENTRY, buildJsonObject { put("id", entryId) })
    }

    private fun anilistId(target: WatchlistTarget): Int =
        target.anime?.anilist ?: throw TrackerException.Unsupported(service, "watchlist item without an AniList id")

    private val viewerLock = Mutex()
    private var viewerId: Long? = null

    override suspend fun catalogs(): List<TrackerCatalog> = listOf(
        TrackerCatalog(service, "planning", TrackerCatalog.Kind.PLANNING, type = null),
        TrackerCatalog(service, "airing", TrackerCatalog.Kind.AIRING, type = null),
    )

    override suspend fun page(catalog: TrackerCatalog, cursor: String?): TrackerCatalogPage {
        val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return when (catalog.kind) {
            TrackerCatalog.Kind.PLANNING -> planning(page)
            TrackerCatalog.Kind.AIRING -> airing(page)
            else -> throw TrackerException.Unsupported(service, "${catalog.kind} row")
        }
    }

    /** `MediaListCollection` in chunks, newest additions first; custom lists repeat entries and are skipped. */
    private suspend fun planning(chunk: Int): TrackerCatalogPage {
        val collection = api.query(
            PLANNING,
            buildJsonObject {
                put("userId", viewerId())
                put("chunk", chunk)
                put("perChunk", PLANNING_CHUNK)
            },
        )["MediaListCollection"].obj ?: return TrackerCatalogPage(emptyList(), next = null)
        val seen = HashSet<String>()
        val items = collection["lists"].objects
            .filterNot { it["isCustomList"].flexibleBool }
            .flatMap { it["entries"].objects }
            .mapNotNull { preview(it["media"].obj) }
            .filter { seen.add(it.id) }
        return TrackerCatalogPage(items, next = if (collection["hasNextChunk"].flexibleBool) (chunk + 1).toString() else null)
    }

    private suspend fun airing(page: Int): TrackerCatalogPage {
        val (season, year) = currentSeason(nowEpochSeconds())
        val result = api.query(
            AIRING,
            buildJsonObject {
                put("page", page)
                put("perPage", AIRING_PAGE)
                put("season", season)
                put("seasonYear", year)
            },
        )["Page"].obj ?: return TrackerCatalogPage(emptyList(), next = null)
        val items = result["media"].objects.mapNotNull(::preview)
        val hasNext = result["pageInfo"].obj?.get("hasNextPage").flexibleBool
        return TrackerCatalogPage(items, next = if (hasNext) (page + 1).toString() else null)
    }

    private suspend fun viewerId(): Long = viewerLock.withLock {
        viewerId ?: (api.query("query { Viewer { id } }")["Viewer"].obj?.get("id").flexibleLong
            ?: throw TrackerException.Api(service, 200)).also { viewerId = it }
    }

    internal companion object {
        const val PLANNING_CHUNK = 500
        const val AIRING_PAGE = 25

        /** The anime season [nowEpochSeconds] falls in (UTC); December is the next year's winter. */
        fun currentSeason(nowEpochSeconds: Long): Pair<String, Int> {
            val date = Instant.fromEpochSeconds(nowEpochSeconds).toString()
            val year = date.substring(0, 4).toInt()
            return when (date.substring(5, 7).toInt()) {
                12 -> "WINTER" to year + 1
                1, 2 -> "WINTER" to year
                in 3..5 -> "SPRING" to year
                in 6..8 -> "SUMMER" to year
                else -> "FALL" to year
            }
        }

        fun preview(media: JsonObject?): MetaItemPreview? {
            val id = CatalogItems.animeId(anilist = media?.get("id").flexibleLong) ?: return null
            val title = media?.get("title").obj
            val cover = media?.get("coverImage").obj
            return MetaItemPreview(
                id = id,
                type = if (media?.get("format").string == "MOVIE") CatalogItems.MOVIE else CatalogItems.SERIES,
                name = title?.get("userPreferred").string ?: title?.get("english").string ?: title?.get("romaji").string.orEmpty(),
                poster = cover?.get("extraLarge").string ?: cover?.get("large").string,
                background = media?.get("bannerImage").string,
                releaseInfo = CatalogItems.year(media?.get("seasonYear").flexibleLong ?: media?.get("startDate").obj?.get("year").flexibleLong),
            )
        }

        private const val MEDIA_FIELDS = "id format seasonYear startDate { year } title { userPreferred english romaji } coverImage { extraLarge large } bannerImage"

        val SET_PLANNING = """
            mutation(${'$'}mediaId: Int) {
              SaveMediaListEntry(mediaId: ${'$'}mediaId, status: PLANNING) { id }
            }
        """.trimIndent()

        val LIST_ENTRY = """
            query(${'$'}mediaId: Int) {
              Media(id: ${'$'}mediaId, type: ANIME) { mediaListEntry { id status } }
            }
        """.trimIndent()

        val DELETE_ENTRY = """
            mutation(${'$'}id: Int) {
              DeleteMediaListEntry(id: ${'$'}id) { deleted }
            }
        """.trimIndent()

        val PLANNING = """
            query(${'$'}userId: Int!, ${'$'}chunk: Int, ${'$'}perChunk: Int) {
              MediaListCollection(userId: ${'$'}userId, type: ANIME, status: PLANNING, sort: [ADDED_TIME_DESC], chunk: ${'$'}chunk, perChunk: ${'$'}perChunk) {
                hasNextChunk
                lists { isCustomList entries { media { $MEDIA_FIELDS } } }
              }
            }
        """.trimIndent()

        val AIRING = """
            query(${'$'}page: Int, ${'$'}perPage: Int, ${'$'}season: MediaSeason, ${'$'}seasonYear: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                pageInfo { hasNextPage }
                media(type: ANIME, isAdult: false, sort: [POPULARITY_DESC], season: ${'$'}season, seasonYear: ${'$'}seasonYear) { $MEDIA_FIELDS }
              }
            }
        """.trimIndent()
    }
}
