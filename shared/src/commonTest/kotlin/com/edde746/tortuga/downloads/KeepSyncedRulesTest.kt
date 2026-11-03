package com.edde746.tortuga.downloads

import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.urlStream
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/** Keep synced rules as the rules screen manages them: the list, pause, stop (deleting or not) and the notice. */
class KeepSyncedRulesTest {
    private val bytes = lcgBytes(100_000, seed = 17)

    @Test
    fun theListShowsEachSeriesFromWhatThisDeviceSaved() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams(::stream)
        val manager = h.install()
        manager.keepSynced(h.profileId, SOPRANOS, KeepSynced(1))
        manager.keepSynced(h.profileId, SHOW, KeepSynced(null))
        h.settle()

        h.network(online = false)
        val requests = h.http.requests.size
        val rules = manager.keepSyncedRules(h.profileId).first()

        assertEquals(
            listOf(
                KeepSyncedRule(SHOW.id, "series", "Breaking Bad", "https://img.example/bb.jpg", KeepSynced(null), paused = false),
                KeepSyncedRule(SOPRANOS.id, "series", "The Sopranos", "https://img.example/sopranos.jpg", KeepSynced(1), paused = false),
            ),
            rules,
        )
        assertEquals(requests, h.http.requests.size, "offline, the list asks nothing of the addons")
    }

    @Test
    fun aPausedRuleDoesntRunUntilResumed() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams(::stream)
        val manager = h.install()
        manager.keepSynced(h.profileId, SHOW, KeepSynced(1))
        h.settle()
        assertEquals(listOf("tt0903747:1:1"), episodes(manager, h))

        manager.pauseKeepSynced(h.profileId, SHOW.id, paused = true)
        h.settle()
        assertEquals(true, manager.keepSyncedRules(h.profileId).first().single().paused)
        val ranAt = h.data.downloads.rule(h.profileId, SHOW.id)?.lastRun

        h.wallClock.now += 4 * 60.minutes.inWholeMilliseconds
        h.data.library.markVideoWatched(h.profileId, SHOW, "tt0903747:1:1", true)
        h.settle()
        h.network(online = false)
        h.network(online = true)
        assertEquals(listOf("tt0903747:1:1"), episodes(manager, h), "paused: neither a watch nor the network tops it up")
        assertEquals(ranAt, h.data.downloads.rule(h.profileId, SHOW.id)?.lastRun)

        manager.pauseKeepSynced(h.profileId, SHOW.id, paused = false)
        h.settle()
        assertEquals(false, manager.keepSyncedRules(h.profileId).first().single().paused)
        assertEquals(listOf("tt0903747:1:1", "tt0903747:1:2"), episodes(manager, h), "resumed, it runs at once")
        assertNull(manager.keepSyncedNotice(h.profileId).first(), "a run the user asked for isn't announced")
    }

    @Test
    fun choosingACountRunsAPausedRuleAgain() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams(::stream)
        val manager = h.install()
        manager.keepSynced(h.profileId, SHOW, KeepSynced(1))
        manager.pauseKeepSynced(h.profileId, SHOW.id, paused = true)
        h.settle()

        manager.keepSynced(h.profileId, SHOW, KeepSynced(3))
        h.settle()

        assertEquals(KeepSyncedRule(SHOW.id, "series", "Breaking Bad", "https://img.example/bb.jpg", KeepSynced(3), paused = false), manager.keepSyncedRules(h.profileId).first().single())
        assertEquals(listOf("tt0903747:1:1", "tt0903747:1:2", "tt0903747:1:3"), episodes(manager, h))
    }

    @Test
    fun stoppingCanDeleteWhatTheRuleQueuedButNotWhatWasAskedForByHandNorTheOnePlaying() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams(::stream)
        val manager = h.install()
        val byHand = manager.enqueue(h.profileId, SHOW, "tt0903747:2:2", cdnChoice("2-2.mkv")).id
        // The episode asked for by hand counts towards the rule's 4: it queues S01E01 to S01E03.
        manager.keepSynced(h.profileId, SHOW, KeepSynced(4))
        h.settle()
        val ids = manager.downloads(h.profileId).first().associate { it.videoId to it.id }
        manager.enqueue(h.profileId, SHOW, "tt0903747:1:2", cdnChoice("1-2.mkv"))
        manager.playbackStarted(ids.getValue("tt0903747:1:1"))
        h.settle()

        manager.stopKeepSynced(h.profileId, SHOW.id, deleteDownloads = true)
        h.settle()

        assertEquals(emptyList(), manager.keepSyncedRules(h.profileId).first())
        assertNull(h.download(ids.getValue("tt0903747:1:3")), "the rule's download goes")
        assertNotNull(h.download(ids.getValue("tt0903747:1:2")), "an episode of the rule's also asked for by hand stays")
        assertNotNull(h.download(byHand), "a download asked for by hand stays")
        assertNotNull(h.download(ids.getValue("tt0903747:1:1")), "the one playing stays while it plays")

        manager.playbackEnded(ids.getValue("tt0903747:1:1"))
        h.settle()
        assertNull(h.download(ids.getValue("tt0903747:1:1")), "and goes once it ends")
        assertEquals(listOf("tt0903747:1:2", "tt0903747:2:2"), episodes(manager, h))
    }

    @Test
    fun stoppingLeavesWhatAnotherProfileOwns() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams(::stream)
        val other = h.createProfile("Sam")
        val manager = h.install()
        manager.keepSynced(h.profileId, SHOW, KeepSynced(1))
        manager.keepSynced(other, SHOW, KeepSynced(1))
        h.settle()

        manager.stopKeepSynced(h.profileId, SHOW.id, deleteDownloads = true)
        h.settle()

        assertEquals(emptyList(), episodes(manager, h))
        assertEquals(listOf("tt0903747:1:1"), manager.downloads(other).first().map { it.videoId }, "the other profile's rule keeps it")
        assertEquals(1, manager.keepSyncedRules(other).first().size)
    }

    @Test
    fun runsOnTheirOwnLeaveANoticeUntilItIsSeen() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams(::stream)
        val manager = h.install()
        manager.keepSynced(h.profileId, SHOW, KeepSynced(1))
        h.settle()
        assertNull(manager.keepSyncedNotice(h.profileId).first(), "setting a rule isn't announced")

        h.data.library.markVideoWatched(h.profileId, SHOW, "tt0903747:1:1", true)
        h.settle()
        assertEquals(KeepSyncedNotice(1, listOf("Breaking Bad")), manager.keepSyncedNotice(h.profileId).first())

        h.data.library.markVideoWatched(h.profileId, SHOW, "tt0903747:1:2", true)
        h.settle()
        assertEquals(KeepSyncedNotice(2, listOf("Breaking Bad")), manager.keepSyncedNotice(h.profileId).first(), "it adds up until seen")

        manager.dismissKeepSyncedNotice(h.profileId)
        assertNull(manager.keepSyncedNotice(h.profileId).first())
    }

    private suspend fun episodes(manager: DownloadManager, h: DownloadsHarness): List<String> =
        manager.downloads(h.profileId).first().map { it.videoId }.sorted()

    private fun stream(videoId: String): List<Stream> =
        listOf(urlStream("https://cdn.example/${videoId.replace(':', '-')}.mkv", name = "720p", bingeGroup = "rel-720"))

    private companion object {
        val SOPRANOS = MetaItem(
            MetaItemPreview(id = "tt0141842", type = "series", name = "The Sopranos", poster = "https://img.example/sopranos.jpg"),
            videos = listOf(Video(id = "tt0141842:1:1", title = "Pilot", seriesInfo = SeriesInfo(1, 1))),
        )
    }
}
