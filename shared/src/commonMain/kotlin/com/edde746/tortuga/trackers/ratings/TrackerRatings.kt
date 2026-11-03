package com.edde746.tortuga.trackers.ratings

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.trackers.ResolvedIds
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerIdResolver
import com.edde746.tortuga.trackers.TrackerItem
import com.edde746.tortuga.trackers.TrackerService
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

/**
 * The scores the profile's connected trackers have for a title, for Detail's ratings row.
 *
 * Every connected tracker is asked at once, each with one request (two where the service first maps the title's ids
 * to its own: Simkl always, Trakt without an IMDb id), and the answers come in as they arrive. MyAnimeList and AniList
 * wait for the title's anime ids (which may download the mapping datasets); the others don't.
 *
 * Answers are kept per service and title for [TTL] (the services refresh their scores daily at most), the
 * [CAPACITY] most recently used; a title a service doesn't know is kept as such. A failed or rate-limited request
 * leaves that service's scores out, is logged and isn't kept, so the next visit asks again. Main-thread confined, as
 * the hub that owns it.
 */
class TrackerRatings internal constructor(
    private val sources: (profileId: String) -> Flow<List<TrackerRatingsSource>>,
    private val resolver: TrackerIdResolver,
    private val timeSource: TimeSource.WithComparableMarks,
) {
    private data class Key(val service: TrackerService, val title: RatingTitle)

    private class Entry(val ratings: List<TitleRating>, val at: ComparableTimeMark)

    /** Least recently used first. */
    private val cache = LinkedHashMap<Key, Entry>()

    /**
     * The trackers' scores for [meta] (the title Detail shows), as one growing list: emitted again each time another
     * tracker adds scores, in no particular order ([TitleRatings.merge] orders them). Emits nothing when the profile has
     * no tracker connected or none has a score; completes once every tracker has answered.
     */
    fun ratings(profileId: String, meta: MetaItem): Flow<List<TitleRating>> = channelFlow {
        val connected = sources(profileId).first()
        if (connected.isEmpty()) return@channelFlow
        val item = TrackerItem(metaId = meta.id, type = meta.type, videoId = meta.id, meta = meta)
        val catalogIds = async { resolve(item, animeIds = false) }
        val animeIds: Deferred<ResolvedIds?>? = if (connected.any { it.needsAnimeIds }) async { resolve(item, animeIds = true) } else null
        val movie = meta.type == MOVIE
        for (source in connected) {
            launch {
                val ids = (if (source.needsAnimeIds) animeIds else catalogIds)?.await() ?: return@launch
                val scores = load(source, RatingTitle(movie, ids.ids, ids.anime))
                if (scores.isNotEmpty()) send(scores)
            }
        }
    }.runningFold(emptyList<TitleRating>()) { all, more -> all + more }.drop(1)

    private suspend fun resolve(item: TrackerItem, animeIds: Boolean): ResolvedIds? = try {
        resolver.resolve(item, animeIds)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, e) { "ratings: resolving ${item.metaId} failed" }
        null
    }

    private suspend fun load(source: TrackerRatingsSource, title: RatingTitle): List<TitleRating> {
        val key = Key(source.service, title)
        cache.remove(key)?.let { entry ->
            if (entry.at.elapsedNow() < TTL) {
                cache[key] = entry
                return entry.ratings
            }
        }
        val scores = try {
            source.ratings(title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerException) {
            Log.i(TAG) { "${source.service.id} ratings left out: ${e.message}" }
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, e) { "${source.service.id} ratings left out" }
            return emptyList()
        }
        cache[key] = Entry(scores, timeSource.markNow())
        while (cache.size > CAPACITY) cache.remove(cache.keys.first())
        return scores
    }

    internal companion object {
        private const val TAG = "trackers"
        private const val MOVIE = "movie"
        val TTL: Duration = 12.hours
        const val CAPACITY = 300
    }
}
