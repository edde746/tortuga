package com.edde746.tortuga.server.crypto

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The server's data key: [SIZE] random bytes read from the file named by `TORTUGA_DATA_KEY_FILE` ([ENV]), kept
 * outside Postgres and outside its backups. Records are sealed at rest under keys derived from it ([RecordCipher]),
 * so the database, a dump or a backup is unreadable without this file, and a restored database needs the same file.
 */
class DataKey(bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(bytes.size == SIZE) { "A data key is $SIZE bytes, not ${bytes.size}" }
    }

    /**
     * A 32-byte key for the purpose named by [label]: HKDF-SHA256 (RFC 5869) of the data key with the default
     * all-zero salt and [label] as the info. Distinct labels give independent keys.
     */
    fun derive(label: String): ByteArray {
        val prk = hmacSha256(ByteArray(HASH_BYTES), bytes)
        return hmacSha256(prk, label.encodeToByteArray() + FIRST_BLOCK)
    }

    override fun toString(): String = "DataKey(…)"

    companion object {
        const val SIZE: Int = 32

        /** The environment variable naming the key file. */
        const val ENV: String = "TORTUGA_DATA_KEY_FILE"

        private const val HASH_BYTES = 32
        private const val FIRST_BLOCK: Byte = 1

        /**
         * Reads the key from [path]. Fails with an [IllegalArgumentException] that says what to fix if the file can't
         * be read or doesn't hold exactly [SIZE] bytes.
         */
        fun load(path: Path): DataKey {
            val bytes = try {
                // One byte more than a key at most, so a wrong path (a device, a large file) can't stall startup.
                Files.newInputStream(path).use { it.readNBytes(SIZE + 1) }
            } catch (e: IOException) {
                throw IllegalArgumentException("$ENV: can't read $path ($e)")
            }
            try {
                require(bytes.size == SIZE) {
                    val found = if (bytes.size > SIZE) "more" else "${bytes.size}"
                    "$ENV: $path must hold exactly $SIZE bytes, not $found; create the key with " +
                        "`openssl rand 32 > tortuga-data.key` (see server/README.md)"
                }
                return DataKey(bytes)
            } finally {
                bytes.fill(0)
            }
        }

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }
    }
}
