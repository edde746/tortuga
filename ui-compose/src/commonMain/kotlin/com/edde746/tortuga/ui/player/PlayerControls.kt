package com.edde746.tortuga.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.player.ControlsScrim
import com.edde746.tortuga.presentation.player.PlaybackMode
import com.edde746.tortuga.presentation.player.PlayerConfig
import com.edde746.tortuga.presentation.player.PlayerUiState
import com.edde746.tortuga.presentation.player.ScrubState
import com.edde746.tortuga.presentation.player.TimelineState
import com.edde746.tortuga.presentation.watch.PlayerWatchUi
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.focus.isFocusVisible
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

/**
 * The controls' sizes for the window at hand: compact on every form factor, a step larger on TV. The controls keep
 * [edge] from the window's sides (the screen padding: overscan-safe on TV) and [vertical] from its top and bottom; icon
 * buttons reach [inset] past the edge, so their icons line up with the time labels and the title.
 */
@Immutable
internal class PlayerMetrics(
    val tv: Boolean,
    val edge: Dp,
    val vertical: Dp,
    val button: Dp,
    val icon: Dp,
    val playButton: Dp,
    val playIcon: Dp,
    val gap: Dp,
    val timeline: Dp,
) {
    val inset: Dp get() = (button - icon) / 2
}

@Composable
internal fun rememberPlayerMetrics(): PlayerMetrics {
    val layout = LocalWindowLayout.current
    val edge = LocalTortugaDimens.current.screenPadding
    return remember(layout.formFactor, edge) {
        if (layout.formFactor == FormFactor.TV) {
            PlayerMetrics(tv = true, edge = edge, vertical = 28.dp, button = 48.dp, icon = 28.dp, playButton = 56.dp, playIcon = 36.dp, gap = 8.dp, timeline = 40.dp)
        } else {
            PlayerMetrics(tv = false, edge = edge, vertical = 12.dp, button = 40.dp, icon = 24.dp, playButton = 48.dp, playIcon = 32.dp, gap = 2.dp, timeline = 32.dp)
        }
    }
}

/**
 * The menu buttons: where each is, in root coordinates, while it is laid out (a desktop menu opens over the button that
 * opened it, or the options' when that button is gone: the session indicator after leaving a room), and its focus (on
 * TV focus goes back to that button when its menu closes). A menu belongs to its [PlayerMenu.parent]'s button.
 */
internal class MenuButtons {
    private val rects = HashMap<PlayerMenu, Rect>()
    private val requesters = HashMap<PlayerMenu, FocusRequester>()

    fun rect(menu: PlayerMenu): Rect? = rects[menu.parent]

    fun focus(menu: PlayerMenu): FocusRequester = requesters.getOrPut(menu.parent) { FocusRequester() }

    /** This element is [menu]'s button: its place is kept while it is laid out and goes as it leaves. */
    fun place(menu: PlayerMenu): Modifier = MenuButtonElement(this, menu.parent)

    private data class MenuButtonElement(val buttons: MenuButtons, val menu: PlayerMenu) : ModifierNodeElement<MenuButtonNode>() {
        override fun create() = MenuButtonNode(buttons, menu)

        override fun update(node: MenuButtonNode) {
            node.buttons.rects.remove(node.menu)
            node.buttons = buttons
            node.menu = menu
        }
    }

    private class MenuButtonNode(var buttons: MenuButtons, var menu: PlayerMenu) : Modifier.Node(), GlobalPositionAwareModifierNode {
        override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
            buttons.rects[menu] = coordinates.boundsInRoot()
        }

        override fun onDetach() {
            buttons.rects.remove(menu)
        }
    }
}

/**
 * The top bar's row as it last laid out with the controls up, in pixels: how far close and the title's text (its lines'
 * own width, not the room they have) reach in from the bar's leading side, how far the session indicator reaches in
 * from its trailing side (0 without one), and close's middle, down from the bar's top. The messages at the top go
 * between the two, level with close (PlayerOverlays).
 */
@Immutable
internal data class TopBarReach(val leading: Int, val trailing: Int, val middle: Int)

/**
 * The bottom bar as it last laid out, in pixels: its top row's top (the timeline row's; live, the buttons'), up from the
 * bar's bottom edge, and how far the lock line's text reaches in from the bar's leading side (0 without one). The
 * bottom-trailing corner goes directly above that row, beside the lock line where it fits there (PlayerOverlays).
 */
@Immutable
internal data class BottomBarReach(val topRow: Int, val lockLine: Int)

/** A bar's layout and its named parts', kept as they're placed: [bounds] is a part's in the bar, null once it's gone. */
private class BarParts {
    private var bar: LayoutCoordinates? = null
    private val parts = HashMap<String, LayoutCoordinates>()

    val size: IntSize get() = bar?.size ?: IntSize.Zero

    fun bar(onPlaced: () -> Unit): Modifier = Modifier.onGloballyPositioned {
        bar = it
        onPlaced()
    }

    fun part(name: String, onPlaced: () -> Unit): Modifier = Modifier.onGloballyPositioned {
        parts[name] = it
        onPlaced()
    }

    fun bounds(name: String): Rect? {
        val bar = bar?.takeIf { it.isAttached } ?: return null
        val part = parts[name]?.takeIf { it.isAttached } ?: return null
        return bar.localBoundingBoxOf(part, clipBounds = false)
    }
}

/**
 * Close, then the title and the episode line; in a watch-together room, the session indicator at the end (not over the
 * error, [PlayerUiState.showsPlaybackControls]). While the controls are up [onReach] gets how far its parts reach into
 * the row as they lay out ([TopBarReach]); going away, the bar may lay out once more as it fades, which doesn't count.
 * Harness names: `player.back`, `player.title` (the title's lines, as wide as their text), `player.watch`.
 */
@Composable
internal fun PlayerTopBar(
    state: PlayerUiState,
    buttons: MenuButtons,
    onCommand: (PlayerCommand) -> Unit,
    onReach: (TopBarReach) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberPlayerMetrics()
    val typography = MaterialTheme.typography
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val parts = remember { BarParts() }
    fun report() {
        if (!state.controlsVisible) return
        val close = parts.bounds(CLOSE) ?: return
        val title = parts.bounds(TITLE) ?: return
        val width = parts.size.width
        // The title's lines follow close: as far as they reach, so does close.
        val leading = if (ltr) title.right else width - title.left
        val trailing = parts.bounds(INDICATOR)?.let { if (ltr) width - it.left else it.right } ?: 0f
        onReach(TopBarReach(leading.roundToInt(), trailing.roundToInt(), close.center.y.roundToInt()))
    }
    // A part that goes (the session indicator, as the room ends) lays nothing out: it's left out from the next frame.
    SideEffect { report() }
    Row(
        modifier
            .fillMaxWidth()
            .then(parts.bar(::report))
            .padding(start = metrics.edge - metrics.inset, end = metrics.edge, top = metrics.vertical, bottom = metrics.vertical * 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlayerButton(
            TortugaIcons.ArrowBack,
            T.nav.player.close,
            onClick = { onCommand(PlayerCommand.Close) },
            modifier = parts.part(CLOSE, ::report),
            probe = "player.back",
        )
        Box(Modifier.weight(1f)) {
            Column(parts.part(TITLE, ::report).layoutProbe("player.title", reportsFocus = false)) {
                Text(
                    state.title,
                    style = (if (metrics.tv) typography.titleLarge else typography.titleMedium).contentDirection(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.subtitle?.let {
                    Text(
                        it,
                        style = if (metrics.tv) typography.bodyMedium else typography.bodySmall,
                        color = mutedText(state.hdrPicture),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (state.showsPlaybackControls) {
            state.watch?.indicator?.let { SessionIndicator(it, state.menu, state.hdrPicture, buttons, onCommand, parts.part(INDICATOR, ::report)) }
        }
    }
}

private const val CLOSE = "close"
private const val TITLE = "title"
private const val INDICATOR = "indicator"
private const val TOP_ROW = "topRow"
private const val LOCK_LINE = "lockLine"

/**
 * The timeline with the position and the time left beside it, then one row of buttons: the transport on the left,
 * the menus on the right. The transport is rewind, play/pause and forward; an episode of a series adds the previous
 * episode before them and the next one after (both always there, each off where there is no episode that way, and
 * the next one there while the up-next button offers it too). Like the timeline above it, the transport runs left to
 * right in every language. A window too narrow for both groups on one line (a narrow phone in portrait) puts the
 * menus on a line of their own, at its end. [onControlsHeight] gets the height in pixels from the top of the lock line
 * (or the bar's top row) to the bar's bottom edge (the bar's own measure, so it holds whatever the player's place in
 * the window): everything in it is the controls, which subtitles stay above. [onReach] gets where the top row's top
 * and the lock line's text are as they lay out ([BottomBarReach]): the prompt button goes beside the lock line where it
 * fits.
 *
 * Live ([PlaybackMode.LIVE]) there is no timeline row and no rewind or forward ([PlayerUiState.showsTimeline],
 * [PlayerUiState.showsSeekButtons]): the live label ([LiveLabel]) follows play/pause, the other buttons where they are
 * for a video. Live with a rewind window ([PlaybackMode.LIVE_WINDOW]) the timeline row is back as the window, from its
 * start to the live edge, scrubbed as a video's (a remote's Left and Right, a finger, a click or drag, the arrow keys),
 * and the live label is the go-to-live control. Those two alone collect the [timeline]; the rest of the bar is drawn
 * again only as [state] changes.
 *
 * On TV focus comes to the bar on play/pause ([PlayerFocus.playPause]), in the middle of the transport.
 *
 * In a watch-together room the guest of a host-only room has play/pause, the skips, the timeline and the speed off,
 * with why above the timeline ([PlayerWatchUi.controlsLocked]); changing the episode is the host's alone (the store
 * turns both episode buttons off for anyone else, [PlayerWatchUi.canChangeEpisode]). A guest whose player paused here
 * alone while the room plays on has play/pause on, whose Play catches up with the room, and the line says so
 * ([PlayerWatchUi.pausedHere]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerBottomBar(
    state: PlayerUiState,
    timeline: StateFlow<TimelineState>,
    config: PlayerConfig,
    focus: PlayerFocus,
    onCommand: (PlayerCommand) -> Unit,
    onTimelineFocusChanged: (Boolean) -> Unit,
    onControlsHeight: (Int) -> Unit,
    onReach: (BottomBarReach) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberPlayerMetrics()
    val desktop = LocalWindowLayout.current.formFactor == FormFactor.DESKTOP
    val watch = state.watch
    val canControl = state.canControl
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val parts = remember { BarParts() }
    fun report() {
        val topRow = parts.bounds(TOP_ROW) ?: return
        val size = parts.size
        val lockLine = parts.bounds(LOCK_LINE)?.let { if (ltr) it.right else size.width - it.left } ?: 0f
        onReach(BottomBarReach((size.height - topRow.top).roundToInt(), lockLine.roundToInt()))
    }
    // The lock line gone (the guest given control) lays nothing out: it's left out from the next frame.
    SideEffect { report() }
    Column(
        modifier
            .fillMaxWidth()
            .then(parts.bar(::report))
            .padding(start = metrics.edge - metrics.inset, end = metrics.edge - metrics.inset, top = metrics.vertical * 3)
            // Below the bar's top margin: the lock line, the timeline row, the buttons and the bottom margin.
            .onSizeChanged { onControlsHeight(it.height) }
            .padding(bottom = metrics.vertical),
    ) {
        watch?.lockLine?.let { ControlsLockedLine(it, mutedText(state.hdrPicture), parts.part(LOCK_LINE, ::report).padding(start = metrics.inset, bottom = 4.dp)) }
        if (state.showsTimeline) {
            TimelineRow(
                timeline,
                seekable = state.canSeek,
                onCommand,
                onTimelineFocusChanged,
                parts.part(TOP_ROW, ::report).padding(horizontal = metrics.inset),
            )
        }
        FlowRow(
            Modifier.fillMaxWidth().then(if (state.showsTimeline) Modifier else parts.part(TOP_ROW, ::report)),
            horizontalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            // Time runs left to right on the timeline in every language, and so do the skips and episodes under it.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    Modifier.align(Alignment.CenterVertically),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metrics.gap),
                ) {
                    val episodes = state.episodes
                    if (episodes != null) {
                        PlayerButton(
                            TortugaIcons.SkipPrevious,
                            T.player.previousEpisode,
                            onClick = { onCommand(PlayerCommand.PreviousEpisode) },
                            probe = "player.previous",
                            enabled = episodes.previous,
                        )
                    }
                    if (state.showsSeekButtons) {
                        PlayerButton(
                            TortugaIcons.Replay10,
                            T.nav.player.rewind,
                            onClick = { onCommand(PlayerCommand.SeekBy(-config.steps.seekSeconds)) },
                            probe = "player.rewind",
                            enabled = canControl,
                        )
                    }
                    PlayerButton(
                        if (state.paused) TortugaIcons.PlayArrow else TortugaIcons.Pause,
                        state.playPauseLabel,
                        onClick = { onCommand(PlayerCommand.TogglePause) },
                        probe = "player.playPause",
                        large = true,
                        enabled = state.canPlayPause,
                        focusRequester = focus.playPause,
                    )
                    if (state.showsSeekButtons) {
                        PlayerButton(
                            TortugaIcons.Forward10,
                            T.nav.player.fastForward,
                            onClick = { onCommand(PlayerCommand.SeekBy(config.steps.seekSeconds)) },
                            probe = "player.fastForward",
                            enabled = canControl,
                        )
                    } else {
                        val window = state.mode == PlaybackMode.LIVE_WINDOW
                        // The go-to-live pill's own padding stands in for the label's gap: its text stays where it was.
                        LiveLabel(
                            timeline,
                            window,
                            Modifier.padding(start = if (window) 0.dp else LiveLabelGap),
                            onGoToLive = { onCommand(PlayerCommand.GoToLive) },
                        )
                    }
                    if (episodes != null) {
                        PlayerButton(
                            TortugaIcons.SkipNext,
                            T.player.nextEpisode,
                            onClick = { onCommand(PlayerCommand.NextEpisode) },
                            probe = "player.next",
                            enabled = episodes.next,
                        )
                    }
                }
            }
            Row(
                Modifier.weight(1f).align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metrics.gap, Alignment.End),
            ) {
                if (desktop) VolumeControl(state, onCommand)
                if (state.showsAudioMenu) {
                    MenuButton(TortugaIcons.Audiotrack, T.player.menu.audio, PlayerMenu.AUDIO, state.menu, focus.menuButtons, onCommand, probe = "player.audio")
                }
                if (state.showsSubtitleMenu) {
                    MenuButton(TortugaIcons.Subtitles, T.player.menu.subtitles, PlayerMenu.SUBTITLES, state.menu, focus.menuButtons, onCommand, probe = "player.subtitles")
                }
                MenuButton(TortugaIcons.Speed, T.player.menu.speed, PlayerMenu.SPEED, state.menu, focus.menuButtons, onCommand, probe = "player.speed", enabled = canControl)
                MenuButton(TortugaIcons.Tune, T.player.options.title, PlayerMenu.OPTIONS, state.menu, focus.menuButtons, onCommand, probe = "player.options")
                if (desktop) {
                    PlayerButton(
                        if (state.fullscreen) TortugaIcons.FullscreenExit else TortugaIcons.Fullscreen,
                        if (state.fullscreen) T.nav.player.exitFullscreen else T.nav.player.fullscreen,
                        onClick = { onCommand(PlayerCommand.ToggleFullscreen) },
                        probe = "player.fullscreen",
                    )
                }
            }
        }
    }
}

/**
 * `0:15 ━━━━○──── −0:15`: the position, the timeline and the time left on one line, as [timeline] has them now;
 * [seekable] false: no seeking. A live window's (`−1:30 ━━━━━━○━━`) has where the window starts on the left and nothing
 * on the right: the bar ends at the live edge.
 */
@Composable
private fun TimelineRow(
    timeline: StateFlow<TimelineState>,
    seekable: Boolean,
    onCommand: (PlayerCommand) -> Unit,
    onTimelineFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberPlayerMetrics()
    val style = timeStyle()
    val now by timeline.collectAsState()
    // Time runs left to right on a timeline in every language: the bar is drawn, hit-tested and keyed that way.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeLabel(now.position, style, TortugaColors.OnVideo, "player.position")
            ScrubBar(
                timeline = now,
                height = metrics.timeline,
                seekable = seekable,
                onCommand = onCommand,
                onFocusChanged = onTimelineFocusChanged,
                modifier = Modifier.weight(1f).layoutProbe("player.scrub"),
            )
            if (now.remaining.isNotEmpty()) TimeLabel(now.remaining, style, TortugaColors.OnVideo, "player.remaining")
        }
    }
}

/** The timeline's times' style, with tabular figures: they don't shift as the digits change. */
@Composable
private fun timeStyle(): TextStyle =
    (if (rememberPlayerMetrics().tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge).copy(fontFeatureSettings = "tnum")

/**
 * `● LIVE · 0:07`: a red dot ([TortugaColors.Live]), then the live text ([TimelineState.liveLabel]) in the timeline's
 * time style and white, read as [TimelineState.positionLabel], as [timeline] has them now. Plain text: nothing to
 * focus, a tap on it does nothing (the bar keeps it from the video). Harness name `player.live`.
 *
 * With a rewind window ([window]) it is the go-to-live button ([onGoToLive]), a pill focused and pressed as every
 * control is (focused: the primary fill, black text): at the live edge as above; behind it the dot the text's colour
 * muted (grey, dark grey on the fill) and the label with the time behind (`● LIVE · −0:40`).
 */
@Composable
private fun LiveLabel(timeline: StateFlow<TimelineState>, window: Boolean, modifier: Modifier = Modifier, onGoToLive: () -> Unit) {
    val now by timeline.collectAsState()
    if (!window) {
        Row(
            modifier.layoutProbe("player.live").clearAndSetSemantics { contentDescription = now.positionLabel },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LiveLabelContent(TortugaColors.Live, now.liveLabel, TortugaColors.OnVideo)
        }
        return
    }
    val metrics = rememberPlayerMetrics()
    TortugaSurface(
        onClick = onGoToLive,
        modifier = modifier
            .height(metrics.button)
            .layoutProbe("player.live")
            .clearAndSetSemantics {
                contentDescription = "${T.player.live.goToLive}${T.detail.listSeparator}${now.positionLabel}"
                role = Role.Button
                onClick { onGoToLive(); true }
            },
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = TortugaColors.OnVideo,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val text = LocalContentColor.current
            LiveLabelContent(if (now.atLiveEdge) TortugaColors.Live else text.copy(alpha = 0.6f), now.liveLabel, text)
        }
    }
}

@Composable
private fun LiveLabelContent(dot: Color, label: String, text: Color) {
    Box(Modifier.size(10.dp).background(dot, CircleShape))
    Text(label, style = timeStyle(), color = text, maxLines = 1)
}

/** Between play/pause and the live label, past the transport's own gap. */
private val LiveLabelGap = 16.dp

/** The controls' muted text colour over the video, their full white over an HDR picture ([ControlsScrim.textMuted]). */
internal fun mutedText(hdr: Boolean): Color = if (ControlsScrim.textMuted(hdr)) TortugaColors.OnVideoMuted else TortugaColors.OnVideo

@Composable
private fun TimeLabel(text: String, style: TextStyle, color: Color, probe: String) {
    Text(text, style = style, color = color, maxLines = 1, modifier = Modifier.layoutProbe(probe))
}

@Composable
private fun MenuButton(
    icon: ImageVector,
    label: String,
    menu: PlayerMenu,
    open: PlayerMenu?,
    buttons: MenuButtons,
    onCommand: (PlayerCommand) -> Unit,
    probe: String,
    enabled: Boolean = true,
) {
    // The delay menus belong to the audio and subtitle buttons, the More subtitles pages to the subtitle button, the
    // options' pages to the options button.
    val owns = open?.parent == menu
    PlayerButton(
        icon,
        label,
        onClick = { onCommand(if (owns) PlayerCommand.CloseMenu else PlayerCommand.OpenMenu(menu)) },
        modifier = buttons.place(menu),
        probe = probe,
        selected = owns,
        enabled = enabled,
        focusRequester = buttons.focus(menu),
    )
}

/** Mute and a volume slider (desktop; phones and TVs have hardware volume). */
@Composable
private fun VolumeControl(state: PlayerUiState, onCommand: (PlayerCommand) -> Unit) {
    val volume = state.volume
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlayerButton(
            if (volume.muted) TortugaIcons.VolumeOff else TortugaIcons.VolumeUp,
            if (volume.muted) T.player.volume.unmute else T.player.volume.mute,
            onClick = { onCommand(PlayerCommand.ToggleMute) },
            probe = "player.mute",
        )
        VolumeSlider(
            value = if (volume.muted) 0f else volume.fraction,
            onValueChange = { onCommand(PlayerCommand.SetVolume(it * 100.0)) },
            modifier = Modifier
                .width(96.dp)
                .semantics {
                    contentDescription = T.player.volume.title
                    stateDescription = volume.label
                },
        )
    }
}

/**
 * The volume, drawn as the timeline is: a thin track filled up to the value and a small round thumb in the primary
 * colour. Under the pointer or a drag the thumb and the track grow a step; keyboard focus (while focus shows) also
 * haloes the thumb. Material's [Slider] underneath carries the dragging, the arrow keys and the semantics.
 */
@Composable
private fun VolumeSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val focusedRaw by interaction.collectIsFocusedAsState()
    val focused = focusedRaw && isFocusVisible()
    val active = hovered || pressed || dragged || focused
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.hoverable(interaction),
        interactionSource = interaction,
        thumb = {
            // A box of the largest thumb's size, so the track keeps its length as the thumb grows; the halo spills
            // past it.
            Spacer(
                Modifier.size(VolumeThumbMax).drawBehind {
                    val radius = (if (active) VolumeThumbMax else VolumeThumb).toPx() / 2
                    if (focused) drawCircle(TortugaColors.OnVideo.copy(alpha = 0.24f), radius = radius + 6.dp.toPx())
                    drawCircle(TortugaColors.OnVideo, radius = radius)
                },
            )
        },
        track = { slider ->
            val range = slider.valueRange
            val fraction = ((slider.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(if (active) 6.dp else 4.dp)) {
                val radius = CornerRadius(size.height / 2)
                val filled = size.width * fraction
                // The fill grows from the slider's start, as the thumb moves: the right in a right-to-left layout.
                val start = if (layoutDirection == LayoutDirection.Rtl) size.width - filled else 0f
                drawRoundRect(TortugaColors.ProgressTrack, cornerRadius = radius)
                drawRoundRect(TortugaColors.OnVideo, topLeft = Offset(start, 0f), size = Size(filled, size.height), cornerRadius = radius)
            }
        },
    )
}

/** The volume thumb's diameter at rest, and under the pointer, a drag or keyboard focus. */
private val VolumeThumb = 12.dp
private val VolumeThumbMax = 16.dp

/**
 * The timeline: played, buffered and chapter marks; while scrubbing ([TimelineState.scrub]), the preview's position
 * with its time, offset and chapter in a bubble above it. Drag or click to scrub (committed on release), on the bar or
 * anywhere on its thumb: at the bar's ends (its start, a live window's edge) half the thumb lies past the bar, so the
 * pointer area reaches half the largest thumb past each end, the bar where it is. Focusable while [seekable], so the TV
 * keymap can scrub with left/right while it has focus; focus (while focus shows) thickens the bar and haloes the thumb.
 * While the video plays the played part and the thumb move on at each frame between the store's reports
 * ([TimelineState.progressNow]); only drawing and placing them read it, so nothing is composed again for it.
 */
@Composable
private fun ScrubBar(
    timeline: TimelineState,
    height: Dp,
    seekable: Boolean,
    onCommand: (PlayerCommand) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focusedRaw by interaction.collectIsFocusedAsState()
    val focused = focusedRaw && isFocusVisible()
    val scrub = timeline.scrub
    val progress = scrub?.progress ?: timeline.progress
    val motion = timeline.motion
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(motion) {
        if (motion != null) while (true) withFrameNanos { frame.longValue = it }
    }

    /** The played fraction for the frame being drawn. */
    fun played(): Float {
        frame.longValue
        return (scrub?.progress ?: timeline.progressNow()).coerceIn(0f, 1f)
    }
    val active = scrub != null || hovered || focused
    val reach = ScrubThumbFocused / 2
    BoxWithConstraints(
        modifier
            .height(height)
            .semantics {
                contentDescription = T.player.timeline
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                stateDescription = timeline.positionLabel
                // A screen reader's seek (a live window's rewind too, which has no ±10 s buttons).
                if (seekable) {
                    setProgress { fraction ->
                        onCommand(PlayerCommand.SeekToFraction(fraction.toDouble()))
                        true
                    }
                }
            }
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .focusable(enabled = seekable, interactionSource = interaction)
            .beyond(WindowInsets(left = reach, right = reach))
            .hoverable(interaction)
            .pointerInput(seekable) {
                if (!seekable) return@pointerInput
                // The bar starts and ends [reach] inside the pointer area.
                val past = reach.roundToPx()
                val bar = size.width - 2 * past
                fun fraction(x: Float) = if (bar > 0) ((x - past) / bar).coerceIn(0f, 1f).toDouble() else 0.0
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onCommand(PlayerCommand.ScrubTo(fraction(down.position.x)))
                    var ended = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) break
                            if (!change.pressed) {
                                change.consume()
                                ended = true
                                onCommand(PlayerCommand.CommitScrub)
                                break
                            }
                            if (change.positionChanged()) {
                                change.consume()
                                onCommand(PlayerCommand.ScrubTo(fraction(change.position.x)))
                            }
                        }
                    } finally {
                        // Not released on the bar: the pointer went, or the bar did mid-drag (picture in picture).
                        if (!ended) onCommand(PlayerCommand.CancelScrub)
                    }
                }
            }
            .padding(horizontal = reach),
        contentAlignment = Alignment.CenterStart,
    ) {
        val barHeight = when {
            focused -> 8.dp
            active -> 6.dp
            else -> 4.dp
        }
        val chapters = timeline.chapters
        Canvas(Modifier.fillMaxWidth().height(barHeight)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(TortugaColors.ProgressTrack, cornerRadius = radius)
            drawRoundRect(TortugaColors.ProgressTrack, size = Size(size.width * timeline.buffered.coerceIn(0f, 1f), size.height), cornerRadius = radius)
            drawRoundRect(TortugaColors.OnVideo, size = Size(size.width * played(), size.height), cornerRadius = radius)
            // Chapter starts as gaps in the bar.
            val gap = 2.dp.toPx()
            for (mark in chapters) {
                if (mark <= 0f || mark >= 1f) continue
                drawRect(Color.Black.copy(alpha = 0.7f), topLeft = Offset(size.width * mark - gap / 2, 0f), size = Size(gap, size.height))
            }
        }
        val thumb = when {
            focused -> ScrubThumbFocused
            active -> ScrubThumbActive
            else -> ScrubThumb
        }
        // Focus (TV scrubbing) haloes the thumb, hugging it.
        if (focused) {
            val halo = thumb + 12.dp
            Box(Modifier.graphicsLayer { translationX = maxWidth.toPx() * played() - (halo / 2).toPx() }.size(halo).background(TortugaColors.OnVideo.copy(alpha = 0.24f), CircleShape))
        }
        Box(Modifier.graphicsLayer { translationX = maxWidth.toPx() * played() - (thumb / 2).toPx() }.size(thumb).background(TortugaColors.OnVideo, CircleShape))
        if (scrub != null) ScrubBubble(scrub, anchorX = maxWidth * scrub.progress.coerceIn(0f, 1f), maxWidth = maxWidth)
    }
}

/** The timeline's thumb's diameter at rest, under the pointer or scrubbing, and with focus (TV scrubbing). */
private val ScrubThumb = 12.dp
private val ScrubThumbActive = 16.dp
private val ScrubThumbFocused = 20.dp

@Composable
private fun ScrubBubble(scrub: ScrubState, anchorX: Dp, maxWidth: Dp) {
    var bubble by remember { mutableStateOf(IntSize.Zero) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = TortugaColors.SurfaceHigh,
        contentColor = TortugaColors.OnVideo,
        modifier = Modifier
            .onSizeChanged { bubble = it }
            // Centered over the preview, kept inside the bar, above it.
            .offset {
                val left = (anchorX.roundToPx() - bubble.width / 2).coerceIn(0, (maxWidth.roundToPx() - bubble.width).coerceAtLeast(0))
                IntOffset(left, -(bubble.height / 2 + 24.dp.roundToPx()))
            }
            .widthIn(max = 280.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${scrub.position}  ${scrub.offset}", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
            scrub.chapter?.let { Text(it, style = MaterialTheme.typography.labelMedium.contentDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * A round player button: icon only, [label] for accessibility. Transparent over the video, a translucent tint hovered
 * or pressed ([TortugaSurface]); [large] for play/pause; [selected] fills it (its menu or panel is open), [filled] gives
 * it the tonal fill of a button on a panel. Focus fills it with the primary colour (and grows it on TV), as every
 * control does.
 */
@Composable
internal fun PlayerButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    probe: String? = null,
    large: Boolean = false,
    selected: Boolean = false,
    filled: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val metrics = rememberPlayerMetrics()
    TortugaSurface(
        onClick = onClick,
        modifier = modifier
            .size(if (large) metrics.playButton else metrics.button)
            .then(if (probe != null) Modifier.layoutProbe(probe) else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = CircleShape,
        color = when {
            selected -> TortugaColors.SurfaceHighest
            filled -> TortugaColors.SurfaceHigh
            else -> Color.Transparent
        },
        contentColor = TortugaColors.OnVideo,
        enabled = enabled,
        role = Role.Button,
        selected = selected,
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, Modifier.size(if (large) metrics.playIcon else metrics.icon))
    }
}

/**
 * The loading spinner with the store's text ("Preparing stream…", "Buffering…") on black, darker over an HDR picture
 * ([hdr], [ControlsScrim]).
 */
@Composable
internal fun LoadingOverlay(text: String?, hdr: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayerLoadingIndicator()
        if (text != null) {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = ControlsScrim.alpha(0.6f, hdr)), contentColor = TortugaColors.OnVideo) {
                Text(text, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
