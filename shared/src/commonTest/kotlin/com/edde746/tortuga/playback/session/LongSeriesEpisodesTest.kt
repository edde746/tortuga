package com.edde746.tortuga.playback.session

import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.model.CountedVideos
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The next episode of a series of a thousand: found in one pass over its videos, however many are watched. */
class LongSeriesEpisodesTest {
    @Test
    fun theNextUnwatchedEpisodeAfterManyWatchedOnesTakesOnePass() {
        val videos = CountedVideos.episodes("tt0388629", 1_000)
        val meta = MetaItem(MetaItemPreview(id = "tt0388629", type = "series", name = "One Piece"), videos)
        val marks = WatchedBitField.empty((1..1_000).map { "tt0388629:1:$it" })
        (1..999).forEach { marks.set("tt0388629:1:$it", true) }
        videos.reads = 0

        val next = meta.nextUnwatched("tt0388629:1:1", marks, Instant.parse("2026-01-01T00:00:00Z"))

        assertEquals("tt0388629:1:1000", next?.id)
        assertTrue(videos.reads <= 5 * 1_000, "${videos.reads} reads of 1000 videos")
    }
}
