package com.edde746.tortuga.playback.streams

import kotlin.test.Test
import kotlin.test.assertEquals

/** A row's badges less the ones its text already shows ([notShownIn]), on addon-shaped text (see [StreamTextTest]). */
class StreamBadgeShownTest {
    /** The badges a row shows for [name] and [description], tidied as the row shows them. */
    private fun shown(name: String, description: String): List<String> {
        val text = listOfNotNull(StreamText.name(name), StreamText.description(description)).joinToString("\n")
        return StreamBadgeParser.parse(name, description).display().notShownIn(text).map { it.text }
    }

    @Test
    fun aCustomFormatterWritesEveryBadgeItself() {
        val name = "\u00a0\u00a0\u00a04K\u00a0\u200d\u200d\u00a0\u00a0\u00a0\u00a0\u200d\u200d\u200d⚡\u200d\u200d\n" +
            "\u00a0\u00a0〈Web\u200d-\u200ddl〉\u200d\u00a0\u00a0\u00a0\u00a0\u00a0\u200d\n\u00a0\u00a0★★★★★\u00a0\u00a0\u00a0"
        val description = "✎  Example Show  s₀₁·ᴇ₀₁\n▣  HEVC  ✦ DV · HDR  \n♬  Atmos · DD+  ♯ 5.1\n❖ 11.4 / 188 GB · 24.6 ᴹᵇᵖˢ · 12d\n" +
            "⛉ Addon A · NOVA\n✓  ᴇɴ  · sᴜʙ  (ᴇɴ)   »   ᴡᴇʙ ᴛ₁ ᴀᴘᴘʟᴇ ᴛᴠ+ ₆₃₈₅"
        // 4K, DV, HDR, HEVC, WEB-DL (written `Web‍-‍dl`), Atmos, DD+, 5.1 and the size are all in the text; ⚡ isn't
        // the word "Instant".
        assertEquals(listOf("Instant"), shown(name, description))

        val remux = "\u00a0\u00a0\u00a04K\u00a0\u200d\u200d\u00a0\u00a0\u00a0\u00a0\u200d\u200d\u200d⚡\u200d\u200d\n" +
            "\u00a0\u00a0〈Remux〉\u200d\u00a0\u00a0\u00a0\u00a0\u00a0\u200d\n\u00a0\u00a0★★★★★\u00a0\u00a0\u00a0"
        assertEquals(
            listOf("Instant"),
            shown(
                remux,
                "✎  The Example Fi… (1994) \n▣  HEVC  ✦ DV · HDR10  \n♬  DD · DTS-HD MA  ♯ 5.1 · 2.0\n" +
                    "◈ 58.3 / 117 GB · 54.6 ᴹᵇᵖˢ · 12d\n⛉ Addon A · ORBIT\n✓  ᴇɴ  · sᴜʙ  (ᴇɴ)   »   ʀᴇᴍᴜx ᴛ₁ ʜʏʙʀɪᴅ ₆₉₀₀",
            ),
        )
    }

    @Test
    fun aReleaseNameKeepsWhatItOnlyImplies() {
        // H.265 is HEVC, DDP5.1 is DD+ and 5.1: read from the release name, not written as the badges say them.
        assertEquals(
            listOf("Instant", "HEVC", "DD+", "5.1"),
            shown(
                "[HW+] Example Streams\n4k DV | HDR",
                "Example.Show.S01E03.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-NOVA\n💾 8.51 GB",
            ),
        )
        // Blu-ray and DTS-HD.MA are spelled otherwise than the badges; 1080p and 7.1 are written as they are.
        assertEquals(
            listOf("AVC", "BluRay", "DTS-HD MA"),
            shown("Example Streams\n1080P", "Sample.Film.Part.Two.2024.1080p.Blu-ray.x264.DTS-HD.MA.7.1-LUMEN\n💾 25 GB"),
        )
    }

    @Test
    fun labelsMatchWholeTokensOnly() {
        val badges = listOf(
            StreamBadge(BadgeKind.HDR, "DV"),
            StreamBadge(BadgeKind.HDR, "HDR10"),
            StreamBadge(BadgeKind.AUDIO, "DD"),
            StreamBadge(BadgeKind.CHANNELS, "5.1"),
            StreamBadge(BadgeKind.RESOLUTION, "1080p"),
        )
        // DVD isn't DV, HDR10+ isn't HDR10, DD+ isn't DD, DDP5.1's 5.1 runs into the codec; 1080P is 1080p.
        assertEquals(listOf("DV", "HDR10", "DD", "5.1"), badges.notShownIn("DVDRip HDR10+ DD+ DDP5.1 1080P").map { it.text })
        assertEquals(emptyList(), badges.notShownIn("dv · hdr10 · dd 5.1 · 1080p").map { it.text })
    }

    @Test
    fun aSizeShowsWheneverTheTextNamesOne() {
        val size = listOf(StreamBadge(BadgeKind.SIZE, "8.5 GB"))
        assertEquals(emptyList(), size.notShownIn("💾 8.51 GB"))
        assertEquals(emptyList(), size.notShownIn("◈ 8.5 / 117 GB · 54.6 ᴹᵇᵖˢ"))
        assertEquals(size, size.notShownIn("Movie.2160p.WEB-DL.mkv"))
    }
}
