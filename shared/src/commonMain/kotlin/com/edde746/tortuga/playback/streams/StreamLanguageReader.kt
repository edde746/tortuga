package com.edde746.tortuga.playback.streams

import com.edde746.tortuga.lang.LanguageTags

/**
 * Reads the audio and subtitle languages out of a stream's text ([StreamBadgeParser] hands it every field), as the
 * addons write them:
 * - flags (`🇬🇧 / 🇮🇹`): an addon's language line, or its emoji lists. A language line can list the languages the
 *   addon's title parser found, subtitles' too, and leave English out when it is the only one; they read as
 *   audio, since the line doesn't tell them apart, except one the release name gives as a subtitle's alone.
 * - labelled lists: `🌎 English | Italian`, `🗣️ 🇬🇧`, `Languages: English, Italian`, `📝 English` and
 *   `Subtitles: French` (subtitles), `🌐 English + Italian`, and the small-capital codes of custom
 *   formatters (`✓ ᴇɴ · ɪᴛ · sᴜʙ (ᴇɴ · ꜰʀ)`).
 * - release-name words: codes (`ITA`, `eng`, `FR`), names (`French`, `iTALiAN`), dub labels (`VFF`, `TRUEFRENCH`,
 *   `Lat`), and what follows a subtitle word (`SUB ITA ENG`, `Sub Eng-Arabic`, `VOSTFR`, `eSubs`).
 * - several tracks without names: `MULTi`, "Multi Audio", "Dual Audio", `DUAL` ([StreamLanguages.multiAudio]); "Multi
 *   Subs", `MSubs` ([StreamLanguages.multiSubtitles]).
 *
 * A film's title holds words that read as languages ("The Italian Job", "IT", "Dan in Real Life", Community's episode
 * "Spanish 101"), so outside a labelled list a run of language words counts only beside a technical word: a
 * resolution, a codec, a source, an audio format or channel count, a subtitle word, or a flag (`ITA.ENG.1080p`,
 * `DDP 5.1 English`, `Spanish+English Audio`). Two-letter codes count in capitals only (`FR EN`), or in a labelled list.
 */
internal object StreamLanguageReader {
    fun read(text: String): StreamLanguages {
        val audio = LinkedHashSet<String>()
        // Audio languages a word or a labelled list named, not only a bare flag line.
        val namedAudio = HashSet<String>()
        val subtitles = LinkedHashSet<String>()
        var multiAudio = false
        var multiSubtitles = false
        for (line in text.lineSequence()) {
            val tokens = tokens(line)
            var labelled = false
            var inSubtitles = false
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                val next = tokens.getOrNull(i + 1)
                when (token.kind) {
                    Kind.AUDIO_LABEL -> {
                        labelled = true
                        inSubtitles = false
                    }
                    Kind.SUBTITLE_LABEL -> {
                        labelled = true
                        inSubtitles = true
                    }
                    Kind.LIST_MARK -> if (i == 0) labelled = true
                    // A globe: a label before names (`🌎 English | Multi`), "Multi" itself among flags.
                    Kind.GLOBE -> if (next?.kind == Kind.WORD) {
                        labelled = true
                        inSubtitles = false
                    } else {
                        multiAudio = true
                    }
                    Kind.FLAG, Kind.WORD -> {
                        val word = token.low
                        when {
                            token.kind == Kind.WORD && (word == "multi" || word == "dual") && next?.low in SUBTITLE_WORDS -> {
                                multiSubtitles = true
                                i++
                            }
                            token.kind == Kind.WORD && word in MULTI_SUBTITLES -> multiSubtitles = true
                            token.kind == Kind.WORD && i == 0 && word in AUDIO_LABELS -> labelled = true
                            token.kind == Kind.WORD && word in SUBTITLE_WORDS -> {
                                if (i == 0) labelled = true
                                inSubtitles = true
                                SUBTITLE_WORDS.getValue(word)?.let(subtitles::add)
                            }
                            token.kind == Kind.WORD && multiAudio(tokens, i, labelled) -> {
                                multiAudio = true
                                if (next?.low == "audio") i++
                            }
                            else -> {
                                // A run of language words, read whole: it counts or not as one.
                                val start = i
                                val run = mutableListOf<String>()
                                while (i < tokens.size) {
                                    var code = language(tokens[i], labelled) ?: break
                                    if (code == "pt" && tokens.getOrNull(i + 1)?.low in BRAZIL) {
                                        code = "pt-BR"
                                        i++
                                    }
                                    run += code
                                    i++
                                }
                                if (run.isEmpty()) {
                                    // A word that isn't a language ends a subtitle list (`SUB ITA ENG) - GEGE`).
                                    if (token.kind == Kind.WORD) inSubtitles = false
                                    i = start + 1
                                    continue
                                }
                                val flags = tokens.subList(start, i).all { it.kind == Kind.FLAG }
                                val counts = labelled || inSubtitles || flags || tokens.getOrNull(start - 1).anchors() || tokens.getOrNull(i).anchors()
                                when {
                                    !counts -> {}
                                    inSubtitles -> subtitles.addAll(run)
                                    else -> {
                                        audio.addAll(run)
                                        if (labelled || !flags) namedAudio.addAll(run)
                                    }
                                }
                                continue
                            }
                        }
                    }
                }
                i++
            }
        }
        // A language line's flags list the subtitles' languages too: one the text names as subtitles alone is a subtitle.
        return StreamLanguages(audio.filter { it in namedAudio || it !in subtitles }, subtitles.toList(), multiAudio, multiSubtitles)
    }

    /**
     * Whether the word at [index] says the file has several audio tracks: `MULTi`/`MULTI` as release names write it,
     * "Multi"/"Dual" before "Audio", `DUAL` in capitals, or either beside a technical word (`1080p-Dual-Lat`); in a
     * labelled list "Multi", "Dual Audio" and the small capitals' `ᴅᴜᴏ`.
     */
    private fun multiAudio(tokens: List<Token>, index: Int, labelled: Boolean): Boolean {
        val token = tokens[index]
        if (token.low != "multi" && token.low != "dual" && !(labelled && token.low == "duo")) return false
        return labelled || token.raw.startsWith("MULT") || token.raw == "DUAL" || tokens.getOrNull(index + 1)?.low == "audio" ||
            tokens.getOrNull(index - 1).anchors() || tokens.getOrNull(index + 1).anchors()
    }

    /** The language [token] names, as a canonical tag; two-letter codes only in capitals unless [labelled]. */
    private fun language(token: Token, labelled: Boolean): String? = when (token.kind) {
        Kind.FLAG -> FLAGS[token.low]
        Kind.WORD -> NAMES[token.low] ?: THREE_LETTER[token.low]
            ?: TWO_LETTER[token.low]?.takeIf { labelled || token.raw.all { it in 'A'..'Z' } }
        else -> null
    }

    /** A token that makes a language word beside it count: technical, a flag, or a subtitle or multi-track word. */
    private fun Token?.anchors(): Boolean = when (this?.kind) {
        null -> false
        Kind.FLAG, Kind.SUBTITLE_LABEL, Kind.AUDIO_LABEL -> true
        Kind.WORD -> low in SUBTITLE_WORDS || low in MULTI_SUBTITLES || low == "multi" || low == "dual" || technical.matches(low)
        else -> false
    }

    private enum class Kind { WORD, FLAG, AUDIO_LABEL, SUBTITLE_LABEL, LIST_MARK, GLOBE }

    /** A word as written ([raw]) and in lower case ([low]); a flag's [low] is its region code (`gb`). */
    private class Token(val kind: Kind, val raw: String, val low: String)

    /**
     * [line] as words (letters and digits), flags and the list marks; everything else separates them. Small capitals
     * read as capitals.
     */
    private fun tokens(line: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val word = StringBuilder()
        fun endWord() {
            if (word.isEmpty()) return
            val raw = word.toString()
            tokens += Token(Kind.WORD, raw, raw.lowercase())
            word.clear()
        }
        var i = 0
        while (i < line.length) {
            val codePoint = codePointAt(line, i)
            val width = if (codePoint > 0xFFFF) 2 else 1
            val smallCapital = SMALL_CAPITALS[line[i]]
            when {
                smallCapital != null -> word.append(smallCapital)
                width == 1 && line[i].isLetterOrDigit() -> word.append(line[i])
                codePoint in REGIONAL_INDICATORS -> {
                    endWord()
                    val second = if (i + 2 < line.length) codePointAt(line, i + 2) else -1
                    if (second in REGIONAL_INDICATORS) {
                        val region = charArrayOf('a' + (codePoint - REGIONAL_INDICATORS.first), 'a' + (second - REGIONAL_INDICATORS.first)).concatToString()
                        tokens += Token(Kind.FLAG, region, region)
                        i += 2
                    }
                }
                else -> {
                    endWord()
                    MARKS[codePoint]?.let { tokens += Token(it, "", "") }
                }
            }
            i += width
        }
        endWord()
        return tokens
    }

    private fun codePointAt(text: String, index: Int): Int {
        val high = text[index]
        if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
        }
        return high.code
    }

    private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

    private val MARKS = mapOf(
        0x1F30E to Kind.GLOBE, // 🌎
        0x1F310 to Kind.AUDIO_LABEL, // 🌐
        0x1F5E3 to Kind.AUDIO_LABEL, // 🗣
        0x1F4DD to Kind.SUBTITLE_LABEL, // 📝
        0x2713 to Kind.LIST_MARK, // ✓
        0x26FF to Kind.LIST_MARK, // ⛿
    )

    private val SMALL_CAPITALS: Map<Char, Char> = "ᴀAʙBᴄCᴅDᴇEꜰFɢGʜHɪIᴊJᴋKʟLᴍMɴNᴏOᴘPǫQʀRꜱSᴛTᴜUᴠVᴡWʏYᴢZ"
        .chunked(2).associate { it[0] to it[1] }

    /**
     * Words that start a list of subtitle languages, with the language some name themselves: `VOSTFR` (French
     * subtitles), `eSubs` (English). A first word of a line that is one labels the line (`Subtitles: French`).
     */
    private val SUBTITLE_WORDS: Map<String, String?> = mapOf(
        "sub" to null, "subs" to null, "subbed" to null, "subtitle" to null, "subtitles" to null, "subtitulado" to null,
        "legendado" to null, "vostfr" to "fr", "esub" to "en", "esubs" to "en",
    )

    /** A line's first word that labels it a list of audio languages (`Languages: English, Italian`). */
    private val AUDIO_LABELS = setOf("language", "languages", "audio", "lang")

    private val MULTI_SUBTITLES = setOf("msub", "msubs", "multisub", "multisubs")

    private val BRAZIL = setOf("br", "brazil", "brasil")

    /** Region codes of the flags addons write for languages, as the addons' emoji maps use them. */
    private val FLAGS: Map<String, String> = mapOf(
        "gb" to "en", "us" to "en", "au" to "en", "ca" to "en", "jp" to "ja", "ru" to "ru", "it" to "it", "pt" to "pt",
        "br" to "pt-BR", "es" to "es", "mx" to "es-419", "kr" to "ko", "cn" to "zh", "tw" to "zh-TW", "hk" to "zh-HK",
        "fr" to "fr", "de" to "de", "nl" to "nl", "in" to "hi", "bd" to "bn", "pk" to "pa", "pl" to "pl", "lt" to "lt",
        "lv" to "lv", "ee" to "et", "cz" to "cs", "sk" to "sk", "si" to "sl", "hu" to "hu", "ro" to "ro", "bg" to "bg",
        "rs" to "sr", "hr" to "hr", "ua" to "uk", "gr" to "el", "dk" to "da", "fi" to "fi", "se" to "sv", "no" to "no",
        "tr" to "tr", "sa" to "ar", "ir" to "fa", "il" to "he", "vn" to "vi", "id" to "id", "my" to "ms", "th" to "th",
        "ph" to "fil",
    ).mapValues { (_, tag) -> checkNotNull(LanguageTags.canonical(tag)) }

    /** Language names as English and release names write them, and a few in their own language. */
    private val NAMES: Map<String, String> = mapOf(
        "english" to "en", "italian" to "it", "italiano" to "it", "french" to "fr", "francais" to "fr", "français" to "fr",
        "truefrench" to "fr", "vff" to "fr", "vf2" to "fr", "vfi" to "fr", "vfq" to "fr-CA", "spanish" to "es",
        "español" to "es", "espanol" to "es", "castellano" to "es", "castilian" to "es", "latino" to "es-419",
        "german" to "de", "deutsch" to "de", "japanese" to "ja", "russian" to "ru", "ukrainian" to "uk", "korean" to "ko",
        "chinese" to "zh", "mandarin" to "zh", "cantonese" to "yue", "taiwanese" to "zh-TW", "hindi" to "hi",
        "tamil" to "ta", "telugu" to "te", "malayalam" to "ml", "kannada" to "kn", "bengali" to "bn", "punjabi" to "pa",
        "marathi" to "mr", "gujarati" to "gu", "urdu" to "ur", "portuguese" to "pt", "brazilian" to "pt-BR",
        "polish" to "pl", "czech" to "cs", "slovak" to "sk", "slovakian" to "sk", "hungarian" to "hu", "turkish" to "tr",
        "arabic" to "ar", "dutch" to "nl", "flemish" to "nl", "swedish" to "sv", "norwegian" to "no", "danish" to "da",
        "finnish" to "fi", "icelandic" to "is", "greek" to "el", "hebrew" to "he", "thai" to "th", "vietnamese" to "vi",
        "indonesian" to "id", "malay" to "ms", "romanian" to "ro", "bulgarian" to "bg", "serbian" to "sr",
        "croatian" to "hr", "slovenian" to "sl", "lithuanian" to "lt", "latvian" to "lv", "estonian" to "et",
        "persian" to "fa", "farsi" to "fa", "filipino" to "fil", "tagalog" to "tl", "catalan" to "ca",
    ).mapValues { (_, tag) -> checkNotNull(LanguageTags.canonical(tag)) }

    /**
     * The three-letter codes release names use, in any case (`ITA`, `Ita`, `ita`); the ones that are common words in
     * titles (`mal`, `tam`, `per`, `cat`, `lit`) are left out. `LAT` is Latin American Spanish, as release names write it.
     */
    private val THREE_LETTER: Map<String, String> = mapOf(
        "eng" to "en", "ita" to "it", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de", "spa" to "es",
        "esp" to "es", "lat" to "es-419", "rus" to "ru", "ukr" to "uk", "jpn" to "ja", "jap" to "ja", "kor" to "ko",
        "chi" to "zh", "chs" to "zh-Hans", "cht" to "zh-Hant", "hin" to "hi", "por" to "pt", "pol" to "pl", "cze" to "cs",
        "ces" to "cs", "hun" to "hu", "tur" to "tr", "ara" to "ar", "dut" to "nl", "nld" to "nl", "swe" to "sv",
        "nor" to "no", "dan" to "da", "fin" to "fi", "gre" to "el", "ell" to "el", "heb" to "he", "tha" to "th",
        "vie" to "vi", "rum" to "ro", "ron" to "ro", "bul" to "bg", "srp" to "sr", "hrv" to "hr",
    ).mapValues { (_, tag) -> checkNotNull(LanguageTags.canonical(tag)) }

    /** Two-letter codes, counted in capitals only (`FR EN`, `CZ/EN`): in lower case they are words (`it`, `de`). */
    private val TWO_LETTER: Map<String, String> = mapOf(
        "en" to "en", "it" to "it", "fr" to "fr", "vf" to "fr", "es" to "es", "de" to "de", "pt" to "pt", "ru" to "ru",
        "nl" to "nl", "pl" to "pl", "cz" to "cs", "hu" to "hu", "tr" to "tr", "jp" to "ja", "ja" to "ja", "kr" to "ko",
        "ko" to "ko", "cn" to "zh", "zh" to "zh", "ua" to "uk", "sv" to "sv", "da" to "da", "ar" to "ar", "hi" to "hi",
    ).mapValues { (_, tag) -> checkNotNull(LanguageTags.canonical(tag)) }

    /**
     * Words of a release name that are no title's (it splits at every non-alphanumeric character, so `DDP5.1` is
     * `ddp5` and `1`): resolutions, dynamic ranges, codecs, sources, audio formats, channel counts' digits, containers,
     * "audio" and dub words.
     */
    private val technical = Regex(
        "\\d{3,4}[pi]|[48]k|uhd|fhd|s?hd|sdr|hdr\\w*|dv|dovi|hlg|1[02]b(?:it)?|8bit|[xh]26[45]|26[45]|h|hevc|avc|av1|vp9|xvid|divx|" +
            "aac\\d?|e?ac3|ac|ddp?\\d?|ddpa\\d?|dts\\w*|ma|truehd|atmos|opus|flac|mp3|l?pcm|\\d?ch|blu\\w*|bd\\w*|br\\w*rip|brrip|" +
            "web\\w*|dl|hdtv|remux|\\w*rip|\\w*mux|amzn|nf|dsnp|hmax|atvp|repack|proper|mkv|mp4|avi|audio|dub|dubbed|\\d",
    )
}
