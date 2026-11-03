package com.edde746.tortuga.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoInfoTest {
    @Test
    fun aPictureTallerThanWideIsTall() {
        assertTrue(VideoInfo.isTall(720, 1280, 0), "a phone's upright video")
        assertFalse(VideoInfo.isTall(3840, 2160, 0), "a wide video")
        assertFalse(VideoInfo.isTall(1080, 1080, 0), "a square one is not taller than wide")
    }

    @Test
    fun aQuarterTurnShowsTheOtherSideUp() {
        // A phone's upright video stored sideways: 1920x1080 frames turned a quarter show 1080 wide and 1920 tall.
        assertTrue(VideoInfo.isTall(1920, 1080, 90))
        assertTrue(VideoInfo.isTall(1920, 1080, 270))
        assertFalse(VideoInfo.isTall(720, 1280, 90), "tall frames turned a quarter show wide")
        assertTrue(VideoInfo.isTall(720, 1280, 180), "a half turn keeps the shape")
        assertTrue(VideoInfo(1920, 1080, "h264", 30.0, DynamicRange.SDR, rotation = 90).tall)
    }
}
