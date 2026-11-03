package com.edde746.tortuga.playback.streams

import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.settings.StreamCodec
import com.edde746.tortuga.data.settings.StreamQuality
import com.edde746.tortuga.data.settings.StreamRange
import com.edde746.tortuga.model.Stream

/**
 * The profile's stream filters, applied to every stream list and to auto-play's pick. A stream is hidden when, read
 * from its text ([StreamBadgeParser]):
 * 1. its resolution is in [hiddenQualities], its dynamic range in [hiddenRanges] or its video codec in [hiddenCodecs];
 * 2. else its file is smaller than [minSizeBytes] or larger than [maxSizeBytes] (the addon's `videoSize`, else the size
 *    its text names; a stream of no known size passes);
 * 3. else its name, description or file name matches the exclude pattern;
 * 4. else, with an include pattern, it doesn't match it.
 *
 * Patterns are regular expressions matched anywhere in the stream's name, description and file name
 * (`behaviorHints.filename`), ignoring case, with line breaks read as spaces (so `1080p.*HEVC` spans the name and the
 * description). A blank pattern filters nothing, and so does one that doesn't compile: [includeInvalid] and
 * [excludeInvalid] say so for the settings screen. Nothing here throws.
 */
class StreamFilter(
    val hiddenQualities: Set<StreamQuality>,
    includePattern: String,
    excludePattern: String,
    val hiddenRanges: Set<StreamRange> = emptySet(),
    val hiddenCodecs: Set<StreamCodec> = emptySet(),
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
) {
    private val include = compile(includePattern)
    private val exclude = compile(excludePattern)

    /** The include pattern is set but isn't a valid regular expression: it is ignored. */
    val includeInvalid: Boolean = includePattern.isNotBlank() && include == null

    /** The exclude pattern is set but isn't a valid regular expression: it is ignored. */
    val excludeInvalid: Boolean = excludePattern.isNotBlank() && exclude == null

    /** Some rule goes by what the stream's text says (its badges), not by the patterns alone. */
    private val readsBadges: Boolean = hiddenQualities.isNotEmpty() || hiddenRanges.isNotEmpty() || hiddenCodecs.isNotEmpty() ||
        minSizeBytes != null || maxSizeBytes != null

    /** Some stream can be hidden by this filter. */
    val active: Boolean = readsBadges || include != null || exclude != null

    /** [stream] passes, [badges] being what its text says ([StreamBadgeParser.parse] of it). */
    fun accepts(stream: Stream, badges: StreamBadges): Boolean {
        if (!active) return true
        if (qualityOf(badges.resolution) in hiddenQualities) return false
        if (hiddenRanges.isNotEmpty() && rangeOf(badges) in hiddenRanges) return false
        if (hiddenCodecs.isNotEmpty() && codecOf(badges.videoCodec) in hiddenCodecs) return false
        if (minSizeBytes != null || maxSizeBytes != null) {
            val size = sizeOf(stream, badges)
            if (size != null && (minSizeBytes != null && size < minSizeBytes || maxSizeBytes != null && size > maxSizeBytes)) return false
        }
        if (include == null && exclude == null) return true
        val text = searchText(stream)
        if (exclude?.containsMatchIn(text) == true) return false
        return include?.containsMatchIn(text) ?: true
    }

    /** [stream] passes, its badges read from its text here when a rule needs them. */
    fun accepts(stream: Stream): Boolean =
        !active || accepts(stream, if (readsBadges) StreamBadgeParser.parse(stream) else NO_BADGES)

    companion object {
        /** Lets every stream through. */
        val NONE: StreamFilter = StreamFilter(emptySet(), "", "")

        private val NO_BADGES = StreamBadges()

        private const val MIB = 1L shl 20

        /** The filter of a profile's [settings]. */
        fun of(settings: ProfileSettings): StreamFilter = StreamFilter(
            hiddenQualities = settings.hiddenStreamQualities.toSet(),
            includePattern = settings.streamIncludePattern,
            excludePattern = settings.streamExcludePattern,
            hiddenRanges = settings.hiddenStreamRanges.toSet(),
            hiddenCodecs = settings.hiddenStreamCodecs.toSet(),
            minSizeBytes = settings.streamMinSizeMb.takeIf { it > 0 }?.let { it * MIB },
            maxSizeBytes = settings.streamMaxSizeMb.takeIf { it > 0 }?.let { it * MIB },
        )

        /** [pattern] is set but isn't a valid regular expression (the settings' editor says so while it's typed). */
        fun invalidPattern(pattern: String): Boolean = pattern.isNotBlank() && compile(pattern) == null

        /** The quality bucket of a resolution read from a stream's text; none read is [StreamQuality.UNKNOWN]. */
        fun qualityOf(resolution: ResolutionBadge?): StreamQuality = when (resolution) {
            ResolutionBadge.UHD_2160 -> StreamQuality.UHD_4K
            ResolutionBadge.QHD_1440 -> StreamQuality.QHD_1440
            ResolutionBadge.FHD_1080 -> StreamQuality.FHD_1080
            ResolutionBadge.HD_720 -> StreamQuality.HD_720
            ResolutionBadge.SD_480 -> StreamQuality.SD
            null -> StreamQuality.UNKNOWN
        }

        /** The dynamic range bucket of a stream's [badges]: Dolby Vision whatever else it names, else any HDR format, else SDR. */
        fun rangeOf(badges: StreamBadges): StreamRange = when {
            HdrBadge.DOLBY_VISION in badges.hdr -> StreamRange.DOLBY_VISION
            badges.hdr.isNotEmpty() -> StreamRange.HDR
            else -> StreamRange.SDR
        }

        /** The codec bucket of a codec read from a stream's text; none read is [StreamCodec.UNKNOWN]. */
        fun codecOf(codec: VideoCodecBadge?): StreamCodec = when (codec) {
            VideoCodecBadge.AVC -> StreamCodec.AVC
            VideoCodecBadge.HEVC -> StreamCodec.HEVC
            VideoCodecBadge.AV1 -> StreamCodec.AV1
            VideoCodecBadge.VP9 -> StreamCodec.VP9
            VideoCodecBadge.XVID -> StreamCodec.XVID
            null -> StreamCodec.UNKNOWN
        }

        /** The file's size: the addon's `videoSize` (exact), else the size the stream's text names; null when neither says. */
        fun sizeOf(stream: Stream, badges: StreamBadges): Long? = stream.behaviorHints.videoSize ?: badges.sizeBytes

        private fun compile(pattern: String): Regex? {
            val trimmed = pattern.trim()
            if (trimmed.isEmpty()) return null
            return try {
                Regex(trimmed, RegexOption.IGNORE_CASE)
            } catch (_: IllegalArgumentException) {
                // java.util.regex and Kotlin/Native's engine both throw a PatternSyntaxException, an IllegalArgumentException.
                null
            }
        }

        private fun searchText(stream: Stream): String =
            listOfNotNull(stream.name, stream.description, stream.behaviorHints.filename).joinToString(" ").replace('\n', ' ').replace('\r', ' ')
    }
}
