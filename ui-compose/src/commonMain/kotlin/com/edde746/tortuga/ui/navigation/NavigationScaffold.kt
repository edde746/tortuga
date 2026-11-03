package com.edde746.tortuga.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.presentation.probeName
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.LocalWindowSize
import com.edde746.tortuga.ui.adaptive.NavigationKind
import com.edde746.tortuga.ui.components.LocalOverlayOpeners
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.fadeStops
import com.edde746.tortuga.ui.focus.focusEntry
import com.edde746.tortuga.ui.focus.isFocusVisible
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors

/** The width of the rail on tablets and desktop. */
private val RailWidth = 80.dp

/** The width of the TV column while it is collapsed (icons only); the screens begin right after it. */
private val TvCollapsedWidth = 72.dp

/** Between the collapsed TV column and the screen's content: room for a focused card's growth. */
private val TvContentGap = 16.dp

/** The width of the TV column while focus is in it (icons and labels, over the screen). */
private val TvExpandedWidth = 248.dp

/** The height of the [NavigationScaffold]'s footer (the profile's avatar in its round 4 dp frame). */
private val FooterHeight = 48.dp

/**
 * The rail's vertical measures: its margin above the destinations and below the footer, the room above each item's
 * pill and below its label, and the gap between items. [ROOMY] on tablets and desktop; [DENSE] where the roomy rail
 * doesn't fit the window's height (a phone in landscape).
 */
private enum class RailSpacing(val margin: Dp, val itemPadding: Dp, val itemGap: Dp) {
    ROOMY(16.dp, 6.dp, 4.dp),
    DENSE(8.dp, 2.dp, 0.dp),
}

/** A rail item's pill (behind its icon), and the gap from it to the label under it. */
private val RailPillHeight = 30.dp
private val RailLabelGap = 4.dp

/** How far the status bar's scrim reaches below the status bar. */
private val ScrimFade = 16.dp

/**
 * How dark a backdrop is at the window's start edge under the rail or the TV column ([NavigationBackdrop]): the least
 * that keeps the rail's muted labels at 4.5:1 over pure white, out to a label as wide as its item (its icons then read
 * at 5.5:1).
 */
internal const val NAVIGATION_DIM = 0.95f

/** How far from the window's start edge a backdrop's dim takes to clear, in widths of the rail or the TV column. */
private const val NAVIGATION_DIM_REACH = 3f

/**
 * The app frame around the current screen: the top-level [destinations] as a bottom bar on phones, an 80 dp rail on
 * tablets and desktop, and on TV a 72 dp column of icons that widens over the screen to show its labels while it has
 * focus. With [showNavigation] off (the player) the screen gets the whole window. Each item is a layout probe
 * `nav.<destination>` (`nav.board`, `nav.settings`) for the harness, and the screen's place beside them `nav.content`.
 * Overlays never give focus back to the navigation ([LocalOverlayOpeners]).
 *
 * The rail and the TV column are drawn over the window's start edge with no background of their own (the TV column has
 * one while open): the frame's background is under them, or a screen's backdrop, which runs under them to the window's
 * edge, the same picture as beside them, dimmed from that edge ([NavigationBackdrop]).
 *
 * On phones a top-level screen is drawn from the window's top edge, under the status bar ([statusBarOverlap] says how
 * far, so the screen starts its content that much lower); while its content is scrolled up under the status bar
 * ([ScrimWhileScrolled]) a black-to-transparent scrim behind the status bar softens the edge.
 *
 * @param footer drawn at the bottom of the rail and the TV column (the running profile's avatar and the sync status),
 *   given whether the TV column shows its labels; phones' bottom bar has none (their profile is in Settings' card).
 */
@Composable
fun NavigationScaffold(
    destinations: List<TopLevelDestination>,
    selected: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    showNavigation: Boolean,
    modifier: Modifier = Modifier,
    footer: (@Composable (expanded: Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // The content keeps one call site whatever the layout, so neither hiding the chrome (entering the player) nor a
    // change of layout (a phone turned to landscape, a window resized across a width class) recreates the screens:
    // they keep their state, and the player keeps its video surface.
    val show = showNavigation && destinations.isNotEmpty()
    val kind = LocalWindowLayout.current.navigation
    val screenPadding = LocalTortugaDimens.current.screenPadding
    val start = when {
        !show -> 0.dp
        kind == NavigationKind.RAIL -> RailWidth
        // The screens' own (overscan) padding starts under the column: their content begins a gap after it, and rows
        // scroll away under it.
        kind == NavigationKind.TV_SIDE -> (TvCollapsedWidth + TvContentGap - screenPadding).coerceAtLeast(0.dp)
        else -> 0.dp
    }
    // How far the rail or the TV column (collapsed) reaches from the window's start edge.
    val sideWidth = when {
        !show -> 0.dp
        kind == NavigationKind.RAIL -> RailWidth
        kind == NavigationKind.TV_SIDE -> TvCollapsedWidth
        else -> 0.dp
    }
    val side = remember(start, sideWidth) { if (sideWidth > 0.dp) SideNavigation(start, sideWidth) else null }
    // A phone's top-level screen reaches up under the status bar (the window pads the app below the system bars).
    val density = LocalDensity.current
    val statusBar = if (show && kind == NavigationKind.BAR) with(density) { WindowInsets.safeDrawing.getTop(this).toDp() } else 0.dp
    val overlap = remember(statusBar) { if (statusBar > 0.dp) StatusBarOverlap(statusBar) else null }
    val tvColumnEntry = remember { FocusRequester() }
    val towardColumn = if (side != null && kind == NavigationKind.TV_SIDE) Modifier.towardTvColumn(tvColumnEntry) else Modifier
    Box(modifier.fillMaxSize()) {
        // Beside the screen's place, under the navigation: the background, where no backdrop runs.
        if (start > 0.dp) Box(Modifier.width(start).fillMaxHeight().background(TortugaColors.Background))
        Column(Modifier.fillMaxSize().padding(start = start)) {
            Box(Modifier.weight(1f).extendUp(statusBar).then(towardColumn).layoutProbe("nav.content", reportsFocus = false)) {
                CompositionLocalProvider(
                    LocalStatusBarOverlap provides overlap,
                    LocalSideNavigation provides side,
                ) { content() }
            }
            if (show && kind == NavigationKind.BAR) BottomBar(destinations, selected, onSelect)
        }
        overlap?.let { StatusBarScrim(it) }
        // Focus that sat on the navigation as an overlay opened goes to the screen as it closes.
        if (side != null) {
            CompositionLocalProvider(LocalOverlayOpeners provides false) {
                when (kind) {
                    NavigationKind.RAIL -> Rail(destinations, selected, onSelect, footer)
                    NavigationKind.TV_SIDE -> TvSideNavigation(destinations, selected, onSelect, footer, tvColumnEntry)
                    NavigationKind.BAR -> Unit
                }
            }
        }
    }
}

/**
 * How far the top of a phone's top-level screen lies under the status bar, and whether its content is scrolled up
 * under it now ([ScrimWhileScrolled]).
 */
@Stable
class StatusBarOverlap internal constructor(val height: Dp) {
    internal var scrolled by mutableStateOf(false)
}

/** The current screen's [StatusBarOverlap]; null where the screen starts below the status bar. */
val LocalStatusBarOverlap = staticCompositionLocalOf<StatusBarOverlap?> { null }

/** How far the top of the current screen lies under the status bar: start its content this much lower. */
val statusBarOverlap: Dp
    @Composable get() = LocalStatusBarOverlap.current?.height ?: 0.dp

/**
 * The rail or the TV column beside the current screen: the screen's place starts [inset] from the window's start edge,
 * and the navigation covers [width] from that edge.
 */
class SideNavigation internal constructor(val inset: Dp, val width: Dp)

/** The rail or the TV column beside the current screen; null without one (phones' bar, the player). */
val LocalSideNavigation = staticCompositionLocalOf<SideNavigation?> { null }

/**
 * How far the current screen's place lies from the window's start edge, under the rail or the TV column. A backdrop
 * runs under them to the window's edge ([NavigationBackdrop]); one inside a scrolling list reaches only as far as the
 * list, so the list reaches under them too (`Modifier.extendStart(navigationInset)`), its content padded back by as much.
 */
val navigationInset: Dp
    @Composable get() = LocalSideNavigation.current?.inset ?: 0.dp

/**
 * How far the collapsed TV column reaches over the start of the current screen's place (0 beside the rail, which ends
 * where the place starts). A row laid out from the place's start that scrolls sideways cuts its cards at the column's
 * edge (`Modifier.clipStart(navigationOverlap)`): only the backdrop is under the clear column.
 */
val navigationOverlap: Dp
    @Composable get() = LocalSideNavigation.current?.let { (it.width - it.inset).coerceAtLeast(0.dp) } ?: 0.dp

/**
 * A screen's backdrop: [content] (the picture and the screen's own fades, filling the box) laid out reaching under the
 * rail or the TV column to the window's start edge, and dimmed from that edge so the navigation's labels and icons read
 * over the brightest picture: [NAVIGATION_DIM] black at the edge, eased out to clear [NAVIGATION_DIM_REACH] times the
 * navigation's width from it, so the dim shows no edge where it ends. [modifier] sizes the box within the screen's
 * place. The Board's backdrop draws no dim: its own fade from the window's edge is darker wherever the dim reaches.
 */
@Composable
fun NavigationBackdrop(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val side = LocalSideNavigation.current
    val dim = if (side == null) {
        Modifier
    } else {
        Modifier.drawWithCache {
            val reach = side.width.toPx() * NAVIGATION_DIM_REACH
            val drawn = minOf(reach, size.width)
            val rtl = layoutDirection == LayoutDirection.Rtl
            val stops = fadeStops(TortugaColors.Background, 0f, 1f, from = NAVIGATION_DIM)
            val brush = Brush.horizontalGradient(*stops, startX = if (rtl) size.width else 0f, endX = if (rtl) size.width - reach else reach)
            onDrawWithContent {
                drawContent()
                drawRect(brush, topLeft = Offset(if (rtl) size.width - drawn else 0f, 0f), size = Size(drawn, size.height))
            }
        }
    }
    // The extension stays in the chain at 0 (no side navigation): see extendUp.
    Box(modifier.extendStart(side?.inset ?: 0.dp).then(dim), content = content)
}

/** Cuts what [this] draws [by] in from its start edge (nothing at 0); past its other edges it draws as before. */
internal fun Modifier.clipStart(by: Dp): Modifier = drawWithContent {
    val cut = by.toPx()
    if (cut <= 0f) {
        drawContent()
    } else {
        val rtl = layoutDirection == LayoutDirection.Rtl
        // Well past the other edges: a focused card grows beyond the row.
        val left = if (rtl) -size.width else cut
        val right = if (rtl) size.width - cut else size.width * 2
        clipRect(left, -size.height, right, size.height * 2) { this@drawWithContent.drawContent() }
    }
}

/**
 * For a screen whose content scrolls up under the status bar (its list pads its top by [statusBarOverlap]): the
 * status bar's scrim shows while [scrolled] (the list has left its top).
 */
@Composable
fun ScrimWhileScrolled(scrolled: () -> Boolean) {
    val overlap = LocalStatusBarOverlap.current ?: return
    val current by rememberUpdatedState(scrolled)
    LaunchedEffect(overlap) { snapshotFlow { current() }.collect { overlap.scrolled = it } }
    DisposableEffect(overlap) { onDispose { overlap.scrolled = false } }
}

/**
 * Lays the content out [by] taller, reaching up beyond the top of its place (drawn, not clipped, there). The layout stays
 * in the modifier chain when [by] is 0 (a pushed screen, the bar hidden): with Compose 1.12.1, once a node's outermost
 * layout modifier leaves its chain, `positionInWindow` of everything under it stays off by the offset that modifier gave,
 * and a popup takes its anchor from it, so every menu on a pushed screen opened the status bar's height above its anchor
 * (ExtendUpTest).
 */
internal fun Modifier.extendUp(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(constraints.copy(minHeight = constraints.minHeight + extra, maxHeight = constraints.maxHeight + extra))
    layout(placeable.width, placeable.height - extra) { placeable.place(0, -extra) }
}

/** [extendUp] towards the start: lays the content out [by] wider, reaching beyond the start of its place. */
internal fun Modifier.extendStart(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.placeRelative(-extra, 0) }
}

/** Black behind the status bar fading out below it, shown while the screen's content is scrolled under it. */
@Composable
private fun StatusBarScrim(overlap: StatusBarOverlap) {
    val alpha by animateFloatAsState(if (overlap.scrolled) 1f else 0f, tween(160), label = "statusBarScrim")
    val fraction = overlap.height / (overlap.height + ScrimFade)
    Box(
        Modifier
            .offset(y = -overlap.height)
            .fillMaxWidth()
            .height(overlap.height + ScrimFade)
            .graphicsLayer { this.alpha = alpha }
            .background(
                Brush.verticalGradient(
                    0f to TortugaColors.Background,
                    fraction * 0.5f to TortugaColors.Background.copy(alpha = 0.9f),
                    1f to Color.Transparent,
                ),
            ),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BottomBar(destinations: List<TopLevelDestination>, selected: TopLevelDestination?, onSelect: (TopLevelDestination) -> Unit) {
    Column {
        HorizontalDivider(color = TortugaColors.Hairline)
        ShortNavigationBar(containerColor = TortugaColors.Background, contentColor = TortugaColors.Text) {
            val colors = ShortNavigationBarItemDefaults.colors(
                selectedIconColor = TortugaColors.Text,
                selectedTextColor = TortugaColors.Text,
                selectedIndicatorColor = TortugaColors.SurfaceHighest,
                unselectedIconColor = TortugaColors.TextMuted,
                unselectedTextColor = TortugaColors.TextMuted,
            )
            for (destination in destinations) {
                ShortNavigationBarItem(
                    selected = destination == selected,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.layoutProbe("nav.${destination.probeName}"),
                    icon = { Icon(destination.icon, contentDescription = null) },
                    label = { Text(destination.shortLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = colors,
                )
            }
        }
    }
}

/**
 * The rail of tablets, desktop and phones in landscape: the destinations as icon and short label, centred as a group,
 * and [footer] (the profile's avatar) at the bottom edge. Where the rail is too short for that ([RailSpacing.DENSE]: a
 * phone in landscape) the items sit closer and the group is centred in the space above the footer; a group still
 * taller than its space scrolls there, never under the footer. It has no background of its own: the frame's is under
 * it, or a screen's backdrop, dimmed under it ([NavigationBackdrop]).
 */
@Composable
private fun Rail(
    destinations: List<TopLevelDestination>,
    selected: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    footer: (@Composable (expanded: Boolean) -> Unit)?,
) {
    val footerHeight = if (footer != null) FooterHeight else 0.dp
    val label = with(LocalDensity.current) { MaterialTheme.typography.labelMedium.lineHeight.toDp() }
    // The roomy rail's height: its group centred in the rail, as far from its top as the footer from its bottom.
    val roomy = with(RailSpacing.ROOMY) {
        (margin + footerHeight) * 2 + (itemPadding * 2 + RailPillHeight + RailLabelGap + label) * destinations.size +
            itemGap * (destinations.size - 1).coerceAtLeast(0)
    }
    // Composed again as the rail turns dense or roomy, not on each frame of a resize.
    val window = LocalWindowSize.current
    val dense by remember(window, roomy) { derivedStateOf { window.value.height < roomy } }
    val spacing = if (dense) RailSpacing.DENSE else RailSpacing.ROOMY
    Column(
        Modifier
            .width(RailWidth)
            .fillMaxHeight()
            .padding(vertical = spacing.margin)
            .focusRestorer()
            .focusGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // As tall as the footer, so the group sits in the middle of the rail.
        if (spacing == RailSpacing.ROOMY) Spacer(Modifier.height(footerHeight))
        // The weight's height is the scroll's minimum: a group that fits is centred in it.
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.itemGap, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (destination in destinations) RailItem(destination, destination == selected, spacing) { onSelect(destination) }
        }
        footer?.invoke(false)
    }
}

/**
 * A rail destination: its icon over its label, [spacing]'s room above and below them. The pill behind the icon is the
 * one indication: a tone while [selected], a step lighter under the pointer, the primary fill while it has visible
 * focus (keyboard); the item around it never fills.
 */
@Composable
private fun RailItem(destination: TopLevelDestination, selected: Boolean, spacing: RailSpacing, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    val showFocus = focused && isFocusVisible()
    val pill by animateColorAsState(
        when {
            showFocus -> TortugaColors.Primary
            selected -> TortugaColors.Text.copy(alpha = if (hovered || pressed) 0.24f else 0.16f)
            hovered || pressed -> TortugaColors.Text.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        tween(120),
        label = "railPill",
    )
    val content = if (selected) TortugaColors.Text else TortugaColors.TextMuted
    Column(
        Modifier
            .width(72.dp)
            .layoutProbe("nav.${destination.probeName}")
            .selectable(selected = selected, interactionSource = interactions, indication = null, role = Role.Tab, onClick = onClick)
            .padding(vertical = spacing.itemPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RailLabelGap),
    ) {
        Box(Modifier.size(width = 52.dp, height = RailPillHeight).background(pill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(destination.icon, contentDescription = null, Modifier.size(22.dp), tint = if (showFocus) TortugaColors.OnPrimary else content)
        }
        Text(destination.shortLabel, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The TV column (Android TV's navigation drawer pattern): 72 dp of icons, the destinations centred in the height and
 * [footer] (the avatar) at the bottom, with equal margins on both sides. Moving focus into it (D-pad left from the
 * screen's first column) widens it over the screen to show the labels; focus comes back to the destination it left
 * from, else goes to the [selected] one, never to the one level with the element it came from ([focusEntry]).
 * Collapsed it has no background: a screen's backdrop runs under it, dimmed ([NavigationBackdrop]), and sideways rows
 * end at its edge ([navigationOverlap]). Open, it has the background and dims the screen beside it. [entry] is on the
 * [selected] destination, else the first: Left asked of it comes in as a move from the screen does
 * ([towardTvColumn]).
 */
@Composable
private fun TvSideNavigation(
    destinations: List<TopLevelDestination>,
    selected: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    footer: (@Composable (expanded: Boolean) -> Unit)?,
    entry: FocusRequester,
) {
    var open by remember { mutableStateOf(false) }
    // Read while measuring: the widening re-lays the column out on each frame without composing it again.
    val width = animateDpAsState(if (open) TvExpandedWidth else TvCollapsedWidth, tween(180), label = "tvNav")
    // Read while drawing: the fade redraws the column without composing it again.
    val opacity = animateFloatAsState(if (open) 1f else 0f, tween(180), label = "tvNavBackground")
    Row(Modifier.fillMaxHeight().zIndex(1f)) {
        Column(
            Modifier
                .layout { measurable, constraints ->
                    val px = constraints.constrainWidth(width.value.roundToPx())
                    val placeable = measurable.measure(constraints.copy(minWidth = px, maxWidth = px))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .fillMaxHeight()
                .drawBehind { drawRect(TortugaColors.Background, alpha = opacity.value) }
                .padding(horizontal = 12.dp, vertical = 27.dp)
                .onFocusChanged { open = it.hasFocus }
                .focusEntry(entry.takeIf { selected != null }),
        ) {
            Spacer(Modifier.height(if (footer != null) FooterHeight else 0.dp))
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                val entered = selected ?: destinations.first()
                for (destination in destinations) {
                    TvItem(destination, destination == selected, open, if (destination == entered) Modifier.focusRequester(entry) else Modifier) { onSelect(destination) }
                }
            }
            footer?.invoke(open)
        }
        // The screen under the open column dims towards it, eased so the dim shows no edge.
        AnimatedVisibility(open, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            Box(Modifier.width(96.dp).fillMaxHeight().background(Brush.horizontalGradient(*fadeStops(TortugaColors.Background, 0f, 1f, from = 1f))))
        }
    }
}

/**
 * Left (Right in a right-to-left layout) from the screen beside the TV column where nothing lies further that way goes
 * into the column at [entry]. Focus moves find the column only beside it: while it narrows after focus left it (180 ms,
 * over a second on a busy TV box) its open destinations reach over the screen's first column, and Left from Home's
 * first shortcut found nothing.
 */
@Composable
private fun Modifier.towardTvColumn(entry: FocusRequester): Modifier {
    val focusManager = LocalFocusManager.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.key != (if (rtl) Key.DirectionRight else Key.DirectionLeft)) return@onKeyEvent false
        val direction = if (rtl) FocusDirection.Right else FocusDirection.Left
        focusManager.moveFocus(direction) || entry.requestFocus(direction)
    }
}

/** A TV destination: a pill that rests surfaceHigh while [selected] and turns the primary fill with focus (no growth). */
@Composable
private fun TvItem(destination: TopLevelDestination, selected: Boolean, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    TortugaSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp).layoutProbe("nav.${destination.probeName}"),
        shape = CircleShape,
        color = if (selected) TortugaColors.SurfaceHigh else Color.Transparent,
        contentColor = if (selected) TortugaColors.Text else TortugaColors.TextMuted,
        role = Role.Tab,
        selected = selected,
        contentAlignment = Alignment.CenterStart,
        grows = false,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The icon keeps its place (the middle of the collapsed column) as the column widens.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(destination.icon, contentDescription = if (open) null else destination.label, Modifier.size(24.dp))
            }
            if (open) {
                Text(
                    destination.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(start = 8.dp, end = 16.dp),
                )
            }
        }
    }
}

/** [this] destination's icon in the bar, rail and TV column. */
internal val TopLevelDestination.icon: ImageVector
    get() = when (this) {
        TopLevelDestination.BOARD -> TortugaIcons.Home
        TopLevelDestination.DISCOVER -> TortugaIcons.Explore
        TopLevelDestination.SEARCH -> TortugaIcons.Search
        TopLevelDestination.LIBRARY -> TortugaIcons.VideoLibrary
        TopLevelDestination.SETTINGS -> TortugaIcons.Settings
    }
