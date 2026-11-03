package com.edde746.tortuga.data.library

import com.edde746.tortuga.model.CountedVideos
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A series of a thousand episodes in Continue Watching: every library change reads its watched marks again, against
 * the same meta, which orders its videos for the marks once.
 */
class LongSeriesMarksTest {
    @Test
    fun readingTheMarksAgainForTheSameMetaDoesNotOrderItsVideosAgain() {
        val videos = CountedVideos.episodes("tt0388629", 1_000)
        val meta = MetaItem(MetaItemPreview(id = "tt0388629", type = "series", name = "One Piece"), videos)
        val field = WatchedBitField.empty((1..1_000).map { "tt0388629:1:$it" }).apply { (1..499).forEach { set("tt0388629:1:$it", true) } }
        val state = LibraryItemState(
            lastWatched = 0, timeWatched = 0, timeOffset = 600_000, overallTimeWatched = 0, timesWatched = 0, flaggedWatched = 0,
            duration = 1_440_000, videoId = "tt0388629:1:500", watchedVideos = field.serialize(), noNotif = false,
        )
        videos.reads = 0

        // Fifty library changes, each settling the item against the meta: episode 500 is unwatched, so it stays.
        repeat(50) { assertEquals(state, state.settled(meta, Instant.parse("2026-01-01T00:00:00Z"))) }

        assertTrue(videos.reads <= 5 * 1_000, "${videos.reads} reads of 1000 videos")
    }
}
