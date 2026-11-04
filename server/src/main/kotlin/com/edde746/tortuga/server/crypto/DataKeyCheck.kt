package com.edde746.tortuga.server.crypto

import com.edde746.tortuga.server.crypto.RecordCipher.Companion.NO_ACCOUNT
import com.edde746.tortuga.server.crypto.RecordCipher.Field
import com.edde746.tortuga.server.db.Database
import com.edde746.tortuga.server.db.Tx
import com.edde746.tortuga.server.db.uuid
import org.slf4j.LoggerFactory

/**
 * Refuses a data key that isn't the one the database's sealed values were written with, so a wrong
 * `TORTUGA_DATA_KEY_FILE` stops the server at start instead of serving every account a library without the records the
 * key can't open (server/README.md, The data key). The table `data_key` holds one row, its check: a value sealed with
 * the database's key ([Field.DATA_KEY_CHECK]).
 *
 * - A check: [cipher] must open it.
 * - NULL, as migration V8 left it (a database from before the check, until its first start): [cipher] must open the
 *   newest sealed value of each kind there is (a record, a tracker connection, a GitHub token, an Apple refresh token),
 *   so a database whose accounts have only signed in still refuses another key; its check is then stored.
 * - No row, which the operator's `DELETE FROM data_key` leaves once the old key is lost for good: [cipher]'s check is
 *   stored, and what the lost key sealed is gone.
 *
 * @throws IllegalArgumentException when [cipher] holds another key; its message says what to do.
 */
suspend fun checkDataKey(db: Database, cipher: RecordCipher) {
    db.transaction {
        val rows = query("SELECT sealed_check FROM data_key FOR UPDATE") { it.getBytes("sealed_check") }
        if (rows.isNotEmpty()) {
            val stored = rows.single()
            if (stored != null) {
                require(cipher.open(Field.DATA_KEY_CHECK, NO_ACCOUNT, NOTHING, stored) != null) { ANOTHER_KEY }
                return@transaction
            }
            require(newestSealedValues(cipher).all { it }) { ANOTHER_KEY }
        }
        update(
            "INSERT INTO data_key (sealed_check) VALUES (?) ON CONFLICT (id) DO UPDATE SET sealed_check = excluded.sealed_check",
            cipher.seal(Field.DATA_KEY_CHECK, NO_ACCOUNT, NOTHING, NOTHING),
        )
        log.info("Stored the data key's check: the server starts with this key only")
    }
}

/** Whether [cipher] opens the newest sealed value of each kind the database holds; one entry per kind it has. */
private fun Tx.newestSealedValues(cipher: RecordCipher): List<Boolean> = listOf(
    query("SELECT account_id, id_index, sealed_record_id FROM records ORDER BY updated_at DESC LIMIT 1") {
        cipher.open(Field.RECORD_ID, it.uuid("account_id"), it.getBytes("id_index"), it.getBytes("sealed_record_id")) != null
    },
    query("SELECT account_id, profile_id, service, sealed_session FROM tracker_connections ORDER BY connected_at DESC LIMIT 1") {
        val index = "${it.getString("profile_id")}/${it.getString("service")}".encodeToByteArray()
        cipher.open(Field.TRACKER_SESSION, it.uuid("account_id"), index, it.getBytes("sealed_session")) != null
    },
    query("SELECT id, account_id, github_token FROM identities WHERE github_token IS NOT NULL ORDER BY last_used_at DESC LIMIT 1") {
        cipher.open(Field.GITHUB_TOKEN, it.uuid("account_id"), it.uuid("id").toString().encodeToByteArray(), it.getBytes("github_token")) != null
    },
    query(
        """
        SELECT id, account_id, sealed_apple_refresh_token FROM identities WHERE sealed_apple_refresh_token IS NOT NULL
        ORDER BY last_used_at DESC LIMIT 1
        """,
    ) {
        val index = it.uuid("id").toString().encodeToByteArray()
        cipher.open(Field.APPLE_REFRESH_TOKEN, it.uuid("account_id"), index, it.getBytes("sealed_apple_refresh_token")) != null
    },
).flatten()

private val NOTHING = ByteArray(0)

private const val ANOTHER_KEY = "${DataKey.ENV} holds another key than the one this database's records are sealed with: " +
    "start with that key (server/README.md, The data key)"

private val log = LoggerFactory.getLogger("com.edde746.tortuga.server.crypto.DataKeyCheck")
