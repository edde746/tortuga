package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.PlaybackSettingRow
import com.edde746.tortuga.presentation.settings.PlaybackSettingsGroup
import com.edde746.tortuga.presentation.settings.PlaybackSettingsSection
import com.edde746.tortuga.presentation.settings.PlaybackSettingsUiState
import com.edde746.tortuga.presentation.settings.SettingChoiceOption
import com.edde746.tortuga.presentation.settings.SettingChoiceRow
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings → Playback. */
@OptIn(ExperimentalTestApi::class)
class PlaybackSettingsPaneTest {
    private val text = T.settings.playback.skipping

    /** Each Skipping row shows its mode and its choices pick a mode for its own row, by the row's and the option's ids. */
    @Test
    fun eachSkippingRowSetsItsKindsMode() = runComposeUiTest {
        val picked = mutableListOf<Pair<String, String>>()
        val placed = HashSet<String>()
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides LayoutProbe { name, bounds: Rect? -> if (bounds != null) placed += name }) {
                TortugaTheme {
                    Box(Modifier.size(800.dp, 900.dp)) {
                        AdaptiveLayout(FormFactor.HANDHELD) {
                            PlaybackSettingsPane(
                                state = state,
                                player = null,
                                onChoose = { key, id -> picked += key to id },
                                onToggle = { _, _ -> },
                                onStep = { _, _ -> },
                                onOpenLanguages = {},
                                onOpenPlayer = {},
                                onOpenStreams = {},
                            )
                        }
                    }
                }
            }
        }
        onNodeWithText(text.header).assertExists()
        onNodeWithText(text.footer).assertExists()
        runOnIdle {
            for (name in listOf("skipIntros", "skipRecaps", "skipCredits")) assertTrue("settings.playback.$name" in placed, "no $name row")
        }
        onNodeWithText(text.recaps).performClick()
        onNodeWithText(text.auto).performClick()
        onNodeWithText(text.credits).performClick()
        onNodeWithText(text.off).performClick()
        onNodeWithText(text.intros).performClick()
        onNodeWithText(text.auto).performClick()
        runOnIdle {
            assertEquals(listOf("skipRecaps" to "AUTO", "skipCredits" to "OFF", "skipIntros" to "AUTO"), picked)
        }
    }

    /** The store's Skipping group alone: every row at Show button, as the defaults are. */
    private val state = PlaybackSettingsUiState(
        loading = false,
        title = T.settings.playback.title,
        note = T.settings.playback.note,
        sections = listOf(
            PlaybackSettingsSection(
                group = PlaybackSettingsGroup.SKIPPING,
                header = text.header,
                footer = text.footer,
                rows = listOf(
                    PlaybackSettingRow.Choice(row("skipIntros", text.intros, text.introsHint)),
                    PlaybackSettingRow.Choice(row("skipRecaps", text.recaps, text.recapsHint)),
                    PlaybackSettingRow.Choice(row("skipCredits", text.credits, text.creditsHint)),
                ),
            ),
        ),
        languagePicker = null,
    )

    private fun row(id: String, title: String, subtitle: String) = SettingChoiceRow(
        id,
        title,
        subtitle,
        listOf(
            SettingChoiceOption("OFF", text.off, selected = false),
            SettingChoiceOption("BUTTON", text.button, selected = true),
            SettingChoiceOption("AUTO", text.auto, selected = false),
        ),
    )
}
