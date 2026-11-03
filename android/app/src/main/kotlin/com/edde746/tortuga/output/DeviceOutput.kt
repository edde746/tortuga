package com.edde746.tortuga.output

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import com.edde746.tortuga.mpv.DisplayHdr
import com.edde746.tortuga.playback.output.DisplayInfo
import com.edde746.tortuga.playback.output.DisplayMode
import com.edde746.tortuga.playback.output.VideoCapabilities
import com.edde746.tortuga.playback.streams.VideoCodecBadge
import com.edde746.tortuga.presentation.settings.DeviceFeatures

/**
 * What this device's display and audio route can do (detected here and reported to
 * shared code): the display's modes and HDR for the output plan, the compressed formats the route takes undecoded for
 * passthrough, and from both, which device settings apply here ([features]).
 */
internal object DeviceOutput {
    /** The default display: its modes (physical size), the current one, and its HDR support (always on where present). */
    fun display(context: Context): DisplayInfo? {
        val display = defaultDisplay(context) ?: return null
        val modes = display.supportedModes.map { DisplayMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate.toDouble()) }
        val hdr = DisplayHdr.hdrTypes(context).isNotEmpty()
        return DisplayInfo(modes, modes.firstOrNull { it.id == display.mode.modeId }, hdrSupported = hdr, hdrEnabled = hdr)
    }

    /**
     * The device settings that do something here:
     * - HDR: the display shows HDR and the app can keep a video from it (`SurfaceView.setDesiredHdrHeadroom`, API 35)
     *   on phones and tablets. A TV's HDMI output follows the content by the system's own setting, which an app
     *   can't switch.
     * - Forcing the frame rate: a TV (a window's preferred mode switches the display, whatever the system's setting)
     *   whose current resolution has more than one refresh rate. Phones and tablets have nothing to force: the video's
     *   Surface votes for its rate on every playback and the system decides.
     * - Passthrough: the current audio route takes a compressed surround format undecoded.
     */
    fun features(context: Context, television: Boolean): DeviceFeatures {
        val display = display(context)
        val current = display?.current
        val rates = display?.modes.orEmpty().filter { it.width == current?.width && it.height == current.height }.map { it.refreshRate }.distinct()
        return DeviceFeatures(
            hardwareDecoding = true,
            hdr = !television && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && display?.hdrSupported == true,
            matchFrameRate = television && rates.size > 1,
            audioPassthrough = passthroughCodecs(context).isNotEmpty(),
        )
    }

    /**
     * What the display and the decoders take ([com.edde746.tortuga.app.AppPlatform.videoCapabilities]): its HDR types,
     * and the video codecs a hardware decoder takes (`MediaCodecInfo.isHardwareAccelerated` from API 29; before it, a
     * decoder that isn't Android's own software one).
     */
    fun videoCapabilities(context: Context): VideoCapabilities {
        val hdrTypes = DisplayHdr.hdrTypes(context)
        val hardware = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            }
        }
        val types = hardware.flatMap { it.supportedTypes.asList() }.mapTo(mutableSetOf()) { it.lowercase() }
        return VideoCapabilities(
            hdr = hdrTypes.isNotEmpty(),
            dolbyVision = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes,
            hardwareCodecs = HARDWARE_TYPES.filterKeys { it in types }.values.toSet(),
        )
    }

    private val HARDWARE_TYPES = mapOf(
        MediaFormat.MIMETYPE_VIDEO_AVC to VideoCodecBadge.AVC,
        MediaFormat.MIMETYPE_VIDEO_HEVC to VideoCodecBadge.HEVC,
        "video/av01" to VideoCodecBadge.AV1,
        MediaFormat.MIMETYPE_VIDEO_VP9 to VideoCodecBadge.VP9,
    )

    /**
     * The compressed formats the current audio route plays undecoded, as mpv's `audio-spdif` names them. API 33+ asks
     * the route for its direct profiles; API 29–32 asks per format (as Media3's `AudioCapabilities` does); older
     * releases read the formats of an HDMI sink from the sticky HDMI plug broadcast. DTS also needs the sink itself to
     * list it ([passthroughCodecs] with [sinkEncodings]).
     */
    fun passthroughCodecs(context: Context): List<String> = passthroughCodecs(encodings(context), sinkEncodings(context))

    /**
     * The encodings (`AudioDeviceInfo.getEncodings`) of the outputs a movie plays through: on API 33+ the active movie
     * route's devices, whatever their type (an Amlogic HAL can name its HDMI output "Speaker", with the TV's codecs);
     * before API 33, or when the route names none, every HDMI output.
     */
    fun sinkEncodings(context: Context): List<IntArray> {
        val manager = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hdmi = outputs.filter { it.type in HDMI_TYPES }.map { it.encodings }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return hdmi
        val route = manager.getAudioDevicesForAttributes(ATTRIBUTES)
        if (route.isEmpty()) return hdmi
        return outputs.filter { device -> route.any { it.type == device.type && it.address == device.address } }.map { it.encodings }
    }

    private val HDMI_TYPES = setOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC)

    private fun encodings(context: Context): Set<Int> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            context.getSystemService(AudioManager::class.java)?.getDirectProfilesForAttributes(ATTRIBUTES).orEmpty().map { it.format }.toSet()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> SPDIF_CODECS.map { it.first }.filter { encoding ->
            val format = AudioFormat.Builder().setEncoding(encoding).setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            // Deprecated from API 33, where the branch above answers.
            @Suppress("DEPRECATION")
            AudioTrack.isDirectPlaybackSupported(format, ATTRIBUTES)
        }.toSet()
        else -> {
            val plug = context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG))
            if (plug?.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) == 1) plug.encodings() else emptySet()
        }
    }

    private fun Intent.encodings(): Set<Int> = getIntArrayExtra(AudioManager.EXTRA_ENCODINGS)?.toSet().orEmpty()

    private fun defaultDisplay(context: Context): Display? =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    private val ATTRIBUTES: AudioAttributes =
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
}

/**
 * [platform]'s encodings as `audio-spdif` codecs, without DTS when the sinks in [sinks] (each
 * `AudioDeviceInfo.getEncodings()`) are described and none lists DTS. The platform's answer can be the HAL's own DTS
 * decoder rather than the sink's: an Amlogic box in front of a Samsung or LG TV (no DTS in their EDID) lists
 * `ENCODING_DTS_HD`, then plays the bitstream as silence. Without DTS named, mpv decodes it to PCM the sink takes.
 * A sink that lists no compressed codec (PCM and the IEC 61937 transport alone, or nothing) is undescribed: any such
 * sink, or none at all, leaves the platform's answer standing. Dolby codecs aren't gated: the same HALs carry TrueHD
 * to sinks whose lists leave it out.
 */
internal fun passthroughCodecs(platform: Set<Int>, sinks: List<IntArray>): List<String> {
    val described = sinks.isNotEmpty() && sinks.all { sink -> sink.any { it !in TRANSPORT_ENCODINGS } }
    val sinkTakesDts = !described || sinks.any { sink -> sink.any { it in DTS_SINK_ENCODINGS } }
    return SPDIF_CODECS.filter { (encoding, _) -> encoding in platform && (sinkTakesDts || encoding !in DTS_SINK_ENCODINGS) }
        .map { it.second }.distinct()
}

/** Android's encoding → mpv's `audio-spdif` codec. */
private val SPDIF_CODECS: List<Pair<Int, String>> = listOf(
    AudioFormat.ENCODING_AC3 to "ac3",
    AudioFormat.ENCODING_E_AC3 to "eac3",
    AudioFormat.ENCODING_E_AC3_JOC to "eac3",
    AudioFormat.ENCODING_DTS to "dts",
    AudioFormat.ENCODING_DTS_HD to "dts-hd",
    AudioFormat.ENCODING_DOLBY_TRUEHD to "truehd",
)

/** Encodings that say nothing of which codecs a sink decodes: PCM, and IEC 61937, a transport. */
private val TRANSPORT_ENCODINGS = setOf(
    AudioFormat.ENCODING_PCM_16BIT,
    AudioFormat.ENCODING_PCM_8BIT,
    AudioFormat.ENCODING_PCM_FLOAT,
    AudioFormat.ENCODING_PCM_24BIT_PACKED,
    AudioFormat.ENCODING_PCM_32BIT,
    AudioFormat.ENCODING_IEC61937,
)

/** DTS as a sink lists it: DTS, DTS-HD, DTS:X (UHD profiles 1 and 2) and DTS-HD MA. */
private val DTS_SINK_ENCODINGS = setOf(
    AudioFormat.ENCODING_DTS,
    AudioFormat.ENCODING_DTS_HD,
    AudioFormat.ENCODING_DTS_UHD_P1,
    AudioFormat.ENCODING_DTS_HD_MA,
    AudioFormat.ENCODING_DTS_UHD_P2,
)
