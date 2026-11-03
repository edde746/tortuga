package com.edde746.tortuga.trackers.ids

import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.kids.gunzip

/**
 * Writes anime-mappings' packed list (unpacked) as its README's "The format" lays it out, for tests: [file] of
 * [table]s of [column]s, each column's data from the type's own writer ([ints], [intDeltas], [strs], [dict],
 * [intLists], [strLists]; `null` for an absent value).
 */
internal object PackedList {
    fun file(vararg tables: ByteArray, magic: String = "AMAP", version: Int = 1): ByteArray =
        magic.encodeToByteArray() + byteArrayOf(version.toByte()) + varint(tables.size.toLong()) + tables.join()

    fun table(name: String, rows: Int, vararg columns: ByteArray): ByteArray =
        string(name) + varint(rows.toLong()) + varint(columns.size.toLong()) + columns.join()

    fun column(name: String, type: Int, data: ByteArray): ByteArray =
        string(name) + byteArrayOf(type.toByte()) + varint(data.size.toLong()) + data

    fun ints(vararg values: Long?): ByteArray = values.map { v -> v?.let { varint(zigzag(it) + 1) } ?: absent }.join()

    fun intDeltas(vararg values: Long?): ByteArray {
        var previous = 0L
        return values.map { v ->
            if (v == null) return@map absent
            varint(zigzag(v - previous) + 1).also { previous = v }
        }.join()
    }

    fun strs(vararg values: String?): ByteArray =
        values.map { v -> v?.encodeToByteArray()?.let { varint(it.size + 1L) + it } ?: absent }.join()

    /** The dictionary in the order the values first appear. */
    fun dict(vararg values: String?): ByteArray {
        val words = values.filterNotNull().distinct()
        return varint(words.size.toLong()) + words.map(::string).join() +
            values.map { v -> v?.let { varint(words.indexOf(it) + 1L) } ?: absent }.join()
    }

    fun intLists(vararg values: List<Long>?): ByteArray =
        values.map { v -> v?.let { list -> varint(list.size + 1L) + list.map { varint(zigzag(it)) }.join() } ?: absent }.join()

    fun strLists(vararg values: List<String>?): ByteArray =
        values.map { v -> v?.let { list -> varint(list.size + 1L) + list.map(::string).join() } ?: absent }.join()

    fun string(s: String): ByteArray = s.encodeToByteArray().let { varint(it.size.toLong()) + it }

    fun varint(n: Long): ByteArray {
        val out = ArrayList<Byte>()
        var rest = n
        while (rest ushr 7 != 0L) {
            out += ((rest and 0x7F) or 0x80).toByte()
            rest = rest ushr 7
        }
        out += rest.toByte()
        return out.toByteArray()
    }

    private fun zigzag(n: Long): Long = (n shl 1) xor (n shr 63)

    private val absent = byteArrayOf(0)

    private fun Array<out ByteArray>.join(): ByteArray = toList().join()

    private fun List<ByteArray>.join(): ByteArray = fold(ByteArray(0)) { acc, b -> acc + b }
}

/** `fixtures/trackers/anime-mappings.excerpt.bin.gz.hex`, as released (gzipped); its source: [AnimeMappingIndexTest]. */
internal fun animeMappingsExcerpt(): ByteArray =
    FixtureCorpus.text("trackers/anime-mappings.excerpt.bin.gz.hex").filterNot { it.isWhitespace() }.hexToByteArray()

/** [animeMappingsExcerpt]'s indexes. */
internal val ANIME_MAPPINGS_EXCERPT: AnimeMappingIndexes by lazy { AnimeMappings.read(gunzip(animeMappingsExcerpt())) }
