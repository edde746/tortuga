package com.edde746.tortuga.ui.streams

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.players.PlayWithMenu
import com.edde746.tortuga.presentation.streams.GroupStatus
import com.edde746.tortuga.presentation.streams.StreamFilterBar
import com.edde746.tortuga.presentation.streams.StreamGroup
import com.edde746.tortuga.presentation.streams.StreamNoteKind
import com.edde746.tortuga.presentation.streams.StreamRow
import com.edde746.tortuga.presentation.streams.StreamSortMenu
import com.edde746.tortuga.presentation.streams.StreamsBody
import com.edde746.tortuga.presentation.streams.StreamsStore
import com.edde746.tortuga.presentation.streams.StreamsUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.board.isDown
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.ChoiceMenu
import com.edde746.tortuga.ui.components.EmptyState
import com.edde746.tortuga.ui.components.GroupGap
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.ItemMenuEntry
import com.edde746.tortuga.ui.components.ItemMenuSection
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.MenuChoice
import com.edde746.tortuga.ui.components.ScreenHeader
import com.edde746.tortuga.ui.components.SectionedItemMenu
import com.edde746.tortuga.ui.components.StreamBadgeRow
import com.edde746.tortuga.ui.components.TimedToast
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaChip
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.contentColumn
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.components.menuGestures
import com.edde746.tortuga.ui.components.sheetSides
import com.edde746.tortuga.ui.detail.ChipMaxWidth
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.StayInViewSpec
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.focus.tvRow
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.players.menuSection
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.probe.perfMark
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/** [route]'s [StreamsStore], kept by the navigator while the route is on the back stack. */
@Composable
private fun rememberStreamsStore(app: AppSession, route: Route.Streams): StreamsStore? =
    // Once per appearance: a sheet or screen still draws while it animates out after its route left the stack (its
    // store released, the item forgotten), and one first composed after that gets no store and draws nothing.
    remember(route) { app.navigator.retain(route, key = StreamsStore.KEY, create = { app.streamsStore(route) }, release = StreamsStore::release) }

/**
 * The Streams sheet of [route] over the screen under it (a bottom sheet, a side panel on TV). Picking a
 * stream plays it (the navigator replaces the sheet with the player, or closes it over the player that asked for a
 * stream), or in download mode queues its download (the app closes the sheet); Back, a tap outside or a drag down pops
 * the route. [visible] false (the route left the stack) slides it away.
 */
@Composable
fun StreamsSheet(app: AppSession, route: Route.Streams, visible: Boolean) {
    val store = rememberStreamsStore(app, route) ?: return
    val state by store.state.collectAsState()
    val intents = remember(store) {
        StreamsIntents(store::pick, store::download, store::playWith, store::dismissDownloadMessage, store::reload, store::toggleFiltered, store::selectAddon, store::refresh, store::toggleSort)
    }
    TortugaSheet(onDismiss = { app.navigator.pop(route) }, visible = visible, probe = "streams.sheet", label = state.title) {
        StreamsSheetContent(state, intents)
    }
}

/**
 * The Streams route drawn as a screen of its own: only when it is alone on the back stack (a development session
 * opened on Streams); over another screen it is a [StreamsSheet]. Coming back from the player finds the list as it
 * was, with focus on the stream that was picked.
 */
@Composable
fun StreamsRoute(app: AppSession, route: Route.Streams) {
    val navigator = app.navigator
    val store = rememberStreamsStore(app, route) ?: return
    val state by store.state.collectAsState()
    StreamsScreen(
        state = state,
        onPick = store::pick,
        onDownload = store::download,
        onPlayWith = store::playWith,
        onDismissDownloadMessage = store::dismissDownloadMessage,
        onReload = store::reload,
        onToggleFiltered = store::toggleFiltered,
        onSelectAddon = store::selectAddon,
        onRefresh = store::refresh,
        onToggleSort = store::toggleSort,
        onBack = if (navigator.canGoBack) ({ navigator.back() }) else null,
    )
}

/**
 * The streams of one video as a screen: the title and episode line with the sort button and Refresh at their end
 * ([StreamsActions]), why the list opened instead of a stream playing (auto-play's notice, or download mode's), the
 * row of the addons' chips while there are some ([StreamsBar]), then the filters' line and the streams grouped by addon
 * in the installed order.
 * Every text comes from [StreamsUiState]; the screen only lays it out and reports [onPick] (a row id),
 * [onDownload] (a row's Download action), [onPlayWith] (a row's "Play with…" choice: its id and the player's),
 * [onReload] (a failed addon's retry), [onToggleFiltered] (the filters' Show / Hide), [onSelectAddon] (the row's chips),
 * [onToggleSort] (the sort menu's options), [onRefresh] and [onBack]. What a Download came to shows as a toast
 * ([onDismissDownloadMessage] once shown).
 */
@Composable
fun StreamsScreen(
    state: StreamsUiState,
    onPick: (String) -> Unit,
    onDownload: (String) -> Unit,
    onPlayWith: (id: String, playerId: String) -> Unit,
    onDismissDownloadMessage: () -> Unit,
    onReload: () -> Unit,
    onToggleFiltered: () -> Unit,
    onSelectAddon: (String?) -> Unit,
    onRefresh: () -> Unit,
    onToggleSort: (String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTortugaDimens.current
    val focus = rememberFocusMemory()
    val chips = remember { ChipsFocus() }
    val intents = remember(onPick, onDownload, onPlayWith, onDismissDownloadMessage, onReload, onToggleFiltered, onSelectAddon, onRefresh, onToggleSort) {
        StreamsIntents(onPick, onDownload, onPlayWith, onDismissDownloadMessage, onReload, onToggleFiltered, onSelectAddon, onRefresh, onToggleSort)
    }
    Box(modifier.fillMaxSize().perfMarks(state)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                state.title,
                Modifier.contentColumn().tvRow(toNavigation = false),
                subtitle = state.subtitle,
                onBack = onBack,
                contentTitle = true,
                actions = { StreamsActions(state, intents, focus, chips) },
            )
            state.notice?.let {
                StreamsNotice(it, Modifier.contentColumn().padding(start = dimens.screenPadding, end = dimens.screenPadding, bottom = 4.dp), icon = state.noticeIcon)
            }
            if (state.addons.isNotEmpty()) {
                val side = dimens.screenPadding
                StreamsBar(state, intents, focus, chips, start = side, end = side, Modifier.contentColumn().padding(start = side, end = side, bottom = 4.dp))
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                when (val body = state.body) {
                    StreamsBody.Loading -> LoadingState()
                    is StreamsBody.Empty -> EmptyState(body.text)
                    // Another addon's chip, or another sort, lists the streams from the top.
                    is StreamsBody.Groups -> key(state.selectedAddon, state.sort?.label) {
                        LazyColumn(
                            state = rememberLazyListState(),
                            modifier = Modifier.contentColumn().fillMaxSize(),
                            // At the top only the room for a retry button's focus: the header's own margin is the gap.
                            contentPadding = PaddingValues(start = dimens.screenPadding, end = dimens.screenPadding, top = dimens.focusReach, bottom = dimens.gutter),
                        ) { streamGroups(state, focus, intents) }
                    }
                }
            }
        }
        state.downloadMessage?.let {
            TimedToast(it, "streams.downloadMessage", onDismissDownloadMessage, Modifier.align(Alignment.BottomCenter).padding(bottom = dimens.sectionSpacing))
        }
    }
    StreamsFocus(state, focus)
}

internal class StreamsIntents(
    val pick: (String) -> Unit,
    val download: (String) -> Unit,
    val playWith: (String, String) -> Unit,
    val dismissDownloadMessage: () -> Unit,
    val reload: () -> Unit,
    val toggleFiltered: () -> Unit,
    val selectAddon: (String?) -> Unit,
    val refresh: () -> Unit,
    val toggleSort: (String) -> Unit,
)

/**
 * The perf runs' marks on the list's root, as a screen or a sheet: `streams.content` once it shows stream rows,
 * `streams.done` once every addon has answered.
 */
private fun Modifier.perfMarks(state: StreamsUiState): Modifier {
    val count = state.groups.sumOf { it.streams.size }
    return perfMark("streams.content", "count=$count loading=${!state.answered}", ready = count > 0)
        .perfMark("streams.done", "count=$count", ready = state.answered)
}

/**
 * The sheet's content: the title and episode line as one compact header with the sort button and Refresh at its end
 * ([StreamsActions]; on TV the panel's top corner), auto-play's (or download mode's) notice, the row of the addons'
 * chips while there are some ([StreamsBar]; with none the list starts under the header), then the filters' line and
 * the streams grouped by addon, scrolling within the sheet; while nothing has come
 * yet, a loading indicator, and when no addon has a stream, the store's text. One column with its own tight spacing,
 * not the sheet's roomier one, never shorter than it has been while the sheet is open: a bottom sheet is as tall as its
 * content, and an addon's chip listing fewer streams would pull the row down from under the finger or the pointer. What
 * a row's Download came to shows under the list for a few seconds.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.StreamsSheetContent(state: StreamsUiState, intents: StreamsIntents) {
    val focus = rememberFocusMemory()
    val chips = remember { ChipsFocus() }
    val (start, end) = LocalWindowLayout.current.sheetSides()
    var tallest by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            .weight(1f, fill = false)
            .heightIn(min = with(LocalDensity.current) { tallest.toDp() })
            .onSizeChanged { tallest = maxOf(tallest, it.height) }
            .perfMarks(state),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().tvRow(toNavigation = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.titleMedium.contentDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            StreamsActions(state, intents, focus, chips)
        }
        state.notice?.let { StreamsNotice(it, icon = state.noticeIcon) }
        if (state.addons.isNotEmpty()) StreamsBar(state, intents, focus, chips, start, end)
        when (val body = state.body) {
            StreamsBody.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.size(48.dp))
            }
            is StreamsBody.Empty -> Text(
                body.text,
                style = MaterialTheme.typography.bodyLarge,
                color = TortugaColors.TextMuted,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            // Another addon's chip, or another sort, lists the streams from the top.
            is StreamsBody.Groups -> key(state.selectedAddon, state.sort?.label) {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    state = rememberLazyListState(),
                    // Room at the list's ends for the focus of the retry buttons beside the addons' names.
                    contentPadding = PaddingValues(vertical = LocalTortugaDimens.current.focusReach),
                ) { streamGroups(state, focus, intents) }
            }
        }
        state.downloadMessage?.let { TimedToast(it, "streams.downloadMessage", intents.dismissDownloadMessage, Modifier.align(Alignment.CenterHorizontally)) }
    }
    StreamsFocus(state, focus)
}

/**
 * The buttons at the end of the title's line, level with the title, on every layout (the bottom sheet, the TV panel's
 * top corner, the screen's header): the sort button ([StreamsSort]), then Refresh (`streams.refresh`), a round solid
 * button ([TortugaIconButton]). Refresh asks every addon again past the addon cache, keeping the selected chip, and
 * focus stays on it (a click moves focus to it, shown from the next key). Keys, a remote's D-pad or a keyboard's
 * arrows: Up from the row of chips, or from the list's top where there is no row, reaches the sort button, the nearer
 * of the two; Down from either enters the row on its selected chip ([chips]: All while none is), else lands on the
 * list's first stream ([focus]); Left and Right move between them and stay on their line (a [tvRow], which holds
 * nothing else), and Up stays too (nothing is above it). Tab reaches them before the row, the sort button first.
 */
@Composable
private fun StreamsActions(state: StreamsUiState, intents: StreamsIntents, focus: FocusMemory, chips: ChipsFocus) {
    val refresh = remember { FocusRequester() }
    val selected = state.selectedAddon
    val first = state.firstStream
    val row = state.addons.isNotEmpty()
    val down = Modifier.onPreviewKeyEvent { event -> event.isDown() && (if (row) chips.enter(selected) else first != null && focus.focus(first)) }
    state.sort?.let { StreamsSort(it, intents.toggleSort, down) }
    TortugaIconButton(
        TortugaIcons.Refresh,
        T.streams.refresh,
        {
            refresh.requestFocus()
            intents.refresh()
        },
        Modifier
            .focusRequester(refresh)
            .then(down)
            .layoutProbe("streams.refresh"),
    )
}

/**
 * The sort button (`streams.sort`), Refresh's size: the sort icon on surfaceHigh, in the primary fill while some key is
 * in effect ([StreamSortMenu.sorted]), named for screen readers by what the list goes by ("Sort by: Resolution"). It
 * opens [menu]'s options in a [ChoiceMenu] (Addon order, then each key, the ones in effect checked), which stays open
 * while keys are picked; [onPick] takes the option's id. Closed (Esc, Back, a click or a tap outside it), it gives
 * focus back to the button, shown from the next key: a desktop dropdown leaves focus nowhere as it closes. [modifier]
 * goes to the button. Harness names: `streams.sort`, its options `streams.sort.option.<i>`.
 */
@Composable
private fun StreamsSort(menu: StreamSortMenu, onPick: (String) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val button = remember { FocusRequester() }
    // Counted, so the button takes focus after the recomposition that closes the menu, once the menu is gone.
    var closings by remember { mutableIntStateOf(0) }
    LaunchedEffect(closings) { if (closings > 0) button.requestFocus() }
    Box {
        TortugaIconButton(
            TortugaIcons.Sort,
            menu.spoken,
            { open = true },
            modifier.focusRequester(button).layoutProbe("streams.sort"),
            style = if (menu.sorted) IconButtonStyle.PRIMARY else IconButtonStyle.FILLED,
        )
        ChoiceMenu(
            expanded = open,
            onDismiss = {
                open = false
                closings++
            },
            title = menu.title,
            choices = menu.options.map { MenuChoice(it.label, it.selected) },
            onPick = { onPick(menu.options[it].id) },
            probe = "streams.sort",
            closeOnPick = false,
        )
    }
}

/** The focus of the row of chips ([StreamsBar]): All's and each addon's, so a move into the row lands on the selected one. */
private class ChipsFocus {
    val all = FocusRequester()
    private val addons = HashMap<String, FocusRequester>()

    fun addon(id: String): FocusRequester = addons.getOrPut(id) { FocusRequester() }

    /** Focus to the chip of the addon [selected], All's while none is; false when it isn't shown. */
    fun enter(selected: String?): Boolean = (if (selected == null) all else addons[selected])?.requestFocus() == true
}

/**
 * The row over the list while two or more addons have a chip ([StreamsUiState.addons]): All and those chips in the
 * groups' order. Every one is the app's solid chip ([TortugaChip]) at the layout's chip height (40 dp on TV, 32 dp on
 * phones, tablets and desktop) on surfaceHigh, the selected one with a leading check mark and a semibold label on that
 * same fill (its `checkmark`), each as wide selected as not, so a click, a tap or Select moves no chip; focused (a
 * remote, a keyboard), the primary fill and a ring inside the edge, nothing growing, so the row keeps one height. 8 dp
 * apart, a name at most [ChipMaxWidth] wide. The row reaches [start] and [end] past its place to the list's sides (the
 * sheet's edges; the screen's column's), so at rest All starts where the title and the rows do and a chip goes out of
 * sight at the sheet's edge, not at its padding. It scrolls sideways under a finger or the pointer's sideways scroll;
 * focus scrolls it only when it moves to a chip out of view ([StayInViewSpec]: a chip in view, clear of that padding,
 * stays where it is, so All stays on screen while the chips beside it fit, and Left back to All brings the row back to
 * rest). Keys, a remote's D-pad or a keyboard's arrows (which move focus the same way): a move into the row (Up from
 * the list, Down from the title line's buttons) lands on the selected chip, All while none is ([chips]); Down from it
 * on the list's first stream ([focus]; what lies under the row where the list has none), not on whichever of that
 * row's controls lies nearest the chip; Up from it on those buttons ([StreamsActions]); Left and Right stay in the row
 * at its ends ([tvRow]); Tab walks it from All, after them.
 * Select, Enter, a click or a tap picks one, focus staying on it or, from a click, moving to it (shown from the next
 * key, which goes on from there; a tap leaves focus alone, as Android's touch mode does). The chips are not in the
 * screen's focus memory: focus comes back to the list's rows. Harness names: `streams.bar` (the row, the list's side to
 * its other side), `streams.bar.all`, `streams.bar.addon.<i>`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamsBar(state: StreamsUiState, intents: StreamsIntents, focus: FocusMemory, chips: ChipsFocus, start: Dp, end: Dp, modifier: Modifier = Modifier) {
    val selected = state.selectedAddon
    val first = state.firstStream
    val platformScroll = LocalBringIntoViewSpec.current
    // The padding in px as it is laid out, given to the spec by the screen's left and right.
    val (before, after) = with(LocalDensity.current) { start.roundToPx().toFloat() to end.roundToPx().toFloat() }
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val stayInView = remember(platformScroll, before, after, ltr) { if (ltr) StayInViewSpec(platformScroll, before, after) else StayInViewSpec(platformScroll, after, before) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides stayInView) {
        Row(
            modifier
                .fillMaxWidth()
                .reaching(start, end)
                .layoutProbe("streams.bar", reportsFocus = false)
                .onPreviewKeyEvent { event -> event.isDown() && first != null && focus.focus(first) }
                .tvRow(toNavigation = false) { chips.enter(selected) }
                .horizontalScroll(rememberScrollState())
                .padding(start = start, end = end),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TortugaChip(
                T.streams.all,
                selected = selected == null,
                onClick = {
                    chips.all.requestFocus()
                    intents.selectAddon(null)
                },
                modifier = Modifier.focusRequester(chips.all).layoutProbe("streams.bar.all"),
                checkmark = true,
            )
            state.addons.forEachIndexed { index, addon ->
                key(addon.id) {
                    val chip = chips.addon(addon.id)
                    TortugaChip(
                        addon.name,
                        selected = addon.selected,
                        onClick = {
                            chip.requestFocus()
                            intents.selectAddon(addon.id)
                        },
                        modifier = Modifier
                            .widthIn(max = ChipMaxWidth)
                            .focusRequester(chip)
                            .layoutProbe("streams.bar.addon.$index"),
                        checkmark = true,
                        contentLabel = true,
                    )
                }
            }
        }
    }
}

/** Lays the content out [start] and [end] wider than its place, reaching past both its sides (drawn there, not cut). */
private fun Modifier.reaching(start: Dp, end: Dp): Modifier = layout { measurable, constraints ->
    val before = start.roundToPx()
    val after = end.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = before + after))
    layout(placeable.width - before - after, placeable.height) { placeable.placeRelative(-before, 0) }
}

/** Why the list opened instead of a stream playing (auto-play found none, or its pick didn't start), or that a pick downloads. */
@Composable
private fun StreamsNotice(text: String, modifier: Modifier = Modifier, icon: ImageVector = TortugaIcons.Info) {
    Row(
        modifier
            .fillMaxWidth()
            .background(TortugaColors.SurfaceHighest, MaterialTheme.shapes.large)
            .padding(horizontal = RowInset, vertical = 10.dp)
            .layoutProbe("streams.notice"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * The filters' line: how many streams they hide in this list (or that it shows them), and Show / Hide, in the screen's
 * focus memory ([focus], [FILTER_FOCUS]). Its button keeps its size under focus, as a list's controls do on TV.
 */
@Composable
private fun StreamsFilterBar(filter: StreamFilterBar, focus: FocusMemory, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = RowInset, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Named apart from its button: a probe around the button would take the button's focus for its own.
        Text(
            filter.text,
            style = MaterialTheme.typography.bodyMedium,
            color = TortugaColors.TextMuted,
            modifier = Modifier.weight(1f).layoutProbe("streams.filter"),
        )
        TortugaButton(
            filter.actionLabel,
            onToggle,
            Modifier.rememberFocus(focus, FILTER_FOCUS).layoutProbe("streams.filter.toggle"),
            style = ButtonStyle.SECONDARY,
            grows = false,
        )
    }
}

/** The filters' Show / Hide in the screen's focus memory: no stream row's id. */
private const val FILTER_FOCUS = "streams.filter.toggle"

/** The notice's icon: the download arrow in download mode (the pick downloads), else the info mark. */
private val StreamsUiState.noticeIcon: ImageVector
    get() = if (downloadMode) TortugaIcons.Download else TortugaIcons.Info

/**
 * Focus to the stream picked last time, else the first, else the filters' Show when they hide every stream, once there
 * is one ([firstFocus]); since Refresh and the row over the list hold focus too, only until one of the list's elements
 * has had focus: streams that go and come back after that (Refresh, another addon's chip) leave focus where it is, and
 * focus whose element went (a Retry) goes back through the screen's memory ([com.edde746.tortuga.ui.focus.ScreenFocus]).
 *
 * The addons answer in any order (one whose answer is cached first), so the first stream can change after focus came
 * to it: focus the list placed there itself follows to the new first stream ([RestoreFocus]); left where it was, it
 * would sit under the rows of the addons above, and Up would walk through them before reaching the row. So does focus
 * placed on Show, once a stream comes or Show lists the hidden ones.
 */
@Composable
private fun StreamsFocus(state: StreamsUiState, focus: FocusMemory) {
    val first = state.firstFocus
    RestoreFocus(focus, ready = first != null || focus.lastFocused != null, fallback = first)
}

/** What takes focus first: the list's first stream, else the filters' Show when they hide every stream. */
private val StreamsUiState.firstFocus: String?
    get() = firstStream ?: filter?.let { FILTER_FOCUS }

/**
 * The filters' line when they hide streams, then each addon's heading (its name, a loading mark while it is asked, its
 * error with a retry), its streams as one connected group of rows, named `streams.row.<n>` across the groups for the
 * harness, and its notes (streams this app can't play, streams the filters hide). With an addon's chip selected the chip
 * right above names the list, so a heading leaves the name out: it shows only while the addon is asked or failed, and a
 * group after another without one keeps the heading's gap. Headings, notes and the filters' line start where
 * the rows' text does ([RowInset]). A row's Download ([StreamRow.downloadActionText]) and "Play with…"
 * ([StreamsUiState.playWith], on rows that play) are its menu's entries on every device (a long press, a right click,
 * the Menu key: `streams.menu.download`, `streams.menu.playWith.<player id>`); Download is on TV and desktop also a
 * button beside the row (`streams.row.<n>.download`), every row of the list keeping its room for one so the groups stay
 * aligned.
 */
private fun LazyListScope.streamGroups(state: StreamsUiState, focus: FocusMemory, intents: StreamsIntents) {
    state.filter?.let { filter -> item(key = "filter", contentType = "filter") { StreamsFilterBar(filter, focus, intents.toggleFiltered) } }
    val downloads = state.groups.any { group -> group.streams.any { it.downloadActionText != null } }
    val named = state.namesGroups
    state.groups.forEachIndexed { groupIndex, group ->
        val heading = state.headed(group)
        if (heading) item(key = "group:${group.id}", contentType = "header") { StreamGroupHeader(group, groupIndex, named, intents.reload) }
        val top = if (heading || groupIndex == 0) 0.dp else GroupsGap
        val first = state.firstRow(groupIndex)
        val count = group.streams.size
        itemsIndexed(group.streams, key = { _, row -> row.id }, contentType = { _, _ -> "row" }) { offset, row ->
            StreamRowWithActions(
                row = row,
                probe = "streams.row.${first + offset}",
                reserveDownload = downloads,
                playWith = state.playWith,
                focus = focus,
                intents = intents,
                modifier = Modifier.padding(top = if (offset > 0) GroupGap else top),
                shape = groupShape(offset, count),
            )
        }
        listOfNotNull(group.unplayableNote, group.filteredNote).forEachIndexed { noteIndex, note ->
            item(key = "note:${group.id}:$noteIndex", contentType = "note") {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = TortugaColors.TextMuted,
                    modifier = Modifier.padding(start = RowInset, end = RowInset, top = if (noteIndex == 0) 6.dp else 2.dp),
                )
            }
        }
    }
}

/**
 * A stream row ([StreamRowItem]) with its actions: the row's menu (long press, right click, the Menu key) holds its
 * Download and, when it plays here (not [StreamRow.external]), [playWith]'s players; on TV and desktop a download
 * button follows the row ([reserveDownload]: the room for one is kept on rows without it).
 */
@Composable
private fun StreamRowWithActions(
    row: StreamRow,
    probe: String,
    reserveDownload: Boolean,
    playWith: PlayWithMenu?,
    focus: FocusMemory,
    intents: StreamsIntents,
    modifier: Modifier,
    shape: Shape,
) {
    val label = row.downloadActionText
    val formFactor = LocalWindowLayout.current.formFactor
    val button = reserveDownload && (formFactor == FormFactor.TV || formFactor == FormFactor.DESKTOP)
    var menu by remember { mutableStateOf(false) }
    val sections = listOfNotNull(
        label?.let { ItemMenuSection(null, listOf(ItemMenuEntry(it, "download") { intents.download(row.id) })) },
        playWith?.takeUnless { row.external }?.menuSection { playerId -> intents.playWith(row.id, playerId) },
    )
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f)) {
            StreamRowItem(
                row = row,
                onClick = { intents.pick(row.id) },
                onMenu = if (sections.isNotEmpty()) ({ menu = true }) else null,
                modifier = Modifier.rememberFocus(focus, row.id).layoutProbe(probe),
                shape = shape,
            )
            if (sections.isNotEmpty()) {
                SectionedItemMenu(
                    expanded = menu,
                    onDismiss = { menu = false },
                    sections = sections,
                    probe = "streams.menu",
                    title = row.name,
                    contentTitle = true,
                )
            }
        }
        if (button) {
            val size = LocalTortugaDimens.current.iconButtonSize
            val spoken = row.downloadActionLabel
            if (spoken != null) {
                TortugaIconButton(
                    TortugaIcons.Download,
                    spoken,
                    { intents.download(row.id) },
                    Modifier.layoutProbe("$probe.download"),
                    style = IconButtonStyle.PLAIN,
                    size = size,
                )
            } else {
                Spacer(Modifier.size(size))
            }
        }
    }
}

/**
 * An addon's heading: its name ([named]) with a loading mark while it is asked; when it failed, its error under the
 * name and a retry at the end of the heading, beside both lines (`streams.group.<index>.retry`, [index] the group's
 * among the listed ones).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StreamGroupHeader(group: StreamGroup, index: Int, named: Boolean, onReload: () -> Unit) {
    val status = group.status
    Row(
        Modifier.fillMaxWidth().padding(start = RowInset, top = if (index == 0) 0.dp else GroupsGap, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (named) {
                    Text(
                        group.addonName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold).contentDirection(),
                        color = TortugaColors.TextMuted,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (status == GroupStatus.Loading) LoadingIndicator(Modifier.size(20.dp))
            }
            if (status is GroupStatus.Failed) Text(status.error, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
        }
        if (status is GroupStatus.Failed) {
            TortugaButton(
                T.nav.retry,
                onReload,
                Modifier.layoutProbe("streams.group.$index.retry").semantics { contentDescription = group.retryLabel },
                style = ButtonStyle.TEXT,
            )
        }
    }
}

/**
 * A stream: its name (quality) with its badges after it on the same line while they fit (wrapping as late as they
 * can), the note ("Last played", "Hidden by your filters", "Opens outside the app") at the top trailing corner, level
 * with the name's first line and kept whole on its one line while the name wraps and truncates before it, then the
 * addon's description lines, styled ([StreamDescription]), on a solid row of its addon's group; the one played last
 * rests a step lighter. The suggested one
 * ([StreamRow.suggestion]) says so above its name, sparkles and "Suggested", and why on its last line; both are part of
 * the row, in its content colour, so they follow the row's focus fill as its name does and focus never stops on them.
 * As a row of a list its focus is the fill alone (no growth on TV), and its badges invert on it. A click plays it (or
 * opens it outside the app, as its note says); [onMenu], where the row has a menu, opens it on a long press, a right
 * click or the Menu key.
 */
@Composable
private fun StreamRowItem(row: StreamRow, onClick: () -> Unit, onMenu: (() -> Unit)?, modifier: Modifier, shape: Shape) {
    TortugaSurface(
        onClick = onClick,
        modifier = (if (onMenu != null) modifier.menuGestures(onMenu) else modifier).fillMaxWidth(),
        shape = shape,
        color = if (row.lastUsed) TortugaColors.SurfaceHighest else TortugaColors.SurfaceHigh,
        onLongClick = onMenu,
        grows = false,
    ) {
        val muted = LocalContentColor.current.copy(alpha = 0.6f)
        Column(Modifier.padding(horizontal = RowInset, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            row.suggestion?.let { suggestion ->
                Row(Modifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(TortugaIcons.AutoAwesome, contentDescription = null, Modifier.size(16.dp))
                    Text(suggestion.label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StreamBadgeRow(row.badges, Modifier.weight(1f).alignByBaseline()) {
                    Text(
                        row.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold).contentDirection(),
                        modifier = Modifier.padding(end = 2.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                row.note?.let { note ->
                    Row(
                        Modifier.alignByBaseline(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val icon = when (row.noteKind) {
                            StreamNoteKind.LAST_USED -> TortugaIcons.History
                            StreamNoteKind.FILTERED -> TortugaIcons.VisibilityOff
                            StreamNoteKind.EXTERNAL, null -> TortugaIcons.OpenInNew
                        }
                        Icon(icon, contentDescription = null, Modifier.size(14.dp), tint = muted)
                        Text(
                            note,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = LocalContentColor.current.copy(alpha = 0.8f),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            row.description?.let {
                StreamDescription(it, row.descriptionRuns.orEmpty(), MaterialTheme.typography.bodySmall.contentDirection(), muted, maxLines = 6)
            }
            row.suggestion?.let {
                Text(it.reason, style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A stream row's inset, which the addons' headings, their notes and the filters' line share as their leading edge. */
private val RowInset = 12.dp

/** Between one addon's group and the next: above the next one's heading, or its first row where it has none. */
private val GroupsGap = 12.dp
