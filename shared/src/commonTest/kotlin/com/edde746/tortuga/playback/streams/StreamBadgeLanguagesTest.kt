package com.edde746.tortuga.playback.streams

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The languages of a stream's text ([StreamLanguages]) as addons write them: a flag line, formatters' language and
 * subtitle fields, a `🌐` line, and the release names themselves (invented titles in the addons' shapes).
 */
class StreamBadgeLanguagesTest {
    private fun languages(name: String?, description: String?) = StreamBadgeParser.parse(name, description).languages

    private fun languages(description: String) = languages(null, description)

    @Test
    fun flagLine() {
        assertEquals(
            StreamLanguages(audio = listOf("en", "it")),
            languages("Example Streams\n1080p", "Example.Film.2023.1080p.BluRay.x265-ORBIT\n💾 4.3 GB\n🇬🇧 / 🇮🇹"),
        )
        assertEquals(
            StreamLanguages(audio = listOf("en", "it", "es", "es-419", "fr", "de", "hi"), multiAudio = true),
            languages(
                "Example Streams\n1080p",
                "The.Example.Film.1994.1080p.Bluray.MULTi.DTS-HD.Master.H264-NOVA\n💾 19.56 GB\n" +
                    "Multi Audio / 🇬🇧 / 🇮🇹 / 🇪🇸 / 🇲🇽 / 🇫🇷 / 🇩🇪 / 🇮🇳",
            ),
        )
        // "Multi Subs" heads the flag line: the flags after it are still the languages, not the subtitles.
        assertEquals(
            StreamLanguages(audio = listOf("en", "hi"), multiSubtitles = true),
            languages("Example Streams\n4k", "The Example Film (1994) 4K Remastered 1080p 10bit Bluray…\n💾 8.17 GB\nMulti Subs / 🇬🇧 / 🇮🇳"),
        )
        assertEquals(
            StreamLanguages(audio = listOf("en", "pt"), multiAudio = true),
            languages("Example Streams\n1080p", "Example Series 1ª Temporada Completa [1080p] [WEB-DL] [DUAL]\n💾 1.3 GB\nDual Audio / 🇬🇧 / 🇵🇹"),
        )
        assertEquals(StreamLanguages(audio = listOf("zh-TW")), languages("💾 1 GB\n🇹🇼"))
        // The flag line takes in the subtitles' languages: one the release name gives as a subtitle alone is one.
        assertEquals(
            StreamLanguages(subtitles = listOf("en", "ar", "fr")),
            languages("Example Streams\nBRRip", "The Example Film 1994 BRRip Sub Eng-Arabic-French-Swedis\n💾 1.73 GB\n🇬🇧 / 🇫🇷 / 🇸🇦"),
        )
    }

    @Test
    fun releaseNameCodesAndNames() {
        assertEquals(listOf("it", "en"), languages("Example.Film.2023.iTA-ENG.1080p.BluRay.x265.10bit.AC3.5.1-ORBIT").audio)
        assertEquals(listOf("fr", "en"), languages("Les Exemples 1994 - 1080p FR EN x264 ac3 nova..mkv").audio)
        assertEquals(listOf("en", "fr", "es", "de", "it"), languages("The Example Film 1994 Bluray 1080p AV1 EN/FR/ES/DE/ITA OPUS 5.1-NOVA").audio)
        assertEquals(listOf("cs", "en"), languages("Příklad / Example 1. serie (2019)(CZ/EN)[2160p][HDR10/DV]").audio)
        assertEquals(listOf("es", "en"), languages("Example Series Season-Temporada 1 Spanish+English Audio x265").audio)
        assertEquals(listOf("hi", "en"), languages("The Example Film (1994) 4K Remastered 1080p [Org DDP 2.0 Hindi + DDP 5.1 English] MSubs").audio)
        assertEquals(listOf("en"), languages("Example Series 2019 S01E01 1080p BluRay x265 SDR DDP 5 1 English Kai-NOVA").audio)
        assertEquals(listOf("fr"), languages("Example.Series.S01E01.FRENCH.WEBRiP.XviD-NOVA.avi").audio)
        assertEquals(listOf("ru"), languages("Example.Series.S01E01.MVO.AVO.rus.HDRip.mp4").audio)
        assertEquals(listOf("pt-BR"), languages("Movie.2020.1080p.WEB-DL.PT-BR.x264").audio)
        assertEquals(listOf("ja"), languages("Example.Anime.1988.1080p.BluRay.Japanese.DTS-HD.MA.5.1.x264").audio)
    }

    @Test
    fun severalTracksWithoutNames() {
        assertEquals(StreamLanguages(audio = listOf("fr"), multiAudio = true), languages("Example.Series.S01E01.MULTi.VFF.2160p.HDR.DV.WEBRip.x265-NOVA.mkv"))
        assertEquals(StreamLanguages(audio = listOf("es-419"), multiAudio = true), languages("Example.Series.S01E01.2019.WEB-DL.1080p-Dual-Lat"))
        assertEquals(StreamLanguages(multiAudio = true), languages("Example.Series.S01E01.1080p.AMZN.WEB-DL.DDP5.1.x265.DUAL-NOVA.mkv"))
        assertEquals(StreamLanguages(multiSubtitles = true), languages("Example.Series.S01.1080p.DS4K.DV.HDR10.BDRip.AV1.Opus.mSubs-NOVA"))
    }

    @Test
    fun subtitlesFollowTheirWord() {
        assertEquals(
            StreamLanguages(audio = listOf("it", "en"), subtitles = listOf("it", "en")),
            languages("Il Film Esempio (1994) 2160p H265 HDR10 DV ITA DTS-HD MA 5.1 ITA ENG AC3 5.1 SUB ITA ENG"),
        )
        assertEquals(
            StreamLanguages(audio = listOf("it", "en"), subtitles = listOf("it", "en")),
            languages("Example Series 1x01 1-23-45 (2019) AC3 5.1 ITA.ENG 1080p H265 sub ita.eng NOVA.mkv"),
        )
        assertEquals(
            StreamLanguages(subtitles = listOf("en", "ar", "fr")),
            languages("The Example Film 1994 BRRip Sub Eng-Arabic-French-Swedis"),
        )
        // Codes before SUBS are the audio's: the word only says there are subtitles.
        assertEquals(StreamLanguages(audio = listOf("it", "en")), languages("Il.film.esempio.1994.UHDRip.2160p.Hevc.HDR.AC3.ITA.ENG.SUBS.NOVA.mkv"))
        assertEquals(StreamLanguages(subtitles = listOf("fr")), languages("Show.S01E01.VOSTFR.1080p.WEB.x264"))
        assertEquals(StreamLanguages(audio = listOf("ja"), subtitles = listOf("en")), languages("[Group] Show - 01 (1080p) [Japanese Audio] eSubs"))
    }

    @Test
    fun formatterLanguageFields() {
        // A flag style: the language flags, then "Subs / " and the subtitles' flags.
        assertEquals(
            StreamLanguages(audio = listOf("ja"), subtitles = listOf("en", "fr")),
            languages("[HW+] Example Streams 1080p", "Example.Anime.S01E01.1080p.WEB-DL.mkv\n💾700 MiB\n🇯🇵 Subs / 🇬🇧 / 🇫🇷"),
        )
        // Labelled fields: names after their labels.
        assertEquals(
            StreamLanguages(audio = listOf("en", "it"), subtitles = listOf("en", "pt-BR")),
            languages("Addon C (Instant) (1080p)", "Quality: BluRay\nName: Movie.mkv\nLanguages: English, Italian | Subtitles: English, Portuguese (Brazil)"),
        )
        // Field-per-emoji styles: 🌎 names, 📝 subtitles, no space between them.
        assertEquals(
            StreamLanguages(audio = listOf("en", "es-419"), subtitles = listOf("en"), multiAudio = true),
            languages("[HW⚡] Addon A 1080p", "🎥 WEB-DL 🎞️ HEVC\n🌎 Multi | English | Latino📝 English\n📁 Movie.mkv"),
        )
        // Compact styles: flags after 🌐 or 🗣️, 🌎 is "Multi".
        assertEquals(
            StreamLanguages(audio = listOf("en", "ko"), subtitles = listOf("en"), multiAudio = true),
            languages("🚀 FHD", "🎧 DD+ 🔊 5.1 🗣️ 🌎 / 🇬🇧 / 🇰🇷📝 🇬🇧\n📦 2.1 GB"),
        )
        // A custom formatter's small capitals: languages, then the subtitles in brackets.
        assertEquals(
            StreamLanguages(audio = listOf("en"), subtitles = listOf("en")),
            languages("4K", "✎  Example Show  s₀₁·ᴇ₀₁\n▣  HEVC  ✦ DV · HDR\n✓  ᴇɴ  · sᴜʙ  (ᴇɴ)   »   ᴡᴇʙ ᴛ₁ ᴀᴘᴘʟᴇ ᴛᴠ+ ₆₃₈₅"),
        )
        assertEquals(
            StreamLanguages(audio = listOf("ja", "en"), subtitles = listOf("en", "es"), multiAudio = true),
            languages("4K", "✓  ᴊᴀ · ᴇɴ · ᴅᴜᴏ  · sᴜʙ  (ᴇɴ · ᴇs)   »   ʙᴅ"),
        )
    }

    @Test
    fun globeLineAndFlags() {
        assertEquals(StreamLanguages(audio = listOf("en", "it")), languages("Addon B HW ⏳ 1080p", "📺 BluRay 🎞️ hevc\n📦 4.30 GB\n🌐 English + Italian"))
        assertEquals(StreamLanguages(audio = listOf("en", "it")), languages("[HW⚡] Addon A 1080p", "📄 Example.Film.2023.1080p\n💾 4.3 GB\n🇬🇧/🇮🇹"))
    }

    @Test
    fun titleWordsThatLookLikeLanguagesDontCount() {
        assertEquals(StreamLanguages(), languages("IT.Lives.2031.1080p.BluRay.x264-NOVA"))
        assertEquals(StreamLanguages(), languages("The.Italian.Garden.2003.1080p.BluRay.x264"))
        assertEquals(StreamLanguages(), languages("The.English.Lighthouse.1996.1080p.BluRay.x264"))
        assertEquals(StreamLanguages(), languages("Dan.in.the.Hills.2007.720p.BluRay.x264"))
        assertEquals(StreamLanguages(), languages("Campus.Days.S01E02.Spanish.101.1080p.WEB-DL.DD5.1.H.264"))
        assertEquals(StreamLanguages(), languages("The.French.Window.2021.2160p.WEB-DL.DDP5.1"))
        assertEquals(StreamLanguages(), languages("CIDADE.DE.VIDRO.2002.1080p.BluRay"))
        assertEquals(StreamLanguages(), languages("Dual.2031.1080p.WEB-DL.H264"))
        assertEquals(StreamLanguages(), languages("Imdb top 263 movies hindi english gdrive"))
        // Two-letter codes count in capitals only: in any other case they are words.
        assertEquals(StreamLanguages(), languages("It.Ends.Tonight.2031.1080p.WEB-DL.DDP5.1"))
        assertEquals(StreamLanguages(), languages("Movie.2020.it.ends.tonight.1080p"))
    }
}
