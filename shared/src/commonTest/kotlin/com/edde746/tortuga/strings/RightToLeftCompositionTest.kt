package com.edde746.tortuga.strings

import com.edde746.tortuga.app.INFUSE
import com.edde746.tortuga.app.PlayerAppsWorld
import com.edde746.tortuga.downloads.SHOW
import com.edde746.tortuga.downloads.cdnChoice
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.model.ManifestCatalog
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.SERIES
import com.edde746.tortuga.playback.session.episodeLine
import com.edde746.tortuga.playback.session.json
import com.edde746.tortuga.presentation.board.catalogRowTitle
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.navigation.Route
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * App strings that hold text the app didn't write, in Arabic: each piece of content sits whole in an isolate of its
 * own, and the line's first strong character outside the isolates (UAX #9 rule P2: what gives a paragraph its
 * direction) is right to left, so the app's words, numbers and punctuation read in Arabic order around the content.
 */
class RightToLeftCompositionTest {
    @BeforeTest
    fun arabic() {
        InterfaceLocale.setSystemLanguages(listOf("ar"))
    }

    @AfterTest
    fun reset() {
        InterfaceLocale.setChosen(null)
        InterfaceLocale.setSystemLanguages(listOf("en"))
    }

    @Test
    fun detailsNumberedEpisodeTitleAndCastLine() = runTest {
        val w = PlayerAppsWorld(this)
        w.routes["meta.example"] = { request ->
            if (request.url.encodedPath != "/meta/series/${SERIES.id}.json") {
                respond("", HttpStatusCode.NotFound)
            } else {
                json(
                    """{"meta":{"id":"${SERIES.id}","type":"series","name":"${SERIES.name}","links":[
                        {"name":"Jared Harris","category":"Cast","url":"stremio:///search?search=Jared%20Harris"},
                        {"name":"Jessie Buckley","category":"Cast","url":"stremio:///search?search=Jessie%20Buckley"}
                    ],"videos":[
                        {"id":"tt1:1:1","title":"1:23:45","season":1,"episode":1},
                        {"id":"tt1:1:2","title":"Please Remain Calm","season":1,"episode":2}
                    ]}}""",
                )
            }
        }
        w.data.addons.install(
            w.profileId,
            "https://meta.example/manifest.json",
            """{"id":"org.example.meta","version":"1.0.0","name":"Meta","types":["series"],"resources":["meta"],"idPrefixes":["tt"]}""",
        )
        val app = w.open()
        val detail = app.detailStore(Route.Detail("series", SERIES.id))
        w.settle()
        val state = detail.state.value
        assertReadsRightToLeftAround(state.episodes.single { it.videoId == "tt1:1:2" }.numberedTitle, "Please Remain Calm")
        assertReadsRightToLeftAround(assertNotNull((state.body as? DetailBody.Page)?.header?.castLine), "Jared Harris", "Jessie Buckley")
        app.close()
    }

    @Test
    fun theEpisodeLine() {
        val line = Video(id = "tt1:1:2", title = "Please Remain Calm", seriesInfo = SeriesInfo(1, 2)).episodeLine()
        assertReadsRightToLeftAround(assertNotNull(line), "Please Remain Calm")
    }

    @Test
    fun theTitleHandedToAPlayerApp() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        val app = w.open()
        app.playback.play(w.episode(), INFUSE.id)
        w.settle()
        assertReadsRightToLeftAround(w.launcher.media.title, SERIES.name)
        app.close()
    }

    @Test
    fun theDownloadNotificationsTitle() = downloadsTest(fake = true) { h ->
        h.serveCdn(lcgBytes(100_000, seed = 1))
        val manager = h.install()
        manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1-1.mkv"))
        h.settle()
        assertReadsRightToLeftAround(h.fakeTransport.started.single().title, SHOW.name)
    }

    @Test
    fun aRowTitleAndATypeTheAppDoesntKnow() {
        assertReadsRightToLeftAround(catalogRowTitle(ManifestCatalog(id = "top", type = "movie", name = "Popular")), "Popular")
        assertReadsRightToLeftAround(catalogRowTitle(ManifestCatalog(id = "top", type = "anime", name = "Popular")), "Popular", "Anime")
    }

    /** [text] holds [contents], in order, each whole in an isolate of its own, and reads right to left (rule P2). */
    private fun assertReadsRightToLeftAround(text: String, vararg contents: String) {
        val shown = text.map { if (it in '\u200E'..'\u200F' || it in '\u2066'..'\u2069') "\\u" + it.code.toString(16).uppercase() else it.toString() }.joinToString("")
        assertEquals(contents.toList(), isolatedRuns(text), "the isolates of \"$shown\"")
        assertTrue(readsRightToLeft(text), "\"$shown\" reads right to left: its first strong character outside isolates is R or AL")
    }

    /** What [text]'s outermost isolates (U+2066, U+2067 or U+2068 … U+2069) hold. */
    private fun isolatedRuns(text: String): List<String> {
        val runs = mutableListOf<String>()
        var depth = 0
        var start = 0
        text.forEachIndexed { i, c ->
            when {
                c in '\u2066'..'\u2068' -> {
                    if (depth == 0) start = i + 1
                    depth++
                }
                c == '\u2069' && depth > 0 -> {
                    depth--
                    if (depth == 0) runs += text.substring(start, i)
                }
            }
        }
        return runs
    }

    /**
     * UAX #9 rule P2: the paragraph's first strong character, skipping isolates and what they hold (to the end, for one
     * left open), is right to left; false for a left-to-right one or none.
     */
    private fun readsRightToLeft(text: String): Boolean {
        var depth = 0
        for (c in text) {
            when {
                c in '\u2066'..'\u2068' -> depth++
                c == '\u2069' -> if (depth > 0) depth--
                depth > 0 -> {}
                c == '\u200F' || c.isRightToLeftLetter() -> return true
                c == '\u200E' || c.isLetter() -> return false
            }
        }
        return false
    }

    /** A Hebrew or Arabic letter (bidi classes R and AL): the right-to-left scripts of these strings. */
    private fun Char.isRightToLeftLetter() =
        isLetter() && (this in '\u0590'..'\u08FF' || this in '\uFB1D'..'\uFDFF' || this in '\uFE70'..'\uFEFF')
}
