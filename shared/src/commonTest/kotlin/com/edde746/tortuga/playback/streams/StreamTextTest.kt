package com.edde746.tortuga.playback.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Names and descriptions in the shapes addons sent them (2026-10-04), with invented titles and names: an addon install
 * with a custom formatter, and an addon's name-over-release format (as in [StreamBadgeFormatsTest]).
 */
class StreamTextTest {
    @Test
    fun customFormatterNamePaddingCollapsesOntoOneLine() {
        // Each line is padded with no-break spaces and zero-width joiners to fill Stremio's name column.
        assertEquals(
            "4K ⚡ 〈Web\u200d-\u200ddl〉 ★★★★★",
            StreamText.name(
                "\u00a0\u00a0\u00a04K\u00a0\u200d\u200d\u00a0\u00a0\u00a0\u00a0\u200d\u200d\u200d⚡\u200d\u200d\n" +
                    "\u00a0\u00a0〈Web\u200d-\u200ddl〉\u200d\u00a0\u00a0\u00a0\u00a0\u00a0\u200d\n" +
                    "\u00a0\u00a0★★★★★\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0",
            ),
        )
        assertEquals(
            "4K ⚡ 〈Remux〉 ★★★☆",
            StreamText.name(
                "\u00a0\u00a0\u00a04K\u00a0\u200d\u200d\u00a0\u00a0\u00a0\u00a0\u200d\u200d\u200d⚡\u200d\u200d\n" +
                    "\u00a0\u00a0〈Remux〉\u200d\u00a0\u00a0\u00a0\u00a0\u00a0\u200d\n" +
                    "\u00a0\u00a0★★★☆\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0",
            ),
        )
        // The same name with its line breaks already turned into spaces.
        assertEquals(
            "1080P ⚡ 〈Remux〉 ★★★",
            StreamText.name(
                "1080P\u200d\u200d\u00a0\u00a0\u00a0\u00a0\u200d\u200d\u200d⚡\u200d\u200d \u00a0\u00a0〈Remux〉\u200d" +
                    "\u00a0\u00a0\u00a0\u00a0\u00a0\u200d \u00a0\u00a0★★★",
            ),
        )
    }

    @Test
    fun customFormatterDescriptionKeepsItsLinesWithSingleSpaces() {
        assertEquals(
            "✎ Example Show s₀₁·ᴇ₀₁\n▣ HEVC ✦ DV · HDR\n♬ Atmos · DD+ ♯ 5.1\n❖ 11.4 / 188 GB · 24.6 ᴹᵇᵖˢ · 12d\n⛉ Addon A · NOVA\n" +
                "✓ ᴇɴ · sᴜʙ (ᴇɴ) » ᴡᴇʙ ᴛ₁ ᴀᴘᴘʟᴇ ᴛᴠ+ ₆₃₈₅",
            StreamText.description(
                "✎  Example Show  s₀₁·ᴇ₀₁\n▣  HEVC  ✦ DV · HDR  \n♬  Atmos · DD+  ♯ 5.1\n❖ 11.4 / 188 GB · 24.6 ᴹᵇᵖˢ · 12d\n" +
                    "⛉ Addon A · NOVA\n✓  ᴇɴ  · sᴜʙ  (ᴇɴ)   »   ᴡᴇʙ ᴛ₁ ᴀᴘᴘʟᴇ ᴛᴠ+ ₆₃₈₅",
            ),
        )
        assertEquals(
            "✎ The Example Fi… (1994)\n▣ HEVC ✦ DV · HDR10\n♬ DD+ · DTS-HD MA ♯ 5.1 · 2.0\n◈ 58.4 / 117 GB · 54.6 ᴹᵇᵖˢ · 12d\n" +
                "⛉ Addon A · ORBIT\n✓ ᴇɴ · sᴜʙ (ᴇɴ) » ₅₈₅₀",
            StreamText.description(
                "✎  The Example Fi… (1994) \n▣  HEVC  ✦ DV · HDR10  \n♬  DD+ · DTS-HD MA  ♯ 5.1 · 2.0\n" +
                    "◈ 58.4 / 117 GB · 54.6 ᴹᵇᵖˢ · 12d\n⛉ Addon A · ORBIT\n✓  ᴇɴ  · sᴜʙ  (ᴇɴ)   »    ₅₈₅₀",
            ),
        )
    }

    @Test
    fun releaseNameTextKeepsItsWords() {
        assertEquals("[HW+] Example Streams 4k DV | HDR", StreamText.name("[HW+] Example Streams\n4k DV | HDR"))
        val description = "Example.Show.S01E03.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-NOVA\n💾 8.51 GB ⚙️ Addon A"
        assertEquals(description, StreamText.description(description))
        // A season pack: the pack's name, then the file's name, then the stats; the flags' line after a blank one.
        assertEquals(
            "Kitchen.Story.S03.1080p.WEB-DL.DDP5.1.H.264-QUILL[pack]\nKitchen.Story.S03E01.Tomorrow.1080p.WEB-DL.DDP5.1.H.264-QUILL.mkv\n" +
                "💾 2.73 GB ⚙️ Addon A\n🇬🇧 / 🇮🇹",
            StreamText.description(
                "Kitchen.Story.S03.1080p.WEB-DL.DDP5.1.H.264-QUILL[pack]\r\nKitchen.Story.S03E01.Tomorrow.1080p.WEB-DL.DDP5.1.H.264-QUILL.mkv\n" +
                    "💾 2.73 GB ⚙️ Addon A\n\n  \u00a0\n🇬🇧 / 🇮🇹",
            ),
        )
    }

    @Test
    fun zeroWidthCharactersBetweenVisibleOnesStay() {
        // An emoji sequence (a family: three emoji joined by ZWJs) and a variation selector are left whole.
        assertEquals("👨\u200d👩\u200d👧 Family ⚡\ufe0f", StreamText.name("\u200d👨\u200d👩\u200d👧\u2003Family\u2002⚡\ufe0f\u200b"))
        // A zero-width run beside a space is part of the space; at a line's ends it goes.
        assertEquals("a b\nc", StreamText.description("\ufeffa\u200b \u200cb\u2060\n\u200dc\t"))
    }

    @Test
    fun nothingVisibleIsNoText() {
        assertNull(StreamText.name(null))
        assertNull(StreamText.name(""))
        assertNull(StreamText.name("\u00a0\u200d\n \u200b\n\u3000"))
        assertNull(StreamText.description(" \n\n\u00a0"))
    }
}
