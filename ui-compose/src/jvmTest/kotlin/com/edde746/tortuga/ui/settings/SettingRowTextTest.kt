package com.edde746.tortuga.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.SettingChoiceOption
import com.edde746.tortuga.presentation.settings.SettingChoiceRow
import com.edde746.tortuga.presentation.settings.SettingStepperRow
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.strings.InterfaceLocale
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Settings' rows with a control at their end (a stepper, a switch, a menu's value), worded in every language. */
@OptIn(ExperimentalTestApi::class)
class SettingRowTextTest {
    /**
     * A 360 dp phone at font scales 1 and 1.3: every row's title, hint and value show whole (no line cut off, nothing
     * past its box) inside the list's padding, and each stepper and switch lies clear of its row's text, on screen, and
     * takes a tap.
     */
    @Test
    fun onANarrowPhoneEveryRowShowsItsTextWholeAndItsControlInReach() {
        val wrong = mutableListOf<String>()
        try {
            for (language in languages) {
                T.locale = language
                val rows = rows()
                for (fontScale in listOf(1f, 1.3f)) runComposeUiTest {
                    val at = "in $language at font scale $fontScale"
                    val stepped = mutableListOf<String>()
                    val toggled = mutableListOf<String>()
                    page(rows, PHONE, FormFactor.HANDHELD, fontScale, onStep = { stepped += it }, onToggle = { toggled += it })
                    val list = onNode(hasScrollToIndexAction())
                    val inside = 16.dp..PHONE.width - 16.dp
                    for ((index, row) in rows.steppers.withIndex()) {
                        list.performScrollToKey("stepper.$index")
                        wrong += cutTexts(at)
                        val text = textBounds(row.title, row.subtitle)
                        val control = stepperBounds(row.title)
                        if (text.left !in inside || text.right !in inside) wrong += "\"${row.title}\" spans ${text.left}–${text.right}, outside the list's padding $at"
                        if (control.left !in inside || control.right !in inside) wrong += "the stepper of \"${row.title}\" spans ${control.left}–${control.right}, outside the list's padding $at"
                        if (overlaps(text, control)) wrong += "the stepper of \"${row.title}\" ($control) lies over its text ($text) $at"
                        onNode(hasContentDescription(T.settings.increaseNamed(row.title)) and hasClickAction()).performClick()
                    }
                    for ((index, row) in rows.toggles.withIndex()) {
                        list.performScrollToKey("toggle.$index")
                        wrong += cutTexts(at)
                        val text = textBounds(row.title, row.subtitle)
                        if (text.left !in inside || text.right !in inside) wrong += "\"${row.title}\" spans ${text.left}–${text.right}, outside the list's padding $at"
                        onNode(isToggleable() and hasText(row.title)).performClick()
                    }
                    for (index in rows.choices.indices) {
                        list.performScrollToKey("choice.$index")
                        wrong += cutTexts(at)
                    }
                    runOnIdle {
                        if (stepped != rows.steppers.map { it.title }) wrong += "the steppers' + took ${stepped.size} of ${rows.steppers.size} taps $at"
                        if (toggled != rows.toggles.map { it.title }) wrong += "the switch rows took ${toggled.size} of ${rows.toggles.size} taps $at"
                    }
                }
            }
        } finally {
            T.locale = "en"
        }
        assertTrue(wrong.isEmpty(), wrong.distinct().joinToString("\n"))
        assertEquals(21, languages.size, "a language is missing from the list")
    }

    /**
     * Where the text has room beside the control (a TV in every language, a Pixel 7 in English), each stepper stays
     * beside its title and hint, level with them.
     */
    @Test
    fun withRoomEveryStepperStaysBesideItsText() {
        val wrong = mutableListOf<String>()
        val cases = languages.map { Triple(it, TV, FormFactor.TV) } + Triple("en", PIXEL_7, FormFactor.HANDHELD)
        try {
            for ((language, size, formFactor) in cases) {
                T.locale = language
                val rows = rows()
                runComposeUiTest {
                    page(rows, size, formFactor, 1f)
                    val list = onNode(hasScrollToIndexAction())
                    for ((index, row) in rows.steppers.withIndex()) {
                        list.performScrollToKey("stepper.$index")
                        val text = textBounds(row.title, row.subtitle)
                        val control = stepperBounds(row.title)
                        val level = abs(((text.top + text.bottom) / 2 - (control.top + control.bottom) / 2).value) < 1f
                        val beside = control.left >= text.right || control.right <= text.left
                        if (!level || !beside) wrong += "the stepper of \"${row.title}\" ($control) isn't beside its text ($text) in $language at ${size.width}"
                    }
                }
            }
        } finally {
            T.locale = "en"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** The rows on a settings list in a window of [size], laid out right to left in a language that reads so. */
    private fun ComposeUiTest.page(
        rows: Rows,
        size: DpSize,
        formFactor: FormFactor,
        fontScale: Float,
        onStep: (String) -> Unit = {},
        onToggle: (String) -> Unit = {},
    ) = setContent {
        val direction = if (InterfaceLocale.isRightToLeft(T.locale)) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale), LocalLayoutDirection provides direction) {
            TortugaTheme {
                Box(Modifier.size(size)) {
                    AdaptiveLayout(formFactor) {
                        SettingsList {
                            settingsGroup(
                                "steppers",
                                null,
                                rows.steppers.mapIndexed { index, row ->
                                    SettingsItem("stepper.$index") { shape -> SettingStepperItem(row, { up -> if (up) onStep(row.title) }, "stepper.$index", shape) }
                                },
                            )
                            settingsGroup(
                                "toggles",
                                null,
                                rows.toggles.mapIndexed { index, row -> SettingsItem("toggle.$index") { shape -> SettingToggleItem(row, { onToggle(row.title) }, shape) } },
                            )
                            settingsGroup(
                                "choices",
                                null,
                                rows.choices.mapIndexed { index, row -> SettingsItem("choice.$index") { shape -> SettingChoiceItem(row, {}, "choice.$index", shape) } },
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Each text shown that is cut: lines past its maximum, taller than its box, or a line wider than its box. Not
     * [TextLayoutResult.hasVisualOverflow] whole: the result the semantics action gives lays its paragraph out at the
     * text's widest allowed width, not at the text's own, so its `didOverflowWidth` holds for every text narrower than
     * its slot ("3" in a 360 dp column). Its height half (`didOverflowHeight`: lines past the maximum, or taller than
     * the box) is sound, the line breaks being those of the same widest width the text was laid out at.
     */
    private fun ComposeUiTest.cutTexts(at: String): List<String> {
        val nodes = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        return List(nodes.fetchSemanticsNodes().size) { nodes[it] }.mapNotNull { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val widest = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
            when {
                layout.didOverflowHeight -> "\"${layout.layoutInput.text}\" is cut after ${layout.lineCount} lines ($at)"
                widest > layout.size.width + 1 -> "\"${layout.layoutInput.text}\" is ${widest - layout.size.width} px wider than its box ($at)"
                else -> null
            }
        }
    }

    /** The bounds of a row's [title] and [hint] together. */
    private fun ComposeUiTest.textBounds(title: String, hint: String?): DpRect {
        val bounds = listOfNotNull(title, hint).map { onNode(hasText(it), useUnmergedTree = true).getUnclippedBoundsInRoot() }
        return DpRect(bounds.minOf { it.left }, bounds.minOf { it.top }, bounds.maxOf { it.right }, bounds.maxOf { it.bottom })
    }

    /** The bounds of the stepper of the row titled [title], from its − to its +. */
    private fun ComposeUiTest.stepperBounds(title: String): DpRect {
        val ends = listOf(T.settings.decreaseNamed(title), T.settings.increaseNamed(title))
            .map { onNode(hasContentDescription(it) and hasClickAction()).getUnclippedBoundsInRoot() }
        return DpRect(ends.minOf { it.left }, ends.minOf { it.top }, ends.maxOf { it.right }, ends.maxOf { it.bottom })
    }

    private fun overlaps(a: DpRect, b: DpRect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private class Rows(val steppers: List<SettingStepperRow>, val toggles: List<SettingToggleRow>, val choices: List<SettingChoiceRow>)

    /**
     * Settings' steppers (each at its widest value) and switches, and menus of the Playback and Appearance pages, worded in the current
     * language.
     */
    private fun rows(): Rows {
        val playback = T.settings.playback
        val appearance = T.settings.appearance
        val subtitles = T.settings.subtitles
        val downloads = T.settings.downloads
        val trackers = T.settings.trackers
        val skipping = playback.skipping
        val value = T.settings.value
        return Rows(
            steppers = listOf(
                stepper(playback.watchedThreshold, playback.watchedThresholdHint, value.percent("100")),
                stepper(playback.upNextCountdown, playback.upNextCountdownHint, playback.upNextImmediately),
                stepper(playback.seekShort, playback.seekShortHint, value.seconds(60)),
                stepper(playback.seekLong, playback.seekLongHint, value.seconds(300)),
                stepper(subtitles.size, null, value.percent("200")),
                stepper(subtitles.background, subtitles.backgroundHint, subtitles.backgroundOff),
                stepper(subtitles.position, subtitles.positionHint, subtitles.positionBottom),
                stepper(downloads.atOnce, downloads.atOnceHint, "3"),
            ),
            toggles = listOf(
                SettingToggleRow("bingeWatching", playback.bingeWatching, playback.bingeWatchingHint, checked = true),
                SettingToggleRow("bold", subtitles.bold, null, checked = false),
                SettingToggleRow("styledOverride", subtitles.styledOverride, subtitles.styledOverrideHint, checked = true),
                SettingToggleRow("wifiOnly", downloads.wifiOnly, downloads.wifiOnlyHint, checked = true),
                SettingToggleRow("autoRemoveWatched", downloads.autoRemoveWatched, downloads.autoRemoveWatchedHint, checked = false),
                SettingToggleRow("blurUnwatched", appearance.blurUnwatched, appearance.blurUnwatchedHint, checked = false),
                SettingToggleRow("hideWatched", appearance.hideWatched, appearance.hideWatchedHint, checked = false),
                SettingToggleRow("hidePosterCaptions", appearance.hidePosterCaptions, appearance.hidePosterCaptionsHint, checked = false),
                SettingToggleRow("continueWatching", T.browse.continueWatching.title, T.settings.home.continueWatchingHint, checked = true),
                SettingToggleRow("TRAKT.scrobble", trackers.scrobble, trackers.scrobbleHint("Trakt"), checked = true),
                SettingToggleRow("TRAKT.movies", trackers.movies, null, checked = true),
                SettingToggleRow("TRAKT.series", trackers.series, null, checked = true),
                SettingToggleRow("TRAKT.anime", trackers.anime, null, checked = false),
            ),
            choices = listOf(
                choice("interfaceLanguage", appearance.interfaceLanguage, appearance.interfaceLanguageHint, appearance.interfaceLanguageSystem),
                choice("videoClick", playback.videoClick, playback.videoClickHint, playback.videoClickControls),
                choice("skipIntros", skipping.intros, skipping.introsHint, skipping.button),
                choice("skipRecaps", skipping.recaps, skipping.recapsHint, skipping.button),
                choice("skipCredits", skipping.credits, skipping.creditsHint, skipping.button),
            ),
        )
    }

    private fun stepper(title: String, hint: String?, value: String) = SettingStepperRow(
        title, hint, 0, value, min = -1, max = 1, step = 1, canDecrease = true, canIncrease = true,
        decreaseLabel = T.settings.decreaseNamed(title), increaseLabel = T.settings.increaseNamed(title),
    )

    private fun choice(id: String, title: String, hint: String?, value: String) =
        SettingChoiceRow(id, title, hint, listOf(SettingChoiceOption("value", value, selected = true)))

    /** The interface's languages (shared/i18n/). */
    private val languages = listOf(
        "ar", "cs", "de", "en", "es", "fr", "hi", "id", "it", "ja", "ko", "nl", "pl", "pt-BR", "ro", "ru", "sv", "th", "tr", "uk", "vi",
    )

    private companion object {
        /** The narrowest common phone (a Galaxy A's 360 dp), at a height the test's window holds. */
        val PHONE = DpSize(360.dp, 760.dp)

        /** A Pixel 7's 412 dp. */
        val PIXEL_7 = DpSize(412.dp, 760.dp)

        /** A TV's 960 × 540 dp. */
        val TV = DpSize(960.dp, 540.dp)
    }
}
