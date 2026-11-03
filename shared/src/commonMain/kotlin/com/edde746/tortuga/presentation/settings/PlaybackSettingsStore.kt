package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.data.settings.StreamCodec
import com.edde746.tortuga.data.settings.StreamQuality
import com.edde746.tortuga.data.settings.StreamRange
import com.edde746.tortuga.data.settings.SubtitleMode
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.VideoClickAction
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The profile's playback settings screen: audio and subtitle languages (ordered lists
 * picked from every language `LanguageTags` knows) and the second subtitle's language (one of them, or none), when
 * subtitles show (following the audio, or always), the watched threshold, binge watching,
 * the up-next countdown, the seek steps (steppers within fixed bounds; nothing is typed but the stream filters'
 * patterns), what a click on the video does (only where [inputProfile] clicks with a mouse), the Skipping group and
 * the Streams group: auto-play, the suggestions' switch and the stream filters.
 * Values are stored per profile and synced; every change is written at once and the screen follows the stored values,
 * including changes synced from another device. A step or a list edit applies to the stored value, so quick edits add
 * up. The state lists the rows in their groups in the pane's order, headed by this device's Player where
 * [playerChoice] (its own store's).
 *
 * Call everything on the main thread; [release] ends it.
 */
class PlaybackSettingsStore internal constructor(
    private val profileId: String,
    private val settings: SettingsRepository,
    private val inputProfile: InputProfile,
    private val playerChoice: Boolean,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var stored: ProfileSettings? = null
    private var picker: LanguageListKind? = null
    private var query = ""
    private var languages: Pair<String, List<SettingLanguageOption>>? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<PlaybackSettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            settings.observe(profileId).collect {
                stored = it
                publish()
            }
        }
    }

    /**
     * Sets the choice row [key] ([SettingChoiceRow.id] of a [PlaybackSettingRow.Choice]) to its option [optionId]; an
     * option the row doesn't offer changes nothing. Previews have no Skipping row of their own: they follow the credits'.
     */
    fun choose(key: String, optionId: String) {
        when (key) {
            "showSubtitles" -> SubtitleMode.entries.firstOrNull { it.optionId == optionId }?.let { write(ProfileSetting.ShowSubtitles, it) }
            "videoClick" -> VideoClickAction.entries.firstOrNull { it.optionId == optionId }?.let { write(ProfileSetting.VideoClick, it) }
            "skipIntros" -> skipMode(optionId)?.let { write(ProfileSetting.SkipIntros, it) }
            "skipRecaps" -> skipMode(optionId)?.let { write(ProfileSetting.SkipRecaps, it) }
            "skipCredits" -> skipMode(optionId)?.let { write(ProfileSetting.SkipCredits, it) }
            "streams.autoPlay" -> autoPlayMode(optionId)?.let { write(ProfileSetting.StreamAutoPlay, it) }
            "streams.minSize" -> optionId.toIntOrNull()?.let { write(ProfileSetting.StreamMinSizeMb, it) }
            "streams.maxSize" -> optionId.toIntOrNull()?.let { write(ProfileSetting.StreamMaxSizeMb, it) }
            else -> error("Playback settings have no choice row $key")
        }
    }

    /** Switches the row [key] ([SettingToggleRow.id] of a [PlaybackSettingRow.Toggle]) on or off. */
    fun toggle(key: String, checked: Boolean) {
        when (key) {
            "bingeWatching" -> write(ProfileSetting.BingeWatching, checked)
            "streams.suggestions" -> write(ProfileSetting.StreamSuggestions, checked)
            else -> error("Playback settings have no switch $key")
        }
    }

    /** One step up or down ([SettingStepperRow]: a stepper, D-pad left and right); nothing past the row's bounds. */
    fun stepNumber(field: PlaybackNumber, up: Boolean) = edit(field.setting) { field.spec.stepped(it, up) }

    // ---- Language picker ---------------------------------------------------------------------------------------

    fun openLanguages(kind: LanguageListKind) {
        picker = kind
        query = ""
        publish()
    }

    fun closeLanguages() {
        picker = null
        query = ""
        publish()
    }

    fun searchLanguages(query: String) {
        this.query = query
        publish()
    }

    /** Adds [code] as the least preferred language of the open list; the second subtitle's one language it replaces. */
    fun addLanguage(code: String) = editLanguages { if (code in it) it else it + code }

    fun removeLanguage(code: String) = editLanguages { it - code }

    fun moveLanguage(code: String, up: Boolean) = editLanguages { list ->
        val index = list.indexOf(code)
        if (index < 0) list else LanguageChoices.moved(list, index, if (up) index - 1 else index + 1)
    }

    /** Moves [code] to [index] of the chosen list (dragging). */
    fun moveLanguageTo(code: String, index: Int) = editLanguages { list ->
        val from = list.indexOf(code)
        if (from < 0) list else LanguageChoices.moved(list, from, index)
    }

    // ---- Streams: the stream filters' switches and patterns ---------------------------------------------------------

    /** Shows or hides the resolution with the [SettingChoiceOption.id] of the qualities' row ([StreamChoicesKind.QUALITIES]). */
    fun setStreamQualityShown(optionId: String, shown: Boolean) {
        val quality = StreamQuality.fromKey(optionId) ?: return
        edit(ProfileSetting.HiddenStreamQualities) { hidden -> if (shown) hidden - quality else hidden + quality }
    }

    /** Shows or hides the dynamic range with the [SettingChoiceOption.id] of the ranges' row ([StreamChoicesKind.RANGES]). */
    fun setStreamRangeShown(optionId: String, shown: Boolean) {
        val range = StreamRange.fromKey(optionId) ?: return
        edit(ProfileSetting.HiddenStreamRanges) { hidden -> if (shown) hidden - range else hidden + range }
    }

    /** Shows or hides the video codec with the [SettingChoiceOption.id] of the codecs' row ([StreamChoicesKind.CODECS]). */
    fun setStreamCodecShown(optionId: String, shown: Boolean) {
        val codec = StreamCodec.fromKey(optionId) ?: return
        edit(ProfileSetting.HiddenStreamCodecs) { hidden -> if (shown) hidden - codec else hidden + codec }
    }

    /** Stores [pattern] as the [kind] filter, as typed (trimmed); one that doesn't compile is kept and reported. */
    fun setStreamPattern(kind: StreamPatternKind, pattern: String) {
        val setting = when (kind) {
            StreamPatternKind.INCLUDE -> ProfileSetting.StreamIncludePattern
            StreamPatternKind.EXCLUDE -> ProfileSetting.StreamExcludePattern
        }
        write(setting, pattern)
    }

    /** The editor's line under [pattern] while it's typed: why it would be ignored, or null when it's fine (or empty). */
    fun patternError(pattern: String): String? =
        if (StreamFilter.invalidPattern(pattern)) T.settings.playback.streams.invalidPattern else null

    fun release() {
        scope.cancel()
    }

    private fun editLanguages(change: (List<String>) -> List<String>) {
        when (picker ?: return) {
            LanguageListKind.AUDIO -> edit(ProfileSetting.AudioLanguages, change)
            LanguageListKind.SUBTITLES -> edit(ProfileSetting.SubtitleLanguages, change)
            LanguageListKind.SECOND_SUBTITLE -> edit(ProfileSetting.SecondSubtitleLanguage) { change(listOfNotNull(it)).lastOrNull() }
        }
    }

    private fun <T> write(setting: ProfileSetting<T>, value: T) {
        scope.launch { settings.set(profileId, setting, value) }
    }

    /** Stores what [change] makes of the stored value: edits sent before the screen caught up start from the last. */
    private fun <T> edit(setting: ProfileSetting<T>, change: (T) -> T) {
        scope.launch { settings.update(profileId, setting, change) }
    }

    private fun current(): ProfileSettings = stored ?: ProfileSettings.DEFAULT

    private fun numberValue(field: PlaybackNumber): Int = current().let {
        when (field) {
            PlaybackNumber.WATCHED_THRESHOLD -> it.watchedThresholdPercent
            PlaybackNumber.UP_NEXT_COUNTDOWN -> it.upNextCountdownSeconds
            PlaybackNumber.SEEK_SHORT -> it.seekShortSeconds
            PlaybackNumber.SEEK_LONG -> it.seekLongSeconds
        }
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): PlaybackSettingsUiState {
        val values = current()
        val locale = T.locale
        val text = T.settings.playback
        fun number(field: PlaybackNumber, title: String, subtitle: String?) =
            PlaybackSettingRow.Stepper(field, field.spec.row(title, subtitle, numberValue(field)))
        val player = PlaybackSettingsSection(PlaybackSettingsGroup.PLAYER, header = null, footer = null, listOf(PlaybackSettingRow.Player))
        val languages = PlaybackSettingsSection(
            group = PlaybackSettingsGroup.LANGUAGES,
            header = text.languagesHeader,
            footer = null,
            rows = listOf(
                PlaybackSettingRow.Languages(
                    LanguageListKind.AUDIO,
                    SettingLanguagesRow(
                        title = text.audioLanguages,
                        subtitle = text.audioLanguagesHint,
                        valueLabel = LanguageChoices.list(values.audioLanguages, locale) ?: text.audioLanguagesNone,
                    ),
                ),
                PlaybackSettingRow.Languages(
                    LanguageListKind.SUBTITLES,
                    SettingLanguagesRow(
                        title = text.subtitleLanguages,
                        subtitle = text.subtitleLanguagesHint,
                        valueLabel = LanguageChoices.list(values.subtitleLanguages, locale) ?: text.subtitleLanguagesNone,
                    ),
                ),
                PlaybackSettingRow.Languages(
                    LanguageListKind.SECOND_SUBTITLE,
                    SettingLanguagesRow(
                        title = text.secondSubtitleLanguage,
                        subtitle = text.secondSubtitleLanguageHint,
                        valueLabel = values.secondSubtitleLanguage?.let { LanguageChoices.name(it, locale) } ?: text.secondSubtitleLanguageNone,
                    ),
                ),
                PlaybackSettingRow.Choice(showSubtitlesRow(values.showSubtitles)),
            ),
        )
        val playback = PlaybackSettingsSection(
            group = PlaybackSettingsGroup.PLAYBACK,
            header = text.playbackHeader,
            footer = null,
            rows = listOfNotNull(
                number(PlaybackNumber.WATCHED_THRESHOLD, text.watchedThreshold, text.watchedThresholdHint),
                PlaybackSettingRow.Toggle(SettingToggleRow("bingeWatching", text.bingeWatching, text.bingeWatchingHint, values.bingeWatching)),
                number(PlaybackNumber.UP_NEXT_COUNTDOWN, text.upNextCountdown, text.upNextCountdownHint),
                number(PlaybackNumber.SEEK_SHORT, text.seekShort, text.seekShortHint),
                number(PlaybackNumber.SEEK_LONG, text.seekLong, text.seekLongHint),
                if (inputProfile.followsVideoClick) PlaybackSettingRow.Choice(videoClickRow(values.videoClick)) else null,
            ),
        )
        return PlaybackSettingsUiState(
            loading = stored == null,
            title = text.title,
            note = text.note,
            sections = listOfNotNull(
                player.takeIf { playerChoice },
                languages,
                playback,
                skipSettingsSection(values),
                streamSettingsSection(values),
            ),
            languagePicker = picker?.let { pickerState(it, values, locale) },
        )
    }

    private fun showSubtitlesRow(selected: SubtitleMode): SettingChoiceRow {
        val text = T.settings.playback
        val options = SubtitleMode.entries.map { mode ->
            val label = when (mode) {
                SubtitleMode.FOLLOW_AUDIO -> text.showSubtitlesFollowAudio
                SubtitleMode.ALWAYS -> text.showSubtitlesAlways
            }
            SettingChoiceOption(mode.optionId, label, selected = mode == selected)
        }
        return SettingChoiceRow("showSubtitles", text.showSubtitles, text.showSubtitlesHint, options)
    }

    private fun videoClickRow(selected: VideoClickAction): SettingChoiceRow {
        val text = T.settings.playback
        val options = VideoClickAction.entries.map { action ->
            val label = when (action) {
                VideoClickAction.PAUSE -> text.videoClickPause
                VideoClickAction.CONTROLS -> text.videoClickControls
            }
            SettingChoiceOption(action.optionId, label, selected = action == selected)
        }
        return SettingChoiceRow("videoClick", text.videoClick, text.videoClickHint, options)
    }

    private fun pickerState(kind: LanguageListKind, values: ProfileSettings, locale: String): LanguagePickerUiState {
        val text = T.settings.playback
        val all = languages?.takeIf { it.first == locale }?.second
            ?: LanguageChoices.preferenceLanguages(locale).also { languages = locale to it }
        return when (kind) {
            LanguageListKind.AUDIO -> LanguageChoices.picker(
                kind, text.audioLanguages, text.audioLanguagesExplanation, text.audioLanguagesNone, values.audioLanguages, all, query, locale,
            )
            LanguageListKind.SUBTITLES -> LanguageChoices.picker(
                kind, text.subtitleLanguages, text.subtitleLanguagesExplanation, text.subtitleLanguagesNone, values.subtitleLanguages, all, query, locale,
            )
            LanguageListKind.SECOND_SUBTITLE -> LanguageChoices.picker(
                kind, text.secondSubtitleLanguage, text.secondSubtitleLanguageExplanation, text.secondSubtitleLanguageNone,
                listOfNotNull(values.secondSubtitleLanguage), all, query, locale,
            )
        }
    }
}

private val PlaybackNumber.setting: ProfileSetting<Int>
    get() = when (this) {
        PlaybackNumber.WATCHED_THRESHOLD -> ProfileSetting.WatchedThresholdPercent
        PlaybackNumber.UP_NEXT_COUNTDOWN -> ProfileSetting.UpNextCountdownSeconds
        PlaybackNumber.SEEK_SHORT -> ProfileSetting.SeekShortSeconds
        PlaybackNumber.SEEK_LONG -> ProfileSetting.SeekLongSeconds
    }

/** The [SettingChoiceOption.id] of [this]. */
private val VideoClickAction.optionId: String get() = name

/** The [SettingChoiceOption.id] of [this]. */
private val SubtitleMode.optionId: String get() = name

private val PlaybackNumber.spec: StepperSpec
    get() = when (this) {
        PlaybackNumber.WATCHED_THRESHOLD -> WATCHED_THRESHOLD_SPEC
        PlaybackNumber.UP_NEXT_COUNTDOWN -> UP_NEXT_SPEC
        PlaybackNumber.SEEK_SHORT -> SEEK_SHORT_SPEC
        PlaybackNumber.SEEK_LONG -> SEEK_LONG_SPEC
    }

// The steppers' bounds lie inside the settings' stored ranges (ProfileSetting), which also take what earlier versions
// let users type: a threshold under half the video, seek steps of a few seconds or of ten minutes.

private val WATCHED_THRESHOLD_SPEC = StepperSpec(min = 50, max = 100, step = 5) { T.settings.value.percent(it.toString()) }

private val UP_NEXT_SPEC = StepperSpec(min = 0, max = 60, step = 5) {
    if (it == 0) T.settings.playback.upNextImmediately else T.settings.value.seconds(it)
}

private val SEEK_SHORT_SPEC = StepperSpec(min = 5, max = 60, step = 5) { T.settings.value.seconds(it) }

private val SEEK_LONG_SPEC = StepperSpec(min = 10, max = 300, step = 10) { T.settings.value.seconds(it) }
