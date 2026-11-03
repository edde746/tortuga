package com.edde746.tortuga.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.calendar.CalendarAgendaDay
import com.edde746.tortuga.presentation.calendar.CalendarBody
import com.edde746.tortuga.presentation.calendar.CalendarDay
import com.edde746.tortuga.presentation.calendar.CalendarEpisode
import com.edde746.tortuga.presentation.calendar.CalendarUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CalendarScreenTest {
    private fun episode(series: String, number: Int) = CalendarEpisode(
        "series", series.lowercase(), "${series.lowercase()}:1:$number", series, "S1E$number · Episode $number · 46 min",
        overview = DESCRIPTION, still = null, art = null, blurred = false, hiddenOverviewLabel = null,
    )

    /** October 2026 seen on Monday the 26th: one episode that day, two on the 27th, one on the 29th. */
    private val october = run {
        val episodes = mapOf(26 to listOf(episode("Andor", 4)), 27 to listOf(episode("Severance", 2), episode("Slow Horses", 6)), 29 to listOf(episode("The Bear", 1)))
        CalendarUiState(
            title = T.calendar.title,
            month = "October 2026",
            weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
            leadingDays = 3,
            days = (1..31).map { CalendarDay(it, today = it == 26, episodes = episodes[it]?.size ?: 0, accessibilityLabel = "October $it") },
            body = CalendarBody.Agenda(episodes.map { (day, list) -> CalendarAgendaDay(day, "October $day", today = day == 26, episodes = list) }),
            currentMonth = true,
        )
    }

    /**
     * On a phone an episode's row is Detail's: no card behind it (the page shows between its still and its edges), its
     * still 128 dp wide on the month's leading edge, the series a row's inset after it, the line under the series and
     * the description under that, cut at two lines.
     */
    @Test
    fun aPhonesEpisodeRowIsDetailsWithoutACard() = runComposeUiTest {
        setContent {
            TortugaTheme {
                Box(Modifier.size(412.dp, 1200.dp).background(Color.Black)) {
                    AdaptiveLayout(FormFactor.HANDHELD) {
                        CalendarScreen(october, CalendarIntents({}, {}, {}, {}), onBack = null)
                    }
                }
            }
        }
        waitForIdle()
        val row = onNode(hasClickAction() and hasText("Andor")).getBoundsInRoot()
        val series = onNodeWithText("Andor", useUnmergedTree = true).getBoundsInRoot()
        val line = onNodeWithText("S1E4 · Episode 4 · 46 min", useUnmergedTree = true).getBoundsInRoot()
        // Andor's: the first day's.
        val description = onAllNodesWithText(DESCRIPTION, useUnmergedTree = true).onFirst()
        val month = onNodeWithText("October 2026").getBoundsInRoot()
        assertNear(month.left, row.left + 12.dp, "the still's leading edge, the month's name at ${month.left}")
        assertNear(row.left + (12 + 128 + 12).dp, series.left, "the series after a 128 dp still between the row's insets")
        assertTrue(series.bottom <= line.top && line.bottom <= description.getBoundsInRoot().top, "series $series, line $line, description ${description.getBoundsInRoot()}")
        val layouts = mutableListOf<TextLayoutResult>().also { description.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(it) } }
        assertEquals(2, layouts.single().lineCount, "the description's lines")
        assertTrue(layouts.single().hasVisualOverflow, "the description cut short")
        val pixels = onRoot().captureToImage().toPixelMap()
        for ((x, at) in listOf(row.left + 4.dp to "before the still", row.right - 4.dp to "at the row's end")) {
            val pixel = with(density) { pixels[x.toPx().roundToInt(), ((row.top + row.bottom) / 2).toPx().roundToInt()] }
            assertEquals(Color.Black, pixel, "the page $at: a card's fill")
        }
    }

    /**
     * On TV a focused episode's row shows focus as Detail's episode cards there do: its still grows past its bounds,
     * and the row draws no fill.
     */
    @Test
    fun onTvAFocusedEpisodesStillGrowsAndTheRowStaysUnfilled() = runComposeUiTest {
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp).background(Color.Black)) {
                    AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                        CalendarScreen(october, CalendarIntents({}, {}, {}, {}), onBack = null)
                    }
                }
            }
        }
        waitForIdle()
        val episode = onNode(hasClickAction() and hasText("Andor"))
        episode.requestFocus()
        waitForIdle()
        val row = episode.getBoundsInRoot()
        val pixels = onRoot().captureToImage().toPixelMap()
        fun pixel(x: Dp) = with(density) { pixels[x.toPx().roundToInt(), ((row.top + row.bottom) / 2).toPx().roundToInt()] }
        assertNotEquals(Color.Black, pixel(row.left + 8.dp), "4 dp before the focused still: its growth")
        assertEquals(Color.Black, pixel(row.right - 4.dp), "the focused row's end: a fill")
    }

    /**
     * On TV the remote walks the month's grid a day at a time and goes in and out of the agenda without landing on the
     * element nearest the move: the screen opens on today with the month's bar still in view under Android TV's
     * scrolling (the focused element pivoted to 30 % of the height when it comes in); Down from the last week goes to
     * the first episode and Up from it back to the day it left; Select on a day moves focus to its first episode; Up
     * from the grid's first week enters the month's bar on Previous month, not on Next month above the Sunday.
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun onTvTheRemoteWalksTheGridAndTheAgenda() = runComposeUiTest {
        setContent {
            TortugaTheme {
                CompositionLocalProvider(LocalBringIntoViewSpec provides TvPivot) {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                            CalendarScreen(october, CalendarIntents({}, {}, {}, {}), onBack = null)
                        }
                    }
                }
            }
        }
        waitForIdle()
        assertFocus("October 26", "the screen opens on today")
        onNodeWithText("October 2026").assertIsDisplayed()
        press(Key.DirectionRight)
        assertFocus("October 27", "Right from today")
        press(Key.DirectionDown)
        assertFocus("Andor", "Down from the last week")
        press(Key.DirectionUp)
        assertFocus("October 27", "Up from the first episode goes back to the day it left")
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        assertFocus("October 29", "Right twice from the 27th")
        press(Key.Enter)
        assertFocus("The Bear", "Select on the 29th")
        onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        onNode(hasContentDescription("October 4") and hasClickAction()).requestFocus()
        press(Key.DirectionUp)
        assertFocus(T.calendar.previousMonth, "Up from Sunday the 4th")
    }

    /**
     * Select on Today (two months on) leaves focus in the month's bar on Previous month, the bar's first control
     * once Today is gone, not dropped (Android gives dropped focus to the screen's first element, the back button).
     */
    @Test
    fun onTvTodayHandsFocusToPreviousMonth() = runComposeUiTest {
        var state by mutableStateOf(october.copy(month = "December 2026", currentMonth = false))
        setContent {
            TortugaTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                        CalendarScreen(state, CalendarIntents({}, {}, { state = october }, {}), onBack = null)
                    }
                }
            }
        }
        waitForIdle()
        onNode(hasText(T.calendar.today) and hasClickAction()).requestFocus()
        press(Key.Enter)
        onNodeWithText("October 2026").assertIsDisplayed()
        assertFocus(T.calendar.previousMonth, "Select on Today")
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun assertNear(expected: Dp, actual: Dp, what: String) =
        assertTrue(abs((expected - actual).value) < 0.5f, "$what: $actual, not $expected")

    private companion object {
        const val DESCRIPTION = "A cargo pilot is drawn into a rebellion against the Empire, one job at a time, from the shipyards of " +
            "Ferrix to the prisons of Narkina 5, and every step costs someone he knows."
    }

    /** The focused element's text or screen-reader name contains [name]. */
    private fun ComposeUiTest.assertFocus(name: String, at: String) {
        val node = onNode(isFocused()).fetchSemanticsNode()
        val names = (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
        assertEquals(true, names.any { name in it }, "$at: focus is on $names, not $name")
    }
}

/** Android TV's scrolling: an element coming into view is placed at 30 % of the list's height. */
@OptIn(ExperimentalFoundationApi::class)
private object TvPivot : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - containerSize * 0.3f
}
