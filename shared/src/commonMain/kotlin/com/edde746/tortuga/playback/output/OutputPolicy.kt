package com.edde746.tortuga.playback.output

import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.DynamicRange
import com.edde746.tortuga.playback.VideoInfo
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One mode of a display.
 * @param id the platform's own handle for it (Android's `Display.Mode.modeId`; whatever the desktop glue indexes its
 *   list by), handed back in [OutputPlan.mode].
 * @param refreshRate in Hz, as exact as the platform knows it (59.94 as 60000/1001, not 59).
 */
data class DisplayMode(val id: Int, val width: Int, val height: Int, val refreshRate: Double)

/**
 * The display the player shows on, as the platform detects it (capabilities are reported to shared code).
 * @param modes every mode it offers; empty where the platform can't switch modes.
 * @param current the mode it shows now; null when unknown, and then no mode is picked.
 * @param hdrSupported it can show HDR video.
 * @param hdrEnabled its HDR output is on now. Where HDR has no switch (Android turns it on per surface), the same as
 *   [hdrSupported].
 */
data class DisplayInfo(
    val modes: List<DisplayMode>,
    val current: DisplayMode?,
    val hdrSupported: Boolean,
    val hdrEnabled: Boolean,
)

/**
 * How the platform outputs the current video (shared code decides, the platform switches). Every plan is
 * complete: a null [mode] means the display's own mode from before the playback, and [enableHdr] false its own HDR
 * state, so the platform undoes whatever an earlier plan of the playback changed and touches nothing else.
 *
 * @param frameRate the video's frame rate, snapped to the standard rate it stands for (24000/1001 for a container's
 *   23.976); null before the video is known or when it declares none. A platform that can tell the system the rate
 *   does so with every plan, whatever the settings (Android's `Surface.setFrameRate`, tvOS's `AVDisplayCriteria`):
 *   the system's own setting (Android's "Match content frame rate", tvOS's Match Content) decides whether the display
 *   switches.
 * @param seamlessOnly the video is short ([OutputPolicy.isShort]: a trailer, or a few minutes long): the system may
 *   switch the display for [frameRate] only where that doesn't interrupt the picture (Android's
 *   `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS`). False for anything longer (a movie, an episode, a live stream), which may
 *   switch even where the screen goes blank for a moment, as the system's setting allows (`CHANGE_FRAME_RATE_ALWAYS`).
 * @param mode the mode the app switches the display to itself, whatever the system's settings (a forced switch: an
 *   Android TV window's preferred mode, a Windows monitor's or a Mac display's mode); null for the display's own mode.
 *   Only with the MatchFrameRate setting on, and on a desktop only while the player is fullscreen.
 * @param hdr HDR output for the playback: the Hdr setting is on, the display supports HDR and the video is HDR, or not
 *   known yet (the plan before the first video). False for an HDR video means it is tone-mapped to SDR; false before
 *   the video means an HDR video will be.
 * @param hdrOutput the playback may present HDR at all: the Hdr setting is on and the display supports HDR, whatever
 *   the video. Off, everything goes out as SDR, an HDR video tone-mapped, even where the display's HDR is on (the
 *   engine must not pass HDR through then); on, the engine presents as it does for the display's state (an SDR video
 *   on a display in HDR mode may go out in its HDR format).
 * @param enableHdr turn the display's HDR output on for the playback: an HDR video with [hdr], while the display's
 *   HDR is off. Never before the video is known.
 * @param audioPassthrough the AudioPassthrough setting, or its default for the form factor
 *   ([DeviceSettings.audioPassthroughOn]): send the compressed formats the audio route decodes itself (the platform
 *   detects which) without decoding them.
 */
data class OutputPlan(
    val frameRate: Double?,
    val seamlessOnly: Boolean,
    val mode: DisplayMode?,
    val hdr: Boolean,
    val hdrOutput: Boolean,
    val enableHdr: Boolean,
    val audioPassthrough: Boolean,
)

/**
 * Picks the display mode and HDR state for a video.
 *
 * Frame rate: the video's rate goes out in every plan ([OutputPlan.frameRate]) for the system to follow by its own
 * setting, interrupting the picture only for a video longer than [isShort]'s.
 *
 * Mode, the forced switch: only with the MatchFrameRate setting on, only among the modes of the current resolution,
 * and only where the app can switch the mode itself: on a TV, and on a desktop while the player is fullscreen (a
 * window shares the monitor with everything else). A handheld leaves the mode to the system, which follows the
 * frame rate. The video's rate is first snapped to the standard rate it stands for ([contentRate]); then
 * - on a TV, a mode whose refresh rate equals it wins, else the current mode when it is an integer multiple of it, else
 *   the smallest integer multiple on offer;
 * - on a desktop monitor, the fastest mode whose refresh rate is an integer multiple of it (the rate itself included):
 *   119.88 Hz rather than 23.976 Hz for 23.976 fps, since every multiple shows each frame for the same time and the
 *   faster mode keeps the pointer and the controls smooth. The current mode stays when it is as fast a multiple.
 *
 * Rates match within [MODE_TOLERANCE], which keeps 23.976 and 24 (0.1 % apart) apart: 23.976 fps finds no match on a
 * 239.991 Hz monitor (10 × 23.976 is 239.76), 24 fps does. Nothing matching means no switch.
 *
 * HDR, on every device and windowed or fullscreen: an HDR video (HDR10, HLG, Dolby Vision) shows as HDR when the Hdr
 * setting is on and the display supports it, turning the display's HDR on where it is off. Before the video is known,
 * HDR output follows the setting and the display (so a platform that starts the surface in HDR doesn't flip it once
 * the video arrives) but nothing turns on.
 * With the setting off (or an SDR display) nothing presents HDR ([OutputPlan.hdrOutput]): HDR video is tone-mapped to
 * SDR even on a display whose HDR is already on.
 */
object OutputPolicy {
    /** How far (relative) a refresh rate may be from a multiple of the frame rate and still count as one. */
    const val MODE_TOLERANCE = 0.0003

    /**
     * How far (relative) a declared frame rate may be from a standard rate and still snap to it: 1 %, so a Matroska
     * file muxed with a 42 ms default duration (23.8095 fps declared for 23.976 content) snaps to 24000/1001.
     */
    const val SNAP_TOLERANCE = 0.01

    /** The frame rates a video can have; a declared rate outside (a cover image's, a timebase) counts as none. */
    const val MIN_FRAME_RATE = 10.0
    const val MAX_FRAME_RATE = 125.0

    /**
     * The longest video that is short ([isShort]): Android's frame-rate guide strongly recommends against a switch
     * that blanks the screen for a video expected to play several minutes or less.
     */
    const val SHORT_VIDEO_SECONDS = 10 * 60.0

    /**
     * The plan for [video] (null before it is known) on [display] (null when unknown) of a [formFactor] device, whose
     * player is [fullscreen] or not (only a desktop's is ever windowed); [short] per [isShort].
     */
    fun plan(video: VideoInfo?, display: DisplayInfo?, settings: DeviceSettings, formFactor: FormFactor, fullscreen: Boolean, short: Boolean): OutputPlan {
        val rate = video?.fps?.let(::contentRate)
        val videoHdr = video != null && video.dynamicRange != DynamicRange.SDR
        val hdrOutput = settings.hdr && display?.hdrSupported == true
        val hdr = hdrOutput && (video == null || videoHdr)
        val forced = settings.matchFrameRate && (formFactor != FormFactor.DESKTOP || fullscreen)
        return OutputPlan(
            frameRate = rate,
            seamlessOnly = short,
            mode = if (forced && rate != null && display != null) pickMode(rate, display, formFactor) else null,
            hdr = hdr,
            hdrOutput = hdrOutput,
            enableHdr = hdr && videoHdr && !display.hdrEnabled,
            audioPassthrough = settings.audioPassthroughOn(formFactor),
        )
    }

    /**
     * A video is short when it is a [trailer], or its duration is known ([durationSeconds] above 0) and at most
     * [SHORT_VIDEO_SECONDS]. An unknown duration (not reported yet, a live stream) isn't short.
     */
    fun isShort(trailer: Boolean, durationSeconds: Double): Boolean =
        trailer || (durationSeconds > 0 && durationSeconds <= SHORT_VIDEO_SECONDS)

    /** [refreshRate] shows a video at [rate] evenly: it is an integer multiple of it (the rate itself included). */
    fun fits(refreshRate: Double, rate: Double): Boolean = multiple(refreshRate, rate) != null

    /**
     * [fps] snapped to the standard rate it stands for: the nearer of the integer rate and the NTSC rate
     * (n × 1000/1001) when within [SNAP_TOLERANCE], so a container's 23.976, 23.98 or 23.8095 becomes 24000/1001 and
     * 25.0 stays 25. Other rates are kept as they are; null for none, or one outside [MIN_FRAME_RATE]–[MAX_FRAME_RATE].
     */
    fun contentRate(fps: Double): Double? {
        if (!fps.isFinite() || fps < MIN_FRAME_RATE || fps > MAX_FRAME_RATE) return null
        val integer = fps.roundToInt().toDouble()
        val ntsc = (fps * 1001 / 1000).roundToInt() * 1000.0 / 1001
        val nearest = if (abs(fps - ntsc) < abs(fps - integer)) ntsc else integer
        return if (abs(fps - nearest) <= nearest * SNAP_TOLERANCE) nearest else fps
    }

    /**
     * The mode to switch to for a video at [rate] (already snapped) on a [formFactor] device, or null to stay: the
     * current mode already fits, or nothing fits, or the device is a handheld.
     */
    fun pickMode(rate: Double, display: DisplayInfo, formFactor: FormFactor): DisplayMode? {
        val current = display.current ?: return null
        val sameSize = display.modes.filter { it.width == current.width && it.height == current.height }
        return when (formFactor) {
            FormFactor.TV -> exactFirst(rate, current, sameSize)
            FormFactor.DESKTOP -> fastestMultiple(rate, current, sameSize)
            FormFactor.HANDHELD -> null
        }
    }

    /** A TV's pick: the exact rate, else the current mode as a multiple, else the smallest multiple on offer. */
    private fun exactFirst(rate: Double, current: DisplayMode, modes: List<DisplayMode>): DisplayMode? {
        if (multiple(current.refreshRate, rate) == 1) return null
        modes.filter { multiple(it.refreshRate, rate) == 1 }.bestFor(rate)?.let { return it }
        if (multiple(current.refreshRate, rate) != null) return null
        return multiples(rate, modes).minByOrNull { it.key }?.value?.bestFor(rate)
    }

    /** A monitor's pick: the fastest multiple on offer, unless the current mode is as fast a multiple. */
    private fun fastestMultiple(rate: Double, current: DisplayMode, modes: List<DisplayMode>): DisplayMode? {
        val (k, fastest) = multiples(rate, modes).maxByOrNull { it.key } ?: return null
        return if (multiple(current.refreshRate, rate) == k) null else fastest.bestFor(rate)
    }

    /** [modes] whose refresh rate is an integer multiple of [rate], by that multiple. */
    private fun multiples(rate: Double, modes: List<DisplayMode>): Map<Int, List<DisplayMode>> =
        modes.mapNotNull { mode -> multiple(mode.refreshRate, rate)?.let { it to mode } }.groupBy({ it.first }, { it.second })

    /** The integer k ≥ 1 with [refreshRate] = k × [rate] within [MODE_TOLERANCE], or null. */
    private fun multiple(refreshRate: Double, rate: Double): Int? {
        val k = (refreshRate / rate).roundToInt()
        val target = rate * k
        return k.takeIf { it >= 1 && abs(refreshRate - target) <= target * MODE_TOLERANCE }
    }

    /** Of modes that all fit, the one whose refresh rate is closest to a multiple of [rate]. */
    private fun List<DisplayMode>.bestFor(rate: Double): DisplayMode? = minByOrNull { mode ->
        val k = floor(mode.refreshRate / rate + 0.5)
        abs(mode.refreshRate - k * rate)
    }
}
