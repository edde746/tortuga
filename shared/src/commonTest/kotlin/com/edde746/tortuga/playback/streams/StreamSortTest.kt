package com.edde746.tortuga.playback.streams

import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.settings.StreamSortKey
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamBehaviorHints
import com.edde746.tortuga.model.StreamSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** The stream lists' sort ([StreamSort]): each key's order, the unknowns after, ties keeping the order they came in. */
class StreamSortTest {
    private fun stream(name: String, description: String? = null, size: Long? = null) = Stream(
        StreamSource.Url("https://cdn.example/${name.hashCode()}.mkv"),
        name = name,
        description = description,
        behaviorHints = StreamBehaviorHints(videoSize = size),
    )

    private fun sorted(vararg keys: StreamSortKey, streams: List<Stream>): List<String?> =
        StreamSort(keys.toList()).sorted(streams, { it }, StreamBadgeParser::parse).map { it.name }

    private val webFhd = stream("1080p WEB", "Movie.2026.1080p.WEB-DL.H264\n💾 2.1 GB")
    private val remuxUhd = stream("2160p remux", "Movie.2026.2160p.BluRay.REMUX.HEVC\n💾 58.3 GB")
    private val hdtv = stream("720p HDTV", "Movie.2026.720p.HDTV.x264\n💾 900 MB")
    private val dvdRip = stream("DVDRip", "Movie.2026.DVDRip.x264\n💾 1.4 GB")
    private val webUhd = stream("2160p WEB", "Movie.2026.2160p.WEB-DL.DV.HEVC\n💾 14.8 GB")
    private val bareFhd = stream("1080p", "no source, no size")
    private val list = listOf(webFhd, remuxUhd, hdtv, dvdRip, webUhd, bareFhd)

    @Test
    fun addonOrderKeepsTheListAsItCame() {
        assertEquals(list.map { it.name }, sorted(streams = list))
        assertEquals(list, StreamSort.ADDON_ORDER.sorted(list, { it }, StreamBadgeParser::parse))
    }

    @Test
    fun resolutionHighestFirstTiesKeepingTheirOrderAndUnknownLast() {
        assertEquals(
            listOf("2160p remux", "2160p WEB", "1080p WEB", "1080p", "720p HDTV", "DVDRip"),
            sorted(StreamSortKey.RESOLUTION, streams = list),
        )
    }

    @Test
    fun sourceBestFirstThenUnknown() {
        assertEquals(
            listOf("2160p remux", "1080p WEB", "2160p WEB", "720p HDTV", "DVDRip", "1080p"),
            sorted(StreamSortKey.SOURCE, streams = list),
        )
    }

    @Test
    fun sizeEitherWayTheAddonsVideoSizeFirstAndUnknownLast() {
        // The addon's exact size wins over its text's; a stream that says no size goes last either way.
        val exact = stream("exact", "Movie.2026.1080p.WEB-DL\n💾 1 GB", size = 30_000_000_000)
        val streams = list + exact
        assertEquals(
            listOf("2160p remux", "exact", "2160p WEB", "1080p WEB", "DVDRip", "720p HDTV", "1080p"),
            sorted(StreamSortKey.SIZE_LARGEST, streams = streams),
        )
        assertEquals(
            listOf("720p HDTV", "DVDRip", "1080p WEB", "2160p WEB", "exact", "2160p remux", "1080p"),
            sorted(StreamSortKey.SIZE_SMALLEST, streams = streams),
        )
    }

    @Test
    fun instantFirstThenUnsaidThenNotReady() {
        val instant = stream("[⚡] Addon A", "Movie.2026.1080p.WEB")
        val notReady = stream("Addon A not ready", "Movie.2026.2160p.WEB")
        assertEquals(listOf("[⚡] Addon A", "1080p", "Addon A not ready"), sorted(StreamSortKey.CACHED, streams = listOf(notReady, bareFhd, instant)))
    }

    @Test
    fun laterKeysBreakTheEarliersTies() {
        assertEquals(
            listOf("2160p remux", "2160p WEB", "1080p WEB", "1080p", "720p HDTV", "DVDRip"),
            sorted(StreamSortKey.RESOLUTION, StreamSortKey.SIZE_LARGEST, streams = list),
        )
        // Smallest first under each resolution: the 14.8 GB WEB-DL before the 58.3 GB remux; 1080p unsized after the WEB-DL.
        assertEquals(
            listOf("2160p WEB", "2160p remux", "1080p WEB", "1080p", "720p HDTV", "DVDRip"),
            sorted(StreamSortKey.RESOLUTION, StreamSortKey.SIZE_SMALLEST, streams = list),
        )
        // Source first: the remux, the two WEB-DLs by resolution, HDTV, the DVD rip, the unknown.
        assertEquals(
            listOf("2160p remux", "2160p WEB", "1080p WEB", "720p HDTV", "DVDRip", "1080p"),
            sorted(StreamSortKey.SOURCE, StreamSortKey.RESOLUTION, streams = list),
        )
    }

    @Test
    fun storedKeysDecodeLenientlyEachOnceOneSizeDirection() {
        val settings = ProfileSettings.fromStored(
            // A key a later version added is dropped; a repeated one counts once; of the two size directions the first stays.
            mapOf("streams.sort" to """["resolution","popularity","sizeSmallest","resolution","sizeLargest","cached"]"""),
        )
        assertEquals(listOf(StreamSortKey.RESOLUTION, StreamSortKey.SIZE_SMALLEST, StreamSortKey.CACHED), settings.streamSort)
        assertEquals(emptyList(), ProfileSettings.DEFAULT.streamSort)
    }
}
