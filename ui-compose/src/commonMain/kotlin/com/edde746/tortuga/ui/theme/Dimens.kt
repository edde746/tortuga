package com.edde746.tortuga.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.ui.adaptive.WidthClass
import com.edde746.tortuga.ui.adaptive.WindowLayout

/**
 * Size tokens for the window at hand ([com.edde746.tortuga.ui.adaptive.AdaptiveLayout] provides them): dense on
 * phones, a step roomier on tablets and desktop, larger on TV (10-foot viewing) where focused elements also grow.
 *
 * @param screenPadding the horizontal inset of screen content (TV: overscan-safe).
 * @param gutter the gap between items in a row or grid.
 * @param sectionSpacing the gap between shelves and between groups of a page.
 * @param shelfHeaderGap the gap between a shelf's heading and its cards.
 * @param posterWidth the width of a portrait poster; landscape and square posters derive from it.
 * @param sectionTitleSize a shelf's or group's heading.
 * @param chipHeight a chip's height.
 * @param controlHeight a button's height.
 * @param iconButtonSize a round icon button's diameter.
 * @param controlFocusScale how much a focused button, chip or row grows (TV only; 1 elsewhere).
 * @param posterFocusScale how much a focused poster or thumbnail grows (TV only; 1 elsewhere).
 * @param focusRing the width of the focus ring.
 * @param contentMaxWidth lists and text columns stay this narrow on wide windows, centred
 *   ([com.edde746.tortuga.ui.components.contentColumn]).
 */
@Immutable
data class TortugaDimens(
    val screenPadding: Dp,
    val gutter: Dp,
    val sectionSpacing: Dp,
    val shelfHeaderGap: Dp,
    val posterWidth: Dp,
    val sectionTitleSize: TextUnit,
    val chipHeight: Dp,
    val controlHeight: Dp,
    val iconButtonSize: Dp,
    val controlFocusScale: Float,
    val posterFocusScale: Float,
    val focusRing: Dp,
    val contentMaxWidth: Dp,
) {
    /** The width of a card in a row for [shape]: landscape cards are wider so rows keep a similar height. */
    fun cardWidth(shape: PosterShape): Dp = when (shape) {
        PosterShape.POSTER -> posterWidth
        PosterShape.SQUARE -> posterWidth * 1.2f
        PosterShape.LANDSCAPE -> posterWidth * 1.9f
    }

    /** Whether focused elements grow (TV). */
    val focusGrows: Boolean get() = controlFocusScale > 1f

    companion object {
        fun of(layout: WindowLayout): TortugaDimens = when {
            layout.formFactor == FormFactor.TV -> TortugaDimens(
                screenPadding = 48.dp, gutter = 16.dp, sectionSpacing = 24.dp, shelfHeaderGap = 8.dp, posterWidth = 150.dp,
                sectionTitleSize = 22.sp, chipHeight = 40.dp, controlHeight = 48.dp, iconButtonSize = 48.dp,
                controlFocusScale = 1.05f, posterFocusScale = 1.06f, focusRing = 2.dp, contentMaxWidth = 1100.dp,
            )
            layout.widthClass == WidthClass.COMPACT -> TortugaDimens(
                screenPadding = 16.dp, gutter = 10.dp, sectionSpacing = 16.dp, shelfHeaderGap = 8.dp, posterWidth = 104.dp,
                sectionTitleSize = 18.sp, chipHeight = 32.dp, controlHeight = 40.dp, iconButtonSize = 40.dp,
                controlFocusScale = 1f, posterFocusScale = 1f, focusRing = 2.dp, contentMaxWidth = 600.dp,
            )
            layout.formFactor == FormFactor.DESKTOP -> TortugaDimens(
                screenPadding = 24.dp, gutter = 12.dp, sectionSpacing = 20.dp, shelfHeaderGap = 8.dp, posterWidth = 132.dp,
                sectionTitleSize = 18.sp, chipHeight = 32.dp, controlHeight = 40.dp, iconButtonSize = 40.dp,
                controlFocusScale = 1f, posterFocusScale = 1f, focusRing = 2.dp, contentMaxWidth = 960.dp,
            )
            else -> TortugaDimens(
                screenPadding = 24.dp, gutter = 12.dp, sectionSpacing = 16.dp, shelfHeaderGap = 8.dp, posterWidth = 120.dp,
                sectionTitleSize = 18.sp, chipHeight = 32.dp, controlHeight = 40.dp, iconButtonSize = 40.dp,
                controlFocusScale = 1f, posterFocusScale = 1f, focusRing = 2.dp, contentMaxWidth = 760.dp,
            )
        }
    }
}

val LocalTortugaDimens = staticCompositionLocalOf { TortugaDimens.of(WindowLayout(FormFactor.DESKTOP, WidthClass.EXPANDED)) }
