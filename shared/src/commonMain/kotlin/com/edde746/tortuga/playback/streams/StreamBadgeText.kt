package com.edde746.tortuga.playback.streams

import com.edde746.tortuga.format.formatByteSize
import com.edde746.tortuga.strings.T

enum class BadgeKind { CACHED, RESOLUTION, HDR, VIDEO_CODEC, SOURCE, AUDIO, CHANNELS, SIZE }

/** One preformatted chip for the stream list; [kind] lets a view style it (say, an accent for [BadgeKind.CACHED]). */
data class StreamBadge(val kind: BadgeKind, val text: String)

/**
 * The badges as the stream list shows them, in this order: cached state, resolution, HDR formats, video codec,
 * source, audio formats, channels, size. Words come from `T` (`streams.badge`), sizes from [formatByteSize]; format
 * names are the same in every language.
 */
fun StreamBadges.display(): List<StreamBadge> = buildList {
    cached?.let { add(StreamBadge(BadgeKind.CACHED, if (it) T.streams.badge.instant else T.streams.badge.notInstant)) }
    resolution?.let { add(StreamBadge(BadgeKind.RESOLUTION, it.label)) }
    hdr.forEach { add(StreamBadge(BadgeKind.HDR, it.label)) }
    videoCodec?.let { add(StreamBadge(BadgeKind.VIDEO_CODEC, it.label)) }
    source?.let { add(StreamBadge(BadgeKind.SOURCE, it.label)) }
    audio.forEach { add(StreamBadge(BadgeKind.AUDIO, it.label)) }
    channels?.let { add(StreamBadge(BadgeKind.CHANNELS, it.label)) }
    sizeBytes?.let { add(StreamBadge(BadgeKind.SIZE, formatByteSize(it))) }
}

/**
 * The badges [text] doesn't already show, [text] being a row's name and description as it shows them: a badge whose
 * label the text writes only repeats it. Labels match case-insensitively between boundaries that aren't letters or
 * digits (nor a `+` after: "DV" isn't shown by "DVD", "HDR10" not by "HDR10+"), zero-width characters ignored
 * (an addon writes `Web‍-‍dl`). A size is shown whenever the text names one, written as the addon writes it ("8.51
 * GB", "11.4 / 188 GB"), since that's where [StreamBadgeParser] reads it.
 */
fun List<StreamBadge>.notShownIn(text: String): List<StreamBadge> {
    val plain = text.filterNot { it.isZeroWidth() || it == '\uFE0F' }
    return filterNot { badge ->
        if (badge.kind == BadgeKind.SIZE) StreamBadgeParser.namesSize(plain) else plain.writes(badge.text)
    }
}

private fun String.writes(label: String): Boolean {
    var from = 0
    while (true) {
        val at = indexOf(label, from, ignoreCase = true)
        if (at < 0) return false
        val end = at + label.length
        if (getOrNull(at - 1)?.isAsciiAlphanumeric() != true && getOrNull(end)?.let { it.isAsciiAlphanumeric() || it == '+' } != true) return true
        from = at + 1
    }
}

private fun Char.isAsciiAlphanumeric() = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
