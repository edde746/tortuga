package com.edde746.tortuga.playback.session

import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemBehaviorHints
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.Video
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** stremio-core's `next_video` tests (`types/resource/meta_item.rs`), on this app's video order (specials last). */
class EpisodesTest {
    private val now = Instant.parse("2026-09-29T12:00:00Z")

    private fun series(vararg videos: Video, type: String = "series", live: Boolean = false) = MetaItem(
        MetaItemPreview(id = "tt1", type = type, name = "Show", behaviorHints = MetaItemBehaviorHints(isLive = live)),
        videos = videos.toList(),
    )

    @Test
    fun theNextEpisodeNeverCrossesIntoTheSpecials() {
        val meta = series(episode(1, 1), episode(1, 2), episode(2, 1), episode(0, 1), episode(0, 2))
        fun next(id: String) = meta.nextVideo(id, now)?.id
        assertEquals("tt1:1:2", next("tt1:1:1"))
        assertEquals("tt1:2:1", next("tt1:1:2"), "into the next season")
        assertNull(next("tt1:2:1"), "not into season 0")
        assertEquals("tt1:0:2", next("tt1:0:1"), "within the specials")
        assertNull(next("tt1:0:2"))
        assertNull(next("missing"))
    }

    @Test
    fun anUnreleasedEpisodeIsNotNextYet() {
        val meta = series(episode(1, 1), episode(1, 2, released = now + 1.days))
        assertNull(meta.nextVideo("tt1:1:1", now))
        assertEquals("tt1:1:2", meta.nextVideo("tt1:1:1", now + 1.days)?.id)
    }

    @Test
    fun liveItemsHaveNoNextVideo() {
        assertNull(series(episode(1, 1), episode(1, 2), live = true).nextVideo("tt1:1:1", now))
        assertNull(series(episode(1, 1), episode(1, 2), type = "tv").nextVideo("tt1:1:1", now))
    }

    @Test
    fun thePreviousEpisodeMirrorsTheNext() {
        val meta = series(episode(1, 1), episode(1, 2), episode(2, 1), episode(0, 1), episode(0, 2))
        fun previous(id: String) = meta.previousVideo(id)?.id
        assertNull(previous("tt1:1:1"), "the first episode has none")
        assertEquals("tt1:1:1", previous("tt1:1:2"))
        assertEquals("tt1:1:2", previous("tt1:2:1"), "into the season before")
        assertNull(previous("tt1:0:1"), "not out of the specials into a regular season")
        assertEquals("tt1:0:1", previous("tt1:0:2"), "within the specials")
        assertNull(previous("missing"))
        for (id in listOf("tt1:1:2", "tt1:2:1", "tt1:0:2")) {
            assertEquals(id, meta.nextVideo(previous(id)!!, now)?.id, "the next of $id's previous is $id")
        }
    }

    @Test
    fun anEpisodeIsAnyOfASeriesVideosButNotAMovie() {
        val meta = series(episode(1, 1), episode(1, 2))
        assertTrue(meta.isEpisode("tt1:1:1"))
        assertTrue(series(episode(1, 1)).isEpisode("tt1:1:1"), "a series of one episode")
        assertFalse(meta.isEpisode("tt1"), "not one of its videos")
        assertFalse(series(type = "movie").isEpisode("tt1"), "a movie without videos")
        assertFalse(series(Video(id = "tt1", title = "The movie"), type = "movie").isEpisode("tt1"), "a movie's one video")
        assertTrue(series(Video(id = "yt:1", title = "One"), Video(id = "yt:2", title = "Two"), type = "channel").isEpisode("yt:1"))
        assertFalse(series(episode(1, 1), episode(1, 2), live = true).isEpisode("tt1:1:1"))
        assertNull(series(episode(1, 1), episode(1, 2), live = true).previousVideo("tt1:1:2"))
    }

    @Test
    fun episodeLines() {
        assertEquals("S1E2 · \u2068Pilot\u2069", episode(1, 2, "Pilot").episodeLine(), "the title isolated, the code not")
        assertEquals("S1E2", episode(1, 2).episodeLine())
        assertEquals("S1E2", episode(1, 2, "Pilot").episodeCode())
    }
}
