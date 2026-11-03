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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A long season's ranges ([DetailUiState.episodeRanges]) over a series whose season 1 has 120 episodes (numbered from
 * 0, as some anime addons number them) and season 2 has 50.
 */
class DetailStoreEpisodeRangesTest {
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

    /** A store of the series with [videoId] played to 12:34 of 24 minutes, and the library it reads; [covered] by the player. */
    private suspend fun TestScope.store(videoId: String, covered: Flow<Boolean> = flowOf(false)): Pair<LocalData, DetailStore> {
        val data = LocalData(inMemoryDatabase(), { NOW }, StandardTestDispatcher(testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        val http = FakeHttp(this) { request ->
            if (request.url.encodedPath == "/meta/series/$ID.json") json(META_JSON) else respond("", HttpStatusCode.NotFound)
        }
        val aggregator = AddonAggregator(AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(testScheduler)))
        data.library.upsertFromMeta("p", MetaSnapshot(ID, "series", "Tortuga Long", null, "poster"))
        data.library.recordProgress("p", ID, videoId, 754_000, 1_440_000, META)
        val store = DetailStore(
            profileId = "p",
            type = "series",
            id = ID,
            videoId = null,
            shownVideoId = null,
            aggregator = aggregator,
            installedAddons = { listOf(catalog) },
            library = data.library,
            streamHistory = data.streamHistory,
            settings = data.settings.observe("p"),
            autoPicker = StreamAutoPicker(aggregator, data.streamHistory),
            host = Host(),
            clock = object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(NOW)
            },
            parentScope = backgroundScope,
            network = MutableStateFlow(NetworkState.ONLINE),
            covered = covered,
        )
        runCurrent()
        return data to store
    }

    @Test
    fun aLongSeasonComesInRangesOfFiftyHoldingTheCurrentEpisode() = runTest {
        val (_, store) = store(episode(1, 75))
        var state = store.state.value
        assertEquals(
            listOf(DetailEpisodeRange("0–49", 0, 50), DetailEpisodeRange("50–99", 50, 100), DetailEpisodeRange("100–119", 100, 120)),
            state.episodeRanges,
        )
        assertEquals(120, state.episodes.size, "every UI still gets the whole season")
        assertEquals(1, state.listedRange, "E75 is in 50–99")
        assertEquals((50..99).map { episode(1, it) }, state.listedEpisodes.map { it.videoId })
        assertEquals(50, state.listedStart)
        assertEquals(1, state.rangeOf(50), "the range's first")
        assertEquals(0, state.rangeOf(49), "the range before's last")

        store.selectSeason(2)
        state = store.state.value
        assertEquals(emptyList(), state.episodeRanges, "50 episodes are one row")
        assertEquals(0, state.listedRange)
        assertEquals(50, state.listedEpisodes.size, "the whole season")
    }

    @Test
    fun aPickedRangeStaysThroughAMarkUntilAnotherSeasonOrTheListFollowsTheCurrentEpisode() = runTest {
        val covered = MutableStateFlow(false)
        val (data, store) = store(episode(1, 99), covered)
        assertEquals(1, store.state.value.listedRange, "E99 is in 50–99")

        // A mark made here moves the current episode into the next range: the range picked stays.
        store.pickRange(0)
        assertEquals(0, store.state.value.listedRange)
        store.episodeMenuAction(episode(1, 99), MediaAction.MARK_WATCHED)
        runCurrent()
        var state = store.state.value
        assertEquals(episode(1, 100), state.currentEpisode)
        assertEquals(0, state.listedRange, "the mark moves no list")

        // Another season and back: the range holding the current episode.
        store.selectSeason(2)
        store.selectSeason(1)
        assertEquals(2, store.state.value.listedRange, "E100 is in 100–119")
        store.episodeShown()

        // Back from the player having finished the current episode: the list follows the next, whatever was picked.
        store.pickRange(0)
        covered.value = true
        runCurrent()
        data.library.recordProgress("p", ID, episode(1, 100), 0, 1_440_000, META)
        data.library.recordProgress("p", ID, episode(1, 100), 1_400_000, 1_440_000, META, watched = true)
        runCurrent()
        covered.value = false
        runCurrent()
        state = store.state.value
        assertEquals(episode(1, 101), state.shownEpisode)
        assertEquals(2, state.listedRange)
    }

    private companion object {
        /** 2026-09-21. */
        const val NOW = 1_790_000_000_000L

        const val ID = "tt200"

        fun episode(season: Int, number: Int) = "$ID:$season:$number"

        val META_JSON: String = run {
            val videos = (0..119).map { """{"id":"$ID:1:$it","title":"Episode $it","season":1,"episode":$it}""" } +
                (1..50).map { """{"id":"$ID:2:$it","title":"Episode $it","season":2,"episode":$it}""" }
            """{"meta":{"id":"$ID","type":"series","name":"Tortuga Long","videos":[${videos.joinToString(",")}]}}"""
        }

        val META: MetaItem = (AddonResponse.decode(META_JSON).content as ResourceContent.Meta).item
    }
}
