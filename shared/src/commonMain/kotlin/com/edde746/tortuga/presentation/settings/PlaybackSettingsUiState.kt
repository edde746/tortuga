package com.edde746.tortuga.presentation.settings

/**
 * The profile's playback settings: stored per profile and synced to the account's other devices, as
 * [note] says; only the Player row is this device's.
 *
 * @param loading the stored values haven't been read yet; the rows show the defaults meanwhile.
 * @param sections the pane's groups and their rows, in the order every UI shows them: this device's Player in a group
 *   of its own (where it has player apps), then the profile's Languages, Playback, Skipping and Streams.
 * @param languagePicker the audio or subtitle language list being edited, or null.
 */
data class PlaybackSettingsUiState(
    val loading: Boolean,
    val title: String,
    val note: String,
    val sections: List<PlaybackSettingsSection>,
    val languagePicker: LanguagePickerUiState?,
) {
    /** The Streams row of [kind]'s switches, which its editor shows. */
    fun streamChoices(kind: StreamChoicesKind): SettingMultiChoiceRow =
        sections.flatMap { it.rows }.firstNotNullOf { (it as? PlaybackSettingRow.StreamChoices)?.takeIf { row -> row.kind == kind }?.row }

    /** The Streams row of the [kind] pattern, whose editor starts from its value. */
    fun streamPattern(kind: StreamPatternKind): SettingPatternRow =
        sections.flatMap { it.rows }.firstNotNullOf { (it as? PlaybackSettingRow.StreamPattern)?.row?.takeIf { row -> row.kind == kind } }
}

/** A group of the Playback pane: [rows] under [header] (none for the Player's), then [footer]. */
data class PlaybackSettingsSection(val group: PlaybackSettingsGroup, val header: String?, val footer: String?, val rows: List<PlaybackSettingRow>)

/** The Playback pane's groups. */
enum class PlaybackSettingsGroup { PLAYER, LANGUAGES, PLAYBACK, SKIPPING, STREAMS }

/** A row of the Playback pane, with the store intent it takes. */
sealed interface PlaybackSettingRow {
    /** This device's player: its row and page come from its own store (`PlayerSettingsUiState.row`). */
    data object Player : PlaybackSettingRow

    /**
     * The audio or subtitle languages, or the second subtitle's language ([kind]); its press opens the editor
     * ([PlaybackSettingsStore.openLanguages]).
     */
    data class Languages(val kind: LanguageListKind, val row: SettingLanguagesRow) : PlaybackSettingRow

    /**
     * A choice: when subtitles show, what a click on the video does, the Skipping group's rows,
     * auto-play and the file size limits ([PlaybackSettingsStore.choose] takes its [SettingChoiceRow.id] and an
     * option's id).
     */
    data class Choice(val row: SettingChoiceRow) : PlaybackSettingRow

    /** One of the steppers ([PlaybackSettingsStore.stepNumber] takes [field]). */
    data class Stepper(val field: PlaybackNumber, val row: SettingStepperRow) : PlaybackSettingRow

    /**
     * A switch: binge watching, the suggestions
     * ([PlaybackSettingsStore.toggle] takes its [SettingToggleRow.id]).
     */
    data class Toggle(val row: SettingToggleRow) : PlaybackSettingRow

    /**
     * The resolutions, dynamic ranges or video codecs the stream lists show ([kind]); its press opens their switches
     * ([PlaybackSettingsStore.setStreamQualityShown], `setStreamRangeShown`, `setStreamCodecShown`).
     */
    data class StreamChoices(val kind: StreamChoicesKind, val row: SettingMultiChoiceRow) : PlaybackSettingRow

    /**
     * The include or exclude pattern ([SettingPatternRow.kind]); its press opens its editor
     * ([PlaybackSettingsStore.setStreamPattern]). Exclude wins over include.
     */
    data class StreamPattern(val row: SettingPatternRow) : PlaybackSettingRow
}

/** A language preference as a settings row: "English, Japanese", or what happens with none. */
data class SettingLanguagesRow(val title: String, val subtitle: String, val valueLabel: String)

/** A language preference: an ordered list ([AUDIO], [SUBTITLES]), or one language at most ([SECOND_SUBTITLE]). */
enum class LanguageListKind { AUDIO, SUBTITLES, SECOND_SUBTITLE }

/** The stepper settings of [PlaybackSettingsStore]. */
enum class PlaybackNumber { WATCHED_THRESHOLD, UP_NEXT_COUNTDOWN, SEEK_SHORT, SEEK_LONG }

/**
 * Editing one language preference: the [chosen] languages, most preferred first, and the others to add, filtered by
 * [query].
 *
 * @param emptyText no language matches [query]: the text to show instead of [available].
 */
data class LanguagePickerUiState(
    val kind: LanguageListKind,
    val title: String,
    val explanation: String,
    val chosenHeader: String,
    val chosen: List<SettingChosenLanguage>,
    val noneChosenText: String?,
    val availableHeader: String,
    val query: String,
    val searchHint: String,
    val available: List<SettingLanguageOption>,
    val emptyText: String?,
)

/**
 * A chosen language. [code] is what the picker's intents take.
 *
 * @param rank its place in the list, "1", "2", ….
 * @param moveUpLabel the name of its Move up button, with its name: "Move English up".
 * @param moveDownLabel the name of its Move down button: "Move English down".
 * @param removeLabel the name of its Remove button: "Remove English".
 * @param dragLabel the name of its drag handle: "Reorder English".
 * @param moreLabel the name of its menu button: "More actions for English".
 */
data class SettingChosenLanguage(
    val code: String,
    val name: String,
    val rank: String,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val moveUpLabel: String,
    val moveDownLabel: String,
    val removeLabel: String,
    val dragLabel: String,
    val moreLabel: String,
)

/** A language that can be added; [code] is what `addLanguage` takes. */
data class SettingLanguageOption(val code: String, val name: String)
