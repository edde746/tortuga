package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.data.transfer.DataCategory
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.DataCategoryRow
import com.edde746.tortuga.presentation.settings.DataImportProfileRow
import com.edde746.tortuga.presentation.settings.DataImportReview
import com.edde746.tortuga.presentation.settings.SettingChoiceOption
import com.edde746.tortuga.presentation.settings.SettingChoiceRow
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings → Export and import's review of an opened file, by keyboard. */
@OptIn(ExperimentalTestApi::class)
class DataSettingsPaneTest {
    /** The harness name of the element holding focus, as the layout probes report it. */
    private var focused: String? = null

    /** The harness names' bounds (window pixels), as the layout probes report them. */
    private val bounds = HashMap<String, Rect>()

    private val probe = object : LayoutProbe {
        override fun place(name: String, bounds: Rect?) {
            if (bounds != null) this@DataSettingsPaneTest.bounds[name] = bounds else this@DataSettingsPaneTest.bounds.remove(name)
        }

        override fun focus(name: String, focused: Boolean) {
            if (focused) this@DataSettingsPaneTest.focused = name
            else if (this@DataSettingsPaneTest.focused == name) this@DataSettingsPaneTest.focused = null
        }
    }

    private var review by mutableStateOf(review(profileTicked = true))
    private var cancelled = 0

    private val intents = DataSettingsIntents(
        setExportCategory = { _, _ -> },
        setExportProfile = { _, _ -> },
        export = {},
        import = {},
        dismissNotice = {},
        setImportCategory = { _, _ -> },
        setImportProfile = { _, enabled -> review = review(profileTicked = enabled) },
        setImportTarget = { _, _ -> },
        confirmImport = {},
        cancelImport = { cancelled++ },
    )

    /**
     * With the keyboard (desktop) the review opens on its first category, and Down walks every category, the profile
     * and its Into row to the buttons, Right to Import. Unticking the profile takes Into and Import out of reach (both
     * disabled); Escape cancels.
     */
    @Test
    fun theKeyboardWalksTheReviewInOrderAndEscapeCancelsIt() = runComposeUiTest {
        showReview()
        runOnIdle { assertEquals("settings.data.review.addons", focused, "the review didn't open on its first category") }
        val walked = listOf("library", "profile.0", "profile.0.target", "cancel").map { next ->
            press(Key.DirectionDown)
            runOnIdle { focused } to "settings.data.review.$next"
        }
        walked.forEach { (was, expected) -> assertEquals(expected, was, "Down went elsewhere: $walked") }
        press(Key.DirectionRight)
        runOnIdle { assertEquals("settings.data.review.confirm", focused) }

        press(Key.DirectionUp)
        press(Key.DirectionUp)
        runOnIdle { assertEquals("settings.data.review.profile.0", focused) }
        press(Key.Enter)
        press(Key.DirectionDown)
        runOnIdle { assertEquals("settings.data.review.cancel", focused, "Down from an unticked profile didn't pass over its Into") }
        press(Key.DirectionRight)
        runOnIdle { assertEquals("settings.data.review.cancel", focused, "a disabled Import took focus") }

        press(Key.Escape)
        runOnIdle { assertEquals(1, cancelled) }
    }

    /**
     * The Into menu, among the sheet's last rows, opens over its row's end, where the row's value is: never off at the
     * row's start, and never above or below the row, over the row before or after it (as if it were that row's menu).
     */
    @Test
    fun theIntoMenuOpensAtItsRow() = runComposeUiTest {
        showReview()
        repeat(3) { press(Key.DirectionDown) }
        val target = "settings.data.review.profile.0.target"
        runOnIdle { assertEquals(target, focused) }
        press(Key.Enter)
        runOnIdle {
            val row = bounds.getValue(target)
            val first = bounds.getValue("$target.option.0")
            val last = bounds.getValue("$target.option.1")
            val padding = density.run { 8.dp.toPx() }
            assertEquals(row.right, first.right, 1f, "the menu's end isn't at its row's end: row $row, menu from $first")
            assertTrue(first.top - padding <= row.top + 1f && last.bottom + padding >= row.bottom - 1f, "the menu isn't over its row: row $row, menu $first to $last")
        }
    }

    private fun ComposeUiTest.showReview() {
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 600.dp)) {
                        AdaptiveLayout(FormFactor.DESKTOP, Modifier.arrowKeyFocus()) {
                            OverlayHostBox(Modifier.fillMaxSize()) { DataImportSheet(review, intents) }
                        }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun review(profileTicked: Boolean) = DataImportReview(
        title = "Import",
        source = "Exported on Oct 11, 2026 by Tortuga 1.0.0",
        dataHeader = "Data",
        categories = listOf(
            DataCategoryRow(DataCategory.ADDONS, SettingToggleRow("review.ADDONS", "Addons", "3 addons", checked = true)),
            DataCategoryRow(DataCategory.LIBRARY, SettingToggleRow("review.LIBRARY", "Library", "12 titles", checked = true)),
        ),
        profilesHeader = "Profiles",
        profiles = listOf(
            DataImportProfileRow(
                "file-profile",
                SettingToggleRow("review.profile.file-profile", "Developer", null, checked = profileTicked),
                SettingChoiceRow(
                    "review.profile.file-profile.target",
                    "Into",
                    null,
                    listOf(SettingChoiceOption(DataImportProfileRow.NEW_PROFILE, "A new profile", true), SettingChoiceOption("here", "Developer", false)),
                    enabled = profileTicked,
                ),
            ),
        ),
        note = "Opens a file exported from Tortuga. Newer data wins item by item, and nothing on this device is deleted.",
        confirmLabel = "Import",
        canConfirm = profileTicked,
        cancelLabel = "Cancel",
    )
}
