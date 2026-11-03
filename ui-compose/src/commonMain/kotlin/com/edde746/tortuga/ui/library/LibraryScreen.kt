package com.edde746.tortuga.ui.library

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.downloads.DownloadsStore
import com.edde746.tortuga.presentation.library.LibraryBody
import com.edde746.tortuga.presentation.library.LibraryRow
import com.edde746.tortuga.presentation.library.LibrarySort
import com.edde746.tortuga.presentation.library.LibraryStore
import com.edde746.tortuga.presentation.library.LibraryUiState
import com.edde746.tortuga.presentation.menu.FilterChip
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.navigation.LibrarySection
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.WidthClass
import com.edde746.tortuga.ui.components.EmptyState
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.LocalPosterTemplate
import com.edde746.tortuga.ui.components.MediaMenu
import com.edde746.tortuga.ui.components.MenuChip
import com.edde746.tortuga.ui.components.MenuChoice
import com.edde746.tortuga.ui.components.PosterCard
import com.edde746.tortuga.ui.components.TortugaChip
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.gridTop
import com.edde746.tortuga.ui.components.menuGestures
import com.edde746.tortuga.ui.components.playKey
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.navigation.statusBarOverlap
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors

/**
 * The Library destination in [app]: its sections as tabs ([AppSession.librarySections]: the profile's library,
 * Continue Watching and, where this device has downloads, Downloads), each with its own store, kept for the session
 * (Library is a tab: filters, sort and pages survive a visit to a title or another tab). It opens on
 * [AppSession.librarySection] (Downloads while offline, or the section asked for), keeps the section picked, and
 * switches to a section asked for while it shows
 * ([com.edde746.tortuga.presentation.navigation.TortugaNavigator.librarySection]).
 */
@Composable
fun LibraryRoute(app: AppSession) {
    val navigator = app.navigator
    val sections = app.librarySections
    // Once per appearance: the entry still draws while it animates out after its route left the stack, and an entry
    // first composed after that gets no stores and draws nothing.
    val stores = remember(app) {
        val library = navigator.retain(Route.Library, key = LibraryStore.KEY, create = app::libraryStore, release = LibraryStore::release)
        val continueWatching = navigator.retain(Route.Library, key = LibraryStore.CONTINUE_WATCHING_KEY, create = app::continueWatchingStore, release = LibraryStore::release)
        // Downloads only where this device has them; the store exists there.
        val downloads = if (LibrarySection.DOWNLOADS in sections) {
            navigator.retain(Route.Library, key = DownloadsStore.KEY, create = { checkNotNull(app.downloadsStore()) }, release = DownloadsStore::release)
        } else {
            null
        }
        if (library != null && continueWatching != null) LibraryStores(library, continueWatching, downloads) else null
    } ?: return
    var selected by rememberSaveable { mutableStateOf(app.librarySection().name) }
    val asked by navigator.librarySection.collectAsState()
    LaunchedEffect(asked) {
        if (asked != null) navigator.takeLibrarySection()?.takeIf { it in sections }?.let { selected = it.name }
    }
    val section = sections.firstOrNull { it.name == selected } ?: LibrarySection.LIBRARY
    val tabs = sections.map { it.label }
    val selectedTab = sections.indexOf(section)
    val onSelectTab = { index: Int -> selected = sections[index].name }
    val downloads = stores.downloads
    if (section == LibrarySection.DOWNLOADS && downloads != null) {
        val state by downloads.state.collectAsState()
        // The free space again each time the section shows.
        LaunchedEffect(downloads) { downloads.refreshStorage() }
        DownloadsScreen(
            state = state,
            tabs = tabs,
            selectedTab = selectedTab,
            onSelectTab = onSelectTab,
            onOpen = downloads::open,
            onPerform = downloads::perform,
            onPlayWith = downloads::playWith,
            onDeleteGroup = downloads::deleteGroup,
            onConfirmDeleteGroup = downloads::confirmDeleteGroup,
            onDismissDeleteGroup = downloads::dismissDeleteGroup,
            onPauseAll = downloads::pauseAll,
            onResumeAll = downloads::resumeAll,
            onOpenKeepSynced = downloads::openKeepSynced,
            onNoticeShown = downloads::dismissNotice,
        )
    } else {
        val store = if (section == LibrarySection.CONTINUE_WATCHING) stores.continueWatching else stores.library
        val state by store.state.collectAsState()
        LibraryScreen(
            state = state,
            tabs = tabs,
            selectedTab = selectedTab,
            onSelectTab = onSelectTab,
            onSelectType = store::selectType,
            onSelectSort = store::selectSort,
            onOpen = store::open,
            onMenu = store::menuAction,
            onLoadMore = store::loadNextPage,
            onOpenCalendar = store::openCalendar,
        )
    }
}

private class LibraryStores(val library: LibraryStore, val continueWatching: LibraryStore, val downloads: DownloadsStore?)

/**
 * The library or Continue Watching: one filter row (the [tabs] are the screen's only title), then
 * a grid of posters with their progress and watched marks, paged as it scrolls ([onLoadMore]). Each tab keeps its own
 * grid: switching shows the other section where it was left (from its top the first time), and only the section shown
 * asks for its next page. Where they fit beside each other (wide windows and TV) the row is solid chips: the tabs and
 * the type filter, the sort menu at its end; elsewhere (phones, and narrower windows in a language with long labels)
 * it is three dropdown chips fitted to the window, as Discover's menus: the view (the tabs), the type and the sort; in
 * a right-to-left language the row runs from the right. Its round Calendar button ends the row ([onOpenCalendar]). A
 * poster opens its title, in Continue Watching resumes its video, as the remote's Play/Pause does there on TV; its menu
 * ([LibraryRow.menu]: a right click, a long press, the Menu key, a hold of Select on TV) gives the picked entry's action
 * to [onMenu]. Every text comes from [LibraryUiState]. Harness names: `library.item.<i>`, its menu's entries
 * `library.item.<i>.menu.<action>`, the Calendar button `library.calendar`.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    tabs: List<String>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onSelectType: (String?) -> Unit,
    onSelectSort: (LibrarySort) -> Unit,
    onOpen: (String) -> Unit,
    onMenu: (String, MediaAction) -> Unit,
    onLoadMore: () -> Unit,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = rememberFocusMemory()
    val tabGrids = rememberSaveableStateHolder()
    Column(modifier.fillMaxSize().padding(top = statusBarOverlap)) {
        FilterBar(state, tabs, selectedTab, onSelectTab, onSelectType, onSelectSort, onOpenCalendar)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // The filter row stays: focus on a tab chip stays there as the grid under it changes.
            tabGrids.SaveableStateProvider(selectedTab) {
                when (val body = state.body) {
                    LibraryBody.Loading -> LoadingState()
                    is LibraryBody.Empty -> EmptyState(body.text, icon = TortugaIcons.VideoLibrary)
                    is LibraryBody.Grid -> LibraryGrid(body, state.continueWatching, focus, onOpen, onMenu, onLoadMore)
                }
            }
        }
    }
    val firstItem = when (val body = state.body) {
        LibraryBody.Loading, is LibraryBody.Empty -> null
        is LibraryBody.Grid -> body.items.first().id
    }
    RestoreFocus(focus, ready = firstItem != null, fallback = firstItem)
}

/**
 * The filter row: the tabs and the type filter as chips with the sort menu after them where they all fit side by side
 * (the sort naming its order where there is room for that on expanded windows and TV, else its icon alone), else
 * dropdown chips fitted to the window ([MenuFilterRow]), as always on phones; no chip lies under another or runs past
 * the window's edge in any language. A section without filters ([state] null: Downloads) has the tabs alone. With
 * [onOpenCalendar] the Calendar's button ends the row, at the screen's edge. Harness names: the chips
 * `library.tab.<i>` and `library.type.<i>`; the menus `library.view` and `library.type` with their `.option.<i>`; the
 * sort `library.sort` with its `.option.<i>`; the Calendar button `library.calendar`.
 */
@Composable
internal fun FilterBar(
    state: LibraryUiState?,
    tabs: List<String>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onSelectType: (String?) -> Unit,
    onSelectSort: (LibrarySort) -> Unit,
    onOpenCalendar: (() -> Unit)?,
) {
    val showsFilters = state?.showsFilters == true
    val showsTypes = state?.showsTypes == true
    val widthClass = LocalWindowLayout.current.widthClass
    if (widthClass == WidthClass.COMPACT) {
        MenuFilterRow(state, tabs, selectedTab, showsFilters, showsTypes, onSelectTab, onSelectType, onSelectSort, onOpenCalendar)
        return
    }
    val sorted = state?.takeIf { showsFilters && it.sortChip != null }
    val dimens = LocalTortugaDimens.current
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        // The chips and the sort chip's two looks, composed to be measured only: never placed, with nothing for screen
        // readers and no harness names.
        fun widthOf(slot: String, content: @Composable (Modifier) -> Unit): Int =
            subcompose(slot) { CompositionLocalProvider(LocalLayoutProbe provides null) { content(Modifier.clearAndSetSemantics {}) } }
                .single()
                .maxIntrinsicWidth(constraints.maxHeight)
        val chips = widthOf("chips") { FilterChips(state, tabs, selectedTab, onSelectTab = {}, onSelectType = {}, it) }
        val iconSort = sorted?.let { sort -> widthOf("iconSort") { SortChip(sort, iconOnly = true, onSelectSort = {}, it) } }
        // Below the expanded width the sort is its icon alone, so the tabs beside it keep their whole labels.
        val namedSort = sorted?.takeIf { widthClass == WidthClass.EXPANDED }?.let { sort -> widthOf("namedSort") { SortChip(sort, iconOnly = false, onSelectSort = {}, it) } }
        val gap = FILTER_GAP.roundToPx()
        val calendar = if (onOpenCalendar != null) gap + dimens.chipHeight.roundToPx() else 0
        val room = constraints.maxWidth - dimens.screenPadding.roundToPx() - calendar
        val namesSort = namedSort != null && chips + gap + namedSort <= room
        val chipsFit = namesSort || chips + (iconSort?.let { gap + it } ?: 0) <= room
        val row = subcompose("row") {
            if (chipsFit) {
                ChipFilterRow(state, tabs, selectedTab, sorted, sortIconOnly = !namesSort, onSelectTab, onSelectType, onSelectSort, onOpenCalendar)
            } else {
                MenuFilterRow(state, tabs, selectedTab, showsFilters, showsTypes, onSelectTab, onSelectType, onSelectSort, onOpenCalendar)
            }
        }.map { it.measure(constraints.copy(minHeight = 0)) }
        layout(constraints.maxWidth, row.maxOfOrNull { it.height } ?: 0) { row.forEach { it.placeRelative(0, 0) } }
    }
}

/**
 * The chip row: the [FilterChips] from the screen's edge, [sorted]'s sort chip ([sortIconOnly]: its icon alone) at
 * the row's end, [FILTER_GAP] or more after them, then the Calendar's button ([onOpenCalendar]).
 */
@Composable
private fun ChipFilterRow(
    state: LibraryUiState?,
    tabs: List<String>,
    selectedTab: Int,
    sorted: LibraryUiState?,
    sortIconOnly: Boolean,
    onSelectTab: (Int) -> Unit,
    onSelectType: (String?) -> Unit,
    onSelectSort: (LibrarySort) -> Unit,
    onOpenCalendar: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().padding(end = LocalTortugaDimens.current.screenPadding), verticalAlignment = Alignment.CenterVertically) {
        FilterChips(state, tabs, selectedTab, onSelectTab, onSelectType, Modifier.weight(1f).focusRestorer().focusGroup())
        if (sorted != null) {
            Spacer(Modifier.width(FILTER_GAP))
            SortChip(sorted, sortIconOnly, onSelectSort)
        }
        if (onOpenCalendar != null) {
            Spacer(Modifier.width(FILTER_GAP))
            CalendarButton(onOpenCalendar)
        }
    }
}

/** The tabs and, where [state] shows types, a hairline and the type filter: solid chips [FILTER_GAP] apart. */
@Composable
private fun FilterChips(
    state: LibraryUiState?,
    tabs: List<String>,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onSelectType: (String?) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.padding(start = LocalTortugaDimens.current.screenPadding, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(FILTER_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, label ->
            TortugaChip(label, selected = index == selectedTab, onClick = { onSelectTab(index) }, modifier = Modifier.layoutProbe("library.tab.$index"))
        }
        if (state != null && state.showsTypes) {
            VerticalDivider(Modifier.padding(horizontal = 4.dp).height(20.dp), color = TortugaColors.Hairline)
            state.types.forEachIndexed { index, option ->
                // A type keeps its chip (and focus on it) as types come and go before it.
                key(option.type) {
                    TortugaChip(option.label, selected = option.selected, onClick = { onSelectType(option.type) }, modifier = Modifier.layoutProbe("library.type.$index"))
                }
            }
        }
    }
}

/**
 * The filter row where the chips don't fit (phones, and narrower windows in a language with long labels): the view
 * (Library or Continue Watching, the selected tab), the type ([LibraryUiState.typeChip]: "Type", or the type it narrows
 * to in the primary fill) and the sort (its icon) as dropdown chips, fitted to the window ([FittingRow]) beside the
 * Calendar's button at the row's end ([onOpenCalendar]).
 */
@Composable
private fun MenuFilterRow(
    state: LibraryUiState?,
    tabs: List<String>,
    selectedTab: Int,
    showsFilters: Boolean,
    showsTypes: Boolean,
    onSelectTab: (Int) -> Unit,
    onSelectType: (String?) -> Unit,
    onSelectSort: (LibrarySort) -> Unit,
    onOpenCalendar: (() -> Unit)?,
) {
    val dimens = LocalTortugaDimens.current
    Row(Modifier.fillMaxWidth().padding(horizontal = dimens.screenPadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FittingRow(Modifier.weight(1f).focusGroup()) {
            MenuChip(
                FilterChip(T.library.title, tabs[selectedTab], tabs[selectedTab], narrowed = false),
                choices = tabs.mapIndexed { index, label -> MenuChoice(label, index == selectedTab) },
                onPick = onSelectTab,
                probe = "library.view",
            )
            if (state != null && showsTypes) {
                state.typeChip?.let { chip ->
                    MenuChip(
                        chip,
                        choices = state.types.map { MenuChoice(it.label, it.selected) },
                        onPick = { onSelectType(state.types[it].type) },
                        probe = "library.type",
                    )
                }
            }
            if (state != null && showsFilters) SortChip(state, iconOnly = true, onSelectSort)
        }
        if (onOpenCalendar != null) {
            Spacer(Modifier.width(FILTER_GAP))
            CalendarButton(onOpenCalendar)
        }
    }
}

/** The Calendar's round button, as tall as the chips beside it: it opens the Calendar. */
@Composable
private fun CalendarButton(onClick: () -> Unit) {
    TortugaIconButton(
        TortugaIcons.CalendarToday,
        T.calendar.title,
        onClick,
        Modifier.layoutProbe("library.calendar"),
        size = LocalTortugaDimens.current.chipHeight,
        focusInset = true,
    )
}

/**
 * The sort order as a [MenuChip] ([LibraryUiState.sortChip]): its icon and the order's name, or [iconOnly] the icon;
 * [modifier] goes to the chip.
 */
@Composable
private fun SortChip(state: LibraryUiState, iconOnly: Boolean, onSelectSort: (LibrarySort) -> Unit, modifier: Modifier = Modifier) {
    val chip = state.sortChip ?: return
    MenuChip(
        chip,
        choices = state.sorts.map { MenuChoice(it.label, it.selected) },
        onPick = { onSelectSort(state.sorts[it].sort) },
        probe = "library.sort",
        modifier = modifier,
        leadingIcon = TortugaIcons.Sort,
        iconOnly = iconOnly,
    )
}

/**
 * Its children side by side, [FILTER_GAP] apart, each as wide as it asks while they all fit; past that the narrowest
 * keep their width and the widest share what is left evenly (a chip then cuts its label short), so the row never runs
 * past its own width.
 */
@Composable
private fun FittingRow(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = FILTER_GAP.roundToPx()
        var left = (constraints.maxWidth - gap * (measurables.size - 1)).coerceAtLeast(0)
        val wanted = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val widths = IntArray(measurables.size)
        wanted.indices.sortedBy { wanted[it] }.forEachIndexed { done, index ->
            widths[index] = minOf(wanted[index], left / (measurables.size - done))
            left -= widths[index]
        }
        val placeables = measurables.mapIndexed { index, child -> child.measure(constraints.copy(minWidth = 0, maxWidth = widths[index], minHeight = 0)) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gap
            }
        }
    }
}

private val FILTER_GAP = 8.dp

@Composable
private fun LibraryGrid(
    grid: LibraryBody.Grid,
    plays: Boolean,
    focus: FocusMemory,
    onOpen: (String) -> Unit,
    onMenu: (String, MediaAction) -> Unit,
    onLoadMore: () -> Unit,
) {
    val dimens = LocalTortugaDimens.current
    val loadMore by rememberUpdatedState(onLoadMore)
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(dimens.cardWidth(PosterShape.POSTER)),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        // The screen's content edge at the sides; room for a focused poster's growth (TV) at the top.
        contentPadding = PaddingValues(start = dimens.screenPadding, end = dimens.screenPadding, top = dimens.gridTop, bottom = dimens.sectionSpacing),
        horizontalArrangement = Arrangement.spacedBy(dimens.gutter),
        verticalArrangement = Arrangement.spacedBy(dimens.gutter),
    ) {
        itemsIndexed(grid.items, key = { _, row -> row.id }) { index, row ->
            LibraryCard(row, index, focus, onOpen, onMenu, plays = plays)
        }
    }
    // The next page once the last rows come into view.
    LaunchedEffect(gridState, grid.hasMore, grid.items.size) {
        if (!grid.hasMore) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= grid.items.size - LibraryStore.LOAD_AHEAD) loadMore() }
    }
}

@Composable
private fun LibraryCard(
    row: LibraryRow,
    index: Int,
    focus: FocusMemory,
    onOpen: (String) -> Unit,
    onMenu: (String, MediaAction) -> Unit,
    plays: Boolean,
) {
    val probe = "library.item.$index"
    val poster = LocalPosterTemplate.current?.url(row.type, row.id, null)
    MediaMenu(row.name, row.menu, { action -> onMenu(row.id, action) }, probe) { openMenu ->
        PosterCard(
            title = row.name,
            imageUrl = poster ?: row.poster,
            fallbackUrl = row.poster,
            shape = row.posterShape,
            onClick = { onOpen(row.id) },
            onLongClick = openMenu,
            progress = row.progress?.toFloat(),
            watched = row.watched,
            badge = row.badge,
            badgeDescription = row.badgeDescription,
            reserveSubtitle = false,
            caption = row.caption,
            modifier = Modifier
                .fillMaxWidth()
                .rememberFocus(focus, row.id)
                .menuGestures(openMenu)
                .then(if (plays) Modifier.playKey { onOpen(row.id) } else Modifier)
                .layoutProbe(probe),
        )
    }
}
