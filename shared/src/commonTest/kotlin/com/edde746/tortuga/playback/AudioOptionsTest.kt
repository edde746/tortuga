package com.edde746.tortuga.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioOptionsTest {
    private val dolby = listOf("ac3", "eac3")

    @Test
    fun passthroughNamesTheCodecsWhileNothingNeedsDecodedAudio() {
        assertEquals("ac3,eac3", passthroughSpdif(dolby, normalization = false, AudioChannels.ORIGINAL, speed = 1.0))
        assertEquals(
            "ac3,eac3,truehd,dts-hd",
            passthroughSpdif(dolby + listOf("truehd", "dts-hd"), normalization = false, AudioChannels.ORIGINAL, speed = 1.0),
        )
        assertEquals("ac3,eac3", passthroughSpdif(dolby, normalization = false, AudioChannels.SURROUND_51, speed = 1.0), "5.1 shapes decoded audio only")
        assertEquals("", passthroughSpdif(emptyList(), normalization = false, AudioChannels.ORIGINAL, speed = 1.0), "the route takes nothing undecoded")
    }

    @Test
    fun normalizationStereoAndAnotherSpeedDecode() {
        assertEquals("", passthroughSpdif(dolby, normalization = true, AudioChannels.ORIGINAL, speed = 1.0), "a filter can't process a bitstream")
        assertEquals("", passthroughSpdif(dolby, normalization = false, AudioChannels.STEREO, speed = 1.0), "stereo is a promise for everything")
        assertEquals("", passthroughSpdif(dolby, normalization = false, AudioChannels.ORIGINAL, speed = 1.25), "no tempo change of a bitstream")
        assertEquals("", passthroughSpdif(dolby, normalization = false, AudioChannels.ORIGINAL, speed = 0.75))
    }
}
