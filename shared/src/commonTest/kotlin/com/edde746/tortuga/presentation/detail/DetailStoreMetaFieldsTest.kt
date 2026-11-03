package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.session.PlayPurpose
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.StreamAutoPicker
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.profiles.ParentCheck
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The Detail store over what a meta addon may send beyond Cinemeta's fields: default videos, live titles, carried streams, old names. */
class DetailStoreMetaFieldsTest {
    private fun metaAddon(name: String) = Descriptor(
        Manifest.decode(
            """{"id":"org.example.$name","version":"1.0.0","name":"$name","types":["channel","tv","movie"],"resources":["meta"],"idPrefixes":["ch","tv","tt"]}""",
        ),
        "https://$name.example.com/manifest.json".transportUrl(),
    )

    private val meta = metaAddon("meta")
    private val broken = metaAddon("broken")

    private class Host : DetailHost {
        val streams = mutableListOf<String>()
        val played = mutableListOf<PlayRequest>()
        val urls = mutableListOf<String>()
        val links = mutableListOf<DeepLink>()

        override fun openStreams(meta: MetaItem, videoId: String, notice: String?, fromStart: Boolean) {
            streams += "${meta.id}/$videoId"
        }

        override fun openDownloadStreams(meta: MetaItem, videoId: String) = Unit

        override fun openKeepSynced() = Unit

        override fun play(request: PlayRequest) {
            played += request
        }

        override fun openUrl(url: String) {
            urls += url
        }

        override fun openDeepLink(link: DeepLink) {
            links += link
        }

        override fun showTitle(entryId: String, type: String, id: String, shownVideoId: String?, preview: MetaItemPreview?) = Unit

        override fun openMeta(type: String, id: String, preview: MetaItemPreview?) = Unit

        override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) = Unit

        override fun askParent(check: ParentCheck, onUnlocked: suspend () -> Unit) = Unit
    }

    private class Fixture(private val scope: TestScope, private val addons: List<Descriptor>) {
        val data = LocalData(inMemoryDatabase(), { NOW }, StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        val http = FakeHttp(scope) { request ->
            val path = request.url.encodedPath
            when (request.url.host) {
                "meta.example.com" -> METAS.entries.firstOrNull { path.endsWith("/${it.key}.json") }?.let { json(it.value) }
                    ?: respond("", HttpStatusCode.NotFound)
                else -> respond("", HttpStatusCode.InternalServerError)
            }
        }
        val host = Host()
        val aggregator = AddonAggregator(AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(scope.testScheduler)))

        fun store(type: String, id: String, sharing: Boolean = true, videoAction: DetailVideoAction = DetailVideoAction.STREAMS) = DetailStore(
            profileId = "p",
            type = type,
            id = id,
            videoId = null,
            shownVideoId = null,
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
            sharing = sharing,
            videoAction = videoAction,
        )
    }

    private val PlayRequest.url get() = (stream.stream.source as StreamSource.Url).url

    @Test
    fun aDefaultVideoIsWhatPlayPlaysEvenWhenTheTitleListsVideos() = runTest {
        val store = Fixture(this, listOf(meta)).store("channel", "ch1")
        runCurrent()
        val action = store.state.value.primaryAction
        assertEquals(Triple("Play", "ch1:v2", false), action?.let { Triple(it.label, it.videoId, it.resume) })
    }

    @Test
    fun aLiveChannelPlaysItsOwnIdWhateverProgrammesItLists() = runTest {
        val store = Fixture(this, listOf(meta)).store("tv", "tv1")
        runCurrent()
        // The fixture's one addon serves no streams: nothing can be found.
        assertEquals(DetailPrimaryAction("Play", "tv1", resume = false, episodeTitle = null, progress = null, noStreams = "No streams found"), store.state.value.primaryAction)
    }

    @Test
    fun autoPlayPlaysTheStreamTheTitleCarriesForItsVideoWithNoStreamAddon() = runTest {
        val f = Fixture(this, listOf(meta))
        f.data.settings.set("p", ProfileSetting.StreamAutoPlay, AutoPlayMode.FIRST_MATCH)
        val store = f.store("channel", "ch1")
        runCurrent()
        store.play()
        runCurrent()
        val played = f.host.played.single()
        assertEquals(listOf("ch1:v2", "https://cdn.example/v2.mp4", "meta"), listOf(played.videoId, played.url, played.stream.addonName))
        assertEquals(emptyList(), f.host.streams, "no list: the carried stream settles it")
    }

    @Test
    fun oldCastAndDirectorNamesAndActorLinksAreCast() = runTest {
        val f = Fixture(this, listOf(meta))
        val old = f.store("movie", "tt5")
        runCurrent()
        val header = (old.state.value.body as DetailBody.Page).header
        assertEquals(
            listOf("Cast" to listOf("Ann Actor", "Bo Actor"), "Directors" to listOf("Di Rector")),
            header.linkGroups.map { group -> group.title to group.links.map { it.label } },
        )
        assertEquals(
            listOf("Cast" to "\u2068Ann Actor\u2069, \u2068Bo Actor\u2069", "Directors" to "\u2068Di Rector\u2069"),
            header.facts.filter { it.linked }.map { it.label to it.value },
            "each name isolated",
        )
        old.openLink(header.linkGroups[0].links[1].id)
        assertEquals(listOf<DeepLink>(DeepLink.Search("Bo Actor")), f.host.links)

        val channel = f.store("channel", "ch1")
        runCurrent()
        val cast = (channel.state.value.body as DetailBody.Page).header.linkGroups.single { it.title == "Cast" }
        assertEquals(listOf("Ann Actor"), cast.links.map { it.label }, "an `actors` link")
    }

    @Test
    fun anEmptyOrNullMetaMeansTheAddonHasntTheTitle() = runTest {
        val f = Fixture(this, listOf(meta))
        for (id in listOf("tt6", "tt7")) {
            val store = f.store("movie", id)
            runCurrent()
            assertEquals("No addon has details for this title.", (store.state.value.body as? DetailBody.Failed)?.text, id)
            assertNull((store.state.value.body as? DetailBody.Failed)?.detail, id)
        }
    }

    @Test
    fun theInformationEndsWithTheAddonTheDetailsCameFromAndTheOnesThatFailed() = runTest {
        val shown = Fixture(this, listOf(broken, meta)).store("movie", "tt5")
        runCurrent()
        assertEquals(
            listOf(DetailFact("Details from", "meta", linked = false), DetailFact("Couldn't load from", "broken", linked = false)),
            (shown.state.value.body as DetailBody.Page).header.facts.takeLast(2),
        )

        val failed = Fixture(this, listOf(broken)).store("movie", "tt5")
        runCurrent()
        assertEquals("Couldn't load the details.", (failed.state.value.body as? DetailBody.Failed)?.text)
        assertEquals("\u2068broken\u2069: The addon returned an error (HTTP 500).", (failed.state.value.body as? DetailBody.Failed)?.detail, "the addon's name isolated")
    }

    @Test
    fun httpTrailersPlayInThePlayerAndYouTubeOnesOutside() = runTest {
        val f = Fixture(this, listOf(meta))
        val store = f.store("channel", "ch1")
        runCurrent()
        val state = store.state.value
        assertEquals(listOf("Trailer 1", "Trailer 2"), state.trailers.map { it.label })
        store.openTrailer(state.trailers[0].id)
        store.openTrailer(state.trailers[1].id)
        assertEquals(listOf("ch1" to "https://cdn.example/trailer.mp4"), f.host.played.map { it.videoId to it.url })
        assertTrue(f.host.played.all { it.purpose == PlayPurpose.TRAILER })
        assertEquals(listOf("https://www.youtube.com/watch?v=t1"), f.host.urls)
    }

    @Test
    fun aCardsTrailerEntryPlaysTheTitlesFirstTrailerAndAnEpisodesMenuItsOwn() = runTest {
        val f = Fixture(this, listOf(meta))
        val store = f.store("channel", "ch1", videoAction = DetailVideoAction.TRAILER)
        runCurrent()
        assertEquals(listOf("ch1" to "https://cdn.example/trailer.mp4"), f.host.played.map { it.videoId to it.url })
        assertTrue(f.host.played.single().purpose == PlayPurpose.TRAILER)

        val episodes = store.state.value.episodes
        assertTrue(MediaAction.TRAILER in episodes.single { it.videoId == "ch1:v2" }.menu.map { it.action })
        assertFalse(MediaAction.TRAILER in episodes.single { it.videoId == "ch1:v1" }.menu.map { it.action }, "a video without trailers offers none")
        store.episodeMenuAction("ch1:v2", MediaAction.TRAILER)
        assertEquals("ch1:v2" to "https://cdn.example/v2-trailer.mp4", f.host.played.last().let { it.videoId to it.url })
        assertEquals(2, f.host.played.size)
        assertEquals(emptyList(), f.host.urls)
    }

    @Test
    fun shareSendsTheTitlesTortugaLinkNotTheAddonsAndTheWebsiteOpens() = runTest {
        val f = Fixture(this, listOf(meta))
        val store = f.store("channel", "ch1")
        runCurrent()
        val state = store.state.value
        // The addon's own `share` link (Stremio's page, for Cinemeta) is not what Share sends.
        assertEquals(DetailShare("https://usetortuga.app/t/channel/ch1?name=A%20channel", "Share", "Link copied."), state.share)
        val website = (state.body as DetailBody.Page).header.linkGroups.single { it.title == "Website" }.links.single()
        assertEquals("example.com/channel", website.label)
        store.openLink(website.id)
        assertEquals(listOf("https://www.example.com/channel/"), f.host.urls)

        val tv = f.store("channel", "ch1", sharing = false)
        runCurrent()
        assertNull(tv.state.value.share, "a TV doesn't share")

        // A title whose addon gives no `share` link has one all the same.
        val plain = f.store("movie", "tt5")
        runCurrent()
        assertEquals("https://usetortuga.app/t/movie/tt5?name=Old", plain.state.value.share?.url)
    }

    private companion object {
        /** 2026-09-21. */
        const val NOW = 1_790_000_000_000L

        val METAS = mapOf(
            "ch1" to """{"meta":{"id":"ch1","type":"channel","name":"A channel","behaviorHints":{"defaultVideoId":"ch1:v2"},
                "website":"https://www.example.com/channel/",
                "links":[{"name":"A channel","category":"share","url":"https://example.com/share/ch1"},
                    {"name":"Ann Actor","category":"actors","url":"stremio:///search?search=Ann%20Actor"}],
                "trailerStreams":[{"url":"https://cdn.example/trailer.mp4"},{"ytId":"t1"}],
                "videos":[{"id":"ch1:v1","title":"First","released":"2026-01-01T00:00:00.000Z"},
                    {"id":"ch1:v2","title":"Second","released":"2026-02-01T00:00:00.000Z",
                     "streams":[{"name":"Direct","url":"https://cdn.example/v2.mp4"}],
                     "trailers":[{"url":"https://cdn.example/v2-trailer.mp4"},{"ytId":"abc"}]}]}}""",
            "tv1" to """{"meta":{"id":"tv1","type":"tv","name":"A station","videos":[{"id":"tv1:news","title":"News"},{"id":"tv1:weather","title":"Weather"}]}}""",
            "tt5" to """{"meta":{"id":"tt5","type":"movie","name":"Old","cast":["Ann Actor"," Bo Actor "],"director":["Di Rector"]}}""",
            "tt6" to """{"meta":{}}""",
            "tt7" to """{"meta":null}""",
        )
    }
}
