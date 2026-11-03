package com.edde746.tortuga.ui.addons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.addons.AddonBrowseBody
import com.edde746.tortuga.presentation.addons.AddonBrowseChoice
import com.edde746.tortuga.presentation.addons.AddonBrowseItem
import com.edde746.tortuga.presentation.addons.AddonBrowseUiState
import com.edde746.tortuga.presentation.addons.AddonDetailsUiState
import com.edde746.tortuga.presentation.addons.AddonInstallAction
import com.edde746.tortuga.presentation.addons.AddonInstallStep
import com.edde746.tortuga.presentation.addons.AddonInstallUiState
import com.edde746.tortuga.presentation.addons.AddonPreviewUiState
import com.edde746.tortuga.presentation.addons.AddonRemoveDialog
import com.edde746.tortuga.presentation.addons.AddonRow
import com.edde746.tortuga.presentation.addons.AddonWatchActivity
import com.edde746.tortuga.presentation.addons.AddonsBody
import com.edde746.tortuga.presentation.addons.AddonsSection
import com.edde746.tortuga.presentation.addons.AddonsUiState
import com.edde746.tortuga.presentation.addons.ShareLinkUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.FocusedProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Addons list on phones, tablets, desktop and TV: each row's ⋮ opens its menu, Move up and Move down where the
 * addon can go, Configure where it can be configured and Remove unless it is built in, in that order; screen readers
 * get the same entries as the row's actions. A right click opens the same menu, a long press none. Remove asks first,
 * from the menu or with Delete on desktop. Each addon but the built-in one has a switch, on TV beside its row, which a
 * remote reaches with Right; its details hold the switch and, for an addon that asks for it, the watch-activity one.
 * Browse lists a catalog's addons, each opening the install preview, which offers Configure for an addon with
 * settings, and installs a new addon that asks for the user's watch activity only once its tick box is ticked. On TV
 * the remote reaches every line of the preview and of the details, however far below their room they reach. A link
 * that fails brings the install page's field back to the store's link, its error staying.
 */
@OptIn(ExperimentalTestApi::class)
class AddonsScreenTest {
    private val calls = mutableListOf<String>()
    private var removeDialog by mutableStateOf<AddonRemoveDialog?>(null)
    private var order by mutableStateOf(listOf("cinemeta", "opensubtitles", "streams"))
    private var open by mutableStateOf(true)
    private var section by mutableStateOf(AddonsSection.INSTALLED)
    private var browse by mutableStateOf<AddonBrowseUiState?>(null)
    private var install by mutableStateOf(AddonInstallUiState(AddonInstallStep.Closed, "", "", ""))
    private var details by mutableStateOf<AddonDetailsUiState?>(null)
    private var notice by mutableStateOf<String?>(null)
    private var off by mutableStateOf(setOf<String>())

    private val names = mapOf("cinemeta" to "Cinemeta", "opensubtitles" to "OpenSubtitles", "streams" to "Example Streams")
    private val up = T.addons.moveUp
    private val down = T.addons.moveDown
    private val configure = T.addons.details.configure
    private val remove = T.addons.details.remove

    /** The built-in addon first, the configurable one in the middle, the last one: each menu's entries. */
    @Test
    fun everyRowsMenuOffersWhatItsPlaceAllowsAndScreenReadersTheSame() {
        val menus = listOf("Cinemeta" to listOf(down), "OpenSubtitles" to listOf(up, down, configure, remove), "Example Streams" to listOf(up, remove))
        for (formFactor in listOf(FormFactor.HANDHELD, FormFactor.DESKTOP)) {
            for ((name, entries) in menus) runComposeUiTest {
                addons(formFactor)
                assertEquals(entries, actionsOf(name), "$name's actions on $formFactor")
                openMenu(name)
                assertEquals(entries, menu(), "$name's menu on $formFactor")
            }
        }
    }

    /** Where there is a pointer (desktop, a tablet with a mouse) a right click on the row opens its menu. */
    @Test
    fun aRightClickOpensTheRowsMenu() {
        for (formFactor in listOf(FormFactor.HANDHELD, FormFactor.DESKTOP)) runComposeUiTest {
            addons(formFactor)
            rowNode("OpenSubtitles").performMouseInput { rightClick() }
            assertEquals(listOf(up, down, configure, remove), menu(), "on $formFactor")
        }
    }

    /** On touch the hold belongs to the drag: a long press opens no menu. */
    @Test
    fun aLongPressOpensNoMenu() = runComposeUiTest {
        addons(FormFactor.HANDHELD)
        rowNode("OpenSubtitles").performTouchInput { longClick() }
        assertEquals(emptyList<String>(), menu())
    }

    @Test
    fun removeFromTheMenuAsksFirst() = runComposeUiTest {
        addons(FormFactor.HANDHELD)
        openMenu("Example Streams")
        onNode(hasClickAction() and hasText(remove)).performClick()
        runOnIdle { assertEquals(listOf("remove:streams"), calls) }

        onNodeWithText("Remove Example Streams?").assertIsDisplayed()
        onNodeWithText("Remove").performClick()
        runOnIdle { assertEquals(listOf("remove:streams", "confirmRemove"), calls) }
    }

    /** Example Streams moved up from its menu is still there when Addons opens again, and its menu follows its new place. */
    @Test
    fun aMoveFromTheMenuKeepsTheOrderWhenAddonsOpensAgain() = runComposeUiTest {
        addons(FormFactor.HANDHELD)
        openMenu("Example Streams")
        onNode(hasClickAction() and hasText(up)).performClick()
        runOnIdle { open = false }
        runOnIdle { open = true }
        assertEquals(listOf("moveUp:streams"), calls)
        assertEquals(listOf("Cinemeta", "Example Streams", "OpenSubtitles"), names.values.sortedBy { rowNode(it).fetchSemanticsNode().boundsInRoot.top })
        openMenu("Example Streams")
        assertEquals(listOf(up, down, remove), menu())
    }

    @Test
    fun onDesktopDeleteAsksToRemoveTheFocusedAddonUnlessBuiltIn() = runComposeUiTest {
        addons(FormFactor.DESKTOP)
        rowNode("Cinemeta").requestFocus().performKeyInput { pressKey(Key.Delete) }
        rowNode("Example Streams").requestFocus().performKeyInput { pressKey(Key.Delete) }
        assertEquals(listOf("remove:streams"), calls)
    }

    @Test
    fun browseListsTheChosenCatalogAndAPickedAddonOpensItsPreview() = runComposeUiTest {
        browse = browsing
        addons(FormFactor.HANDHELD)
        onNodeWithText("Community").assertDoesNotExist()

        onNodeWithText("Browse").performClick()
        onNodeWithText("Community").performClick()
        onNodeWithText("Movies").performClick()
        onNodeWithText("On this profile").assertExists()
        onNodeWithText("WatchHub").performClick()
        onNodeWithText("Installed").performClick()
        assertEquals(listOf("section:BROWSE", "catalog:1", "type:movie", "previewBrowse:1", "section:INSTALLED"), calls)
        onNodeWithText("Example Streams").assertExists()
        onNodeWithText("WatchHub").assertDoesNotExist()
    }

    @Test
    fun aPreviewOfAnAddonWithSettingsOffersConfigureBesideInstall() = runComposeUiTest {
        val preview = AddonPreviewUiState(
            name = "Example Streams", version = "Version 0.0.15", description = null, logo = null, types = "Movies, Series",
            resources = "Streams", catalogs = emptyList(), status = null, warning = null, watchActivity = null, action = AddonInstallAction.Install("Install"),
            canConfirm = true, configureLabel = "Configure", cancelLabel = "Cancel",
        )
        install = AddonInstallUiState(AddonInstallStep.Preview(preview, null), "Install addon", "", "")
        addons(FormFactor.HANDHELD)
        onNodeWithText("Configure").performClick()
        onNodeWithText("Install").performClick()
        assertEquals(listOf("configurePreview", "confirmInstall"), calls)
    }

    /**
     * A link followed into the app while the install page holds a typed one, and failing at once: the page is back at
     * the link with the error and the store's link, which is none, without the loading step ever drawn. The field
     * empties, so Continue's state matches what it shows, and the error stays.
     */
    @Test
    fun aFailedLinkFromOutsideEmptiesTheFieldAndKeepsTheError() = runComposeUiTest {
        val typed = "https://typed.invalid/manifest.json"
        fun enterLink(link: String, error: String?) =
            AddonInstallUiState(AddonInstallStep.EnterLink(link, link.isNotBlank(), error), "Install addon", "Addon link", "Continue")
        install = enterLink("", null)
        addons(FormFactor.HANDHELD)
        onNode(hasSetTextAction()).performTextInput(typed)
        install = enterLink(typed, null)
        waitForIdle()
        onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(typed)))

        install = enterLink("", T.addons.error.network)
        waitForIdle()
        onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        onNodeWithText(T.addons.error.network).assertIsDisplayed()
        // The emptied field isn't sent back as if typed: that would clear the store's error.
        assertEquals("link:$typed", calls.last { it.startsWith("link:") })
    }

    /**
     * The built-in addon's details on a phone and on TV: its note is a line of its own under the heading, so the name
     * keeps one line beside the logo, and Close stays on screen.
     */
    @Test
    fun theBuiltInNoteIsALineUnderTheHeadingNotBesideTheName() {
        for (formFactor in listOf(FormFactor.HANDHELD, FormFactor.TV)) runComposeUiTest {
            // The list under the sheet without Cinemeta, so its name is the details' alone.
            order = listOf("opensubtitles", "streams")
            details = cinemetaDetails()
            addons(formFactor)
            val shot = File("build/screenshots/addon-details-${formFactor.name.lowercase()}.png").also { it.parentFile.mkdirs() }
            assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shot), "no PNG writer")
            val name = onNodeWithText("Cinemeta").getUnclippedBoundsInRoot()
            val note = onNodeWithText(BUILT_IN_NOTE).getUnclippedBoundsInRoot()
            val lines = mutableListOf<TextLayoutResult>().also { onNodeWithText("Cinemeta").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }
            assertEquals(1, lines.single().lineCount, "$formFactor: the name takes ${lines.single().lineCount} lines")
            assertTrue(note.top >= name.bottom, "$formFactor: the note (${note.top}) isn't under the name (${name.bottom})")
            // On TV the badge beside the name pushed the sheet past the window's right edge, Close with it.
            val right = onRoot().getUnclippedBoundsInRoot().right
            for ((what, bounds) in listOf("the note" to note, "Close" to onNodeWithText(T.addons.close).getUnclippedBoundsInRoot())) {
                assertTrue(bounds.right <= right, "$formFactor: $what ends at ${bounds.right}, past the window's ${right}")
            }
        }
    }

    /**
     * On TV Down from the section chips and Up from the first addon land on the notice's Close, however many lines the
     * notice takes: alone at the banner's end, Close would lose to the full-width list of addons under it and the row
     * of chips above.
     */
    @Test
    fun onTvUpAndDownLandOnTheNoticesCloseHoweverManyLinesItTakes() {
        val wrong = mutableListOf<String>()
        val moves = listOf(
            Triple("Down from Installed", Key.DirectionDown, "Installed"),
            Triple("Down from Browse", Key.DirectionDown, "Browse"),
            Triple("Up from Cinemeta", Key.DirectionUp, "Cinemeta"),
        )
        for (text in notices) runComposeUiTest {
            browse = browsing
            notice = text
            val probe = FocusedProbe()
            addons(FormFactor.TV, probe)
            for ((move, key, start) in moves) {
                onNodeWithText(start).requestFocus()
                press(key)
                if (probe.focused != "notice.close") wrong += "A ${text.length}-character notice: $move went to ${probe.focused}"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * A row's switch turns its addon off and on again; the off row keeps its place and its menu. The built-in addon has
     * no switch.
     */
    @Test
    fun theRowSwitchTurnsTheAddonOffAndOnAndTheBuiltInOneHasNone() {
        for (formFactor in listOf(FormFactor.HANDHELD, FormFactor.DESKTOP)) runComposeUiTest {
            calls.clear()
            off = emptySet()
            addons(formFactor)
            onNode(hasContentDescription(T.addons.enabledNamed("Cinemeta"))).assertDoesNotExist()
            val switch = onNode(hasContentDescription(T.addons.enabledNamed("OpenSubtitles")))
            switch.performClick()
            assertEquals(listOf("enabled:opensubtitles:false"), calls, "on $formFactor")
            assertEquals(listOf("Cinemeta", "OpenSubtitles", "Example Streams"), names.values.sortedBy { rowNode(it).fetchSemanticsNode().boundsInRoot.top }, "on $formFactor")
            assertEquals(listOf(up, down, configure, remove), actionsOf("OpenSubtitles"), "the off row's menu on $formFactor")
            calls.clear()
            switch.performClick()
            assertEquals(listOf("enabled:opensubtitles:true"), calls, "on $formFactor")
        }
    }

    /**
     * A narrow row (a 320 dp phone) gives its text the row's width: its switch and ⋮ are a line under the name and the
     * facts. On a wider screen they sit beside the text.
     */
    @Test
    fun aNarrowRowPutsItsControlsUnderItsTextAWideOneBesideIt() {
        for ((width, under) in listOf(320.dp to true, 720.dp to false)) runComposeUiTest {
            addons(FormFactor.HANDHELD, width = width)
            val name = onNodeWithText("OpenSubtitles", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val switch = onNode(hasContentDescription(T.addons.enabledNamed("OpenSubtitles"))).getUnclippedBoundsInRoot()
            val more = onNode(hasContentDescription(T.detail.moreActionsNamed(isolate("OpenSubtitles")))).getUnclippedBoundsInRoot()
            for ((what, control) in listOf("switch" to switch, "⋮" to more)) {
                assertEquals(under, control.top >= name.bottom, "$width: the $what's top ${control.top}, the name's bottom ${name.bottom}")
            }
        }
    }

    /**
     * On TV the switch is beside its row: Right from the row lands on it, Select turns the addon off, Right goes on to
     * the row's ↑, and Left back to the switch and the row, whose Select still opens its details and a held Select its
     * menu. The built-in addon's row has no switch: Right from it lands on its ↓.
     */
    @Test
    fun onTvTheDpadReachesTheRowsSwitchAndTogglesIt() = runComposeUiTest {
        val probe = FocusedProbe()
        addons(FormFactor.TV, probe)
        onNodeWithText("Cinemeta").requestFocus()
        press(Key.DirectionRight)
        assertEquals("addons.row.0.down", probe.focused, "Right from Cinemeta")

        val steps = mutableListOf<String?>()
        onNodeWithText("Example Streams").requestFocus()
        for (key in listOf(Key.DirectionRight, Key.DirectionCenter, Key.DirectionRight, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionCenter)) {
            press(key)
            steps += probe.focused
        }
        assertEquals(
            listOf<String?>("addons.row.2.switch", "addons.row.2.switch", "addons.row.2.up", "addons.row.2.switch", "addons.row.2", "addons.row.2"),
            steps,
            "where focus was after each key",
        )
        assertEquals(listOf("enabled:streams:false", "details:streams"), calls)
        onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        mainClock.advanceTimeBy(600)
        onRoot().performKeyInput { keyUp(Key.DirectionCenter) }
        assertEquals(listOf(remove), menu(), "Example Streams' menu on TV")
    }

    /**
     * A new addon that asks for the user's watch activity: Install stays off until the tick box is ticked, and ticking
     * tells the store. On TV the tick box has focus first, as the button can't take it.
     */
    @Test
    fun aPreviewThatAsksForWatchActivityInstallsOnlyOnceTicked() {
        for (formFactor in listOf(FormFactor.HANDHELD, FormFactor.TV)) runComposeUiTest {
            calls.clear()
            val consent = AddonWatchActivity(T.addons.watchActivity.agree, T.addons.watchActivity.explanation, checked = false)
            val preview = AddonPreviewUiState(
                name = "Tortuga Watch Activity", version = "Version 1.0.0", description = null, logo = null, types = "Movies, Series",
                resources = "Catalogs, Watch activity", catalogs = emptyList(), status = null, warning = null, watchActivity = consent,
                action = AddonInstallAction.Install("Install"), canConfirm = false, configureLabel = null, cancelLabel = "Cancel",
            )
            install = AddonInstallUiState(AddonInstallStep.Preview(preview, null), "Install addon", "", "")
            addons(formFactor)
            if (formFactor == FormFactor.TV) onNode(isFocused()).assert(hasText(consent.label))
            val confirm = onNodeWithText("Install")
            confirm.assertIsNotEnabled().performClick()
            assertEquals(emptyList<String>(), calls, "on $formFactor")

            onNodeWithText(consent.label).performClick()
            confirm.assertIsEnabled().performClick()
            assertEquals(listOf("consent:true", "confirmInstall"), calls, "on $formFactor")
        }
    }

    /**
     * On TV the remote reaches the preview's last line, its catalogs, under a summary taller than its room: Up from the
     * tick box lands on the summary, whose Down scrolls it until they show whole, then goes on to the tick box. The
     * addon's name shows whole as the preview opens.
     */
    @Test
    fun onTvTheRemoteReachesThePreviewsCatalogs() = runComposeUiTest {
        val probe = FocusedProbe()
        val consent = AddonWatchActivity(T.addons.watchActivity.agree, T.addons.watchActivity.explanation, checked = false)
        val last = "Tortuga Activity (Movies)"
        val preview = AddonPreviewUiState(
            name = "Tortuga Watch Activity", version = "Version 1.0.0", description = "Records the player and library events it receives.",
            logo = null, types = "Movies, Series", resources = "Catalogs, Playback activity, Library activity",
            catalogs = listOf("Popular (Movies)", "Popular (Series)", last), status = null, warning = null, watchActivity = consent,
            action = AddonInstallAction.Install("Install"), canConfirm = false, configureLabel = null, cancelLabel = "Cancel",
        )
        install = AddonInstallUiState(AddonInstallStep.Preview(preview, null), "Install addon", "", "")
        addons(FormFactor.TV, probe)
        assertTrue(shownWhole(preview.name), "the name as the preview opens")
        assertTrue(!shownWhole(last), "the last catalog shows as the preview opens: nothing to reach")

        val focus = mutableListOf<String?>()
        for (key in listOf(Key.DirectionUp) + List(6) { Key.DirectionDown }) {
            if (shownWhole(last)) break
            press(key)
            focus += probe.focused
        }
        assertTrue(shownWhole(last), "the last catalog never showed whole; focus after each key: $focus")
        assertEquals("addons.install.summary", probe.focused, "focus once the last catalog shows")
        press(Key.DirectionDown)
        assertEquals("addons.install.consent", probe.focused, "Down at the summary's end")
    }

    /**
     * On TV the remote reaches every line of the details: past their last switch Down scrolls them until their last
     * catalog shows whole, then goes on to the link's card; past their first, Up scrolls back to the name. The built-in
     * addon's, with no switch, are a stop of their own (Up from the link's card) whose Down scrolls them.
     */
    @Test
    fun onTvTheRemoteReachesEveryLineOfTheDetails() {
        val catalogs = listOf("Popular (Movies)", "Popular (Series)", "New (Movies)", "New (Series)", "Featured (Movies)", "Featured (Series)")
        runComposeUiTest {
            val probe = FocusedProbe()
            order = listOf("cinemeta", "opensubtitles")
            details = streamsDetails().copy(description = "Streams from an addon.", catalogs = catalogs)
            addons(FormFactor.TV, probe)
            assertTrue(!shownWhole(catalogs.last()), "the last catalog shows as the details open: nothing to reach")
            onNodeWithText(T.addons.watchActivity.title).requestFocus()
            waitForIdle()
            val focus = mutableListOf<String?>()
            for (key in List(8) { Key.DirectionDown }) {
                if (shownWhole(catalogs.last())) break
                press(key)
                focus += probe.focused
            }
            assertTrue(shownWhole(catalogs.last()), "Down from the last switch: the last catalog never showed whole; focus after each key: $focus")
            press(Key.DirectionDown)
            assertTrue(probe.focused in listOf("addons.details.reveal", "addons.details.copy"), "Down at the details' end went to ${probe.focused}")

            onNodeWithText(T.addons.details.enabled).requestFocus()
            waitForIdle()
            assertTrue(!shownWhole("Example Streams"), "the name shows with focus on the first switch: nothing to reach")
            focus.clear()
            for (key in List(8) { Key.DirectionUp }) {
                if (shownWhole("Example Streams")) break
                press(key)
                focus += probe.focused
            }
            assertTrue(shownWhole("Example Streams"), "Up from the first switch: the name never showed whole; focus after each key: $focus")
        }
        runComposeUiTest {
            val probe = FocusedProbe()
            order = listOf("opensubtitles", "streams")
            details = cinemetaDetails().copy(catalogs = catalogs)
            addons(FormFactor.TV, probe)
            assertTrue(!shownWhole(catalogs.last()), "the built-in addon's last catalog shows as the details open: nothing to reach")
            val focus = mutableListOf<String?>()
            for (key in List(2) { Key.DirectionUp } + List(8) { Key.DirectionDown }) {
                if (shownWhole(catalogs.last())) break
                press(key)
                focus += probe.focused
            }
            assertTrue(shownWhole(catalogs.last()), "the built-in addon's last catalog never showed whole; focus after each key: $focus")
            assertEquals("addons.details.facts", probe.focused, "focus once it shows")
        }
    }

    /**
     * The details' switches tell the store: Use this addon and, for an addon that asks for it, Send watch activity. The
     * built-in addon's details have neither.
     */
    @Test
    fun theDetailsSwitchesTellTheStoreAndTheBuiltInOneHasNone() {
        for (formFactor in listOf(FormFactor.HANDHELD, FormFactor.TV)) {
            runComposeUiTest {
                calls.clear()
                details = streamsDetails()
                addons(formFactor)
                onNodeWithText(T.addons.watchActivity.explanation).assertExists()
                onNodeWithText(T.addons.watchActivity.title).performClick()
                onNodeWithText(T.addons.details.enabled).performClick()
                assertEquals(listOf("watchActivity:streams:true", "enabled:streams:false"), calls, "on $formFactor")
                onNodeWithText(T.addons.details.enabledOff).assertExists()
                onNodeWithText(T.addons.watchActivity.title).performClick()
                assertEquals("watchActivity:streams:false", calls.last(), "on $formFactor")
            }
            runComposeUiTest {
                details = cinemetaDetails()
                addons(formFactor)
                onNodeWithText(T.addons.details.enabled).assertDoesNotExist()
                onNodeWithText(T.addons.watchActivity.title).assertDoesNotExist()
            }
        }
    }

    private fun ComposeUiTest.rowNode(name: String): SemanticsNodeInteraction = onNodeWithText(name)

    private fun ComposeUiTest.actionsOf(name: String): List<String> =
        rowNode(name).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    /** The ⋮ of the addon [name], found by what screen readers call it. */
    private fun ComposeUiTest.openMenu(name: String) {
        onNode(hasContentDescription(T.detail.moreActionsNamed(isolate(name)))).performClick()
    }

    /** The open menu's entries, top to bottom (none while no menu is open). */
    private fun ComposeUiTest.menu(): List<String> =
        onAllNodes(hasClickAction() and (hasText(up) or hasText(down) or hasText(configure) or hasText(remove)))
            .fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .map { it.config[SemanticsProperties.Text].first().text }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    /** Whether the line [text] shows whole: nothing of it clipped by the scroll it is in or the window. */
    private fun ComposeUiTest.shownWhole(text: String): Boolean {
        val node = onNodeWithText(text).fetchSemanticsNode()
        return node.boundsInRoot.height >= node.size.height - 0.5f && node.boundsInRoot.width > 0f
    }

    /**
     * The pane in a phone's window ([width] wide), or a 1080p TV's on TV, the arrow keys as the D-pad, its focus going
     * to [probe].
     */
    private fun ComposeUiTest.addons(formFactor: FormFactor, probe: FocusedProbe? = null, width: Dp = 411.dp) = setContent {
        CompositionLocalProvider(LocalLayoutProbe provides probe) {
            TortugaTheme {
                Box(if (formFactor == FormFactor.TV) Modifier.size(960.dp, 540.dp) else Modifier.size(width, 800.dp)) {
                    AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                        if (open) AddonsPane(state(), intents, linkCopied = false)
                    }
                }
            }
        }
    }

    private fun state() = AddonsUiState(
        title = "Addons",
        section = if (browse == null) AddonsSection.INSTALLED else section,
        typeFilters = emptyList(),
        body = AddonsBody.Rows(order.mapIndexed { index, id -> row(id, index) }),
        installLabel = "Install addon",
        install = install,
        browse = browse,
        details = details,
        removeDialog = removeDialog,
        working = false,
        notice = notice,
    )

    /** As the store lists [id] at [index]: Cinemeta built in (no switch), OpenSubtitles configurable. */
    private fun row(id: String, index: Int) = AddonRow(
        id = id,
        name = names.getValue(id),
        description = null,
        logo = null,
        facts = "Version 1.0.0 · Movies, Series · Streams",
        note = if (id == "cinemeta") "Built in" else null,
        configurable = id == "opensubtitles",
        removable = id != "cinemeta",
        enabled = id !in off,
        canDisable = id != "cinemeta",
        enabledLabel = T.addons.enabledNamed(names.getValue(id)),
        canMoveUp = index > 0,
        canMoveDown = index < order.lastIndex,
        moveUpLabel = T.addons.moveUpNamed(isolate(names.getValue(id))),
        moveDownLabel = T.addons.moveDownNamed(isolate(names.getValue(id))),
        dragLabel = T.addons.dragToReorderNamed(isolate(names.getValue(id))),
        moreLabel = T.detail.moreActionsNamed(isolate(names.getValue(id))),
    )

    /** Cinemeta's details as the store gives them: built in, so with its note, no Remove and no switch. */
    private fun cinemetaDetails() = AddonDetailsUiState(
        id = "cinemeta", name = "Cinemeta", version = "Version 3.0.13", description = "The official addon for movie and series catalogs.",
        logo = null, background = null, typesLabel = "Types", types = "Movies, Series", resourcesLabel = "Provides", resources = "Catalogs, Metadata",
        catalogsLabel = "Catalogs", catalogs = listOf("Popular · Movies", "Popular · Series"), contactLabel = "Contact", contact = null,
        note = BUILT_IN_NOTE, configurable = false, configureLabel = "Configure", removable = false, removeLabel = "Remove",
        enabled = true, canDisable = false, enabledLabel = T.addons.details.enabled, enabledNote = null, watchActivity = null,
        share = ShareLinkUiState("Addon link", "Anyone with this link can use the addon as you set it up.", false, null, "Show link", "Hide link", "Copy link"),
    )

    /** Example Streams' details as the store gives them while it is on, for a manifest that asks for watch activity. */
    private fun streamsDetails() = AddonDetailsUiState(
        id = "streams", name = "Example Streams", version = "Version 0.0.15", description = null,
        logo = null, background = null, typesLabel = "Types", types = "Movies, Series", resourcesLabel = "Provides", resources = "Streams",
        catalogsLabel = "Catalogs", catalogs = emptyList(), contactLabel = "Contact", contact = null,
        note = null, configurable = false, configureLabel = "Configure", removable = true, removeLabel = "Remove",
        enabled = true, canDisable = true, enabledLabel = T.addons.details.enabled, enabledNote = null,
        watchActivity = AddonWatchActivity(T.addons.watchActivity.title, T.addons.watchActivity.explanation, checked = false),
        share = ShareLinkUiState("Addon link", "Anyone with this link can use the addon as you set it up.", false, null, "Show link", "Hide link", "Copy link"),
    )

    /** Browse with two catalogs and two types, Official's addons listed. */
    private val browsing = AddonBrowseUiState(
        label = "Browse",
        installedLabel = "Installed",
        catalogs = listOf(AddonBrowseChoice("0", "Official", true), AddonBrowseChoice("1", "Community", false)),
        types = listOf(AddonBrowseChoice("all", "All", true), AddonBrowseChoice("movie", "Movies", false)),
        body = AddonBrowseBody.Results(
            listOf(
                AddonBrowseItem("0", "Example Streams", "Streams from an addon.", null, "Movies, Series", note = "On this profile"),
                AddonBrowseItem("1", "WatchHub", null, null, "Movies, Series", note = null),
            ),
        ),
        retryLabel = "Retry",
    )

    /** A notice of one line, of two and of several on a TV. */
    private val notices = listOf(
        "Example Streams is updated.",
        "Example Streams couldn't be updated: its server didn't answer in time. It keeps the version you have, and Tortuga tries " +
            "again the next time the addons are loaded.",
        "OpenSubtitles and Example Streams couldn't be updated: their servers didn't answer in time. Each keeps the version you " +
            "have, and Tortuga tries again the next time the addons are loaded. Until then their subtitles and streams come " +
            "from the versions you have, which may miss what the new ones fix or add.",
    )

    /** [id] one place up or down in [order], as the store moves it. */
    private fun move(id: String, by: Int) {
        val from = order.indexOf(id)
        order = order.toMutableList().apply { add(from + by, removeAt(from)) }
    }

    /**
     * Records the screen's requests; a move reorders the list, a removal request opens the confirmation, a section
     * switch switches and a switch or tick box changes what it shows, as the store does.
     */
    private val intents = AddonsIntents(
        moveUp = { id ->
            calls += "moveUp:$id"
            move(id, -1)
        },
        moveDown = { id ->
            calls += "moveDown:$id"
            move(id, 1)
        },
        moveTo = { id, index -> calls += "moveTo:$id:$index" },
        configure = { id -> calls += "configure:$id" },
        setEnabled = { id, enabled ->
            calls += "enabled:$id:$enabled"
            off = if (enabled) off - id else off + id
            details = details?.let { if (it.id == id) it.copy(enabled = enabled, enabledNote = T.addons.details.enabledOff.takeUnless { enabled }) else it }
        },
        setWatchActivity = { id, allowed ->
            calls += "watchActivity:$id:$allowed"
            details = details?.let { it.copy(watchActivity = it.watchActivity?.copy(checked = allowed)) }
        },
        remove = { id ->
            calls += "remove:$id"
            removeDialog = AddonRemoveDialog(id, "Remove Example Streams?", "It stops providing streams.", "Remove", "Cancel")
        },
        confirmRemove = { calls += "confirmRemove" },
        dismissRemove = { removeDialog = null },
        dismissNotice = {},
        openDetails = { id -> calls += "details:$id" },
        closeDetails = {},
        revealLink = {},
        hideLink = {},
        copyLink = {},
        updateInstallLink = { calls += "link:$it" },
        submitInstallLink = {},
        confirmInstall = { calls += "confirmInstall" },
        setInstallConsent = { agreed ->
            calls += "consent:$agreed"
            val step = install.step
            if (step is AddonInstallStep.Preview) {
                val preview = step.preview
                install = install.copy(step = step.copy(preview = preview.copy(watchActivity = preview.watchActivity?.copy(checked = agreed), canConfirm = agreed)))
            }
        },
        closeInstall = {},
        configurePreview = { calls += "configurePreview" },
        showSection = { section ->
            calls += "section:$section"
            this@AddonsScreenTest.section = section
        },
        selectBrowseCatalog = { id -> calls += "catalog:$id" },
        selectBrowseType = { id -> calls += "type:$id" },
        retryBrowse = { calls += "retryBrowse" },
        previewBrowseItem = { id -> calls += "previewBrowse:$id" },
    )
}

private const val BUILT_IN_NOTE = "This addon is built in and can't be removed."
