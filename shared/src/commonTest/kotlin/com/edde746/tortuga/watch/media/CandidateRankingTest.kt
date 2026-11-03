package com.edde746.tortuga.watch.media

import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.AddonLoadable
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.StreamQuality
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamBehaviorHints
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.streams.StreamSort
import kotlin.test.Test
import kotlin.test.assertEquals

/** The order a guest opens its candidates in ([CandidateRanking]): the host's file, size, then the guest's own order. */
class CandidateRankingTest {
    private val first = addon("first")
    private val second = addon("second")

    private fun ready(addon: Descriptor, vararg streams: Stream) =
        AddonLoadable(addon, ResourceRequest(addon.transport, ResourcePath("stream", "series", "tt1:1:2")), Loadable.Ready(streams.toList()))

    private fun failed(addon: Descriptor) =
        AddonLoadable<List<Stream>>(addon, ResourceRequest(addon.transport, ResourcePath("stream", "series", "tt1:1:2")), Loadable.Error(AddonError.Timeout))

    private fun stream(path: String, name: String? = null, hash: String? = null, size: Long? = null, filename: String? = null) = Stream(
        StreamSource.Url("https://cdn.example/$path"),
        name = name,
        behaviorHints = StreamBehaviorHints(videoHash = hash, videoSize = size, filename = filename),
    )

    private val host = FileFingerprint(HOUR, sizeBytes = 2 * GIB, videoHash = "8e245d9679d31e12", filename = "Example.Show.S01E02.1080p.WEB.mkv")
    private val bare = FileFingerprint(HOUR)

    private fun rank(
        host: FileFingerprint,
        vararg states: AddonLoadable<List<Stream>>,
        filter: StreamFilter = StreamFilter.NONE,
        mode: AutoPlayMode = AutoPlayMode.FIRST_MATCH,
        remembered: StreamChoice? = null,
    ): List<String> = CandidateRanking.rank(states.toList(), host, filter, StreamSort.ADDON_ORDER, mode, remembered).map { it.path }

    @Test
    fun theHostsVideoHashComesFirst() {
        val order = rank(
            host,
            ready(first, stream("a", size = 2 * GIB), stream("b")),
            ready(second, stream("c", hash = "8E245D9679D31E12")),
        )
        assertEquals(listOf("c", "a", "b"), order)
    }

    @Test
    fun thenTheSameFileNameOrSizeThenTheClosestSize() {
        val order = rank(
            host,
            ready(
                first,
                stream("far", name = "Streams\n💾 9.5 GB"),
                stream("unknown"),
                stream("close", name = "Streams\n💾 2.1 GB"),
                stream("named", filename = "example.show.s01e02.1080p.web.mkv"),
                stream("closer", size = 2 * GIB + 1000),
                stream("sized", name = "Streams\n💾 9.9 GB", size = 2 * GIB),
            ),
        )
        // Within each rule the closer size goes first; a size hint wins over the size the text names.
        assertEquals(listOf("sized", "named", "closer", "close", "far", "unknown"), order)
    }

    @Test
    fun withoutAHostSizeTheGuestsOwnOrderDecides() {
        val order = rank(bare, ready(first, stream("a", name = "💾 9 GB"), stream("b", name = "💾 2 GB")))
        assertEquals(listOf("a", "b"), order)
    }

    @Test
    fun theGuestsOrderIsAutoPlaysPickThenTheLastLinkThenTheFilters() {
        val uhd = stream("uhd", name = "Streams\n2160p")
        val fhd = stream("fhd", name = "Streams\n1080p")
        val hd = stream("hd", name = "Streams\n720p")
        val sd = stream("sd", name = "Streams\n480p")
        val states = arrayOf(failed(first), ready(second, uhd, fhd, hd, sd))
        val no4k = StreamFilter(setOf(StreamQuality.UHD_4K), "", "")
        val remembered = StreamChoice(hd, second.transportUrl, "")

        assertEquals(listOf("fhd", "hd", "sd", "uhd"), rank(bare, *states, filter = no4k, mode = AutoPlayMode.FIRST_MATCH, remembered = remembered))
        assertEquals(listOf("hd", "fhd", "sd", "uhd"), rank(bare, *states, filter = no4k, mode = AutoPlayMode.REUSE_LAST_LINK, remembered = remembered))
        assertEquals(listOf("hd", "fhd", "sd", "uhd"), rank(bare, *states, filter = no4k, mode = AutoPlayMode.OFF, remembered = remembered))
        assertEquals(listOf("fhd", "hd", "sd", "uhd"), rank(bare, *states, filter = no4k, mode = AutoPlayMode.OFF))
        // A remembered link of another addon, or one no longer offered, places nothing.
        assertEquals(listOf("fhd", "hd", "sd", "uhd"), rank(bare, *states, filter = no4k, remembered = StreamChoice(hd, first.transportUrl, "")))
        assertEquals(listOf("fhd", "hd", "sd", "uhd"), rank(bare, *states, filter = no4k, remembered = StreamChoice(stream("gone"), second.transportUrl, "")))
    }

    @Test
    fun theHostsFileOutranksTheGuestsOrder() {
        val fhd = stream("fhd", name = "Streams\n1080p")
        val uhd = stream("uhd", name = "Streams\n2160p", hash = "8e245d9679d31e12")
        val no4k = StreamFilter(setOf(StreamQuality.UHD_4K), "", "")
        val order = rank(host, ready(first, fhd, uhd), filter = no4k, remembered = StreamChoice(fhd, first.transportUrl, ""))
        assertEquals(listOf("uhd", "fhd"), order)
    }

    @Test
    fun streamsNotReadyOrNotPlayableAreNeverCandidates() {
        val order = rank(
            host,
            ready(
                first,
                stream("later", name = "[Not ready] Addon A\n1080p", hash = "8e245d9679d31e12"),
                stream("ready", name = "⚡ Addon A\n1080p"),
                stream("plain"),
                Stream(StreamSource.PeerToPeer("0123456789abcdef0123456789abcdef01234567", fileIdx = 1), name = "1080p"),
                Stream(StreamSource.YouTube("dQw4w9WgXcQ"), name = "Trailer"),
            ),
        )
        assertEquals(listOf("ready", "plain"), order)
    }
}

internal const val GIB = 1L shl 30
