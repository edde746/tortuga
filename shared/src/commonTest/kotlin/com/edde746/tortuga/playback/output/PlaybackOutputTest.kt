@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.playback.output

import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.DynamicRange
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.VideoInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackOutputTest {
    private val modes = listOf(23.976025, 24.0, 50.0, 60.000004).mapIndexed { i, rate -> DisplayMode(i, 3840, 2160, rate) }

    /** A display that really switches: [display] reports the mode the last plan asked for, as a real one would. */
    private class SwitchingHost(private val modes: List<DisplayMode>) : PlaybackOutputHost {
        val plans = mutableListOf<OutputPlan>()
        var displayReads = 0
        var restores = 0
        private val own = modes.last()
        private var current = own

        override suspend fun display(): DisplayInfo {
            displayReads++
            return DisplayInfo(modes, current, hdrSupported = true, hdrEnabled = true)
        }

        override fun apply(plan: OutputPlan) {
            plans += plan
            current = plan.mode ?: own
        }

        override fun restore() {
            restores++
            current = own
        }
    }

    private class Fixture(scope: TestScope, modes: List<DisplayMode>, settings: DeviceSettings, formFactor: FormFactor) {
        val engine = MutableStateFlow(EngineState())
        val trailer = MutableStateFlow(false)
        val fullscreen = MutableStateFlow(false)
        val host = SwitchingHost(modes)
        val loaded = CompletableDeferred<DeviceSettings>()
        val output = PlaybackOutput(engine, trailer, fullscreen, formFactor, { loaded.await() }, host, scope.backgroundScope)

        init {
            loaded.complete(settings)
            scope.runCurrent()
        }

        fun video(fps: Double?, range: DynamicRange = DynamicRange.SDR, durationSeconds: Double = 0.0) = engine.update {
            it.copy(loaded = true, durationSeconds = durationSeconds, video = VideoInfo(3840, 2160, "hevc", fps, range))
        }

        fun nextFile() = engine.update { it.copy(loaded = false, video = null) }
    }

    private fun outputTest(
        settings: DeviceSettings = DeviceSettings.DEFAULT.copy(matchFrameRate = true),
        formFactor: FormFactor = FormFactor.TV,
        body: Fixture.(TestScope) -> Unit,
    ) = runTest { Fixture(this, modes, settings, formFactor).body(this) }

    @Test
    fun theSettingsGoOutFirstThenEachVideosPlan() = outputTest(DeviceSettings.DEFAULT.copy(matchFrameRate = true, audioPassthrough = true)) { scope ->
        val first = host.plans.single()
        assertNull(first.mode)
        assertNull(first.frameRate)
        assertTrue(first.audioPassthrough)
        assertTrue(first.hdr, "before the video, HDR output follows the setting and the display")
        assertFalse(first.enableHdr)

        video(23.976023976023978)
        scope.runCurrent()
        assertEquals(23.976025, host.plans.last().mode?.refreshRate)
        assertEquals(24000.0 / 1001, host.plans.last().frameRate)
    }

    @Test
    fun aNextFileAtTheSameRateChangesNothingAndAnotherRateIsDecidedAgainstTheDisplaysOwnMode() = outputTest { scope ->
        video(24.0)
        scope.runCurrent()
        assertEquals(24.0, host.plans.last().mode?.refreshRate)
        val count = host.plans.size

        nextFile()
        scope.runCurrent()
        video(24.0)
        scope.runCurrent()
        assertEquals(count, host.plans.size, "between files and for the same rate, the mode stays")

        // 30 fps: the display's own 60 Hz fits, although the playback left it at 24 Hz; the plan goes back to it.
        video(30.0)
        scope.runCurrent()
        assertNull(host.plans.last().mode)
        assertEquals(1, host.displayReads, "the display is read once, before anything switched it")

        video(30.0, DynamicRange.HDR10)
        scope.runCurrent()
        assertTrue(host.plans.last().hdr, "a change of dynamic range is a new plan")
    }

    @Test
    fun aTrailerOrAShortVideoAsksForASeamlessSwitchOnly() = outputTest(DeviceSettings.DEFAULT) { scope ->
        video(24.0, durationSeconds = 5400.0)
        scope.runCurrent()
        assertEquals(24.0, host.plans.last().frameRate, "the rate goes out with the setting off")
        assertFalse(host.plans.last().seamlessOnly, "a movie may switch as the system allows")
        assertNull(host.plans.last().mode)

        nextFile()
        trailer.value = true
        video(24.0)
        scope.runCurrent()
        assertTrue(host.plans.last().seamlessOnly, "a trailer, its duration not reported yet")

        nextFile()
        trailer.value = false
        video(24.0)
        scope.runCurrent()
        assertFalse(host.plans.last().seamlessOnly, "a video whose duration isn't known yet")
        video(24.0, durationSeconds = 150.0)
        scope.runCurrent()
        assertTrue(host.plans.last().seamlessOnly, "its duration turns out short: a plan follows")
    }

    @Test
    fun releaseRestoresTheDisplay() = outputTest { scope ->
        video(24.0)
        scope.runCurrent()
        output.release()
        output.release()
        assertEquals(1, host.restores)

        video(50.0)
        scope.runCurrent()
        assertEquals(24.0, host.plans.last().mode?.refreshRate, "nothing applies after the release")
    }

    @Test
    fun aDesktopSwitchesTheModeOnlyWhileFullscreenAndKeepsHdrForThePlayback() = outputTest(formFactor = FormFactor.DESKTOP) { scope ->
        video(24.0, DynamicRange.HDR10)
        scope.runCurrent()
        assertNull(host.plans.last().mode, "windowed, the display keeps its mode")
        assertTrue(host.plans.last().hdr)

        fullscreen.value = true
        scope.runCurrent()
        assertEquals(24.0, host.plans.last().mode?.refreshRate)

        fullscreen.value = false
        scope.runCurrent()
        assertNull(host.plans.last().mode, "leaving fullscreen puts the display's own mode back")
        assertTrue(host.plans.last().hdr, "HDR stays for the playback")
        assertEquals(0, host.restores, "the playback goes on")
        assertEquals(1, host.displayReads)
    }

    @Test
    fun aDisplayThatCantBeReadLeavesTheOutputAlone() = runTest {
        val host = object : PlaybackOutputHost {
            var touched = false

            override suspend fun display(): DisplayInfo = throw IllegalStateException("the display executor timed out")

            override fun apply(plan: OutputPlan) { touched = true }

            override fun restore() { touched = true }
        }
        val engine = MutableStateFlow(EngineState())
        val output = PlaybackOutput(engine, MutableStateFlow(false), MutableStateFlow(true), FormFactor.DESKTOP, { DeviceSettings.DEFAULT }, host, backgroundScope)
        runCurrent()
        engine.value = EngineState(loaded = true, video = VideoInfo(3840, 2160, "hevc", 24.0, DynamicRange.HDR10))
        runCurrent()
        output.release()
        assertFalse(host.touched, "no plan goes out and nothing is restored")
    }

    @Test
    fun aReleaseWhileTheDisplayIsStillBeingReadAppliesNothing() = runTest {
        val readable = CompletableDeferred<Unit>()
        val host = object : PlaybackOutputHost {
            val plans = mutableListOf<OutputPlan>()
            var restores = 0

            override suspend fun display(): DisplayInfo {
                readable.await()
                return DisplayInfo(modes, modes.last(), hdrSupported = true, hdrEnabled = false)
            }

            override fun apply(plan: OutputPlan) { plans += plan }

            override fun restore() { restores++ }
        }
        val output = PlaybackOutput(MutableStateFlow(EngineState()), MutableStateFlow(false), MutableStateFlow(false), FormFactor.TV, { DeviceSettings.DEFAULT }, host, backgroundScope)
        runCurrent()
        assertTrue(host.plans.isEmpty(), "no plan before the display is read")

        output.release()
        readable.complete(Unit)
        runCurrent()
        assertTrue(host.plans.isEmpty(), "the released playback's read applies nothing")
        assertEquals(0, host.restores, "nothing was switched, so nothing is restored")
    }
}
