package com.edde746.tortuga.data.library

/**
 * zlib streams (RFC 1950) around DEFLATE (RFC 1951), for Stremio's watched-videos bitfield ([WatchedBitField]) and
 * the synced addon list ([com.edde746.tortuga.data.sync.AddonListBody]): common Kotlin has no zlib.
 *
 * - [inflate] reads stored, fixed-Huffman and dynamic-Huffman blocks and checks the Adler-32 trailer.
 * - [deflate] writes a single fixed-Huffman block with LZ77 matches (hash chains, greedy). That is the block type
 *   flate2 (stremio-core's encoder, level 6) chooses for inputs as small as a bitfield, and a bitfield without
 *   repeats comes out as the bytes flate2 writes; any zlib decoder reads the result either way.
 */
internal object Zlib {
    /** The most [inflate] produces before giving up: a bitfield of a million videos is 125 KB. */
    const val MAX_OUTPUT: Int = 1 shl 20

    /** @throws ZlibException when [data] isn't a complete, valid zlib stream, or it inflates past [maxOutput]. */
    fun inflate(data: ByteArray, maxOutput: Int = MAX_OUTPUT): ByteArray {
        if (data.size < 2 + 4) throw ZlibException("too short")
        val cmf = data[0].toInt() and 0xFF
        val flg = data[1].toInt() and 0xFF
        if ((cmf shl 8 or flg) % 31 != 0) throw ZlibException("bad header check")
        if (cmf and 0x0F != 8 || cmf ushr 4 > 7) throw ZlibException("not deflate")
        if (flg and 0x20 != 0) throw ZlibException("preset dictionary")
        val inflater = Inflater(data, 2, maxOutput)
        val out = inflater.run()
        val trailer = inflater.position
        if (trailer + 4 > data.size) throw ZlibException("missing Adler-32")
        var expected = 0
        for (i in 0 until 4) expected = expected shl 8 or (data[trailer + i].toInt() and 0xFF)
        if (adler32(out) != expected) throw ZlibException("Adler-32 mismatch")
        return out
    }

    fun deflate(data: ByteArray): ByteArray {
        val out = BitWriter(data.size + 16)
        out.byte(0x78) // CM 8 (deflate), CINFO 7 (32 KiB window)
        out.byte(0x9C) // FLEVEL 2 (default), FCHECK
        out.bits(1, 1) // BFINAL
        out.bits(1, 2) // BTYPE 01: fixed Huffman codes
        Lz77(data).forEach(
            literal = { out.literal(it) },
            match = { length, distance -> out.match(length, distance) },
        )
        out.literal(END_OF_BLOCK)
        out.alignToByte()
        val adler = adler32(data)
        for (shift in intArrayOf(24, 16, 8, 0)) out.byte(adler ushr shift and 0xFF)
        return out.toByteArray()
    }

    internal fun adler32(data: ByteArray): Int {
        var a = 1
        var b = 0
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % ADLER_MOD
            b = (b + a) % ADLER_MOD
        }
        return b shl 16 or a
    }

    private const val ADLER_MOD = 65521
    private const val END_OF_BLOCK = 256
    private const val MAX_BITS = 15
    private const val MIN_MATCH = 3
    private const val MAX_MATCH = 258
    private const val WINDOW = 32768

    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DISTANCE_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097,
        6145, 8193, 12289, 16385, 24577,
    )
    private val DISTANCE_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

    /** Order of the code-length code lengths in a dynamic block header. */
    private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    private val fixedLiterals by lazy {
        val lengths = IntArray(288) { symbol ->
            when {
                symbol < 144 -> 8
                symbol < 256 -> 9
                symbol < 280 -> 7
                else -> 8
            }
        }
        Huffman(lengths, 0, lengths.size)
    }
    private val fixedDistances by lazy { Huffman(IntArray(30) { 5 }, 0, 30) }

    /** A canonical Huffman code as counts per length and symbols in code order (zlib's puff.c). */
    private class Huffman(lengths: IntArray, offset: Int, n: Int) {
        val count = IntArray(MAX_BITS + 1)
        val symbol = IntArray(n)

        /** 0 for a complete code, positive when codes are left over (incomplete), negative when over-subscribed. */
        val left: Int

        init {
            for (s in 0 until n) count[lengths[offset + s]]++
            var remaining = 1
            if (count[0] != n) {
                for (length in 1..MAX_BITS) {
                    remaining = (remaining shl 1) - count[length]
                    if (remaining < 0) break
                }
            } else {
                remaining = 0
            }
            left = remaining
            if (remaining >= 0) {
                val offsets = IntArray(MAX_BITS + 1)
                for (length in 1 until MAX_BITS) offsets[length + 1] = offsets[length] + count[length]
                for (s in 0 until n) {
                    val length = lengths[offset + s]
                    if (length != 0) symbol[offsets[length]++] = s
                }
            }
        }
    }

    private class Inflater(private val input: ByteArray, start: Int, private val maxOutput: Int) {
        /** The next unread byte; after [run], the first byte after the deflate data. */
        var position = start
            private set
        private var bitBuffer = 0
        private var bitCount = 0
        private var out = ByteArray(64)
        private var size = 0

        fun run(): ByteArray {
            do {
                val last = bits(1) == 1
                when (bits(2)) {
                    0 -> stored()
                    1 -> codes(fixedLiterals, fixedDistances)
                    2 -> dynamic()
                    else -> throw ZlibException("invalid block type")
                }
            } while (!last)
            // The trailer starts at the next byte boundary.
            bitBuffer = 0
            bitCount = 0
            return out.copyOf(size)
        }

        private fun bits(need: Int): Int {
            var value = bitBuffer
            while (bitCount < need) {
                if (position >= input.size) throw ZlibException("truncated")
                value = value or ((input[position++].toInt() and 0xFF) shl bitCount)
                bitCount += 8
            }
            bitBuffer = value ushr need
            bitCount -= need
            return value and ((1 shl need) - 1)
        }

        private fun stored() {
            bitBuffer = 0
            bitCount = 0
            if (position + 4 > input.size) throw ZlibException("truncated")
            val length = (input[position].toInt() and 0xFF) or ((input[position + 1].toInt() and 0xFF) shl 8)
            val complement = (input[position + 2].toInt() and 0xFF) or ((input[position + 3].toInt() and 0xFF) shl 8)
            position += 4
            if (length != complement.inv() and 0xFFFF) throw ZlibException("stored length mismatch")
            if (position + length > input.size) throw ZlibException("truncated")
            ensure(length)
            input.copyInto(out, size, position, position + length)
            size += length
            position += length
        }

        private fun decode(huffman: Huffman): Int {
            var code = 0
            var first = 0
            var index = 0
            for (length in 1..MAX_BITS) {
                code = code or bits(1)
                val count = huffman.count[length]
                if (code - count < first) return huffman.symbol[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            throw ZlibException("invalid code")
        }

        private fun codes(literals: Huffman, distances: Huffman) {
            while (true) {
                val symbol = decode(literals)
                when {
                    symbol < 256 -> {
                        ensure(1)
                        out[size++] = symbol.toByte()
                    }
                    symbol == END_OF_BLOCK -> return
                    else -> {
                        val lengthSymbol = symbol - 257
                        if (lengthSymbol >= LENGTH_BASE.size) throw ZlibException("invalid length code")
                        val length = LENGTH_BASE[lengthSymbol] + bits(LENGTH_EXTRA[lengthSymbol])
                        val distanceSymbol = decode(distances)
                        if (distanceSymbol >= DISTANCE_BASE.size) throw ZlibException("invalid distance code")
                        val distance = DISTANCE_BASE[distanceSymbol] + bits(DISTANCE_EXTRA[distanceSymbol])
                        if (distance > size) throw ZlibException("distance too far back")
                        ensure(length)
                        // Byte by byte: a match may overlap the bytes it produces.
                        repeat(length) {
                            out[size] = out[size - distance]
                            size++
                        }
                    }
                }
            }
        }

        private fun dynamic() {
            val literalCount = bits(5) + 257
            val distanceCount = bits(5) + 1
            val codeLengthCount = bits(4) + 4
            if (literalCount > 286 || distanceCount > 30) throw ZlibException("bad counts")
            val lengths = IntArray(286 + 30)
            for (i in 0 until codeLengthCount) lengths[CODE_LENGTH_ORDER[i]] = bits(3)
            val lengthCode = Huffman(lengths, 0, 19)
            if (lengthCode.left != 0) throw ZlibException("incomplete code-length code")
            var index = 0
            while (index < literalCount + distanceCount) {
                val symbol = decode(lengthCode)
                if (symbol < 16) {
                    lengths[index++] = symbol
                    continue
                }
                var length = 0
                val repeat = when (symbol) {
                    16 -> {
                        if (index == 0) throw ZlibException("repeat with no first length")
                        length = lengths[index - 1]
                        3 + bits(2)
                    }
                    17 -> 3 + bits(3)
                    else -> 11 + bits(7)
                }
                if (index + repeat > literalCount + distanceCount) throw ZlibException("too many lengths")
                repeat(repeat) { lengths[index++] = length }
            }
            if (lengths[END_OF_BLOCK] == 0) throw ZlibException("no end-of-block code")
            val literals = Huffman(lengths, 0, literalCount)
            if (literals.left < 0 || (literals.left > 0 && literalCount - literals.count[0] != 1)) {
                throw ZlibException("bad literal/length code")
            }
            val distances = Huffman(lengths, literalCount, distanceCount)
            if (distances.left < 0 || (distances.left > 0 && distanceCount - distances.count[0] != 1)) {
                throw ZlibException("bad distance code")
            }
            codes(literals, distances)
        }

        private fun ensure(extra: Int) {
            val needed = size + extra
            if (needed > maxOutput) throw ZlibException("output over $maxOutput bytes")
            if (needed > out.size) out = out.copyOf(maxOf(needed, minOf(out.size * 2, maxOutput)))
        }
    }

    /** Greedy LZ77 over hash chains of three-byte prefixes, within DEFLATE's 32 KiB window. */
    private class Lz77(private val data: ByteArray) {
        private val head = IntArray(HASH_SIZE) { -1 }
        private val previous = IntArray(data.size)

        fun forEach(literal: (Int) -> Unit, match: (length: Int, distance: Int) -> Unit) {
            var i = 0
            while (i < data.size) {
                var bestLength = 0
                var bestDistance = 0
                if (i + MIN_MATCH <= data.size) {
                    val maxLength = minOf(MAX_MATCH, data.size - i)
                    var candidate = head[hash(i)]
                    var chain = MAX_CHAIN
                    while (candidate >= 0 && i - candidate <= WINDOW && chain-- > 0) {
                        var length = 0
                        while (length < maxLength && data[candidate + length] == data[i + length]) length++
                        if (length > bestLength) {
                            bestLength = length
                            bestDistance = i - candidate
                            if (length == maxLength) break
                        }
                        candidate = previous[candidate]
                    }
                }
                if (bestLength >= MIN_MATCH) {
                    match(bestLength, bestDistance)
                    repeat(bestLength) { insert(i++) }
                } else {
                    literal(data[i].toInt() and 0xFF)
                    insert(i++)
                }
            }
        }

        private fun insert(i: Int) {
            if (i + MIN_MATCH > data.size) return
            val h = hash(i)
            previous[i] = head[h]
            head[h] = i
        }

        private fun hash(i: Int): Int =
            ((data[i].toInt() and 0xFF) shl 10 xor ((data[i + 1].toInt() and 0xFF) shl 5) xor (data[i + 2].toInt() and 0xFF)) and (HASH_SIZE - 1)

        private companion object {
            const val HASH_SIZE = 1 shl 15
            const val MAX_CHAIN = 128
        }
    }

    /** Bits least significant first, as DEFLATE packs them; Huffman codes go in most significant bit first. */
    private class BitWriter(capacity: Int) {
        private var out = ByteArray(capacity)
        private var size = 0
        private var buffer = 0
        private var count = 0

        fun byte(value: Int) {
            if (size == out.size) out = out.copyOf(out.size * 2)
            out[size++] = value.toByte()
        }

        fun bits(value: Int, n: Int) {
            buffer = buffer or (value shl count)
            count += n
            while (count >= 8) {
                byte(buffer and 0xFF)
                buffer = buffer ushr 8
                count -= 8
            }
        }

        fun alignToByte() {
            if (count > 0) byte(buffer and 0xFF)
            buffer = 0
            count = 0
        }

        /** A literal byte or the end-of-block symbol, in the fixed literal/length code. */
        fun literal(symbol: Int) {
            when {
                symbol < 144 -> code(0x30 + symbol, 8)
                symbol < 256 -> code(0x190 + symbol - 144, 9)
                symbol < 280 -> code(symbol - 256, 7)
                else -> code(0xC0 + symbol - 280, 8)
            }
        }

        fun match(length: Int, distance: Int) {
            val lengthSymbol = LENGTH_BASE.lastIndexOfAtMost(length)
            literal(257 + lengthSymbol)
            bits(length - LENGTH_BASE[lengthSymbol], LENGTH_EXTRA[lengthSymbol])
            val distanceSymbol = DISTANCE_BASE.lastIndexOfAtMost(distance)
            code(distanceSymbol, 5)
            bits(distance - DISTANCE_BASE[distanceSymbol], DISTANCE_EXTRA[distanceSymbol])
        }

        fun toByteArray(): ByteArray = out.copyOf(size)

        private fun code(code: Int, length: Int) {
            var reversed = 0
            for (i in 0 until length) reversed = reversed or ((code ushr i and 1) shl (length - 1 - i))
            bits(reversed, length)
        }

        private fun IntArray.lastIndexOfAtMost(value: Int): Int {
            var index = size - 1
            while (this[index] > value) index--
            return index
        }
    }
}

internal class ZlibException(message: String) : IllegalArgumentException(message)
