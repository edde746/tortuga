package com.edde746.tortuga.trackers

import com.edde746.tortuga.data.tracker.TrackerWriteQueue
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.trackers.TrackerService.ANILIST
import com.edde746.tortuga.trackers.TrackerService.SIMKL
import com.edde746.tortuga.trackers.TrackerService.TRAKT
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Failed watched/unwatched writes: queued, coalesced per remote row, replayed. */
class TrackerRetryQueueTest {
    private val fixtures = mutableListOf<TrackerFixture>()

    @AfterTest
    fun close() = fixtures.forEach { it.close() }

    private fun TestScope.fixture() = TrackerFixture(this).also { fixtures += it }

    private val anime = MetaItem(
        MetaItemPreview(id = "kitsu:100", type = "series", name = "An anime"),
        videos = (1..12).map { Video(id = "kitsu:100:$it", seriesInfo = SeriesInfo(1, it)) },
    )

    private fun TrackerFixture.mark(videoId: String, watched: Boolean, meta: MetaItem = seriesMeta("tt0903747", 3)) {
        coordinator.marks.onMarked(WatchedMark.Video(meta, videoId, watched))
        drain()
    }

    private fun TrackerFixture.replay() {
        coordinator.replayQueuedWrites()
        advance(30.seconds)
    }

    /** Whether a write for [key] is stored, waiting or failed. */
    private fun TrackerFixture.stored(key: String) =
        data.db.database.trackerQueries.selectQueuedByKey(profileId, key.substringBefore('|'), key).executeAsOneOrNull() != null

    @Test
    fun theNewestIntentForARowIsTheOneReplayed() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        val trakt = f.tracker(TRAKT)
        trakt.failNext("watched", TrackerException.Api(TRAKT, 503))
        f.mark("tt0903747:1:1", watched = true)
        f.advance(60.seconds)
        trakt.failNext("unwatched", TrackerException.Api(TRAKT, 503))
        f.mark("tt0903747:1:1", watched = false)
        assertEquals(listOf("trakt trakt|s1e1|imdb=tt0903747 watched=false attempts=0"), f.queued(), "one row per remote row")

        f.replay()
        assertEquals(listOf("watched tt0903747:1:1", "unwatched tt0903747:1:1", "unwatched tt0903747:1:1"), f.undated(TRAKT))
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun aReplayedWatchKeepsItsDate() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.tracker(TRAKT).failNext("watched", TrackerException.Network(TRAKT, IllegalStateException("offline")))
        f.mark("tt0903747:1:2", watched = true)
        val watchedAt = f.data.trackerWriteQueue.pending(f.profileId).single().watchedAt
        f.advance(3600.seconds)
        f.replay()
        assertEquals("watched tt0903747:1:2 at=$watchedAt", f.calls(TRAKT).last())
    }

    @Test
    fun transientFailuresKeepTheirAttemptsOthersCountUntilTheFifthGivesThemUp() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.connect(SIMKL)
        for (service in listOf(TRAKT, SIMKL)) f.tracker(service).failNext("watched", TrackerException.Api(service, 500), TrackerException.Api(service, 500))
        f.mark("tt0903747:1:1", watched = true)
        f.mark("tt0903747:1:2", watched = true)
        assertEquals(4, f.queued().size)
        f.log.clear()

        // Trakt fails on its side: its rows keep their attempts. Simkl's go through, both in one request.
        f.tracker(TRAKT).failNext("watched", TrackerException.Api(TRAKT, 503))
        f.replay()
        assertEquals(listOf("watched tt0903747:1:1, tt0903747:1:2"), f.undated(TRAKT))
        assertEquals(listOf("watched tt0903747:1:1, tt0903747:1:2"), f.undated(SIMKL))
        assertEquals(
            listOf(
                "trakt trakt|s1e1|imdb=tt0903747 watched=true attempts=0",
                "trakt trakt|s1e2|imdb=tt0903747 watched=true attempts=0",
            ),
            f.queued(),
        )

        // An answer about the write (404) counts an attempt on every row it carried; after the fifth they're given up on.
        repeat(TrackerWriteQueue.MAX_ATTEMPTS) { attempt ->
            f.tracker(TRAKT).failNext("watched", TrackerException.Api(TRAKT, 404))
            f.replay()
            if (attempt < TrackerWriteQueue.MAX_ATTEMPTS - 1) {
                assertEquals(
                    listOf(
                        "trakt trakt|s1e1|imdb=tt0903747 watched=true attempts=${attempt + 1}",
                        "trakt trakt|s1e2|imdb=tt0903747 watched=true attempts=${attempt + 1}",
                    ),
                    f.queued(),
                )
            }
        }
        assertEquals(emptyList(), f.queued(), "given up on after 5 attempts")
        // Kept, failed, for Trackers settings to show, and never sent again.
        assertTrue(f.stored("trakt|s1e1|imdb=tt0903747") && f.stored("trakt|s1e2|imdb=tt0903747"), "kept")
        f.log.clear()
        f.replay()
        assertEquals(emptyList(), f.calls(TRAKT))
    }

    @Test
    fun aQueuedWriteThatNoLongerReadsIsKeptAsFailedNotSent() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        // A write an earlier build queued in a shape this one can't read (a kind it no longer has).
        val key = "trakt|movie|imdb=tt0111161"
        f.data.db.database.trackerQueries.insertQueued(
            f.profileId, TRAKT.id, key, true, """{"metaId":"tt0111161","videoId":"tt0111161","kind":"FILM","contentType":"MOVIE"}""", null, 1, null, false,
        )
        f.replay()
        assertEquals(emptyList(), f.calls(TRAKT))
        assertEquals(emptyList(), f.queued())
        assertTrue(f.stored(key), "kept, failed, not deleted")
    }

    @Test
    fun aSecretStoreThatCanNotReadTheTokenKeepsTheWriteQueuedWithItsAttempts() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.store.failReads = true
        f.mark("tt0903747:1:1", watched = true)
        val queued = listOf("trakt trakt|s1e1|imdb=tt0903747 watched=true attempts=0")
        assertEquals(queued, f.queued())

        // Not an answer about the write: however often it's replayed, it keeps its attempts.
        repeat(TrackerWriteQueue.MAX_ATTEMPTS) { f.replay() }
        assertEquals(queued, f.queued())
        assertEquals(emptyList(), f.calls(TRAKT))

        f.store.failReads = false
        f.replay()
        assertEquals(listOf("watched tt0903747:1:1"), f.undated(TRAKT))
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun aFailedSeasonMarkIsReplayedAsOneRequest() = runTest {
        val f = fixture()
        f.connect(SIMKL)
        f.tracker(SIMKL).failNext("watched", TrackerException.Network(SIMKL, IllegalStateException("offline")))
        f.coordinator.marks.onMarked(WatchedMark.Season(seriesMeta("tt0903747", 3), 1, watched = true))
        f.drain()
        assertEquals(3, f.queued().size, "a row per episode")
        f.replay()
        assertEquals(
            listOf("watched tt0903747:1:1, tt0903747:1:2, tt0903747:1:3", "watched tt0903747:1:1, tt0903747:1:2, tt0903747:1:3"),
            f.undated(SIMKL),
        )
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun aServiceThatSaysWhenToComeBackGetsNoRequestUntilThen() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.tracker(TRAKT).failNext("watched", TrackerException.RateLimited(TRAKT, 60))
        f.mark("tt0903747:1:1", watched = true)
        // During the wait: a mark is queued unsent, a playback isn't reported, and a replay leaves Trakt alone.
        f.mark("tt0903747:1:2", watched = true)
        f.observer.onVideoStarted(movieMeta(), "tt0111161", at(0), playing = true)
        f.drain()
        f.observer.onVideoStopped(at(1))
        f.replay()
        assertEquals(listOf("watched tt0903747:1:1"), f.calls(TRAKT))
        assertEquals(2, f.queued().size)

        // Past the minute.
        f.advance(1.seconds)
        f.replay()
        assertEquals(listOf("watched tt0903747:1:1", "watched tt0903747:1:1, tt0903747:1:2"), f.undated(TRAKT))
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun aRateLimitThatDoesNotSayWhenToComeBackHoldsTheServiceTenSeconds() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.tracker(TRAKT).failNext("watched", TrackerException.RateLimited(TRAKT))
        val show = seriesMeta("tt0903747", 3)
        f.coordinator.marks.onMarked(WatchedMark.Video(show, "tt0903747:1:1", watched = true))
        f.advance(4.seconds)
        f.coordinator.marks.onMarked(WatchedMark.Video(show, "tt0903747:1:2", watched = true))
        f.advance(4.seconds)
        assertEquals(listOf("watched tt0903747:1:1"), f.calls(TRAKT), "the next mark is queued unsent")
        assertEquals(2, f.queued().size)

        // Past the 10 s.
        f.advance(3.seconds)
        f.replay()
        assertEquals(listOf("watched tt0903747:1:1", "watched tt0903747:1:1, tt0903747:1:2"), f.undated(TRAKT))
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun replaysRunWhenTheProfileBindsAnAccountConnectsAndOnResumeAndNetworkRestore() = runTest {
        val f = fixture()
        f.connect(SIMKL)
        repeat(5) { f.tracker(SIMKL).failNext("watched", TrackerException.Network(SIMKL, IllegalStateException("offline"))) }
        f.mark("tt0903747:1:1", watched = true)

        // Another account connecting replays the profile's queue.
        f.connect(TRAKT)
        f.drain()
        assertEquals(2, f.calls(SIMKL).size)
        f.mark("tt0903747:1:1", watched = true)
        f.log.clear()

        f.hub.onForeground()
        f.drain()
        assertEquals(listOf("watched tt0903747:1:1"), f.undated(SIMKL))
        f.hub.onNetworkRestored()
        f.drain()
        assertEquals(listOf("watched tt0903747:1:1", "watched tt0903747:1:1"), f.undated(SIMKL))
        assertEquals(1, f.queued().size)

        // The next launch: a new hub over the same database replays as soon as the profile's coordinator starts.
        val next = f.newHub()
        next.coordinator(f.profileId)
        f.drain()
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun aNetworkRestoredWhileAReplayWaitsOnTheDeadOneReplaysAgain() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        val trakt = f.tracker(TRAKT)
        trakt.failNext("watched", TrackerException.Network(TRAKT, IllegalStateException("offline")))
        f.mark("tt0903747:1:1", watched = true)
        f.log.clear()

        // Back in the foreground before the network: the replay's request waits, then fails on the dead network.
        trakt.latency = 20.seconds
        trakt.failNext("watched", TrackerException.Network(TRAKT, IllegalStateException("offline")))
        f.hub.onForeground()
        f.advance(5.seconds)
        trakt.latency = 0.seconds
        f.hub.onNetworkRestored()
        f.advance(30.seconds)
        assertEquals(listOf("watched tt0903747:1:1", "watched tt0903747:1:1"), f.undated(TRAKT))
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun aWriteThatLandsDropsTheQueuedClaimsItCovers() = runTest {
        val f = fixture()
        f.connect(ANILIST)
        val anilist = f.tracker(ANILIST)
        anilist.failNext("watched", TrackerException.Api(ANILIST, 500))
        f.mark("kitsu:100:5", watched = true, meta = anime)
        assertEquals(listOf("anilist anilist|series|100 watched=true attempts=0"), f.queued())
        assertEquals(5L, f.data.trackerWriteQueue.pending(f.profileId).single().progressClaim)
        // Episode 6 lands: it covers the queued claim to 5.
        f.mark("kitsu:100:6", watched = true, meta = anime)
        assertEquals(emptyList(), f.queued())

        anilist.failNext("watched", TrackerException.Api(ANILIST, 500))
        f.mark("kitsu:100:9", watched = true, meta = anime)
        // Episode 8 lands after 9 failed: the higher claim still waits, so the list can't walk backwards.
        f.mark("kitsu:100:8", watched = true, meta = anime)
        assertEquals(9L, f.data.trackerWriteQueue.pending(f.profileId).single().progressClaim)
        f.replay()
        assertEquals("watched 100@9", f.calls(ANILIST).last())
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun rowsWaitForADisabledServiceAndGoWhenTheirContentIsFilteredOut() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        f.connect(SIMKL)
        f.tracker(TRAKT).failNext("watched", TrackerException.Api(TRAKT, 500))
        f.tracker(SIMKL).failNext("watched", TrackerException.Api(SIMKL, 500))
        f.mark("tt0903747:1:1", watched = true)
        f.hub.accounts.setEnabled(f.profileId, TRAKT, false)
        f.hub.accounts.setContentFilter(f.profileId, SIMKL, TrackerContentType.SERIES, on = false)
        f.settle()
        f.log.clear()
        f.replay()
        assertEquals(emptyList(), f.log)
        assertEquals(listOf("trakt trakt|s1e1|imdb=tt0903747 watched=true attempts=0"), f.queued())
    }

    @Test
    fun aWriteFailingAfterItsAccountSignedOutIsNotQueued() = runTest {
        val f = fixture()
        f.connect(TRAKT)
        val trakt = f.tracker(TRAKT)
        trakt.latency = 5.seconds
        trakt.failNext("watched", TrackerException.Api(TRAKT, 500))
        f.coordinator.marks.onMarked(WatchedMark.Video(seriesMeta("tt0903747", 3), "tt0903747:1:1", watched = true))
        f.advance(1.seconds)
        f.hub.accounts.disconnect(f.profileId, TRAKT)
        f.connect(TRAKT)
        f.drain()
        assertEquals(listOf("watched tt0903747:1:1"), f.calls(TRAKT))
        assertEquals(emptyList(), f.queued(), "the failure belongs to the old sign-in")
    }
}
