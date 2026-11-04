package com.edde746.tortuga.playback.session

import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.playback.mpv.MpvLoad
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.resolve.redirect
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A live stream's rewind window ([com.edde746.tortuga.playback.session.SessionInfo.liveWindow]): read from its HLS
 * playlist, reached by a seek ahead or a reopen at a segment, and nothing about it saved.
 */
class LiveWindowTest {
    private val channel = MetaItem(MetaItemPreview(id = "tortuga-live-now", type = "tv", name = "Tortuga Live Now"))
    private val stream = urlStream("https://live.example/event.m3u8")

    /** The stream's media playlist: [segments] segments of 2 s, closed with `#EXT-X-ENDLIST` once [ended]. */
    private var segments = 45
    private var ended = false
    private var playlistReads = 0

    private fun SessionHarness.servePlaylist() {
        routes["live.example"] = {
            playlistReads++
            val text = buildString {
                append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:500\n")
                repeat(segments) { append("#EXTINF:2.000,\nevent/${500 + it}.ts\n") }
                if (ended) append("#EXT-X-ENDLIST\n")
            }
            respond(text, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/vnd.apple.mpegurl"))
        }
    }

    /** The engine shows the live file's first frame at [position], as mpv does: unseekable, 6 s downloaded. */
    private fun SessionHarness.firstFrame(position: Double = 0.0) {
        engine.report {
            it.copy(loaded = true, durationSeconds = position + 6.0, positionSeconds = position, buffering = false, seekable = false, playbackRestarts = 1)
        }
        settle()
    }

    @Test
    fun theWindowIsThePlaylistAndARewindReopensTheStreamAtItsSegment() = runTest {
        val h = SessionHarness(this)
        h.servePlaylist()
        h.start(PlayRequest(channel, channel.id, choice(stream)))
        assertNull(h.session.info.value.liveWindow, "nothing before the first frame")
        h.firstFrame()
        val window = assertNotNull(h.session.info.value.liveWindow)
        assertEquals(90.0, window.windowSeconds)
        assertEquals(6.0, window.behind(0.0), "lavf opens three segments before the playlist's end")

        h.session.seekLive(40.0)
        h.settle()
        val reopen = h.engine.loads.last()
        assertEquals(2, h.engine.loads.size, "back past what was read: the stream opens again")
        assertEquals(-20, reopen.liveStartIndex, "at the segment that holds 40 s behind")
        assertEquals(0.0, reopen.startSeconds)
        assertEquals(40.0, h.session.info.value.liveWindow!!.behind(0.0), "placed in the window at once")
        h.firstFrame(position = 0.2)
        assertEquals(40.0, h.session.info.value.liveWindow!!.behind(0.2), 1e-9, "and at its first frame")

        // Ahead within what the engine read: a seek, no reopen.
        h.engine.report { it.copy(cacheEndSeconds = 0.2 + 40.0) }
        h.settle()
        h.session.seekLive(20.0)
        h.settle()
        assertEquals(2, h.engine.loads.size)
        assertEquals(20.2, h.engine.state.positionSeconds, 1e-9)

        // Back again past what was read, then to the edge with too little read ahead to reach it: the edge's segment.
        h.session.seekLive(30.0)
        h.engine.report { it.copy(cacheEndSeconds = 1.0) }
        h.firstFrame(position = 0.1)
        h.session.seekLive(0.0)
        h.settle()
        assertEquals(listOf(null, -20, -15, -3), h.engine.loads.map { it.liveStartIndex })
    }

    /**
     * Go to live from behind, the read-ahead short of the edge: the stream opens again three segments before the
     * playlist's end, with them read at once, and plays from its first frame there, where the window places it, as the
     * rewind's reopen does; each goes on after a stall only with seconds buffered. Waiting to start with seconds buffered
     * too, as the title's first open does, mpv would wait for the edge to move on and play further behind it.
     */
    @Test
    fun aReopenAtTheEdgeOrBehindItPlaysFromItsFirstFrame() = runTest {
        val h = SessionHarness(this)
        h.servePlaylist()
        h.start(PlayRequest(channel, channel.id, choice(stream)))
        h.firstFrame()
        h.session.seekLive(40.0)
        h.settle()
        h.firstFrame(position = 0.2)
        h.session.seekLive(0.0)
        h.settle()
        assertEquals(listOf(null, -20, -3), h.engine.loads.map { it.liveStartIndex })
        val buffering = h.engine.loads.map { MpvLoad.fileProperties(it).let { p -> p["cache-pause-initial"] to p["cache-pause-wait"] } }
        assertEquals(listOf("yes" to "6", "no" to "6", "no" to "6"), buffering, "the first open, the rewind, go to live")
    }

    /**
     * Go to live from behind, the read-ahead reaching the edge: the engine seeks to where an open lands, within what
     * mpv can seek to, and the playback reads as at the edge while it plays on.
     */
    @Test
    fun goToLiveFromBehindReachesTheEdgeAndStaysThereWhilePlaying() = runTest {
        val h = SessionHarness(this)
        h.servePlaylist()
        h.start(PlayRequest(channel, channel.id, choice(stream)))
        h.firstFrame()
        h.session.seekLive(40.0)
        h.settle()
        h.firstFrame(position = 0.2)
        // The reopened file read ahead to a second before the edge. As mpv 0.41 with serve.py's live stream, its last
        // 1.8 s are a keyframe range not yet whole, past which it refuses a seek.
        h.engine.report { it.copy(cacheEndSeconds = 0.2 + 39.0) }
        h.settle()
        h.engine.seekableEnd = 0.2 + 39.0 - 1.8
        h.session.seekLive(0.0)
        h.settle()
        assertEquals(2, h.engine.loads.size, "within what the engine read: a seek, no reopen")
        val landed = h.session.info.value.liveWindow!!.behind(h.engine.state.positionSeconds)
        assertEquals(6.0, landed, 1e-9, "where an open lands, three segments before the playlist's end")
        assertTrue(h.session.info.value.liveWindow!!.atEdge(landed))

        h.playTo(h.engine.state.positionSeconds + 30.0)
        val window = h.session.info.value.liveWindow!!
        assertEquals(6.0, window.behind(h.engine.state.positionSeconds), 1e-9, "and stays there while it plays")
        assertTrue(window.atEdge(window.behind(h.engine.state.positionSeconds)))
    }

    /**
     * Back within what the engine holds read since the file opened: a seek, no reopen, landing where asked. Before what
     * it holds, where mpv would land at its start instead, the stream opens again at the point's segment.
     */
    @Test
    fun aRewindWithinWhatTheEngineHoldsIsASeek() = runTest {
        val h = SessionHarness(this)
        h.servePlaylist()
        h.start(PlayRequest(channel, channel.id, choice(stream)))
        h.firstFrame()
        h.playTo(40.0)
        // As mpv 0.41 after a minute of serve.py's live stream: it holds everything since the first frame.
        h.engine.report { it.copy(cacheStartSeconds = 0.0) }
        h.settle()
        val behind = h.session.info.value.liveWindow!!.behind(h.engine.state.positionSeconds)
        h.session.seekLive(behind + 30.0)
        h.settle()
        assertEquals(1, h.engine.loads.size, "within what the engine holds: a seek, no reopen")
        assertEquals(10.0, h.engine.state.positionSeconds, 1e-9, "30 s back in the file")
        assertEquals(behind + 30.0, h.session.info.value.liveWindow!!.behind(h.engine.state.positionSeconds), 1e-9)

        h.session.seekLive(behind + 50.0)
        h.settle()
        assertEquals(2, h.engine.loads.size, "before what it holds: the stream opens again")
    }

    @Test
    fun nothingIsSavedWhileBehindOrAtTheEdge() = runTest {
        val h = SessionHarness(this)
        h.servePlaylist()
        h.start(PlayRequest(channel, channel.id, choice(stream)))
        h.firstFrame()
        h.playTo(20.0)
        h.session.seekLive(60.0)
        h.settle()
        h.firstFrame(position = 0.1)
        h.playTo(30.0)
        h.session.seekLive(0.0)
        h.settle()
        h.firstFrame()
        h.playTo(10.0)
        h.session.close()
        h.settle()

        val state = h.item(channel.id)!!.state
        assertEquals(0, state.timeOffset)
        assertEquals(0, state.duration)
        assertEquals(0, state.timesWatched)
        assertNotNull(h.data.streamHistory.lastStream(h.profileId, channel.id, channel.id), "its stream is remembered once")
    }

    @Test
    fun aPlaylistShorterThanTheMinimumOrNoPlaylistIsNoWindow() = runTest {
        segments = 10
        val h = SessionHarness(this)
        h.servePlaylist()
        h.start(PlayRequest(channel, channel.id, choice(stream)))
        h.firstFrame()
        assertNull(h.session.info.value.liveWindow, "20 s is short of the minimum")
        h.session.seekLive(10.0)
        h.settle()
        assertEquals(1, h.engine.loads.size, "nothing to rewind")

        val other = SessionHarness(this)
        other.serveCdn(lcgBytes(300_000))
        other.routes["addon.example"] = { request -> redirect("https://cdn.example/${request.url.segments.last()}.mkv") }
        other.start(PlayRequest(channel, channel.id, choice(urlStream("https://addon.example/live"))))
        other.firstFrame()
        assertNull(other.session.info.value.liveWindow, "a live stream that isn't HLS has none")
    }

    @Test
    fun anEventThatEndsLosesItsWindowAndStopsReadingThePlaylist() = runTest {
        val h = SessionHarness(this)
        h.servePlaylist()
        h.start(PlayRequest(channel, channel.id, choice(stream)))
        h.firstFrame()
        assertNotNull(h.session.info.value.liveWindow)
        h.advance(4.seconds)
        assertNotNull(h.session.info.value.liveWindow, "read again every target duration")

        ended = true
        h.advance(2.seconds)
        assertNull(h.session.info.value.liveWindow, "the event ended: it plays to its end as live")
        val reads = playlistReads
        h.advance(30.seconds)
        assertEquals(reads, playlistReads, "an ended playlist isn't read again")
    }
}
