package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.DeleteAllDownloadsRow
import com.edde746.tortuga.presentation.settings.DownloadSettingsUiState
import com.edde746.tortuga.presentation.settings.SettingLanguagesRow
import com.edde746.tortuga.presentation.settings.SettingStepperRow
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings → Downloads by remote. */
@OptIn(ExperimentalTestApi::class)
class DownloadSettingsPaneTest {
    /** The harness name of the element holding focus, as the layout probes report it. */
    private var focused: String? = null

    private val probe = object : LayoutProbe {
        override fun place(name: String, bounds: Rect?) = Unit

        override fun focus(name: String, focused: Boolean) {
            if (focused) this@DownloadSettingsPaneTest.focused = name
            else if (this@DownloadSettingsPaneTest.focused == name) this@DownloadSettingsPaneTest.focused = null
        }
    }

    /**
     * On TV Down from Wi-Fi only lands on downloads at once (its value: at the minimum, − is off), between the two
     * full-width switches, and Down again on auto-remove watched, from which Up comes back to the stepper.
     */
    @Test
    fun onTvUpAndDownReachTheStepperBetweenTheSwitches() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) {
                            DownloadSettingsPane(state, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {})
                        }
                    }
                }
            }
        }
        onNodeWithText(state.wifiOnly!!.title).requestFocus()
        runOnIdle { assertEquals("settings.downloads.wifiOnly", focused) }
        press(Key.DirectionDown)
        runOnIdle { assertEquals("settings.downloads.atOnce.value", focused, "Down from Wi-Fi only passed over the stepper") }
        press(Key.DirectionDown)
        runOnIdle { assertEquals("settings.downloads.autoRemoveWatched", focused) }
        press(Key.DirectionUp)
        runOnIdle { assertEquals("settings.downloads.atOnce.value", focused, "Up from auto-remove passed over the stepper") }
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private val state = DownloadSettingsUiState(
        loading = false,
        title = "Downloads",
        note = "These settings apply to this device only.",
        location = null,
        folder = null,
        transferHeader = "Transfers",
        wifiOnly = SettingToggleRow("wifiOnly", "Wi-Fi only", "Downloads wait for Wi-Fi or another unmetered network.", checked = false),
        atOnce = SettingStepperRow(
            "Downloads at once", null, value = 1, valueLabel = "1", min = 1, max = 3, step = 1, canDecrease = false, canIncrease = true,
            decreaseLabel = "Decrease Downloads at once", increaseLabel = "Increase Downloads at once",
        ),
        autoRemoveWatched = SettingToggleRow("autoRemoveWatched", "Auto-remove watched", "Deletes a download once it's watched.", checked = false),
        subtitleLanguages = SettingLanguagesRow("Subtitle languages", "Saved with each download.", "English"),
        useProfileLanguagesText = null,
        languagePicker = null,
        storageHeader = "Storage",
        storage = "33.9 MB used · 40 GB free",
        deleteAll = DeleteAllDownloadsRow("Delete all downloads", enabled = true),
        deleteAllDialog = null,
    )
}
