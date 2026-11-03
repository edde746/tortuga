package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.presentation.calendar.libraryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** What "Hide watched titles" counts as finished ([finishedTitles]). */
class FinishedTitlesTest {
    private val now = Instant.parse("2026-06-01T00:00:00Z")

    /** A series of [episodes] in season 1 released on [released] each, and a special in season 0. */
    private fun series(id: String, vararg released: String) = MetaItem(
        MetaItemPreview(id = id, type = "series", name = id),
        videos = listOf(Video(id = "$id:0:1", title = "Special", released = Instant.parse("2020-01-01T00:00:00Z"), seriesInfo = SeriesInfo(0, 1))) +
            released.mapIndexed { index, date ->
                Video(id = "$id:1:${index + 1}", title = "Episode ${index + 1}", released = Instant.parse(date), seriesInfo = SeriesInfo(1, index + 1))
            },
    )

    private fun watched(item: LibraryItem, meta: MetaItem, vararg episodes: Int): LibraryItem {
        val marks = WatchedBitField.empty(meta.watchedOrder)
        episodes.forEach { marks.set("${meta.id}:1:$it", true) }
        return item.copy(state = item.state.copy(timesWatched = episodes.size.toLong(), watchedVideos = marks.serialize()))
    }

    private fun movie(id: String, timesWatched: Long) =
        libraryItem(id, type = "movie").let { it.copy(state = it.state.copy(timesWatched = timesWatched)) }

    @Test
    fun aMovieIsFinishedOnceWatched() {
        assertEquals(setOf("tt1"), finishedTitles(listOf(movie("tt1", 1), movie("tt2", 0)), emptyList(), now))
    }

    @Test
    fun aSeriesIsFinishedWithEveryEpisodeOutWatchedAndNotOnceANewOneIsOut() {
        val done = series("tt10", "2026-01-01T00:00:00Z", "2026-01-08T00:00:00Z", "2026-12-01T00:00:00Z")
        val newEpisode = series("tt11", "2026-01-01T00:00:00Z", "2026-01-08T00:00:00Z", "2026-05-31T00:00:00Z")
        val started = series("tt12", "2026-01-01T00:00:00Z", "2026-01-08T00:00:00Z")
        val items = listOf(
            // The third episode comes out in December, and the special doesn't count.
            watched(libraryItem("tt10"), done, 1, 2),
            // Its third episode came out yesterday.
            watched(libraryItem("tt11"), newEpisode, 1, 2),
            watched(libraryItem("tt12"), started, 1),
            // The addons don't answer for it.
            watched(libraryItem("tt13"), series("tt13", "2026-01-01T00:00:00Z"), 1),
        )
        assertEquals(setOf("tt10"), finishedTitles(items, listOf(done, newEpisode, started), now))
        assertEquals(
            setOf("tt11"),
            finishedTitles(listOf(watched(libraryItem("tt11"), newEpisode, 1, 2, 3)), listOf(newEpisode), now),
            "watching the new episode finishes it again",
        )
    }
}
