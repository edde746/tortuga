package com.edde746.tortuga.kids

import com.edde746.tortuga.fixtures.FixtureCorpus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** agerating's packed list read into an [AgeIndex], and the gzip it is published in. */
class AgeIndexTest {
    @Test
    fun aWrittenListReadsBackEveryTableAndAge() {
        val tables = listOf(
            "imdb" to listOf(0L to 0, 111161L to 12, 114709L to 0, 133093L to 16, 1109624L to 6, Int.MAX_VALUE.toLong() to 18),
            "tmdb_movie" to (0 until 500).map { 3L + it * 9_973L to SCALE[it % SCALE.size] },
            "tmdb_tv" to listOf(1399L to 16, 81994110L to 12),
            "tvdb_series" to listOf(121361L to 16),
            "tvdb_movie" to listOf(1L to 6, 1574171L to 18),
        )
        val index = AgeIndex.read(packAgeList(tables))

        for ((name, rows) in tables) {
            val table = AgeIdTable.entries.single { it.table == name }
            for ((id, age) in rows) assertEquals(age, index.age(table, id), "$name $id")
        }
        assertFalse(index.isEmpty())
        assertEquals("6 imdb, 500 tmdb_movie, 2 tmdb_tv, 1 tvdb_series, 2 tvdb_movie", index.summary())
    }

    @Test
    fun aTableOfAnotherNameIsSkippedAndOneTheListLacksHasNoRows() {
        val index = AgeIndex.read(packAgeList(listOf("imdb_episode" to listOf(5L to 18, 9L to 0), "imdb" to listOf(5L to 6))))
        assertEquals(6, index.age(AgeIdTable.IMDB, 5))
        assertNull(index.age(AgeIdTable.IMDB, 9))
        assertNull(index.age(AgeIdTable.TMDB_MOVIE, 5))
        assertTrue(AgeIndex.read(packAgeList(listOf("imdb" to emptyList()))).isEmpty())
    }

    @Test
    fun theFirstAndLastIdsAreFoundAndAbsentOnesAreNot() {
        val index = AgeIndex.read(packAgeList(listOf("imdb" to listOf(10L to 0, 20L to 6, 30L to 12), "tmdb_tv" to listOf(7L to 18))))
        assertEquals(0, index.age(AgeIdTable.IMDB, 10), "the first id")
        assertEquals(12, index.age(AgeIdTable.IMDB, 30), "the last id")
        assertEquals(18, index.age(AgeIdTable.TMDB_TV, 7), "a table's only id")
        for (absent in listOf(0L, 9L, 15L, 31L, -10L, Int.MAX_VALUE + 10L, Long.MAX_VALUE)) {
            assertNull(index.age(AgeIdTable.IMDB, absent), "$absent")
        }
        assertNull(index.age(AgeIdTable.TMDB_MOVIE, 10), "the id in another table")
    }

    @Test
    fun aFileThatIsntAVersionOnePackedListIsRejected() {
        val good = packAgeList(listOf("imdb" to listOf(1L to 0, 2L to 6), "tmdb_tv" to listOf(3L to 12)))
        // Two ids of three bytes each, then their two ages, after a 15-byte header.
        val wide = packAgeList(listOf("imdb" to listOf(100_000L to 0, 200_000L to 6)))
        AgeIndex.read(good)
        AgeIndex.read(wide)
        val rejected = mapOf(
            "another magic" to good.copyOf().also { it[0] = 'X'.code.toByte() },
            "version 2" to good.copyOf().also { it[4] = 2 },
            "shorter than its header" to good.copyOf(5),
            "more tables than it holds" to good.copyOf().also { it[5] = 3 },
            "cut in a table's name" to good.copyOf(8),
            "more rows than fit" to packAgeList(listOf("imdb" to listOf(1L to 0)), rows = mapOf("imdb" to 4L)),
            "a row count past 2^31" to packAgeList(listOf("imdb" to listOf(1L to 0)), rows = mapOf("imdb" to 0xFFFF_FFFFL)),
            "cut in its ids" to wide.copyOf(19),
            "cut in its ages" to wide.copyOf(wide.size - 1),
            "bytes after its last table" to good + byteArrayOf(0),
            "a repeated id" to packAgeList(listOf("imdb" to listOf(1L to 0)), gaps = mapOf("imdb" to listOf(1L, 0L))),
            "an id past Int.MAX_VALUE" to packAgeList(listOf("imdb" to listOf(1L to 0)), gaps = mapOf("imdb" to listOf(Int.MAX_VALUE.toLong(), 1L))),
            "a varint longer than an Int's" to packAgeList(listOf("imdb" to listOf(1L to 0)), gaps = mapOf("imdb" to listOf(1L shl 40))),
            "an age off the scale" to packAgeList(listOf("imdb" to listOf(1L to 7))),
            "a table twice" to packAgeList(listOf("imdb" to listOf(1L to 0), "imdb" to listOf(2L to 6))),
            "ids out of order in a skipped table" to packAgeList(listOf("imdb_episode" to listOf(1L to 0)), gaps = mapOf("imdb_episode" to listOf(5L, 0L))),
        )
        for ((case, bytes) in rejected) assertFailsWith<IllegalArgumentException>(case) { AgeIndex.read(bytes) }
    }

    /**
     * The fixture `fixtures/kids/agerating-ids.bin.gz.hex` (`xxd -p -c 15` of the file) is agerating's own writer's
     * output: `pipeline/pack_ids.py --check` (agerating 935017c) over a small database with [FIXTURE]'s rows, gzipped
     * with `gzip -9 -n` as its weekly release does.
     */
    @Test
    fun theListAgeratingsOwnWriterMadeReadsBack() {
        val index = AgeIndex.read(gunzip(fixture()))
        for ((table, rows) in FIXTURE) for ((id, age) in rows) assertEquals(age, index.age(table, id), "${table.table} $id")
        assertEquals("7 imdb, 403 tmdb_movie, 3 tmdb_tv, 3 tvdb_series, 0 tvdb_movie", index.summary())
        assertNull(index.age(AgeIdTable.IMDB, 2))
        assertNull(index.age(AgeIdTable.TVDB_MOVIE, 1))
    }

    @Test
    fun aDamagedOrCutGzipIsRejected() {
        val packed = fixture()
        assertEquals(1509, gunzip(packed).size)
        val rejected = mapOf(
            "not gzip" to packAgeList(listOf("imdb" to (1L..20L).map { it to 0 })),
            "cut short" to packed.copyOf(packed.size - 9) + packed.copyOfRange(packed.size - 4, packed.size),
            "its CRC wrong" to packed.copyOf().also { it[packed.size - 8] = (it[packed.size - 8] + 1).toByte() },
            "a second member" to packed + packed,
            "too short for gzip" to packed.copyOf(10),
        )
        for ((case, bytes) in rejected) assertFails(case) { gunzip(bytes) }
    }

    private fun fixture(): ByteArray =
        FixtureCorpus.text("kids/agerating-ids.bin.gz.hex").filterNot { it.isWhitespace() }.hexToByteArray()

    private companion object {
        val SCALE = intArrayOf(0, 6, 12, 16, 18)

        val FIXTURE: Map<AgeIdTable, List<Pair<Long, Int>>> = mapOf(
            AgeIdTable.IMDB to listOf(1L to 0, 111161L to 12, 114709L to 0, 133093L to 16, 1109624L to 6, 64439064L to 18, 2147483647L to 12),
            AgeIdTable.TMDB_MOVIE to listOf(2L to 0, 603L to 16, 8202927L to 18) + (0 until 400).map { k -> 10_000L + k.toLong() * k * 37 to SCALE[k % 5] },
            AgeIdTable.TMDB_TV to listOf(1L to 6, 1399L to 16, 81994110L to 12),
            AgeIdTable.TVDB_SERIES to listOf(8450L to 6, 121361L to 16, 8207351L to 18),
            AgeIdTable.TVDB_MOVIE to emptyList(),
        )
    }
}

/**
 * A packed list as agerating's `pipeline/pack_ids.py` writes it: [tables] in their order, each row's id as its gap from
 * the previous one. [rows] overrides a table's row count and [gaps] its gaps as written, for files that break the format.
 */
internal fun packAgeList(
    tables: List<Pair<String, List<Pair<Long, Int>>>>,
    rows: Map<String, Long> = emptyMap(),
    gaps: Map<String, List<Long>> = emptyMap(),
): ByteArray {
    val out = ArrayList<Byte>()
    "AGID".encodeToByteArray().forEach { out += it }
    out += 1.toByte()
    out += tables.size.toByte()
    for ((name, table) in tables) {
        val ascii = name.encodeToByteArray()
        out += ascii.size.toByte()
        ascii.forEach { out += it }
        val written = gaps[name] ?: table.mapIndexed { i, (id, _) -> if (i == 0) id else id - table[i - 1].first }
        val count = rows[name] ?: written.size.toLong()
        for (shift in 0 until 32 step 8) out += (count shr shift).toByte()
        for (gap in written) {
            var n = gap
            while (n > 0x7F) {
                out += (n and 0x7F or 0x80).toByte()
                n = n ushr 7
            }
            out += n.toByte()
        }
        // The ages follow the ids as written: one per gap.
        written.indices.forEach { out += (table.getOrNull(it)?.second ?: table.last().second).toByte() }
    }
    return out.toByteArray()
}
