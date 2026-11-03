package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.StreamAutoPicker
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.profiles.ParentCheck
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Detail's current episode ([DetailUiState.currentEpisode]): the season it opens on, the list brought to it
 * ([DetailUiState.shownEpisode]) and how both follow it, over a series of three seasons of 10, 12 and 8 episodes and
 * three specials.
 */
class DetailStoreCurrentEpisodeTest {
    private val catalog = Descriptor(
        Manifest.decode("""{"id":"org.example.catalog","version":"1.0.0","name":"catalog","types":["series"],"resources":["meta"],"idPrefixes":["tt"]}"""),
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

    private class Fixture(private val scope: TestScope, private val addons: List<Descriptor>, metaDelay: Long) {
        val data = LocalData(inMemoryDatabase(), { NOW }, StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        val http = FakeHttp(scope) { request ->
            if (request.url.host == "catalog.example.com" && request.url.encodedPath == "/meta/series/$ID.json") {
                delay(metaDelay)
                json(META_JSON)
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
        val aggregator = AddonAggregator(AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(scope.testScheduler)))

        fun store(
            videoId: String? = null,
            shownVideoId: String? = null,
            covered: Flow<Boolean> = flowOf(false),
            videoAction: DetailVideoAction = DetailVideoAction.STREAMS,
        ) = DetailStore(
            profileId = "p",
            type = "series",
            id = ID,
            videoId = videoId,
            shownVideoId = shownVideoId,
            aggregator = aggregator,
            installedAddons = { addons },
            library = data.library,
            streamHistory = data.streamHistory,
            settings = data.settings.observe("p"),
            autoPicker = StreamAutoPicker(aggregator, data.streamHistory),
            host = Host(),
            clock = object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(NOW)
            },
            parentScope = scope.backgroundScope,
            network = MutableStateFlow(NetworkState.ONLINE),
            covered = covered,
            videoAction = videoAction,
        )

        /** [videoId] played to 12:34 of 65 minutes, as the player records it. */
        suspend fun leaveAt(videoId: String) {
            data.library.upsertFromMeta("p", SNAPSHOT)
            data.library.recordProgress("p", ID, videoId, 754_000, 3_900_000, META)
        }

        /** [videoId] played past the watched threshold, as the player records it: the next episode comes up. */
        suspend fun finish(videoId: String) {
            data.library.upsertFromMeta("p", SNAPSHOT)
            data.library.recordProgress("p", ID, videoId, 0, 3_900_000, META)
            data.library.recordProgress("p", ID, videoId, 3_800_000, 3_900_000, META, watched = true)
        }
    }

    private fun TestScope.fixture(metaDelay: Long = 0) = Fixture(this, listOf(catalog), metaDelay)

    private fun episode(season: Int, episode: Int) = "$ID:$season:$episode"

    /** Where the selection sits in the lists both UIs show: the season's chip, the current episode's row. */
    @Test
    fun theSelectedSeasonsChipAndTheCurrentEpisodesRowFollowTheSelection() = runTest {
        val f = fixture()
        f.leaveAt(episode(2, 7))
        val store = f.store()
        runCurrent()
        var state = store.state.value
        assertTrue(state.hasEpisodes)
        assertEquals(1, state.selectedSeasonIndex, "season 2 is the second chip")
        assertEquals(6, state.currentEpisodeIndex, "E7 is the season's seventh")
        store.selectSeason(0)
        state = store.state.value
        assertEquals(0, state.selectedSeason)
        assertEquals(3, state.selectedSeasonIndex, "the specials come last")
        assertNull(state.currentEpisode)
        assertEquals(0, state.currentEpisodeIndex, "a season without the current episode starts at its first")
    }

    @Test
    fun theEpisodeInProgressOpensOnItsSeasonWithTheListBroughtToIt() = runTest {
        val f = fixture()
        f.leaveAt(episode(2, 7))
        val store = f.store()
        runCurrent()
        var state = store.state.value
        assertEquals("S2E7 · 12:34", state.primaryAction?.label)
        assertEquals(2, state.selectedSeason)
        assertEquals(episode(2, 7), state.currentEpisode)
        assertEquals(episode(2, 7), state.shownEpisode)
        assertFalse(state.shownEpisodeFromEntry, "the one-column page leaves its list at the top")
        store.episodeShown()
        state = store.state.value
        assertNull(state.shownEpisode, "the list is brought there once")
        assertEquals(episode(2, 7), state.currentEpisode)
    }

    @Test
    fun theTitleAnsweringAfterTheLibraryStateOpensTheSameWay() = runTest {
        val f = fixture(metaDelay = 3.seconds.inWholeMilliseconds)
        f.leaveAt(episode(2, 7))
        val store = f.store()
        runCurrent()
        assertNull((store.state.value.body as? DetailBody.Page)?.header)
        testScheduler.advanceTimeBy(4.seconds)
        runCurrent()
        val state = store.state.value
        assertEquals(2, state.selectedSeason)
        assertEquals(episode(2, 7), state.shownEpisode)
    }

    /** The library's state reaching the open page after the title (a sync from another device): the page follows it. */
    @Test
    fun theLibraryStateArrivingAfterTheTitleMovesTheSeasonAndTheList() = runTest {
        val f = fixture()
        val store = f.store()
        runCurrent()
        assertEquals(1, store.state.value.selectedSeason)
        assertNull(store.state.value.shownEpisode)
        f.leaveAt(episode(2, 7))
        runCurrent()
        val state = store.state.value
        assertEquals(2, state.selectedSeason)
        assertEquals(episode(2, 7), state.currentEpisode)
        assertEquals(episode(2, 7), state.shownEpisode)
    }

    @Test
    fun aSeasonsFirstEpisodeOpensAtTheListsTop() = runTest {
        // Not started: the first episode.
        val fresh = fixture().store()
        runCurrent()
        assertEquals(1, fresh.state.value.selectedSeason)
        assertEquals(episode(1, 1), fresh.state.value.currentEpisode)
        assertNull(fresh.state.value.shownEpisode)

        // Season 1 finished: the next season's first episode.
        val f = fixture()
        f.finish(episode(1, 10))
        val next = f.store()
        runCurrent()
        assertEquals("S2E1", next.state.value.primaryAction?.label)
        assertEquals(2, next.state.value.selectedSeason)
        assertEquals(episode(2, 1), next.state.value.currentEpisode)
        assertNull(next.state.value.shownEpisode)

        // Everything watched: the first episode again.
        val all = fixture()
        all.data.library.upsertFromMeta("p", SNAPSHOT)
        all.data.library.markAllWatched("p", META, true)
        val watched = all.store()
        runCurrent()
        assertEquals("S1E1", watched.state.value.primaryAction?.label)
        assertEquals(1, watched.state.value.selectedSeason)
        assertNull(watched.state.value.shownEpisode)
    }

    @Test
    fun aSpecialInProgressOpensOnTheSpecials() = runTest {
        val f = fixture()
        f.leaveAt(episode(0, 2))
        val store = f.store()
        runCurrent()
        assertEquals(0, store.state.value.selectedSeason)
        assertEquals(episode(0, 2), store.state.value.shownEpisode)
    }

    @Test
    fun continueWatchingsEpisodeFinishedInThePlayerBringsTheListToTheNext() = runTest {
        val f = fixture()
        f.leaveAt(episode(2, 7))
        val covered = MutableStateFlow(false)
        val store = f.store(videoId = episode(2, 7), covered = covered, videoAction = DetailVideoAction.PLAY)
        runCurrent()
        assertEquals(2, store.state.value.selectedSeason)
        assertEquals(episode(2, 7), store.state.value.shownEpisode)
        store.episodeShown()

        covered.value = true
        f.finish(episode(2, 7))
        runCurrent()
        covered.value = false
        runCurrent()
        val state = store.state.value
        assertEquals("S2E8", state.primaryAction?.label)
        assertEquals(2, state.selectedSeason)
        assertEquals(episode(2, 8), state.currentEpisode)
        assertEquals(episode(2, 8), state.shownEpisode)
    }

    @Test
    fun aSeasonsLastEpisodeFinishedMovesToTheNextSeason() = runTest {
        val f = fixture()
        f.leaveAt(episode(2, 12))
        val covered = MutableStateFlow(false)
        val store = f.store(covered = covered)
        runCurrent()
        store.episodeShown()
        covered.value = true
        f.finish(episode(2, 12))
        runCurrent()
        covered.value = false
        runCurrent()
        val state = store.state.value
        assertEquals(3, state.selectedSeason)
        assertEquals(episode(3, 1), state.currentEpisode)
        assertEquals(episode(3, 1), state.shownEpisode, "brought to the season's first too: the list was elsewhere")
    }

    @Test
    fun anotherSeasonChosenStaysAsTheCurrentEpisodeMovesOnAndItsSeasonBringsItBack() = runTest {
        val f = fixture()
        f.leaveAt(episode(2, 7))
        val covered = MutableStateFlow(false)
        val store = f.store(covered = covered)
        runCurrent()
        store.selectSeason(1)
        var state = store.state.value
        assertEquals(1, state.selectedSeason)
        assertNull(state.shownEpisode, "another season shows from its first episode")
        assertNull(state.currentEpisode, "season 1 doesn't list the current episode")

        covered.value = true
        f.finish(episode(2, 7))
        runCurrent()
        covered.value = false
        runCurrent()
        state = store.state.value
        assertEquals(1, state.selectedSeason)
        assertNull(state.shownEpisode)

        store.selectSeason(2)
        state = store.state.value
        assertEquals(2, state.selectedSeason)
        assertEquals(episode(2, 8), state.currentEpisode)
        assertEquals(episode(2, 8), state.shownEpisode)
        store.episodeShown()
        // Its season chosen again, while it shows: the list comes back to it.
        store.selectSeason(2)
        assertEquals(episode(2, 8), store.state.value.shownEpisode)
    }

    @Test
    fun aMarkMadeOnThePageMovesTheCurrentEpisodeButNotTheSeasonOrTheList() = runTest {
        val f = fixture()
        f.leaveAt(episode(2, 12))
        val covered = MutableStateFlow(false)
        val store = f.store(covered = covered)
        runCurrent()
        store.episodeShown()
        store.episodeMenuAction(episode(2, 12), MediaAction.MARK_WATCHED)
        runCurrent()
        var state = store.state.value
        assertEquals("S3E1", state.primaryAction?.label)
        assertEquals(2, state.selectedSeason, "the season shown stays")
        assertNull(state.shownEpisode)
        assertNull(state.currentEpisode, "season 2 doesn't list the current episode, S3E1")
        store.selectSeason(3)
        state = store.state.value
        assertEquals(episode(3, 1), state.currentEpisode)
        assertEquals(episode(3, 1), state.shownEpisode)
        store.episodeShown()

        // Within the season: the current episode moves on, the list stays.
        store.episodeMenuAction(episode(3, 1), MediaAction.MARK_WATCHED)
        runCurrent()
        state = store.state.value
        assertEquals(episode(3, 2), state.currentEpisode)
        assertNull(state.shownEpisode)

        // Covered again (the player), the list follows once more.
        covered.value = true
        f.finish(episode(3, 2))
        runCurrent()
        covered.value = false
        runCurrent()
        assertEquals(episode(3, 3), store.state.value.shownEpisode)
    }

    @Test
    fun aSeasonKeptByAMarkFollowsTheCurrentEpisodeAgainAfterThePlayer() = runTest {
        val f = fixture()
        f.leaveAt(episode(2, 12))
        val covered = MutableStateFlow(false)
        val store = f.store(covered = covered)
        runCurrent()
        store.episodeShown()
        store.episodeMenuAction(episode(2, 12), MediaAction.MARK_WATCHED)
        runCurrent()
        assertEquals(2, store.state.value.selectedSeason, "the mark keeps the season shown")

        // S3E1 played (the primary action) to its end: the season and the list follow S3E2, as no season was chosen.
        covered.value = true
        f.finish(episode(3, 1))
        runCurrent()
        covered.value = false
        runCurrent()
        val state = store.state.value
        assertEquals(3, state.selectedSeason)
        assertEquals(episode(3, 2), state.currentEpisode)
        assertEquals(episode(3, 2), state.shownEpisode)
    }

    @Test
    fun theVideoOpenedAtIsTheCurrentEpisodeUntilThePrimaryActionMovesOn() = runTest {
        // A link to S1E3 while S2E7 is in progress: its streams over the page, on season 1.
        val f = fixture()
        f.leaveAt(episode(2, 7))
        val covered = MutableStateFlow(false)
        val store = f.store(videoId = episode(1, 3), covered = covered)
        runCurrent()
        var state = store.state.value
        assertEquals(1, state.selectedSeason)
        assertEquals(episode(1, 3), state.currentEpisode)
        assertEquals(episode(1, 3), state.shownEpisode)
        store.episodeShown()

        // A trailer played: nothing moved on.
        covered.value = true
        runCurrent()
        covered.value = false
        runCurrent()
        assertNull(store.state.value.shownEpisode)

        // S1E3 played to its end: S1E4.
        covered.value = true
        f.finish(episode(1, 3))
        runCurrent()
        covered.value = false
        runCurrent()
        state = store.state.value
        assertEquals(1, state.selectedSeason)
        assertEquals(episode(1, 4), state.currentEpisode)
        assertEquals(episode(1, 4), state.shownEpisode)
    }

    private companion object {
        /** 2026-09-21. */
        const val NOW = 1_790_000_000_000L

        const val ID = "tt100"

        /** Seasons 1 to 3 of 10, 12 and 8 episodes, then three specials. */
        val META_JSON: String = run {
            val videos = listOf(1 to 10, 2 to 12, 3 to 8, 0 to 3).flatMap { (season, count) ->
                (1..count).map { """{"id":"$ID:$season:$it","title":"Episode $it","season":$season,"episode":$it}""" }
            }
            """{"meta":{"id":"$ID","type":"series","name":"Tortuga Current","videos":[${videos.joinToString(",")}]}}"""
        }

        val META: MetaItem = (AddonResponse.decode(META_JSON).content as ResourceContent.Meta).item

        val SNAPSHOT = MetaSnapshot(ID, "series", "Tortuga Current", null, "poster")
    }
}
