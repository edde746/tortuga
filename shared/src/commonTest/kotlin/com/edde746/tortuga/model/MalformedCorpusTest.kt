package com.edde746.tortuga.model

import com.edde746.tortuga.fixtures.FixtureCorpus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hand-written malformed corpus (`fixtures/malformed`): what survives, what is dropped, what fails. */
class MalformedCorpusTest {
    private fun decode(name: String) = AddonResponse.decode(FixtureCorpus.text("malformed/$name"))

    @Test
    fun malformedCatalogItemsAreDroppedAndCounted() {
        val response = decode("catalog-mixed.json")
        val items = (response.content as ResourceContent.Metas).items
        assertEquals(listOf("tt0000001", "tt0000005", "tt0000007"), items.map { it.id })
        // Four items (no id, numeric type, a string, null) and one link with an invalid URL.
        assertEquals(5, response.dropped)

        val lenient = items[1]
        assertNull(lenient.poster)
        assertNull(lenient.background)
        assertNull(lenient.released)
        assertEquals(PosterShape.POSTER, lenient.posterShape)
        assertEquals("2001", lenient.releaseInfo)
        assertEquals("95.5", lenient.runtime)
        assertEquals(listOf("7.1"), lenient.links.map { it.name })

        val nullName = items[2]
        assertEquals("", nullName.name)
        assertEquals(PosterShape.LANDSCAPE, nullName.posterShape)
        assertEquals("2001-09-09T01:46:40Z", nullName.released.toString())
        // A string cacheMaxAge and a negative staleError are ignored; the valid hint stays.
        assertEquals(CacheHints(cacheMaxAge = null, staleRevalidate = 600, staleError = null), response.cache)
    }

    @Test
    fun streamSourcesFollowStremioCoresVariantOrder() {
        val response = decode("streams-mixed.json")
        val streams = (response.content as ResourceContent.Streams).items
        // Dropped: no source, a numeric bingeGroup; plus a subtitle without url and a null subtitle.
        assertEquals(4, response.dropped)
        val sources = streams.map { it.source }
        assertEquals(
            listOf(
                StreamSource.Url("https://cdn.example.com/video.mkv"),
                StreamSource.PeerToPeer("0123456789abcdef0123456789abcdef01234567", 2),
                StreamSource.YouTube("dQw4w9WgXcQ"),
                StreamSource.External(externalUrl = "stremio:///detail/series/tt7366338/tt7366338%3A1%3A1"),
                StreamSource.Archive(
                    ArchiveFormat.RAR,
                    listOf(ArchiveUrl("https://files.example.com/a.part1.rar", 1048576), ArchiveUrl("https://files.example.com/a.part2.rar")),
                ),
                StreamSource.Nntp(nzbUrl = "https://files.example.com/a.index", servers = listOf("nntps://news.example.com:563/4")),
                StreamSource.Url("https://cdn.example.com/c.mkv"),
                StreamSource.PlayerFrame("https://player.example.com/embed/1"),
                StreamSource.External(androidTvUrl = "https://tv.example.com/x"),
                StreamSource.Url("rtmp://live.example.com/app/stream"),
            ),
            sources,
        )
        assertEquals("Legacy title\nsecond line", streams[0].description)
        assertEquals("Description wins", streams[2].description)

        val hinted = streams[6]
        assertEquals(listOf("1"), hinted.subtitles.map { it.id })
        assertEquals(
            StreamBehaviorHints(
                notWebReady = false,
                bingeGroup = "example|1080p",
                proxyHeaders = StreamProxyHeaders(request = mapOf("User-Agent" to "Tortuga")),
                filename = "c.mkv",
                videoHash = "8e245d9679d31e12",
                videoSize = 1351041024,
                other = mapOf("custom" to buildJsonObject { put("x", 1) }),
            ),
            hinted.behaviorHints,
        )
    }

    @Test
    fun playabilityFollowsPlanSection7() {
        val streams = (decode("streams-mixed.json").content as ResourceContent.Streams).items
        assertEquals(
            listOf(
                StreamPlayability.Playable("https://cdn.example.com/video.mkv"),
                // The synthetic infoHash stream: the app plays HTTP(S) streams only and lists this one as unsupported.
                StreamPlayability.Unsupported(UnsupportedSource.PEER_TO_PEER),
                StreamPlayability.YouTube("dQw4w9WgXcQ"),
                StreamPlayability.External("stremio:///detail/series/tt7366338/tt7366338%3A1%3A1"),
                StreamPlayability.Unsupported(UnsupportedSource.ARCHIVE),
                StreamPlayability.Unsupported(UnsupportedSource.NNTP),
                StreamPlayability.Playable("https://cdn.example.com/c.mkv"),
                StreamPlayability.External("https://player.example.com/embed/1"),
                StreamPlayability.Unsupported(UnsupportedSource.OTHER),
                StreamPlayability.Unsupported(UnsupportedSource.OTHER),
            ),
            streams.map { it.playability },
        )
        val external = streams[3].playability as StreamPlayability.External
        assertEquals(DeepLink.Detail("series", "tt7366338", "tt7366338:1:1"), external.deepLink)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", (streams[2].playability as StreamPlayability.YouTube).watchUrl)
    }

    @Test
    fun videosAreDedupedSortedAndLenient() {
        val response = decode("meta-videos.json")
        val meta = (response.content as ResourceContent.Meta).item
        // Dropped: the video without id, and one malformed stream of "extra-dated".
        assertEquals(2, response.dropped)
        assertEquals(
            listOf("tt9000000:1:1", "tt9000000:1:2", "tt9000000:2:1", "tt9000000:0:1", "extra-dated", "extra-late", "extra-early"),
            meta.videos.map { it.id },
        )
        val first = meta.videos[0]
        assertEquals("First (legacy name)", first.title)
        assertEquals(SeriesInfo(1, 1), first.seriesInfo)
        assertNull(meta.videos[2].released)
        assertNull(meta.videos.first { it.id == "extra-late" }.seriesInfo)
        assertEquals(listOf(StreamSource.YouTube("abc123")), meta.videos.first { it.id == "extra-early" }.streams.map { it.source })
        assertEquals("2019-12-31T22:00:00Z", meta.videos.first { it.id == "extra-dated" }.released.toString())
        assertEquals(listOf(1, 2, 0), meta.seasons)
        assertEquals(listOf("tt9000000:1:1", "tt9000000:1:2"), meta.videosInSeason(1).map { it.id })
    }

    @Test
    fun invalidEnvelopesFail() {
        for (name in listOf("not-json.txt", "array-root.json", "no-known-key.json", "two-keys.json")) {
            assertFailsWith<SerializationException>(name) { decode(name) }
        }
    }

    @Test
    fun aNullMetaOrOneWithoutAnIdIsNoMeta() {
        assertEquals(ResourceContent.NoMeta, decode("meta-null.json").content)
        assertEquals(ResourceContent.NoMeta, AddonResponse.decode("""{"meta":{}}""").content)
        assertFailsWith<SerializationException>("a meta with an id is read, and a malformed one fails") {
            AddonResponse.decode("""{"meta":{"id":"tt1"}}""")
        }
    }

    @Test
    fun nullListIsEmpty() {
        val response = decode("metas-null.json")
        assertEquals(ResourceContent.Metas(emptyList()), response.content)
        assertEquals(60L, response.cache.cacheMaxAge)
    }

    @Test
    fun manifestDecodesLeniently() {
        val m = Manifest.decode(FixtureCorpus.text("malformed/manifest-lenient.json"))
        assertNull(m.logo)
        assertNull(m.background)
        assertNull(m.idPrefixes)
        assertEquals(
            listOf(
                ManifestResource.Short("catalog"),
                ManifestResource.Full("meta", listOf("movie"), listOf("tt")),
                ManifestResource.Full("stream", null, emptyList()),
                ManifestResource.Full("subtitles", listOf("series"), null),
            ),
            m.resources,
        )
        assertEquals(listOf("top" to "movie", "legacy" to "series", "fallback" to "movie", "required-search" to "movie"), m.catalogs.map { it.id to it.type })
        // Duplicate extras keep the first; `skip` is normalized to optional.
        assertEquals(listOf(ExtraProp("genre", options = listOf("Drama")), ExtraProp.SKIP), m.catalogs[0].extra)
        // Legacy extraSupported/extraRequired: supported names in order, required only if supported.
        assertEquals(listOf(ExtraProp("search"), ExtraProp("genre", isRequired = true)), m.catalogs[1].extra)
        // An invalid full `extra` falls back to the legacy fields.
        assertEquals(listOf(ExtraProp("search")), m.catalogs[2].extra)
        assertTrue(m.behaviorHints.configurable)
        assertEquals(false, m.behaviorHints.adult)
    }

    @Test
    fun manifestRequiresSemverAndTypes() {
        assertFailsWith<SerializationException> { Manifest.decode(FixtureCorpus.text("malformed/manifest-bad-version.json")) }
        assertFailsWith<SerializationException> { Manifest.decode(FixtureCorpus.text("malformed/manifest-missing-types.json")) }
    }

    @Test
    fun numberAsStringRendersLikeRust() {
        fun releaseInfo(json: String) =
            (AddonResponse.decode("""{"metas":[{"id":"x","type":"movie","releaseInfo":$json}]}""").content as ResourceContent.Metas)
                .items.single().releaseInfo
        assertEquals("1994", releaseInfo("1994"))
        assertEquals("1994", releaseInfo("1994.0"))
        assertEquals("7.5", releaseInfo("7.50"))
        assertEquals("-3", releaseInfo("-3"))
        assertEquals("2011-2019", releaseInfo(JsonPrimitive("2011-2019").toString()))
    }

    @Test
    fun httpUrlsAreCanonicalLikeUrlCrate() {
        val body = """{"streams":[{"url":"HTTPS://CDN.Example.com:443/My Movie.mkv?t=a b","subtitles":[{"id":"1","lang":"eng","url":"https://Subs.example.com/1 .srt"}]}]}"""
        val stream = (AddonResponse.decode(body).content as ResourceContent.Streams).items.single()
        assertEquals(StreamSource.Url("https://cdn.example.com/My%20Movie.mkv?t=a%20b"), stream.source)
        assertEquals("https://subs.example.com/1%20.srt", stream.subtitles.single().url)
    }

    @Test
    fun fileHintsKeepOnlyUsableValues() {
        fun hints(json: String) =
            (AddonResponse.decode("""{"streams":[{"url":"https://cdn.example/f.mkv","behaviorHints":$json}]}""").content as ResourceContent.Streams)
                .items.single().behaviorHints
        // A hash of another form would stop the resolver computing the real one; a 0 size would beat the server's.
        assertEquals(
            StreamBehaviorHints(),
            hints("""{"videoHash":"not-a-hash-value","videoSize":0,"filename":"  "}"""),
        )
        assertNull(hints("""{"videoHash":"8e245d9679d31e1"}""").videoHash, "15 digits")
        assertNull(hints("""{"videoHash":"8e245d9679d31e1g"}""").videoHash, "not hex")
        assertEquals("8e245d9679d31e12", hints("""{"videoHash":" 8E245D9679D31E12 "}""").videoHash)
        assertEquals(1L, hints("""{"videoSize":1}""").videoSize)
    }
}
