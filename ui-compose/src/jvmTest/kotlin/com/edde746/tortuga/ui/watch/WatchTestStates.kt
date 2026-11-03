package com.edde746.tortuga.ui.watch

import com.edde746.tortuga.input.WatchCommand
import com.edde746.tortuga.presentation.watch.ConfirmAction
import com.edde746.tortuga.presentation.watch.ControlModeChoice
import com.edde746.tortuga.presentation.watch.ParticipantTone
import com.edde746.tortuga.presentation.watch.ParticipantUi
import com.edde746.tortuga.presentation.watch.PlayerWatchUi
import com.edde746.tortuga.presentation.watch.RecentRoomUi
import com.edde746.tortuga.presentation.watch.RelayStatusUi
import com.edde746.tortuga.presentation.watch.ToggleUi
import com.edde746.tortuga.presentation.watch.WatchActionUi
import com.edde746.tortuga.presentation.watch.WatchIndicatorUi
import com.edde746.tortuga.presentation.watch.WatchLobbyUiState
import com.edde746.tortuga.presentation.watch.WatchOffsetUi
import com.edde746.tortuga.presentation.watch.WatchPromptUi
import com.edde746.tortuga.presentation.watch.WatchRelayRow
import com.edde746.tortuga.presentation.watch.WatchRoomUi
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.watch.WatchControlMode

/** The two ways to run a room, as the store words them. */
internal val testStartChoices = listOf(
    ControlModeChoice(WatchControlMode.HOST_ONLY, T.watch.controlMode.hostOnly, T.watch.controlMode.hostOnlyDetail),
    ControlModeChoice(WatchControlMode.ANYONE, T.watch.controlMode.anyone, T.watch.controlMode.anyoneDetail),
)

/** Anna's offset in [testRoom], as the store words it. */
internal val testAnnaOffset = T.watch.status.offset("+" + T.watch.delta.seconds("2.5"))

/**
 * Room K7M-Q2X on the public relays with Sam and Anna, in [mode], [locked] or not. As the host ([host] true: "You're
 * the host", who controls playback and the lock, Make host and Remove on Anna, Leave and End) or as Anna, the guest
 * ("Hosted by Sam", Leave); Anna's file 12 s longer than Sam's, played 2.5 s later ([testAnnaOffset]).
 */
internal fun testRoom(
    host: Boolean = true,
    playing: Boolean = true,
    locked: Boolean = false,
    mode: WatchControlMode = WatchControlMode.HOST_ONLY,
) = WatchRoomUi(
    code = "K7M-Q2X",
    codeLabel = T.watch.room.codeLabel,
    copyCodeLabel = T.watch.room.copyCode,
    codeCopied = T.watch.room.codeCopied,
    inviteUrl = "tortuga://watch?code=K7MQ2X",
    shareText = T.watch.room.shareText("tortuga://watch?code=K7MQ2X", "K7M-Q2X"),
    shareLabel = T.watch.room.share,
    scanLabel = T.watch.room.scan,
    role = if (host) T.watch.room.youHost else T.watch.room.hostedBy("Sam"),
    isHost = host,
    controlMode = if (mode == WatchControlMode.ANYONE) T.watch.room.anyone else T.watch.room.hostOnly,
    controlTitle = T.watch.room.controlTitle,
    controlChoices = if (host) testStartChoices else emptyList(),
    selectedMode = mode,
    lock = if (host) ToggleUi(T.watch.room.lock, T.watch.room.lockDetail, locked) else null,
    lockedText = if (locked && !host) T.watch.room.lockDetail else null,
    connection = null,
    nowPlaying = if (playing) T.watch.room.nowPlayingEpisode("Chernobyl", "S1 E1") else null,
    idleText = if (playing) null else T.watch.room.idleHost,
    participantsLabel = T.watch.room.participants(2),
    participants = listOf(
        ParticipantUi(
            "sam", if (host) T.watch.room.you(isolate("Sam")) else "Sam", isYou = host, initial = "S", isHost = true, status = null,
            tone = ParticipantTone.NEUTRAL, offset = null, canMakeHost = false, kick = null,
        ),
        ParticipantUi(
            "anna", if (host) "Anna" else T.watch.room.you(isolate("Anna")), isYou = !host, initial = "A", isHost = false,
            status = T.watch.status.longer("12 s"), tone = ParticipantTone.WARNING, offset = testAnnaOffset, canMakeHost = host,
            kick = if (host) ConfirmAction(T.watch.room.kickNamed("Anna"), T.watch.room.kickTitle("Anna"), T.watch.room.kickConfirm, T.watch.room.cancel) else null,
        ),
    ),
    makeHostLabel = T.watch.room.makeHost,
    hostBadge = T.watch.room.hostBadge,
    joinPlayback = null,
    leave = ConfirmAction(T.watch.room.leave, if (host) T.watch.room.leaveHostTitle else T.watch.room.leaveTitle, T.watch.room.leaveConfirm, T.watch.room.cancel),
    end = if (host) ConfirmAction(T.watch.room.end, T.watch.room.endTitle, T.watch.room.endConfirm, T.watch.room.cancel) else null,
)

/** A recent room, K7M-Q2X on the public relays, used yesterday. */
internal val testRecentRoom = RecentRoomUi("K7M-Q2X", "K7MQ2X", "", T.watch.recentUsed("yesterday"), T.watch.removeRecentNamed("K7M-Q2X"))

/** This device's relay as the store words it: the public relays, or [custom], a server of its own. */
internal fun testRelaySetting(custom: String? = null) = WatchRelayRow(
    title = T.watch.relay.title,
    subtitle = T.watch.relay.hint,
    value = custom.orEmpty(),
    valueLabel = if (custom != null) "relay.example.org" else T.watch.relay.hosted,
    custom = custom != null,
    placeholder = T.watch.relay.placeholder,
    invalid = T.watch.relay.invalid,
    saveLabel = T.watch.relay.save,
    resetLabel = T.watch.relay.reset,
)

/**
 * The lobby out of a room ([room] null) or in [room], with the join field holding [joinText]; [inviteQuestion] asks
 * first for an invite.
 */
internal fun testLobby(
    room: WatchRoomUi? = null,
    inviteQuestion: ConfirmAction? = null,
    joinText: String = "",
    canJoin: Boolean = false,
    joinError: String? = null,
    recent: List<RecentRoomUi> = emptyList(),
    relay: RelayStatusUi = RelayStatusUi(null, unreachable = false, retryLabel = T.watch.relay.retry),
    busy: String? = null,
    failure: String? = null,
    toast: String? = null,
    relaySetting: WatchRelayRow = testRelaySetting(),
) = WatchLobbyUiState(
    title = T.watch.title,
    intro = T.watch.intro,
    relay = relay,
    joinTitle = T.watch.joinTitle,
    joinHint = T.watch.joinHint,
    joinLabel = T.watch.joinLabel,
    joinText = joinText,
    canJoin = canJoin,
    joinError = joinError,
    recentTitle = T.watch.recentTitle,
    recentRooms = recent,
    relaySetting = relaySetting,
    room = room,
    inviteQuestion = inviteQuestion,
    busy = busy,
    failure = failure,
    dismissLabel = T.watch.dismiss,
    toast = toast,
)

/** A guest's mismatch, as the store words it: its file 12 s longer than Sam's; Play anyway, Choose another stream, Don't play. */
internal val testMismatch = WatchPromptUi(
    message = T.watch.player.longer("12 s", "Sam"),
    detail = T.watch.player.mismatchDetail,
    actions = listOf(
        WatchActionUi(T.watch.player.playAnyway, WatchCommand.PlayAnyway),
        WatchActionUi(T.watch.player.chooseStream, WatchCommand.ChooseStream),
        WatchActionUi(T.watch.player.dontPlay, WatchCommand.DontPlay),
    ),
)

/** This device's offset as the store words it: +2.5 s, with -5 s, -0.5 s, +0.5 s, +5 s and Reset. */
internal val testOffset = WatchOffsetUi(
    title = T.watch.player.offsetTitle,
    value = "+" + T.watch.delta.seconds("2.5"),
    detail = T.watch.player.offsetDetail,
    actions = listOf(
        WatchActionUi("-" + T.watch.delta.seconds("5"), WatchCommand.NudgeOffset(-5_000)),
        WatchActionUi("-" + T.watch.delta.seconds("0.5"), WatchCommand.NudgeOffset(-500)),
        WatchActionUi("+" + T.watch.delta.seconds("0.5"), WatchCommand.NudgeOffset(500)),
        WatchActionUi("+" + T.watch.delta.seconds("5"), WatchCommand.NudgeOffset(5_000)),
        WatchActionUi(T.watch.player.offsetReset, WatchCommand.ResetOffset),
    ),
)

/**
 * Watch together in the player: out of a room ([room] null: the start choices), or in [room] with its indicator; a
 * guest that is [matching] its file or holds a [prompt]; this device's [offset] while it plays the room's media;
 * [canControl] false for a guest of a host-only room, [pausedHere] one whose player paused here alone.
 */
internal fun testPlayerWatch(
    room: WatchRoomUi? = null,
    pill: String? = null,
    matching: String? = null,
    prompt: WatchPromptUi? = null,
    offset: WatchOffsetUi? = null,
    canControl: Boolean = true,
    canChangeEpisode: Boolean = true,
    syncing: Boolean = false,
    pausedHere: Boolean = false,
) = PlayerWatchUi(
    title = T.watch.title,
    room = room,
    startTitle = T.watch.player.startTitle,
    startChoices = if (room == null) testStartChoices else emptyList(),
    indicator = room?.let { WatchIndicatorUi(it.participants.size, T.watch.player.watching(it.participants.size), it.isHost, T.watch.player.hostBadge, syncing) },
    pill = pill,
    matching = matching,
    prompt = prompt,
    offset = offset,
    canControl = canControl,
    canChangeEpisode = canChangeEpisode,
    controlsLocked = if (canControl) null else T.watch.player.hostControls("Sam"),
    pausedHere = if (pausedHere) T.watch.player.pausedHere else null,
    busy = null,
    failure = null,
    dismissLabel = T.watch.dismiss,
)
