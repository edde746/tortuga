package com.edde746.tortuga.playback.streams

import com.edde746.tortuga.playback.streams.AudioBadge.AAC
import com.edde746.tortuga.playback.streams.AudioBadge.AC3
import com.edde746.tortuga.playback.streams.AudioBadge.ATMOS
import com.edde746.tortuga.playback.streams.AudioBadge.DTS_HD_MA
import com.edde746.tortuga.playback.streams.AudioBadge.DTS_X
import com.edde746.tortuga.playback.streams.AudioBadge.EAC3
import com.edde746.tortuga.playback.streams.AudioBadge.OPUS
import com.edde746.tortuga.playback.streams.AudioBadge.TRUEHD
import com.edde746.tortuga.playback.streams.ChannelsBadge.STEREO
import com.edde746.tortuga.playback.streams.ChannelsBadge.SURROUND_5_1
import com.edde746.tortuga.playback.streams.ChannelsBadge.SURROUND_7_1
import com.edde746.tortuga.playback.streams.HdrBadge.DOLBY_VISION
import com.edde746.tortuga.playback.streams.HdrBadge.HDR
import com.edde746.tortuga.playback.streams.HdrBadge.HDR10
import com.edde746.tortuga.playback.streams.HdrBadge.HDR10_PLUS
import com.edde746.tortuga.playback.streams.HdrBadge.HLG
import com.edde746.tortuga.playback.streams.ResolutionBadge.FHD_1080
import com.edde746.tortuga.playback.streams.ResolutionBadge.HD_720
import com.edde746.tortuga.playback.streams.ResolutionBadge.UHD_2160
import com.edde746.tortuga.playback.streams.SourceBadge.BLURAY
import com.edde746.tortuga.playback.streams.SourceBadge.DVDRIP
import com.edde746.tortuga.playback.streams.SourceBadge.HDRIP
import com.edde746.tortuga.playback.streams.SourceBadge.HDTV
import com.edde746.tortuga.playback.streams.SourceBadge.REMUX
import com.edde746.tortuga.playback.streams.SourceBadge.WEBRIP
import com.edde746.tortuga.playback.streams.SourceBadge.WEB_DL
import com.edde746.tortuga.playback.streams.VideoCodecBadge.AV1
import com.edde746.tortuga.playback.streams.VideoCodecBadge.AVC
import com.edde746.tortuga.playback.streams.VideoCodecBadge.HEVC
import com.edde746.tortuga.playback.streams.VideoCodecBadge.XVID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stream names and descriptions in the shapes addons emit them, produced (2026-09-29) by running addons' own formatter
 * code on release names, then given invented titles, release groups and addon names. `HW` stands for an addon's
 * account short name before its instant (`+`, `⚡`) and not-ready (` download`, `⏳`, `⬇️`) markers.
 * Sizes are read 1024-based (these addons' "GB" are GiB).
 */
class StreamBadgeFormatsTest {
    private fun gb(value: Double) = (value * (1L shl 30)).toLong()
    private fun mb(value: Double) = (value * (1L shl 20)).toLong()

    private fun check(name: String, description: String, expected: StreamBadges) =
        assertEquals(expected, StreamBadgeParser.parse(name, description), "$name | $description")

    @Test
    fun addonNameOverReleaseName() {
        check(
            "[HW+] Example Streams\n4k DV | HDR",
            "Example.Show.S01E03.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-NOVA\n💾 8.51 GB",
            StreamBadges(UHD_2160, listOf(DOLBY_VISION, HDR), HEVC, WEB_DL, listOf(ATMOS, EAC3), SURROUND_5_1, gb(8.51), cached = true),
        )
        // The name says HDR for HDR10 (the addon's title parser collapses it); the release name keeps the detail.
        check(
            "[HW download] Example Streams\n4k DV | HDR",
            "Example.Film.2023.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.7.1.Atmos-ORBIT\n💾 82.28 GB",
            StreamBadges(UHD_2160, listOf(DOLBY_VISION, HDR10), HEVC, REMUX, listOf(ATMOS, TRUEHD), SURROUND_7_1, gb(82.28), cached = false),
        )
        check(
            "Example Streams\n1080p",
            "Sample.Film.Part.Two.2024.1080p.BluRay.x264.DTS-HD.MA.7.1-LUMEN\n💾 25 GB",
            StreamBadges(FHD_1080, emptyList(), AVC, BLURAY, listOf(DTS_HD_MA), SURROUND_7_1, gb(25.0), cached = null),
        )
        // A season pack: the pack's name, then the file's name.
        check(
            "[HW+] Example Streams\n1080p",
            "Kitchen.Story.S03.1080p.WEB-DL.DDP5.1.H.264-QUILL\nKitchen.Story.S03E01.Tomorrow.1080p.WEB-DL.DDP5.1.H.264-QUILL.mkv\n" +
                "💾 2.73 GB",
            StreamBadges(FHD_1080, emptyList(), AVC, WEB_DL, listOf(EAC3), SURROUND_5_1, gb(2.73), cached = true),
        )
        check(
            "[HW+] Example Streams\n1080p",
            "Example.Film.2023.iTA-ENG.1080p.BluRay.x265.10bit.AC3.5.1-ORBIT\n💾 4.3 GB\n🇬🇧 / 🇮🇹",
            StreamBadges(FHD_1080, emptyList(), HEVC, BLURAY, listOf(AC3), SURROUND_5_1, gb(4.3), cached = true, StreamLanguages(audio = listOf("it", "en"))),
        )
        check(
            "[HW+] Example Streams\n4k HDR10+",
            "Sample.Series.S02E01.2160p.WEB-DL.DDP5.1.Atmos.HDR10+.H.265-NOVA\n💾 6.3 GB",
            StreamBadges(UHD_2160, listOf(HDR10_PLUS), HEVC, WEB_DL, listOf(ATMOS, EAC3), SURROUND_5_1, gb(6.3), cached = true),
        )
        // Without a resolution the name's second line is the source: HDRip and DVDRip, neither HDR nor DV.
        check(
            "Example Streams\nHDRip",
            "Example.Comedy.2016.HDRip.XviD.AC3-NOVA\n💾 1.4 GB",
            StreamBadges(videoCodec = XVID, source = HDRIP, audio = listOf(AC3), sizeBytes = gb(1.4)),
        )
        check(
            "[HW download] Example Streams\nDVDRip",
            "Example.Classic.1999.DVDRip.XviD.AC3-NOVA\n💾 1.37 GB",
            StreamBadges(videoCodec = XVID, source = DVDRIP, audio = listOf(AC3), sizeBytes = gb(1.37), cached = false),
        )
        check(
            "Example Streams\n1080p",
            "Sample.Future.2017.1080p.WEBRip.x265.10bit.AAC5.1-ORBIT\n💾 2.2 GB",
            StreamBadges(FHD_1080, emptyList(), HEVC, WEBRIP, listOf(AAC), SURROUND_5_1, gb(2.2)),
        )
    }

    @Test
    fun emojiFieldLines() {
        check(
            "[HW⚡] Addon A 2160p",
            "📄 Example.Show.S01E03.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-NOVA\n📹 hevc • DV • HDR | 🔊 Atmos • Dolby Digital Plus • 5.1\n" +
                "⭐ WEB-DL | 🏷️ NOVA\n💾 8.5 GB",
            StreamBadges(UHD_2160, listOf(DOLBY_VISION, HDR), HEVC, WEB_DL, listOf(ATMOS, EAC3), SURROUND_5_1, gb(8.5), cached = true),
        )
        check(
            "[HW⬇️] Addon A 2160p",
            "📄 Example.Film.2023.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.7.1.Atmos-ORBIT\n📹 hevc • DV • HDR10 • 10bit | 🔊 Atmos • TrueHD • 7.1\n" +
                "⭐ BluRay REMUX | 🏷️ ORBIT\n💾 82.3 GB",
            StreamBadges(UHD_2160, listOf(DOLBY_VISION, HDR10), HEVC, REMUX, listOf(ATMOS, TRUEHD), SURROUND_7_1, gb(82.3), cached = false),
        )
        // "DTS Lossless" is the addon's word for DTS-HD MA and DTS:X alike; the release name tells them apart.
        check(
            "Addon A 1080p",
            "📄 Sample.Film.Part.Two.2024.1080p.BluRay.x264.DTS-HD.MA.7.1-LUMEN\n📹 avc | 🔊 DTS Lossless • 7.1\n⭐ BluRay | 🏷️ LUMEN\n" +
                "💾 25.0 GB",
            StreamBadges(FHD_1080, emptyList(), AVC, BLURAY, listOf(DTS_HD_MA), SURROUND_7_1, gb(25.0), cached = null),
        )
        check(
            "[HW⚡] Addon A 2160p",
            "📄 Example.Dream.2010.2160p.UHD.BluRay.x265.10bit.HDR.DTS-X.7.1-LUMEN\n📹 hevc • HDR • 10bit | 🔊 DTS Lossless • 7.1\n⭐ BluRay | 🏷️ LUMEN\n" +
                "💾 30.0 GB",
            StreamBadges(UHD_2160, listOf(HDR), HEVC, BLURAY, listOf(DTS_X), SURROUND_7_1, gb(30.0), cached = true),
        )
        // The addon's parser dropped the Opus; the raw name still has it.
        check(
            "[HW⚡] Addon A 1080p",
            "📄 Example.Anime.S01E01.1080p.WEB-DL.AV1.Opus.2.0-NOVA\n📹 av1 | 🔊 2.0\n⭐ WEB-DL | 🏷️ NOVA\n💾 700.0 MB",
            StreamBadges(FHD_1080, emptyList(), AV1, WEB_DL, listOf(OPUS), STEREO, mb(700.0), cached = true),
        )
        check(
            "[HW⬇️] Addon A unknown",
            "📄 Example.Classic.1999.DVDRip.XviD.AC3-NOVA\n📹 xvid | 🔊 Dolby Digital\n⭐ DVDRip | 🏷️ NOVA\n💾 1.4 GB",
            StreamBadges(videoCodec = XVID, source = DVDRIP, audio = listOf(AC3), sizeBytes = gb(1.4), cached = false),
        )
        check(
            "[HW⚡] Addon A 1080p",
            "📄 Example.Film.2023.iTA-ENG.1080p.BluRay.x265.10bit.AC3.5.1-ORBIT\n📹 hevc • 10bit | 🔊 Dolby Digital • 5.1\n⭐ BluRay\n" +
                "💾 4.3 GB\n🇬🇧/🇮🇹",
            StreamBadges(FHD_1080, emptyList(), HEVC, BLURAY, listOf(AC3), SURROUND_5_1, gb(4.3), cached = true, StreamLanguages(audio = listOf("it", "en"))),
        )
    }

    @Test
    fun glyphTemplate() {
        check(
            "Addon B HW ⚡️ 2160p",
            "🎨 DV|HDR 📺 WEB-DL 🎞️ hevc 🎵 Atmos|Dolby Digital Plus 🔊 5.1\n📦 8.51 GB",
            StreamBadges(UHD_2160, listOf(DOLBY_VISION, HDR), HEVC, WEB_DL, listOf(ATMOS, EAC3), SURROUND_5_1, gb(8.51), cached = true),
        )
        check(
            "Addon B HW ⏳ 2160p",
            "🎨 DV|HDR 📺 BluRay REMUX 🎞️ hevc 🎵 Atmos|TrueHD 🔊 7.1\n📦 82.28 GB",
            StreamBadges(UHD_2160, listOf(DOLBY_VISION, HDR), HEVC, REMUX, listOf(ATMOS, TRUEHD), SURROUND_7_1, gb(82.28), cached = false),
        )
        check(
            "Addon B HW ⚡️ 2160p",
            "🎨 HDR10+ 📺 WEB-DL 🎞️ hevc 🎵 Atmos|Dolby Digital Plus 🔊 5.1\n📦 6.30 GB",
            StreamBadges(UHD_2160, listOf(HDR10_PLUS), HEVC, WEB_DL, listOf(ATMOS, EAC3), SURROUND_5_1, gb(6.30), cached = true),
        )
        // Without an account the template still prints ⏳, after an empty short name: not a marker.
        check(
            "Addon B  ⏳ 2160p",
            "📺 WEB-DL 🎞️ hevc 🎵 AAC 🔊 2.0\n📦 5.10 GB",
            StreamBadges(UHD_2160, emptyList(), HEVC, WEB_DL, listOf(AAC), STEREO, gb(5.10), cached = null),
        )
        check(
            "Addon B HW ⚡️ 1080p",
            "📺 BluRay 🎞️ avc 🎵 Dolby Digital 🔊 5.1\n📦 12.00 GB",
            StreamBadges(FHD_1080, emptyList(), AVC, BLURAY, listOf(AC3), SURROUND_5_1, gb(12.0), cached = true),
        )
        check(
            "Addon B HW ⚡️ ",
            "📺 DVDRip 🎞️ xvid 🎵 Dolby Digital \n📦 1.37 GB",
            StreamBadges(videoCodec = XVID, source = DVDRIP, audio = listOf(AC3), sizeBytes = gb(1.37), cached = true),
        )
        check(
            "Addon B HW ⏳ 1080p",
            "📺 BluRay 🎞️ hevc 🎵 Dolby Digital 🔊 5.1\n📦 4.30 GB\n🌐 English + Italian",
            StreamBadges(FHD_1080, emptyList(), HEVC, BLURAY, listOf(AC3), SURROUND_5_1, gb(4.30), cached = false, StreamLanguages(audio = listOf("en", "it"))),
        )
    }

    @Test
    fun formatterStyles() {
        // A name-over-release style: 1024-based with GiB labels, no space after the emoji.
        check(
            "[HW+] Example Streams 2160p\nHDR | DV",
            "Example.Show.S01E03.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-NOVA.mkv\n💾8.51 GiB",
            StreamBadges(UHD_2160, listOf(DOLBY_VISION, HDR), HEVC, WEB_DL, listOf(ATMOS, EAC3), SURROUND_5_1, gb(8.51), cached = true),
        )
        check(
            "[HW download] Example Streams 2160p\nHDR10 | DV",
            "Example.Film.2023.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.7.1.Atmos-ORBIT.mkv\n💾82.28 GiB",
            StreamBadges(UHD_2160, listOf(DOLBY_VISION, HDR10), HEVC, REMUX, listOf(ATMOS, TRUEHD), SURROUND_7_1, gb(82.28), cached = false),
        )
        // A field-per-emoji style over other addons' results; its sizes are decimal, read here as binary.
        check(
            "[HW⚡] Addon A 2160p",
            "🎥 WEB-DL 🎞️ HEVC 🏷️ NOVA \n📺 HDR | DV 🎧 Atmos | DD+ 🔊 5.1\n📦 9.13 GB\n" +
                "📁 Example.Show.S01E03.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-NOVA.mkv",
            StreamBadges(UHD_2160, listOf(DOLBY_VISION, HDR), HEVC, WEB_DL, listOf(ATMOS, EAC3), SURROUND_5_1, gb(9.13), cached = true),
        )
        check(
            "[HW⏳] Addon B 1080p",
            "🎥 BluRay 🎞️ AVC 🏷️ ORBIT \n🎧 DD 🔊 5.1\n📦 12.9 GB\n📁 Sample.Road.2015.1080p.BluRay.DD5.1.x264-ORBIT.mkv",
            StreamBadges(FHD_1080, emptyList(), AVC, BLURAY, listOf(AC3), SURROUND_5_1, gb(12.9), cached = false),
        )
        // The tag line drops the 2.0 glued to AAC; the file name keeps it.
        check(
            "[HW⚡] Addon C 2160p",
            "🎥 WEB-DL 🎞️ HEVC 🏷️ NOVA \n📺 HLG 🎧 AAC \n📦 5.48 GB 📅 3d\n📁 Example.Nature.S01E01.2160p.WEB-DL.HLG.AAC2.0.HEVC-NOVA.mkv",
            StreamBadges(UHD_2160, listOf(HLG), HEVC, WEB_DL, listOf(AAC), STEREO, gb(5.48), cached = true),
        )
        check(
            "[HW⚡] Addon D 2160p",
            "📁 Example Dream (2010)\n🎥 BluRay 🎞️ HEVC 🏷️ LUMEN\n📺 10bit • HDR 🎧 DTS:X 🔊 7.1\n📦 32.2 GB",
            StreamBadges(UHD_2160, listOf(HDR), HEVC, BLURAY, listOf(DTS_X), SURROUND_7_1, gb(32.2), cached = true),
        )
        // File size / folder size.
        check(
            "[HW⏳] Example Streams 1080p",
            "📁 Kitchen Story S03 • E01\n🎥 WEB-DL 🎞️ AVC 🏷️ QUILL \n🎧 DD+ 🔊 5.1\n📦 2.94 GB / 29.4 GB",
            StreamBadges(FHD_1080, emptyList(), AVC, WEB_DL, listOf(EAC3), SURROUND_5_1, gb(2.94), cached = false),
        )
        // A custom formatter (an install's answer, 2026-10-04): name lines padded with no-break spaces and zero-width
        // joiners, the file's and the folder's size under one unit.
        check(
            "\u00a0\u00a0\u00a04K\u00a0\u200d\u200d\u00a0\u00a0\u00a0\u00a0\u200d\u200d\u200d⚡\u200d\u200d\n" +
                "\u00a0\u00a0〈Web\u200d-\u200ddl〉\u200d\u00a0\u00a0\u00a0\u00a0\u00a0\u200d\n\u00a0\u00a0★★★★★\u00a0\u00a0\u00a0",
            "✎  Example Show  s₀₁·ᴇ₀₁\n▣  HEVC  ✦ DV · HDR  \n♬  Atmos · DD+  ♯ 5.1\n❖ 11.4 / 188 GB · 24.6 ᴹᵇᵖˢ · 12d\n" +
                "⛉ Addon A · NOVA\n✓  ᴇɴ  · sᴜʙ  (ᴇɴ)   »   ᴡᴇʙ ᴛ₁ ᴀᴘᴘʟᴇ ᴛᴠ+ ₆₃₈₅",
            StreamBadges(UHD_2160, listOf(DOLBY_VISION, HDR), HEVC, WEB_DL, listOf(ATMOS, EAC3), SURROUND_5_1, gb(11.4), cached = true, StreamLanguages(listOf("en"), listOf("en"))),
        )
        check(
            "\u00a0\u00a0\u00a04K\u00a0\u200d\u200d\u00a0\u00a0\u00a0\u00a0\u200d\u200d\u200d⚡\u200d\u200d\n" +
                "\u00a0\u00a0〈Remux〉\u200d\u00a0\u00a0\u00a0\u00a0\u00a0\u200d\n\u00a0\u00a0★★★★★\u00a0\u00a0\u00a0",
            "✎  The Example Fi… (1994) \n▣  HEVC  ✦ DV · HDR10  \n♬  DD · DTS-HD MA  ♯ 5.1 · 2.0\n" +
                "◈ 58.3 / 117 GB · 54.6 ᴹᵇᵖˢ · 12d\n⛉ Addon A · ORBIT\n✓  ᴇɴ  · sᴜʙ  (ᴇɴ)   »   ʀᴇᴍᴜx ᴛ₁ ʜʏʙʀɪᴅ ₆₉₀₀",
            StreamBadges(UHD_2160, listOf(DOLBY_VISION, HDR10), HEVC, REMUX, listOf(DTS_HD_MA, AC3), SURROUND_5_1, gb(58.3), cached = true, StreamLanguages(listOf("en"), listOf("en"))),
        )
        // A minimal style: 🎫 instant, 🎟️ not.
        check(
            "✨ 4K 🎫 \nWEB-DL",
            "🔆 HDR10+  🔊 Atmos • DD+\n📦 6.76 GB ",
            StreamBadges(UHD_2160, listOf(HDR10_PLUS), null, WEB_DL, listOf(ATMOS, EAC3), null, gb(6.76), cached = true),
        )
        check(
            "N/A 🎟️ \nHDRIP",
            "🔊 DD\n📦 1.5 GB ",
            StreamBadges(source = HDRIP, audio = listOf(AC3), sizeBytes = gb(1.5), cached = false),
        )
        // Resolution as a word, the instant state on the description's last line.
        check(
            "🚀 FHD",
            "🎬 Example Anime 🍂 S01 🎞️ E01\n🎥 WEB-DL 🎞️ AV1 \n🎧 OPUS 🔊 2.0 \n📦 734 MB \n🏷️ NOVA\n⚡Ready",
            StreamBadges(FHD_1080, emptyList(), AV1, WEB_DL, listOf(OPUS), STEREO, mb(734.0), cached = true),
        )
        check(
            "🚀 FHD",
            "🎬 Sample Future (2017) \n🎥 WEBRip 📺 10bit 🎞️ HEVC \n📦 2.36 GB \n🏷️ ORBIT ",
            StreamBadges(FHD_1080, emptyList(), HEVC, WEBRIP, emptyList(), null, gb(2.36), cached = null),
        )
    }

    @Test
    fun labelledFields() {
        check(
            "Addon C (Instant) (2160p)",
            "Quality: WEB-DL\nName: Example.Show.S01E03.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-NOVA.mkv\nSize: 9.13 GB \nLanguages: ",
            StreamBadges(UHD_2160, listOf(DOLBY_VISION, HDR), HEVC, WEB_DL, listOf(ATMOS, EAC3), SURROUND_5_1, gb(9.13), cached = true),
        )
        check(
            "Addon C (Your Media)  (Instant) (1080p)",
            "Quality: WEB-DL\nName: Kitchen.Story.S03E01.Tomorrow.1080p.WEB-DL.DDP5.1.H.264-QUILL.mkv\nSize: 2.94 GB \nLanguages: ",
            StreamBadges(FHD_1080, emptyList(), AVC, WEB_DL, listOf(EAC3), SURROUND_5_1, gb(2.94), cached = true),
        )
        // Only the absence of "Instant" marks this one not instant; that isn't a marker, so the state stays unknown.
        check(
            "Addon C (720p)",
            "Quality: HDTV\nName: Example.Drama.2024.S01E01.720p.HDTV.x264-NOVA.mkv\nSize: 891.29 MB \nLanguages: ",
            StreamBadges(HD_720, emptyList(), AVC, HDTV, emptyList(), null, mb(891.29), cached = null),
        )
    }

    @Test
    fun noticeStreamsCarryNoBadges() {
        for ((name, description) in listOf("Example Streams\nAccount error" to "", "[HW🔄] Addon A Sync" to "", "[❌] Addon A" to "", "[⚠️] Addon A" to "")) {
            assertEquals(StreamBadges(), StreamBadgeParser.parse(name, description), name)
        }
    }

    @Test
    fun badgesReadInDisplayOrder() {
        val badges = StreamBadgeParser.parse(
            "[HW+] Example Streams\n4k DV | HDR",
            "Example.Show.S01E03.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-NOVA\n💾 8.51 GB",
        )
        assertEquals(
            listOf("Instant", "4K", "DV", "HDR", "HEVC", "WEB-DL", "Atmos", "DD+", "5.1", "8.5 GB"),
            badges.display().map { it.text },
        )
        assertEquals(listOf("Not instant"), StreamBadges(cached = false).display().map { it.text })
    }
}
