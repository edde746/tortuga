package com.edde746.tortuga.presentation.watch

import com.edde746.tortuga.input.WatchCommand
import com.edde746.tortuga.watch.WatchControlMode

/** How a participant's status reads ([ParticipantUi.status]): a view may color it. */
enum class ParticipantTone {
    NEUTRAL,

    /** Ready, or a matching file. */
    GOOD,

    /** Finding a file, loading, buffering. */
    BUSY,

    /** A file of another duration, or none; disconnected. */
    WARNING,
}

/**
 * One participant as the room lists it.
 *
 * @param name the display name; this device's own is an app string ("Anna (you)", the name isolated in it), anyone
 *   else's the name as they chose it, which the views show in its own direction.
 * @param isYou the row is this device's own: [name] is an app string, in the interface's direction.
 * @param initial its avatar's initial ([com.edde746.tortuga.presentation.profiles.profileInitial]), "?" for a blank name.
 * @param status "Ready", "Finding a file", "Buffering", "Same length (0.4 s)", "12 s longer", "No stream found",
 *   "Not in the player", "Disconnected"; null when there is nothing to say.
 * @param offset "Offset +2.5 s" when the participant plays with an offset; null otherwise.
 * @param canMakeHost this device hosts and may hand the room to it ([WatchCommand.MakeHost]).
 * @param kick this device hosts and may remove it ([WatchCommand.Kick]), asking first ("Remove Anna"); null otherwise.
 */
data class ParticipantUi(
    val peerId: String,
    val name: String,
    val isYou: Boolean,
    val initial: String,
    val isHost: Boolean,
    val status: String?,
    val tone: ParticipantTone,
    val offset: String?,
    val canMakeHost: Boolean,
    val kick: ConfirmAction?,
)

/**
 * A button that asks first: [label] on the button, then [title] with [confirm] and [cancel]. [destructive] the views draw
 * [confirm] as a destructive action (Leave, End, Remove); joining a room isn't one.
 */
data class ConfirmAction(val label: String, val title: String, val confirm: String, val cancel: String, val destructive: Boolean = true)

/** A switch: [label] and [detail], on when [checked]. */
data class ToggleUi(val label: String, val detail: String, val checked: Boolean)

/**
 * The room as both the lobby and the player's room panel lay it out.
 *
 * @param code the room code as people read it ("K7M-Q2X"), large; [inviteUrl] the link the QR code and the share sheet
 *   carry (`tortuga://watch?code=...`, the relay only when self-hosted), [shareText] the message a share sheet sends.
 * @param role "You're the host" or "Hosted by Sam".
 * @param controlMode "Only the host controls playback" or "Everyone controls playback".
 * @param controlChoices the host's choice of who controls playback ([WatchCommand.SetControlMode]), [controlTitle]
 *   its heading; empty for a guest. [selectedMode] is the room's.
 * @param lock the host's lock ([WatchCommand.Lock]); null for a guest. [lockedText] tells a guest the room is locked
 *   (the host's switch says it already); null when it isn't, and for the host.
 * @param connection "Reconnecting…" while the room's link is down; null when connected.
 * @param nowPlaying "Playing The Matrix · S1 E3" once the room has media; else [idleText] says what to do.
 * @param joinPlayback a guest's way back into the room's playback after leaving the player ([WatchCommand.JoinPlayback]);
 *   null when it plays it (or hosts).
 * @param leave Leave ([WatchCommand.Leave]): a host's room goes on with the next participant as host.
 * @param end End for everyone ([WatchCommand.End]), the host's only; null for a guest.
 */
data class WatchRoomUi(
    val code: String,
    val codeLabel: String,
    val copyCodeLabel: String,
    val codeCopied: String,
    val inviteUrl: String,
    val shareText: String,
    val shareLabel: String,
    val scanLabel: String,
    val role: String,
    val isHost: Boolean,
    val controlMode: String,
    val controlTitle: String,
    val controlChoices: List<ControlModeChoice>,
    val selectedMode: WatchControlMode,
    val lock: ToggleUi?,
    val lockedText: String?,
    val connection: String?,
    val nowPlaying: String?,
    val idleText: String?,
    val participantsLabel: String,
    val participants: List<ParticipantUi>,
    val makeHostLabel: String,
    val hostBadge: String,
    val joinPlayback: String?,
    val leave: ConfirmAction,
    val end: ConfirmAction?,
)

/** One way to run a room: who controls playback. */
data class ControlModeChoice(val mode: WatchControlMode, val label: String, val detail: String)

/**
 * A recent room: tap to enter it again ([code] and [relay] name it). [label] is the code as people read it ("K7M-Q2X");
 * [detail] says when it was used ("Yesterday"); [removeLabel] ("Remove K7M-Q2X") forgets it.
 */
data class RecentRoomUi(val label: String, val code: String, val relay: String, val detail: String, val removeLabel: String)

/** The relay check: [status] while checking or when unreachable (null: fine); [retryLabel] when unreachable. */
data class RelayStatusUi(val status: String?, val unreachable: Boolean, val retryLabel: String)

/**
 * This device's watch-together relay: [title] and [subtitle]; [valueLabel] names the relay in use ("Open Watch Party's
 * public relays", or a self-hosted relay's host); [value] is the URL to edit (empty for the public relays, [placeholder]
 * then). [invalid] is what a field says when `setRelay` refused its text; [resetLabel] goes back to the public relays,
 * offered while [custom].
 */
data class WatchRelayRow(
    val title: String,
    val subtitle: String,
    val value: String,
    val valueLabel: String,
    val custom: Boolean,
    val placeholder: String,
    val invalid: String,
    val saveLabel: String,
    val resetLabel: String,
)

/**
 * The Watch together screen (the lobby): join a room, recent rooms, the relay; in a room, the room. A room starts in
 * the player ([PlayerWatchUi.startChoices]).
 *
 * @param intro what watch together is, and that a room starts from the player's options.
 * @param joinText the join field as typed; [joinHint] its placeholder ("Code or invite link"); [canJoin] it holds a
 *   code or an invite; [joinError] why it can't.
 * @param relaySetting the relay this device uses (`setRelay`, `resetRelay`); the views offer it out of a room.
 * @param inviteQuestion asked at once when an invite link came in that needs the viewer's yes: in a room, to another
 *   room, the room's own Leave question, naming the code and the relay when the invite is on another relay than this
 *   device's ("Leave the room to join room K7M-Q2X? It's on relay.example.com, not on your relay server."); out of a
 *   room, to a room on another relay than this device's, "Join room K7M-Q2X? It's on relay.example.com, not on your
 *   relay server.", whose Join isn't destructive. Confirmed, this device leaves, if in a room, and joins the invite's
 *   room (`joinInvite`); cancelled, nothing changes (`dismissInvite`). Null otherwise; an invite to the room this
 *   device is in asks nothing.
 * @param busy what is in progress ("Joining the room…"), with the lobby's actions disabled; null when idle.
 * @param failure the last failure ("There's no room with that code"); [dismissLabel] clears it.
 * @param toast the room's latest event ("Sam joined") until the screen has shown it (`WatchStore.toastShown`).
 */
data class WatchLobbyUiState(
    val title: String,
    val intro: String,
    val relay: RelayStatusUi,
    val joinTitle: String,
    val joinHint: String,
    val joinLabel: String,
    val joinText: String,
    val canJoin: Boolean,
    val joinError: String?,
    val recentTitle: String,
    val recentRooms: List<RecentRoomUi>,
    val relaySetting: WatchRelayRow,
    val room: WatchRoomUi?,
    val inviteQuestion: ConfirmAction?,
    val busy: String?,
    val failure: String?,
    val dismissLabel: String,
    val toast: String?,
)

/** A prompt's button. */
data class WatchActionUi(val label: String, val command: WatchCommand)

/**
 * What waits for the viewer in a guest's player: [message] ("Your file is 12 s longer than Sam's"), [detail], and
 * its [actions] (Play anyway, Choose another stream, Don't play).
 */
data class WatchPromptUi(val message: String, val detail: String?, val actions: List<WatchActionUi>)

/**
 * This device's offset from the room: [title] ("Offset"), [value] ("+2.5 s", "0 s"), [detail] what it is for, and its
 * [actions]: -5 s, -0.5 s, +0.5 s, +5 s ([WatchCommand.NudgeOffset]) and Reset ([WatchCommand.ResetOffset]).
 */
data class WatchOffsetUi(val title: String, val value: String, val detail: String, val actions: List<WatchActionUi>)

/**
 * The session indicator in the player's controls: [count] participants, [hostBadge] ("HOST") when this device hosts,
 * [syncing] while the room waits for someone; [label] reads it out ("3 watching together").
 */
data class WatchIndicatorUi(val count: Int, val label: String, val isHost: Boolean, val hostBadge: String, val syncing: Boolean)

/**
 * Watch together in the player.
 *
 * @param title "Watch together", the room panel's heading ([com.edde746.tortuga.input.PlayerMenu.WATCH_TOGETHER]).
 * @param room the room, once in one; [startChoices] start one from what plays while not, under [startTitle]. A live
 *   stream can't start a room: no choices, and [startTitle] says so ("A live stream can't be watched together").
 * @param indicator the session indicator, in a room.
 * @param pill "Waiting for Anna, Ben…" or "Syncing…", over the video.
 * @param matching "Finding a file that matches Sam's…" while a guest looks for its file.
 * @param prompt what waits for the viewer (a guest's mismatch, no stream, a declined file, a pick that failed).
 * @param offset this device's offset, while it plays the room's media.
 * @param canControl play, pause, seek and speed are this device's to change; false for a guest in a host-only room,
 *   whose controls show disabled.
 * @param canChangeEpisode the next episode is this device's to start (the host's only, in a room).
 * @param controlsLocked why the controls are disabled ("Only Sam controls playback in this room"), while
 *   [canControl] is false: the line above them, and the message a refused command shows.
 * @param pausedHere "Paused on this device…" while the player paused here alone, out of the room's playback, which
 *   plays on (a guest's call, its audio output gone, its sleep timer: [com.edde746.tortuga.presentation.player.PlayerWatch.stopHere]):
 *   the line above the controls in [controlsLocked]'s place, and play/pause enabled, whose Play catches up with the
 *   room. Null otherwise.
 * @param busy what is in progress ("Starting a room…"), with the panel's actions disabled; null when idle.
 * @param failure the last failure ("Couldn't reach the relay"); [dismissLabel] clears it ([WatchCommand.DismissFailure]).
 */
data class PlayerWatchUi(
    val title: String,
    val room: WatchRoomUi?,
    val startTitle: String,
    val startChoices: List<ControlModeChoice>,
    val indicator: WatchIndicatorUi?,
    val pill: String?,
    val matching: String?,
    val prompt: WatchPromptUi?,
    val offset: WatchOffsetUi?,
    val canControl: Boolean,
    val canChangeEpisode: Boolean,
    val controlsLocked: String?,
    val pausedHere: String?,
    val busy: String?,
    val failure: String?,
    val dismissLabel: String,
) {
    /** The line above the controls: [pausedHere], else [controlsLocked]. */
    val lockLine: String? get() = pausedHere ?: controlsLocked
}
