package com.edde746.tortuga.output

import android.media.AudioFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which compressed formats mpv bitstreams, from the platform's encodings and the HDMI sink's. */
class PassthroughCodecsTest {
    private val platform = setOf(
        AudioFormat.ENCODING_AC3,
        AudioFormat.ENCODING_E_AC3,
        AudioFormat.ENCODING_E_AC3_JOC,
        AudioFormat.ENCODING_DTS,
        AudioFormat.ENCODING_DTS_HD,
        AudioFormat.ENCODING_DOLBY_TRUEHD,
    )

    @Test
    fun aSinkThatListsNoDtsGetsNoDtsWhateverThePlatformTakes() {
        // An Amlogic box's HDMI output in front of a Samsung TV: Dolby only.
        val sink = intArrayOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_E_AC3_JOC, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3, AudioFormat.ENCODING_IEC61937)
        assertEquals(listOf("ac3", "eac3", "truehd"), passthroughCodecs(platform, listOf(sink)))
    }

    @Test
    fun aSinkThatListsDtsInAnyFormKeepsIt() {
        val sink = intArrayOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_DTS_HD_MA)
        assertEquals(listOf("ac3", "eac3", "dts", "dts-hd", "truehd"), passthroughCodecs(platform, listOf(sink)))
    }

    @Test
    fun anUndescribedSinkLeavesThePlatformsAnswer() {
        val all = listOf("ac3", "eac3", "dts", "dts-hd", "truehd")
        assertEquals(all, passthroughCodecs(platform, emptyList()))
        assertEquals(all, passthroughCodecs(platform, listOf(intArrayOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_IEC61937))))
        assertEquals(all, passthroughCodecs(platform, listOf(intArrayOf(AudioFormat.ENCODING_AC3), intArrayOf())))
    }
}
