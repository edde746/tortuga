package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.profiles.ProfileAvatars
import com.edde746.tortuga.presentation.settings.AboutUiState
import com.edde746.tortuga.presentation.settings.SettingsProfileCard
import com.edde746.tortuga.presentation.settings.SettingsSection
import com.edde746.tortuga.presentation.settings.SettingsSectionRow
import com.edde746.tortuga.presentation.settings.SettingsUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings' first page: the profile's card over the sections. */
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    /** As the store first publishes it: the sections, the profile not read yet. */
    private val reading = SettingsUiState(
        title = "Settings",
        profile = null,
        sections = SettingsSection.entries.map { SettingsSectionRow(it, it.name, "What the section holds") },
        section = SettingsSection.PLAYBACK,
        about = AboutUiState("Tortuga", "0.1.0", "", "", "", "", "", emptyList(), notice = null),
    )

    /** The profile as the store reads it, a locked one (the longer detail). */
    private val developer = T.settings.profile.let { SettingsProfileCard("Developer", "D", ProfileAvatars.all.first(), it.locked, it.edit, it.manage) }

    /**
     * The store has the sections before the profile is read; the card that arrives then is in view at the list's top,
     * on a window where the list scrolls (the desktop app's smallest wide window).
     */
    @Test
    fun aProfileReadAfterTheSectionsShowsAtTheTop() = runComposeUiTest {
        var state by mutableStateOf(reading)
        settings(920.dp, probe = null) { state }
        waitForIdle()
        state = state.copy(profile = developer)
        onNodeWithText("Developer").assertIsDisplayed()
        onNodeWithContentDescription(T.settings.profile.edit).assertIsDisplayed()
    }

    /**
     * The card has its final size from the first frame: the sections stay where they are when the profile arrives, so
     * a click aimed at one as Settings opens lands on it. The list alone in the desktop app's smallest window, and
     * beside a section in the smallest window that has room for both.
     */
    @Test
    fun theSectionsStayPutWhenTheProfileArrives() {
        for ((width, section) in listOf(640.dp to null, 920.dp to SettingsSection.PLAYBACK)) runComposeUiTest {
            val placed = HashMap<String, Rect>()
            var state by mutableStateOf(reading.copy(section = section))
            settings(width, probe = { name, bounds -> if (bounds != null) placed[name] = bounds else placed.remove(name) }) { state }
            waitForIdle()
            val sections = placed.filterKeys { it.startsWith("settings.section.") }
            assertTrue(sections.size >= 2, "sections on screen at $width: ${sections.keys}")
            assertTrue("settings.profile.edit" !in placed, "the placeholder's button reported at $width")

            state = state.copy(profile = developer)
            onNodeWithText("Developer").assertIsDisplayed()
            assertTrue("settings.profile.edit" in placed, "the card's button reported at $width")
            for ((name, bounds) in sections) assertEquals(bounds, placed[name], "$name once the profile arrived, at $width")
        }
    }

    /**
     * Back from a section pushed over the list (a window too narrow for both: phones) finds the list where it was left,
     * its last section where it was, not the list back at its top.
     */
    @Test
    fun backFromASectionFindsTheListWhereItWasLeft() = runComposeUiTest {
        val placed = HashMap<String, Rect>()
        var state by mutableStateOf(reading.copy(profile = developer, section = null))
        settings(400.dp, probe = { name, bounds -> if (bounds != null) placed[name] = bounds else placed.remove(name) }) { state }
        val last = SettingsSection.entries.last()
        val row = "settings.section.${last.name.lowercase()}"
        // The profile's card, then the sections.
        onNode(hasScrollToIndexAction()).performScrollToIndex(state.sections.size)
        waitForIdle()
        val left = placed[row]
        assertTrue(left != null, "$row on screen once the list is scrolled to its end")

        state = state.copy(section = last)
        waitForIdle()
        assertTrue(row !in placed, "$row still on screen with its section open")
        state = state.copy(section = null)
        waitForIdle()
        assertEquals(left, placed[row], "$row back from its section")
    }

    /** Settings on a [width] × 480 dp desktop window showing [state], its harness names' bounds going to [probe]. */
    private fun ComposeUiTest.settings(width: Dp, probe: LayoutProbe?, state: () -> SettingsUiState) = setContent {
        CompositionLocalProvider(LocalLayoutProbe provides probe) {
            TortugaTheme {
                Box(Modifier.size(width, 480.dp)) {
                    AdaptiveLayout(FormFactor.DESKTOP) {
                        SettingsScreen(
                            state(), canManageProfiles = true, onOpen = {}, onClose = {}, onCloseNotice = {}, onEditProfile = {}, onManageProfiles = {},
                            sectionActions = {}, sectionContent = {},
                        )
                    }
                }
            }
        }
    }
}
