package com.edde746.tortuga.watch

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.playback.session.OpenedFile
import com.edde746.tortuga.playback.session.PlayPurpose
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.PlaybackSession
import com.edde746.tortuga.watch.media.FileMatcher
import com.edde746.tortuga.watch.media.RoomFile
import com.edde746.tortuga.watch.media.RoomMedia
import com.edde746.tortuga.watch.media.RoomMediaBuilder
import com.edde746.tortuga.watch.media.RoomTitle
import com.edde746.tortuga.watch.media.RoomTitleResolver
import com.edde746.tortuga.watch.media.roomMedia
import com.edde746.tortuga.watch.media.toMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import party.openwatch.Candidate
import party.openwatch.Media
import party.openwatch.PartyException
import party.openwatch.PartyState
import party.openwatch.PlayerAdapter
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the room asks of the app's playbacks ([com.edde746.tortuga.app.PlaybackController]). */
internal interface WatchPlaybackHost {
    /** The playback the player shows, or null. */
    val active: RoomPlayback?

    /** Opens the player over a new playback with nothing loaded yet (a guest's, while it looks for a file). */
    fun openRoomPlayer(): RoomPlayback?

    /** Closes the player (a guest whose host left it). */
    fun closePlayer()

    /** Opens the Streams sheet of [videoId] of [meta] over the player (a guest choosing its file by hand). */
    fun openStreams(meta: MetaItem, videoId: String)
}

/**
 * One of the app's playbacks as the room follows it: its [session], which opens the room's files and says which is
 * open, its [player] as the room drives it, and whether that player shows in a picture-in-picture window
 * ([pictureInPicture]), where it stays in the room out of sight.
 */
internal class RoomPlayback(val session: PlaybackSession, val player: RoomPlayer, val pictureInPicture: StateFlow<Boolean>)

/**
 * A player as the room drives it (the Open Watch Party SDK's adapter), which tells [onChanged] at once of the changes
 * the room reads from it: paused, buffering, the file, a seek landing.
 */
internal interface RoomPlayer : PlayerAdapter {
    var onChanged: (() -> Unit)?
}

/**
 * The player side of a room, over the Open Watch Party SDK's party ([WatchTogether.party]): the SDK's resolver, the
 * player it attaches, and what the app opens for it.
 *
 * - **Host.** Every file the host opens in a room opens paused ([beforePlay]); once it is open and its duration known
 *   it becomes the room's media (`setMedia`) and the player is attached with it. A reload of the same file stays the
 *   room's. Leaving the player clears the media for everyone; another playback taking its place shares its file next.
 *   A live stream is never the room's (its length isn't known, and the room ends at its media's length): the host's
 *   live playback isn't shared, and the room's media is cleared while it plays.
 * - **Guest.** Each new media pulls the guest's player in, and the SDK resolves it: [resolve] lists the guest's files
 *   ([FileMatcher.files]) and each probe opens one in the player, paused at the room's position, until the SDK
 *   decides. The choice the SDK publishes is opened unless the player has it open already, and the player is attached
 *   with the file it actually has open. A file the player opens that isn't the choice (a stream picked by hand, or the
 *   session's next stream after a failure) is offered to the SDK as a candidate of its own ([WatchParty.choose]).
 *   Leaving the player detaches it; [joinPlayback] comes back. A search the SDK goes on with meanwhile opens nothing.
 * - **Host changes.** A promoted guest keeps its attached file (the room's media doesn't change); an open file that
 *   isn't attached becomes the room's, and with none open the media is cleared. A former host keeps playing its own
 *   file as the room's media (the SDK's own media), and comes back to it through [joinPlayback].
 * - **Out of sight.** While the app can't be seen ([setAppVisible]) and the room's player isn't in picture in picture,
 *   the player is detached, so the room doesn't wait for it; in sight again, it is attached again.
 * - **Stopped here.** A stop of a guest's playback on this device (a call, its audio output gone, its sleep timer:
 *   [stopHere], from the player store, which pauses it) detaches the player too, and the room plays on without it; its
 *   Play or the system's resume ([resumeHere]) attaches it again, and the SDK catches it up with the room. Each reason
 *   to stay detached holds on its own ([away]): one ending while the other holds keeps the player detached.
 * - **Failed.** A file that fails for good once attached detaches the player, and the room plays on without it,
 *   keeping its media; the next file that opens in the player (a retry, [joinPlayback]) is attached again.
 *
 * Main thread only.
 */
internal class WatchPlayback(
    private val room: WatchTogether,
    private val scope: CoroutineScope,
    private val host: WatchPlaybackHost,
    private val mediaBuilder: RoomMediaBuilder,
    private val titles: RoomTitleResolver,
    private val matcher: FileMatcher,
) {

    private val mutableFollowing = MutableStateFlow<RoomPlayback?>(null)

    /** The playback following the room, or null while this device isn't in the room's playback. */
    private var roomPlayback: RoomPlayback?
        get() = mutableFollowing.value
        set(value) {
            mutableFollowing.value = value
        }
    private var follow: Job? = null
    private var attachedTo: RoomPlayback? = null
    private val mutableAttached = MutableStateFlow<RoomFile?>(null)
    private val mutablePickFailed = MutableStateFlow(false)
    private val mutableSearchFailed = MutableStateFlow(false)
    private var probing: RoomFile? = null
    private var opening: RoomFile? = null
    private var guestTitle: RoomTitle? = null
    private var sentMedia: Media? = null

    /** The file [sentMedia] describes. */
    private var sentRef: RoomFile? = null
    private var sharedSerial: Int? = null
    private var lastEpoch: Long? = null
    private var hadMedia = false
    private var wasHost = false
    private var picks = 0

    /** A stream picked by hand in the Streams sheet is opening (its failure shows as [pickFailed]). */
    private var picking = false

    /**
     * The room started over a paused player: its first media, once the room has it, is paused, as the host left it
     * (sharing otherwise starts everyone, Open Watch Party's SPEC.md, section 7.10). [pauseShared] is that media while
     * the pause waits for it.
     */
    private var holdStart = false
    private var pauseShared: Media? = null

    /** The app can't be seen ([setAppVisible]). */
    private var appHidden = false

    /** The room's player shows in picture in picture ([RoomPlayback.pictureInPicture]). */
    private var pictureInPicture = false

    /** Why the room's player stays detached now ([away]). */
    private val mutableAway = MutableStateFlow(emptySet<Away>())

    /** The live file the host was told can't be the room's ([onLive]). */
    private var liveSerial: Int? = null

    /** The file the player is attached to the party with, or null. */
    val attached: StateFlow<RoomFile?> = mutableAttached.asStateFlow()

    /** Why the room's player stays detached, so the room doesn't wait for it, each reason on its own; empty while it may be attached. */
    val away: StateFlow<Set<Away>> = mutableAway.asStateFlow()

    /** The playback following the room ([follows]), or null. */
    val following: StateFlow<RoomPlayback?> = mutableFollowing.asStateFlow()

    /** A file picked by hand didn't open or had no duration. */
    val pickFailed: StateFlow<Boolean> = mutablePickFailed.asStateFlow()

    /**
     * Some addon failed in the last search for the room's current media: a guest that found no file may have one. Until
     * the next search or media.
     */
    val searchFailed: StateFlow<Boolean> = mutableSearchFailed.asStateFlow()

    /** Whether [active] is the playback attached to the room: its play, pause, seek and speed go to the room. */
    fun routes(active: RoomPlayback): Boolean = attachedTo === active && room.state.value.inRoom

    /** Whether [active] follows the room: attached, or a guest's player looking for its file. */
    fun follows(active: RoomPlayback): Boolean = roomPlayback === active && room.state.value.inRoom

    /** Whether [active]'s player paused here alone, out of the room's playback ([stopHere]). */
    fun stoppedHere(active: RoomPlayback): Boolean = roomPlayback === active && Away.STOPPED in mutableAway.value

    /** The room's title as this device plays it, for the Streams sheet. */
    private fun title(): RoomTitle? = guestTitle ?: room.state.value.party.choice?.ref?.title

    // The SDK's resolver

    /** The guest's candidates for [media]: its files in its own order, each opened in the player by its probe. */
    suspend fun resolve(media: Media): List<Candidate<RoomFile>> {
        val roomMedia = roomMedia(media)
        if (roomMedia.metaId.isEmpty()) return emptyList()
        mutableSearchFailed.value = false
        val title = titles.resolve(roomMedia)
        guestTitle = title
        val found = matcher.files(roomMedia, title)
        mutableSearchFailed.value = found.searchFailed
        return found.files.mapIndexed { index, file ->
            val hints = file.choice.stream.behaviorHints
            Candidate(
                id = "c$index",
                ref = file,
                sizeBytes = file.local?.size ?: hints.videoSize,
                oshash = hints.videoHash,
                name = hints.filename,
                probe = { probe(file) },
            )
        }
    }

    /**
     * Opens [file] paused at the room's position as a probe; its duration once open, or null when it fails or the viewer
     * left the room's player (the search goes on without opening it again; [joinPlayback] starts it over).
     */
    private suspend fun probe(file: RoomFile): Long? {
        val active = roomPlayback ?: return null
        probing = file
        try {
            start(active, file)
            return awaitDuration(active, file)
        } finally {
            if (probing == file) probing = null
        }
    }

    private suspend fun awaitDuration(active: RoomPlayback, file: RoomFile): Long? {
        val session = active.session
        val opened = withTimeoutOrNull(PROBE_TIMEOUT) {
            combine(session.openedFile, session.engineState, session.info) { opened, engine, info -> Triple(opened, engine, info) }
                .first { (opened, engine, info) -> info.failure != null || (opened != null && opened.choice == file.choice && engine.loaded && engine.durationSeconds > 0) }
        } ?: return null
        if (opened.third.failure != null) return null
        return (opened.second.durationSeconds * 1000).roundToLong()
    }

    private fun start(active: RoomPlayback, file: RoomFile) {
        val position = room.party?.roomPositionMs()?.let { it / 1000.0 }
        active.session.start(
            PlayRequest(file.title.meta, file.title.videoId, file.choice, startPositionSeconds = position, startPaused = true, local = file.local, purpose = PlayPurpose.PROBE),
        )
    }

    // From the playback controller

    /**
     * [request] as it plays in a room: the host's opens paused (not a trailer or a live stream, which aren't the room's).
     * Null when the room plays it in the current playback instead (a guest's stream picked by hand for the room's
     * video).
     */
    fun beforePlay(request: PlayRequest): PlayRequest? {
        val state = room.state.value
        if (!state.inRoom) return request
        if (state.party.isHost) return if (request.purpose == PlayPurpose.TRAILER || request.meta.preview.isLive) request else request.copy(startPaused = true)
        val title = title()
        val active = roomPlayback ?: host.active
        if (title != null && active != null && request.meta.id == title.meta.id && request.videoId == title.videoId) {
            mutablePickFailed.value = false
            picking = true
            val position = room.party?.roomPositionMs()?.let { it / 1000.0 }
            active.session.start(request.copy(startPositionSeconds = position, startPaused = true, purpose = if (request.purpose == PlayPurpose.TRAILER) PlayPurpose.TRAILER else PlayPurpose.PROBE))
            return null
        }
        return request
    }

    /** A playback started: a host's file becomes the room's. */
    fun onStarted(active: RoomPlayback) {
        val state = room.state.value
        if (state.inRoom && state.party.isHost) followRoom(active)
    }

    /**
     * A playback ended. The host's leaving the player clears the room's media (not when another of its playbacks takes
     * over at once, which shares its file next); a guest's player is detached.
     */
    fun onStopped(active: RoomPlayback, replaced: Boolean) {
        if (roomPlayback !== active) return
        val party = room.party
        val state = room.state.value
        unfollow()
        if (party != null && state.inRoom) {
            party.detachPlayer(active.player)
            if (state.party.isHost && !replaced) call { party.clearMedia() }
        }
    }

    // From the room

    /** A room started with this device as host: the file open, if any, becomes the room's, paused if its player is. */
    fun onRoomStarted() {
        wasHost = true
        holdStart = host.active?.let { it.session.engineState.value.paused } == true
        host.active?.let(::followRoom)
    }

    /** The room ended for this device (the SDK released the player). */
    fun onRoomEnded() {
        unfollow()
        sentMedia = null
        sentRef = null
        lastEpoch = null
        hadMedia = false
        wasHost = false
        holdStart = false
        pauseShared = null
        guestTitle = null
        liveSerial = null
        mutablePickFailed.value = false
        mutableSearchFailed.value = false
    }

    /** The party's state changed. */
    fun onState(state: PartyState<RoomFile>) {
        val room = state.room ?: return
        if (state.isHost != wasHost) {
            wasHost = state.isHost
            if (state.isHost) onPromoted() else sharedSerial = null
        }
        val media = state.media
        val epoch = room.epoch
        if (media == null) {
            if (hadMedia && !state.isHost && (attachedTo != null || roomPlayback != null)) {
                unfollow()
                host.closePlayer()
            }
            hadMedia = false
            lastEpoch = null
            return
        }
        hadMedia = true
        if (media == pauseShared) {
            pauseShared = null
            call { this.room.party?.pause() }
        }
        if (epoch != lastEpoch) {
            lastEpoch = epoch
            mutablePickFailed.value = false
            mutableSearchFailed.value = false
            if (media != sentMedia) {
                guestTitle = null
                pullIn(roomMedia(media))
            }
        }
        syncChoice()
    }

    /** This device became the host: what its player has open stays, or becomes, the room's; with nothing open the media ends. */
    private fun onPromoted() {
        val active = roomPlayback ?: host.active
        val file = active?.session?.openedFile?.value
        if (file == null) {
            call { room.party?.clearMedia() }
            return
        }
        active.session.keepProbe()
        sharedSerial = if (mutableAttached.value == file.ref()) file.serial else null
        followRoom(active)
    }

    /** A guest's player shows the room's new media while the SDK resolves it. */
    private fun pullIn(media: RoomMedia) {
        if (room.state.value.party.isHost) return
        val active = roomPlayback ?: host.active ?: host.openRoomPlayer() ?: return
        followRoom(active)
        if (active.session.openedFile.value?.let { file -> room.state.value.party.choice?.ref == file.ref() } != true) {
            active.session.prepare(media.title, media.episodeLine, artworkUrl = null, resolving = true)
        }
    }

    /** The guest back in the room's playback after leaving the player: its file again, or a new search. */
    fun joinPlayback() {
        val party = room.party ?: return
        val state = room.state.value
        if (!state.inRoom || state.media == null) return
        val active = roomPlayback ?: host.active ?: host.openRoomPlayer() ?: return
        followRoom(active)
        val choice = state.party.choice
        when {
            choice != null -> if (active.session.openedFile.value?.ref() == choice.ref) attach(active, choice.ref) else open(active, choice.ref)
            state.party.mismatch == null -> call { party.resolveAgain() }
        }
    }

    /** The guest plays the closest file found, off as it is. */
    fun playAnyway() = call { room.party?.acceptMismatch() }

    /** The guest doesn't play the closest file found. */
    fun dontPlay() = call { room.party?.declineMismatch() }

    /** The guest chooses its file by hand in the Streams sheet. */
    fun chooseStream() {
        val title = title() ?: return
        host.openStreams(title.meta, title.videoId)
    }

    /** Moves this device's offset from the room by [deltaMs]. */
    fun nudgeOffset(deltaMs: Long) {
        val party = room.party ?: return
        val offset = (room.state.value.party.offsetMs + deltaMs).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        call { party.setOffset(offset) }
    }

    fun resetOffset() = call { room.party?.setOffset(0) }

    /**
     * The app can be seen ([visible]) or not (in the background, minimized, on the Mac its window covered whole). Out of
     * sight, unless in picture in picture, the room's player is detached, so the room doesn't wait for it; in sight again
     * it is attached again, unless it stopped here meanwhile ([setAway]).
     */
    fun setAppVisible(visible: Boolean) {
        appHidden = !visible
        followSight()
    }

    private fun followSight() = setAway(Away.OUT_OF_SIGHT, appHidden && !pictureInPicture)

    /**
     * [active]'s playback stopped on this device for one who doesn't control the room's playback (a call, the audio
     * output gone, the sleep timer; the player store pauses it): the player leaves the room's playback, which plays on
     * without it, until [resumeHere].
     */
    fun stopHere(active: RoomPlayback) {
        if (roomPlayback === active) setAway(Away.STOPPED, true)
    }

    /** Back from [stopHere]: [active]'s player is attached again, unless out of sight, and the SDK catches it up. */
    fun resumeHere(active: RoomPlayback) {
        if (roomPlayback === active) setAway(Away.STOPPED, false)
    }

    /**
     * [reason] holds, or no longer does. The player is detached while any reason holds; once none does it is attached
     * again with the file it has open (the host's shared one, or a guest's choice), or opens the choice.
     */
    private fun setAway(reason: Away, holds: Boolean) {
        val was = mutableAway.value
        val now = if (holds) was + reason else was - reason
        if (now == was) return
        mutableAway.value = now
        if (was.isEmpty() == now.isEmpty()) return
        val party = room.party ?: return
        val active = roomPlayback ?: return
        if (now.isNotEmpty()) {
            if (attachedTo != null) party.detachPlayer(active.player)
            attachedTo = null
            mutableAttached.value = null
            return
        }
        val state = room.state.value
        if (!state.inRoom) return
        if (state.party.isHost) {
            val file = active.session.openedFile.value ?: return
            if (file.serial == sharedSerial) attach(active, file.ref())
        } else {
            syncChoice()
        }
    }

    // The viewer's controls, while attached

    fun play() = call { room.party?.play() }

    fun pause() = call { room.party?.pause() }

    fun seek(positionMs: Long) = call { room.party?.seek(positionMs.coerceAtLeast(0)) }

    fun setRate(rate: Double) = call { room.party?.setRate(rate.coerceIn(MIN_RATE, MAX_RATE)) }

    fun close() {
        onRoomEnded()
    }

    // Following the room in one playback

    private fun followRoom(active: RoomPlayback) {
        if (roomPlayback === active && follow?.isActive == true) return
        unfollow()
        roomPlayback = active
        active.player.onChanged = { room.party?.playerChanged() }
        val session = active.session
        follow = scope.launch {
            launch {
                session.info.map { it.failure != null }.distinctUntilChanged().collect { failed ->
                    if (!failed) return@collect
                    if (picking) {
                        picking = false
                        mutablePickFailed.value = true
                    }
                    // Failed for good once in the room's playback: the room plays on without it.
                    if (attachedTo === active) setAway(Away.FAILED, true)
                }
            }
            launch {
                active.pictureInPicture.collect {
                    pictureInPicture = it
                    followSight()
                }
            }
            combine(session.openedFile, session.info.map { it.live }.distinctUntilChanged(), session.engineState.map { it.durationSeconds }.distinctUntilChanged()) { file, live, duration ->
                Triple(file, live, duration)
            }.collect { (file, live, duration) ->
                if (file == null) return@collect
                if (live) return@collect onLive(active, file)
                if (duration <= 0) return@collect
                onOpened(active, file, (duration * 1000).roundToLong())
            }
        }
    }

    private fun unfollow() {
        follow?.cancel()
        follow = null
        roomPlayback?.player?.onChanged = null
        roomPlayback = null
        attachedTo = null
        mutableAttached.value = null
        opening = null
        probing = null
        picking = false
        // No player follows: what was in picture in picture, or stopped here, no longer counts.
        pictureInPicture = false
        mutableAway.value = if (appHidden) setOf(Away.OUT_OF_SIGHT) else emptySet()
    }

    /**
     * [active] plays a live stream ([file]), which can't be the room's: a host's is detached, the room's media (a file
     * shared before it showed live, or the one before it) is cleared, and the host is told once per file. A guest's own
     * live stream isn't offered to the SDK.
     */
    private fun onLive(active: RoomPlayback, file: OpenedFile) {
        val party = room.party ?: return
        val state = room.state.value
        if (!state.inRoom || !state.party.isHost) return
        if (attachedTo === active) {
            party.detachPlayer(active.player)
            attachedTo = null
            mutableAttached.value = null
        }
        sharedSerial = null
        if (state.party.media != null) call { party.clearMedia() }
        if (liveSerial != file.serial) {
            liveSerial = file.serial
            room.showFailure(WatchFailure.LIVE)
        }
    }

    private suspend fun onOpened(active: RoomPlayback, file: OpenedFile, durationMs: Long) {
        val state = room.state.value
        if (!state.inRoom) return
        // A file open again after a failure (a retry, [joinPlayback]): back in the room's playback.
        setAway(Away.FAILED, false)
        if (state.party.isHost) return share(active, file, durationMs)
        val ref = file.ref()
        if (opening == ref) opening = null
        if (state.party.choice?.ref == ref) return syncChoice()
        if (probing != null || state.media == null) return
        val title = title() ?: return
        if (file.meta.id != title.meta.id || file.videoId != title.videoId) return
        val party = room.party ?: return
        picking = false
        val chosen = try {
            party.choose(Candidate(id = "pick-${++picks}", ref = ref, durationMs = durationMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: PartyException) {
            Log.w(TAG, e) { "choosing the file picked by hand failed" }
            false
        }
        mutablePickFailed.value = !chosen
    }

    private suspend fun share(active: RoomPlayback, file: OpenedFile, durationMs: Long) {
        if (file.serial == sharedSerial) return
        val party = room.party ?: return
        val ref = file.ref()
        if (ref == sentRef && sentMedia != null && room.state.value.party.media == sentMedia) {
            // The room's media already: the same file opened again (a retry after a failure, a reload).
            sharedSerial = file.serial
            attach(active, ref)
            return
        }
        val described = try {
            mediaBuilder.build(file.meta, file.videoId, file.choice, file.resolved, durationMs).toMedia()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, e) { "describing the host's file failed" }
            room.showFailure(WatchFailure.SHARE)
            return
        }
        if (active.session.openedFile.value?.serial != file.serial || !room.state.value.party.isHost) return
        val position = (active.session.engineState.value.positionSeconds * 1000).roundToLong().coerceAtLeast(0)
        try {
            party.setMedia(described, ref, position)
        } catch (e: PartyException) {
            Log.w(TAG, e) { "sharing the host's file failed" }
            room.showFailure(WatchFailure.SHARE)
            return
        }
        sentMedia = described
        sentRef = ref
        sharedSerial = file.serial
        if (holdStart) {
            holdStart = false
            pauseShared = described
        }
        attach(active, ref)
    }

    /**
     * The choice the SDK published: attached when the player has it open, else opened (never the host's own, nor again
     * after it failed here: [joinPlayback] does that).
     */
    private fun syncChoice() {
        val state = room.state.value
        if (mutableAway.value.isNotEmpty()) return
        val active = roomPlayback ?: return
        val choice = state.party.choice ?: return
        val opened = active.session.openedFile.value?.ref()
        if (opened == choice.ref) {
            if (mutableAttached.value != choice.ref || attachedTo !== active) attach(active, choice.ref)
            return
        }
        if (choice.id.isEmpty() || state.party.isHost || probing != null || opening == choice.ref) return
        if (active.session.info.value.failure != null) return
        open(active, choice.ref)
    }

    private fun open(active: RoomPlayback, file: RoomFile) {
        opening = file
        start(active, file)
    }

    private fun attach(active: RoomPlayback, ref: RoomFile) {
        if (mutableAway.value.isNotEmpty()) return
        val party = room.party ?: return
        // The file this device plays now: recorded from here on (stream history, library, trackers).
        active.session.keepProbe()
        party.attachPlayer(active.player, ref)
        attachedTo = active
        mutableAttached.value = ref
    }

    /** A party command that the SDK refuses locally (not in a room, not allowed): logged, the next state corrects it. */
    private inline fun call(block: () -> Unit) {
        try {
            block()
        } catch (e: PartyException) {
            Log.w(TAG, e) { "the party refused a command" }
        }
    }

    private fun OpenedFile.ref(): RoomFile = RoomFile(RoomTitle(meta, videoId), choice, local)

    /** Why the room's player stays detached from the room ([away]). */
    enum class Away {
        /** The app can't be seen, and the player isn't in picture in picture ([setAppVisible]). */
        OUT_OF_SIGHT,

        /** The playback stopped on this device for one who doesn't control the room's playback ([stopHere]). */
        STOPPED,

        /**
         * The file failed for good while in the room's playback, which would otherwise hold everyone for it at each pause,
         * seek or play; the room's media stays, and the next file that opens in the player is attached again.
         */
        FAILED,
    }

    private companion object {
        const val TAG = "watch-playback"

        /** How long one candidate may take to open before it counts as failed (the SDK's budget bounds the rest). */
        val PROBE_TIMEOUT: Duration = 30.seconds

        const val MAX_OFFSET_MS = 604_800_000L
        const val MIN_RATE = 0.5
        const val MAX_RATE = 2.0
    }
}
