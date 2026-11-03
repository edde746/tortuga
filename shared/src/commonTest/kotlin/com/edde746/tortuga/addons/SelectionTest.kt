package com.edde746.tortuga.addons

import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.transportUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Addon selection (stremio-core `Manifest::is_resource_supported`, `AggrRequest::plan`). */
class SelectionTest {
    private fun live(addon: String, url: String) =
        Descriptor(Manifest.decode(FixtureCorpus.text("live/$addon/manifest.json")), url.transportUrl())

    private val cinemeta = live("cinemeta", DefaultAddons.CINEMETA_URL)
    private val subtitles = live("opensubtitles", DefaultAddons.OPENSUBTITLES_URL)
    private val streams = Descriptor(
        Manifest.decode(FixtureCorpus.text("addon-lists/heavy/example-streams.json")),
        "https://streams.example/manifest.json".transportUrl(),
    )
    private val installed = listOf(cinemeta, subtitles, streams)

    private fun manifest(resources: String, types: String = """["movie","series"]""", idPrefixes: String = "null") = Descriptor(
        Manifest.decode("""{"id":"x","version":"1.0.0","name":"X","types":$types,"idPrefixes":$idPrefixes,"resources":$resources}"""),
        "https://example.com/manifest.json".transportUrl(),
    )

    private fun Descriptor.supports(resource: String, type: String, id: String) =
        manifest.isResourceSupported(ResourcePath(resource, type, id))

    @Test
    fun liveAddonsAreSelectedPerResourceInInstalledOrder() {
        fun names(request: AggrRequest) = request.plan(installed).map { it.addon.manifest.name }
        assertEquals(listOf("Cinemeta"), names(AggrRequest.meta("movie", "tt0111161")))
        assertEquals(listOf("Example Streams"), names(AggrRequest.streams("series", "tt7366338:1:1")))
        assertEquals(listOf("OpenSubtitles v3"), names(AggrRequest.subtitles("series", "tt7366338:1:1")))
        // Example Streams also takes kitsu ids; Cinemeta's meta only takes tt ids.
        assertEquals(listOf("Example Streams"), names(AggrRequest.streams("series", "kitsu:1:1")))
        assertEquals(emptyList(), names(AggrRequest.meta("series", "kitsu:1")))
        assertEquals(emptyList(), names(AggrRequest.streams("channel", "tt0111161")))
        // A full resource's own types win over the manifest's: Example Streams lists `other` globally, not for `stream`.
        assertEquals(emptyList(), names(AggrRequest.streams("other", "tt0111161")))
        // Order is the installed order.
        val other = manifest("""["stream"]""")
        val request = AggrRequest.streams("movie", "tt0111161")
        assertEquals(listOf(streams, other), request.plan(listOf(cinemeta, streams, other)).map { it.addon })
        assertEquals(listOf(other, streams), request.plan(listOf(other, subtitles, streams)).map { it.addon })
    }

    @Test
    fun boardLeavesOutCatalogsWithRequiredExtras() {
        val board = AggrRequest.board().plan(installed).map { it.request.path.type + "/" + it.request.path.id }
        // Cinemeta's `year` (genre required), `last-videos` and `calendar-videos` need extras.
        assertEquals(listOf("movie/top", "series/top", "movie/imdbRating", "series/imdbRating"), board)
        assertEquals(listOf("series/top", "series/imdbRating"), AggrRequest.board("series").plan(installed).map { it.request.path.type + "/" + it.request.path.id })
    }

    @Test
    fun searchUsesCatalogsWithTheSearchExtra() {
        val plan = AggrRequest.search("chernobyl").plan(installed)
        assertEquals(listOf("movie/top", "series/top"), plan.map { it.request.path.type + "/" + it.request.path.id })
        assertTrue(plan.all { it.request.path.extra == listOf(ExtraValue("search", "chernobyl")) })
    }

    @Test
    fun shortResourceUsesManifestTypesAndPrefixes() {
        val addon = manifest("""["stream"]""", idPrefixes = """["tt","kitsu:"]""")
        assertTrue(addon.supports("stream", "movie", "tt1"))
        assertTrue(addon.supports("stream", "series", "kitsu:1:2"))
        assertFalse(addon.supports("stream", "movie", "imdb:tt1"))
        assertFalse(addon.supports("stream", "channel", "tt1"))
        assertFalse(addon.supports("meta", "movie", "tt1"))
    }

    @Test
    fun fullResourceUsesOnlyItsOwnTypesAndPrefixes() {
        // Resource-level lists replace the manifest's, even when they are missing: no `types` supports no type.
        val addon = manifest(
            """[{"name":"stream","types":["movie"],"idPrefixes":["yt_id:"]},{"name":"meta"},{"name":"subtitles","types":["series"],"idPrefixes":[]}]""",
            idPrefixes = """["tt"]""",
        )
        assertTrue(addon.supports("stream", "movie", "yt_id:abc"))
        assertFalse(addon.supports("stream", "movie", "tt1"))
        assertFalse(addon.supports("stream", "series", "yt_id:abc"))
        assertFalse(addon.supports("meta", "movie", "tt1"))
        assertTrue(addon.supports("subtitles", "series", "anything"))
    }

    @Test
    fun missingOrEmptyPrefixesMatchEveryId() {
        assertTrue(manifest("""["meta"]""").supports("meta", "movie", "whatever"))
        assertTrue(manifest("""["meta"]""", idPrefixes = "[]").supports("meta", "movie", "whatever"))
        assertFalse(manifest("""["meta"]""", types = "[]").supports("meta", "movie", "tt1"))
    }

    @Test
    fun catalogSelectionChecksExtras() {
        val top = cinemeta.manifest.catalogs.first { it.id == "top" && it.type == "movie" }
        assertTrue(top.isExtraSupported(listOf(ExtraValue("genre", "Drama"), ExtraValue("skip", "49"))))
        assertFalse(top.isExtraSupported(listOf(ExtraValue("lastVideosIds", "tt1"))))
        val year = cinemeta.manifest.catalogs.first { it.id == "year" && it.type == "movie" }
        assertFalse(year.isExtraSupported(emptyList()))
        assertEquals(listOf(ExtraValue("genre", year.extraProp("genre")!!.options.first())), year.defaultRequiredExtra())
        assertTrue(cinemeta.supports("catalog", "movie", "top"))
        assertFalse(cinemeta.supports("catalog", "movie", "year"))
        assertTrue(cinemeta.manifest.isResourceSupported(ResourcePath("addon_catalog", "all", "community")))
    }
}
