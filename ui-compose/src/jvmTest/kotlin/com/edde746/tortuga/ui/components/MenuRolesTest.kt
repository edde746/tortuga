package com.edde746.tortuga.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.menu.FilterChip
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test

/**
 * What a screen reader hears of the menus: a dropdown chip as a dropdown list whose options are radio buttons, the
 * one in effect selected; an item's menu on TV as buttons (the one in effect selected), not as choices.
 */
@OptIn(ExperimentalTestApi::class)
class MenuRolesTest {
    @Test
    fun aMenuChipIsADropdownListOfChoicesSayingWhichIsSelected() = runComposeUiTest {
        menu(FormFactor.DESKTOP) {
            MenuChip(FilterChip("Sort", "Popular", "Sort: Popular", narrowed = false), choices = listOf(MenuChoice("Popular", selected = true), MenuChoice("Newest", selected = false)), onPick = {}, probe = "sort")
        }
        onNode(hasClickAction() and hasText("Popular")).assert(hasRole(Role.DropdownList)).performClick()
        onNode(hasClickAction() and hasText("Popular") and hasAnyAncestor(isPopup())).assert(hasRole(Role.RadioButton)).assertIsSelected()
        onNode(hasClickAction() and hasText("Newest") and hasAnyAncestor(isPopup())).assert(hasRole(Role.RadioButton)).assertIsNotSelected()
    }

    @Test
    fun onTvAnItemsMenuListsItsActionsAsButtons() = runComposeUiTest {
        menu(FormFactor.TV) {
            ItemMenu(true, {}, listOf(ItemMenuEntry("Mark as watched") {}, ItemMenuEntry("Keep synced", selected = true) {}), probe = "menu")
        }
        onNode(hasClickAction() and hasText("Mark as watched")).assert(hasRole(Role.Button)).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        onNode(hasClickAction() and hasText("Keep synced")).assert(hasRole(Role.Button)).assertIsSelected()
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun ComposeUiTest.menu(formFactor: FormFactor, content: @Composable () -> Unit) = setContent {
        TortugaTheme {
            AdaptiveLayout(formFactor) {
                OverlayHostBox(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}
