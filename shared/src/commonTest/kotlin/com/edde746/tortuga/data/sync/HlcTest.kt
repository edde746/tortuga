package com.edde746.tortuga.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HlcTest {
    private val a = "00000000000000aa"
    private val b = "00000000000000bb"

    // Ordered by wall time, then counter, then node; includes values whose decimal/hex widths differ.
    private val ordered = listOf(
        Hlc(0, 0, a),
        Hlc(9, 0, a),
        Hlc(10, 0, a),
        Hlc(10, 1, a),
        Hlc(10, 1, b),
        Hlc(10, 255, a),
        Hlc(10, 256, a),
        Hlc(10, Hlc.MAX_COUNTER, a),
        Hlc(11, 0, a),
        Hlc(1_790_000_000_000, 3, a),
        Hlc(1_790_000_000_001, 0, a),
        Hlc(Hlc.MAX_WALL_MILLIS, Hlc.MAX_COUNTER, b),
    )

    @Test
    fun textFormSortsInClockOrder() {
        assertEquals(ordered, ordered.shuffled().sorted())
        assertEquals(ordered.map { it.toString() }, ordered.map { it.toString() }.shuffled().sorted())
        ordered.forEach { assertEquals(34, it.toString().length) }
    }

    @Test
    fun textRoundTrips() {
        ordered.forEach { assertEquals(it, Hlc.parse(it.toString())) }
        assertEquals("01a0c4506c00-0003-00000000000000aa", Hlc(1_790_000_000_000, 3, a).toString())
    }

    @Test
    fun rejectsMalformedText() {
        listOf(
            "",
            "01a0c4506c00-0003-00000000000000a",
            "01a0c4506c00-0003-00000000000000AA",
            "01a0c4506c00_0003_00000000000000aa",
            "01a0c3f3c80g-0003-00000000000000aa",
            "-1a0c3f3c800-0003-00000000000000aa",
            "ffffffffffff-0000-00000000000000aa",
        ).forEach { text -> assertFailsWith<IllegalArgumentException>(text) { Hlc.parse(text) } }
    }

    @Test
    fun rejectsOutOfRangeParts() {
        assertFailsWith<IllegalArgumentException> { Hlc(-1, 0, a) }
        assertFailsWith<IllegalArgumentException> { Hlc(Hlc.MAX_WALL_MILLIS + 1, 0, a) }
        assertFailsWith<IllegalArgumentException> { Hlc(0, Hlc.MAX_COUNTER + 1, a) }
        assertFailsWith<IllegalArgumentException> { Hlc(0, 0, "node") }
    }

    @Test
    fun newNodeIdsAreValidAndDistinct() {
        val ids = List(100) { Hlc.newNodeId() }
        ids.forEach { Hlc(0, 0, it) }
        assertEquals(ids.size, ids.toSet().size)
    }
}
