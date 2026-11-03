package com.edde746.tortuga.playback.streams

import com.edde746.tortuga.model.Stream

/**
 * Reads display badges out of a stream's free-text `name` and `description` (`title` in older addons), as stream
 * addons and aggregators format them, and its file name
 * (`behaviorHints.filename`), which often names what the row leaves out, and the audio and subtitle languages they name
 * ([StreamLanguageReader]). It only reads; the stream list keeps the addon's order.
 *
 * It goes by vocabulary, not by line or emoji position: addons let users restyle their output, and one emoji can mean
 * different fields. Tokens match between non-alphanumeric boundaries, so words that merely contain one don't count
 * (`HDRip` is not HDR, `DVDRip` is not DV, `AV10` is not AV1); audio tokens may run into their channel count
 * (`DDP5.1`, `AAC2.0`), source tags into the resolution (`BD1080P`). Where one value is wanted (resolution, codec,
 * size, cached state), the name wins over the description and the description over the file name: addons put their
 * summary in the name and the raw release name, with whatever it contains, in the description. Where the addons
 * disagree on detail (HDR10 shown as HDR, DTS-X as "DTS Lossless"), the most specific token anywhere wins.
 *
 * The size and the cached state come from the name and the description alone: no addon marks the cache state in a
 * file name, and a size there is a season pack's or a bitrate as often as the file's.
 *
 * A stream list parses every stream of every answer, so it stays cheap: the text is case-folded once and the patterns
 * match it case-sensitively, and each pattern runs only when the text holds one of its keys (a plain substring every
 * match contains).
 */
object StreamBadgeParser {
    /** The badges of [stream]'s name, description and file name. */
    fun parse(stream: Stream): StreamBadges = parse(stream.name, stream.description, stream.behaviorHints.filename)

    fun parse(name: String?, description: String?, filename: String? = null): StreamBadges {
        val cleanName = name?.clean()?.takeIf { it.isNotBlank() }
        val shown = listOfNotNull(cleanName, description?.clean()?.takeIf { it.isNotBlank() }).map(::Text)
        val fields = shown + listOfNotNull(filename?.clean()?.takeIf { it.isNotBlank() }).map(::Text)
        val all = fields.singleOrNull() ?: Text(fields.joinToString("\n") { it.raw }, fields.joinToString("\n") { it.low })
        return StreamBadges(
            resolution = fields.firstNotNullOfOrNull(::resolution),
            hdr = hdr(all),
            videoCodec = fields.firstNotNullOfOrNull { field -> firstMatch(field, codecs) },
            source = sources.firstOrNull { (_, rule) -> rule.containsMatchIn(all) }?.first,
            audio = audio(all),
            channels = channels(all),
            sizeBytes = size(shown),
            cached = shown.withIndex().firstNotNullOfOrNull { (index, field) -> cached(field, isName = index == 0 && cleanName != null) },
            languages = StreamLanguageReader.read(all.raw),
        )
    }

    /** Whether [text] names a size the way [parse] reads one. */
    internal fun namesSize(text: String): Boolean = sizeText.containsMatchIn(Text(text))

    /** Custom formatters wrap markers in zero-width characters; `⚡️` and `⚡` differ only by a variation selector. */
    private fun String.clean() = filterNot { it in '\u200B'..'\u200D' || it == '\uFE0F' }

    /** Within one field a pixel count beats a word: "4K Remastered" titles are often 1080p files. */
    private fun resolution(field: Text): ResolutionBadge? =
        pixelResolution.find(field)?.let { pixels[it.groupValues[1]] }
            ?: resolutionWords.firstOrNull { (_, rule) -> rule.containsMatchIn(field) }?.first

    private fun hdr(text: Text): List<HdrBadge> = buildList {
        if (dolbyVision.containsMatchIn(text)) add(HdrBadge.DOLBY_VISION)
        when {
            hdr10Plus.containsMatchIn(text) -> add(HdrBadge.HDR10_PLUS)
            hdr10.containsMatchIn(text) -> add(HdrBadge.HDR10)
            hdr.containsMatchIn(text) -> add(HdrBadge.HDR)
        }
        if (hlg.containsMatchIn(text)) add(HdrBadge.HLG)
    }

    private fun audio(text: Text): List<AudioBadge> {
        val found = audioFormats.filter { (_, rule) -> rule.containsMatchIn(text) }.mapTo(mutableSetOf()) { it.first }
        if (ddpAtmos.containsMatchIn(text)) found += listOf(AudioBadge.EAC3, AudioBadge.ATMOS)
        // Some addons' "DTS Lossless" stands for DTS-HD MA or DTS:X; a raw token says which.
        if (dtsLossless.containsMatchIn(text) && AudioBadge.DTS_X !in found) found += AudioBadge.DTS_HD_MA
        // The specific DTS format replaces the plain one; `DTS-HD MA` also contains `DTS-HD`.
        if (AudioBadge.DTS_X in found || AudioBadge.DTS_HD_MA in found || AudioBadge.DTS_HD in found) found -= AudioBadge.DTS
        if (AudioBadge.DTS_HD_MA in found) found -= AudioBadge.DTS_HD
        return AudioBadge.entries.filter { it in found }
    }

    private fun channels(text: Text): ChannelsBadge? {
        val layouts = codecChannels.findAll(text).map { it.groupValues[1] + "." + it.groupValues[2] } +
            taggedChannels.findAll(text).map { it.groupValues[1] + "." + it.groupValues[2] } +
            looseChannels.findAll(text).map { it.value } +
            channelCount.findAll(text).map { countLayouts.getValue(it.groupValues[1]) }
        return layouts.mapNotNull { layout -> ChannelsBadge.entries.firstOrNull { it.label == layout } }.maxOrNull()
    }

    /**
     * A size behind a size marker (💾, 📦, `Size:`) first, else the first size named; in `2.94 GB / 29.4 GB` and
     * an aggregator's `11.4 / 188 GB` the file's.
     */
    private fun size(fields: List<Text>): Long? {
        val match = fields.firstNotNullOfOrNull { markedSize.find(it) } ?: fields.firstNotNullOfOrNull { sizeText.find(it) }
        return match?.let { bytes(it.groupValues[1], it.groupValues[2]) }
    }

    /**
     * 1024-based whatever the label: most addons write GiB as "GB" (an aggregator's cloud-drive styles are
     * decimal, which reads 7 % large here). [unit] is folded: `t`, `g`, `m` or `k`.
     */
    private fun bytes(number: String, unit: String): Long? {
        // "1,5 GB" uses a decimal comma; "1,024 MB" a thousands separator.
        val normalized = if (thousands.matches(number)) number.replace(",", "") else number.replace(',', '.')
        val value = normalized.toDoubleOrNull() ?: return null
        val shift = when (unit) {
            "t" -> 40
            "g" -> 30
            "m" -> 20
            else -> 10
        }
        return (value * (1L shl shift)).toLong().takeIf { it > 0 }
    }

    /**
     * Not-ready markers first: "Not cached" contains "cached". "Instant" and "Your Media" only count in the
     * name, where no film title ("Instant Family") appears. A symbol marker counts as the addons write it: after a
     * service's short name (`[XX⚡]`, `XX ⚡️`), on its own (`4K ⚡`) or before the word it marks (`⚡Ready (XX)`); one
     * glued to any other word decorates that word (`⚡Name⚡`).
     */
    private fun cached(field: Text, isName: Boolean): Boolean? = when {
        uncachedMarkers.any { it.containsMatchIn(field) } -> false
        cachedMarkers.any { it.containsMatchIn(field) } -> true
        isName && cachedNameWords.containsMatchIn(field) -> true
        else -> null
    }

    private fun <T> firstMatch(text: Text, rules: List<Pair<T, Rule>>): T? =
        rules.mapNotNull { (value, rule) -> rule.find(text)?.let { value to it.range.first } }.minByOrNull { it.second }?.first

    /**
     * A field as written ([raw]) and case-folded character for character ([low]: each one's upper case's lower case,
     * as an IGNORE_CASE match compares them, so `ſ` is `s` and `K` (Kelvin) is `k`). Both have the same length, so a
     * match's position is the same in either.
     */
    private class Text(val raw: String, val low: String = CharArray(raw.length) { raw[it].uppercaseChar().lowercaseChar() }.concatToString())

    /**
     * A pattern tried only on a text whose folded form holds one of [keys] (lower case; every match contains one). It
     * matches the folded text, so it is written in lower case; a [raw] one matches the text as written (it tells case
     * apart).
     */
    private class Rule(pattern: String, private val keys: List<String>, private val raw: Boolean = false) {
        private val regex = Regex(pattern)

        private fun target(text: Text): String? = if (keys.any { it in text.low }) (if (raw) text.raw else text.low) else null

        fun containsMatchIn(text: Text): Boolean = target(text)?.let(regex::containsMatchIn) ?: false

        fun find(text: Text): MatchResult? = target(text)?.let(regex::find)

        fun findAll(text: Text): Sequence<MatchResult> = target(text)?.let(regex::findAll) ?: emptySequence()
    }

    // ---- patterns ----

    private const val START = "(?<![A-Za-z0-9])"
    private const val END = "(?![A-Za-z0-9])"

    /** An audio token may run into its channel count (`DDP5.1`) but not into more letters. */
    private const val AUDIO_END = "(?![A-Za-z])"

    /**
     * Follows a channel count: the number isn't a size. `5.1 GB`, `7.1GiB` and an aggregator's `5.1 / 188 GB` are a
     * file's size as [SIZE] reads it (here in either case), not its channels.
     */
    private const val NOT_SIZE = "(?!(?:\\s*/\\s*\\d{1,5}(?:[.,]\\d{1,3})?)?\\s?[KkMmGgTt][Ii]?[Bb](?![A-Za-z]))"

    private fun rule(pattern: String, vararg keys: String) = Rule(pattern, keys.toList())

    private fun token(pattern: String, vararg keys: String, end: String = END) = Rule("$START(?:$pattern)$end", keys.toList())

    /** Letters may come right before the pixel count (`BD1080P`, `848x480p`), digits not. */
    private val pixelResolution = rule("(?<![0-9])(2160|1440|1080|720|480)[pi]$END", "0p", "0i")
    private val pixels = mapOf(
        "2160" to ResolutionBadge.UHD_2160,
        "1440" to ResolutionBadge.QHD_1440,
        "1080" to ResolutionBadge.FHD_1080,
        "720" to ResolutionBadge.HD_720,
        "480" to ResolutionBadge.SD_480,
    )

    /** Addons write `4k`, `✨ 4K`, `🔥4K UHD`, `📀 2K`, `🚀 FHD`. */
    private val resolutionWords = listOf(
        ResolutionBadge.UHD_2160 to token("4k|uhd", "4k", "uhd"),
        ResolutionBadge.QHD_1440 to token("2k|qhd", "2k", "qhd"),
        ResolutionBadge.FHD_1080 to token("fhd", "fhd"),
    )

    private val dolbyVision = token("dv|dovi|dolby[ ._-]?vision", "dv", "dovi", "dolby")
    private val hdr10Plus = rule("${START}hdr10(?:\\+|plus|p)(?![A-Za-z0-9+])", "hdr10")
    private val hdr10 = rule("${START}hdr10(?![A-Za-z0-9+])", "hdr10")
    private val hdr = rule("${START}hdr(?![A-Za-z0-9+])", "hdr")
    private val hlg = token("hlg", "hlg")

    private val codecs = listOf(
        VideoCodecBadge.HEVC to token("hevc|[xh]\\.?265", "hevc", "265"),
        VideoCodecBadge.AVC to token("avc|[xh]\\.?264", "avc", "264"),
        VideoCodecBadge.AV1 to token("av1", "av1"),
        VideoCodecBadge.VP9 to token("vp9", "vp9"),
        VideoCodecBadge.XVID to token("xvid", "xvid"),
    )

    /** A source tag may run into the resolution after it (`BD1080P`, `WEBDL1080p`). */
    private const val SOURCE_END = "(?:(?![A-Za-z0-9])|(?=(?:2160|1440|1080|720|576|480)[pi](?![A-Za-z0-9])))"

    private fun source(pattern: String, vararg keys: String) = token(pattern, *keys, end = SOURCE_END)

    /**
     * Most specific first: `BluRay REMUX` is a remux, `WEB-DLRip` a rip, `WEB HDRip` an HDRip. Release names' plain
     * `WEB` is a WEB-DL, unless it starts `WEB-DL`/`WEBRip`; Italian names' `DLMux`/`WEBMux` (web video, Italian audio muxed
     * in) are too. A plain `DVD`
     * (`DVD5`, `DVD9`, `DVDR`) comes last: after a rip it names the size the rip was cut to (`BDRip.720p.DVD9`), and
     * `HD DVD` is no DVD.
     */
    private val sources = listOf(
        SourceBadge.REMUX to source("(?:bd|uhd|dvd)?remux", "remux"),
        SourceBadge.BLURAY to source("blu[ ._-]?ray(?:[ ._-]?rip)?|bd[ ._-]?(?:rip|mux)|br[ ._-]?rip|uhd[ ._-]?rip|bdmv|bd", "blu", "bd", "br", "uhd"),
        SourceBadge.WEBRIP to source("web[ ._-]?(?:dl[ ._-]?)?rip", "web"),
        SourceBadge.HDRIP to source("f?hd[ ._-]?rip", "rip"),
        SourceBadge.WEB_DL to rule(
            "$START(?:web(?:[ ._-]?(?:dl|hd))?(?:[ ._-]?mux)?|dl[ ._-]?mux)" +
                "(?:(?![A-Za-z0-9]|[ ._-]?(?:dl|rip)(?![A-Za-z]))|(?=(?:2160|1440|1080|720|576|480)[pi](?![A-Za-z0-9])))",
            "web", "mux",
        ),
        SourceBadge.DVDRIP to source("dvd[ ._-]?rip", "dvd"),
        SourceBadge.HDTV to source("hdtv(?:[ ._-]?(?:rip|mux))?", "hdtv"),
        SourceBadge.TVRIP to source("(?:tv|sat|dvb)[ ._-]?rip|dsr(?:ip)?|pdtv|sdtv", "rip", "dsr", "pdtv", "sdtv"),
        SourceBadge.DCP to source("dcp(?:[ ._-]?rip)?", "dcp"),
        SourceBadge.DVD to source("(?<!hd[ ._-])dvd(?:[ ._-]?(?:[59]|r))?", "dvd"),
    )

    private val audioFormats = listOf(
        AudioBadge.ATMOS to token("atmos", "atmos", end = AUDIO_END),
        AudioBadge.TRUEHD to token("true[ ._-]?hd", "true", end = AUDIO_END),
        // Not followed by a digit either: `DTS.x264` is DTS audio and an x264 encode.
        AudioBadge.DTS_X to token("dts[ ._:-]?x", "dts"),
        AudioBadge.DTS_HD_MA to token("dts[ ._-]?hd[ ._-]?ma", "dts", end = AUDIO_END),
        AudioBadge.FLAC to token("flac", "flac", end = AUDIO_END),
        AudioBadge.DTS_HD to token("dts[ ._-]?hd", "dts", end = AUDIO_END),
        AudioBadge.DTS to token("dts", "dts", end = AUDIO_END),
        AudioBadge.EAC3 to token("dd\\+|ddp|e-?ac-?3|dolby[ ._-]?digital[ ._-]?plus", "dd", "ac", "dolby", end = AUDIO_END),
        // `DD` but not `DD+`; `AC3`/`AC-3` but not the tail of `E-AC-3`; "Dolby Digital" but not "Dolby Digital Plus".
        AudioBadge.AC3 to token("dd(?![+])|(?<!e-)ac-?3|dolby[ ._-]?digital(?![ ._-]?plus)", "dd", "ac", "dolby", end = AUDIO_END),
        AudioBadge.AAC to token("aac", "aac", end = AUDIO_END),
        AudioBadge.MP3 to token("mp3", "mp3", end = AUDIO_END),
        // A film can be called Opus (Mr. Holland's Opus): the codec needs its channel count or the tag's capitals.
        AudioBadge.OPUS to Rule("$START(?:[Oo][Pp][Uu][Ss](?=[ ._-]?[1-8]\\.[0-2]$NOT_SIZE)|OPUS$AUDIO_END)", listOf("opus"), raw = true),
    )
    private val ddpAtmos = token("ddpa", "ddpa", end = AUDIO_END)
    private val dtsLossless = token("dts[ ._-]?lossless", "lossless")

    private val codecChannels = rule(
        "$START(?:dd\\+?|ddpa?|e-?ac-?3|ac-?3|aac|dts(?:[ ._-]?hd)?(?:[ ._-]?(?:ma|hra|es))?|dts[ ._:-]?x|true[ ._-]?hd|opus|flac|l?pcm|mp3|atmos)" +
            "[ ._-]?([1-9])[ ._]([0-2])(?![0-9])$NOT_SIZE",
        "dd", "ac", "dts", "true", "opus", "flac", "pcm", "mp3", "atmos",
    )

    /** In formatter tag lines: `🔊 2.0`, `🔊 AAC • 2.0`. */
    private val taggedChannels = Rule("(?:🔊|•)\\s*([1-9])\\.([0-2])(?![0-9.])$NOT_SIZE", listOf("🔊", "•"), raw = true)

    /**
     * `5.1`/`7.1` on their own, but not inside a longer number (`Babylon.5.1993`, `S05.1080p`, `v2.5.1`) nor as a size
     * (`5.1 GB`); a lone `2.0` is too often a title or a version (the film "2.0") to count.
     */
    private val looseChannels = Rule("(?<![A-Za-z0-9])(?<![0-9]\\.)[57]\\.1(?![0-9]|\\.[0-9])$NOT_SIZE", listOf("5.1", "7.1"), raw = true)
    private val channelCount = token("([268])ch", "ch")
    private val countLayouts = mapOf("2" to "2.0", "6" to "5.1", "8" to "7.1")

    /** One size, or a file's and its folder's under one unit (`11.4 / 188 GB`): the first number, with the unit. */
    private const val SIZE = "(\\d{1,5}(?:[.,]\\d{1,3})?)(?:\\s*/\\s*\\d{1,5}(?:[.,]\\d{1,3})?)?\\s?([tgmk])i?b(?![A-Za-z])"
    private val sizeText = rule("(?<![A-Za-z0-9.,])$SIZE", "tb", "gb", "mb", "kb", "ib")
    private val markedSize = rule("(?:💾|📦|size:)\\s*$SIZE", "💾", "📦", "size:")
    private val thousands = Regex("""^\d+,\d{3}$""")

    /** Short names as an addon prints them before `+` (instant) and ` download` (not ready). */
    private const val PLUS_SERVICES = "rd|pm|ad|dl|ed|oc|tb|putio|hw"

    /** Every short name the addons print before an instant or not-ready marker. */
    private const val SERVICES = "$PLUS_SERVICES|pp|pkp|trb|sdr|st|trn|qb|db|dbr|en"

    /**
     * [symbols] as a cache marker ([cached]): after a service's short name, on its own, or glued to the start of one
     * of [words].
     */
    private fun marker(symbols: String, vararg keys: String, words: String = SERVICES) = Rule(
        "(?:$START(?:$SERVICES)\\s*|(?<![A-Za-z0-9]))(?:$symbols)(?:(?:$words)$END|(?![A-Za-z0-9]))",
        keys.toList(),
    )

    private val uncachedMarkers = listOf(
        rule("\\[(?:$SERVICES) download]", " download]"),
        // ⏳/⬇ only after a service: an addon may print `🧲  ⏳` for a stream with no service at all.
        rule("$START(?:$SERVICES)\\s*[⏳⬇]", "⏳", "⬇"),
        marker("🎟", "🎟"),
        rule("(?<![A-Za-z])(?:un[ -]?cached|not[ -]?cached|not[ -]?ready)(?![A-Za-z])", "cached", "ready"),
    )
    private val cachedMarkers = listOf(
        marker("⚡|🎫", "⚡", "🎫", words = "$SERVICES|ready"),
        rule("\\[(?:$SERVICES)\\+]", "+]"),
        rule("$START(?:$PLUS_SERVICES)\\+", "+"),
        rule("(?<![A-Za-z])cached(?![A-Za-z])", "cached"),
    )
    private val cachedNameWords = rule("(?<![A-Za-z])(?:instant|your media)(?![A-Za-z])", "instant", "your media")
}
