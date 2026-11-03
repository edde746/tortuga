package com.edde746.tortuga.desktop.windows

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.mpv.MpvPlayerEngine
import com.edde746.tortuga.playback.output.DisplayInfo
import com.edde746.tortuga.playback.output.DisplayMode
import com.edde746.tortuga.playback.output.OutputPlan
import com.edde746.tortuga.playback.output.PlaybackOutputHost
import com.edde746.tortuga.presentation.settings.DeviceFeatures
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Display matching on Windows: each playback's [PlaybackOutputHost] ([host]) switches the display the window is on to
 * the mode and HDR state the shared policy picks (a mode only while the player is fullscreen; leaving fullscreen brings
 * a plan without one, which puts the mode back), and back when the playback ends; [close] (the app's exit) and a JVM
 * shutdown hook put back whatever is still switched. A mode set with `CDS_FULLSCREEN` also goes back when the process
 * dies; the HDR state doesn't, so a crash mid-playback leaves HDR on.
 *
 * One `display` thread does the switching, in order (a switch blocks for a moment), so a playback reads the display
 * only after the one before it has restored it; the playback's read waits for that without blocking the main thread.
 * Switching HDR drops a temporary mode (Windows goes back to the mode in the registry), so a plan switches HDR first
 * and then sets its mode, again if HDR changed; a restore goes back to the registry mode, then turns HDR off. A plan
 * that switches anything holds its playback ([MpvPlayerEngine.holdForDisplay]) from before the switch until
 * [SETTLE_MS] after it, while the monitor syncs to the new signal, so the picture doesn't go blank under the sound.
 * After each mode change the playback's mpv gets the new rate ([MpvPlayerEngine.setDisplayFps]), since its child
 * window hears nothing of the change, and presents HDR only with the plan's HDR output ([MpvPlayerEngine.setHdrOutput]);
 * while it presents HDR, SDR video's white is the display's SDR white level ([MpvPlayerEngine.setSdrWhite]), read at
 * each plan. The plan's audio passthrough goes to the playback's mpv as each plan comes
 * ([MpvPlayerEngine.setAudioPassthrough]), on every output: Windows doesn't say which formats a device takes undecoded.
 *
 * @param window the window's HWND, read on the main thread when a playback reads the display.
 * @param onEvent what was switched, for the test report.
 */
internal class WindowsDisplayMatching(
    private val window: () -> Long,
    private val onEvent: (name: String, detail: Map<String, Any?>) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val thread: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { Thread(it, "display").apply { isDaemon = true } }
    private val dispatcher = thread.asCoroutineDispatcher()
    private val shutdownHook = Thread(::close, "display-restore")

    // The display thread's state: the display of the playback, what it changed there, and the end of its hold.
    private var display: WindowsDisplay? = null
    private var switchedTo: WindowsDisplay.Mode? = null
    private var hdrTurnedOn = false
    private var holdRelease: ScheduledFuture<*>? = null

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    /**
     * What the display under the window can switch, for the Settings rows: HDR where it supports HDR, forcing the
     * frame rate where its resolution comes with more than one rate (Windows has no setting of its own that follows a
     * video's rate); neither when the display can't be read. Passthrough always. Read on the calling thread (a few
     * milliseconds).
     */
    fun features(): DeviceFeatures {
        val display = try {
            window().takeIf { it != 0L }?.let(WindowsDisplay::forWindow)
        } catch (e: Exception) {
            Log.w(TAG, e) { "reading the display failed; no display switching" }
            null
        }
        return DeviceFeatures(
            hardwareDecoding = true,
            hdr = display?.hdr()?.supported == true,
            matchFrameRate = (display?.modes()?.size ?: 0) > 1,
            audioPassthrough = true,
        )
    }

    /** The output host of the playback on [engine]. */
    fun host(engine: MpvPlayerEngine): PlaybackOutputHost = object : PlaybackOutputHost {
        override suspend fun display(): DisplayInfo {
            val hwnd = window()
            return withContext(dispatcher) { read(hwnd) }
        }

        override fun apply(plan: OutputPlan) {
            engine.setAudioPassthrough(plan.audioPassthrough)
            post("apply") { applyPlan(plan, engine) }
        }

        override fun restore() = post("restore") { restoreDisplay(engine) }
    }

    /** Puts the display back as it was before a playback switched it (the app is closing), then stops the thread. */
    override fun close() {
        if (thread.isShutdown) return
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        post("restore") { restoreDisplay(null) }
        thread.shutdown()
        thread.awaitTermination(15, TimeUnit.SECONDS)
    }

    private fun post(what: String, block: () -> Unit) {
        if (thread.isShutdown) return
        thread.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, e) { "display $what failed" }
            }
        }
    }

    private fun read(hwnd: Long): DisplayInfo {
        restoreDisplay(null) // Nothing is switched when a playback starts; a missed restore is undone before reading.
        val display = WindowsDisplay.forWindow(hwnd).also { display = it }
        val hdr = display.hdr()
        val info = DisplayInfo(
            modes = display.modes().map { it.toDisplayMode() },
            current = display.currentMode()?.toDisplayMode(),
            hdrSupported = hdr?.supported == true,
            hdrEnabled = hdr?.enabled == true,
        )
        onEvent("displayInfo", mapOf("device" to display.device, "current" to info.current?.describe(), "modes" to info.modes.map { it.describe() }, "hdrSupported" to info.hdrSupported, "hdrEnabled" to info.hdrEnabled))
        return info
    }

    private fun applyPlan(plan: OutputPlan, engine: MpvPlayerEngine) {
        // Without HDR output (the Hdr setting off) mpv tone-maps to SDR, even on a display whose HDR is on.
        engine.setHdrOutput(plan.hdrOutput)
        // A plan before the playback read the display has nothing to switch here.
        val display = display ?: return
        val mode = plan.mode
        val held = plan.enableHdr != hdrTurnedOn || (if (mode == null) switchedTo != null else switchedTo?.frequency != mode.id)
        if (held) {
            holdRelease?.cancel(false)
            engine.holdForDisplay(true)
        }
        val (hdrSet, modeSet) = try {
            switch(display, plan)
        } finally {
            if (held) holdRelease = thread.schedule({ engine.holdForDisplay(false) }, SETTLE_MS, TimeUnit.MILLISECONDS)
        }
        if (hdrSet == false || modeSet == false) Log.w(TAG) { "display switch refused: hdr=$hdrSet mode=$modeSet for $plan" }
        val state = state(display)
        if (modeSet != null) engine.setDisplayFps(if (switchedTo != null) state.refreshHz else null)
        // mpv presents in PQ: SDR video at the white DWM shows the controls and every other window at, not mpv's 203.
        val sdrWhite = if (plan.hdrOutput && state.hdrEnabled == true) display.sdrWhiteNits() else null
        engine.setSdrWhite(sdrWhite)
        onEvent(
            "displayApplied",
            mapOf(
                "frameRate" to plan.frameRate, "mode" to mode?.describe(), "enableHdr" to plan.enableHdr, "hdr" to plan.hdr, "hdrOutput" to plan.hdrOutput,
                "sdrWhite" to sdrWhite,
                "hdrSet" to hdrSet, "modeSet" to modeSet, "held" to held,
            ) + state.fields,
        )
    }

    /** Switches [display]'s HDR state, then its mode, to [plan]'s: whether each switch was taken, null where none was due. */
    private fun switch(display: WindowsDisplay, plan: OutputPlan): Pair<Boolean?, Boolean?> {
        var hdrSet: Boolean? = null
        var modeSet: Boolean? = null
        if (plan.enableHdr != hdrTurnedOn) {
            hdrSet = display.setHdr(plan.enableHdr)
            // What the display shows, not what the call answered: a refused switch can still turn HDR on (a Remote
            // Desktop session's display does), and HDR on here is this playback's (the plan enables HDR only when the
            // display had it off), to turn off again at the restore.
            hdrTurnedOn = display.hdr()?.enabled == true
            if (hdrSet && switchedTo != null) {
                // The HDR switch dropped the mode set before it: the display is back at its registry mode.
                switchedTo = null
                if (plan.mode == null) modeSet = true
            }
        }
        val mode = plan.mode
        if (mode == null) {
            if (switchedTo != null) modeSet = restoreMode(display)
        } else if (switchedTo?.frequency != mode.id) {
            val target = WindowsDisplay.Mode(mode.width, mode.height, mode.id, mode.refreshRate)
            modeSet = display.setMode(target)
            if (modeSet) switchedTo = target
        }
        return hdrSet to modeSet
    }

    /** Undoes what the playback switched: the registry mode first, then HDR; its mpv (if still playing) reads the rate itself again. */
    private fun restoreDisplay(engine: MpvPlayerEngine?) {
        holdRelease?.cancel(false)
        holdRelease = null
        val display = display ?: return
        val changed = switchedTo != null || hdrTurnedOn
        val mode = if (switchedTo != null) restoreMode(display) else null
        val hdr = if (hdrTurnedOn) display.setHdr(false).also { hdrTurnedOn = display.hdr()?.enabled == true } else null
        this.display = null
        if (mode != null) engine?.setDisplayFps(null)
        if (changed) onEvent("displayRestored", mapOf("modeRestored" to mode, "hdrRestored" to hdr) + state(display).fields)
    }

    private fun restoreMode(display: WindowsDisplay): Boolean = display.restoreMode().also { if (it) switchedTo = null }

    /** The display's mode and HDR state now, as the report shows them. */
    private class State(val current: WindowsDisplay.Mode?, val hdrEnabled: Boolean?) {
        val refreshHz: Double? get() = current?.refreshHz
        val fields: Map<String, Any?> get() = mapOf("current" to current?.toDisplayMode()?.describe(), "hdrEnabled" to hdrEnabled)
    }

    private fun state(display: WindowsDisplay) = State(display.currentMode(), display.hdr()?.enabled)

    private companion object {
        const val TAG = "display"

        /** How long a monitor takes to show a picture again after its mode or HDR state switched. */
        const val SETTLE_MS = 2_000L

        /** A GDI mode as the policy sees it: its id is the GDI frequency, which [WindowsDisplay.setMode] takes back. */
        fun WindowsDisplay.Mode.toDisplayMode() = DisplayMode(id = frequency, width = width, height = height, refreshRate = refreshHz)

        fun DisplayMode.describe(): String = "${width}x$height@${"%.3f".format(java.util.Locale.ROOT, refreshRate)}"
    }
}
