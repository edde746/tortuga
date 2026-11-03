package com.edde746.tortuga.mpv

import com.edde746.mpv.GpuVoPolicy

/**
 * What the video's Surface asks of the display for the current playback (frame-rate matching and HDR), applied by
 * [MpvVideoView] to each Surface it has.
 *
 * @param frameRate the content's frame rate to vote for (`Surface.setFrameRate`, API 30+), or null for no vote.
 * @param changeAlways the vote may switch the display even where the switch interrupts the picture
 *   (`CHANGE_FRAME_RATE_ALWAYS`, API 31+); otherwise only seamless switches.
 * @param sdr keep the picture within SDR: from API 35 the Surface's HDR headroom is capped at SDR
 *   (`SurfaceView.setDesiredHdrHeadroom(1)`) and the compositor tone-maps HDR video; below API 35 the engine moves
 *   HDR video to its GL vo, where mpv tone-maps it. HDR in a format the display doesn't take goes to the GL vo on
 *   every API ([GpuVoPolicy.needsToneMapping]). A file's first frames, before the move, reach the plane as they are.
 */
data class SurfaceHints(val frameRate: Float? = null, val changeAlways: Boolean = false, val sdr: Boolean = false) {
    companion object {
        val NONE = SurfaceHints()
    }
}
