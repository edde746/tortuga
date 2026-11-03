package com.edde746.tortuga.playback.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Sources, codecs and audio formats beyond disc, web and TV releases, and an instant marker's look-alike, on rows
 * shaped as addons format them: a name and a description, with invented titles.
 */
class StreamBadgeVocabularyTest {
    /** The labels of [kind] the stream list has for a row's [name] and [description]. */
    private fun labels(kind: BadgeKind, name: String, description: String): List<String> =
        StreamBadgeParser.parse(name, description).display().filter { it.kind == kind }.map { it.text }

    private fun source(name: String, description: String) = labels(BadgeKind.SOURCE, name, description)

    @Test
    fun blurayWrittenBdAndAResolutionRunIntoLetters() {
        // `BD1080P`.
        assertEquals(
            listOf("BluRay"),
            source(
                "Example Streams 1080p",
                "📄 Example.Film.1988.Directors.Cut.BD1080P.X264.AAC.Italian-NOVA\n" +
                    "📹 avc | 🔊 AAC\n💾 9.8 GB\n🌎/🇮🇹",
            ),
        )
        // The name writes the frame size, `1448x1080p`.
        assertEquals(
            listOf("1080p"),
            labels(
                BadgeKind.RESOLUTION,
                "Example Streams 1448x1080p",
                "📄 [Nova] Example Show Ep 01 [1448x1080_Blu_Ray_Dual_Audio_FLAC]\n🔊 FLAC\n⭐ BluRay | 🏷️ Nova\n" +
                    "💾 3.6 GB\n🌎",
            ),
        )
    }

    @Test
    fun ripsOfDvdsHdSourcesAndTv() {
        assertEquals(
            listOf("DVDRip"),
            source(
                "Example Streams unknown",
                "📄 Example Film 1981 FRENCH DVDRip XviD Mp3-NOVA\n📹 xvid | 🔊 MP3\n⭐ DVDRip | 🏷️ NOVA\n" +
                    "💾 2.0 GB\n🇫🇷",
            ),
        )
        // A full-HD rip.
        assertEquals(
            listOf("HDRip"),
            source(
                "Example Streams\n1080p",
                "EXAMPLE.FILM.2019.1080p.FHDRip.H264.AAC-NOVA\n💾 5.03 GB",
            ),
        )
        // `WEB.HDRip` is an HDRip, not a WEB-DL.
        assertEquals(
            listOf("HDRip"),
            source(
                "Example Streams 1080p",
                "📄 Example.Show.2023.S01.1080p.Hindi.Dual.WEB.HDRip.DDP.5.1.x264.MSubs-NOVA\n" +
                    "📹 avc | 🔊 Dolby Digital Plus • 5.1\n⭐ HDRip\n💾 1.1 GB\n🌎/🇮🇳",
            ),
        )
        assertEquals(
            listOf("TVRip"),
            source(
                "Example Streams\nTVRip",
                "Example Cartoons Collection 1930 to 1969 TVrip\n" +
                    "Example.Cartoons.S1954.TVRip.Mixed-NOVA/S1954E11.Example.Short.avi\n" +
                    "💾 55.18 MB\n🇱🇹",
            ),
        )
        // A digital cinema package source.
        assertEquals(
            listOf("DCP"),
            source(
                "Example Streams\n1080p",
                "[Nova-Raws] Example Film [DCPrip 1922x1080 HEVC…\n" +
                    "[Nova-Raws] Example Film [DCPrip bt709 ver 1922x1080 HEVC TrueHD].mkv\n" +
                    "💾 8.9 GB",
            ),
        )
    }

    @Test
    fun aDiscsOwnVideoIsADvdAndARipSizedForOneStaysARip() {
        // A bare `dvd` line.
        assertEquals(listOf("DVD"), source("Example Streams\ndvd", "Example Film 1954 dvd\n💾 7.60 GB"))
        // DVD5 and DVD-R.
        assertEquals(
            listOf("DVD"),
            source("Example Streams unknown", "[FILE] Example Film (1946, DVD5) MVO + Original + Sub (rus)\n rus\n DVD5\n 4.2 GB"),
        )
        assertEquals(
            listOf("DVD"),
            source("Example Streams unknown", "[FILE] Example Film DVD-R R1 NTSC NOVA\n DVD\n 4.4 GB"),
        )
        // A Blu-ray rip cut to fit a DVD9 is a rip; `HD DVD` is no DVD.
        assertEquals(
            listOf("BluRay"),
            source("Example Streams\n720p", "Example.Film.1972.BDRip.720p.DVD9.x264.rus.srt.NOVA.mkv\n💾 7.89 GB"),
        )
        assertEquals(emptyList(), source("Movie 2007 HD DVD", "1080p VC-1"))
    }

    @Test
    fun xvidVideoAndFlacAndMp3Audio() {
        assertEquals(
            listOf("XviD"),
            labels(
                BadgeKind.VIDEO_CODEC,
                "Example Streams\nXviD",
                "Example Show Season 1 (2017)\nExample.Show.S01E01.XviD-NOVA.avi\n💾 464.92 MB",
            ),
        )
        assertEquals(
            listOf("FLAC"),
            labels(
                BadgeKind.AUDIO,
                "Example Streams 1080p",
                "📄 Example Show S01E01 Pilot.1080p.BluRay.x265.10bit.FLAC.2.0-NOVA.mkv\n" +
                    "📹 hevc • 10bit | 🔊 FLAC • 2.0\n⭐ BluRay | 🏷️ NOVA\n💾 1.5 GB",
            ),
        )
        assertEquals(
            listOf("MP3"),
            labels(
                BadgeKind.AUDIO,
                "Example Streams unknown",
                "📄 Example Film 1981 FRENCH DVDRip XviD Mp3-NOVA\n📹 xvid | 🔊 MP3\n⭐ DVDRip | 🏷️ NOVA\n" +
                    "💾 2.0 GB\n🇫🇷",
            ),
        )
    }

    @Test
    fun aLightningSignInAReleaseGroupIsNotAnInstantMarker() {
        // The group decorated `⚡NOVA-Team⚡`.
        val badges = StreamBadgeParser.parse(
            "Example Streams 2160p",
            "📄 Example.Film.2014.MULTI.HEVC.2160P.x264.AC3&AAC.5.1-⚡NOVA-Team⚡.mkv\n" +
                "📹 hevc | 🔊 Dolby Digital • AAC • 5.1\n🏷️ NOVA-Team⚡\n💾 8.0 GB\n🌎",
        )
        assertNull(badges.cached)
    }
}
