package com.edde746.tortuga.ui.watch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.watch.WatchRoomUi
import com.edde746.tortuga.strings.InterfaceLocale
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A room's participants in an Arabic interface, in the lobby on a phone (as the player's room panel lays them out too)
 * and on a TV: someone else's name is theirs and reads in the direction of its script, a Latin name left to right;
 * your own row ("Sam (you)") is the app's and reads right to left; both names are drawn from the interface's start
 * edge, the same distance from the avatar.
 */
@OptIn(ExperimentalTestApi::class)
class WatchParticipantDirectionTest {
    @Test
    fun anotherParticipantsLatinNameReadsLeftToRightAndYourOwnRowRightToLeftInAnArabicInterface() {
        val wrong = mutableListOf<String>()
        try {
            T.locale = "ar"
            // Hosting: Sam's row is this device's own, Anna's someone else's.
            val hosting = testRoom(host = true)
            val you = hosting.participants.single { it.isYou }.name
            val other = hosting.participants.single { !it.isYou }.name
            val layouts = listOf(
                Triple(FormFactor.HANDHELD, false, DpSize(411.dp, 914.dp)),
                Triple(FormFactor.TV, true, DpSize(960.dp, 540.dp)),
            )
            for ((formFactor, wide, size) in layouts) {
                runSkikoComposeUiTest(size = Size(size.width.value * DENSITY, size.height.value * DENSITY), density = Density(DENSITY)) {
                    room(hosting, formFactor, wide, size)
                    val at = "on $formFactor"
                    val otherName = drawn(other)
                    val youName = drawn(you)
                    if (otherName.reads != ResolvedTextDirection.Ltr) wrong += "\"$other\" reads ${otherName.reads}, not left to right, $at"
                    if (youName.reads != ResolvedTextDirection.Rtl) wrong += "your own row \"$you\" reads ${youName.reads}, not right to left, $at"
                    for (name in listOf(otherName, youName)) {
                        if (!name.inBox) wrong += "\"${name.text}\" isn't drawn in its own place: its ink ends at ${name.inkRight}, its box spans ${name.box}, $at"
                    }
                    // Right to left, both rows start on the right: each name's ink ends where the gap to the avatar begins.
                    if (abs(otherName.inkRight - youName.inkRight) >= 2 * DENSITY) {
                        wrong += "\"$other\" is drawn up to ${otherName.inkRight}, your own row up to ${youName.inkRight}, not both from the start edge, $at"
                    }
                }
            }
        } finally {
            T.locale = "en"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * [text] as drawn, scrolled into view: the direction its paragraph reads, its box in the window's pixels, and where its
     * ink ends on the right, the first column with ink found going left from the 12 dp gap between the name's column and
     * the avatar (the row's own colour there). Pixels, not the text layout's line ends: the layout the semantics hand out
     * is laid out across the whole width the row leaves the name (311 px for "Anna", drawn in a box 80 px wide), so its
     * lines' ends don't say where the name is drawn.
     */
    private fun ComposeUiTest.drawn(text: String): Drawn {
        val node = onNode(hasText(text), useUnmergedTree = true).performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val box = node.fetchSemanticsNode().boundsInRoot
        val pixels = onRoot().captureToImage().toPixelMap()
        val band = box.top.roundToInt() until box.bottom.roundToInt()
        val gap = (box.right + 6 * DENSITY).roundToInt()
        val background = pixels[gap, (box.top + box.bottom).roundToInt() / 2]
        val inkRight = (gap downTo 0).first { x -> band.any { y -> pixels[x, y] != background } } + 1
        return Drawn(text, layout.getParagraphDirection(0), box, inkRight.toFloat())
    }

    private class Drawn(val text: String, val reads: ResolvedTextDirection, val box: Rect, val inkRight: Float) {
        /** Its ink ends inside its own box (a pixel of antialiasing past its edge allowed). */
        val inBox get() = inkRight > box.left + 1 && inkRight <= box.right + DENSITY
    }

    /**
     * [room] in a window of [size], scrolling as the lobby does, laid out right to left in a language that reads so;
     * [wide] puts the code beside the rest, as the lobby does on a TV.
     */
    private fun ComposeUiTest.room(room: WatchRoomUi, formFactor: FormFactor, wide: Boolean, size: DpSize) = setContent {
        val direction = if (InterfaceLocale.isRightToLeft(T.locale)) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            TortugaTheme {
                Box(Modifier.size(size)) {
                    AdaptiveLayout(formFactor) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            WatchRoomContent(room, actions, wide = wide, qrSize = 160.dp)
                        }
                    }
                }
            }
        }
    }

    private val actions = RoomActions(
        onCopy = {},
        onShare = null,
        onMakeHost = {},
        onKick = {},
        onControlMode = {},
        onLock = {},
        onJoinPlayback = {},
        onLeave = {},
        onEnd = {},
    )

    private companion object {
        /** The window's pixels per dp. */
        const val DENSITY = 2f
    }
}
