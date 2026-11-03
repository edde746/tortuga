package com.edde746.tortuga.app

import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.presentation.board.BoardBody
import com.edde746.tortuga.presentation.board.boardStore
import com.edde746.tortuga.presentation.detail.detailStore
import com.edde746.tortuga.presentation.discover.DiscoverBody
import com.edde746.tortuga.presentation.discover.discoverStore
import com.edde746.tortuga.presentation.search.SearchBody
import com.edde746.tortuga.presentation.search.searchStore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Hide watched titles" on the app graph's real screens: Home's catalog rows and Discover leave out what the profile
 * finished (a movie watched, a series whose episodes out are all watched); Continue Watching, Search and More like this
 * keep it.
 */
class HideWatchedTest {
    private val manifest =
        """{"id":"org.example.titles","version":"1.0.0","name":"Titles","types":["movie","series"],"idPrefixes":["tt"],"resources":["catalog","meta"],""" +
            """"catalogs":[{"type":"movie","id":"top","name":"Top"},{"type":"movie","id":"find","name":"Find","extra":[{"name":"search","isRequired":true}]},""" +
            """{"type":"movie","id":"like","name":"Like","extra":[{"name":"similar","isRequired":true}]},""" +
            """{"type":"series","id":"shows","name":"Shows"},""" +
            """{"type":"series","id":"last-videos","extra":[{"name":"lastVideosIds","isRequired":true,"optionsLimit":100}]}]}"""

    /** The ids each screen shows: Home's rows, Home's Continue Watching, Discover, Search and More like this. */
    private suspend fun TestScope.shown(w: AddonWorld, p: String): Map<String, List<String>> {
        val board = w.graph.boardStore(p, NoHosts, remote = false, watchingTogether = flowOf(0), kidsProfile = flowOf(false))
        val discover = w.graph.discoverStore(p, DeepLink.Discover("https://titles.example/manifest.json", "movie", "top"), NoHosts)
        val search = w.graph.searchStore(p, "film", NoHosts)
        val detail = w.graph.detailStore(p, "movie", FILM_ID, null, NoHosts)
        runCurrent()
        val result = mapOf(
            "home rows" to ((board.state.value.body as? BoardBody.Rows)?.rows.orEmpty().flatMap { row -> row.items.map { it.id } }),
            "continue watching" to board.state.value.continueWatching.map { it.id },
            "discover" to ((discover.state.value.body as? DiscoverBody.Grid)?.items.orEmpty().map { it.id }),
            "search" to ((search.state.value.body as? SearchBody.Results)?.groups.orEmpty().flatMap { group -> group.items.map { it.id } }),
            "more like this" to detail.state.value.related?.cards.orEmpty().map { it.id },
        )
        listOf(board::release, discover::release, search::release, detail::release).forEach { it() }
        return result
    }

    @Test
    fun finishedTitlesLeaveHomesRowsAndDiscoverWhileTheSwitchIsOn() = runTest {
        val w = AddonWorld(this)
        w.manifests["titles.example"] = manifest
        val films = """{"metas":[{"id":"$FILM_ID","type":"movie","name":"Film"},{"id":"tt0000009","type":"movie","name":"Other"}]}"""
        w.answers["titles.example /catalog/movie/top.json"] = films
        w.answers["titles.example /catalog/movie/find/search=film.json"] = films
        // More like this leaves the title itself out: another watched film shows there.
        w.answers["titles.example /catalog/movie/like/similar=$FILM_ID.json"] =
            """{"metas":[{"id":"tt0000007","type":"movie","name":"Seen"},{"id":"tt0000009","type":"movie","name":"Other"}]}"""
        w.answers["titles.example /catalog/series/shows.json"] =
            """{"metas":[{"id":"$SHOW_ID","type":"series","name":"Show"},{"id":"tt0000008","type":"series","name":"Another"}]}"""
        w.answers["titles.example /meta/movie/$FILM_ID.json"] = w.answers.getValue("meta.example /meta/movie/$FILM_ID.json")
        // The show's last videos: its 102 episodes out; its 103rd comes out in 2030.
        w.answers["titles.example /catalog/series/last-videos/lastVideosIds=$SHOW_ID.json"] =
            """{"metasDetailed":[${AddonJson.encodeToString(MetaItem.serializer(), SHOW)}]}"""
        val p = w.data.profiles.create("Ann", null, kids = false).id
        w.graph.addonRegistry(p).install("https://titles.example/manifest.json".transportUrl())
        val library = w.data.library
        // The film watched, and in Continue Watching again (a rewatch started); the show's episodes out all watched.
        library.markWatched(p, MetaSnapshot(FILM_ID, "movie", "Film", null, "poster"), true)
        library.markWatched(p, MetaSnapshot("tt0000007", "movie", "Seen", null, "poster"), true)
        library.recordProgress(p, FILM_ID, FILM_ID, 600_000, 6_000_000)
        library.markAllWatched(p, SHOW, true)
        // The addons are asked for the series' last videos while the app is in the foreground.
        w.graph.setForeground(true)
        runCurrent()

        val all = mapOf(
            "home rows" to listOf(FILM_ID, "tt0000009", SHOW_ID, "tt0000008"),
            "continue watching" to listOf(FILM_ID),
            "discover" to listOf(FILM_ID, "tt0000009"),
            "search" to listOf(FILM_ID, "tt0000009"),
            "more like this" to listOf("tt0000007", "tt0000009"),
        )
        assertEquals(all, shown(w, p), "the switch off, every screen shows every title")

        w.data.settings.set(p, ProfileSetting.HideWatched, true)
        runCurrent()
        assertEquals(
            all + mapOf("home rows" to listOf("tt0000009", "tt0000008"), "discover" to listOf("tt0000009")),
            shown(w, p),
        )
    }
}
