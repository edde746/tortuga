package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.CountedVideos
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.net.AddonResponseCache
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.StreamAutoPicker
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.profiles.ParentCheck
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A season of 1,410 episodes, as an anime addon lists One Piece: once its page is built, the page built again as
 * something changes reads the title's videos a few times, not once or more per video, and builds only the rows that
 * changed.
 */
class DetailStoreLongSeasonTest {
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
    fun anUpdateReadsTheVideosOfWhatItChangesOnly() = runTest {
        val videos = CountedVideos.episodes(ID, 1_410, firstRelease = Instant.parse("2000-01-01T00:00:00Z"))
        val meta = MetaItem(MetaItemPreview(id = ID, type = "series", name = "One Piece"), videos)
        // The library's own copy, so only the page's reads count.
        val libraryMeta = MetaItem(meta.preview, videos.toList())
        // The addon's answer, already in the response cache: the page shows these very videos.
        val cache = AddonResponseCache()
        cache.store("https://catalog.example.com/meta/series/$ID.json", AddonResponse(ResourceContent.Meta(meta)), bodySize = 1)
        val http = FakeHttp(this) { respond("", HttpStatusCode.NotFound) }
        val aggregator = AddonAggregator(AddonTransport(http.client, cache, parseDispatcher = StandardTestDispatcher(testScheduler)))
        val data = LocalData(inMemoryDatabase(), { NOW }, StandardTestDispatcher(testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        data.library.upsertFromMeta("p", MetaSnapshot(ID, "series", "One Piece", null, "poster"))
        data.library.recordProgress("p", ID, episode(700), 600_000, 1_440_000, libraryMeta)
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
            covered = flowOf(false),
        )
        runCurrent()
        val built = store.state.value
        assertEquals(1_410, built.episodes.size)
        assertEquals(episode(700), built.primaryAction?.videoId)

        // Nothing of an episode changes.
        videos.reads = 0
        store.dismissDownloadNotice()
        assertTrue(videos.reads <= MAX_READS, "${videos.reads} reads of 1410 videos for a page that changed nothing")

        // The player saves the current episode's position: its row alone is built again.
        videos.reads = 0
        data.library.recordProgress("p", ID, episode(700), 900_000, 1_440_000, libraryMeta)
        runCurrent()
        assertTrue(videos.reads <= MAX_READS, "${videos.reads} reads of 1410 videos for one episode's progress")
        val moved = store.state.value
        assertNotSame(built.episodes[699], moved.episodes[699])
        assertEquals(0.625, moved.episodes[699].progress)
        assertEquals(1_409, moved.episodes.indices.count { moved.episodes[it] === built.episodes[it] }, "every other row is the one built before")
    }

    private companion object {
        /** 2026-09-21. */
        const val NOW = 1_790_000_000_000L

        const val ID = "tt388629"

        /** A few lookups of single videos (Play's, the current episode's season): nothing near a pass over them. */
        const val MAX_READS = 50L

        fun episode(number: Int) = "$ID:1:$number"
    }
}
