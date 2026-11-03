package com.edde746.tortuga.playback.streams

import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.settings.StreamSortKey
import com.edde746.tortuga.model.Stream

/**
 * The profile's sort of the stream lists and of auto-play's pick: [keys] in order, most important first, each read
 * from a stream's text ([StreamBadgeParser]; the size from the addon's `videoSize` first, [StreamFilter.sizeOf]). A
 * stream whose text doesn't say goes after the ones that do.
 * The sort is stable: streams the keys rank alike keep the order they came in (the addons as installed, then each
 * one's own order), and with no key every stream does ([ADDON_ORDER]).
 */
class StreamSort(val keys: List<StreamSortKey>) {
    /** No key: the addons' own order. */
    val addonOrder: Boolean get() = keys.isEmpty()

    /** [items] in this sort, [stream] and [badges] giving each one's stream and what its text says. */
    fun <T> sorted(items: List<T>, stream: (T) -> Stream, badges: (T) -> StreamBadges): List<T> {
        if (keys.isEmpty() || items.size < 2) return items
        val ranked = items.map { item ->
            val parsed = badges(item)
            val of = stream(item)
            Ranked(item, LongArray(keys.size) { rank(keys[it], of, parsed) })
        }
        return ranked.sortedWith(byRanks).map { it.item }
    }

    private class Ranked<T>(val item: T, val ranks: LongArray)

    override fun equals(other: Any?): Boolean = other is StreamSort && other.keys == keys

    override fun hashCode(): Int = keys.hashCode()

    companion object {
        /** The addons' own order. */
        val ADDON_ORDER: StreamSort = StreamSort(emptyList())

        /** The sort of a profile's [settings]. */
        fun of(settings: ProfileSettings): StreamSort = StreamSort(settings.streamSort)

        /** Ranks after every value read: the text doesn't say. */
        private const val UNKNOWN = 1_000L

        private val byRanks = Comparator<Ranked<*>> { a, b ->
            for (i in a.ranks.indices) {
                val order = a.ranks[i].compareTo(b.ranks[i])
                if (order != 0) return@Comparator order
            }
            0
        }

        /** Lower first. */
        private fun rank(key: StreamSortKey, stream: Stream, badges: StreamBadges): Long = when (key) {
            StreamSortKey.RESOLUTION -> badges.resolution?.ordinal?.toLong() ?: UNKNOWN
            StreamSortKey.SOURCE -> badges.source?.ordinal?.toLong() ?: UNKNOWN
            StreamSortKey.SIZE_LARGEST -> StreamFilter.sizeOf(stream, badges)?.let { -it } ?: Long.MAX_VALUE
            StreamSortKey.SIZE_SMALLEST -> StreamFilter.sizeOf(stream, badges) ?: Long.MAX_VALUE
            StreamSortKey.CACHED -> when (badges.cached) {
                true -> 0
                null -> 1
                false -> 2
            }
        }
    }
}
