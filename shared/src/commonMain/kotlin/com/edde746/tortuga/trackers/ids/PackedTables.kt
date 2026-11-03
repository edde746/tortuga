package com.edde746.tortuga.trackers.ids

/**
 * anime-mappings' packed list, unpacked (https://github.com/edde746/anime-mappings, its README's "The format"): `AMAP`,
 * the format version, then tables of named, typed columns, each column's data after its length in bytes. Numbers are
 * LEB128 varints; every value starts with a tag, 0 for an absent one.
 *
 * [read] checks the layout (the magic, the version, every length within the file, nothing after the last table) and
 * keeps each column's place; a column's values are decoded only when asked for, so the columns Tortuga doesn't read,
 * of whatever type, are skipped by their length.
 */
internal class PackedTables private constructor(private val tables: Map<String, Table>) {
    /** The table [name]; throws [IllegalArgumentException] when the file has none. */
    fun table(name: String): Table = tables[name] ?: throw IllegalArgumentException("the packed list has no table $name")

    class Table internal constructor(
        val name: String,
        val rows: Int,
        private val bytes: ByteArray,
        private val columns: Map<String, Column>,
    ) {
        /** An `int` or `int_delta` column: [NONE] for an absent value and for one outside [Int]'s range. */
        fun ints(name: String): IntArray {
            val column = column(name, INT, INT_DELTA)
            val r = column.reader()
            val out = IntArray(rows)
            var previous = 0L
            for (i in 0 until rows) {
                val tag = r.varint()
                out[i] = if (tag == 0L) {
                    NONE
                } else {
                    val v = unzigzag(tag - 1).let { if (column.type == INT_DELTA) previous + it else it }
                    if (column.type == INT_DELTA) previous = v
                    if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else NONE
                }
            }
            r.end()
            return out
        }

        /** A `str` or `dict` column: null for an absent value. */
        fun strings(name: String): Array<String?> {
            val column = column(name, STR, DICT)
            val r = column.reader()
            val words = if (column.type == DICT) Array(r.count()) { r.string() } else null
            val out = arrayOfNulls<String>(rows)
            for (i in 0 until rows) {
                val tag = r.varint()
                if (tag == 0L) continue
                out[i] = if (words != null) {
                    words.getOrNull((tag - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        ?: throw IllegalArgumentException("$name: dictionary entry ${tag - 1} of ${words.size}")
                } else {
                    r.string(r.length(tag - 1))
                }
            }
            r.end()
            return out
        }

        /** An `int_list` column: null for an absent list; an item outside [Int]'s range is left out. */
        fun intLists(name: String): Array<IntArray?> {
            val r = column(name, INT_LIST).reader()
            val out = arrayOfNulls<IntArray>(rows)
            for (i in 0 until rows) {
                val tag = r.varint()
                if (tag == 0L) continue
                val items = IntArray(r.length(tag - 1))
                var n = 0
                repeat(items.size) {
                    val v = unzigzag(r.varint())
                    if (v in Int.MIN_VALUE..Int.MAX_VALUE) items[n++] = v.toInt()
                }
                out[i] = if (n == items.size) items else items.copyOf(n)
            }
            r.end()
            return out
        }

        /** A `str_list` column: null for an absent list. */
        fun stringLists(name: String): Array<List<String>?> {
            val r = column(name, STR_LIST).reader()
            val out = arrayOfNulls<List<String>>(rows)
            for (i in 0 until rows) {
                val tag = r.varint()
                if (tag == 0L) continue
                out[i] = List(r.length(tag - 1)) { r.string() }
            }
            r.end()
            return out
        }

        private fun column(name: String, vararg types: Int): Column {
            val column = columns[name] ?: throw IllegalArgumentException("${this.name} has no column $name")
            require(column.type in types) { "${this.name}.$name is of type ${column.type}, not one of ${types.toList()}" }
            // Every row takes at least its tag's byte: a shorter column is damaged, and isn't decoded.
            require(column.end - column.start >= rows) { "${this.name}.$name is shorter than its $rows rows" }
            return column
        }

        private fun Column.reader() = Reader(bytes, start, end)
    }

    class Column internal constructor(val type: Int, val start: Int, val end: Int)

    /** The bytes from [p] to [end], read in turn; throws [IllegalArgumentException] past [end]. */
    private class Reader(private val bytes: ByteArray, var p: Int, private val end: Int) {
        fun byte(): Int {
            require(p < end) { "the packed list is cut short" }
            return bytes[p++].toInt() and 0xFF
        }

        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                val b = byte()
                require(shift < 64 && (shift < 63 || b <= 1)) { "a varint longer than 64 bits" }
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return value
                shift += 7
            }
        }

        /** A count of what follows, each at least a byte. */
        fun count(): Int = length(varint())

        fun length(n: Long): Int {
            require(n >= 0 && n <= end - p) { "the packed list is cut short" }
            return n.toInt()
        }

        fun string(): String = string(count())

        fun string(length: Int): String {
            require(length <= end - p) { "the packed list is cut short" }
            return bytes.decodeToString(p, p + length).also { p += length }
        }

        /** The column's data must end where its length said. */
        fun end() = require(p == end) { "a column has ${end - p} bytes after its last row" }
    }

    companion object {
        /** The value of an absent number in [Table.ints]. */
        const val NONE: Int = Int.MIN_VALUE

        const val INT = 1
        const val INT_DELTA = 2
        const val STR = 3
        const val DICT = 4
        const val INT_LIST = 5
        const val STR_LIST = 6

        /** `AMAP`. */
        private val MAGIC = byteArrayOf(0x41, 0x4D, 0x41, 0x50)
        private const val VERSION = 1

        /** The packed list in [bytes] (unpacked); throws [IllegalArgumentException] for anything else. */
        fun read(bytes: ByteArray): PackedTables {
            require(bytes.size >= 5 && (0 until 4).all { bytes[it] == MAGIC[it] }) { "not an anime-mappings packed list" }
            require(bytes[4].toInt() == VERSION) { "the packed list's version is ${bytes[4]}, not $VERSION" }
            val r = Reader(bytes, 5, bytes.size)
            val tables = HashMap<String, Table>()
            repeat(r.count()) {
                val name = r.string()
                val rows = r.varint()
                require(rows <= Int.MAX_VALUE) { "$name: $rows rows" }
                val columns = HashMap<String, Column>()
                repeat(r.count()) {
                    val column = r.string()
                    val type = r.byte()
                    val length = r.count()
                    columns[column] = Column(type, r.p, r.p + length)
                    r.p += length
                }
                tables[name] = Table(name, rows.toInt(), bytes, columns)
            }
            require(r.p == bytes.size) { "the packed list has ${bytes.size - r.p} bytes after its last table" }
            return PackedTables(tables)
        }

        private fun unzigzag(n: Long): Long = (n ushr 1) xor -(n and 1)
    }
}
