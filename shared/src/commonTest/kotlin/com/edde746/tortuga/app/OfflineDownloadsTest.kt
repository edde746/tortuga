package com.edde746.tortuga.app

import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.downloads.FILM
import com.edde746.tortuga.downloads.SHOW
import com.edde746.tortuga.downloads.cdnChoice
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.json
import com.edde746.tortuga.presentation.board.BoardBody
import com.edde746.tortuga.presentation.board.OfflineState
import com.edde746.tortuga.presentation.board.RowStatus
import com.edde746.tortuga.presentation.board.rows
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.discover.DiscoverBody
import com.edde746.tortuga.presentation.downloads.DownloadsBody
import com.edde746.tortuga.presentation.downloads.downloaded
import com.edde746.tortuga.presentation.downloads.installMetaAddon
import com.edde746.tortuga.presentation.downloads.serveMeta
import com.edde746.tortuga.presentation.navigation.LibrarySection
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.search.SearchBody
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app offline over a real DownloadManager: Library opens on Downloads, downloads play, a reconnect brings the rest back. */
class OfflineDownloadsTest {
    private val bytes = lcgBytes(100_000, seed = 19)

    @Test
    fun libraryOpensOnDownloadsWhileOfflineUnlessASectionWasAskedFor() = downloadsTest { h ->
        h.install()
        val app = AppSession(h.graph, h.profileId, FakeAppPlatform(), start = null, profileAccount = null)
        assertEquals(LibrarySection.entries, app.librarySections)
        assertEquals(LibrarySection.LIBRARY, app.librarySection())

        h.network(online = false)
        assertEquals(LibrarySection.DOWNLOADS, app.librarySection())
        app.navigator.openLibrary(LibrarySection.CONTINUE_WATCHING)
        assertEquals(LibrarySection.CONTINUE_WATCHING, app.librarySection(), "the section asked for comes first")
        assertEquals(LibrarySection.DOWNLOADS, app.librarySection(), "and is taken once")

        h.network(online = true, metered = true)
        assertEquals(LibrarySection.LIBRARY, app.librarySection())
        app.close()
    }

    @Test
    fun withoutDownloadsLibraryHasNoDownloadsSection() = runTest {
        val graph = testGraph()
        graph.setNetwork(NetworkState.OFFLINE)
        val app = AppSession(graph, "profile-1", FakeAppPlatform(), start = null, profileAccount = null, startProfile = graph.data.addProfile("profile-1"))
        runCurrent()
        assertEquals(listOf(LibrarySection.LIBRARY, LibrarySection.CONTINUE_WATCHING), app.librarySections)
        assertEquals(LibrarySection.LIBRARY, app.librarySection())
        assertNull(app.downloadsStore())
        app.openDownloads()
        assertEquals(Route.Board, app.navigator.current)
        app.close()
    }

    @Test
    fun offlineTheAppPointsToDownloadsThatPlayAndAReconnectBringsTheRestBack() = downloadsTest { h ->
        var reachable = true
        h.serveCdn(bytes)
        h.serveMeta(FILM, SHOW, reachable = { reachable })
        h.routes["catalog.example.com"] = {
            if (!reachable) throw IOException("no connection")
            json("""{"metas":[{"id":"${FILM.id}","type":"movie","name":"${FILM.name}"}]}""")
        }
        h.installMetaAddon()
        h.data.addons.install(h.profileId, CATALOG_URL, CATALOG_MANIFEST)
        val manager = h.install()
        val movie = h.downloaded(FILM, FILM.id, cdnChoice())

        // The connection goes; an episode queued now waits for it.
        reachable = false
        h.network(online = false)
        val episode = manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1-1.mkv")).id
        h.settle()
        assertEquals(DownloadStatus.QUEUED, h.download(episode)?.status)

        val platform = FakeAppPlatform()
        val app = AppSession(h.graph, h.profileId, platform, start = null, profileAccount = null)
        val board = app.boardStore()
        val discover = app.discoverStore()
        val search = app.searchStore()
        h.settle()
        val offline = OfflineState(T.offline.title, T.offline.text, T.offline.openDownloads)
        assertEquals(
            listOf(BoardBody.Offline(offline), DiscoverBody.Offline(offline), SearchBody.Offline(offline)),
            listOf(board.state.value.body, discover.state.value.body, search.state.value.body),
        )
        board.openDownloads()
        assertEquals(Route.Library, app.navigator.current)
        assertEquals(LibrarySection.DOWNLOADS, app.librarySection())

        val downloads = assertNotNull(app.downloadsStore())
        h.settle()
        var state = downloads.state.value
        assertEquals(T.downloads.offline, state.offlineNote)
        val groups = assertIs<DownloadsBody.Groups>(state.body).groups
        assertEquals(T.downloads.status.completed, groups.single { it.key == FILM.id }.movie?.statusText)
        val waiting = groups.single { it.key == SHOW.id }.seasons.single().episodes.single()
        assertEquals(T.downloads.status.waitingForNetwork, waiting.statusText)

        // The download plays from its file; its title shows as saved.
        downloads.open(movie.id)
        h.settle()
        assertEquals(Route.Player, app.navigator.current)
        val local = assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id))
        assertEquals(local.path, platform.playbacks.single().scripted.loads.single().url)
        val detail = app.detailStore(Route.Detail("movie", FILM.id))
        h.settle()
        assertEquals(FILM.name, (detail.state.value.body as? DetailBody.Page)?.header?.title)
        assertEquals(T.offline.detail, detail.state.value.offlineNote)

        // Back online: what failed loads, the episode downloads and Detail shows the addon's answer.
        reachable = true
        h.network(online = true)
        assertEquals(
            listOf(false, false, false),
            listOf(board.state.value.body is BoardBody.Offline, discover.state.value.body is DiscoverBody.Offline, search.state.value.body is SearchBody.Offline),
        )
        assertEquals(listOf(RowStatus.Ready(loadingMore = false)), board.state.value.rows.map { it.status })
        assertTrue(discover.state.value.body !is DiscoverBody.Failed)
        assertEquals(DownloadStatus.COMPLETED, h.download(episode)?.status)
        state = downloads.state.value
        assertNull(state.offlineNote)
        assertEquals(T.downloads.status.completed, assertIs<DownloadsBody.Groups>(state.body).groups.single { it.key == SHOW.id }.seasons.single().episodes.single().statusText)
        assertNull(detail.state.value.offlineNote)
        assertEquals(LibrarySection.LIBRARY, app.librarySection())
        app.close()
    }

    private companion object {
        const val CATALOG_URL = "https://catalog.example.com/manifest.json"
        const val CATALOG_MANIFEST =
            """{"id":"org.example.catalog","version":"1.0.0","name":"Catalog","types":["movie"],"resources":["catalog"],"catalogs":[{"type":"movie","id":"top","name":"Top"}]}"""
    }
}
