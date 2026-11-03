package com.edde746.tortuga.presentation.watch

import com.edde746.tortuga.presentation.player.PlayerText
import com.edde746.tortuga.presentation.profiles.profileInitial
import com.edde746.tortuga.presentation.settings.AccountText
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.watch.WatchActivity
import com.edde746.tortuga.watch.WatchControlMode
import com.edde746.tortuga.watch.WatchFailure
import com.edde746.tortuga.watch.WatchState
import com.edde746.tortuga.watch.relay.WatchInvite
import com.edde746.tortuga.watch.relay.WatchRelayEndpoint
import party.openwatch.Connection
import party.openwatch.MatchState
import party.openwatch.Participant
import party.openwatch.PartyEvent
import party.openwatch.PartyException
import party.openwatch.Phase
import party.openwatch.PlayerState
import party.openwatch.formatCode
import kotlin.math.abs
import kotlin.math.roundToLong

/** Watch together's words: the room as both UIs lay it out, its toasts and failures. */
internal object WatchText {
    fun controlModeChoices(): List<ControlModeChoice> = listOf(
        ControlModeChoice(WatchControlMode.HOST_ONLY, T.watch.controlMode.hostOnly, T.watch.controlMode.hostOnlyDetail),
        ControlModeChoice(WatchControlMode.ANYONE, T.watch.controlMode.anyone, T.watch.controlMode.anyoneDetail),
    )

    /**
     * The toast for [event], or null when it says nothing (this device's own doing, an event this version ignores).
     * [myPeerId] is this device's peer id in the room.
     */
    fun event(event: PartyEvent, mediaTitle: String?, myPeerId: String?): String? = when (event) {
        is PartyEvent.ParticipantJoined -> T.watch.toast.joined(isolate(event.participant.name))
        is PartyEvent.ParticipantLeft -> T.watch.toast.left(isolate(event.participant.name))
        is PartyEvent.Action -> action(event, mediaTitle, myPeerId)
        is PartyEvent.Error -> if (event.error.code == PartyException.FORBIDDEN) T.watch.toast.forbidden else null
        else -> null
    }

    private fun action(event: PartyEvent.Action, mediaTitle: String?, myPeerId: String?): String? {
        if (event.kind == HOST) {
            val host = event.room.participants.firstOrNull { it.host } ?: return null
            return if (host.peerId == myPeerId) T.watch.toast.becameHost else T.watch.toast.hostChanged(isolate(host.name))
        }
        if (event.byMe) return null
        val name = event.by?.name?.let(::isolate)
        return when (event.kind) {
            "play" -> name?.let(T.watch.toast::resumed)
            "pause" -> name?.let(T.watch.toast::paused)
            "seek" -> name?.let(T.watch.toast::seeked)
            "rate" -> name?.let { T.watch.toast.speed(it, PlayerText.speed(event.room.rate)) }
            "media" -> if (name != null && mediaTitle != null) T.watch.toast.media(name, isolate(mediaTitle)) else null
            "stall" -> name?.let(T.watch.toast::buffering)
            else -> null
        }
    }

    fun failure(failure: WatchFailure): String = when (failure) {
        WatchFailure.CREATE -> T.watch.failure.create
        WatchFailure.JOIN -> T.watch.failure.join
        WatchFailure.SHARE -> T.watch.failure.share
        WatchFailure.LIVE -> T.watch.failure.live
        WatchFailure.ROOM_NOT_FOUND -> T.watch.failure.roomNotFound
        WatchFailure.ROOM_FULL -> T.watch.failure.roomFull
        WatchFailure.ROOM_LOCKED -> T.watch.failure.roomLocked
        WatchFailure.RELAY_FULL -> T.watch.failure.relayFull
        WatchFailure.RATE_LIMITED -> T.watch.failure.rateLimited
        WatchFailure.CONNECTION -> T.watch.failure.connection
        WatchFailure.BLOCKED -> T.watch.failure.blocked
        WatchFailure.SESSION_LOST -> T.watch.failure.sessionLost
        WatchFailure.ENDED -> T.watch.failure.ended
        WatchFailure.KICKED -> T.watch.failure.kicked
        WatchFailure.EXPIRED -> T.watch.failure.expired
        WatchFailure.REPLACED -> T.watch.failure.replaced
    }

    fun busy(activity: WatchActivity?): String? = when (activity) {
        WatchActivity.CREATING -> T.watch.busy.creating
        WatchActivity.JOINING -> T.watch.busy.joining
        null -> null
    }

    /**
     * The question [invite] asks before this device follows it. Out of a room (only an invite on another relay than this
     * device's asks, [com.edde746.tortuga.watch.WatchTogether.openInvite]): whether to join it there, naming the code and
     * the relay ("Join room K7M-Q2X? It's on relay.example.com, not on your relay server."), not destructive. In [room]:
     * the room's Leave question, which also names the code and the relay when that is another relay than [deviceRelay].
     */
    fun inviteQuestion(invite: WatchInvite, deviceRelay: WatchRelayEndpoint, room: WatchRoomUi?): ConfirmAction {
        val code = formatCode(invite.code)
        val relay = if (invite.relay == WatchRelayEndpoint.HOSTED) T.watch.relay.hosted else isolate(server(invite.relay.baseUrl))
        val text = T.watch.invite
        return when {
            room == null -> ConfirmAction(text.join, text.title(code, relay), text.join, T.watch.room.cancel, destructive = false)
            invite.relay == deviceRelay -> room.leave
            else -> room.leave.copy(title = if (room.isHost) text.leaveHostTitle(code, relay) else text.leaveTitle(code, relay))
        }
    }

    /** A length difference as people read it: "0.4 s", "12 s", "2 min 5 s". */
    fun delta(deltaMs: Long): String {
        val millis = abs(deltaMs)
        return when {
            millis < 10_000 -> T.watch.delta.seconds(PlayerText.decimal(millis / 1000.0, 1, 1))
            millis < 60_000 -> T.watch.delta.seconds((millis / 1000.0).roundToLong().toString())
            else -> {
                val seconds = (millis / 1000.0).roundToLong()
                T.watch.delta.minutes((seconds / 60).toString(), (seconds % 60).toString())
            }
        }
    }

    /** An offset with its sign: "+2.5 s", "-12 s", "0 s". */
    fun signedDelta(deltaMs: Long): String = when {
        deltaMs > 0 -> "+" + delta(deltaMs)
        deltaMs < 0 -> "-" + delta(deltaMs)
        else -> T.watch.delta.seconds("0")
    }

    /** What a participant is doing, and how it reads; [searchFailed] its search for a file met a failing addon. */
    fun status(participant: Participant, roomHasMedia: Boolean, searchFailed: Boolean): Pair<String?, ParticipantTone> {
        if (!participant.connected) return T.watch.status.away to ParticipantTone.WARNING
        if (!roomHasMedia) return null to ParticipantTone.NEUTRAL
        val match = participant.match
        return when {
            participant.player == PlayerState.BUFFERING -> T.watch.status.buffering to ParticipantTone.BUSY
            match?.state == MatchState.UNAVAILABLE -> when {
                match.deltaMs != null -> T.watch.status.declined
                searchFailed -> T.watch.status.searchFailed
                else -> T.watch.status.noStream
            } to ParticipantTone.WARNING
            match?.state == MatchState.MISMATCHED -> lengthDifference(match.deltaMs ?: 0) to ParticipantTone.WARNING
            match?.state == MatchState.SEARCHING -> T.watch.status.matching to ParticipantTone.BUSY
            participant.player == PlayerState.IDLE -> T.watch.status.notPlaying to ParticipantTone.NEUTRAL
            participant.player == PlayerState.LOADING -> T.watch.status.loading to ParticipantTone.BUSY
            match?.state == MatchState.MATCHED && match.deltaMs != null && match.deltaMs != 0L -> T.watch.status.sameLength(delta(match.deltaMs ?: 0)) to ParticipantTone.GOOD
            else -> T.watch.status.ready to ParticipantTone.GOOD
        }
    }

    private fun lengthDifference(deltaMs: Long): String =
        if (deltaMs >= 0) T.watch.status.longer(delta(deltaMs)) else T.watch.status.shorter(delta(deltaMs))

    /**
     * The room [state] is in, as both UIs lay it out; [inPlayer] this device plays the room's media, [searchFailed] its
     * search for a file met a failing addon ([com.edde746.tortuga.watch.WatchPlayback.searchFailed]).
     */
    fun room(state: WatchState, inPlayer: Boolean, searchFailed: Boolean): WatchRoomUi? {
        val party = state.party
        val room = party.room ?: return null
        if (!state.inRoom) return null
        val isHost = party.isHost
        val host = room.participants.firstOrNull { it.host }
        val media = state.media
        val relay = state.roomRelay ?: WatchRelayEndpoint.HOSTED
        val link = WatchInvite(room.code, relay).toUrl()
        val code = formatCode(room.code)
        val participants = room.participants.map { participant ->
            val me = participant.peerId == party.peerId
            val (status, tone) = status(participant, media != null, searchFailed = me && searchFailed)
            ParticipantUi(
                peerId = participant.peerId,
                name = if (me) T.watch.room.you(isolate(participant.name)) else participant.name,
                isYou = me,
                initial = profileInitial(participant.name).ifEmpty { "?" },
                isHost = participant.host,
                status = status,
                tone = tone,
                offset = participant.offsetMs.takeIf { it != 0L }?.let { T.watch.status.offset(signedDelta(it)) },
                canMakeHost = isHost && !me && participant.connected,
                kick = if (isHost && !me) {
                    ConfirmAction(T.watch.room.kickNamed(isolate(participant.name)), T.watch.room.kickTitle(isolate(participant.name)), T.watch.room.kickConfirm, T.watch.room.cancel)
                } else {
                    null
                },
            )
        }
        val mode = WatchControlMode.of(room.control)
        return WatchRoomUi(
            code = code,
            codeLabel = T.watch.room.codeLabel,
            copyCodeLabel = T.watch.room.copyCode,
            codeCopied = T.watch.room.codeCopied,
            inviteUrl = link,
            shareText = T.watch.room.shareText(link, code),
            shareLabel = T.watch.room.share,
            scanLabel = T.watch.room.scan,
            role = if (isHost) T.watch.room.youHost else T.watch.room.hostedBy(host?.name?.let(::isolate) ?: "…"),
            isHost = isHost,
            controlMode = if (mode == WatchControlMode.ANYONE) T.watch.room.anyone else T.watch.room.hostOnly,
            controlTitle = T.watch.room.controlTitle,
            controlChoices = if (isHost) controlModeChoices() else emptyList(),
            selectedMode = mode,
            lock = if (isHost) ToggleUi(T.watch.room.lock, T.watch.room.lockDetail, room.locked) else null,
            lockedText = if (room.locked && !isHost) T.watch.room.lockDetail else null,
            connection = when {
                party.connection == Connection.RECONNECTING -> T.watch.room.reconnecting
                host != null && !host.connected && !isHost -> T.watch.room.hostReconnecting(isolate(host.name))
                else -> null
            },
            nowPlaying = media?.let { if (it.episodeLine != null) T.watch.room.nowPlayingEpisode(isolate(it.title), it.episodeLine) else T.watch.room.nowPlaying(isolate(it.title)) },
            idleText = if (media != null) null else if (isHost) T.watch.room.idleHost else T.watch.room.idleGuest(host?.name?.let(::isolate) ?: "…"),
            participantsLabel = T.watch.room.participants(participants.size),
            participants = participants,
            makeHostLabel = T.watch.room.makeHost,
            hostBadge = T.watch.room.hostBadge,
            joinPlayback = if (media != null && !inPlayer && room.phase != Phase.IDLE) T.watch.room.joinPlayback else null,
            leave = ConfirmAction(T.watch.room.leave, if (isHost) T.watch.room.leaveHostTitle else T.watch.room.leaveTitle, T.watch.room.leaveConfirm, T.watch.room.cancel),
            end = if (isHost) ConfirmAction(T.watch.room.end, T.watch.room.endTitle, T.watch.room.endConfirm, T.watch.room.cancel) else null,
        )
    }

    /** "Used 3 days ago". */
    fun recentUsed(thenMillis: Long, nowMillis: Long): String = T.watch.recentUsed(AccountText.ago(thenMillis, nowMillis))

    /** The relay's host, as a person reads it. */
    fun server(baseUrl: String): String = AccountText.server(baseUrl)

    private const val HOST = "host"
}
