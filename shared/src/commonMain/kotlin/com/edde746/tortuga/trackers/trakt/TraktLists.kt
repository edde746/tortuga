package com.edde746.tortuga.trackers.trakt

import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.CatalogItems
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogPage
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.client.TrackerHttp
import com.edde746.tortuga.trackers.client.flexibleLong
import com.edde746.tortuga.trackers.client.obj
import com.edde746.tortuga.trackers.client.string
import io.ktor.http.HttpMethod
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Trakt's public lists, read without a Trakt account: asked with this app's client id alone, as [TraktRelated] is
 * (Trakt documents a list's items as "OAuth optional"). A list is a [TrackerCatalog.Kind.PUBLIC_LIST] whose id is its
 * numeric Trakt id ([list] finds it from a list's URL). It has no rows of its own ([catalogs] is empty): collections
 * name the lists they show.
 *
 * Trakt's unauthenticated calls share 500 a client id and public address every 5 minutes: a 429 holds every call back
 * for its `Retry-After` (else [HOLD]), failing as [TrackerException.RateLimited] meanwhile. Main-thread confined, as
 * the hub that owns it.
 */
class TraktLists internal constructor(
    private val http: TrackerHttp,
    private val headers: Map<String, String>,
    private val timeSource: TimeSource.WithComparableMarks,
) : TrackerCatalogSource {
    override val service: TrackerService get() = TrackerService.TRAKT

    /** No call goes out before this, after a 429. */
    private var heldUntil: ComparableTimeMark? = null

    override suspend fun catalogs(): List<TrackerCatalog> = emptyList()

    /**
     * A page of the public list [catalog] (docs.trakt.tv/reference/getlistslistitems, `GET /lists/{id}/items/{type}`):
     * its movies and shows, or only those of its [TrackerCatalog.type], in the list's order.
     */
    override suspend fun page(catalog: TrackerCatalog, cursor: String?): TrackerCatalogPage {
        if (catalog.kind != TrackerCatalog.Kind.PUBLIC_LIST || catalog.id.toLongOrNull() == null) {
            throw TrackerException.Unsupported(service, "${catalog.kind} row")
        }
        val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val types = when (catalog.type) {
            CatalogItems.MOVIE -> "movie"
            CatalogItems.SERIES -> "show"
            else -> "movie,show"
        }
        val response = get("/lists/${catalog.id}/items/$types?${TraktCatalog.EXTENDED}&page=$page&limit=${TraktCatalog.WATCHLIST_LIMIT}")
        return TraktCatalog.paged(response, page) { TraktCatalog.watchlistEntry(it) }
    }

    /**
     * The public list [input] names (a list's URL, `trakt.tv/users/<user>/lists/<slug>` or `trakt.tv/lists/<id>`, a
     * `?id=` parameter, a bare id or slug), looked up with `GET /lists/{id}` (docs.trakt.tv/reference/getlistslist) for
     * its numeric id and name. Null when [input] names no list.
     *
     * @throws TrackerException when Trakt can't be asked or doesn't have it (`Api` with status 404).
     */
    override suspend fun publicList(input: String): TrackerCatalog? {
        val path = listPath(input) ?: return null
        val list = get("/lists/$path").jsonObject()
        val id = list["ids"].obj?.get("trakt").flexibleLong ?: path.toLongOrNull() ?: throw TrackerException.Api(service, 200)
        return TrackerCatalog(service, id.toString(), TrackerCatalog.Kind.PUBLIC_LIST, type = null, name = list["name"].string)
    }

    private suspend fun get(path: String): TrackerHttp.Response {
        heldUntil?.let { if (it.hasNotPassedNow()) throw TrackerException.RateLimited(service, -it.elapsedNow().inWholeSeconds) }
        try {
            val response = http.send(HttpMethod.Get, TraktProvider.API + path, headers)
            if (!response.isSuccess) throw http.failure(response)
            return response
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerException.RateLimited) {
            heldUntil = timeSource.markNow() + (e.retryAfterSeconds?.seconds ?: HOLD)
            throw e
        }
    }

    internal companion object {
        val HOLD: Duration = 60.seconds

        private val QUERY_ID = Regex("""[?&]id=([^&#/]+)""")
        private val LISTS_URL = Regex("""trakt\.tv/lists/([^/?#]+)""", RegexOption.IGNORE_CASE)
        private val USER_LIST_URL = Regex("""trakt\.tv/users/[^/]+/lists/([^/?#]+)""", RegexOption.IGNORE_CASE)
        private val SLUG = Regex("""[A-Za-z0-9_-]+""")

        /**
         * The list id or slug [input] names: a number, a `?id=` parameter, `trakt.tv/lists/<id>`,
         * `trakt.tv/users/<user>/lists/<slug>`, or a bare slug; null for anything else.
         */
        fun listPath(input: String): String? {
            val text = input.trim()
            if (text.isEmpty()) return null
            text.toLongOrNull()?.let { return it.toString() }
            val found = sequenceOf(QUERY_ID, LISTS_URL, USER_LIST_URL).firstNotNullOfOrNull { regex ->
                regex.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            }
            return (found ?: text).takeIf { SLUG.matches(it) }
        }
    }
}
