@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.format.formatByteSize
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSettingsStoreTest {
    private val everything = DeviceFeatures(hardwareDecoding = true, hdr = true, matchFrameRate = true, audioPassthrough = true)

    private class Fixture(scope: TestScope, features: DeviceFeatures) {
        val data = LocalData(
            inMemoryDatabase(), { 1_790_000_000_000L },
            StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        )
        val store = DeviceSettingsStore(data.deviceSettings, features, FormFactor.HANDHELD, scope.backgroundScope)
        val state get() = store.state.value
    }

    private fun TestScope.fixture(features: DeviceFeatures = everything) = Fixture(this, features).also { runCurrent() }

    private fun megabytes(value: Int) = formatByteSize(value.toLong() * 1024 * 1024)

    @Test
    fun theCacheSizeIsAutomaticOrAPreset() = runTest {
        val f = fixture()
        val cache = f.state.cacheSize
        assertEquals(T.settings.automatic, cache.valueLabel)
        assertEquals(
            listOf(T.settings.automatic) + listOf(64, 128, 256, 512, 1024, 2048, 4096).map(::megabytes),
            cache.options.map { it.label },
        )
        assertEquals(listOf("auto"), cache.options.filter { it.selected }.map { it.id })

        f.store.choose(cache.id, cache.options[4].id)
        runCurrent()
        assertEquals(512, f.data.deviceSettings.settings().cacheSizeMegabytes)
        assertEquals(megabytes(512), f.state.cacheSize.valueLabel)
        assertEquals(listOf(4), f.state.cacheSize.options.withIndex().filter { it.value.selected }.map { it.index })

        // Only the offered sizes are taken.
        f.store.choose("cacheSize", "300")
        f.store.choose("cacheSize", "lots")
        runCurrent()
        assertEquals(512, f.data.deviceSettings.settings().cacheSizeMegabytes)

        f.store.choose("cacheSize", "auto")
        runCurrent()
        assertNull(f.data.deviceSettings.settings().cacheSizeMegabytes)
        assertEquals(T.settings.automatic, f.state.cacheSize.valueLabel)
        f.data.close()
    }

    @Test
    fun aStoredSizeNoPresetHasIsListedSelected() = runTest {
        val f = fixture()
        // Typed in an earlier version, or a development session's --dev-setting.
        f.data.deviceSettings.set(DeviceSetting.CacheSizeMegabytes, 300)
        runCurrent()
        val cache = f.state.cacheSize
        assertEquals(megabytes(300), cache.valueLabel)
        assertEquals(listOf("auto", "64", "128", "256", "300", "512", "1024", "2048", "4096"), cache.options.map { it.id })
        assertEquals(listOf("300"), cache.options.filter { it.selected }.map { it.id })
        // Picking a preset drops it from the list.
        f.store.choose("cacheSize", "256")
        runCurrent()
        assertEquals(256, f.data.deviceSettings.settings().cacheSizeMegabytes)
        assertFalse(f.state.cacheSize.options.any { it.id == "300" })
        f.data.close()
    }

    @Test
    fun rowsTheDeviceCannotUseAreLeftOutAndSwitchesTakeEffectAtOnce() = runTest {
        val f = fixture(everything.copy(hdr = false, matchFrameRate = false, audioPassthrough = false))
        assertNull(f.state.hdr)
        assertNull(f.state.matchFrameRate)
        assertNull(f.state.audioPassthrough)
        assertNull(f.state.audioHeader)
        assertNotNull(f.state.videoHeader)

        assertTrue(f.state.hardwareDecoding!!.checked)
        f.store.toggle(f.state.hardwareDecoding!!.id, false)
        runCurrent()
        assertFalse(f.data.deviceSettings.settings().hardwareDecoding)
        assertFalse(f.state.hardwareDecoding!!.checked)
        f.data.close()
    }

    /** Passthrough's hint says where this platform sends the audio; while normalizing, the note that it is off instead. */
    @Test
    fun thePassthroughHintFollowsThePlatformsTargetUntilNormalizationTurnsItOff() = runTest {
        val text = T.settings.device
        val hints = PassthroughTarget.entries.associateWith { target ->
            val f = fixture(everything.copy(passthroughTarget = target))
            f.state.audioPassthrough?.subtitle.also { f.data.close() }
        }
        assertEquals(
            mapOf(
                PassthroughTarget.RECEIVER to text.audioPassthroughHint,
                PassthroughTarget.SYSTEM_DECODER to text.audioPassthroughHintApple,
                PassthroughTarget.SYSTEM_SPATIAL to text.audioPassthroughHintAppleSpatial,
            ),
            hints,
        )

        val f = fixture(everything.copy(passthroughTarget = PassthroughTarget.SYSTEM_SPATIAL))
        f.data.deviceSettings.set(DeviceSetting.AudioNormalization, true)
        runCurrent()
        val row = assertNotNull(f.state.audioPassthrough)
        assertEquals(text.audioPassthroughNormalized, row.subtitle)
        assertFalse(row.enabled)
        f.data.close()
    }

    /**
     * Discord's switch only where the platform speaks to a Discord application (a build or session with one): without
     * it neither the group nor the switch; with it the switch is off until switched on, and the store keeps it.
     */
    @Test
    fun discordsSwitchIsOfferedOnlyWithADiscordApplicationAndOffUntilSwitchedOn() = runTest {
        val without = fixture()
        assertNull(without.state.discordHeader)
        assertNull(without.state.discordPresence)
        without.data.close()

        val f = fixture(everything.copy(discordPresence = true))
        assertEquals(T.settings.device.discordHeader, f.state.discordHeader)
        assertFalse(f.state.discordPresence!!.checked)
        f.store.toggle("discordPresence", true)
        runCurrent()
        assertTrue(f.data.deviceSettings.settings().discordPresence)
        assertTrue(f.state.discordPresence!!.checked)
        f.data.close()
    }
}
