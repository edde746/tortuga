package com.edde746.tortuga.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Random bearer secrets and their at-rest form. Every secret is 256 random bits, so a plain SHA-256 is enough to
 * store it: there is nothing to brute-force.
 */
object Secrets {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** [prefix] followed by 32 random bytes in unpadded base64url. The prefix names the kind for secret scanners. */
    fun generate(prefix: String): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return prefix + encoder.encodeToString(bytes)
    }

    fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    /**
     * Unpadded base64url of [sha256]: the stored and compared form of the web hand-offs' verifier challenges and proofs
     * (Apple and GitHub), the tracker sign-in relay's poll and confirmation digests, and the device-link answer limit's
     * key (an access token).
     */
    fun sha256Base64Url(value: String): String = encoder.encodeToString(sha256(value))

    const val ACCESS_PREFIX: String = "tta_"
    const val REFRESH_PREFIX: String = "ttr_"
    const val NONCE_PREFIX: String = "ttn_"
    const val TRACKER_OAUTH_POLL_PREFIX: String = "tto_"
}
