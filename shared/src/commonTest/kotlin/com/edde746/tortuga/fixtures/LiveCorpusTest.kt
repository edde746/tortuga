package com.edde746.tortuga.fixtures

import com.edde746.tortuga.log.Redactor
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamPlayability
import com.edde746.tortuga.model.Subtitles
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The recorded corpus (`fixtures/live`, see `index.json`) decoded, and checked for keys. */
class LiveCorpusTest {
    private fun live(file: String) = AddonResponse.decode(FixtureCorpus.text("live/$file"))

    @Test
    fun corpusHoldsNoKeys() {
        for ((path, text) in FixtureCorpus.files) {
            assertEquals(emptyList(), Redactor.findKeys(text), "key-shaped text in fixtures/$path")
        }
    }

    @Test
    fun movieAndSeriesFromCinemeta() {
        val search = (live("cinemeta/search-movie-shawshank.json").content as ResourceContent.Metas).items
        assertEquals("tt0111161", search.first().id)

        val movie = (live("cinemeta/meta-movie-tt0111161.json").content as ResourceContent.Meta).item
        assertEquals("The Shawshank Redemption", movie.name)
        assertEquals("1994", movie.preview.releaseInfo)
        assertTrue(movie.preview.trailerStreams.all { it.playability is StreamPlayability.YouTube })
        val routed = movie.preview.links.mapNotNull { DeepLink.parse(it.url) }
        assertTrue(routed.any { it is DeepLink.Discover } && routed.any { it is DeepLink.Search }, "$routed")

        val series = (live("cinemeta/meta-series-tt7366338.json").content as ResourceContent.Meta).item
        assertEquals((1..5).map { SeriesInfo(1, it) }, series.videosInSeason(1).map { it.seriesInfo })
        assertEquals("tt7366338:1:1", series.videos.first().id)
        assertEquals(0, series.seasons.last())
    }

    @Test

    fun openSubtitlesForMovieAndEpisode() {
        for (file in listOf("opensubtitles/subtitles-movie-tt0111161.json", "opensubtitles/subtitles-series-tt7366338-1-1.json")) {
            val subtitles = (live(file).content as ResourceContent.Subtitles).items
            assertTrue(subtitles.any { it.lang == "eng" }, file)
            assertTrue(subtitles.all { it.url.startsWith("https://") }, file)
        }
    }

    @Test
    fun decodedItemsSurviveEncoding() {
        fun <T> roundTrip(items: List<T>, serializer: KSerializer<T>) = items.forEach { item ->
            assertEquals(item, AddonJson.decodeFromString(serializer, AddonJson.encodeToString(serializer, item)))
        }
        for (path in FixtureCorpus.files.keys.filter { it.startsWith("live/") && !it.endsWith("manifest.json") && !it.endsWith("index.json") }) {
            when (val content = AddonResponse.decode(FixtureCorpus.text(path)).content) {
                is ResourceContent.Metas -> roundTrip(content.items, MetaItemPreview.serializer())
                is ResourceContent.Meta -> roundTrip(listOf(content.item), MetaItem.serializer())
                is ResourceContent.Streams -> roundTrip(content.items, Stream.serializer())
                is ResourceContent.Subtitles -> roundTrip(content.items, Subtitles.serializer())
                else -> error("unexpected content in $path")
            }
        }
    }
}
