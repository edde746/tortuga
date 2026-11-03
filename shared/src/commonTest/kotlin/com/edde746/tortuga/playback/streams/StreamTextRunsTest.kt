package com.edde746.tortuga.playback.streams

import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.playback.streams.StreamTextStyle.PLAIN
import com.edde746.tortuga.playback.streams.StreamTextStyle.SMALL_CAPS
import com.edde746.tortuga.playback.streams.StreamTextStyle.SUBSCRIPT
import com.edde746.tortuga.playback.streams.StreamTextStyle.SUPERSCRIPT
import com.edde746.tortuga.playback.streams.StreamTextStyle.SYMBOL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stream description's styled Unicode as plain letters in styled runs ([StreamText.runs]), over the lines of an
 * custom formatter's styled lines (shared's fixtures/streams/styled-texts) and the texts most people see.
 */
class StreamTextRunsTest {
    private fun runs(text: String) = StreamText.runs(text).map { it.style to it.text }

    @Test
    fun smallCapitalsAreLowerCaseSmallCapsRuns() {
        assertEquals(
            listOf(
                SYMBOL to "✓", PLAIN to " ", SMALL_CAPS to "en", PLAIN to " · ", SMALL_CAPS to "sub", PLAIN to " (",
                SMALL_CAPS to "en", PLAIN to ") » ", SMALL_CAPS to "remux", PLAIN to " ", SMALL_CAPS to "t", SUBSCRIPT to "1",
                PLAIN to " ", SUBSCRIPT to "6900",
            ),
            runs("✓ ᴇɴ · sᴜʙ (ᴇɴ) » ʀᴇᴍᴜx ᴛ₁ ₆₉₀₀"),
        )
        // The stand-ins for the small capitals Unicode lacks or the formatter missed count only beside real ones.
        assertEquals(
            listOf(
                SMALL_CAPS to "special", PLAIN to " ", SMALL_CAPS to "edition", PLAIN to " ", SMALL_CAPS to "obfuscated",
                PLAIN to " ", SMALL_CAPS to "lq", PLAIN to " x264 ғалаба",
            ),
            runs("ꜱᴘᴇᴄɪᴀʟ ᴇᴅɪᴛɪᴏɴ ᴏʙғᴜsᴄᴀᴛᴇᴅ ʟǫ x264 ғалаба"),
        )
    }

    @Test
    fun aWordWithOtherLettersStaysAsWritten() {
        assertEquals(
            listOf(SYMBOL to "✎", PLAIN to " Spy×family s", SUBSCRIPT to "01", PLAIN to "·", SMALL_CAPS to "e", SUBSCRIPT to "01"),
            runs("✎ Spy×family s₀₁·ᴇ₀₁"),
        )
        assertEquals(listOf(PLAIN to "sᴜʙtitles WEB T", SUBSCRIPT to "4"), runs("sᴜʙtitles WEB T₄"))
    }

    @Test
    fun superscriptsAreRaisedRunsOfPlainCharacters() {
        assertEquals(
            listOf(SYMBOL to "◈", PLAIN to " 58.4 / 117 GB · 54.6 ", SUPERSCRIPT to "Mbps", PLAIN to " · 12d"),
            runs("◈ 58.4 / 117 GB · 54.6 ᴹᵇᵖˢ · 12d"),
        )
        assertEquals(listOf(PLAIN to "1080P ", SUPERSCRIPT to "(p2p)", PLAIN to " ", SUPERSCRIPT to "(live)"), runs("1080P ⁽ᵖ²ᵖ⁾ ⁽ˡᶦᵛᵉ⁾"))
    }

    @Test
    fun textSymbolsAreSymbolRunsAndEmojiStayPlain() {
        // ☁ with variation selector 15 is text; ⚡ is an emoji by default, ⚙ with variation selector 16 one by choice.
        assertEquals(
            listOf(SYMBOL to "☁\uFE0E", PLAIN to " Paper Lantern ⚡ ⚙\uFE0F Example Library ", SYMBOL to "⛉", PLAIN to " [EX] × 2"),
            runs("☁\uFE0E Paper Lantern ⚡ ⚙\uFE0F Example Library ⛉ [EX] × 2"),
        )
    }

    @Test
    fun textsWithoutStyledUnicodeAreOnePlainRun() {
        val texts = listOf(
            // A release name, its size and its languages' flags (fixtures/streams/plain-texts).
            "The.Copper.Orchard.1971.1080p.35mm.4K.Scan.x264.AAC\n💾 3.5 GB\n🇬🇧 / 🇫🇷",
            // Emoji-labelled facts.
            "🎥 BluRay REMUX 🎞️ HEVC 🏷️ Example\n📺 DV | HDR10 🎧 Atmos | TrueHD 🔊 7.1\n📦 58.3 GB / 117 GB (54.6 Mbps)📅 8d 🔍 Library",
            // A line per emoji-led group.
            "🎬 The Copper Orchard (1971)\n🗣️ 🇬🇧 / 🇫🇷📝 🇬🇧\n⚡Ready 🔍Library\n❌ Not Ready",
            // Labelled plain text.
            "Quality: BluRay REMUX\nSize: 58.3 GB | Source: Library\nLanguages: English | Subtitles: English, French",
        )
        for (text in texts) assertEquals(listOf(PLAIN to text), runs(text))
    }

    @Test
    fun plainTextDescriptionsWithoutStyledUnicodeAreOnePlainRun() {
        val descriptions = corpusDescriptions("plain-texts")
        assertTrue(descriptions.size > 1000, "${descriptions.size} plain-text descriptions")
        for (text in descriptions.filterNot { STYLED.containsMatchIn(it) }) assertEquals(listOf(PLAIN to text), runs(text))
    }

    @Test
    fun styledTextDescriptionsKeepNoStyledUnicodeInTheirPlainRuns() {
        val descriptions = corpusDescriptions("styled-texts")
        assertTrue(descriptions.size > 1000, "${descriptions.size} styled-text descriptions")
        for (text in descriptions) {
            for (run in StreamText.runs(text)) {
                if (run.style == PLAIN) {
                    val left = STYLED.find(run.text)
                    assertTrue(left == null, "\"${left?.value}\" left in the plain run \"${run.text}\" of \"$text\"")
                }
            }
        }
    }

    private companion object {
        /** Small capitals, superscript and subscript characters. */
        val STYLED = Regex("[ᴀ-ᴢʙɢʜɪʟɴʀʏꜰꜱꞯᴬ-ᵜᶜᶠᶦᶻʰ-ʸˡ-ˣⁱⁿⱽ⁰-⁾¹²³₀-₎ₐ-ₜᵢ-ᵥⱼ]")
    }

    /** Every tidied description of the stream lists in fixtures/streams/[folder]. */
    private fun corpusDescriptions(folder: String): List<String> =
        FixtureCorpus.files.filterKeys { it.startsWith("streams/$folder/") }.values.flatMap { json ->
            (AddonResponse.decode(json).content as ResourceContent.Streams).items.mapNotNull { StreamText.description(it.description) }
        }
}
