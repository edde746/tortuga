package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.data.library.librarySnapshot
import com.edde746.tortuga.downloads.FILM
import com.edde746.tortuga.downloads.SHOW
import com.edde746.tortuga.downloads.cdnChoice
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.STREAM_ADDON
import com.edde746.tortuga.presentation.downloads.DownloadDeleteDialog
import com.edde746.tortuga.presentation.downloads.downloaded
import com.edde746.tortuga.presentation.downloads.installMetaAddon
import com.edde746.tortuga.presentation.downloads.serveMeta
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.profiles.ParentCheck
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Detail over a real DownloadManager: Play, Resume and an episode play the download; the saved title stands in offline. */
class DetailStoreDownloadsTest {
    private val bytes = lcgBytes(100_000, seed = 7)

    private class Host : DetailHost {
        val streams = mutableListOf<String>()
        val played = mutableListOf<PlayRequest>()

        override fun openStreams(meta: MetaItem, videoId: String, notice: String?, fromStart: Boolean) {
            streams += "${meta.id}/$videoId"
        }

        override fun openDownloadStreams(meta: MetaItem, videoId: String) {
            streams += "download ${meta.id}/$videoId"
        }

        override fun openKeepSynced() {
            streams += "keepSynced"
        }

        override fun play(request: PlayRequest) {
            played += request
        }

        override fun openUrl(url: String) = Unit

        override fun openDeepLink(link: DeepLink) = Unit

        override fun showTitle(entryId: String, type: String, id: String, shownVideoId: String?, preview: MetaItemPreview?) = Unit

        override fun openMeta(type: String, id: String, preview: MetaItemPreview?) = Unit

        override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) = Unit

        override fun askParent(check: ParentCheck, onUnlocked: suspend () -> Unit) = Unit
    }

    @Test
    fun playResumeAndAnEpisodePlayTheDownloadAndChoosingAStreamStillStreams() = downloadsTest { h ->
        h.serveCdn(bytes)
        h.serveMeta(SHOW)
        h.installMetaAddon()
        val manager = h.install()
        h.downloaded(SHOW, "tt0903747:1:1", cdnChoice("1-1.mkv"))
        val local = assertNotNull(manager.localVideo(h.profileId, SHOW.id, "tt0903747:1:1"))
        val host = Host()
        val store = h.graph.detailStore(h.profileId, "series", SHOW.id, videoId = null, host)
        h.settle()
        assertEquals("S1E1", store.state.value.primaryAction?.label)

        store.play()
        h.settle()
        val played = host.played.single()
        assertEquals(local, played.local)
        assertEquals("tt0903747:1:1", played.videoId)
        assertEquals(SHOW.id, played.meta.id)
        assertEquals(cdnChoice("1-1.mkv"), played.stream, "the stream it was downloaded from, for the history")

        // Resume plays the download too (the player resumes it from the library's position).
        h.data.library.upsertFromMeta(h.profileId, SHOW.librarySnapshot())
        h.data.library.recordProgress(h.profileId, SHOW.id, "tt0903747:1:1", 600_000, 2_700_000)
        h.settle()
        assertTrue(store.state.value.primaryAction!!.resume)
        store.play()
        h.settle()
        assertEquals(listOf(local, local), host.played.map { it.local })

        // An episode plays its download, or opens its stream list without one.
        store.openEpisode("tt0903747:1:1")
        h.settle()
        assertEquals(local, host.played.last().local)
        store.openEpisode("tt0903747:1:2")
        h.settle()
        assertEquals(3, host.played.size)
        assertEquals(listOf("tt0903747/tt0903747:1:2"), host.streams)

        // Choose a stream opens the list, download or not.
        store.chooseStream()
        h.settle()
        assertEquals(listOf("tt0903747/tt0903747:1:2", "tt0903747/tt0903747:1:1"), host.streams)
        assertEquals(3, host.played.size)
    }

    @Test
    fun aMoviesDownloadPlaysForTheProfilesThatOwnItAndItsButtonFollowsIt() = downloadsTest { h ->
        h.serveCdn(bytes)
        h.serveMeta(FILM)
        h.installMetaAddon()
        val other = h.createProfile("Sam", listOf(STREAM_ADDON))
        h.installMetaAddon(other)
        h.install()
        val download = h.downloaded(FILM, FILM.id, cdnChoice())
        val mine = Host()
        val store = h.graph.detailStore(h.profileId, "movie", FILM.id, videoId = null, mine)
        val theirs = Host()
        val theirStore = h.graph.detailStore(other, "movie", FILM.id, videoId = null, theirs)
        h.settle()
        assertEquals(DetailDownloadState.DOWNLOADED, store.state.value.download?.state)
        assertEquals(DetailDownloadState.AVAILABLE, theirStore.state.value.download?.state, "another profile's download isn't its own")

        store.play()
        theirStore.play()
        h.settle()
        assertEquals(download.id, mine.played.single().local?.downloadId)
        assertEquals(emptyList(), theirs.played)
        assertEquals(listOf("tt0111161/tt0111161"), theirs.streams)

        // Download picks the stream to download; Delete download lets the file go and Play streams again.
        theirStore.pressDownload()
        assertEquals("download tt0111161/tt0111161", theirs.streams.last())
        store.downloadAction(DetailDownloads.DELETE)
        h.settle()
        assertEquals(DetailDownloadState.AVAILABLE, store.state.value.download?.state)
        assertNull(h.download(download.id))
        store.play()
        h.settle()
        assertEquals(1, mine.played.size)
        assertEquals(listOf("tt0111161/tt0111161"), mine.streams)
    }

    @Test
    fun aPlayStillLookingForItsDownloadPlaysNothingOnceAStreamIsChosenOrThePageIsCovered() = downloadsTest { h ->
        h.serveCdn(bytes)
        h.serveMeta(FILM)
        h.installMetaAddon()
        h.install()
        h.downloaded(FILM, FILM.id, cdnChoice())
        val covered = MutableStateFlow(false)
        val host = Host()
        val store = h.graph.detailStore(h.profileId, "movie", FILM.id, videoId = null, host, covered = covered)
        h.settle()

        // Play, then Choose a stream before the download is found: the list opens and nothing plays.
        store.play()
        store.chooseStream()
        h.settle()
        assertEquals(listOf("tt0111161/tt0111161"), host.streams)
        assertEquals(emptyList(), host.played)

        // Play, then another screen over the page before the download is found: nothing plays.
        store.play()
        covered.value = true
        h.settle()
        assertEquals(emptyList(), host.played)

        covered.value = false
        h.settle()
        store.play()
        h.settle()
        assertEquals(1, host.played.size, "a play left alone plays the download")
    }

    @Test
    fun menuEntriesPlayTheDownloadFromTheStartAndOpenTheListToDownloadFrom() = downloadsTest { h ->
        h.serveCdn(bytes)
        h.serveMeta(SHOW, FILM)
        h.installMetaAddon()
        val manager = h.install()
        h.downloaded(SHOW, "tt0903747:1:1", cdnChoice("1-1.mkv"))
        val local = assertNotNull(manager.localVideo(h.profileId, SHOW.id, "tt0903747:1:1"))

        // A Continue Watching card's Play from the beginning plays its episode's download from 0.
        val resumed = Host()
        h.graph.detailStore(h.profileId, "series", SHOW.id, videoId = "tt0903747:1:1", resumed, videoAction = DetailVideoAction.PLAY_FROM_START)
        // Download opens the list to download from: a movie card's for the movie, a Continue Watching card's for its episode.
        val film = Host()
        h.graph.detailStore(h.profileId, "movie", FILM.id, videoId = null, film, videoAction = DetailVideoAction.DOWNLOAD)
        val episode = Host()
        val store = h.graph.detailStore(h.profileId, "series", SHOW.id, videoId = "tt0903747:1:2", episode, videoAction = DetailVideoAction.DOWNLOAD)
        h.settle()
        val played = resumed.played.single()
        assertEquals(local, played.local)
        assertEquals(0.0, played.startPositionSeconds)
        assertEquals(emptyList(), resumed.streams)
        assertEquals(listOf("download tt0111161/tt0111161"), film.streams)
        assertEquals(listOf("download tt0903747/tt0903747:1:2"), episode.streams)

        // An episode's menu holds its download's entries, which act on it.
        fun menu(videoId: String) = store.state.value.episodes.single { it.videoId == videoId }.menu.map { it.action }
        assertEquals(listOf(MediaAction.MARK_WATCHED, MediaAction.MARK_SEASON_WATCHED, MediaAction.DELETE_DOWNLOAD), menu("tt0903747:1:1"))
        assertEquals(listOf(MediaAction.MARK_WATCHED, MediaAction.MARK_SEASON_WATCHED, MediaAction.DOWNLOAD), menu("tt0903747:1:3"))
        store.episodeMenuAction("tt0903747:1:3", MediaAction.DOWNLOAD)
        assertEquals("download tt0903747/tt0903747:1:3", episode.streams.last())
        store.episodeMenuAction("tt0903747:1:1", MediaAction.DELETE_DOWNLOAD)
        h.settle()
        assertNull(manager.localVideo(h.profileId, SHOW.id, "tt0903747:1:1"))
        assertEquals(MediaAction.DOWNLOAD, menu("tt0903747:1:1").last())
    }

    @Test
    fun deletingASeasonsOrTheWholeTitlesDownloadsAsksFirstAndCancelKeepsTheirFiles() = downloadsTest { h ->
        h.serveCdn(bytes)
        h.serveMeta(SHOW)
        h.installMetaAddon()
        val manager = h.install()
        val videos = listOf("tt0903747:1:1", "tt0903747:1:2", "tt0903747:2:1")
        videos.forEachIndexed { index, videoId -> h.downloaded(SHOW, videoId, cdnChoice("$index.mkv")) }
        val store = h.graph.detailStore(h.profileId, "series", SHOW.id, videoId = null, Host())
        h.settle()
        suspend fun kept() = videos.map { manager.localVideo(h.profileId, SHOW.id, it) != null }
        assertEquals(1, store.state.value.selectedSeason)

        // Delete this season's downloads: nothing goes before the answer.
        store.downloadAction(DetailDownloads.DELETE_SEASON)
        h.settle()
        assertEquals(listOf(true, true, true), kept(), "asked, not deleted")
        val text = T.downloads.deleteConfirm
        assertEquals(
            DownloadDeleteDialog(text.title(T.downloads.episodes(2), isolate(SHOW.preview.name)), text.message, text.confirm, text.cancel),
            store.state.value.downloadDelete,
        )
        store.dismissDownloadDelete()
        h.settle()
        assertNull(store.state.value.downloadDelete)
        assertEquals(listOf(true, true, true), kept(), "Cancel keeps them")

        store.downloadAction(DetailDownloads.DELETE_SEASON)
        store.confirmDownloadDelete()
        h.settle()
        assertNull(store.state.value.downloadDelete)
        assertEquals(listOf(false, false, true), kept(), "Delete takes the season's")

        // Delete all downloads asks about what is left of the title.
        store.downloadAction(DetailDownloads.DELETE_ALL)
        h.settle()
        assertEquals(text.title(T.downloads.episodes(1), isolate(SHOW.preview.name)), store.state.value.downloadDelete?.title)
        assertEquals(listOf(false, false, true), kept(), "asked, not deleted")
        store.confirmDownloadDelete()
        h.settle()
        assertEquals(listOf(false, false, false), kept())
        assertNull(store.state.value.downloadDelete)
    }

    @Test
    fun offlineTheTitleSavedWithItsDownloadsShowsAndTheAddonsAnswerOnceBack() = downloadsTest { h ->
        var reachable = true
        h.serveCdn(bytes)
        h.serveMeta(MetaItem(FILM.preview.copy(name = "The Shawshank Redemption (addon)")), reachable = { reachable })
        h.installMetaAddon()
        h.install()
        val download = h.downloaded(FILM, FILM.id, cdnChoice())

        reachable = false
        h.network(online = false)
        val host = Host()
        val store = h.graph.detailStore(h.profileId, "movie", FILM.id, videoId = null, host)
        // A link to the video opens its stream list at once, over the saved title.
        val linkHost = Host()
        h.graph.detailStore(h.profileId, "movie", FILM.id, videoId = FILM.id, linkHost)
        h.settle()
        assertEquals(listOf("tt0111161/tt0111161"), linkHost.streams)
        var state = store.state.value
        assertEquals(FILM.name, (state.body as? DetailBody.Page)?.header?.title)
        assertEquals(T.offline.detail, state.offlineNote)
        assertNull((state.body as? DetailBody.Failed)?.text)
        assertEquals(DetailDownloadState.DOWNLOADED, state.download?.state)
        store.play()
        h.settle()
        assertEquals(download.id, host.played.single().local?.downloadId)

        reachable = true
        h.network(online = true)
        state = store.state.value
        assertEquals("The Shawshank Redemption (addon)", (state.body as? DetailBody.Page)?.header?.title, "the addons are asked again")
        assertNull(state.offlineNote)
        assertEquals(listOf("tt0111161/tt0111161"), linkHost.streams, "the link's list doesn't open again once back")
    }

    @Test
    fun offlineContinueWatchingPlaysTheDownloadAtOnceAndNothingAgainOnceBack() = downloadsTest { h ->
        var reachable = true
        h.serveCdn(bytes)
        h.serveMeta(SHOW, reachable = { reachable })
        h.installMetaAddon()
        val manager = h.install()
        h.downloaded(SHOW, "tt0903747:1:1", cdnChoice("1-1.mkv"))
        val local = assertNotNull(manager.localVideo(h.profileId, SHOW.id, "tt0903747:1:1"))
        h.data.library.upsertFromMeta(h.profileId, SHOW.librarySnapshot())
        h.data.library.recordProgress(h.profileId, SHOW.id, "tt0903747:1:1", 600_000, 2_700_000)

        reachable = false
        h.network(online = false)
        // Continue Watching's card: its episode plays from its download at once, over the title saved with it.
        val covered = MutableStateFlow(false)
        val host = Host()
        val store = h.graph.detailStore(h.profileId, "series", SHOW.id, "tt0903747:1:1", host, covered = covered, videoAction = DetailVideoAction.PLAY)
        h.settle()
        assertEquals(T.offline.detail, store.state.value.offlineNote)
        assertEquals(local, host.played.single().local)

        // The player covers the page; the network comes back and the addons' title replaces the saved one: the card's
        // action doesn't run again, under the player or once back on the page.
        covered.value = true
        reachable = true
        h.network(online = true)
        h.settle()
        covered.value = false
        h.settle()
        assertNull(store.state.value.offlineNote)
        assertEquals(1, host.played.size)
        assertEquals(emptyList(), host.streams)
    }

    @Test
    fun anActionTheTitleCouldNotLoadForIsDroppedNotDoneOnceTheNetworkIsBack() = downloadsTest { h ->
        var reachable = false
        h.serveMeta(FILM, reachable = { reachable })
        h.installMetaAddon()
        h.install()
        h.data.library.upsertFromMeta(h.profileId, FILM.librarySnapshot())
        h.data.library.recordProgress(h.profileId, FILM.id, FILM.id, 600_000, 2_700_000)

        // Offline, nothing downloaded: the details can't load, which the page says.
        h.network(online = false)
        val host = Host()
        val store = h.graph.detailStore(h.profileId, "movie", FILM.id, FILM.id, host, videoAction = DetailVideoAction.PLAY)
        h.settle()
        assertEquals("Couldn't load the details.", (store.state.value.body as? DetailBody.Failed)?.text)

        reachable = true
        h.network(online = true)
        h.settle()
        assertEquals(FILM.name, (store.state.value.body as? DetailBody.Page)?.header?.title)
        assertEquals(emptyList(), host.played)
        assertEquals(emptyList(), host.streams)
    }

    @Test
    fun offlineTheSavedTitleShowsEvenWhereAnAddonStillAnswers() = downloadsTest { h ->
        h.serveCdn(bytes)
        // The addon answers whatever the network state says: a cached answer, or the dev session's offline switch.
        h.serveMeta(MetaItem(FILM.preview.copy(name = "The Shawshank Redemption (addon)")))
        h.installMetaAddon()
        h.install()
        h.downloaded(FILM, FILM.id, cdnChoice())

        h.network(online = false)
        val store = h.graph.detailStore(h.profileId, "movie", FILM.id, videoId = null, Host())
        h.settle()
        var state = store.state.value
        assertEquals(FILM.name, (state.body as? DetailBody.Page)?.header?.title)
        assertEquals(T.offline.detail, state.offlineNote)

        h.network(online = true)
        h.settle()
        state = store.state.value
        assertEquals("The Shawshank Redemption (addon)", (state.body as? DetailBody.Page)?.header?.title)
        assertNull(state.offlineNote)
    }

    @Test
    fun whenEveryAddonFailsTheSavedTitleShowsAndWithoutADownloadTheError() = downloadsTest { h ->
        h.serveCdn(bytes)
        h.routes["meta.example.com"] = { respond("", HttpStatusCode.InternalServerError) }
        h.installMetaAddon()
        h.install()
        h.downloaded(FILM, FILM.id, cdnChoice())

        val saved = h.graph.detailStore(h.profileId, "movie", FILM.id, videoId = null, Host())
        val missing = h.graph.detailStore(h.profileId, "series", SHOW.id, videoId = null, Host())
        h.settle()
        assertEquals(FILM.name, (saved.state.value.body as? DetailBody.Page)?.header?.title)
        assertEquals(T.offline.detail, saved.state.value.offlineNote)
        assertNull((missing.state.value.body as? DetailBody.Page)?.header)
        assertNull(missing.state.value.offlineNote)
        assertEquals("Couldn't load the details.", (missing.state.value.body as? DetailBody.Failed)?.text)
    }
}
