package com.edde746.tortuga.playback.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Release-name fragments that look like a badge and aren't, or read as a different one. Most are cases where
 * addons' own parsers disagree (collected with the formats in [StreamBadgeFormatsTest]).
 */
class StreamBadgeTrapsTest {
    private fun parse(description: String) = StreamBadgeParser.parse(null, description)

    @Test
    fun wordsThatContainATokenDontCount() {
        assertEquals(emptyList(), parse("Movie.2011.HDRip.XviD-GROUP").hdr, "HDRip is a rip, not HDR")
        assertEquals(emptyList(), parse("Movie.2004.DVDRip.x264").hdr, "DVDRip is not Dolby Vision")
        assertEquals(emptyList(), parse("Movie 2004 DVD9").hdr)
        assertEquals(emptyList(), parse("Movie.2020.SDR.2160p.WEB-DL.H.265.AAC5.1").hdr)
        assertEquals(emptyList(), parse("📹 hevc • 10bit").hdr)
        val show = parse("Example.Show.S01E01.4Kids.Dub.480p.DVDRip.x264")
        assertEquals(ResolutionBadge.SD_480, show.resolution)
        assertEquals(emptyList(), show.hdr)
        assertEquals(ResolutionBadge.FHD_1080, parse("Example.Film.1977.Project.4K77.1080p.BluRay.x264-NOVA").resolution)
        assertNull(parse("Example.Film.4K77.Restoration").resolution, "4K77 is a restoration project's name")
        assertNull(parse("Clip.AV10.Test").videoCodec)
        assertNull(parse("Uhdexample.Site.Rip").resolution)
    }

    @Test
    fun serviceCodesBeforeAnInstantMarkerAreNotBadges() {
        // An addon's service codes before its instant marker: `DL` is not WEB-DL, `SDR` not standard dynamic range.
        val serviceCode = StreamBadgeParser.parse("[DL+] Example Streams\n1080p", null)
        assertEquals(null to true, serviceCode.source to serviceCode.cached)
        assertEquals(StreamBadges(resolution = ResolutionBadge.UHD_2160, cached = true), StreamBadgeParser.parse("Example Streams SDR ⚡️ 2160p", null))
        // A film title in the release name is not the addon's word for an instant stream.
        assertNull(StreamBadgeParser.parse("Example Streams\n1080p", "Instant.Example.2018.1080p.BluRay.x264").cached)
        // Zero-width joiners around a marker (custom addon formatters).
        assertEquals(false, StreamBadgeParser.parse("[HW\u200D⏳\u200B] Stream", null).cached)
    }

    @Test
    fun sourceSpellings() {
        assertEquals(SourceBadge.WEB_DL, parse("Example.Show.S03E01.1080p.WEB.h264-NOVA").source, "a plain WEB")
        assertEquals(SourceBadge.WEBRIP, parse("Movie.2019.1080p.WEB-DLRip.x264").source)
        assertEquals(SourceBadge.BLURAY, parse("Example.Film.2023.1080p.BRRip.x264-NOVA").source)
        assertEquals(SourceBadge.BLURAY, parse("[Group] Anime - 01 [BD 1080p]").source)
        assertEquals(SourceBadge.REMUX, parse("Movie.2019.1080p.BDRemux.AVC").source)
        assertEquals(SourceBadge.HDTV, parse("Show.S01E01.720p.HDTV.x264").source)
    }

    @Test
    fun numbersNeedTheirUnit() {
        // Peer counts, years and bitrates next to the resolution or the size.
        assertNull(parse("👤 1080 ⚙️ NOVASITE").resolution)
        assertNull(parse("Movie 2160 Edition").resolution)
        assertNull(parse("20 Mbps").sizeBytes)
        assertNull(parse("320kbps").sizeBytes)
        assertEquals(ResolutionBadge.FHD_1080, parse("Movie.1080i.HDTV").resolution)
    }

    @Test
    fun pixelsBeatTheWordFourK() {
        assertEquals(ResolutionBadge.FHD_1080, parse("Movie.1984.4K.Remastered.1080p.BluRay.x264").resolution)
        assertEquals(ResolutionBadge.UHD_2160, parse("Movie UHD BluRay").resolution)
    }

    @Test
    fun theMostSpecificHdrFormatWins() {
        assertEquals(listOf(HdrBadge.DOLBY_VISION, HdrBadge.HDR10_PLUS), parse("Show.S01E01.2160p.DV.HDR10+.HEVC").hdr)
        assertEquals(listOf(HdrBadge.HDR10_PLUS), parse("Movie.2160p.HDR10Plus.x265").hdr)
        assertEquals(listOf(HdrBadge.DOLBY_VISION, HdrBadge.HDR), parse("Movie 2160p DoVi HDR").hdr)
        assertEquals(listOf(HdrBadge.HDR10), parse("Movie.2160p.HDR10.HEVC").hdr)
        assertEquals(listOf(HdrBadge.HLG), parse("Match.2160p.HLG.HEVC").hdr)
    }

    @Test
    fun codecSpellings() {
        for (text in listOf("x265", "H.265", "h265", "HEVC")) assertEquals(VideoCodecBadge.HEVC, parse("Movie.1080p.$text-GRP").videoCodec, text)
        for (text in listOf("x264", "H.264", "H264", "AVC")) assertEquals(VideoCodecBadge.AVC, parse("Movie.1080p.$text-GRP").videoCodec, text)
        assertEquals(VideoCodecBadge.AV1, parse("Movie.2160p.WEB-DL.AV1.Opus.5.1").videoCodec)
        assertEquals(VideoCodecBadge.VP9, parse("Clip.1080p.VP9").videoCodec)
        assertNull(parse("Movie.AVCHD.Camcorder").videoCodec, "AVCHD is a camcorder format")
    }

    @Test
    fun audioFormatsAndTheirChannels() {
        fun audio(text: String) = parse(text).let { it.audio to it.channels }
        assertEquals(listOf(AudioBadge.EAC3) to ChannelsBadge.SURROUND_5_1, audio("Show.S01E01.1080p.WEB-DL.DDP5.1.H.264"))
        assertEquals(listOf(AudioBadge.EAC3) to ChannelsBadge.SURROUND_5_1, audio("Show S01E01 1080p WEB-DL DD+ 5.1 H 264"))
        assertEquals(listOf(AudioBadge.AC3) to ChannelsBadge.SURROUND_5_1, audio("Movie.720p.BluRay.DD5.1.x264"))
        assertEquals(listOf(AudioBadge.EAC3) to null, audio("Movie.1080p.E-AC-3.x264"), "E-AC-3's tail is not AC-3")
        assertEquals(listOf(AudioBadge.ATMOS, AudioBadge.EAC3) to ChannelsBadge.SURROUND_5_1, audio("Movie.2160p.WEB-DL.DDPA5.1.DV.H.265"))
        assertEquals(listOf(AudioBadge.ATMOS, AudioBadge.TRUEHD) to ChannelsBadge.SURROUND_7_1, audio("Movie.2160p.BluRay.REMUX.TrueHD.Atmos.7.1"))
        assertEquals(listOf(AudioBadge.DTS_HD_MA) to ChannelsBadge.SURROUND_5_1, audio("Movie.1080p.BluRay.DTS-HD.MA.5.1.x264"))
        assertEquals(listOf(AudioBadge.DTS_X) to ChannelsBadge.SURROUND_7_1, audio("Movie.2160p.UHD.BluRay.DTS-X.7.1"))
        assertEquals(listOf(AudioBadge.DTS_X) to null, audio("Movie 2160p DTS:X"))
        assertEquals(listOf(AudioBadge.DTS) to ChannelsBadge.SURROUND_5_1, audio("Movie.720p.BluRay.DTS.x264 6ch"))
        assertEquals(listOf(AudioBadge.AAC) to ChannelsBadge.STEREO, audio("Show.S02E03.720p.WEB.AAC2.0.H.264"))
        // Multi-audio: every format, the largest layout.
        assertEquals(listOf(AudioBadge.EAC3, AudioBadge.AAC) to ChannelsBadge.SURROUND_5_1, audio("Anime.1080p.DDP5.1.AAC2.0.x264"))
        assertEquals(listOf(AudioBadge.DTS_HD) to ChannelsBadge.SURROUND_7_1, audio("Example.Film.1995.1080p.BluRay.DTS-HD.HRA.7.1.x264"))
        assertEquals(listOf(AudioBadge.DTS) to ChannelsBadge.SURROUND_5_1, audio("🔊 DTS Lossy • 5.1"))
        assertEquals(listOf(AudioBadge.DTS_HD_MA) to ChannelsBadge.SURROUND_7_1, audio("🔊 DTS Lossless • 7.1"))
    }

    @Test
    fun opusTheCodecIsNotOpusTheWord() {
        assertEquals(emptyList(), parse("Mr.Examples.Opus.1995.1080p.BluRay.x264").audio)
        assertEquals(emptyList(), parse("Magnum Opus 2023 1080p").audio)
        assertEquals(listOf(AudioBadge.OPUS), parse("Show.S01E01.1080p.WEB.Opus.2.0.AV1").audio)
        assertEquals(listOf(AudioBadge.OPUS), parse("Show 1080p WEB OPUS AV1").audio)
    }

    @Test
    fun aLoneTwoPointZeroIsNotAChannelLayout() {
        assertNull(parse("2.0.2031.1080p.BluRay.x264").channels, "2.0 is a title")
        assertNull(parse("App.v2.0.Tutorial.720p").channels)
        assertNull(parse("Show.S05.1080p.WEB").channels)
        assertNull(parse("Station.5.1993.The.Pilot.DVDRip.XviD").channels)
        assertEquals(ChannelsBadge.SURROUND_7_1, parse("Movie 2160p 7.1").channels)
    }

    @Test
    fun aSizeIsNotAChannelLayout() {
        for (text in listOf("💾 5.1 GB", "📦 7.1GB", "Movie 2160p 7.1 GiB", "1080p • 2.0 GB • 🔊 AAC", "AAC 5.1 GB", "💾 5.1 / 188 GB", "Opus 2.0 GB")) {
            assertNull(parse(text).channels, text)
        }
        assertEquals(ChannelsBadge.SURROUND_5_1, parse("Movie.1080p.DDP5.1.x264\n💾 7.1 GB").channels)
        assertEquals(emptyList(), parse("Magnum Opus 2.0 GB").audio, "Opus the word, then a size")
    }

    @Test
    fun sizesInTheirUsualSpellings() {
        val gib = 1L shl 30
        val mib = 1L shl 20
        assertEquals((12.3 * gib).toLong(), parse("💾 12.3 GB").sizeBytes)
        assertEquals((12.3 * gib).toLong(), parse("12.3GB").sizeBytes)
        assertEquals(850 * mib, parse("850 MB").sizeBytes)
        assertEquals((1.5 * gib).toLong(), parse("1,5 GB").sizeBytes, "decimal comma")
        assertEquals(1024 * mib, parse("1,024 MB").sizeBytes, "thousands separator")
        assertEquals(4L * gib, parse("4 GiB").sizeBytes)
        // The size after 💾 wins over a size earlier in the text.
        assertEquals((2.5 * gib).toLong(), parse("Pack 45 GB episode\n💾 2.5 GB").sizeBytes)
    }

    @Test
    fun instantAndNotReadyMarkers() {
        fun instant(name: String) = StreamBadgeParser.parse(name, null).cached
        for (name in listOf("[HW+] Example Streams\n4k", "HW+ Addon A", "[HW⚡] Stream", "⚡ 1080p", "⚡️ Example Streams", "Cached")) {
            assertEquals(true, instant(name), name)
        }
        for (name in listOf("[HW download] Example Streams\n4k", "[HW⏳] 1080p", "[HW⬇️] Addon A", "Uncached", "Not cached", "not-cached")) {
            assertEquals(false, instant(name), name)
        }
        for (name in listOf("Example Streams\n4k", "WEB-DL DD+ 5.1", "HDR10+", "Multi ENG+SPA")) assertNull(instant(name), name)
    }

    @Test
    fun theNameWinsOverTheDescription() {
        val badges = StreamBadgeParser.parse("[HW+] Example Streams\n1080p", "Movie.2160p.x265\n💾 3 GB ⚙️ NOVASITE")
        assertEquals(ResolutionBadge.FHD_1080, badges.resolution)
        assertEquals(VideoCodecBadge.HEVC, badges.videoCodec)
        assertEquals(3L shl 30, badges.sizeBytes)
    }
}
