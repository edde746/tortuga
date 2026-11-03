package com.edde746.tortuga.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.WidthClass
import com.edde746.tortuga.ui.adaptive.WindowLayout
import com.edde746.tortuga.ui.focus.AppFocus
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.ScreenFocus
import com.edde746.tortuga.ui.focus.focusHomeGroup
import com.edde746.tortuga.ui.focus.focusEntry
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.ColorsTheme
import com.edde746.tortuga.ui.theme.OnVideoTheme
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection
import kotlinx.coroutines.launch

/**
 * Where dialogs ([ModalOverlay]) and sheets ([TortugaSheet]) are drawn: over the whole app (the navigation too), in the
 * app's own window, so the harness's probes and the TV's focus stay in one place. Lines that show whatever covers the
 * screens ([TopOverlay]) are drawn over them.
 *
 * Focus an overlay held goes back, once it closes, to the control that had focus as it opened ([opener]), or, when that
 * control is gone, to the control nearest its place on its screen; never to the navigation ([closed]).
 */
internal class OverlayHost {
    val entries = mutableStateListOf<@Composable () -> Unit>()

    /** The [TopOverlay]s, drawn over [entries], newest on top. */
    val tops = mutableStateListOf<@Composable () -> Unit>()

    /**
     * A [TopOverlay] has focus, or is taking it: the newest of [entries] isn't on top meanwhile, so a sheet lets focus
     * go to it and doesn't take it back.
     */
    var topHasFocus by mutableStateOf(false)

    /**
     * The app under the overlays, focus's last way back to it ([home]). Its focus restorer finds only the child of the
     * app that had focus, one level deep: an element inside a group of its own (a scrolling column) isn't found again,
     * and focus goes to the app's first element, the navigation. The restorer is there only while no overlay is open:
     * it sends any focus coming into the app to the child it kept, and focus passes through the app while one is open
     * (Android drops focus on the navigation, the window's first element, as the focused control in a sheet leaves:
     * Remove rating going with the score), which it would keep, sending the opener's focus there as the overlay closes.
     * So an overlay that closes with its opener gone and no screen on view able to take focus leaves focus on the app's
     * first element, the navigation, not on the part of the app the restorer had kept.
     */
    val contentFocus = FocusRequester()

    /** The [FocusMemory]s composed in the app ([rememberFocusMemory]): the screens' elements focus can go back to. */
    val memories = mutableListOf<FocusMemory>()

    /** The screens composed in the app ([ScreenFocus]): the one on view takes focus no opener gets back ([home]). */
    val screens = mutableListOf<ScreenFocus>()

    /** Whether anything in the app or its overlays has focus ([com.edde746.tortuga.ui.focus.focusHome]). */
    val appFocus = AppFocus()

    /** The control ([overlayOpener]) that has focus, while one has; focus arriving at one ends a pending [follow]. */
    var focusedControl: OpenerNode? = null
        set(value) {
            field = value
            if (value != null) leaving = null
        }

    /**
     * The control an overlay gave focus back to ([FocusOpener]) while it keeps it: gone with it (the action the overlay
     * confirmed replaced it after the overlay closed: Disconnect turning into Connect once the server answers), it
     * hands focus to the control taking its place ([left]).
     */
    var returned: OpenerNode? = null

    /** Holds focus between an overlay leaving and focus going back ([closed]). */
    val holder = FocusHolder()

    /** The opener of the overlay that closed last, until [settle] has given focus back. */
    private var closing: FocusOpener? = null

    /** Counts the overlays that closed: each has [settle] run a frame later. */
    var closings by mutableIntStateOf(0)
        private set

    /** The place of the [returned] control that left with focus, until [follow] has moved focus on. */
    private var leaving: ControlPlace? = null

    /**
     * Where focus goes back to from an overlay opening now: the element that has focus, by its key where a screen's
     * [FocusMemory] knows it, and as the control it is ([overlayOpener]); null when focus is on neither (the
     * navigation, nothing).
     */
    fun opener(): FocusOpener? {
        val memory = memories.firstOrNull { it.focusedKey != null }
        val control = focusedControl
        if (memory == null && control == null) return null
        return FocusOpener(memory, memory?.focusedKey, control, control?.currentPlace())
    }

    /**
     * An overlay opened from [opener] has closed; [held]: it had focus. Focus leaves it at once, while its focused
     * control is still there to leave (once that control is gone, the platform puts focus on the window's first
     * control, the navigation): to the opener if it can take it now, else to the [holder]. A frame later, once the
     * screens under it have changed with what closed it (Trakt's Connect enabled again as its dialog closes, or turned
     * into Disconnect as Trakt connects), [settle] gives focus back.
     */
    fun closed(opener: FocusOpener?, held: Boolean) {
        closing = opener
        if (held && opener?.exact(this) != true) holder.take()
        closings++
    }

    /**
     * [closed]'s second half. Focus that is in no screen (the [holder] kept it, or the overlay had let it go as it slid
     * away and nothing took it) goes to the opener, else (gone) to its screen's place for it or the control nearest its
     * place, else to the screen on view ([home]); focus something took meanwhile (the next screen) stays. While an
     * overlay is still open (one a menu opened over) focus goes back to an opener in it, else that overlay takes it
     * back itself.
     */
    fun settle(contentFocused: Boolean) {
        val opener = closing
        closing = null
        if (entries.isNotEmpty() || topHasFocus) {
            if (holder.held) opener?.exact(this)
            return
        }
        if (!contentFocused && opener?.restore(this) != true) home()
    }

    /** The [returned] control at [place] left with focus: the surface that takes the drop [follow]s it. */
    fun left(place: ControlPlace) {
        leaving = place
    }

    /**
     * Focus to the control nearest the place of the [returned] control that left with it, unless an overlay has opened
     * since; true if it moved. Asked first by the surface that takes the drop ([com.edde746.tortuga.ui.focus.focusHome]),
     * once what replaced the control is laid out.
     */
    fun follow(): Boolean {
        val place = leaving ?: return false
        leaving = null
        if (entries.isNotEmpty() || topHasFocus) return false
        returned = place.nearest() ?: return false
        return true
    }

    /** Focus to the screen on view (its remembered element, else its first), else through the app's restorer. */
    private fun home(): Boolean = screens.firstOrNull { it.onView() }?.home() == true || contentFocus.requestFocus()
}

internal val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/**
 * [content] with its dialogs and sheets drawn over all of it, newest on top, and the [TopOverlay]s over those. Placed
 * once around the app ([com.edde746.tortuga.ui.app.TortugaFrame]). An overlay that closes gives focus back to the
 * control that opened it ([OverlayHost.closed]). The content and the overlays share the window's Back key press
 * ([LocalBackPress]) and know the press key held down ([LocalHeldSelect]). A screen reader reaches only the newest
 * dialog or sheet: what lies under it (the app, an earlier one) has no semantics while it is open, as the scrim keeps
 * touch and focus from it.
 */
@Composable
internal fun OverlayHostBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val host = remember { OverlayHost() }
    val backPress = remember { BackPress() }
    val heldSelect = remember { HeldSelect() }
    val contentFocused = remember { booleanArrayOf(false) }
    Box(modifier.then(host.appFocus.modifier).onPreviewKeyEvent(heldSelect::watch)) {
        CompositionLocalProvider(LocalOverlayHost provides host, LocalBackPress provides backPress, LocalHeldSelect provides heldSelect) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (host.entries.isEmpty()) Modifier else Modifier.clearAndSetSemantics { })
                    .onFocusChanged { contentFocused[0] = it.hasFocus }
                    .focusRequester(host.contentFocus)
                    .then(if (host.entries.isEmpty()) Modifier.focusRestorer() else Modifier)
                    .focusGroup(),
            ) { content() }
            Box(host.holder.modifier)
            for ((index, entry) in host.entries.withIndex()) {
                key(entry) {
                    Box(if (index == host.entries.lastIndex) Modifier else Modifier.clearAndSetSemantics { }) { entry() }
                }
            }
            for (top in host.tops) key(top) { top() }
        }
    }
    LaunchedEffect(host.closings) {
        if (host.closings == 0) return@LaunchedEffect
        // After the overlay has left the layout, and the screens under it have changed with what closed it.
        withFrameNanos { }
        host.settle(contentFocused[0])
    }
}

/**
 * Draws [overlay] in the [LocalOverlayHost] while this is composed (in place when there is none: tests). [overlay] gets
 * whether it is the newest overlay, the one on top (not while a [TopOverlay] has focus), and the [OverlayPass] it runs
 * its content with ([ForPass]). Composed on each pass of the screen that shows the overlay, however little else
 * changed: new values in the content are all that pass may bring. The host composes it at the app's root, in the
 * colours of where it opens ([ColorsTheme]: the player's sheets in [OnVideoTheme]'s white).
 */
@NonSkippableComposable
@Composable
private fun HostedOverlay(overlay: @Composable (isTop: () -> Boolean, pass: OverlayPass) -> Unit) {
    val host = LocalOverlayHost.current
    if (host == null) {
        overlay({ true }, InPlacePass)
        return
    }
    val current = rememberUpdatedState(overlay)
    val colors = rememberUpdatedState(MaterialTheme.colorScheme)
    val pass = rememberOverlayPass()
    DisposableEffect(host) {
        lateinit var entry: @Composable () -> Unit
        entry = { ColorsTheme(colors.value) { current.value({ host.entries.lastOrNull() === entry && !host.topHasFocus }, pass.value) } }
        host.entries += entry
        onDispose { host.entries -= entry }
    }
}

/**
 * One pass of the screen that shows an overlay ([HostedOverlay], [TopOverlay]): a new one each time the screen composes
 * the overlay, which the host hands to the overlay to run its content with ([ForPass]).
 *
 * The host composes the content in its own composition, often not the screen's (a section laid out by its width has
 * one of its own), and Compose runs a content lambda there again only where it tracked it running. That is lost when
 * the screen composes again in the frame the host first composes the content (its store answering as the overlay
 * opens: the players found as the Player page shows): the content's invalidation reaches the host before that first
 * composition is applied and is dropped, and the overlay keeps the screen's earlier values. Run for each pass, the
 * content follows the screen whatever was tracked.
 */
@Stable
private class OverlayPass

/** The pass of an overlay drawn in place (no host): the screen's own composition runs its content again. */
private val InPlacePass = OverlayPass()

/**
 * The [OverlayPass] of the overlay composed here, read by the host: a new one after each pass of the screen, written
 * once that pass is applied, so the host reads it in a composition of its own after its first one is applied.
 */
@Composable
private fun rememberOverlayPass(): State<OverlayPass> {
    val pass = remember { mutableStateOf(OverlayPass()) }
    SideEffect { pass.value = OverlayPass() }
    return pass
}

/** The pass an overlay's content is run for ([ForPass]); read by nothing. */
private val LocalOverlayPass = staticCompositionLocalOf<OverlayPass?> { null }

/**
 * Runs [content] for [pass], and again for each new one: a new value of a static composition local
 * ([LocalOverlayPass]) runs everything it is provided to again, whatever [content] reads or is handed.
 */
@Composable
private fun ForPass(pass: OverlayPass, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverlayPass provides pass, content = content)
}

/**
 * Draws [content] over every dialog and sheet of the [LocalOverlayHost] while this is composed (in place when there is
 * none: tests): a line that shows whatever covers the screens (a player app's hand-off). Unlike them it neither takes
 * focus on its own nor keeps it: each time [focus] turns non-null focus moves there (a sheet under it lets it go), and
 * while focus is in it Back (Esc, the remote's Back) runs [onBack]. Gone while it has focus, it gives focus back to the
 * control that had it before, else to the dialog or sheet under it, else to the control nearest the opener's place
 * ([OverlayHost.closed]).
 */
@NonSkippableComposable
@Composable
internal fun TopOverlay(focus: FocusRequester?, onBack: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    val top = remember { TopFocus() }
    val press = LocalBackPress.current
    val back by rememberUpdatedState(onBack)
    val layer: @Composable (pass: OverlayPass) -> Unit = { pass ->
        // Registered as focus arrives, so it is the newest handler: the system's Back (Android 13 on) comes here.
        if (top.held) SystemBackHandler { back() }
        Box(
            Modifier
                .onFocusChanged { state ->
                    val had = top.held
                    top.held = state.hasFocus
                    if (had && !state.hasFocus) host?.topHasFocus = false
                }
                .onKeyEvent { event -> top.held && press.handle(event, back) }
                .focusGroup(),
        ) { ForPass(pass, content) }
    }
    if (host == null) {
        layer(InPlacePass)
    } else {
        val current = rememberUpdatedState(layer)
        val pass = rememberOverlayPass()
        DisposableEffect(host) {
            val entry: @Composable () -> Unit = { current.value(pass.value) }
            host.tops += entry
            onDispose {
                host.tops -= entry
                if (top.held) {
                    host.topHasFocus = false
                    host.closed(top.opener, held = true)
                }
            }
        }
    }
    LaunchedEffect(focus) {
        if (focus == null) return@LaunchedEffect
        withFrameNanos { }
        top.opener = host?.opener()
        // First, so the sheet under it lets focus go.
        host?.topHasFocus = true
        val moved = focus.requestFocus()
        if (!moved) host?.topHasFocus = top.held
    }
}

/**
 * A [TopOverlay]'s focus: whether it has it, and where it goes back to once the line is gone
 * ([OverlayHost.opener]).
 */
private class TopFocus {
    var held by mutableStateOf(false)
    var opener: FocusOpener? = null
}

/**
 * A dialog: a scrim that dismisses on click, a card that keeps keyboard and D-pad focus inside it, and Back (Esc,
 * the remote's Back) dismissing it. Focus moves to [initialFocus], else to the card's first control, and goes there
 * again when the focused control is disabled or leaves (a step that replaces the dialog's controls: [focusHome]), or
 * when the dialog is on top again without it (a menu sheet over it closed over a control that is gone); once the
 * dialog is gone it returns to the control that had it before, else to the one nearest its place
 * ([OverlayHost.closed]). [content] follows each pass of the composable that shows the dialog ([OverlayPass]). A
 * screen reader announces the card as a pane titled [title] and reaches nothing else meanwhile.
 *
 * The card is as tall as its content up to the window; content that can grow scrolls itself (a `weight(1f, false)`
 * list or scrolling column).
 */
@NonSkippableComposable
@Composable
fun ModalOverlay(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: FocusRequester? = null,
    maxWidth: Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalOverlayHost.current
    // Opening: the element that has focus now, before anything in the dialog takes it, is where it goes back to.
    val focus = remember { DialogFocus(host?.opener()) }
    HostedOverlay { isTop, pass -> OverlayCard(title, onDismiss, modifier, initialFocus, maxWidth, focus, isTop, pass, content) }
    DisposableEffect(host) {
        onDispose {
            focus.closed = true
            host?.closed(focus.opener, focus.held)
        }
    }
}

/**
 * [content] as a page over the whole app, the navigation too (the profile editor, adding a sign-in method:
 * [com.edde746.tortuga.ui.app.AppContent]): focus it has goes back, once it closes, to the control that had focus as
 * it opened, else to the one nearest its place, as from a dialog ([OverlayHost.closed]).
 */
@Composable
internal fun OverlayPage(content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    // Opening: the element that has focus now, before anything in the page takes it, is where it goes back to.
    val focus = remember { DialogFocus(host?.opener()) }
    Box(Modifier.onFocusChanged { focus.held = it.hasFocus }) { content() }
    DisposableEffect(host) {
        onDispose { host?.closed(focus.opener, focus.held) }
    }
}

/**
 * A [ModalOverlay]'s or [OverlayPage]'s focus: whether it has it, where it goes back to once it is gone
 * ([OverlayHost.opener]), and whether the dialog has closed, when focus may leave it.
 */
private class DialogFocus(val opener: FocusOpener?) {
    var held = false
    var closed = false
}

@Composable
private fun OverlayCard(
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier,
    initialFocus: FocusRequester?,
    maxWidth: Dp,
    focus: DialogFocus,
    isTop: () -> Boolean,
    pass: OverlayPass,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardFocus = remember { FocusRequester() }
    SystemBackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(TortugaColors.Scrim)
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .then(modifier)
                .semantics {
                    if (title != null) paneTitle = title
                    isTraversalGroup = true
                }
                // Clicks on the card stay on it; directional and Tab moves never leave it, nor does focus asked for
                // elsewhere until the dialog has closed.
                .pointerInput(Unit) { detectTapGestures { } }
                .onFocusChanged { focus.held = it.hasFocus }
                .focusHomeGroup { initialFocus?.requestFocus() == true || cardFocus.requestFocus() }
                .focusRequester(cardFocus)
                .focusProperties { onExit = { if (!focus.closed) cancelFocusChange() } }
                .focusGroup()
                .background(TortugaColors.Surface, MaterialTheme.shapes.extraLarge)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (title != null) Text(title, style = MaterialTheme.typography.headlineSmall)
            ForPass(pass) { content() }
        }
    }
    HoldDialogFocus(focus, initialFocus, cardFocus, isTop)
}

/**
 * After the first frame focus to [initial], else into [group] (its first control); again each time the dialog comes
 * back on top without focus (a menu sheet or a [TopOverlay] over it gone).
 */
@Composable
private fun HoldDialogFocus(focus: DialogFocus, initial: FocusRequester?, group: FocusRequester, isTop: () -> Boolean) {
    val currentInitial by rememberUpdatedState(initial)
    val top by rememberUpdatedState(isTop)
    // A requester whose control isn't shown (a step without it) moves nothing; the group's first control takes focus
    // then.
    fun take() {
        if (currentInitial?.requestFocus() != true) group.requestFocus()
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        take()
        snapshotFlow { top() }.collect { onTop ->
            if (!onTop) return@collect
            withFrameNanos { }
            if (!focus.held && !focus.closed && top()) take()
        }
    }
}

/**
 * The buttons along the bottom of a [ModalOverlay], end-aligned and wrapping on narrow windows. They are one focus group
 * as wide as the card: a move into them (Down from a field) lands on the button that had focus in them last, else on
 * [primary] (the dialog's main action, last), whatever the labels' widths and wrap ([focusEntry]). A dialog with
 * nothing focusable above its buttons needs no [primary].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlayActions(modifier: Modifier = Modifier, primary: FocusRequester? = null, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().focusEntry(primary),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * The app's one sheet (streams, an addon's install preview, the player's track, speed and delay menus, TV item menus):
 * on phones, tablets and desktop a modal bottom sheet (drag handle, surface fill, 28 dp top corners, at most 640 dp wide
 * and centred on wide windows; dragging it down dismisses it); on TV a panel along the right edge (40 % of the width,
 * at least 480 dp, full height), or with [bottom] (a media item's menu) the bottom sheet without its handle, [title] on
 * one line. Either way over a scrim that dismisses on click, with keyboard, D-pad and remote focus
 * kept inside it and Back (Esc, the remote's Back, the system's back) running [onBack]: [onDismiss], unless the sheet
 * holds a page that Back leaves for the one before it (the player's options). Focus moves to [initialFocus], else to the
 * sheet's first control, else (nothing in it can take focus yet: a list still loading) to the sheet itself; once the
 * sheet is gone it returns to the control that had it before, else to the one nearest its place ([OverlayHost.closed]).
 *
 * While it is open and on top of the other overlays the sheet holds focus: when focus leaves it (the focused control
 * went away, a screen under it asked for focus) it takes focus back the same way, so keys and Back always reach the
 * sheet and never the screen under it.
 *
 * Close it by no longer composing it, or with [visible] = false (it slides away first). The sheet doesn't scroll:
 * [content] that can grow scrolls itself (a list with `Modifier.weight(1f, fill = false)`, vertical content padding of
 * `focusReach`); the sheet's own padding leaves room for focus to grow sideways. [content] follows each pass of the
 * composable that shows the sheet ([OverlayPass]). [probe] names the sheet for the harness. A screen reader announces
 * the sheet as a pane titled [label] ([title], or what heads the sheet when it shows no title of its own) and reaches
 * nothing else meanwhile. [contentTitle]: whether [title] is content text (an addon's or a person's), which takes its
 * direction from its first letter ([contentDirection]).
 */
@NonSkippableComposable
@Composable
fun TortugaSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    visible: Boolean = true,
    initialFocus: FocusRequester? = null,
    probe: String? = null,
    onBack: () -> Unit = onDismiss,
    bottom: Boolean = false,
    label: String? = title,
    contentTitle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shown = remember { MutableTransitionState(false) }
    val focus = remember { SheetFocus() }
    val host = LocalOverlayHost.current
    // Opening: the element that has focus now, before anything in the sheet takes it, is where it goes back to.
    if (visible && !shown.targetState) focus.opener = host?.opener()
    shown.targetState = visible
    if (!visible && !shown.currentState && shown.isIdle) return
    HostedOverlay { isTop, pass ->
        SheetFrame(shown, focus, onDismiss, onBack, modifier, title, contentTitle, label, initialFocus, probe, bottom, isTop, pass, content)
    }
    DisposableEffect(host) {
        onDispose {
            // No longer composed while open (its caller dropped it): closing, so the trap lets focus leave it.
            shown.targetState = false
            // Focus back to the app once the sheet has gone (dismissed, or no longer composed).
            host?.closed(focus.opener, focus.held)
        }
    }
}

@Composable
private fun SheetFrame(
    shown: MutableTransitionState<Boolean>,
    focus: SheetFocus,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    title: String?,
    contentTitle: Boolean,
    label: String?,
    initialFocus: FocusRequester?,
    probe: String?,
    bottom: Boolean,
    isTop: () -> Boolean,
    pass: OverlayPass,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layout = LocalWindowLayout.current
    val back by rememberUpdatedState(onBack)
    val press = LocalBackPress.current
    val tv = layout.formFactor == FormFactor.TV
    val side = tv && !bottom
    val (start, end) = layout.sheetSides(bottom)
    if (shown.targetState) SystemBackHandler { back() }
    // One transition for the scrim and the sheet, so the sheet counts as gone once both are.
    val transition = rememberTransition(shown, label = "sheet")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        transition.AnimatedVisibility(visible = { it }, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(TortugaColors.Scrim).pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
        }
        val trap = Modifier
            // Clicks on the sheet stay on it; directional and Tab moves never leave it while it is open and on top. Once
            // it is closing, focus may leave (the player that took the Streams sheet's place asks for it at once), and so
            // it may for an overlay opened over it (a row's menu on TV) or a line over it that takes it ([TopOverlay]).
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics {
                if (label != null) paneTitle = label
                isTraversalGroup = true
            }
            .then(if (probe != null) Modifier.layoutProbe(probe) else Modifier)
            .ignoreHeldSelect()
            .onFocusChanged { focus.held = it.hasFocus }
            .onKeyEvent { event -> shown.targetState && press.handle(event, back) }
            // The sheet itself, which holds focus while none of its controls can take it; moves from it enter them.
            .focusRequester(focus.sheet)
            .focusProperties { onExit = { if (shown.targetState && isTop()) cancelFocusChange() } }
            .focusTarget()
            .focusRequester(focus.controls)
            .focusGroup()
        if (side) {
            transition.AnimatedVisibility(
                visible = { it },
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(tween(240)) { it } + fadeIn(tween(240)),
                exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(200)),
            ) {
                Column(
                    modifier
                        .width((maxWidth * 0.4f).coerceAtLeast(480.dp).coerceAtMost(maxWidth))
                        .fillMaxHeight()
                        .then(trap)
                        .background(TortugaColors.Surface)
                        .padding(start = start, end = end, top = 32.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (title != null) {
                        val style = MaterialTheme.typography.headlineSmall
                        Text(title, style = if (contentTitle) style.contentDirection() else style)
                    }
                    ForPass(pass) { content() }
                }
            }
        } else {
            val limit = maxHeight * 0.9f
            transition.AnimatedVisibility(
                visible = { it },
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(260)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
            ) {
                BottomSheetBody(
                    onDismiss = onDismiss,
                    modifier = modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .heightIn(max = limit)
                        .then(trap),
                    title = title,
                    contentTitle = contentTitle,
                    padding = start,
                    tv = tv,
                    pass = pass,
                    content = content,
                )
            }
        }
    }
    if (shown.targetState) HoldFocus(focus, initialFocus, isTop)
}

/**
 * A sheet's focus: the sheet itself, its controls (the group of them), whether focus is in it, and where it goes back
 * to once the sheet is gone ([OverlayHost.opener]).
 */
private class SheetFocus {
    val sheet = FocusRequester()
    val controls = FocusRequester()
    var held by mutableStateOf(false)
    var opener: FocusOpener? = null

    /** Focus to [initial] (while its control is shown), else the first control, else the sheet itself. */
    fun take(initial: FocusRequester?) {
        // A requester whose control isn't shown (a step without it) moves nothing.
        if (initial?.requestFocus() == true) return
        if (controls.requestFocus()) return
        sheet.requestFocus()
    }
}

/**
 * The window's Back key press (Escape's too), shared by the layers that take it (the player, a sheet, a [TopOverlay]):
 * one press acts once, on its release. Its press is only taken, so it doesn't move focus out (Compose's default for an
 * unhandled Back) and never reaches the screen under the layer. Its release is its key-up, to where focus is then; from
 * Android 13 (predictive back) a remote's release comes first as the system's back event, to the newest back handler
 * ([SystemBackHandler], which ends the press), then as the key-up. The press, the system's back and the key-up needn't
 * reach the same layer: a Back pressed as a player menu opens, before its sheet has taken focus, reaches the player,
 * the system's back closes the sheet, and the key-up, to the player again, must not hide the controls as well.
 */
internal class BackPress {
    private var down = false

    /** Takes a Back or Escape key [event]: notes its press; its release runs [onBack] unless the press has ended. */
    fun handle(event: KeyEvent, onBack: () -> Unit): Boolean {
        if (event.key != Key.Back && event.key != Key.Escape) return false
        when (event.type) {
            KeyEventType.KeyDown -> down = true
            KeyEventType.KeyUp -> if (down) {
                down = false
                onBack()
            }
        }
        return true
    }

    /** The system's back event released the press: its key-up does nothing. */
    fun end() {
        down = false
    }
}

/** The window's [BackPress], one per [OverlayHostBox]. */
internal val LocalBackPress = staticCompositionLocalOf { BackPress() }

/**
 * The system's back ([NavigationBackHandler]: Android's back gesture and Back, a remote's Back from Android 13, Esc on
 * desktop) runs [onBack] while this is composed and [enabled]; the newest one composed gets it. It ends the window's
 * Back key press ([BackPress]): the key-up that follows does nothing.
 */
@Composable
internal fun SystemBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val press = LocalBackPress.current
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = {
            press.end()
            onBack()
        },
    )
}

/**
 * After the first frame, focus into the sheet ([SheetFocus.take]); then, whenever focus leaves it while it is the top
 * overlay, into it again a frame later (unless it came back meanwhile: a control that replaced the focused one took it).
 */
@Composable
private fun HoldFocus(focus: SheetFocus, initial: FocusRequester?, isTop: () -> Boolean) {
    val currentInitial by rememberUpdatedState(initial)
    val top by rememberUpdatedState(isTop)
    LaunchedEffect(focus) {
        withFrameNanos { }
        focus.take(currentInitial)
        snapshotFlow { focus.held || !top() }.collect { held ->
            if (held) return@collect
            withFrameNanos { }
            if (!focus.held && top()) focus.take(currentInitial)
        }
    }
}

/**
 * A bottom sheet's body: the drag handle, [title] and [content]; dragging it (or a list in it, from its top) down
 * dismisses it. On [tv], where nothing drags, no handle, [title] on one line, and the sheet stays put: a scroll that
 * focus asks of a list in it (bringing its first entry into view as the sheet slides in) never moves the sheet.
 */
@Composable
private fun BottomSheetBody(
    onDismiss: () -> Unit,
    modifier: Modifier,
    title: String?,
    contentTitle: Boolean,
    padding: Dp,
    tv: Boolean,
    pass: OverlayPass,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    var offset by remember { mutableFloatStateOf(0f) }
    val height = remember { floatArrayOf(0f) }
    val dismiss by rememberUpdatedState(onDismiss)
    fun settle(velocity: Float) {
        if (offset > height[0] * 0.3f || velocity > 1200f) {
            dismiss()
        } else {
            scope.launch { animate(offset, 0f, animationSpec = tween(180)) { value, _ -> offset = value } }
        }
    }
    val drag = rememberDraggableState { delta -> offset = (offset + delta).coerceAtLeast(0f) }
    // A list inside: pulling down at its top moves the sheet; pushing up first takes the sheet back.
    val nested = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || offset <= 0f) return Offset.Zero
                val used = maxOf(available.y, -offset)
                offset += used
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                offset += available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }
    Column(
        modifier
            .onSizeChanged { height[0] = it.height.toFloat() }
            .then(
                if (tv) {
                    Modifier
                } else {
                    Modifier
                        .graphicsLayer { translationY = offset }
                        .nestedScroll(nested)
                        .draggable(drag, Orientation.Vertical, onDragStopped = { velocity -> settle(velocity) })
                },
            )
            .background(TortugaColors.Surface, SheetShape)
            .padding(start = padding, end = padding, bottom = if (tv) 24.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (tv) {
            Spacer(Modifier.height(12.dp))
        } else {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 32.dp, height = 4.dp).background(TortugaColors.TextFaint, CircleShape))
            }
        }
        if (title != null) {
            val style = MaterialTheme.typography.titleLarge
            Text(
                title,
                style = if (contentTitle) style.contentDirection() else style,
                maxLines = if (tv) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ForPass(pass) { content() }
    }
}

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * A sheet's room between its sides and its content, start then end ([TortugaSheet]): TV's side panel's, else the bottom
 * sheet's ([bottom]: a TV media item's menu). A row that scrolls sideways reaches across it to the sheet's edges (the
 * Streams list's row of addons), its first element still at the content's start.
 */
internal fun WindowLayout.sheetSides(bottom: Boolean = false): Pair<Dp, Dp> = when {
    formFactor == FormFactor.TV && !bottom -> 32.dp to 48.dp
    widthClass == WidthClass.COMPACT -> 16.dp to 16.dp
    formFactor == FormFactor.TV -> 32.dp to 32.dp
    else -> 24.dp to 24.dp
}
