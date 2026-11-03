package com.edde746.tortuga.presentation.downloads

import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.downloads.DownloadStorage
import com.edde746.tortuga.downloads.FILM
import com.edde746.tortuga.downloads.SHOW
import com.edde746.tortuga.downloads.SeasonPick
import com.edde746.tortuga.downloads.cdnChoice
import com.edde746.tortuga.downloads.complete
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The Downloads screen over a real DownloadManager and a scripted transport: each action reaches the queue. */
class DownloadsStoreTest {
    private val bytes = lcgBytes(100_000, seed = 13)

    private class Host : DownloadsHost {
        val played = mutableListOf<PlayRequest>()
        val opened = mutableListOf<String>()

        override fun play(request: PlayRequest) {
            played += request
        }

        val playedWith = mutableListOf<Pair<PlayRequest, String>>()

        override fun playWith(request: PlayRequest, playerId: String) {
            playedWith += request to playerId
        }

        override fun openMeta(type: String, id: String) {
            opened += "$type/$id"
        }

        override fun openDownloadStreams(meta: MetaItem, videoId: String) {
            opened += "download ${meta.id}/$videoId (${meta.videos.size} videos)"
        }

        override fun openKeepSynced() {
            opened += "keepSynced"
        }
    }

    @Test
    fun aRowPausesResumesCancelsRetriesPlaysAndIsDeleted() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        val host = Host()
        val store = assertNotNull(h.graph.downloadsStore(h.profileId, host))
        h.settle()
        fun row() = store.state.value.groups().single().movie!!
        var state = store.state.value
        assertEquals(DownloadStatus.DOWNLOADING, row().status)
        assertEquals(T.downloads.pauseAll, state.pauseAllText)
        assertNull(state.resumeAllText)

        store.perform(id, DownloadAction.PAUSE)
        h.settle()
        state = store.state.value
        assertEquals(DownloadStatus.PAUSED, row().status)
        assertEquals(listOf(id.toString()), h.fakeTransport.stopped)
        assertEquals(listOf(DownloadAction.RESUME, DownloadAction.CANCEL, DownloadAction.OPEN_DETAIL), row().actions.map { it.action })
        assertNull(state.pauseAllText)
        assertEquals(T.downloads.resumeAll, state.resumeAllText)

        store.perform(id, DownloadAction.RESUME)
        h.settle()
        assertEquals(DownloadStatus.DOWNLOADING, row().status)
        assertEquals(2, h.fakeTransport.started.size)

        // A row that isn't downloaded opens its title.
        store.open(id)
        store.perform(id, DownloadAction.OPEN_DETAIL)
        assertEquals(listOf("movie/${FILM.id}", "movie/${FILM.id}"), host.opened)

        store.perform(id, DownloadAction.CANCEL)
        h.settle()
        assertEquals(DownloadStatus.CANCELLED, row().status)
        assertEquals(listOf(DownloadAction.RETRY, DownloadAction.DELETE, DownloadAction.OPEN_DETAIL), row().actions.map { it.action })

        store.perform(id, DownloadAction.RETRY)
        h.settle()
        assertEquals(DownloadStatus.DOWNLOADING, row().status)
        h.complete(h.fakeTransport.started.last())
        assertEquals(DownloadStatus.COMPLETED, row().status)

        // A downloaded row plays its file, with the title saved with it.
        store.open(id)
        h.settle()
        store.perform(id, DownloadAction.PLAY)
        h.settle()
        val local = assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id))
        assertEquals(listOf(local, local), host.played.map { it.local })
        assertEquals(FILM.preview.releaseInfo, host.played.first().meta.preview.releaseInfo, "the saved meta, not a stand-in")
        assertEquals(FILM.id, host.played.first().videoId)

        store.perform(id, DownloadAction.DELETE)
        h.settle()
        state = store.state.value
        assertEquals(DownloadsBody.Empty(T.downloads.empty), state.body)
        assertNull(manager.localVideo(h.profileId, FILM.id, FILM.id))
    }

    @Test
    fun aCommandThatFailsSaysSo() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        val store = assertNotNull(h.graph.downloadsStore(h.profileId, Host()))
        h.settle()
        store.perform(id, DownloadAction.PAUSE)
        h.settle()
        assertNull(store.state.value.notice)

        // Cancelling removes the partial file, from a location that can't be read.
        h.platform.locationFailures = 1
        store.perform(id, DownloadAction.CANCEL)
        h.settle()
        assertEquals(0, h.platform.locationFailures, "cancelling read the locations")
        assertEquals(DownloadStatus.PAUSED, h.download(id)?.status)
        assertEquals(T.downloads.actionFailed, store.state.value.notice)

        store.dismissNotice()
        assertNull(store.state.value.notice)
    }

    /**
     * A downloaded row whose file is gone fails as it plays, saying why, and nothing opens: its location gone ("Play
     * with…"), then its file deleted (a press). Retry downloads it again each time.
     */
    @Test
    fun playingADownloadWhoseFileIsGoneFailsItsRow() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        h.complete(h.fakeTransport.started.single())
        val host = Host()
        val store = assertNotNull(h.graph.downloadsStore(h.profileId, host))
        h.settle()
        fun row() = store.state.value.groups().single().movie!!
        assertEquals(DownloadStatus.COMPLETED, row().status)

        // Its location gone (a card removed): the row fails, says so and offers Retry and Delete.
        val locations = h.platform.locationList
        h.platform.locationList = emptyList()
        store.playWith(id, "org.videolan.vlc")
        h.settle()
        assertEquals(DownloadStatus.FAILED, row().status, "not left Downloaded with a Play that does nothing")
        assertEquals(T.downloads.failure.locationUnavailable, row().failureText)
        assertEquals(listOf(DownloadAction.RETRY, DownloadAction.DELETE, DownloadAction.OPEN_DETAIL), row().actions.map { it.action })
        assertEquals(emptyList(), host.playedWith)

        h.platform.locationList = locations
        store.perform(id, DownloadAction.RETRY)
        h.settle()
        h.complete(h.fakeTransport.started.last())
        assertEquals(DownloadStatus.COMPLETED, row().status)

        // Its file deleted outside the app: the row fails as missing, nothing playing and nothing opening.
        SystemFileSystem.delete(Path(assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id)).path))
        store.open(id)
        h.settle()
        assertEquals(DownloadStatus.FAILED, row().status)
        assertEquals("FILE_MISSING", h.download(id)?.failure?.kind?.name)
        assertEquals(emptyList(), host.played)
        assertEquals(emptyList(), host.opened)

        store.perform(id, DownloadAction.RETRY)
        h.settle()
        h.complete(h.fakeTransport.started.last())
        store.open(id)
        h.settle()
        assertEquals(listOf(assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id))), host.played.map { it.local })
    }

    @Test
    fun pauseAllResumeAllAndDeletingAGroup() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val movie = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.wallClock.now += 1000
        val pilot = manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1-1.mkv")).id
        h.wallClock.now += 1000
        val second = manager.enqueue(h.profileId, SHOW, "tt0903747:1:2", cdnChoice("1-2.mkv")).id
        h.settle()
        val store = assertNotNull(h.graph.downloadsStore(h.profileId, Host()))
        h.settle()
        fun statuses() = listOf(movie, pilot, second).map { h.download(it)?.status }
        assertEquals(listOf(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.QUEUED), statuses())

        store.pauseAll()
        h.settle()
        assertEquals(List(3) { DownloadStatus.PAUSED }, statuses())
        assertNull(store.state.value.pauseAllText)
        assertEquals(T.downloads.resumeAll, store.state.value.resumeAllText)

        store.resumeAll()
        h.settle()
        assertEquals(
            listOf(DownloadStatus.QUEUED, DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING),
            statuses().sortedBy { it?.ordinal },
            "one at a time again",
        )
        assertNull(store.state.value.resumeAllText)

        val series = store.state.value.groups().single { it.key == SHOW.id }
        assertEquals(listOf("tt0903747:1:1", "tt0903747:1:2"), series.seasons.single().episodes.map { it.videoId })
        // A group's delete asks first: nothing goes before the answer, and Cancel keeps it all.
        store.deleteGroup(series.key)
        h.settle()
        assertEquals(listOf(true, true), listOf(pilot, second).map { h.download(it) != null }, "asked, not deleted")
        val text = T.downloads.deleteConfirm
        assertEquals(
            DownloadDeleteDialog(text.title(T.downloads.episodes(2), isolate(SHOW.preview.name)), text.message, text.confirm, text.cancel),
            store.state.value.deleteDialog,
        )
        store.dismissDeleteGroup()
        h.settle()
        assertNull(store.state.value.deleteDialog)
        assertEquals(listOf(true, true), listOf(pilot, second).map { h.download(it) != null }, "Cancel keeps them")

        store.deleteGroup(series.key)
        store.confirmDeleteGroup()
        h.settle()
        assertNull(store.state.value.deleteDialog)
        assertEquals(listOf(FILM.id), store.state.value.groups().map { it.key })
        assertNull(h.download(pilot))
        assertNull(h.download(second))
    }

    @Test
    fun anEpisodeNoStreamWasPickedForPicksOneAndTheStorageFollowsTheDisk() = downloadsTest(fake = true) { h ->
        h.serveStreams { emptyList() }
        val manager = h.install()
        assertEquals(0, manager.enqueueEpisodes(h.profileId, SHOW, season = 2, SeasonPick.ALL, reference = null))
        h.settle()
        val host = Host()
        val store = assertNotNull(h.graph.downloadsStore(h.profileId, host))
        h.settle()
        val season = store.state.value.groups().single().seasons.single()
        assertEquals(2, season.season)
        val row = season.episodes.first()
        assertEquals("tt0903747:2:1", row.videoId)
        assertEquals(DownloadStatus.FAILED, row.status)
        assertEquals(listOf(DownloadAction.PICK_STREAM, DownloadAction.DELETE, DownloadAction.OPEN_DETAIL), row.actions.map { it.action })

        store.open(row.id)
        h.settle()
        store.perform(row.id, DownloadAction.PICK_STREAM)
        h.settle()
        val pick = "download ${SHOW.id}/tt0903747:2:1 (${SHOW.videos.size} videos)"
        assertEquals(listOf(pick, pick), host.opened, "the Streams sheet in download mode, over the title saved with it")

        h.platform.free = 5L shl 30
        store.refreshStorage()
        h.settle()
        assertEquals(DownloadRows.storage(DownloadStorage(manager.storage.value.usedBytes, 5L shl 30)), store.state.value.storage)
    }
}

private fun DownloadsUiState.groups(): List<DownloadGroup> = assertIs<DownloadsBody.Groups>(body).groups
