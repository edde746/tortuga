package com.edde746.tortuga.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DevProgressTest {
    @Test
    fun theTimesComeLastAfterTheVideo() {
        assertEquals(DevProgress("movie", "tt0111161", "tt0111161", 2400.0, 8520.0), DevProgress.parse("movie:tt0111161:tt0111161:2400:8520"))
        assertEquals(DevProgress("series", "tt0944947", "tt0944947:1:2", 600.5, 3300.0), DevProgress.parse("series:tt0944947:tt0944947:1:2:600.5:3300"))
        // An id with a colon of its own (kitsu:1), as a Streams screen's.
        assertEquals(DevProgress("series", "kitsu:1", "kitsu:1:2", 60.0, 1400.0), DevProgress.parse("series:kitsu:1:kitsu:1:2:60:1400"))
    }

    @Test
    fun aPositionAtTheEndPlaysTheVideoThrough() {
        assertTrue(DevProgress.parse("series:tt1:tt1:1:3:1200:1200").watched)
        assertFalse(DevProgress.parse("series:tt1:tt1:1:3:1199:1200").watched)
    }

    @Test
    fun aPositionOutsideTheVideoOrAMissingPartIsRejected() {
        for (bad in listOf(
            "movie:tt1:tt1:0:100", "movie:tt1:tt1:101:100", "movie:tt1:tt1:-5:100", "movie:tt1:tt1:5:x",
            "movie:tt1:5:100", "movie::tt1:5:100", "tt1:5:100",
        )) {
            assertFailsWith<IllegalArgumentException>(bad) { DevProgress.parse(bad) }
        }
    }
}
