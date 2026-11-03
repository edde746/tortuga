package com.edde746.tortuga.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.LinkApprovalStep
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The link code's field as a code is typed into it key after key, with no pause between keys (the keyboard's pace, or
 * `adb shell input text`'s): every key stays. The field's own text is the keys as typed, never replaced while they
 * come (on Android a replaced text restarts the keyboard's input, and a key typed during the restart is lost); it is
 * drawn upper-case and grouped as the store groups it, and the store gets every key. A refused code empties it, as the
 * store's code is.
 */
@OptIn(ExperimentalTestApi::class)
class LinkCodeFieldTest {
    @Test
    fun aCodeTypedKeyAfterKeyKeepsEveryKey() = runComposeUiTest {
        val sent = mutableListOf<String>()
        field(onCode = { sent += it })
        val field = onNode(hasSetTextAction())
        field.requestFocus()

        // No frame between the keys: the field takes each one while the last is still undrawn.
        mainClock.autoAdvance = false
        for (key in "nf3m68EN") field.performTextInput(key.toString())
        mainClock.autoAdvance = true
        waitForIdle()

        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("nf3m68EN")))
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("NF3M-68EN")))
        assertEquals("nf3m68EN", sent.last())
    }

    /**
     * A code refused at once (one with a letter the codes never use, refused before the server is asked): the sheet is
     * back at the code with the error and the store's code cleared, without the checking step ever drawn. The field
     * empties, so Continue's state matches what it shows, and the error stays.
     */
    @Test
    fun aRefusedCodeEmptiesTheField() = runComposeUiTest {
        var link by mutableStateOf(enterCode)
        val sent = mutableListOf<String>()
        setContent {
            TortugaTheme {
                AdaptiveLayout(FormFactor.HANDHELD) {
                    LinkCodeField("ABCD-EFGH", link, intents { sent += it }, Modifier)
                }
            }
        }
        val field = onNode(hasSetTextAction())
        field.performTextInput("OOOO1111")
        link = enterCode.copy(code = "OOOO-1111", canSubmit = true)
        waitForIdle()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("OOOO1111")))

        link = enterCode.copy(code = "", canSubmit = false, error = "That isn't a device link's code.")
        waitForIdle()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("")))
        // The emptied field isn't sent back as if typed: that would clear the store's error.
        assertEquals("OOOO1111", sent.last())
    }

    private fun ComposeUiTest.field(onCode: (String) -> Unit) = setContent {
        TortugaTheme {
            AdaptiveLayout(FormFactor.HANDHELD) {
                LinkCodeField("ABCD-EFGH", enterCode, intents(onCode), Modifier)
            }
        }
    }

    private val enterCode = LinkApprovalStep.EnterCode(code = "", canSubmit = false, error = null)

    private fun intents(onCode: (String) -> Unit) = AccountSettingsIntents(
        refresh = {},
        syncNow = {},
        linkIdentity = {},
        unlinkIdentity = {},
        revokeDevice = {},
        signOut = {},
        deleteAccount = {},
        confirmDialog = {},
        dismissDialog = {},
        dismissNotice = {},
        startLinkApproval = {},
        updateLinkCode = onCode,
        submitLinkCode = {},
        approveLink = {},
        rejectLink = {},
        closeLinkApproval = {},
    )
}
