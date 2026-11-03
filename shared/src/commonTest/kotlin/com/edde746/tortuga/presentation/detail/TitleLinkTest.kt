package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.app.IncomingLink
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.presentation.homescreen.HomeScreenLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The link Detail's Share sends, per kind of ID, and how the app reads it back. */
class TitleLinkTest {
    @Test
    fun anImdbTitleKeepsItsIdAsWritten() {
        val link = TitleLink("movie", "tt0111161", "The Shawshank Redemption")
        assertEquals("https://usetortuga.app/t/movie/tt0111161?name=The%20Shawshank%20Redemption", link.toUrl())
        assertEquals("tortuga://detail?type=movie&id=tt0111161", link.appUrl())
        assertEquals(link, TitleLink.parse(link.toUrl()))
        assertEquals("https://usetortuga.app/t/series/tt7366338", TitleLink("series", "tt7366338").toUrl())
    }

    @Test
    fun aKitsuOrCustomPrefixIdIsEncodedWhole() {
        val kitsu = TitleLink("series", "kitsu:1", "Cowboy Bebop")
        assertEquals("https://usetortuga.app/t/series/kitsu%3A1?name=Cowboy%20Bebop", kitsu.toUrl())
        assertEquals("tortuga://detail?type=series&id=kitsu%3A1", kitsu.appUrl())
        assertEquals(kitsu, TitleLink.parse(kitsu.toUrl()))

        // A custom prefix may carry any character, a slash, a query's or a fragment's among them.
        val custom = TitleLink("tv", "my addon/ch 1?x=1&y#z")
        assertEquals("https://usetortuga.app/t/tv/my%20addon%2Fch%201%3Fx%3D1%26y%23z", custom.toUrl())
        assertEquals(custom, TitleLink.parse(custom.toUrl()))
        assertEquals(custom, TitleLink.parse(custom.appUrl()))
    }

    @Test
    fun theNameIsEncodedSoChatAppsKeepTheWholeLink() {
        val link = TitleLink("movie", "tt0113277", "Heat (1995) — Mann's")
        assertEquals("https://usetortuga.app/t/movie/tt0113277?name=Heat%20%281995%29%20%E2%80%94%20Mann%27s", link.toUrl())
        assertEquals(link, TitleLink.parse(link.toUrl()))
    }

    @Test
    fun theAppOpensEitherFormAsTheTitlesDetail() {
        val detail = IncomingLink.Stremio(DeepLink.Detail("series", "kitsu:1"))
        assertEquals(detail, IncomingLink.parse("https://usetortuga.app/t/series/kitsu%3A1?name=Cowboy%20Bebop"))
        assertEquals(detail, IncomingLink.parse(" HTTPS://www.UseTortuga.app/t/series/kitsu:1/#top "))
        assertEquals(detail, IncomingLink.parse("tortuga://detail?type=series&id=kitsu%3A1"))
        // With a profile it is a TV home screen item's link, which opens in that profile only.
        assertEquals(
            IncomingLink.HomeScreen(HomeScreenLink.Detail("p1", "series", "kitsu:1")),
            IncomingLink.parse("tortuga://detail?profile=p1&type=series&id=kitsu%3A1"),
        )
    }

    @Test
    fun anythingElseIsNoTitleLink() {
        listOf(
            "https://usetortuga.app/",
            "https://usetortuga.app/t/movie",
            "https://usetortuga.app/t/movie/",
            "https://usetortuga.app/t//tt1",
            "https://usetortuga.app/t/movie/tt1/extra",
            "https://usetortuga.app/privacy/movie/tt1",
            "https://usetortuga.app.example/t/movie/tt1",
            "https://example.com/t/movie/tt1",
            "ftp://usetortuga.app/t/movie/tt1",
            "tortuga://detail?type=movie",
            "tortuga://detail?id=tt1",
            "tortuga://detail?profile=p1&type=movie&id=tt1",
            "tortuga://play?type=movie&id=tt1",
        ).forEach { assertNull(TitleLink.parse(it), it) }
    }
}
