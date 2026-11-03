package com.edde746.tortuga.presentation.calendar

import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.LibraryItemState
import com.edde746.tortuga.model.CountedVideos
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The new-episode badge of a series of a thousand episodes, counted again at every library change (each progress save
 * while it plays) against the same answer: its videos are gone through once, not at every change.
 */
class LongSeriesNewEpisodesTest {
    @Test
    fun theBadgeOfALongSeriesIsCountedAgainWithoutGoingThroughItsVideos() {
        val first = Instant.parse("2024-01-01T00:00:00Z")
        val videos = CountedVideos.episodes("tt0388629", 1_000, firstRelease = first)
        val meta = MetaItem(MetaItemPreview(id = "tt0388629", type = "series", name = "One Piece"), videos)
        // Episode 732 comes out now, 733 tomorrow.
        val now = Instant.parse("2026-01-01T00:00:00Z")
        videos.reads = 0

        repeat(50) { change ->
            // Watched an hour after episode 500 + change came out.
            val item = item(lastWatched = first + (499 + change).days + 1.hours)
            assertEquals(mapOf("tt0388629" to 232 - change), newEpisodeCounts(listOf(item), listOf(meta), now))
            assertEquals(now + 1.days, nextNewEpisode(listOf(item), listOf(meta), now))
        }

        assertTrue(videos.reads <= 5 * 1_000, "${videos.reads} reads of 1000 videos")
    }

    private fun item(lastWatched: Instant) = LibraryItem(
        id = "tt0388629", type = "series", name = "One Piece", poster = null, posterShape = "poster", removed = false, temp = false,
        ctime = 0, mtime = 0,
        state = LibraryItemState(
            lastWatched = lastWatched.toEpochMilliseconds(), timeWatched = 0, timeOffset = 0, overallTimeWatched = 0, timesWatched = 1,
            flaggedWatched = 0, duration = 0, videoId = null, watchedVideos = null, noNotif = false,
        ),
    )
}
