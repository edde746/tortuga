package com.edde746.tortuga.addons

import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.model.ExtraProp
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.TransportUrl
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.model.withExtra
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogPagingTest {
    private val manifest = Manifest.decode(FixtureCorpus.text("live/cinemeta/manifest.json"))
    private val top = manifest.catalogs.first { it.id == "top" && it.type == "movie" }
    private val genre = ResourceRequest(DefaultAddons.CINEMETA_URL.transportUrl(), ResourcePath("catalog", "movie", "top", listOf(ExtraValue("genre", "Drama"))))

    @Test
    fun skipIsTheNumberOfItemsLoadedSoFar() {
        val page2 = CatalogPaging.nextPage(top, genre, listOf(49))!!
        // `skip` goes first, as stremio-core's `extend_one` orders it.
        assertEquals(listOf(ExtraValue("skip", "49"), ExtraValue("genre", "Drama")), page2.path.extra)
        val page3 = CatalogPaging.nextPage(top, page2, listOf(49, 48))!!
        assertEquals(listOf(ExtraValue("skip", "97"), ExtraValue("genre", "Drama")), page3.path.extra)
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/movie/top/skip=97&genre=Drama.json",
            TransportUrl.resourceUrl(page3.base, page3.path),
        )
    }

    @Test
    fun pagingStopsAtAnEmptyOrFailedPageAndNeedsSkip() {
        assertNull(CatalogPaging.nextPage(top, genre, emptyList()))
        assertNull(CatalogPaging.nextPage(top, genre, listOf(49, 0)))
        assertNull(CatalogPaging.nextPage(top, genre, listOf(49, null)))
        val calendar = manifest.catalogs.first { it.id == "calendar-videos" }
        assertNull(CatalogPaging.nextPage(calendar, genre, listOf(10)))
    }

    @Test
    fun aMultiValueExtraTogglesItsValuesAndDropsTheOldestBeyondItsLimit() {
        val genre = ExtraProp("genre", options = listOf("A", "B", "C"), optionsLimit = 2)
        val skip = listOf(ExtraValue("skip", "49"))
        val a = skip.withExtra(genre, "A")
        assertEquals(listOf(ExtraValue("genre", "A"), ExtraValue("skip", "49")), a)
        val ba = a.withExtra(genre, "B")
        assertEquals(listOf(ExtraValue("genre", "B"), ExtraValue("genre", "A"), ExtraValue("skip", "49")), ba, "the newest goes first")
        assertEquals(listOf(ExtraValue("genre", "C"), ExtraValue("genre", "B"), ExtraValue("skip", "49")), ba.withExtra(genre, "C"), "the oldest goes")
        assertEquals(listOf(ExtraValue("genre", "B"), ExtraValue("skip", "49")), ba.withExtra(genre, "A"), "a value set goes")
        assertEquals(skip, ba.withExtra(genre, null))
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/movie/top/genre=B&genre=A&skip=49.json",
            TransportUrl.resourceUrl(DefaultAddons.CINEMETA_URL.transportUrl(), ResourcePath("catalog", "movie", "top", ba)),
        )
    }

    @Test
    fun aRequiredExtraIsNeverCleared() {
        val one = ExtraProp("genre", isRequired = true, options = listOf("A", "B"))
        assertEquals(listOf(ExtraValue("genre", "A")), listOf(ExtraValue("genre", "A")).withExtra(one, null))
        val many = ExtraProp("genre", isRequired = true, options = listOf("A", "B"), optionsLimit = 2)
        val ba = listOf(ExtraValue("genre", "B"), ExtraValue("genre", "A"))
        assertEquals(ba, ba.withExtra(many, null))
        assertEquals(listOf(ExtraValue("genre", "B")), ba.withExtra(many, "A"))
        assertEquals(listOf(ExtraValue("genre", "B")), listOf(ExtraValue("genre", "B")).withExtra(many, "B"), "its last value stays")
    }
}
