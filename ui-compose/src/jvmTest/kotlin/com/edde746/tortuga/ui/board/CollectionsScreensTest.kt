package com.edde746.tortuga.ui.board

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.BoardBody
import com.edde746.tortuga.presentation.board.BoardRow
import com.edde746.tortuga.presentation.board.BoardUiState
import com.edde746.tortuga.presentation.board.FolderTile
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.board.RowStatus
import com.edde746.tortuga.presentation.board.SeeAll
import com.edde746.tortuga.presentation.collections.FolderGrid
import com.edde746.tortuga.presentation.collections.FolderLayout
import com.edde746.tortuga.presentation.collections.FolderNote
import com.edde746.tortuga.presentation.collections.FolderNoteAction
import com.edde746.tortuga.presentation.collections.FolderRow
import com.edde746.tortuga.presentation.collections.FolderTab
import com.edde746.tortuga.presentation.collections.FolderUiState
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.aspectRatio
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaDimens
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Collections in Compose: a collection's row on Home shows its folders as tiles of the collection's shape, and a tile
 * opens its folder; a folder shows its sources as tabs over a grid, or as rows, with a source's note in place of its
 * titles.
 */
@OptIn(ExperimentalTestApi::class)
class CollectionsScreensTest {
    /** Where each harness name was placed, in px. */
    private val placed = mutableMapOf<String, Rect>()

    /**
     * Every tile of a row has the collection's shape at Home's card width for it, so the row is one height; the caption
     * lines line up, a folder that hides its title (and one without a cover, its title on the tile) keeping the
     * caption's room empty; a press opens the folder of that row.
     */
    @Test
    fun aCollectionRowsTilesAreOneSizeWithTheirCaptionsLevel() = runComposeUiTest {
        val calls = mutableListOf<String>()
        board(FormFactor.DESKTOP, calls) { BoardUiState(emptyList(), BoardBody.Rows(listOf(collection, catalog))) }
        for (tile in collection.folders) {
            val bounds = onNode(hasContentDescription(tile.title) and hasClickAction()).getUnclippedBoundsInRoot()
            val ratio = bounds.width / bounds.height
            assertTrue(abs(ratio - PosterShape.SQUARE.aspectRatio) < 0.02f, "${tile.title}: ${bounds.width} × ${bounds.height}, not square")
            assertEquals(dimens.cardWidth(PosterShape.SQUARE).value, bounds.width.value, 0.5f, "${tile.title} isn't as wide as Home's square cards")
        }
        val captions = collection.folders.indices.map { placed.getValue("board.row.0.folder.$it.caption") }
        assertTrue(captions.first().height > 0f)
        for ((i, caption) in captions.withIndex()) {
            assertEquals(captions.first().top, caption.top, 0.5f, "${collection.folders[i].title}'s caption isn't level")
            assertEquals(captions.first().height, caption.height, 0.5f, "${collection.folders[i].title}'s caption line isn't one line")
        }
        // No cover: the title on the tile. A cover with the title hidden: an empty caption.
        onNodeWithText("Tide Studio", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Moss Works", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Needs the addon Missing Addon", useUnmergedTree = true).assertIsDisplayed()

        onNode(hasContentDescription("Dusk House") and hasClickAction()).performClick()
        assertEquals(listOf("folder:0:dusk"), calls)
    }

    /** On TV a Home whose first row is a collection opens with focus on its first tile, as on the first card otherwise. */
    @Test
    fun onTvHomeOpensOnTheFirstTileOfACollectionAtItsTop() = runComposeUiTest {
        board(FormFactor.TV) { BoardUiState(emptyList(), BoardBody.Rows(listOf(collection, catalog))) }
        waitForIdle()
        onNode(isFocused()).assert(hasContentDescription("Ember Pictures"))
    }

    /**
     * A folder shown as tabs: All first, then its sources; a press on a tab asks for it, and the grid shows the
     * selected tab's titles alone. On TV Up from the grid's first card reaches the tabs.
     */
    @Test
    fun aFolderSwitchesTabsAndTheGridFollows() = runComposeUiTest {
        val selected = mutableListOf<Int>()
        var state by mutableStateOf(tabs(0, listOf(card("Ember Film"), card("Ember Show"))))
        folder(FormFactor.TV, { state }, selected)
        waitForIdle()
        onNode(isFocused()).assert(hasContentDescription("Ember Film"))
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        onNode(isFocused()).assert(hasText("All"))

        onNodeWithText("Ember Pictures Series").performClick()
        assertEquals(listOf(2), selected)
        state = tabs(2, listOf(card("Ember Show")))
        onNode(hasContentDescription("Ember Show") and hasClickAction()).assertIsDisplayed()
        onNode(hasContentDescription("Ember Film") and hasClickAction()).assertDoesNotExist()
    }

    /**
     * A folder's grid asks for its next page as its end comes into view, and after a page that failed (no titles came)
     * again once its end leaves the view and comes back.
     */
    @Test
    fun aFolderGridAsksAgainForAPageThatFailedAsItsEndComesBack() = runComposeUiTest {
        var asked = 0
        val items = List(60) { card("Film $it") }
        folder(FormFactor.DESKTOP, { tabs(0, items) }, nearEnd = { asked++ })
        val grid = onNode(hasScrollToIndexAction() and !hasAnyDescendant(hasText("All")))
        grid.performScrollToIndex(items.lastIndex)
        runOnIdle { assertEquals(1, asked) }

        grid.performScrollToIndex(0)
        waitForIdle()
        grid.performScrollToIndex(items.lastIndex)
        runOnIdle { assertEquals(2, asked) }
    }

    /** A folder shown as rows: a source that can't show has its note and action in place of its titles. */
    @Test
    fun aFolderRowThatCantShowHasItsNoteAndAction() = runComposeUiTest {
        val notes = mutableListOf<Int>()
        val rows = listOf(
            FolderRow("moss", "Moss Works Movies", "Test", RowStatus.Ready(loadingMore = false), listOf(card("Moss Film")), null),
            FolderRow("missing", "Missing", "Missing Addon", RowStatus.Ready(loadingMore = false), emptyList(), FolderNote("Needs the addon Missing Addon", FolderNoteAction.OPEN_ADDONS, "Open addons")),
        )
        folder(FormFactor.DESKTOP, { FolderUiState("Mixed Sources", null, FolderLayout.ROWS, emptyList(), 0, null, rows, gone = false) }, notes = notes)
        onNodeWithText("Needs the addon Missing Addon").assertIsDisplayed()
        onNodeWithText("Open addons").performClick()
        assertEquals(listOf(1), notes)
    }

    /** The dimensions Home was drawn with. */
    private lateinit var dimens: TortugaDimens

    private val collection = BoardRow(
        key = "collection/studios",
        title = "Studios",
        sourceName = "Collection",
        fromTracker = false,
        status = RowStatus.Ready(loadingMore = false),
        items = emptyList(),
        seeAll = SeeAll.NONE,
        folders = listOf(
            FolderTile("ember", "Ember Pictures", "http://127.0.0.1:9/ember.png", showTitle = true, note = null),
            FolderTile("tide", "Tide Studio", null, showTitle = true, note = null),
            FolderTile("moss", "Moss Works", "http://127.0.0.1:9/moss.gif", showTitle = false, note = null),
            FolderTile("dusk", "Dusk House", "http://127.0.0.1:9/dusk.png", showTitle = true, note = "Needs the addon Missing Addon"),
        ),
        folderShape = PosterShape.SQUARE,
    )

    private val catalog = BoardRow(
        key = "popular",
        title = "Popular · Movies",
        sourceName = "Cinemeta",
        fromTracker = false,
        status = RowStatus.Ready(loadingMore = false),
        items = List(6) { card("Popular $it") },
        seeAll = SeeAll.NONE,
    )

    private fun card(title: String) = MetaCard(title, "movie", title, "2026", null, null, PosterShape.POSTER, null)

    private fun tabs(selected: Int, items: List<MetaCard>) = FolderUiState(
        title = "Ember Pictures",
        backdropUrl = null,
        layout = FolderLayout.TABS,
        tabs = listOf(FolderTab("all", "All"), FolderTab("movies", "Ember Pictures Movies"), FolderTab("series", "Ember Pictures Series")),
        selectedTab = selected,
        grid = FolderGrid(RowStatus.Ready(loadingMore = false), items, null),
        rows = emptyList(),
        gone = false,
    )

    /** Home in a [formFactor] window (1000 × 700 dp; TV 960 × 540, the arrow keys moving focus as the D-pad). */
    private fun ComposeUiTest.board(formFactor: FormFactor, calls: MutableList<String> = mutableListOf(), state: () -> BoardUiState) = setContent {
        val probe = LayoutProbe { name, bounds -> if (bounds != null) placed[name] = bounds else placed -= name }
        CompositionLocalProvider(LocalLayoutProbe provides probe) {
            TortugaTheme {
                val tv = formFactor == FormFactor.TV
                Box(Modifier.size(if (tv) 960.dp else 1000.dp, if (tv) 540.dp else 700.dp)) {
                    AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                        dimens = LocalTortugaDimens.current
                        BoardScreen(
                            state(),
                            onRowVisible = {},
                            onRetryRow = {},
                            onLoadMore = {},
                            onSeeAll = {},
                            onOpenItem = {},
                            onOpenFolder = { row, id -> calls += "folder:$row:$id" },
                            onOpenContinueWatching = {},
                            onItemMenu = { _, _ -> },
                            onContinueWatchingMenu = { _, _ -> },
                            onOpenDownloads = {},
                            onOpenShortcut = {},
                        )
                    }
                }
            }
        }
    }

    /** A folder's screen in a [formFactor] window, its tab, note and grid's end intents going to [selected], [notes], [nearEnd]. */
    private fun ComposeUiTest.folder(
        formFactor: FormFactor,
        state: () -> FolderUiState,
        selected: MutableList<Int> = mutableListOf(),
        notes: MutableList<Int> = mutableListOf(),
        nearEnd: () -> Unit = {},
    ) = setContent {
        TortugaTheme {
            val tv = formFactor == FormFactor.TV
            Box(Modifier.size(if (tv) 960.dp else 1000.dp, if (tv) 540.dp else 700.dp)) {
                AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                    FolderScreen(
                        state(),
                        FolderIntents(
                            selectTab = { selected += it },
                            rowVisible = {},
                            loadNextPage = {},
                            gridNearEnd = nearEnd,
                            retry = {},
                            openItem = {},
                            menuAction = { _, _ -> },
                            noteAction = { notes += it },
                        ),
                        onBack = null,
                    )
                }
            }
        }
    }
}
