package com.edde746.tortuga.app

import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.playback.HardwareDecoder
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.ScriptedEngine
import com.edde746.tortuga.playback.session.choice
import com.edde746.tortuga.playback.session.urlStream
import com.edde746.tortuga.presentation.addons.AddonsSection
import com.edde746.tortuga.presentation.addons.addonsStore
import com.edde746.tortuga.presentation.board.boardStore
import com.edde746.tortuga.presentation.detail.detailStore
import com.edde746.tortuga.presentation.discover.discoverStore
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.menu.mediaActions
import com.edde746.tortuga.presentation.search.searchStore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

internal const val EVERYTHING_URL = "https://everything.example/manifest.json"

/** Every resource there is: a catalog, one searched, meta, streams, subtitles, addon catalogs and the watch activity. */
internal const val EVERYTHING_MANIFEST =
    """{"id":"org.example.everything","version":"1.0.0","name":"Everything","types":["movie","series"],""" +
        """"idPrefixes":["tt"],"resources":["catalog","meta","stream","subtitles","addon_catalog","player","library"],""" +
        """"catalogs":[{"type":"movie","id":"top","name":"Top"},{"type":"movie","id":"find","name":"Find","extra":[{"name":"search","isRequired":true}]}],""" +
        """"addonCatalogs":[{"type":"all","id":"more","name":"More addons"}]}"""

/** What each place of the app asks an addon with every resource, once it is on and agreed to. */
internal val ASKED_OF_EVERYTHING = mapOf(
    "home" to setOf("catalog"),
    "discover, linked to its catalog" to setOf("catalog"),
    "search" to setOf("catalog"),
    "detail" to setOf("meta", "stream"),
    "streams" to setOf("stream"),
    "browse" to setOf("addon_catalog"),
    "playback" to setOf("subtitles", "player"),
    "library" to setOf("library"),
)

/** An [AddonWorld] where `everything.example` ([EVERYTHING_MANIFEST]) answers [FILM] from each of its resources. */
internal fun TestScope.everythingWorld() = AddonWorld(this).apply {
    manifests["everything.example"] = EVERYTHING_MANIFEST
    val metas = """{"metas":[{"id":"$FILM_ID","type":"movie","name":"Film"}]}"""
    answers["everything.example /catalog/movie/top.json"] = metas
    answers["everything.example /catalog/movie/find/search=film.json"] = metas
    answers["everything.example /meta/movie/$FILM_ID.json"] = answers.getValue("meta.example /meta/movie/$FILM_ID.json")
    answers["everything.example /stream/movie/$FILM_ID.json"] = """{"streams":[{"url":"https://cdn.example/film.mkv"}]}"""
    answers["everything.example /addon_catalog/all/more.json"] = """{"addons":[]}"""
}

/**
 * What Home, Discover, Search, Detail, Streams, Browse, a playback and the library actions of profile [p] asked
 * `everything.example`, each as the resources of its requests.
 */
internal suspend fun TestScope.askedOfEverything(w: AddonWorld, p: String): Map<String, Set<String>> {
    val asked = LinkedHashMap<String, Set<String>>()
    suspend fun step(name: String, block: suspend () -> Unit) {
        val before = w.requests.size
        block()
        runCurrent()
        asked[name] = w.requests.drop(before).filter { it.startsWith("everything.example ") }.mapTo(LinkedHashSet()) { it.split('/')[1] }
    }
    step("home") {
        val store = w.graph.boardStore(
            p, NoHosts, remote = false, watchingTogether = flowOf(0), kidsProfile = flowOf(false),
        )
        runCurrent()
        store.release()
    }
    step("discover, linked to its catalog") {
        val store = w.graph.discoverStore(p, DeepLink.Discover(EVERYTHING_URL, "movie", "top"), NoHosts)
        runCurrent()
        store.release()
    }
    step("search") {
        val store = w.graph.searchStore(p, "film", NoHosts)
        runCurrent()
        store.release()
    }
    step("detail") {
        val store = w.graph.detailStore(p, "movie", FILM_ID, null, NoHosts)
        runCurrent()
        store.release()
    }
    step("streams") {
        val store = w.graph.streamsStore(p, FILM, FILM_ID, NoHosts, notice = null)
        runCurrent()
        store.release()
    }
    step("browse") {
        val store = w.graph.addonsStore(p, NoHosts)
        runCurrent()
        store.showSection(AddonsSection.BROWSE)
        runCurrent()
        store.release()
    }
    step("playback") {
        val engine = ScriptedEngine()
        val session = w.graph.playbackSession(p, engine, HardwareDecoder.APPLE)
        session.start(PlayRequest(FILM, FILM_ID, choice(urlStream("https://cdn.example/film.mkv"))))
        runCurrent()
        engine.open(duration = 100.0)
        runCurrent()
        session.close()
    }
    step("library") {
        val film = MetaSnapshot(FILM_ID, "movie", "Film", null, "poster")
        val actions = w.graph.mediaActions(p)
        actions.carryOut(film, null, MediaAction.ADD_TO_LIBRARY)
        actions.carryOut(film, null, MediaAction.MARK_WATCHED)
        actions.carryOut(film, null, MediaAction.MARK_UNWATCHED)
        actions.carryOut(film, null, MediaAction.REMOVE_FROM_LIBRARY)
    }
    return asked
}
