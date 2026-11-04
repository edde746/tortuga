package com.edde746.tortuga.server.crypto

import com.edde746.tortuga.server.crypto.RecordCipher.Companion.NO_ACCOUNT
import com.edde746.tortuga.server.crypto.RecordCipher.Field
import com.edde746.tortuga.server.db.Database
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
 *   newest record, if there is one; its check is then stored.
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
            val newestOpens = query("SELECT account_id, id_index, sealed_record_id FROM records ORDER BY updated_at DESC LIMIT 1") {
                cipher.open(Field.RECORD_ID, it.uuid("account_id"), it.getBytes("id_index"), it.getBytes("sealed_record_id")) != null
            }
            require(newestOpens.all { it }) { ANOTHER_KEY }
        }
        update(
            "INSERT INTO data_key (sealed_check) VALUES (?) ON CONFLICT (id) DO UPDATE SET sealed_check = excluded.sealed_check",
            cipher.seal(Field.DATA_KEY_CHECK, NO_ACCOUNT, NOTHING, NOTHING),
        )
        log.info("Stored the data key's check: the server starts with this key only")
    }
}

private val NOTHING = ByteArray(0)

private const val ANOTHER_KEY = "${DataKey.ENV} holds another key than the one this database's records are sealed with: " +
    "start with that key (server/README.md, The data key)"

private val log = LoggerFactory.getLogger("com.edde746.tortuga.server.crypto.DataKeyCheck")
