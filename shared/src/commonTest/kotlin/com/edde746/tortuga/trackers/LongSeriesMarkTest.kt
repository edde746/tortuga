package com.edde746.tortuga.trackers

import com.edde746.tortuga.model.CountedVideos
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.trackers.TrackerService.TRAKT
import com.edde746.tortuga.trackers.ids.FixtureMappings
import com.edde746.tortuga.trackers.ids.StremioTrackerIdResolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A series of a thousand episodes marked watched as a whole, through the coordinator and the id resolver: each episode
 * is looked up in the meta's videos without going through them all again.
 */
class LongSeriesMarkTest {
    @Test
    fun markingALongSeriesGoesThroughItsVideosAFewTimesNotOncePerEpisode() = runTest {
        val f = TrackerFixture(this, idResolver = StremioTrackerIdResolver(FixtureMappings()))
        try {
            f.connect(TRAKT)
            val videos = CountedVideos.episodes("tt0388629", 1_000)
            val show = MetaItem(MetaItemPreview(id = "tt0388629", type = "series", name = "One Piece"), videos)
            videos.reads = 0

            f.coordinator.marks.onMarked(WatchedMark.Title("tt0388629", "series", show, watched = true))
            f.drain()

            val sent = f.log.filter { it.service == TRAKT }.flatMap { it.op.removePrefix("watched ").split(", ") }
            assertEquals((1..1_000).map { "tt0388629:1:$it" }, sent)
            assertTrue(videos.reads <= 20 * 1_000, "${videos.reads} reads of 1000 videos")
        } finally {
            f.close()
        }
    }
}
