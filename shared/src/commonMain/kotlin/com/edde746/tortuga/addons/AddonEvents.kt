package com.edde746.tortuga.addons

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.Resources
import com.edde746.tortuga.playback.session.PlaybackObserver
import com.edde746.tortuga.playback.session.PlaybackPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

/** A `player` event's action (stremio-core `PlayerEventAction`). */
internal enum class PlayerEventAction(val value: String) { START("start"), PAUSE("pause"), STOP("stop") }

/** A `library` event's action (stremio-core `LibraryEventAction`). */
internal enum class LibraryEventAction(val value: String) {
    LIBRARY_ADD("libraryAdd"),
    LIBRARY_REMOVE("libraryRemove"),
    WATCHED("watched"),
    UNWATCHED("unwatched"),
    ;

    companion object {
        fun of(watched: Boolean) = if (watched) WATCHED else UNWATCHED
    }
}

/** At most this many video ids go in one `library` event; more go in further events (stremio-core's constant). */
internal const val LIBRARY_EVENT_VIDEOS_COUNT = 100

/** `/player/{type}/{videoId}/action={action}&currentTime={ms}&duration={ms}.json`. */
internal fun playerEventPath(type: String, videoId: String, action: PlayerEventAction, timeMillis: Long, durationMillis: Long) =
    ResourcePath(
        Resources.PLAYER,
        type,
        videoId,
        listOf(ExtraValue("action", action.value), ExtraValue("currentTime", "$timeMillis"), ExtraValue("duration", "$durationMillis")),
    )

/** `/library/{type}/{id}/action={action}[&videoId={ids}].json`; [videoIds] joined by commas. */
internal fun libraryEventPath(type: String, id: String, action: LibraryEventAction, videoIds: String? = null) =
    ResourcePath(Resources.LIBRARY, type, id, listOfNotNull(ExtraValue("action", action.value), videoIds?.let { ExtraValue("videoId", it) }))

/** The events of a mark that changed [videoIds] of the title: [LIBRARY_EVENT_VIDEOS_COUNT] ids at most in each. */
internal fun videosWatchedEventPaths(type: String, id: String, videoIds: List<String>, watched: Boolean): List<ResourcePath> =
    videoIds.chunked(LIBRARY_EVENT_VIDEOS_COUNT).map { libraryEventPath(type, id, LibraryEventAction.of(watched), it.joinToString(",")) }

/**
 * The watch-activity events Stremio sends addons, ported from stremio-core (`src/models/common/addon_events.rs` and
 * its callers in `models/player.rs`, `models/ctx/update_library.rs` and `models/meta_details.rs`, as of 22 Oct 2026).
 * The `player` and `library` resources are not in the public addon SDK's documentation, which lists only catalog,
 * meta, stream, subtitles and addon_catalog: stremio-core alone defines them, and Stremio's clients send them.
 *
 * - `player`: `/player/{type}/{videoId}/action=start|pause|stop&currentTime={ms}&duration={ms}.json`. `start` when the
 *   video plays (opened playing, or resumed) and after a seek while it plays; `pause` when it pauses and after a seek
 *   while paused; `stop` once, when it is left or plays to its end ([player]).
 * - `library`: `/library/{type}/{id}/action=libraryAdd|libraryRemove|watched|unwatched[&videoId={id},{id}…].json`.
 *   `libraryAdd` and `libraryRemove` when the title enters or leaves the library; `watched` and `unwatched` for a
 *   mark of the whole title, or, with the ids of the videos whose mark changed, of some of its videos, at most
 *   [LIBRARY_EVENT_VIDEOS_COUNT] ids a request. Playback that comes to count a video as watched sends none: an addon
 *   that tracks watching reads that from the `player` events.
 *
 * An addon gets an event only when its manifest declares that resource for the event's type and id
 * ([com.edde746.tortuga.model.Manifest.isResourceSupported]), and only while it is enabled and the user agreed that
 * it receives their watch activity ([addons]: [AddonRegistry.watchActivityAddons]). Only what the user does on this
 * device makes events: playback here ([player]) and their own library actions (the other functions, which callers
 * use only for what the user did); a sync pull, a file import or another device's change make none.
 *
 * Fire and forget: the events go out in the order they came, nothing waits for one, and a failed one is logged. Each
 * addon gets its events one after the other, the next once the last was answered (a slow `start` can't land after
 * its `stop`), while one addon's slow answers hold up no other's.
 */
internal class AddonEvents(
    private val addons: suspend () -> List<Descriptor>,
    private val transport: AddonTransport,
    private val scope: CoroutineScope,
) {
    private val queue = Channel<List<ResourcePath>>(Channel.UNLIMITED)

    /** Each addon's events on their way, by transport URL; read and written only by the [queue]'s consumer. */
    private val lanes = mutableMapOf<String, SendChannel<PlannedRequest>>()

    init {
        scope.launch {
            for (paths in queue) {
                val targets = try {
                    addons()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, e) { "reading the addons for ${paths.size} event(s) failed; they aren't sent" }
                    continue
                }
                for (path in paths) {
                    for (planned in AggrRequest.AllOfResource(path).plan(targets)) {
                        lanes.getOrPut(planned.addon.transportUrl) { lane() }.trySend(planned)
                    }
                }
            }
        }
    }

    /**
     * A new observer of one producer of playbacks (this device's player, a player app's hand-off): one video at a time,
     * as [PlaybackObserver] tells it, so each producer needs its own.
     */
    fun player(): PlaybackObserver = object : PlaybackObserver {
        private var video: Pair<String, String>? = null
        private var playing = false
        private var ended = false

        override fun onVideoStarted(meta: MetaItem, videoId: String, at: PlaybackPosition, playing: Boolean) {
            video = meta.type to videoId
            ended = false
            this.playing = playing
            if (playing) send(PlayerEventAction.START, at)
        }

        override fun onPlayingChanged(playing: Boolean, at: PlaybackPosition) {
            // A new file of the same video (a stream switch, a reload) repeats the state: stremio-core sends on a change.
            if (playing == this.playing) return
            this.playing = playing
            // Playing to the end stops the video; the pause that comes with it isn't one the user made.
            if (playing || !ended) send(if (playing) PlayerEventAction.START else PlayerEventAction.PAUSE, at)
        }

        override fun onPosition(at: PlaybackPosition) {}

        override fun onSeeked(at: PlaybackPosition) {
            send(if (playing) PlayerEventAction.START else PlayerEventAction.PAUSE, at)
        }

        override fun onEnded(at: PlaybackPosition) {
            if (ended) return
            ended = true
            send(PlayerEventAction.STOP, at)
        }

        override fun onVideoStopped(at: PlaybackPosition) {
            if (!ended) send(PlayerEventAction.STOP, at)
            video = null
        }

        private fun send(action: PlayerEventAction, at: PlaybackPosition) {
            val (type, videoId) = video ?: return
            send(listOf(playerEventPath(type, videoId, action, at.positionMillis, at.durationMillis)))
        }
    }

    /** The user added the title to the library (it wasn't in it). */
    fun libraryAdded(type: String, id: String) = send(listOf(libraryEventPath(type, id, LibraryEventAction.LIBRARY_ADD)))

    /** The user took the title out of the library (it was in it). */
    fun libraryRemoved(type: String, id: String) = send(listOf(libraryEventPath(type, id, LibraryEventAction.LIBRARY_REMOVE)))

    /** The user marked the whole title watched or unwatched. */
    fun titleMarked(type: String, id: String, watched: Boolean) = send(listOf(libraryEventPath(type, id, LibraryEventAction.of(watched))))

    /** The user's mark of some of the title's videos changed [videoIds]' marks (none: nothing is sent). */
    fun videosMarked(type: String, id: String, videoIds: List<String>, watched: Boolean) = send(videosWatchedEventPaths(type, id, videoIds, watched))

    private fun send(paths: List<ResourcePath>) {
        if (paths.isNotEmpty()) queue.trySend(paths)
    }

    /** A new addon's events, delivered in [scope] one at a time as they come. */
    private fun lane(): SendChannel<PlannedRequest> = Channel<PlannedRequest>(Channel.UNLIMITED).also { lane ->
        scope.launch { for (planned in lane) deliver(planned) }
    }

    private suspend fun deliver(planned: PlannedRequest) {
        try {
            transport.send(planned.request)
        } catch (e: AddonException) {
            Log.w(TAG) { "${planned.addon.manifest.name} didn't take ${planned.request.path.resource} event ${planned.request.path.extra.firstOrNull()?.value}: ${e.error}" }
        }
    }

    private companion object {
        const val TAG = "addon-events"
    }
}
