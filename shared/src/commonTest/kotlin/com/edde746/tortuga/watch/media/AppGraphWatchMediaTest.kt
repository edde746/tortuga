package com.edde746.tortuga.watch.media

import com.edde746.tortuga.app.testGraph
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.playback.session.StreamChoice
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The graph's watch-together media parts for a profile with no addons and no trackers. */
class AppGraphWatchMediaTest {
    private val movie = MetaItem(MetaItemPreview(id = "tt0111161", type = "movie", name = "The Shawshank Redemption"))
    private val choice = StreamChoice(Stream(StreamSource.Url("https://cdn.example/movie.mkv")), "https://streams.example.com/manifest.json", "Streams")

    @Test
    fun aProfileWithoutAddonsPlaysTheRoomsOwnMetaAndFindsNoFile() = runTest {
        val graph = testGraph()
        val media = graph.roomMediaBuilder().build(movie, movie.id, choice, resolved = null, durationMs = HOUR)

        val title = graph.roomTitleResolver(PROFILE).resolve(media)
        val files = graph.fileMatcher(PROFILE).files(media, title).files

        assertEquals("tt0111161", media.imdbId)
        assertTrue(title.minimal)
        assertEquals(MetaItem(movie.preview.copy(imdbId = "tt0111161")), title.meta)
        assertEquals(emptyList(), files)
    }
}
