@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.settings.ProfileSetting
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
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.profiles.ParentCheck
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * With the profile's "Blur unwatched episodes" on, Detail blurs the still and hides the description of every episode
 * neither watched nor started, upcoming ones included; a watched or started one shows both, and with it off every one
 * does. Titles stay.
 */
class DetailStoreBlurTest {
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
    fun theStillsAndDescriptionsOfEpisodesNeitherWatchedNorStartedAreHiddenWhileTheSettingIsOn() = runTest {
        val clock = object : Clock {
            override fun now() = NOW
        }
        val data = LocalData(inMemoryDatabase(), { NOW.toEpochMilliseconds() }, StandardTestDispatcher(testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        val http = FakeHttp(this) { request ->
            if (request.url.encodedPath == "/meta/series/$ID.json") json(META_JSON) else respond("", HttpStatusCode.NotFound)
        }
        val aggregator = AddonAggregator(AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(testScheduler)))
        data.library.markVideoWatched("p", META, WATCHED, true)
        data.library.recordProgress("p", ID, STARTED, 600_000, 2_400_000, META)
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
        fun blurred() = store.state.value.episodes.associate { it.videoId to it.blurred }
        fun hidden() = store.state.value.episodes.associate { it.videoId to it.hiddenOverviewLabel }
        runCurrent()
        val episodes = store.state.value.episodes
        assertEquals(listOf(WATCHED, STARTED, UNWATCHED, UPCOMING), episodes.map { it.videoId })
        assertEquals(listOf(true, false, false, false), episodes.map { it.watched }, "the marks as set up")
        assertEquals(listOf(false, true, false, false), episodes.map { it.progress != null })
        assertEquals(listOf(false, false, false, true), episodes.map { it.upcoming })
        assertEquals(mapOf(WATCHED to false, STARTED to false, UNWATCHED to false, UPCOMING to false), blurred(), "off: none is blurred")
        assertEquals(mapOf(WATCHED to null, STARTED to null, UNWATCHED to null, UPCOMING to null), hidden(), "off: every description shows")

        data.settings.set("p", ProfileSetting.BlurUnwatched, true)
        runCurrent()
        assertEquals(mapOf(WATCHED to false, STARTED to false, UNWATCHED to true, UPCOMING to true), blurred(), "on: the unwatched and the upcoming")
        assertEquals(
            mapOf(WATCHED to null, STARTED to null, UNWATCHED to "Description hidden", UPCOMING to "Description hidden"),
            hidden(),
            "on: the unwatched and upcoming descriptions hidden, screen readers told so",
        )
        assertEquals(episodes.map { it.numberedTitle }, store.state.value.episodes.map { it.numberedTitle }, "the titles stay")
        assertEquals(listOf("One", "Two", "Three", "Four"), store.state.value.episodes.map { it.overview }, "each description kept to lay its place out")

        data.library.recordProgress("p", ID, UNWATCHED, 60_000, 2_400_000, META)
        runCurrent()
        assertEquals(false, blurred()[UNWATCHED], "started: sharp")
        assertEquals(null, hidden()[UNWATCHED], "started: its description shows")
        data.library.markVideoWatched("p", META, UPCOMING, true)
        runCurrent()
        assertEquals(false, blurred()[UPCOMING], "marked watched: sharp")
        assertEquals(null, hidden()[UPCOMING], "marked watched: its description shows")

        data.settings.set("p", ProfileSetting.BlurUnwatched, false)
        runCurrent()
        assertEquals(setOf(false), blurred().values.toSet(), "off again: none is blurred")
        assertEquals(setOf(null), hidden().values.toSet(), "off again: every description shows")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-02T12:00:00Z")
        const val ID = "tt301"
        const val WATCHED = "$ID:1:1"
        const val STARTED = "$ID:1:2"
        const val UNWATCHED = "$ID:1:3"
        const val UPCOMING = "$ID:1:4"

        val META_JSON = """{"meta":{"id":"$ID","type":"series","name":"Tortuga Spoilers","videos":[
            {"id":"$WATCHED","title":"Pilot","released":"${NOW - 21.days}","season":1,"episode":1,"thumbnail":"https://img.example.com/1.jpg","overview":"One"},
            {"id":"$STARTED","title":"The Turn","released":"${NOW - 14.days}","season":1,"episode":2,"thumbnail":"https://img.example.com/2.jpg","overview":"Two"},
            {"id":"$UNWATCHED","title":"The Reveal","released":"${NOW - 7.days}","season":1,"episode":3,"thumbnail":"https://img.example.com/3.jpg","overview":"Three"},
            {"id":"$UPCOMING","title":"The End","released":"${NOW + 7.days}","season":1,"episode":4,"thumbnail":"https://img.example.com/4.jpg","overview":"Four"}
        ]}}"""

        val META: MetaItem = (AddonResponse.decode(META_JSON).content as ResourceContent.Meta).item
    }
}
