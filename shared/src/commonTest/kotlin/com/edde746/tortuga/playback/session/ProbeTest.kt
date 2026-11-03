package com.edde746.tortuga.playback.session

import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.resolve.redirect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A probe ([PlayPurpose.PROBE]: a watch-together guest's candidate, opened paused to read its duration) records
 * nothing: no library item or progress (so nothing in Continue Watching), no stream history, no tracker events; a kept
 * one ([PlaybackSession.keepProbe]) is recorded from then on as any played file is.
 */
class ProbeTest {
    private val file = lcgBytes(300_000)

    private fun SessionHarness.links() {
        serveCdn(file)
        routes["addon.example"] = { request -> redirect("https://cdn.example/${request.url.segments.last()}.mkv") }
    }

    private fun probe(name: String) = PlayRequest(
        MOVIE, MOVIE.id, choice(urlStream("https://addon.example/$name")), startPositionSeconds = 600.0, startPaused = true, purpose = PlayPurpose.PROBE,
    )

    private fun SessionHarness.observer() = RecordingObserver().also { session.observe(it) }

    @Test
    fun probesRecordNothingWhileOpenPlayedSeekedOrLeft() = runTest {
        val h = SessionHarness(this)
        h.links()
        val observer = h.observer()
        h.start(probe("a"))
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(listOf(true), h.engine.pauses, "a probe opens paused")
        assertEquals(600.0, h.engine.loads.single().startSeconds)
        // Whatever happens to it while open: played, paused, seeked, past the watched threshold.
        h.engine.setPaused(false)
        h.playTo(620.0)
        h.engine.setPaused(true)
        h.session.onSeek(2900.0)
        h.engine.seek(2900.0, exact = true)
        h.advance(60.seconds)
        // Another probe takes its place, then the session closes.
        h.start(probe("b"))
        h.engine.open(duration = 3010.0)
        h.settle()
        h.session.close()
        h.settle()

        assertNull(h.item(MOVIE.id), "no library item: nothing in Continue Watching")
        assertNull(h.data.streamHistory.lastStream(h.profileId, MOVIE.id, MOVIE.id), "no stream history")
        assertEquals(emptyList(), observer.events, "no tracker events")
        assertEquals(0, h.sync.flushes)
        assertTrue(h.events.isEmpty(), "no session events: ${h.events}")
    }

    @Test
    fun aKeptProbeIsRecordedFromThenOn() = runTest {
        val h = SessionHarness(this)
        h.links()
        val observer = h.observer()
        h.start(probe("a"))
        h.engine.open(duration = 3000.0)
        h.settle()
        h.start(probe("b"))
        h.engine.open(duration = 3001.0)
        h.settle()
        assertNull(h.item(MOVIE.id))

        h.session.keepProbe()
        h.settle()
        val item = assertNotNull(h.item(MOVIE.id), "the kept file's title is in the library")
        assertEquals(600_000, item.state.timeOffset, "its position saved")
        val entry = assertNotNull(h.data.streamHistory.lastStream(h.profileId, MOVIE.id, MOVIE.id))
        assertEquals(urlStream("https://addon.example/b"), entry.stream, "the kept stream is the one remembered")
        assertEquals(listOf("started 600000/3001000"), observer.events, "the trackers hear the kept file start, once")

        // From now on it is watched: progress is saved as it plays, and leaving stops it for the trackers.
        h.engine.setPaused(false)
        h.playTo(640.0)
        h.engine.setPaused(true)
        h.settle()
        assertEquals(640_000, h.item(MOVIE.id)!!.state.timeOffset)
        h.session.close()
        h.settle()
        assertEquals("stopped 640000/3001000", observer.events.last())
    }

    @Test
    fun aProbeKeptBeforeItOpensIsRecordedOnceOpen() = runTest {
        val h = SessionHarness(this)
        h.links()
        val observer = h.observer()
        h.start(probe("a"))
        h.session.keepProbe()
        h.settle()
        assertNull(h.data.streamHistory.lastStream(h.profileId, MOVIE.id, MOVIE.id), "not before it opens")
        assertEquals(emptyList(), observer.events)
        h.engine.open(duration = 3000.0)
        h.settle()
        assertNotNull(h.data.streamHistory.lastStream(h.profileId, MOVIE.id, MOVIE.id))
        assertEquals(listOf("started 600000/3000000"), observer.events)
        assertNotNull(h.item(MOVIE.id))
    }

    @Test
    fun keepingAPlainRequestChangesNothing() = runTest {
        val h = SessionHarness(this)
        h.links()
        val observer = h.observer()
        h.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/a"))))
        h.engine.open(duration = 3000.0)
        h.settle()
        val before = observer.events.toList()
        assertEquals("started 0/3000000", before.first())
        h.session.keepProbe()
        h.settle()
        assertEquals(before, observer.events, "no second start")
    }
}
