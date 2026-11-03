package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.settings.StreamCodec
import com.edde746.tortuga.data.settings.StreamQuality
import com.edde746.tortuga.data.settings.StreamRange
import com.edde746.tortuga.format.formatByteSize
import com.edde746.tortuga.playback.streams.ResolutionBadge
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.streams.VideoCodecBadge
import com.edde746.tortuga.strings.T

/** Which of the Streams group's lists of switches a [PlaybackSettingRow.StreamChoices] is. */
enum class StreamChoicesKind { QUALITIES, RANGES, CODECS }

/**
 * A setting with any number of a fixed list of values on at once: a list of checkable options (switches, check marks);
 * [SettingChoiceOption.selected] is on.
 *
 * @param valueLabel the options on, as the row shows them: "All", "All but 4K", "1080p, 720p", "None".
 */
data class SettingMultiChoiceRow(val title: String, val subtitle: String?, val valueLabel: String, val options: List<SettingChoiceOption>)

/** Which of the stream filters' regular expressions a [SettingPatternRow] edits. */
enum class StreamPatternKind { INCLUDE, EXCLUDE }

/**
 * A regular expression, typed in an editor (a text field with [placeholder]; while typing,
 * [PlaybackSettingsStore.patternError] says whether the text compiles).
 *
 * @param value the stored pattern, "" for none: the editor's starting text.
 * @param valueLabel what the row shows: the pattern, or what none means ("Any stream", "None").
 * @param error the stored pattern isn't a valid regular expression, so it filters nothing: the text to show on the row.
 */
data class SettingPatternRow(
    val kind: StreamPatternKind,
    val title: String,
    val subtitle: String,
    val value: String,
    val valueLabel: String,
    val error: String?,
    val placeholder: String,
)

/** The [SettingChoiceOption.id] of [mode]. */
internal val AutoPlayMode.optionId: String get() = name

internal fun autoPlayMode(optionId: String): AutoPlayMode? = AutoPlayMode.entries.firstOrNull { it.optionId == optionId }

/**
 * Settings → Playback → Streams, per profile and synced: auto-play, the suggestions' switch, the resolutions, dynamic
 * ranges and codecs the stream lists show, the smallest and largest file size, then the include and
 * exclude patterns; under them, what the filters do to the lists.
 */
internal fun streamSettingsSection(values: ProfileSettings): PlaybackSettingsSection {
    val text = T.settings.playback.streams
    val autoPlayOptions = AutoPlayMode.entries.map { mode ->
        val label = when (mode) {
            AutoPlayMode.OFF -> text.autoPlayOff
            AutoPlayMode.FIRST_MATCH -> text.autoPlayFirstMatch
            AutoPlayMode.REUSE_LAST_LINK -> text.autoPlayReuseLastLink
        }
        SettingChoiceOption(mode.optionId, label, selected = mode == values.streamAutoPlay)
    }
    val hidden = values.hiddenStreamQualities.toSet()
    val qualityOptions = StreamQuality.entries.map { SettingChoiceOption(it.key, it.label(), selected = it !in hidden) }
    val hiddenRanges = values.hiddenStreamRanges.toSet()
    val rangeOptions = StreamRange.entries.map { SettingChoiceOption(it.key, it.label(), selected = it !in hiddenRanges) }
    val hiddenCodecs = values.hiddenStreamCodecs.toSet()
    val codecOptions = StreamCodec.entries.map { SettingChoiceOption(it.key, it.label(), selected = it !in hiddenCodecs) }
    val filter = StreamFilter.of(values)
    return PlaybackSettingsSection(
        group = PlaybackSettingsGroup.STREAMS,
        header = text.header,
        footer = text.footer,
        rows = listOf(
            PlaybackSettingRow.Choice(SettingChoiceRow("streams.autoPlay", text.autoPlay, text.autoPlayHint, autoPlayOptions)),
            PlaybackSettingRow.Toggle(SettingToggleRow("streams.suggestions", text.suggestions, text.suggestionsHint, values.streamSuggestions)),
            PlaybackSettingRow.StreamChoices(
                StreamChoicesKind.QUALITIES,
                SettingMultiChoiceRow(text.qualities, text.qualitiesHint, shownLabel(qualityOptions), qualityOptions),
            ),
            PlaybackSettingRow.StreamChoices(StreamChoicesKind.RANGES, SettingMultiChoiceRow(text.ranges, text.rangesHint, shownLabel(rangeOptions), rangeOptions)),
            PlaybackSettingRow.StreamChoices(StreamChoicesKind.CODECS, SettingMultiChoiceRow(text.codecs, text.codecsHint, shownLabel(codecOptions), codecOptions)),
            PlaybackSettingRow.Choice(sizeRow("streams.minSize", text.minSize, text.minSizeHint, values.streamMinSizeMb, MIN_SIZES_MB)),
            PlaybackSettingRow.Choice(sizeRow("streams.maxSize", text.maxSize, text.maxSizeHint, values.streamMaxSizeMb, MAX_SIZES_MB)),
            PlaybackSettingRow.StreamPattern(
                SettingPatternRow(
                    kind = StreamPatternKind.INCLUDE,
                    title = text.includePattern,
                    subtitle = text.includePatternHint,
                    value = values.streamIncludePattern,
                    valueLabel = values.streamIncludePattern.ifEmpty { text.includePatternNone },
                    error = if (filter.includeInvalid) text.invalidPattern else null,
                    placeholder = text.includePlaceholder,
                ),
            ),
            PlaybackSettingRow.StreamPattern(
                SettingPatternRow(
                    kind = StreamPatternKind.EXCLUDE,
                    title = text.excludePattern,
                    subtitle = text.excludePatternHint,
                    value = values.streamExcludePattern,
                    valueLabel = values.streamExcludePattern.ifEmpty { text.excludePatternNone },
                    error = if (filter.excludeInvalid) text.invalidPattern else null,
                    placeholder = text.excludePlaceholder,
                ),
            ),
        ),
    )
}

/** The options on as a row shows them, the shorter of the two: "All but 4K" while most are on, the ones on otherwise. */
private fun shownLabel(options: List<SettingChoiceOption>): String {
    val text = T.settings.playback.streams
    val shown = options.filter { it.selected }
    val hidden = options.filterNot { it.selected }
    return when {
        hidden.isEmpty() -> text.qualitiesAll
        shown.isEmpty() -> text.qualitiesNone
        hidden.size < shown.size -> text.qualitiesAllBut(hidden.joinToString(T.settings.listSeparator) { it.label })
        else -> shown.joinToString(T.settings.listSeparator) { it.label }
    }
}

/** The smallest file sizes offered, in MiB: none, 500 MB to 10 GB. */
private val MIN_SIZES_MB = listOf(0, 512, 1024, 2048, 5120, 10240)

/** The largest file sizes offered, in MiB: none, 2 GB to 80 GB. */
private val MAX_SIZES_MB = listOf(0, 2048, 5120, 10240, 20480, 40960, 81920)

/** A size limit's row over [sizes] (MiB, 0 for none), with [current] among them even when another version stored it. */
private fun sizeRow(id: String, title: String, subtitle: String, current: Int, sizes: List<Int>): SettingChoiceRow {
    val options = (sizes + current).distinct().sorted().map { mb ->
        SettingChoiceOption(mb.toString(), if (mb == 0) T.settings.playback.streams.sizeAny else formatByteSize(mb.toLong() shl 20), selected = mb == current)
    }
    return SettingChoiceRow(id, title, subtitle, options)
}

/** Resolutions keep their technical labels in every language (as the stream badges do); SD and Unknown are words. */
private fun StreamQuality.label(): String = when (this) {
    StreamQuality.UHD_4K -> ResolutionBadge.UHD_2160.label
    StreamQuality.QHD_1440 -> ResolutionBadge.QHD_1440.label
    StreamQuality.FHD_1080 -> ResolutionBadge.FHD_1080.label
    StreamQuality.HD_720 -> ResolutionBadge.HD_720.label
    StreamQuality.SD -> T.settings.playback.streams.qualitySd
    StreamQuality.UNKNOWN -> T.settings.playback.streams.qualityUnknown
}

/** Dynamic ranges by their formats' names, the same in every language. */
private fun StreamRange.label(): String = when (this) {
    StreamRange.SDR -> "SDR"
    StreamRange.HDR -> "HDR"
    StreamRange.DOLBY_VISION -> "Dolby Vision"
}

/** Codecs by their technical names (the stream badges'); Unknown is a word. */
private fun StreamCodec.label(): String = when (this) {
    StreamCodec.AVC -> VideoCodecBadge.AVC.label
    StreamCodec.HEVC -> VideoCodecBadge.HEVC.label
    StreamCodec.AV1 -> VideoCodecBadge.AV1.label
    StreamCodec.VP9 -> VideoCodecBadge.VP9.label
    StreamCodec.XVID -> VideoCodecBadge.XVID.label
    StreamCodec.UNKNOWN -> T.settings.playback.streams.qualityUnknown
}
