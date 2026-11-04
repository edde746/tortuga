package com.edde746.tortuga.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.detail.DetailUserRating
import com.edde746.tortuga.strings.InterfaceLocale
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * In Arabic, laid out right to left, the rating picker's stars run from the right and are counted from there: a tap on
 * the leftmost star's right half (the fifth star's leading half) gives 9, on the rightmost star's left half 2 and its
 * right half 1; a drag from the rightmost star to the leftmost's left quarter gives 10 on release; Left raises the score
 * and Right lowers it (four Lefts and a Right, then Select: 3); and a score of 1 lights the rightmost star's right
 * half, not its left.
 */
@OptIn(ExperimentalTestApi::class)
class UserRatingDirectionTest {
    private val unrated = DetailUserRating(text = null, label = T.detail.userRating.rateLabel, score = null, trackers = emptyList())

    @Test
    fun aTapCountsTheStarsFromTheRightInArabic() = arabic {
        runComposeUiTest {
            val rated = picker(unrated)
            val stars = stars()
            // In star widths from the stars' left edge, the gaps before each star added.
            for (at in listOf(0.75f, 4.25f, 4.75f)) {
                stars.performTouchInput { click(Offset(starWidth() * at + at.toInt() * StarGap.toPx(), centerY)) }
                waitForIdle()
            }
            assertEquals(listOf<Int?>(9, 2, 1), rated, "taps on the leftmost star's right half, the rightmost's left and right halves")
        }
    }

    @Test
    fun aDragCountsTheStarsFromTheRightInArabic() = arabic {
        runComposeUiTest {
            val rated = picker(unrated)
            stars().performTouchInput {
                swipe(Offset(width - starWidth() / 2, centerY), Offset(starWidth() / 4, centerY), durationMillis = 300)
            }
            waitForIdle()
            assertEquals(listOf<Int?>(10), rated, "a drag from the rightmost star to the leftmost's left quarter")
        }
    }

    @Test
    fun leftRaisesTheScoreInArabic() = arabic {
        runComposeUiTest {
            val rated = picker(unrated)
            val stars = stars()
            stars.requestFocus()
            stars.performKeyInput {
                repeat(4) { pressKey(Key.DirectionLeft) }
                pressKey(Key.DirectionRight)
                pressKey(Key.Enter)
            }
            waitForIdle()
            assertEquals(listOf<Int?>(3), rated, "four Lefts and a Right on the unrated stars, then Select")
        }
    }

    @Test
    fun aHalfStarLightsFromTheRightInArabic() = arabic {
        runComposeUiTest {
            picker(unrated.copy(text = "1", label = T.detail.userRating.label("1"), score = 1))
            val pixels = stars().captureToImage().toPixelMap()
            val star = (pixels.width - with(density) { (StarGap * 4).toPx() }) / 5
            fun lit(from: Float, to: Float) = (from.toInt() until to.toInt()).sumOf { x ->
                (0 until pixels.height).count { y -> pixels[x, y].red > 0.8f }
            }
            val right = lit(pixels.width - star / 2, pixels.width.toFloat())
            val left = lit(pixels.width - star, pixels.width - star / 2)
            assertTrue(right > 0, "a score of 1 in Arabic lights nothing of the rightmost star's right half")
            assertEquals(0, left, "a score of 1 in Arabic lights the rightmost star's left half")
        }
    }

    private fun arabic(test: () -> Unit) {
        try {
            T.locale = "ar"
            test()
        } finally {
            T.locale = "en"
        }
    }

    /** The rating picker for [rating] on a phone, laid out right to left as the app lays out Arabic; the scores it gives. */
    private fun ComposeUiTest.picker(rating: DetailUserRating): List<Int?> {
        val rated = mutableListOf<Int?>()
        setContent {
            val direction = if (InterfaceLocale.isRightToLeft(T.locale)) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                TortugaTheme {
                    Box(Modifier.size(411.dp, 914.dp)) {
                        AdaptiveLayout(FormFactor.HANDHELD) {
                            OverlayHostBox { UserRatingSheet(rating, { rated += it }, onDismiss = {}) }
                        }
                    }
                }
            }
        }
        waitForIdle()
        return rated
    }

    /** The stars, one element adjustable as a slider is. */
    private fun ComposeUiTest.stars(): SemanticsNodeInteraction =
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))

    /** Each star's width across the stars, five of them [StarGap] apart. */
    private fun TouchInjectionScope.starWidth() = (width - (StarGap * 4).toPx()) / 5
}
