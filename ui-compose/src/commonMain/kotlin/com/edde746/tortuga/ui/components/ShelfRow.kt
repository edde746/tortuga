package com.edde746.tortuga.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.board.SHELF_LOAD_AHEAD
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.focus.DirectionalMoves
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.navigation.clipStart
import com.edde746.tortuga.ui.navigation.navigationOverlap
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaDimens
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A titled, horizontally scrolling row of cards (a catalog on the Board, Continue Watching, a search result group):
 * the heading ([SectionTitle], at most 32 dp tall), [LocalTortugaDimens]' `shelfHeaderGap` and the cards. The row's
 * content padding holds the focused card's growth (TV) at its ends and bottom, so nothing clips it; lists of shelves
 * space them with `sectionSpacing` less that bottom room ([shelfSpacing]).
 *
 * Keyboard, D-pad and remote: left/right move between cards and the row scrolls to keep the focused card in view;
 * moving into the row from above or below lands on the card that had focus last, else, on TV, the first, not the card
 * under the focus, which the cards' widths, their count and the row's scroll would decide (the first Down from Search's
 * field). Elsewhere it lands on the card under the focus: the heading's See all sits at the row's right end, and Down
 * from it to the first card would leave Up no way back to it. Compose's memory of a group's focused element
 * ([focusEntry][com.edde746.tortuga.ui.focus.focusEntry]) gave the first card in a lazy row, so the row keeps the card
 * itself ([ShelfFocus.enter]). The row spans the shelf's width whatever its cards: Compose's Up and Down pick between
 * rows by their middles, so a row of one card, as wide as the card, would win over a full-width row nearer the focus
 * (Home's shortcuts under a Continue Watching of one card). A focused card that leaves the row (Continue Watching's,
 * watched to its end) hands focus to the card that takes its place, or at the row's end to the card before it, the
 * screen around it waiting meanwhile ([com.edde746.tortuga.ui.focus.focusHome]): focus is never left on nothing, where
 * the remote does nothing.
 * [state] is saveable, so the row keeps its scroll position across navigation; hoist it to keep it across the row
 * leaving the screen's list.
 *
 * @param title the row's heading (the catalog name, preformatted by the store).
 * @param action a trailing element in the heading ("See all").
 * @param onNearEnd the row's end came into view (its last [SHELF_LOAD_AHEAD] cards), or the items changed while it is in
 *   view: the row's next page, where it pages (the store ignores the call otherwise). After a page that fails, it comes
 *   again once the end leaves the view and comes back.
 * @param end an element after the last card (Home's See all card on a remote's UI).
 */
@Composable
fun <T> ShelfRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    action: (@Composable () -> Unit)? = null,
    onNearEnd: (() -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
    itemContent: @Composable LazyItemScope.(index: Int, item: T) -> Unit,
) {
    val dimens = LocalTortugaDimens.current
    val focus = remember { ShelfFocus<T>() }
    // The surfaces around the row wait while it hands focus on (FocusHome), rather than take the drop meanwhile.
    val appFocus = LocalOverlayHost.current?.appFocus
    val entry = if (LocalWindowLayout.current.formFactor == FormFactor.TV) items.firstOrNull()?.let(key) else null
    // Before the change is applied, while the row still holds focus: the card taking the place of the focused card the
    // items left. Kept once the change is applied, so a composition Compose drops leaves no hand-off behind.
    val gone = focus.key
    val handOff = if (items !== focus.items && focus.inRow && gone != null && items.isNotEmpty() && items.none { key(it) == gone }) {
        focus.items.indexOfFirst { key(it) == gone }.takeIf { it >= 0 }?.let { at -> key(items[at.coerceAtMost(items.lastIndex)]) }
    } else {
        null
    }
    SideEffect {
        if (items === focus.items) return@SideEffect
        focus.items = items
        focus.keep(items.mapTo(HashSet()) { key(it) })
        if (handOff != null) {
            focus.handOff = HandOff(handOff)
            appFocus?.handOffs?.add(focus)
        }
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(HeaderHeight).padding(horizontal = dimens.screenPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(title, Modifier.weight(1f))
            action?.invoke()
        }
        LazyRow(
            state = state,
            // The heading's gap above; the focused card's growth at the ends and below (TV). Scrolled-away cards end at
            // the TV column's edge.
            contentPadding = PaddingValues(start = dimens.screenPadding, end = dimens.screenPadding, top = dimens.shelfHeaderGap, bottom = dimens.shelfBottom),
            horizontalArrangement = Arrangement.spacedBy(dimens.gutter),
            modifier = Modifier
                .fillMaxWidth()
                .clipStart(navigationOverlap)
                .onFocusChanged { focus.inRow = it.hasFocus }
                .focusProperties { onEnter = { if (requestedFocusDirection in DirectionalMoves) focus.enter(entry) } }
                .focusGroup(),
        ) {
            itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
                val scope = this
                val itemKey = key(item)
                Box(Modifier.focusRequester(focus.requester(itemKey)).onFocusChanged { if (it.hasFocus) focus.key = itemKey }) {
                    scope.itemContent(index, item)
                }
            }
            if (end != null) item(key = END_KEY) { end() }
        }
    }
    focus.handOff?.let { pending ->
        LaunchedEffect(pending) {
            // The card composes in its new place with the row's next measure.
            for (frame in 0 until HAND_OFF_FRAMES) {
                withFrameNanos { }
                if (focus.requester(pending.target).requestFocus()) break
            }
            focus.handOff = null
            appFocus?.handOffs?.remove(focus)
        }
    }
    DisposableEffect(appFocus) { onDispose { appFocus?.handOffs?.remove(focus) } }
    if (onNearEnd != null) {
        val nearEnd by rememberUpdatedState(onNearEnd)
        LaunchedEffect(state, items.size) {
            snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= items.size - SHELF_LOAD_AHEAD }
                .distinctUntilChanged()
                .collect { if (it) nearEnd() }
        }
    }
}

/** [ShelfRow]'s focus: the items it last composed, whether focus is in the row, the card that had it last. */
private class ShelfFocus<T> {
    var items: List<T> = emptyList()
    var inRow = false
    var key: Any? = null

    /**
     * Focus to the card that had it last, else to [first]'s; false when neither is composed or [first] is null (Compose
     * then picks, as for a row scrolled away from both).
     */
    fun enter(first: Any?): Boolean {
        val last = key?.takeIf { it in requesters }
        return (last != null && requester(last).requestFocus()) || (first != null && requester(first).requestFocus())
    }

    /** The card to give focus to, the focused one having left the row. */
    var handOff by mutableStateOf<HandOff?>(null)

    private val requesters = HashMap<Any, FocusRequester>()

    fun requester(key: Any): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** Drops the requesters of cards no longer among the row's items ([keys]): they can't compose again. */
    fun keep(keys: Set<Any>) {
        requesters.keys.retainAll(keys)
    }
}

/** One hand-off of [ShelfRow]'s focus, to the card keyed [target]: each one runs anew, to the same card too. */
private class HandOff(val target: Any)

/** How many frames [ShelfRow] waits for the card taking a focused card's place to take focus: about half a second. */
private const val HAND_OFF_FRAMES = 30

/** The lazy key of [ShelfRow]'s `end`, apart from any item's. */
private const val END_KEY = "shelf\u0000end"

/** A shelf's or a group's heading: [LocalTortugaDimens]' `sectionTitleSize`, semibold, one line. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = sectionTitleStyle(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** A heading row's height (a shelf's, a group's). */
val HeaderHeight = 32.dp

/**
 * A heading's trailing action ("See all"): a quiet text button that fits the 32 dp heading, [trailingIcon] after its
 * [text]; focus fills it (TV) as every control. On a touch screen it takes taps in a square of at least 48 dp
 * ([TortugaSurface]'s `hitTarget`), over the heading's room.
 */
@Composable
fun HeaderAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, trailingIcon: ImageVector? = null) {
    TortugaSurface(
        onClick = onClick,
        modifier = modifier.height(HeaderHeight),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = TortugaColors.TextMuted,
        contentAlignment = Alignment.Center,
        hitTarget = true,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = if (trailingIcon != null) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (trailingIcon != null) Icon(trailingIcon, contentDescription = null, Modifier.size(18.dp))
        }
    }
}

/** The style of a [SectionTitle]: semibold at `sectionTitleSize`. */
@Composable
fun sectionTitleStyle(): TextStyle {
    val size = LocalTortugaDimens.current.sectionTitleSize
    return MaterialTheme.typography.titleLarge.copy(fontSize = size, lineHeight = size * 1.3f, fontWeight = FontWeight.SemiBold)
}

/** The room under a shelf's cards for a focused card's growth (TV); nothing elsewhere. */
val TortugaDimens.shelfBottom get() = if (focusGrows) focusReach else 0.dp

/** The gap to leave between shelves in a list: `sectionSpacing`, less the room each shelf already keeps under its cards. */
val TortugaDimens.shelfSpacing get() = sectionSpacing - shelfBottom

/** The room above a grid's first row for a focused poster's growth (TV); a hair elsewhere. */
val TortugaDimens.gridTop get() = if (focusGrows) focusReach else 4.dp
