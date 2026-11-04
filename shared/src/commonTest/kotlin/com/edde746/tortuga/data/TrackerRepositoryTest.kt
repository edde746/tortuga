package com.edde746.tortuga.data

import com.edde746.tortuga.data.tracker.QueuedTrackerContent
import com.edde746.tortuga.data.tracker.TrackerQueueCounts
import com.edde746.tortuga.data.tracker.TrackerWriteFailure
import com.edde746.tortuga.data.tracker.TrackerWriteQueue
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.movieMark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerRepositoryTest {
    private val clock = TestClock()
    private val data = testData(clock)
    private val queue = data.trackerWriteQueue
    private val p = "p1"
    private val trakt = TrackerService.TRAKT
    private val anilist = TrackerService.ANILIST

    @AfterTest
    fun close() = data.close()

    private suspend fun keys() = queue.pending(p).map { "${it.service.id}/${it.coalesceKey}/${(it.content as QueuedTrackerContent.Mark).watched}" }

    private suspend fun counts() = queue.observeCounts(p).first()

    /** The account server's sign-in times, one later than the other. */
    private var signIns = 0L

    /** Connects [service] for [profile] and returns the binding its queued writes carry. */
    private suspend fun connected(service: TrackerService, profile: String = p): Long =
        data.trackerAccounts.connect(profile, service, "alex", emptyList(), ++signIns).connectedAt

    private suspend fun enqueue(
        service: TrackerService,
        key: String,
        watched: Boolean,
        claim: Long? = null,
        at: Long = 1,
        profile: String = p,
    ): Boolean {
        val binding = data.trackerAccounts.account(profile, service)?.connectedAt ?: connected(service, profile)
        return queue.enqueue(profile, service, binding, key, movieMark(watched = watched), claim, at)
    }

    /** Queues a write for [key] and has [service] refuse it, answering [status], at every attempt. */
    private suspend fun failed(service: TrackerService, key: String, claim: Long? = null, status: Int? = 422) {
        enqueue(service, key, true, claim)
        val id = queue.pending(p).single { it.coalesceKey == key }.id
        repeat(TrackerWriteQueue.MAX_ATTEMPTS) { queue.recordFailure(id, status) }
    }

    @Test
    fun reconnectUpdatesTheSignInAndDisconnectDropsQueuedWrites() = runTest {
        data.trackerAccounts.connect(p, trakt, "alex", listOf("public"), 1)
        data.trackerAccounts.connect(p, trakt, "alex2", listOf("public", "scrobble"), 2)
        val account = data.trackerAccounts.account(p, trakt)!!
        assertEquals("alex2", account.username)
        assertEquals(listOf("public", "scrobble"), account.scopes)

        enqueue(trakt, "movie:tt1", true)
        failed(trakt, "movie:tt2")
        enqueue(anilist, "anime:1", true, claim = 3)
        assertTrue(data.trackerAccounts.disconnect(p, trakt))
        assertEquals(listOf(anilist), data.trackerAccounts.accounts(p).map { it.service })
        assertEquals(listOf("anilist/anime:1/true"), keys())
        assertEquals(setOf(anilist), counts().keys, "its failed writes went too")
    }

    @Test
    fun aQueuedWriteBelongsToTheSignInItWasMadeUnder() = runTest {
        val first = connected(trakt)
        assertTrue(queue.enqueue(p, trakt, first, "movie:tt1", movieMark(), null, 1))
        // Signing in again (maybe as someone else) is a new binding: the old sign-in's writes go.
        val second = connected(trakt)
        assertNotEquals(first, second)
        assertEquals(emptyList(), keys())
        // A write that fails after the reconnect can't be queued under the old sign-in either.
        assertFalse(queue.enqueue(p, trakt, first, "movie:tt1", movieMark(), null, 2))
        assertTrue(queue.enqueue(p, trakt, second, "movie:tt1", movieMark(), null, 2))
        // A dead session found under the old sign-in doesn't sign out the new one.
        assertFalse(data.trackerAccounts.disconnect(p, trakt, connectedAt = first))
        assertEquals(1, queue.pending(p).size)
        assertTrue(data.trackerAccounts.disconnect(p, trakt, connectedAt = second))
        assertFalse(queue.enqueue(p, trakt, second, "movie:tt1", movieMark(), null, 3))
        assertEquals(emptyList(), keys())
    }

    @Test
    fun aNewerIntentReplacesTheQueuedOneAndMovesToTheBack() = runTest {
        enqueue(trakt, "movie:tt1", true, at = 1)
        enqueue(trakt, "movie:tt2", true, at = 2)
        enqueue(trakt, "movie:tt1", false, at = 3)
        assertEquals(listOf("trakt/movie:tt2/true", "trakt/movie:tt1/false"), keys())
        // The same key on another service or profile is another remote row.
        enqueue(TrackerService.SIMKL, "movie:tt1", true, at = 4)
        enqueue(trakt, "movie:tt1", true, at = 4, profile = "p2")
        assertEquals(mapOf(trakt to TrackerQueueCounts(2, 0, null), TrackerService.SIMKL to TrackerQueueCounts(1, 0, null)), counts())
        assertEquals(1, queue.pending("p2").size)
    }

    @Test
    fun progressClaimsNeverMoveBackwards() = runTest {
        assertTrue(enqueue(anilist, "anime:21", true, claim = 6, at = 1))
        assertFalse(enqueue(anilist, "anime:21", true, claim = 5, at = 2))
        assertEquals(6, queue.pending(p).single().progressClaim)
        assertTrue(enqueue(anilist, "anime:21", true, claim = 7, at = 3))
        assertEquals(7, queue.pending(p).single().progressClaim)
        // A removal is not a claim and replaces any claim.
        assertTrue(enqueue(anilist, "anime:21", false, at = 4))
        assertEquals(null, queue.pending(p).single().progressClaim)
    }

    @Test
    fun aWriteRefusedFiveTimesIsKeptAsFailedWithItsStatus() = runTest {
        enqueue(trakt, "movie:tt1", true)
        val id = queue.pending(p).single().id
        repeat(TrackerWriteQueue.MAX_ATTEMPTS - 1) { assertFalse(queue.recordFailure(id, 422)) }
        assertEquals(4, queue.pending(p).single().attempts)
        assertTrue(queue.recordFailure(id, 422))
        assertEquals(emptyList(), queue.pending(p), "never replayed again")
        assertFalse(queue.isQueued(id))
        assertEquals(mapOf(trakt to TrackerQueueCounts(0, 1, TrackerWriteFailure.Refused(422))), counts())
    }

    @Test
    fun aRowThatNoLongerReadsIsKeptAsFailedUntilANewerWriteForItsKeyReplacesIt() = runTest {
        enqueue(trakt, "movie:tt1", true)
        // A row written in a shape this build can't read (a kind it no longer has).
        data.db.database.trackerQueries.insertQueued(
            p, trakt.id, "movie:tt2", true, """{"metaId":"tt2","videoId":"tt2","kind":"FILM","contentType":"MOVIE"}""", null, 2, null, false,
        )
        assertEquals(listOf("trakt/movie:tt1/true"), keys(), "it isn't replayed")
        assertEquals(mapOf(trakt to TrackerQueueCounts(1, 1, TrackerWriteFailure.Unreadable)), counts())

        assertTrue(enqueue(trakt, "movie:tt2", false))
        assertEquals(listOf("trakt/movie:tt1/true", "trakt/movie:tt2/false"), keys())
        assertEquals(mapOf(trakt to TrackerQueueCounts(2, 0, null)), counts())
    }

    @Test
    fun aFailedWriteGoesWhenAWriteForItsKeyReplacesOrCoversIt() = runTest {
        failed(trakt, "movie:tt1")
        assertTrue(enqueue(trakt, "movie:tt1", false))
        assertEquals(listOf("trakt/movie:tt1/false"), keys())
        assertEquals(TrackerQueueCounts(1, 0, null), counts()[trakt])

        // A write that went through covers a failed claim as it covers a queued one: up to its own progress.
        failed(anilist, "anime:1", claim = 8)
        queue.invalidate(p, anilist, "anime:1", appliedProgress = 7)
        assertEquals(TrackerQueueCounts(0, 1, TrackerWriteFailure.Refused(422)), counts()[anilist])
        queue.invalidate(p, anilist, "anime:1", appliedProgress = 8)
        assertNull(counts()[anilist])

        // A failed claim never reached the service: it holds no lower claim back.
        failed(anilist, "anime:1", claim = 8)
        assertTrue(enqueue(anilist, "anime:1", true, claim = 5))
        assertEquals(TrackerQueueCounts(1, 0, null), counts()[anilist])
    }

    @Test
    fun theLastFailureIsTheOneGivenUpOnLatest() = runTest {
        enqueue(trakt, "movie:tt1", true)
        failed(trakt, "movie:tt2", status = 404)
        clock.now += 1000
        // The older write is given up on later, without an HTTP answer.
        val older = queue.pending(p).single { it.coalesceKey == "movie:tt1" }.id
        repeat(TrackerWriteQueue.MAX_ATTEMPTS) { queue.recordFailure(older, null) }
        assertEquals(mapOf(trakt to TrackerQueueCounts(0, 2, TrackerWriteFailure.Refused(null))), counts())
    }

    @Test
    fun completingAStaleIdKeepsTheNewerIntent() = runTest {
        enqueue(trakt, "movie:tt1", true, at = 1)
        val stale = queue.pending(p).single().id
        enqueue(trakt, "movie:tt1", false, at = 2)
        assertFalse(queue.isQueued(stale))
        queue.complete(stale)
        assertEquals(listOf("trakt/movie:tt1/false"), keys())
        assertFalse(queue.recordFailure(stale, 422))
        assertEquals(0, queue.pending(p).single().attempts)
    }

    @Test
    fun invalidateDropsOnlyCoveredWrites() = runTest {
        enqueue(anilist, "anime:1", true, claim = 8)
        queue.invalidate(p, anilist, "anime:1", appliedProgress = 7)
        assertEquals(1, queue.pending(p).size)
        queue.invalidate(p, anilist, "anime:1", appliedProgress = 8)
        assertEquals(emptyList(), queue.pending(p))

        enqueue(trakt, "movie:tt1", true)
        queue.invalidate(p, trakt, "movie:tt1", appliedProgress = null)
        assertEquals(emptyList(), queue.pending(p))
    }
}
