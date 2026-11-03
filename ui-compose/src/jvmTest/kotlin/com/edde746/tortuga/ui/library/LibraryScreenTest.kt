package com.edde746.tortuga.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.library.LibraryBody
import com.edde746.tortuga.presentation.library.LibraryRow
import com.edde746.tortuga.presentation.library.LibrarySort
import com.edde746.tortuga.presentation.library.LibrarySortOption
import com.edde746.tortuga.presentation.library.LibraryTypeOption
import com.edde746.tortuga.presentation.library.LibraryUiState
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.strings.InterfaceLocale
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.navigation.NavigationScaffold
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LibraryScreenTest {
    private val state = LibraryUiState(
        title = "Continue Watching",
        types = listOf(LibraryTypeOption(null, "All", false), LibraryTypeOption("movie", "Movies", true), LibraryTypeOption("series", "Series", false)),
        sorts = listOf(LibrarySortOption(LibrarySort.LAST_WATCHED, "Recently watched", true), LibrarySortOption(LibrarySort.NAME, "Name", false)),
        body = LibraryBody.Grid(
            listOf(
                LibraryRow(
                    "tt0111161", "movie", "The Shawshank Redemption", null, PosterShape.POSTER, 0.4, watched = false,
                    menu = emptyList(),
                ),
            ),
            hasMore = false,
        ),
    )

    @AfterTest
    fun restoreLanguage() = InterfaceLocale.setChosen(null)

    /**
     * On the narrowest phone window (a Fold's 320 dp cover screen), with the longest view and a type picked, the view,
     * type and sort menus and the Calendar's button share one row inside the window, side by side, none past its edge,
     * the button at the row's end; it opens the Calendar.
     */
    @Test
    fun onANarrowWindowTheFilterMenusFitInOneRow() = runComposeUiTest {
        var calendar = 0
        library(width = 320, selectedTab = 1, onOpenCalendar = { calendar++ })
        val view = onNode(hasClickAction() and hasText("Continue Watching")).getUnclippedBoundsInRoot()
        val type = onNode(hasClickAction() and hasText("Movies")).getUnclippedBoundsInRoot()
        val sort = onNode(hasClickAction() and hasContentDescription("Recently watched", substring = true)).getUnclippedBoundsInRoot()
        val button = onNode(hasClickAction() and hasContentDescription(T.calendar.title))
        val end = button.getUnclippedBoundsInRoot()
        assertTrue(view.left >= 0.dp && end.right <= 320.dp, "the row runs from ${view.left} to ${end.right} in a 320 dp window")
        assertTrue(view.right <= type.left && type.right <= sort.left && sort.right <= end.left, "the row overlaps: view ${view.left}–${view.right}, type ${type.left}–${type.right}, sort ${sort.left}–${sort.right}, calendar ${end.left}–${end.right}")
        assertEquals(view.top, type.top, "the type menu isn't on the view menu's row")
        assertEquals(view.top, sort.top, "the sort menu isn't on the view menu's row")
        assertTrue(end.top < view.bottom && end.bottom > view.top, "the Calendar's button at ${end.top}–${end.bottom} isn't on the menus' row at ${view.top}–${view.bottom}")
        assertTrue(320.dp - end.right <= 17.dp, "the Calendar's button ends at ${end.right}, not at the row's end")
        button.performClick()
        runOnIdle { assertEquals(1, calendar) }
    }

    /** On a narrow window Library and Continue Watching are the options of the view menu. */
    @Test
    fun onANarrowWindowTheViewMenuSwitchesToContinueWatching() = runComposeUiTest {
        val picked = mutableListOf<Int>()
        library(width = 411, selectedTab = 0, onSelectTab = { picked += it })
        onNode(hasClickAction() and hasText("Library")).performClick()
        onNode(hasClickAction() and hasText("Continue Watching")).performClick()
        assertEquals(listOf(1), picked)
    }

    /**
     * Library and Continue Watching each keep their own place in the grid and ask for their own next page: switching
     * from Library scrolled far down shows Continue Watching from its top, scrolling to its end pages Continue
     * Watching (not Library), and switching back finds Library where it was left.
     */
    @Test
    fun eachSectionKeepsItsOwnPlaceAndPagesItself() = runComposeUiTest {
        var tab by mutableIntStateOf(0)
        val loads = mutableListOf<String>()
        setContent {
            TortugaTheme {
                Box(Modifier.size(411.dp, 800.dp)) {
                    AdaptiveLayout(FormFactor.HANDHELD) {
                        val section = if (tab == 0) "library" else "continue"
                        LibraryScreen(
                            page(section),
                            tabs = listOf("Library", "Continue Watching"),
                            selectedTab = tab,
                            onSelectTab = {},
                            onSelectType = {},
                            onSelectSort = {},
                            onOpen = {},
                            onMenu = { _, _ -> },
                            onLoadMore = { loads += section },
                            onOpenCalendar = {},
                        )
                    }
                }
            }
        }
        onNode(hasScrollToIndexAction()).performScrollToIndex(50)
        waitForIdle()
        onNode(hasContentDescription("library50")).assertIsDisplayed()
        loads.clear()
        tab = 1
        waitForIdle()
        onNode(hasContentDescription("continue0")).assertIsDisplayed()
        onNode(hasScrollToIndexAction()).performScrollToIndex(PAGE - 1)
        waitForIdle()
        assertTrue("continue" in loads && "library" !in loads, "scrolling Continue Watching to its end asked for $loads")
        tab = 0
        waitForIdle()
        onNode(hasContentDescription("library50")).assertIsDisplayed()
    }

    /**
     * In Arabic the narrow filter row runs from the right: the view menu at the right edge, the sort menu left of it, the
     * Calendar's button at the left end.
     */
    @Test
    fun onANarrowWindowTheFilterMenusRunFromTheRightInArabic() = runComposeUiTest {
        library(width = 411, selectedTab = 1, layoutDirection = LayoutDirection.Rtl)
        val view = onNode(hasClickAction() and hasText("Continue Watching")).getUnclippedBoundsInRoot()
        val sort = onNode(hasClickAction() and hasContentDescription("Recently watched", substring = true)).getUnclippedBoundsInRoot()
        val calendar = onNode(hasClickAction() and hasContentDescription(T.calendar.title)).getUnclippedBoundsInRoot()
        assertTrue(view.right > 300.dp && sort.right <= view.left, "the view menu runs ${view.left}–${view.right}, the sort menu ${sort.left}–${sort.right}")
        assertTrue(calendar.left <= 17.dp && calendar.right <= sort.left, "the Calendar's button runs ${calendar.left}–${calendar.right}, the sort menu ${sort.left}–${sort.right}")
    }

    /**
     * Each filter menu shows one text and a screen reader hears its name with its selection: on a phone the type's chip
     * says "Type" while every type shows, heard as "Type: All", and "Movies" once Movies narrows the list, selected,
     * heard as "Type: Movies"; on a wide window the sort's chip says its order, heard as "Sort by: Recently watched".
     */
    @Test
    fun eachFilterMenuShowsOneTextAndIsHeardWithItsName() {
        runComposeUiTest {
            library(width = 411, selectedTab = 0, shown = state.copy(types = state.types.map { it.copy(selected = it.type == null) }))
            onNode(hasClickAction() and hasText("Type"))
                .assert(hasContentDescription("\u2068Type\u2069: \u2068All\u2069"))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        }
        runComposeUiTest {
            library(width = 411, selectedTab = 0)
            onNode(hasClickAction() and hasText("Movies")).assert(hasContentDescription("\u2068Type\u2069: \u2068Movies\u2069")).assertIsSelected()
        }
        runComposeUiTest {
            library(width = 1280, selectedTab = 0)
            onNode(hasClickAction() and hasText("Recently watched")).assert(hasContentDescription("Sort by: Recently watched"))
        }
    }

    /** On TV the remote's Play/Pause opens a poster in Continue Watching, as Select does (it resumes); in the library, nothing. */
    @Test
    fun onTvPlayPauseOpensAPosterInContinueWatchingAlone() {
        for (continueWatching in listOf(true, false)) runComposeUiTest {
            val opened = mutableListOf<String>()
            setContent {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        AdaptiveLayout(FormFactor.TV) {
                            LibraryScreen(
                                state.copy(continueWatching = continueWatching),
                                tabs = listOf("Library", "Continue Watching"),
                                selectedTab = if (continueWatching) 1 else 0,
                                onSelectTab = {},
                                onSelectType = {},
                                onSelectSort = {},
                                onOpen = { opened += it },
                                onMenu = { _, _ -> },
                                onLoadMore = {},
                                onOpenCalendar = {},
                            )
                        }
                    }
                }
            }
            onNode(hasContentDescription("The Shawshank Redemption") and hasClickAction()).assertIsFocused()
            onRoot().performKeyInput { pressKey(Key.MediaPlayPause) }
            runOnIdle { assertEquals(if (continueWatching) listOf("tt0111161") else emptyList(), opened, "continueWatching=$continueWatching") }
        }
    }

    /**
     * In every language of the app, in its frame on a phone, tablets 960 and 1280 dp wide, a narrow and a wide desktop
     * window and a 1080p TV, each chip (or menu) of the filter row and the Calendar's button lie whole in the screen's
     * place and none on another. The type chips ran on under the sort chip where the row was too narrow for
     * them (on Fire TV in Spanish, French, Polish and Brazilian Portuguese).
     */
    @Test
    fun noFilterChipLiesUnderAnotherInAnyLanguageOrWindow() {
        val wrong = mutableListOf<String>()
        for ((formFactor, size) in windows) runComposeUiTest {
            var language by mutableStateOf(InterfaceLocale.available.first())
            val probe = PlaceProbe()
            InterfaceLocale.setChosen(language)
            setContent { Frame(language, formFactor, size, probe) }
            for (tag in InterfaceLocale.available) {
                InterfaceLocale.setChosen(tag)
                language = tag
                waitForIdle()
                val at = "$tag on a ${size.width} × ${size.height} $formFactor window"
                val place = checkNotNull(probe.place) { "the screen's place wasn't laid out" }
                val chips = filterChips(place)
                if (chips.size < 4) wrong += "$at: the row shows ${chips.map { it.name }}"
                for (chip in chips) {
                    if (chip.bounds.left < place.left || chip.bounds.right > place.right) wrong += "$at: ${chip.name} at ${chip.bounds} runs out of the place at $place"
                }
                chips.zipWithNext { a, b -> if (a.bounds.overlaps(b.bounds)) wrong += "$at: ${a.name} at ${a.bounds} and ${b.name} at ${b.bounds} overlap" }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * On a 1080p TV in Brazilian Portuguese, whose labels ran on under the sort chip, and in English, which fitted: the
     * last type chip ends before the sort chip, the Calendar's button ends the row, and Right from the first tab reaches
     * every chip of the row in turn, then the button.
     */
    @Test
    fun onTvNoTypeChipIsUnderTheSortChipAndRightReachesEveryChip() {
        for (language in listOf("pt-BR", "en")) runComposeUiTest {
            InterfaceLocale.setChosen(language)
            val probe = PlaceProbe()
            setContent { Frame(language, FormFactor.TV, DpSize(960.dp, 540.dp), probe) }
            waitForIdle()
            val chips = filterChips(checkNotNull(probe.place))
            val sort = chips[chips.size - 2]
            assertEquals(tabs() + listOf(T.browse.type.all, T.browse.type.movie, T.browse.type.series), chips.dropLast(2).map { it.name }, "$language: the row's chips before the sort")
            assertTrue(T.library.sort.lastWatched in sort.name, "$language: the row ends in ${sort.name}, not the sort")
            assertEquals(T.calendar.title, chips.last().name, "$language: the Calendar's button doesn't end the row")
            val series = chips[chips.size - 3]
            assertFalse(series.bounds.overlaps(sort.bounds), "$language: ${series.name} at ${series.bounds} lies under the sort at ${sort.bounds}")
            onNode(SemanticsMatcher("node ${chips.first().id}") { it.id == chips.first().id }).requestFocus()
            for (chip in chips.drop(1)) {
                onRoot().performKeyInput { pressKey(Key.DirectionRight) }
                waitForIdle()
                assertEquals(chip.name, onNode(isFocused()).fetchSemanticsNode().name, "$language: Right didn't reach ${chip.name}")
            }
        }
    }

    /** A section's first page of [PAGE] posters named `<section><i>`, with more to come. */
    private fun page(section: String) = state.copy(
        body = LibraryBody.Grid(
            List(PAGE) { LibraryRow("$section$it", "movie", "$section$it", null, PosterShape.POSTER, null, watched = false, menu = emptyList()) },
            hasMore = true,
        ),
    )

    private fun ComposeUiTest.library(
        width: Int,
        selectedTab: Int,
        onSelectTab: (Int) -> Unit = {},
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        onOpenCalendar: () -> Unit = {},
        shown: LibraryUiState = state,
    ) = setContent {
        TortugaTheme {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                Box(Modifier.size(width.dp, 800.dp)) {
                    AdaptiveLayout(FormFactor.HANDHELD) {
                        LibraryScreen(
                            shown,
                            tabs = listOf("Library", "Continue Watching"),
                            selectedTab = selectedTab,
                            onSelectTab = onSelectTab,
                            onSelectType = {},
                            onSelectSort = {},
                            onOpen = {},
                            onMenu = { _, _ -> },
                            onLoadMore = {},
                            onOpenCalendar = onOpenCalendar,
                        )
                    }
                }
            }
        }
    }

    /** A phone, tablets 960 and 1280 dp wide, a narrow and a wide desktop window and a 1080p TV. */
    private val windows = listOf(
        FormFactor.HANDHELD to DpSize(411.dp, 891.dp),
        FormFactor.HANDHELD to DpSize(960.dp, 600.dp),
        FormFactor.HANDHELD to DpSize(1280.dp, 800.dp),
        FormFactor.DESKTOP to DpSize(700.dp, 600.dp),
        FormFactor.DESKTOP to DpSize(1280.dp, 800.dp),
        FormFactor.TV to DpSize(960.dp, 540.dp),
    )

    /** The Library's tabs in the current language. */
    private fun tabs() = listOf(T.library.title, T.library.continueWatching, T.downloads.title)

    /**
     * The Library in the app's frame (the bar, the rail or the TV column beside it) on a [formFactor] window of [size],
     * in [language] (right to left in Arabic) with its three types and its sort, the arrow keys as the D-pad; [probe]
     * gets the screen's place. Its texts are taken as [language] is shown: choose it ([InterfaceLocale.setChosen]) first.
     */
    @Composable
    private fun Frame(language: String, formFactor: FormFactor, size: DpSize, probe: PlaceProbe) {
        val filters = remember(language) {
            state.copy(
                types = listOf(LibraryTypeOption(null, T.browse.type.all, true), LibraryTypeOption("movie", T.browse.type.movie, false), LibraryTypeOption("series", T.browse.type.series, false)),
                sorts = listOf(LibrarySortOption(LibrarySort.LAST_WATCHED, T.library.sort.lastWatched, true), LibrarySortOption(LibrarySort.NAME, T.library.sort.name, false)),
            )
        }
        val direction = if (InterfaceLocale.isRightToLeft(language)) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutProbe provides probe, LocalLayoutDirection provides direction) {
            TortugaTheme {
                Box(Modifier.size(size)) {
                    AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                        NavigationScaffold(TopLevelDestination.entries, TopLevelDestination.LIBRARY, onSelect = {}, showNavigation = true) {
                            LibraryScreen(
                                filters,
                                tabs = remember(language) { tabs() },
                                selectedTab = 0,
                                onSelectTab = {},
                                onSelectType = {},
                                onSelectSort = {},
                                onOpen = {},
                                onMenu = { _, _ -> },
                                onLoadMore = {},
                                onOpenCalendar = {},
                            )
                        }
                    }
                }
            }
        }
    }

    /** The screen's place beside the navigation (its harness name `nav.content`), in the window's pixels. */
    private class PlaceProbe : LayoutProbe {
        var place: Rect? = null

        override fun place(name: String, bounds: Rect?) {
            if (name == "nav.content") place = bounds
        }
    }

    /** A chip of the filter row: its [name] (its text, or what a screen reader hears) and where all of it lies. */
    private class Chip(val id: Int, val name: String, val bounds: Rect)

    /**
     * The filter row's chips and menus and the Calendar's button, from the left: the tabs, dropdowns and button in the
     * screen's [place] (not the navigation's), each where all of it lies, however much of it shows.
     */
    private fun ComposeUiTest.filterChips(place: Rect): List<Chip> =
        onAllNodes(hasClickAction() and (hasRole(Role.Tab) or hasRole(Role.DropdownList) or hasContentDescription(T.calendar.title))).fetchSemanticsNodes()
            .map { Chip(it.id, it.name, Rect(it.positionInRoot, it.size.toSize())) }
            .filter { place.contains(it.bounds.center) }
            .sortedBy { it.bounds.left }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private val SemanticsNode.name: String
        get() = (config.getOrNull(SemanticsProperties.Text) ?: config.getOrNull(SemanticsProperties.ContentDescription))?.joinToString().orEmpty()
}

private const val PAGE = 60
