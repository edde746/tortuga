package com.edde746.tortuga.data

import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Per-video watched marks in the library item (Stremio's bitfield; stremio-core `MetaDetails` mark actions). */
class WatchedMarksTest {
    private val clock = TestClock()
    private val data = testData(clock)
    private val p = "p1"
    private val day = 86_400_000L

    @AfterTest
    fun close() = data.close()

    /** Two seasons of three, then two specials; S2E3 airs tomorrow. Videos in MetaItem order (specials last). */
    private val show = MetaItem(
        MetaItemPreview(id = "tt9", type = "series", name = "Show"),
        listOf(
            episode(1, 1, -300), episode(1, 2, -290), episode(1, 3, -280),
            episode(2, 1, -20), episode(2, 2, -10), episode(2, 3, +1),
            episode(0, 1, -250), episode(0, 2, -5),
        ),
    )

    private fun episode(season: Int, episode: Int, releasedInDays: Int) = Video(
        id = "tt9:$season:$episode",
        released = Instant.fromEpochMilliseconds(clock.now + releasedInDays * day),
        seriesInfo = SeriesInfo(season, episode),
    )

    private suspend fun watched(): List<String> {
        val item = data.library.item(p, "tt9") ?: return emptyList()
        val marks = WatchedBitField.of(item.state.watchedVideos, show)
        return show.videos.map { it.id }.filter(marks::isWatched)
    }

    @Test
    fun markingAVideoCreatesATemporaryItemAndUnmarkingClearsIt() = runTest {
        data.library.markVideoWatched(p, show, "tt9:1:1", watched = false)
        assertNull(data.library.item(p, "tt9"), "unmarking creates nothing")

        data.library.markVideoWatched(p, show, "tt9:1:2", watched = true)
        val item = assertNotNull(data.library.item(p, "tt9"))
        assertTrue(item.removed && item.temp, "not in the library")
        assertTrue(item.state.watchedVideos!!.startsWith("tt9:1:2:4:"), "anchored at S1E2, 4th in bit order (specials first)")
        assertEquals(listOf("tt9:1:2"), watched())

        data.library.markVideoWatched(p, show, "tt9:1:3", watched = true)
        data.library.markVideoWatched(p, show, "tt9:1:2", watched = false)
        assertEquals(listOf("tt9:1:3"), watched())
    }

    @Test
    fun markingThePointedVideoWatchedMovesThePointerOn() = runTest {
        data.library.upsertFromMeta(p, MetaSnapshot("tt9", "series", "Show", null, "poster"))
        data.library.recordProgress(p, "tt9", "tt9:1:3", 600_000, 2_400_000, show)
        data.library.markVideoWatched(p, show, "tt9:1:3", watched = true)
        var state = data.library.item(p, "tt9")!!.state
        assertEquals("tt9:2:1", state.videoId)
        assertEquals(1, state.timeOffset, "the next video starts over, still in Continue Watching")

        data.library.recordProgress(p, "tt9", "tt9:2:2", 60_000, 2_400_000, show)
        data.library.markVideoWatched(p, show, "tt9:2:2", watched = true)
        state = data.library.item(p, "tt9")!!.state
        assertEquals("tt9:2:2", state.videoId, "S2E3 isn't out: nothing to move to")
        assertEquals(0, state.timeOffset)
    }

    @Test
    fun markingASeasonSkipsUnreleasedEpisodesAndMovesThePointerPastIt() = runTest {
        data.library.addToLibrary(p, MetaSnapshot("tt9", "series", "Show", null, "poster"))
        data.library.recordProgress(p, "tt9", "tt9:1:1", 60_000, 2_400_000, show)
        val lastWatched = data.library.item(p, "tt9")!!.state.lastWatched

        data.library.markSeasonWatched(p, show, 1, watched = true)
        var state = data.library.item(p, "tt9")!!.state
        assertEquals(listOf("tt9:1:1", "tt9:1:2", "tt9:1:3"), watched())
        assertEquals("tt9:2:1", state.videoId)
        assertEquals(1, state.timeOffset)
        assertEquals(lastWatched, state.lastWatched, "releases are older than the last watch")

        data.library.markSeasonWatched(p, show, 2, watched = true)
        state = data.library.item(p, "tt9")!!.state
        assertEquals(listOf("tt9:1:1", "tt9:1:2", "tt9:1:3", "tt9:2:1", "tt9:2:2"), watched(), "S2E3 airs tomorrow")
        assertEquals("tt9:2:1", state.videoId, "nothing out after S2E2: the pointer stays, with no position")
        assertEquals(0, state.timeOffset)

        data.library.markSeasonWatched(p, show, 2, watched = false)
        assertEquals(listOf("tt9:1:1", "tt9:1:2", "tt9:1:3"), watched())
    }

    @Test
    fun markingAllCountsAWatchAndClearsTheResumePosition() = runTest {
        data.library.markAllWatched(p, show, watched = true)
        var item = data.library.item(p, "tt9")!!
        assertEquals(show.videos.map { it.id } - "tt9:2:3", watched())
        assertEquals(1, item.state.timesWatched)
        assertEquals(0, item.state.timeOffset)

        data.library.markAllWatched(p, show, watched = false)
        item = data.library.item(p, "tt9")!!
        assertEquals(emptyList(), watched())
        assertFalse(item.isWatched)
    }

    @Test
    fun countingAsWatchedMarksTheVideo() = runTest {
        data.library.upsertFromMeta(p, MetaSnapshot("tt9", "series", "Show", null, "poster"))
        data.library.markVideoWatched(p, show, "tt9:1:1", watched = true)
        data.library.recordProgress(p, "tt9", "tt9:1:2", 0, 100_000, show)
        assertFalse(data.library.recordProgress(p, "tt9", "tt9:1:2", 50_000, 100_000, show)!!.crossedWatchedThreshold)
        assertEquals(listOf("tt9:1:1"), watched())
        assertTrue(data.library.recordProgress(p, "tt9", "tt9:1:2", 75_000, 100_000, show, watched = true)!!.crossedWatchedThreshold)
        assertEquals(listOf("tt9:1:1", "tt9:1:2"), watched())
    }

    @Test
    fun aFieldWrittenByStremioIsReadAndExtended() = runTest {
        // stremio-core's own vector: S1E1–S1E5 of tt2934286 watched, anchored at S1E5.
        val meta = MetaItem(
            MetaItemPreview(id = "tt2934286", type = "series", name = "Halo"),
            (1..9).map { Video(id = "tt2934286:1:$it", seriesInfo = SeriesInfo(1, it)) },
        )
        data.library.markVideoWatched(p, meta, "tt2934286:1:1", watched = true)
        data.library.setWatchedVideos(p, "tt2934286", "tt2934286:1:5:5:eJyTZwAAAEAAIA==")
        data.library.markVideoWatched(p, meta, "tt2934286:1:6", watched = true)
        val field = data.library.item(p, "tt2934286")!!.state.watchedVideos!!
        assertEquals("tt2934286:1:6:6:eJyzZwAAAIAAQA==", field)
        val marks = WatchedBitField.of(field, meta)
        assertEquals((1..6).map { "tt2934286:1:$it" }, meta.videos.map { it.id }.filter(marks::isWatched))
    }

    /** A stored field that can't be read reads as no marks, and no mark replaces it: what it holds would be gone everywhere. */
    @Test
    fun aFieldThatCantBeReadIsNotReplacedByAMark() = runTest {
        data.library.markVideoWatched(p, show, "tt9:1:1", watched = true)
        val unreadable = "tt9:1:1:4:not-base64"
        data.library.setWatchedVideos(p, "tt9", unreadable)
        assertEquals(emptyList(), watched())

        assertFalse(data.library.markVideoWatched(p, show, "tt9:1:2", watched = true))
        assertEquals(emptyList(), data.library.markSeasonWatched(p, show, 1, watched = true))
        val update = assertNotNull(data.library.recordProgress(p, "tt9", "tt9:2:1", 75_000, 100_000, show, watched = true))
        assertTrue(update.crossedWatchedThreshold)
        assertEquals(1, update.item.state.timesWatched, "the rest of the write goes through")
        assertTrue(data.library.markAllWatched(p, show, watched = true))
        assertEquals(2, data.library.item(p, "tt9")?.state?.timesWatched)
        assertEquals(unreadable, data.library.item(p, "tt9")?.state?.watchedVideos)
    }
}
