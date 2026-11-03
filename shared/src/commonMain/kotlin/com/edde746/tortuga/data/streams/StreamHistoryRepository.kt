package com.edde746.tortuga.data.streams

import com.edde746.tortuga.data.Db
import com.edde746.tortuga.data.db.Stream_history
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.Stream

/**
 * The stream last played for one video. [stream] (the addon's stream object) and [transportUrl] can embed API keys,
 * so never log them.
 */
data class StreamHistoryEntry(
    val metaId: String,
    val videoId: String,
    /** Null when the stored stream no longer decodes (logged as it is read): nothing to play again or match. */
    val stream: Stream?,
    /** The addon that returned the stream. */
    val transportUrl: String,
    /** Epoch milliseconds. */
    val updatedAt: Long,
)

/**
 * The last stream per (meta, video), so resume and binge reuse the same source. Synced per profile.
 */
class StreamHistoryRepository internal constructor(private val db: Db) {
    suspend fun lastStream(profileId: String, metaId: String, videoId: String): StreamHistoryEntry? =
        db.read { streamHistoryQueries.selectLive(profileId, metaId, videoId).executeAsOneOrNull()?.toModel() }

    /** The stream played most recently for any video of [metaId]: auto-play's last stream for an episode not played yet. */
    suspend fun latestForMeta(profileId: String, metaId: String): StreamHistoryEntry? =
        db.read { streamHistoryQueries.selectLatestForMeta(profileId, metaId).executeAsOneOrNull()?.toModel() }

    /** Remembers [streamJson] from the addon at [transportUrl] as the stream of this video. */
    suspend fun record(profileId: String, metaId: String, videoId: String, streamJson: String, transportUrl: String) {
        db.write {
            val stored = q.streamHistoryQueries.select(profileId, metaId, videoId).executeAsOneOrNull()
            q.streamHistoryQueries.upsert(
                Stream_history(
                    profile_id = profileId,
                    meta_id = metaId,
                    video_id = videoId,
                    stream_json = streamJson,
                    transport_url = transportUrl,
                    updated_at = now(),
                    record_id = stored?.record_id ?: RecordKey.StreamHistory(profileId, metaId, videoId).recordId(),
                    hlc = tick().toString(),
                    server_version = stored?.server_version,
                    dirty = true,
                    deleted = false,
                ),
            )
        }
    }

    /** Forgets the video's stream on every device, e.g. after it stopped playing for good. */
    suspend fun forget(profileId: String, metaId: String, videoId: String) {
        db.write {
            val stored = q.streamHistoryQueries.selectLive(profileId, metaId, videoId).executeAsOneOrNull() ?: return@write
            q.streamHistoryQueries.upsert(stored.copy(hlc = tick().toString(), dirty = true, deleted = true))
        }
    }
}

private fun Stream_history.toModel() = StreamHistoryEntry(meta_id, video_id, streamOf(), transport_url, updated_at)

/** The stored stream, decoded once here; one that doesn't decode is logged (the redacted error, never the JSON: it holds keys). */
private fun Stream_history.streamOf(): Stream? = try {
    AddonJson.decodeFromString(Stream.serializer(), stream_json)
} catch (e: IllegalArgumentException) {
    Log.w("streams", e) { "the stream history of $meta_id/$video_id doesn't decode" }
    null
}
