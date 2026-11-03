package com.edde746.tortuga.ui.profiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.profiles.ProfilePickerBody
import com.edde746.tortuga.presentation.profiles.ProfilePickerUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProfilePickerTextTest {
    /**
     * On a phone (412 dp) at the default font scale the "Add profile" tile's label (120 dp wide) shows whole in every
     * language, on a second line where it needs one.
     */
    @Test
    fun onAPhoneAddProfileShowsWholeInEveryLanguage() {
        val wrong = mutableListOf<String>()
        val intents = ProfilePickerIntents({}, {}, {}, {}, {}, {}, {}, {})
        try {
            for (language in languages) {
                T.locale = language
                val state = ProfilePickerUiState(T.profiles.title, ProfilePickerBody.Empty(T.profiles.empty), managing = false, canClose = false, pinPrompt = null, syncStatus = "")
                runComposeUiTest {
                    setContent {
                        TortugaTheme {
                            Box(Modifier.size(412.dp, 760.dp)) {
                                AdaptiveLayout(FormFactor.HANDHELD) { ProfilePickerScreen(state, intents) }
                            }
                        }
                    }
                    val layouts = mutableListOf<TextLayoutResult>()
                    onNode(hasText(T.profiles.add), useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    if (layouts.single().didOverflowHeight) wrong += "\"${T.profiles.add}\" is cut in $language"
                }
            }
        } finally {
            T.locale = "en"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** The interface's languages (shared/i18n/). */
    private val languages = listOf(
        "ar", "cs", "de", "en", "es", "fr", "hi", "id", "it", "ja", "ko", "nl", "pl", "pt-BR", "ro", "ru", "sv", "th", "tr", "uk", "vi",
    )
}
