package com.edde746.tortuga.ui.settings

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.players.CustomPlayerKind
import com.edde746.tortuga.presentation.settings.CustomPlayerForm
import com.edde746.tortuga.presentation.settings.PlayerChoiceRow
import com.edde746.tortuga.presentation.settings.PlayerSettingRow
import com.edde746.tortuga.presentation.settings.PlayerSettingsUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.focus.arrowKeyFocus
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Settings → Playback → Player: its row, its page of players and the Add custom player form. */
@OptIn(ExperimentalTestApi::class)
class PlayerSettingsTest {
    private val calls = mutableListOf<String>()

    /** The harness name of the element holding focus, as the layout probes report it. */
    private var focused: String? = null

    private val probe = object : LayoutProbe {
        override fun place(name: String, bounds: Rect?) = Unit

        override fun focus(name: String, focused: Boolean) {
            if (focused) this@PlayerSettingsTest.focused = name
            else if (this@PlayerSettingsTest.focused == name) this@PlayerSettingsTest.focused = null
        }
    }

    /** The spinner shown while the players are looked for. */
    private val searching = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    private val dispatcher = NavigationEventDispatcher()
    private val back = DirectNavigationEventInput().also { dispatcher.addInput(it) }

    private val vlc = PlayerChoiceRow("org.videolan.vlc", "VLC", "Resumes where you stopped", selected = false, missing = false, hasIcon = false, deleteText = null)
    private val mpv = PlayerChoiceRow("is.xyz.mpv", "mpv (not found)", null, selected = false, missing = true, hasIcon = false, deleteText = null)
    private val custom = PlayerChoiceRow("custom:dev", "My player", "/usr/bin/player {url}", selected = false, missing = false, hasIcon = false, deleteText = "Delete My player")

    private val page = PlayerSettingsUiState(
        row = PlayerSettingRow("Player", "Tortuga", "This device only"),
        pageOpen = true,
        title = "Player",
        note = "Every video on this device plays in the player you choose.",
        detecting = false,
        players = listOf(
            PlayerChoiceRow("tortuga", "Tortuga", null, selected = true, missing = false, hasIcon = false, deleteText = null),
            vlc,
            mpv,
        ),
        customHeader = "Custom players",
        customPlayers = listOf(custom),
        addCustomText = "Add custom player",
        customForm = null,
    )

    private val commandForm = CustomPlayerForm(
        title = "Add custom player",
        kind = CustomPlayerKind.COMMAND,
        nameLabel = "Name",
        nameHint = "mpv",
        commandLabel = "Program",
        commandHint = "/usr/bin/mpv",
        argumentsLabel = "Arguments",
        argumentsHint = "--fs {url}",
        argumentsDefault = "{url}",
        placeholders = "{url} is the video's link, {title} its title.",
        error = null,
        saveText = "Save",
        cancelText = "Cancel",
    )

    private val packageForm = commandForm.copy(
        kind = CustomPlayerKind.PACKAGE,
        commandLabel = "Package name",
        commandHint = "org.videolan.vlc",
        argumentsLabel = null,
        argumentsHint = null,
        placeholders = null,
    )

    /** The row shows the player chosen and that it is this device's only; its press opens the page. */
    @Test
    fun theRowShowsTheChoiceAndOpensThePage() = runComposeUiTest {
        var opened = 0
        setContent {
            TortugaTheme {
                AdaptiveLayout(FormFactor.HANDHELD) {
                    SettingsList { settingsGroup("playback", "Playback", listOf(playerSettingsItem(page.copy(pageOpen = false)) { opened++ })) }
                }
            }
        }
        onNodeWithText("This device only").assertIsDisplayed()
        onNode(hasClickAction() and hasText("Player")).assertTextContains("Tortuga").performClick()
        runOnIdle { assertEquals(1, opened) }
    }

    /** Without player apps on the platform the pane has no Player row. */
    @Test
    fun withoutPlayerAppsThereIsNoRow() {
        assertEquals(null, playerSettingsItem(null) {})
    }

    /**
     * As in the app: the pane's row and the page over the store's state, collected in a Settings section laid out by
     * its window's width (a subcomposition) in a navigation entry the user navigated to, under the app's overlay host.
     * The players are found as the page first shows (the store answers in the frame the page appears, as a desktop's
     * detection can): the page lists them, and "Add custom player" then puts the form in the list's place.
     */
    @Test
    fun inTheAppThePageFollowsTheStore() = runComposeUiTest {
        var backStack by mutableStateOf(listOf("detail"))
        pageFollowsTheStore(prepare = { backStack = listOf("board", "settings") }) { section ->
            NavDisplay(
                backStack = backStack,
                onBack = {},
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                popTransitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                entryProvider = { key -> NavEntry(key) { if (key == "settings") BoxWithConstraints { section() } else Text(key) } },
            )
        }
    }

    /** The same in a section laid out by its width, outside navigation. */
    @Test
    fun inASubcompositionThePageFollowsTheStore() = runComposeUiTest { pageFollowsTheStore { section -> BoxWithConstraints { section() } } }

    /** The same with the section composed with the screens (no subcomposition between it and the overlay host). */
    @Test
    fun thePageFollowsTheStore() = runComposeUiTest { pageFollowsTheStore { section -> section() } }

    private fun ComposeUiTest.pageFollowsTheStore(prepare: () -> Unit = {}, container: @Composable (section: @Composable () -> Unit) -> Unit) {
        val store = MutableStateFlow(page.copy(pageOpen = false, players = page.players.take(1)))
        setContent {
            CompositionLocalProvider(LocalLayoutProbe provides probe) {
                TortugaTheme {
                    Box(Modifier.size(960.dp, 800.dp)) {
                        AdaptiveLayout(FormFactor.DESKTOP, Modifier.arrowKeyFocus()) {
                            OverlayHostBox { container { StoreSection(store) } }
                        }
                    }
                }
            }
        }
        runOnIdle(prepare)
        onNode(hasClickAction() and hasText("Player")).performClick()
        onNodeWithText(page.note).assertIsDisplayed()
        onNodeWithText("VLC").assertIsDisplayed()
        onNode(searching).assertDoesNotExist()

        onNode(hasClickAction() and hasText("Add custom player")).performClick()
        onNode(hasText("Program")).assertIsDisplayed()
        onNodeWithText("Custom players").assertDoesNotExist()
        runOnIdle { assertEquals("settings.player.custom.name", focused) }
    }

    /**
     * A Settings section over [store]: the Player row, and the page while it is open, as the store would answer; the
     * players are found as soon as the page shows.
     */
    @Composable
    private fun StoreSection(store: MutableStateFlow<PlayerSettingsUiState>) {
        val state by store.collectAsState()
        SettingsList {
            settingsGroup("playback", "Playback", listOf(playerSettingsItem(state) { store.update { it.copy(pageOpen = true, detecting = true) } }))
        }
        if (state.pageOpen) {
            PlayerSettingsOverlay(
                state = state,
                onSelect = {},
                onDelete = {},
                onAddCustom = { store.update { it.copy(customForm = commandForm) } },
                onSaveCustom = { _, _, _ -> },
                onCloseCustom = { store.update { it.copy(customForm = null) } },
                onClose = { store.update { it.copy(pageOpen = false, customForm = null) } },
            )
            SideEffect { if (state.detecting) store.update { it.copy(detecting = false, players = page.players) } }
        }
    }

    /**
     * The page lists Tortuga (checked), the players found, the missing one and the custom ones; a press chooses a
     * player, a custom player's Delete deletes it, and "Add custom player" and Close do what they say.
     */
    @Test
    fun thePageChoosesDeletesAndAdds() = runComposeUiTest {
        overlay(FormFactor.DESKTOP) { page }
        onNode(hasClickAction() and hasText("Tortuga")).assertIsSelected()
        onNodeWithText("mpv (not found)").assertIsDisplayed()
        onNodeWithText("Custom players").assertIsDisplayed()
        onNode(hasClickAction() and hasText("VLC")).performClick()
        onNode(hasClickAction() and hasText("My player")).performClick()
        onNode(hasClickAction() and hasContentDescription("Delete My player")).performClick()
        onNode(hasClickAction() and hasText("Add custom player")).performClick()
        onNode(hasClickAction() and hasText(T.settings.close)).performClick()
        runOnIdle { assertEquals(listOf("select:org.videolan.vlc", "select:custom:dev", "delete:custom:dev", "addCustom", "close"), calls) }
    }

    /** While the players are looked for, the ones found so far are listed with the spinner after them. */
    @Test
    fun theFoundPlayersShowWhileTheSearchGoesOn() = runComposeUiTest {
        overlay(FormFactor.HANDHELD) { page.copy(detecting = true, players = page.players.take(1)) }
        onNode(hasClickAction() and hasText("Tortuga")).assertIsDisplayed()
        onNodeWithText("VLC").assertDoesNotExist()
        onNode(searching).assertExists()
    }

    /**
     * A program takes arguments: the field starts at `{url}`, and Save hands over what was typed with them; the
     * placeholders' line explains them.
     */
    @Test
    fun theCommandFormSavesTheProgramAndItsArguments() = runComposeUiTest {
        overlay(FormFactor.DESKTOP) { page.copy(customForm = commandForm) }
        onNodeWithText(commandForm.placeholders!!).assertIsDisplayed()
        onNode(hasText("{url}")).assertIsDisplayed()
        onNode(hasText("Name")).performTextInput("mpv")
        onNode(hasText("Program")).performTextInput("/usr/bin/mpv")
        onNode(hasClickAction() and hasText("Save")).performClick()
        runOnIdle { assertEquals(listOf("save:mpv|/usr/bin/mpv|{url}"), calls) }
    }

    /** A package takes no arguments: there is no such field, and Save hands over none. Cancel closes the form. */
    @Test
    fun thePackageFormHasNoArguments() = runComposeUiTest {
        overlay(FormFactor.HANDHELD) { page.copy(customForm = packageForm) }
        onNodeWithText("Arguments").assertDoesNotExist()
        onNode(hasText("Name")).performTextInput("VLC")
        onNode(hasText("Package name")).performTextInput("org.videolan.vlc")
        onNode(hasClickAction() and hasText("Save")).performClick()
        onNode(hasClickAction() and hasText("Cancel")).performClick()
        runOnIdle { assertEquals(listOf("save:VLC|org.videolan.vlc|", "closeCustom"), calls) }
    }

    /** Why the store refused the last Save shows under the fields, which keep what was typed. */
    @Test
    fun aRefusedSaveShowsWhy() = runComposeUiTest {
        var state by mutableStateOf(page.copy(customForm = commandForm))
        overlay(FormFactor.DESKTOP) { state }
        onNode(hasText("Program")).performTextInput("/usr/bin/mpv")
        state = state.copy(customForm = commandForm.copy(error = "Give the player a name."))
        onNodeWithText("Give the player a name.").assertIsDisplayed()
        onNode(hasText("Program")).assertTextContains("/usr/bin/mpv")
    }

    /**
     * On TV the page opens on the chosen player and Down walks the list to "Add custom player" and Close; Right from a
     * custom player reaches its Delete.
     */
    @Test
    fun onTvTheRemoteWalksThePage() = runComposeUiTest {
        overlay(FormFactor.TV) { page }
        runOnIdle { assertEquals("settings.player.option.tortuga", focused) }
        val order = listOf(
            "settings.player.option.org.videolan.vlc",
            "settings.player.option.is.xyz.mpv",
            "settings.player.option.custom:dev",
            "settings.player.addCustom",
            "settings.player.close",
        )
        for (name in order) {
            press(Key.DirectionDown)
            runOnIdle { assertEquals(name, focused) }
        }
        repeat(2) { press(Key.DirectionUp) }
        press(Key.DirectionRight)
        runOnIdle { assertEquals("settings.player.option.custom:dev.delete", focused) }
    }

    /**
     * On TV the form opens on its name; Down walks the fields. Back closes the form first, giving focus back to "Add
     * custom player", then the page.
     */
    @Test
    fun onTvBackClosesTheFormThenThePage() = runComposeUiTest {
        var state by mutableStateOf(page)
        overlay(FormFactor.TV, onCloseCustom = { state = state.copy(customForm = null) }) { state }
        state = state.copy(customForm = commandForm)
        runOnIdle { assertEquals("settings.player.custom.name", focused) }
        for (name in listOf("settings.player.custom.command", "settings.player.custom.arguments")) {
            press(Key.DirectionDown)
            runOnIdle { assertEquals(name, focused) }
        }
        runOnIdle { back.backCompleted() }
        runOnIdle {
            assertEquals(listOf("closeCustom"), calls)
            assertEquals("settings.player.addCustom", focused)
        }
        runOnIdle { back.backCompleted() }
        runOnIdle { assertEquals(listOf("closeCustom", "close"), calls) }
    }

    /**
     * On TV Down from the form's last field lands on Save, the form's main action, not on Cancel (nearer the field's
     * middle): the arguments of a command, the package name of a package.
     */
    @Test
    fun onTvDownFromTheLastFieldLandsOnSave() {
        val wrong = mutableListOf<String>()
        for ((form, last) in listOf(commandForm to "settings.player.custom.arguments", packageForm to "settings.player.custom.command")) runComposeUiTest {
            focused = null
            overlay(FormFactor.TV) { page.copy(customForm = form) }
            var presses = 0
            while (runOnIdle { focused } != last && presses++ < 3) press(Key.DirectionDown)
            if (runOnIdle { focused } != last) wrong += "${form.kind}: Down never reached the last field ($focused)"
            press(Key.DirectionDown)
            runOnIdle { if (focused != "settings.player.custom.save") wrong += "${form.kind}: Down from the last field went to $focused" }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * The page and its form on a 1080p TV (960 × 540 dp at twice the pixels), the page while the players are looked
     * for. The pictures are kept as build/screenshots/settings-player-tv.png and settings-player-form-tv.png.
     */
    @Test
    fun onTvThePageAndItsFormShow() = runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(2f)) {
        var state by mutableStateOf(page.copy(detecting = true))
        overlay(FormFactor.TV) { state }
        onNodeWithText("Custom players").assertIsDisplayed()
        shot("settings-player-tv")
        state = page.copy(customForm = commandForm.copy(error = "The arguments need {url}."))
        onNodeWithText("The arguments need {url}.").assertIsDisplayed()
        shot("settings-player-form-tv")
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun ComposeUiTest.shot(name: String) {
        mainClock.advanceTimeBy(1_000)
        val file = File("build/screenshots/$name.png")
        file.parentFile.mkdirs()
        assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file), "no PNG writer")
    }

    /** The window of each form factor: a phone, a desktop window, a 1080p TV. */
    private fun FormFactor.window(): DpSize = when (this) {
        FormFactor.HANDHELD -> DpSize(411.dp, 800.dp)
        FormFactor.TV -> DpSize(960.dp, 540.dp)
        else -> DpSize(960.dp, 800.dp)
    }

    private fun ComposeUiTest.overlay(formFactor: FormFactor, onCloseCustom: () -> Unit = {}, state: () -> PlayerSettingsUiState) = setContent {
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner, LocalLayoutProbe provides probe) {
            TortugaTheme {
                Box(Modifier.size(formFactor.window()).background(TortugaColors.Background)) {
                    AdaptiveLayout(formFactor, Modifier.arrowKeyFocus()) {
                        OverlayHostBox {
                            PlayerSettingsOverlay(
                                state = state(),
                                onSelect = { calls += "select:$it" },
                                onDelete = { calls += "delete:$it" },
                                onAddCustom = { calls += "addCustom" },
                                onSaveCustom = { name, command, arguments -> calls += "save:$name|$command|$arguments" },
                                onCloseCustom = {
                                    calls += "closeCustom"
                                    onCloseCustom()
                                },
                                onClose = { calls += "close" },
                            )
                        }
                    }
                }
            }
        }
    }
}
