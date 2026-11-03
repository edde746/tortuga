package com.edde746.tortuga.ui.profiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.profiles.AvatarChoice
import com.edde746.tortuga.presentation.profiles.KidsSectionUiState
import com.edde746.tortuga.presentation.profiles.ProfileAvatars
import com.edde746.tortuga.presentation.profiles.ProfileBadge
import com.edde746.tortuga.presentation.profiles.ProfileEditorUiState
import com.edde746.tortuga.presentation.profiles.ProfilePickerBody
import com.edde746.tortuga.presentation.profiles.ProfilePickerUiState
import com.edde746.tortuga.presentation.profiles.ProfileTile
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * On TV a D-pad move into the profile picker's tiles or the buttons under them, or into the profile editor's avatars or
 * PIN buttons, lands on the element that had focus there last, else on the first tile, Manage profiles (Done while
 * managing), the chosen avatar or Set (Change) PIN: never on the one nearest the move, which the tile count, the chosen
 * avatar's place and the labels' widths decide.
 */
@OptIn(ExperimentalTestApi::class)
class ProfilesFocusTest {
    /**
     * On TV Up from the buttons under the tiles (Manage profiles, or Done while managing, and Back) lands on the first
     * tile, with one, three or five profiles (two, four or six tiles with "Add profile", on one line or two): not on the
     * tile nearest the button, which the tile count decides.
     */
    @Test
    fun onTvUpFromTheButtonsLandsOnTheFirstTileWhateverTheTileCount() {
        val wrong = mutableListOf<String>()
        for ((count, managing) in pickerCases) {
            val state = picker(count, managing)
            for (button in listOf(if (managing) T.profiles.done else T.profiles.manage, T.nav.back)) runComposeUiTest {
                val probe = FocusedProbe()
                tv(probe) { ProfilePickerScreen(state, pickerIntents) }
                onNodeWithText(button).requestFocus()
                press(Key.DirectionUp)
                if (probe.focused != "profiles.tile.0") wrong += "${pickerCase(count, managing)}: Up from $button went to ${probe.focused}"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * On TV Down from the last tile ("Add profile") lands on Manage profiles (Done while managing), the first button, with
     * one, three or five profiles: not on Back beside it, nearer the tile wherever the tile count puts it.
     */
    @Test
    fun onTvDownFromTheLastTileLandsOnManageWhateverTheTileCount() {
        val wrong = mutableListOf<String>()
        for ((count, managing) in pickerCases) runComposeUiTest {
            val probe = FocusedProbe()
            val state = picker(count, managing)
            tv(probe) { ProfilePickerScreen(state, pickerIntents) }
            onNodeWithText(T.profiles.add).requestFocus()
            press(Key.DirectionDown)
            val expected = if (managing) "profiles.done" else "profiles.manage"
            if (probe.focused != expected) wrong += "${pickerCase(count, managing)}: Down from ${T.profiles.add} went to ${probe.focused}, not $expected"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * On TV Down from the profile's name lands on the chosen avatar, wherever it is among the avatars (ten to a line):
     * the first line's first or last, or the second line's last. Not on the avatar under the name field's middle.
     */
    @Test
    fun onTvDownFromTheNameLandsOnTheChosenAvatarWhereverItIs() {
        val wrong = mutableListOf<String>()
        for (chosen in listOf(0, 9, 11)) runComposeUiTest {
            val probe = FocusedProbe()
            val state = editor(chosen, hasPin = false)
            tv(probe) { ProfileEditorScreen(state, editorIntents) }
            if (probe.focused != "editor.name") wrong += "avatar $chosen chosen: the editor opens with focus on ${probe.focused}, not the name"
            press(Key.DirectionDown)
            if (probe.focused != "editor.avatar.$chosen") wrong += "avatar $chosen chosen: Down from the name went to ${probe.focused}"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * On TV Up from the PIN's buttons (Set a PIN; Change PIN and Remove PIN) lands on the chosen avatar, the first line's
     * first or last, or on the avatar that had focus last (the second line's first, left by Down): not on the second
     * line's avatar nearest the button.
     */
    @Test
    fun onTvUpFromThePinButtonsLandsOnTheChosenAvatarOrTheOneLeftLast() {
        val wrong = mutableListOf<String>()
        val buttons = listOf(false to T.profiles.editor.setPin, true to T.profiles.editor.changePin, true to T.profiles.editor.removePin)
        for (chosen in listOf(0, 9)) for ((hasPin, button) in buttons) runComposeUiTest {
            val probe = FocusedProbe()
            val state = editor(chosen, hasPin)
            tv(probe) { ProfileEditorScreen(state, editorIntents) }
            onNodeWithText(button).requestFocus()
            press(Key.DirectionUp)
            if (probe.focused != "editor.avatar.$chosen") wrong += "avatar $chosen chosen: Up from $button went to ${probe.focused}"
        }
        runComposeUiTest {
            val probe = FocusedProbe()
            val state = editor(chosen = 0, hasPin = false)
            tv(probe) { ProfileEditorScreen(state, editorIntents) }
            onNodeWithContentDescription(ProfileAvatars.all[10].name).requestFocus()
            press(Key.DirectionDown)
            if (probe.focused != "editor.pin.set") wrong += "avatar 0 chosen: Down from avatar 10 went to ${probe.focused}, not ${T.profiles.editor.setPin}"
            press(Key.DirectionUp)
            if (probe.focused != "editor.avatar.10") wrong += "avatar 0 chosen: Up from ${T.profiles.editor.setPin}, after Down from avatar 10, went to ${probe.focused}"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * On TV Up from the Kids profile switch, the control under the PIN's buttons, lands on Change PIN, the first of
     * them, whatever the labels' widths (English, French, Indonesian): not on Remove PIN beside it, nearer the
     * switch's middle.
     */
    @Test
    fun onTvUpFromTheKidsSwitchLandsOnChangePinWhateverTheLabelsWidths() {
        val wrong = mutableListOf<String>()
        try {
            for (language in listOf("en", "fr", "id")) {
                T.locale = language
                val state = editor(chosen = 0, hasPin = true)
                runComposeUiTest {
                    val probe = FocusedProbe()
                    tv(probe) { ProfileEditorScreen(state, editorIntents) }
                    onNodeWithText(T.profiles.editor.kids.title).requestFocus()
                    press(Key.DirectionUp)
                    if (probe.focused != "editor.pin.set") wrong += "$language: Up from ${T.profiles.editor.kids.title} went to ${probe.focused}, not ${T.profiles.editor.changePin}"
                }
            }
        } finally {
            T.locale = "en"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** [content] on a 1080p TV (960 × 540 dp), the arrow keys as the D-pad, focus reported to [probe]. */
    private fun ComposeUiTest.tv(probe: FocusedProbe, content: @Composable () -> Unit) {
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { content() }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    /** Profile counts, and whether the picker is managing: four tiles a line on TV. */
    private val pickerCases = listOf(1 to false, 3 to false, 5 to false, 3 to true)

    private fun pickerCase(count: Int, managing: Boolean) = if (managing) "$count profiles, managing" else "$count profiles"

    /** The picker opened from the app (Back shows) with [count] profiles. */
    private fun picker(count: Int, managing: Boolean) = ProfilePickerUiState(
        title = if (managing) T.profiles.manageTitle else T.profiles.title,
        body = ProfilePickerBody.Tiles(
            listOf("Alice", "Bruno", "Chloé", "Dmitri", "Elif").take(count).mapIndexed { index, name ->
                ProfileTile(
                    "p$index", name, name.take(1), ProfileAvatars.all[index], locked = false, current = index == 0,
                    badge = if (managing) ProfileBadge.EDIT else if (index == 0) ProfileBadge.CURRENT else null, stateText = null,
                )
            },
        ),
        managing = managing,
        canClose = true,
        pinPrompt = null,
        syncStatus = "Synced just now",
    )

    private val pickerIntents = ProfilePickerIntents({}, {}, {}, {}, {}, {}, {}, {})

    /** Editing a profile whose avatar is the [chosen]th of the set. */
    private fun editor(chosen: Int, hasPin: Boolean) = ProfileEditorUiState(
        creating = false,
        title = T.profiles.editor.editTitle,
        loaded = true,
        name = "Alice",
        nameError = null,
        initial = "A",
        avatar = ProfileAvatars.all[chosen],
        avatars = ProfileAvatars.all.mapIndexed { index, avatar -> AvatarChoice(avatar, index == chosen) },
        hasPin = hasPin,
        pinStatus = if (hasPin) T.profiles.editor.pinOn else T.profiles.editor.pinOff,
        pinSetup = null,
        kids = KidsSectionUiState(
            enabled = false, canSwitch = true, blockedReason = null, caps = emptyList(), allowUnrated = false,
            homeCatalogs = emptyList(), allowed = emptyList(), blocked = emptyList(),
        ),
        canDelete = true,
        deleteConfirmation = null,
        canSave = true,
        saving = false,
        error = null,
    )

    private val editorIntents = ProfileEditorIntents(
        setName = {}, selectAvatar = {}, startPinSetup = {}, pinDigit = {}, pinBackspace = {}, cancelPinSetup = {},
        removePin = {}, save = {}, cancel = {}, requestDelete = {}, cancelDelete = {}, confirmDelete = {}, setKids = {}, setKidsCap = {},
        setAllowUnrated = {}, setHomeCatalog = { _, _ -> }, removeAllowed = {}, removeBlocked = {},
    )
}
