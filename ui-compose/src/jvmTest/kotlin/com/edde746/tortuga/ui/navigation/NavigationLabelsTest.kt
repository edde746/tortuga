package com.edde746.tortuga.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.strings.InterfaceLocale
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.interfaceLanguages
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertTrue

/** The top-level destinations' names in the navigation, worded in every language. */
@OptIn(ExperimentalTestApi::class)
class NavigationLabelsTest {
    /**
     * The bar of a 360 dp phone, the rail (tablets, phones in landscape) and the TV column opened by focus in Android's
     * font (Roboto), and the desktop rail in Linux's (Noto Sans; fonts from the test's resources `fonts/`), each show
     * all five names whole, none needing more width than its place gives it. Windows draws Arial, narrower than both.
     */
    @Test
    fun everyDestinationsNameShowsWholeInEveryLanguage() {
        val wrong = mutableListOf<String>()
        try {
            for (language in interfaceLanguages) {
                T.locale = language
                for ((place, size, formFactor, font) in places) runComposeUiTest {
                    navigation(size, formFactor, font)
                    if (formFactor == FormFactor.TV) {
                        onNode(hasContentDescription(TopLevelDestination.BOARD.label) and hasClickAction()).requestFocus()
                        waitForIdle()
                    }
                    val labels = labels()
                    if (labels.size != TopLevelDestination.entries.size) wrong += "the $place shows ${labels.size} names in $language: $labels"
                    for ((text, needed, room) in labels) {
                        if (needed > room) wrong += "\"$text\" needs $needed dp, the $place gives it $room dp ($language)"
                    }
                }
            }
        } finally {
            T.locale = "en"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * The navigation alone in a window of [size], laid out right to left in a language that reads so, its labels' styles
     * in [font].
     */
    private fun ComposeUiTest.navigation(size: DpSize, formFactor: FormFactor, font: FontFamily) = setContent {
        val direction = if (InterfaceLocale.isRightToLeft(T.locale)) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            TortugaTheme {
                val type = MaterialTheme.typography
                MaterialTheme(
                    typography = type.copy(
                        labelMedium = type.labelMedium.copy(fontFamily = font),
                        titleMedium = type.titleMedium.copy(fontFamily = font),
                    ),
                ) {
                    Box(Modifier.size(size)) {
                        AdaptiveLayout(formFactor) {
                            NavigationScaffold(
                                destinations = TopLevelDestination.entries,
                                selected = TopLevelDestination.BOARD,
                                onSelect = {},
                                showNavigation = true,
                            ) {
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }

    /** Each text shown, the width in dp it needs on one line, and the width in dp its place gives it. */
    private fun ComposeUiTest.labels(): List<Triple<String, Int, Int>> {
        val nodes = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        return List(nodes.fetchSemanticsNodes().size) { nodes[it] }.map { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val needed = ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth / density.density).toInt()
            Triple(layout.layoutInput.text.text, needed, (layout.layoutInput.constraints.maxWidth / density.density).toInt())
        }
    }

    private data class Place(val name: String, val size: DpSize, val formFactor: FormFactor, val font: FontFamily)

    private companion object {
        /** Roboto at the weights the navigation's labels use: Medium (the bar and the rail), SemiBold (the TV column). */
        val roboto = FontFamily(
            Font("fonts/Roboto-Medium.ttf", FontWeight.Medium),
            Font("fonts/Roboto-SemiBold.ttf", FontWeight.SemiBold),
        )

        /** Noto Sans Medium (its Latin and Cyrillic), Compose's sans serif on Linux, where the rail's labels use it. */
        val notoSans = FontFamily(Font("fonts/NotoSans-Medium.ttf", FontWeight.Medium))

        /**
         * The navigation's places: the bar on the narrowest common phone (a Galaxy A's 360 dp), the rail in a tablet's
         * and a desktop window, and the TV column on a TV's 960 × 540 dp.
         */
        val places = listOf(
            Place("bar at 360 dp", DpSize(360.dp, 760.dp), FormFactor.HANDHELD, roboto),
            Place("rail", DpSize(800.dp, 760.dp), FormFactor.HANDHELD, roboto),
            Place("rail in Noto Sans", DpSize(800.dp, 760.dp), FormFactor.DESKTOP, notoSans),
            Place("TV column", DpSize(960.dp, 540.dp), FormFactor.TV, roboto),
        )
    }
}
