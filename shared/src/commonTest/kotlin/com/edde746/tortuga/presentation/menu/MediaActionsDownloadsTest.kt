package com.edde746.tortuga.presentation.menu

import com.edde746.tortuga.data.library.librarySnapshot
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.downloads.FILM
import com.edde746.tortuga.downloads.SHOW
import com.edde746.tortuga.downloads.TransferResult
import com.edde746.tortuga.downloads.cdnChoice
import com.edde746.tortuga.downloads.complete
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.playback.resolve.lcgBytes
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A card's download entries over a real DownloadManager: each reaches the download, and the card's menu follows it. */
class MediaActionsDownloadsTest {
    @Test
    fun aCardsDownloadEntriesActOnItsDownloadAndItsMenuFollows() = downloadsTest(fake = true) { h ->
        h.serveCdn(lcgBytes(10_000, seed = 3))
        val manager = h.install()
        val actions = MediaActions(h.profileId, h.data.library, trackerMarks = null, h.graph.scope, notice = {}, downloads = manager) { _, _ -> null }
        val marks = assertNotNull(actions.downloadMarks)
        val film = FILM.librarySnapshot()
        suspend fun movie() = marks.first().menuDownload("movie", FILM.id, videoId = null)
        assertEquals(MenuDownload.NONE, movie())

        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        assertEquals(MenuDownload.ACTIVE, movie())
        actions.carryOut(film, videoId = null, MediaAction.PAUSE_DOWNLOAD)
        h.settle()
        assertEquals(DownloadStatus.PAUSED, h.download(id)?.status)
        assertEquals(MenuDownload.PAUSED, movie())
        actions.carryOut(film, videoId = null, MediaAction.RESUME_DOWNLOAD)
        h.settle()
        assertEquals(2, h.fakeTransport.started.size, "the transfer started again")
        assertEquals(MenuDownload.ACTIVE, movie())
        actions.carryOut(film, videoId = null, MediaAction.CANCEL_DOWNLOAD)
        h.settle()
        assertEquals(DownloadStatus.CANCELLED, h.download(id)?.status)
        assertEquals(MenuDownload.NONE, movie(), "cancelled: Download again")

        // A Continue Watching card's entries are its episode's.
        val show = SHOW.librarySnapshot()
        val episode = "tt0903747:1:2"
        suspend fun card() = marks.first().menuDownload("series", SHOW.id, episode)
        val episodeId = manager.enqueue(h.profileId, SHOW, episode, cdnChoice("1-2.mkv")).id
        h.settle()
        h.fakeTransport.finish(h.fakeTransport.started.last().id, TransferResult.Http(400))
        h.settle()
        assertEquals(MenuDownload.FAILED, card())
        actions.carryOut(show, episode, MediaAction.RETRY_DOWNLOAD)
        h.settle()
        assertEquals(episodeId.toString(), h.fakeTransport.started.last().id, "queued again")
        h.complete(h.fakeTransport.started.last())
        assertEquals(MenuDownload.DOWNLOADED, card())
        actions.carryOut(show, episode, MediaAction.DELETE_DOWNLOAD)
        h.settle()
        assertNull(h.download(episodeId))
        assertEquals(MenuDownload.NONE, card())
    }
}
