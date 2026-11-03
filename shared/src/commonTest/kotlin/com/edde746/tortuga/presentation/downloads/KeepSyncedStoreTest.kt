package com.edde746.tortuga.presentation.downloads

import com.edde746.tortuga.downloads.KeepSynced
import com.edde746.tortuga.downloads.SHOW
import com.edde746.tortuga.downloads.cdnChoice
import com.edde746.tortuga.downloads.complete
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.urlStream
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The Keep synced screen over a real DownloadManager and a scripted transport: each rule, its state and its stop. */
class KeepSyncedStoreTest {
    private val bytes = lcgBytes(100_000, seed = 29)

    private class Host : DownloadsHost {
        val opened = mutableListOf<String>()

        override fun play(request: PlayRequest) = Unit

        override fun playWith(request: PlayRequest, playerId: String) = Unit

        override fun openMeta(type: String, id: String) {
            opened += "$type/$id"
        }

        override fun openDownloadStreams(meta: MetaItem, videoId: String) = Unit

        override fun openKeepSynced() {
            opened += "keepSynced"
        }
    }

    @Test
    fun aRuleSaysItsCountAndWhereItsEpisodesArePausesResumesAndOpensItsSeries() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams(::stream)
        val manager = h.install()
        val host = Host()
        val store = assertNotNull(h.graph.keepSyncedStore(h.profileId, host))
        h.settle()
        assertEquals(KeepSyncedBody.Empty(T.downloads.keepSynced.empty), store.state.value.body)
        assertNull(store.state.value.note)

        manager.keepSynced(h.profileId, SHOW, KeepSynced(3))
        h.settle()
        fun row() = assertIs<KeepSyncedBody.Rules>(store.state.value.body).rules.single()
        assertEquals(T.downloads.keepSynced.note, store.state.value.note)
        assertEquals(SHOW.id, row().metaId)
        assertEquals("Breaking Bad", row().title)
        assertEquals("https://img.example/bb.jpg", row().poster)
        assertEquals(T.detail.download.keepSyncedCount(3), row().countText)
        assertEquals(T.downloads.keepSynced.status.downloading(3), row().statusText)
        assertEquals(T.downloads.keepSynced.pauseRule(isolate("Breaking Bad")), row().pauseLabel)
        assertEquals(T.downloads.keepSynced.stopRule(isolate("Breaking Bad")), row().stopLabel)

        h.network(online = false)
        assertEquals(T.downloads.status.waitingForNetwork, row().statusText)
        h.network(online = true)
        repeat(3) {
            h.complete(h.fakeTransport.started.last())
            h.settle()
        }
        assertEquals(T.downloads.keepSynced.status.downloaded(3), row().statusText)

        store.pause(SHOW.id)
        h.settle()
        assertEquals(true, row().paused)
        assertEquals(T.downloads.status.paused, row().statusText)
        assertEquals(T.downloads.keepSynced.resumeRule(isolate("Breaking Bad")), row().pauseLabel)
        store.resume(SHOW.id)
        h.settle()
        assertEquals(false, row().paused)
        assertEquals(T.downloads.keepSynced.status.downloaded(3), row().statusText)

        store.open(SHOW.id)
        assertEquals(listOf("series/${SHOW.id}"), host.opened)
        assertNull(store.state.value.notice)
        store.release()
    }

    @Test
    fun stopAsksFirstThenCanDeleteWhatTheRuleQueued() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams(::stream)
        val manager = h.install()
        val byHand = manager.enqueue(h.profileId, SHOW, "tt0903747:2:2", cdnChoice("2-2.mkv")).id
        // The episode asked for by hand counts towards the rule's 3: it queues two.
        manager.keepSynced(h.profileId, SHOW, KeepSynced(3))
        h.settle()
        val store = assertNotNull(h.graph.keepSyncedStore(h.profileId, Host()))
        h.settle()

        store.stop(SHOW.id)
        val text = T.downloads.keepSynced.stop
        assertEquals(
            KeepSyncedStopDialog(text.title(isolate("Breaking Bad")), text.message(2), text.keep, text.delete, text.cancel),
            store.state.value.stopDialog,
        )
        store.dismissStop()
        h.settle()
        assertNull(store.state.value.stopDialog)
        assertEquals(1, assertIs<KeepSyncedBody.Rules>(store.state.value.body).rules.size, "dismissed, the rule stays")

        store.stop(SHOW.id)
        store.confirmStop(deleteDownloads = true)
        h.settle()
        assertNull(store.state.value.stopDialog)
        assertEquals(KeepSyncedBody.Empty(T.downloads.keepSynced.empty), store.state.value.body)
        assertEquals(listOf(byHand), manager.downloads(h.profileId).first().map { it.id }, "only the download asked for by hand stays")
        store.release()
    }

    @Test
    fun aRuleThatQueuedNothingStopsWithoutOfferingToDelete() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams(::stream)
        val manager = h.install()
        manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1-1.mkv"))
        h.settle()
        manager.keepSynced(h.profileId, SHOW, KeepSynced(1))
        h.settle()
        val store = assertNotNull(h.graph.keepSyncedStore(h.profileId, Host()))
        h.settle()

        store.stop(SHOW.id)
        val text = T.downloads.keepSynced.stop
        assertEquals(KeepSyncedStopDialog(text.title(isolate("Breaking Bad")), text.messageNone, text.confirm, null, text.cancel), store.state.value.stopDialog)
        store.confirmStop(deleteDownloads = false)
        h.settle()
        assertEquals(KeepSyncedBody.Empty(T.downloads.keepSynced.empty), store.state.value.body)
        assertEquals(1, manager.downloads(h.profileId).first().size)
        store.release()
    }

    @Test
    fun downloadsLeadsToTheScreenWhileTheProfileHasRules() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        h.serveStreams(::stream)
        val manager = h.install()
        val host = Host()
        val downloads = assertNotNull(h.graph.downloadsStore(h.profileId, host))
        h.settle()
        assertNull(downloads.state.value.keepSynced, "no rules, no row")

        manager.keepSynced(h.profileId, SHOW, KeepSynced(1))
        h.settle()
        assertEquals(KeepSyncedLink(T.downloads.keepSynced.title, T.downloads.keepSynced.series(1)), downloads.state.value.keepSynced)
        downloads.openKeepSynced()
        assertEquals(listOf("keepSynced"), host.opened)

        manager.stopKeepSynced(h.profileId, SHOW.id, deleteDownloads = false)
        h.settle()
        assertNull(downloads.state.value.keepSynced)
        downloads.release()
    }

    private fun stream(videoId: String): List<Stream> =
        listOf(urlStream("https://cdn.example/${videoId.replace(':', '-')}.mkv", name = "720p", bingeGroup = "rel-720"))
}
