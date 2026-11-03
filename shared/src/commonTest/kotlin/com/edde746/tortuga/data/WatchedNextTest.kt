package com.edde746.tortuga.data

import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.presentation.board.toContinueWatchingCard
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The library's one rule for where an item rests (`LibraryItemState.settled`): never on a watched episode
 * with a position, whichever writer would leave it there, and Continue Watching shows the next episode Up next.
 */
class WatchedNextTest {
    private val clock = TestClock()
    private val data = testData(clock)
    private val p = "p1"
    private val day = 86_400_000L
    private val duration = 1_422_123L

    @AfterTest
    fun close() = data.close()

    /** A user's series as Cinemeta lists it: specials first, then S1E1–S1E6 out and S1E7 tomorrow. */
    private val show = MetaItem(
        MetaItemPreview(id = "tt39551330", type = "series", name = "Chainsmoker Cat"),
        listOf(episode(0, 1, -300), episode(0, 2, -200)) + (1..6).map { episode(1, it, -70 + 7 * it) } + episode(1, 7, +1),
    )

    private fun episode(season: Int, episode: Int, releasedInDays: Int) = Video(
        id = "tt39551330:$season:$episode",
        released = Instant.fromEpochMilliseconds(clock.now + releasedInDays * day),
        seriesInfo = SeriesInfo(season, episode),
    )

    private fun e(episode: Int) = "tt39551330:1:$episode"

    private suspend fun state() = data.library.item(p, show.id)!!.state

    private suspend fun card() = data.library.observeContinueWatching(p, 10).first().singleOrNull()?.toContinueWatchingCard()

    private suspend fun watched(): List<String> {
        val marks = WatchedBitField.of(state().watchedVideos, show)
        return show.videos.map { it.id }.filter(marks::isWatched)
    }

    /** S1E1 to [last] marked as a sync or Stremio stores them: the bitfield alone. */
    private suspend fun watchedThrough(last: Int) {
        data.library.upsertFromMeta(p, MetaSnapshot(show.id, show.type, show.name, null, "poster"))
        val marks = WatchedBitField.of(null, show).apply { (1..last).forEach { set(e(it), true) } }
        data.library.setWatchedVideos(p, show.id, marks.serialize())
    }

    private val upNextSix get() = T.browse.continueWatching.detail(T.session.episode("1", "6"), T.browse.continueWatching.upNext)

    @Test
    fun aProgressSavePastTheThresholdMovesOnInTheWriteThatCountsIt() = runTest {
        // A user's S1E5: resumed at 61.7 %, counted at 70 %, played on to 93.7 %.
        watchedThrough(4)
        data.library.recordProgress(p, show.id, e(5), 877_293, duration, show)
        val crossing = data.library.recordProgress(p, show.id, e(5), 997_330, duration, show, watched = true)!!
        assertTrue(crossing.crossedWatchedThreshold)
        assertEquals(e(6), crossing.item.state.videoId, "the write that marks S1E5 moves the pointer on")
        assertEquals(1, crossing.item.state.timeOffset)

        data.library.recordProgress(p, show.id, e(5), 1_332_748, duration, show, watched = true)
        assertEquals(e(6), state().videoId, "later saves of the watched episode leave the pointer on S1E6")
        assertEquals(1, state().timeOffset)
        assertEquals((1..5).map(::e), watched())
        val card = card()!!
        assertEquals(e(6), card.videoId)
        assertEquals(upNextSix, card.detail)
        assertEquals(0f, card.progress)
    }

    @Test
    fun theLastEpisodeOutLeavesContinueWatchingOnceItCounts() = runTest {
        watchedThrough(5)
        data.library.recordProgress(p, show.id, e(6), 1_000_000, duration, show, watched = true)
        data.library.recordProgress(p, show.id, e(6), 1_300_000, duration, show, watched = true)
        assertEquals(e(6), state().videoId)
        assertEquals(0, state().timeOffset, "S1E7 airs tomorrow: nothing to move to")
        assertNull(card())
    }

    @Test
    fun markingTheCurrentEpisodeMovesPastTheWatchedOnes() = runTest {
        watchedThrough(2)
        data.library.markVideoWatched(p, show, e(4), watched = true)
        data.library.recordProgress(p, show.id, e(3), 600_000, duration, show)
        data.library.markVideoWatched(p, show, e(3), watched = true)
        assertEquals(e(5), state().videoId, "S1E4 is watched already")
        assertEquals(1, state().timeOffset)
    }

    @Test
    fun theNextEpisodeAskedForMovesPastOneWatchedAlready() = runTest {
        watchedThrough(2)
        data.library.markVideoWatched(p, show, e(4), watched = true)
        data.library.recordProgress(p, show.id, e(3), 600_000, duration, show)
        data.library.advanceToVideo(p, show, e(4))
        assertEquals(e(5), state().videoId)
        assertEquals(1, state().timeOffset)
    }

    @Test
    fun aRewatchLeavesContinueWatchingOnTheEpisodeItHad() = runTest {
        watchedThrough(5)
        data.library.recordProgress(p, show.id, e(6), 400_000, duration, show)
        data.library.recordProgress(p, show.id, e(2), 0, duration, show)
        data.library.recordSeek(p, show.id, e(2), 900_000, duration)
        data.library.recordProgress(p, show.id, e(2), 1_000_000, duration, show, watched = true)
        assertEquals(e(6), state().videoId)
        assertEquals(400_000, state().timeOffset, "S1E6's own position stays")
        assertEquals(e(6), card()!!.videoId)
    }

    @Test
    fun unmarkingAnEpisodeStillWorks() = runTest {
        watchedThrough(4)
        data.library.recordProgress(p, show.id, e(5), 1_000_000, duration, show, watched = true)
        data.library.markVideoWatched(p, show, e(5), watched = false)
        assertEquals((1..4).map(::e), watched())
        assertEquals(e(6), state().videoId, "Continue Watching stays where it was")

        // Unwatched again, S1E5 is no rewatch: the pointer follows it.
        data.library.recordProgress(p, show.id, e(5), 300_000, duration, show)
        assertEquals(e(5), state().videoId)
        assertEquals(300_000, state().timeOffset)
    }

    @Test
    fun aStateAnOlderBuildLeftMovesOnAsContinueWatchingReadsIt() = runTest {
        // A user's Mac after 11 Oct: S1E1–S1E5 marked, the pointer still on S1E5 at 93.7 %, flagged watched,
        // written without the meta (as an older build or a sync from one stores it).
        watchedThrough(5)
        data.library.recordProgress(p, show.id, e(5), 1_332_748, duration, watched = true)
        assertEquals(e(5), state().videoId)
        assertEquals(1, state().flaggedWatched)

        val settling = launch(Dispatchers.Default) { data.library.settleContinueWatching(p) { _, _ -> show } }
        val item = withContext(Dispatchers.Default) {
            withTimeout(5_000) { data.library.observeContinueWatching(p, 10).first { it.singleOrNull()?.state?.videoId == e(6) }.single() }
        }
        settling.cancel()
        assertEquals(1, item.state.timeOffset)
        assertEquals(upNextSix, item.toContinueWatchingCard().detail)
        assertEquals((1..5).map(::e), watched(), "the marks stay")
    }

    @Test
    fun aMetaNoAddonAnsweredIsAskedAgainWithTheNextChange() = runTest {
        watchedThrough(5)
        data.library.recordProgress(p, show.id, e(5), 1_332_748, duration, watched = true)
        val firstAsked = CompletableDeferred<Unit>()
        var asked = 0
        // The first time no addon answers (offline, a timeout).
        val settling = launch(Dispatchers.Default) {
            data.library.settleContinueWatching(p) { _, _ -> (if (asked++ == 0) null else show).also { firstAsked.complete(Unit) } }
        }
        withContext(Dispatchers.Default) { withTimeout(5_000) { firstAsked.await() } }
        assertEquals(e(5), state().videoId)

        // The next change to the library asks again, and the item moves on.
        data.library.upsertFromMeta(p, MetaSnapshot("tt0111161", "movie", "Another title", null, "poster"))
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { data.library.observeContinueWatching(p, 10).first { items -> items.any { it.state.videoId == e(6) } } }
        }
        settling.cancel()
    }
}
