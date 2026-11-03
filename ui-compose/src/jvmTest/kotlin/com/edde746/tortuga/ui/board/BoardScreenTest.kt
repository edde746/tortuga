package com.edde746.tortuga.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.BoardBody
import com.edde746.tortuga.presentation.board.BoardRow
import com.edde746.tortuga.presentation.board.BoardShortcut
import com.edde746.tortuga.presentation.board.BoardShortcutKind
import com.edde746.tortuga.presentation.board.BoardUiState
import com.edde746.tortuga.presentation.board.ContinueWatchingCard
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.board.RowStatus
import com.edde746.tortuga.presentation.board.SeeAll
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.focus.FocusVisibility
import com.edde746.tortuga.ui.focus.LocalFocusVisibility
import com.edde746.tortuga.ui.focus.LocalScreenFocus
import com.edde746.tortuga.ui.focus.ScreenFocus
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.focus.trackFocusVisibility
import com.edde746.tortuga.ui.navigation.NavigationScaffold
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import java.awt.event.KeyEvent
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BoardScreenTest {
    private val catalogs = listOf("Popular · Movies", "Popular · Series", "Featured · Movies", "Featured · Series").map { row(it, fromTracker = false) }

    /** What arrives after the catalogs: the library's Continue Watching and a tracker's list, both above them. */
    private fun BoardUiState.withHead() = copy(
        continueWatching = listOf(
            ContinueWatchingCard("tt0111161", "movie", "tt0111161", "The Shawshank Redemption", "1 min left", 0.85f, null, PosterShape.POSTER),
        ),
        body = BoardBody.Rows(listOf(row("Watchlist", fromTracker = true)) + rows),
    )

    /** The rows on the page. */
    private val BoardUiState.rows: List<BoardRow>
        get() = (body as BoardBody.Rows).rows

    private fun BoardUiState.withRows(rows: List<BoardRow>, shortcuts: List<BoardShortcut> = this.shortcuts) =
        copy(body = BoardBody.Rows(rows), shortcuts = shortcuts)

    /**
     * The Board shows its catalogs once the installed addons are read; Continue Watching (the library) and the
     * trackers' lists (a network call) can come in after that, above them, while the list is still at its top.
     */
    @Test
    fun continueWatchingAndTrackerRowsArrivingAfterTheCatalogsShowAtTheTop() = runComposeUiTest {
        var state by mutableStateOf(BoardUiState(emptyList(), BoardBody.Rows(catalogs)))
        board { state }
        onNodeWithText("Popular · Movies").assertIsDisplayed()

        state = state.withHead()
        onNodeWithText(T.browse.continueWatching.title).assertIsDisplayed()
        onNodeWithText("Watchlist").assertIsDisplayed()
    }

    /** Scrolled down with the wheel, the Board keeps the row it shows at the top where it is. */
    @Test
    fun aBoardScrolledDownKeepsItsRowInPlaceAsRowsArriveAbove() = runComposeUiTest {
        var state by mutableStateOf(BoardUiState(emptyList(), BoardBody.Rows(catalogs)))
        board { state }
        onNodeWithText("Popular · Movies").performMouseInput { moveTo(center); scroll(5f) }
        waitForIdle()
        val top = onNodeWithText("Popular · Series").getUnclippedBoundsInRoot().top

        state = state.withHead()
        assertEquals(top, onNodeWithText("Popular · Series").getUnclippedBoundsInRoot().top)
    }

    /**
     * On TV the Board focuses its first card as it opens. Continue Watching and a tracker's list arriving after the
     * catalogs, before the user moved, keep the Board at its top, and focus moves to the new first card (Continue
     * Watching's): a remote's first press starts from the top, as on a Board that had them from the start.
     */
    @Test
    fun onTvRowsArrivingAboveBeforeTheUserMovedKeepTheTopAndFocusTheirFirstCard() = runComposeUiTest {
        var state by mutableStateOf(BoardUiState(emptyList(), BoardBody.Rows(catalogs)))
        tvBoard { state }
        assertCardFocused("Popular · Movies 0")

        state = state.withHead()
        onNodeWithText(T.browse.continueWatching.title).assertIsDisplayed()
        assertCardFocused("The Shawshank Redemption")
    }

    /**
     * On TV a row with more than it shows ([SeeAll.CARD]) ends in the See all card: Right from its last card focuses
     * it, and Select opens the row's catalog. Neither that row's heading nor one showing its whole catalog
     * ([SeeAll.NONE]) has a See all.
     */
    @Test
    fun onTvARowsSeeAllCardFollowsItsLastCardAndOpensItsCatalog() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val rows = catalogs.mapIndexed { i, row -> row.copy(seeAll = if (i == 0) SeeAll.CARD else SeeAll.NONE) }
        tvBoard(calls) { BoardUiState(emptyList(), BoardBody.Rows(rows)) }
        assertCardFocused("Popular · Movies 0")
        repeat(rows[0].items.size) { press(Key.DirectionRight) }
        onNode(isFocused()).assert(hasText(T.browse.board.seeAll))
        onAllNodesWithText(T.browse.board.seeAll).assertCountEquals(1)

        press(Key.DirectionCenter)
        assertEquals(listOf("seeAll:0"), calls)
    }

    /**
     * The catalogs load in any order: the second one's cards can come first, and the TV Board focuses its first card
     * then. Before the user moved, focus follows to the first catalog's first card as its cards come, the Board at its
     * top, as on a Board whose catalogs came in order.
     */
    @Test
    fun onTvTheFirstCatalogArrivingAfterTheSecondTakesTheFocusTheBoardPlaced() = runComposeUiTest {
        val first = catalogs.first().copy(status = RowStatus.Loading, items = emptyList())
        var state by mutableStateOf(BoardUiState(emptyList(), BoardBody.Rows(listOf(first) + catalogs.drop(1))))
        tvBoard { state }
        assertCardFocused("Popular · Series 0")

        state = state.withRows(catalogs)
        assertCardFocused("Popular · Movies 0")
        onNodeWithText("Popular · Movies").assertIsDisplayed()
    }

    /** Moved into a row of its own, the TV Board keeps that card focused and in view as rows arrive above. */
    @Test
    fun onTvACardTheUserMovedToKeepsFocusAsRowsArriveAbove() = runComposeUiTest {
        var state by mutableStateOf(BoardUiState(emptyList(), BoardBody.Rows(catalogs)))
        tvBoard { state }
        assertCardFocused("Popular · Movies 0")
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        assertCardFocused("Popular · Movies 1")

        state = state.withHead()
        assertCardFocused("Popular · Movies 1")
        onNodeWithText("Popular · Movies 1").assertIsDisplayed()
    }

    /**
     * Back on the TV Board from the card the user opened (the first, played from its Detail), focus is on that card
     * again and stays there as Continue Watching arrives above it after the Board showed (the progress saved as the
     * player closed), the card in view.
     */
    @Test
    fun onTvTheCardFocusCameBackToKeepsFocusAsContinueWatchingArrivesAbove() = runComposeUiTest {
        var state by mutableStateOf(loaded)
        var away by mutableStateOf(false)
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                        // The navigation's: the Board's saved state (its focus memory) while another screen is on top.
                        val screens = rememberSaveableStateHolder()
                        if (!away) screens.SaveableStateProvider("board") { boardScreen(state) }
                    }
                }
            }
        }
        assertCardFocused("Popular · Movies 0")
        away = true
        waitForIdle()
        away = false
        assertCardFocused("Popular · Movies 0")

        state = state.copy(continueWatching = state.withHead().continueWatching)
        assertCardFocused("Popular · Movies 0")
        onNodeWithText("Popular · Movies 0").assertIsDisplayed()
    }

    /**
     * On TV a focused Continue Watching card that leaves the row after the Board shows (watched to its end) hands focus
     * to the card that takes its place; the row's last card, to the card before it.
     */
    @Test
    fun onTvAFocusedCardThatLeavesItsRowHandsFocusToTheCardTakingItsPlace() = runComposeUiTest {
        val cards = listOf("First", "Second", "Third").mapIndexed { i, title ->
            ContinueWatchingCard("tt$i", "movie", "tt$i", title, "1 min left", 0.5f, null, PosterShape.POSTER)
        }
        var state by mutableStateOf(loaded.copy(continueWatching = cards))
        tvBoard { state }
        assertCardFocused("First")

        state = state.copy(continueWatching = cards.drop(1))
        assertCardFocused("Second")

        press(Key.DirectionRight)
        assertCardFocused("Third")
        state = state.copy(continueWatching = cards.subList(1, 2))
        assertCardFocused("Second")
    }

    /**
     * A shelf on screen reports itself to the store, loaded or not, so the store loads the row after it before the
     * list composes that row ahead of a scroll. The first rows load at once: the row after them, requested only once
     * composed itself, got its items after the list had composed it as a loading shelf, and its cards were composed
     * inside the frame that scrolled it in.
     */
    @Test
    fun loadedShelvesOnScreenReportThemselves() = runComposeUiTest {
        val reported = mutableListOf<Int>()
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    AdaptiveLayout(FormFactor.TV) { boardScreen(loaded, onRowVisible = { reported += it }) }
                }
            }
        }
        waitForIdle()
        assertTrue(reported.containsAll(listOf(0, 1)), "the loaded shelves on screen, 0 and 1, reported only $reported")
    }

    /**
     * On a phone, beside a tablet's rail, on TV and in a desktop window, narrow or wide, nothing sits above Home's first
     * content: the first shelf's heading is at the screen's top, past only the list's top padding.
     */
    @Test
    fun nothingSitsAboveTheFirstShelf() {
        for ((formFactor, width) in windows) {
            runComposeUiTest {
                board(formFactor, width, 540) { loaded }
                val heading = onNodeWithText("Popular · Movies").assertIsDisplayed().getUnclippedBoundsInRoot()
                assertTrue(heading.top < 40.dp, "on $formFactor at $width dp the first shelf's heading at $heading leaves a header's room above it")
            }
        }
    }

    /**
     * On every layout, desktop's narrow and wide windows among them, Home's shortcuts are one line of chips in the
     * store's order, right under Continue Watching and above the next shelf; without Continue Watching, at the top of
     * the shelves.
     */
    @Test
    fun theShortcutsComeUnderContinueWatchingInTheirOrder() {
        for ((formFactor, width) in windows) {
            runComposeUiTest {
                var state by mutableStateOf(loaded.withHead().copy(shortcuts = shortcuts))
                board(formFactor, width, 540) { state }
                val where = "on $formFactor at $width dp"
                val labels = shortcuts.map { onNodeWithText(it.label).assertIsDisplayed().getUnclippedBoundsInRoot() }
                val above = onNodeWithText("The Shawshank Redemption").getUnclippedBoundsInRoot()
                val below = onNodeWithText("Watchlist").getUnclippedBoundsInRoot()
                assertEquals(labels.sortedBy { it.left.value }, labels, "$where: the shortcuts at $labels aren't in the store's order")
                assertTrue(labels.all { it.top == labels.first().top }, "$where: the shortcuts at $labels aren't on one line")
                assertTrue(
                    labels.first().top > above.bottom && labels.first().bottom < below.top,
                    "$where: the shortcuts at ${labels.first()} aren't between Continue Watching ($above) and the next shelf ($below)",
                )

                state = loaded.copy(shortcuts = shortcuts)
                val first = onNodeWithText(shortcuts.first().label).getUnclippedBoundsInRoot()
                val shelf = onNodeWithText("Popular · Movies").getUnclippedBoundsInRoot()
                assertTrue(first.bottom < shelf.top, "$where: without Continue Watching the shortcuts at $first aren't above the first shelf at $shelf")
            }
        }
    }

    /**
     * In a desktop window beside the rail the keyboard goes through Home's shortcuts as a remote does: Down from
     * Continue Watching lands on the first one, Tab and the arrow keys go along them in order, Enter opens one, Down
     * goes on to the next shelf's first card and Up comes back to the first shortcut. A click opens one too.
     */
    @Test
    fun onDesktopTheKeyboardAndTheMouseOpenTheShortcuts() = runComposeUiTest {
        val calls = mutableListOf<String>()
        setContent { TortugaTheme { app(FormFactor.DESKTOP, 1280, 800, loaded.withHead().copy(shortcuts = shortcuts), calls) } }
        assertCardFocused("The Shawshank Redemption")
        press(Key.DirectionDown)
        onNode(isFocused()).assert(hasText("Watch together"))
        press(Key.Tab)
        onNode(isFocused()).assert(hasText("Downloads"))
        press(Key.DirectionRight)
        onNode(isFocused()).assert(hasText("Library"))
        press(Key.Tab, shift = true)
        onNode(isFocused()).assert(hasText("Downloads"))
        press(Key.Enter)
        runOnIdle { assertEquals(listOf("shortcut:downloads"), calls) }

        press(Key.DirectionDown)
        assertCardFocused("Watchlist 0")
        press(Key.DirectionUp)
        onNode(isFocused()).assert(hasText("Watch together"))

        onNodeWithText("Watch together").performMouseInput { click() }
        runOnIdle { assertEquals(listOf("shortcut:downloads", "shortcut:watchTogether"), calls) }
    }

    /**
     * In a desktop window the keyboard walks the shelves both ways through the same stops: from a card at the first
     * shelf's right, Down goes through each shelf's See all to the card under it, and as many presses of Up come back
     * through them to that card, never on past the first shelf to the shortcuts.
     */
    @Test
    fun onDesktopUpRetracesDownThroughTheShelvesAndTheirSeeAll() = runComposeUiTest {
        val probe = FocusedProbe()
        val rows = catalogs.map { row ->
            row.copy(items = List(20) { MetaCard("${row.title} $it", "movie", "${row.title} $it", "2026", null, null, PosterShape.POSTER, null) })
        }
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme { app(FormFactor.DESKTOP, 1280, 720, loaded.withRows(rows, shortcuts = shortcuts)) }
            }
        }
        assertCardFocused("Popular · Movies 0")
        repeat(7) { press(Key.DirectionRight) }
        val start = runOnIdle { probe.focused }
        val down = List(4) { press(Key.DirectionDown); runOnIdle { probe.focused } }
        val up = List(4) { press(Key.DirectionUp); runOnIdle { probe.focused } }
        assertEquals(listOf(down[2], down[1], down[0], start), up, "Down went $start → ${down.joinToString(" → ")}")
    }

    /**
     * The catalogs load in any order: in a desktop window the third one's cards can come first, reaching below the
     * fold, and the keyboard's first focus comes to its first card, the page scrolling to it. Before the user moved,
     * focus follows to the first catalog's first card as its cards come, the page back at its top with the shortcuts,
     * as on a Board whose catalogs came in order.
     */
    @Test
    fun onDesktopTheFirstCatalogArrivingAfterALowerOneTakesTheFocusTheBoardPlacedAndTheTop() = runComposeUiTest {
        var state by mutableStateOf(thirdCatalogFirst)
        setContent { TortugaTheme { app(FormFactor.DESKTOP, 1000, 800, state) } }
        assertCardFocused("Featured · Movies 0")
        onNodeWithText("Watch together").assertDoesNotExist()

        state = state.withRows(catalogs)
        assertCardFocused("Popular · Movies 0")
        onNodeWithText("Watch together").assertIsDisplayed()
    }

    /**
     * Home opened with a click: a catalog's cards that come first reaching below the fold take no focus, which would
     * scroll the page to them, away from the shortcuts the pointer is about to click; the page stays at its top. Focus
     * comes to the first card once it is on screen whole, for a key pressed next.
     */
    @Test
    fun onDesktopAfterAClickACatalogArrivingFirstBelowTheFoldLeavesThePageAtItsTop() = runComposeUiTest {
        var state by mutableStateOf(thirdCatalogFirst.withRows(catalogs.map { it.loading() }))
        lateinit var input: InputModeManager
        setContent {
            input = LocalInputModeManager.current
            TortugaTheme { app(FormFactor.DESKTOP, 1000, 800, state) }
        }
        onNodeWithText(TopLevelDestination.BOARD.shortLabel).performMouseInput { click() }
        runOnIdle { assertEquals(InputMode.Touch, input.inputMode) }

        state = thirdCatalogFirst
        waitForIdle()
        onNodeWithText("Watch together").assertIsDisplayed()
        onNodeWithText("Popular · Movies").assertIsDisplayed()

        state = state.withRows(catalogs)
        assertCardFocused("Popular · Movies 0")
        onNodeWithText("Watch together").assertIsDisplayed()
    }

    /**
     * Scrolled with the wheel after its first focus came, Home stays where the user took it as the first catalog
     * arrives, focus on its card: not back at its top.
     */
    @Test
    fun onDesktopABoardScrolledWithTheWheelStaysThereAsTheFirstCatalogArrives() = runComposeUiTest {
        var state by mutableStateOf(thirdCatalogFirst)
        setContent { TortugaTheme { app(FormFactor.DESKTOP, 1000, 800, state) } }
        assertCardFocused("Featured · Movies 0")
        onNodeWithText("Featured · Movies").performMouseInput { moveTo(center); scroll(1f) }

        state = state.withRows(catalogs)
        waitForIdle()
        onNodeWithText("Watch together").assertDoesNotExist()
        assertCardFocused("Featured · Movies 0")
    }

    /** A tap on a shortcut opens what it names: its key goes to the store. */
    @Test
    fun aTapOnAShortcutOpensWhatItNames() = runComposeUiTest {
        val calls = mutableListOf<String>()
        board(FormFactor.HANDHELD, 411, 760, calls = calls) { loaded.copy(shortcuts = shortcuts) }
        onNodeWithText("Library").performClick()
        runOnIdle { assertEquals(listOf("shortcut:library"), calls) }
    }

    /**
     * On every layout (a phone or a tablet, TV, desktop) a shortcut leads with its icon, 18 dp, 10 dp into the chip,
     * centred on it and before the label, drawn in the label's colour: the light text at rest and the black of a focused
     * chip's label. One that opens one of the app's screens shows its feature's icon, a tracker's list (MyAnimeList's,
     * Trakt's) its service's mark; every chip keeps one height.
     */
    @Test
    fun aShortcutLeadsWithItsIconInItsLabelsColourOnEveryLayout() {
        for (formFactor in listOf(FormFactor.HANDHELD, FormFactor.TV, FormFactor.DESKTOP)) {
            runSkikoComposeUiTest(size = Size(2000f, 1200f), density = Density(2f)) {
                val placed = mutableMapOf<String, Rect>()
                val probe = LayoutProbe { name, bounds ->
                    placed.remove(name)
                    if (bounds != null) placed[name] = bounds
                }
                setContent {
                    CompositionLocalProvider(LocalLayoutProbe provides probe) {
                        TortugaTheme {
                            Box(Modifier.size(1000.dp, 600.dp).background(TortugaColors.Background)) {
                                AdaptiveLayout(formFactor) { boardScreen(loaded.copy(shortcuts = everyKind)) }
                            }
                        }
                    }
                }
                waitForIdle()

                fun label(shortcut: BoardShortcut): Rect = with(density) { onNodeWithText(shortcut.label, useUnmergedTree = true).getUnclippedBoundsInRoot().toRect() }

                fun icon(shortcut: BoardShortcut, colour: Color, screen: PixelMap) {
                    val chip = checkNotNull(placed["board.shortcut.${shortcut.key}"]) { "on $formFactor ${shortcut.key}: no chip" }
                    val icon = checkNotNull(placed["board.shortcut.${shortcut.key}.icon"]) { "on $formFactor ${shortcut.key}: no icon in its chip at $chip" }
                    val where = "on $formFactor ${shortcut.key}: its icon at $icon in its chip at $chip, its label at ${label(shortcut)}"
                    assertTrue(abs(icon.width - 36f) <= 1 && abs(icon.height - 36f) <= 1, "$where isn't 18 dp")
                    assertTrue(abs(icon.left - chip.left - 20f) <= 1 && abs(icon.center.y - chip.center.y) <= 1, "$where isn't 10 dp into the chip, centred on it")
                    assertTrue(icon.right <= label(shortcut).left, "$where isn't before its label")
                    assertTrue(screen.share(icon, colour) > 0.05f, "$where isn't drawn in the label's $colour (${screen.share(icon, colour)})")
                    assertTrue(screen.share(label(shortcut), colour) > 0.05f, "on $formFactor ${shortcut.key}: its label at ${label(shortcut)} isn't $colour")
                }

                val rest = onRoot().captureToImage().toPixelMap()
                for (shortcut in everyKind) icon(shortcut, TortugaColors.Text, rest)
                val heights = everyKind.map { checkNotNull(placed["board.shortcut.${it.key}"]).height }
                assertTrue(heights.all { it == heights.first() }, "on $formFactor the chips aren't one height: $heights")

                for (kind in listOf(BoardShortcutKind.DOWNLOADS, BoardShortcutKind.TRACKER_LIST)) {
                    val shortcut = everyKind.first { it.kind == kind }
                    onNode(hasText(shortcut.label) and hasClickAction()).requestFocus()
                    waitForIdle()
                    icon(shortcut, TortugaColors.OnPrimary, onRoot().captureToImage().toPixelMap())
                }
            }
        }
    }

    /**
     * On TV Down from a Continue Watching card lands on the first shortcut, not the one under the card; Select on a
     * shortcut opens what it names; Down from a shortcut goes to the next shelf's first card, not the one under it; Up
     * from that shelf comes back to the first shortcut.
     */
    @Test
    fun onTvTheDpadGoesFromContinueWatchingThroughTheShortcutsToTheNextShelf() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val cards = listOf("First", "Second", "Third").mapIndexed { i, title ->
            ContinueWatchingCard("tt$i", "movie", "tt$i", title, "1 min left", 0.5f, null, PosterShape.POSTER)
        }
        tvBoard(calls) { loaded.withHead().copy(continueWatching = cards, shortcuts = shortcuts) }
        assertCardFocused("First")
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertCardFocused("Third")

        press(Key.DirectionDown)
        onNode(isFocused()).assert(hasText(shortcuts.first().label))
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        onNode(isFocused()).assert(hasText("Library"))
        press(Key.DirectionCenter)
        runOnIdle { assertEquals(listOf("shortcut:library"), calls) }

        press(Key.DirectionDown)
        assertCardFocused("Watchlist 0")
        press(Key.DirectionUp)
        onNode(isFocused()).assert(hasText(shortcuts.first().label))
    }

    /**
     * On TV Up from the shelf under the shortcuts comes back to them past a Continue Watching of one card, a row much
     * narrower than theirs (the device found focus going on to that card).
     */
    @Test
    fun onTvUpFromTheNextShelfStopsAtTheShortcutsUnderOneContinueWatchingCard() = runComposeUiTest {
        tvBoard { loaded.withHead().copy(shortcuts = shortcuts) }
        assertCardFocused("The Shawshank Redemption")
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        onNode(isFocused()).assert(hasText("Downloads"))
        press(Key.DirectionDown)
        assertCardFocused("Watchlist 0")
        press(Key.DirectionUp)
        onNode(isFocused()).assert(hasText(shortcuts.first().label))
    }

    /**
     * A long press on a Continue Watching card opens its menu: the card's entries ([ContinueWatchingCard.menu]) in
     * their order and no other; the pick goes to the store with its action and closes the menu.
     */
    @Test
    fun aLongPressOnAContinueWatchingCardOpensItsMenuAndThePickGoesToTheStore() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val state = loaded.withHead()
        val card = state.continueWatching.single()
        board(FormFactor.HANDHELD, 411, 760, calls = calls) { state }
        onRoot().performTouchInput { longClick(artwork(card.title)) }
        assertMenu(card.menu.map { it.label })
        onNodeWithText(T.mediaMenu.markUnwatched).assertDoesNotExist()
        onNodeWithText(T.mediaMenu.removeFromContinueWatching).performClick()
        runOnIdle { assertEquals(listOf("menu:${card.id}:REMOVE_FROM_CONTINUE_WATCHING"), calls) }
        onNodeWithText(T.mediaMenu.details).assertDoesNotExist()
    }

    /** On desktop a right click on a catalog's poster opens its menu ([MetaCard.menu]); the pick goes to the store. */
    @Test
    fun onDesktopARightClickOnAPosterOpensItsMenu() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val card = catalogs.first().items.first()
        board(FormFactor.DESKTOP, 1280, 800, calls = calls) { loaded }
        onRoot().performMouseInput { rightClick(artwork(card.title)) }
        assertMenu(card.menu.map { it.label })
        onNodeWithText(T.mediaMenu.markWatched).performClick()
        runOnIdle { assertEquals(listOf("menu:${card.id}:MARK_WATCHED"), calls) }
    }

    /**
     * On TV a short press of Select opens the focused card; holding it for 500 ms opens the card's menu
     * instead, a bottom sheet titled with the card's name, focus on its first entry; the Menu key opens it at once and
     * the D-pad picks an entry. Remove from Continue Watching takes the card off the Board as the menu closes, and focus
     * goes to the Board's first card, not to the navigation. The picture is kept as
     * build/screenshots/board-tv-media-menu.png.
     */
    @Test
    fun onTvHoldingSelectOrTheMenuKeyOpensACardsMenu() = runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(2f)) {
        val calls = mutableStateListOf<String>()
        val state = loaded.withHead()
        val card = state.continueWatching.single()
        val labels = card.menu.map { it.label }
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp).background(TortugaColors.Background)) {
                    // The store takes the card out of Continue Watching as the pick reaches it.
                    val shown = if (calls.any { it.startsWith("menu:") }) loaded else state
                    AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { OverlayHostBox { boardScreen(shown, calls = calls) } }
                }
            }
        }
        assertCardFocused(card.title)
        hold(Key.DirectionCenter, 100)
        runOnIdle { assertEquals(listOf("open:${card.id}"), calls) }
        onNodeWithText(labels.first()).assertDoesNotExist()

        hold(Key.DirectionCenter, 600)
        onNode(isFocused()).assert(hasText(labels.first()))
        assertMenu(labels)
        // The sheet's title: the card under the sheet is hidden from screen readers while it shows.
        onAllNodesWithText(card.title).assertCountEquals(1)
        val last = onNodeWithText(labels.last()).getUnclippedBoundsInRoot()
        assertTrue(last.bottom > 540.dp - 64.dp, "the menu's last entry at $last isn't at the bottom of the screen")
        mainClock.advanceTimeBy(1_000)
        val shot = File("build/screenshots/board-tv-media-menu.png")
        shot.parentFile.mkdirs()
        assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shot), "no PNG writer")
        press(Key.Escape)
        onNodeWithText(labels.first()).assertDoesNotExist()
        assertCardFocused(card.title)

        press(Key(KeyEvent.VK_CONTEXT_MENU))
        onNode(isFocused()).assert(hasText(labels.first()))
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        runOnIdle { assertEquals(listOf("open:${card.id}", "menu:${card.id}:${card.menu[1].action}"), calls) }
        onNodeWithText(labels.first()).assertDoesNotExist()
        assertCardFocused("Popular · Movies 0")
    }

    /** On TV the remote's Play/Pause and Play keys resume a Continue Watching card, as Select does; on a poster, nothing. */
    @Test
    fun onTvPlayPauseAndPlayResumeAContinueWatchingCard() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val state = loaded.withHead()
        val card = state.continueWatching.single()
        tvBoard(calls) { state }
        assertCardFocused(card.title)
        press(Key.MediaPlayPause)
        press(Key.MediaPlay)
        runOnIdle { assertEquals(listOf("open:${card.id}", "open:${card.id}"), calls) }
        assertCardFocused(card.title)

        press(Key.DirectionDown)
        assertCardFocused("Watchlist 0")
        press(Key.MediaPlayPause)
        runOnIdle { assertEquals(listOf("open:${card.id}", "open:${card.id}"), calls, "Play/Pause on a poster opened it") }
    }

    private val loaded get() = BoardUiState(emptyList(), BoardBody.Rows(catalogs))

    /** Home with its shortcuts, the third catalog's cards come before the others'. */
    private val thirdCatalogFirst
        get() = BoardUiState(emptyList(), BoardBody.Rows(catalogs.mapIndexed { i, row -> if (i == 2) row else row.loading() }), shortcuts = shortcuts)

    private fun BoardRow.loading() = copy(status = RowStatus.Loading, items = emptyList())

    /** Home's shortcuts as the store words them. */
    private val shortcuts = listOf(
        BoardShortcut("watchTogether", BoardShortcutKind.WATCH_TOGETHER, "Watch together"),
        BoardShortcut("downloads", BoardShortcutKind.DOWNLOADS, "Downloads"),
        BoardShortcut("library", BoardShortcutKind.LIBRARY, "Library"),
    )

    /** A shortcut of each kind. */
    private val everyKind = shortcuts + listOf(
        BoardShortcut("guide", BoardShortcutKind.GUIDE, "TV Guide"),
        BoardShortcut("calendar", BoardShortcutKind.CALENDAR, "Calendar"),
        BoardShortcut("tracker.myanimelist.airing", BoardShortcutKind.TRACKER_LIST, "Airing now", tracker = TrackerService.MYANIMELIST),
        BoardShortcut("tracker.trakt.watchlist", BoardShortcutKind.TRACKER_LIST, "Watchlist", tracker = TrackerService.TRAKT),
    )

    /** The share of [area]'s pixels drawn in [colour]: each channel within 0.06 of it. */
    private fun PixelMap.share(area: Rect, colour: Color): Float {
        val xs = area.left.roundToInt() until area.right.roundToInt()
        val ys = area.top.roundToInt() until area.bottom.roundToInt()
        val drawn = ys.sumOf { y ->
            xs.count { x ->
                val pixel = this[x, y]
                abs(pixel.red - colour.red) <= 0.06f && abs(pixel.green - colour.green) <= 0.06f && abs(pixel.blue - colour.blue) <= 0.06f
            }
        }
        return drawn.toFloat() / (xs.count() * ys.count())
    }

    /** A phone, a tablet's rail, a 1080p TV, a narrow and a wide desktop window: each form factor at the widths it has. */
    private val windows = listOf(FormFactor.HANDHELD to 411, FormFactor.HANDHELD to 700, FormFactor.TV to 960, FormFactor.DESKTOP to 500, FormFactor.DESKTOP to 1000)

    private fun ComposeUiTest.press(key: Key, shift: Boolean = false) =
        onRoot().performKeyInput { if (shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key) }

    /** Holds [key] down for [millis] ms, then lets it go. */
    private fun ComposeUiTest.hold(key: Key, millis: Long) {
        onRoot().performKeyInput { keyDown(key) }
        mainClock.advanceTimeBy(millis)
        onRoot().performKeyInput { keyUp(key) }
    }

    /** A point on the artwork of the card captioned [title], right above its caption, in the root's pixels. */
    private fun ComposeUiTest.artwork(title: String): Offset {
        val caption = onNodeWithText(title).getUnclippedBoundsInRoot()
        return with(density) { Offset((caption.left + 16.dp).toPx(), (caption.top - 24.dp).toPx()) }
    }

    /** The open menu shows [labels], in their order from the top. */
    private fun ComposeUiTest.assertMenu(labels: List<String>) {
        val tops = labels.map { onNodeWithText(it).assertIsDisplayed().getUnclippedBoundsInRoot().top }
        assertEquals(tops.sortedBy { it.value }, tops, "the menu's entries $labels aren't in the store's order")
    }

    /** The focused element is the artwork of the card captioned [title]: right above its caption, at its left. */
    private fun ComposeUiTest.assertCardFocused(title: String) {
        val focused = onNode(isFocused()).getUnclippedBoundsInRoot()
        val caption = onNodeWithText(title).getUnclippedBoundsInRoot()
        assertTrue(
            focused.left == caption.left && focused.bottom <= caption.top && caption.top - focused.bottom < 16.dp,
            "focus is on the element at $focused, not the artwork above '$title' at $caption",
        )
    }

    /**
     * On a phone (412 dp: cards 104 dp wide) at the default font scale Continue Watching's captions show whole in every
     * language, on more lines where they need them: a series' "S1E4 · 1 h 37 min left" and a Kitsu entry's "Episode
     * 12 · 1 h 37 min left", as the store words them.
     */
    @Test
    fun onAPhoneContinueWatchingCaptionsShowWholeInEveryLanguage() {
        val wrong = mutableListOf<String>()
        try {
            for (language in languages) {
                T.locale = language
                val left = T.browse.continueWatching.hoursMinutesLeft("1", "37")
                val captions = listOf(T.session.episode("1", "4"), T.browse.continueWatching.episode("12")).map { T.browse.continueWatching.detail(it, left) }
                val cards = captions.mapIndexed { index, caption ->
                    ContinueWatchingCard("tt$index", "series", "tt$index:1:4", "Breaking Bad", caption, 0.5f, null, PosterShape.POSTER)
                }
                runComposeUiTest {
                    board(FormFactor.HANDHELD, 412, 760) { BoardUiState(cards, BoardBody.Rows(catalogs)) }
                    for (caption in captions) {
                        val layouts = mutableListOf<TextLayoutResult>()
                        onNode(hasText(caption), useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        if (layouts.single().didOverflowHeight) wrong += "\"$caption\" is cut in $language"
                    }
                }
            }
        } finally {
            T.locale = "en"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** The interface's languages (shared/i18n/). */
    private val languages = listOf(
        "ar", "cs", "de", "en", "es", "fr", "hi", "id", "it", "ja", "ko", "nl", "pl", "pt-BR", "ro", "ru", "sv", "th", "tr", "uk", "vi",
    )

    private fun ComposeUiTest.board(state: () -> BoardUiState) = setContent {
        TortugaTheme { appScreen { boardScreen(state()) } }
    }

    /** The Board in a [formFactor] window of [width] × [height] dp. */
    private fun ComposeUiTest.board(
        formFactor: FormFactor,
        width: Int,
        height: Int,
        calls: MutableList<String> = mutableListOf(),
        state: () -> BoardUiState,
    ) = setContent {
        TortugaTheme {
            Box(Modifier.size(width.dp, height.dp)) {
                AdaptiveLayout(formFactor) { boardScreen(state(), calls = calls) }
            }
        }
    }

    /** The Board in a 1080p TV's window (960 × 540 dp), with the arrow keys moving focus as the remote's D-pad. */
    private fun ComposeUiTest.tvBoard(calls: MutableList<String> = mutableListOf(), state: () -> BoardUiState) = setContent {
        TortugaTheme {
            Box(Modifier.size(960.dp, 540.dp)) {
                AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { boardScreen(state(), calls = calls) }
            }
        }
    }

    /**
     * The Board as the app draws it in a [formFactor] window: in the navigation frame, over the app's background, its
     * focus hidden until a key is pressed but on TV (TortugaFrame's).
     */
    @Composable
    private fun app(formFactor: FormFactor, width: Int, height: Int, state: BoardUiState = loaded, calls: MutableList<String> = mutableListOf()) {
        val focusVisibility = remember { FocusVisibility(initial = formFactor == FormFactor.TV) }
        CompositionLocalProvider(LocalFocusVisibility provides focusVisibility) {
            Box(Modifier.size(width.dp, height.dp).background(TortugaColors.Background)) {
                AdaptiveLayout(formFactor, Modifier.trackFocusVisibility(focusVisibility).arrowKeyFocus()) {
                    OverlayHostBox {
                        NavigationScaffold(
                            destinations = TopLevelDestination.entries,
                            selected = TopLevelDestination.BOARD,
                            onSelect = {},
                            showNavigation = true,
                        ) {
                            appScreen { boardScreen(state, calls = calls) }
                        }
                    }
                }
            }
        }
    }

    /** [content] as a screen of the app's back stack ([ScreenFocus]): a press or the wheel on it ends the focus it placed. */
    @Composable
    private fun appScreen(content: @Composable () -> Unit) {
        val screen = remember { ScreenFocus { true } }
        Box(Modifier.fillMaxSize().then(screen.modifier)) {
            CompositionLocalProvider(LocalScreenFocus provides screen, content = content)
        }
    }

    /**
     * The Board, what it asks of its store going to [calls]: `open:<id>`, `menu:<id>:<action>` (a card's menu),
     * `seeAll:<row>`, `shortcut:<key>`, `folder:<row>:<id>`.
     */
    @Composable
    private fun boardScreen(
        state: BoardUiState,
        onRowVisible: (Int) -> Unit = {},
        calls: MutableList<String> = mutableListOf(),
    ) = BoardScreen(
        state,
        onRowVisible = onRowVisible,
        onRetryRow = {},
        onLoadMore = {},
        onSeeAll = { calls += "seeAll:$it" },
        onOpenItem = { calls += "open:${it.id}" },
        onOpenFolder = { row, id -> calls += "folder:$row:$id" },
        onOpenContinueWatching = { calls += "open:${it.id}" },
        onItemMenu = { card, action -> calls += "menu:${card.id}:$action" },
        onContinueWatchingMenu = { card, action -> calls += "menu:${card.id}:$action" },
        onOpenDownloads = {},
        onOpenShortcut = { calls += "shortcut:$it" },
    )

    private fun row(title: String, fromTracker: Boolean) = BoardRow(
        key = title,
        title = title,
        sourceName = if (fromTracker) "Trakt" else "Cinemeta",
        fromTracker = fromTracker,
        status = RowStatus.Ready(loadingMore = false),
        items = List(6) { MetaCard("$title $it", "movie", "$title $it", "2026", null, null, PosterShape.POSTER, null) },
        seeAll = SeeAll.HEADING,
    )
}
