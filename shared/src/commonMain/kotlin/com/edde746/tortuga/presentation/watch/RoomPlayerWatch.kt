package com.edde746.tortuga.presentation.watch

import com.edde746.tortuga.input.WatchCommand
import com.edde746.tortuga.presentation.player.PlayerWatch
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.watch.RoomPlayback
import com.edde746.tortuga.watch.WatchPlayback
import com.edde746.tortuga.watch.WatchState
import com.edde746.tortuga.watch.WatchTogether
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import party.openwatch.MatchState
import party.openwatch.Phase
import party.openwatch.PlayerState
import kotlin.math.roundToLong

/**
 * Watch together in [active]'s player ([PlayerWatch]): the room from [watch], this playback's part in it from
 * [playback] (attached to the room or not, a guest's search for its file, paused here alone), formatted for the views.
 */
internal class RoomPlayerWatch(
    private val watch: WatchTogether,
    private val playback: WatchPlayback,
    private val active: RoomPlayback,
    private val scope: CoroutineScope,
) : PlayerWatch {
    override val state: StateFlow<PlayerWatchUi?> = combine(
        watch.state,
        playback.attached,
        playback.following,
        combine(playback.pickFailed, playback.away, playback.searchFailed, ::Triple),
        active.session.info.map { it.live }.distinctUntilChanged(),
    ) { state, _, _, (pickFailed, _, searchFailed), live ->
        build(state, playback.routes(active), playback.follows(active), pickFailed, searchFailed, playback.stoppedHere(active), live)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(),
        build(
            watch.state.value, playback.routes(active), playback.follows(active), playback.pickFailed.value, playback.searchFailed.value,
            playback.stoppedHere(active), active.session.info.value.live,
        ),
    )

    override val notices: Flow<String> = watch.events.mapNotNull { event ->
        WatchText.event(event, watch.state.value.media?.let { it.episodeLine?.let { line -> T.detail.infoJoin(isolate(it.title), line) } ?: it.title }, watch.state.value.party.peerId)
    }

    override val routesControls: Boolean get() = playback.routes(active)

    override val roomRate: Double? get() = watch.state.value.party.room?.takeIf { routesControls }?.rate

    override val stoppedHere: Boolean get() = playback.stoppedHere(active)

    override fun stopHere() = playback.stopHere(active)

    override fun resumeHere() = playback.resumeHere(active)

    override fun play() = playback.play()

    override fun pause() = playback.pause()

    override fun onLocalSeek(seconds: Double) {
        if (routesControls) playback.seek((seconds * 1000).roundToLong())
    }

    override fun onLocalRate(rate: Double) {
        if (routesControls) playback.setRate(rate)
    }

    override fun execute(command: WatchCommand) {
        when (command) {
            is WatchCommand.Start -> scope.launch { watch.create(command.mode) }
            WatchCommand.PlayAnyway -> playback.playAnyway()
            WatchCommand.DontPlay -> playback.dontPlay()
            WatchCommand.ChooseStream -> playback.chooseStream()
            WatchCommand.Leave -> watch.leave()
            WatchCommand.End -> watch.end()
            is WatchCommand.MakeHost -> watch.transferHost(command.peerId)
            is WatchCommand.Kick -> watch.kick(command.peerId)
            is WatchCommand.SetControlMode -> watch.setControl(command.mode)
            is WatchCommand.Lock -> watch.lock(command.locked)
            is WatchCommand.NudgeOffset -> playback.nudgeOffset(command.deltaMs)
            WatchCommand.ResetOffset -> playback.resetOffset()
            WatchCommand.JoinPlayback -> playback.joinPlayback()
            WatchCommand.DismissFailure -> watch.clearFailure()
        }
    }

    companion object {
        /**
         * The player's watch together: [attached] this player is attached to the room with its file, [following] it
         * follows the room (attached, or a guest's player looking for its file), [pickFailed] a file picked by hand
         * didn't open, [searchFailed] the search for a file met a failing addon, [stoppedHere] it paused here alone, out
         * of the room's playback ([WatchPlayback.stopHere]), [live] it plays a live stream, which can't start a room (its
         * length isn't known).
         */
        fun build(
            state: WatchState,
            attached: Boolean,
            following: Boolean,
            pickFailed: Boolean,
            searchFailed: Boolean,
            stoppedHere: Boolean,
            live: Boolean,
        ): PlayerWatchUi {
            val party = state.party
            val room = party.room?.takeIf { state.inRoom }
            val host = room?.participants?.firstOrNull { it.host }
            val hostName = host?.name?.let(::isolate) ?: "…"
            // A playback beside the room's (another title, the room's left behind) is the viewer's to control.
            val inRoom = room != null && following
            val canControl = !inRoom || party.canControl
            val media = state.media
            val guest = room != null && following && !party.isHost && media != null
            return PlayerWatchUi(
                title = T.watch.title,
                room = WatchText.room(state, attached, searchFailed),
                startTitle = if (live) T.watch.player.liveNoRoom else T.watch.player.startTitle,
                startChoices = if (room == null && !live) WatchText.controlModeChoices() else emptyList(),
                indicator = room?.let {
                    WatchIndicatorUi(
                        count = it.participants.size,
                        label = T.watch.player.watching(it.participants.size),
                        isHost = party.isHost,
                        hostBadge = T.watch.player.hostBadge,
                        syncing = it.phase == Phase.WAITING,
                    )
                },
                pill = if (room != null && attached) pill(state) else null,
                matching = if (guest && party.choice == null && party.mismatch == null && party.match?.state == MatchState.SEARCHING) {
                    T.watch.player.matchingStart(hostName)
                } else {
                    null
                },
                prompt = if (guest) prompt(state, hostName, pickFailed, searchFailed) else null,
                offset = if (room != null && attached && media != null) offset(party.offsetMs) else null,
                canControl = canControl,
                canChangeEpisode = !inRoom || party.isHost,
                controlsLocked = if (canControl) null else if (host != null) T.watch.player.hostControls(hostName) else T.watch.player.hostControlsUnnamed,
                pausedHere = if (stoppedHere) T.watch.player.pausedHere else null,
                busy = WatchText.busy(state.activity),
                failure = state.failure?.let(WatchText::failure),
                dismissLabel = T.watch.dismiss,
            )
        }

        private fun pill(state: WatchState): String? {
            val party = state.party
            val room = party.room ?: return null
            val waiting = if (room.phase == Phase.WAITING) {
                room.waitingOn.filter { it != party.peerId }.mapNotNull { id -> room.participants.firstOrNull { it.peerId == id }?.name?.let(::isolate) }
            } else {
                emptyList()
            }
            return when {
                waiting.size > MAX_NAMES ->
                    T.watch.player.waitingMore(waiting.size - MAX_NAMES, waiting.take(MAX_NAMES).joinToString(T.detail.listSeparator))
                waiting.isNotEmpty() -> T.watch.player.waiting(waiting.joinToString(T.detail.listSeparator))
                room.phase == Phase.WAITING || party.player == PlayerState.LOADING -> T.watch.player.syncing
                else -> null
            }
        }

        private fun prompt(state: WatchState, hostName: String, pickFailed: Boolean, searchFailed: Boolean): WatchPromptUi? {
            val party = state.party
            val title = state.media?.title?.let(::isolate) ?: return null
            val mismatch = party.mismatch
            val match = party.match
            return when {
                pickFailed -> WatchPromptUi(
                    message = T.watch.player.pickFailed,
                    detail = null,
                    actions = listOf(WatchActionUi(T.watch.player.chooseStream, WatchCommand.ChooseStream)),
                )
                mismatch != null -> WatchPromptUi(
                    message = if (mismatch.deltaMs >= 0) {
                        T.watch.player.longer(WatchText.delta(mismatch.deltaMs), hostName)
                    } else {
                        T.watch.player.shorter(WatchText.delta(mismatch.deltaMs), hostName)
                    },
                    detail = T.watch.player.mismatchDetail,
                    actions = listOf(
                        WatchActionUi(T.watch.player.playAnyway, WatchCommand.PlayAnyway),
                        WatchActionUi(T.watch.player.chooseStream, WatchCommand.ChooseStream),
                        WatchActionUi(T.watch.player.dontPlay, WatchCommand.DontPlay),
                    ),
                )
                party.choice != null || match?.state != MatchState.UNAVAILABLE -> null
                // Declined: the closest file was offered and the viewer didn't play it.
                match.deltaMs != null -> WatchPromptUi(
                    message = T.watch.player.declined(title),
                    detail = null,
                    actions = listOf(WatchActionUi(T.watch.player.chooseStream, WatchCommand.ChooseStream)),
                )
                else -> WatchPromptUi(
                    message = if (searchFailed) T.watch.player.searchFailed(title) else T.watch.player.noStream(title),
                    detail = null,
                    actions = listOf(WatchActionUi(T.watch.player.chooseAStream, WatchCommand.ChooseStream)),
                )
            }
        }

        private fun offset(offsetMs: Long): WatchOffsetUi = WatchOffsetUi(
            title = T.watch.player.offsetTitle,
            value = WatchText.signedDelta(offsetMs),
            detail = T.watch.player.offsetDetail,
            actions = listOf(
                WatchActionUi(WatchText.signedDelta(-OFFSET_STEP_LARGE), WatchCommand.NudgeOffset(-OFFSET_STEP_LARGE)),
                WatchActionUi(WatchText.signedDelta(-OFFSET_STEP_SMALL), WatchCommand.NudgeOffset(-OFFSET_STEP_SMALL)),
                WatchActionUi(WatchText.signedDelta(OFFSET_STEP_SMALL), WatchCommand.NudgeOffset(OFFSET_STEP_SMALL)),
                WatchActionUi(WatchText.signedDelta(OFFSET_STEP_LARGE), WatchCommand.NudgeOffset(OFFSET_STEP_LARGE)),
                WatchActionUi(T.watch.player.offsetReset, WatchCommand.ResetOffset),
            ),
        )

        private const val MAX_NAMES = 2
        private const val OFFSET_STEP_SMALL = 500L
        private const val OFFSET_STEP_LARGE = 5_000L
    }
}
