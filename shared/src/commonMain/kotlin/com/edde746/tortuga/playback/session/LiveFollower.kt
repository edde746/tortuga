package com.edde746.tortuga.playback.session

import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.resolve.LiveJoin
import com.edde746.tortuga.playback.resolve.LivePlaylist
import com.edde746.tortuga.playback.resolve.ResolvedStream
import com.edde746.tortuga.playback.resolve.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The live side of one file a [PlaybackSession] opened ([stream]): whether the engine's reports say it is a live
 * stream ([detect]), and its rewind window ([com.edde746.tortuga.playback.session.SessionInfo.liveWindow], shown
 * through [publish]): its HLS playlist read with the stream's headers once the file plays live, and again every target
 * duration until it ends ([followWindow]); where each open of it joined the playlist; and where a move within the
 * window lands ([seek]: a seek in what the engine holds, or the stream opened again, [reopen]). The session keeps the
 * engine's loads, the tracks and what is recorded; [close] stops it with the file.
 */
internal class LiveFollower(
    private val resolver: StreamResolver,
    private val stream: ResolvedStream,
    private val userAgent: String,
    private val scope: CoroutineScope,
    /** The clock a window's edge moves on with ([LiveWindow.joinedAt]). */
    private val timeSource: TimeSource,
    /** Shows the window, or null for none. */
    private val publish: (LiveWindow?) -> Unit,
) {
    /** The engine's reports say the file is a live stream ([detect]). */
    var detected = false
        private set

    /**
     * Since when the engine has reported that it can't seek in the file ([detect]), with the duration and the position
     * it reported then (the duration as it first came, when there was none then); null while it reports that it can.
     */
    private var unseekableSince: TimeMark? = null
    private var unseekableDuration = 0.0
    private var unseekablePosition = 0.0

    /** When the file was last asked to open (its load, or a [reopen]): the window's edge moves on from then. */
    private var openedAt: TimeMark = timeSource.markNow()

    /**
     * The playlist's latest read and the job that reads it ([followWindow]); where the open joined it
     * ([LivePlaylist.join]).
     */
    private var playlist: LivePlaylist? = null
    private var windowJob: Job? = null
    private var join: LiveJoin? = null

    /**
     * The engine's position at the open's first frame ([followWindow]); null until then. A reopen takes 0 (a new file's
     * start) until its first frame says ([joinPending]).
     */
    private var joinedPosition: Double? = null
    private var joinPending = false

    private var closed = false

    /**
     * Whether [state] makes the file a live stream now, latched from then on ([detected]): while the engine can't seek
     * in it, its duration grows [GROWTH_SECONDS] past the first one reported (mpv's duration of a live HLS, TS or radio
     * stream is what it has downloaded so far), or, without a duration, its position runs on that far while as much
     * time passes. What can't be seeked in and isn't live stays a video: a video from a server without Range support
     * (its duration stays put), and a still image (mpv 0.41 shows a PNG or a JPEG with no duration and its position held
     * at its first frame's until its display ends; an animated GIF has a duration that stays put).
     *
     * Only what the engine reported of this file counts: mpv hands its client queued events (`file-loaded`,
     * `playback-restart`) before property changes, so the report of the first frame can hold no duration yet and the
     * last file's `seekable` (the engine's default, false, for its first file), and the file's own values follow it
     * in any order. So a duration that comes where there was none is the file's length, not growth; a report that it
     * can seek in the file starts the count over; and without a duration the position's second counts only once a
     * second has passed, as a position that jumps on at once is a late report catching up.
     */
    fun detect(state: EngineState): Boolean {
        if (detected) return false
        if (state.seekable) {
            unseekableSince = null
            return false
        }
        val since = unseekableSince
        if (since == null || unseekableDuration <= 0 && state.durationSeconds > 0) {
            unseekableDuration = state.durationSeconds
            unseekablePosition = state.positionSeconds
            unseekableSince = timeSource.markNow()
            return false
        }
        val grew = state.durationSeconds >= unseekableDuration + GROWTH_SECONDS
        val ran = unseekableDuration <= 0 && state.durationSeconds <= 0 &&
            state.positionSeconds >= unseekablePosition + GROWTH_SECONDS && since.elapsedNow() >= GROWTH_SECONDS.seconds
        if (!grew && !ran) return false
        detected = true
        return true
    }

    /**
     * The window of a file that plays live: its playlist read from the first call on ([readWindow]), and each open
     * placed in it at its first frame.
     */
    fun followWindow(state: EngineState) {
        if (windowJob == null) windowJob = readWindow()
        if ((joinedPosition == null || joinPending) && state.playbackRestarts > 0) {
            joinedPosition = state.positionSeconds
            joinPending = false
            publishWindow()
        }
    }

    /**
     * Where a move to [behindSeconds] behind the edge of [window] goes from where the engine is ([state], its latest
     * report of the open; null while it hasn't reported the latest open); null without a playlist. The edge itself is
     * where an open lands, a few segments back: no closer, as every open joins there.
     */
    fun seek(window: LiveWindow, behindSeconds: Double, state: EngineState?): LiveMove? {
        val playlist = playlist ?: return null
        val target = behindSeconds.coerceIn(playlist.join(0.0).behindSeconds, window.windowSeconds)
        if (state == null || !state.loaded) return LiveMove.Reopen(target)
        val ahead = window.behind(state.positionSeconds) - target
        // Ahead within what the engine read, short of its last segment: a seek. In a stream it can't seek in, mpv seeks
        // only up to the last keyframe range it read whole and refuses one past it ("Cannot seek in this stream"), and
        // a segment holds a keyframe. Back within what it holds read: a seek too (mpv 0.41: 10 s and 30 s back in
        // 0.03 s or less); before that, mpv would land at its start, not at the point. Anything else opens the stream
        // again.
        val point = state.positionSeconds + ahead
        val read = if (ahead >= 0) {
            state.cacheEndSeconds?.let { point + window.segmentSeconds <= it } == true
        } else {
            state.cacheStartSeconds?.let { point >= it } == true
        }
        return if (read) LiveMove.Seek(point) else LiveMove.Reopen(target)
    }

    /**
     * The stream opens again [behindSeconds] behind the edge, at the segment of the latest playlist read that holds
     * that point (lavf reads the playlist again as it opens, a segment later at most): returns where it joins, null
     * without a playlist. The window places it at once as a new file's position 0, and again at its first frame.
     */
    fun reopen(behindSeconds: Double): LiveJoin? {
        val join = playlist?.join(behindSeconds) ?: return null
        this.join = join
        joinedPosition = 0.0
        joinPending = true
        openedAt = timeSource.markNow()
        publishWindow()
        return join
    }

    /** The file is left: the playlist is no longer read, and a read under way shows nothing. */
    fun close() {
        closed = true
        windowJob?.cancel()
    }

    /**
     * Reads the playlist now and every target duration until it ends. No playlist at the first read (not HLS, or
     * unreachable) is no window; a later read that fails keeps the last one. The first read also places the first
     * open, which lavf started [LivePlaylist.JOIN_SEGMENTS] segments before the end.
     */
    private fun readWindow(): Job = scope.launch {
        val headers = stream.playerHeaders(userAgent)
        while (true) {
            val read = resolver.livePlaylist(stream.url, headers)
            if (closed) return@launch
            val latest = read ?: playlist ?: return@launch
            if (read != null) {
                if (join == null) join = read.join(0.0)
                playlist = read
                publishWindow()
                if (read.ended) return@launch
            }
            delay(latest.targetSeconds.seconds.coerceIn(READ_MIN, READ_MAX))
        }
    }

    /**
     * The window as shown: none while the playlist is short of [LiveWindow.MIN_SECONDS] or ended (an event that ended
     * plays to its end as live without one), nor before the first open's first frame.
     */
    private fun publishWindow() {
        val playlist = playlist
        val join = join
        val joined = joinedPosition
        val window = when {
            playlist == null || playlist.ended || playlist.windowSeconds < LiveWindow.MIN_SECONDS -> null
            join == null || joined == null -> return
            else -> LiveWindow(playlist.windowSeconds, playlist.targetSeconds, join.behindSeconds, joined, openedAt)
        }
        publish(window)
    }

    private companion object {
        /**
         * An unseekable file whose duration grows this much past the first one reported, or without a duration whose
         * position runs on this much in as much time, is a live stream ([detect]).
         */
        const val GROWTH_SECONDS = 1.0

        /** The playlist is read again every target duration, within these. */
        val READ_MIN = 2.seconds
        val READ_MAX = 10.seconds
    }
}

/** Where a move within a live window goes ([LiveFollower.seek]). */
internal sealed interface LiveMove {
    /** A seek in the file, to [seconds]. */
    data class Seek(val seconds: Double) : LiveMove

    /** The stream opened again [behindSeconds] behind the edge ([LiveFollower.reopen]). */
    data class Reopen(val behindSeconds: Double) : LiveMove
}
