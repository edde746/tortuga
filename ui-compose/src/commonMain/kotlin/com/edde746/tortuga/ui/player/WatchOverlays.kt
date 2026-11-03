package com.edde746.tortuga.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.input.WatchCommand
import com.edde746.tortuga.presentation.player.ControlsScrim
import com.edde746.tortuga.presentation.watch.PlayerWatchUi
import com.edde746.tortuga.presentation.watch.WatchIndicatorUi
import com.edde746.tortuga.presentation.watch.WatchOffsetUi
import com.edde746.tortuga.presentation.watch.WatchPromptUi
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.account.NoticeCard
import com.edde746.tortuga.ui.account.ProgressLine
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.focus.StayInViewSpec
import com.edde746.tortuga.ui.focus.focusEntry
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.watch.ControlModeChoices
import com.edde746.tortuga.ui.watch.HostBadge
import com.edde746.tortuga.ui.watch.RoomActions
import com.edde746.tortuga.ui.watch.WatchRoomContent

/**
 * The session indicator at the end of the top bar while this device is in a room: the people icon (sync
 * arrows while the player is being brought back in line), how many are in the room, and the host badge on the host's
 * player; its [WatchIndicatorUi.label] is what a screen reader hears. It opens the room panel
 * ([PlayerMenu.WATCH_TOGETHER]), and closes it while it is open. On black, darker over an HDR picture ([hdr],
 * [ControlsScrim]). Harness name: `player.watch`.
 */
@Composable
internal fun SessionIndicator(
    indicator: WatchIndicatorUi,
    open: PlayerMenu?,
    hdr: Boolean,
    buttons: MenuButtons,
    onCommand: (PlayerCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberPlayerMetrics()
    val owns = open == PlayerMenu.WATCH_TOGETHER
    TortugaSurface(
        onClick = { onCommand(if (owns) PlayerCommand.CloseMenu else PlayerCommand.OpenMenu(PlayerMenu.WATCH_TOGETHER)) },
        modifier = modifier
            .height(metrics.button)
            .then(buttons.place(PlayerMenu.WATCH_TOGETHER))
            .focusRequester(buttons.focus(PlayerMenu.WATCH_TOGETHER))
            .semantics { contentDescription = indicator.label }
            .layoutProbe("player.watch"),
        shape = CircleShape,
        color = if (owns) TortugaColors.SurfaceHighest else Color.Black.copy(alpha = ControlsScrim.alpha(0.45f, hdr)),
        selected = owns,
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (indicator.syncing) TortugaIcons.Sync else TortugaIcons.Groups, contentDescription = null, Modifier.size(metrics.icon - 4.dp))
            Text(
                indicator.count.toString(),
                style = (if (metrics.tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall).copy(fontWeight = FontWeight.Bold),
            )
            if (indicator.isHost) HostBadge(indicator.hostBadge)
        }
    }
}

/**
 * "Waiting for Anna, Ben…" or "Syncing…" over the video, among the messages at the top (PlayerOverlays), on black,
 * darker over an HDR picture ([hdr], [ControlsScrim]). Harness name: `player.watch.pill`.
 */
@Composable
internal fun WatchPill(text: String, hdr: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier.layoutProbe("player.watch.pill"), shape = CircleShape, color = Color.Black.copy(alpha = ControlsScrim.alpha(0.6f, hdr)), contentColor = TortugaColors.OnVideo) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(TortugaIcons.Groups, contentDescription = null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * What waits for a guest whose file search ended without a match, in place of the loading spinner: the message ("Your
 * file is 12 s longer than Sam's"), what that means, and its actions (Play anyway, Choose another stream, Don't
 * play) on as many lines as the card's width needs, the first the primary one with [PlayerFocus.watchPrompt] (the
 * player gives it focus, as an error's). TalkBack speaks the message as it shows and changes, wherever its focus is.
 * Harness names: `player.watch.prompt`, `player.watch.prompt.<i>`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchPromptCard(prompt: WatchPromptUi, focus: PlayerFocus, onCommand: (PlayerCommand) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.widthIn(max = 520.dp).padding(24.dp).layoutProbe("player.watch.prompt", reportsFocus = false),
        shape = PanelShape,
        color = TortugaColors.Surface,
        contentColor = TortugaColors.OnVideo,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(TortugaIcons.Groups, contentDescription = null, Modifier.size(40.dp))
            Text(prompt.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            prompt.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = TortugaColors.OnVideoMuted) }
            Spacer(Modifier.size(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                prompt.actions.forEachIndexed { index, action ->
                    TortugaButton(
                        action.label,
                        onClick = { onCommand(PlayerCommand.Watch(action.command)) },
                        modifier = (if (index == 0) Modifier.focusRequester(focus.watchPrompt) else Modifier).layoutProbe("player.watch.prompt.$index"),
                        style = if (index == 0) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
                    )
                }
            }
        }
    }
}

/**
 * Why the playback controls are off: a guest in a room only its host controls, in [color] (the controls' muted text,
 * `mutedText`). Harness name: `player.watch.locked`.
 */
@Composable
internal fun ControlsLockedLine(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.layoutProbe("player.watch.locked"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(TortugaIcons.Lock, contentDescription = null, Modifier.size(16.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The room panel ([PlayerMenu.WATCH_TOGETHER]): in a room, this device's offset while it plays the room's media
 * ([WatchOffsetControl]), then the room as the lobby shows it, stacked ([WatchRoomContent], Copy taking focus as it
 * opens: an offset button taking it would move playback on a stray press, and Copy taking it in view leaves the offset
 * above it in view, [StayInViewSpec], where Android TV would pivot Copy up and scroll the offset away); outside one, the
 * ways to start a room with what plays (the first taking focus), or for a live stream, which can't start one, the line
 * that says so and Close, which takes focus and closes the panel. A failure shows over it until dismissed, work in
 * progress as a line with the actions off. [room] carries the platform's clipboard and share sheet and the room's
 * commands. Harness names: `player.watch.start.none`, `player.watch.close` (a live stream's).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WatchPanelContent(watch: PlayerWatchUi?, room: RoomActions, initial: FocusRequester, onCommand: (PlayerCommand) -> Unit, modifier: Modifier) {
    val dimens = LocalTortugaDimens.current
    val tv = rememberPlayerMetrics().tv
    val platformScroll = LocalBringIntoViewSpec.current
    val stayInView = remember(platformScroll) { StayInViewSpec(platformScroll) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides stayInView) {
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(PaddingValues(horizontal = 8.dp, vertical = dimens.focusReach)).layoutProbe("player.watch.panel", reportsFocus = false),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (watch == null) return@Column
            watch.failure?.let { NoticeCard(it, { onCommand(PlayerCommand.Watch(WatchCommand.DismissFailure)) }, Modifier.layoutProbe("player.watch.failure")) }
            watch.busy?.let { ProgressLine(it, Modifier.layoutProbe("player.watch.busy")) }
            val current = watch.room
            if (current != null) {
                watch.offset?.let { WatchOffsetControl(it, enabled = watch.busy == null, onCommand) }
                WatchRoomContent(current, room, wide = false, qrSize = if (tv) 200.dp else 160.dp, enabled = watch.busy == null, copyFocus = initial)
            } else if (watch.startChoices.isEmpty()) {
                Text(watch.startTitle, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.layoutProbe("player.watch.start.none"))
                TortugaButton(
                    T.nav.close,
                    onClick = { onCommand(PlayerCommand.CloseMenu) },
                    modifier = Modifier.focusRequester(initial).layoutProbe("player.watch.close"),
                    style = ButtonStyle.SECONDARY,
                )
            } else {
                Text(watch.startTitle, style = MaterialTheme.typography.titleSmall, color = TortugaColors.OnVideoMuted)
                ControlModeChoices(
                    watch.startChoices,
                    onStart = { onCommand(PlayerCommand.Watch(WatchCommand.Start(it))) },
                    enabled = watch.busy == null,
                    firstFocus = initial,
                )
            }
        }
    }
}

/**
 * This device's offset from the room: the title with the value at its end (digits of one width, so a nudge moves only
 * them), what it is for, and its actions (-5 s, -0.5 s, +0.5 s, +5 s, then Reset, quieter) on as many lines as the
 * panel's width needs. A button keeps focus as it nudges, so a remote presses it again. A move into the actions (Up
 * from Copy) lands on the one that had focus last, else the first, wherever the panel's width wraps them
 * ([focusEntry]). Harness names: `player.watch.offset`, `player.watch.offset.value`, `player.watch.offset.<i>`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchOffsetControl(offset: WatchOffsetUi, enabled: Boolean, onCommand: (PlayerCommand) -> Unit) {
    val first = remember { FocusRequester() }
    Column(Modifier.layoutProbe("player.watch.offset", reportsFocus = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(offset.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                offset.value,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                modifier = Modifier.layoutProbe("player.watch.offset.value"),
            )
        }
        Text(offset.detail, style = MaterialTheme.typography.bodySmall, color = TortugaColors.OnVideoMuted)
        FlowRow(Modifier.focusEntry(first), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            offset.actions.forEachIndexed { index, action ->
                TortugaButton(
                    action.label,
                    onClick = { onCommand(PlayerCommand.Watch(action.command)) },
                    modifier = (if (index == 0) Modifier.focusRequester(first) else Modifier).layoutProbe("player.watch.offset.$index"),
                    style = if (action.command is WatchCommand.NudgeOffset) ButtonStyle.SECONDARY else ButtonStyle.TEXT,
                    enabled = enabled,
                )
            }
        }
    }
}
