package com.edde746.tortuga.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.menu.WatchlistRow
import com.edde746.tortuga.presentation.menu.WatchlistSheet
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each row of the trackers' watchlists sheet changes its own tracker's list. */
@OptIn(ExperimentalTestApi::class)
class WatchlistSheetTest {
    @Test
    fun aRowTogglesItsOwnTracker() = runComposeUiTest {
        val toggled = mutableListOf<TrackerService>()
        val sheet = WatchlistSheet(
            "Tracker watchlists",
            "Each list is changed on its own.",
            listOf(
                WatchlistRow(TrackerService.TRAKT, "Trakt watchlist", listed = true, status = null, busy = false, attention = false),
                WatchlistRow(TrackerService.SIMKL, "Simkl plan to watch", listed = false, status = "Not saved", busy = false, attention = true),
            ),
        )
        setContent {
            TortugaTheme {
                Box(Modifier.size(411.dp, 800.dp)) {
                    AdaptiveLayout(FormFactor.HANDHELD) { OverlayHostBox { WatchlistSheet(sheet, { toggled += it }, {}) } }
                }
            }
        }
        onNodeWithText("Simkl plan to watch").performClick()
        onNodeWithText("Trakt watchlist").performClick()
        runOnIdle { assertEquals(listOf(TrackerService.SIMKL, TrackerService.TRAKT), toggled) }
    }
}
