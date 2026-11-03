@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.settings.HomeLayout
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.presentation.board.CINEMETA
import com.edde746.tortuga.presentation.board.FakeCatalogSource
import com.edde746.tortuga.presentation.board.catalogAddon
import com.edde746.tortuga.presentation.rows
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class HomeSettingsStoreTest {
    /** A catalog Home shows and one it leaves out (a required genre). */
    private val lists = catalogAddon(
        "lists",
        """[
            {"type":"movie","id":"trending","name":"Trending"},
            {"type":"series","id":"by-genre","name":"By genre","extra":[{"name":"genre","isRequired":true,"options":["Drama"]}]}
        ]""",
    )
    private val extra = catalogAddon("extra", """[{"type":"movie","id":"new","name":"New"}]""")

    private class Fixture(
        scope: TestScope,
        addons: List<Descriptor>,
        trackers: List<TrackerCatalogSource> = emptyList(),
        connected: Set<TrackerService> = TrackerService.entries.toSet(),
        /** The installed addons as the store reads them; by default [addons]. */
        installed: Flow<List<Descriptor>>? = null,
    ) {
        val data = LocalData(
            inMemoryDatabase(), { 1_790_000_000_000L },
            StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        )
        val addons = MutableStateFlow(addons)
        val trackers = MutableStateFlow(trackers)
        val connected = MutableStateFlow(connected)
        val store = HomeSettingsStore(
            "p", installed ?: this.addons, this.trackers, this.connected, data.settings, data.collections, scope.backgroundScope,
            newImport = { error("no import here") }, newEditor = { _, _ -> error("no editor here") }, now = { 0L }, utcOffset = { 0 },
        )
        val state get() = store.state.value

        fun titles() = state.rows.map { it.title }

        suspend fun stored() = data.settings.settings("p").homeRows
    }

    private fun trakt(lists: List<TrackerCatalog> = listOf(WATCHLIST, TRENDING)) = FakeCatalogSource(TrackerService.TRAKT, lists, emptyMap())

    /** A catalog's key in Home's layout: its addon's link, its type and id. */
    private fun key(addon: Descriptor, type: String, id: String) = "addon|${addon.transportUrl}|$type|$id"

    private val popularMovies = key(CINEMETA, "movie", "top")
    private val popularSeries = key(CINEMETA, "series", "top")
    private val featuredMovies = key(CINEMETA, "movie", "imdbRating")
    private val featuredSeries = key(CINEMETA, "series", "imdbRating")

    /** A movie row's title: its catalog's or list's name, isolated, and the type. */
    private fun movies(name: String) = "\u2068$name\u2069 · Movies"

    /** A series row's title: its catalog's or list's name, isolated, and the type. */
    private fun series(name: String) = "\u2068$name\u2069 · Series"

    @Test
    fun listsEveryRowInHomesOrderAndStoresMovesAndVisibility() = runTest {
        val f = Fixture(this, listOf(lists, CINEMETA), listOf(trakt()))
        runCurrent()
        assertTrue(f.state.body is HomeSettingsBody.Rows)
        assertEquals(
            listOf("Watchlist", movies("Trending"), movies("Trending"), movies("Popular"), series("Popular"), movies("Featured"), series("Featured")),
            f.titles(),
            "the trackers' lists, then the addons' catalogs without required extras",
        )
        assertEquals(listOf("Trakt", "Trakt", "lists", "Cinemeta"), f.state.rows.take(4).map { it.sourceName })
        assertEquals(listOf(false to true, true to false), listOf(f.state.rows.first(), f.state.rows.last()).map { it.canMoveUp to it.canMoveDown })
        assertTrue(f.state.rows.all { it.shown } && f.state.continueWatching.checked && !f.state.canReset)

        f.store.moveTo("com.linvo.cinemeta/series/imdbRating", 0)
        assertEquals(series("Featured"), f.titles().first(), "the page moves the row at once")
        f.store.moveUp("org.example.lists/movie/trending")
        f.store.setShown("com.linvo.cinemeta/series/top", shown = false)
        f.store.toggle("continueWatching", false)
        runCurrent()
        assertEquals(
            HomeLayout(
                order = listOf(
                    featuredSeries, "tracker|trakt|watchlist", key(lists, "movie", "trending"), "tracker|trakt|trending.movie",
                    popularMovies, popularSeries, featuredMovies,
                ),
                hidden = listOf(popularSeries, HomeLayout.CONTINUE_WATCHING),
            ),
            f.stored(),
        )
        assertEquals(listOf(true, true, true, true, true, false, true), f.state.rows.map { it.shown })
        assertFalse(f.state.continueWatching.checked)
        assertTrue(f.state.canReset)

        val before = f.stored()
        f.store.moveUp(f.state.rows.first().id)
        f.store.moveDown(f.state.rows.last().id)
        f.store.setShown("no.such/row", shown = false)
        runCurrent()
        assertEquals(before, f.stored(), "nothing moves past the ends")
        f.data.close()
    }

    @Test
    fun newRowsTakeTheirDefaultPlaceAndRemovedAddonsLeaveTheLayout() = runTest {
        val f = Fixture(this, listOf(lists, CINEMETA))
        runCurrent()
        f.store.moveTo("com.linvo.cinemeta/series/imdbRating", 0)
        runCurrent()

        f.addons.value = listOf(lists, CINEMETA, extra)
        runCurrent()
        assertEquals(
            listOf(series("Featured"), movies("Trending"), movies("Popular"), series("Popular"), movies("Featured"), movies("New")),
            f.titles(),
            "a newly installed addon's rows go last",
        )

        f.addons.value = listOf(extra, CINEMETA)
        runCurrent()
        assertEquals(
            listOf(movies("New"), series("Featured"), movies("Popular"), series("Popular"), movies("Featured")),
            f.titles(),
            "a removed addon's rows leave; an addon first in the addon order leads, as by default",
        )
        f.store.setShown("org.example.extra/movie/new", shown = false)
        runCurrent()
        assertEquals(
            HomeLayout(order = listOf(featuredSeries, popularMovies, popularSeries, featuredMovies), hidden = listOf(key(extra, "movie", "new"))),
            f.stored(),
            "the removed addon's key is dropped on save",
        )
        f.data.close()
    }

    @Test
    fun trackerListsThisDeviceDoesntReadKeepTheirPlace() = runTest {
        val failing = trakt().apply { failLists = TrackerException.Network(TrackerService.TRAKT, IllegalStateException("offline")) }
        val f = Fixture(this, listOf(CINEMETA), listOf(failing))
        val simkl = "tracker|simkl|plan-to-watch"
        // As another device, with Simkl connected and Trakt's lists read, stored it.
        f.data.settings.set(
            "p",
            ProfileSetting.HomeRows,
            HomeLayout(order = listOf(simkl, featuredSeries, "tracker|trakt|watchlist", popularMovies, popularSeries, featuredMovies)),
        )
        runCurrent()
        assertEquals(listOf(series("Featured"), movies("Popular"), series("Popular"), movies("Featured")), f.titles())

        f.store.moveTo("com.linvo.cinemeta/movie/top", 0)
        runCurrent()
        assertEquals(
            listOf(simkl, popularMovies, featuredSeries, "tracker|trakt|watchlist", popularSeries, featuredMovies),
            f.stored().order,
            "a service not connected here and one whose lists failed keep their lists' places",
        )

        // Read again, Trakt no longer has a watchlist: its key goes with the next change.
        f.trackers.value = listOf(trakt(listOf(TRENDING)))
        runCurrent()
        assertEquals(movies("Trending"), f.titles().first(), "a list not in the stored order takes its default place: trackers lead")
        f.store.setShown(f.state.rows.first().id, shown = false)
        runCurrent()
        assertEquals(listOf(simkl, popularMovies, featuredSeries, popularSeries, featuredMovies), f.stored().order)
        f.data.close()
    }

    @Test
    fun aDisconnectedTrackersListsLeaveTheLayoutWithTheNextChange() = runTest {
        val f = Fixture(this, listOf(CINEMETA), connected = setOf(TrackerService.TRAKT, TrackerService.SIMKL))
        val simkl = "tracker|simkl|plan-to-watch"
        val trakt = "tracker|trakt|watchlist"
        f.data.settings.set("p", ProfileSetting.HomeRows, HomeLayout(order = listOf(simkl, trakt, popularMovies), hidden = listOf(simkl)))
        runCurrent()

        // Simkl was disconnected (on any device); Trakt is connected but its lists aren't read here.
        f.connected.value = setOf(TrackerService.TRAKT)
        runCurrent()
        f.store.moveTo("com.linvo.cinemeta/movie/top", 1)
        runCurrent()
        assertTrue(trakt in f.stored().order, "a connected service's unread lists keep their places")
        assertFalse(simkl in f.stored().order || simkl in f.stored().hidden, "the disconnected service's keys are gone")
        f.data.close()
    }

    @Test
    fun anAddonListedTwiceIsTwoRowsOfTheirOwn() = runTest {
        val twice = catalogAddon("twice", """[{"type":"movie","id":"top","name":"Top"}]""")
        val f = Fixture(this, listOf(twice, twice))
        runCurrent()
        assertEquals(listOf("org.example.twice/movie/top", "org.example.twice/movie/top#2"), f.state.rows.map { it.id })
        f.store.moveUp("org.example.twice/movie/top#2")
        runCurrent()
        val top = key(twice, "movie", "top")
        assertEquals(listOf("$top|#2", top), f.stored().order)
        assertEquals(listOf("org.example.twice/movie/top#2", "org.example.twice/movie/top"), f.state.rows.map { it.id })
        f.data.close()
    }

    @Test
    fun resetGoesBackToHomesDefault() = runTest {
        val f = Fixture(this, listOf(lists, CINEMETA))
        runCurrent()
        val default = f.titles()
        f.store.moveTo("com.linvo.cinemeta/series/imdbRating", 0)
        f.store.setShown("org.example.lists/movie/trending", shown = false)
        f.store.toggle("continueWatching", false)
        runCurrent()
        assertTrue(f.state.canReset)

        f.store.reset()
        assertEquals(default, f.titles())
        runCurrent()
        assertEquals(HomeLayout(), f.stored())
        assertEquals(default, f.titles())
        assertTrue(f.state.rows.all { it.shown } && f.state.continueWatching.checked && !f.state.canReset)
        f.data.close()
    }

    @Test
    fun continueWatchingTakesChangesOnceTheLayoutIsRead() = runTest {
        val f = Fixture(this, listOf(CINEMETA))
        assertEquals(HomeSettingsBody.Loading, f.state.body)
        assertFalse(f.state.continueWatching.enabled, "before the stored layout is read, the switch would change nothing")
        runCurrent()
        assertTrue(f.state.body is HomeSettingsBody.Rows)
        assertTrue(f.state.continueWatching.enabled)
        f.data.close()
    }

    @Test
    fun withTheAddonsUnreadableContinueWatchingStillChanges() = runTest {
        val f = Fixture(this, emptyList(), installed = flow { throw IllegalStateException("no such table: addon") })
        val stored = HomeLayout(order = listOf(popularMovies), hidden = listOf(featuredSeries))
        f.data.settings.set("p", ProfileSetting.HomeRows, stored)
        runCurrent()
        assertEquals(HomeSettingsBody.Empty(T.addons.unreadable), f.state.body)
        assertTrue(f.state.continueWatching.enabled)

        f.store.toggle(f.state.continueWatching.id, false)
        runCurrent()
        assertFalse(f.state.continueWatching.checked)
        assertEquals(stored.copy(hidden = listOf(featuredSeries, HomeLayout.CONTINUE_WATCHING)), f.stored(), "no row is known gone: every key stays")
        f.data.close()
    }

    private companion object {
        val WATCHLIST = TrackerCatalog(TrackerService.TRAKT, "watchlist", TrackerCatalog.Kind.WATCHLIST, type = null)
        val TRENDING = TrackerCatalog(TrackerService.TRAKT, "trending.movie", TrackerCatalog.Kind.TRENDING, "movie")
    }
}
