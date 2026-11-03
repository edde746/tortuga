package com.edde746.tortuga.trackers.client

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.trackers.AnimeIds
import com.edde746.tortuga.trackers.Rating
import com.edde746.tortuga.trackers.RatingKind
import com.edde746.tortuga.trackers.RatingTarget
import com.edde746.tortuga.trackers.SeriesProgress
import com.edde746.tortuga.trackers.TrackerContext
import com.edde746.tortuga.trackers.TrackerException
import kotlin.time.Instant

/** An anime list entry as the next progress write needs it. */
internal data class AnimeListSnapshot(
    /** The entry's episode total, `null` when unknown (still airing). */
    val episodeCount: Int?,
    /** A rewatch is in progress (MyAnimeList `is_rewatching`, AniList `REPEATING`). */
    val rewatching: Boolean,
    val completed: Boolean,
    /** Finished rewatches so far. */
    val rewatchCount: Int,
    /** Episodes watched on the entry. */
    val progress: Int,
)

/**
 * The write side MyAnimeList and AniList share: one progress counter per
 * list entry, written as an absolute value once playback crosses the watched threshold.
 *
 * Progress only moves forward: an earlier episode replayed or marked leaves an entry that is further along as it is,
 * a rewatch in progress included. A completed entry is the exception: new progress on it starts a rewatch, as both
 * services model one (MyAnimeList: `completed` with `is_rewatching`; AniList: `REPEATING`). Rewatch state is kept,
 * never stomped back to watching: an entry the user set to rewatching stays a rewatch until it completes. Completing
 * a rewatch writes the bumped count as an absolute value from the snapshot, so a replayed completion writes the same
 * count instead of adding another.
 *
 * Every write reads the entry first, never a copy kept from an earlier one: another device or the service's own site
 * may have moved it since. A read that fails fails the write, so the coordinator queues it and waits out a rate limit:
 * written blind, a watched write would turn the rewatch of a completed entry into watching, and an unwatch would be
 * lost. Only an entry the service doesn't know reads as none ([loadSnapshot]'s `null`).
 */
internal abstract class AnimeListTracker : SeriesProgress, Rating {
    override val needsAnimeIds: Boolean get() = true

    /** The service's id for the entry, from the anime ids. */
    protected abstract fun animeId(anime: AnimeIds?): Int?

    /** The entry, `null` when the service doesn't know [animeId]; any other failure throws. */
    protected abstract suspend fun loadSnapshot(animeId: Int): AnimeListSnapshot?

    /**
     * Writes [progress] to the entry. [rewatching]: the write is an in-progress rewatch, not a first watch.
     * [rewatchCount]: the absolute finished-rewatch count, set only when this write completes a rewatch.
     */
    protected abstract suspend fun saveProgress(animeId: Int, progress: Int, completed: Boolean, rewatching: Boolean, rewatchCount: Int?)

    /** Sets the entry's score on the app's 1–10 scale; 0 clears it. */
    protected abstract suspend fun saveScore(animeId: Int, score: Int)

    /** The entry's score on the app's 1–10 scale, `null` for none. */
    protected abstract suspend fun loadScore(animeId: Int): Int?

    override fun seriesEntryId(context: TrackerContext): String? = animeId(context.anime)?.toString()

    /** A movie entry is one unit; an episode claims the entry's progress, else its episode number on the entry. */
    override fun seriesProgress(context: TrackerContext): Int? =
        if (context.isMovie) 1 else context.animeProgress ?: context.animeEpisode

    /**
     * [watchedAt] is dropped: an entry keeps a counter, not dated plays. Every claim here is in the entry's numbering,
     * so the entry's total always caps it.
     */
    override suspend fun markWatched(context: TrackerContext, watchedAt: Instant?) {
        val id = animeId(context.anime) ?: return
        val progress = seriesProgress(context)?.takeIf { it > 0 } ?: return
        val snapshot = loadSnapshot(id)
        val total = if (context.isMovie) null else snapshot?.episodeCount
        val watched = if (total != null && progress > total) total else progress
        if (snapshot != null && (!snapshot.completed || snapshot.rewatching) && watched < snapshot.progress) {
            Log.d(TAG) { "${service.id}: list entry $id stays at ${snapshot.progress}, ahead of $watched" }
            return
        }
        val completed = context.isMovie || (total != null && progress >= total)
        val rewatching = !completed && snapshot != null && (snapshot.rewatching || snapshot.completed)
        val rewatchCount = if (completed && snapshot?.rewatching == true) snapshot.rewatchCount + 1 else null
        saveProgress(id, watched, completed, rewatching, rewatchCount)
    }

    /**
     * Unwatching is a progress write, never a removal: the entry's dates, score and rewatch history can't be
     * restored. The entry goes back to just before the first episode unmarked ([TrackerContext.animeEpisode]; a movie
     * entry to zero), keeping the progress before it and its rewatch flag: an entry spanning several seasons keeps the
     * seasons before the one unmarked. An entry not that far along stays as it is, and so does a completed one (new
     * progress on it becomes a rewatch). Without that episode, or with no entry on the service, nothing is written: a
     * blind write is the one that could destroy state.
     */
    override suspend fun markUnwatched(context: TrackerContext) {
        val id = animeId(context.anime) ?: return
        val from = if (context.isMovie) 1 else context.animeEpisode?.takeIf { it > 0 } ?: return
        val snapshot = loadSnapshot(id) ?: return
        if (snapshot.progress < from) return
        if (snapshot.completed && !snapshot.rewatching) return
        saveProgress(id, from - 1, completed = false, rewatching = snapshot.rewatching, rewatchCount = null)
    }

    override suspend fun rating(target: RatingTarget): Int? = loadScore(ratingId(target))

    override suspend fun rate(target: RatingTarget, score: Int) = saveScore(ratingId(target), score.coerceIn(1, 10))

    override suspend fun clearRating(target: RatingTarget) = saveScore(ratingId(target), 0)

    /** A list entry is a whole movie, show or season; an episode has no score of its own. */
    private fun ratingId(target: RatingTarget): Int {
        if (target.kind == RatingKind.EPISODE) throw TrackerException.Unsupported(service, "episode rating")
        return animeId(target.anime) ?: throw TrackerException.Unsupported(service, "rating without an anime id")
    }

    private companion object {
        const val TAG = "trackers"
    }
}
