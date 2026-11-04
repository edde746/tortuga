package com.edde746.tortuga.data

import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.sync.PushAck
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRepositoryTest {
    private val clock = TestClock()
    private val data = testData(clock)
    private val p = "p1"

    @AfterTest
    fun close() = data.close()

    private suspend fun cw() = data.library.observeContinueWatching(p, 100).first().map { it.id }

    @Test
    fun addRemoveAndDelete() = runTest {
        assertTrue(data.library.addToLibrary(p, movie()), "the title enters the library")
        assertFalse(data.library.addToLibrary(p, movie()), "a second add finds it in the library")
        val added = assertNotNull(data.library.item(p, "tt0111161"))
        assertFalse(added.removed)
        assertFalse(added.temp)
        assertEquals(clock.now, added.ctime)
        assertEquals(listOf("tt0111161"), data.library.observeLibrary(p).first().map { it.id })

        data.library.recordProgress(p, "tt0111161", "tt0111161", 60_000, 8_520_000)
        data.library.removeFromLibrary(p, "tt0111161")
        val removed = assertNotNull(data.library.item(p, "tt0111161"))
        assertTrue(removed.removed)
        assertEquals(60_000, removed.state.timeOffset) // watch state stays
        assertEquals(emptyList(), data.library.observeLibrary(p).first())
        assertEquals(emptyList(), cw())

        data.library.delete(p, "tt0111161")
        assertNull(data.library.item(p, "tt0111161"))
        assertTrue(data.sync.dirtyRecords(null, 10).single().deleted)
    }

    @Test
    fun playbackCreatesATemporaryItemThatShowsInContinueWatchingOnly() = runTest {
        val item = data.library.upsertFromMeta(p, series())
        assertTrue(item.removed && item.temp)
        assertEquals(clock.now, item.state.lastWatched)
        assertEquals(emptyList(), cw())

        data.library.recordProgress(p, item.id, "tt0903747:1:1", 30_000, 2_900_000)
        assertEquals(listOf(item.id), cw())
        assertEquals(emptyList(), data.library.observeLibrary(p).first())
    }

    @Test
    fun progressAccumulatesWatchedTimeAndOnlyMovesForward() = runTest {
        data.library.upsertFromMeta(p, movie())
        val id = "tt0111161"
        data.library.recordProgress(p, id, id, 10_000, 100_000)
        data.library.recordProgress(p, id, id, 15_000, 100_000)
        data.library.recordProgress(p, id, id, 21_000, 100_000)
        var state = data.library.item(p, id)!!.state
        assertEquals(21_000, state.timeOffset)
        assertEquals(100_000, state.duration)
        assertEquals(11_000, state.timeWatched)
        assertEquals(11_000, state.overallTimeWatched)

        // A report behind the position (no seek reported) neither rewinds nor counts.
        data.library.recordProgress(p, id, id, 18_000, 100_000)
        state = data.library.item(p, id)!!.state
        assertEquals(21_000, state.timeOffset)
        assertEquals(11_000, state.timeWatched)

        // A reported seek moves the position without counting the jump.
        data.library.recordSeek(p, id, id, 70_000, 100_000)
        data.library.recordProgress(p, id, id, 72_000, 100_000)
        state = data.library.item(p, id)!!.state
        assertEquals(72_000, state.timeOffset)
        assertEquals(13_000, state.timeWatched)
    }

    @Test
    fun theFirstUpdateThatCountsAsWatchedMarksTheVideoOnce() = runTest {
        val item = data.library.upsertFromMeta(p, movie())
        // The playback says when the video counts as watched (PlaybackRules.countsAsWatched): from step 7 on here.
        val updates = (1..10).map { step -> data.library.recordProgress(p, item.id, item.id, step * 10_000L, 100_000, watched = step >= 7)!! }
        assertEquals(listOf(7), updates.withIndex().filter { it.value.crossedWatchedThreshold }.map { it.index + 1 })
        val state = updates.last().item.state
        assertEquals(1, state.timesWatched)
        assertEquals(1, state.flaggedWatched)
        assertTrue(updates.last().item.isWatched)
        // Playback never adds the item to the library: it stays temporary, in Continue Watching only.
        assertTrue(updates.last().item.temp && updates.last().item.removed)
    }

    @Test
    fun aNewVideoRestartsTheVideoCounters() = runTest {
        val show = data.library.upsertFromMeta(p, series())
        data.library.recordProgress(p, show.id, "tt0903747:1:1", 0, 100_000)
        data.library.recordProgress(p, show.id, "tt0903747:1:1", 95_000, 100_000, watched = true)
        val next = data.library.recordProgress(p, show.id, "tt0903747:1:2", 4_000, 100_000)!!.item.state
        assertEquals("tt0903747:1:2", next.videoId)
        assertEquals(0, next.timeWatched)
        assertEquals(0, next.flaggedWatched)
        assertEquals(95_000, next.overallTimeWatched)
        assertEquals(4_000, next.timeOffset)
        assertEquals(1, next.timesWatched)
    }

    @Test
    fun advancingKeepsTheItemInContinueWatchingAndRewindTakesItOut() = runTest {
        val show = data.library.upsertFromMeta(p, series())
        data.library.recordProgress(p, show.id, "tt0903747:1:1", 0, 100_000)
        data.library.recordProgress(p, show.id, "tt0903747:1:1", 92_000, 100_000)
        val meta = MetaItem(
            MetaItemPreview(id = show.id, type = "series", name = "Breaking Bad"),
            listOf(Video(id = "tt0903747:1:1", seriesInfo = SeriesInfo(1, 1)), Video(id = "tt0903747:1:2", seriesInfo = SeriesInfo(1, 2))),
        )
        data.library.advanceToVideo(p, meta, "tt0903747:1:2")
        val state = data.library.item(p, show.id)!!.state
        assertEquals("tt0903747:1:2", state.videoId)
        assertEquals(1, state.timeOffset)
        assertEquals(92_000, state.overallTimeWatched)
        assertEquals(listOf(show.id), cw())

        data.library.rewind(p, show.id)
        assertEquals(emptyList(), cw())
    }

    @Test
    fun markWatchedCreatesTheItemAndUnwatchedResetsTheCount() = runTest {
        data.library.markWatched(p, movie(), watched = false)
        assertNull(data.library.item(p, "tt0111161"))

        data.library.markWatched(p, movie(), watched = true)
        data.library.markWatched(p, movie(), watched = true)
        val watched = data.library.item(p, "tt0111161")!!
        assertEquals(2, watched.state.timesWatched)
        assertEquals(0, watched.state.timeOffset)
        assertTrue(watched.isWatched)

        data.library.markWatched(p, movie(), watched = false)
        assertFalse(data.library.item(p, "tt0111161")!!.isWatched)
    }

    @Test
    fun aMovieMarkedUnwatchedCountsAsWatchedWhenPlayedAgain() = runTest {
        val item = data.library.upsertFromMeta(p, movie())
        data.library.recordProgress(p, item.id, item.id, 90_000, 100_000, watched = true)
        data.library.markWatched(p, movie(), watched = false)

        data.library.recordProgress(p, item.id, item.id, 10_000, 100_000)
        val again = data.library.recordProgress(p, item.id, item.id, 90_000, 100_000, watched = true)!!
        assertTrue(again.crossedWatchedThreshold)
        assertTrue(again.item.isWatched)
        assertEquals(1, again.item.state.timesWatched)
    }

    @Test
    fun continueWatchingIsNewestFirstAndSkipsItemsOutsideIt() = runTest {
        val items = listOf(movie("tt1"), movie("tt2"), series("tt3"), MetaSnapshot("ch1", "tv", "News", null, "landscape"))
        for (meta in items) {
            clock.now += 1_000
            data.library.addToLibrary(p, meta)
            data.library.recordProgress(p, meta.id, meta.id, 5_000, 100_000)
        }
        data.library.addToLibrary(p, movie("tt4"))
        clock.now += 1_000
        data.library.recordProgress(p, "tt1", "tt1", 6_000, 100_000)
        assertEquals(listOf("tt1", "tt3", "tt2"), cw())
        assertEquals(listOf("tt1", "tt3"), data.library.observeContinueWatching(p, 2).first().map { it.id })
    }

    @Test
    fun writesGivenATimeAreOrderedByItNotByTheClock() = runTest {
        // The clock stands still: only the given times tell the writes apart.
        data.library.addToLibrary(p, movie("tt2"), at = 1_002)
        data.library.addToLibrary(p, movie("tt1"), at = 1_001)
        assertEquals(listOf("tt2", "tt1"), data.library.observeLibrary(p).first().map { it.id })
        assertEquals(1_002, data.library.item(p, "tt2")!!.mtime)

        data.library.upsertFromMeta(p, series("tt4"), at = 2_002)
        data.library.recordProgress(p, "tt4", "tt4", 5_000, 100_000, at = 2_002)
        data.library.upsertFromMeta(p, series("tt3"), at = 2_001)
        data.library.recordProgress(p, "tt3", "tt3", 5_000, 100_000, at = 2_001)
        assertEquals(listOf("tt4", "tt3"), cw())
        assertEquals(2_002, data.library.item(p, "tt4")!!.state.lastWatched)
    }

    @Test
    fun anUnchangedItemWritesNothing() = runTest {
        data.library.addToLibrary(p, movie())
        val first = data.sync.dirtyRecords(null, 10).single()
        clock.now += 5_000
        data.library.upsertFromMeta(p, movie())
        data.library.addToLibrary(p, movie())
        val after = data.sync.dirtyRecords(null, 10).single()
        assertEquals(first.hlc, after.hlc)
        assertEquals(clock.now - 5_000, data.library.item(p, "tt0111161")!!.mtime)
    }

    @Test
    fun readdingADeletedItemStartsFreshOnTopOfTheTombstone() = runTest {
        data.library.addToLibrary(p, movie())
        data.library.recordProgress(p, "tt0111161", "tt0111161", 60_000, 100_000)
        data.library.delete(p, "tt0111161")
        val tombstone = data.sync.dirtyRecords(null, 10).single()
        data.sync.markPushed(listOf(PushAck(tombstone.recordId, tombstone.hlc, 7)))

        assertTrue(data.library.addToLibrary(p, movie()), "a deleted title enters the library again")
        val revived = assertNotNull(data.library.item(p, "tt0111161"))
        assertEquals(0, revived.state.timeOffset)
        val push = data.sync.dirtyRecords(null, 10).single()
        assertEquals(tombstone.recordId, push.recordId)
        assertEquals(7L, push.baseVersion)
        assertFalse(push.deleted)
    }
}
