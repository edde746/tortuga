package com.edde746.tortuga.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.WidthClass
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.HeaderHeight
import com.edde746.tortuga.ui.components.LocalPosterTemplate
import com.edde746.tortuga.ui.components.MediaMenu
import com.edde746.tortuga.ui.components.PosterCard
import com.edde746.tortuga.ui.components.SectionTitle
import com.edde746.tortuga.ui.components.ShelfRow
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.aspectRatio
import com.edde746.tortuga.ui.components.menuGestures
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.LocalPerfProbe
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

// Pieces the Board, Discover and Search share: a catalog item's card and a shelf's loading and failed forms.

/**
 * A catalog item's poster card: the title, and under it the year and rating when the addon sends them, marked once the
 * title is watched. In a grid, pass `Modifier.fillMaxWidth()` to fill the cell. Its menu ([MetaCard.menu]: a long
 * press, a right click, the Menu key, a hold of Select on TV) gives the picked entry's action to [onMenu]. Harness
 * names: [probe], its menu's entries `<probe>.menu.<action>`.
 */
@Composable
internal fun MetaPoster(
    card: MetaCard,
    onClick: () -> Unit,
    onMenu: (MediaAction) -> Unit,
    probe: String,
    modifier: Modifier = Modifier,
    width: Dp = LocalTortugaDimens.current.cardWidth(card.posterShape),
) {
    val poster = LocalPosterTemplate.current?.url(card.type, card.id, card.preview?.imdbId)
    MediaMenu(card.title, card.menu, onMenu, probe) { openMenu ->
        PosterCard(
            title = card.title,
            imageUrl = poster ?: card.posterUrl,
            fallbackUrl = card.posterUrl,
            shape = card.posterShape,
            onClick = onClick,
            modifier = modifier.menuGestures(openMenu).layoutProbe(probe),
            subtitle = card.subtitle,
            watched = card.watched,
            onLongClick = openMenu,
            width = width,
            caption = card.caption,
        )
    }
}

/**
 * The addon or tracker a shelf comes from, beside its heading. Addon names are left out on phones, where the heading
 * needs the width; [onPhones] keeps a short one there (a tracker's name: "Trakt"), so a list like "Watchlist" says whose
 * it is.
 */
@Composable
internal fun AddonLabel(name: String, onPhones: Boolean = false, modifier: Modifier = Modifier) {
    if (!onPhones && LocalWindowLayout.current.widthClass == WidthClass.COMPACT) return
    Text(
        name,
        style = MaterialTheme.typography.labelMedium.contentDirection(),
        color = TortugaColors.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 8.dp),
    )
}

/**
 * A shelf whose items are on their way: [title], the loading indicator, [trailing] and a row of blank cards the size
 * of real ones, so the page doesn't jump when they arrive. Nothing in it takes focus.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LoadingShelf(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    ShelfRow(
        title = title,
        items = BLANK_CARDS,
        key = { it },
        modifier = modifier,
        action = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(24.dp))
                trailing()
            }
        },
    ) { _, _ -> BlankCard(PosterShape.POSTER) }
}

private val BLANK_CARDS = List(8) { it }

/** A blank card the size of a [PosterCard] with its two caption lines. */
@Composable
private fun BlankCard(shape: PosterShape) {
    val fill = TortugaColors.SurfaceHigh
    Column(Modifier.width(LocalTortugaDimens.current.cardWidth(shape))) {
        Box(Modifier.fillMaxWidth().aspectRatio(shape.aspectRatio).background(fill, MaterialTheme.shapes.medium))
        Spacer(Modifier.height(if (LocalTortugaDimens.current.focusGrows) 12.dp else 8.dp))
        Box(Modifier.fillMaxWidth(0.7f).height(12.dp).background(fill, MaterialTheme.shapes.extraSmall))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.4f).height(10.dp).background(fill, MaterialTheme.shapes.extraSmall))
    }
}

/**
 * A shelf that failed: [title], the store's [message] and a retry button (it takes [retryModifier]: focus memory, the
 * harness probe).
 */
@Composable
internal fun FailedShelf(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryModifier: Modifier = Modifier,
) {
    MessageShelf(title, message, TortugaIcons.ErrorOutline, modifier) {
        TortugaButton(T.browse.retry, onRetry, retryModifier, style = ButtonStyle.PRIMARY, icon = TortugaIcons.Refresh)
    }
}

/**
 * A shelf whose items can't show: [title], then [message] after [icon] on one filled row, with [action] (a button) at
 * its end.
 */
@Composable
internal fun MessageShelf(
    title: String,
    message: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val dimens = LocalTortugaDimens.current
    Column(modifier.fillMaxWidth().padding(horizontal = dimens.screenPadding)) {
        Box(Modifier.height(HeaderHeight), contentAlignment = Alignment.CenterStart) { SectionTitle(title) }
        Spacer(Modifier.height(dimens.shelfHeaderGap))
        Row(
            Modifier
                .fillMaxWidth()
                .background(TortugaColors.SurfaceHigh, MaterialTheme.shapes.medium)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = TortugaColors.TextMuted)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

/**
 * Up and Down between the shelves of a lazy list ([state]'s), for the keyboard, the D-pad and the remote. Focus search
 * alone stops at the last shelf in view: a shelf below the screen isn't composed until it scrolls in, and a shelf's own
 * row doesn't look beyond its bounds vertically. A shelf wholly on screen takes focus at once; on TV ([onTv]) so does
 * one partly on screen, and the focused card's bring-into-view alone scrolls the list, once (Android TV's pivots the
 * card 30 % down the list), while a press during that scroll moves focus on. Otherwise the list scrolls first (Down:
 * the current shelf to the top; Up: the shelf above to the top), then focus moves. Put [modifier] on the list and
 * [item] on each of its items.
 */
@Stable
internal class ShelfNavigation(
    private val state: LazyListState,
    private val focusManager: FocusManager,
    private val scope: CoroutineScope,
    private val onTv: Boolean,
) {
    /** The list item that holds focus; -1 before any has. */
    private var focused = -1

    /** The scroll a press launched, until it ends with its focus move. */
    private var scrolling: Job? = null

    // One modifier per index, so a shelf composed again with the same index gets an equal modifier and can skip.
    private val items = HashMap<Int, Modifier>()

    /** On the list item at [index] of the lazy list. */
    fun item(index: Int): Modifier = items.getOrPut(index) { Modifier.onFocusChanged { if (it.hasFocus) focused = index } }

    /** On the list. */
    val modifier: Modifier = Modifier.onKeyEvent(::onKey)

    private fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val direction = when (event.key) {
            Key.DirectionDown, Key.NumPadDirectionDown -> FocusDirection.Down
            Key.DirectionUp, Key.NumPadDirectionUp -> FocusDirection.Up
            else -> return false
        }
        // A press while the list still scrolls for the last one (a held key's repeats, a quick second press) is
        // dropped: a new scroll would cancel that one, its focus move with it, and start again from rest.
        if (scrolling?.isActive == true) return true
        val from = focused
        val target = if (direction == FocusDirection.Down) from + 1 else from - 1
        val layout = state.layoutInfo
        if (from < 0 || target !in 0 until layout.totalItemsCount) return false
        val shown = layout.visibleItemsInfo.firstOrNull { it.index == target }
        val whole = shown != null && shown.offset >= layout.viewportStartOffset && shown.offset + shown.size <= layout.viewportEndOffset
        if (whole || onTv && shown != null) return focusManager.moveFocus(direction)
        scrolling = scope.launch {
            state.animateScrollToItem(if (direction == FocusDirection.Down) from else target)
            focusManager.moveFocus(direction)
        }
        return true
    }
}

/**
 * The [ShelfNavigation] of [state]'s list. In a perf run ([LocalPerfProbe]) it marks `shelves.settled` each time the
 * list comes to rest after a scroll, with its first item and that item's offset in pixels (`first=`, `offset=`):
 * the Android perf runs time Home's row-to-row presses by it.
 */
@Composable
internal fun rememberShelfNavigation(state: LazyListState): ShelfNavigation {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val onTv = LocalWindowLayout.current.formFactor == FormFactor.TV
    val perf = LocalPerfProbe.current
    if (perf != null) {
        LaunchedEffect(state, perf) {
            snapshotFlow { state.isScrollInProgress }.drop(1).filter { !it }.collect {
                perf.mark("shelves.settled", "first=${state.firstVisibleItemIndex} offset=${state.firstVisibleItemScrollOffset}")
            }
        }
    }
    return remember(state, focusManager, scope, onTv) { ShelfNavigation(state, focusManager, scope, onTv) }
}
