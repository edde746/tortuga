package com.edde746.tortuga.ui.guide

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.presentation.guide.GuideBody
import com.edde746.tortuga.presentation.guide.GuideChannel
import com.edde746.tortuga.presentation.guide.GuideDay
import com.edde746.tortuga.presentation.guide.GuideProgramme
import com.edde746.tortuga.presentation.guide.GuideUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GuideScreenTest {
    /** Where each harness name was placed, in px. */
    private val placed = mutableMapOf<String, Rect>()

    /**
     * A guide at the day's end in a desktop window that then grows wider: the time shown comes back so the day's last
     * programme still ends at the grid's right edge, with no blank time past the day's end.
     */
    @Test
    fun theTimeShownStaysWithinTheDayAsTheWindowGrows() = runComposeUiTest {
        var width by mutableStateOf(700)
        setContent {
            val probe = LayoutProbe { name, bounds -> if (bounds != null) placed[name] = bounds else placed -= name }
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(width.dp, 700.dp)) {
                        AdaptiveLayout(FormFactor.DESKTOP) {
                            GuideScreen(state, GuideIntents({}, {}, {}, {}, {}), onBack = null)
                        }
                    }
                }
            }
        }
        waitForIdle()
        assertEndsAtTheGridsEdge("at 700 dp")

        // Wider, within the test's 1024 px window.
        width = 1000
        waitForIdle()
        assertEndsAtTheGridsEdge("at 1000 dp")
    }

    /**
     * On the desktop a channel without programmes picked on one day, then a day on which it has some: the details don't
     * call it empty; the first channel's programme the guide opens on stands in, picked, as for a programme the day
     * doesn't have.
     */
    @Test
    fun aChannelPickedWithoutProgrammesIsntShownEmptyOnADayWithSome() = runComposeUiTest {
        var shown by mutableStateOf(twoChannels(secondHasProgrammes = false))
        setContent {
            val probe = LayoutProbe { name, bounds -> if (bounds != null) placed[name] = bounds else placed -= name }
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(1000.dp, 700.dp)) {
                        AdaptiveLayout(FormFactor.DESKTOP) {
                            GuideScreen(shown, GuideIntents({}, {}, {}, {}, {}), onBack = null)
                        }
                    }
                }
            }
        }
        onAllNodesWithText(T.guide.noProgrammes).onFirst().performClick()
        waitForIdle()
        assertEquals(placed["guide.empty.1"], placed["guide.selected"], "the empty channel isn't picked")

        shown = twoChannels(secondHasProgrammes = true)
        waitForIdle()
        onAllNodesWithText(T.guide.noProgrammes).assertCountEquals(0)
        assertEquals(placed["guide.programme.0.0"], placed["guide.selected"], "the opening programme isn't picked")
    }

    /** Two channels of two-hour programmes, the second without any unless [secondHasProgrammes]. */
    private fun twoChannels(secondHasProgrammes: Boolean) = GuideUiState(
        days = listOf(GuideDay(0, "Today", selected = true, today = true)),
        dayStartMillis = 0,
        dayEndMillis = day,
        openingMillis = 0,
        body = GuideBody.Grid(
            listOf(channel("first", programmes = true), channel("second", programmes = secondHasProgrammes)),
        ),
    )

    private fun channel(id: String, programmes: Boolean) = GuideChannel(
        id = id,
        type = "tv",
        name = id,
        logo = null,
        programmes = if (!programmes) emptyList() else List(PROGRAMMES) { i ->
            GuideProgramme(
                id = "$id$i", title = "$id $i", startMillis = i * 2 * HOUR, endMillis = (i + 1) * 2 * HOUR,
                timeText = "", startText = "", cellTimeText = "", overview = null, thumbnail = null, infoText = null,
                creditsText = null, onAir = false, past = false, headline = "$id $i",
            )
        },
        onNow = null,
        next = null,
        startIndex = if (programmes) 0 else null,
        preview = MetaItemPreview(id, "tv", id),
    )

    private fun assertEndsAtTheGridsEdge(window: String) {
        val grid = placed.getValue("guide.grid")
        val last = placed.getValue("guide.programme.0.${PROGRAMMES - 1}")
        // The programmes' gap (4 dp) after the last one, at most.
        assertEquals(grid.right, last.right, 8f, "$window the day's last programme ends ${grid.right - last.right} px before the grid's edge")
    }

    private val day = 24 * HOUR

    /** A day of two-hour programmes on one channel, opened at the day's end. */
    private val state = GuideUiState(
        days = listOf(GuideDay(0, "Today", selected = true, today = true)),
        dayStartMillis = 0,
        dayEndMillis = day,
        openingMillis = day,
        body = GuideBody.Grid(
            listOf(
                GuideChannel(
                    id = "channel",
                    type = "tv",
                    name = "Channel",
                    logo = null,
                    programmes = List(PROGRAMMES) { i ->
                        GuideProgramme(
                            id = "p$i", title = "Programme $i", startMillis = i * 2 * HOUR, endMillis = (i + 1) * 2 * HOUR,
                            timeText = "", startText = "", cellTimeText = "", overview = null, thumbnail = null, infoText = null,
                            creditsText = null, onAir = false, past = false, headline = "Programme $i",
                        )
                    },
                    onNow = null,
                    next = null,
                    startIndex = 0,
                    preview = MetaItemPreview("channel", "tv", "Channel"),
                ),
            ),
        ),
    )

    private companion object {
        const val HOUR = 3_600_000L
        const val PROGRAMMES = 12
    }
}
