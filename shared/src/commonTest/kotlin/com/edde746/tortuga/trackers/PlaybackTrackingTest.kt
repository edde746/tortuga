package com.edde746.tortuga.trackers

import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.resolve.redirect
import com.edde746.tortuga.playback.session.MOVIE
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.SessionHarness
import com.edde746.tortuga.playback.session.choice
import com.edde746.tortuga.playback.session.urlStream
import com.edde746.tortuga.trackers.TrackerService.SIMKL
import com.edde746.tortuga.trackers.TrackerService.TRAKT
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A real playback session feeding the coordinator: what the player does is what the trackers hear. */
class PlaybackTrackingTest {
    private fun SessionHarness.hostedLinks() {
        serveCdn(lcgBytes(300_000))
        routes["addon.example"] = { request -> redirect("https://cdn.example/${request.url.segments.last()}.mkv") }
    }

    @Test
    fun theSessionsLifecycleReachesTheTrackersAndTheirFailuresNeverReachPlayback() = runTest {
        val h = SessionHarness(this)
        h.hostedLinks()
        val f = TrackerFixture(this)
        try {
            f.connect(TRAKT)
            f.connect(SIMKL)
            f.coordinator.follow(h.session)
            // Simkl fails everything, with an error no tracker should throw.
            repeat(10) { f.tracker(SIMKL).failNext("scrobble", IllegalStateException("broken tracker")) }

            h.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/movie"))))
            h.engine.open(duration = 1000.0)
            h.settle()
            h.playTo(10.0)
            // The player seeks (the store tells the session, then the engine), and the engine gets there.
            h.session.onSeek(500.0)
            h.engine.seek(500.0, exact = false)
            h.advance(1.seconds)
            h.playTo(505.0)
            h.engine.setPaused(true)
            h.advance(2.seconds)
            h.session.close()
            h.advance(30.seconds)

            assertEquals(listOf("scrobble START 0", "scrobble SEEK 50", "scrobble PAUSE 50", "scrobble STOP 50"), f.calls(TRAKT))
            // Simkl's start failed, so it has no session to pause or stop.
            assertEquals(listOf("scrobble START 0"), f.calls(SIMKL))
            // Playback went on as if no tracker existed: progress saved, engine released.
            assertEquals(505_000L, h.item(MOVIE.id)?.state?.timeOffset)
            assertTrue(h.engine.released)
        } finally {
            f.close()
        }
    }

    @Test
    fun aNewerPlaybackStopsTheOpenOneAndTheOlderOnesEventsNoLongerCount() = runTest {
        val first = SessionHarness(this)
        val second = SessionHarness(this)
        first.hostedLinks()
        second.hostedLinks()
        val f = TrackerFixture(this)
        try {
            f.connect(TRAKT)
            f.coordinator.follow(first.session)
            f.coordinator.follow(second.session)

            first.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/movie"))))
            first.engine.open(duration = 1000.0)
            first.settle()
            first.playTo(10.0)

            // Another player starts: the first playback stops for the trackers where it was.
            second.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/movie"))))
            second.engine.open(duration = 1000.0)
            second.settle()
            second.playTo(100.0)

            // The first player goes on, seeks and closes: none of it moves the second playback's scrobble.
            first.session.onSeek(500.0)
            first.engine.seek(500.0, exact = false)
            first.advance(1.seconds)
            first.playTo(505.0)
            first.session.close()
            first.advance(2.seconds)
            second.session.close()
            second.advance(30.seconds)

            assertEquals(listOf("scrobble START 0", "scrobble STOP 1", "scrobble START 0", "scrobble STOP 10"), f.calls(TRAKT))
        } finally {
            f.close()
        }
    }
}
