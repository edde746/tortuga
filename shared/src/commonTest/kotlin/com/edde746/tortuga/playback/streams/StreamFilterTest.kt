package com.edde746.tortuga.playback.streams

import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.settings.StreamCodec
import com.edde746.tortuga.data.settings.StreamQuality
import com.edde746.tortuga.data.settings.StreamRange
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamBehaviorHints
import com.edde746.tortuga.model.StreamSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamFilterTest {
    private fun stream(name: String?, description: String? = null) =
        Stream(StreamSource.Url("https://cdn.example/${name.hashCode()}.mkv"), name = name, description = description)

    private val uhd = stream("Addon A\n4k DV", "Example.Show.S01E01.2160p.DV.HEVC-NOVA.mkv\n💾 9.1 GB")
    private val fhd = stream("Addon B\n1080p", "Example.Show.S01E01.1080p.WEB.H264-ORBIT.mkv")
    private val hd = stream("Addon C 720p", "Example Show S01E01 720p HDTV x264")
    private val sd = stream("Old 480p encode")
    private val unknown = stream("Some stream", "no resolution named here")

    @Test
    fun qualitiesComeFromTheBadgeParsersResolution() {
        val filter = StreamFilter(setOf(StreamQuality.UHD_4K, StreamQuality.UNKNOWN), "", "")
        assertEquals(listOf(false, true, true, true, false), listOf(uhd, fhd, hd, sd, unknown).map(filter::accepts))
        val sdOnly = StreamFilter(setOf(StreamQuality.SD), "", "")
        assertEquals(listOf(true, true, true, false, true), listOf(uhd, fhd, hd, sd, unknown).map(sdOnly::accepts))
        assertEquals(StreamQuality.QHD_1440, StreamFilter.qualityOf(ResolutionBadge.QHD_1440))
        assertEquals(StreamQuality.UNKNOWN, StreamFilter.qualityOf(null))
    }

    @Test
    fun patternsIgnoreCaseAndSpanTheNameAndTheDescription() {
        assertTrue(StreamFilter(emptySet(), "hevc", "").accepts(uhd))
        assertFalse(StreamFilter(emptySet(), "hevc", "").accepts(fhd))
        // Line breaks read as spaces: the name's quality line and the description's codec in one pattern.
        assertTrue(StreamFilter(emptySet(), "4k dv.*hevc", "").accepts(uhd))
        assertTrue(StreamFilter(emptySet(), "^addon a 4k", "").accepts(uhd))
    }

    @Test
    fun qualityThenExcludeThenInclude() {
        // A 4K stream matching the include pattern is still hidden by the quality filter.
        val filter = StreamFilter(setOf(StreamQuality.UHD_4K), includePattern = "example.show", excludePattern = "orbit")
        assertFalse(filter.accepts(uhd), "the quality filter comes first")
        assertFalse(filter.accepts(fhd), "the exclude pattern wins over the include pattern")
        assertTrue(filter.accepts(hd))
        assertFalse(filter.accepts(sd), "with an include pattern, a stream that doesn't match it is hidden")
    }

    @Test
    fun aBlankOrInvalidPatternFiltersNothingAndSaysSo() {
        val invalid = StreamFilter(emptySet(), includePattern = "(1080p", excludePattern = "[")
        assertTrue(invalid.includeInvalid)
        assertTrue(invalid.excludeInvalid)
        assertFalse(invalid.active)
        assertTrue(listOf(uhd, fhd, hd, sd, unknown).all(invalid::accepts))

        // One valid pattern still applies beside an invalid one.
        val half = StreamFilter(emptySet(), includePattern = "*1080p", excludePattern = "hdtv")
        assertTrue(half.includeInvalid)
        assertFalse(half.excludeInvalid)
        assertEquals(listOf(true, true, false, true, true), listOf(uhd, fhd, hd, sd, unknown).map(half::accepts))

        val blank = StreamFilter(emptySet(), "  ", "")
        assertFalse(blank.includeInvalid)
        assertFalse(blank.active)
        assertTrue(StreamFilter.invalidPattern("hevc)"))
        assertFalse(StreamFilter.invalidPattern(""))
        assertFalse(StreamFilter.invalidPattern("x26[45]|hevc"))
    }

    @Test
    fun storedSettingsDecodeLeniently() {
        val settings = ProfileSettings.fromStored(
            mapOf(
                "streams.autoPlay" to "\"firstMatch\"",
                // A bucket a later version added is dropped; the rest still applies, in order, once each.
                "streams.hiddenQualities" to """["sd","8k","4k","sd"]""",
                "streams.includePattern" to "\"  hevc \"",
            ),
        )
        assertEquals(AutoPlayMode.FIRST_MATCH, settings.streamAutoPlay)
        assertEquals(listOf(StreamQuality.UHD_4K, StreamQuality.SD), settings.hiddenStreamQualities)
        assertEquals("hevc", settings.streamIncludePattern)
        assertEquals("", settings.streamExcludePattern)

        val unknown = ProfileSettings.fromStored(mapOf("streams.autoPlay" to "\"teleport\""))
        assertEquals(AutoPlayMode.OFF, unknown.streamAutoPlay, "a mode this version doesn't know is the default")
        assertEquals(AutoPlayMode.OFF, ProfileSettings.DEFAULT.streamAutoPlay)
        assertEquals(emptyList(), ProfileSettings.DEFAULT.hiddenStreamQualities)
    }

    private val hdr = stream("Addon A 4K", "Movie.2026.2160p.WEB-DL.HDR10.HEVC")
    private val av1 = stream("Addon A 1080p", "Movie.2026.1080p.WEB-DL.AV1")

    @Test
    fun rangesGoByTheMostCapableFormatNamed() {
        // uhd names Dolby Vision, hdr HDR10, fhd none: Dolby Vision wins whatever else a stream names.
        val noDv = StreamFilter(emptySet(), "", "", hiddenRanges = setOf(StreamRange.DOLBY_VISION))
        assertEquals(listOf(false, true, true), listOf(uhd, hdr, fhd).map(noDv::accepts))
        val sdrOnly = StreamFilter(emptySet(), "", "", hiddenRanges = setOf(StreamRange.DOLBY_VISION, StreamRange.HDR))
        assertEquals(listOf(false, false, true), listOf(uhd, hdr, fhd).map(sdrOnly::accepts))
        val noSdr = StreamFilter(emptySet(), "", "", hiddenRanges = setOf(StreamRange.SDR))
        assertEquals(listOf(true, true, false), listOf(uhd, hdr, fhd).map(noSdr::accepts))
    }

    @Test
    fun codecsGoByTheCodecNamedUnknownItsOwn() {
        val noHevc = StreamFilter(emptySet(), "", "", hiddenCodecs = setOf(StreamCodec.HEVC))
        assertEquals(listOf(false, true, true, true), listOf(uhd, fhd, av1, unknown).map(noHevc::accepts))
        val named = StreamFilter(emptySet(), "", "", hiddenCodecs = setOf(StreamCodec.UNKNOWN))
        assertEquals(listOf(true, true, true, false), listOf(uhd, fhd, av1, unknown).map(named::accepts))
    }

    @Test
    fun sizeLimitsGoByTheAddonsSizeElseTheTextsAndPassAStreamOfNoKnownSize() {
        val gib = 1L shl 30
        val limits = StreamFilter(emptySet(), "", "", minSizeBytes = 2 * gib, maxSizeBytes = 20 * gib)
        // uhd's text says 9.1 GB; hd's says nothing (passes); exact says 1 GB but the addon says 30 GiB.
        val exact = Stream(StreamSource.Url("https://cdn.example/exact.mkv"), name = "exact", description = "💾 1 GB",
            behaviorHints = StreamBehaviorHints(videoSize = 30 * gib))
        val small = stream("small", "💾 700 MB")
        assertEquals(listOf(true, true, false, false), listOf(uhd, hd, exact, small).map(limits::accepts))
        val atMost = StreamFilter(emptySet(), "", "", maxSizeBytes = 9 * gib)
        assertFalse(atMost.accepts(uhd), "9.1 GB is over 9 GiB")
        assertTrue(atMost.accepts(small))
    }

    @Test
    fun theNewFiltersStoredDecodeLeniently() {
        val settings = ProfileSettings.fromStored(
            mapOf(
                "streams.hiddenRanges" to """["dv","hdr12","dv"]""",
                "streams.hiddenCodecs" to """["hevc","h266"]""",
                "streams.minSizeMb" to "-5",
                "streams.maxSizeMb" to "20480",
            ),
        )
        assertEquals(listOf(StreamRange.DOLBY_VISION), settings.hiddenStreamRanges)
        assertEquals(listOf(StreamCodec.HEVC), settings.hiddenStreamCodecs)
        val filter = StreamFilter.of(settings)
        assertEquals(null, filter.minSizeBytes, "a size under 0 is none")
        assertEquals(20L shl 30, filter.maxSizeBytes)
        assertFalse(StreamFilter.of(ProfileSettings.DEFAULT).active)
    }
}
