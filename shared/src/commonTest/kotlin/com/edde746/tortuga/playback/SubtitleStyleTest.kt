package com.edde746.tortuga.playback

import com.edde746.tortuga.data.settings.SubtitleAppearance
import com.edde746.tortuga.data.settings.SubtitleColor
import com.edde746.tortuga.data.settings.SubtitleOutline
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleStyleTest {
    private val default = SubtitleAppearance.DEFAULT

    private fun properties(appearance: SubtitleAppearance) = SubtitleStyle.of(appearance).properties()

    @Test
    fun theDefaultsAreMpvsOwnLookAndLeaveStyledSubtitlesAlone() {
        assertEquals(
            mapOf(
                "sub-ass-override" to "scale",
                "sub-font-size" to "38",
                "sub-color" to "#FFFFFFFF",
                "sub-outline-size" to "1.65",
                "sub-outline-color" to "#FF000000",
                "sub-border-style" to "outline-and-shadow",
                "sub-back-color" to "#AF000000",
                "sub-shadow-offset" to "0",
                "sub-bold" to "no",
            ),
            properties(default),
        )
        assertEquals(100.0, SubtitleStyle.DEFAULT.position(0.0, SubtitleFrame.SCREEN), "mpv's own place")
    }

    @Test
    fun theSizeScalesTheTextItsOutlineAndTheBoxMarginTogether() {
        val large = SubtitleStyle.of(default.copy(sizePercent = 150, backgroundPercent = 50))
        assertEquals(57.0, large.fontSize)
        assertEquals(2.475, large.outlineSize)
        assertEquals(6.0, large.shadowOffset)
        val small = SubtitleStyle.of(default.copy(sizePercent = 50, outline = SubtitleOutline.THICK))
        assertEquals(19.0, small.fontSize)
        assertEquals(1.65, small.outlineSize)
        // Written as decimals mpv reads back unchanged, not as the doubles' long expansions.
        val odd = properties(default.copy(sizePercent = 110))
        assertEquals("41.8", odd["sub-font-size"])
        assertEquals("1.815", odd["sub-outline-size"])
        assertEquals("76", properties(default.copy(sizePercent = 200))["sub-font-size"])
    }

    @Test
    fun coloursAreOpaqueArgbAndTheOutlineIsNoneThinOrThick() {
        val styled = properties(default.copy(textColor = SubtitleColor.YELLOW, outlineColor = SubtitleColor.CYAN))
        assertEquals("#FFFFFF00", styled["sub-color"])
        assertEquals("#FF00FFFF", styled["sub-outline-color"])
        assertEquals(
            listOf("#FFFFFFFF", "#FFFFFF00", "#FF00FF00", "#FF00FFFF", "#FFA0A0A0", "#FF000000"),
            SubtitleColor.entries.map { SubtitleStyle.argb(it, 100) },
        )
        assertEquals(
            listOf("0", "1.65", "3.3"),
            SubtitleOutline.entries.map { properties(default.copy(outline = it))["sub-outline-size"] },
        )
    }

    @Test
    fun aBackgroundIsABlackBoxOfItsOpacityWithMpvsMarginAndNoneAtZero() {
        for ((percent, color) in listOf(25 to "#40000000", 50 to "#80000000", 75 to "#BF000000", 100 to "#FF000000")) {
            val box = properties(default.copy(backgroundPercent = percent))
            assertEquals("background-box", box["sub-border-style"], "$percent %")
            assertEquals(color, box["sub-back-color"], "$percent %")
            assertEquals("4", box["sub-shadow-offset"], "$percent %")
        }
        val none = properties(default.copy(backgroundPercent = 0))
        assertEquals("outline-and-shadow", none["sub-border-style"])
        assertEquals("0", none["sub-shadow-offset"], "no box and no shadow")
    }

    @Test
    fun boldAndTheStyledOverrideMapToTheirSwitches() {
        assertEquals("yes", properties(default.copy(bold = true))["sub-bold"])
        assertEquals("force", properties(default.copy(styledOverride = true))["sub-ass-override"])
    }

    @Test
    fun subtitlesSitAtTheHigherOfTheirLiftAndTheControls() {
        val screen = SubtitleFrame.SCREEN
        val lifted = SubtitleStyle.of(default.copy(liftPercent = 10))
        assertEquals(90.0, lifted.position(0.0, screen))
        assertEquals(80.0, lifted.position(0.2, screen), "controls above the lift: above the controls")
        assertEquals(90.0, lifted.position(0.05, screen), "controls under the lifted line: it stays")
        assertEquals(90.0, lifted.position(Double.NaN, screen), "no measured controls")
        assertEquals(0.0, lifted.position(1.5, screen), "never above the top")
        assertEquals(50.0, SubtitleStyle.of(default.copy(liftPercent = 50)).position(0.2, screen))
        // Placed by the screen, without a lift the controls' lift is the old `100 × (1 − fraction)`.
        assertEquals(75.0, SubtitleStyle.DEFAULT.position(0.25, screen))
    }

    @Test
    fun placedByThePictureSubtitlesClearOnlyTheControlsOverIt() {
        // An iPad in landscape (2064 px tall) with a 16:9 picture between 258 px bands; an upright phone (2400 px)
        // with a 1080 px band in its middle.
        val ipad = SubtitleFrame.picture(2064.0, 258.0, 258.0)
        val phone = SubtitleFrame.picture(2400.0, 660.0, 660.0)
        assertEquals(100.0, SubtitleStyle.DEFAULT.position(0.12, ipad), "controls in the band below: no jump")
        assertEquals(100.0, SubtitleStyle.DEFAULT.position(0.25, phone), "controls in the band below: no jump")
        // Controls reaching 361.2 px up cover the picture's bottom 103.2 px: a fifteenth of its 1548 px.
        assertEquals(1.0 / 15, ipad.lift(0.175), 1e-9)
        assertEquals(100.0 * (1 - 1.0 / 15), SubtitleStyle.DEFAULT.position(0.175, ipad), 1e-9)
        // A picture filling the height (a phone in landscape): the controls' share as on the screen.
        val filled = SubtitleFrame.picture(1080.0, 0.0, 0.0)
        assertEquals(75.0, SubtitleStyle.DEFAULT.position(0.25, filled))
        assertEquals(SubtitleStyle.DEFAULT.position(0.25, SubtitleFrame.SCREEN), SubtitleStyle.DEFAULT.position(0.25, filled))
        // The style's own lift where the controls lift less, the controls where they lift more.
        val lifted = SubtitleStyle.of(default.copy(liftPercent = 10))
        assertEquals(90.0, lifted.position(0.12, ipad), "controls in the band: the style's lift")
        assertEquals(90.0, lifted.position(0.175, ipad), "a fifteenth is less than the style's tenth")
        assertEquals(80.0, lifted.position(0.2, filled), "controls over a fifth of the picture: above them")
    }

    @Test
    fun theFrameTakesItsBandsFromMpvsOsdDimensions() {
        // A band above the picture only (the picture at the surface's bottom): all the controls cover is over it, a
        // share of the shorter picture.
        val topOnly = SubtitleFrame.picture(2000.0, 500.0, 0.0)
        assertEquals(0.2 / 1.5, topOnly.lift(0.1), 1e-9)
        assertEquals(100.0 * (1 - 0.2 / 1.5), SubtitleStyle.DEFAULT.position(0.1, topOnly), 1e-9)
        // A picture zoomed past the surface's bottom edge (negative band): its bottom 100 px are off the surface.
        assertEquals(0.3, SubtitleFrame.picture(1000.0, -100.0, -100.0).lift(0.26), 1e-9)
        // No output size yet: the whole surface.
        assertEquals(SubtitleFrame.SCREEN, SubtitleFrame.picture(0.0, 0.0, 0.0))
        assertEquals(SubtitleFrame.SCREEN, SubtitleFrame.picture(Double.NaN, 0.0, 0.0))
        assertEquals(0.0, SubtitleFrame(100.0, 50.0, 50.0).lift(0.9), "a frame of no height")
        assertEquals(0.0, SubtitleFrame.SCREEN.lift(Double.NaN), "no measured controls")
    }
}
