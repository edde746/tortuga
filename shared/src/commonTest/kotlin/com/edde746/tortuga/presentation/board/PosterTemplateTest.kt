package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.crash.CrashReports
import com.edde746.tortuga.log.Redactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PosterTemplateTest {
    private val key = "t0-free-rpdb"

    private fun template(text: String) = assertNotNull(PosterTemplate.of(text, kids = false))

    @Test
    fun eachPlaceholderBecomesTheTitlesValue() {
        assertEquals(
            "https://posters.example/$key/imdb/tt0133093.jpg",
            template("https://posters.example/$key/imdb/{imdb_id}.jpg").url("movie", "tt0133093", null),
        )
        assertEquals(
            "https://posters.example/$key/tmdb/movie-603.jpg",
            template("https://posters.example/$key/tmdb/{type}-{tmdb_id}.jpg").url("movie", "tmdb:603", null),
        )
        assertEquals(
            "https://posters.example/$key/tvdb/81189.jpg",
            template("https://posters.example/$key/tvdb/{tvdb_id}.jpg").url("series", "tvdb:81189", null),
        )
        assertEquals(
            "https://posters.example/series/tt0903747/tt0903747",
            template("https://posters.example/{type}/{imdb_id}/{imdb_id}").url("series", "tt0903747", null),
        )
    }

    @Test
    fun theImdbIdComesFromTheAddonWhenTheTitlesOwnIdIsAnother() {
        val posters = template("https://posters.example/{imdb_id}.jpg")
        assertEquals("https://posters.example/tt0388629.jpg", posters.url("series", "kitsu:12", "tt0388629"))
        // An imdb_id that isn't an IMDb title id names none.
        assertNull(posters.url("series", "kitsu:12", "not an id"))
    }

    @Test
    fun valuesAreUrlEncoded() {
        assertEquals(
            "https://posters.example/a%20b%2Fc%3Fd%26e/tt1.jpg",
            template("https://posters.example/{type}/{imdb_id}.jpg").url("a b/c?d&e", "tt1", null),
        )
    }

    @Test
    fun aTitleWithoutTheIdTheTemplateNamesKeepsTheAddonsPoster() {
        assertNull(template("https://posters.example/{imdb_id}.jpg").url("movie", "tmdb:603", null))
        assertNull(template("https://posters.example/{tmdb_id}.jpg").url("movie", "tt0133093", null))
        assertNull(template("https://posters.example/{tvdb_id}.jpg").url("series", "tt0903747", "tt0903747"))
        assertNull(template("https://posters.example/{imdb_id}.jpg").url("channel", "yt_id:UC1", null))
    }

    @Test
    fun anEmptyTemplateChangesNothing() {
        assertNull(PosterTemplate.of("", kids = false))
        assertNull(PosterTemplate.of("   ", kids = false))
    }

    @Test
    fun aTemplateThatIsNoHttpAddressIsIgnored() {
        assertNull(PosterTemplate.of("posters.example/{imdb_id}.jpg", kids = false))
        assertNull(PosterTemplate.of("file:///{imdb_id}.jpg", kids = false))
        assertNull(PosterTemplate.of("https:///{imdb_id}.jpg", kids = false))
    }

    @Test
    fun kidsProfilesIgnoreIt() {
        assertNull(PosterTemplate.of("https://posters.example/{imdb_id}.jpg", kids = true))
    }

    @Test
    fun logsAndCrashReportsNeverHoldTheKey() {
        val text = "https://posters.example/$key/imdb/{imdb_id}.jpg"
        val posters = template(text)
        val url = assertNotNull(posters.url("movie", "tt0133093", null))
        for (line in listOf("poster failed: $url", "template $text", "state ${AppStateLike(posters)}")) {
            assertFalse(key in Redactor.redact(line), line)
            assertFalse(key in CrashReports.scrub(line, home = null), line)
        }
        assertFalse(key in posters.toString())
    }

    private data class AppStateLike(val posterTemplate: PosterTemplate)
}
