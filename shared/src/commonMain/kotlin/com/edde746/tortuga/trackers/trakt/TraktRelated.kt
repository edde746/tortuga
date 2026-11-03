package com.edde746.tortuga.trackers.trakt

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.ImdbId
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.client.TrackerHttp
import com.edde746.tortuga.trackers.client.arr
import com.edde746.tortuga.trackers.client.flexibleLong
import com.edde746.tortuga.trackers.client.obj
import com.edde746.tortuga.trackers.client.objects
import com.edde746.tortuga.trackers.client.string
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Trakt's related titles of a movie or a series (`GET /movies/{id}/related`, `/shows/{id}/related`): public data,
 * asked with this app's client id alone, so every user has them, with or without a Trakt account. The title is named by
 * its IMDb id; the answer is the first [LIMIT] titles in Trakt's order, as catalog items whose id is their IMDb id (one
 * without is left out), with Trakt's poster (`extended=images`, whose URLs come without a scheme). Trakt answers a
 * title it doesn't know with an empty list, as does this.
 *
 * Answers are kept per title for [TTL] (the endpoint's own `Cache-Control: max-age=10800`), the [CAPACITY] most
 * recently used; a failure isn't kept, so the next visit asks again. Trakt's unauthenticated calls share 500 a client
 * id and public address every 5 minutes: a 429 holds every call back for its `Retry-After` (else [HOLD]), answering
 * as a failure meanwhile. Main-thread confined, as the hub that owns it.
 */
class TraktRelated internal constructor(
    private val http: TrackerHttp,
    private val headers: Map<String, String>,
    private val timeSource: TimeSource.WithComparableMarks,
) {
    private data class Key(val kind: String, val imdb: String)

    private class Entry(val titles: List<MetaItemPreview>, val at: ComparableTimeMark)

    /** Least recently used first. */
    private val cache = LinkedHashMap<Key, Entry>()

    /** No call goes out before this, after a 429. */
    private var heldUntil: ComparableTimeMark? = null

    /**
     * The titles Trakt relates to the [type] (`movie` or `series`) title [imdb]; null when Trakt couldn't be asked (a
     * failure, which is logged, or the hold after a 429).
     */
    suspend fun related(type: String, imdb: String): List<MetaItemPreview>? {
        val kind = kind(type) ?: return null
        val key = Key(kind, imdb)
        cache.remove(key)?.let { entry ->
            if (entry.at.elapsedNow() < TTL) {
                cache[key] = entry
                return entry.titles
            }
        }
        heldUntil?.let { if (it.hasNotPassedNow()) return null }
        val titles = try {
            val response = http.send(HttpMethod.Get, "${TraktProvider.API}/$kind/$imdb/related?extended=images&limit=$LIMIT", headers)
            if (!response.isSuccess) throw http.failure(response)
            parse(response.jsonArray(), type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerException) {
            if (e is TrackerException.RateLimited) heldUntil = timeSource.markNow() + (e.retryAfterSeconds?.seconds ?: HOLD)
            Log.i(TAG) { "trakt related left out: ${e.message}" }
            return null
        }
        cache[key] = Entry(titles, timeSource.markNow())
        while (cache.size > CAPACITY) cache.remove(cache.keys.first())
        return titles
    }

    internal companion object {
        private const val TAG = "trackers"
        const val LIMIT = 20
        val TTL: Duration = 3.hours
        const val CAPACITY = 100
        val HOLD: Duration = 60.seconds

        /** Trakt's path segment for a Stremio type: movies and series (Trakt's shows); null for any other. */
        fun kind(type: String): String? = when (type) {
            "movie" -> "movies"
            "series" -> "shows"
            else -> null
        }

        /** A related answer as catalog items of [type]. */
        fun parse(answer: JsonElement?, type: String): List<MetaItemPreview> = answer.objects.mapNotNull { item ->
            val imdb = item["ids"].obj?.get("imdb").string?.takeIf { ImdbId.parse(it) != null } ?: return@mapNotNull null
            MetaItemPreview(
                id = imdb,
                type = type,
                name = item["title"].string.orEmpty(),
                poster = item["images"].obj?.get("poster").arr?.firstOrNull().string?.let(::imageUrl),
                releaseInfo = item["year"].flexibleLong?.toString(),
                imdbId = imdb,
            )
        }

        /** Trakt's image paths (`media.trakt.tv/images/…`) as https URLs. */
        private fun imageUrl(path: String): String = if (path.contains("://")) path else "https://$path"
    }
}
