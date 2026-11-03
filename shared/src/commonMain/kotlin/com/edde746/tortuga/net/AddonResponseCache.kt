package com.edde746.tortuga.net

import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.CacheHints
import com.edde746.tortuga.model.ResourceContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * In-memory cache of decoded addon responses, keyed by request URL, honouring the response's own [CacheHints] as
 * HTTP caches honour `Cache-Control`:
 * - younger than `cacheMaxAge`: [Lookup.Fresh], served without a request;
 * - within `staleRevalidate` after that: [Lookup.Stale], served while the caller refreshes it in the background;
 * - within `staleError` after `cacheMaxAge`: served by [staleIfError] when a new request fails.
 *
 * A response with hints, none of them positive, is not stored. A meta answer without any hint (Cinemeta's: its server
 * lets HTTP caches keep them 3 h) is kept as fresh for [RECENT_META_AGE], the [RECENT_METAS] most recent of them, so
 * Detail opened again (back and forth from Home) asks nothing. The cache is bounded by the total size of the stored
 * bodies ([maxBodyChars]), evicting the least recently used entry first. Keys are addon URLs, which are secrets
 * (configured addons embed API keys in them): the cache never leaves memory and is never logged. Safe to use from
 * any coroutine.
 */
class AddonResponseCache(
    private val maxBodyChars: Long = 16L * 1024 * 1024,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    sealed interface Lookup {
        data class Fresh(val response: AddonResponse) : Lookup
        data class Stale(val response: AddonResponse) : Lookup
        data object Miss : Lookup
    }

    private class Entry(val response: AddonResponse, val size: Long, val stored: TimeMark, val recentMeta: Boolean) {
        val maxAge: Duration = if (recentMeta) RECENT_META_AGE else (response.cache.cacheMaxAge ?: 0L).seconds
        val revalidateUntil: Duration = maxAge + (response.cache.staleRevalidate ?: 0L).seconds
        val errorUntil: Duration = maxAge + (response.cache.staleError ?: 0L).seconds
        val expiresAfter: Duration = maxOf(revalidateUntil, errorUntil)
    }

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()
    private var totalSize = 0L
    private var recentMetas = 0

    suspend fun lookup(url: String): Lookup = mutex.withLock {
        val entry = touch(url) ?: return@withLock Lookup.Miss
        val age = entry.stored.elapsedNow()
        when {
            age < entry.maxAge -> Lookup.Fresh(entry.response)
            age < entry.revalidateUntil -> Lookup.Stale(entry.response)
            else -> Lookup.Miss
        }
    }

    /** The stored response for [url] if a failed request may still fall back to it (`stale-if-error`). */
    suspend fun staleIfError(url: String): AddonResponse? = mutex.withLock {
        val entry = touch(url) ?: return@withLock null
        entry.response.takeIf { entry.stored.elapsedNow() < entry.errorUntil }
    }

    /** Stores [response] for [url]; [bodySize] is the length of the body it was decoded from. */
    suspend fun store(url: String, response: AddonResponse, bodySize: Long) {
        val hints = response.cache
        val cacheable = listOfNotNull(hints.cacheMaxAge, hints.staleRevalidate, hints.staleError).any { it > 0 }
        val recentMeta = !cacheable && hints == CacheHints() && response.content is ResourceContent.Meta
        mutex.withLock {
            remove(url)
            if (!(cacheable || recentMeta) || bodySize > maxBodyChars) return@withLock
            entries[url] = Entry(response, bodySize, timeSource.markNow(), recentMeta)
            totalSize += bodySize
            if (recentMeta) recentMetas++
            val iterator = entries.entries.iterator()
            while ((totalSize > maxBodyChars || recentMetas > RECENT_METAS) && iterator.hasNext()) {
                val entry = iterator.next().value
                if (totalSize <= maxBodyChars && !entry.recentMeta) continue
                drop(entry)
                iterator.remove()
            }
        }
    }

    /** The entry for [url] moved to the most recently used end, or null (expired entries are dropped). */
    private fun touch(url: String): Entry? {
        val entry = entries.remove(url) ?: return null
        if (entry.stored.elapsedNow() >= entry.expiresAfter) {
            drop(entry)
            return null
        }
        entries[url] = entry
        return entry
    }

    private fun remove(url: String) {
        entries.remove(url)?.let(::drop)
    }

    /** Takes [entry], already out of [entries] or about to be, out of the counts. */
    private fun drop(entry: Entry) {
        totalSize -= entry.size
        if (entry.recentMeta) recentMetas--
    }

    private companion object {
        val RECENT_META_AGE = 30.minutes
        const val RECENT_METAS = 8
    }
}
