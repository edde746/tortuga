package com.edde746.tortuga.playback.session

import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.playback.PlaybackFailure
import com.edde746.tortuga.playback.displayText
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.resolve.redirect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine's end of a file well before its length, where the stream's link died or its connection closed early and
 * the engine played out what it had read: the stream failing, not the episode ending. Past the last tenth of the length
 * it is the episode's end.
 */
class CutShortTest {
    private val file = lcgBytes(300_000)

    /** Each observer event, the end among them, as a line. */
    private class Heard : PlaybackObserver {
        val events = mutableListOf<String>()

        override fun onVideoStarted(meta: MetaItem, videoId: String, at: PlaybackPosition, playing: Boolean) {
            events += "started ${at.positionMillis}"
        }

        override fun onPlayingChanged(playing: Boolean, at: PlaybackPosition) {
            events += "${if (playing) "playing" else "paused"} ${at.positionMillis}"
        }

        override fun onPosition(at: PlaybackPosition) = Unit

        override fun onEnded(at: PlaybackPosition) {
            events += "ended ${at.positionMillis}"
        }

        override fun onVideoStopped(at: PlaybackPosition) {
            events += "stopped ${at.positionMillis}"
        }
    }

    /** `addon.example/<name>` resolves to a fresh link each time it is asked: `cdn.example/<name>-<n>.mkv`. */
    private fun SessionHarness.freshLinks() {
        serveCdn(file)
        var resolves = 0
        routes["addon.example"] = { request ->
            resolves++
            redirect("https://cdn.example/${request.url.segments.last()}-$resolves.mkv")
        }
    }

    /** The first of two episodes, 40 minutes long, played to [to] from its start. */
    private fun SessionHarness.playFirstEpisode(to: Double): Heard {
        freshLinks()
        val heard = Heard().also { session.observe(it) }
        start(PlayRequest(SERIES, "tt1:1:1", choice(urlStream("https://addon.example/e1"))))
        engine.open(duration = LENGTH)
        settle()
        playTo(to, step = 5.0)
        return heard
    }

    /** The engine reaches the end of the file at [at], and pauses there (mpv's keep-open). */
    private fun SessionHarness.endAt(at: Double) {
        engine.report { it.copy(positionSeconds = at, eof = true, paused = true) }
        settle()
    }

    private suspend fun SessionHarness.watched(videoId: String) =
        WatchedBitField.of(item(SERIES.id)?.state?.watchedVideos, SERIES).isWatched(videoId)

    @Test
    fun anEndWellBeforeTheLengthIsNoEpisodeEndAndPlaysOnFromAFreshLink() = runTest {
        val h = SessionHarness(this)
        val heard = h.playFirstEpisode(to = 600.0)
        h.endAt(600.0)

        assertFalse(h.watched("tt1:1:1"), "the episode isn't watched")
        val stopped = h.item(SERIES.id)!!.state
        assertEquals("tt1:1:1" to 600_000L, stopped.videoId to stopped.timeOffset, "the series stays on the episode, its progress where it stopped")
        assertTrue(heard.events.none { it.startsWith("ended") }, "the trackers hear no end: ${heard.events}")
        assertFalse(h.session.engineState.value.eof, "no end for up next, the system's media session or a room to follow")
        assertNull(h.session.info.value.failure, "the session recovers: nothing to show")
        assertEquals(listOf("https://cdn.example/e1-1.mkv", "https://cdn.example/e1-2.mkv"), h.engine.loads.map { it.url }, "a fresh link from the addon")
        assertEquals(600.0, h.engine.loads[1].startSeconds, "where the stream stopped")

        // The fresh link plays to the end: the episode's end.
        h.engine.open(duration = LENGTH)
        h.settle()
        h.playTo(LENGTH, step = 5.0)
        h.endAt(LENGTH)
        assertTrue(h.session.engineState.value.eof)
        assertTrue(h.watched("tt1:1:1"))
        assertEquals(listOf("ended 2400000"), heard.events.filter { it.startsWith("ended") })
    }

    @Test
    fun anEndInTheLastTenthOfTheLengthIsTheEpisodesEnd() = runTest {
        val h = SessionHarness(this)
        val heard = h.playFirstEpisode(to = 2165.0)
        h.endAt(2165.0)
        assertTrue(h.session.engineState.value.eof, "90.2 % of the length: the end")
        assertEquals(1, h.engine.loads.size)
        assertTrue(h.watched("tt1:1:1"))
        assertEquals(listOf("ended 2165000"), heard.events.filter { it.startsWith("ended") })
    }

    @Test
    fun anEndJustShortOfTheLastTenthIsTheStreamCutShort() = runTest {
        val h = SessionHarness(this)
        val heard = h.playFirstEpisode(to = 2155.0)
        h.endAt(2155.0)
        assertFalse(h.session.engineState.value.eof, "89.8 % of the length: the stream cut short")
        assertEquals(listOf(1, 2155.0), listOf(h.engine.loads.size - 1, h.engine.loads.last().startSeconds), "one fresh link, where it stopped")
        assertTrue(heard.events.none { it.startsWith("ended") }, "the trackers hear no end: ${heard.events}")
    }

    @Test
    fun aFreshLinkCutShortTooShowsTheErrorAndRetryPlaysOnFromThere() = runTest {
        val h = SessionHarness(this)
        h.playFirstEpisode(to = 600.0)
        h.endAt(600.0)
        h.engine.open(duration = LENGTH)
        h.settle()
        h.playTo(900.0, step = 5.0)
        h.endAt(900.0)

        val failure = assertIs<PlaybackFailure.Engine>(h.session.info.value.failure, "one fresh link only")
        assertEquals("The stream stopped before the end of the video.", failure.displayText)
        assertFalse(h.session.engineState.value.eof)
        assertFalse(h.watched("tt1:1:1"))
        assertEquals(900_000, h.item(SERIES.id)!!.state.timeOffset)

        h.session.retry()
        h.settle()
        assertEquals(listOf("https://cdn.example/e1-3.mkv", 900.0), h.engine.loads.last().let { listOf(it.url, it.startSeconds) })
        assertNull(h.session.info.value.failure)
    }

    /**
     * A length that runs past the file's last frame: the fresh link's reload at the point where the end came ends
     * there again at once, and that is the episode's end.
     */
    @Test
    fun aFileWhoseLengthRunsPastItsEndStillEnds() = runTest {
        val h = SessionHarness(this)
        val heard = h.playFirstEpisode(to = 1500.0)
        h.endAt(1500.0)
        assertEquals(2, h.engine.loads.size)
        h.engine.open(duration = LENGTH)
        h.settle()
        h.endAt(1500.0)

        assertTrue(h.session.engineState.value.eof)
        assertNull(h.session.info.value.failure)
        assertTrue(h.watched("tt1:1:1"))
        assertEquals(listOf("ended 1500000"), heard.events.filter { it.startsWith("ended") })
    }

    private companion object {
        const val LENGTH = 2400.0
    }
}
