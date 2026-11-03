package com.edde746.tortuga.sync.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One record as the server holds it, in [PullResponse.changes] and [PushResponse.stale].
 *
 * Versions: each account has one monotonic counter. Every accepted write takes the next value as the record's
 * [version], so versions are unique per account, grow in commit order, and double as pull cursors.
 *
 * @property recordId the record's natural key ([SyncProtocol.isValidRecordId]).
 * @property deleted a tombstone. Tombstones still carry a [payload] (the client's deletion marker, with the clock its
 *   last-writer-wins merge needs) and are kept until the account is deleted.
 * @property payload the record as the client wrote it.
 */
@Serializable
data class RecordChange(
    val recordId: String,
    val version: Long,
    val deleted: Boolean,
    val payload: JsonObject,
    val updatedAtMillis: Long,
)

/**
 * `GET` [SyncPaths.SYNC_PULL]`?since=<cursor>&limit=<n>`: records written after `since` (default 0), oldest first,
 * at most `limit` ([SyncProtocol.DEFAULT_PULL_LIMIT] by default, up to [SyncProtocol.MAX_PULL_LIMIT]), and fewer when
 * they are large: a page stops once its records reach about 1 MiB, the first always included. The client stores
 * [cursor] once it has applied [changes], and pulls again while [hasMore].
 *
 * A pull returns the device's own accepted writes too (same version as in [PushResponse.accepted]); skip them.
 *
 * @property cursor the version of the last change returned, or `since` if there were none.
 * @property latestVersion the account's counter. A `since` above it means the server lost writes the device has seen
 *   (it was restored from a backup): pull from 0, then push every local record again with the base versions the pull
 *   returned.
 * @property trackersVersion the account's tracker connections counter (`TrackerConnectionList.version`): it changes
 *   whenever a connection is added, replaced or removed, and not when a token is refreshed. A device lists the
 *   connections again when it differs from the listing it holds; `null` from a server older than the field.
 */
@Serializable
data class PullResponse(
    val changes: List<RecordChange>,
    val cursor: Long,
    val hasMore: Boolean,
    val latestVersion: Long,
    val trackersVersion: Long? = null,
)

/**
 * One write in a [PushRequest].
 *
 * @property recordId the record's natural key ([SyncProtocol.isValidRecordId]).
 * @property baseVersion the [RecordChange.version] this write is based on: the last one the device pulled or had
 *   accepted for this record, or 0 for a record the device believes is new.
 * @property deleted writes a tombstone.
 * @property payload the record, at most [SyncProtocol.MAX_RECORD_BYTES] bytes encoded.
 */
@Serializable
data class RecordWrite(
    val recordId: String,
    val baseVersion: Long,
    val deleted: Boolean = false,
    val payload: JsonObject,
)

/**
 * `POST` [SyncPaths.SYNC_PUSH]: uploads dirty records with optimistic concurrency.
 *
 * Each write is decided on its own, atomically with the rest of the batch:
 * - accepted if [RecordWrite.baseVersion] equals the record's current version (0 for a record the server doesn't
 *   have): it gets the next version, reported in [PushResponse.accepted];
 * - stale otherwise: nothing is written and the current record comes back in [PushResponse.stale]. The client
 *   merges (last writer wins by the hybrid logical clock inside the payload; progress keeps the furthest position)
 *   and pushes the merge with that record's version as the new base.
 *
 * A retried push whose first response was lost comes back stale with the device's own write, which merges to itself.
 * The stale records of one response stop once they reach about 1 MiB, the first always included: a stale write past
 * them is in neither list, nothing was written for it, and the device pushes it again.
 *
 * Fails as a whole with [ApiErrorCode.QUOTA_EXCEEDED] if the accepted writes would exceed
 * [ServerInfo.accountQuotaBytes], [ApiErrorCode.PAYLOAD_TOO_LARGE] for a payload over [SyncProtocol.MAX_RECORD_BYTES],
 * [ApiErrorCode.UNKNOWN_BASE_VERSION] for a nonzero base version of a record the server doesn't have (it lost writes),
 * and [ApiErrorCode.INVALID_REQUEST] for a malformed push: more than [SyncProtocol.MAX_WRITES_PER_PUSH] writes, a
 * repeated or invalid record id, a negative base version, or a tombstone whose payload is over
 * [SyncProtocol.MAX_TOMBSTONE_BYTES].
 */
@Serializable
data class PushRequest(val writes: List<RecordWrite>)

/** Response to [PushRequest]; every write appears in at most one of the two lists (in neither: push it again). */
@Serializable
data class PushResponse(val accepted: List<AcceptedWrite>, val stale: List<RecordChange>)

/** An accepted write and the version it was given. */
@Serializable
data class AcceptedWrite(val recordId: String, val version: Long)
