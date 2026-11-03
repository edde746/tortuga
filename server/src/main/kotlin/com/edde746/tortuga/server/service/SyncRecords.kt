package com.edde746.tortuga.server.service

import com.edde746.tortuga.server.crypto.RecordCipher
import com.edde746.tortuga.server.crypto.RecordCipher.Field
import com.edde746.tortuga.server.db.Database
import com.edde746.tortuga.server.db.instant
import com.edde746.tortuga.server.http.ApiException
import com.edde746.tortuga.server.http.Bearer
import com.edde746.tortuga.server.http.ServerBusy
import com.edde746.tortuga.server.http.badRequest
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.PullResponse
import com.edde746.tortuga.sync.api.PushRequest
import com.edde746.tortuga.sync.api.PushResponse
import com.edde746.tortuga.sync.api.RecordChange
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.FileStore
import java.sql.ResultSet
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Records with per-account versions (the protocol is on [PushRequest] and [PullResponse]), stored
 * sealed by [cipher]: found by their index, with the record id and the payload's compact JSON sealed in the row.
 *
 * Every write locks the account row first, so an account's writes are serialized and versions are handed out in
 * commit order: a pull that has seen version `n` has seen every version below it.
 *
 * The server never reads a payload. A pull or a stale write's answer copies each one into the response as the compact
 * JSON it was stored as ([ChangesJson]), opened outside the transaction, never parsed. An answer holds at most
 * [PAGE_BYTES] of records past its first, and the call reserves what it holds of the server's byte budget before its
 * transaction ends.
 *
 * An accepted tombstone of a profile record ([SyncProtocol.profileIdOf]) deletes that profile's tracker connections in
 * the same transaction; they are revoked at their services once it has committed.
 *
 * A push that accepted a write tells the account's other notice streams its new version once it has committed
 * ([SyncEvents]); the pushing session has its answer.
 *
 * An account's records count toward [quotaBytes] by what they take on disk, about: each record its payload, its sealed
 * id and [ROW_BYTES] for its row. While the file system that holds the database ([databaseDisk]) has less than
 * [minFreeDiskBytes] free, every push is refused (503 `server_busy`), so no account can fill the disk Postgres needs.
 */
class SyncRecords(
    private val db: Database,
    private val sessions: Sessions,
    private val cipher: RecordCipher,
    private val trackers: TrackerConnections,
    private val events: SyncEvents,
    private val clock: Clock,
    private val quotaBytes: Long,
    /** The database's file system ([com.edde746.tortuga.server.ServerConfig.databaseDisk]); null: not watched. */
    private val databaseDisk: FileStore? = null,
    private val minFreeDiskBytes: Long = 0,
) {
    /** Whether the last push found [databaseDisk] short, so the change is logged once. */
    private val diskShort = AtomicBoolean(false)

    /**
     * A [PullResponse] as its JSON: the next [limit] changes after [since], cut once they reach [PAGE_BYTES] (the first
     * always goes); [PullResponse.hasMore] says whether more follow. [reserve] gets what the page holds before the
     * transaction ends, and may refuse it.
     */
    suspend fun pull(bearer: Bearer, since: Long, limit: Int, reserve: (Long) -> Unit): ChangesJson {
        if (since < 0) throw badRequest("since must be >= 0")
        if (limit !in 1..SyncProtocol.MAX_PULL_LIMIT) throw badRequest("limit out of range")
        val (accountId, rows) = sessions.transaction(bearer) { session ->
            // One statement, so one snapshot: the counters and the page agree. Of the next limit + 1 changes, those that
            // start before PAGE_BYTES, and how many there were: more than the page takes means more to pull.
            val rows = query(
                """
                SELECT a.sync_version, a.trackers_version, r.id_index, r.sealed_record_id, r.version, r.deleted,
                       r.sealed_payload, r.updated_at, r.upcoming
                FROM accounts a
                LEFT JOIN LATERAL (
                    SELECT * FROM (
                        SELECT next_changes.*, count(*) OVER () AS upcoming,
                               sum(change_bytes) OVER (ORDER BY version) - change_bytes AS bytes_before
                        FROM (
                            SELECT $CHANGE_COLUMNS, $CHANGE_BYTES AS change_bytes FROM records
                            WHERE account_id = a.id AND version > ? ORDER BY version LIMIT ?
                        ) next_changes
                    ) counted
                    WHERE bytes_before < ?
                ) r ON true
                WHERE a.id = ?
                ORDER BY r.version
                """,
                since, limit + 1, PAGE_BYTES, session.accountId,
            ) { row ->
                PulledRow(
                    row.getLong("sync_version"), row.getLong("trackers_version"),
                    row.getBytes("id_index")?.let { row.toSealed() }, row.getInt("upcoming"),
                )
            }
            reserve(heldBytes(rows.mapNotNull { it.change }))
            session.accountId to rows
        }
        val counters = rows.firstOrNull() ?: throw accountGone()
        val page = rows.mapNotNull { it.change }.take(limit)
        return ChangesJson(page.sumOf { it.size })
            .ascii("{\"changes\":").changes(accountId, page)
            .ascii(",\"cursor\":").number(page.lastOrNull()?.version ?: since)
            .ascii(",\"hasMore\":").ascii((counters.upcoming > page.size).toString())
            .ascii(",\"latestVersion\":").number(counters.syncVersion)
            .ascii(",\"trackersVersion\":").number(counters.trackersVersion)
            .ascii("}")
    }

    /**
     * A [PushResponse] as its JSON for [writes], which the body's reader holds to [SyncProtocol.MAX_WRITES_PER_PUSH]. Its
     * stale records stop once they reach [PAGE_BYTES] (the first always goes): a stale write past them is in neither
     * list, and its device pushes it again. [reserve] gets what the answer holds before the transaction commits, and may
     * refuse it.
     */
    suspend fun push(bearer: Bearer, writes: List<IncomingWrite>, reserve: (Long) -> Unit): ChangesJson {
        val ids = HashSet<String>(writes.size * 2)
        for (write in writes) {
            if (!SyncProtocol.isValidRecordId(write.recordId)) throw badRequest("Invalid record id")
            if (!ids.add(write.recordId)) throw badRequest("Record id repeated in one push")
            if (write.baseVersion < 0) throw badRequest("baseVersion must be >= 0")
            if (write.deleted && write.payload.size > SyncProtocol.MAX_TOMBSTONE_BYTES) {
                throw badRequest("A tombstone's payload holds at most ${SyncProtocol.MAX_TOMBSTONE_BYTES} bytes")
            }
        }
        if (writes.isEmpty()) return sessions.transaction(bearer) { _ -> ChangesJson(EMPTY_PUSH.length).ascii(EMPTY_PUSH) }
        checkDisk()

        val (accountId, outcome) = sessions.transaction(bearer) { session ->
            val accountId = session.accountId
            // Sealed before the account's lock is taken; a write that turns out stale wastes its seals.
            val prepared = writes.map { write ->
                val index = cipher.index(accountId, write.recordId)
                PreparedWrite(
                    request = write,
                    index = index,
                    sealedId = cipher.seal(Field.RECORD_ID, accountId, index, write.recordId.encodeToByteArray()),
                    sealedPayload = cipher.seal(Field.PAYLOAD, accountId, index, write.payload),
                )
            }
            val account = lockAccount(accountId)
            val current = query(
                "SELECT id_index, version, $QUOTA_BYTES AS charged FROM records WHERE account_id = ? AND id_index = ANY(?)",
                accountId, byteaArray(prepared.map { it.index }),
            ) { ByteBuffer.wrap(it.getBytes("id_index")) to Stored(it.getLong("version"), it.getLong("charged")) }.toMap()

            val now = clock.instant()
            var version = account.syncVersion
            var storedBytes = account.storedBytes
            val accepted = ArrayList<Pair<String, Long>>()
            val staleIndexes = ArrayList<ByteArray>()
            val deletedProfiles = LinkedHashSet<String>()
            val upserts = ArrayList<Array<Any?>>()
            for (write in prepared) {
                val existing = current[ByteBuffer.wrap(write.index)]
                val baseVersion = write.request.baseVersion
                if (existing == null && baseVersion != 0L) {
                    throw ApiException(HttpStatusCode.Conflict, ApiErrorCode.UNKNOWN_BASE_VERSION, "baseVersion names a record the server doesn't have")
                }
                if ((existing?.version ?: 0L) != baseVersion) {
                    staleIndexes += write.index
                    continue
                }
                version += 1
                storedBytes += write.request.payload.size + write.sealedId.size + ROW_BYTES - (existing?.charged ?: 0)
                accepted += write.request.recordId to version
                if (write.request.deleted) SyncProtocol.profileIdOf(write.request.recordId)?.let { deletedProfiles += it }
                upserts += arrayOf(
                    accountId, write.index, write.sealedId, version, write.request.deleted, write.sealedPayload,
                    write.request.payload.size, now,
                )
            }
            if (storedBytes > quotaBytes && storedBytes > account.storedBytes) {
                throw ApiException(HttpStatusCode.PayloadTooLarge, ApiErrorCode.QUOTA_EXCEEDED, "Account quota exceeded")
            }
            batch(
                """
                INSERT INTO records (account_id, id_index, sealed_record_id, version, deleted, sealed_payload,
                                     payload_size, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id, id_index) DO UPDATE SET sealed_record_id = excluded.sealed_record_id,
                    version = excluded.version, deleted = excluded.deleted, sealed_payload = excluded.sealed_payload,
                    payload_size = excluded.payload_size, updated_at = excluded.updated_at
                """,
                upserts,
            )
            if (upserts.isNotEmpty()) {
                update("UPDATE accounts SET sync_version = ?, stored_bytes = ? WHERE id = ?", version, storedBytes, accountId)
                val latest = version
                afterCommit { events.changed(accountId, session.sessionId, latest) }
            }
            val stale = if (staleIndexes.isEmpty()) emptyList() else query(
                """
                SELECT $CHANGE_COLUMNS FROM (
                    SELECT $CHANGE_COLUMNS,
                           sum($CHANGE_BYTES) OVER (ORDER BY version) - $CHANGE_BYTES AS bytes_before
                    FROM records WHERE account_id = ? AND id_index = ANY(?)
                ) stale
                WHERE bytes_before < ?
                ORDER BY version
                """,
                accountId, byteaArray(staleIndexes), PAGE_BYTES,
            ) { it.toSealed() }
            reserve(heldBytes(stale))
            accountId to PushOutcome(accepted, stale, trackers.deleteProfiles(this, accountId, deletedProfiles))
        }
        trackers.revoke(accountId, outcome.removedTrackers)
        val json = ChangesJson(outcome.stale.sumOf { it.size } + outcome.accepted.size * 64).ascii("{\"accepted\":[")
        outcome.accepted.forEachIndexed { i, (recordId, version) ->
            if (i > 0) json.ascii(",")
            json.ascii("{\"recordId\":").string(recordId).ascii(",\"version\":").number(version).ascii("}")
        }
        return json.ascii("],\"stale\":").changes(accountId, outcome.stale).ascii("}")
    }

    /**
     * Refuses the push while [databaseDisk] has less than [minFreeDiskBytes] free, and logs when that changes (the
     * free space only: nothing about clients).
     */
    private fun checkDisk() {
        val disk = databaseDisk ?: return
        val free = disk.usableSpace
        val short = free < minFreeDiskBytes
        if (diskShort.getAndSet(short) != short) {
            if (short) {
                log.warn("The database's disk has {} MB free, under the {} MB kept free: pushes are refused", free shr 20, minFreeDiskBytes shr 20)
            } else {
                log.info("The database's disk has {} MB free again: pushes are accepted", free shr 20)
            }
        }
        if (short) throw ServerBusy("The server's disk is nearly full; pushes are refused until it has room", DISK_SHORT_RETRY_AFTER_SECONDS)
    }

    private fun ResultSet.toSealed() = SealedChange(
        index = getBytes("id_index"),
        sealedRecordId = getBytes("sealed_record_id"),
        version = getLong("version"),
        deleted = getBoolean("deleted"),
        sealedPayload = getBytes("sealed_payload"),
        updatedAtMillis = instant("updated_at").toEpochMilli(),
    )

    /**
     * [changes] as a JSON array of [RecordChange], each opened here and its payload copied in as stored. A record that
     * doesn't open with the data key (sealed with a key that was lost and replaced: server/README.md, The data key) is
     * left out and logged; a pull's cursor still moves past it.
     */
    private fun ChangesJson.changes(accountId: UUID, changes: List<SealedChange>): ChangesJson {
        ascii("[")
        var written = 0
        for (change in changes) {
            val recordId = cipher.open(Field.RECORD_ID, accountId, change.index, change.sealedRecordId) ?: continue
            val payload = cipher.open(Field.PAYLOAD, accountId, change.index, change.sealedPayload) ?: continue
            if (written++ > 0) ascii(",")
            ascii("{\"recordId\":").string(recordId.decodeToString())
            ascii(",\"version\":").number(change.version)
            ascii(",\"deleted\":").ascii(change.deleted.toString())
            ascii(",\"payload\":").bytes(payload)
            ascii(",\"updatedAtMillis\":").number(change.updatedAtMillis)
            ascii("}")
        }
        if (written < changes.size) {
            log.atWarn().addKeyValue("accountId", accountId).addKeyValue("records", changes.size - written)
                .log("Records that don't open with the data key were left out of the answer")
        }
        return ascii("]")
    }

    /** A validated write: its index, and its record id and payload sealed. */
    private class PreparedWrite(
        val request: IncomingWrite,
        val index: ByteArray,
        val sealedId: ByteArray,
        val sealedPayload: ByteArray,
    )

    /** A stored record: its version and what it counts toward the quota. */
    private class Stored(val version: Long, val charged: Long)

    /** A record as its row holds it: id and payload still sealed. */
    private class SealedChange(
        val index: ByteArray,
        val sealedRecordId: ByteArray,
        val version: Long,
        val deleted: Boolean,
        val sealedPayload: ByteArray,
        val updatedAtMillis: Long,
    ) {
        /** About the bytes it takes in a response. */
        val size: Int get() = sealedRecordId.size + sealedPayload.size + 96
    }

    /**
     * One row of a pull: the account's counters, a change unless the page is empty, and how many changes came after
     * the cursor (at most the limit + 1).
     */
    private class PulledRow(val syncVersion: Long, val trackersVersion: Long, val change: SealedChange?, val upcoming: Int)

    private class PushOutcome(
        val accepted: List<Pair<String, Long>>,
        val stale: List<SealedChange>,
        val removedTrackers: List<TrackerConnections.Stored>,
    )

    companion object {
        /**
         * The records' bytes (payloads and ids) an answer holds past its first record: a pull's page, a push's stale
         * records. With the largest record (MAX_RECORD_BYTES) it holds under 1.3 MiB.
         */
        const val PAGE_BYTES: Long = 1L shl 20

        private const val CHANGE_COLUMNS = "id_index, sealed_record_id, version, deleted, sealed_payload, updated_at"

        /** What a row's record takes in an answer, about: its payload and its sealed id. */
        private const val CHANGE_BYTES = "(payload_size + octet_length(sealed_record_id))"

        /**
         * What a row holds on disk beyond its payload and sealed id, which every record counts toward the quota, from
         * Postgres's page layout: the heap tuple's line pointer, header, fixed columns and padding, and the payload's
         * 29-byte seal, about 145 bytes; its primary key entry (account, index), 68; its `records_account_version`
         * entry, 36. Free space in the pages isn't counted. Migration V5 recounted the accounts' totals with it.
         */
        const val ROW_BYTES = 256

        /** What a row counts toward the quota: its payload, its sealed id and [ROW_BYTES]. */
        private const val QUOTA_BYTES = "(payload_size + octet_length(sealed_record_id) + $ROW_BYTES)"

        /** What a push refused for the disk asks the device to wait, in seconds: the operator has to free space. */
        private const val DISK_SHORT_RETRY_AFTER_SECONDS = 600

        private val log = LoggerFactory.getLogger(SyncRecords::class.java)

        private const val EMPTY_PUSH = "{\"accepted\":[],\"stale\":[]}"

        /** What [changes] hold until the answer is written: their sealed rows, then the JSON they become. */
        private fun heldBytes(changes: List<SealedChange>): Long = 2L * changes.sumOf { it.size.toLong() }
    }
}

/**
 * One write of a push as its body held it: the payload already the compact JSON the server stores, at most
 * [SyncProtocol.MAX_RECORD_BYTES] (the body's reader refuses more).
 */
class IncomingWrite(val recordId: String, val baseVersion: Long, val deleted: Boolean, val payload: ByteArray)

/**
 * A response's JSON, written as [PullResponse] and [PushResponse] encode (their field names, in their order, compact),
 * so record payloads go in as the bytes they were stored as. SyncTest reads every response back through those classes.
 * The route takes its bytes once the rows it was written from are gone: the JSON and that copy are what was reserved.
 */
class ChangesJson internal constructor(sizeHint: Int) {
    private val out = ByteArrayOutputStream(sizeHint + 128)

    fun toByteArray(): ByteArray = out.toByteArray()

    /** [text] holds only ASCII: field names, punctuation, numbers, `true` and `false`. */
    internal fun ascii(text: String) = bytes(text.encodeToByteArray())

    internal fun number(value: Long) = ascii(value.toString())

    /** [value] as a JSON string, escaped as kotlinx.serialization escapes it. */
    internal fun string(value: String) = bytes(JsonPrimitive(value).toString().encodeToByteArray())

    internal fun bytes(value: ByteArray) = apply { out.writeBytes(value) }
}
