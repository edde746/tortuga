package com.edde746.tortuga.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** A store's one-off message at the bottom of a screen: TalkBack speaks it, and it stays up as its user needs. */
@OptIn(ExperimentalTestApi::class)
class TimedToastTest {
    private var text by mutableStateOf<String?>("Added to Downloads")
    private var shown = 0

    /** TalkBack speaks a toast as it shows and as its text changes, wherever its focus is. */
    @Test
    fun aToastIsSpokenAsItShowsAndChanges() = runComposeUiTest {
        toast(accessibility = null)
        onNodeWithText("Added to Downloads").assert(spoken)
        text = "Sam joined"
        onNodeWithText("Sam joined").assert(spoken)
    }

    /**
     * Android's "Time to take action" set to 20 s keeps a toast up past its usual [TOAST_MILLIS]: the store hears it was
     * shown (and clears it) only after the 20 s.
     */
    @Test
    fun aToastStaysForTheTimeTheUserAsksFor() = runComposeUiTest {
        toast(accessibility = twentySeconds)
        mainClock.advanceTimeBy(TOAST_MILLIS + 500)
        onNodeWithText("Added to Downloads").assertExists()
        runOnIdle { assertEquals(0, shown) }
        mainClock.advanceTimeBy(20_000 - TOAST_MILLIS)
        onNodeWithText("Added to Downloads").assertDoesNotExist()
        runOnIdle { assertEquals(1, shown) }
    }

    /** The toast while [text] is set, with [accessibility] as the platform's accessibility settings (none: desktop's). */
    private fun ComposeUiTest.toast(accessibility: AccessibilityManager?) = setContent {
        CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
            TortugaTheme {
                text?.let {
                    TimedToast(it, "toast", onShown = {
                        shown++
                        text = null
                    })
                }
            }
        }
    }

    private val spoken = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    /** Android's "Time to take action" set to 20 s: what shows text or icons stays up at least that long. */
    private val twentySeconds = object : AccessibilityManager {
        override fun calculateRecommendedTimeoutMillis(
            originalTimeoutMillis: Long,
            containsIcons: Boolean,
            containsText: Boolean,
            containsControls: Boolean,
        ) = if (containsText || containsIcons) maxOf(originalTimeoutMillis, 20_000L) else originalTimeoutMillis
    }
}
