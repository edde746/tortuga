package com.edde746.tortuga.ui.players

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.players.PlayerAppBanner
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.components.OverlayHostBox
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A hand-off's banner shows over the Streams sheet: its actions take clicks there, a tap on it leaves the sheet open,
 * and on TV it takes focus from the sheet once it offers Play in Tortuga, Back then dismissing it, not the sheet.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerAppBannerTest {
    private val dispatcher = NavigationEventDispatcher()
    private val back = DirectNavigationEventInput().also { dispatcher.addInput(it) }
    private var banner by mutableStateOf<PlayerAppBanner?>(opening)
    private var sheetDismissed = 0
    private var playedInTortuga = 0
    private var dismissed = 0

    /** While the hand-off is under way the banner says so with Cancel, which cancels it; there is no Play in Tortuga. */
    @Test
    fun whileOpeningCancelCancels() = runComposeUiTest {
        bannerOverSheet(FormFactor.HANDHELD)
        onNodeWithText("Opening in VLC…").assertIsDisplayed()
        onNodeWithText("Play in Tortuga").assertDoesNotExist()
        onNode(hasClickAction() and hasText("Cancel")).performClick()
        runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, sheetDismissed)
        }
    }

    /** After a failure Play in Tortuga takes a click over the sheet; a tap on the text leaves the sheet open. */
    @Test
    fun afterAFailurePlayInTortugaPlays() = runComposeUiTest {
        banner = failed
        bannerOverSheet(FormFactor.DESKTOP)
        onNodeWithText(failed.text).performClick()
        onNode(hasClickAction() and hasText("Play in Tortuga")).performClick()
        runOnIdle {
            assertEquals(1, playedInTortuga)
            assertEquals(0, sheetDismissed)
        }
    }

    /**
     * On TV the sheet keeps focus while the banner only says it is opening; once the hand-off failed, Play in Tortuga
     * has focus, and Back dismisses the banner (not the sheet). Once the banner has gone the sheet has focus again.
     */
    @Test
    fun onTvTheBannerTakesFocusFromTheSheetAfterAFailure() = runComposeUiTest {
        bannerOverSheet(FormFactor.TV)
        onNodeWithText("First").assertIsFocused()

        banner = failed
        onNodeWithText("Play in Tortuga").assertIsFocused()

        runOnIdle { back.backCompleted() }
        runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, sheetDismissed)
        }

        banner = null
        onNodeWithText("First").assertIsFocused()
    }

    /**
     * The failed hand-off's banner over the Streams sheet on a phone (411 × 800 dp at twice the pixels). The picture is
     * kept as build/screenshots/player-app-banner-phone.png.
     */
    @Test
    fun theBannerShowsOverTheSheetOnAPhone() = runSkikoComposeUiTest(size = Size(822f, 1600f), density = Density(2f)) {
        banner = failed
        bannerOverSheet(FormFactor.HANDHELD)
        onNodeWithText(failed.text).assertIsDisplayed()
        shot("player-app-banner-phone")
    }

    private fun ComposeUiTest.shot(name: String) {
        mainClock.advanceTimeBy(1_000)
        val file = File("build/screenshots/$name.png")
        file.parentFile.mkdirs()
        assertTrue(ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file), "no PNG writer")
    }

    private fun ComposeUiTest.bannerOverSheet(formFactor: FormFactor) = setContent {
        val owner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = dispatcher
        }
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
            TortugaTheme {
                val size = if (formFactor == FormFactor.HANDHELD) DpSize(411.dp, 800.dp) else DpSize(960.dp, 540.dp)
                Box(Modifier.size(size).background(TortugaColors.Background)) {
                    AdaptiveLayout(formFactor) {
                        OverlayHostBox {
                            TortugaSheet(onDismiss = { sheetDismissed++ }) {
                                TortugaButton("First", {})
                                TortugaButton("Second", {})
                            }
                            banner?.let { PlayerAppBannerLine(it, onPlayInTortuga = { playedInTortuga++ }, onDismiss = { dismissed++ }) }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val opening = PlayerAppBanner("Opening in VLC…", busy = true, playInTortugaText = null, dismissText = "Cancel")
        val failed = PlayerAppBanner("VLC isn't installed on this device.", busy = false, playInTortugaText = "Play in Tortuga", dismissText = "Dismiss")
    }
}
