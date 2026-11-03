package com.edde746.tortuga.playback.session

import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.mpv.MpvLoad
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.resolve.redirect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A live playback ([com.edde746.tortuga.playback.session.SessionInfo.live]): what the session saves of it. */
class LivePlaybackTest {
    private val channel = MetaItem(MetaItemPreview(id = "tortuga-live", type = "tv", name = "Tortuga 24/7"))

    private fun SessionHarness.links() {
        serveCdn(lcgBytes(300_000))
        routes["addon.example"] = { request -> redirect("https://cdn.example/${request.url.segments.last()}.mkv") }
    }

    /**
     * The engine shows the file it loaded, saying it can't seek in it: a live stream's (its duration what it has
     * downloaded so far) or a video's from a server without Range support.
     */
    private fun SessionHarness.openUnseekable(duration: Double) {
        val start = engine.loads.last().startSeconds
        engine.report {
            it.copy(loaded = true, durationSeconds = duration, positionSeconds = start, buffering = false, seekable = false, playbackRestarts = 1)
        }
        settle()
    }

    private fun SessionHarness.seekTo(seconds: Double) {
        session.onSeek(seconds)
        engine.seek(seconds, exact = false)
        settle()
    }

    @Test
    fun aLiveTitleSavesNoPositionEvenWhenItsFileHasADurationAndKeepsItsStream() = runTest {
        val h = SessionHarness(this)
        h.links()
        h.start(PlayRequest(channel, channel.id, choice(urlStream("https://addon.example/live")), startPositionSeconds = 30.0))
        assertTrue(h.session.info.value.live, "the title says so before anything plays")
        assertEquals(0.0, h.engine.loads.single().startSeconds, "a live title starts where the stream is, never at a position")

        h.engine.open(duration = 3000.0)
        h.settle()
        assertTrue(h.session.info.value.live, "a duration and a seekable file don't make the title a video")
        h.playTo(60.0)
        h.seekTo(10.0)
        h.playTo(2900.0, step = 100.0)
        h.session.close()
        h.settle()

        val state = h.item(channel.id)!!.state
        assertEquals(0, state.timeOffset)
        assertEquals(0, state.duration)
        assertEquals(0, state.timesWatched)
        assertTrue(state.watchedVideos.isNullOrEmpty(), "never counted as watched")
        assertNotNull(h.data.streamHistory.lastStream(h.profileId, channel.id, channel.id), "its stream is remembered")
    }

    /**
     * A live title's stream arrives at 1×: it opens with seconds buffered, so a late segment doesn't stall it. A video
     * starts at its first frame. What the engine sets for each load is mpv's (MpvLoad.fileProperties).
     */
    @Test
    fun aLiveTitleOpensWithSecondsBufferedAndAVideoAtItsFirstFrame() = runTest {
        val h = SessionHarness(this)
        h.links()
        h.start(PlayRequest(channel, channel.id, choice(urlStream("https://addon.example/live"))))
        val live = MpvLoad.fileProperties(h.engine.loads.single())
        assertEquals("yes" to "6", live["cache-pause-initial"] to live["cache-pause-wait"], "the live channel")
        h.session.close()
        h.settle()

        val v = SessionHarness(this)
        v.links()
        v.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/video"))))
        val video = MpvLoad.fileProperties(v.engine.loads.single())
        assertEquals("no" to "1", video["cache-pause-initial"] to video["cache-pause-wait"], "the movie")
        v.session.close()
        v.settle()
    }

    @Test
    fun anUnseekableFileWhoseDurationGrowsPlaysLiveAndAVideoFileAfterItDoesNot() = runTest {
        val h = SessionHarness(this)
        h.links()
        val live = choice(urlStream("https://addon.example/live"))
        val video = choice(urlStream("https://addon.example/video"))
        h.start(PlayRequest(MOVIE, MOVIE.id, live, alternatives = listOf(video)))
        assertFalse(h.session.info.value.live, "nothing says so before the engine shows the file")

        h.openUnseekable(duration = 6.0)
        assertFalse(h.session.info.value.live, "not before its duration grows")
        h.engine.report { it.copy(durationSeconds = 8.0) }
        h.settle()
        assertTrue(h.session.info.value.live)
        h.playTo(40.0)
        h.seekTo(5.0)
        assertEquals(0, h.item(MOVIE.id)!!.state.timeOffset, "no progress or seek saved")
        assertNotNull(h.data.streamHistory.lastStream(h.profileId, MOVIE.id, MOVIE.id), "its stream is remembered")

        h.session.playNextStream()
        h.settle()
        assertFalse(h.session.info.value.live, "the next file starts out as the title's")
        h.engine.open(duration = 100.0)
        h.settle()
        assertFalse(h.session.info.value.live, "the engine seeks in it")
        h.playTo(50.0)
        h.session.close()
        h.settle()
        assertEquals(50_000, h.item(MOVIE.id)!!.state.timeOffset)
        assertEquals(MOVIE.id, h.data.library.observeContinueWatching(h.profileId, 10).first().single().id)
    }

    /** A live stream with no duration yet when it shows: live once its duration grows past the first one it gets. */
    @Test
    fun anUnseekableFileWithoutADurationPlaysLiveOnceItsDurationGrows() = runTest {
        val h = noDurationYet()
        h.engine.report { it.copy(durationSeconds = 6.0) }
        h.settle()
        assertFalse(h.session.info.value.live, "a duration that comes where there was none is the file's length")
        h.engine.report { it.copy(durationSeconds = 8.0) }
        h.settle()
        assertTrue(h.session.info.value.live)
    }

    /** A live stream that never gets a duration: live once its position runs on as time passes. */
    @Test
    fun anUnseekableFileThatNeverGetsADurationPlaysLiveOncePlayingOn() = runTest {
        val h = noDurationYet()
        h.engine.play(1.5)
        h.settle()
        assertFalse(h.session.info.value.live, "a position that jumps on at once is a late report catching up")
        h.playTo(3.0)
        assertTrue(h.session.info.value.live)
    }

    /**
     * mpv hands its client queued events (`file-loaded`, `playback-restart`) before property changes, so the engine's
     * report of a video's first frame can come before the file's duration and `seekable` (an engine's first file: no
     * duration, unseekable), which follow in any order, and a late position report can catch up in one jump. It plays
     * as a video.
     */
    @Test
    fun aVideoWhoseLengthAndSeekabilityComeAfterItsFirstFramePlaysAsAVideo() = runTest {
        val orders: Map<String, List<(EngineState) -> EngineState>> = mapOf(
            "the duration first" to listOf({ it.copy(durationSeconds = 30.0) }, { it.copy(seekable = true) }),
            "seekable first" to listOf({ it.copy(seekable = true) }, { it.copy(durationSeconds = 30.0) }),
            "the position caught up first" to listOf(
                { it.copy(positionSeconds = 1.5) },
                { it.copy(durationSeconds = 30.0) },
                { it.copy(seekable = true) },
            ),
        )
        for ((order, reports) in orders) {
            val h = SessionHarness(this)
            h.links()
            h.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/video"))))
            h.openUnseekable(duration = 0.0)
            for (report in reports) {
                h.engine.report(report)
                h.settle()
            }
            h.playTo(20.0)
            assertFalse(h.session.info.value.live, order)
            h.session.close()
            h.settle()
            assertEquals(20_000, h.item(MOVIE.id)!!.state.timeOffset, "$order: it keeps its resume position")
        }
    }

    /**
     * A live stream opened after a video: the report of its first frame still says the engine can seek (the video's
     * `seekable`, which a load keeps until mpv reports the new file's) and has no duration. Live once the engine says
     * it can't seek in it and its duration grows.
     */
    @Test
    fun aLiveStreamWhoseFirstFrameStillReadsSeekablePlaysLiveOnceItsDurationGrows() = runTest {
        val h = SessionHarness(this)
        h.links()
        val video = choice(urlStream("https://addon.example/video"))
        val live = choice(urlStream("https://addon.example/live"))
        h.start(PlayRequest(MOVIE, MOVIE.id, video, alternatives = listOf(live)))
        h.engine.open(duration = 100.0)
        h.settle()
        h.session.playNextStream()
        h.settle()
        h.engine.report { it.copy(loaded = true, durationSeconds = 0.0, positionSeconds = 0.0, buffering = false, playbackRestarts = 1) }
        h.settle()
        h.engine.report { it.copy(durationSeconds = 6.0) }
        h.engine.report { it.copy(seekable = false) }
        h.settle()
        assertFalse(h.session.info.value.live, "not before its duration grows")
        h.engine.report { it.copy(durationSeconds = 8.0) }
        h.settle()
        assertTrue(h.session.info.value.live)
    }

    /** The live stream shown by the engine, unseekable with no duration: not live yet. */
    private fun TestScope.noDurationYet(): SessionHarness {
        val h = SessionHarness(this)
        h.links()
        h.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/live"))))
        h.openUnseekable(duration = 0.0)
        assertFalse(h.session.info.value.live, "not at its first frame")
        return h
    }

    /**
     * A still image (a PNG or a JPEG): mpv shows it unseekable with no duration, its position held at its first frame's
     * until its display ends (keep-open then pauses at the end). It plays as a video, however long it shows.
     */
    @Test
    fun aStillImagePlaysAsAVideo() = runTest {
        val h = SessionHarness(this)
        h.links()
        h.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/frame"))))
        h.openUnseekable(duration = 0.0)
        h.advance(5.seconds)
        h.engine.report { it.copy(eof = true, paused = true) }
        h.settle()
        h.advance(30.seconds)
        assertFalse(h.session.info.value.live)
    }

    /** An animated GIF: mpv shows it unseekable with a duration that stays put, as a video from a server without Range. */
    @Test
    fun anAnimatedImagePlaysAsAVideo() = runTest {
        val h = SessionHarness(this)
        h.links()
        h.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/animation"))))
        h.openUnseekable(duration = 2.9)
        h.playTo(2.9, step = 0.5)
        h.engine.report { it.copy(eof = true, paused = true) }
        h.settle()
        assertFalse(h.session.info.value.live)
    }

    @Test
    fun aVideoFromAServerWithoutRangeSupportPlaysAsAVideo() = runTest {
        val h = SessionHarness(this)
        h.links()
        h.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/norange"))))
        h.openUnseekable(duration = 100.0)
        h.playTo(50.0)
        assertFalse(h.session.info.value.live, "unseekable, but its duration stays put")
        h.session.close()
        h.settle()
        assertEquals(50_000, h.item(MOVIE.id)!!.state.timeOffset, "it keeps its resume position")
        assertEquals(MOVIE.id, h.data.library.observeContinueWatching(h.profileId, 10).first().single().id)
    }

    /** The same video when the engine's report of its first frame comes before its duration: still a video. */
    @Test
    fun aVideoFromAServerWithoutRangeSupportPlaysAsAVideoWhenItsDurationComesAfterItsFirstFrame() = runTest {
        val h = SessionHarness(this)
        h.links()
        h.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/norange"))))
        h.openUnseekable(duration = 0.0)
        h.engine.report { it.copy(durationSeconds = 100.0) }
        h.settle()
        h.playTo(50.0)
        assertFalse(h.session.info.value.live, "unseekable, and its duration stays put once it comes")
        h.session.close()
        h.settle()
        assertEquals(50_000, h.item(MOVIE.id)!!.state.timeOffset, "it keeps its resume position")
    }
}
