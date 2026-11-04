package com.edde746.tortuga.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edde746.tortuga.ui.focus.focusGrowth
import com.edde746.tortuga.ui.focus.focusInset
import com.edde746.tortuga.ui.focus.isFocusVisible
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection

/**
 * The colours a [TortugaSurface] draws with in its current state. [fill] is read while drawing ([surfaceFill]), so its
 * animation only redraws; [content] switches at once, as the content reads it while composing.
 */
private class SurfaceState(val fill: State<Color>, val content: Color)

/**
 * The fill and content colours of a control resting in [color] / [contentColor]: focused (keyboard, D-pad, remote;
 * while focus shows, [isFocusVisible]) it turns the primary fill with black content; hovered or pressed a step
 * lighter; disabled faint. A control resting in the primary fill keeps it (its focus shows as a ring, [focusGrowth]).
 * One resting transparent or translucent (an icon button over a picture or the video) stays see-through hovered or
 * pressed: [TortugaColors.HoverTint] over its rest, never a solid surface.
 */
@Composable
private fun surfaceState(interactions: MutableInteractionSource, color: Color, contentColor: Color, enabled: Boolean): SurfaceState {
    val focusedRaw by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val focused = focusedRaw && isFocusVisible()
    val primary = MaterialTheme.colorScheme.primary
    val restsPrimary = color == primary
    val target = when {
        !enabled -> if (color.alpha == 0f) color else TortugaColors.SurfaceHigh.copy(alpha = 0.6f)
        focused && !restsPrimary -> primary
        pressed || hovered -> when {
            restsPrimary -> lerp(primary, TortugaColors.OnPrimary, 0.12f)
            color.alpha < 1f -> TortugaColors.HoverTint.compositeOver(color)
            else -> TortugaColors.SurfaceHighest
        }
        else -> color
    }
    val content = when {
        !enabled -> TortugaColors.TextFaint
        focused && !restsPrimary -> TortugaColors.OnPrimary
        else -> contentColor
    }
    return SurfaceState(animateColorAsState(target, tween(durationMillis = 120), label = "fill"), content)
}

/** [fill] in [shape] behind the content, read while drawing. */
private fun Modifier.surfaceFill(fill: State<Color>, shape: Shape): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    onDrawBehind {
        val color = fill.value
        if (color.alpha > 0f) drawOutline(outline, color)
    }
}

/**
 * The one focusable, clickable surface every control is made of (buttons, chips, rows, icon buttons): a [color] fill in
 * [shape], never an outline; transparent over a picture or the video. Focus (keyboard, D-pad, remote) turns it the
 * primary fill with black content and, on TV, grows it unless it is a row of a list ([grows] = false: a stream, a
 * setting, a menu option, an addon, where the fill alone shows focus); with [focusInset] (a chip in a row of chips) it
 * keeps its size everywhere and marks focus with a ring inside its edge as well. Hover and press lighten it, a
 * see-through one with a tint ([surfaceState]). Content reads [LocalContentColor], so text and icons follow the state
 * (secondary text: `LocalContentColor.current.copy(alpha = 0.6f)`). An overlay opened from it gives focus back to it
 * ([overlayOpener]). [onLongClick] is a long press (touch) or a held Select (a remote), which screen readers offer as an
 * action named [onLongClickLabel]. With [hitTarget] (a control drawn smaller than a finger's least target: an icon
 * button, a one-line button) it takes taps on a touch screen in a square of at least 48 dp centred on it ([HitTarget]),
 * keeping its drawn size and place.
 */
@Composable
fun TortugaSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = TortugaColors.SurfaceHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    role: Role = Role.Button,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    grows: Boolean = true,
    focusInset: Boolean = false,
    hitTarget: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val state = surfaceState(interactions, color, contentColor, enabled)
    val target = if (hitTarget) rememberHitTarget() else null
    val restsPrimary = color == MaterialTheme.colorScheme.primary
    Box(
        modifier
            .then(
                when {
                    focusInset -> Modifier.focusInset(shape)
                    grows || restsPrimary -> Modifier.focusGrowth(shape, ring = restsPrimary, grows = grows)
                    else -> Modifier
                },
            )
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier)
            .overlayOpener()
            .then(target?.bounds ?: Modifier)
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                role = role,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .then(target?.square ?: Modifier)
            .clip(shape)
            .surfaceFill(state.fill, shape),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalContentColor provides state.content) { content() }
    }
}

/**
 * A [TortugaSurface] that toggles [checked] (a switch row, a tick box row): the whole surface is the control, and as a
 * row of a list its fill alone shows focus (no growth on TV). [contentColor] is its content's colour at rest (a control
 * inside a row passes the row's, black on a focused row). An overlay opened from it gives focus back to it
 * ([overlayOpener]).
 */
@Composable
fun TortugaToggleSurface(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = TortugaColors.SurfaceHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    role: Role = Role.Switch,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val state = surfaceState(interactions, color, contentColor, enabled)
    Box(
        modifier
            .clip(shape)
            .surfaceFill(state.fill, shape)
            .overlayOpener()
            .toggleable(checked, interactions, indication = null, enabled = enabled, role = role, onValueChange = onCheckedChange),
    ) {
        CompositionLocalProvider(LocalContentColor provides state.content) { content() }
    }
}

/**
 * The switch at the end of a toggle row ([TortugaToggleSurface]): it shows the state, the row is the control. On a
 * light fill (the focused row: its content colour turns dark) its colours invert, so its track never vanishes into
 * the row.
 */
@Composable
fun RowSwitch(checked: Boolean, enabled: Boolean = true) {
    val colors = if (LocalContentColor.current.luminance() < 0.5f) {
        SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedTrackColor = TortugaColors.OnPrimary,
            uncheckedThumbColor = TortugaColors.OnPrimary,
            uncheckedTrackColor = Color.Transparent,
            uncheckedBorderColor = TortugaColors.OnPrimary,
        )
    } else {
        SwitchDefaults.colors()
    }
    Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = colors)
}

/**
 * The tick box at the end of a tick box row ([TortugaToggleSurface] with [Role.Checkbox]): it shows the state, the row
 * is the control. On a light fill (the focused row) its colours invert, as [RowSwitch]'s do.
 */
@Composable
fun RowCheckbox(checked: Boolean) {
    val colors = if (LocalContentColor.current.luminance() < 0.5f) {
        CheckboxDefaults.colors(
            checkedColor = TortugaColors.OnPrimary,
            uncheckedColor = TortugaColors.OnPrimary,
            checkmarkColor = MaterialTheme.colorScheme.primary,
        )
    } else {
        CheckboxDefaults.colors()
    }
    Checkbox(checked = checked, onCheckedChange = null, colors = colors)
}

/** How prominent a [TortugaButton] is. */
enum class ButtonStyle {
    /** The primary fill with black content: the screen's main action. */
    PRIMARY,

    /** A solid surfaceHigh fill: every other action. */
    SECONDARY,

    /** No fill until hovered or focused: a quiet action ("See all", Cancel). */
    TEXT,

    /** A translucent fill ([TortugaColors.PictureButton]): over a picture (Home's header over its backdrop). */
    OVER_PICTURE,

    /** A secondary button in the error colour: an action that deletes or signs out. */
    DESTRUCTIVE,
}

/**
 * A pill button: [text] with an optional leading [icon] (or [logo], a mark drawn in its own colours: a sign-in
 * provider's), [LocalTortugaDimens]' `controlHeight` tall, in [style]'s fill ([fill] = true: as wide as its
 * parent). [progress] (0–1) shows as a fill inside the button (the watched part of a Resume), in a tone a step from the
 * button's own; [iconProgress] (0–1) as a ring round [icon] ([DownloadMark]: Home's Downloads). A label the button's
 * width can't hold at full size (a Resume beside the round buttons on a narrow phone) shrinks a little before it is
 * cut short; the icons keep their room. A [lead] goes before [text] and is never cut short (an episode's code before
 * its name): [text] is cut short after it, at full size. [grows] = false keeps it its size under focus on TV (a button
 * of a pair that must stay one size), as [TortugaSurface]'s. On a touch screen it takes taps in at least 48 dp of
 * height ([TortugaSurface]'s `hitTarget`). [contentText]: whether [text] is content text (an addon's or a person's),
 * which takes its direction from its first letter ([contentDirection]).
 */
@Composable
fun TortugaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.SECONDARY,
    icon: ImageVector? = null,
    logo: Painter? = null,
    enabled: Boolean = true,
    fill: Boolean = false,
    progress: Float? = null,
    trailingIcon: ImageVector? = null,
    grows: Boolean = true,
    lead: String? = null,
    iconProgress: Float? = null,
    contentText: Boolean = false,
) {
    val dimens = LocalTortugaDimens.current
    val (color, content) = when (style) {
        ButtonStyle.PRIMARY -> MaterialTheme.colorScheme.primary to TortugaColors.OnPrimary
        ButtonStyle.SECONDARY -> TortugaColors.SurfaceHigh to MaterialTheme.colorScheme.onSurface
        ButtonStyle.TEXT -> Color.Transparent to MaterialTheme.colorScheme.onSurface
        ButtonStyle.OVER_PICTURE -> TortugaColors.PictureButton to MaterialTheme.colorScheme.onSurface
        ButtonStyle.DESTRUCTIVE -> TortugaColors.SurfaceHigh to TortugaColors.Error
    }
    TortugaSurface(
        onClick = onClick,
        modifier = modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).heightIn(min = dimens.controlHeight),
        shape = CircleShape,
        color = color,
        contentColor = content,
        enabled = enabled,
        contentAlignment = Alignment.Center,
        grows = grows,
        hitTarget = true,
    ) {
        if (progress != null && progress > 0f) {
            // The watched part, from the button's leading edge (right to left in Arabic): the button's content colour
            // over its fill, faintly.
            val tone = LocalContentColor.current.copy(alpha = 0.16f)
            val watched = progress.coerceIn(0f, 1f)
            Box(
                Modifier.matchParentSize().drawBehind {
                    val width = size.width * watched
                    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                    drawRect(tone, topLeft = Offset(left, 0f), size = Size(width, size.height))
                },
            )
        }
        Row(
            Modifier.padding(start = if (icon != null || logo != null) 16.dp else ButtonLabelInset, end = if (trailingIcon != null) 14.dp else ButtonLabelInset, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (logo != null) {
                Icon(logo, contentDescription = null, Modifier.size(20.dp), tint = Color.Unspecified)
            } else if (icon != null) {
                if (iconProgress != null) DownloadMark(icon, iconProgress, size = 20.dp) else Icon(icon, contentDescription = null, Modifier.size(20.dp))
            }
            val label = MaterialTheme.typography.labelLarge
            val textStyle = if (contentText) label.contentDirection() else label
            if (lead == null) {
                Text(
                    text,
                    Modifier.weight(1f, fill = false),
                    autoSize = TextAutoSize.StepBased(minFontSize = MIN_LABEL_SIZE, maxFontSize = label.fontSize, stepSize = 0.5.sp),
                    style = textStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(Modifier.weight(1f, fill = false)) {
                    Text(lead, style = label, maxLines = 1, softWrap = false)
                    Text(text, Modifier.weight(1f, fill = false), style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailingIcon != null) Icon(trailingIcon, contentDescription = null, Modifier.size(20.dp))
        }
    }
}

/** The smallest a [TortugaButton]'s label shrinks to before it is cut short. */
internal val MIN_LABEL_SIZE = 11.sp

/**
 * How far inside a [TortugaButton] without icons its label starts (and ends): a text button lines its label up with
 * the text around it by standing this far out.
 */
internal val ButtonLabelInset = 20.dp

/** How a [TortugaIconButton] rests. */
enum class IconButtonStyle {
    /** A round surfaceHigh fill. */
    FILLED,

    /** The primary fill with black content (the one emphasised action). */
    PRIMARY,

    /** No fill until hovered or focused (inside a row or a field). */
    PLAIN,

    /** A translucent circle ([TortugaColors.PictureButton]): over a picture (Detail's back button and actions). */
    OVER_PICTURE,
}

/**
 * A round icon button, [LocalTortugaDimens]' `iconButtonSize` wide ([size] to override), with [contentDescription] for
 * screen readers. Focus turns it the primary fill (and on TV grows it), as every control; with [focusInset] (a round
 * button in a row of chips, as tall as they are) it keeps its size and marks focus with a ring inside its edge, as the
 * chips do. On a touch screen it takes taps in a square of at least 48 dp ([TortugaSurface]'s `hitTarget`).
 */
@Composable
fun TortugaIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: IconButtonStyle = IconButtonStyle.FILLED,
    enabled: Boolean = true,
    size: Dp = LocalTortugaDimens.current.iconButtonSize,
    selected: Boolean? = null,
    focusInset: Boolean = false,
) {
    val (color, content) = when (style) {
        IconButtonStyle.FILLED -> TortugaColors.SurfaceHigh to MaterialTheme.colorScheme.onSurface
        IconButtonStyle.PRIMARY -> MaterialTheme.colorScheme.primary to TortugaColors.OnPrimary
        // Inside a row or field: the row's content colour (black on a focused row).
        IconButtonStyle.PLAIN -> Color.Transparent to LocalContentColor.current
        IconButtonStyle.OVER_PICTURE -> TortugaColors.PictureButton to MaterialTheme.colorScheme.onSurface
    }
    TortugaSurface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = color,
        contentColor = content,
        enabled = enabled,
        selected = selected,
        focusInset = focusInset,
        contentAlignment = Alignment.Center,
        hitTarget = true,
    ) {
        Icon(icon, contentDescription = contentDescription, Modifier.size(size * 0.5f))
    }
}

/**
 * A solid pill chip ([LocalTortugaDimens]' `chipHeight`): unselected on surfaceHigh, [selected] in the primary fill
 * with black text; no outline. With [checkmark] (the Streams list's row of addons) a selected chip keeps the
 * unselected fill and shows a leading check mark and a semibold label instead, and the chip is as wide either way, the
 * narrower look centred ([widestLook]), so a pick moves no chip of its row. Focused it keeps its size and draws
 * nothing past its edge, so a row of chips keeps one height: the primary fill and a ring inside the edge, which marks a
 * selected chip's focus too ([TortugaSurface]'s `focusInset`). A [dropdown] chip opens a menu: it ends in the dropdown
 * arrow, and a screen reader hears a dropdown list ("selected" while it narrows what is shown) where the others are
 * tabs. An empty [label] leaves the icons alone (a menu chip where the row has no room for its text; name it for
 * screen readers through [modifier]). A chip given less than its width (a row that fits its chips to the window) cuts
 * its label short; the icons keep their room. [onLongClick] is a long press (touch) or a held Select (a remote), for
 * the chip's menu. On a touch screen it takes taps in at least 48 dp of height ([TortugaSurface]'s `hitTarget`).
 * [contentLabel]: whether [label] is content text (an addon's or a person's), which takes its direction from its first
 * letter ([contentDirection]). [iconProbe]: the leading icon's harness name ([layoutProbe]), its bounds alone.
 */
@Composable
fun TortugaChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dropdown: Boolean = false,
    leadingIcon: ImageVector? = null,
    onLongClick: (() -> Unit)? = null,
    checkmark: Boolean = false,
    contentLabel: Boolean = false,
    iconProbe: String? = null,
) {
    val dimens = LocalTortugaDimens.current
    val filled = selected && !checkmark
    val checked = selected && checkmark
    val icon = if (checked) TortugaIcons.Check else leadingIcon
    TortugaSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = dimens.chipHeight),
        shape = CircleShape,
        color = if (filled) MaterialTheme.colorScheme.primary else TortugaColors.SurfaceHigh,
        contentColor = if (filled) TortugaColors.OnPrimary else MaterialTheme.colorScheme.onSurface,
        enabled = enabled,
        selected = if (dropdown && !selected) null else selected,
        role = if (dropdown) Role.DropdownList else Role.Tab,
        onLongClick = onLongClick,
        contentAlignment = Alignment.Center,
        focusInset = true,
        hitTarget = true,
    ) {
        val tv = dimens.focusGrows
        val style = if (tv) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge
        val checkedStyle = style.copy(fontWeight = FontWeight.SemiBold)
        val start = if (icon != null) CHIP_ICON_START else if (tv) 18.dp else 14.dp
        val end = if (dropdown) 8.dp else if (tv) 18.dp else 14.dp
        val measurer = if (checkmark && label.isNotEmpty()) rememberTextMeasurer() else null
        val density = LocalDensity.current
        Row(
            Modifier
                .then(
                    if (measurer == null) {
                        Modifier
                    } else {
                        with(density) {
                            // Beside the label checked: its start, the check and the gap; unchecked: its start (and a
                            // leading icon and the gap); then its end. Each rounded as this row rounds it.
                            val checkedStart = CHIP_ICON_START.roundToPx() + CHIP_ICON.roundToPx() + CHIP_ICON_GAP.roundToPx()
                            val plainStart = if (leadingIcon != null) checkedStart else (if (tv) 18.dp else 14.dp).roundToPx()
                            Modifier.widestLook(measurer, label, style, plainStart + end.roundToPx(), checkedStyle, checkedStart + end.roundToPx())
                        }
                    },
                )
                .padding(start = start, end = end, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(CHIP_ICON_GAP, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, Modifier.size(CHIP_ICON).then(iconProbe?.let { Modifier.layoutProbe(it, reportsFocus = false) } ?: Modifier))
            if (label.isNotEmpty()) {
                val labelStyle = if (checked) checkedStyle else style
                Text(
                    label,
                    // The row hands its width out in order: the label leaves the trailing icon its room.
                    Modifier.layout { measurable, constraints ->
                        val reserve = if (dropdown) (CHIP_TRAILING_ICON + CHIP_ICON_GAP).roundToPx() else 0
                        val room = if (constraints.hasBoundedWidth) (constraints.maxWidth - reserve).coerceAtLeast(0) else constraints.maxWidth
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = room))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
                    style = if (contentLabel) labelStyle.contentDirection() else labelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (dropdown) Icon(TortugaIcons.ArrowDropDown, contentDescription = null, Modifier.size(CHIP_TRAILING_ICON))
        }
    }
}

/**
 * At least as wide as the wider of [label]'s two looks: set in [plain] with [plainBeside] px beside it (padding and
 * icons) and in [selected] with [selectedBeside] px, each on one line, cut short where the room left beside it ends. A
 * control whose label's look follows its selection keeps one width, its content centred while it is narrower.
 */
internal fun Modifier.widestLook(measurer: TextMeasurer, label: String, plain: TextStyle, plainBeside: Int, selected: TextStyle, selectedBeside: Int): Modifier =
    layout { measurable, constraints ->
        fun width(style: TextStyle, beside: Int): Int {
            val room = if (constraints.hasBoundedWidth) (constraints.maxWidth - beside).coerceAtLeast(0) else Constraints.Infinity
            val text = measurer.measure(label, style, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = room), layoutDirection = layoutDirection, density = this)
            return beside + text.size.width
        }
        val least = maxOf(width(plain, plainBeside), width(selected, selectedBeside))
        val placeable = measurable.measure(constraints.copy(minWidth = least.coerceIn(constraints.minWidth, constraints.maxWidth)))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

private val CHIP_ICON_START = 10.dp
private val CHIP_ICON = 18.dp
private val CHIP_TRAILING_ICON = 20.dp
private val CHIP_ICON_GAP = 6.dp
