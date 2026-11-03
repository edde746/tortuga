package com.edde746.tortuga.playback

import com.edde746.tortuga.data.settings.SubtitleAppearance
import com.edde746.tortuga.data.settings.SubtitleColor
import com.edde746.tortuga.data.settings.SubtitleOutline
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * How the player draws plain-text subtitles, as mpv's `sub-*` values, decided here for every platform from this
 * device's subtitle settings ([of]). The playback session hands it to the engine as it starts and again whenever a
 * setting changes ([PlayerEngine.setSubtitleStyle]), so a change shows at once, on the file playing too. [DEFAULT] is
 * mpv's own look.
 *
 * Sizes are mpv's scaled pixels (a 720-line picture's pixels, scaled with the picture). The outline and the box's
 * margin grow with the text, so a larger size keeps the same look.
 *
 * @param fontSize `sub-font-size`; it applies to plain-text subtitles only (styled ones keep theirs unless
 *   [assOverride] forces it), unlike `sub-scale`, which mpv applies to styled subtitles too.
 * @param color `sub-color`, `#AARRGGBB`.
 * @param outlineSize `sub-outline-size`; 0 draws none.
 * @param outlineColor `sub-outline-color`, `#AARRGGBB`.
 * @param borderStyle `sub-border-style`: [OUTLINE] or, with a box behind the text, [BACKGROUND_BOX].
 * @param backColor `sub-back-color`, `#AARRGGBB`: the box's colour and opacity.
 * @param shadowOffset `sub-shadow-offset`: the box's margin around the text; 0 without a box (no shadow either).
 * @param bold `sub-bold`.
 * @param lift how far bottom subtitles sit above their own place, as a share of the picture's height (0–1); with the
 *   player's controls up they sit at least above them ([position]).
 * @param assOverride `sub-ass-override`: [ASS_KEEP] leaves styled subtitles their own look (mpv's default, which
 *   still moves them with `sub-pos`); [ASS_FORCE] gives them this style too.
 */
data class SubtitleStyle(
    val fontSize: Double,
    val color: String,
    val outlineSize: Double,
    val outlineColor: String,
    val borderStyle: String,
    val backColor: String,
    val shadowOffset: Double,
    val bold: Boolean,
    val lift: Double,
    val assOverride: String,
) {
    /** The mpv properties of this style, as `name` to value; `sub-pos` is [position]'s. */
    fun properties(): Map<String, String> = linkedMapOf(
        "sub-ass-override" to assOverride,
        "sub-font-size" to number(fontSize),
        "sub-color" to color,
        "sub-outline-size" to number(outlineSize),
        "sub-outline-color" to outlineColor,
        "sub-border-style" to borderStyle,
        "sub-back-color" to backColor,
        "sub-shadow-offset" to number(shadowOffset),
        "sub-bold" to if (bold) "yes" else "no",
    )

    /**
     * mpv's `sub-pos` for bottom subtitles lifted by [lift] or, while the player's controls cover [controlsLift] of the
     * surface's height from the bottom (`PlayerEngine.setSubtitleLift`), by at least the part of them over [frame], the
     * frame mpv places the subtitles in ([SubtitleFrame.lift]): 100 is their own place, every point less lifts them by
     * 1 % of the frame's height.
     */
    fun position(controlsLift: Double, frame: SubtitleFrame): Double =
        100.0 * (1.0 - maxOf(lift, frame.lift(controlsLift)).coerceIn(0.0, 1.0))

    companion object {
        /** The picture height mpv's scaled pixels are pixels of: a size of 72 is a tenth of the picture's height. */
        const val SCALED_HEIGHT = 720.0

        /** mpv's `sub-margin-y` default: bottom subtitles' distance from the bottom edge in their own place. */
        const val MARGIN_Y = 34.0

        /** mpv's `sub-font-size` default: the size [SubtitleAppearance.sizePercent] 100 stands for. */
        const val FONT_SIZE = 38.0

        /** mpv's `sub-outline-size` default: [SubtitleOutline.THIN]; [SubtitleOutline.THICK] is twice it. */
        const val THIN_OUTLINE = 1.65

        /** mpv's `sub-shadow-offset` in its own `sub-box` profile: the box's margin around the text. */
        const val BOX_MARGIN = 4.0

        /** mpv's `sub-back-color` default (black at 175 of 255), kept while no box is drawn. */
        const val DEFAULT_BACK_COLOR = "#AF000000"

        const val OUTLINE = "outline-and-shadow"
        const val BACKGROUND_BOX = "background-box"
        const val ASS_KEEP = "scale"
        const val ASS_FORCE = "force"

        /** The style of [appearance]: the subtitle settings as mpv values. */
        fun of(appearance: SubtitleAppearance): SubtitleStyle {
            val scale = appearance.sizePercent / 100.0
            val box = appearance.backgroundPercent > 0
            val outline = when (appearance.outline) {
                SubtitleOutline.NONE -> 0.0
                SubtitleOutline.THIN -> THIN_OUTLINE
                SubtitleOutline.THICK -> 2 * THIN_OUTLINE
            }
            return SubtitleStyle(
                fontSize = rounded(FONT_SIZE * scale),
                color = argb(appearance.textColor, 100),
                outlineSize = rounded(outline * scale),
                outlineColor = argb(appearance.outlineColor, 100),
                borderStyle = if (box) BACKGROUND_BOX else OUTLINE,
                backColor = if (box) argb(SubtitleColor.BLACK, appearance.backgroundPercent) else DEFAULT_BACK_COLOR,
                shadowOffset = if (box) rounded(BOX_MARGIN * scale) else 0.0,
                bold = appearance.bold,
                lift = appearance.liftPercent / 100.0,
                assOverride = if (appearance.styledOverride) ASS_FORCE else ASS_KEEP,
            )
        }

        /** mpv's own look: the subtitle settings' defaults. */
        val DEFAULT: SubtitleStyle get() = of(SubtitleAppearance.DEFAULT)

        /** [color] at [opacityPercent] (0–100) as mpv's `#AARRGGBB`. */
        fun argb(color: SubtitleColor, opacityPercent: Int): String {
            val alpha = (opacityPercent.coerceIn(0, 100) * 255 / 100.0).roundToInt()
            return "#" + hex(alpha, 2) + hex(color.rgb, 6)
        }

        private fun hex(value: Int, digits: Int) = value.toString(16).uppercase().padStart(digits, '0')

        /** To 3 decimals, so the values read as written ("41.8", not "41.800000000000004"). */
        private fun rounded(value: Double) = round(value * 1000) / 1000

        private fun number(value: Double): String =
            if (value == round(value)) value.toLong().toString() else rounded(value).toString()
    }
}
