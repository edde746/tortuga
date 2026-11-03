package com.edde746.tortuga.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.presentation.navigation.TortugaNavigator
import com.edde746.tortuga.presentation.search.RecentSearch
import com.edde746.tortuga.presentation.search.SearchBody
import com.edde746.tortuga.presentation.search.SearchGroup
import com.edde746.tortuga.presentation.search.SearchGroupStatus
import com.edde746.tortuga.presentation.search.SearchUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.OnTextChange
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.navigation.NavigationScaffold
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Search's field: picked in a phone's navigation bar, it takes the cursor; and the button that clears it, by a TV's
 * remote (the on-screen keyboard waits for Select) and by a desktop's keyboard; each recent search's Remove, which
 * screen readers find by the search it removes.
 */
@OptIn(ExperimentalTestApi::class)
class SearchScreenTest {
    private val typed = mutableListOf<String>()
    private var clears = 0
    private var recent by mutableStateOf(listOf("matrix"))

    private val field = SemanticsMatcher.keyIsDefined(SemanticsProperties.IsEditable)

    private fun editable(editable: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, editable)

    /**
     * Search opens with focus on its field, not editing it (an edited field has the keyboard up, which takes the
     * remote's keys); Select starts the editing. Down leaves the field for the recent searches, which ends it: focus
     * back on the field, Select again.
     */
    @Test
    fun onTvTheFieldTakesFocusAndIsEditedOnlyAfterSelect() = runComposeUiTest {
        search()
        onNode(field).assertIsFocused().assert(editable(false))
        press(Key.DirectionCenter)
        onNode(field).assertIsFocused().assert(editable(true)).performTextInput("dune")
        runOnIdle { assertEquals("dune", typed.last()) }
        press(Key.DirectionDown)
        onNode(field).assertIsNotFocused().assert(editable(false))
        onNode(field).requestFocus().assertIsFocused().assert(editable(false))
    }

    /**
     * The clear button, drawn over the field's end, is reached and pressed with a remote: at the text's end Right goes
     * to it (focus search from the field doesn't find it, and a remote has no Tab), Left back to the field, and Select
     * on it clears the query once, focus back on the field.
     */
    @Test
    fun onTvTheClearButtonIsReachedWithRightAndClearsOnSelect() = runComposeUiTest {
        search(query = "chernobyl")
        val clear = onNodeWithContentDescription(T.browse.search.clearQuery)
        onNode(field).assertIsFocused()
        press(Key.DirectionRight)
        clear.assertIsFocused()
        press(Key.DirectionLeft)
        onNode(field).assertIsFocused()
        press(Key.DirectionRight)
        clear.assertIsFocused()
        press(Key.DirectionCenter)
        runOnIdle { assertEquals(1, clears) }
        onNode(field).assertIsFocused().assert(hasText(""))
    }

    /**
     * On TV the first Down from the field lands on the first result's first card, not on the card under the field's
     * middle, which the cards' count and widths would pick; a later Down lands on the card that had focus last (one
     * card along the row, Up to the field, Down again). Results of 2, 4 and 12 cards; every wrong move is listed.
     */
    @Test
    fun onTvDownFromTheFieldLandsOnTheFirstCardThenOnTheLastFocused() {
        val wrong = mutableListOf<String>()
        for (count in listOf(2, 4, 12)) runComposeUiTest {
            val probe = FocusedProbe()
            val cards = List(count) { MetaCard("tt$it", "movie", "Dune $it", "2026", null, null, PosterShape.POSTER, null) }
            search(query = "dune", probe = probe, groups = listOf(SearchGroup("movies", "Movies", "Cinemeta", SearchGroupStatus.Ready(loadingMore = false), cards)))
            if (probe.focused != "search.field") wrong += "$count cards: the field doesn't open with focus (${probe.focused})"
            press(Key.DirectionDown)
            val first = probe.focused
            if (first != "search.group.0.item.0") wrong += "$count cards: the first Down from the field went to $first"
            press(if (first == "search.group.0.item.${count - 1}") Key.DirectionLeft else Key.DirectionRight)
            val last = probe.focused
            if (last == first || last?.startsWith("search.group.0.item.") != true) {
                wrong += "$count cards: along the row from $first focus went to $last"
                return@runComposeUiTest
            }
            press(Key.DirectionUp)
            if (probe.focused != "search.field") wrong += "$count cards: Up from $last went to ${probe.focused}"
            press(Key.DirectionDown)
            if (probe.focused != last) wrong += "$count cards: Down from the field went to ${probe.focused}, not $last, focused last"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * On desktop Tab goes through the clear button as through any control: from the field to it, then on to what
     * follows; Shift+Tab back to it, then to the field.
     */
    @Test
    fun onDesktopTabGoesThroughTheClearButton() = runComposeUiTest {
        search(query = "chernobyl", formFactor = FormFactor.DESKTOP)
        val clear = onNodeWithContentDescription(T.browse.search.clearQuery)
        onNode(field).requestFocus().assertIsFocused()
        press(Key.Tab)
        clear.assertIsFocused()
        press(Key.Tab)
        clear.assertIsNotFocused()
        onNode(field).assertIsNotFocused()
        press(Key.Tab, shift = true)
        clear.assertIsFocused()
        press(Key.Tab, shift = true)
        onNode(field).assertIsFocused()
    }

    /** Each recent search's Remove is named for its search, and removes that one only. */
    @Test
    fun eachRecentSearchsRemoveIsNamedForItAndRemovesThatOne() = runComposeUiTest {
        recent = listOf("matrix", "the bear")
        search(formFactor = FormFactor.HANDHELD)
        onNodeWithContentDescription(T.browse.search.removeRecentNamed(isolate("the bear"))).performClick()
        onNodeWithText("the bear").assertDoesNotExist()
        onNodeWithText("matrix").assertExists()
        onNodeWithContentDescription(T.browse.search.removeRecentNamed(isolate("matrix"))).performClick()
        onNodeWithText("matrix").assertDoesNotExist()
    }

    /**
     * By touch, Search's field takes the cursor (raising the software keyboard on a device) when the user picks Search
     * in a phone's navigation bar: from another destination, each time, and again while it shows. Search shown without
     * a pick (opened on, as a launch does, or come back to with Back from a screen opened from it) leaves it be.
     */
    @Test
    fun pickingSearchInThePhonesBarPutsTheCursorInItsField() = runComposeUiTest {
        val navigator = TortugaNavigator(Route.Board)
        phone(navigator)
        // A press of the pointer puts Compose in touch mode, as a finger does on a phone.
        onRoot().performMouseInput { click(center) }
        runOnIdle { navigator.select(TopLevelDestination.SEARCH) }
        onNode(field).assertIsNotFocused()
        pick(TopLevelDestination.SEARCH)
        onNode(field).assertIsFocused()
        pick(TopLevelDestination.BOARD)
        pick(TopLevelDestination.SEARCH)
        onNode(field).assertIsFocused()
        runOnIdle { navigator.navigate(Route.Detail("movie", "tt0133093")) }
        onNode(field).assertDoesNotExist()
        runOnIdle { navigator.back() }
        onNode(field).assertIsNotFocused()
        pick(TopLevelDestination.SEARCH)
        onNode(field).assertIsFocused()
    }

    /** A press on [destination] in the navigation bar. */
    private fun ComposeUiTest.pick(destination: TopLevelDestination) {
        onNode(hasText(destination.shortLabel) and hasClickAction()).performMouseInput { click() }
        waitForIdle()
    }

    private fun ComposeUiTest.press(key: Key, shift: Boolean = false) {
        onRoot().performKeyInput { if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key) }
        waitForIdle()
    }

    private fun ComposeUiTest.search(
        query: String = "",
        formFactor: FormFactor = FormFactor.TV,
        probe: FocusedProbe = FocusedProbe(),
        groups: List<SearchGroup> = emptyList(),
    ) = setContent {
        val text = remember { TextFieldState(query) }
        OnTextChange(text) { typed += it }
        CompositionLocalProvider(LocalLayoutProbe provides probe) {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                        SearchScreen(
                            state = SearchUiState(
                                query = "",
                                searching = false,
                                body = when {
                                    groups.isNotEmpty() -> SearchBody.Results(groups)
                                    recent.isNotEmpty() -> SearchBody.Recent(recent.map(::recentSearch))
                                    else -> SearchBody.Hint
                                },
                            ),
                            field = text,
                            fieldFocusAsked = false,
                            onFieldFocusTaken = {},
                            onSubmit = {},
                            onClear = {
                                text.clearText()
                                clears++
                            },
                            onPickRecent = {},
                            onRemoveRecent = { index -> recent = recent.filterIndexed { i, _ -> i != index } },
                            onClearRecent = {},
                            onRetry = {},
                            onLoadMore = {},
                            onOpenItem = {},
                            onItemMenu = { _, _ -> },
                            onOpenDownloads = {},
                        )
                    }
                }
            }
        }
    }

    /**
     * A phone's app over [navigator]: its navigation bar picking ([TortugaNavigator.pick]) and Search as the app wires
     * it (`SearchRoute`), any other screen blank; each screen keeps its state while another shows, as the app's do.
     */
    private fun ComposeUiTest.phone(navigator: TortugaNavigator) = setContent {
        val stack by navigator.backStack.collectAsState()
        val picked by navigator.searchFocus.collectAsState()
        val screens = rememberSaveableStateHolder()
        val text = remember { TextFieldState() }
        TortugaTheme {
            Box(Modifier.size(412.dp, 892.dp)) {
                AdaptiveLayout(FormFactor.HANDHELD) {
                    NavigationScaffold(TopLevelDestination.entries, TopLevelDestination.selectedIn(stack), navigator::pick, showNavigation = true) {
                        val top = stack.last()
                        screens.SaveableStateProvider(top.toString()) {
                            if (top == Route.Search) {
                                SearchScreen(
                                    state = recentState,
                                    field = text,
                                    fieldFocusAsked = picked,
                                    onFieldFocusTaken = { navigator.takeSearchFocus() },
                                    onSubmit = {},
                                    onClear = {},
                                    onPickRecent = {},
                                    onRemoveRecent = {},
                                    onClearRecent = {},
                                    onRetry = {},
                                    onLoadMore = {},
                                    onOpenItem = {},
                                    onItemMenu = { _, _ -> },
                                    onOpenDownloads = {},
                                )
                            } else {
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }

    private val recentState = SearchUiState(query = "", searching = false, body = SearchBody.Recent(listOf(recentSearch("matrix"))))

    /** A recent search as the store lists it, its Remove named for it. */
    private fun recentSearch(query: String) = RecentSearch(query, T.browse.search.removeRecentNamed(isolate(query)))
}
