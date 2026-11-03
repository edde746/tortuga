package com.edde746.tortuga.app

import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.presentation.board.BoardBody
import com.edde746.tortuga.presentation.board.boardStore
import com.edde746.tortuga.presentation.detail.detailStore
import com.edde746.tortuga.presentation.discover.DiscoverBody
import com.edde746.tortuga.presentation.discover.discoverStore
import com.edde746.tortuga.presentation.library.LibraryBody
import com.edde746.tortuga.presentation.library.LibraryHost
import com.edde746.tortuga.presentation.library.continueWatchingStore
import com.edde746.tortuga.presentation.library.libraryStore
import com.edde746.tortuga.presentation.menu.MediaMenuHost
import com.edde746.tortuga.presentation.search.SearchBody
import com.edde746.tortuga.presentation.search.searchStore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Hide titles under posters" on the app graph's real screens: every poster card but Continue Watching's goes caption-less. */
class PosterCaptionsTest {
    private object LibraryHosts : LibraryHost, MediaMenuHost by NoHosts {
        override fun openVideo(type: String, id: String, videoId: String) = Unit

        override fun openCalendar() = Unit
    }

    private val manifest =
        """{"id":"org.example.posters","version":"1.0.0","name":"Posters","types":["movie"],"idPrefixes":["tt"],"resources":["catalog","meta"],""" +
            """"catalogs":[{"type":"movie","id":"top","name":"Top"},{"type":"movie","id":"find","name":"Find","extra":[{"name":"search","isRequired":true}]},""" +
            """{"type":"movie","id":"like","name":"Like","extra":[{"name":"similar","isRequired":true}]}]}"""

    /** Each screen's cards' captions, as the profile's switch leaves them: Home's rows and Continue Watching, Discover, Search, More like this, Library and the Continue Watching screen. */
    private suspend fun TestScope.captions(w: AddonWorld, p: String): Map<String, List<Boolean>> {
        val board = w.graph.boardStore(p, NoHosts, remote = false, watchingTogether = flowOf(0), kidsProfile = flowOf(false))
        val discover = w.graph.discoverStore(p, DeepLink.Discover("https://posters.example/manifest.json", "movie", "top"), NoHosts)
        val search = w.graph.searchStore(p, "film", NoHosts)
        val detail = w.graph.detailStore(p, "movie", FILM_ID, null, NoHosts)
        val library = w.graph.libraryStore(p, LibraryHosts)
        val continueWatching = w.graph.continueWatchingStore(p, LibraryHosts)
        runCurrent()
        val boardState = board.state.value
        return mapOf(
            "home rows" to (boardState.body as BoardBody.Rows).rows.flatMap { row -> row.items.map { it.caption } },
            "discover" to (discover.state.value.body as DiscoverBody.Grid).items.map { it.caption },
            "search" to (search.state.value.body as SearchBody.Results).groups.flatMap { group -> group.items.map { it.caption } },
            "more like this" to detail.state.value.related?.cards.orEmpty().map { it.caption },
            "library" to (library.state.value.body as LibraryBody.Grid).items.map { it.caption },
            "continue watching screen" to (continueWatching.state.value.body as LibraryBody.Grid).items.map { it.caption },
        ).also {
            assertTrue(boardState.continueWatching.isNotEmpty(), "Home's Continue Watching has its card, whose caption is its episode and time left")
            listOf(board::release, discover::release, search::release, detail::release, library::release, continueWatching::release).forEach { it() }
        }
    }

    @Test
    fun theSwitchTakesTheCaptionsOffEveryCardButContinueWatchings() = runTest {
        val w = AddonWorld(this)
        w.manifests["posters.example"] = manifest
        val other = """{"metas":[{"id":"tt0000009","type":"movie","name":"Other"}]}"""
        w.answers["posters.example /catalog/movie/top.json"] = other
        w.answers["posters.example /catalog/movie/find/search=film.json"] = other
        w.answers["posters.example /catalog/movie/like/similar=$FILM_ID.json"] = other
        w.answers["posters.example /meta/movie/$FILM_ID.json"] = w.answers.getValue("meta.example /meta/movie/$FILM_ID.json")
        val p = w.data.profiles.create("Ann", null, kids = false).id
        w.graph.addonRegistry(p).install("https://posters.example/manifest.json".transportUrl())
        val library = w.data.library
        library.addToLibrary(p, MetaSnapshot(FILM_ID, "movie", "Film", null, "poster"))
        library.recordProgress(p, FILM_ID, FILM_ID, 600_000, 6_000_000)
        runCurrent()

        val shown = captions(w, p)
        assertTrue(shown.values.all { it.isNotEmpty() && it.all { caption -> caption } }, "off, every card has its caption: $shown")

        w.data.settings.set(p, ProfileSetting.HidePosterCaptions, true)
        runCurrent()
        val hidden = captions(w, p)
        assertEquals(
            mapOf(
                "home rows" to false, "discover" to false, "search" to false, "more like this" to false, "library" to false,
                "continue watching screen" to true,
            ),
            hidden.mapValues { (_, captions) -> captions.distinct().single() },
        )
    }
}
