package com.edde746.tortuga.ui.board

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.Bitmap
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.pxOrElse
import coil3.transform.Transformation
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.BoardBody
import com.edde746.tortuga.presentation.board.BoardRow
import com.edde746.tortuga.presentation.board.BoardStore
import com.edde746.tortuga.presentation.board.BoardUiState
import com.edde746.tortuga.presentation.board.ContinueWatchingCard
import com.edde746.tortuga.presentation.board.FolderTile
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.board.RowStatus
import com.edde746.tortuga.presentation.board.SeeAll
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.WidthClass
import com.edde746.tortuga.ui.components.EmptyState
import com.edde746.tortuga.ui.components.HeaderAction
import com.edde746.tortuga.ui.components.LocalPosterTemplate
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.MediaMenu
import com.edde746.tortuga.ui.components.OfflineMessage
import com.edde746.tortuga.ui.components.PosterCard
import com.edde746.tortuga.ui.components.ShelfRow
import com.edde746.tortuga.ui.components.aspectRatio
import com.edde746.tortuga.ui.components.fadeStops
import com.edde746.tortuga.ui.components.menuGestures
import com.edde746.tortuga.ui.components.overlayOpener
import com.edde746.tortuga.ui.components.playKey
import com.edde746.tortuga.ui.components.shelfSpacing
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.RestoreFocus
import com.edde746.tortuga.ui.focus.focusRing
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.image.toCoilBitmap
import com.edde746.tortuga.ui.image.toComposeBitmap
import com.edde746.tortuga.ui.navigation.ScrimWhileScrolled
import com.edde746.tortuga.ui.navigation.extendStart
import com.edde746.tortuga.ui.navigation.navigationInset
import com.edde746.tortuga.ui.navigation.statusBarOverlap
import com.edde746.tortuga.ui.probe.HOME_CONTENT_MARK
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.probe.perfMark
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import coil3.size.Size as ImageSize

/**
 * The Board of [app]'s profile: its [BoardStore] lives while the Board is on the back stack (it is the root), so
 * coming back from Detail or another tab finds the rows loaded, scrolled and focused as they were.
 */
@Composable
fun BoardRoute(app: AppSession) {
    // Once per appearance: the entry still draws while it animates out after its route left the stack, and an entry
    // first composed after that gets no store and draws nothing.
    val store = remember {
        app.navigator.retain(
            Route.Board,
            key = BoardStore.KEY,
            create = app::boardStore,
            release = BoardStore::release,
        )
    } ?: return
    val state by store.state.collectAsState()
    BoardScreen(
        state = state,
        onRowVisible = store::rowVisible,
        onRetryRow = store::retryRow,
        onLoadMore = store::loadNextPage,
        onSeeAll = store::seeAll,
        onOpenItem = store::openItem,
        onOpenFolder = store::openFolder,
        onOpenContinueWatching = store::openContinueWatching,
        onItemMenu = store::menuAction,
        onContinueWatchingMenu = store::continueWatchingAction,
        onOpenDownloads = store::openDownloads,
        onOpenShortcut = store::openShortcut,
    )
}

/**
 * The home screen: Continue Watching with progress bars, then Home's shortcuts
 * ([BoardUiState.shortcuts], [BoardShortcuts]: a press or a click runs [onOpenShortcut] with the shortcut's key; at the
 * top of the shelves without Continue Watching), then one shelf per catalog, on every layout: phones, tablets, TV and
 * desktop. Nothing sits above the first of them: the profile is in the rail, the TV column or, on phones, Settings. A
 * shelf reports itself with [onRowVisible] as it comes near the screen (the store loads rows lazily); each shows its own
 * loading, failure ([onRetryRow]) and items, with "See all" ([onSeeAll]) opening its catalog in Discover
 * ([BoardRow.seeAll]: in its heading, or on TV a card after its last item, [SeeAllCard]), and asks for its next page as
 * its end comes into view ([onLoadMore]), the heading's loading indicator showing while it comes. On TV and wide windows
 * the backdrop of the focused item fades in, in colour, behind the top of the page. A collection's row
 * ([BoardRow.folders]) shows its folders' tiles ([FolderTileCard]) in place of cards; a tile opens its folder
 * ([onOpenFolder]).
 * Row intents take the index of the row in [BoardBody.Rows] as rendered. Offline ([BoardBody.Offline]) the
 * catalogs give way to the offline state ([OfflineMessage]: Go to Downloads, [onOpenDownloads]), Continue Watching and
 * the shortcuts staying above it. A card's menu ([MetaCard.menu], [ContinueWatchingCard.menu]: a long press, a right
 * click, the Menu key, a hold of Select on TV) gives the picked entry's action to [onItemMenu] or
 * [onContinueWatchingMenu].
 */
@Composable
fun BoardScreen(
    state: BoardUiState,
    onRowVisible: (Int) -> Unit,
    onRetryRow: (Int) -> Unit,
    onLoadMore: (Int) -> Unit,
    onSeeAll: (Int) -> Unit,
    onOpenItem: (MetaCard) -> Unit,
    onOpenFolder: (row: Int, folderId: String) -> Unit,
    onOpenContinueWatching: (ContinueWatchingCard) -> Unit,
    onItemMenu: (MetaCard, MediaAction) -> Unit,
    onContinueWatchingMenu: (ContinueWatchingCard, MediaAction) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenShortcut: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTortugaDimens.current
    val layout = LocalWindowLayout.current
    val focus = rememberFocusMemory()
    val listState = rememberLazyListState()
    val showsBackdrop = layout.formFactor == FormFactor.TV || layout.widthClass == WidthClass.EXPANDED
    var focusedBackdrop by remember { mutableStateOf<String?>(null) }
    val body = state.body
    val rows = (body as? BoardBody.Rows)?.rows.orEmpty()
    val firstRow = rows.firstOrNull { it.items.isNotEmpty() || it.folders.isNotEmpty() }
    val firstCard = state.continueWatching.firstOrNull()?.let(::continueWatchingKey) ?: firstRow?.let(::firstKey)
    val shortcutsShown = state.shortcuts.isNotEmpty()
    // The perf runs' mark: the catalogs on screen with at least the first one's cards.
    val firstRowShown = rows.firstOrNull()?.let { it.items.isNotEmpty() || it.folders.isNotEmpty() } == true
    Box(modifier.fillMaxSize().perfMark(HOME_CONTENT_MARK, "rows=${rows.count { it.items.isNotEmpty() }}", ready = firstRowShown)) {
        when (body) {
            is BoardBody.Offline -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = (if (dimens.focusGrows) 24.dp else 12.dp) + statusBarOverlap, bottom = dimens.sectionSpacing),
                verticalArrangement = Arrangement.spacedBy(dimens.shelfSpacing),
            ) {
                if (state.continueWatching.isNotEmpty()) {
                    item(key = CONTINUE_WATCHING, contentType = SHELF) {
                        ContinueWatchingShelf(state.continueWatching, focus, onOpenContinueWatching, onContinueWatchingMenu)
                    }
                }
                if (shortcutsShown) {
                    item(key = SHORTCUTS, contentType = SHORTCUTS) { BoardShortcuts(state.shortcuts, focus, onOpenShortcut, below = null) }
                }
                item(key = OFFLINE) {
                    OfflineMessage(
                        body.offline,
                        onOpenDownloads,
                        // Alone, about the middle of the screen; under Continue Watching, right after it.
                        if (state.continueWatching.isEmpty()) Modifier.fillParentMaxHeight(0.8f) else Modifier,
                        actionModifier = Modifier.rememberFocus(focus, FOCUS_OFFLINE),
                    )
                }
            }
            BoardBody.Loading -> Box(Modifier.fillMaxSize()) { LoadingState() }
            is BoardBody.Empty -> Box(Modifier.fillMaxSize()) { EmptyState(body.text) }
            is BoardBody.Rows -> {
                if (showsBackdrop) Backdrop { focusedBackdrop }
                val navigation = rememberShelfNavigation(listState)
                // Continue Watching and the trackers' lists can arrive after the catalogs are on screen.
                ScrimWhileScrolled { listState.canScrollBackward }
                val keys = buildList {
                    if (state.continueWatching.isNotEmpty()) add(CONTINUE_WATCHING)
                    if (shortcutsShown) add(SHORTCUTS)
                    rows.forEach { add(it.key) }
                }
                val rowsFrom = keys.size - rows.size
                KeepAtTop(
                    listState,
                    firstKey = keys.firstOrNull(),
                    focus,
                    firstCard,
                    indexOf = { key -> keys.indexOf(key).takeIf { it >= 0 } },
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().then(navigation.modifier),
                    contentPadding = PaddingValues(top = (if (dimens.focusGrows) 24.dp else 12.dp) + statusBarOverlap, bottom = dimens.sectionSpacing),
                    verticalArrangement = Arrangement.spacedBy(dimens.shelfSpacing),
                ) {
                    if (state.continueWatching.isNotEmpty()) {
                        item(key = CONTINUE_WATCHING, contentType = SHELF) {
                            ContinueWatchingShelf(state.continueWatching, focus, onOpenContinueWatching, onContinueWatchingMenu, navigation.item(keys.indexOf(CONTINUE_WATCHING)))
                        }
                    }
                    if (shortcutsShown) {
                        item(key = SHORTCUTS, contentType = SHORTCUTS) {
                            // Down from the row: the next shelf's first card.
                            val below = rows.firstOrNull()?.let(::firstKey)
                            BoardShortcuts(state.shortcuts, focus, onOpenShortcut, below, navigation.item(rowsFrom - 1))
                        }
                    }
                    itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, _ -> SHELF }) { index, row ->
                        val currentIndex by rememberUpdatedState(index)
                        // Composed means on screen or about to be: the store loads it and keeps the row shown after it
                        // requested, whichever row that becomes. A loaded row reports too, for the row after it:
                        // requested only once composed itself, that row's items would come after the list composed it
                        // ahead as a loading shelf, and its cards would be composed inside the frame that scrolls it in.
                        LaunchedEffect(row.key, row.status == RowStatus.Idle) { onRowVisible(currentIndex) }
                        CatalogShelf(
                            row = row,
                            index = index,
                            focus = focus,
                            onRetry = { onRetryRow(currentIndex) },
                            onLoadMore = { onLoadMore(currentIndex) },
                            onSeeAll = { onSeeAll(currentIndex) },
                            onOpenItem = onOpenItem,
                            onOpenFolder = { folderId -> onOpenFolder(currentIndex, folderId) },
                            onItemMenu = onItemMenu,
                            onFocusItem = { focusedBackdrop = it.backgroundUrl },
                            modifier = navigation.item(rowsFrom + index),
                        )
                    }
                }
            }
        }
    }
    // After a click or a tap (touch mode) focus goes to the first card only once it is on screen whole: a card lower
    // down (a catalog below the fold answering before the ones above it) would scroll the page to it, away from what the
    // pointer is about to click (the shortcuts). Continue Watching's first card is the page's first.
    val firstShelf = firstRow?.key?.takeIf { state.continueWatching.isEmpty() }
    val firstShelfWhole by remember(listState, firstShelf) { derivedStateOf { firstShelf == null || listState.layoutInfo.showsWhole(firstShelf) } }
    val focusCard = firstCard?.takeIf { LocalInputModeManager.current.inputMode != InputMode.Touch || firstShelfWhole }
    val fallback = focusCard ?: FOCUS_OFFLINE.takeIf { body is BoardBody.Offline && body.offline.actionText != null }
    RestoreFocus(focus, ready = fallback != null, fallback = fallback)
}

internal fun KeyEvent.isDown(): Boolean =
    type == KeyEventType.KeyDown && (key == Key.DirectionDown || key == Key.NumPadDirectionDown)

/**
 * Keeps [state]'s list at its top when an item arrives before its first one ([firstKey], the list's first key: Continue
 * Watching's, the shortcuts' or the first shelf's, changes) while the list is at the top. A lazy list keeps its first
 * visible item where it is, so the new item would come in above it, out of sight: Continue Watching and the trackers'
 * rows can arrive after the catalogs are on screen (the library's and the trackers' answers may come after the
 * installed addons'). A list scrolled away from the top keeps its place.
 *
 * Focus the Board placed on its first card itself ([FocusMemory.placedKey]: a remote's, a keyboard's first focus as the
 * Board shows, before the user moved it or pressed, touched or scrolled the Board) follows to a new [firstCard] (new
 * rows above, or a shelf above getting its cards: the catalogs load in any order; [RestoreFocus] moves it), and the
 * list goes back to its top, on every layout, so the screen opens at its top with its first card focused, as when the
 * rows were there from the start, wherever the placed focus had scrolled it; left on the old first card, focus would
 * sit below the new rows and the first press would scroll the Board past them. Focus on any other card stays on it, in
 * view: a card the user moved to, and the card focus came back to (the one the user opened, whose playback can bring
 * Continue Watching in after the Board shows again); the list scrolls to its shelf ([indexOf] gives a key's place in
 * the list), the new rows above it.
 */
@Composable
private fun KeepAtTop(
    state: LazyListState,
    firstKey: Any?,
    focus: FocusMemory,
    firstCard: String?,
    indexOf: (Any) -> Int?,
) {
    val shown = remember(state) { FirstKey(firstKey, firstCard) }
    // After the composition that changed the items and before their measure: the state still describes the list as
    // it was laid out.
    SideEffect {
        val focused = focus.focusedKey
        val placed = focus.placedFocus.let { it != null && it == shown.card }
        val atTop = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
        if (placed && firstCard != null && firstCard != focused) {
            state.requestScrollToItem(0)
        } else if (firstKey != shown.key && atTop) {
            if (focused == null || placed) {
                state.requestScrollToItem(0)
            } else {
                shown.key?.let(indexOf)?.let { state.requestScrollToItem(it) }
            }
        }
        shown.key = firstKey
        shown.card = firstCard
    }
}

private class FirstKey(var key: Any?, var card: String?)

/** Whether the item under [key] is on screen whole, its top and its bottom inside the list's viewport. */
private fun LazyListLayoutInfo.showsWhole(key: Any): Boolean =
    visibleItemsInfo.firstOrNull { it.key == key }?.let { it.offset >= viewportStartOffset && it.offset + it.size <= viewportEndOffset } == true

/**
 * Continue Watching's cards: a press resumes one, and so does the remote's Play/Pause on TV ([playKey]); its menu
 * ([ContinueWatchingCard.menu]) goes to [onMenu]. A card's episode and time left take more lines when they need them
 * (most languages on a phone's card; Polish, Russian and Ukrainian three). A series' card counts its new episodes in
 * its corner ([ContinueWatchingCard.badge]).
 */
@Composable
private fun ContinueWatchingShelf(
    cards: List<ContinueWatchingCard>,
    focus: FocusMemory,
    onOpen: (ContinueWatchingCard) -> Unit,
    onMenu: (ContinueWatchingCard, MediaAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ShelfRow(
        title = T.browse.continueWatching.title,
        items = cards,
        key = { it.id },
        modifier = modifier,
    ) { index, card ->
        val probe = "board.cw.$index"
        val poster = LocalPosterTemplate.current?.url(card.type, card.id, null)
        MediaMenu(card.title, card.menu, { action -> onMenu(card, action) }, probe) { openMenu ->
            PosterCard(
                title = card.title,
                imageUrl = poster ?: card.posterUrl,
                fallbackUrl = card.posterUrl,
                shape = card.posterShape,
                onClick = { onOpen(card) },
                modifier = Modifier.rememberFocus(focus, continueWatchingKey(card)).menuGestures(openMenu).playKey { onOpen(card) }.layoutProbe(probe),
                subtitle = card.detail,
                wrapSubtitle = true,
                progress = card.shownProgress,
                badge = card.badge,
                badgeDescription = card.badgeDescription,
                onLongClick = openMenu,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CatalogShelf(
    row: BoardRow,
    index: Int,
    focus: FocusMemory,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSeeAll: () -> Unit,
    onOpenItem: (MetaCard) -> Unit,
    onOpenFolder: (folderId: String) -> Unit,
    onItemMenu: (MetaCard, MediaAction) -> Unit,
    onFocusItem: (MetaCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = row.status
    when {
        row.folders.isNotEmpty() -> ShelfRow(
            title = row.title,
            items = row.folders,
            key = { it.id },
            modifier = modifier,
            action = { AddonLabel(row.sourceName, modifier = Modifier.layoutProbe("board.row.$index.source")) },
        ) { position, tile ->
            FolderTileCard(
                tile = tile,
                shape = row.folderShape,
                onClick = { onOpenFolder(tile.id) },
                probe = "board.row.$index.folder.$position",
                modifier = Modifier.rememberFocus(focus, folderKey(row, tile)),
            )
        }
        row.items.isNotEmpty() -> ShelfRow(
            title = row.title,
            items = row.items,
            key = { it.id },
            modifier = modifier,
            action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Reloading, or loading the next page, keeps the items on screen.
                    val busy = when (status) {
                        RowStatus.Loading -> true
                        is RowStatus.Ready -> status.loadingMore
                        RowStatus.Idle, is RowStatus.Failed -> false
                    }
                    if (busy) LoadingIndicator(Modifier.size(24.dp))
                    AddonLabel(row.sourceName, onPhones = row.fromTracker, modifier = Modifier.layoutProbe("board.row.$index.source"))
                    if (row.seeAll == SeeAll.HEADING) {
                        HeaderAction(T.browse.board.seeAll, onSeeAll, Modifier.layoutProbe("board.row.$index.seeAll"), trailingIcon = TortugaIcons.ChevronRight)
                    }
                }
            },
            onNearEnd = onLoadMore,
            end = if (row.seeAll == SeeAll.CARD) {
                {
                    SeeAllCard(
                        row.items.last().posterShape,
                        onSeeAll,
                        Modifier.rememberFocus(focus, "seeAll:${row.key}").layoutProbe("board.row.$index.seeAllCard"),
                    )
                }
            } else {
                null
            },
        ) { position, card ->
            MetaPoster(
                card = card,
                onClick = { onOpenItem(card) },
                onMenu = { action -> onItemMenu(card, action) },
                probe = "board.row.$index.item.$position",
                modifier = Modifier
                    .rememberFocus(focus, itemKey(row, card))
                    .onFocusChanged { if (it.hasFocus) onFocusItem(card) },
            )
        }
        status is RowStatus.Failed -> FailedShelf(
            title = row.title,
            message = status.text,
            onRetry = onRetry,
            modifier = modifier,
            retryModifier = Modifier.rememberFocus(focus, "retry:${row.key}").layoutProbe("board.row.$index.retry"),
        )
        else -> LoadingShelf(row.title, modifier, trailing = {
            AddonLabel(row.sourceName, onPhones = row.fromTracker, modifier = Modifier.layoutProbe("board.row.$index.source"))
        })
    }
}

/**
 * See all at the end of a row on TV ([SeeAll.CARD]): the size of the row's [shape]d artwork, in the blank cards' fill,
 * a chevron over "See all"; focus shows as on the cards (the ring and the growth). [onClick] opens the row's catalog in
 * Discover.
 */
@Composable
private fun SeeAllCard(shape: PosterShape, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val corner = MaterialTheme.shapes.medium
    Box(
        modifier
            .width(LocalTortugaDimens.current.cardWidth(shape))
            .aspectRatio(shape.aspectRatio)
            .focusRing(corner)
            .clip(corner)
            .background(TortugaColors.SurfaceHigh)
            .overlayOpener()
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(TortugaIcons.ChevronRight, contentDescription = null, Modifier.size(36.dp), tint = TortugaColors.Text)
            Text(T.browse.board.seeAll, style = MaterialTheme.typography.titleSmall, color = TortugaColors.Text)
        }
    }
}

/**
 * The backdrop of the item that has focus ([url], read only here so focus moves don't recompose the Board; a
 * collection folder's screen gives its collection's backdrop), in colour
 * and faint, fading into the background towards the bottom and the start so the shelves stay readable. It follows
 * focus once focus rests (a D-pad held down doesn't load every image). It runs under the side navigation to the
 * window's edge ([navigationInset]), its fades laid across the whole picture. It draws no `NavigationBackdrop` dim:
 * its fade from the start edge is darker than that dim wherever the dim reaches.
 *
 * The image comes baked with its dim and fades ([BackdropArtwork]) at half the area's resolution: the shelves
 * scrolling over it redraw the screen every frame, and the image and two gradients blended over the top of the screen
 * on each of those frames cost a low-end TV's GPU more than a frame's time; one opaque image costs about nothing.
 */
@Composable
internal fun Backdrop(url: () -> String?) {
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow(url).collectLatest {
            delay(BACKDROP_SETTLE_MS)
            shown = it
        }
    }
    val background = MaterialTheme.colorScheme.background
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val context = LocalPlatformContext.current
    var area by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.extendStart(navigationInset).fillMaxWidth().fillMaxHeight(0.7f).onSizeChanged { area = it }) {
        if (area != IntSize.Zero) {
            Crossfade(shown, animationSpec = tween(durationMillis = 600), label = "backdrop") { current ->
                if (current != null) {
                    val request = remember(current, area, background, rtl) {
                        ImageRequest.Builder(context)
                            .data(current)
                            .size(area.width / BACKDROP_SCALE, area.height / BACKDROP_SCALE)
                            .transformations(BackdropArtwork(background, rtl))
                            .build()
                    }
                    AsyncImage(request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
            }
        }
    }
}

/**
 * The backdrop as one opaque image of the requested size: the artwork at [BACKDROP_ALPHA] over [background], cropped
 * to fill it from its top end (left in [rtl]), fading into [background] towards the bottom (from 30 % of the height)
 * and towards the start (across [BACKDROP_FADE] of the width), both fades eased so neither shows an edge.
 */
private class BackdropArtwork(private val background: Color, private val rtl: Boolean) : Transformation() {
    override val cacheKey = "backdrop(${background.toArgb()},$rtl)"

    override suspend fun transform(input: Bitmap, size: ImageSize): Bitmap {
        val width = size.width.pxOrElse { input.width }
        val height = size.height.pxOrElse { input.height }
        val artwork = input.toComposeBitmap()
        val baked = ImageBitmap(width, height, hasAlpha = false)
        val area = Size(width.toFloat(), height.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(baked), area) {
            drawRect(background)
            val scale = max(area.width / artwork.width, area.height / artwork.height)
            val drawn = IntSize((artwork.width * scale).roundToInt(), (artwork.height * scale).roundToInt())
            drawImage(
                artwork,
                dstOffset = IntOffset(if (rtl) 0 else width - drawn.width, 0),
                dstSize = drawn,
                alpha = BACKDROP_ALPHA,
            )
            val fadeTop = area.height * 0.3f
            drawRect(
                Brush.verticalGradient(*fadeStops(background, 1f, 0f, from = 1f), startY = fadeTop, endY = area.height),
                topLeft = Offset(0f, fadeTop),
                size = Size(area.width, area.height - fadeTop),
            )
            val fade = area.width * BACKDROP_FADE
            val across = fadeStops(background, 0f, 1f, from = 1f)
            if (rtl) {
                drawRect(Brush.horizontalGradient(*across, startX = area.width, endX = area.width - fade), topLeft = Offset(area.width - fade, 0f), size = Size(fade, area.height))
            } else {
                drawRect(Brush.horizontalGradient(*across, startX = 0f, endX = fade), size = Size(fade, area.height))
            }
        }
        return baked.toCoilBitmap()
    }
}

private const val CONTINUE_WATCHING = "continueWatching"
private const val SHORTCUTS = "shortcuts"
private const val SHELF = "shelf"
private const val OFFLINE = "offline"

/** The offline state's Go to Downloads in the Board's focus memory. */
private const val FOCUS_OFFLINE = "offline"

private const val BACKDROP_SETTLE_MS = 350L

/**
 * The artwork's opacity over the black page, in colour: the shelf headings' muted labels read at 3.8:1 or better
 * against the brightest 5 % of the picture under them, over ten Cinemeta backdrops.
 */
private const val BACKDROP_ALPHA = 0.3f

/** How far across the backdrop, from its start edge, it takes to clear of the background. */
private const val BACKDROP_FADE = 0.6f

/** The backdrop's resolution: a half, under its fades. */
private const val BACKDROP_SCALE = 2

private fun continueWatchingKey(card: ContinueWatchingCard) = "cw:${card.id}"

private fun itemKey(row: BoardRow, card: MetaCard) = "${row.key}|${card.id}"

private fun folderKey(row: BoardRow, tile: FolderTile) = "${row.key}|folder:${tile.id}"

/** The focus key of [row]'s first card or tile; null while it has neither. */
private fun firstKey(row: BoardRow): String? =
    row.items.firstOrNull()?.let { itemKey(row, it) } ?: row.folders.firstOrNull()?.let { folderKey(row, it) }
