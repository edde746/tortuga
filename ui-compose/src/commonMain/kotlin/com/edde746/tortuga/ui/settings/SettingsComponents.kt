package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.edde746.tortuga.presentation.settings.SettingChoiceRow
import com.edde746.tortuga.presentation.settings.SettingStepperRow
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.ui.components.Chevron
import com.edde746.tortuga.ui.components.ChoiceMenu
import com.edde746.tortuga.ui.components.GroupGap
import com.edde746.tortuga.ui.components.GroupHeader
import com.edde746.tortuga.ui.components.GroupSpacing
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.HitTarget
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.MenuChoice
import com.edde746.tortuga.ui.components.RowCheckbox
import com.edde746.tortuga.ui.components.RowSwitch
import com.edde746.tortuga.ui.components.TextAndTrailing
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaToggleSurface
import com.edde746.tortuga.ui.components.contentColumn
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.components.overlayOpener
import com.edde746.tortuga.ui.components.rememberHitTarget
import com.edde746.tortuga.ui.focus.focusGrowth
import com.edde746.tortuga.ui.focus.isFocusVisible
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection
import kotlin.math.abs

/** A page's explanation under its title: what the settings apply to. */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = TortugaColors.TextMuted,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * A note among settings rows, not a control (a warning beside the action it concerns): [icon] and [text] in the rows'
 * subtitle style, the icon at the text's size, centred where a [GroupRow]'s icon sits, and the text where its title
 * starts.
 */
@Composable
internal fun NoteLine(text: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current.copy(alpha = 0.6f)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, Modifier.padding(horizontal = 3.dp).size(16.dp), tint = color)
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * One row of a settings group, drawn in the [Shape] its place in the group gives it ([settingsGroup]); [key] keeps
 * the lazy list's item.
 */
internal class SettingsItem(val key: String, val content: @Composable (Shape) -> Unit)

/**
 * A group of settings rows (the ones present: a device lacking a feature has no row for it) under an optional
 * [header], connected ([groupShape]), [GroupSpacing] below what is above it: the header's top padding, or a space as
 * tall without one, so two groups never read as one.
 */
internal fun LazyListScope.settingsGroup(key: String, header: String?, rows: List<SettingsItem?>) {
    val present = rows.filterNotNull()
    if (present.isEmpty()) return
    if (header != null) item(key = "$key.header") { GroupHeader(header) } else item(key = "$key.space") { Spacer(Modifier.height(GroupSpacing)) }
    present.forEachIndexed { index, row -> item(key = row.key) { row.content(groupShape(index, present.size)) } }
}

/**
 * A settings list: the pane's rows in its [contentColumn], centred under the pane's heading, with room at every edge
 * for a focused control's growth (TV; the rows themselves don't grow); scrolled by [state]. Harness name
 * `settings.list`.
 */
@Composable
internal fun SettingsList(modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(), content: LazyListScope.() -> Unit) {
    val dimens = LocalTortugaDimens.current
    LazyColumn(
        modifier.contentColumn().fillMaxSize().layoutProbe("settings.list", reportsFocus = false),
        state = state,
        contentPadding = PaddingValues(start = dimens.screenPadding, end = dimens.screenPadding, top = dimens.focusReach, bottom = dimens.sectionSpacing),
        verticalArrangement = Arrangement.spacedBy(GroupGap),
        content = content,
    )
}

/** A row that opens something (a list, a page): [title], an optional [subtitle] and [value], and a chevron. */
@Composable
internal fun SettingsLinkRow(
    title: String,
    subtitle: String?,
    value: String?,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
) {
    GroupRow(title, onClick, modifier, shape, subtitle, value, icon, selected = selected, trailing = { Chevron() })
}

/**
 * An on/off setting: the whole row toggles it (click, Enter, the remote's OK); the switch shows the state, beside the
 * text or under it ([TextAndTrailing]); with [tickBox] (an agreement to tick) a tick box instead. [contentTitle]:
 * whether the row's title is content text (a person's: a profile's name), which takes its direction from its first
 * letter ([contentDirection]). Not [SettingToggleRow.enabled], it neither toggles nor takes focus, and shows dimmed.
 */
@Composable
internal fun SettingToggleItem(
    row: SettingToggleRow,
    onChange: (Boolean) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    contentTitle: Boolean = false,
    tickBox: Boolean = false,
) {
    TortugaToggleSurface(
        row.checked, onChange, modifier.fillMaxWidth(), shape = shape, enabled = row.enabled,
        role = if (tickBox) Role.Checkbox else Role.Switch,
    ) {
        TextAndTrailing(
            text = { RowText(row.title, row.subtitle, contentTitle = contentTitle) },
            trailing = { if (tickBox) RowCheckbox(row.checked) else RowSwitch(row.checked, row.enabled) },
            modifier = Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        )
    }
}

/**
 * Title and optional subtitle of a settings row, in the row's content colour, each wrapping in full. [contentTitle],
 * [contentSubtitle]: whether [title], [subtitle] is content text (an addon's or a person's), which takes its direction
 * from its first letter ([contentDirection]).
 */
@Composable
internal fun RowText(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    contentTitle: Boolean = false,
    contentSubtitle: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val titleStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
        Text(title, style = if (contentTitle) titleStyle.contentDirection() else titleStyle)
        if (subtitle != null) {
            val subtitleStyle = MaterialTheme.typography.bodySmall
            Text(
                subtitle,
                style = if (contentSubtitle) subtitleStyle.contentDirection() else subtitleStyle,
                color = LocalContentColor.current.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * A bounded whole-number setting: a stepper at the row's end (the value between − and +; the value itself steps with
 * the D-pad's left and right while focused); [onStep] asks the store for one step up or down. Nothing is typed.
 *
 * Up and Down from the rows around land on the stepper, its row's text and stepper being one focus group
 * ([TextAndTrailing]): alone at the row's end, Compose's search would pass over the stepper's controls for a full-width
 * row beyond (a remote could never reach a stepper between two switches).
 *
 * Harness names: `[probe].decrease`, `.value`, `.increase`.
 */
@Composable
internal fun SettingStepperItem(row: SettingStepperRow, onStep: (up: Boolean) -> Unit, probe: String, shape: Shape, modifier: Modifier = Modifier) {
    GroupRow(row.title, onClick = null, modifier = modifier, shape = shape, subtitle = row.subtitle) {
        Row(
            Modifier.background(TortugaColors.SurfaceHighest, CircleShape).padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TortugaIconButton(
                TortugaIcons.Remove,
                contentDescription = row.decreaseLabel,
                onClick = { onStep(false) },
                modifier = Modifier.layoutProbe("$probe.decrease"),
                style = IconButtonStyle.PLAIN,
                enabled = row.canDecrease,
                size = 36.dp,
            )
            StepperValue(row.title, row.valueLabel, onStep = { up -> if (if (up) row.canIncrease else row.canDecrease) onStep(up) }, probe = "$probe.value")
            TortugaIconButton(
                TortugaIcons.Add,
                contentDescription = row.increaseLabel,
                onClick = { onStep(true) },
                modifier = Modifier.layoutProbe("$probe.increase"),
                style = IconButtonStyle.PLAIN,
                enabled = row.canIncrease,
                size = 36.dp,
            )
        }
    }
}

/**
 * The stepper's value: focusable (the primary fill while focused), and the D-pad's (or arrow keys') left and right step
 * it. Screen readers hear the row's [title] with the value, [label].
 */
@Composable
private fun StepperValue(title: String, label: String, onStep: (up: Boolean) -> Unit, probe: String) {
    val interactions = remember { MutableInteractionSource() }
    val focusedRaw by interactions.collectIsFocusedAsState()
    val focused = focusedRaw && isFocusVisible()
    Box(
        Modifier
            .widthIn(min = 88.dp)
            .heightIn(min = 36.dp)
            .focusGrowth(CircleShape)
            .layoutProbe(probe)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = label
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val up = when (event.key) {
                    Key.DirectionLeft, Key.Minus, Key.NumPadSubtract -> false
                    Key.DirectionRight, Key.Plus, Key.Equals, Key.NumPadAdd -> true
                    else -> return@onPreviewKeyEvent false
                }
                onStep(up)
                true
            }
            .overlayOpener()
            .focusable(interactionSource = interactions)
            .background(if (focused) TortugaColors.Primary else Color.Transparent, CircleShape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = if (focused) TortugaColors.OnPrimary else TortugaColors.Text,
            maxLines = 1,
        )
    }
}

/** Why an input wasn't taken, under the field it belongs to. TalkBack speaks it as it shows and as it changes. */
@Composable
internal fun InlineError(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(TortugaIcons.ErrorOutline, contentDescription = null, Modifier.size(18.dp), tint = TortugaColors.Error)
        Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.Error)
    }
}

/**
 * A setting with a fixed list of values: the row shows the value and opens them as a dropdown ([ChoiceMenu]; on TV a
 * side panel), the selected one focused; picking one calls [onSelect] with its id. Not [SettingChoiceRow.enabled], it
 * neither opens nor takes focus. Harness names: [probe], `[probe].option.<i>`.
 */
@Composable
internal fun SettingChoiceItem(
    row: SettingChoiceRow,
    onSelect: (String) -> Unit,
    probe: String,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        GroupRow(
            row.title,
            onClick = { open = true },
            modifier = Modifier.layoutProbe(probe),
            shape = shape,
            subtitle = row.subtitle,
            value = row.valueLabel,
            enabled = row.enabled,
            trailing = { Icon(TortugaIcons.ArrowDropDown, contentDescription = null, tint = LocalContentColor.current.copy(alpha = 0.6f)) },
        )
        ChoiceMenu(
            expanded = open,
            onDismiss = { open = false },
            title = row.title,
            choices = row.options.map { MenuChoice(it.label, it.selected) },
            onPick = { onSelect(row.options[it].id) },
            probe = probe,
        )
    }
}

/**
 * A store's notice ("Pixel 7 is signed out.", why a change failed) until [onDismiss]; an [error] has the error symbol.
 * The banner is one focus group as wide as the list, so Up and Down from the rows and chips around it land on its
 * Close, which alone at the end would lose to a full-width row beyond, however many lines the notice takes.
 * TalkBack speaks its text as it shows and as it changes, wherever its focus is.
 */
@Composable
internal fun NoticeBanner(text: String, closeLabel: String, onDismiss: () -> Unit, modifier: Modifier = Modifier, error: Boolean = false) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .focusGroup()
            .background(TortugaColors.SurfaceHighest, MaterialTheme.shapes.large)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error) Icon(TortugaIcons.ErrorOutline, contentDescription = null, tint = TortugaColors.Error) else Icon(TortugaIcons.Info, contentDescription = null)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
        TortugaIconButton(TortugaIcons.Close, closeLabel, onDismiss, Modifier.layoutProbe("notice.close"), style = IconButtonStyle.PLAIN)
    }
}

/**
 * Dragging items of a list to a new place (desktop mouse, touch): the dragged item follows the pointer, the items it
 * passes make room, and dropping reports the item's key and its new index. Keyboard and D-pad users move items with
 * the rows' up and down buttons instead.
 */
@Stable
internal class ReorderState(private val spacingPx: Float) {
    /** The key of the item being dragged, or null. */
    var dragging: String? by mutableStateOf(null)
        private set

    private var offset by mutableFloatStateOf(0f)
    private var from = -1
    private var count = 0

    // Each item's height by its place in the list, as last measured there.
    private val heights = HashMap<Int, Int>()

    /** How far each item the dragged one passes moves to make room: the dragged item's height and the spacing. */
    private val step: Float get() = if (dragging == null) 0f else (heights[from] ?: 0) + spacingPx

    /**
     * Where the dragged item drops: past each item, one after the other, once the drag has gone half of that item's own
     * height (with the spacing) beyond the items before it, so it drops where it is drawn among rows of any height.
     */
    private val target: Int
        get() {
            val direction = if (offset > 0f) 1 else -1
            var left = abs(offset)
            var to = from
            while (to + direction in 0 until count) {
                val passed = (heights[to + direction] ?: break) + spacingPx
                if (left < passed / 2) break
                left -= passed
                to += direction
            }
            return to
        }

    internal fun measured(index: Int, height: Int) {
        heights[index] = height
    }

    internal fun start(key: String, index: Int, count: Int) {
        dragging = key
        from = index
        this.count = count
        offset = 0f
    }

    internal fun drag(dy: Float) {
        offset += dy
    }

    internal fun end(onMove: (key: String, toIndex: Int) -> Unit) {
        val key = dragging ?: return
        val to = target
        val moved = to != from
        cancel()
        if (moved) onMove(key, to)
    }

    internal fun cancel() {
        dragging = null
        offset = 0f
        from = -1
    }

    /** How far the item [key] at [index] is drawn from its place while an item is dragged. */
    internal fun shift(key: String, index: Int): Float {
        val dragged = dragging ?: return 0f
        if (key == dragged) return offset
        val to = target
        return when {
            index in (from + 1)..to -> -step
            index in to until from -> step
            else -> 0f
        }
    }
}

/** A [ReorderState] for a list whose items are [spacing] apart. */
@Composable
internal fun rememberReorderState(spacing: Dp): ReorderState {
    val px = with(LocalDensity.current) { spacing.toPx() }
    return remember(px) { ReorderState(px) }
}

/**
 * An item of a reorderable list: measured (again at each new [index]), drawn where the drag puts it, and above the
 * others while dragged.
 */
internal fun Modifier.reorderItem(state: ReorderState, key: String, index: Int): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        state.measured(index, placeable.height)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
        .zIndex(if (state.dragging == key) 1f else 0f)
        .graphicsLayer { translationY = state.shift(key, index) }

/** The drag handle of the item [key] at [index] of [count]; dropping calls [onMove] with its new index. */
internal fun Modifier.reorderHandle(state: ReorderState, key: String, index: Int, count: Int, onMove: (key: String, toIndex: Int) -> Unit): Modifier =
    pointerHoverIcon(PointerIcon.Hand).pointerInput(state, key, index, count) {
        detectDragGestures(
            onDragStart = { state.start(key, index, count) },
            onDragEnd = { state.end(onMove) },
            onDragCancel = { state.cancel() },
            onDrag = { change, amount ->
                change.consume()
                state.drag(amount.y)
            },
        )
    }

/**
 * A drag handle's icon, for mouse and touch only (not focusable); [modifier] holds its [reorderHandle]. On a touch screen
 * the drag starts anywhere in a square of at least 48 dp centred on it ([HitTarget]), the icon keeping its size and place.
 */
@Composable
internal fun DragHandleIcon(description: String, modifier: Modifier = Modifier) {
    val target = rememberHitTarget()
    Icon(
        SettingsIcons.DragIndicator,
        contentDescription = description,
        modifier = (target?.bounds ?: Modifier).then(modifier).then(target?.square ?: Modifier).padding(4.dp).size(24.dp),
        tint = TortugaColors.TextMuted,
    )
}
