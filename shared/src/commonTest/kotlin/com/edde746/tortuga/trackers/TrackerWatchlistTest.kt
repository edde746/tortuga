package com.edde746.tortuga.trackers

import com.edde746.tortuga.data.tracker.QueuedTrackerContent
import com.edde746.tortuga.data.tracker.TrackerQueueCounts
import com.edde746.tortuga.data.tracker.TrackerWriteFailure
import com.edde746.tortuga.data.tracker.TrackerWriteQueue
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.trackers.TrackerService.ANILIST
import com.edde746.tortuga.trackers.TrackerService.MDBLIST
import com.edde746.tortuga.trackers.TrackerService.MYANIMELIST
import com.edde746.tortuga.trackers.TrackerService.SIMKL
import com.edde746.tortuga.trackers.TrackerService.TRAKT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A title on the trackers' watchlists ([TrackerCoordinator.watchlist], [TrackerCoordinator.setWatchlisted]): one line
 * per tracker whose list takes it, its membership known only from the list pages read and the user's writes, and each
 * write going to its own tracker, queued and retried, and taken back once given up on.
 */
class TrackerWatchlistTest {
    private val fixtures = mutableListOf<TrackerFixture>()

    @AfterTest
    fun close() = fixtures.forEach { it.close() }

    private suspend fun TestScope.fixture(vararg services: TrackerService): TrackerFixture {
        val f = TrackerFixture(this, watchlists = true).also { fixtures += it }
        for (service in services) f.connect(service)
        f.log.clear()
        return f
    }

    /** Every list [flow] showed until now and, as a page left open would see them, later in the test: `service listed state`. */
    private fun TrackerFixture.shown(flow: Flow<List<WatchlistLine>>): List<List<String>> {
        val seen = mutableListOf<List<String>>()
        scope.backgroundScope.launch {
            flow.collect { lines -> seen += lines.map { "${it.service.id} ${it.listed} ${it.state}" } }
        }
        drain()
        return seen
    }

    private fun TrackerFixture.lines(meta: MetaItem = movieMeta()) = shown(coordinator.watchlist(meta.type, meta.id, meta))

    /** Puts [meta] on [service]'s list, or takes it off, and lets the channels drain: what the write came to. */
    private fun TrackerFixture.set(service: TrackerService, listed: Boolean, meta: MetaItem = movieMeta()): WatchlistState? {
        val done = coordinator.setWatchlisted(meta.type, meta.id, meta, service, listed)
        drain()
        assertTrue(done.isCompleted, "the write ended")
        return done.getCompleted()
    }

    private suspend fun TrackerFixture.queuedWatchlist() = data.trackerWriteQueue.pending(profileId).mapNotNull { row ->
        (row.content as? QueuedTrackerContent.Watchlist)?.let { "${row.service.id} ${row.coalesceKey} listed=${it.listed} attempts=${row.attempts}" }
    }

    @Test
    fun eachTrackerWhoseListTakesTheTitleHasALineUnknownUntilItsListIsRead() = runTest {
        val f = fixture(*TrackerService.entries.toTypedArray())
        val lines = f.lines()
        assertEquals(
            listOf("trakt null IDLE", "simkl null IDLE", "mdblist null IDLE"),
            lines.last(),
            "an anime list takes anime only; nothing is asked of a service, so membership is unknown",
        )
        assertEquals(emptyList(), f.log, "no request to know it")

        // The Board reads the lists: Trakt's has the title; Simkl's and MDBList's, read to their end, don't.
        f.watchlist(TRAKT).titles += MetaItemPreview(id = "tt0111161", type = "movie", name = "The Shawshank Redemption")
        f.readLists()
        assertEquals(listOf("trakt true IDLE", "simkl false IDLE", "mdblist false IDLE"), lines.last())

        // An anime film is on the anime lists too.
        f.resolver.animeTt["tt0245429"] = 199
        assertEquals(
            listOf("trakt false IDLE", "simkl false IDLE", "mdblist false IDLE", "mal false IDLE", "anilist false IDLE"),
            f.lines(movieMeta("tt0245429")).last(),
        )
    }

    @Test
    fun aToggleWritesToItsOwnTrackerAndItsLineShowsWhatItWrites() = runTest {
        val f = fixture(TRAKT, SIMKL)
        val lines = f.lines()
        assertEquals(listOf("trakt null IDLE", "simkl null IDLE"), lines.last())

        assertEquals(WatchlistState.IDLE, f.set(TRAKT, listed = true))
        assertTrue(listOf("trakt true SAVING", "simkl null IDLE") in lines, "the line shows the write at once: $lines")
        assertEquals(listOf("trakt true IDLE", "simkl null IDLE"), lines.last())
        assertEquals(listOf("watchlist add tt0111161"), f.calls(TRAKT))
        assertEquals(emptyList(), f.calls(SIMKL), "Simkl's list is its own toggle")

        assertEquals(WatchlistState.IDLE, f.set(TRAKT, listed = false))
        assertEquals(listOf("trakt false IDLE", "simkl null IDLE"), lines.last())
        assertEquals(listOf("watchlist add tt0111161", "watchlist remove tt0111161"), f.calls(TRAKT))

        // A page read before the write doesn't take it back; a page read after it is the service's answer.
        f.watchlist(TRAKT).titles += MetaItemPreview(id = "tt0111161", type = "movie", name = "The Shawshank Redemption")
        f.readLists()
        assertEquals(listOf("trakt true IDLE", "simkl false IDLE"), lines.last())
    }

    @Test
    fun aWriteThatFailsIsQueuedAndTheRetrySendsIt() = runTest {
        val f = fixture(TRAKT)
        f.watchlist(TRAKT).failNext("add", TrackerException.Api(TRAKT, 503))
        val lines = f.lines()

        assertEquals(WatchlistState.QUEUED, f.set(TRAKT, listed = true))
        assertEquals(listOf("trakt true QUEUED"), lines.last(), "the queued write shows what it writes")
        assertEquals(listOf("trakt trakt|watchlist|movie|imdb=tt0111161 listed=true attempts=0"), f.queuedWatchlist())
        assertEquals(listOf("trakt true QUEUED"), f.lines().last(), "a page opened later shows it queued too")

        f.coordinator.replayQueuedWrites()
        f.drain()
        assertEquals(listOf("watchlist add tt0111161", "watchlist add tt0111161"), f.calls(TRAKT))
        assertEquals(listOf("trakt true IDLE"), lines.last())
        assertEquals(emptyList(), f.queuedWatchlist())
    }

    @Test
    fun aQueuedWriteGivenUpOnGoesBackToWhatTheListSaid() = runTest {
        val f = fixture(TRAKT)
        f.readLists()
        val lines = f.lines()
        assertEquals(listOf("trakt false IDLE"), lines.last(), "Trakt's list, read to its end, doesn't have it")

        f.watchlist(TRAKT).failNext("add", *Array(TrackerWriteQueue.MAX_ATTEMPTS + 1) { TrackerException.Api(TRAKT, 422) })
        assertEquals(WatchlistState.QUEUED, f.set(TRAKT, listed = true))
        assertEquals(listOf("trakt true QUEUED"), lines.last())

        repeat(TrackerWriteQueue.MAX_ATTEMPTS - 1) {
            f.coordinator.replayQueuedWrites()
            f.drain()
            assertEquals(listOf("trakt true QUEUED"), lines.last(), "still retried after ${it + 1} refusals")
        }
        f.coordinator.replayQueuedWrites()
        f.drain()
        assertEquals(listOf("trakt false FAILED"), lines.last(), "given up on: the line goes back")
        assertEquals(
            mapOf(TRAKT to TrackerQueueCounts(queued = 0, failed = 1, lastFailure = TrackerWriteFailure.Refused(422))),
            f.data.trackerWriteQueue.observeCounts(f.profileId).first(),
            "kept as failed, as Trackers settings shows failed writes",
        )
    }

    @Test
    fun aWriteThatCantBeQueuedGoesBackAtOnce() = runTest {
        val f = fixture(TRAKT)
        val lines = f.lines()
        f.watchlist(TRAKT).failNext("add", TrackerException.Unsupported(TRAKT, "no such title"))
        assertEquals(WatchlistState.FAILED, f.set(TRAKT, listed = true))
        assertEquals(listOf("trakt null FAILED"), lines.last(), "unknown again, never a check it doesn't have")
        assertEquals(emptyList(), f.queuedWatchlist())
    }

    @Test
    fun withoutATrackerThatKeepsAListThereIsNoLine() = runTest {
        assertEquals(emptyList(), fixture().lines().last(), "no tracker connected")
        assertEquals(emptyList(), fixture(MYANIMELIST, ANILIST).lines().last(), "only anime lists, and the title isn't anime")
        val channel = MetaItem(MetaItemPreview(id = "tt0000001", type = "channel", name = "A channel"))
        assertEquals(emptyList(), fixture(TRAKT, MDBLIST).lines(channel).last(), "no list takes a channel")
    }
}
