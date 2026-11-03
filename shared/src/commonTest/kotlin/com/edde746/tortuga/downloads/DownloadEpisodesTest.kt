package com.edde746.tortuga.downloads

import com.edde746.tortuga.data.downloads.DownloadStream
import com.edde746.tortuga.data.library.librarySnapshot
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.urlStream
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** "Download season…" and Keep synced: which episodes, and each one's stream picked without asking. */
class DownloadEpisodesTest {
    private val bytes = lcgBytes(100_000, seed = 13)

    @Test
    fun aSeasonFollowsTheReferencesAddonAndBingeGroup() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams { videoId -> releases(videoId) }
        val manager = h.install()

        val queued = manager.enqueueEpisodes(h.profileId, SHOW, season = 1, SeasonPick.ALL, reference = cdnChoice("x.mkv", bingeGroup = "rel-1080"))

        assertEquals(3, queued)
        assertEquals(listOf("1-1-1080", "1-2-1080", "1-3-1080"), (1..3).map { picked(h, "tt0903747:1:$it") })
    }

    @Test
    fun theSeriesLastDownloadIsTheReferenceWithoutOne() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams { videoId -> releases(videoId) }
        val manager = h.install()
        manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1-1-1080.mkv", bingeGroup = "rel-1080"))

        val queued = manager.enqueueEpisodes(h.profileId, SHOW, season = 1, SeasonPick.ALL, reference = null)

        assertEquals(2, queued, "the episode already queued is skipped")
        assertEquals(listOf("1-2-1080", "1-3-1080"), (2..3).map { picked(h, "tt0903747:1:$it") })
    }

    @Test
    fun anEpisodePickedOutsideTheReleaseLeavesTheRestInIt() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        // S1E2's 1080p isn't ready: it gets the 720p, and S1E3 is back in the 1080p release.
        h.serveStreams { videoId ->
            releases(videoId).map { if (videoId.endsWith(":1:2") && it.behaviorHints.bingeGroup == "rel-1080") it.copy(description = "Not ready") else it }
        }
        val manager = h.install()
        manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1-1-1080.mkv", bingeGroup = "rel-1080"))

        val queued = manager.enqueueEpisodes(h.profileId, SHOW, season = 1, SeasonPick.ALL, reference = null)

        assertEquals(2, queued)
        assertEquals(listOf("1-2-720", "1-3-1080"), (2..3).map { picked(h, "tt0903747:1:$it") })
    }

    @Test
    fun theAutoRuleNeverPicksAStreamThatIsNotReadyAndNoPickFails() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams { videoId ->
            val path = videoId.substringAfter(':').replace(':', '-')
            val notReady = urlStream("https://cdn.example/$path-later.mkv", name = "Addon A").copy(description = "Not ready")
            if (videoId.endsWith(":1:2")) listOf(notReady) else listOf(notReady, urlStream("https://cdn.example/$path-ready.mkv", name = "Addon A ⚡"))
        }
        val manager = h.install()

        val queued = manager.enqueueEpisodes(h.profileId, SHOW, season = 1, SeasonPick.ALL, reference = null)
        h.settle()

        assertEquals(2, queued)
        assertEquals("1-1-ready", picked(h, "tt0903747:1:1"))
        assertEquals("1-3-ready", picked(h, "tt0903747:1:3"))
        val failed = manager.downloads(h.profileId).first().single { it.videoId == "tt0903747:1:2" }
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertEquals(DownloadFailure(DownloadFailureKind.NO_STREAM), failed.failure)
        assertEquals(DownloadStream.None, h.data.downloads.download(failed.id)?.stream)
    }

    @Test
    fun unwatchedLeavesOutWatchedEpisodesAndSpecials() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams { videoId -> releases(videoId) }
        h.data.library.markVideoWatched(h.profileId, SHOW, "tt0903747:1:1", true)
        val manager = h.install()

        val queued = manager.enqueueEpisodes(h.profileId, SHOW, season = null, SeasonPick.UNWATCHED, reference = null)
        h.settle()

        assertEquals(4, queued)
        assertEquals(
            listOf("tt0903747:1:2", "tt0903747:1:3", "tt0903747:2:1", "tt0903747:2:2"),
            manager.downloads(h.profileId).first().sortedBy { it.addedAt * 100 + it.id }.map { it.videoId },
        )
    }

    @Test
    fun nextEpisodesStartAtTheLibrarysPointerAndAreReleased() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams { videoId -> releases(videoId) }
        val future = Video(id = "tt0903747:2:3", title = "Later", released = Instant.parse("2099-01-01T00:00:00Z"), seriesInfo = SeriesInfo(2, 3))
        val show = SHOW.copy(videos = SHOW.videos.dropLast(1) + future + SHOW.videos.last())
        h.data.library.upsertFromMeta(h.profileId, show.librarySnapshot())
        h.data.library.advanceToVideo(h.profileId, show, "tt0903747:1:3")
        val manager = h.install()

        val queued = manager.enqueueEpisodes(h.profileId, show, season = null, SeasonPick.NEXT_5, reference = null)
        h.settle()

        assertEquals(3, queued)
        assertEquals(setOf("tt0903747:1:3", "tt0903747:2:1", "tt0903747:2:2"), manager.downloads(h.profileId).first().map { it.videoId }.toSet())
    }

    @Test
    fun keepSyncedTopsUpAfterAWatchAndWaitsBetweenRuns() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams { videoId -> releases(videoId) }
        val manager = h.install()

        manager.keepSynced(h.profileId, SHOW, KeepSynced(2))
        h.settle()

        assertEquals(listOf(KeepSynced(2)), manager.keepSyncedRules(h.profileId).first().map { it.rule })
        val firstRun = h.data.downloads.rule(h.profileId, SHOW.id)?.lastRun
        assertEquals(h.wallClock.now, firstRun)
        assertEquals(listOf("tt0903747:1:1", "tt0903747:1:2"), episodes(manager, h))
        assertEquals(true, manager.all.value.all { it.keepSyncedEpisode })

        h.wallClock.now += 10.minutes.inWholeMilliseconds
        h.data.library.markVideoWatched(h.profileId, SHOW, "tt0903747:1:1", true)
        h.settle()
        assertEquals(listOf("tt0903747:1:1", "tt0903747:1:2", "tt0903747:1:3"), episodes(manager, h), "topped up right after the watch")
        val afterWatch = h.wallClock.now
        assertEquals(afterWatch, h.data.downloads.rule(h.profileId, SHOW.id)?.lastRun)

        h.wallClock.now += 20.minutes.inWholeMilliseconds
        h.network(online = false)
        h.network(online = true)
        assertEquals(afterWatch, h.data.downloads.rule(h.profileId, SHOW.id)?.lastRun, "30 minutes apart on an unmetered network")

        h.wallClock.now += 20.minutes.inWholeMilliseconds
        h.network(online = true, metered = true)
        assertEquals(afterWatch, h.data.downloads.rule(h.profileId, SHOW.id)?.lastRun, "3 hours apart on a metered one")

        h.network(online = true, metered = false)
        assertEquals(h.wallClock.now, h.data.downloads.rule(h.profileId, SHOW.id)?.lastRun)
        assertEquals(3, episodes(manager, h).size, "never more than the rule's unwatched count")

        manager.stopKeepSynced(h.profileId, SHOW.id, deleteDownloads = false)
        h.settle()
        assertEquals(emptyList(), manager.keepSyncedRules(h.profileId).first())
        assertEquals(3, manager.all.value.size, "stopping a rule keeps its downloads")
    }

    private suspend fun episodes(manager: DownloadManager, h: DownloadsHarness): List<String> =
        manager.downloads(h.profileId).first().map { it.videoId }.sorted()

    /** The cdn path (without `.mkv`) of the stream picked for [videoId]. */
    private suspend fun picked(h: DownloadsHarness, videoId: String): String? {
        val stored = h.data.downloads.byVideo(SHOW.id, videoId)?.stream as? DownloadStream.Picked
        val source = stored?.choice?.stream?.source as? StreamSource.Url
        return source?.url?.removePrefix("https://cdn.example/")?.removeSuffix(".mkv")
    }

    /** Two releases of every episode, 720p first, each in its binge group. */
    private fun releases(videoId: String): List<Stream> {
        val path = videoId.substringAfter(':').replace(':', '-')
        return listOf(
            urlStream("https://cdn.example/$path-720.mkv", name = "720p", bingeGroup = "rel-720"),
            urlStream("https://cdn.example/$path-1080.mkv", name = "1080p", bingeGroup = "rel-1080"),
        )
    }
}
