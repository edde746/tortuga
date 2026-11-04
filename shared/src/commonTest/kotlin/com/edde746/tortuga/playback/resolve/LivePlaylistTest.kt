package com.edde746.tortuga.playback.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LivePlaylistTest {
    private fun playlist(target: String, vararg durations: String) = buildString {
        append("#EXTM3U\n#EXT-X-TARGETDURATION:$target\n#EXT-X-MEDIA-SEQUENCE:500\n")
        durations.forEachIndexed { index, duration -> append("#EXTINF:$duration,\nevent/${500 + index}.ts\n") }
    }

    @Test
    fun aPlaylistReadsAsItsSegmentsAndTarget() {
        assertEquals(LivePlaylist(listOf(2.0, 2.0, 1.5), 2.0, ended = false), LivePlaylist.parse(playlist("2", "2.000", "2.000", "1.500")))
    }

    @Test
    fun aDurationThatIsNoNumberOfSecondsIsNoPlaylist() {
        for (bad in listOf("NaN", "Infinity", "-Infinity", "-2.0")) {
            assertNull(LivePlaylist.parse(playlist("2", "2.000", bad, "2.000")), "a segment of $bad")
            assertNull(LivePlaylist.parse(playlist(bad, "2.000", "2.000", "2.000")), "a target of $bad")
        }
    }
}
