package com.edde746.tortuga.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A screen's backdrop runs under the rail and the collapsed TV column to the window's edge, dimmed from that edge and
 * clear three widths of the navigation from it; a TV row's scrolled-away cards end at the column's edge, so only the
 * backdrop is under it.
 */
@OptIn(ExperimentalTestApi::class)
class NavigationBackdropTest {
    @Test
    fun aBackdropRunsUnderTheRailDimmed() = runComposeUiTest { assertUnderNavigation(FormFactor.DESKTOP, width = 80) }

    @Test
    fun aBackdropRunsUnderTheCollapsedTvColumnDimmed() = runComposeUiTest { assertUnderNavigation(FormFactor.TV, width = 72) }

    /** A white row laid out from the screen's place: black under the column (72 dp), white past its edge. */
    @Test
    fun aTvRowEndsAtTheColumnsEdge() = runComposeUiTest {
        val pixels = frame(FormFactor.TV) { Box(Modifier.fillMaxSize().clipStart(navigationOverlap).background(Color.White)) }
        assertEquals(0f, pixels.at(70, 4).red, 0.01f, "the row draws under the column: ${pixels.at(70, 4)}")
        assertEquals(1f, pixels.at(76, 4).red, 0.01f, "the row is cut past the column's edge: ${pixels.at(76, 4)}")
    }

    /**
     * A white backdrop: dark at the window's edge under the navigation ([width] dp), lighter at the navigation's far
     * edge, white three widths from the window's edge and beyond.
     */
    private fun ComposeUiTest.assertUnderNavigation(formFactor: FormFactor, width: Int) {
        val pixels = frame(formFactor) { NavigationBackdrop(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(Color.White)) } }
        val edge = pixels.at(1, 4).red
        val farEdge = pixels.at(width, 4).red
        assertEquals(1 - NAVIGATION_DIM, edge, 0.02f, "the window's edge under the navigation is $edge, not the dimmed backdrop")
        assertTrue(farEdge > edge + 0.1f && farEdge < 0.5f, "the navigation's far edge is $farEdge: the dim doesn't ease out")
        assertEquals(1f, pixels.at(width * 3 + 2, 4).red, 0.01f, "three widths out the backdrop is ${pixels.at(width * 3 + 2, 4)}, not clear")
        assertEquals(1f, pixels.at(990, 300).red, 0.01f, "the backdrop beside the navigation is ${pixels.at(990, 300)}")
    }

    /** [screen] in the navigation frame of a 1000 × 600 dp [formFactor] window, captured. */
    private fun ComposeUiTest.frame(formFactor: FormFactor, screen: @Composable () -> Unit): PixelMap {
        setContent {
            TortugaTheme {
                Box(Modifier.size(1000.dp, 600.dp).background(TortugaColors.Background)) {
                    AdaptiveLayout(formFactor) {
                        NavigationScaffold(
                            destinations = TopLevelDestination.entries,
                            selected = TopLevelDestination.BOARD,
                            onSelect = {},
                            showNavigation = true,
                        ) { screen() }
                    }
                }
            }
        }
        return onRoot().captureToImage().toPixelMap()
    }

    /** The pixel at ([x], [y]) dp of a 1000 dp wide capture. */
    private fun PixelMap.at(x: Int, y: Int): Color {
        val scale = width / 1000f
        return this[(x * scale).roundToInt(), (y * scale).roundToInt()]
    }
}
