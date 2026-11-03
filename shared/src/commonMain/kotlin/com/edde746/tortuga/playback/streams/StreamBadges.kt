package com.edde746.tortuga.playback.streams

/**
 * What a stream's `name`, `description` and file name (`behaviorHints.filename`) say about the file, as
 * [StreamBadgeParser] reads them for the stream list. Every field is a hint from free text; null or empty means the text
 * doesn't say.
 *
 * @param hdr in display order: Dolby Vision, then the most specific HDR format (HDR10+ over HDR10 over HDR), then HLG.
 * @param audio in display order, most capable first; a more specific DTS or Dolby format replaces the plain one.
 * @param channels the largest layout named.
 * @param cached true for a stream its addon marks instant (plays at once), false for one it marks not ready.
 * @param languages the audio and subtitle languages the text names ([StreamLanguageReader]); the list shows none of them.
 */
data class StreamBadges(
    val resolution: ResolutionBadge? = null,
    val hdr: List<HdrBadge> = emptyList(),
    val videoCodec: VideoCodecBadge? = null,
    val source: SourceBadge? = null,
    val audio: List<AudioBadge> = emptyList(),
    val channels: ChannelsBadge? = null,
    val sizeBytes: Long? = null,
    val cached: Boolean? = null,
    val languages: StreamLanguages = StreamLanguages(),
)

/**
 * The languages a stream's text names, as canonical BCP 47 tags (`LanguageTags`: `ITA`, `ita`, "Italian" and 🇮🇹 are
 * all `it`), each once, in the order named. Empty says nothing: an addon may leave English out when it is the only
 * language, and most release names name none.
 *
 * @param audio the languages the file's audio is in, as far as the text tells them from subtitles' (flags and bare
 *   codes read as audio).
 * @param subtitles the languages named as subtitles' (`SUB ITA ENG`, "Subs / 🇬🇧", `📝 French`, `VOSTFR`).
 * @param multiAudio the text says the file has several audio tracks without naming them all (`MULTi`, "Dual Audio").
 * @param multiSubtitles the text says it has subtitles in several languages without naming them ("Multi Subs", `MSubs`).
 */
data class StreamLanguages(
    val audio: List<String> = emptyList(),
    val subtitles: List<String> = emptyList(),
    val multiAudio: Boolean = false,
    val multiSubtitles: Boolean = false,
)

/** Technical labels stay as release names write them in every language. */
enum class ResolutionBadge(val label: String) {
    UHD_2160("4K"),
    QHD_1440("1440p"),
    FHD_1080("1080p"),
    HD_720("720p"),
    SD_480("480p"),
}

enum class HdrBadge(val label: String) {
    DOLBY_VISION("DV"),
    HDR10_PLUS("HDR10+"),
    HDR10("HDR10"),
    HDR("HDR"),
    HLG("HLG"),
}

enum class VideoCodecBadge(val label: String) {
    HEVC("HEVC"),
    AVC("AVC"),
    AV1("AV1"),
    VP9("VP9"),
    XVID("XviD"),
}

/**
 * In order of fidelity, best first. Release names' plain `WEB` tag reads as [WEB_DL]; [DVD] is a disc's own video
 * (`DVD5`, `DVD9`, `DVDR`), not re-encoded as a [DVDRIP] is.
 */
enum class SourceBadge(val label: String) {
    REMUX("REMUX"),
    BLURAY("BluRay"),
    WEB_DL("WEB-DL"),
    WEBRIP("WEBRip"),
    HDTV("HDTV"),
    HDRIP("HDRip"),
    DVD("DVD"),
    DVDRIP("DVDRip"),
    TVRIP("TVRip"),
    DCP("DCP"),
}

enum class AudioBadge(val label: String) {
    ATMOS("Atmos"),
    TRUEHD("TrueHD"),
    DTS_X("DTS:X"),
    DTS_HD_MA("DTS-HD MA"),
    FLAC("FLAC"),
    DTS_HD("DTS-HD"),
    DTS("DTS"),
    EAC3("DD+"),
    AC3("DD"),
    AAC("AAC"),
    OPUS("Opus"),
    MP3("MP3"),
}

enum class ChannelsBadge(val label: String) {
    STEREO("2.0"),
    SURROUND_5_1("5.1"),
    SURROUND_7_1("7.1"),
}
