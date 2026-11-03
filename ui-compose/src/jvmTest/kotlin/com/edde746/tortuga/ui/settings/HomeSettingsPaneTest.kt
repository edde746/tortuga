package com.edde746.tortuga.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.HomeCollectionActions
import com.edde746.tortuga.presentation.settings.HomeRowSetting
import com.edde746.tortuga.presentation.settings.HomeSettingsBody
import com.edde746.tortuga.presentation.settings.HomeSettingsUiState
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test

/** Settings → Home while its store reads the installed addons and the stored layout, then once both are read. */
@OptIn(ExperimentalTestApi::class)
class HomeSettingsPaneTest {
    /**
     * Before the stored layout is read, no change applies yet: the page offers no row, nor Reset, until loading ends,
     * and then both.
     */
    @Test
    fun whileLoadingThePageOffersNoRowAndNoReset() = runComposeUiTest {
        var state by mutableStateOf(loaded.copy(body = HomeSettingsBody.Loading, continueWatching = loaded.continueWatching.copy(enabled = false)))
        setContent {
            TortugaTheme {
                AdaptiveLayout(FormFactor.DESKTOP) { HomeSettingsPane(state, { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}) }
            }
        }
        onNodeWithText(ROW).assertDoesNotExist()
        onNodeWithText(RESET).assertDoesNotExist()

        state = loaded
        onNode(isToggleable() and hasText(ROW)).assertIsEnabled()
        onNodeWithText(RESET).assertIsEnabled()
    }

    private companion object {
        const val ROW = "Popular · Movies"
        const val RESET = "Reset to default"

        val loaded = HomeSettingsUiState(
            note = "Per profile, synced to your other devices.",
            continueWatching = SettingToggleRow("continueWatching", "Continue Watching", "What you're watching, first on Home", checked = true),
            collections = HomeCollectionActions("New collection", "Import collections", "Export collections", canExport = false),
            rowsHeader = "Rows",
            body = HomeSettingsBody.Rows(listOf(row("com.linvo.cinemeta/movie/top", ROW, canMoveUp = false), row("com.linvo.cinemeta/series/top", "Popular · Series", canMoveUp = true))),
            resetLabel = RESET,
            canReset = true,
        )

        fun row(id: String, title: String, canMoveUp: Boolean) = HomeRowSetting(
            id, title, "Cinemeta", shown = true, canMoveUp = canMoveUp, canMoveDown = !canMoveUp,
            moveUpLabel = "Move $title up", moveDownLabel = "Move $title down", dragLabel = "Reorder $title", moreLabel = "More actions for $title",
        )
    }
}
