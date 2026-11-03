package com.edde746.tortuga.kids

/**
 * agerating's packed list (`agerating-ids.bin`) in memory: per [AgeIdTable] its ids, sorted, in an [IntArray] and
 * their ages in a [ByteArray] at the same places, looked up by binary search. About a million rows take about 4 MB of
 * ids and 1 MB of ages, against tens of MB for maps of boxed numbers. Every id agerating publishes fits an [Int]
 * (the largest, a TMDB series, is under 100 million); a file with a larger one is rejected.
 */
internal class AgeIndex private constructor(private val ids: Array<IntArray>, private val ages: Array<ByteArray>) : AgeLookup {
    override fun age(table: AgeIdTable, id: Long): Int? {
        if (id < 0 || id > Int.MAX_VALUE) return null
        val ids = ids[table.ordinal]
        val key = id.toInt()
        var low = 0
        var high = ids.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val at = ids[mid]
            when {
                at < key -> low = mid + 1
                at > key -> high = mid - 1
                else -> return ages[table.ordinal][mid].toInt()
            }
        }
        return null
    }

    /** True when no table has a row. */
    fun isEmpty(): Boolean = ids.all { it.isEmpty() }

    fun summary(): String = AgeIdTable.entries.joinToString { "${ids[it.ordinal].size} ${it.table}" }

    companion object {
        /**
         * The packed list [bytes] (unpacked), as agerating's `pipeline/pack_ids.py` writes it (integers little-endian,
         * varints LEB128):
         * - `AGID`, the format version (u8, 1), the number of tables (u8), then per table:
         * - its name's length (u8) and its name in ASCII (`imdb`, `tmdb_movie`, `tmdb_tv`, `tvdb_series`, `tvdb_movie`),
         *   its number of rows (u32), one varint per row (the ids in ascending order, each as its gap from the previous
         *   one, the first from 0), and one byte per row (the ages, in the same order: 0, 6, 12, 16 or 18).
         *
         * A table whose name isn't an [AgeIdTable] is skipped; a table the file lacks has no rows.
         *
         * @throws IllegalArgumentException when [bytes] isn't such a list: another magic or version, a table or its rows
         *   past the end, bytes after the last table, a table twice, ids out of order or past [Int.MAX_VALUE], or an
         *   age off the scale.
         */
        fun read(bytes: ByteArray): AgeIndex {
            require(bytes.size >= HEADER_BYTES && MAGIC.indices.all { bytes[it] == MAGIC[it] }) { "not an agerating packed list" }
            require(bytes[4].toInt() == VERSION) { "packed list version ${bytes[4].toInt() and 0xFF}, not $VERSION" }
            val tables = bytes[5].toInt() and 0xFF
            val ids = arrayOfNulls<IntArray>(AgeIdTable.entries.size)
            val ages = arrayOfNulls<ByteArray>(AgeIdTable.entries.size)
            var p = HEADER_BYTES
            repeat(tables) {
                require(p < bytes.size) { "the packed list ends before its table ${it + 1} of $tables" }
                val nameLength = bytes[p].toInt() and 0xFF
                require(p + 1 + nameLength + 4 <= bytes.size) { "the packed list ends in its table ${it + 1}'s name" }
                val name = bytes.decodeToString(p + 1, p + 1 + nameLength)
                p += 1 + nameLength
                val rows = (bytes[p].toLong() and 0xFF) or ((bytes[p + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[p + 2].toLong() and 0xFF) shl 16) or ((bytes[p + 3].toLong() and 0xFF) shl 24)
                p += 4
                // Each row takes at least a byte of id and one of age.
                require(rows * 2 <= bytes.size - p) { "$name: $rows rows don't fit the ${bytes.size - p} bytes left" }
                val count = rows.toInt()
                val table = AgeIdTable.entries.firstOrNull { entry -> entry.table == name }
                val tableIds = if (table != null) IntArray(count) else null
                var previous = -1L
                for (row in 0 until count) {
                    var gap = 0L
                    var shift = 0
                    while (true) {
                        require(p < bytes.size) { "$name: the packed list ends in its ids" }
                        require(shift < MAX_VARINT_SHIFT) { "$name: an id past ${Int.MAX_VALUE}" }
                        val b = bytes[p++].toInt()
                        gap = gap or ((b and 0x7F).toLong() shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    val id = if (row == 0) gap else previous + gap
                    require(row == 0 || gap > 0) { "$name: id $id out of order" }
                    require(id <= Int.MAX_VALUE) { "$name: id $id past ${Int.MAX_VALUE}" }
                    tableIds?.set(row, id.toInt())
                    previous = id
                }
                require(count <= bytes.size - p) { "$name: the packed list ends in its ages" }
                for (i in p until p + count) {
                    val age = bytes[i].toInt()
                    require(age in 0..18 && (AGES shr age) and 1 == 1) { "$name: age $age isn't 0, 6, 12, 16 or 18" }
                }
                if (table != null) {
                    require(ids[table.ordinal] == null) { "$name: the table twice" }
                    ids[table.ordinal] = tableIds
                    ages[table.ordinal] = bytes.copyOfRange(p, p + count)
                }
                p += count
            }
            require(p == bytes.size) { "${bytes.size - p} bytes after the packed list's last table" }
            return AgeIndex(Array(ids.size) { ids[it] ?: IntArray(0) }, Array(ages.size) { ages[it] ?: ByteArray(0) })
        }

        private val MAGIC = "AGID".encodeToByteArray()
        private const val VERSION = 1
        private const val HEADER_BYTES = 6

        /** An id's varint takes at most 5 bytes (35 bits); more can't be an [Int]. */
        private const val MAX_VARINT_SHIFT = 35

        /** The scale's ages as bits: 0, 6, 12, 16 and 18. */
        private const val AGES = 1 or (1 shl 6) or (1 shl 12) or (1 shl 16) or (1 shl 18)
    }
}
