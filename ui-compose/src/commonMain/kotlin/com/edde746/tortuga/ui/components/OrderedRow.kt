package com.edde746.tortuga.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe

/**
 * What can be done to one row of a list the user orders (Home's rows, addons, preferred languages): [moveUp] and
 * [moveDown], offered while [canMoveUp] / [canMoveDown]; [remove] where it can be removed; and [more] entries for its
 * menu (an addon's Configure). Each entry's probe names it in the menu (`up`, `down`, `remove`). [moveUpName] and
 * [moveDownName] are what screen readers call TV's ↑ and ↓ ("Move Cinemeta up").
 */
@Immutable
class OrderedRowActions(
    val moveUp: ItemMenuEntry,
    val moveDown: ItemMenuEntry,
    val moveUpName: String,
    val moveDownName: String,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val remove: ItemMenuEntry?,
    val more: List<ItemMenuEntry> = emptyList(),
)

/**
 * A row of a list the user orders, [index] its place there: [onClick] (null: a click opens the row's menu). Its menu
 * holds [actions] under [title] in this order: Move up and Move down where the row can go (not on TV), the other
 * entries, Remove. Screen readers get the same entries, Move up and Move down included, as the row's actions. As a
 * row of a list its focus is the fill alone. [control] is a control of the row's own (an addon's switch), its own
 * focus target.
 *
 * Phones, tablets and desktop: [content] starts with the list's drag handle (where the list can be dragged); [control]
 * follows it, then ⋮ at the row's end opens the menu, as do a right click and the Menu key. A long press opens nothing,
 * as on iPhone and iPad. Delete runs Remove. With [controlsBelow] (a row too narrow for its text beside its controls)
 * [content] takes the row's whole width, and the controls are a line of their own under it: [controlsBelow]'s own
 * content at its start (the drag handle), [control] and ⋮ at its end.
 *
 * TV (a D-pad, no pointer): [control] and round ↑ and ↓ buttons follow the row beside it, in that order, so Right
 * from the row reaches them one by one ([MovableRow]); Select (where [onClick] is null), the Menu key or a held
 * Select opens the menu with the rest.
 *
 * [modifier] places the row (its reorder offset), [surfaceModifier] goes on its focus target (focus memory, its
 * probe). [probe] names the ⋮ `<probe>.more`, [moreLabel] is its spoken name ("More actions for Addon A"); the
 * menu's entries are `<probe>.menu.<entry>` and TV's arrows `<probe>.up` and `<probe>.down`. [contentTitle]: whether
 * [title] is content text (an addon's or a person's), which takes its direction from its first letter
 * ([com.edde746.tortuga.ui.theme.contentDirection]).
 */
@Composable
fun OrderedRow(
    actions: OrderedRowActions,
    title: String,
    moreLabel: String,
    probe: String,
    index: Int,
    shape: Shape,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    surfaceModifier: Modifier = Modifier,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 10.dp,
    contentTitle: Boolean = false,
    control: (@Composable () -> Unit)? = null,
    controlsBelow: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    var menu by remember { mutableStateOf(false) }
    val remove = actions.remove
    val moves = listOfNotNull(actions.moveUp.takeIf { actions.canMoveUp }, actions.moveDown.takeIf { actions.canMoveDown })
    val all = moves + actions.more + listOfNotNull(remove)
    // On TV the arrows move the row: its menu keeps the rest.
    val entries = if (tv) actions.more + listOfNotNull(remove) else all
    val accessibility = all.map { entry ->
        CustomAccessibilityAction(entry.label) {
            entry.onClick()
            true
        }
    }
    val more = !tv && entries.isNotEmpty()
    val surface = @Composable { placement: Modifier ->
        TortugaSurface(
            onClick = onClick ?: { if (entries.isNotEmpty()) menu = true },
            modifier = placement
                .fillMaxWidth()
                .then(surfaceModifier)
                .semantics { customActions = accessibility }
                .menuGestures { if (entries.isNotEmpty()) menu = true }
                .onKeyEvent { event ->
                    val delete = event.type == KeyEventType.KeyDown && event.key == Key.Delete && remove != null
                    if (delete) remove?.onClick?.invoke()
                    delete
                },
            shape = shape,
            onLongClick = if (tv && entries.isNotEmpty()) ({ menu = true }) else null,
            grows = false,
        ) {
            val padding = Modifier.padding(start = horizontalPadding, end = if (more) 4.dp else horizontalPadding, top = verticalPadding)
            val moreButton = @Composable {
                TortugaIconButton(TortugaIcons.MoreVert, moreLabel, { menu = true }, Modifier.layoutProbe("$probe.more"), style = IconButtonStyle.PLAIN)
            }
            if (controlsBelow != null && !tv) {
                Column(padding) {
                    // The text ends the row's padding from its edge, as the ⋮ beside it would.
                    Row(Modifier.fillMaxWidth().padding(end = if (more) horizontalPadding - 4.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        content()
                    }
                    // Empty (no handle, switch or menu), the line is the row's bottom padding.
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = verticalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        controlsBelow()
                        Spacer(Modifier.weight(1f))
                        control?.invoke()
                        if (more) moreButton()
                    }
                }
            } else {
                Row(padding.padding(bottom = verticalPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    content()
                    if (!tv) control?.invoke()
                    if (more) moreButton()
                }
            }
        }
    }
    Box(modifier) {
        if (tv) MovableRow(actions, probe, index, control, surface) else surface(Modifier)
        ItemMenu(expanded = menu, onDismiss = { menu = false }, entries = entries, probe = "$probe.menu", title = title, contentTitle = contentTitle)
    }
}

/**
 * TV's ordered row: [surface] (the row, in the width the rest leaves) followed by the row's [control], if any, and
 * round ↑ and ↓ buttons, [actions]' Move up and Move down (`<probe>.up`, `<probe>.down`), each its own focus target
 * and off where the row can't move that way. The list keys its rows, so the pressed arrow keeps focus as its row
 * moves, and the row is brought into view at its new [index]. An arrow turned off while it has focus (the row reached
 * an end) hands focus to the other arrow, or to the row when that one is off too (a list of one).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MovableRow(actions: OrderedRowActions, probe: String, index: Int, control: (@Composable () -> Unit)?, surface: @Composable (Modifier) -> Unit) {
    val row = remember { FocusRequester() }
    val up = remember { FocusRequester() }
    val down = remember { FocusRequester() }
    val inView = remember { BringIntoViewRequester() }
    // The arrow with focus (true: ↑), still set after it lost focus by turning off.
    var focused by remember { mutableStateOf<Boolean?>(null) }
    val canUp by rememberUpdatedState(actions.canMoveUp)
    val canDown by rememberUpdatedState(actions.canMoveDown)
    LaunchedEffect(index, actions.canMoveUp, actions.canMoveDown) {
        val next = when (focused) {
            null -> return@LaunchedEffect
            true -> if (canUp) null else if (canDown) down else row
            false -> if (canDown) null else if (canUp) up else row
        }
        if (next != null) {
            focused = null
            next.requestFocus()
        }
        // Once the row is laid out in its new place.
        withFrameNanos { }
        inView.bringIntoView()
    }
    Row(
        Modifier.bringIntoViewRequester(inView),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        surface(Modifier.weight(1f).focusRequester(row))
        control?.invoke()
        TortugaIconButton(
            TortugaIcons.ArrowUpward,
            actions.moveUpName,
            actions.moveUp.onClick,
            Modifier
                .focusRequester(up)
                // Losing focus while still on: focus went elsewhere.
                .onFocusChanged { if (it.isFocused) focused = true else if (focused == true && canUp) focused = null }
                .layoutProbe("$probe.up"),
            enabled = actions.canMoveUp,
        )
        TortugaIconButton(
            TortugaIcons.ArrowDownward,
            actions.moveDownName,
            actions.moveDown.onClick,
            Modifier
                .focusRequester(down)
                .onFocusChanged { if (it.isFocused) focused = false else if (focused == false && canDown) focused = null }
                .layoutProbe("$probe.down"),
            enabled = actions.canMoveDown,
        )
    }
}
