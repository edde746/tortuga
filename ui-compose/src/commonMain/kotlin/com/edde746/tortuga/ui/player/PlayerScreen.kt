package com.edde746.tortuga.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.zIndex
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.input.InputKey
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.input.SystemEdges
import com.edde746.tortuga.input.WatchCommand
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.player.ControlsScrim
import com.edde746.tortuga.presentation.player.LevelIndicator
import com.edde746.tortuga.presentation.player.LevelKind
import com.edde746.tortuga.presentation.player.PlayerCentre
import com.edde746.tortuga.presentation.player.PlayerConfig
import com.edde746.tortuga.presentation.player.PlayerUiState
import com.edde746.tortuga.presentation.player.ScreenLockState
import com.edde746.tortuga.presentation.player.TimelineState
import com.edde746.tortuga.presentation.player.UnlockButton
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.app.LocalTortugaPlatform
import com.edde746.tortuga.ui.components.LocalBackPress
import com.edde746.tortuga.ui.components.SystemBackHandler
import com.edde746.tortuga.ui.focus.focusHome
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.probe.perfMark
import com.edde746.tortuga.ui.theme.OnVideoTheme
import com.edde746.tortuga.ui.watch.RoomActions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

/**
 * The player route of [app]: its current playback's store, rendered over the platform's video surface. Each playback
 * gets a player of its own (a stream picked over the player starts a new one): nothing of the last one's (its state, its
 * callbacks, focus, the open menu) carries over, not even for a frame.
 */
@Composable
fun PlayerRoute(app: AppSession) {
    val platform = LocalTortugaPlatform.current
    val session by app.state.collectAsState()
    // Null for the frames the route animates out after its playback ended.
    val playback = session.playback ?: return
    val stack by app.navigator.backStack.collectAsState()
    key(playback) {
        val store = playback.store
        val state by store.state.collectAsState()
        PlayerScreen(
            state = state,
            timeline = store.timeline,
            config = playback.config,
            onCommand = store::dispatch,
            onInput = store::onInput,
            onTimelineFocusChanged = store::onTimelineFocusChanged,
            onControlsHeightChanged = store::onControlsHeightChanged,
            backEnabled = stack.lastOrNull() == Route.Player,
            pictureInPicture = platform.inPictureInPicture,
            onCopyText = platform::setClipboardText,
            onShareText = if (platform.canShare) platform::share else null,
        ) {
            platform.VideoSurface(playback.engine, Modifier.fillMaxSize())
        }
        // A picture-in-picture window shows the whole picture; the fit and zoom come back with the player.
        LaunchedEffect(platform.inPictureInPicture) { store.onPictureInPictureChanged(platform.inPictureInPicture) }
        // Out of sight (a minimized window) the display may sleep.
        LaunchedEffect(platform.windowVisible) { store.onVisibleChanged(platform.windowVisible) }
        // TalkBack walking the controls one at a time: they stay up.
        LaunchedEffect(platform.assistiveTechnology) { store.onAssistiveTechnologyChanged(platform.assistiveTechnology) }
    }
}

/**
 * The player (the shared `PlayerStore` decides, this only renders [state] and forwards input): [video]
 * under a transparent layer of controls. The video reaches the display's edges: the window keeps the app clear of
 * the display cutout and the system bars ([WindowInsets.safeDrawing], Android), and the video is laid out beyond the
 * player's place by as much, under them, as video players do; the controls keep to the player's place.
 *
 * Input: keys go to [onInput] before any focused control sees them (with key-up, and auto-repeat marked), so the
 * keymap decides and only what it leaves alone moves focus or presses the focused button; Back goes to the store as
 * [PlayerCommand.Back] once, on its release. Clicks and taps on the video go with the platform's click count (the
 * store tells a click from a double click), pointer moves say whether they are over the controls, the wheel goes as
 * notches, and on a touch screen two fingers pinching the video as [InputEvent.Pinch] (the zoom), one finger held on it
 * as [InputEvent.Hold] (2×) and one dragged up or down it as [InputEvent.Swipe] (the brightness and the volume, with
 * the edges the system swipes from). The held speed shows with the transient message, a swipe's level on its side.
 * Buttons, menus and the seek bar send [onCommand] directly.
 *
 * Menus open in a [com.edde746.tortuga.ui.components.TortugaSheet] on phones, tablets and TV (a bottom sheet, a side
 * panel), which holds focus and takes Back itself while it is open; on desktop in a panel above the controls, over the
 * button that opened it, where the pointer already is, with the video and the timeline still in view. The options
 * ([PlayerMenu.OPTIONS]) and their pages open the same way, a page in place of the options.
 *
 * @param timeline the store's timeline ([com.edde746.tortuga.presentation.player.PlayerStore.timeline]): the timeline's
 *   row and the live label collect it while they show, and nothing does while the controls are down.
 * @param config the store's config: the seek step the buttons use, the volume range.
 * @param onControlsHeightChanged how much of the video's height the bottom controls cover, from its bottom edge (0
 *   in picture-in-picture): the store keeps subtitles above them while they show. A new one (another store's) is told
 *   at once.
 * @param backEnabled the system back goes to the store; off while the player leaves (a Back then belongs to the
 *   screen beneath).
 * @param pictureInPicture the app shows in a picture-in-picture window: only [video], which stays where it is so its
 *   surface lives on into and out of the window.
 * @param onCopyText puts a watch-together room's code on the clipboard; [onShareText] opens the platform's share sheet
 *   with its invite (null where there is none).
 */
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    timeline: StateFlow<TimelineState>,
    config: PlayerConfig,
    onCommand: (PlayerCommand) -> Unit,
    onInput: (InputEvent) -> Boolean,
    onTimelineFocusChanged: (Boolean) -> Unit,
    onControlsHeightChanged: (Double) -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    pictureInPicture: Boolean = false,
    onCopyText: (String) -> Unit = {},
    onShareText: ((String) -> Unit)? = null,
    video: @Composable () -> Unit,
) {
    val formFactor = LocalWindowLayout.current.formFactor
    val input by rememberUpdatedState(onInput)
    val rootFocus = remember { FocusRequester() }
    val focus = remember { PlayerFocus() }
    val regions = remember { ControlRegions() }
    // The key each physical key's press gave, which its release goes as: a key-up may type nothing (AltGr let go
    // first), so it can't be read again from the event.
    val downKeys = remember { HashMap<Key, InputKey>() }
    val backPress = LocalBackPress.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val videoInsets = WindowInsets.safeDrawing

    fun onKey(event: KeyEvent): Boolean {
        val key = when (event.type) {
            KeyEventType.KeyUp -> downKeys.remove(event.key) ?: event.toInputKey()
            else -> event.toInputKey()
        } ?: return false
        if (key == InputKey.BACK) return backPress.handle(event) { onCommand(PlayerCommand.Back) }
        return when (event.type) {
            KeyEventType.KeyDown -> input(InputEvent.KeyDown(key, event.inputModifiers, repeat = downKeys.put(event.key, key) != null))
            KeyEventType.KeyUp -> input(InputEvent.KeyUp(key))
            else -> false
        }
    }

    // Keys held as focus leaves the player are released here: their key-up goes where focus is then (a sheet), or
    // nowhere while nothing has it (a menu's page replaced under focus), and a key still noted as held reads its next
    // press as a repeat, which the store drops.
    fun releaseKeys() {
        for (key in downKeys.values) input(InputEvent.KeyUp(key))
        downKeys.clear()
    }

    // Where focus belongs in the player as it is now ([focusHome]'s home, and the effect below once a change has
    // settled): an error's primary action, a watch-together guest's prompt, on TV the sleep timer's Continue, the
    // prompt button while the controls are down and play/pause (the options for a guest whose playback controls are
    // off) while they are up; the player itself otherwise, so keys reach the store. A TV or handheld menu's sheet holds
    // focus itself.
    val current by rememberUpdatedState(state)
    fun home(): Boolean {
        val now = current
        val tv = formFactor == FormFactor.TV
        return when {
            now.error != null -> focus.error.requestFocus() || rootFocus.requestFocus()
            now.menu != null && formFactor != FormFactor.DESKTOP -> false
            now.centre is PlayerCentre.Prompt -> focus.watchPrompt.requestFocus() || rootFocus.requestFocus()
            tv && now.stillWatching != null -> focus.stillWatching.requestFocus()
            tv && now.prompt != null && !now.controlsVisible -> focus.prompt.requestFocus() || rootFocus.requestFocus()
            tv && now.controlsVisible -> focus.playPause.requestFocus() || focus.menuButtons.focus(PlayerMenu.OPTIONS).requestFocus()
            else -> rootFocus.requestFocus()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // The perf runs' mark: the player's first draw.
            .perfMark("player.shown", "", ready = true)
            .onGloballyPositioned {
                bounds = it.boundsInRoot()
                regions.origin = bounds.topLeft
            }
            // Hover, observed before any control handles it: over the controls they stay up. A mouse resting on the
            // player gets a move of Compose's own when what lies under it changes (a control appearing there): only a
            // move to somewhere else than where the mouse was is the viewer's (any input stops an automatic skip's
            // countdown). A hover's previousPosition is no help: Compose keeps none for a pointer that isn't down.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var at = Offset.Unspecified
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.type != PointerType.Mouse) continue
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter -> if (change.position != at) {
                                at = change.position
                                input(InputEvent.PointerMove(regions.contains(change.position)))
                            }
                            PointerEventType.Exit -> {
                                at = Offset.Unspecified
                                input(InputEvent.PointerExit)
                            }
                        }
                    }
                }
            }
            .pointerHoverIcon(if (state.controlsVisible || formFactor != FormFactor.DESKTOP) PointerIcon.Default else HiddenPointerIcon)
            .onPreviewKeyEvent(::onKey)
            .onFocusChanged {
                focus.inPlayer = it.hasFocus
                focus.onControl = it.hasFocus && !it.isFocused
                if (!it.hasFocus) releaseKeys()
            }
            .focusHome(::home)
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        Box(Modifier.fillMaxSize().beyond(videoInsets)) { video() }
        // In picture-in-picture: the video alone (the window's own actions play, pause and seek).
        if (!pictureInPicture) {
            // Everything over the video draws in its white (OnVideoTheme), the controls named or not.
            OnVideoTheme {
                PlayerOverlays(state, timeline, config, focus, regions, bounds, videoInsets, onCommand, onInput, onTimelineFocusChanged, onControlsHeightChanged, onCopyText, onShareText)
            }
        }
    }

    // No controls in picture-in-picture: nothing for subtitles to clear.
    LaunchedEffect(pictureInPicture, onControlsHeightChanged) { if (pictureInPicture) onControlsHeightChanged(0.0) }
    // One effect places focus, from the player's first frame on; a second request elsewhere (one of a panel's own)
    // would race it. Keys must keep reaching the store: when the controls (or a panel) that had focus go away, the
    // player takes it back. An error's primary action takes focus, also when the error comes with the first frame (a
    // stream that fails at once); where it can't (Android's touch mode) the player keeps it. A watch-together guest's
    // prompt (no file of the room's length) takes it the same way. On TV the sleep timer's Continue takes focus while
    // its card shows; the prompt button (skip, up next) takes it as it comes up by itself, over the controls too unless
    // the viewer is scrubbing, and while the controls are down (Select presses it then); otherwise the controls take
    // focus when they appear, starting at play/pause (the options for a guest whose playback controls are off), also
    // when Down brings them up from the prompt button and when the button, past its own seconds, comes back with them
    // ([PlayerPrompt.withControls]); once a menu sheet has slid away and given focus back to the player, the menu's
    // button takes it (play/pause when the button is gone). The prompt button gone with focus on it sends focus to
    // play/pause; a control the viewer moved to from it keeps it. Focus that drops otherwise (the focused control
    // disabled or gone: Previous at the first episode) goes where [home] puts it ([focusHome]).
    var openMenu by remember { mutableStateOf<PlayerMenu?>(null) }
    var promptWasShown by remember { mutableStateOf(false) }
    val stillWatchingShown = state.stillWatching != null
    val promptShown = state.prompt != null
    val watchPromptShown = state.centre is PlayerCentre.Prompt
    val canPlayPause = state.canPlayPause
    LaunchedEffect(state.controlsVisible, state.menu, state.error?.primary?.action, stillWatchingShown, promptShown, watchPromptShown, canPlayPause) {
        val closed = openMenu.takeIf { state.menu == null }
        openMenu = state.menu
        val promptAppeared = promptShown && !promptWasShown && state.prompt?.withControls == false
        val promptGone = promptWasShown && !promptShown
        promptWasShown = promptShown
        when {
            state.error != null -> home()
            state.menu != null -> Unit
            watchPromptShown || stillWatchingShown && formFactor == FormFactor.TV -> {
                // The card is laid out by the next frame.
                withFrameNanos { }
                home()
            }
            promptShown && formFactor == FormFactor.TV && (promptAppeared || !state.controlsVisible) -> {
                // Over the controls in the middle of a scrub, focus stays where the viewer scrubs.
                if (state.controlsVisible && state.scrubbing) return@LaunchedEffect
                // The button is laid out by the next frame.
                withFrameNanos { }
                if (!focus.prompt.requestFocus()) rootFocus.requestFocus()
            }
            state.controlsVisible && formFactor == FormFactor.TV -> {
                if (promptGone) {
                    // The focus the button had is gone by the next frame.
                    withFrameNanos { }
                    if (focus.onControl) return@LaunchedEffect
                }
                if (closed != null) {
                    var frames = 0
                    while (!focus.inPlayer && frames++ < SHEET_EXIT_FRAMES) withFrameNanos { }
                    if (focus.menuButtons.focus(closed).requestFocus()) return@LaunchedEffect
                }
                home()
            }
            // The phone and tablet sheets give focus back themselves (to the player, in touch mode).
            closed != null && formFactor == FormFactor.HANDHELD -> Unit
            else -> rootFocus.requestFocus()
        }
    }
    // The system back: Android's back gesture, and a remote's Back from Android 13 (predictive back) before its key-up,
    // reach the app as a navigation event. It backs out of the innermost layer; with none left the store closes the
    // player.
    SystemBackHandler(enabled = backEnabled) { onCommand(PlayerCommand.Back) }
}

/** How long a closing menu sheet may take to slide away and give focus back: about half a second. */
private const val SHEET_EXIT_FRAMES = 30

/**
 * Lays the content out beyond its place by [insets] on every side, drawn there (not clipped): the video, out to the
 * display's edges past the insets the window keeps the app's content clear of; the timeline's pointer area, past the
 * bar's ends.
 */
internal fun Modifier.beyond(insets: WindowInsets): Modifier = layout { measurable, constraints ->
    val left = insets.getLeft(this, layoutDirection)
    val top = insets.getTop(this)
    val right = insets.getRight(this, layoutDirection)
    val bottom = insets.getBottom(this)
    val placeable = measurable.measure(constraints.offset(horizontal = left + right, vertical = top + bottom))
    layout(placeable.width - left - right, placeable.height - top - bottom) { placeable.place(-left, -top) }
}

/** The scrim behind the controls, over the whole video, by whether the picture is HDR ([ControlsScrim]). */
private val Scrim = listOf(false, true).associateWith { hdr ->
    Brush.verticalGradient(*ControlsScrim.stops(hdr).map { it.position to Color.Black.copy(alpha = it.alpha) }.toTypedArray())
}

/**
 * The player's layers above the video: the click and wheel surface, the controls, and every panel. [bounds] is the
 * player in root coordinates, and the video reaches beyond it by [videoInsets]; the share of the video's height the
 * controls cover goes to [onControlsHeightChanged]. In a watch-together room a guest's file search shows in place of
 * the loading spinner's text, and its prompt in place of the spinner.
 *
 * Nothing over the video meets the controls, and nothing keeps further from the edges than that needs:
 * - The messages at the top (the room's pill and the transient message, [TopMessages]) sit side by side in the top
 *   bar's row, centred and level with close, the same with the controls up or down, where the room between close and
 *   the title's text and the session indicator holds them; else (a phone upright, a long title) under the top bar.
 * - With the controls up, the bottom-trailing corner (the sleep timer's card above the prompt button) sits directly
 *   above the bottom bar's top row (the timeline row; live, the buttons), its trailing edge on the row's, beside a
 *   guest's lock line where it fits there; else above the lock line. With the controls down, at their own bottom margin.
 * - Over an error, the error's black ([ErrorPanel]) covers everything but the top bar, which goes above it.
 */
@Composable
private fun BoxScope.PlayerOverlays(
    state: PlayerUiState,
    timeline: StateFlow<TimelineState>,
    config: PlayerConfig,
    focus: PlayerFocus,
    regions: ControlRegions,
    bounds: Rect,
    videoInsets: WindowInsets,
    onCommand: (PlayerCommand) -> Unit,
    onInput: (InputEvent) -> Boolean,
    onTimelineFocusChanged: (Boolean) -> Unit,
    onControlsHeightChanged: (Double) -> Unit,
    onCopyText: (String) -> Unit,
    onShareText: ((String) -> Unit)?,
) {
    val input by rememberUpdatedState(onInput)
    val metrics = rememberPlayerMetrics()
    val density = LocalDensity.current
    // How far up from the player's bottom edge the controls reach (to the bottom bar's top row's top), in pixels, once
    // they have been laid out; their top in root coordinates from that (a desktop menu stays above it).
    var controlsReach by remember { mutableIntStateOf(-1) }
    val controlsHeight = if (controlsReach < 0 || bounds.height <= 0f) 0f else controlsReach.toFloat().coerceAtMost(bounds.height)
    val controlsTop = if (controlsReach < 0) Float.NaN else bounds.bottom - controlsHeight
    // The bars' parts that the messages at the top and the corner go beside, as the bars last laid out (the top bar's
    // with the controls up only).
    var topReach by remember { mutableStateOf<TopBarReach?>(null) }
    var bottomReach by remember { mutableStateOf<BottomBarReach?>(null) }
    // Subtitles are placed on the video's plane, which reaches below the player by the inset there (none while the
    // system bars are hidden, but for a cutout on that edge).
    val videoTop = videoInsets.getTop(density)
    val videoBottom = videoInsets.getBottom(density)
    // Again for a new callback (another store) with the same height.
    LaunchedEffect(controlsHeight, bounds.height, videoTop, videoBottom, onControlsHeightChanged) {
        val videoHeight = bounds.height + videoTop + videoBottom
        if (bounds.height > 0f) onControlsHeightChanged((if (controlsHeight > 0f) (controlsHeight + videoBottom) / videoHeight else 0f).toDouble())
    }
    // Panels above the controls; before the controls' first layout, about where they will be.
    val aboveControls = if (controlsHeight > 0f) with(density) { controlsHeight.toDp() } + 12.dp else 128.dp
    val belowTopBar = metrics.vertical + metrics.button + 12.dp
    // The bottom-trailing corner's card and prompt button (laid out below), while either shows: their height in pixels.
    val cornerShown = state.stillWatching != null || state.prompt != null
    var cornerHeight by remember { mutableIntStateOf(0) }

    // The video itself: clicks, taps, holds, swipes, pinches and the wheel. Controls above it take their own pointer
    // input. A swipe that starts in an edge the system swipes from is the system's ([systemEdges]).
    val edges by rememberUpdatedState(systemEdges(bounds))
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectVideoTouch({ edges }) { input(it) } }
            .pointerInput(Unit) { detectPinch { input(it) } }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        // Compose reports wheel-down as positive; the store counts notches scrolled up.
                        val notches = -change.scrollDelta.y.toDouble()
                        if (notches != 0.0 && input(InputEvent.Wheel(notches))) change.consume()
                    }
                }
            },
    )

    // While a desktop menu's panel is open a screen reader reaches it alone, as it does a sheet (the overlay host hides
    // what lies under one).
    val desktop = LocalWindowLayout.current.formFactor == FormFactor.DESKTOP
    val underMenu = if (desktop && state.menu != null) Modifier.clearAndSetSemantics { } else Modifier
    // Over the error, above its black: Back and the title stay over it, with no scrim to dim its column.
    val overError = state.error != null
    AnimatedVisibility(state.controlsVisible, Modifier.zIndex(if (overError) 1f else 0f), enter = fadeIn(tween(150)), exit = fadeOut(tween(250))) {
        Box(Modifier.fillMaxSize().then(underMenu)) {
            if (!overError) Box(Modifier.fillMaxSize().beyond(videoInsets).background(Scrim.getValue(state.hdrPicture)))
            PlayerTopBar(
                state = state,
                buttons = focus.menuButtons,
                onCommand = onCommand,
                onReach = { topReach = it },
                modifier = Modifier.align(Alignment.TopCenter).controlRegion(regions, "top"),
            )
            // Over the error, the top bar's title and Back alone: nothing behind its black to reach.
            if (state.showsPlaybackControls) {
                PlayerBottomBar(
                    state = state,
                    timeline = timeline,
                    config = config,
                    focus = focus,
                    onCommand = onCommand,
                    onTimelineFocusChanged = onTimelineFocusChanged,
                    onControlsHeight = { controlsReach = it },
                    onReach = { bottomReach = it },
                    modifier = Modifier.align(Alignment.BottomCenter).controlRegion(regions, "bottom"),
                )
            }
        }
    }

    val watch = state.watch
    when (val centre = state.centre) {
        is PlayerCentre.Prompt -> WatchPromptCard(centre.prompt, focus, onCommand, Modifier.align(Alignment.Center).controlRegion(regions, "watchPrompt"))
        is PlayerCentre.Matching -> LoadingOverlay(centre.text, state.hdrPicture, Modifier.align(Alignment.Center).layoutProbe("player.watch.matching"))
        is PlayerCentre.Loading -> LoadingOverlay(centre.text, state.hdrPicture, Modifier.align(Alignment.Center))
        // The error goes last, over the menus too.
        is PlayerCentre.Error, null -> Unit
    }

    val pill = watch?.pill
    if (pill != null || state.osd != null || state.heldSpeed != null) {
        TopMessages({ topReach }, below = belowTopBar + 12.dp) {
            pill?.let { WatchPill(it, state.hdrPicture) }
            state.heldSpeed?.let { HeldSpeedMessage(it, state.hdrPicture) }
            state.osd?.let { OsdMessage(it, state.hdrPicture) }
        }
    }

    // A swipe's level on the side it is swiped on, vertically centred, 20 dp in from the player's edge and the edge the
    // system swipes from there (the player already keeps clear of a display cutout).
    val lastLevel = remember { arrayOfNulls<LevelIndicator>(1) }
    state.level?.let { lastLevel[0] = it }
    lastLevel[0]?.let { level ->
        val left = level.kind == LevelKind.BRIGHTNESS
        val edge = with(density) { (if (left) edges.left else edges.right).times(bounds.width).toDp() } + 20.dp
        AnimatedVisibility(
            state.level != null,
            Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd).padding(start = if (left) edge else 0.dp, end = if (left) 0.dp else edge),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(160)),
        ) {
            LevelBar(level, state.hdrPicture)
        }
    }

    if (state.infoVisible) {
        // Under the top bar, and above the controls while they show and the corner's card or prompt button while they
        // do (they stay usable): what doesn't fit there scrolls. On TV the card's padding reaches past the safe area's
        // sides, so its text spans the safe area.
        val corner = if (cornerShown) with(density) { cornerHeight.toDp() } + 12.dp else 0.dp
        val bottom = (if (state.controlsVisible) aboveControls else metrics.vertical) + corner
        val side = if (metrics.tv) metrics.edge - InfoPanelPadding else metrics.edge
        // Down from the card (a stop while it scrolls) lands on the corner's card, else its prompt button, under the
        // card's end, not on whichever of them or the timeline the corner's height and width would put nearest.
        val below = when {
            state.stillWatching != null -> focus.stillWatching
            state.prompt != null -> focus.prompt
            else -> null
        }
        InfoPanel(
            state.info,
            Modifier.align(Alignment.TopEnd)
                .padding(start = side, top = belowTopBar, end = side, bottom = bottom)
                .controlRegion(regions, "info")
                .then(underMenu)
                .focusProperties { below?.let { down = it } },
        )
    }

    // With the controls up, directly above the bottom bar's top row, its trailing edge on the row's: beside a guest's lock
    // line where it fits there (its leading edge a gap past the line's text), else above the lock line. With the controls
    // down, in the bottom-trailing corner at their own margins. The card and the prompt button move as the controls
    // fade: the sleep timer's question over the button, should both come at once.
    if (cornerShown) {
        val up by animateFloatAsState(if (state.controlsVisible) 1f else 0f, tween(if (state.controlsVisible) 150 else 250), label = "corner")
        Column(
            Modifier.align(Alignment.BottomEnd)
                .padding(horizontal = metrics.edge)
                .layout { measurable, constraints ->
                    val corner = measurable.measure(constraints)
                    val gap = 12.dp.roundToPx()
                    val bar = bottomReach
                    val above = when {
                        controlsHeight <= 0f || bar == null -> aboveControls.roundToPx()
                        constraints.maxWidth + metrics.edge.roundToPx() - corner.width >= bar.lockLine + gap -> bar.topRow + gap
                        else -> controlsHeight.roundToInt() + gap
                    }
                    val hidden = metrics.vertical.roundToPx()
                    val bottom = hidden + ((above - hidden) * up).roundToInt()
                    layout(corner.width, corner.height) { corner.place(0, -bottom) }
                }
                .onSizeChanged { cornerHeight = it.height }
                .then(underMenu),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            state.stillWatching?.let { StillWatchingCard(it, focus, onCommand, Modifier.controlRegion(regions, "stillWatching")) }
            state.prompt?.let { PromptButton(it, focus, onCommand, Modifier.controlRegion(regions, "prompt")) }
        }
    }

    val room = remember(onCommand, onCopyText, onShareText) {
        RoomActions(
            onCopy = onCopyText,
            onShare = onShareText,
            onMakeHost = { onCommand(PlayerCommand.Watch(WatchCommand.MakeHost(it))) },
            onKick = { onCommand(PlayerCommand.Watch(WatchCommand.Kick(it))) },
            onControlMode = { onCommand(PlayerCommand.Watch(WatchCommand.SetControlMode(it))) },
            onLock = { onCommand(PlayerCommand.Watch(WatchCommand.Lock(it))) },
            onJoinPlayback = { onCommand(PlayerCommand.Watch(WatchCommand.JoinPlayback)) },
            onLeave = { onCommand(PlayerCommand.Watch(WatchCommand.Leave)) },
            onEnd = { onCommand(PlayerCommand.Watch(WatchCommand.End)) },
        )
    }
    if (desktop) {
        state.menu?.let { menu ->
            // The room panel opens over the session indicator, or over the options it was opened from outside a room.
            val anchor = focus.menuButtons.rect(menu) ?: focus.menuButtons.rect(PlayerMenu.OPTIONS)
            // Above the corner's cards or skip button too, as the info panel is: they stay usable. Decided as the menu
            // opens, from those showing then: one that comes, goes or changes size while it's open (the skip button
            // leaving as the intro ends) doesn't move its rows from under the pointer. The menu covers one that comes.
            val corner = remember(menu) { if (cornerShown) cornerHeight + with(density) { 12.dp.toPx() } else 0f }
            AnchoredMenu(anchor = anchor, player = bounds, limit = controlsTop - corner, edge = metrics.edge) {
                PlayerMenuPanel(menu, state, room, onCommand, Modifier.controlRegion(regions, "menu"))
            }
        }
    } else {
        // The last menu stays in its sheet while the sheet slides away.
        val lastMenu = remember { arrayOfNulls<PlayerMenu>(1) }
        state.menu?.let { lastMenu[0] = it }
        (state.menu ?: lastMenu[0])?.let { menu -> PlayerMenuSheet(menu, visible = state.menu != null, state, room, onCommand) }
    }

    state.error?.let { error ->
        ErrorPanel(error, focus, onCommand, topBar = belowTopBar, videoInsets = videoInsets, modifier = Modifier.controlRegion(regions, "error"))
    }

    // Over everything else: no touch reaches what lies under a locked screen.
    state.screenLock?.let { lock -> ScreenLockShield(lock, state.hdrPicture, onCommand, input) }
}

/**
 * The locked screen ([ScreenLockState]): a shield over the whole player that takes every touch. A tap on it
 * goes to the store as [InputEvent.Click] (which shows or hides the unlock button), a second finger as the start of a
 * pinch (which hides it), and nothing else comes of a touch. The unlock button sits at the top centre, inside the safe
 * area: a tap on it is [PlayerCommand.UnlockPress]; a finger held on it past the long-press time, or a touch that put
 * a second finger down, counts for nothing. TalkBack reaches the button as a button. Harness names:
 * `player.lockShield`, `player.unlock`.
 */
@Composable
private fun BoxScope.ScreenLockShield(lock: ScreenLockState, hdr: Boolean, onCommand: (PlayerCommand) -> Unit, onInput: (InputEvent) -> Boolean) {
    // The shield's and the button's bounds in the root, as last laid out.
    var shield by remember { mutableStateOf(Rect.Zero) }
    var button by remember { mutableStateOf(Rect.Zero) }
    val shown by rememberUpdatedState(lock.unlock != null)
    val command by rememberUpdatedState(onCommand)
    val input by rememberUpdatedState(onInput)
    Box(
        Modifier
            .fillMaxSize()
            .layoutProbe("player.lockShield")
            .onGloballyPositioned { shield = it.boundsInRoot() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val onButton = shown && button.contains(down.position + shield.topLeft)
                    var multiTouch = false
                    var moved = false
                    var last = down
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        event.changes.firstOrNull { it.id == down.id }?.let { last = it }
                        if (!multiTouch && event.changes.count { it.pressed } > 1) {
                            multiTouch = true
                            input(InputEvent.Pinch(1.0))
                            input(InputEvent.PinchEnd)
                        }
                        if ((last.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (event.changes.none { it.pressed }) break
                    }
                    if (multiTouch || moved) return@awaitEachGesture
                    val held = last.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                    when {
                        onButton -> if (!held) command(PlayerCommand.UnlockPress)
                        else -> input(InputEvent.Click(if (size.width > 0) (down.position.x / size.width).coerceIn(0f, 1f) else 0.5f))
                    }
                }
            },
    )
    val lastUnlock = remember { arrayOfNulls<UnlockButton>(1) }
    lock.unlock?.let { lastUnlock[0] = it }
    lastUnlock[0]?.let { unlock ->
        AnimatedVisibility(
            lock.unlock != null,
            Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            UnlockButtonPill(unlock, hdr, onUnlock = { command(PlayerCommand.UnlockPress) }, Modifier.onGloballyPositioned { button = it.boundsInRoot() })
        }
    }
}

/**
 * The messages at the top, the room's pill then the transient message: side by side in the top bar's row, centred on
 * the player and level with close, 12 dp clear of close, the title's text and the session indicator, where the room
 * between those holds them; else (a phone upright, a long title, before the bar's first layout) [below] the player's
 * top, under the bar, one above the other. [row] is the bar as it last laid out with the controls up: the messages stay
 * put as the controls come and go.
 */
@Composable
private fun TopMessages(row: () -> TopBarReach?, below: Dp, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val messages = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val spacing = 8.dp.roundToPx()
        val gap = 12.dp.roundToPx()
        val width = constraints.maxWidth
        val inRow = messages.sumOf { it.width } + spacing * (messages.size - 1)
        // Centred on the player: both sides kept as far in as the further one reaches, and a gap.
        val bar = row()?.takeIf { inRow <= width - 2 * (max(it.leading, it.trailing) + gap) }
        layout(width, constraints.maxHeight) {
            if (bar != null) {
                var x = (width - inRow) / 2
                for (message in messages) {
                    message.placeRelative(x, bar.middle - message.height / 2)
                    x += message.width + spacing
                }
            } else {
                var y = below.roundToPx()
                for (message in messages) {
                    message.placeRelative((width - message.width) / 2, y)
                    y += message.height + spacing
                }
            }
        }
    }
}

/**
 * Places [content] above [limit] (root coordinates: the controls' top, or the corner's cards' over them), centred on
 * [anchor] (the menu's button) and kept inside the [player], [edge] from its sides as the controls are: a desktop menu
 * opens where the pointer is and leaves the timeline uncovered. Without an anchor, at the end.
 */
@Composable
private fun AnchoredMenu(anchor: Rect?, player: Rect, limit: Float, edge: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val margin = with(density) { 12.dp.roundToPx() }
    val side = with(density) { edge.roundToPx() }
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val bottom = when {
            !limit.isNaN() -> limit - player.top
            anchor != null -> anchor.top - player.top
            else -> constraints.maxHeight * 0.8f
        }.roundToInt() - margin
        val panel = measurables.first().measure(
            Constraints(maxWidth = (constraints.maxWidth - 2 * side).coerceAtLeast(0), maxHeight = (bottom - margin).coerceAtLeast(0)),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val center = (anchor?.center?.x?.minus(player.left))?.roundToInt() ?: (constraints.maxWidth - side - panel.width / 2)
            val x = (center - panel.width / 2).coerceIn(side, (constraints.maxWidth - side - panel.width).coerceAtLeast(side))
            panel.place(x, (bottom - panel.height).coerceAtLeast(margin))
        }
    }
}

/**
 * Focus targets the player moves focus to (TV, an error's action, and after a menu closes), the menu buttons, and
 * whether the player (its controls, or the player itself) holds focus: a menu sheet holds it elsewhere until it has
 * gone. [onControl]: one of the player's controls holds it, not the player itself.
 */
internal class PlayerFocus {
    val playPause = FocusRequester()
    val stillWatching = FocusRequester()
    val prompt = FocusRequester()
    val error = FocusRequester()
    val watchPrompt = FocusRequester()
    val menuButtons = MenuButtons()
    var inPlayer = false
    var onControl = false
}

/**
 * The edges of the player ([bounds], in the window) the system swipes from, in shares of the player's size: Android's
 * back gesture at the sides, the notification shade at the top and the home gesture at the bottom
 * ([WindowInsets.systemGestures], which the system reports with its bars hidden too), less what of them lies outside
 * the player. None where the system takes none (three-button navigation's sides, the desktop).
 */
@Composable
private fun systemEdges(bounds: Rect): SystemEdges {
    val insets = WindowInsets.systemGestures
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    if (bounds.width <= 0f || bounds.height <= 0f) return SystemEdges.NONE
    return SystemEdges(
        left = (insets.getLeft(density, LayoutDirection.Ltr) - bounds.left).coerceAtLeast(0f) / bounds.width,
        top = (insets.getTop(density) - bounds.top).coerceAtLeast(0f) / bounds.height,
        right = (insets.getRight(density, LayoutDirection.Ltr) - (window.width - bounds.right)).coerceAtLeast(0f) / bounds.width,
        bottom = (insets.getBottom(density) - (window.height - bounds.bottom)).coerceAtLeast(0f) / bounds.height,
    )
}

/** What one finger (or a mouse's button) on the video came to before it lifted or held still long enough. */
private enum class VideoTouch { TAP, DRAG, NONE }

/**
 * One finger, a mouse or a pen on the video: [onInput] gets a tap or click as [InputEvent.Click] (with the platform's
 * click count) as it lifts where it went down. A finger held still past the long-press time is [InputEvent.Hold] (true,
 * then false as it lifts or the touch is cancelled) and no tap, where the store takes it; where it doesn't (live, a
 * mouse's keymap) it is the tap it was. A finger dragging clearly up or down is [InputEvent.Swipe] on every move, from
 * where it went down, with the system's [edges] then; one the store leaves alone (swipes off, a system edge), a
 * sideways drag or a second finger (a pinch, [detectPinch], whose consumed changes end it) is nothing.
 */
private suspend fun PointerInputScope.detectVideoTouch(edges: () -> SystemEdges, onInput: (InputEvent) -> Boolean) {
    awaitEachGesture {
        val down = awaitFirstDown()
        if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        val touch = down.type == PointerType.Touch
        var last = down
        suspend fun AwaitPointerEventScope.firstMove(): VideoTouch {
            while (true) {
                val event = awaitPointerEvent()
                // A pinch consumes its changes: no tap or swipe comes of it.
                if (event.changes.any { it.isConsumed }) return VideoTouch.NONE
                val change = event.changes.firstOrNull { it.id == down.id } ?: return VideoTouch.NONE
                last = change
                val moved = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                if (event.changes.none { it.pressed }) return if (moved) VideoTouch.NONE else VideoTouch.TAP
                if (moved && touch) return if (event.changes.count { it.pressed } == 1) VideoTouch.DRAG else VideoTouch.NONE
            }
        }
        var outcome = if (touch) withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { firstMove() } else firstMove()
        if (outcome == null) {
            // Held still past the long-press time.
            if (onInput(InputEvent.Hold(true))) {
                try {
                    while (currentEvent.changes.any { it.pressed }) awaitPointerEvent()
                } finally {
                    onInput(InputEvent.Hold(false))
                }
                return@awaitEachGesture
            }
            outcome = firstMove()
        }
        when (outcome) {
            VideoTouch.TAP -> {
                val x = if (size.width > 0) (last.position.x / size.width).coerceIn(0f, 1f) else 0.5f
                if (onInput(InputEvent.Click(x, currentEvent.platformClickCount()))) last.consume()
            }
            VideoTouch.DRAG -> swipe(down, last, edges(), onInput)
            VideoTouch.NONE -> Unit
        }
    }
}

/**
 * A finger that went [down] and has moved past the touch slop to [moved]: up or down more than sideways, a swipe the
 * store takes, its moves consumed, until it lifts or a second finger comes.
 */
private suspend fun AwaitPointerEventScope.swipe(
    down: PointerInputChange,
    moved: PointerInputChange,
    edges: SystemEdges,
    onInput: (InputEvent) -> Boolean,
) {
    val travel = moved.position - down.position
    if (abs(travel.y) <= abs(travel.x) || size.height <= 0) return
    val x = (down.position.x / size.width).coerceIn(0f, 1f)
    val y = (down.position.y / size.height).coerceIn(0f, 1f)
    var at = down.position.y
    fun send(change: PointerInputChange): Boolean {
        val distance = (at - change.position.y) / size.height
        at = change.position.y
        return onInput(InputEvent.Swipe(x, y, distance.toDouble(), edges))
    }
    if (!send(moved)) return
    moved.consume()
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return
        if (!change.pressed || change.isConsumed || event.changes.count { it.pressed } > 1) return
        if (change.positionChanged()) {
            send(change)
            change.consume()
        }
    }
}

/**
 * Two fingers on a touch screen pinching the video: [onInput] gets [InputEvent.Pinch] with their spread relative to
 * where the pinch began on every move, and [InputEvent.PinchEnd] once fewer than two fingers remain (or the gesture is
 * cancelled). Its changes are consumed, so the tap detector above makes no tap of it. One finger, a mouse or a pen
 * passes through untouched.
 */
private suspend fun PointerInputScope.detectPinch(onInput: (InputEvent) -> Boolean) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var scale = 1f
        var pinching = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed && it.type == PointerType.Touch }
                if (pressed < 2) {
                    if (pinching || event.changes.none { it.pressed }) break
                    continue
                }
                if (!pinching) {
                    pinching = true
                    scale = 1f
                    onInput(InputEvent.Pinch(1.0))
                }
                val zoom = event.calculateZoom()
                if (zoom != 1f && zoom.isFinite()) {
                    scale *= zoom
                    onInput(InputEvent.Pinch(scale.toDouble()))
                }
                // The second finger's down too: a two-finger touch is no tap.
                event.changes.forEach { it.consume() }
            }
        } finally {
            if (pinching) onInput(InputEvent.PinchEnd)
        }
    }
}

/** Where the controls are, in root coordinates, so a hover can tell whether it rests on them. */
private class ControlRegions {
    /** The player's own position in the root. */
    var origin: Offset = Offset.Zero
    val rects = HashMap<String, Rect>()

    fun contains(local: Offset): Boolean {
        val point = local + origin
        return rects.values.any { it.contains(point) }
    }
}

/**
 * Registers this element as part of the controls under [name] (a hover over it keeps them up) and keeps clicks on its
 * empty parts from reaching the video beneath.
 */
private fun Modifier.controlRegion(regions: ControlRegions, name: String): Modifier =
    this then ControlRegionElement(regions, name) then Modifier.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent() }
    }

private data class ControlRegionElement(val regions: ControlRegions, val name: String) : ModifierNodeElement<ControlRegionNode>() {
    override fun create() = ControlRegionNode(regions, name)

    override fun update(node: ControlRegionNode) {
        node.regions.rects.remove(node.name)
        node.regions = regions
        node.name = name
    }
}

private class ControlRegionNode(var regions: ControlRegions, var name: String) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        regions.rects[name] = coordinates.boundsInRoot()
    }

    override fun onDetach() {
        regions.rects.remove(name)
    }
}
