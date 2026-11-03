package com.edde746.tortuga.playback.subtitles

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.Subtitles
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.playback.resolve.FakeHttp
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleResolverTest {
    private fun subtitleAddon(name: String) = Descriptor(
        Manifest.decode("""{"id":"org.example.$name","version":"1.0.0","name":"$name","types":["series"],"resources":["subtitles"]}"""),
        "https://$name.example.com/manifest.json".transportUrl(),
    )

    private val request = SubtitleRequest(
        type = "series",
        videoId = "tt7366338:1:1",
        videoHash = "8e245d9679d31e12",
        videoSize = 1_234_567_890,
        filename = "Example.Show.S01E01.1080p.mkv",
        streamSubtitles = listOf(Subtitles(id = "own", lang = "eng", url = "https://cdn.example/own.srt")),
        streamAddonName = "Streams",
    )

    private fun TestScope.opensubs(body: String): Pair<FakeHttp, SubtitleResolver> {
        val http = FakeHttp(this) { request ->
            when (request.url.host) {
                "opensubs.example.com" -> respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond("", HttpStatusCode.InternalServerError)
            }
        }
        val transport = AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(testScheduler))
        return http to SubtitleResolver(AddonAggregator(transport), maxPerLanguage = 3)
    }

    @Test
    fun mergesTheStreamsOwnWithEveryAddonsAndAddsTheBestPerLanguage() = runTest {
        val (http, resolver) = opensubs(
            """{"subtitles":[
                {"id":"1","url":"https://subs.example/pob.srt","lang":"pob"},
                {"id":"2","url":"https://cdn.example/own.srt","lang":"eng"},
                {"id":"3","url":"https://subs.example/e3.srt","lang":"eng"},
                {"id":"4","url":"https://subs.example/e4.srt","lang":"eng"},
                {"id":"5","url":"https://subs.example/e5.srt","lang":"eng"},
                {"id":"6","url":"https://subs.example/fre.srt","lang":"fre"},
                {"id":"8","url":"https://subs.example/pt.srt","lang":"por"}
            ]}""",
        )
        val found = resolver.resolve(request, listOf(subtitleAddon("broken"), subtitleAddon("opensubs")), wanted = listOf("pt-BR", "en"))

        assertEquals(
            listOf(
                // Brazilian first (the exact tag), then plain Portuguese for the same wish.
                ExternalSubtitle("https://subs.example/pob.srt", "pt-BR", "opensubs"),
                ExternalSubtitle("https://subs.example/pt.srt", "pt", "opensubs"),
                // The stream's own first; the addon's copy of it is dropped; three in all.
                ExternalSubtitle("https://cdn.example/own.srt", "en", "Streams"),
                ExternalSubtitle("https://subs.example/e3.srt", "en", "opensubs 1"),
                ExternalSubtitle("https://subs.example/e4.srt", "en", "opensubs 2"),
            ),
            found.added,
        )
        // Every other one is reachable, numbered as if all were added.
        assertEquals(
            listOf(
                ExternalSubtitle("https://subs.example/e5.srt", "en", "opensubs 3"),
                ExternalSubtitle("https://subs.example/fre.srt", "fr", "opensubs"),
            ),
            found.more,
        )

        val query = http.requests.single { it.url.host == "opensubs.example.com" }.url.encodedPath
        assertEquals(
            "/subtitles/series/tt7366338%3A1%3A1/filename=Example.Show.S01E01.1080p.mkv&videoSize=1234567890&videoHash=8e245d9679d31e12.json",
            query,
        )
    }

    @Test
    fun aLanguageWrittenAsTextIsKeptAndNamedByItsText() = runTest {
        val (_, resolver) = opensubs(
            """{"subtitles":[
                {"id":"1","url":"https://subs.example/text.srt","lang":"Português (Brasil)"},
                {"id":"2","url":"https://subs.example/labelled.srt","lang":"English","label":"English SDH"},
                {"id":"3","url":"https://subs.example/und.srt","lang":"und"}
            ]}""",
        )
        val found = resolver.resolve(request.copy(streamSubtitles = emptyList()), listOf(subtitleAddon("opensubs")), wanted = listOf("en"))

        assertEquals(
            listOf(
                ExternalSubtitle("https://subs.example/text.srt", null, "Português (Brasil)"),
                ExternalSubtitle("https://subs.example/labelled.srt", null, "English SDH"),
                // A code that names no language has no text to show: the addon's name.
                ExternalSubtitle("https://subs.example/und.srt", null, "opensubs"),
            ),
            found.more,
        )
    }

    @Test
    fun theStreamsOwnSubtitlesAreAddedInEveryLanguage() = runTest {
        val (_, resolver) = opensubs("""{"subtitles":[{"id":"1","url":"https://subs.example/es.srt","lang":"spa"}]}""")
        val own = listOf(
            Subtitles(id = "en1", lang = "eng", url = "https://cdn.example/en1.srt"),
            Subtitles(id = "en2", lang = "eng", url = "https://cdn.example/en2.srt"),
            Subtitles(id = "en3", lang = "eng", url = "https://cdn.example/en3.srt"),
            Subtitles(id = "en4", lang = "eng", url = "https://cdn.example/en4.srt"),
            Subtitles(id = "ja", lang = "jpn", url = "https://cdn.example/ja.srt"),
            Subtitles(id = "text", lang = "Klingon", url = "https://cdn.example/tlh.srt"),
        )
        val found = resolver.resolve(request.copy(streamSubtitles = own), listOf(subtitleAddon("opensubs")), wanted = listOf("en"))

        assertEquals(
            listOf("en1", "en2", "en3", "en4", "ja", "tlh").map { "https://cdn.example/$it.srt" },
            found.added.map { it.url },
        )
        assertEquals(listOf("https://subs.example/es.srt"), found.more.map { it.url })
    }

    @Test
    fun withoutAWantedLanguageTheAddonsSubtitlesAllWait() = runTest {
        val (_, resolver) = opensubs("""{"subtitles":[{"id":"1","url":"https://subs.example/es.srt","lang":"spa"}]}""")
        val found = resolver.resolve(request, listOf(subtitleAddon("opensubs")), wanted = emptyList())
        assertEquals(listOf("https://cdn.example/own.srt"), found.added.map { it.url })
        assertEquals(listOf("https://subs.example/es.srt"), found.more.map { it.url })
    }

    @Test
    fun toStringLeavesTheUrlOut() {
        val text = ExternalSubtitle("https://subs.example/key=abc/file.srt", "en", "Subs").toString()
        assertTrue("subs.example" !in text && "abc" !in text, text)
    }
}
