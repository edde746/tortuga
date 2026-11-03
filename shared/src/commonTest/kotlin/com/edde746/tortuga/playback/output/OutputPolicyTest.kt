package com.edde746.tortuga.playback.output

import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.DynamicRange
import com.edde746.tortuga.playback.VideoInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutputPolicyTest {
    /** An Android TV box on a 4K TV, rates as `Display.Mode.getRefreshRate` reports them. */
    private val tvModes = listOf(23.976025, 24.0, 50.0, 59.94006, 60.000004).flatMapIndexed { i, rate ->
        listOf(DisplayMode(10 + i, 3840, 2160, rate), DisplayMode(20 + i, 1920, 1080, rate))
    }

    /**
     * The Windows test host's 4K monitor (Dell AW2725Q) at 3840×2160 and its own 239.991 Hz, as the glue enumerates it
     * there: ids are GDI's whole-hertz frequencies, which it reads as NTSC rates where GDI rounded one down (23 as
     * 24000/1001), and the current mode's exact rate comes from DisplayConfig.
     */
    private val monitorModes = listOf(
        23 to 24000.0 / 1001, 24 to 24.0, 25 to 25.0, 29 to 30000.0 / 1001, 30 to 30.0, 50 to 50.0, 59 to 60000.0 / 1001,
        60 to 60.0, 119 to 120000.0 / 1001, 120 to 120.0, 144 to 144.0, 239 to 239.991,
    ).map { (frequency, rate) -> DisplayMode(frequency, 3840, 2160, rate) }

    /** A Pixel 7 panel. */
    private val phoneModes = listOf(DisplayMode(1, 1080, 2400, 60.000004), DisplayMode(2, 1080, 2400, 90.00001))

    private val ntsc24 = 24000.0 / 1001
    private val ntsc120 = 120000.0 / 1001

    private fun display(modes: List<DisplayMode>, currentRate: Double, hdrSupported: Boolean = false, hdrEnabled: Boolean = false) =
        DisplayInfo(modes, modes.first { it.refreshRate == currentRate }, hdrSupported, hdrEnabled)

    private fun pick(fps: Double, display: DisplayInfo, formFactor: FormFactor = FormFactor.TV): Double? =
        OutputPolicy.pickMode(OutputPolicy.contentRate(fps)!!, display, formFactor)?.refreshRate

    private fun video(fps: Double?, range: DynamicRange = DynamicRange.SDR) = VideoInfo(3840, 2160, "hevc", fps, range)

    private val settings = DeviceSettings.DEFAULT.copy(matchFrameRate = true)

    @Test
    fun declaredRatesSnapToTheStandardRateTheyStandFor() {
        assertEquals(ntsc24, OutputPolicy.contentRate(23.976023976023978))
        assertEquals(ntsc24, OutputPolicy.contentRate(23.976))
        assertEquals(ntsc24, OutputPolicy.contentRate(23.98))
        assertEquals(ntsc24, OutputPolicy.contentRate(1000.0 / 42), "Matroska's 42 ms default duration")
        assertEquals(24.0, OutputPolicy.contentRate(24.0))
        assertEquals(24.0, OutputPolicy.contentRate(24.004))
        assertEquals(25.0, OutputPolicy.contentRate(25.0))
        assertEquals(30000.0 / 1001, OutputPolicy.contentRate(29.97))
        assertEquals(60000.0 / 1001, OutputPolicy.contentRate(59.94))
        assertEquals(12.5, OutputPolicy.contentRate(12.5), "a rate near no standard one stays")
        assertEquals(1000.0 / 33, OutputPolicy.contentRate(1000.0 / 33), "30.3 is more than 1 % from 30 and 29.97")
        assertNull(OutputPolicy.contentRate(1.0), "a cover image's rate is no video rate")
        assertNull(OutputPolicy.contentRate(90_000.0))
        assertNull(OutputPolicy.contentRate(Double.NaN))
    }

    @Test
    fun aTvSwitchesToTheExactRateAndTellsTwentyFourFromTwentyThreeNineSevenSix() {
        val tv = display(tvModes, 60.000004)
        val film = OutputPolicy.pickMode(ntsc24, tv, FormFactor.TV)!!
        assertEquals(23.976025, film.refreshRate)
        assertEquals(3840 to 2160, film.width to film.height, "the resolution stays")
        assertEquals(24.0, pick(24.0, tv))
        assertEquals(59.94006, pick(59.94, tv))
        assertEquals(50.0, pick(50.0, tv))
    }

    @Test
    fun withoutAnExactRateATvTakesAMultiple() {
        val tv = display(tvModes, 60.000004)
        assertEquals(50.0, pick(25.0, tv), "25 fps doubles to 50 Hz")
        assertEquals(59.94006, pick(29.97, tv), "29.97 fps doubles to 59.94 Hz, not 60")
        assertNull(pick(30.0, tv), "60 Hz already shows 30 fps evenly")
        assertNull(pick(12.0, tv), "nor does 12 fps need a switch at 60 Hz")
        assertEquals(24.0, pick(12.0, display(tvModes, 50.0)), "at 50 Hz, 12 fps goes to the smallest multiple on offer")
    }

    @Test
    fun aTvKeepsItsModeWhenItAlreadyFits() {
        val tv = display(tvModes, 23.976025)
        assertNull(pick(23.976, tv))
        assertNull(pick(47.952, tv), "only the exact mode or a multiple; 47.952 fps is neither at 23.976 Hz and has none")
    }

    @Test
    fun aMonitorTakesTheFastestMultipleOnOffer() {
        val monitor = display(monitorModes, 239.991)
        val film = OutputPolicy.pickMode(ntsc24, monitor, FormFactor.DESKTOP)!!
        assertEquals(119, film.id, "5 × 23.976: faster than the exact 23.976 Hz; 239.991 Hz is 0.1 % off 10 × 23.976")
        assertEquals(ntsc120, film.refreshRate)
        assertEquals(ntsc120, pick(29.97, monitor, FormFactor.DESKTOP))
        assertEquals(ntsc120, pick(59.94, monitor, FormFactor.DESKTOP))
        assertEquals(50.0, pick(25.0, monitor, FormFactor.DESKTOP), "2 × 25; nothing faster is a multiple")
        assertEquals(50.0, pick(50.0, monitor, FormFactor.DESKTOP))
        assertNull(pick(24.0, monitor, FormFactor.DESKTOP), "239.991 Hz is 10 × 24 within the tolerance, the fastest already")
        assertNull(pick(30.0, monitor, FormFactor.DESKTOP), "and 8 × 30")
        assertNull(pick(48.0, monitor, FormFactor.DESKTOP), "and 5 × 48")
        assertNull(pick(60.0, monitor, FormFactor.DESKTOP))
        assertNull(pick(1000.0 / 33, monitor, FormFactor.DESKTOP), "nothing is a multiple of 30.3")

        // The same list on a TV: the exact rate first.
        assertEquals(ntsc24, pick(23.976, monitor, FormFactor.TV))
        assertEquals(24.0, pick(24.0, monitor, FormFactor.TV))
    }

    @Test
    fun aMacTakesItsDisplaysFastestRateVariantThatShowsTheVideoEvenly() {
        // A MacBook Pro's built-in display: ProMotion (120 Hz) and its fixed-rate variants at the same size, rates as
        // CoreGraphics reports them. ProMotion can't hold 23.976 fps evenly, so film lands on 47.95 Hz.
        val macBookPro = display(
            listOf(120.0, 60.0, 59.93998718261719, 50.0, 48.0, 47.94999694824219).mapIndexed { i, rate -> DisplayMode(i + 1, 3024, 1964, rate) },
            120.0,
        )
        assertEquals(47.94999694824219, pick(23.976, macBookPro, FormFactor.DESKTOP), "2 × 23.976, not 48 Hz")
        assertEquals(50.0, pick(25.0, macBookPro, FormFactor.DESKTOP))
        assertEquals(59.93998718261719, pick(29.97, macBookPro, FormFactor.DESKTOP), "2 × 29.97, not 60 Hz")
        assertEquals(50.0, pick(50.0, macBookPro, FormFactor.DESKTOP))
        assertEquals(59.93998718261719, pick(59.94, macBookPro, FormFactor.DESKTOP))
        assertNull(pick(24.0, macBookPro, FormFactor.DESKTOP), "ProMotion's 120 Hz is 5 × 24, the fastest already")
        assertNull(pick(60.0, macBookPro, FormFactor.DESKTOP), "and 2 × 60")
        assertNull(pick(1000.0 / 33, macBookPro, FormFactor.DESKTOP), "nothing is a multiple of 30.3")

        // An external 4K display at 60 Hz with the TV rates its EDID offers: no 47.95 Hz, so film goes to 23.976 Hz.
        val external = display(
            listOf(60.0, 59.93998718261719, 50.0, 30.0, 29.97002997002997, 25.0, 24.0, 23.976023976023978)
                .mapIndexed { i, rate -> DisplayMode(i + 1, 3840, 2160, rate) },
            60.0,
        )
        assertEquals(23.976023976023978, pick(23.976, external, FormFactor.DESKTOP))
        assertEquals(50.0, pick(25.0, external, FormFactor.DESKTOP))
        assertEquals(59.93998718261719, pick(29.97, external, FormFactor.DESKTOP), "2 × 29.97 over 29.97 Hz itself")
        assertEquals(50.0, pick(50.0, external, FormFactor.DESKTOP))
        assertEquals(59.93998718261719, pick(59.94, external, FormFactor.DESKTOP))
        assertNull(pick(1000.0 / 33, external, FormFactor.DESKTOP), "nothing is a multiple of 30.3")
    }

    @Test
    fun aDesktopSwitchesOnlyWhileFullscreenAndHdrEitherWay() {
        val monitor = display(monitorModes, 239.991, hdrSupported = true, hdrEnabled = false)
        val windowed = OutputPolicy.plan(video(23.976, DynamicRange.HDR10), monitor, settings, FormFactor.DESKTOP, fullscreen = false, short = false)
        assertNull(windowed.mode, "a window shares the monitor: its mode stays")
        assertEquals(ntsc24, windowed.frameRate)
        assertTrue(windowed.enableHdr, "HDR goes on in a window too")

        val fullscreen = OutputPolicy.plan(video(23.976, DynamicRange.HDR10), monitor, settings, FormFactor.DESKTOP, fullscreen = true, short = false)
        assertEquals(ntsc120, fullscreen.mode?.refreshRate)
        assertEquals(windowed, fullscreen.copy(mode = null), "fullscreen changes the mode only")

        val tv = display(tvModes, 60.000004)
        assertEquals(23.976025, OutputPolicy.plan(video(23.976), tv, settings, FormFactor.TV, fullscreen = false, short = false).mode?.refreshRate)
    }

    @Test
    fun aHandheldLeavesTheModeToTheSystem() {
        val phone = display(phoneModes, 60.000004)
        val plan = OutputPolicy.plan(video(45.0), phone, settings, FormFactor.HANDHELD, fullscreen = true, short = false)
        assertNull(plan.mode, "90 Hz would show 45 fps evenly, but the system picks it from the Surface's vote")
        assertEquals(45.0, plan.frameRate)
        assertNull(pick(45.0, phone, FormFactor.HANDHELD))
    }

    @Test
    fun theRateGoesOutWhateverTheSettingAndOnlyTheSettingForcesASwitch() {
        val tv = display(tvModes, 60.000004)
        val monitor = display(monitorModes, 239.991)
        val phone = display(phoneModes, 60.000004)
        fun plan(display: DisplayInfo, formFactor: FormFactor, settings: DeviceSettings) =
            OutputPolicy.plan(video(23.976), display, settings, formFactor, fullscreen = true, short = false)

        for ((display, formFactor) in listOf(tv to FormFactor.TV, monitor to FormFactor.DESKTOP, phone to FormFactor.HANDHELD)) {
            val off = plan(display, formFactor, DeviceSettings.DEFAULT)
            assertEquals(ntsc24, off.frameRate, "$formFactor: the system hears the video's rate with the setting off")
            assertFalse(off.seamlessOnly, "$formFactor: a long video may switch as the system's own setting allows")
            assertNull(off.mode, "$formFactor: nothing is forced with the setting off")
            assertEquals(off.copy(mode = null), plan(display, formFactor, settings).copy(mode = null), "$formFactor: the setting changes the forced mode only")
        }
        assertEquals(23.976025, plan(tv, FormFactor.TV, settings).mode?.refreshRate, "a TV's mode is forced with the setting on")
        assertEquals(ntsc120, plan(monitor, FormFactor.DESKTOP, settings).mode?.refreshRate, "so is a fullscreen monitor's")
        assertNull(plan(phone, FormFactor.HANDHELD, settings).mode, "a handheld has no mode to force")
    }

    @Test
    fun aShortVideoAsksTheSystemForASeamlessSwitchOnly() {
        val tv = display(tvModes, 60.000004)
        val trailer = OutputPolicy.plan(video(23.976), tv, settings, FormFactor.TV, fullscreen = true, short = true)
        assertTrue(trailer.seamlessOnly)
        assertEquals(ntsc24, trailer.frameRate, "its rate still goes out")
        assertEquals(23.976025, trailer.mode?.refreshRate, "a forced switch is forced for it too")

        assertTrue(OutputPolicy.isShort(trailer = true, durationSeconds = 0.0), "a trailer, before its duration is known")
        assertTrue(OutputPolicy.isShort(trailer = true, durationSeconds = 7200.0))
        assertTrue(OutputPolicy.isShort(trailer = false, durationSeconds = 150.0))
        assertTrue(OutputPolicy.isShort(trailer = false, durationSeconds = OutputPolicy.SHORT_VIDEO_SECONDS))
        assertFalse(OutputPolicy.isShort(trailer = false, durationSeconds = OutputPolicy.SHORT_VIDEO_SECONDS + 1))
        assertFalse(OutputPolicy.isShort(trailer = false, durationSeconds = 0.0), "an unknown duration (a live stream) is long")
    }

    @Test
    fun noModeWithoutACurrentModeOrARate() {
        val tv = display(tvModes, 60.000004)
        fun plan(video: VideoInfo?, display: DisplayInfo?) = OutputPolicy.plan(video, display, settings, FormFactor.TV, fullscreen = true, short = false)

        assertNull(plan(video(23.976), tv.copy(current = null)).mode)
        assertNull(plan(video(null), tv).mode)
        assertNull(plan(video(null), tv).frameRate)
        assertNull(plan(null, null).frameRate)
    }

    @Test
    fun hdrVideoShowsAsHdrWhenTheSettingAndTheDisplayAllow() {
        val off = display(monitorModes, 239.991, hdrSupported = true, hdrEnabled = false)
        fun plan(video: VideoInfo?, display: DisplayInfo, settings: DeviceSettings) =
            OutputPolicy.plan(video, display, settings, FormFactor.DESKTOP, fullscreen = false, short = false)

        for (range in listOf(DynamicRange.HDR10, DynamicRange.HLG, DynamicRange.DOLBY_VISION)) {
            val plan = plan(video(23.976, range), off, settings)
            assertTrue(plan.hdr, "$range")
            assertTrue(plan.enableHdr, "$range turns the display's HDR on")
        }
        val on = plan(video(23.976, DynamicRange.HDR10), off.copy(hdrEnabled = true), settings)
        assertTrue(on.hdr)
        assertFalse(on.enableHdr, "already on")

        val sdr = plan(video(23.976), off, settings)
        assertFalse(sdr.hdr)
        assertFalse(sdr.enableHdr)

        val settingOff = plan(video(23.976, DynamicRange.HDR10), off, settings.copy(hdr = false))
        assertFalse(settingOff.hdr, "tone-mapped to SDR")
        assertFalse(settingOff.enableHdr)

        val sdrDisplay = plan(video(23.976, DynamicRange.HDR10), off.copy(hdrSupported = false), settings)
        assertFalse(sdrDisplay.hdr)
        assertFalse(sdrDisplay.enableHdr)

        // Before the video: HDR output as the setting and the display allow, but nothing switches on yet.
        val before = plan(null, off, settings)
        assertTrue(before.hdr)
        assertFalse(before.enableHdr)
        assertFalse(plan(null, off, settings.copy(hdr = false)).hdr)
        assertFalse(plan(null, off.copy(hdrSupported = false), settings).hdr)
    }

    @Test
    fun theSettingOffMeansSdrOutputEvenOnADisplayInHdr() {
        val hdrOn = display(monitorModes, 239.991, hdrSupported = true, hdrEnabled = true)
        fun plan(video: VideoInfo?, display: DisplayInfo, settings: DeviceSettings) =
            OutputPolicy.plan(video, display, settings, FormFactor.DESKTOP, fullscreen = true, short = false)

        val settingOff = plan(video(23.976, DynamicRange.HDR10), hdrOn, settings.copy(hdr = false))
        assertFalse(settingOff.hdrOutput, "tone-mapped to SDR, not passed through")
        assertFalse(plan(null, hdrOn, settings.copy(hdr = false)).hdrOutput)
        // Setting on: the engine presents for the display, an SDR video too (hdr is about the video, hdrOutput isn't).
        val sdrVideo = plan(video(23.976), hdrOn, settings)
        assertFalse(sdrVideo.hdr)
        assertTrue(sdrVideo.hdrOutput)
        assertTrue(plan(video(23.976, DynamicRange.HDR10), hdrOn, settings).hdrOutput)
        assertFalse(plan(video(23.976, DynamicRange.HDR10), hdrOn.copy(hdrSupported = false), settings).hdrOutput)
    }

    @Test
    fun passthroughFollowsTheSetting() {
        assertFalse(OutputPolicy.plan(null, null, DeviceSettings.DEFAULT.copy(audioPassthrough = false), FormFactor.TV, fullscreen = true, short = false).audioPassthrough)
        assertTrue(OutputPolicy.plan(null, null, DeviceSettings.DEFAULT.copy(audioPassthrough = true), FormFactor.TV, fullscreen = true, short = false).audioPassthrough)
    }
}
