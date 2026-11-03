package com.edde746.tortuga.data.library

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.MetaItem
import kotlin.io.encoding.Base64

/**
 * Stremio's per-video watched marks (stremio-core's `stremio-watched-bitfield` crate, ported as is), stored in
 * [LibraryItemState.watchedVideos] so a library item stays what Stremio writes.
 *
 * The stored text is `{anchor video id}:{anchor length}:{bits}`: one bit per video of the meta, in its
 * [MetaItem.watchedOrder], least significant bit first, zlib-compressed and base64-encoded. The anchor is the last
 * watched video, so when videos are added before it (a new special, a re-numbered season) the bits are shifted to its
 * new position when read; when the anchor video is gone, the marks start over empty, as in Stremio. Sync and import
 * refuse a field that isn't one ([WatchedField.parse]); one stored before that reads as no marks and is never replaced
 * by a mark ([readable]).
 */
internal class WatchedBitField private constructor(private val bits: BitField8, val videoIds: List<String>) {
    /** Each video's bit, by id (the first of an id listed twice, as `indexOf` finds it). */
    private val positions: Map<String, Int> by lazy {
        HashMap<String, Int>(videoIds.size * 2).also { map -> videoIds.forEachIndexed { index, id -> map.getOrPut(id) { index } } }
    }

    fun isWatched(videoId: String): Boolean {
        val index = positions[videoId] ?: return false
        return bits[index]
    }

    /** Sets the mark of [videoId]; a video not in [videoIds] is ignored. */
    fun set(videoId: String, watched: Boolean) {
        val index = positions[videoId] ?: return
        bits[index] = watched
    }

    /** The stored form (stremio-core `WatchedField::from(WatchedBitField)`). */
    fun serialize(): String {
        val last = bits.lastIndexOf(true) ?: 0
        return WatchedField(videoIds.getOrNull(last) ?: "undefined", last + 1, bits).serialize()
    }

    companion object {
        /**
         * The marks of [meta]'s videos in [serialized] (stremio-core `LibraryItemState::watched_bitfield`): none when
         * it is absent or unreadable (logged).
         */
        fun of(serialized: String?, meta: MetaItem): WatchedBitField = readable(serialized, meta) ?: empty(meta.watchedOrder)

        /**
         * The marks of [meta]'s videos in [serialized] to change: none when it is absent, null when it is unreadable
         * (logged). A write leaves an unreadable field as it is: marks set over none would replace every mark it holds.
         */
        fun readable(serialized: String?, meta: MetaItem): WatchedBitField? {
            val ids = meta.watchedOrder
            if (serialized == null) return empty(ids)
            return try {
                parse(serialized, ids)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, e) { "a stored watched field doesn't read: its marks read as none" }
                null
            }
        }

        /** Every video unwatched. */
        fun empty(videoIds: List<String>): WatchedBitField = WatchedBitField(BitField8(videoIds.size), videoIds)

        /**
         * `WatchedBitField::construct_and_resize`: reads [serialized] against [videoIds], shifting the bits to where
         * the anchor video is now.
         *
         * @throws IllegalArgumentException when [serialized] isn't a watched field.
         */
        fun parse(serialized: String, videoIds: List<String>): WatchedBitField {
            val field = WatchedField.parse(serialized)
            val anchorIndex = videoIds.indexOf(field.anchor)
            if (anchorIndex < 0) return empty(videoIds)
            val offset = field.anchorLength - anchorIndex - 1
            val bits = BitField8.withValues(field.bits.values, videoIds.size)
            if (offset == 0) return WatchedBitField(bits, videoIds)
            val resized = BitField8(videoIds.size)
            for (i in videoIds.indices) {
                val previous = i + offset
                if (previous >= 0 && previous < bits.length) resized[i] = bits[previous]
            }
            return WatchedBitField(resized, videoIds)
        }

        private const val TAG = "library"
    }
}

/**
 * A watched field as stored, not yet laid over a video list: the [anchor] video's id, the [anchorLength] of the bits
 * up to it, and the [bits] (all of them, [BitField8.length] a whole number of bytes).
 */
internal class WatchedField(val anchor: String, val anchorLength: Int, val bits: BitField8) {
    /** The stored form. */
    fun serialize(): String = "$anchor:$anchorLength:${bits.encode()}"

    companion object {
        /** @throws IllegalArgumentException when [text] isn't a watched field. */
        fun parse(text: String): WatchedField {
            val components = text.split(':')
            require(components.size >= 3) { "not enough components" }
            val anchorLength = components[components.size - 2].toIntOrNull()?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("bad anchor length")
            val bits = BitField8.decode(components.last(), length = null)
            return WatchedField(components.subList(0, components.size - 2).joinToString(":"), anchorLength, bits)
        }
    }
}

/** stremio-watched-bitfield's `BitField8`: [length] bits over [values], least significant bit of each byte first. */
internal class BitField8 private constructor(length: Int, values: ByteArray) {
    var length: Int = length
        private set
    var values: ByteArray = values
        private set

    constructor(length: Int) : this(length, ByteArray(byteCount(length)))

    /** False past the stored bytes. */
    operator fun get(index: Int): Boolean {
        val byte = index / 8
        return byte < values.size && (values[byte].toInt() ushr (index % 8)) and 1 != 0
    }

    /** Past the stored bytes, the field grows to take [index] (and [length] becomes every bit of it). */
    operator fun set(index: Int, value: Boolean) {
        val byte = index / 8
        if (byte >= values.size) {
            values = values.copyOf(byte + 1)
            length = values.size * 8
        }
        val mask = 1 shl (index % 8)
        values[byte] = (if (value) values[byte].toInt() or mask else values[byte].toInt() and mask.inv()).toByte()
    }

    fun lastIndexOf(value: Boolean): Int? = (length - 1 downTo 0).firstOrNull { get(it) == value }

    /** base64 of the zlib-compressed bytes. */
    fun encode(): String = Base64.Default.encode(Zlib.deflate(values))

    companion object {
        private val lenientBase64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

        /** [values] as [length] bits (all of them when null), padded with zero bytes to hold that many. */
        fun withValues(values: ByteArray, length: Int?): BitField8 {
            val bitLength = length ?: (values.size * 8)
            val bytes = byteCount(bitLength)
            return BitField8(bitLength, if (bytes > values.size) values.copyOf(bytes) else values.copyOf())
        }

        /** @throws IllegalArgumentException for text that isn't base64 of a zlib stream. */
        fun decode(encoded: String, length: Int?): BitField8 = withValues(Zlib.inflate(lenientBase64.decode(encoded)), length)

        private fun byteCount(bits: Int) = (bits + 7) / 8
    }
}
