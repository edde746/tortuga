package com.edde746.tortuga.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.edde746.tortuga.app.AppBootstrap
import com.edde746.tortuga.app.BuildFlavor
import com.edde746.tortuga.app.SystemInputs
import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.settings.DeviceFeatures
import com.edde746.tortuga.presentation.settings.deviceSettingsStore
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import com.edde746.tortuga.ui.adaptive.AdaptiveLayout
import com.edde746.tortuga.ui.theme.TortugaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Settings → This device over the app's own store and database, the store on a main thread of its own. */
@OptIn(ExperimentalTestApi::class)
class DeviceSettingsPaneTest {
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(main)
        // The system's settings, as a platform reports them before it builds a core.
        SystemInputs.report(listOf("en"), region = null, captions = false, audioDescriptions = false)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        main.close()
    }

    /**
     * While loudness normalization is on (the player's choice, kept for the next video), the Passthrough switch is
     * disabled, keeping its value, and its row says that passthrough is off meanwhile, in place of its hint; once
     * normalization is turned off the switch takes changes again and the hint is back.
     */
    @Test
    fun passthroughIsDisabledWhileLoudnessIsNormalized() = runComposeUiTest {
        AppBootstrap.local(DatabaseFactory.inMemory(), BuildFlavor.SIDELOADED, "test", DeviceDescriptor("Test", DevicePlatform.WINDOWS)) {}.use { app ->
            val settings = app.graph.data.deviceSettings
            runBlocking { settings.set(DeviceSetting.AudioNormalization, true) }
            val features = DeviceFeatures(hardwareDecoding = true, hdr = false, matchFrameRate = false, audioPassthrough = true)
            val store = app.graph.deviceSettingsStore(features, FormFactor.DESKTOP)
            setContent { TortugaTheme { AdaptiveLayout(FormFactor.DESKTOP) { DeviceSettingsRoute(store) } } }

            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(NOTE)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(HINT).assertDoesNotExist()
            onNode(isToggleable() and hasText(NOTE)).assertIsNotEnabled()

            runBlocking { settings.set(DeviceSetting.AudioNormalization, false) }
            waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(HINT)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(NOTE).assertDoesNotExist()
            onNode(isToggleable() and hasText(HINT)).assertIsEnabled()
            store.release()
        }
    }

    private companion object {
        const val NOTE = "Off while loudness normalization is on"
        const val HINT = "Send surround sound (Dolby Digital, DTS, TrueHD) to the receiver undecoded"
    }
}
