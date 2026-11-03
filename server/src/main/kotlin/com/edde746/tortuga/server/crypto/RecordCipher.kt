package com.edde746.tortuga.server.crypto

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * At-rest encryption of sync records, tracker connections and the GitHub tokens
 * kept to delete grants. The server reads them in the clear but stores them sealed, so the database, a dump or a backup
 * holds none of an account's addon URLs, ids or titles, nor its tracker tokens and usernames, nor its GitHub tokens:
 *
 * - a record is found by its [index], HMAC-SHA256 of (account id, record id) under the index key, never by its id;
 * - its id and payload are sealed ([seal]) with AES-256-GCM under the sealing key, as
 *   `format (1 byte) ‖ nonce (12) ‖ ciphertext ‖ tag (16)` with a random nonce per seal. The associated data is
 *   `format ‖ field ‖ account id ‖ index`, so a sealed value opens only in the row and column it was written to;
 * - a tracker connection's session is sealed the same way, with the row's `profileId/service` (UTF-8) as the index;
 * - a GitHub identity's token likewise, with the identity's id (its UUID as text, UTF-8) as the index;
 * - the data key's check ([checkDataKey]) likewise: nothing, sealed for no account and no index.
 *
 * Both keys are derived from the [DataKey] with their own labels. Thread-safe: each thread keeps its own cipher and MAC,
 * so a pull's hundreds of opens don't each look up the algorithm and expand the key again (`init` with the same key
 * reuses its schedule).
 */
class RecordCipher(dataKey: DataKey) {
    private val sealingKey = SecretKeySpec(dataKey.derive(SEALING_LABEL), "AES")
    private val indexKey = SecretKeySpec(dataKey.derive(INDEX_LABEL), HMAC)
    private val random = SecureRandom()
    private val ciphers = ThreadLocal.withInitial { Cipher.getInstance(TRANSFORMATION) }
    private val macs = ThreadLocal.withInitial { Mac.getInstance(HMAC).apply { init(indexKey) } }

    /** The column a sealed value belongs to; part of the associated data. */
    enum class Field(val tag: Byte) {
        RECORD_ID(1),
        PAYLOAD(2),
        TRACKER_SESSION(3),
        GITHUB_TOKEN(4),
        DATA_KEY_CHECK(5),
    }

    /** The record's lookup key: the same for the same account and id, unrelated across accounts and data keys. */
    fun index(accountId: UUID, recordId: String): ByteArray = macs.get().run {
        update(accountId.toBytes())
        doFinal(recordId.encodeToByteArray())
    }

    fun seal(field: Field, accountId: UUID, index: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = ciphers.get()
        cipher.init(Cipher.ENCRYPT_MODE, sealingKey, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData(field, accountId, index))
        val sealed = ByteArray(OVERHEAD + plaintext.size)
        sealed[0] = FORMAT
        nonce.copyInto(sealed, 1)
        cipher.doFinal(plaintext, 0, plaintext.size, sealed, 1 + NONCE_BYTES)
        return sealed
    }

    /**
     * Opens what [seal] sealed for the same field, account and index; null for anything else: another data key, another
     * row or column, an altered value. After the data key was lost and replaced (server/README.md, The data key) that
     * is every value sealed before, so callers treat it as gone, never as an error.
     */
    fun open(field: Field, accountId: UUID, index: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < OVERHEAD || sealed[0] != FORMAT) return null
        val cipher = ciphers.get()
        cipher.init(Cipher.DECRYPT_MODE, sealingKey, GCMParameterSpec(TAG_BITS, sealed, 1, NONCE_BYTES))
        cipher.updateAAD(associatedData(field, accountId, index))
        return try {
            cipher.doFinal(sealed, 1 + NONCE_BYTES, sealed.size - 1 - NONCE_BYTES)
        } catch (_: AEADBadTagException) {
            null
        }
    }

    private fun associatedData(field: Field, accountId: UUID, index: ByteArray): ByteArray =
        ByteBuffer.allocate(2 + UUID_BYTES + index.size)
            .put(FORMAT)
            .put(field.tag)
            .put(accountId.toBytes())
            .put(index)
            .array()

    private fun UUID.toBytes(): ByteArray =
        ByteBuffer.allocate(UUID_BYTES).putLong(mostSignificantBits).putLong(leastSignificantBits).array()

    private companion object {
        const val SEALING_LABEL = "tortuga-server/records/seal/v1"
        const val INDEX_LABEL = "tortuga-server/records/index/v1"
        const val HMAC = "HmacSHA256"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT: Byte = 1
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val OVERHEAD = 1 + NONCE_BYTES + TAG_BITS / 8
        const val UUID_BYTES = 16
    }
}
