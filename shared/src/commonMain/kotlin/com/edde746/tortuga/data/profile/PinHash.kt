package com.edde746.tortuga.data.profile

import com.edde746.tortuga.crypto.cryptoProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * A profile PIN as the profile record keeps it: a random 16-byte salt followed by SHA-256 of the salt
 * and the PIN's UTF-8 bytes. The PIN is a client-side lock between the people sharing an account, not access control:
 * the record syncs through the account's server like the rest of the profile.
 */
internal object PinHash {
    private const val SALT_SIZE = 16
    private const val SIZE = SALT_SIZE + 32

    fun hash(pin: String): ByteArray {
        val salt = CryptographyRandom.nextBytes(SALT_SIZE)
        return salt + digest(salt, pin)
    }

    /** Whether [pin] is the PIN [stored] was made from ([hash]); false for anything that isn't such a hash. */
    fun matches(stored: ByteArray, pin: String): Boolean {
        if (stored.size != SIZE) return false
        val expected = digest(stored.copyOfRange(0, SALT_SIZE), pin)
        var diff = 0
        for (i in expected.indices) diff = diff or (stored[SALT_SIZE + i].toInt() xor expected[i].toInt())
        return diff == 0
    }

    private fun digest(salt: ByteArray, pin: String): ByteArray =
        cryptoProvider.get(SHA256).hasher().hashBlocking(salt + pin.encodeToByteArray())
}
