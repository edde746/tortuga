package com.edde746.tortuga.presentation.menu

import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerFixture
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.TrackerService.MYANIMELIST
import com.edde746.tortuga.trackers.TrackerService.SIMKL
import com.edde746.tortuga.trackers.TrackerService.TRAKT
import com.edde746.tortuga.trackers.WatchlistLine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Watchlist entry of a card's menu ([mediaMenu], [MediaActions]) and the sheet of several lists ([watchlistSheet]):
 * a direct toggle with one tracker's list, the sheet with several, nothing without trackers or on a kids profile.
 */
class WatchlistMenuTest {
    private val fixtures = mutableListOf<TrackerFixture>()

    @AfterTest
    fun close() = fixtures.forEach { it.close() }

    private val opened = mutableListOf<WatchlistRequest>()
    private val notices = mutableListOf<String>()

    private object Host : MediaMenuHost {
        override fun openMeta(type: String, id: String, preview: MetaItemPreview?) = Unit

        override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) = Unit
    }

    private suspend fun TestScope.trackers(vararg services: TrackerService): TrackerFixture {
        val f = TrackerFixture(this, watchlists = true).also { fixtures += it }
        for (service in services) f.connect(service)
        f.log.clear()
        return f
    }

    /** The profile's card actions, [kids] its running as a kids profile; without [withTrackers] the build has none. */
    private fun TestScope.actions(f: TrackerFixture, kids: Boolean = false, withTrackers: Boolean = true): MediaActions {
        val profile = Profile(f.profileId, "Alex", avatar = null, hasPin = false, createdAt = 0, kids = kids, homeCatalogs = emptyList())
        val gate = KidsGate(f.profileId, flowOf(profile), f.data.settings.observe(f.profileId), f.data.settings, { null }, backgroundScope, testScheduler.timeSource)
        val actions = MediaActions(
            f.profileId, f.data.library, trackerMarks = null, backgroundScope, notice = { notices += it },
            trackers = f.coordinator.takeIf { withTrackers }, kids = gate, openWatchlists = { opened += it },
        ) { _, _ -> null }
        f.settle()
        return actions
    }

    /** The Watchlist entries of [type]/[id]'s card menu, as the screens stamp them. */
    private suspend fun MediaActions.entries(type: String = "movie", id: String = MOVIE): List<String> {
        val marks = watchlistMarks?.first()
        val card = MetaCard(id, type, "The Shawshank Redemption", null, null, null, PosterShape.POSTER, null, watchlist = marks?.menu(type, id))
        return card.menu.filter { it.action in WATCHLIST_ACTIONS }.map { "${it.action.id} ${it.label}" }
    }

    @Test
    fun oneTrackersListIsADirectToggleThatShowsWhetherTheTitleIsOnIt() = runTest {
        val f = trackers(TRAKT)
        val actions = actions(f)
        assertEquals(listOf("addToWatchlist Add to Trakt watchlist"), actions.entries(), "unknown counts as not on the list")

        f.watchlist(TRAKT).titles += MetaItemPreview(id = MOVIE, type = "movie", name = "The Shawshank Redemption")
        f.readLists()
        assertEquals(listOf("removeFromWatchlist Remove from Trakt watchlist"), actions.entries())

        actions.perform(SNAPSHOT, null, MediaAction.REMOVE_FROM_WATCHLIST, Host)
        f.drain()
        assertEquals(listOf("watchlist remove $MOVIE"), f.calls(TRAKT))
        assertEquals(listOf("addToWatchlist Add to Trakt watchlist"), actions.entries())
        assertEquals(emptyList(), notices)
    }

    @Test
    fun aDirectToggleThatFailsSaysSo() = runTest {
        val f = trackers(TRAKT)
        val actions = actions(f)
        f.watchlist(TRAKT).failNext("add", TrackerException.Unsupported(TRAKT, "no such title"))
        actions.perform(SNAPSHOT, null, MediaAction.ADD_TO_WATCHLIST, Host)
        f.drain()
        assertEquals(listOf("Couldn’t add \u2068The Shawshank Redemption\u2069 to Trakt watchlist."), notices)
        assertEquals(listOf("addToWatchlist Add to Trakt watchlist"), actions.entries(), "it went back")
    }

    @Test
    fun severalTrackersListsOpenTheSheetWithARowEach() = runTest {
        val f = trackers(TRAKT, SIMKL, MYANIMELIST)
        val actions = actions(f)
        assertEquals(listOf("watchlists Tracker watchlists"), actions.entries())
        assertEquals(listOf("watchlists Tracker watchlists"), actions.entries("series", "kitsu:1"), "an anime's card counts MyAnimeList too")

        actions.perform(SNAPSHOT, null, MediaAction.WATCHLISTS, Host)
        assertEquals(listOf("movie $MOVIE The Shawshank Redemption"), opened.map { "${it.type} ${it.id} ${it.name}" })

        // The sheet: a row per list that takes the title, each toggled on its own.
        val lines = mutableListOf<List<WatchlistLine>>()
        backgroundScope.launch { f.coordinator.watchlist("movie", MOVIE).collect { lines += it } }
        f.drain()
        assertEquals(listOf("Trakt watchlist false null", "Simkl plan to watch false null"), lines.last().rows())

        f.watchlist(SIMKL).failNext("add", TrackerException.Api(SIMKL, 503))
        f.coordinator.setWatchlisted("movie", MOVIE, null, SIMKL, listed = true)
        f.drain()
        assertEquals(listOf("Trakt watchlist false null", "Simkl plan to watch true Not sent yet, will retry"), lines.last().rows())
        assertEquals(emptyList(), f.calls(TRAKT))
        assertEquals(listOf("Tracker watchlists"), listOf(watchlistSheet(lines.last()).title))
    }

    @Test
    fun aKidsProfileOrAProfileWithoutTrackersHasNoEntry() = runTest {
        assertEquals(emptyList(), actions(trackers(TRAKT, SIMKL), kids = true).entries(), "a kids profile")
        assertEquals(emptyList(), actions(trackers()).entries(), "no tracker connected")
        assertNull(actions(trackers(TRAKT), withTrackers = false).watchlistMarks, "a build without trackers")
        assertEquals(emptyList(), actions(trackers(MYANIMELIST)).entries(), "an anime list takes no title that isn't anime")
        assertTrue(actions(trackers(MYANIMELIST)).entries("series", "kitsu:1").isNotEmpty(), "and does take an anime")
    }

    private fun List<WatchlistLine>.rows() = watchlistSheet(this).rows.map { "${it.name} ${it.listed} ${it.status}" }

    private companion object {
        const val MOVIE = "tt0111161"
        val SNAPSHOT = MetaSnapshot(MOVIE, "movie", "The Shawshank Redemption", null, "poster")
        val WATCHLIST_ACTIONS = setOf(MediaAction.ADD_TO_WATCHLIST, MediaAction.REMOVE_FROM_WATCHLIST, MediaAction.WATCHLISTS)
    }
}
