package com.edde746.tortuga.watch

import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.data.settings.RecentRoom
import com.edde746.tortuga.data.settings.RecentRooms
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.watch.media.FileMatcher
import com.edde746.tortuga.watch.media.RoomFile
import com.edde746.tortuga.watch.media.RoomMediaBuilder
import com.edde746.tortuga.watch.media.RoomTitleResolver
import com.edde746.tortuga.watch.media.roomMedia
import com.edde746.tortuga.watch.relay.WatchInvite
import com.edde746.tortuga.watch.relay.WatchRelayEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import party.openwatch.EndReason
import party.openwatch.PartyEvent
import party.openwatch.PartyException
import party.openwatch.PartyState
import party.openwatch.Relays
import party.openwatch.Resolver
import party.openwatch.WatchParty
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Watch together for one running profile, on Open Watch Party (the `party.openwatch` SDK): the room this device is in,
 * as a [WatchParty] on the public relays or on one self-hosted relay ([DeviceSetting.WatchRelay]; an invite's relay
 * wins for its room).
 *
 * - [create] starts a room with this device as host (6-symbol codes), [join] enters one by code or invite, [enter] a
 *   recent room again (making it again under its code when it ended); [leave] leaves (a host's room goes on, the relay
 *   promotes the next participant) and [end] ends it for everyone. A create or join whose caller is cancelled (its
 *   screen went) ends with nothing under way.
 * - The room's player side is [playback]: the SDK's resolver and the player it attaches.
 * - [events] are the party's events (actions, participants coming and going, refusals, the end).
 *
 * Recent rooms are kept per profile and relay ([RecentRooms]). Main thread only.
 */
internal class WatchTogether(
    private val http: HttpClient,
    private val deviceSettings: DeviceSettingsRepository,
    profileId: String,
    private val displayName: suspend () -> String,
    /** The client name rooms list: `Tortuga/<version>`. */
    private val client: String,
    private val wallClock: Clock,
    /**
     * Whether the app can be seen, as the platform reports it: in the foreground, or its window in sight behind another
     * app's ([com.edde746.tortuga.app.AppGraph.visible]). The room's player follows the room only then, or in picture in
     * picture ([WatchPlayback.setAppVisible]).
     */
    private val visible: Flow<Boolean>,
    /** Outlives this object, so a room's leave finishes after [close]. */
    private val parentScope: CoroutineScope,
    /** The app's playbacks, as the room's player side ([playback]) opens, follows and closes them. */
    host: WatchPlaybackHost,
    /** The host's file as the room's media. */
    mediaBuilder: RoomMediaBuilder,
    /** A room's media as this profile's own title. */
    titles: RoomTitleResolver,
    /** This profile's files of a room's media. */
    matcher: FileMatcher,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val recentSetting = RecentRooms(profileId)
    private val publicRelays: Relays by lazy { Relays.public(http) }
    private val mutableState = MutableStateFlow(WatchState(relay = WatchRelayEndpoint.HOSTED))
    private val mutableEvents = MutableSharedFlow<PartyEvent>(extraBufferCapacity = 64)
    private val mutableRecent = MutableStateFlow<List<RecentRoom>>(emptyList())
    private var partyJobs: List<Job> = emptyList()
    private var endingByMe = false
    private var operation = 0

    val playback = WatchPlayback(this, scope, host, mediaBuilder, titles, matcher)

    val state: StateFlow<WatchState> = mutableState.asStateFlow()

    val events: SharedFlow<PartyEvent> = mutableEvents.asSharedFlow()

    /** This profile's recent rooms on this device's relay, newest first. */
    val recentRooms: StateFlow<List<RecentRoom>> get() = mutableRecent.asStateFlow()

    /** The party of the room this device is in or joining, or the last one; null before the first. */
    var party: WatchParty<RoomFile>? = null
        private set

    init {
        scope.launch {
            deviceSettings.observe().map { WatchRelayEndpoint.resolve(it.watchRelay) }.distinctUntilChanged().collect { relay ->
                mutableState.update { it.copy(relay = relay) }
                loadRecent()
            }
        }
        // The platform's reports only: the value before the first one says nothing.
        scope.launch { visible.drop(1).collect { playback.setAppVisible(it) } }
    }

    /** Whether [relay] answers: a self-hosted relay's `/health`; the public relays aren't checked. */
    suspend fun checkRelay(relay: WatchRelayEndpoint = state.value.relay): Boolean {
        if (relay == WatchRelayEndpoint.HOSTED) return true
        return try {
            withTimeout(HEALTH_TIMEOUT) { http.get(relay.baseUrl + HEALTH).status.isSuccess() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG) { "the relay's health check failed: ${e.message}" }
            false
        }
    }

    /** Starts a room on this device's relay with this device as host, [mode] deciding who controls playback. */
    suspend fun create(mode: WatchControlMode) {
        val relay = state.value.relay
        val op = begin(relay, WatchActivity.CREATING)
        try {
            val party = open(op, relay) ?: return
            val code = party.create()
            if (op != operation) return
            if (mode != WatchControlMode.HOST_ONLY) party.setControl(mode.control)
            remember(code, relay, anyoneControls = mode == WatchControlMode.ANYONE)
            mutableState.update { it.copy(activity = null) }
            playback.onRoomStarted()
        } catch (e: CancellationException) {
            cancelled(op)
            throw e
        } catch (e: PartyException) {
            failed(op, e, WatchFailure.CREATE)
        }
    }

    /** Joins the room of [invite] (a code typed here is an invite on this device's relay, [WatchInvite.parseInput]). */
    suspend fun join(invite: WatchInvite) = join(invite, remade = null)

    /**
     * Joins the room of [invite]. With [remade], a code no room has any more gets a new room under it (Open Watch Party's
     * `join` with `create`), this device its host in [remade]'s control mode, as after [create]; a typed code never
     * does, so a typo still finds no room.
     */
    private suspend fun join(invite: WatchInvite, remade: WatchControlMode?) {
        val op = begin(invite.relay, WatchActivity.JOINING)
        try {
            val party = open(op, invite.relay) ?: return
            party.join(invite.code, create = remade != null)
            if (op != operation) return
            val joined = party.state.value
            // A room made again has this device as its only participant: its host, with no media yet.
            val made = remade.takeIf { joined.isHost && joined.media == null }
            if (made != null && made != WatchControlMode.HOST_ONLY) party.setControl(made.control)
            val mode = made ?: joined.room?.control?.let(WatchControlMode::of)
            remember(invite.code, invite.relay, anyoneControls = mode == WatchControlMode.ANYONE)
            mutableState.update { it.copy(activity = null) }
            if (made != null) playback.onRoomStarted()
        } catch (e: CancellationException) {
            cancelled(op)
            throw e
        } catch (e: PartyException) {
            failed(op, e, WatchFailure.JOIN)
        }
    }

    /**
     * An invite link the OS handed the app. The room this device is in already: nothing. Another room: it waits for the
     * viewer to leave this one first; out of a room, a room on another relay than this device's ([WatchState.relay]):
     * it waits for the viewer to join it there ([WatchState.invite]; [acceptInvite], [dismissInvite]). Otherwise it is
     * joined at once.
     */
    suspend fun openInvite(invite: WatchInvite) {
        val current = state.value
        if (current.inRoom) {
            if (current.party.room?.code != invite.code || current.roomRelay != invite.relay) mutableState.update { it.copy(invite = invite) }
            return
        }
        if (invite.relay != current.relay) {
            mutableState.update { it.copy(invite = invite) }
            return
        }
        join(invite)
    }

    /** Follows the waiting invite: leaves this room, if any, and joins the invite's. */
    fun acceptInvite() {
        val invite = state.value.invite ?: return
        mutableState.update { it.copy(invite = null) }
        scope.launch { join(invite) }
    }

    fun dismissInvite() = mutableState.update { it.copy(invite = null) }

    /**
     * Joins the recent room [recent] again. One that ended (the relay forgets a room once everyone left) is made again
     * under its code, with this device as host in the control mode it had ([RecentRoom.anyoneControls]) and the file
     * open here as its media.
     */
    suspend fun enter(recent: RecentRoom) {
        val relay = WatchRelayEndpoint.resolve(recent.relay)
        join(WatchInvite(recent.code, relay), remade = if (recent.anyoneControls) WatchControlMode.ANYONE else WatchControlMode.HOST_ONLY)
    }

    /** Leaves the room; the relay hands a host's room to the next participant. */
    fun leave() {
        operation++
        mutableState.update { it.copy(activity = null, invite = null) }
        party?.leave()
    }

    /** Ends the room for everyone (the host's). */
    fun end() = command { it.end().also { endingByMe = true } }

    fun transferHost(peerId: String) = command { it.transferHost(peerId) }

    fun kick(peerId: String) = command { it.kick(peerId) }

    fun lock(locked: Boolean) = command { it.lock(locked) }

    fun setControl(mode: WatchControlMode) = command { it.setControl(mode.control) }

    fun removeRecent(room: RecentRoom) {
        scope.launch {
            deviceSettings.set(recentSetting, deviceSettings.value(recentSetting).filterNot { it.code == room.code && it.relay == room.relay })
            loadRecent()
        }
    }

    fun clearFailure() = mutableState.update { it.copy(failure = null) }

    /** [failure] shows: something the room's player side couldn't do ([WatchPlayback]). */
    internal fun showFailure(failure: WatchFailure) = mutableState.update { it.copy(failure = failure) }

    /** Leaves the room and stops; the leave finishes on the parent scope. */
    fun close() {
        operation++
        party?.let { it.leave(); it.close() }
        playback.close()
        scope.cancel()
    }

    // Opening a room

    /** A create or join on [relay] starts ([activity]); it supersedes the one under way and a waiting invite. Its number. */
    private fun begin(relay: WatchRelayEndpoint, activity: WatchActivity): Int {
        val op = ++operation
        mutableState.update { it.copy(roomRelay = relay, activity = activity, failure = null, invite = null) }
        return op
    }

    /** The caller of operation [op] was cancelled: unless another one took over, nothing is under way any more. */
    private fun cancelled(op: Int) {
        if (op == operation) mutableState.update { it.copy(activity = null) }
    }

    /**
     * A new party for [relay] for operation [op], the previous one left; null when another operation took over, or
     * (with a failure) when [relay] can't be used. Throws the SDK's refusal of the party ([WatchParty]'s checks).
     */
    private suspend fun open(op: Int, relay: WatchRelayEndpoint): WatchParty<RoomFile>? {
        val name = partyName(displayName())
        if (op != operation) return null
        party?.let { old ->
            old.leave()
            old.close()
        }
        partyJobs.forEach(Job::cancel)
        playback.onRoomEnded()
        endingByMe = false
        // The old party's state stops here: nothing shows it while the new one can't be made.
        mutableState.update { it.copy(party = PartyState(), media = null) }
        val relays = try {
            if (relay == WatchRelayEndpoint.HOSTED) publicRelays else Relays.at(http, relay.baseUrl)
        } catch (e: PartyException) {
            Log.w(TAG, e) { "the relay setting isn't a relay" }
            mutableState.update { it.copy(failure = WatchFailure.CONNECTION, activity = null) }
            return null
        }
        val resolver = Resolver<RoomFile> { media -> playback.resolve(media) }
        // The profile's name, as rooms list it.
        val next = WatchParty(relays = relays, client = client, name = name, resolver = resolver, scope = parentScope)
        party = next
        mutableState.update { it.copy(party = next.state.value) }
        partyJobs = listOf(
            scope.launch {
                var lastMedia: party.openwatch.Media? = null
                var lastRoomMedia: com.edde746.tortuga.watch.media.RoomMedia? = null
                next.state.collect { s ->
                    if (s.media !== lastMedia) {
                        lastMedia = s.media
                        lastRoomMedia = s.media?.let(::roomMedia)
                    }
                    mutableState.update { it.copy(party = s, media = lastRoomMedia) }
                    playback.onState(s)
                }
            },
            scope.launch {
                next.events.collect { event ->
                    if (event is PartyEvent.Ended) onEnded(event)
                    mutableEvents.emit(event)
                }
            },
        )
        return next
    }

    private fun onEnded(event: PartyEvent.Ended) {
        val failure = when (event.reason) {
            EndReason.LEFT -> null
            EndReason.ENDED -> WatchFailure.ENDED.takeUnless { endingByMe }
            EndReason.KICKED -> WatchFailure.KICKED
            EndReason.EXPIRED -> WatchFailure.EXPIRED
            EndReason.REPLACED -> WatchFailure.REPLACED
            EndReason.LOST -> event.error?.let { failureOf(it, WatchFailure.SESSION_LOST) } ?: WatchFailure.SESSION_LOST
        }
        playback.onRoomEnded()
        mutableState.update { it.copy(failure = failure ?: it.failure, activity = null, invite = null) }
    }

    private fun failed(op: Int, error: PartyException, fallback: WatchFailure) {
        if (op != operation) return
        Log.w(TAG, error) { "the room operation failed" }
        val failure = if (error.code == PartyException.CANCELLED) null else failureOf(error, fallback)
        mutableState.update { it.copy(activity = null, failure = failure) }
    }

    private inline fun command(block: (WatchParty<RoomFile>) -> Unit) {
        val party = party ?: return
        try {
            block(party)
        } catch (e: PartyException) {
            Log.w(TAG, e) { "the party refused a host command" }
        }
    }

    // Recent rooms

    private fun remember(code: String, relay: WatchRelayEndpoint, anyoneControls: Boolean) {
        scope.launch {
            val rooms = deviceSettings.value(recentSetting)
            val entry = RecentRoom(code = code, relay = relay.baseUrl, lastUsedMillis = wallClock.now().toEpochMilliseconds(), anyoneControls = anyoneControls)
            deviceSettings.set(recentSetting, listOf(entry) + rooms.filterNot { it.code == code && it.relay == relay.baseUrl })
            loadRecent()
        }
    }

    private suspend fun loadRecent() {
        val relay = state.value.relay.baseUrl
        mutableRecent.value = deviceSettings.value(recentSetting).filter { it.relay == relay && WatchInvite.normalizeCode(it.code) == it.code }
    }

    companion object {
        private const val TAG = "watch"
        private const val HEALTH = "/health"
        private val HEALTH_TIMEOUT = 5.seconds
        private const val NAME_PLACEHOLDER = "Tortuga"
        private const val MAX_NAME_LENGTH = 32

        /**
         * The party's name for this device: [name] as Open Watch Party takes it ([partyText]), at most 32 characters
         * ([WatchParty]'s rule); "Tortuga" when nothing is left.
         */
        internal fun partyName(name: String): String = partyText(name, MAX_NAME_LENGTH).ifEmpty { NAME_PLACEHOLDER }

        internal fun failureOf(error: PartyException, fallback: WatchFailure): WatchFailure = when (error.code) {
            PartyException.ROOM_NOT_FOUND, PartyException.INVALID_CODE -> WatchFailure.ROOM_NOT_FOUND
            PartyException.RELAY_FULL -> WatchFailure.RELAY_FULL
            PartyException.UNREACHABLE -> WatchFailure.CONNECTION
            PartyException.CLIENT_BLOCKED -> WatchFailure.BLOCKED
            "room_full" -> WatchFailure.ROOM_FULL
            "room_locked" -> WatchFailure.ROOM_LOCKED
            "rate_limited" -> WatchFailure.RATE_LIMITED
            else -> fallback
        }
    }
}
