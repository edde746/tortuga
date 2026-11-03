package com.edde746.tortuga.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinkTest {
    @Test
    fun installLinks() {
        assertEquals(
            DeepLink.InstallAddon("https://addon.example/sort=size|qualityfilter=480p/manifest.json".transportUrl()),
            DeepLink.parse("stremio://addon.example/sort=size|qualityfilter=480p/manifest.json"),
        )
        assertEquals(
            "stremio://addon.example/manifest.json",
            DeepLink.InstallAddon("https://addon.example/manifest.json".transportUrl()).toUrl(),
        )
        // Not an addon: no manifest path.
        assertNull(DeepLink.parse("stremio://example.com/something"))
    }

    @Test
    fun cinemetaLinks() {
        assertEquals(DeepLink.Search("Tim Robbins"), DeepLink.parse("stremio:///search?search=Tim%20Robbins"))
        assertEquals(DeepLink.Search("better call saul"), DeepLink.parse("stremio:///search?query=better%20call%20saul"))
        assertEquals("stremio:///search?query=better%20call%20saul", DeepLink.Search("better call saul").toUrl())
        assertEquals(
            DeepLink.Discover(
                "https://v3-cinemeta.strem.io/manifest.json",
                "series",
                "top",
                listOf(ExtraValue("genre", "Sci-Fi & Fantasy"), ExtraValue("skip", "49")),
            ),
            DeepLink.parse("stremio:///discover/https%3A%2F%2Fv3-cinemeta.strem.io%2Fmanifest.json/series/top?genre=Sci-Fi%20%26%20Fantasy&skip=49"),
        )
    }

    @Test
    fun detailLinkVariants() {
        assertEquals(DeepLink.Detail("movie", "tt0111161"), DeepLink.parse("stremio://detail/movie/tt0111161"))
        assertEquals(DeepLink.Detail("series", "tt7366338", "tt7366338:1:1"), DeepLink.parse(" stremio:///detail/series/tt7366338/tt7366338%3A1%3A1 "))
        assertNull(DeepLink.parse("stremio:///detail/movie"))
        assertNull(DeepLink.parse("stremio:///detail//tt1"))
    }

    @Test
    fun detailLinksCarryAutoPlay() {
        assertEquals(
            DeepLink.Detail("series", "tt0108778", "tt0108778:1:1", autoPlay = true),
            DeepLink.parse("stremio:///detail/series/tt0108778/tt0108778%3A1%3A1?autoPlay=true"),
        )
        assertEquals(DeepLink.Detail("movie", "tt0066921", "tt0066921"), DeepLink.parse("stremio:///detail/movie/tt0066921/tt0066921?autoPlay=false"))
        assertEquals(
            "stremio:///detail/movie/tt0066921/tt0066921?autoPlay=true",
            DeepLink.Detail("movie", "tt0066921", "tt0066921", autoPlay = true).toUrl(),
        )
    }

    @Test
    fun pageLinks() {
        assertEquals(DeepLink.Board, DeepLink.parse("stremio:///board"))
        assertEquals(DeepLink.Library, DeepLink.parse("stremio:///library"))
        assertEquals(DeepLink.DiscoverDefault, DeepLink.parse("stremio:///discover"))
        assertEquals(DeepLink.DiscoverDefault, DeepLink.parse("stremio:///discover/"))
        assertEquals(DeepLink.Board, DeepLink.parse("stremio://board"))
        assertEquals(DeepLink.Library, DeepLink.parse("stremio://library/"))
        assertNull(DeepLink.parse("stremio:///board/extra"))
        // A host that only starts like a page is an addon's.
        assertEquals(DeepLink.InstallAddon("https://board.example/manifest.json".transportUrl()), DeepLink.parse("stremio://board.example/manifest.json"))
    }

    @Test
    fun otherLinksAreNotRouted() {
        assertNull(DeepLink.parse("https://www.strem.io/s/movie/the-shawshank-redemption-0111161"))
        assertNull(DeepLink.parse("stremio:///player/abc"))
        assertNull(DeepLink.parse("stremio:///search"))
        assertNull(DeepLink.parse("stremio:///discover/not-a-transport/movie/top"))
        assertNull(DeepLink.parse("stremio:detail/movie/tt1"))
    }
}
