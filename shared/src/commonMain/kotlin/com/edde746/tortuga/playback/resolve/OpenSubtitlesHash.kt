package com.edde746.tortuga.playback.resolve

/**
 * The OpenSubtitles movie hash, which subtitle addons take as the `videoHash` extra: the file size plus the sums of
 * the 64-bit little-endian words of the first and the last 64 KiB, all modulo 2^64, as 16 lowercase hex digits.
 *
 * Files under 64 KiB follow the reference Java implementation on OpenSubtitles' wiki (`OpenSubtitlesHasher`): both
 * chunks are the whole file, and bytes after the last complete word are ignored. Between 64 and 128 KiB the two
 * chunks overlap.
 */
internal object OpenSubtitlesHash {
    const val CHUNK_SIZE = 65536

    /** Bytes each chunk must hold for a file of [size] bytes. */
    fun chunkLength(size: Long): Int = minOf(size, CHUNK_SIZE.toLong()).toInt()

    /** [value] as a hash ([compute]'s form: 16 lowercase hex digits), case and surrounding blanks aside; null otherwise. */
    fun parse(value: String): String? = value.trim().lowercase().takeIf { FORM.matches(it) }

    private val FORM = Regex("^[0-9a-f]{16}$")

    /** [head] and [tail] are the first and the last [chunkLength] bytes of a file of [size] bytes. */
    fun compute(size: Long, head: ByteArray, tail: ByteArray): String {
        val length = chunkLength(size)
        require(head.size == length && tail.size == length) { "chunks must be $length bytes" }
        val hash = (size + head.sumOfWords() + tail.sumOfWords()).toULong()
        return hash.toString(16).padStart(16, '0')
    }

    private fun ByteArray.sumOfWords(): Long {
        var sum = 0L
        var offset = 0
        while (offset + 8 <= size) {
            var word = 0L
            for (i in 7 downTo 0) word = (word shl 8) or (this[offset + i].toLong() and 0xFF)
            sum += word
            offset += 8
        }
        return sum
    }
}
