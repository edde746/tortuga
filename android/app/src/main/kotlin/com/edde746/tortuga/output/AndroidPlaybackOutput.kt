package com.edde746.tortuga.output

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Window
import com.edde746.tortuga.mpv.MpvPlayerEngine
import com.edde746.tortuga.mpv.SurfaceHints
import com.edde746.tortuga.playback.output.DisplayInfo
import com.edde746.tortuga.playback.output.OutputPlan
import com.edde746.tortuga.playback.output.OutputPolicy
import com.edde746.tortuga.playback.output.PlaybackOutputHost

/**
 * One playback's output on Android (frame rate, HDR, passthrough), following the shared [OutputPlan]:
 * - Frame rate: the video's Surface votes for the video's rate with every plan (`Surface.setFrameRate`, API 30+),
 *   whatever the app's settings; from API 31 a long video's vote may switch the display even where that blanks it
 *   (`CHANGE_FRAME_RATE_ALWAYS`), a short one's only seamlessly ([OutputPlan.seamlessOnly]). The system's own "Match
 *   content frame rate" setting decides whether the display switches. On a TV with the MatchFrameRate setting on the
 *   window also asks for the plan's mode (`preferredDisplayModeId`), which forces the switch.
 * - The switch: a mode switch that blanks the screen holds playback ([MpvPlayerEngine.holdForDisplay]) until the
 *   display has settled, [SETTLE_MS] after its last mode change, so the picture doesn't go blank under the sound. A
 *   plan that is expected to switch (a forced mode; a long video's vote where the user's setting allows a blank and
 *   no seamless rate fits, the check Android's frame-rate guide gives) holds before it asks, and lets go after
 *   [SWITCH_START_MS] when no change came. On a TV any other mode change during the playback (a system that switched
 *   on the vote by a setting the app can't read, as Fire OS's) holds from when it is seen. While held, [switching]
 *   hears so: HDMI audio becoming noisy during the switch is the switch's, not the viewer's, and doesn't pause.
 * - HDR: without HDR output ([OutputPlan.hdrOutput]: the Hdr setting off, or no HDR display) the video Surface's HDR
 *   headroom is capped at SDR (API 35+). On a display that takes the video's HDR format the compositor then
 *   tone-maps it; below API 35, and on every API for a format the display doesn't take (no HDR display at all
 *   included), the engine moves it to its GL vo, where mpv tone-maps it ([SurfaceHints.sdr]).
 * - Passthrough: with the setting on, mpv's `audio-spdif` lists the formats the current route takes undecoded, again
 *   whenever the route changes (a receiver or headphones come and go).
 *
 * [restore] (the playback ended) withdraws the window's mode and lets go of a hold; the Surface's hints go with the
 * engine. Main thread.
 */
internal class AndroidPlaybackOutput(
    context: Context,
    private val engine: MpvPlayerEngine,
    private val television: Boolean,
    private val window: () -> Window?,
    private val switching: (Boolean) -> Unit,
) : PlaybackOutputHost {
    private val context = context.applicationContext
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val displays = this.context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var passthrough = false
    private var routeWatched = false
    private var modeRequested = false

    /** The playback is held for a display switch ([hold]); [settling] once the display's mode has changed for it. */
    private var held = false
    private var settling = false

    /** The default display's mode as last seen, while [modes] listens. */
    private var shownModeId: Int? = null

    private val route = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = applyPassthrough()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = applyPassthrough()
    }

    private val release = Runnable { release("the display settled") }
    private val noSwitch = Runnable { release("no switch came") }

    /** The default display's mode changes: a switch is under way, and the hold lasts until it has settled. */
    private val modes = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val mode = defaultDisplay()?.mode ?: return
            if (mode.modeId == shownModeId) return
            shownModeId = mode.modeId
            Log.i(TAG, "Display mode now ${mode.modeId} (${mode.physicalWidth}x${mode.physicalHeight} at ${mode.refreshRate} Hz)")
            if (!held && !television) return
            hold("the display's mode changed")
            settling = true
            handler.removeCallbacks(noSwitch)
            handler.removeCallbacks(release)
            handler.postDelayed(release, SETTLE_MS)
        }
    }

    override suspend fun display(): DisplayInfo =
        (DeviceOutput.display(context) ?: DisplayInfo(emptyList(), null, hdrSupported = false, hdrEnabled = false)).also { Log.i(TAG, "Display: $it") }

    override fun apply(plan: OutputPlan) {
        Log.i(TAG, "Output plan: $plan")
        if (shownModeId == null) {
            shownModeId = defaultDisplay()?.mode?.modeId
            displays?.registerDisplayListener(modes, handler)
        }
        val modeId = plan.mode?.id ?: 0
        val forced = modeId != 0 && modeId != shownModeId && window() != null
        if (forced || voteSwitches(plan)) {
            hold(if (forced) "forced mode $modeId" else "the frame-rate vote")
            if (!settling) {
                handler.removeCallbacks(noSwitch)
                handler.postDelayed(noSwitch, SWITCH_START_MS)
            }
        }
        engine.setSurfaceHints(SurfaceHints(frameRate = plan.frameRate?.toFloat(), changeAlways = !plan.seamlessOnly, sdr = !plan.hdrOutput))
        requestMode(modeId)
        passthrough = plan.audioPassthrough
        if (passthrough && !routeWatched) {
            // Registering reports the current devices at once, which applies the passthrough.
            audio.registerAudioDeviceCallback(route, handler)
            routeWatched = true
        } else {
            applyPassthrough()
        }
    }

    override fun restore() {
        if (shownModeId != null) {
            displays?.unregisterDisplayListener(modes)
            shownModeId = null
        }
        handler.removeCallbacks(release)
        handler.removeCallbacks(noSwitch)
        if (held) release("the playback ended")
        if (modeRequested) requestMode(0)
        if (routeWatched) {
            audio.unregisterAudioDeviceCallback(route)
            routeWatched = false
        }
    }

    /**
     * The vote for [plan]'s rate is expected to blank the display (Android's frame-rate guide): a long video's vote
     * (API 31+, `CHANGE_FRAME_RATE_ALWAYS`) where the user lets the system switch even then, the current refresh rate
     * isn't a multiple of the video's, none the current mode switches to seamlessly is, and a mode of the current size
     * has one.
     */
    private fun voteSwitches(plan: OutputPlan): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || plan.seamlessOnly) return false
        val rate = plan.frameRate ?: return false
        if (displays?.matchContentFrameRateUserPreference != DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS) return false
        val display = defaultDisplay() ?: return false
        val current = display.mode
        if (OutputPolicy.fits(current.refreshRate.toDouble(), rate)) return false
        if (current.alternativeRefreshRates.any { OutputPolicy.fits(it.toDouble(), rate) }) return false
        return display.supportedModes.any {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && OutputPolicy.fits(it.refreshRate.toDouble(), rate)
        }
    }

    private fun hold(why: String) {
        if (held) return
        held = true
        switching(true)
        engine.holdForDisplay(true)
        Log.i(TAG, "Playback held for a display switch: $why")
    }

    private fun release(why: String) {
        handler.removeCallbacks(release)
        handler.removeCallbacks(noSwitch)
        settling = false
        if (!held) return
        held = false
        engine.holdForDisplay(false)
        switching(false)
        Log.i(TAG, "Playback released: $why")
    }

    /** The window's preferred display mode; 0 leaves it to the system. */
    private fun requestMode(id: Int) {
        val window = window() ?: return
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId == id) return
        attributes.preferredDisplayModeId = id
        window.attributes = attributes
        modeRequested = id != 0
        Log.i(TAG, "Preferred display mode: ${if (id == 0) "the system's" else id}")
    }

    private fun applyPassthrough() {
        engine.setAudioPassthrough(if (passthrough) DeviceOutput.passthroughCodecs(context) else emptyList())
    }

    private fun defaultDisplay(): Display? = displays?.getDisplay(Display.DEFAULT_DISPLAY)

    private companion object {
        const val TAG = "PlaybackOutput"

        /** How long the display takes to settle after its mode changed: Android's frame-rate guide waits 2 s. */
        const val SETTLE_MS = 2_000L

        /**
         * How long a hold waits for an expected switch to start before playback goes on without one: a TV box's mode
         * change can land more than 3 s after the window asks.
         */
        const val SWITCH_START_MS = 5_000L
    }
}
