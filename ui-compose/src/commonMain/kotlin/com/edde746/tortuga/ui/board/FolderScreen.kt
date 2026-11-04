package com.edde746.tortuga.ui.board

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.board.RowStatus
import com.edde746.tortuga.presentation.board.SHELF_LOAD_AHEAD
import com.edde746.tortuga.presentation.collections.FolderGrid
import com.edde746.tortuga.presentation.collections.FolderLayout
import com.edde746.tortuga.presentation.collections.FolderNote
import com.edde746.tortuga.presentation.collections.FolderNoteAction
import com.edde746.tortuga.presentation.collections.FolderRow
import com.edde746.tortuga.presentation.collections.FolderStore
import com.edde746.tortuga.presentation.collections.FolderUiState
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.EmptyState
import com.edde746.tortuga.ui.components.ErrorState
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.ScreenHeader
import com.edde746.tortuga.ui.components.ShelfRow
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaChip
import com.edde746.tortuga.ui.components.gridTop
import com.edde746.tortuga.ui.components.shelfSpacing
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.navigation.clipStart
import com.edde746.tortuga.ui.navigation.navigationOverlap
import com.edde746.tortuga.ui.navigation.statusBarOverlap
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A collection's folder ([Route.CollectionFolder]), opened from its tile on Home: its [FolderStore] lives while the
 * route is on the back stack. Once the folder is deleted (elsewhere: another device, the editor) the screen goes back.
 */
@Composable
fun CollectionFolderRoute(app: AppSession, route: Route.CollectionFolder) {
    val navigator = app.navigator
    // Once per appearance: the entry still draws while it animates out after its route left the stack (its store
    // released), and an entry first composed after that gets no store and draws nothing.
    val store = remember(route) {
        navigator.retain(route, FolderStore.KEY, create = { app.folderStore(route) }, release = FolderStore::release)
    } ?: return
    val state by store.state.collectAsState()
    val intents = remember(store) {
        FolderIntents(
            selectTab = store::selectTab,
            rowVisible = store::rowVisible,
            loadNextPage = store::loadNextPage,
            gridNearEnd = store::gridNearEnd,
            retry = store::retry,
            openItem = store::openItem,
            menuAction = store::menuAction,
            noteAction = store::noteAction,
        )
    }
    LaunchedEffect(state.gone) { if (state.gone) navigator.back() }
    FolderScreen(state, intents, onBack = if (navigator.canGoBack) ({ navigator.back() }) else null)
}

/** What the folder screen asks of [FolderStore]; row and tab intents take the row's or the tab's index. */
class FolderIntents(
    val selectTab: (Int) -> Unit,
    val rowVisible: (Int) -> Unit,
    val loadNextPage: (Int) -> Unit,
    val gridNearEnd: () -> Unit,
    val retry: (Int) -> Unit,
    val openItem: (MetaCard) -> Unit,
    val menuAction: (MetaCard, MediaAction) -> Unit,
    val noteAction: (Int) -> Unit,
)

/**
 * A folder of a collection: the collection's backdrop behind the top when it has one (as Home's, faded into the page),
 * the folder's title with Back, then its sources as the collection shows them ([FolderUiState.layout]):
 * - Tabs: a row of chips, one per tab (All first, where the folder has it; [FolderUiState.tabs]), over the selected
 *   tab's poster grid, which asks for more as its end comes into view, as Discover's does. A press on a chip selects it
 *   ([FolderIntents.selectTab]) and the grid starts again from its top.
 * - Rows: a shelf per source, as Home's: loading, failed (Retry), or its cards, paging as their end comes into view.
 *
 * A source that can't show (an addon not installed, a tracker not connected) shows its note ([FolderNote]) in place of
 * its cards, with the note's action where it has one (Connect, Open addons). A card's menu is the media menu, as on
 * Home. Focus: the first card, else the selected tab; Up from the grid reaches the tabs, and Back leaves the screen,
 * focus going back to the tile that opened it.
 *
 * Harness names: `folder.header`, `folder.tab.<i>`, `folder.grid.item.<i>`, `folder.grid.note` (with `.action`),
 * `folder.grid.failed`, `folder.grid.retry` (a later page's), `folder.row.<r>.item.<i>`, `folder.row.<r>.source`,
 * `folder.row.<r>.note` (with `.action`), `folder.row.<r>.retry`.
 */
@Composable
fun FolderScreen(state: FolderUiState, intents: FolderIntents, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val focus = rememberFocusMemory()
    Box(modifier.fillMaxSize()) {
        state.backdropUrl?.let { url -> Backdrop { url } }
        Column(Modifier.fillMaxSize().padding(top = statusBarOverlap)) {
            ScreenHeader(state.title, Modifier.layoutProbe("folder.header", reportsFocus = false), onBack = onBack, contentTitle = true)
            when (state.layout) {
                FolderLayout.TABS -> {
                    if (state.tabs.size > 1) Tabs(state, focus, intents.selectTab)
                    // A tab's grid of its own: another tab's starts at its top.
                    key(state.selectedTab) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { state.grid?.let { TabGrid(it, state.selectedTab, focus, intents) } }
                    }
                }
                FolderLayout.ROWS -> SourceRows(state.rows, focus, intents, Modifier.weight(1f))
            }
        }
    }
    val fallback = when (state.layout) {
        FolderLayout.TABS -> state.grid?.items?.firstOrNull()?.let(::gridKey) ?: tabKey(state.selectedTab).takeIf { state.tabs.size > 1 }
        FolderLayout.ROWS -> state.rows.firstNotNullOfOrNull { row -> row.items.firstOrNull()?.let { rowItemKey(row, it) } }
    }
    RestoreFocus(focus, ready = fallback != null, fallback = fallback)
}

/** The tabs as chips in one row that scrolls sideways when they don't fit; the selected one in the primary fill. */
@Composable
private fun Tabs(state: FolderUiState, focus: FocusMemory, onSelect: (Int) -> Unit) {
    val dimens = LocalTortugaDimens.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = dimens.screenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Scrolled-away chips end at the TV column's edge.
        modifier = Modifier.clipStart(navigationOverlap).focusRestorer().focusGroup(),
    ) {
        itemsIndexed(state.tabs, key = { _, tab -> tab.key }) { index, tab ->
            val name = "folder.tab.$index"
            TortugaChip(
                tab.title,
                selected = index == state.selectedTab,
                onClick = { onSelect(index) },
                modifier = Modifier.rememberFocus(focus, tabKey(index)).layoutProbe(name),
            )
        }
    }
}

/** The selected tab's titles as a poster grid, or its note, its loading or its failure. */
@Composable
private fun TabGrid(grid: FolderGrid, tab: Int, focus: FocusMemory, intents: FolderIntents) {
    val note = grid.note
    val status = grid.status
    when {
        note != null -> EmptyState(note.text, Modifier.layoutProbe("folder.grid.note", reportsFocus = false), icon = note.icon) {
            NoteButton(note, { intents.noteAction(tab) }, Modifier.rememberFocus(focus, "note").layoutProbe("folder.grid.note.action"))
        }
        grid.items.isNotEmpty() -> ItemGrid(grid, focus, intents) { intents.retry(tab) }
        status is RowStatus.Failed -> ErrorState(status.text, Modifier.layoutProbe("folder.grid.failed", reportsFocus = false), onRetry = { intents.retry(tab) })
        // A source with no titles comes with its note.
        status is RowStatus.Ready -> {}
        else -> LoadingState()
    }
}

@Composable
private fun ItemGrid(grid: FolderGrid, focus: FocusMemory, intents: FolderIntents, onRetry: () -> Unit) {
    val dimens = LocalTortugaDimens.current
    val gridState = rememberLazyGridState()
    val shape = grid.items.firstOrNull()?.posterShape ?: PosterShape.POSTER
    val status = grid.status
    LazyVerticalGrid(
        columns = GridCells.Adaptive(dimens.cardWidth(shape)),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        // The screen's content edge at the sides; room for a focused poster's growth (TV) at the top.
        contentPadding = PaddingValues(start = dimens.screenPadding, end = dimens.screenPadding, top = dimens.gridTop, bottom = dimens.sectionSpacing),
        horizontalArrangement = Arrangement.spacedBy(dimens.gutter),
        verticalArrangement = Arrangement.spacedBy(dimens.gutter),
    ) {
        itemsIndexed(grid.items, key = { _, card -> card.id }) { index, card ->
            MetaPoster(
                card = card,
                onClick = { intents.openItem(card) },
                onMenu = { action -> intents.menuAction(card, action) },
                probe = "folder.grid.item.$index",
                modifier = Modifier.fillMaxWidth().rememberFocus(focus, gridKey(card)),
            )
        }
        val busy = status is RowStatus.Ready && status.loadingMore || status == RowStatus.Loading
        if (busy || status is RowStatus.Failed) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }) { MoreFooter((status as? RowStatus.Failed)?.text, onRetry) }
        }
    }
    // The next page, once the end of the grid comes into view, again as the items change while it stays in view, and
    // after a page that failed (no titles added), again once the end leaves the view and comes back.
    val nearEnd by rememberUpdatedState(intents.gridNearEnd)
    LaunchedEffect(gridState, grid.items.size) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - SHELF_LOAD_AHEAD
        }.distinctUntilChanged().collect { if (it) nearEnd() }
    }
}

/** Under the grid: the loading indicator while the next page comes, or why it failed with Retry ([onRetry]). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MoreFooter(error: String?, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (error == null) {
            LoadingIndicator(Modifier.size(40.dp))
        } else {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
            TortugaButton(T.browse.retry, onRetry, Modifier.layoutProbe("folder.grid.retry"), style = ButtonStyle.PRIMARY, icon = TortugaIcons.Refresh)
        }
    }
}

/** The folder's sources as Home's shelves, Up and Down moving between them as on Home ([rememberShelfNavigation]). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SourceRows(rows: List<FolderRow>, focus: FocusMemory, intents: FolderIntents, modifier: Modifier) {
    val dimens = LocalTortugaDimens.current
    val listState = rememberLazyListState()
    val navigation = rememberShelfNavigation(listState)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().then(navigation.modifier),
        contentPadding = PaddingValues(top = 8.dp, bottom = dimens.sectionSpacing),
        verticalArrangement = Arrangement.spacedBy(dimens.shelfSpacing),
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            val currentIndex by rememberUpdatedState(index)
            // Composed means on screen or about to be: the store loads it (as Home's rows).
            LaunchedEffect(row.key, row.status == RowStatus.Idle) { intents.rowVisible(currentIndex) }
            val placed = navigation.item(index)
            val note = row.note
            val status = row.status
            when {
                note != null -> MessageShelf(row.title, note.text, note.icon, placed.layoutProbe("folder.row.$index.note", reportsFocus = false)) {
                    NoteButton(note, { intents.noteAction(currentIndex) }, Modifier.rememberFocus(focus, "note:${row.key}").layoutProbe("folder.row.$index.note.action"))
                }
                row.items.isNotEmpty() -> ShelfRow(
                    title = row.title,
                    items = row.items,
                    key = { it.id },
                    modifier = placed,
                    action = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (status == RowStatus.Loading || status is RowStatus.Ready && status.loadingMore) LoadingIndicator(Modifier.size(24.dp))
                            AddonLabel(row.sourceName, modifier = Modifier.layoutProbe("folder.row.$index.source"))
                        }
                    },
                    onNearEnd = { intents.loadNextPage(currentIndex) },
                ) { position, card ->
                    MetaPoster(
                        card = card,
                        onClick = { intents.openItem(card) },
                        onMenu = { action -> intents.menuAction(card, action) },
                        probe = "folder.row.$index.item.$position",
                        modifier = Modifier.rememberFocus(focus, rowItemKey(row, card)),
                    )
                }
                status is RowStatus.Failed -> FailedShelf(
                    title = row.title,
                    message = status.text,
                    onRetry = { intents.retry(currentIndex) },
                    modifier = placed,
                    retryModifier = Modifier.rememberFocus(focus, "retry:${row.key}").layoutProbe("folder.row.$index.retry"),
                )
                // A source with no titles comes with its note.
                status is RowStatus.Ready -> {}
                else -> LoadingShelf(row.title, placed, trailing = { AddonLabel(row.sourceName) })
            }
        }
    }
}

/** A note's action ([FolderNote.action]): Connect opens Settings → Trackers, Open addons Settings → Addons. */
@Composable
private fun NoteButton(note: FolderNote, onClick: () -> Unit, modifier: Modifier) {
    val label = note.actionLabel ?: return
    TortugaButton(label, onClick, modifier, style = ButtonStyle.PRIMARY)
}

/** A note's symbol: a tracker to connect, else an addon (one missing, or without the catalog). */
private val FolderNote.icon: ImageVector
    get() = when (action) {
        FolderNoteAction.CONNECT_TRACKER -> TortugaIcons.Sync
        FolderNoteAction.OPEN_ADDONS, null -> TortugaIcons.Extension
    }

private fun tabKey(index: Int) = "tab:$index"

private fun gridKey(card: MetaCard) = "item:${card.id}"

private fun rowItemKey(row: FolderRow, card: MetaCard) = "${row.key}|${card.id}"
