package com.edde746.tortuga.ui.profiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.kids.AgeStep
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.profiles.AvatarChoice
import com.edde746.tortuga.presentation.profiles.KidsCapChoice
import com.edde746.tortuga.presentation.profiles.KidsCatalogChoice
import com.edde746.tortuga.presentation.profiles.KidsSectionUiState
import com.edde746.tortuga.presentation.profiles.KidsTitleRow
import com.edde746.tortuga.presentation.profiles.ProfileAvatars
import com.edde746.tortuga.presentation.profiles.ProfileEditorUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The profile editor's Kids profile section: its rules only while the profile is a kids profile, each control
 * reporting its own intent, and every control a stop of a TV remote's Down.
 */
@OptIn(ExperimentalTestApi::class)
class KidsSectionTest {
    private val text = T.profiles.editor.kids
    private val calls = mutableListOf<String>()

    /**
     * Off for this profile: the switch alone, none of the rules under it. On: the highest rating, unrated titles, Home
     * rows, the allowed and blocked titles and the lines on what a kids profile does.
     */
    @Test
    fun theSectionShowsItsRulesOnlyWhileItIsOn() {
        runComposeUiTest {
            phone(editor(kids = off))
            onNodeWithText(text.title).assertExists()
            for (rule in listOf(text.capTitle, text.allowUnrated, text.allowed, text.blocked, text.leaving)) onNodeWithText(rule).assertDoesNotExist()
        }
        runComposeUiTest {
            phone(editor(kids = on))
            for (rule in listOf(text.capTitle, text.capKidsAges, text.allowUnrated, text.homeRows, text.allowed, "Bluey", text.blocked, text.blockedNone, text.leaving, text.off)) {
                onNodeWithText(rule).assertExists()
            }
        }
    }

    /** Each control of an on section reports its intent: the switch, a rating, unrated titles, a Home row, a removal. */
    @Test
    fun eachControlReportsItsIntent() = runComposeUiTest {
        phone(editor(kids = on.copy(blocked = listOf(KidsTitleRow("tt0110357", "The Lion King", "Movie", text.remove("The Lion King"))))))
        onNodeWithText(text.title).performScrollTo().performClick()
        onNodeWithText(text.capTeen).performScrollTo().performClick()
        onNodeWithText(text.allowUnrated).performScrollTo().performClick()
        onNodeWithText(text.familySeries).performScrollTo().performClick()
        onNodeWithContentDescription(text.remove("Bluey")).performScrollTo().performClick()
        onNodeWithContentDescription(text.remove("The Lion King")).performScrollTo().performClick()
        assertEquals(
            listOf("kids:false", "cap:TEEN", "unrated:true", "home:1:true", "allowed:tt7678620", "blocked:tt0110357"),
            calls,
        )
    }

    /** A switch that can't be turned on (no other profile has a PIN) says why, and pressing it does nothing. */
    @Test
    fun aSwitchThatCantTurnOnSaysWhyAndIgnoresPresses() = runComposeUiTest {
        phone(editor(kids = off.copy(canSwitch = false, blockedReason = text.needsParent)))
        onNodeWithText(text.needsParent).performScrollTo().assertIsDisplayed()
        onNodeWithText(text.title).performScrollTo().performClick()
        assertEquals(emptyList(), calls)
    }

    /**
     * On TV Down from the switch stops at every control of the section in its order (the three ratings, unrated
     * titles, each Home row, each title's remove button), then Save.
     */
    @Test
    fun onTvDownStopsAtEveryControlOfTheSection() = runComposeUiTest {
        val probe = FocusedProbe()
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        AdaptiveLayout(FormFactor.TV, Modifier.arrowKeyFocus()) { ProfileEditorScreen(editor(kids = onWithBlocked), intents) }
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithText(text.title).requestFocus()
        waitForIdle()
        val visited = mutableListOf<String?>(probe.focused)
        repeat(9) {
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            visited += probe.focused
        }
        assertEquals<List<String?>>(
            listOf(
                "editor.kids", "editor.kids.cap.0", "editor.kids.cap.1", "editor.kids.cap.2", "editor.kids.unrated",
                "editor.kids.home.0", "editor.kids.home.1", "editor.kids.allowed.0", "editor.kids.blocked.0", "editor.save",
            ),
            visited,
        )
    }

    private fun ComposeUiTest.phone(state: ProfileEditorUiState) {
        setContent {
            TortugaTheme {
                Box(Modifier.size(411.dp, 760.dp)) {
                    AdaptiveLayout(FormFactor.HANDHELD) { ProfileEditorScreen(state, intents) }
                }
            }
        }
        waitForIdle()
    }

    private val off = KidsSectionUiState(
        enabled = false, canSwitch = true, blockedReason = null, caps = emptyList(), allowUnrated = false,
        homeCatalogs = emptyList(), allowed = emptyList(), blocked = emptyList(),
    )

    private val on = KidsSectionUiState(
        enabled = true,
        canSwitch = true,
        blockedReason = null,
        caps = listOf(
            KidsCapChoice(AgeStep.LITTLE, text.capLittle, text.capLittleAges, selected = false),
            KidsCapChoice(AgeStep.KIDS, text.capKids, text.capKidsAges, selected = true),
            KidsCapChoice(AgeStep.TEEN, text.capTeen, text.capTeenAges, selected = false),
        ),
        allowUnrated = false,
        homeCatalogs = listOf(KidsCatalogChoice(text.familyMovies, "Cinemeta", true), KidsCatalogChoice(text.familySeries, "Cinemeta", false)),
        allowed = listOf(KidsTitleRow("tt7678620", "Bluey", "Series", text.remove("Bluey"))),
        blocked = emptyList(),
    )

    private val onWithBlocked = on.copy(blocked = listOf(KidsTitleRow("tt0110357", "The Lion King", "Movie", text.remove("The Lion King"))))

    /** Editing a profile without a PIN, its Kids profile section [kids]. */
    private fun editor(kids: KidsSectionUiState) = ProfileEditorUiState(
        creating = false,
        title = T.profiles.editor.editTitle,
        loaded = true,
        name = "Mia",
        nameError = null,
        initial = "M",
        avatar = ProfileAvatars.all.first(),
        avatars = ProfileAvatars.all.mapIndexed { index, avatar -> AvatarChoice(avatar, index == 0) },
        hasPin = false,
        pinStatus = T.profiles.editor.pinOff,
        pinSetup = null,
        kids = kids,
        canDelete = true,
        deleteConfirmation = null,
        canSave = true,
        saving = false,
        error = null,
    )

    private val intents = ProfileEditorIntents(
        setName = {}, selectAvatar = {}, startPinSetup = {}, pinDigit = {}, pinBackspace = {}, cancelPinSetup = {},
        removePin = {}, save = {}, cancel = {}, requestDelete = {}, cancelDelete = {}, confirmDelete = {},
        setKids = { calls += "kids:$it" },
        setKidsCap = { calls += "cap:$it" },
        setAllowUnrated = { calls += "unrated:$it" },
        setHomeCatalog = { index, selected -> calls += "home:$index:$selected" },
        removeAllowed = { calls += "allowed:$it" },
        removeBlocked = { calls += "blocked:$it" },
    )
}
