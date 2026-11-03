package com.edde746.tortuga.playback.session

import com.edde746.tortuga.data.streams.StreamHistoryEntry
import com.edde746.tortuga.downloads.LocalVideo
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamPlayability
import com.edde746.tortuga.playback.resolve.StreamRequest

/**
 * What the player plays: [videoId] of [meta] (a movie's video id is its meta id) from [stream], with [alternatives]
 * to fall back on, in order, when it fails.
 *
 * @param autoPlay how auto-play picked [stream] ([StreamAutoPicker]); null when the user or the stream history did.
 *   An auto-played stream that fails before it ever opened makes the session ask for another in the stream list
 *   ([SessionEvent.ChooseStream]) instead of only showing the failure.
 * @param startPositionSeconds where the video starts instead of the library's resume point (a watch-together room's
 *   position); null resumes as usual.
 * @param startPaused the file opens paused on its first frame (a watch-together room starts everyone together), and
 *   so does the next episode the session moves on to.
 * @param local the video's completed download: it plays from the device's file with its saved subtitles, without
 *   resolving [stream] (the stream it was downloaded from, which the stream history keeps) or asking the addons for
 *   subtitles; null streams [stream].
 * @param purpose why the file opens: to be watched, measured or shown as a trailer ([PlayPurpose]).
 * @param pickId the hand pick of [stream] in the Streams list ([com.edde746.tortuga.playback.suggest.StreamPicks]):
 *   the session records what came of it; null when nobody picked it by hand.
 */
data class PlayRequest(
    val meta: MetaItem,
    val videoId: String,
    val stream: StreamChoice,
    val alternatives: List<StreamChoice> = emptyList(),
    val autoPlay: AutoPlayPick? = null,
    val startPositionSeconds: Double? = null,
    val startPaused: Boolean = false,
    val local: LocalVideo? = null,
    val purpose: PlayPurpose = PlayPurpose.WATCH,
    val pickId: String? = null,
)

/** Why a [PlayRequest]'s file opens. */
enum class PlayPurpose {
    /** To be watched: the session records it (library item, progress, stream history, tracker events). */
    WATCH,

    /**
     * Only to be measured (a watch-together guest's candidate for the host's file): the session records nothing for it
     * (no library item or progress, so nothing in Continue Watching; no stream history; no tracker events) unless it is
     * kept ([PlaybackSession.keepProbe]).
     */
    PROBE,

    /**
     * The stream is a trailer of the request's meta (of its video, or of the title when it is the meta id): it plays
     * from the start in Tortuga's player and records nothing, as a probe (no library item or progress, no stream
     * history, no tracker events), asks no subtitle addon (its stream's own subtitles still show) and moves on to no
     * other episode.
     */
    TRAILER,
}

/** How auto-play chose a stream: the player's info names it. */
enum class AutoPlayPick {
    /** The first stream that passes the filters, in the list's order. */
    FIRST_MATCH,

    /** The same stream as last time: the one the video last played from, or the title's last binge group's for an episode. */
    LAST_LINK,
}

/**
 * A stream and the addon that returned it. [addonUrl] (the addon's transport URL) and the stream's URL embed API
 * keys, so [toString] names the addon and the stream only.
 */
data class StreamChoice(val stream: Stream, val addonUrl: String, val addonName: String) {
    /** The player can play it: an `http`/`https` URL. Only these reach a session. */
    val playable: Boolean get() = stream.playability is StreamPlayability.Playable

    /** The resolve request for this stream, or null when it isn't [playable]. */
    internal fun request(): StreamRequest? {
        val url = (stream.playability as? StreamPlayability.Playable)?.url ?: return null
        val hints = stream.behaviorHints
        return StreamRequest(
            url = url,
            requestHeaders = hints.proxyHeaders?.request.orEmpty(),
            videoHash = hints.videoHash,
            videoSize = hints.videoSize,
            filename = hints.filename,
        )
    }

    /** The same release from the same addon ([sameRelease]): watch together's download beside its own stream. */
    internal fun sameRelease(other: StreamChoice): Boolean = addonUrl == other.addonUrl && sameRelease(stream, other.stream)

    /** The stream JSON as the stream history stores it. */
    internal fun streamJson(): String = AddonJson.encodeToString(Stream.serializer(), stream)

    override fun toString(): String = "StreamChoice(addon=$addonName, name=${stream.name})"

    companion object {
        /**
         * The stream a history entry remembers; null when it no longer decodes (logged as it was read): nothing to
         * play again or match, as when none was played.
         */
        internal fun fromHistory(entry: StreamHistoryEntry, addonName: String): StreamChoice? =
            entry.stream?.let { StreamChoice(it, entry.transportUrl, addonName) }

        /**
         * Where [remembered] is among [streams], the answer of the addon at [addonUrl] now: the stream as it was stored
         * (an addon may list one link twice, under other names or binge groups), else the first of the same release
         * ([sameRelease]); -1 when the addon doesn't offer it (or is another addon). What plays is the stream found,
         * with the link this answer gives: a saved link is never played as it was saved.
         */
        internal fun indexOfRemembered(streams: List<Stream>, addonUrl: String, remembered: StreamChoice): Int {
            if (addonUrl != remembered.addonUrl) return -1
            val exact = streams.indexOfFirst { it == remembered.stream }
            return if (exact >= 0) exact else streams.indexOfFirst { sameRelease(it, remembered.stream) }
        }

        /**
         * [a] and [b], streams from two answers of one addon, are the same release: the same link, or, from an addon
         * that makes a new link for every answer (a `/playback/…` link), the same file: the same `videoHash` when
         * both carry one, else the same `filename` with no `videoSize` that differs.
         */
        internal fun sameRelease(a: Stream, b: Stream): Boolean {
            if (a.source == b.source) return true
            val hashA = a.behaviorHints.videoHash
            val hashB = b.behaviorHints.videoHash
            if (hashA != null && hashB != null) return hashA == hashB
            val filename = a.behaviorHints.filename ?: return false
            if (filename != b.behaviorHints.filename) return false
            val sizeA = a.behaviorHints.videoSize
            val sizeB = b.behaviorHints.videoSize
            return sizeA == null || sizeB == null || sizeA == sizeB
        }
    }
}
