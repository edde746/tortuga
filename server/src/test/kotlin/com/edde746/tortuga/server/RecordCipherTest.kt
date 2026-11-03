package com.edde746.tortuga.server

import com.edde746.tortuga.server.crypto.DataKey
import com.edde746.tortuga.server.crypto.RecordCipher
import com.edde746.tortuga.server.crypto.RecordCipher.Field
import java.security.SecureRandom
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** At-rest sealing of records: what opens where, and what the lookup index reveals. Needs no database. */
class RecordCipherTest {
    private fun dataKey() = DataKey(ByteArray(DataKey.SIZE).also(SecureRandom()::nextBytes))

    private val cipher = RecordCipher(dataKey())
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    @Test
    fun aSealedValueOpensOnlyWithItsKeyInItsRowAndColumn() {
        val index = cipher.index(alice, "library/profile-1/tt0111161")
        val plaintext = """{"body":{"name":"The Shawshank Redemption"}}""".encodeToByteArray()
        val sealed = cipher.seal(Field.PAYLOAD, alice, index, plaintext)
        assertContentEquals(plaintext, cipher.open(Field.PAYLOAD, alice, index, sealed))
        // A fresh nonce each time: equal payloads don't look equal at rest.
        assertFalse(sealed.contentEquals(cipher.seal(Field.PAYLOAD, alice, index, plaintext)))

        val otherIndex = cipher.index(alice, "library/profile-1/tt0068646")
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val elsewhere = mapOf(
            "another record's row" to { cipher.open(Field.PAYLOAD, alice, otherIndex, sealed) },
            "another account's row" to { cipher.open(Field.PAYLOAD, bob, index, sealed) },
            "the record id column" to { cipher.open(Field.RECORD_ID, alice, index, sealed) },
            "another data key" to { RecordCipher(dataKey()).open(Field.PAYLOAD, alice, index, sealed) },
            "an altered value" to { cipher.open(Field.PAYLOAD, alice, index, tampered) },
        )
        for ((case, open) in elsewhere) assertNull(open(), case)
    }

    @Test
    fun theIndexIsStablePerAccountAndKeyed() {
        val id = "addons/profile-1"
        // Stable: it is how the record is found again.
        assertContentEquals(cipher.index(alice, id), cipher.index(alice, id))
        // Per account: two accounts' copies of one record can't be matched up in a dump.
        assertFalse(cipher.index(alice, id).contentEquals(cipher.index(bob, id)))
        // Keyed: without the data key, a guessed id can't be checked against the index.
        assertFalse(cipher.index(alice, id).contentEquals(RecordCipher(dataKey()).index(alice, id)))
    }
}
