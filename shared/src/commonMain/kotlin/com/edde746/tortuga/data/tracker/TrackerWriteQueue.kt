package com.edde746.tortuga.data.tracker

import com.edde746.tortuga.data.Db
import com.edde746.tortuga.data.db.Tracker_write_queue
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.trackers.RatingTarget
import com.edde746.tortuga.trackers.TrackerContentType
import com.edde746.tortuga.trackers.TrackerContext
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.WatchlistTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A watched, unwatched, rating or watchlist write waiting for a retry. */
data class QueuedTrackerWrite(
    /** Identifies this queued write for [TrackerWriteQueue.complete] and [TrackerWriteQueue.recordFailure]. */
    val id: Long,
    val service: TrackerService,
    /** The remote thing the write changes; the queue keeps one write per key. */
    val coalesceKey: String,
    /** What the write sends, for the tracker to replay it exactly. */
    val content: QueuedTrackerContent,
    /** For series-progress services: the progress the write claims; `null` for history writes and removals. */
    val progressClaim: Long?,
    /** When the watch happened (a rating: when it was made), epoch milliseconds. */
    val watchedAt: Long,
    /** Failed attempts so far. */
    val attempts: Int,
)

/** What a queued write sends. */
sealed interface QueuedTrackerContent {
    /** A watched write of [context] (ids, season, episode), or with [watched] `false` its removal. */
    data class Mark(val context: TrackerContext, val watched: Boolean) : QueuedTrackerContent

    /** A rating of [rating]'s title: [score], 1–10, or `null` to remove the rating (stored as 0). */
    data class Rate(val rating: QueuedRating, val score: Int?) : QueuedTrackerContent

    /** [watchlist]'s title put on the service's watchlist ([listed]) or taken off it. */
    data class Watchlist(val watchlist: QueuedWatchlist, val listed: Boolean) : QueuedTrackerContent
}

/** A rating as queued: what it is about, and the content type that decides which accounts take it. */
@Serializable
data class QueuedRating(val target: RatingTarget, val contentType: TrackerContentType)

/** A watchlist write as queued: the title, and the content type that decides which accounts take it. */
@Serializable
data class QueuedWatchlist(val target: WatchlistTarget, val contentType: TrackerContentType)

/** Why a write was given up on. It stays in the queue as a failed write, never replayed, for the user to see. */
sealed interface TrackerWriteFailure {
    /** What it was built from no longer reads: a bug of the app's, logged when it was found. */
    data object Unreadable : TrackerWriteFailure

    /**
     * The service refused it at every one of [TrackerWriteQueue.MAX_ATTEMPTS] attempts; [status]: the HTTP status of
     * its last answer, when it gave one.
     */
    data class Refused(val status: Int?) : TrackerWriteFailure
}

/** One service's writes in the queue: [queued] waiting for a retry, [failed] given up on, the latest for [lastFailure]. */
data class TrackerQueueCounts(val queued: Int, val failed: Int, val lastFailure: TrackerWriteFailure?)

/**
 * The persisted retry queue for failed tracker writes, watched and unwatched marks, ratings and watchlist writes.
 * Live scrobbles are never queued. The tracker coordinator drains it on connect, app resume and network restore, in
 * [pending] order.
 *
 * Coalescing is by intent, per profile, service and [QueuedTrackerWrite.coalesceKey]:
 * - a history write ("watched" or "not watched"), a rating or a watchlist write replaces any write queued or failed for
 *   the same key, so a failed watched write never replays over a later un-watch, nor an earlier score over a later one;
 * - a progress claim ("at least episode N") is dropped when a higher claim for the key is queued, so the list never
 *   walks backwards.
 *
 * A write given up on ([TrackerWriteFailure]) stays as a failed write: [observeCounts] counts it until a newer write
 * for its key replaces it, a write that went through covers it ([invalidate]), or its account's writes are dropped.
 * Rows of a service this build doesn't know are left as they are, for a build that knows it.
 */
class TrackerWriteQueue internal constructor(private val db: Db) {
    /** Each of the profile's services' queued and failed writes; services with none are left out. */
    fun observeCounts(profileId: String): Flow<Map<TrackerService, TrackerQueueCounts>> =
        db.observeList(db.database.trackerQueries.selectQueueStates(profileId)).map { rows ->
            rows.groupBy { it.service }.mapNotNull { (id, states) ->
                val service = TrackerService.fromId(id) ?: return@mapNotNull null
                val failed = states.filter { it.failure != null }
                service to TrackerQueueCounts(
                    queued = states.size - failed.size,
                    failed = failed.size,
                    lastFailure = failed.lastOrNull()?.let { failureOf(service, it.failure, it.failure_status) },
                )
            }.toMap()
        }

    /**
     * The profile's queued writes, in the order their intents were expressed. A row whose content no longer decodes
     * (what it was built from changed shape: a bug of ours) is logged and kept as a failed write
     * ([TrackerWriteFailure.Unreadable]) instead of being replayed or dropped.
     */
    suspend fun pending(profileId: String): List<QueuedTrackerWrite> {
        val unreadable = mutableListOf<Long>()
        val writes = db.read { trackerQueries.selectQueued(profileId).executeAsList() }.mapNotNull { row ->
            val service = TrackerService.fromId(row.service) ?: return@mapNotNull null
            try {
                row.toModel(service)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, e) { "${service.id}: queued write ${row.id} unreadable, kept as failed" }
                unreadable += row.id
                null
            }
        }
        if (unreadable.isNotEmpty()) {
            db.write { for (id in unreadable) q.trackerQueries.failQueued(UNREADABLE, null, now(), id) }
        }
        return writes
    }

    /**
     * Queues a failed write as the surviving intent for its key, at the back with no attempts counted, in place of any
     * write queued or failed for the key. Returns `false` when it was dropped: the account it was written through is no
     * longer connected under [connectedAt] (it signed out or reconnected meanwhile, which dropped its queue), or a
     * higher progress claim for the key is queued. [watchedAt]: when the watch happened (a rating: when it was made).
     */
    suspend fun enqueue(
        profileId: String,
        service: TrackerService,
        connectedAt: Long,
        coalesceKey: String,
        content: QueuedTrackerContent,
        progressClaim: Long?,
        watchedAt: Long,
    ): Boolean = db.write {
        val account = q.trackerQueries.selectAccount(profileId, service.id).executeAsOneOrNull()
        if (account?.connected_at != connectedAt) return@write false
        val queued = q.trackerQueries.selectQueuedByKey(profileId, service.id, coalesceKey).executeAsOneOrNull()
        val queuedClaim = queued?.takeIf { it.failure == null }?.progress_claim
        if (progressClaim != null && queuedClaim != null && queuedClaim > progressClaim) return@write false
        q.trackerQueries.deleteQueuedByKey(profileId, service.id, coalesceKey)
        when (content) {
            is QueuedTrackerContent.Mark -> q.trackerQueries.insertQueued(
                profileId, service.id, coalesceKey, content.watched, contentJson.encodeToString(TrackerContext.serializer(), content.context),
                progressClaim, watchedAt, null, false,
            )
            is QueuedTrackerContent.Rate -> q.trackerQueries.insertQueued(
                profileId, service.id, coalesceKey, false, contentJson.encodeToString(QueuedRating.serializer(), content.rating),
                progressClaim, watchedAt, (content.score ?: 0).toLong(), false,
            )
            is QueuedTrackerContent.Watchlist -> q.trackerQueries.insertQueued(
                profileId, service.id, coalesceKey, content.listed, contentJson.encodeToString(QueuedWatchlist.serializer(), content.watchlist),
                progressClaim, watchedAt, null, true,
            )
        }
        true
    }

    /** Whether the write [id] is still queued (a newer write for its key replaces or covers it; a failed one isn't). */
    suspend fun isQueued(id: Long): Boolean = db.read { trackerQueries.selectAttempts(id).executeAsOneOrNull() != null }

    /** The write went through, or no longer applies: drop it. A newer write queued for the key since stays. */
    suspend fun complete(id: Long) {
        db.write { q.trackerQueries.deleteQueued(id) }
    }

    /**
     * Counts a failed attempt (not for transient errors: rate limits, the service or the network being down). After
     * [MAX_ATTEMPTS] the write is given up on and kept as failed ([TrackerWriteFailure.Refused] with [status], the HTTP
     * status the service answered, when it answered one); returns whether it was.
     */
    suspend fun recordFailure(id: Long, status: Int?): Boolean = db.write {
        q.trackerQueries.incrementAttempts(id)
        val attempts = q.trackerQueries.selectAttempts(id).executeAsOneOrNull() ?: return@write false
        if (attempts < MAX_ATTEMPTS) return@write false
        q.trackerQueries.failQueued(REFUSED, status?.toLong(), now(), id)
        true
    }

    /**
     * Drops queued and failed writes for the key that a completed direct write covers: all of them when
     * [appliedProgress] is `null` (a history write or a removal), otherwise claims up to [appliedProgress] and writes
     * that claim nothing.
     */
    suspend fun invalidate(profileId: String, service: TrackerService, coalesceKey: String, appliedProgress: Long?) {
        db.write {
            val queued = q.trackerQueries.selectQueuedByKey(profileId, service.id, coalesceKey).executeAsOneOrNull() ?: return@write
            val claim = queued.progress_claim
            if (appliedProgress == null || claim == null || claim <= appliedProgress) q.trackerQueries.deleteQueued(queued.id)
        }
    }

    companion object {
        /** The most attempts a write gets. */
        const val MAX_ATTEMPTS: Int = 5

        private const val TAG = "trackers"

        /** `tracker_write_queue.failure`'s codes. */
        private const val UNREADABLE = "unreadable"
        private const val REFUSED = "refused"

        /** The content's JSON in `context_json`: fields a later build added are skipped. */
        private val contentJson = Json { ignoreUnknownKeys = true }

        /** @throws IllegalArgumentException when the row's content doesn't decode. */
        private fun Tracker_write_queue.toModel(service: TrackerService) = QueuedTrackerWrite(
            id = id,
            service = service,
            coalesceKey = coalesce_key,
            content = when {
                watchlist -> QueuedTrackerContent.Watchlist(contentJson.decodeFromString(QueuedWatchlist.serializer(), context_json), watched)
                rating != null -> QueuedTrackerContent.Rate(contentJson.decodeFromString(QueuedRating.serializer(), context_json), rating.toInt().takeIf { it > 0 })
                else -> QueuedTrackerContent.Mark(contentJson.decodeFromString(TrackerContext.serializer(), context_json), watched)
            },
            progressClaim = progress_claim,
            watchedAt = watched_at,
            attempts = attempts.toInt(),
        )

        /** A failed row's reason; one this build doesn't know (a later build's) is logged and reads as unreadable. */
        private fun failureOf(service: TrackerService, code: String?, status: Long?): TrackerWriteFailure = when (code) {
            REFUSED -> TrackerWriteFailure.Refused(status?.toInt())
            UNREADABLE -> TrackerWriteFailure.Unreadable
            else -> {
                Log.e(TAG) { "${service.id}: failed write's reason '$code' unknown, shown as unreadable" }
                TrackerWriteFailure.Unreadable
            }
        }
    }
}
