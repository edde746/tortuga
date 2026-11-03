package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.StreamAutoPicker
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.profiles.ParentCheck
import com.edde746.tortuga.trackers.ids.AnimeEntryTitles
import com.edde746.tortuga.trackers.ids.AnimeListsIndex
import com.edde746.tortuga.trackers.ids.AnimeMappingSource
import com.edde746.tortuga.trackers.ids.FixtureMappings
import com.edde746.tortuga.trackers.ids.FribbIndex
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Detail opened on a MyAnimeList or AniList entry (the trackers' anime rows), over the mapping excerpts in
 * `fixtures/trackers/`: a catalog addon that knows IMDb titles, and an anime addon that knows the entries' own ids
 * (as Anime Kitsu does: `kitsu`, `mal`, `anilist` and `anidb`).
 */
class DetailStoreAnimeEntryTest {
    private val catalog = metaAddon("catalog", types = """["movie","series"]""", prefixes = """["tt"]""")
    private val anime = metaAddon("anime", types = """["anime","movie","series"]""", prefixes = """["kitsu","mal","anilist","anidb"]""")

    private fun metaAddon(name: String, types: String, prefixes: String) = Descriptor(
        Manifest.decode("""{"id":"org.example.$name","version":"1.0.0","name":"$name","types":$types,"resources":["meta"],"idPrefixes":$prefixes}"""),
        "https://$name.example.com/manifest.json".transportUrl(),
    )

    private class Host : DetailHost {
        val shown = mutableListOf<String>()
        val previews = mutableListOf<MetaItemPreview?>()
        val streams = mutableListOf<String>()

        override fun openStreams(meta: MetaItem, videoId: String, notice: String?, fromStart: Boolean) {
            streams += videoId
        }

        override fun openDownloadStreams(meta: MetaItem, videoId: String) = Unit

        override fun openKeepSynced() = Unit

        override fun play(request: PlayRequest) = Unit

        override fun openUrl(url: String) = Unit

        override fun openDeepLink(link: DeepLink) = Unit

        override fun showTitle(entryId: String, type: String, id: String, shownVideoId: String?, preview: MetaItemPreview?) {
            shown += "$entryId → $type/$id/$shownVideoId"
            previews += preview
        }

        override fun openMeta(type: String, id: String, preview: MetaItemPreview?) = Unit

        override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) = Unit

        override fun askParent(check: ParentCheck, onUnlocked: suspend () -> Unit) = Unit
    }

    private class Fixture(
        private val scope: TestScope,
        private val addons: List<Descriptor>,
        animeAddonKnows: Set<String>,
        private val mappings: AnimeMappingSource,
    ) {
        val data = LocalData(inMemoryDatabase(), { NOW }, StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        val http = FakeHttp(scope) { request ->
            val path = request.url.encodedPath.decodeURLPart()
            when (request.url.host) {
                "catalog.example.com" -> CATALOG[path]?.let { json(it) } ?: respond("", HttpStatusCode.NotFound)
                "anime.example.com" -> ANIME[path]?.takeIf { path in animeAddonKnows }?.let { json(it) } ?: respond("", HttpStatusCode.NotFound)
                else -> respond("", HttpStatusCode.InternalServerError)
            }
        }
        val host = Host()
        val aggregator = AddonAggregator(AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(scope.testScheduler)))

        fun store(type: String, id: String, videoId: String? = null, shownVideoId: String? = null, preview: MetaItemPreview? = null) = DetailStore(
            profileId = "p",
            type = type,
            id = id,
            videoId = videoId,
            shownVideoId = shownVideoId,
            aggregator = aggregator,
            installedAddons = { addons },
            library = data.library,
            streamHistory = data.streamHistory,
            settings = data.settings.observe("p"),
            autoPicker = StreamAutoPicker(aggregator, data.streamHistory),
            host = host,
            clock = object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(NOW)
            },
            parentScope = scope.backgroundScope,
            network = MutableStateFlow(NetworkState.ONLINE),
            animeTitles = AnimeEntryTitles(mappings),
            preview = preview,
        )
    }

    private fun TestScope.fixture(vararg addons: Descriptor, animeAddonKnows: Set<String> = ANIME.keys, mappings: AnimeMappingSource = FixtureMappings()) =
        Fixture(this, addons.toList(), animeAddonKnows, mappings)

    /** What [id] opens as, with the catalog addon only, in place of its own screen. */
    private fun TestScope.opened(type: String, id: String, videoId: String? = null): String? {
        val f = fixture(catalog)
        val store = f.store(type, id, videoId)
        runCurrent()
        val state = store.state.value
        assertTrue(state.body == DetailBody.Loading || state.body is DetailBody.Preview, "$id: still loading while its title takes this screen's place")
                return f.host.shown.singleOrNull()?.removePrefix("$id → ")
    }

    @Test
    fun anEntryOpensAsTheCatalogTitleOnItsSeason() = runTest {
        // My Hero Academia, its first season and its sequel, from MyAnimeList and AniList.
        assertEquals("series/tt5626028/tt5626028:1:1", opened("series", "mal:31964"))
        assertEquals("series/tt5626028/tt5626028:2:1", opened("series", "mal:33486"))
        assertEquals("series/tt5626028/tt5626028:2:1", opened("series", "anilist:21856"))
        // Black Lagoon: The Second Barrage starts at episode 13 of the show's season 1; its episode 3 is episode 15.
        assertEquals("series/tt0962826/tt0962826:1:13", opened("series", "mal:1519"))
        assertEquals("series/tt0962826/tt0962826:1:15", opened("series", "anilist:1519", videoId = "anilist:1519:3"))
        // Your Name.
        assertEquals("movie/tt5311514/null", opened("movie", "mal:32281"))
    }

    @Test
    fun anEpisodeTheTitleDoesntListOpensTheTitleAlone() = runTest {
        // The catalog's My Hero Academia lists no third season yet.
        assertEquals("series/tt5626028/null", opened("series", "anilist:100166"))
    }

    @Test
    fun anAnimeAddonThatHasTheEntryKeepsIt() = runTest {
        val f = fixture(anime, catalog)
        val sequel = f.store("series", "mal:33486")
        val unmapped = f.store("series", "mal:61603")
        runCurrent()
        assertEquals("Boku no Hero Academia 2nd Season", (sequel.state.value.body as? DetailBody.Page)?.header?.title)
        assertEquals("Ao Ashi 2nd Season", (unmapped.state.value.body as? DetailBody.Page)?.header?.title)
        assertEquals(emptyList(), f.host.shown)
    }

    @Test
    fun anEntryTheAnimeAddonDoesntHaveOpensAsTheCatalogTitle() = runTest {
        val f = fixture(anime, catalog, animeAddonKnows = emptySet())
        f.store("series", "mal:33486")
        runCurrent()
        assertEquals(listOf("mal:33486 → series/tt5626028/tt5626028:2:1"), f.host.shown)
    }

    @Test
    fun anEntryNoAddonHasSaysToInstallAnAnimeAddon() = runTest {
        val f = fixture(catalog)
        // Aoashi 2nd Season: the excerpt has no row for it (the real Fribb row names no IMDb or TMDB title).
        val unmapped = f.store("series", "mal:61603")
        // A mapped entry with no addon for its titles either.
        val alone = fixture().store("series", "mal:33486")
        runCurrent()
        for (store in listOf(unmapped, alone)) {
            val state = store.state.value
            assertEquals("None of the installed addons has details for this anime. Install an anime addon to open it.", (state.body as? DetailBody.Failed)?.text)
        }
        assertEquals(emptyList(), f.host.shown)
    }

    @Test
    fun theTitleOpensOnTheEntrysSeasonWithNothingOpen() = runTest {
        val f = fixture(catalog)
        // My Hero Academia 2: season 2, at the top of its episodes.
        val sequel = f.store("series", "tt5626028", shownVideoId = "tt5626028:2:1")
        // Black Lagoon: The Second Barrage: season 1, the list brought to its first episode, 13.
        val cour = f.store("series", "tt0962826", shownVideoId = "tt0962826:1:13")
        runCurrent()
        assertEquals(listOf(2), sequel.state.value.seasons.filter { it.selected }.map { it.season })
        assertNull(sequel.state.value.shownEpisode)
        assertEquals(listOf(1), cour.state.value.seasons.filter { it.selected }.map { it.season })
        assertEquals("tt0962826:1:13", cour.state.value.shownEpisode)
        assertTrue(cour.state.value.shownEpisodeFromEntry, "the one-column page brings its list there too")
        assertEquals(emptyList(), f.host.streams)
        // Once shown, the list isn't brought there again, until its season is chosen again, beside the details alone.
        cour.episodeShown()
        assertNull(cour.state.value.shownEpisode)
        cour.selectSeason(1)
        assertEquals("tt0962826:1:13", cour.state.value.shownEpisode)
        assertFalse(cour.state.value.shownEpisodeFromEntry)
        // Another season shows from its first episode.
        val again = f.store("series", "tt5626028", shownVideoId = "tt5626028:2:5")
        runCurrent()
        assertEquals("tt5626028:2:5", again.state.value.shownEpisode)
        again.selectSeason(1)
        assertNull(again.state.value.shownEpisode)
    }

    @Test
    fun anEntryShowsItsCardUntilItsTitleIsFound() = runTest {
        // The anime datasets still downloading.
        val download = CompletableDeferred<Boolean>()
        val f = fixture(catalog, mappings = SlowMappings(download))
        val store = f.store("series", "mal:33486", preview = CARD)
        runCurrent()
        val early = store.state.value
        val header = (early.body as? DetailBody.Preview)?.header
        assertEquals("My Hero Academia Season 2", header?.title)
        assertEquals(CARD.poster, header?.poster)
        assertEquals("series", header?.type)
        assertTrue(early.hasEpisodes, "a series keeps its episodes' column")
        assertNull(early.primaryAction, "nothing to play before the title is known")
        download.complete(true)
        runCurrent()
        assertEquals(listOf("mal:33486 → series/tt5626028/tt5626028:2:1"), f.host.shown)
        assertEquals("My Hero Academia", f.host.previews.single()?.name, "the title shows from its own header while it loads")
    }

    @Test
    fun aLongWaitSaysWhatItWaitsFor() = runTest {
        val store = fixture(catalog, mappings = SlowMappings(CompletableDeferred())).store("series", "mal:33486", preview = CARD)
        advanceTimeBy(DetailStore.ENTRY_NOTE_AFTER - 1.milliseconds)
        runCurrent()
        assertNull(store.state.value.loadingNote)
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals("Looking this anime up in the anime lists…", store.state.value.loadingNote)
        assertTrue(store.state.value.body is DetailBody.Preview)
    }

    @Test
    fun datasetsThatDontLoadLeaveTheEntryToTheAddons() = runTest {
        val lists = "The anime lists didn't load, so this anime can't be matched to a title. Check your connection and try again."
        // The download failed: what an addon has of the entry shows; with none, the error says why.
        val failed = CompletableDeferred(false)
        val kept = fixture(anime, catalog, mappings = SlowMappings(failed)).store("series", "mal:33486")
        val missing = fixture(catalog, mappings = SlowMappings(failed)).store("series", "mal:33486")
        runCurrent()
        assertEquals("Boku no Hero Academia 2nd Season", (kept.state.value.body as? DetailBody.Page)?.header?.title)
        assertEquals(lists, (missing.state.value.body as? DetailBody.Failed)?.text)
        // The download never ends: the anime addon's answer shows at once, and the page stops waiting after a while.
        val stuck = CompletableDeferred<Boolean>()
        val keptStuck = fixture(anime, catalog, mappings = SlowMappings(stuck)).store("series", "mal:33486")
        val missingStuck = fixture(catalog, mappings = SlowMappings(stuck)).store("series", "mal:33486")
        runCurrent()
        assertEquals("Boku no Hero Academia 2nd Season", (keptStuck.state.value.body as? DetailBody.Page)?.header?.title)
        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals("Boku no Hero Academia 2nd Season", (keptStuck.state.value.body as? DetailBody.Page)?.header?.title)
        assertEquals(lists, (missingStuck.state.value.body as? DetailBody.Failed)?.text)
    }

    /** [FixtureMappings] whose Fribb dataset comes once [fribb] completes: true with it, false without it (a failed download). */
    private class SlowMappings(private val fribb: CompletableDeferred<Boolean>) : AnimeMappingSource {
        private val fixture = FixtureMappings()

        override suspend fun fribb(): FribbIndex? = if (fribb.await()) fixture.fribb() else null

        override suspend fun animeLists(): AnimeListsIndex? = fixture.animeLists()
    }

    private companion object {
        /** 2026-09-21. */
        const val NOW = 1_790_000_000_000L

        /** My Hero Academia 2's card on MyAnimeList's row: its name, poster and type, nothing else. */
        val CARD = MetaItemPreview(id = "mal:33486", type = "series", name = "My Hero Academia Season 2", poster = "https://cdn.example.com/mha2.jpg")

        val CATALOG = mapOf(
            "/meta/series/tt5626028.json" to series("tt5626028", "My Hero Academia", seasons = mapOf(1 to 13, 2 to 25)),
            "/meta/series/tt0962826.json" to series("tt0962826", "Black Lagoon", seasons = mapOf(1 to 24)),
            "/meta/movie/tt5311514.json" to """{"meta":{"id":"tt5311514","type":"movie","name":"Your Name."}}""",
        )

        val ANIME = mapOf(
            "/meta/series/mal:33486.json" to series("kitsu:12268", "Boku no Hero Academia 2nd Season", seasons = mapOf(1 to 25)),
            "/meta/series/mal:61603.json" to series("kitsu:49883", "Ao Ashi 2nd Season", seasons = mapOf(1 to 12)),
        )

        fun series(id: String, name: String, seasons: Map<Int, Int>): String {
            val videos = seasons.flatMap { (season, count) ->
                (1..count).map { """{"id":"$id:$season:$it","title":"Episode $it","season":$season,"episode":$it}""" }
            }
            return """{"meta":{"id":"$id","type":"series","name":"$name","videos":[${videos.joinToString(",")}]}}"""
        }
    }
}
