package com.edde746.tortuga.data.sync

import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A hybrid logical clock timestamp (Kulkarni et al., "Logical Physical Clocks", 2014): the wall time of a write in
 * epoch milliseconds, a [counter] that orders writes the wall clock can't tell apart (same millisecond, or a remote
 * clock ahead of this one), and the writing device's [node] id as the final tie-break. Synced records are last
 * writer wins by this order; [HybridClock] issues them.
 *
 * [toString] is `wwwwwwwwwwww-cccc-nnnnnnnnnnnnnnnn`: 12 hex digits of wall time, 4 of counter and the 16 of the node
 * id, fixed width and lowercase, so text comparison (SQLite `TEXT`, [String.compareTo]) is clock order. Storage and
 * record payloads use this form.
 */
@Serializable(with = HlcSerializer::class)
data class Hlc(val wallMillis: Long, val counter: Int, val node: String) : Comparable<Hlc> {
    init {
        require(wallMillis in 0..MAX_WALL_MILLIS) { "HLC wall time out of range: $wallMillis" }
        require(counter in 0..MAX_COUNTER) { "HLC counter out of range: $counter" }
        require(isNodeId(node)) { "HLC node id must be $NODE_LENGTH lowercase hex digits" }
    }

    override fun compareTo(other: Hlc): Int = compareValuesBy(this, other, Hlc::wallMillis, Hlc::counter, Hlc::node)

    override fun toString(): String =
        wallMillis.toString(16).padStart(WALL_DIGITS, '0') + '-' +
            counter.toString(16).padStart(COUNTER_DIGITS, '0') + '-' + node

    companion object {
        const val COUNTER_BITS: Int = 16
        const val MAX_COUNTER: Int = (1 shl COUNTER_BITS) - 1

        /** The latest wall time, 2^47 - 1 ms (in the year 6429), well inside [toString]'s 12 hex digits. */
        const val MAX_WALL_MILLIS: Long = (1L shl 47) - 1
        const val NODE_LENGTH: Int = 16
        private const val WALL_DIGITS = 12
        private const val COUNTER_DIGITS = 4
        private const val TEXT_LENGTH = WALL_DIGITS + 1 + COUNTER_DIGITS + 1 + NODE_LENGTH

        /** Sorts before every timestamp a clock issues; the lower bound for "changed after" queries. */
        val ZERO: Hlc = Hlc(0, 0, "0".repeat(NODE_LENGTH))

        /** Parses [toString]'s form. @throws IllegalArgumentException for anything else. */
        @Throws(IllegalArgumentException::class)
        fun parse(text: String): Hlc {
            require(text.length == TEXT_LENGTH && text[WALL_DIGITS] == '-' && text[WALL_DIGITS + 1 + COUNTER_DIGITS] == '-') {
                "Not an HLC: $text"
            }
            val wall = text.substring(0, WALL_DIGITS)
            val counter = text.substring(WALL_DIGITS + 1, WALL_DIGITS + 1 + COUNTER_DIGITS)
            require(isLowerHex(wall) && isLowerHex(counter)) { "Not an HLC: $text" }
            return Hlc(wall.toLong(16), counter.toInt(16), text.substring(TEXT_LENGTH - NODE_LENGTH))
        }

        /** A fresh random node id for a device (64 bits from the platform CSPRNG). */
        fun newNodeId(): String = CryptographyRandom.nextBytes(NODE_LENGTH / 2).toHexString()

        private fun isNodeId(value: String) = value.length == NODE_LENGTH && isLowerHex(value)
        private fun isLowerHex(value: String) = value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

internal object HlcSerializer : KSerializer<Hlc> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.edde746.tortuga.Hlc", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Hlc) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Hlc = Hlc.parse(decoder.decodeString())
}
