package com.edde746.tortuga.ui.settings

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.AppearanceSettingsUiState
import com.edde746.tortuga.presentation.settings.PosterTemplateRow
import com.edde746.tortuga.presentation.settings.SettingChoiceOption
import com.edde746.tortuga.presentation.settings.SettingChoiceRow
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Settings → Appearance: the interface language, then the blur, hide-watched and hide-captions switches, then the poster
 * template's row, which opens its editor; a kids profile has all but the template. A switch gives its row's id and the
 * new value, the language's menu its row's id and the option's.
 */
@OptIn(ExperimentalTestApi::class)
class AppearanceSettingsPaneTest {
    @Test
    fun anAdultProfileHasTheRowsInOrderEndingWithThePosterTemplate() = runComposeUiTest {
        val toggled = mutableListOf<Pair<String, Boolean>>()
        val chosen = mutableListOf<Pair<String, String>>()
        var opened = 0
        setContent {
            TortugaTheme {
                AdaptiveLayout(FormFactor.DESKTOP) {
                    AppearanceSettingsPane(
                        AppearanceSettingsUiState(language, blur, hideWatched, hideCaptions, template),
                        { key, option -> chosen += key to option }, { key, checked -> toggled += key to checked }, { opened++ },
                    )
                }
            }
        }
        assertOrder(LANGUAGE, BLUR, HIDE_WATCHED, HIDE_CAPTIONS, TEMPLATE)
        onNode(isToggleable() and hasText(BLUR)).performClick()
        onNode(isToggleable() and hasText(HIDE_WATCHED)).performClick()
        onNode(isToggleable() and hasText(HIDE_CAPTIONS)).performClick()
        assertEquals(listOf("blurUnwatched" to true, "hideWatched" to true, "hidePosterCaptions" to true), toggled)
        onNode(hasClickAction() and hasText(LANGUAGE)).performClick()
        onNodeWithText("Deutsch").performClick()
        assertEquals(listOf("interfaceLanguage" to "de"), chosen)
        onNodeWithText("Addon posters", substring = true).assertExists()
        onNode(hasClickAction() and hasText(TEMPLATE)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aKidsProfileHasEveryRowButThePosterTemplate() = runComposeUiTest {
        setContent {
            TortugaTheme {
                AdaptiveLayout(FormFactor.DESKTOP) {
                    AppearanceSettingsPane(AppearanceSettingsUiState(language, blur, hideWatched, hideCaptions, posterTemplate = null), { _, _ -> }, { _, _ -> }, {})
                }
            }
        }
        assertOrder(LANGUAGE, BLUR, HIDE_WATCHED, HIDE_CAPTIONS)
        onNodeWithText(TEMPLATE, substring = true).assertDoesNotExist()
    }

    /** The rows titled [titles] show, each below the one before. */
    private fun ComposeUiTest.assertOrder(vararg titles: String) {
        val tops = titles.map { onNodeWithText(it).getBoundsInRoot().top }
        assertEquals(tops.sorted(), tops, "rows out of order: ${titles.zip(tops)}")
        assertEquals(titles.size, tops.distinct().size, "rows on one line: ${titles.zip(tops)}")
    }

    private companion object {
        const val LANGUAGE = "App language"
        const val BLUR = "Blur unwatched episodes"
        const val HIDE_WATCHED = "Hide watched titles"
        const val HIDE_CAPTIONS = "Hide titles under posters"
        const val TEMPLATE = "Poster template"

        val language = SettingChoiceRow(
            "interfaceLanguage", LANGUAGE, null,
            listOf(SettingChoiceOption("system", "System default", selected = true), SettingChoiceOption("de", "Deutsch", selected = false)),
        )
        val blur = SettingToggleRow("blurUnwatched", BLUR, "Hide the stills and descriptions of episodes you haven't seen", checked = false)
        val hideWatched = SettingToggleRow("hideWatched", HIDE_WATCHED, null, checked = false)
        val hideCaptions = SettingToggleRow("hidePosterCaptions", HIDE_CAPTIONS, null, checked = false)

        val template = PosterTemplateRow(
            title = TEMPLATE, subtitle = "{imdb_id}, {tmdb_id}, {tvdb_id}, {type}", value = "", valueLabel = "Addon posters", error = null,
            placeholder = "https://example.com/{imdb_id}.jpg",
        )
    }
}
