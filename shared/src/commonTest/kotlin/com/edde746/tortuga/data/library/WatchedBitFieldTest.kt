package com.edde746.tortuga.data.library

import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Stremio's watched bitfield against stremio-core's own tests (`stremio-watched-bitfield/src/{watched_bitfield,
 * bitfield8}.rs`, commit 88be65b), and the zlib codec against streams written by flate2 (those vectors) and by
 * CPython's zlib 1.3 (stored, fixed-Huffman and dynamic-Huffman blocks).
 */
class WatchedBitFieldTest {
    private fun b64(text: String) = Base64.Default.decode(text)

    // stremio-core watched_bitfield.rs

    @Test
    fun parseAndModify() {
        val videos = (1..9).map { "tt2934286:1:$it" }
        val watched = "tt2934286:1:5:5:eJyTZwAAAEAAIA=="
        val field = WatchedBitField.parse(watched, videos)
        assertTrue(field.isWatched("tt2934286:1:5"))
        assertFalse(field.isWatched("tt2934286:1:6"))
        assertEquals(watched, field.serialize())
        field.set("tt2934286:1:6", true)
        assertTrue(field.isWatched("tt2934286:1:6"))
    }

    @Test
    fun constructFromArray() {
        val videos = (1 until 500).map { "tt2934286:1:$it" }
        val field = WatchedBitField.empty(videos)
        assertTrue(videos.none(field::isWatched))
        videos.forEachIndexed { i, id -> field.set(id, i % 2 == 0) }
        val again = WatchedBitField.parse(field.serialize(), videos)
        videos.forEachIndexed { i, id -> assertEquals(i % 2 == 0, again.isWatched(id), id) }
    }

    @Test
    fun toStringEmpty() {
        assertEquals("undefined:1:eJwDAAAAAAE=", WatchedBitField.empty(emptyList()).serialize())
    }

    @Test
    fun watchedFieldDeserializeAndSerialize() {
        val field = "tt7767422:3:8:24:eJz7//8/AAX9Av4="
        val videos = (1..24).map { "tt7767422:3:$it" }
        // The anchor `tt7767422:3:8` is video 8 of 24, and 24 bits are set: shifted so the anchor's bit lands on it.
        val parsed = WatchedBitField.parse(field, videos)
        assertEquals((1..8).map { true } + (9..24).map { false }, videos.map(parsed::isWatched))
        assertEquals("eJz7//8/AAX9Av4=", BitField8.decode("eJz7//8/AAX9Av4=", length = null).encode())
    }

    @Test
    fun deserializeEmpty() {
        val field = WatchedBitField.parse("undefined:1:eJwDAAAAAAE=", emptyList())
        assertEquals("undefined:1:eJwDAAAAAAE=", field.serialize())
    }

    @Test
    fun anchorPointsToTheLastWatchedVideo() {
        val videos = (1..20).map { "tt0:1:$it" }
        val field = WatchedBitField.empty(videos)
        field.set("tt0:1:16", true)
        assertTrue(field.serialize().startsWith("tt0:1:16:16:"), field.serialize())
    }

    @Test
    fun marksUpToTheAnchorStayWhenVideosBeforeItAreGone() {
        val videos = (1..16).map { "tt0:1:$it" }
        val field = WatchedBitField.empty(videos)
        videos.takeLast(4).forEach { field.set(it, true) }
        val fewer = videos.drop(2)
        val parsed = WatchedBitField.parse(field.serialize(), fewer)
        assertEquals(fewer.takeLast(4), fewer.filter(parsed::isWatched))
    }

    @Test
    fun anAnchorLengthPastTheBitsIsNoWatchedField() {
        val oneByte = BitField8(8).also { it[0] = true }.encode()
        assertEquals(8, WatchedField.parse("tt0:1:1:8:$oneByte").anchorLength)
        assertFailsWith<IllegalArgumentException> { WatchedField.parse("tt0:1:1:${Int.MAX_VALUE}:$oneByte") }
        assertFailsWith<IllegalArgumentException> { WatchedField.parse("tt0:1:1:9:$oneByte") }
    }

    // stremio-core bitfield8.rs

    @Test
    fun bitField8DecodesAndEncodes() {
        val bits = BitField8.decode("eJyTZwAAAEAAIA==", length = null)
        assertEquals(16, bits.length)
        assertContentEquals(byteArrayOf(31, 0), bits.values)
        assertEquals("eJyTZwAAAEAAIA==", bits.encode())
        assertEquals(9, BitField8.decode("eJyTZwAAAEAAIA==", length = 9).length)
    }

    @Test
    fun bitField8UsesBitLength() {
        assertEquals(20, BitField8(20).length)
        val bits = BitField8(20)
        bits[15] = true
        assertEquals(15, bits.lastIndexOf(true))
    }

    // Beyond stremio-core's tests: what a series needs.

    @Test
    fun marksFollowTheAnchorWhenVideosAreAddedBeforeIt() {
        val before = (1..5).map { "tt1:1:$it" }
        val field = WatchedBitField.empty(before)
        listOf("tt1:1:1", "tt1:1:2", "tt1:1:4").forEach { field.set(it, true) }
        // Two specials appear, ordered first (season 0), and a new episode at the end.
        val after = listOf("tt1:0:1", "tt1:0:2") + before + "tt1:1:6"
        val shifted = WatchedBitField.parse(field.serialize(), after)
        assertEquals(listOf(false, false, true, true, false, true, false, false), after.map(shifted::isWatched))
    }

    @Test
    fun anUnknownAnchorOrUnreadableTextMeansNothingWatched() {
        val meta = meta(listOf(video("tt1:1:1", 1, 1), video("tt1:1:2", 1, 2)))
        assertFalse(WatchedBitField.of("tt9:1:1:1:eJwDAAAAAAE=", meta).isWatched("tt1:1:1"))
        assertFalse(WatchedBitField.of("not a field", meta).isWatched("tt1:1:1"))
        assertFalse(WatchedBitField.of("tt1:1:1:1:!!!", meta).isWatched("tt1:1:1"))
        assertFalse(WatchedBitField.of(null, meta).isWatched("tt1:1:1"))
    }

    @Test
    fun bitOrderIsSeasonEpisodeReleaseWithMissingValuesFirst() {
        val day = 86_400_000L
        val videos = listOf(
            video("s1e2", 1, 2),
            video("s0e1", 0, 1),
            video("s1e1", 1, 1),
            Video(id = "extra-late", released = Instant.fromEpochMilliseconds(2 * day)),
            Video(id = "extra-early", released = Instant.fromEpochMilliseconds(day)),
            Video(id = "extra-undated"),
        )
        assertEquals(listOf("extra-undated", "extra-early", "extra-late", "s0e1", "s1e1", "s1e2"), meta(videos).watchedOrder)
    }

    // zlib

    @Test
    fun inflatesStoredFixedAndDynamicBlocks() {
        assertContentEquals(ByteArray(40) { it.toByte() }, Zlib.inflate(b64("eAEBKADX/wABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicpzAMN")))
        assertContentEquals(run(), Zlib.inflate(b64("eAH7/x8JMIAAPwCoeQ0D")))
        assertContentEquals(ByteArray(300) { 0xAA.toByte() }, Zlib.inflate(b64("eAFbtWoUEAsAJnPHOQ==")))
        val text = words()
        assertEquals(1128, text.size)
        assertContentEquals(
            text,
            Zlib.inflate(
                b64(
                    "eNp1U9FuAyEM+xV+jfayFaktE9BV/fs9xM4U63jiEpzYcbj6vN76KNWPe7uMOj5lrmH1UeynzX5YubT11ex+lHdd15sd5bcd1su0OvuzrD7W67siGdhh8/WwKEFLMjiYURSxF0/o2ukBB/OAiS6AcMnIMbuOFMZ70ZNlbaeQe3AKBQ2SNMBk8igY4gMU2U6qlhU4CB2jg2fVeNYArV5mh2U955MwVAbnZ60azliekRflzZIA2TRtvJC8dkD/3fRrNKQGVS6vTv6dzXqBYpinDyOzr6FLm5FaqvH801+ghHnEcznZ19NDPNgqZuIP/Bmt5g==",
                ),
            ),
        )
        // A dynamic block, an empty stored block (a full flush), then a fixed block.
        assertContentEquals(
            text.copyOf(300) + ByteArray(256) { it.toByte() },
            Zlib.inflate(
                b64(
                    "eNp0jlsOg0AIRbfC1tC5VRKVBrBNd9/Egfkw8YsA5z74mFc14j42mYztRx4G3glvcW2gSeIl2Bp9OeYVjT7SoORg14NCLc6F8zhYg587hiQtK6HDtQ1RedXMXk99MqPuid16JZTP2jrz5FjF6n95/QEAAP//AQAB//4AAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl9gYWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1+f4CBgoOEhYaHiImKi4yNjo+QkZKTlJWWl5iZmpucnZ6foKGio6SlpqeoqaqrrK2ur7CxsrO0tba3uLm6u7y9vr/AwcLDxMXGx8jJysvMzc7P0NHS09TV1tfY2drb3N3e3+Dh4uPk5ebn6Onq6+zt7u/w8fLz9PX29/j5+vv8/f7/ZDzyCQ==",
                ),
            ),
        )
    }

    @Test
    fun deflateWritesStreamsStremioAndZlibRead() {
        // flate2's output for literal-only input (stremio-core's vectors), byte for byte.
        assertEquals("eJwDAAAAAAE=", Base64.Default.encode(Zlib.deflate(ByteArray(0))))
        assertEquals("eJyTZwAAAEAAIA==", Base64.Default.encode(Zlib.deflate(byteArrayOf(31, 0))))
        assertEquals("eJz7//8/AAX9Av4=", Base64.Default.encode(Zlib.deflate(byteArrayOf(-1, -1, -1))))
        // With length/distance matches: each expected stream was checked with CPython's zlib.decompress.
        for ((input, stream) in listOf(
            run() to "eJz7jwwYQIAfAKh5DQM=",
            ByteArray(63) { 0x55 } to "eJwLpQwAAJ29FOw=",
            ByteArray(300) { 0xAA.toByte() } to "eJxbNQqIBgAmc8c5",
            ByteArray(30) { (it % 3 + 1).toByte() } to "eJxjZGLGgwADrAA9",
        )) {
            assertEquals(stream, Base64.Default.encode(Zlib.deflate(input)))
        }
    }

    @Test
    fun deflateRoundTrips() {
        var x = 7L
        val noisy = ByteArray(5000) {
            x = (x * 6364136223846793005L + 1442695040888963407L)
            (x ushr 60).toByte()
        }
        for (input in listOf(words(), noisy, ByteArray(70_000) { (it / 1000).toByte() }, ByteArray(1) { 7 })) {
            assertContentEquals(input, Zlib.inflate(Zlib.deflate(input)))
        }
    }

    @Test
    fun corruptStreamsAreRejected() {
        assertFailsWith<ZlibException> { Zlib.inflate(b64("eJyTZwAAAEAAIQ==")) } // Adler-32 off by one bit
        assertFailsWith<ZlibException> { Zlib.inflate(b64("eJyTZwAAAEAA")) } // truncated trailer
        assertFailsWith<ZlibException> { Zlib.inflate(b64("eJyTZw==")) }
        assertFailsWith<ZlibException> { Zlib.inflate(byteArrayOf(0x78, 0x9D.toByte(), 0x03, 0x00, 0, 0, 0, 1)) } // header check
        assertFailsWith<ZlibException> { Zlib.inflate(byteArrayOf(0x78, 0x9C.toByte(), 0x07, 0x00, 0, 0, 0, 1)) } // block type 3
        assertFailsWith<ZlibException> { Zlib.inflate(Zlib.deflate(ByteArray(4096)), maxOutput = 4095) }
    }

    private fun run() = ByteArray(13) { -1 } + ByteArray(5) + byteArrayOf(0x0F)

    /** 150 words picked by an LCG, the text CPython compressed into the dynamic-block vectors. */
    private fun words(): ByteArray {
        val words = listOf("watched", "episode", "season", "library", "resume", "stream", "tortuga", "bitfield", "anchor", "video")
        var x = 1L
        return List(150) {
            x = (x * 1103515245 + 12345) % (1L shl 31)
            words[((x shr 16) % 10).toInt()]
        }.joinToString(" ").encodeToByteArray()
    }

    private fun video(id: String, season: Int, episode: Int) = Video(id = id, seriesInfo = SeriesInfo(season, episode))

    private fun meta(videos: List<Video>) = MetaItem(MetaItemPreview(id = "tt1", type = "series", name = "Show"), videos)
}
