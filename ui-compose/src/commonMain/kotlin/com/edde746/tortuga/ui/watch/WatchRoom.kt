package com.edde746.tortuga.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.presentation.probeName
import com.edde746.tortuga.presentation.watch.ConfirmAction
import com.edde746.tortuga.presentation.watch.ControlModeChoice
import com.edde746.tortuga.presentation.watch.ParticipantTone
import com.edde746.tortuga.presentation.watch.ParticipantUi
import com.edde746.tortuga.presentation.watch.ToggleUi
import com.edde746.tortuga.presentation.watch.WatchRoomUi
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.ui.account.CodeText
import com.edde746.tortuga.ui.account.ProgressLine
import com.edde746.tortuga.ui.account.QrTile
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.Chevron
import com.edde746.tortuga.ui.components.GroupHeader
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.ModalOverlay
import com.edde746.tortuga.ui.components.OverlayActions
import com.edde746.tortuga.ui.components.RowGroup
import com.edde746.tortuga.ui.components.RowSwitch
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaChip
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaToggleSurface
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.focus.FocusMemory
import com.edde746.tortuga.ui.focus.rememberFocus
import com.edde746.tortuga.ui.focus.focusEntry
import com.edde746.tortuga.ui.focus.rememberFocusMemory
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection
import com.edde746.tortuga.watch.WatchControlMode

/**
 * What the room's controls do, in the lobby and in the player's room panel: copy the code, open the share sheet with
 * the invite ([onShare]: null where the platform has none), join the room's playback and leave the room; the host's:
 * hand the room to a participant or remove one, set who controls playback, lock the room and end it for everyone.
 * Leave, End and removing a participant come once confirmed.
 */
internal class RoomActions(
    val onCopy: (code: String) -> Unit,
    val onShare: ((text: String) -> Unit)?,
    val onMakeHost: (peerId: String) -> Unit,
    val onKick: (peerId: String) -> Unit,
    val onControlMode: (WatchControlMode) -> Unit,
    val onLock: (locked: Boolean) -> Unit,
    val onJoinPlayback: () -> Unit,
    val onLeave: () -> Unit,
    val onEnd: () -> Unit,
)

/**
 * [room] as the lobby and the player's room panel lay it out: its code (large, with Copy and Share) over the invite's
 * QR code, [qrSize] wide; then the room's state (who hosts, who controls playback, locked, the connection, what plays,
 * Join playback for a guest outside it); the host's choice of who controls playback and the lock; the participants (a
 * host badge, each one's file or readiness and offset, Make host and Remove for the host); and Leave, with End for
 * everyone beside it for the host, each behind its confirmation, which gives focus back to its button when it closes
 * (a remote stays where it was). [wide] puts the code and the QR code beside the rest. [copyFocus] goes on Copy, the
 * first control. While [enabled] is false (the room is leaving) its actions are off.
 *
 * Harness names: `watch.room.<code|copy|copied|share|qr|role|controlMode|locked|connection|nowPlaying|joinPlayback>`,
 * `watch.room.control.<hostOnly|anyone>`, `watch.room.lock`, `watch.room.participant.<i>` with `.host`, `.status`,
 * `.offset`, `.makeHost` and `.kick`, `watch.room.<leave|end>`, and the questions' `watch.room.<leave|end|kick>.<confirm|cancel>`.
 */
@Composable
internal fun WatchRoomContent(
    room: WatchRoomUi,
    actions: RoomActions,
    wide: Boolean,
    qrSize: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    copyFocus: FocusRequester? = null,
) {
    // The buttons that ask first: a closing question gives focus back to the one that opened it.
    val focus = rememberFocusMemory()
    if (wide) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            RoomInvite(room, actions, qrSize, copyFocus)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { RoomDetails(room, actions, enabled, focus) }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RoomInvite(room, actions, qrSize, copyFocus)
            RoomDetails(room, actions, enabled, focus)
        }
    }
}

/**
 * The code, Copy (then "Code copied", which TalkBack speaks as it shows) and Share, and the invite's QR code under
 * them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomInvite(room: WatchRoomUi, actions: RoomActions, qrSize: Dp, copyFocus: FocusRequester?) {
    var copied by remember(room.code) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(room.codeLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CodeText(room.code, Modifier.layoutProbe("watch.room.code"))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TortugaButton(
                room.copyCodeLabel,
                onClick = {
                    actions.onCopy(room.code)
                    copied = true
                },
                modifier = (if (copyFocus != null) Modifier.focusRequester(copyFocus) else Modifier).layoutProbe("watch.room.copy"),
                icon = TortugaIcons.ContentCopy,
            )
            actions.onShare?.let { share ->
                TortugaButton(room.shareLabel, { share(room.shareText) }, Modifier.layoutProbe("watch.room.share"), icon = TortugaIcons.Share)
            }
            if (copied) {
                Text(
                    room.codeCopied,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.layoutProbe("watch.room.copied").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        QrTile(room.inviteUrl, room.scanLabel, "watch.room.qr", caption = room.scanLabel, size = qrSize)
    }
}

/**
 * Who hosts and controls playback, whether the room is locked, the connection, what plays; the host's choice of who
 * controls playback and the lock; the participants; Leave, and End for the host.
 */
@Composable
private fun RoomDetails(room: WatchRoomUi, actions: RoomActions, enabled: Boolean, focus: FocusMemory) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(room.role, style = MaterialTheme.typography.titleMedium, modifier = Modifier.layoutProbe("watch.room.role"))
        Text(room.controlMode, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.layoutProbe("watch.room.controlMode"))
        room.lockedText?.let { LockedLine(it) }
    }
    room.connection?.let { ProgressLine(it, Modifier.layoutProbe("watch.room.connection")) }
    (room.nowPlaying ?: room.idleText)?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.layoutProbe("watch.room.nowPlaying"))
    }
    room.joinPlayback?.let {
        TortugaButton(
            it,
            actions.onJoinPlayback,
            Modifier.layoutProbe("watch.room.joinPlayback"),
            style = ButtonStyle.PRIMARY,
            icon = TortugaIcons.PlayArrow,
            enabled = enabled,
        )
    }
    if (room.controlChoices.isNotEmpty()) {
        Column {
            GroupHeader(room.controlTitle)
            ControlModeSelector(room.controlChoices, room.selectedMode, actions.onControlMode, enabled)
        }
    }
    room.lock?.let { LockSwitch(it, actions.onLock, enabled) }
    Column {
        GroupHeader(room.participantsLabel)
        RowGroup {
            room.participants.forEachIndexed { index, participant ->
                // Keyed by peer: a question asked on a row stays with its participant as others come and go.
                key(participant.peerId) {
                    ParticipantRow(
                        participant,
                        room.hostBadge,
                        room.makeHostLabel,
                        index,
                        room.participants.size,
                        enabled,
                        focus,
                        onMakeHost = { actions.onMakeHost(participant.peerId) },
                        onKick = { actions.onKick(participant.peerId) },
                    )
                }
            }
        }
    }
    RoomExits(room, actions, enabled, focus)
}

/** "No one new can join", with a lock, under who controls playback, for everyone while the room is locked. */
@Composable
private fun LockedLine(text: String) {
    Row(Modifier.layoutProbe("watch.room.locked"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(TortugaIcons.Lock, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The host's choice of who controls playback: a solid chip per way ([TortugaChip], the room's in the primary fill), side
 * by side where they fit, and what the room's way means under them. Choosing the room's own way again does nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlModeSelector(choices: List<ControlModeChoice>, selected: WatchControlMode, onSelect: (WatchControlMode) -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                TortugaChip(
                    choice.label,
                    selected = choice.mode == selected,
                    onClick = { if (choice.mode != selected) onSelect(choice.mode) },
                    modifier = Modifier.layoutProbe("watch.room.control.${choice.mode.probeName}"),
                    enabled = enabled,
                    leadingIcon = choice.mode.icon,
                )
            }
        }
        choices.firstOrNull { it.mode == selected }?.let {
            Text(it.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The host's lock: a switch row (the whole row is the control) with what locking means. */
@Composable
private fun LockSwitch(lock: ToggleUi, onLock: (Boolean) -> Unit, enabled: Boolean) {
    TortugaToggleSurface(
        lock.checked,
        onLock,
        Modifier.fillMaxWidth().layoutProbe("watch.room.lock"),
        shape = MaterialTheme.shapes.large,
        enabled = enabled,
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(TortugaIcons.Lock, contentDescription = null, Modifier.size(22.dp), tint = LocalContentColor.current.copy(alpha = 0.7f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(lock.label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                Text(lock.detail, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.6f))
            }
            RowSwitch(lock.checked, enabled)
        }
    }
}

/**
 * A participant: the initial in a round avatar, the name with the host badge (someone else's name reads in the direction
 * of its first letter, your own row, an app string, in the interface's; both from the interface's start edge), the
 * status in its tone (ready or a matching file bright with a check, finding or buffering dim, another duration, no file
 * or disconnected in the error colour) and the offset it plays with; Make host where the host may hand the room to them,
 * and Remove (an icon button, the row being narrow in the player's panel) where the host may remove them, which asks
 * first.
 */
@Composable
private fun ParticipantRow(
    participant: ParticipantUi,
    hostBadge: String,
    makeHostLabel: String,
    index: Int,
    count: Int,
    enabled: Boolean,
    focus: FocusMemory,
    onMakeHost: () -> Unit,
    onKick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(TortugaColors.SurfaceHigh, groupShape(index, count))
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .layoutProbe("watch.room.participant.$index", reportsFocus = false),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(TortugaColors.SurfaceHighest, CircleShape), contentAlignment = Alignment.Center) {
            Text(participant.initial, style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val nameStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                Text(
                    participant.name,
                    style = if (participant.isYou) nameStyle else nameStyle.contentDirection(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (participant.isHost) HostBadge(hostBadge, Modifier.layoutProbe("watch.room.participant.$index.host"))
            }
            participant.status?.let { status ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    participant.tone.icon?.let { Icon(it, contentDescription = null, Modifier.size(16.dp), tint = participant.tone.color) }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = participant.tone.color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.layoutProbe("watch.room.participant.$index.status"),
                    )
                }
            }
            participant.offset?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.layoutProbe("watch.room.participant.$index.offset"),
                )
            }
        }
        if (participant.canMakeHost) {
            TortugaButton(
                makeHostLabel,
                onMakeHost,
                Modifier.layoutProbe("watch.room.participant.$index.makeHost").semantics { contentDescription = T.watch.room.makeHostNamed(isolate(participant.name)) },
                style = ButtonStyle.TEXT,
                enabled = enabled,
            )
        }
        participant.kick?.let { kick ->
            var asking by remember { mutableStateOf(false) }
            TortugaIconButton(
                TortugaIcons.RemoveCircle,
                kick.label,
                { asking = true },
                Modifier.rememberFocus(focus, "watch.room.participant.${participant.peerId}.kick").layoutProbe("watch.room.participant.$index.kick"),
                style = IconButtonStyle.PLAIN,
                enabled = enabled,
            )
            if (asking) {
                ConfirmQuestion(
                    kick,
                    "watch.room.kick",
                    onConfirm = {
                        asking = false
                        onKick()
                    },
                    onCancel = { asking = false },
                )
            }
        }
    }
}

/** "Host": a small label in the primary fill, beside the host's name (and on the player's session indicator). */
@Composable
internal fun HostBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = TortugaColors.OnPrimary,
        maxLines = 1,
    )
}

private val ParticipantTone.color: Color
    @Composable get() = when (this) {
        ParticipantTone.GOOD -> MaterialTheme.colorScheme.onSurface
        ParticipantTone.WARNING -> TortugaColors.Error
        ParticipantTone.BUSY, ParticipantTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private val ParticipantTone.icon
    get() = when (this) {
        ParticipantTone.GOOD -> TortugaIcons.CheckCircle
        ParticipantTone.WARNING -> TortugaIcons.ErrorOutline
        ParticipantTone.BUSY -> TortugaIcons.Sync
        ParticipantTone.NEUTRAL -> null
    }

/**
 * Leave, and for the host End for everyone beside it, on as many lines as the width needs. A move into them (Down from
 * the participants or the lock) lands on the one that had focus last, else Leave, whatever the buttons' labels left
 * under the focus ([focusEntry]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomExits(room: WatchRoomUi, actions: RoomActions, enabled: Boolean, focus: FocusMemory) {
    FlowRow(Modifier.focusEntry(focus.requester(LEAVE_PROBE)), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConfirmButton(room.leave, LEAVE_PROBE, TortugaIcons.Logout, actions.onLeave, enabled, focus)
        room.end?.let { ConfirmButton(it, "watch.room.end", TortugaIcons.Close, actions.onEnd, enabled, focus) }
    }
}

/** Leave's harness name, also its key in the room's focus memory. */
private const val LEAVE_PROBE = "watch.room.leave"

/** [action]'s button in the error colour, harness name [probe]: asks first ([ConfirmQuestion]), as removing an addon does. */
@Composable
private fun ConfirmButton(action: ConfirmAction, probe: String, icon: ImageVector, onConfirm: () -> Unit, enabled: Boolean, focus: FocusMemory) {
    var asking by remember { mutableStateOf(false) }
    TortugaButton(action.label, { asking = true }, Modifier.rememberFocus(focus, probe).layoutProbe(probe), style = ButtonStyle.DESTRUCTIVE, icon = icon, enabled = enabled)
    if (asking) {
        ConfirmQuestion(
            action,
            probe,
            onConfirm = {
                asking = false
                onConfirm()
            },
            onCancel = { asking = false },
        )
    }
}

/**
 * [action]'s question over everything, Cancel focused: Leave's, End's, removing a participant's, and the lobby's for an
 * invite (the room's Leave, or out of a room joining one on another relay). Its confirm button is red where
 * [ConfirmAction.destructive]. Back, Escape and a click outside cancel it. Harness names: `<probe>.<confirm|cancel>`.
 */
@Composable
internal fun ConfirmQuestion(action: ConfirmAction, probe: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val cancel = remember { FocusRequester() }
    ModalOverlay(title = action.title, onDismiss = onCancel, initialFocus = cancel) {
        OverlayActions {
            TortugaButton(action.cancel, onCancel, Modifier.focusRequester(cancel).layoutProbe("$probe.cancel"), style = ButtonStyle.TEXT)
            TortugaButton(
                action.confirm,
                onConfirm,
                Modifier.layoutProbe("$probe.confirm"),
                style = if (action.destructive) ButtonStyle.DESTRUCTIVE else ButtonStyle.PRIMARY,
            )
        }
    }
}

/**
 * The ways to start a room, one row each (who controls playback, with what that means), as one group; [firstFocus] on
 * the first. Harness names: `watch.start.<hostOnly|anyone>`.
 */
@Composable
internal fun ControlModeChoices(
    choices: List<ControlModeChoice>,
    onStart: (WatchControlMode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
) {
    RowGroup(modifier) {
        choices.forEachIndexed { index, choice ->
            GroupRow(
                choice.label,
                onClick = { onStart(choice.mode) },
                modifier = (if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier).layoutProbe("watch.start.${choice.mode.probeName}"),
                shape = groupShape(index, choices.size),
                subtitle = choice.detail,
                icon = choice.mode.icon,
                enabled = enabled,
                trailing = { Chevron() },
            )
        }
    }
}

/** A control mode's icon: a lock for the host's alone, people for everyone's. */
private val WatchControlMode.icon: ImageVector
    get() = when (this) {
        WatchControlMode.HOST_ONLY -> TortugaIcons.Lock
        WatchControlMode.ANYONE -> TortugaIcons.Groups
    }
