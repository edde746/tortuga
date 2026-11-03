package com.edde746.tortuga.presentation.settings

/**
 * This device's subtitle style: never synced, as [note] says. A sample line in the style ([preview])
 * above the rows, which follow the stored values. [outlineColor] is null while there is no outline. The switches go to
 * [SubtitleSettingsStore.toggle], the choices to [SubtitleSettingsStore.choose], by their row's id.
 */
data class SubtitleSettingsUiState(
    val loading: Boolean,
    val title: String,
    val note: String,
    val preview: SubtitlePreview,
    val textHeader: String,
    val size: SettingStepperRow,
    val textColor: SettingChoiceRow,
    val bold: SettingToggleRow,
    val outlineHeader: String,
    val outline: SettingChoiceRow,
    val outlineColor: SettingChoiceRow?,
    val background: SettingStepperRow,
    val positionHeader: String,
    val position: SettingStepperRow,
    val styledHeader: String,
    val styledOverride: SettingToggleRow,
)

/**
 * The sample line as the player draws it in the style, for a view to draw at the bottom of a 16:9 stand-in picture:
 * every length is a share of the picture's height, as mpv's are of the video's.
 *
 * @param fontSize the text's font size.
 * @param textColor / [outlineColor] / [boxColor] colours as 0xAARRGGBB.
 * @param outlineWidth the outline's width around each letter; 0 draws none.
 * @param boxColor the box behind the text, or null for none.
 * @param boxPadding the box's margin around the text.
 * @param bottom from the picture's bottom edge to the text's (or its box's) bottom: mpv's margin plus the lift.
 */
data class SubtitlePreview(
    val text: String,
    val fontSize: Double,
    val bold: Boolean,
    val textColor: Long,
    val outlineColor: Long,
    val outlineWidth: Double,
    val boxColor: Long?,
    val boxPadding: Double,
    val bottom: Double,
)

/** The steppers of [SubtitleSettingsStore]. */
enum class SubtitleNumber { SIZE, BACKGROUND, POSITION }
