package com.edde746.tortuga.playback.mpv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaCodecAsyncTest {
    @Test
    fun everyDeviceDecodesAsynchronouslyFromApi31() {
        assertTrue(MediaCodecAsync.enabled(31, "panther"))
        assertTrue(MediaCodecAsync.enabled(36, "a device nobody checked"))
    }

    @Test
    fun aCheckedDeviceBelowApi31DecodesAsynchronously() {
        assertTrue(MediaCodecAsync.enabled(28, "mdarcy"), "SHIELD TV Pro (2019), Android 9")
        assertTrue(MediaCodecAsync.enabled(30, "karat"), "Fire TV Stick 4K Max (2nd generation), Fire OS 8")
    }

    @Test
    fun anUncheckedDeviceBelowApi31StaysSynchronous() {
        assertFalse(MediaCodecAsync.enabled(30, "sabrina"))
        assertFalse(MediaCodecAsync.enabled(28, "a device nobody checked"))
    }

    @Test
    fun aCheckedDeviceOnAnotherSystemStaysSynchronous() {
        assertFalse(MediaCodecAsync.enabled(30, "mdarcy"), "the SHIELD after its update to Android 11")
        assertFalse(MediaCodecAsync.enabled(28, "karat"))
    }
}
