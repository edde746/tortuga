package com.edde746.tortuga.mpv.video

import kotlin.test.Test
import kotlin.test.assertEquals

class Win32VideoHostTest {
    /**
     * Windows hands mpv the black level the display reports, and libplacebo now renders PQ black at it: a TV that
     * reports a floor above what it shows lifts every video's blacks. The Windows output keeps black absolute, whether
     * or not HDR is on.
     */
    @Test
    fun videoBlackStaysAbsoluteBlack() {
        assertEquals("inf", Win32VideoHost().options(child = 42)["target-contrast"])
    }
}
