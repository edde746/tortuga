package com.edde746.tortuga.presentation.streams

import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.lang.LanguageNames
import com.edde746.tortuga.lang.LanguageTags
import com.edde746.tortuga.playback.streams.AudioBadge
import com.edde746.tortuga.playback.streams.HdrBadge
import com.edde746.tortuga.playback.suggest.LanguagePrefs
import com.edde746.tortuga.playback.suggest.StreamFeature
import com.edde746.tortuga.playback.suggest.StreamScorer
import com.edde746.tortuga.playback.suggest.Suggestion
import com.edde746.tortuga.strings.T

/**
 * A suggestion's reason line: "Like your recent picks: 1080p, HEVC, English audio", its three strongest reasons named
 * the way the list's badges name them (technical labels as release names write them), languages in the app's.
 */
internal object StreamSuggestionText {
    /** The reason line for [suggestion] of [best]; null when none of its reasons can be named here. */
    fun reason(suggestion: Suggestion, best: PickedStream, languages: LanguagePrefs, cacheBadge: Boolean): String? {
        val names = suggestion.reasons.mapNotNull { name(it.feature, it.lead, best, languages, cacheBadge) }.distinct().take(3)
        if (names.isEmpty()) return null
        return T.streams.suggested.reason(names.joinToString(T.settings.listSeparator))
    }

    private fun name(feature: StreamFeature, lead: Double, best: PickedStream, languages: LanguagePrefs, cacheBadge: Boolean): String? =
        when (feature) {
            StreamFeature.RES_4K, StreamFeature.RES_1440, StreamFeature.RES_1080, StreamFeature.RES_720, StreamFeature.RES_SD ->
                best.resolution?.label
            StreamFeature.DV_SHOWN -> HdrBadge.DOLBY_VISION.label
            StreamFeature.HDR_SHOWN -> (best.hdr.firstOrNull { it != HdrBadge.DOLBY_VISION } ?: best.hdr.firstOrNull())?.label
            StreamFeature.HEVC, StreamFeature.AV1, StreamFeature.AVC -> best.videoCodec?.label
            StreamFeature.REMUX, StreamFeature.BLURAY, StreamFeature.WEB_DL, StreamFeature.WEBRIP -> best.source?.label
            StreamFeature.BITRATE, StreamFeature.BITRATE_METERED ->
                if (lead < 0) T.streams.suggested.smaller else T.streams.suggested.larger
            StreamFeature.AUDIO_MATCH -> matched(best.audioLanguages, languages.audio)?.let(T.streams.suggested::audio)
            StreamFeature.SUBTITLE_MATCH -> matched(best.subtitleLanguages, languages.subtitles)?.let(T.streams.suggested::subtitles)
            StreamFeature.CACHED -> if (cacheBadge && best.cached == true) T.streams.badge.instant else null
            StreamFeature.SURROUND -> best.channels?.label
            StreamFeature.LOSSLESS_AUDIO -> best.audio.firstOrNull { it in StreamScorer.LOSSLESS }?.label
            StreamFeature.ATMOS -> AudioBadge.ATMOS.label
            else -> null
        }

    /** The name, in the app's language, of the first of [wanted] that [named] has. */
    private fun matched(named: List<String>, wanted: List<String>): String? {
        val tag = wanted.firstOrNull { want -> named.any { LanguageTags.match(want, it) > 0 } } ?: return null
        return LanguageNames.displayName(tag, T.locale)
    }
}
