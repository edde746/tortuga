package com.edde746.tortuga.playback.output

import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * The platform's side of a playback's output (display-mode and HDR switching, passthrough): the display
 * the player is on, and switching it and the audio route to what [OutputPolicy] decides. One per playback on
 * platforms that can switch something (`TortugaPlatform.playbackOutput`). Called on the main thread.
 */
interface PlaybackOutputHost {
    /**
     * The display the player is on, before the playback changed anything. Read once per playback, at its start. It
     * suspends rather than blocking the main thread while the display can't be read yet (Windows: the previous
     * playback's restore of the mode and HDR still running).
     */
    suspend fun display(): DisplayInfo

    /**
     * Makes the output follow [plan]. Each plan is complete ([OutputPlan]): what it leaves at its default (no [mode],
     * no [OutputPlan.enableHdr]) goes back to how it was before the playback, if an earlier plan changed it.
     */
    fun apply(plan: OutputPlan)

    /**
     * The playback ended: the display's mode and HDR state as they were before it. A platform whose app can quit
     * during a playback also restores on its way out.
     */
    fun restore()
}

/**
 * One playback's output on a [formFactor] device: once the device settings are read, [PlaybackOutputHost.apply]s a plan
 * without a video (HDR output as the setting and the display allow, the audio passthrough), then a plan for each video
 * the [engineState] reports (its first [VideoInfo], and again when a later file's frame rate or dynamic range differs,
 * or it turns out short: a [trailer], or its duration reported as a few minutes, [OutputPolicy.isShort]) and whenever
 * the player enters or leaves [fullscreen] (a desktop switches the mode only while fullscreen), and restores the
 * display when [release]d.
 *
 * The display is read once, at the start, so every plan is decided against the display's own mode and HDR state rather
 * than one the playback switched to. Between files (a next episode) the last plan stays.
 *
 * Main thread.
 */
class PlaybackOutput internal constructor(
    engineState: StateFlow<EngineState>,
    trailer: Flow<Boolean>,
    fullscreen: Flow<Boolean>,
    private val formFactor: FormFactor,
    private val settings: suspend () -> DeviceSettings,
    private val host: PlaybackOutputHost,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var applied: OutputPlan? = null
    private var released = false

    init {
        scope.launch {
            val loaded = settings()
            val display = try {
                host.display()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, e) { "reading the display failed; the output stays as it is" }
                return@launch
            }
            val videos: Flow<ShownVideo?> = combine(engineState, trailer) { state, isTrailer ->
                state.video?.let { ShownVideo(it, OutputPolicy.isShort(isTrailer, state.durationSeconds)) }
            }
                .filterNotNull()
                .distinctUntilChanged { old, new -> old.key == new.key }
            // The first plan has no video yet; each later one is decided for the latest video and fullscreen state.
            combine(videos.onStart { emit(null) }, fullscreen) { shown, inFullscreen ->
                OutputPolicy.plan(shown?.video, display, loaded, formFactor, inFullscreen, short = shown?.short == true)
            }.collect(::apply)
        }
    }

    /** The playback ended: the display goes back to how it was, if anything changed it. */
    fun release() {
        if (released) return
        released = true
        scope.cancel()
        if (applied != null) host.restore()
    }

    private fun apply(plan: OutputPlan) {
        if (plan == applied) return
        applied = plan
        host.apply(plan)
    }

    /** A video as the plans see it: [key] changes when its plan may. */
    private class ShownVideo(val video: VideoInfo, val short: Boolean) {
        val key = Triple(video.fps?.let(OutputPolicy::contentRate), video.dynamicRange, short)
    }

    private companion object {
        const val TAG = "PlaybackOutput"
    }
}
