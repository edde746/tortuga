package com.edde746.tortuga.playback.mpv

import com.edde746.tortuga.playback.LoadRequest
import com.edde746.tortuga.playback.PlaybackOptions
import kotlin.test.Test
import kotlin.test.assertEquals

class MpvLoadTest {
    private val options = PlaybackOptions(hwdec = "videotoolbox", demuxerMaxBytes = 150 * MIB, demuxerMaxBackBytes = 50 * MIB)

    private fun request(
        title: String? = null,
        startSeconds: Double = 0.0,
        demuxerFormat: String? = null,
        liveStartIndex: Int? = null,
        live: Boolean = false,
    ) = LoadRequest(URL, options, title, emptyMap(), startSeconds, demuxerFormat, liveStartIndex, live)

    @Test
    fun aPlainFileClearsTheLastFilesProperties() {
        assertEquals(
            mapOf(
                "force-media-title" to "", "start" to "none", "demuxer-lavf-format" to "", "demuxer-lavf-o" to "",
                "cache-pause-initial" to "no", "cache-pause-wait" to "1",
            ),
            MpvLoad.fileProperties(request()),
        )
    }

    @Test
    fun aFileOpensWhereAskedUnderItsTitleWithItsDemuxer() {
        assertEquals(
            mapOf(
                "force-media-title" to "Tortuga, Live", "start" to "61.500", "demuxer-lavf-format" to "hls", "demuxer-lavf-o" to "",
                "cache-pause-initial" to "no", "cache-pause-wait" to "1",
            ),
            MpvLoad.fileProperties(request(title = "Tortuga, Live", startSeconds = 61.5, demuxerFormat = "hls")),
        )
    }

    /** A rewind in a live window reopens the stream at a segment counted from the playlist's end. */
    @Test
    fun aLiveRewindOpensAtItsSegment() {
        assertEquals("live_start_index=-30", MpvLoad.fileProperties(request(liveStartIndex = -30))["demuxer-lavf-o"])
    }

    /**
     * A live file goes on after a stall only with 6 s buffered, and starts so unless it joins its playlist three or more
     * segments before the end, the hold-back read at once; a first open (lavf's own join, or no HLS at all) and a join
     * closer to the end wait.
     */
    @Test
    fun aLiveFileStartsAtItsFirstFrameOnlyWhenItJoinsWithTheHoldBack() {
        fun buffering(liveStartIndex: Int?) = MpvLoad.fileProperties(request(liveStartIndex = liveStartIndex, live = true))
            .let { it["cache-pause-initial"] to it["cache-pause-wait"] }
        assertEquals("yes" to "6", buffering(null), "a first open")
        assertEquals("yes" to "6", buffering(-2), "two segments before the end")
        assertEquals("no" to "6", buffering(-3), "three segments before the end")
        assertEquals("no" to "6", buffering(-20), "a rewind")
    }

    @Test
    fun headersAreOneListWithEscapedCommas() {
        val headers = mapOf("User-Agent" to "Tortuga/1, like mpv", "X-Path" to "a\\b")
        assertEquals("""User-Agent: Tortuga/1\, like mpv,X-Path: a\b""", MpvLoad.headerFields(headers))
    }

    @Test
    fun noHeadersClearTheLastFilesList() {
        assertEquals("", MpvLoad.headerFields(emptyMap()))
    }

    private companion object {
        const val URL = "http://host/a.mkv"
        const val MIB = 1024L * 1024
    }
}
