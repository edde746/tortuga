@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.downloads.SHOW
import com.edde746.tortuga.downloads.cdnChoice
import com.edde746.tortuga.downloads.downloadsTest
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
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.StreamAutoPicker
import com.edde746.tortuga.presentation.downloads.downloaded
import com.edde746.tortuga.presentation.downloads.installMetaAddon
import com.edde746.tortuga.presentation.downloads.serveMeta
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.profiles.ParentCheck
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** An episode Detail shows ahead of its release is out from its release on, while the page stays open. */
class DetailStoreReleaseTest {
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

    @Test
    fun anEpisodeAiringInAnHourIsOutThenItsDateAndPlayMovingWithNothingElseChanging() = runTest {
        // One clock on the test's time for the library and the page.
        val clock = object : Clock {
            override fun now() = START + testScheduler.currentTime.milliseconds
        }
        val data = LocalData(
            inMemoryDatabase(), { clock.now().toEpochMilliseconds() }, StandardTestDispatcher(testScheduler),
            HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        )
        val http = FakeHttp(this) { request ->
            if (request.url.encodedPath == "/meta/series/$ID.json") json(META_JSON) else respond("", HttpStatusCode.NotFound)
        }
        val aggregator = AddonAggregator(AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(testScheduler)))
        data.library.markVideoWatched("p", META, "$ID:1:1", true)
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
            clock = clock,
            parentScope = backgroundScope,
            network = MutableStateFlow(NetworkState.ONLINE),
        )
        fun episode2() = store.state.value.episodes.single { it.videoId == "$ID:1:2" }
        runCurrent()
        assertTrue(episode2().upcoming)
        assertEquals("Airs ${DetailText.date(RELEASE)}", episode2().infoLine)
        assertEquals("$ID:1:1", store.state.value.primaryAction?.videoId, "Play plays the one episode out, watched")
        val requests = http.requests.size

        advanceTimeBy(1.hours - 1.seconds)
        runCurrent()
        assertTrue(episode2().upcoming, "a second before its release")

        advanceTimeBy(1.seconds)
        runCurrent()
        assertFalse(episode2().upcoming, "out now")
        assertEquals(DetailText.date(RELEASE), episode2().infoLine)
        assertEquals("$ID:1:2", store.state.value.primaryAction?.videoId, "Play moves on to it")
        assertTrue(episode2().nextUp)
        assertEquals(requests, http.requests.size, "from the same answer")
    }

    @Test
    fun anEpisodeOutWhileThePageIsOpenCanBeDownloadedAndCountsInTheTitlesDownloads() = downloadsTest { h ->
        val release = Instant.fromEpochMilliseconds(h.wallClock.now) + 1.hours
        val show = SHOW.copy(
            videos = SHOW.videos.take(3).mapIndexed { index, video ->
                video.copy(released = if (index < 2) release - 30.days else release)
            },
        )
        h.serveCdn(lcgBytes(100_000, seed = 7))
        h.serveMeta(show)
        h.installMetaAddon()
        h.install()
        h.downloaded(show, "tt0903747:1:1", cdnChoice("1-1.mkv"))
        h.downloaded(show, "tt0903747:1:2", cdnChoice("1-2.mkv"))
        val store = h.graph.detailStore(h.profileId, "series", show.id, videoId = null, Host())
        fun episode3() = store.state.value.episodes.single { it.videoId == "tt0903747:1:3" }
        h.settle()
        assertTrue(episode3().upcoming)
        assertNull(episode3().download, "nothing to download before its release")
        assertEquals(DetailDownloadState.DOWNLOADED, store.state.value.download?.state, "every episode out is downloaded")

        h.wallClock.now += 1.hours.inWholeMilliseconds
        h.advance(1.hours)
        assertFalse(episode3().upcoming)
        assertEquals(DetailDownloadState.AVAILABLE, assertNotNull(episode3().download).state)
        assertEquals(DetailDownloadState.PARTIAL, store.state.value.download?.state)
        assertEquals("2 of 3 downloaded", store.state.value.download?.label)
    }

    private companion object {
        /** 2026-09-21. */
        val START = Instant.fromEpochMilliseconds(1_790_000_000_000L)

        /** Episode 2's release, an hour after the test starts; episode 1 came out a week before. */
        val RELEASE = START + 1.hours

        const val ID = "tt300"

        val META_JSON = """{"meta":{"id":"$ID","type":"series","name":"Tortuga Weekly","videos":[
            {"id":"$ID:1:1","title":"Episode 1","released":"${START - 7.days}","season":1,"episode":1},
            {"id":"$ID:1:2","title":"Episode 2","released":"$RELEASE","season":1,"episode":2}
        ]}}"""

        val META: MetaItem = (AddonResponse.decode(META_JSON).content as ResourceContent.Meta).item
    }
}
