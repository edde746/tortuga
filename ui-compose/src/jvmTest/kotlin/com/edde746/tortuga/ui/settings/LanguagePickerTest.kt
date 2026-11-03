package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.LanguageListKind
import com.edde746.tortuga.presentation.settings.LanguagePickerUiState
import com.edde746.tortuga.presentation.settings.SettingChosenLanguage
import com.edde746.tortuga.presentation.settings.SettingLanguageOption
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The chosen languages on phones, tablets and desktop, as the addons: each row's ⋮ opens its menu, Move up and Move
 * down where the language can go and Remove, which screen readers get as the row's actions too. Remove, from the menu or
 * with Delete on desktop, removes at once (a language is added back in a moment, so nothing asks first).
 */
@OptIn(ExperimentalTestApi::class)
class LanguagePickerTest {
    private val calls = mutableListOf<String>()
    private var order by mutableStateOf(listOf("eng" to "English", "deu" to "German", "fra" to "French"))
    private var open by mutableStateOf(true)

    private val up = T.settings.playback.moveUp
    private val down = T.settings.playback.moveDown
    private val remove = T.settings.playback.removeLanguage

    @Test
    fun everyChosenLanguagesMenuOffersWhatItsPlaceAllowsAndScreenReadersTheSame() {
        val menus = listOf("English" to listOf(down, remove), "German" to listOf(up, down, remove), "French" to listOf(up, remove))
        for (formFactor in listOf(FormFactor.HANDHELD, FormFactor.DESKTOP)) {
            for ((name, entries) in menus) runComposeUiTest {
                picker(formFactor)
                assertEquals(entries, actionsOf(name), "$name's actions on $formFactor")
                openMenu(name)
                assertEquals(entries, menu(), "$name's menu on $formFactor")
            }
        }
    }

    @Test
    fun removeFromTheMenuRemovesAtOnce() = runComposeUiTest {
        picker(FormFactor.HANDHELD)
        openMenu("German")
        onNode(hasClickAction() and hasText(remove)).performClick()
        runOnIdle { assertEquals(listOf("remove:deu"), calls) }
    }

    /** French moved up from its menu is still there when the list opens again, and its menu follows its new place. */
    @Test
    fun aMoveFromTheMenuKeepsTheOrderWhenTheListOpensAgain() = runComposeUiTest {
        picker(FormFactor.HANDHELD)
        openMenu("French")
        onNode(hasClickAction() and hasText(up)).performClick()
        runOnIdle { open = false }
        runOnIdle { open = true }
        assertEquals(listOf("move:fra:up"), calls)
        assertEquals(listOf("English", "French", "German"), listOf("English", "German", "French").sortedBy { row(it).fetchSemanticsNode().boundsInRoot.top })
        openMenu("French")
        assertEquals(listOf(up, down, remove), menu())
    }

    @Test
    fun onDesktopDeleteRemovesTheFocusedLanguage() = runComposeUiTest {
        picker(FormFactor.DESKTOP)
        row("French").requestFocus().performKeyInput { pressKey(Key.Delete) }
        assertEquals(listOf("remove:fra"), calls)
    }

    private fun ComposeUiTest.row(name: String): SemanticsNodeInteraction = onNodeWithText(name)

    private fun ComposeUiTest.actionsOf(name: String): List<String> =
        row(name).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    /** The ⋮ of the chosen language [name], found by what screen readers call it. */
    private fun ComposeUiTest.openMenu(name: String) {
        onNode(hasContentDescription(T.detail.moreActionsNamed(name))).performClick()
    }

    /** The open menu's entries, top to bottom. */
    private fun ComposeUiTest.menu(): List<String> =
        onAllNodes(hasClickAction() and (hasText(up) or hasText(down) or hasText(remove)))
            .fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .map { it.config[SemanticsProperties.Text].first().text }

    /** [code] one place up ([upward]) or down in [order], as the store moves it. */
    private fun move(code: String, upward: Boolean) {
        calls += "move:$code:${if (upward) "up" else "down"}"
        val from = order.indexOfFirst { it.first == code }
        order = order.toMutableList().apply { add(if (upward) from - 1 else from + 1, removeAt(from)) }
    }

    private fun ComposeUiTest.picker(formFactor: FormFactor) = setContent {
        TortugaTheme {
            Box(Modifier.size(411.dp, 800.dp)) {
                AdaptiveLayout(formFactor) {
                    if (open) {
                        LanguagePickerOverlay(
                            picker = state(),
                            onSearch = {},
                            onAdd = { calls += "add:$it" },
                            onRemove = { calls += "remove:$it" },
                            onMove = ::move,
                            onMoveTo = { code, index -> calls += "moveTo:$code:$index" },
                            onClose = {},
                        )
                    }
                }
            }
        }
    }

    private fun state() = LanguagePickerUiState(
        kind = LanguageListKind.AUDIO,
        title = "Audio languages",
        explanation = "The first one a video has plays.",
        chosenHeader = "Chosen",
        chosen = order.mapIndexed { index, (code, name) ->
            SettingChosenLanguage(
                code, name, "${index + 1}", canMoveUp = index > 0, canMoveDown = index < order.lastIndex,
                moveUpLabel = "Move $name up", moveDownLabel = "Move $name down", removeLabel = "Remove $name", dragLabel = "Reorder $name",
                moreLabel = T.detail.moreActionsNamed(name),
            )
        },
        noneChosenText = null,
        availableHeader = "Add a language",
        query = "",
        searchHint = "Search languages",
        available = listOf(SettingLanguageOption("spa", "Spanish")),
        emptyText = null,
    )
}
