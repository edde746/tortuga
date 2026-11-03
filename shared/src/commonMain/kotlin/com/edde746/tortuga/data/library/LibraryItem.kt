package com.edde746.tortuga.data.library

import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.playback.session.isEpisode
import com.edde746.tortuga.playback.session.nextUnwatched
import kotlin.time.Instant

/**
 * A library item with its watch state: Stremio's `libraryItem` (stremio-core `types/library/library_item.rs`).
 * Times and durations are milliseconds, instants epoch milliseconds.
 */
data class LibraryItem(
    /** Stremio's `_id`: the meta id. */
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    /** `poster`, `square` or `landscape`. */
    val posterShape: String,
    /** Not in the user's library (never added, or removed). The item stays for its watch state. */
    val removed: Boolean,
    /** Created by playback rather than by adding it; with [removed], shown in Continue Watching only. */
    val temp: Boolean,
    /** `_ctime`. */
    val ctime: Long?,
    /** `_mtime`: the last change, which orders the library and Continue Watching. */
    val mtime: Long,
    val state: LibraryItemState,
) {
    /** Stremio's `LibraryItem::is_in_continue_watching`, without live channels. */
    val isInContinueWatching: Boolean
        get() = type != "other" && type != "tv" && (!removed || temp) && state.timeOffset > 0

    /** Watched at least once (Stremio's `LibraryItem::watched`). */
    val isWatched: Boolean get() = state.timesWatched > 0

    /** Position as a fraction of the duration, 0 when either is unknown. */
    val progress: Double
        get() = if (state.timeOffset > 0 && state.duration > 0) state.timeOffset.toDouble() / state.duration else 0.0
}

/** Stremio's `libraryItem.state`. */
data class LibraryItemState(
    val lastWatched: Long?,
    /** Time actually played of the current video (seeking doesn't count). */
    val timeWatched: Long,
    /** Resume position in the current video. */
    val timeOffset: Long,
    /** Time played across every video. */
    val overallTimeWatched: Long,
    /** Times the item (a movie) or any of its videos (episodes) crossed the watched threshold or was marked. */
    val timesWatched: Long,
    /** 1 once the current video crossed the watched threshold, else 0. */
    val flaggedWatched: Long,
    val duration: Long,
    /** The video last played: an episode id, or the meta's own id for a movie. */
    val videoId: String?,
    /** Stremio's `state.watched`: the per-video watched marks in Stremio's own format (read with [WatchedBitField]). */
    val watchedVideos: String?,
    /** Notifications for new videos are off. */
    val noNotif: Boolean,
)

/** A title's place in the library as a catalog card shows it ([LibraryRepository.observeMarks]). */
data class TitleMark(
    /** In the user's library (added, not removed). */
    val inLibrary: Boolean,
    /** Watched at least once ([LibraryItem.isWatched]). */
    val watched: Boolean,
)

/**
 * Where the item rests, the library's one rule for its pointer: never on a watched episode with a
 * position. An episode pointer whose video [meta]'s marks have watched moves past the watched videos to the next one out
 * by [now], at its start (Up next, as stremio-core's `advance_to_video` leaves it), or with none out the position
 * clears and the series leaves Continue Watching until its next episode is out
 * ([com.edde746.tortuga.presentation.calendar.newEpisodesUpNext]). A movie keeps its position (resumable until its
 * credits), and a pointer without a position stays as it is.
 *
 * Every library write that knows the meta ends with it ([LibraryRepository]), Continue Watching applies it to what
 * writers without the meta stored ([LibraryRepository.settleContinueWatching]), and Detail reads through it.
 */
internal fun LibraryItemState.settled(meta: MetaItem, now: Instant): LibraryItemState {
    val current = videoId ?: return this
    if (timeOffset <= 0 || !meta.isEpisode(current)) return this
    val marks = WatchedBitField.of(watchedVideos, meta)
    if (!marks.isWatched(current)) return this
    val next = meta.nextUnwatched(current, marks, now) ?: return copy(timeOffset = 0)
    return copy(videoId = next.id, timeWatched = 0, flaggedWatched = 0, timeOffset = 1)
}

/** What the library needs from a meta: enough to list the item before its meta is fetched again. */
data class MetaSnapshot(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val posterShape: String,
)

/** The library's copy of [MetaItem]'s fields. */
fun MetaItem.librarySnapshot() = MetaSnapshot(id, type, name, preview.poster, preview.posterShape.libraryName)

/** A poster shape as the library stores it ([MetaSnapshot.posterShape]). */
internal val PosterShape.libraryName: String
    get() = when (this) {
        PosterShape.POSTER -> "poster"
        PosterShape.SQUARE -> "square"
        PosterShape.LANDSCAPE -> "landscape"
    }

/** The poster shape of the library's [libraryName] ([LibraryItem.posterShape]); anything else is a poster. */
internal fun posterShapeOf(libraryName: String): PosterShape =
    PosterShape.entries.firstOrNull { it.libraryName == libraryName } ?: PosterShape.POSTER

/** Result of [LibraryRepository.recordProgress]. */
data class ProgressUpdate(
    val item: LibraryItem,
    /** This update is the first to count the current video as watched, and marked it (unless its field can't be read). */
    val crossedWatchedThreshold: Boolean,
)
