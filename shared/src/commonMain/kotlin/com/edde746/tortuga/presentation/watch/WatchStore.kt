package com.edde746.tortuga.presentation.watch

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.data.settings.RecentRoom
import com.edde746.tortuga.presentation.settings.AccountText
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.watch.WatchControlMode
import com.edde746.tortuga.watch.WatchPlayback
import com.edde746.tortuga.watch.WatchState
import com.edde746.tortuga.watch.WatchTogether
import com.edde746.tortuga.watch.relay.WatchInvite
import com.edde746.tortuga.watch.relay.WatchRelayEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import party.openwatch.formatCode
import kotlin.time.Clock

/**
 * The Watch together screen (the lobby): join a room by code or invite link, enter a recent room again, set this
 * device's relay ([DeviceSetting.WatchRelay]: the public relays, or a self-hosted one); in a room, the room: its code
 * and invite, the participants with their file match and offset, the host's tools (Make host, Remove, who controls
 * playback, Lock), Leave and End. A room starts in the player ([RoomPlayerWatch]). A self-hosted relay is checked while
 * not in a room; the room's latest event shows as a toast until the screen has shown it ([toastShown]). Main thread
 * only; [release] when the screen goes.
 */
class WatchStore internal constructor(
    private val watch: WatchTogether,
    private val playback: WatchPlayback,
    private val settings: DeviceSettingsRepository,
    private val clock: Clock,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var joinText = ""
    private var relayStatus = RelayCheck.CHECKING
    private var checkedRelay: WatchRelayEndpoint? = null
    private var toast: String? = null
    private var recent: List<RecentRoom> = watch.recentRooms.value
    private var inPlayer = playback.attached.value != null
    private var searchFailed = playback.searchFailed.value

    /** When the first recent room's "Used … ago" next changes ([followUsed]), and the publish waiting for it. */
    private var usedChangesAt: Long? = null
    private var usedJob: Job? = null

    private val mutableState = MutableStateFlow(build(watch.state.value))

    val state: StateFlow<WatchLobbyUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(watch.state, watch.recentRooms, playback.attached, playback.searchFailed) { state, rooms, attached, failed ->
                recent = rooms
                inPlayer = attached != null
                searchFailed = failed
                state
            }.collect { state ->
                if (!state.inRoom && state.relay != checkedRelay) checkRelay()
                publish()
            }
        }
        scope.launch {
            watch.events.collect { event ->
                val state = watch.state.value
                val text = WatchText.event(event, state.media?.title, state.party.peerId) ?: return@collect
                toast = text
                publish()
            }
        }
    }

    /** The join field changed. */
    fun setJoinText(text: String) {
        joinText = text
        publish()
    }

    /** Joins the room the join field names (a code on this device's relay, or an invite link). */
    fun join() {
        if (busy()) return
        val invite = WatchInvite.parseInput(joinText, watch.state.value.relay) ?: return
        joinText = ""
        publish()
        scope.launch { watch.join(invite) }
    }

    /** Enters the recent room [code] on [relay] again. */
    fun enterRecent(code: String, relay: String) {
        if (busy()) return
        val room = recent.firstOrNull { it.code == code && it.relay == relay } ?: return
        scope.launch { watch.enter(room) }
    }

    fun removeRecent(code: String, relay: String) {
        recent.firstOrNull { it.code == code && it.relay == relay }?.let(watch::removeRecent)
    }

    /** Leaves the room; a host's goes on with the next participant as host (the views confirm first, [WatchRoomUi.leave]). */
    fun leave() = watch.leave()

    /** Ends the room for everyone, the host's (the views confirm first, [WatchRoomUi.end]). */
    fun end() = watch.end()

    /** Joins the invite's room, leaving this one if in one ([WatchLobbyUiState.inviteQuestion] confirmed). */
    fun joinInvite() = watch.acceptInvite()

    /** Stays where this device is ([WatchLobbyUiState.inviteQuestion] cancelled). */
    fun dismissInvite() = watch.dismissInvite()

    /** The host hands the room to [peerId]. */
    fun makeHost(peerId: String) = watch.transferHost(peerId)

    /** The host removes [peerId] from the room (the views confirm first, [ParticipantUi.kick]). */
    fun kick(peerId: String) = watch.kick(peerId)

    /** The host sets who controls playback. */
    fun setControlMode(mode: WatchControlMode) = watch.setControl(mode)

    /** The host locks the room to new participants, or opens it again. */
    fun setLocked(locked: Boolean) = watch.lock(locked)

    /** A guest back into the room's playback ([WatchRoomUi.joinPlayback]). */
    fun joinPlayback() = playback.joinPlayback()

    /** Checks the relay again after it was unreachable. */
    fun retryRelay() = checkRelay()

    /**
     * Sets this device's relay to [url] (a self-hosted relay's base URL); false, with nothing stored, when it isn't one.
     * A blank [url] goes back to the public relays.
     */
    fun setRelay(url: String): Boolean {
        if (url.isBlank()) {
            resetRelay()
            return true
        }
        val endpoint = WatchRelayEndpoint.parse(url) ?: return false
        scope.launch { settings.set(DeviceSetting.WatchRelay, endpoint.baseUrl) }
        return true
    }

    /** Back to the public relays. */
    fun resetRelay() {
        scope.launch { settings.reset(DeviceSetting.WatchRelay) }
    }

    fun dismissFailure() = watch.clearFailure()

    /** The toast has shown for its time: it goes. */
    fun toastShown() {
        toast = null
        publish()
    }

    fun release() {
        scope.cancel()
    }

    private fun busy() = watch.state.value.activity != null

    private fun checkRelay() {
        val relay = watch.state.value.relay
        checkedRelay = relay
        if (relay == WatchRelayEndpoint.HOSTED) {
            relayStatus = RelayCheck.OK
            publish()
            return
        }
        relayStatus = RelayCheck.CHECKING
        publish()
        scope.launch {
            val reachable = watch.checkRelay(relay)
            if (watch.state.value.relay != relay) return@launch
            relayStatus = if (reachable) RelayCheck.OK else RelayCheck.UNREACHABLE
            publish()
        }
    }

    private fun publish() {
        mutableState.value = build(watch.state.value)
        followUsed()
    }

    /** Publishes again when the first recent room's "Used … ago" next changes. */
    private fun followUsed() {
        val now = clock.now().toEpochMilliseconds()
        val next = recent.minOfOrNull { AccountText.agoChangesAt(it.lastUsedMillis, now) }
        if (next == usedChangesAt) return
        usedChangesAt = next
        usedJob?.cancel()
        usedJob = next?.let {
            scope.launch {
                delay(it - now)
                usedChangesAt = null
                publish()
            }
        }
    }

    private fun build(state: WatchState): WatchLobbyUiState {
        val relay = state.relay
        val parsed = WatchInvite.parseInput(joinText, relay)
        val now = clock.now().toEpochMilliseconds()
        val room = WatchText.room(state, inPlayer, searchFailed)
        return WatchLobbyUiState(
            title = T.watch.title,
            intro = T.watch.intro,
            relay = RelayStatusUi(
                status = when (relayStatus) {
                    RelayCheck.CHECKING -> T.watch.relay.checking
                    RelayCheck.OK -> null
                    RelayCheck.UNREACHABLE -> T.watch.relay.unreachable(isolate(WatchText.server(relay.baseUrl)))
                },
                unreachable = relayStatus == RelayCheck.UNREACHABLE,
                retryLabel = T.watch.relay.retry,
            ),
            joinTitle = T.watch.joinTitle,
            joinHint = T.watch.joinHint,
            joinLabel = T.watch.joinLabel,
            joinText = joinText,
            canJoin = parsed != null && state.activity == null,
            joinError = if (joinText.isNotBlank() && parsed == null) T.watch.joinInvalid else null,
            recentTitle = T.watch.recentTitle,
            recentRooms = recent.map {
                val label = formatCode(it.code)
                RecentRoomUi(label, it.code, it.relay, WatchText.recentUsed(it.lastUsedMillis, now), T.watch.removeRecentNamed(label))
            },
            relaySetting = relaySetting(relay),
            room = room,
            inviteQuestion = state.invite?.let { WatchText.inviteQuestion(it, relay, room) },
            busy = WatchText.busy(state.activity),
            failure = state.failure?.let(WatchText::failure),
            dismissLabel = T.watch.dismiss,
            toast = toast,
        )
    }

    private fun relaySetting(relay: WatchRelayEndpoint): WatchRelayRow {
        val text = T.watch.relay
        val custom = relay != WatchRelayEndpoint.HOSTED
        return WatchRelayRow(
            title = text.title,
            subtitle = text.hint,
            value = relay.baseUrl,
            valueLabel = if (custom) WatchText.server(relay.baseUrl) else text.hosted,
            custom = custom,
            placeholder = text.placeholder,
            invalid = text.invalid,
            saveLabel = text.save,
            resetLabel = text.reset,
        )
    }

    private enum class RelayCheck { CHECKING, OK, UNREACHABLE }

    companion object {
        /** Its key on the navigator, under its route (test reports read it there). */
        const val KEY: String = "watch"
    }
}
