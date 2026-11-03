package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.kids.KidsTitle
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
import com.edde746.tortuga.presentation.menu.MediaActions
import com.edde746.tortuga.presentation.menu.WatchlistRequest
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.profiles.ParentCheck
import com.edde746.tortuga.trackers.TrackerFixture
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.TrackerService.SIMKL
import com.edde746.tortuga.trackers.TrackerService.TRAKT
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Detail's Watchlist action ([DetailUiState.watchlist], [DetailStore.watchlist]) on The Shawshank Redemption, through
 * the profile's real tracker coordinator over fake trackers: a toggle of one list, the sheet of several, never offered
 * without a tracker or on a kids profile.
 */
class DetailStoreWatchlistTest {
    private val fixtures = mutableListOf<TrackerFixture>()

    @AfterTest
    fun close() = fixtures.forEach { it.close() }

    private val opened = mutableListOf<WatchlistRequest>()

    private val catalog = Descriptor(
        Manifest.decode("""{"id":"org.example.catalog","version":"1.0.0","name":"catalog","types":["movie"],"resources":["meta"],"idPrefixes":["tt"]}"""),
        "https://catalog.example.com/manifest.json".transportUrl(),
    )

    private class Host : DetailHost {
        override fun openStreams(meta: MetaItem, videoId: String, notice: String?, fromStart: Boolean) = Unit

        override fun openDownloadStreams(meta: MetaItem, videoId: String) = Unit

        override fun openKeepSynced() = Unit

        override fun play(request: PlayRequest) = Unit

        override fun openUrl(url: String) = Unit

        override fun openDeepLink(link: DeepLink) = Unit

        override fun showTitle(entryId: String, type: String, id: String, shownVideoId: String?, preview: MetaItemPreview?) = Unit

        override fun openMeta(type: String, id: String, preview: MetaItemPreview?) = Unit

        override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) = Unit

        override fun askParent(check: ParentCheck, onUnlocked: suspend () -> Unit) = Unit
    }

    private suspend fun TestScope.trackers(vararg services: TrackerService): TrackerFixture {
        val f = TrackerFixture(this, watchlists = true).also { fixtures += it }
        for (service in services) f.connect(service)
        f.log.clear()
        return f
    }

    /** Detail for the movie, settled; [kids]: on a kids profile, which a parent let see the title. */
    private suspend fun TestScope.detail(f: TrackerFixture, kids: Boolean = false): DetailStore {
        val http = FakeHttp(this) { request ->
            if (request.url.encodedPath == "/meta/movie/$ID.json") json(META_JSON) else respond("", HttpStatusCode.NotFound)
        }
        val aggregator = AddonAggregator(AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(testScheduler)))
        if (kids) f.data.settings.set(f.profileId, ProfileSetting.KidsAllowed, listOf(KidsTitle(ID, "movie", NAME)))
        val profile = Profile(f.profileId, "Alex", avatar = null, hasPin = false, createdAt = 0, kids = kids, homeCatalogs = emptyList())
        val gate = KidsGate(f.profileId, flowOf(profile), f.data.settings.observe(f.profileId), f.data.settings, { null }, backgroundScope, testScheduler.timeSource)
        val actions = MediaActions(
            f.profileId, f.data.library, trackerMarks = null, backgroundScope, notice = {},
            trackers = f.coordinator, kids = gate, openWatchlists = { opened += it },
        ) { _, _ -> null }
        val store = DetailStore(
            profileId = f.profileId,
            type = "movie",
            id = ID,
            videoId = null,
            shownVideoId = null,
            aggregator = aggregator,
            installedAddons = { listOf(catalog) },
            library = f.data.library,
            streamHistory = f.data.streamHistory,
            settings = f.data.settings.observe(f.profileId),
            autoPicker = StreamAutoPicker(aggregator, f.data.streamHistory),
            host = Host(),
            clock = f.clock,
            parentScope = backgroundScope,
            network = MutableStateFlow(NetworkState.ONLINE),
            covered = flowOf(false),
            coordinator = f.coordinator,
            actions = actions,
            kids = gate,
        )
        f.drain()
        assertEquals(NAME, (store.state.value.body as? DetailBody.Page)?.header?.title)
        // The page read the user's scores: not what these tests are about.
        f.log.clear()
        return store
    }

    @Test
    fun withOneTrackersListTheActionTogglesIt() = runTest {
        val f = trackers(TRAKT)
        val store = detail(f)
        assertEquals(DetailWatchlist("Add to Trakt watchlist", listed = false), store.state.value.watchlist, "unknown: no mark")
        assertTrue(DetailAction.WATCHLIST in store.state.value.fitActions(slots = 10).more, "behind ⋯ with Rate and Block")

        store.watchlist()
        f.drain()
        assertEquals(listOf("watchlist add $ID"), f.calls(TRAKT))
        assertEquals(DetailWatchlist("Remove from Trakt watchlist", listed = true), store.state.value.watchlist)

        store.watchlist()
        f.drain()
        assertEquals(listOf("watchlist add $ID", "watchlist remove $ID"), f.calls(TRAKT))
        assertEquals(DetailWatchlist("Add to Trakt watchlist", listed = false), store.state.value.watchlist)
        assertEquals(emptyList(), opened, "no sheet for one list")
    }

    @Test
    fun withSeveralTrackersListsTheActionOpensTheSheet() = runTest {
        val f = trackers(TRAKT, SIMKL)
        val store = detail(f)
        assertEquals(DetailWatchlist("Tracker watchlists", listed = false), store.state.value.watchlist)

        store.watchlist()
        f.drain()
        assertEquals(listOf("movie $ID $NAME"), opened.map { "${it.type} ${it.id} ${it.name}" })
        assertEquals(emptyList(), f.log, "nothing written until a row is toggled")

        f.coordinator.setWatchlisted("movie", ID, null, SIMKL, listed = true)
        f.drain()
        assertEquals(DetailWatchlist("Tracker watchlists", listed = true), store.state.value.watchlist, "on one of them")
    }

    @Test
    fun aKidsProfileOrOneWithoutTrackersIsNotOfferedIt() = runTest {
        assertNull(detail(trackers()).state.value.watchlist, "no tracker connected")
        val kids = detail(trackers(TRAKT, SIMKL), kids = true)
        assertNull(kids.state.value.watchlist, "a kids profile")
        assertTrue(DetailAction.WATCHLIST !in kids.state.value.actions)
    }

    private companion object {
        const val ID = "tt0111161"
        const val NAME = "The Shawshank Redemption"
        const val META_JSON = """{"meta":{"id":"$ID","type":"movie","name":"$NAME"}}"""
    }
}
