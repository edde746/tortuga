package com.edde746.tortuga.ui.discover

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.discover.DiscoverBody
import com.edde746.tortuga.presentation.discover.DiscoverMore
import com.edde746.tortuga.presentation.discover.DiscoverPicker
import com.edde746.tortuga.presentation.discover.DiscoverStore
import com.edde746.tortuga.presentation.discover.DiscoverUiState
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.board.MetaPoster
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.EmptyState
import com.edde746.tortuga.ui.components.ErrorState
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.MenuChip
import com.edde746.tortuga.ui.components.MenuChoice
import com.edde746.tortuga.ui.components.OfflineMessage
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaChip
import com.edde746.tortuga.ui.components.gridTop
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.navigation.clipStart
import com.edde746.tortuga.ui.navigation.navigationOverlap
import com.edde746.tortuga.ui.navigation.statusBarOverlap
import com.edde746.tortuga.ui.probe.DISCOVER_CONTENT_MARK
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.probe.perfMark
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import kotlinx.coroutines.flow.first

/**
 * Discover for [app]'s profile, over a store the navigator keeps for the session (Discover is a tab: shown again, it
 * is on the catalog, filters and pages it was left on, scrolled where it was), opened on the catalog
 * [TortugaNavigator.openDiscover][com.edde746.tortuga.presentation.navigation.TortugaNavigator.openDiscover] asked for
 * (a Board row's "See all", a genre link), else on the first catalog. A link followed later moves it to the linked
 * catalog.
 */
@Composable
fun DiscoverRoute(app: AppSession) {
    val navigator = app.navigator
    // Once per appearance: the entry still draws while it animates out after its route left the stack, and an entry
    // first composed after that gets no store and draws nothing.
    val store = remember {
        navigator.retain(
            Route.Discover,
            key = DiscoverStore.KEY,
            create = app::discoverStore,
            release = DiscoverStore::release,
        )
    } ?: return
    val link by navigator.discoverLink.collectAsState()
    LaunchedEffect(store, link) {
        if (link != null) navigator.takeDiscoverLink()?.let(store::open)
    }
    val state by store.state.collectAsState()
    DiscoverScreen(
        state = state,
        onSelectType = store::selectType,
        onSelectCatalog = store::selectCatalog,
        onSelectFilter = store::selectFilter,
        onLoadMore = store::loadNextPage,
        onRetry = store::retry,
        onOpenItem = store::openItem,
        onItemMenu = store::menuAction,
        onOpenDownloads = store::openDownloads,
    )
}

/**
 * One catalog as a poster grid, picked by type, catalog and the catalog's filters, all in one row
 * of solid chips above the grid: a few types as chips of their own, the catalog and each filter (the genres) as a chip
 * that opens its options (a menu; on TV a side panel a remote moves through). The grid asks for the next page
 * ([onLoadMore]) as its end comes into view; a failed first or later page offers [onRetry]. Offline
 * ([DiscoverBody.Offline]) the offline state ([OfflineMessage]: Go to Downloads, [onOpenDownloads]) takes the grid's
 * place. A poster's menu ([MetaCard.menu]) gives the picked entry's action to [onItemMenu].
 */
@Composable
fun DiscoverScreen(
    state: DiscoverUiState,
    onSelectType: (Int) -> Unit,
    onSelectCatalog: (Int) -> Unit,
    onSelectFilter: (filter: Int, option: Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenItem: (MetaCard) -> Unit,
    onItemMenu: (MetaCard, MediaAction) -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = rememberFocusMemory()
    val body = state.body
    val items = (body as? DiscoverBody.Grid)?.items.orEmpty()
    // The perf runs' mark: the grid on screen with its items.
    val perfDetail = "type=${items.firstOrNull()?.type} items=${items.size}"
    Column(modifier.fillMaxSize().padding(top = statusBarOverlap).perfMark(DISCOVER_CONTENT_MARK, perfDetail, ready = items.isNotEmpty())) {
        FilterRow(state, focus, onSelectType, onSelectCatalog, onSelectFilter)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (body) {
                is DiscoverBody.Offline -> OfflineMessage(body.offline, onOpenDownloads, actionModifier = Modifier.rememberFocus(focus, FOCUS_OFFLINE))
                DiscoverBody.Loading -> LoadingState()
                is DiscoverBody.Failed -> ErrorState(body.text, onRetry = onRetry)
                is DiscoverBody.Empty -> EmptyState(body.text)
                is DiscoverBody.Grid -> ItemGrid(body, focus, onLoadMore, onRetry, onOpenItem, onItemMenu)
            }
        }
    }
    val fallback = when (body) {
        is DiscoverBody.Offline -> FOCUS_OFFLINE.takeIf { body.offline.actionText != null }
        else -> items.firstOrNull()?.let(::itemKey)
    }
    RestoreFocus(focus, ready = fallback != null, fallback = fallback)
}

/** The offline state's Go to Downloads in the screen's focus memory. */
private const val FOCUS_OFFLINE = "offline"

/**
 * The one filter row: the types as chips when there are a few (else a picker like the others), the catalog, then each
 * filter; it scrolls sideways when it doesn't fit.
 */
@Composable
private fun FilterRow(
    state: DiscoverUiState,
    focus: FocusMemory,
    onSelectType: (Int) -> Unit,
    onSelectCatalog: (Int) -> Unit,
    onSelectFilter: (Int, Int) -> Unit,
) {
    val dimens = LocalTortugaDimens.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = dimens.screenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        // Scrolled-away chips end at the TV column's edge.
        modifier = Modifier.clipStart(navigationOverlap).focusRestorer().focusGroup(),
    ) {
        state.types?.let { types ->
            if (types.options.size <= TYPE_CHIPS) {
                itemsIndexed(types.options, key = { index, _ -> "type:$index" }) { index, option ->
                    val name = "discover.type.$index"
                    TortugaChip(
                        option.label,
                        selected = option.selected,
                        onClick = { onSelectType(index) },
                        modifier = Modifier.rememberFocus(focus, name).layoutProbe(name),
                    )
                }
            } else {
                item(key = "type") { PickerChip(types, "discover.type", focus, content = false, onSelect = onSelectType) }
            }
        }
        state.catalogs?.let { catalogs ->
            item(key = "catalog") { PickerChip(catalogs, "discover.catalog", focus, content = true, onSelect = onSelectCatalog) }
        }
        itemsIndexed(state.filters, key = { index, _ -> "filter:$index" }) { index, filter ->
            PickerChip(filter, "discover.filter.$index", focus, content = true) { onSelectFilter(index, it) }
        }
    }
}

/** Types up to this many show as chips of their own. */
private const val TYPE_CHIPS = 4

/**
 * A [MenuChip] of [picker]'s [chip][DiscoverPicker.chip] ("Genre" until it narrows the catalog, then "Drama" in the
 * primary fill). A multi-select filter's options stay open while they are toggled. [content]: whether its words are
 * content text (an addon's catalog names and filter values, with our plain "All", "Genre" and "Catalog"), which take
 * their direction from their first letter ([com.edde746.tortuga.ui.theme.contentDirection]). Harness names: [name],
 * `[name].option.<i>`.
 */
@Composable
private fun PickerChip(picker: DiscoverPicker, name: String, focus: FocusMemory, content: Boolean, onSelect: (Int) -> Unit) {
    MenuChip(
        picker.chip,
        choices = picker.options.map { MenuChoice(it.label, it.selected, it.detail, contentLabel = content, contentDetail = content) },
        onPick = onSelect,
        probe = name,
        modifier = Modifier.rememberFocus(focus, name),
        closeOnPick = !picker.multiSelect,
        contentLabel = content,
        contentTitle = content,
    )
}

@Composable
private fun ItemGrid(
    grid: DiscoverBody.Grid,
    focus: FocusMemory,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenItem: (MetaCard) -> Unit,
    onItemMenu: (MetaCard, MediaAction) -> Unit,
) {
    val dimens = LocalTortugaDimens.current
    val gridState = rememberLazyGridState()
    val shape = grid.items.firstOrNull()?.posterShape ?: PosterShape.POSTER
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
                onClick = { onOpenItem(card) },
                onMenu = { action -> onItemMenu(card, action) },
                probe = "discover.item.$index",
                modifier = Modifier.fillMaxWidth().rememberFocus(focus, itemKey(card)),
            )
        }
        when (val more = grid.more) {
            DiscoverMore.Loading -> item(key = "more", span = { GridItemSpan(maxLineSpan) }) { MoreFooter(error = null, onRetry) }
            is DiscoverMore.Failed -> item(key = "more", span = { GridItemSpan(maxLineSpan) }) { MoreFooter(more.text, onRetry) }
            DiscoverMore.None, DiscoverMore.Available -> {}
        }
    }
    // The next page, once the end of the grid comes into view (and again while it stays in view).
    val canLoad = grid.more == DiscoverMore.Available
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(gridState, canLoad, grid.items.size) {
        if (!canLoad) return@LaunchedEffect
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - DiscoverStore.LOAD_AHEAD
        }.first { it }
        loadMore()
    }
}

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
            TortugaButton(T.browse.retry, onRetry, Modifier.layoutProbe("discover.more.retry"), style = ButtonStyle.PRIMARY, icon = TortugaIcons.Refresh)
        }
    }
}

private fun itemKey(card: MetaCard) = "item:${card.id}"
