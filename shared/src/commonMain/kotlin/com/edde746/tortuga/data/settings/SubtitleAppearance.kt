package com.edde746.tortuga.data.settings

import kotlinx.serialization.Serializable

/**
 * How this device draws plain-text subtitles (SRT, WebVTT and other text converted for the player), resolved against
 * the defaults: the subtitle settings ([DeviceSetting.SubtitleSize] and the rest). Per device, as screens and viewing
 * distances differ. Styled subtitles (ASS/SSA) keep their own look unless [styledOverride] is on. The player gets it
 * as `SubtitleStyle`.
 *
 * @param sizePercent the text's size in percent of the player's own.
 * @param backgroundPercent the opacity of a box behind the text; 0 draws none.
 * @param liftPercent how far bottom subtitles sit above their own place, in percent of the picture's height.
 * @param styledOverride styled subtitles take this look too, in place of their own.
 */
data class SubtitleAppearance(
    val sizePercent: Int,
    val textColor: SubtitleColor,
    val outline: SubtitleOutline,
    val outlineColor: SubtitleColor,
    val backgroundPercent: Int,
    val bold: Boolean,
    val liftPercent: Int,
    val styledOverride: Boolean,
) {
    companion object {
        val DEFAULT: SubtitleAppearance get() = DeviceSettings.DEFAULT.subtitles
    }
}

/** The colours offered for subtitle text and outlines; [rgb] is the opaque colour as 0xRRGGBB. Stored by name. */
@Serializable
enum class SubtitleColor(val rgb: Int) {
    WHITE(0xFFFFFF),
    YELLOW(0xFFFF00),
    GREEN(0x00FF00),
    CYAN(0x00FFFF),
    GRAY(0xA0A0A0),
    BLACK(0x000000),
}

/** The outline around subtitle text. Stored by name. */
@Serializable
enum class SubtitleOutline { NONE, THIN, THICK }
