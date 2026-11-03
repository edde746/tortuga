package com.edde746.tortuga.data.sync

import com.edde746.tortuga.data.Db
import com.edde746.tortuga.data.db.Sync_state

/**
 * The account's pull cursor: the server version up to which changes have been pulled. The account with
 * a cursor is the one the local synced data belongs to ([dataAccount]); there is at most one.
 */
class SyncStateRepository internal constructor(private val db: Db) {
    /** `null` while [accountId] isn't the data's account. */
    suspend fun pullCursor(accountId: String): Long? = db.read { syncStateQueries.selectCursor(accountId).executeAsOneOrNull() }

    suspend fun setPullCursor(accountId: String, cursor: Long) {
        db.write { q.syncStateQueries.upsert(Sync_state(accountId, cursor, now())) }
    }

    /** The account the local synced data belongs to, or null when it belongs to none. */
    suspend fun dataAccount(): String? = db.read { syncStateQueries.selectAccount().executeAsOneOrNull() }

    /** Forgets every cursor, with the data's account. */
    suspend fun clearAll() {
        db.write { q.syncStateQueries.deleteAll() }
    }
}
