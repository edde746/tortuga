package com.edde746.tortuga.ui.components

import androidx.collection.intListOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.DropdownMenuPopupPositionProvider
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.menu.FilterChip
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.LocalWindowSize
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/**
 * Opens an item's menu the way each input asks for one: a secondary (right) click on the element, or the Menu key (a
 * keyboard's context-menu key, a remote's menu button, a gamepad's X or Y) or Shift+F10 while it or something in it has
 * focus. A long press on touch and, on TV, holding Select for 500 ms (`AdaptiveLayout`'s long-press timeout) belong to
 * the element's own click handling (`combinedClickable`'s `onLongClick`, [PosterCard]'s `onLongClick`), which every
 * element with a menu on TV needs: a remote may have no Menu key, and the SHIELD's system keeps it from apps. Place it
 * before the element's focus target, so the target's key events reach it.
 */
fun Modifier.menuGestures(onMenu: () -> Unit): Modifier = this then MenuGesturesElement(onMenu)

private data class MenuGesturesElement(val onMenu: () -> Unit) : ModifierNodeElement<MenuGesturesNode>() {
    override fun create() = MenuGesturesNode(onMenu)

    override fun update(node: MenuGesturesNode) {
        node.onMenu = onMenu
    }
}

private class MenuGesturesNode(var onMenu: () -> Unit) : DelegatingNode(), KeyInputModifierNode {
    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitPointerEventScope {
                    while (true) {
                        // The initial pass, so the element's own click handling never sees the secondary press.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            onMenu()
                        }
                    }
                }
            },
        )
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val menu = event.isContextMenuKey || (event.key == Key.F10 && event.isShiftPressed)
        if (menu) onMenu()
        return menu
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean = false
}

/**
 * On TV, the remote's Play/Pause and Play keys do what Select does on the element ([onPlay]): the elements Select
 * plays, a Continue Watching card and an episode, take it; on any other element the keys go on unhandled, as off TV.
 * The press and its release are both taken, so neither reaches the system's media keys. Place it before the element's
 * focus target, as [menuGestures].
 */
fun Modifier.playKey(onPlay: () -> Unit): Modifier = this then PlayKeyElement(onPlay)

private data class PlayKeyElement(val onPlay: () -> Unit) : ModifierNodeElement<PlayKeyNode>() {
    override fun create() = PlayKeyNode(onPlay)

    override fun update(node: PlayKeyNode) {
        node.onPlay = onPlay
    }
}

private class PlayKeyNode(var onPlay: () -> Unit) : Modifier.Node(), KeyInputModifierNode, CompositionLocalConsumerModifierNode {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.key != Key.MediaPlayPause && event.key != Key.MediaPlay) return false
        if (currentValueOf(LocalWindowLayout).formFactor != FormFactor.TV) return false
        if (event.type == KeyEventType.KeyDown) onPlay()
        return true
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean = false
}

/**
 * The key that asks for a context menu: a keyboard's context-menu key on desktop (which Compose reports by its AWT
 * code, not as `Key.Menu`); a remote's or keyboard's menu key, or a gamepad's X or Y, on Android.
 */
internal expect val KeyEvent.isContextMenuKey: Boolean

/**
 * The key that presses a focused element (Compose's: Select, Enter, the keypad's Enter, Space) held down now, as the app
 * sees it: [OverlayHostBox] watches every key before a screen takes it. A menu that opens while it is down was opened
 * by holding it ([ignoreHeldSelect]).
 */
internal class HeldSelect {
    var key: Key? = null
        private set

    /** Notes a press key's [event] going down or up. It only watches: the event goes on. */
    fun watch(event: KeyEvent): Boolean {
        if (event.isPressKey) {
            when (event.type) {
                KeyEventType.KeyDown -> key = event.key
                KeyEventType.KeyUp -> release(event.key)
            }
        }
        return false
    }

    /** [released] is up: its release went to a menu's own window (a dropdown), where the app's content never sees it. */
    fun release(released: Key) {
        if (key == released) key = null
    }
}

/** The window's [HeldSelect], one per [OverlayHostBox]. */
internal val LocalHeldSelect = staticCompositionLocalOf { HeldSelect() }

private val KeyEvent.isPressKey: Boolean
    get() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter || key == Key.Spacebar

/**
 * Drops the press key ([HeldSelect]) held down as a menu opens, until it is let go: holding Select or Enter on an item
 * opens its menu ([menuGestures]), and the key, still down, repeats into the entry that takes focus, where its release
 * would press the entry. Android counts a key's repeats; desktop doesn't (AWT reports each as a new press), so the
 * menu goes by what was down as it opened. A press that starts in the menu picks its entry as usual.
 */
internal fun Modifier.ignoreHeldSelect(): Modifier = this then IgnoreHeldSelectElement

private data object IgnoreHeldSelectElement : ModifierNodeElement<IgnoreHeldSelectNode>() {
    override fun create() = IgnoreHeldSelectNode()

    override fun update(node: IgnoreHeldSelectNode) = Unit
}

private class IgnoreHeldSelectNode : Modifier.Node(), KeyInputModifierNode, CompositionLocalConsumerModifierNode {
    private var ignored: Key? = null

    override fun onAttach() {
        ignored = currentValueOf(LocalHeldSelect).key
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        val held = ignored ?: return false
        if (event.key != held) return false
        if (event.type == KeyEventType.KeyUp) {
            ignored = null
            currentValueOf(LocalHeldSelect).release(held)
        }
        return true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = false
}

/**
 * One entry of an [ItemMenu]: its [label] from the screen's store and the intent it sends; [probe] names it for the
 * harness where the entries vary from item to item (else its index does). [selected] marks the choice in effect with
 * a check; [destructive] entries (delete, cancel) read in the error colour; [icon] leads the label in the dropdown (the
 * action a button stands for, as in Detail's ⋯) and in a media item's TV sheet, not in TV's side panel.
 * [contentLabel]: whether [label] is content text (an addon's or a person's), which takes its direction from its first
 * letter ([contentDirection]).
 */
@Immutable
class ItemMenuEntry(
    val label: String,
    val probe: String? = null,
    val selected: Boolean = false,
    val destructive: Boolean = false,
    val icon: ImageVector? = null,
    val contentLabel: Boolean = false,
    val onClick: () -> Unit,
)

/** A group of a [SectionedItemMenu]'s entries under an optional [title] ("Keep synced"). */
@Immutable
class ItemMenuSection(val title: String?, val entries: List<ItemMenuEntry>)

/**
 * An item's menu (mark as watched, remove, …): on TV a [TortugaSheet] (a panel along the right edge, [title] at its
 * top), elsewhere a [TortugaDropdownMenu] opened at the element it belongs to (place it next to that element in a Box
 * around both). The first entry takes focus when it opens, so a keyboard or remote user can act at once; picking an
 * entry closes the menu. [probe] names the entries `<probe>.0`, `<probe>.1`, … (or `<probe>.<entry's probe>`) for the
 * harness. A [media] item's menu (a poster, a Continue Watching card, a library item, an episode: [MediaMenu],
 * [itemEntry]) is on TV a bottom sheet instead: [title] on one line, each entry's icon before its label.
 * [contentTitle]: whether [title] is content text (an addon's or a person's), which takes its direction from its first
 * letter ([contentDirection]).
 */
@Composable
fun ItemMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    entries: List<ItemMenuEntry>,
    probe: String,
    title: String? = null,
    media: Boolean = false,
    contentTitle: Boolean = false,
) {
    SectionedItemMenu(expanded, onDismiss, listOf(ItemMenuSection(null, entries)), probe, title, media, contentTitle)
}

/**
 * An [ItemMenu] in [sections]: each section's title over its entries, a divider between sections in the dropdown.
 * Entries are counted across the sections for their harness names.
 */
@Composable
fun SectionedItemMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    sections: List<ItemMenuSection>,
    probe: String,
    title: String? = null,
    media: Boolean = false,
    contentTitle: Boolean = false,
) {
    val pick = { entry: ItemMenuEntry ->
        onDismiss()
        entry.onClick()
    }
    if (LocalWindowLayout.current.formFactor == FormFactor.TV) {
        TortugaSheet(onDismiss = onDismiss, title = title, visible = expanded, probe = "$probe.sheet", bottom = media, contentTitle = contentTitle) {
            // A long menu (a series' download choices) scrolls; focus brings the entry it moves to into view.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var index = 0
                for (section in sections) {
                    section.title?.let { GroupHeader(it) }
                    for (entry in section.entries) {
                        SheetOption(
                            label = entry.label,
                            onClick = { pick(entry) },
                            modifier = Modifier.layoutProbe("$probe.${entry.probe ?: index}"),
                            selected = entry.selected,
                            choice = false,
                            destructive = entry.destructive,
                            icon = if (media) entry.icon else null,
                            contentLabel = entry.contentLabel,
                        )
                        index++
                    }
                }
            }
        }
        return
    }
    TortugaDropdownMenu(expanded = expanded, onDismiss = onDismiss) {
        val first = remember { FocusRequester() }
        var index = 0
        sections.forEachIndexed { sectionIndex, section ->
            if (sectionIndex > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = TortugaColors.Hairline)
            section.title?.let { MenuSectionTitle(it) }
            for (entry in section.entries) {
                TortugaMenuItem(
                    label = entry.label,
                    onClick = { pick(entry) },
                    modifier = Modifier.layoutProbe("$probe.${entry.probe ?: index}").then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                    selected = entry.selected,
                    destructive = entry.destructive,
                    icon = entry.icon,
                    contentLabel = entry.contentLabel,
                )
                index++
            }
        }
        // The menu's content is composed each time it opens.
        if (index > 0) LaunchedEffect(Unit) { first.requestFocus() }
    }
}

/** A section's title in a [TortugaDropdownMenu], lined up with its entries' labels. */
@Composable
private fun MenuSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics { heading() },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A dropdown menu in the app's look: a [TortugaColors.SurfaceHighest] card with rounded corners and Material's menu
 * shadow, a step above the rows and sheets it opens over, no tonal tint. It opens at its anchor, the layout it is placed
 * in ([MenuAtAnchor]), inside the window's safe area ([InSafeArea]: clear of the status and navigation bars, a display
 * cutout and the keyboard) and [MenuMargin] clear of that area's top and bottom; a longer menu scrolls inside that
 * height, and the arrow keys walk its entries.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TortugaDropdownMenu(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val window = LocalWindowSize.current
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
        popupPositionProvider = rememberInSafeArea(MenuDefaults.rememberDropdownMenuPopupPositionProvider(MenuAtAnchor)),
    ) {
        Surface(shape = MaterialTheme.shapes.medium, color = TortugaColors.SurfaceHighest, shadowElevation = MenuDefaults.ShadowElevation) {
            Column(
                modifier
                    .maxHeightOf(window)
                    .ignoreHeldSelect()
                    .arrowKeyFocus()
                    .padding(vertical = 8.dp)
                    .width(IntrinsicSize.Max)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}

/** At most [window]'s height less [MenuMargin] above and below, read as it lays out (the window resizes under it). */
private fun Modifier.maxHeightOf(window: State<DpSize>): Modifier = layout { measurable, constraints ->
    val max = (window.value.height - MenuMargin * 2).roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(
        constraints.copy(minHeight = constraints.minHeight.coerceAtMost(max), maxHeight = constraints.maxHeight.coerceAtMost(max)),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Where a [TortugaDropdownMenu] opens: the candidates Material tries in turn, the first that fits inside the safe area's
 * margins (the last one moved inside them when none does). The window here is the safe area ([InSafeArea]).
 *
 * From a row, an anchor wider than the menu (a setting, a stream, an ordered row, an episode), the menu opens over the
 * row's end, where its value, ⋮ or button is: its end at the row's end, its top at the row's top, else (a sheet's last
 * rows have no room below them) its bottom at the row's bottom. It never opens off at the row's start or beside the
 * row, over the row before or after it, where it would read as that row's. From anything smaller (a chip, a button) it
 * is Material's dropdown: its start at the anchor's start, else its end at the anchor's end; below the anchor, else
 * above it, else centred on its top edge, else at the window's edge nearer to it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val MenuAtAnchor = MenuAnchorPosition.Custom(
    xCandidates = {
        val ltr = layoutDirection == LayoutDirection.Ltr
        val start = if (ltr) anchorBounds.left else anchorBounds.right - menuSize.width
        val end = if (ltr) anchorBounds.right - menuSize.width else anchorBounds.left
        when {
            anchorBounds.width > menuSize.width -> intListOf(end)
            anchorBounds.center.x < windowSize.width / 2 -> intListOf(start, end, 0)
            else -> intListOf(start, end, windowSize.width - menuSize.width)
        }
    },
    yCandidates = {
        if (anchorBounds.width > menuSize.width) {
            intListOf(anchorBounds.top, anchorBounds.bottom - menuSize.height)
        } else {
            val edge = if (anchorBounds.center.y < windowSize.height / 2) 0 else windowSize.height - menuSize.height
            intListOf(anchorBounds.bottom, anchorBounds.top - menuSize.height, anchorBounds.top - menuSize.height / 2, edge)
        }
    },
)

/**
 * Material's margin between a menu and the window's top and bottom edges (its `MenuVerticalMargin`): a menu that fits
 * inside it opens at its anchor, one taller than the window without it is centred over the whole window.
 */
private val MenuMargin = 48.dp

@Composable
private fun rememberInSafeArea(material: DropdownMenuPopupPositionProvider): DropdownMenuPopupPositionProvider {
    val insets = WindowInsets.safeDrawing
    val density = LocalDensity.current
    val window = LocalWindowInfo.current
    return remember(material, insets, density, window) { InSafeArea(material, insets, density, window) }
}

/**
 * [material]'s placement inside the window's safe area: the window ([WindowInfo.containerSize]) less
 * [WindowInsets.safeDrawing], given to Material as its window, the anchor and the menu moved into its coordinates and
 * back. Material checks a menu against a window of the size it is given from that window's origin; on Android the popup
 * gives it the visible display frame's size (the window less the system bars and the keyboard) but the anchor in the
 * window's coordinates, which start at the screen's top, behind the status bar. Material's limits then lay a status
 * bar's height too high: a menu with room below its row opened upward (Into in Export and import's sheet), and one could
 * open under the status bar.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private class InSafeArea(
    private val material: DropdownMenuPopupPositionProvider,
    private val insets: WindowInsets,
    private val density: Density,
    private val window: WindowInfo,
) : DropdownMenuPopupPositionProvider {
    override val transformOrigin: TransformOrigin get() = material.transformOrigin

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val left = insets.getLeft(density, layoutDirection)
        val top = insets.getTop(density)
        val container = window.containerSize
        val area = IntSize(
            (container.width - left - insets.getRight(density, layoutDirection)).coerceAtLeast(0),
            (container.height - top - insets.getBottom(density)).coerceAtLeast(0),
        )
        val origin = IntOffset(left, top)
        return material.calculatePosition(anchorBounds.translate(-origin), area, layoutDirection, popupContentSize) + origin
    }
}

/**
 * An entry of a [TortugaDropdownMenu]: [label] (two lines at most: the menu is at most 280 dp wide, so a long option
 * wraps rather than losing its end), its [detail] line under it, its [icon] before it, a check when [selected];
 * [destructive] in the error colour. A screen reader hears an action, and "selected" on the one in effect; a [choice]
 * (one of a [ChoiceMenu]'s) as a radio button, selected or not. [contentLabel], [contentDetail]: whether [label],
 * [detail] is content text (an addon's or a person's), which takes its direction from its first letter
 * ([contentDirection]).
 */
@Composable
fun TortugaMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    detail: String? = null,
    destructive: Boolean = false,
    icon: ImageVector? = null,
    choice: Boolean = false,
    contentLabel: Boolean = false,
    contentDetail: Boolean = false,
) {
    val text = if (destructive) TortugaColors.Error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = {
            Column {
                val labelStyle = LocalTextStyle.current
                Text(label, style = if (contentLabel) labelStyle.contentDirection() else labelStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail != null) {
                    val detailStyle = MaterialTheme.typography.bodySmall
                    Text(
                        detail,
                        style = if (contentDetail) detailStyle.contentDirection() else detailStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        onClick = onClick,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        trailingIcon = if (selected) ({ Icon(TortugaIcons.Check, contentDescription = null) }) else null,
        // Over the item's own role: semantics outside the click handling are applied last.
        modifier = modifier.semantics {
            if (choice) role = Role.RadioButton
            if (choice || selected) this.selected = selected
        },
        colors = MenuDefaults.itemColors(textColor = text, leadingIconColor = text, trailingIconColor = text),
    )
}

/**
 * One option in a [TortugaSheet]'s list: a full-width row, no fill until hovered or focused (then the primary fill,
 * which alone shows focus: a row doesn't grow), [icon] before [label] with its [detail] line, then [badge], and a check
 * when [selected]; [destructive] in the error colour while not focused. A screen reader hears a [choice] as a radio
 * button, selected or not; an action (an item's menu on TV) as a button, "selected" on the one in effect.
 * [onLongClick] is the row's second action ([TortugaSurface]'s, named [onLongClickLabel]). [contentLabel],
 * [contentDetail]: whether [label], [detail] is content text (an addon's or a person's), which takes its direction
 * from its first letter ([contentDirection]).
 */
@Composable
fun SheetOption(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    detail: String? = null,
    destructive: Boolean = false,
    icon: ImageVector? = null,
    choice: Boolean = true,
    contentLabel: Boolean = false,
    contentDetail: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    badge: (@Composable () -> Unit)? = null,
) {
    TortugaSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = LocalTortugaDimens.current.controlHeight),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        contentColor = if (destructive) TortugaColors.Error else MaterialTheme.colorScheme.onSurface,
        selected = if (choice || selected) selected else null,
        role = if (choice) Role.RadioButton else Role.Button,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        contentAlignment = Alignment.CenterStart,
        grows = false,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                val labelStyle = MaterialTheme.typography.bodyLarge
                Text(label, style = if (contentLabel) labelStyle.contentDirection() else labelStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail != null) {
                    val detailStyle = MaterialTheme.typography.bodySmall
                    Text(
                        detail,
                        style = if (contentDetail) detailStyle.contentDirection() else detailStyle,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            badge?.invoke()
            if (selected) Icon(TortugaIcons.Check, contentDescription = null, Modifier.size(20.dp))
        }
    }
}

/**
 * One option of a [ChoiceMenu]: its [label], a [detail] line (a catalog's addon), and whether it is [selected].
 * [contentLabel], [contentDetail]: whether [label], [detail] is content text (an addon's or a person's), which takes
 * its direction from its first letter ([contentDirection]).
 */
@Immutable
class MenuChoice(
    val label: String,
    val selected: Boolean,
    val detail: String? = null,
    val contentLabel: Boolean = false,
    val contentDetail: Boolean = false,
)

/**
 * The options of a choice (a sort order, a catalog, a genre, a setting's value), opened from an anchor: a
 * [TortugaDropdownMenu] at it (place both in a Box), on TV a [TortugaSheet] titled [title] with the selected option
 * focused, which a remote moves through. Picking an option calls [onPick] and closes it, unless [closeOnPick] is false
 * (a multi-select filter's options are toggled). Harness names: `[probe].option.<i>`. [contentTitle]: whether [title]
 * is content text (an addon's or a person's), which takes its direction from its first letter ([contentDirection]).
 */
@Composable
fun ChoiceMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    title: String,
    choices: List<MenuChoice>,
    onPick: (Int) -> Unit,
    probe: String,
    closeOnPick: Boolean = true,
    contentTitle: Boolean = false,
) {
    val pick = { index: Int ->
        onPick(index)
        if (closeOnPick) onDismiss()
    }
    val selectedIndex = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
    if (LocalWindowLayout.current.formFactor == FormFactor.TV) {
        val initial = remember { FocusRequester() }
        TortugaSheet(onDismiss = onDismiss, title = title, visible = expanded, initialFocus = initial, probe = "$probe.sheet", contentTitle = contentTitle) {
            LazyColumn(
                Modifier.weight(1f, fill = false),
                state = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0)),
                contentPadding = PaddingValues(vertical = LocalTortugaDimens.current.focusReach),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(choices) { index, choice ->
                    SheetOption(
                        choice.label,
                        onClick = { pick(index) },
                        selected = choice.selected,
                        detail = choice.detail,
                        contentLabel = choice.contentLabel,
                        contentDetail = choice.contentDetail,
                        modifier = Modifier
                            .then(if (index == selectedIndex) Modifier.focusRequester(initial) else Modifier)
                            .layoutProbe("$probe.option.$index"),
                    )
                }
            }
        }
        return
    }
    TortugaDropdownMenu(expanded = expanded, onDismiss = onDismiss) {
        val initial = remember { FocusRequester() }
        choices.forEachIndexed { index, choice ->
            TortugaMenuItem(
                choice.label,
                onClick = { pick(index) },
                selected = choice.selected,
                choice = true,
                detail = choice.detail,
                contentLabel = choice.contentLabel,
                contentDetail = choice.contentDetail,
                modifier = Modifier
                    .then(if (index == selectedIndex) Modifier.focusRequester(initial) else Modifier)
                    .layoutProbe("$probe.option.$index"),
            )
        }
        // The menu's content is composed each time it opens: focus on the selected option, for keyboards.
        if (choices.isNotEmpty()) LaunchedEffect(Unit) { initial.requestFocus() }
    }
}

/**
 * A dropdown chip: a [TortugaChip] showing [chip]'s text (in the primary fill while it narrows what is shown) with the
 * dropdown arrow, named for screen readers by [chip]'s spoken text, opening its [choices] in a [ChoiceMenu] (a menu; on
 * TV a side panel titled with [chip]'s name, the selected option focused). [leadingIcon] with [iconOnly] is an
 * icon-only chip (where the row has no room for its text). [modifier] goes to the chip. Harness names: [probe],
 * `[probe].option.<i>`. [contentLabel], [contentTitle]: whether the text, the name is content text (an addon's or a
 * person's), which takes its direction from its first letter ([contentDirection]).
 */
@Composable
fun MenuChip(
    chip: FilterChip,
    choices: List<MenuChoice>,
    onPick: (Int) -> Unit,
    probe: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    iconOnly: Boolean = false,
    closeOnPick: Boolean = true,
    contentLabel: Boolean = false,
    contentTitle: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TortugaChip(
            if (iconOnly) "" else chip.text,
            selected = chip.narrowed,
            onClick = { open = true },
            leadingIcon = leadingIcon,
            dropdown = true,
            modifier = modifier.semantics { contentDescription = chip.spoken }.layoutProbe(probe),
            contentLabel = contentLabel,
        )
        ChoiceMenu(
            expanded = open,
            onDismiss = { open = false },
            title = chip.name,
            choices = choices,
            onPick = onPick,
            probe = probe,
            closeOnPick = closeOnPick,
            contentTitle = contentTitle,
        )
    }
}
