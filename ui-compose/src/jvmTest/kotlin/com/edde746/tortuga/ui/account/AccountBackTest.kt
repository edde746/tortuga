package com.edde746.tortuga.ui.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.account.IdentityLinkStep
import com.edde746.tortuga.presentation.account.IdentityLinkUiState
import com.edde746.tortuga.presentation.account.SignInServerUiState
import com.edde746.tortuga.presentation.account.SignInStep
import com.edde746.tortuga.presentation.account.SignInUiState
import com.edde746.tortuga.presentation.profiles.AvatarChoice
import com.edde746.tortuga.presentation.profiles.KidsSectionUiState
import com.edde746.tortuga.presentation.profiles.ProfileAvatars
import com.edde746.tortuga.presentation.profiles.ProfileEditorUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.profiles.ProfileEditorIntents
import com.edde746.tortuga.ui.profiles.ProfileEditorScreen
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An account page has one way back or out: the header's back button (and the platform's Back), never a Cancel beside
 * it. Where that button is the page's only control (a sign-in in progress), it has focus as the page opens, so a
 * remote's OK leaves the page.
 */
@OptIn(ExperimentalTestApi::class)
class AccountBackTest {
    private val calls = mutableListOf<String>()

    @Test
    fun theProfileEditorLeavesWithoutSavingByItsBackButtonAlone() = runComposeUiTest {
        page(FormFactor.HANDHELD) { ProfileEditorScreen(editor, editorIntents) }
        assertOnlyWayOutIsBack()
        backButton().performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun aSignInInProgressIsCancelledByTheFocusedBackButtonAlone() = runComposeUiTest {
        page(FormFactor.TV) { SignInScreen(signInWorking, notice = null, intents = signInIntents) }
        assertOnlyWayOutIsBack()
        backButton().assertIsFocused().performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun linkingASignInMethodInProgressIsCancelledByTheFocusedBackButtonAlone() = runComposeUiTest {
        val working = IdentityLinkUiState(IdentityLinkStep.Working("Waiting for the browser…"))
        page(FormFactor.TV) {
            IdentityLinkScreen(working, onLink = {}, onCancel = { calls += "cancel" }, onRetry = {}, onClose = { calls += "close" })
        }
        assertOnlyWayOutIsBack()
        backButton().assertIsFocused().performClick()
        assertEquals(listOf("cancel"), calls)
    }

    private fun ComposeUiTest.backButton() = onNodeWithContentDescription(T.nav.back)

    /** One back button, and no Cancel control at all. */
    private fun ComposeUiTest.assertOnlyWayOutIsBack() {
        waitForIdle()
        backButton().assertExists()
        onAllNodesWithText(T.profiles.editor.cancel).assertCountEquals(0)
        onAllNodesWithText(T.account.signIn.cancel).assertCountEquals(0)
        onAllNodes(hasClickAction() and (hasContentDescription(T.nav.back) or hasText(T.nav.back))).assertCountEquals(1)
    }

    private fun ComposeUiTest.page(formFactor: FormFactor, content: @Composable () -> Unit) = setContent {
        TortugaTheme {
            Box(Modifier.size(if (formFactor == FormFactor.TV) 960.dp else 411.dp, if (formFactor == FormFactor.TV) 540.dp else 900.dp)) {
                AdaptiveLayout(formFactor) { content() }
            }
        }
    }

    private val editor = ProfileEditorUiState(
        creating = true,
        title = "Create profile",
        loaded = true,
        name = "Kid",
        nameError = null,
        initial = "K",
        avatar = ProfileAvatars.all.first(),
        avatars = ProfileAvatars.all.map { AvatarChoice(it, it == ProfileAvatars.all.first()) },
        hasPin = false,
        pinStatus = "No PIN",
        pinSetup = null,
        kids = KidsSectionUiState(
            enabled = false, canSwitch = true, blockedReason = null, caps = emptyList(), allowUnrated = false,
            homeCatalogs = emptyList(), allowed = emptyList(), blocked = emptyList(),
        ),
        canDelete = false,
        deleteConfirmation = null,
        canSave = true,
        saving = false,
        error = null,
    )

    private val editorIntents = ProfileEditorIntents(
        setName = {}, selectAvatar = {}, startPinSetup = {}, pinDigit = {}, pinBackspace = {}, cancelPinSetup = {},
        removePin = {}, save = { calls += "save" }, cancel = { calls += "cancel" }, requestDelete = {}, cancelDelete = {},
        confirmDelete = {}, setKids = {}, setKidsCap = {}, setAllowUnrated = {}, setHomeCatalog = { _, _ -> }, removeAllowed = {},
        removeBlocked = {},
    )

    private val signInWorking = SignInUiState(
        step = SignInStep.Working("Waiting for the browser…"),
        server = SignInServerUiState("Server", "sync.example.com", custom = true, editing = false, field = "", checking = false, error = null),
        options = emptyList(),
        optionsLoading = false,
        optionsRoom = 0,
        optionsNote = null,
        canRetry = false,
    )

    private val signInIntents = SignInIntents(
        signIn = {}, cancel = { calls += "cancel" }, retry = {}, editServer = {}, setServerField = {}, submitServer = {},
        cancelServerEdit = {}, useHostedServer = {}, linkDevice = {}, restartLink = {}, dismissNotice = {},
    )
}
