package com.edde746.tortuga.desktop.linux

import com.edde746.tortuga.mpv.MpvPlayerEngine
import com.edde746.tortuga.playback.output.DisplayInfo
import com.edde746.tortuga.playback.output.OutputPlan
import com.edde746.tortuga.playback.output.PlaybackOutputHost

/**
 * A playback's output on Linux: the audio passthrough alone ([MpvPlayerEngine.setAudioPassthrough]), on every output,
 * since nothing here says which formats a device takes undecoded. Linux switches neither the display's mode nor its HDR
 * state, so the display reads as one without either and there is nothing to restore.
 */
internal class LinuxPlaybackOutput(private val engine: MpvPlayerEngine) : PlaybackOutputHost {
    override suspend fun display() = DisplayInfo(modes = emptyList(), current = null, hdrSupported = false, hdrEnabled = false)

    override fun apply(plan: OutputPlan) = engine.setAudioPassthrough(plan.audioPassthrough)

    override fun restore() = Unit
}
