package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.json
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
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerFixture
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.TrackerService.MDBLIST
import com.edde746.tortuga.trackers.TrackerService.SIMKL
import com.edde746.tortuga.trackers.TrackerService.TRAKT
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The user's own rating on Detail ([DetailUiState.userRating], [DetailStore.rate]) of The Shawshank Redemption, through
 * the profile's real tracker coordinator over fake trackers.
 */
class DetailStoreUserRatingTest {
    private val fixtures = mutableListOf<TrackerFixture>()

    @AfterTest
    fun close() = fixtures.forEach { it.close() }

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
        val f = TrackerFixture(this).also { fixtures += it }
        for (service in services) f.connect(service)
        return f
    }

    /** Detail for the movie, settled. */
    private fun TestScope.detail(f: TrackerFixture, network: MutableStateFlow<NetworkState> = MutableStateFlow(NetworkState.ONLINE)): DetailStore {
        val http = FakeHttp(this) { request ->
            if (request.url.encodedPath == "/meta/movie/$ID.json") json(META_JSON) else respond("", HttpStatusCode.NotFound)
        }
        val aggregator = AddonAggregator(AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(testScheduler)))
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
            network = network,
            covered = flowOf(false),
            coordinator = f.coordinator,
        )
        f.drain()
        assertEquals("The Shawshank Redemption", (store.state.value.body as? DetailBody.Page)?.header?.title)
        return store
    }

    private fun DetailUserRating.lines() = trackers.map { "${it.name} ${it.status}" }

    @Test
    fun withoutATrackerThatRatesTheTitleThereIsNothingToRate() = runTest {
        assertNull(detail(trackers()).state.value.userRating, "no tracker connected")
        // An anime list can't name a title that isn't anime.
        assertNull(detail(trackers(TrackerService.MYANIMELIST)).state.value.userRating, "only MyAnimeList")
    }

    @Test
    fun theItemIsTheTrackersScoreOrTheirRangeWhereTheyDiffer() = runTest {
        val f = trackers(TRAKT, SIMKL, MDBLIST)
        f.tracker(TRAKT).scores[TITLE] = 8
        f.tracker(SIMKL).scores[TITLE] = 7
        val rating = assertNotNull(detail(f).state.value.userRating)
        assertEquals(listOf("Trakt 8", "Simkl 7", "MDBList Not rated"), rating.lines())
        assertEquals("7–8", rating.text)
        assertEquals("Your rating: 7–8", rating.label)
        assertNull(rating.score, "no score is picked while they differ")

        f.tracker(SIMKL).scores[TITLE] = 8
        val agreed = assertNotNull(detail(f).state.value.userRating)
        assertEquals("8", agreed.text)
        assertEquals(8, agreed.score, "a tracker without a score doesn't count as differing")

        f.tracker(TRAKT).scores.clear()
        f.tracker(SIMKL).scores.clear()
        val none = assertNotNull(detail(f).state.value.userRating)
        assertNull(none.text, "no score in the ratings row before one is given")
        assertEquals("Rate this title", none.label)
    }

    @Test
    fun aScoreShowsAtOnceThenWhatEachTrackerDidWithIt() = runTest {
        val f = trackers(TRAKT, SIMKL)
        val store = detail(f)
        f.tracker(TRAKT).latency = 2.seconds
        f.tracker(SIMKL).failNext("rate", TrackerException.Api(SIMKL, 503))

        store.rate(9)
        var rating = assertNotNull(store.state.value.userRating)
        assertEquals("9", rating.text)
        assertEquals(9, rating.score)
        assertEquals(listOf("Trakt Saving…", "Simkl Saving…"), rating.lines())
        assertTrue(rating.trackers.all { it.busy })

        f.drain()
        rating = assertNotNull(store.state.value.userRating)
        assertEquals(listOf("Trakt Saved", "Simkl Not sent yet, will retry"), rating.lines())
        assertEquals(listOf(false, true), rating.trackers.map { it.attention })
        assertEquals("9", rating.text, "the score waiting for Simkl is the user's too")
        assertEquals(9, f.tracker(TRAKT).scores[TITLE])

        // The page again: Trakt's score is read, Simkl's waiting one shows without asking Simkl.
        f.log.clear()
        assertEquals(listOf("Trakt 9", "Simkl Not sent yet, will retry"), detail(f).state.value.userRating?.lines())
        assertEquals(emptyList(), f.calls(SIMKL))
    }

    @Test
    fun removingTheRatingRemovesItFromEveryTracker() = runTest {
        val f = trackers(TRAKT, SIMKL)
        f.tracker(TRAKT).scores[TITLE] = 8
        f.tracker(SIMKL).scores[TITLE] = 8
        val store = detail(f)
        assertEquals(8, store.state.value.userRating?.score)

        store.rate(null)
        f.drain()
        val rating = assertNotNull(store.state.value.userRating)
        assertEquals(listOf("Trakt Removed", "Simkl Removed"), rating.lines())
        assertNull(rating.text)
        assertEquals("Rate this title", rating.label)
        assertEquals(emptyMap(), f.tracker(TRAKT).scores)
        assertEquals(emptyMap(), f.tracker(SIMKL).scores)
    }

    @Test
    fun aScoreThatCouldNotBeReadIsReadAgainOnceTheDeviceIsBackOnline() = runTest {
        val f = trackers(TRAKT, SIMKL)
        f.tracker(TRAKT).scores[TITLE] = 8
        f.tracker(SIMKL).scores[TITLE] = 8
        f.tracker(TRAKT).failNext("rating", TrackerException.Network(TRAKT, IOException("no route to host")))
        val network = MutableStateFlow(NetworkState.OFFLINE)
        val store = detail(f, network)
        assertEquals(listOf("Trakt Couldn't check", "Simkl 8"), store.state.value.userRating?.lines())
        assertEquals("8", store.state.value.userRating?.text, "an unknown score doesn't count as differing")

        network.value = NetworkState.ONLINE
        f.drain()
        assertEquals(listOf("Trakt 8", "Simkl 8"), store.state.value.userRating?.lines())

        // Going offline again keeps what was read.
        f.log.clear()
        network.value = NetworkState.OFFLINE
        f.drain()
        network.value = NetworkState.ONLINE
        f.drain()
        assertEquals(emptyList(), f.log)
    }

    private companion object {
        const val ID = "tt0111161"

        /** The movie as the fake trackers name it. */
        const val TITLE = "movie imdb=$ID"

        const val META_JSON = """{"meta":{"id":"$ID","type":"movie","name":"The Shawshank Redemption"}}"""
    }
}
