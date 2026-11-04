package com.edde746.tortuga.presentation.navigation

import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TortugaNavigatorTest {
    private val movie = MetaItem(MetaItemPreview(id = "tt1", type = "movie", name = "Movie"))
    private val streams = Route.Streams("movie", "tt1", "tt1")

    private class Store(val name: String) {
        var released = false
    }

    private fun TortugaNavigator.store(route: Route, name: String = "store") =
        assertNotNull(retain(route, "store", create = { Store(name) }, release = { it.released = true }), "$route is on a stack")

    private fun navigator(start: Route = Route.Board) = TortugaNavigator(start)

    @Test
    fun retainedObjectLivesWhileItsRouteIsOnTheStack() {
        val navigator = navigator()
        val detail = Route.Detail("movie", "tt1")
        navigator.navigate(detail)
        val first = navigator.store(detail)
        navigator.navigate(Route.Detail("movie", "tt2"))
        // Covered by another screen, the Detail route is still on the stack: same store, not released.
        assertSame(first, navigator.store(detail, "second"))
        assertFalse(first.released)
        navigator.back()
        assertFalse(first.released)
        navigator.back()
        assertTrue(first.released)
        assertEquals(listOf<Route>(Route.Board), navigator.backStack.value)
    }

    @Test
    fun aRouteOffTheStackGetsNothing() {
        val navigator = navigator()
        navigator.openStreams(movie, "tt1")
        val kept = navigator.store(streams)
        navigator.back()
        // A popped screen still composes while it animates out, even for the first time: nothing is made for it (a
        // Streams store would have no item: the navigator forgot it with the route), and nothing is handed out; nor for
        // a destination never shown.
        var created = 0
        assertNull(navigator.retain(streams, "store", create = { created++; Store("late") }, release = { it.released = true }))
        assertNull(navigator.retain(Route.Library, "store", create = { created++; Store("never opened") }, release = { it.released = true }))
        assertEquals(0, created)
        assertTrue(kept.released)
        assertNull(navigator.retained(streams, "store"))
        // Opened again, the route gets a new one.
        navigator.openStreams(movie, "tt1")
        val again = navigator.store(streams, "again")
        assertNotSame(kept, again)
        assertEquals("again", again.name)
    }

    @Test
    fun aDetailKeepsItsCatalogItemAndKnowsWhenAScreenCoversIt() = runTest {
        val navigator = navigator()
        val detail = Route.Detail("movie", "tt1")
        navigator.openDetail(detail, movie.preview)
        assertSame(movie.preview, navigator.preview(detail))
        assertFalse(navigator.covered(detail).first())
        navigator.openStreams(movie, "tt1")
        assertFalse(navigator.covered(detail).first(), "a sheet leaves it in view")
        navigator.navigate(Route.Player)
        assertTrue(navigator.covered(detail).first())
        navigator.back()
        assertFalse(navigator.covered(detail).first())
        navigator.back()
        assertNull(navigator.preview(detail), "gone with its route")
    }

    @Test
    fun theStreamsSheetIsDrawnOverTheScreenBelowIt() {
        val navigator = navigator()
        val detail = Route.Detail("movie", "tt1")
        navigator.navigate(detail)
        assertEquals(BackStackLayout(listOf(Route.Board, detail), sheet = null), navigator.layout)
        navigator.openStreams(movie, "tt1")
        assertEquals(listOf(Route.Board, detail, streams), navigator.backStack.value)
        assertEquals(BackStackLayout(listOf(Route.Board, detail), sheet = streams), navigator.layout)
        // The chrome follows the screen under the sheet.
        assertEquals(TopLevelDestination.BOARD, navigator.selected)
        // Back closes the sheet.
        assertTrue(navigator.back())
        assertEquals(BackStackLayout(listOf(Route.Board, detail), sheet = null), navigator.layout)
    }

    @Test
    fun aSheetAloneOnTheStackIsDrawnAsAScreen() {
        // A development session opened on Streams: nothing to draw the sheet over.
        val navigator = navigator(streams)
        assertEquals(BackStackLayout(listOf(streams), sheet = null), navigator.layout)
        navigator.navigate(Route.Player)
        assertEquals(listOf(streams, Route.Player), navigator.backStack.value)
        assertTrue(navigator.back())
        assertEquals(listOf<Route>(streams), navigator.backStack.value)
    }

    @Test
    fun whatOpensFromASheetReplacesIt() {
        val removed = mutableListOf<Route>()
        val navigator = navigator()
        navigator.addRemovalListener { removed += it }
        val detail = Route.Detail("movie", "tt1")
        navigator.navigate(detail)
        navigator.openStreams(movie, "tt1")
        val sheetStore = navigator.store(streams)
        // Playing a stream: the player takes the sheet's place, so Back from it returns to Detail.
        navigator.navigate(Route.Player)
        assertEquals(listOf(Route.Board, detail, Route.Player), navigator.backStack.value)
        assertEquals(listOf<Route>(streams), removed)
        assertTrue(sheetStore.released)
        assertNull(navigator.meta("movie", "tt1"))
        navigator.back()
        assertEquals(listOf(Route.Board, detail), navigator.backStack.value)
        // Another sheet replaces an open one; a screen already under the sheet is only uncovered.
        navigator.openStreams(movie, "tt1")
        navigator.openStreams(movie, "tt1:2")
        assertEquals(listOf(Route.Board, detail, Route.Streams("movie", "tt1", "tt1:2")), navigator.backStack.value)
        navigator.navigate(detail)
        assertEquals(listOf(Route.Board, detail), navigator.backStack.value)
    }

    @Test
    fun aStreamPickedInTheSheetOverThePlayerKeepsThatPlayer() {
        val removed = mutableListOf<Route>()
        val navigator = navigator()
        navigator.addRemovalListener { removed += it }
        navigator.navigate(Route.Player)
        val player = navigator.store(Route.Player)
        navigator.openStreams(movie, "tt1")
        assertEquals(BackStackLayout(listOf(Route.Board, Route.Player), sheet = streams), navigator.layout)
        assertSame(movie, navigator.meta("movie", "tt1"))
        navigator.navigate(Route.Player)
        assertEquals(listOf(Route.Board, Route.Player), navigator.backStack.value)
        // Only the sheet left: the player's route (and what it keeps) stayed.
        assertEquals(listOf<Route>(streams), removed)
        assertFalse(player.released)
    }

    @Test
    fun popRemovesTheRouteAndEverythingAboveIt() {
        val removed = mutableListOf<Route>()
        val navigator = navigator()
        navigator.addRemovalListener { removed += it }
        navigator.navigate(Route.Player)
        navigator.openStreams(movie, "tt1")
        assertTrue(navigator.pop(Route.Player))
        assertEquals(listOf<Route>(Route.Board), navigator.backStack.value)
        assertEquals(setOf(streams, Route.Player), removed.toSet())
        // The item a Streams route needed goes with it.
        assertNull(navigator.meta("movie", "tt1"))
    }

    @Test
    fun theRootStays() {
        val navigator = navigator(Route.Player)
        assertFalse(navigator.back())
        assertFalse(navigator.pop(Route.Player))
        assertFalse(navigator.pop(Route.Settings))
        assertEquals(listOf<Route>(Route.Player), navigator.backStack.value)
    }

    @Test
    fun aDevelopmentSessionsFirstScreenIsHomesStackUntilHomeIsPickedWhileItShows() {
        val detail = Route.Detail("movie", "tt1")
        val navigator = navigator(detail)
        val store = navigator.store(detail)
        // Another destination, then Home: Home's stack as it was left, the first screen.
        navigator.pick(TopLevelDestination.SETTINGS)
        assertTrue(navigator.back())
        assertEquals(listOf<Route>(detail), navigator.backStack.value)
        navigator.pick(TopLevelDestination.DISCOVER)
        navigator.pick(TopLevelDestination.BOARD)
        assertEquals(listOf<Route>(detail), navigator.backStack.value)
        assertFalse(store.released)
        // Home picked while it shows: Home's own screen, the first one released.
        navigator.pick(TopLevelDestination.BOARD)
        assertEquals(listOf<Route>(Route.Board), navigator.backStack.value)
        assertTrue(store.released)
    }

    @Test
    fun eachDestinationShowsAStackOfItsOwn() {
        val navigator = navigator()
        navigator.select(TopLevelDestination.LIBRARY)
        assertEquals(listOf<Route>(Route.Library), navigator.backStack.value)
        assertEquals(TopLevelDestination.LIBRARY, navigator.selected)
        // A destination's route opened as a screen shows its stack: it is never pushed.
        navigator.navigate(Route.Settings)
        assertEquals(listOf<Route>(Route.Settings), navigator.backStack.value)
        assertEquals(TopLevelDestination.SETTINGS, navigator.selected)
        navigator.select(TopLevelDestination.BOARD)
        assertEquals(listOf<Route>(Route.Board), navigator.backStack.value)
        assertEquals(TopLevelDestination.BOARD, navigator.selected)
    }

    @Test
    fun aDestinationPickedAndPickedBackShowsItsStackAsItWasLeft() = runTest {
        val navigator = navigator()
        val detail = Route.Detail("series", "tt1")
        navigator.navigate(detail)
        val detailStore = navigator.store(detail)
        navigator.pick(TopLevelDestination.DISCOVER)
        val discover = navigator.store(Route.Discover)
        val pushed = Route.Detail("movie", "tt2")
        navigator.navigate(pushed)
        val pushedStore = navigator.store(pushed)
        navigator.pick(TopLevelDestination.BOARD)
        // Home's stack as it was left, its Detail's store kept, and Discover's too, under the Detail pushed on it.
        assertEquals(listOf(Route.Board, detail), navigator.backStack.value)
        assertEquals(TopLevelDestination.BOARD, navigator.selected)
        assertSame(detailStore, navigator.store(detail, "again"))
        assertFalse(detailStore.released)
        assertFalse(pushedStore.released)
        assertFalse(discover.released)
        // The Detail on the stack not shown is out of view; the one shown isn't.
        assertTrue(navigator.covered(pushed).first())
        assertFalse(navigator.covered(detail).first())
        navigator.pick(TopLevelDestination.DISCOVER)
        assertEquals(listOf(Route.Discover, pushed), navigator.backStack.value)
        assertSame(pushedStore, navigator.store(pushed, "again"))
        assertSame(discover, navigator.store(Route.Discover, "again"))
        assertTrue(navigator.covered(detail).first())
    }

    @Test
    fun theShownDestinationPickedAgainPopsToItsRoot() {
        val removed = mutableListOf<Route>()
        val navigator = navigator()
        navigator.addRemovalListener { removed += it }
        val detail = Route.Detail("series", "tt1")
        val similar = Route.Detail("series", "tt2")
        navigator.navigate(detail)
        navigator.navigate(similar)
        val detailStore = navigator.store(detail)
        val board = navigator.store(Route.Board)
        navigator.pick(TopLevelDestination.BOARD)
        assertEquals(listOf<Route>(Route.Board), navigator.backStack.value)
        assertEquals(setOf<Route>(detail, similar), removed.toSet())
        assertTrue(detailStore.released)
        assertFalse(board.released)
        // At its root, a pick changes nothing.
        navigator.pick(TopLevelDestination.BOARD)
        assertEquals(listOf<Route>(Route.Board), navigator.backStack.value)
        assertEquals(2, removed.size)
    }

    @Test
    fun backAtADestinationsRootShowsHomesStack() {
        val navigator = navigator()
        val detail = Route.Detail("series", "tt1")
        navigator.navigate(detail)
        navigator.pick(TopLevelDestination.SETTINGS)
        assertTrue(navigator.canGoBack)
        assertTrue(navigator.back())
        assertEquals(listOf(Route.Board, detail), navigator.backStack.value)
        assertTrue(navigator.back())
        assertFalse(navigator.canGoBack)
        assertFalse(navigator.back())
        assertEquals(listOf<Route>(Route.Board), navigator.backStack.value)
        // Settings' stack stays, at its root.
        navigator.pick(TopLevelDestination.SETTINGS)
        assertEquals(listOf<Route>(Route.Settings), navigator.backStack.value)
    }

    @Test
    fun thePlayerAndASheetLeaveWithTheStackLeft() {
        val removed = mutableListOf<Route>()
        val navigator = navigator()
        navigator.addRemovalListener { removed += it }
        val detail = Route.Detail("movie", "tt1")
        navigator.navigate(detail)
        navigator.navigate(Route.Player)
        val player = navigator.store(Route.Player)
        navigator.openStreams(movie, "tt1")
        // Another destination shown from over the player (a menu's shortcut, a link): the player and the sheet over it
        // close, and what lay under them waits on Home's stack.
        navigator.pick(TopLevelDestination.SEARCH)
        assertEquals(listOf<Route>(Route.Search), navigator.backStack.value)
        assertEquals(setOf(Route.Player, streams), removed.toSet())
        assertTrue(player.released)
        navigator.pick(TopLevelDestination.BOARD)
        assertEquals(listOf(Route.Board, detail), navigator.backStack.value)
        // A sheet alone over a screen goes too.
        navigator.openStreams(movie, "tt1")
        navigator.pick(TopLevelDestination.SEARCH)
        navigator.pick(TopLevelDestination.BOARD)
        assertEquals(listOf(Route.Board, detail), navigator.backStack.value)
        assertNull(navigator.meta("movie", "tt1"))
    }

    @Test
    fun aLinkToADestinationShowsItAtItsRoot() {
        val navigator = navigator()
        val link = DeepLink.Discover("https://addon.example/manifest.json", "movie", "top", emptyList())
        navigator.pick(TopLevelDestination.DISCOVER)
        val pushed = Route.Detail("movie", "tt2")
        navigator.navigate(pushed)
        val pushedStore = navigator.store(pushed)
        navigator.pick(TopLevelDestination.BOARD)
        // A Board row's See all: Discover's catalog, not the Detail left on it.
        navigator.openDiscover(link)
        assertEquals(listOf<Route>(Route.Discover), navigator.backStack.value)
        assertTrue(pushedStore.released)
        assertSame(link, navigator.takeDiscoverLink())
        navigator.navigate(pushed)
        navigator.openSearch("Emily Watson")
        assertEquals(listOf<Route>(Route.Search), navigator.backStack.value)
        navigator.pick(TopLevelDestination.DISCOVER)
        assertEquals(listOf(Route.Discover, pushed), navigator.backStack.value)
        navigator.openLibrary(LibrarySection.DOWNLOADS)
        assertEquals(listOf<Route>(Route.Library), navigator.backStack.value)
    }

    @Test
    fun pickingSearchAsksForItsFieldOnceEachTime() {
        val navigator = navigator()
        // Shown without a pick (a launch on it, a link): the field is left be.
        navigator.select(TopLevelDestination.SEARCH)
        assertFalse(navigator.takeSearchFocus())
        // Picked while it shows, then from another destination: asked each time, taken once.
        navigator.pick(TopLevelDestination.SEARCH)
        assertEquals(listOf<Route>(Route.Search), navigator.backStack.value)
        assertTrue(navigator.takeSearchFocus())
        assertFalse(navigator.takeSearchFocus())
        navigator.pick(TopLevelDestination.BOARD)
        navigator.pick(TopLevelDestination.SEARCH)
        assertTrue(navigator.searchFocus.value)
        // Left before its screen took it, the ask goes with Search; Back to Search asks nothing.
        navigator.pick(TopLevelDestination.LIBRARY)
        assertFalse(navigator.searchFocus.value)
        navigator.select(TopLevelDestination.SEARCH)
        navigator.navigate(Route.Detail("movie", "tt1"))
        navigator.back()
        assertFalse(navigator.takeSearchFocus())
        // Picked with a screen opened from it on top, Search shows that screen and asks nothing; picked again, its
        // field.
        navigator.navigate(Route.Detail("movie", "tt1"))
        navigator.pick(TopLevelDestination.BOARD)
        navigator.pick(TopLevelDestination.SEARCH)
        assertEquals(listOf(Route.Search, Route.Detail("movie", "tt1")), navigator.backStack.value)
        assertFalse(navigator.searchFocus.value)
        navigator.pick(TopLevelDestination.SEARCH)
        assertEquals(listOf<Route>(Route.Search), navigator.backStack.value)
        assertTrue(navigator.takeSearchFocus())
    }

    @Test
    fun aDestinationOpensWithAScreenOverItInOneChange() {
        val navigator = navigator()
        val detail = Route.Detail("movie", "tt1")
        navigator.navigate(detail)
        val detailStore = navigator.store(detail)
        navigator.pick(TopLevelDestination.SETTINGS)
        // Home's Watch together, from wherever the user is: over Home's root, which the chrome selects.
        navigator.select(TopLevelDestination.BOARD, Route.WatchTogether)
        assertEquals(listOf(Route.Board, Route.WatchTogether), navigator.backStack.value)
        assertEquals(TopLevelDestination.BOARD, navigator.selected)
        assertTrue(detailStore.released)
        // Asked again while it is there, nothing changes: the lobby's store stays.
        val lobby = navigator.store(Route.WatchTogether)
        navigator.select(TopLevelDestination.BOARD, Route.WatchTogether)
        assertSame(lobby, navigator.store(Route.WatchTogether, "again"))
        assertFalse(lobby.released)
    }

    @Test
    fun closeReleasesWhatEveryStackKept() {
        val navigator = navigator()
        val detail = Route.Detail("movie", "tt1")
        val board = navigator.store(Route.Board)
        navigator.navigate(detail)
        val detailStore = navigator.store(detail)
        navigator.pick(TopLevelDestination.DISCOVER)
        val discover = navigator.store(Route.Discover)
        navigator.close()
        assertTrue(board.released)
        assertTrue(detailStore.released)
        assertTrue(discover.released)
    }

    @Test
    fun aScreenOpenedFromAnotherDestinationLeavesTheStackItWasOnAndOpensAnew() {
        val navigator = navigator()
        val detail = Route.Detail("movie", "tt1")
        navigator.navigate(detail)
        navigator.navigate(Route.Detail("movie", "tt2"))
        val homeDetail = navigator.store(detail)
        val over = navigator.store(Route.Detail("movie", "tt2"))
        // The same title opened from Library: Home's stack drops it and the screen over it, Library's gets its own.
        navigator.pick(TopLevelDestination.LIBRARY)
        navigator.navigate(detail)
        assertEquals(listOf(Route.Library, detail), navigator.backStack.value)
        assertEquals(listOf<Route>(Route.Board), navigator.stacks.value.stack(TopLevelDestination.BOARD))
        assertTrue(homeDetail.released)
        assertTrue(over.released)
        assertNotSame(homeDetail, navigator.store(detail, "library's"))
        // A development session's first screen, the bottom of Home's stack, leaves it to the Board.
        val dev = navigator(start = detail)
        dev.pick(TopLevelDestination.DISCOVER)
        dev.navigate(detail)
        assertEquals(listOf<Route>(Route.Board), dev.stacks.value.stack(TopLevelDestination.BOARD))
    }

    @Test
    fun aScreenOpenedWhileTheShownStackHoldsItPopsBackToIt() {
        val removed = mutableListOf<Route>()
        val navigator = navigator()
        navigator.addRemovalListener { removed += it }
        val a = Route.Detail("movie", "tt1")
        val b = Route.Detail("movie", "tt2")
        // Detail A, More like this B, then More like this A: back to A, its store as it was; B leaves.
        navigator.navigate(a)
        val first = navigator.store(a)
        navigator.navigate(b)
        val over = navigator.store(b)
        navigator.navigate(a)
        assertEquals(listOf(Route.Board, a), navigator.backStack.value)
        assertSame(first, navigator.store(a, "again"))
        assertFalse(first.released)
        assertTrue(over.released)
        assertEquals(listOf<Route>(b), removed)

        // Replacing the top with a screen under it pops back to that one too.
        navigator.navigate(b)
        navigator.replace(a)
        assertEquals(listOf(Route.Board, a), navigator.backStack.value)
    }

    @Test
    fun savedStacksComeBackWithEachScreenOnce() {
        // A Detail opened to play a video and the same title's Detail showing that video's season save alike.
        val played = Route.Detail("movie", "tt1", "tt1", videoAction = DetailVideoAction.PLAY)
        val related = Route.Detail("movie", "tt2")
        val stacks = NavigationStacks(TopLevelDestination.BOARD, mapOf(TopLevelDestination.BOARD to listOf(Route.Board, played, related, Route.Detail("movie", "tt1", shownVideoId = "tt1"))))
        val restored = assertNotNull(SavedStacks.of(stacks).restore { true })
        assertEquals(listOf(Route.Board, Route.Detail("movie", "tt1", shownVideoId = "tt1"), related), restored.current)
    }

    /** The destination the chrome selects. */
    private val TortugaNavigator.selected: TopLevelDestination?
        get() = TopLevelDestination.selectedIn(backStack.value)
}
