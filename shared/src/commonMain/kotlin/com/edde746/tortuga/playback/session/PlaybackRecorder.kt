package com.edde746.tortuga.playback.session

import com.edde746.tortuga.data.library.LibraryRepository
import com.edde746.tortuga.data.library.librarySnapshot
import com.edde746.tortuga.data.streams.StreamHistoryRepository
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a playback records, for both players: Tortuga's ([PlaybackSession]) and the player apps'
 * ([com.edde746.tortuga.playback.players.PlayerAppHandoff]). Each of them keeps its own position source and decides
 * whether a video records at all; this holds the rest:
 * - Writes ([write]: the library, the stream history, settings) run one at a time in call order in [scope], its
 *   owner's, and finish after [close]: one that fails is logged and the next one runs; once the last is done, [scope]
 *   is cancelled.
 * - Observers ([observe]; the trackers) hear each video's life ([notifyObservers]); one that throws is
 *   logged and ignored, so an observer can never disturb playback.
 * - Each video's recording ([video]): its library item, its stream, its seeks, its progress with whether it counts as
 *   watched (an episode that comes to count moves the library's pointer on in that write, and records nothing more),
 *   and its leaving.
 *
 * Call everything on the main thread.
 */
internal class PlaybackRecorder(
    private val profileId: String,
    private val library: LibraryRepository,
    private val streamHistory: StreamHistoryRepository,
    private val sync: SyncTrigger,
    /** How often progress is saved while playing ([progressTicker]): [SessionConfig.progressIntervalMillis]. */
    private val progressIntervalMillis: Long,
    private val scope: CoroutineScope,
    /** The log tag of what records through it (`PlaybackSession`, `PlayerAppHandoff`). */
    val tag: String,
) {
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** The observers, in the order they came ([observe]). */
    var observers: List<PlaybackObserver> = emptyList()
        private set

    init {
        scope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(tag, e) { "saving playback state failed" }
                }
            }
            // close() closed the queue and everything in it is written.
            scope.cancel()
        }
    }

    /** Runs [block] after the writes queued before it. */
    fun write(block: suspend () -> Unit) {
        writes.trySend(block)
    }

    /** Tells [observer] about each video from now on. */
    fun observe(observer: PlaybackObserver) {
        observers += observer
    }

    /** Tells each of [to] [event]; one that throws is logged, and the next one still hears it. */
    inline fun notifyObservers(to: List<PlaybackObserver> = observers, event: PlaybackObserver.() -> Unit) {
        for (observer in to) {
            try {
                observer.event()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, e) { "playback observer failed" }
            }
        }
    }

    /** Runs [tick] every [progressIntervalMillis] until the job is cancelled: the progress saved while playing. */
    fun progressTicker(tick: () -> Unit): Job = scope.launch {
        while (true) {
            delay(progressIntervalMillis.milliseconds)
            tick()
        }
    }

    /** Takes no more writes: the ones queued finish, then [scope] ends. */
    fun close() {
        writes.close()
    }

    /** One playback of [videoId] of [meta]: what it records. */
    fun video(meta: MetaItem, videoId: String) = VideoRecording(meta, videoId)

    inner class VideoRecording(private val meta: MetaItem, private val videoId: String) {
        /** It counted as watched in this playback, and that is saved ([countsAsWatched]). */
        private var watchedSaved = false

        /**
         * An episode that counts as watched: the write that counted it moved the library's pointer on
         * ([LibraryRepository.recordProgress]), so its progress and seeks are no longer recorded.
         */
        var movedOn = false
            private set

        /** The library item, created or refreshed from the meta. */
        fun saveItem() = write { library.upsertFromMeta(profileId, meta.librarySnapshot()) }

        /**
         * [choice] becomes the video's last stream ([StreamHistoryRepository]), unless it can't be played (a download
         * playing as its file, the stream it came from unreadable): the history is what plays again.
         */
        fun recordStream(choice: StreamChoice) {
            if (choice.playable) write { streamHistory.record(profileId, meta.id, videoId, choice.streamJson(), choice.addonUrl) }
        }

        /** A seek to [positionMillis] of [durationMillis]; none once it moved on. */
        fun recordSeek(positionMillis: Long, durationMillis: Long) {
            if (movedOn) return
            write { library.recordSeek(profileId, meta.id, videoId, positionMillis, durationMillis) }
        }

        /**
         * Progress at [positionMillis] of [durationMillis] counts the video as watched ([countsAsWatched]) and it didn't
         * yet: saved now, that is the moment it comes to count.
         */
        fun comesToCount(positionMillis: Long, durationMillis: Long, thresholdPercent: Int, creditsStartMillis: Long?): Boolean =
            !watchedSaved && countsAsWatched(positionMillis, durationMillis, thresholdPercent, creditsStartMillis)

        /**
         * Queues its progress at [positionMillis] of [durationMillis], with whether it counts the video as watched (by
         * [thresholdPercent], or the credits from [creditsStartMillis]); with [flush], sync pushes it right after. An
         * episode that comes to count moves the library's pointer on in that write, and nothing more is recorded.
         */
        fun saveProgress(positionMillis: Long, durationMillis: Long, thresholdPercent: Int, creditsStartMillis: Long?, flush: Boolean) {
            if (movedOn) return
            val watched = countsAsWatched(positionMillis, durationMillis, thresholdPercent, creditsStartMillis)
            write { library.saveProgress(profileId, meta, videoId, positionMillis, durationMillis, watched, sync, flush) }
            if (!watched || watchedSaved) return
            watchedSaved = true
            movedOn = meta.isEpisode(videoId)
        }

        /**
         * Leaves the video once its last progress is saved: left past its credits ([pastCredits], never a live video),
         * the library's pointer moves on ([moveOn]: to [next], or a movie back to its start) unless it moved on as it
         * came to count; then sync pushes.
         */
        fun leave(pastCredits: Boolean, next: Video?) {
            if (pastCredits && !movedOn) write { library.moveOn(profileId, meta, next) }
            write { sync.flushNow() }
        }
    }

    companion object {
        /** Observers hear about the position once it moved this much (a seek is a larger jump). */
        const val OBSERVED_POSITION_STEP_MILLIS = 1000L
    }
}
