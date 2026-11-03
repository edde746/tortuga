package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.format.formatByteSize
import com.edde746.tortuga.playback.AudioDevice
import com.edde746.tortuga.playback.AudioRendering
import com.edde746.tortuga.playback.DynamicRange
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.PlaybackStats
import com.edde746.tortuga.playback.Track
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.VideoInfo
import com.edde746.tortuga.playback.session.AutoPlayPick
import com.edde746.tortuga.playback.session.PlayingStream
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The playback info's sections, each with what is known of it:
 * - Stream: its name, its addon, "Downloaded" when it plays from the device and, when auto-play picked it, how; the
 *   file's name, container, size and overall bitrate (the size over the duration). mpv's file name keeps a stream
 *   URL's query (`movie.mkv?token=…`) and is the whole URL when its path ends in `/`, and resolved links carry their keys
 *   there: the row drops the query, and shows no URL.
 * - Video: the codec with its profile, resolution and frame rate, bit depth and chroma, dynamic range, colour space,
 *   HDR10 metadata where the file has it, the bitrate, the decoder, dropped and delayed frames, and what the display
 *   gets (its mode, HDR or SDR) where the platform says.
 * - Audio: the codec as it is known (Atmos, DTS:X), channels, sample rate, bitrate, the track, the output (its driver
 *   and device, passthrough while on), and how the system renders it (Dolby Atmos, Spatial Audio) where the platform
 *   says.
 * - Subtitles, with a subtitle track to pick: the track (or Off), its format and whether the file carries it, the
 *   delay while there is one.
 * - Network, for a stream that doesn't play from the device: the cache ahead (time and size), the download speed, and
 *   how far a stall has refilled while it lasts. Last, so its row that comes and goes moves nothing else.
 *
 * Rows of the engine's [PlaybackStats] show once it has read them; from then on a live value (a bitrate, a count, the
 * cache, the speed) keeps its row, with [UNKNOWN] while mpv has no value (right after a load or a seek).
 */
internal object PlaybackInfo {
    private const val SEPARATOR = " · "

    /** A live value mpv doesn't have at the moment. */
    const val UNKNOWN = "—"

    fun sections(state: EngineState, stream: PlayingStream?, locale: String): List<InfoSection> = listOfNotNull(
        section(InfoSectionId.STREAM, T.player.info.stream, stream(state, stream)),
        section(InfoSectionId.VIDEO, T.player.info.video, video(state)),
        section(InfoSectionId.AUDIO, T.player.info.audio, audio(state, locale)),
        section(InfoSectionId.SUBTITLES, T.player.info.subtitles, subtitles(state, locale)),
        section(InfoSectionId.NETWORK, T.player.info.network, network(state, stream)),
    )

    private fun section(id: InfoSectionId, title: String, rows: List<InfoRow>) = rows.takeIf { it.isNotEmpty() }?.let { InfoSection(id, title, it) }

    private fun stream(state: EngineState, stream: PlayingStream?): List<InfoRow> = buildList {
        val info = T.player.info
        stream?.let { playing ->
            add(InfoRow(info.name, playing.name ?: playing.addonName))
            if (playing.addonName.isNotBlank()) add(InfoRow(info.addon, playing.addonName))
            if (playing.downloaded) add(InfoRow(info.source, info.downloaded))
            when (playing.autoPlay) {
                AutoPlayPick.FIRST_MATCH -> add(InfoRow(info.pickedBy, info.autoPlayFirstMatch))
                AutoPlayPick.LAST_LINK -> add(InfoRow(info.pickedBy, info.autoPlayLastLink))
                null -> {}
            }
        }
        val stats = state.stats ?: return@buildList
        stats.fileName?.substringBefore('?')?.takeIf { it.isNotBlank() && "://" !in it }?.let { add(InfoRow(info.file, it)) }
        stats.fileFormat?.takeIf { it.isNotBlank() }?.let { add(InfoRow(info.container, CodecNames.container(it))) }
        stats.fileSizeBytes?.takeIf { it > 0 }?.let { size ->
            add(InfoRow(info.fileSize, formatByteSize(size)))
            if (state.durationSeconds > 0) add(InfoRow(info.bitrate, bitrate(size * 8 / state.durationSeconds)))
        }
    }

    private fun video(state: EngineState): List<InfoRow> {
        val track = state.selected(TrackKind.VIDEO)
        val video = state.video
        val stats = state.stats
        if (video == null && track == null) return emptyList()
        val info = T.player.info
        return buildList {
            (video?.codec ?: track?.codec)?.let { add(InfoRow(info.codec, CodecNames.video(it, track?.codecProfile))) }
            video?.let { add(InfoRow(info.resolution, resolution(it))) }
            pixelFormat(stats, track)?.let { add(InfoRow(info.pixelFormat, it)) }
            video?.let { add(InfoRow(info.dynamicRange, dynamicRange(it))) }
            stats?.let(::color)?.let { add(InfoRow(info.color, it)) }
            stats?.let(::lightLevel)?.let { add(InfoRow(info.lightLevel, it)) }
            stats?.let(::mastering)?.let { add(InfoRow(info.mastering, it)) }
            stats?.let { add(InfoRow(info.bitrate, it.videoBitrate?.let(::bitrate) ?: UNKNOWN)) }
            state.hwdec?.let { add(InfoRow(info.decoder, decoder(it))) }
            stats?.let { add(InfoRow(info.droppedFrames, droppedFrames(it))) }
            stats?.let { add(InfoRow(info.delayedFrames, it.delayedFrames?.toString() ?: UNKNOWN)) }
            stats?.let { output(it, state.outputDynamicRange) }?.let { add(InfoRow(info.output, it)) }
        }
    }

    private fun audio(state: EngineState, locale: String): List<InfoRow> {
        val track = state.selected(TrackKind.AUDIO)
        val audio = state.audio
        val stats = state.stats
        if (audio == null && track == null) return emptyList()
        val info = T.player.info
        return buildList {
            (audio?.codec ?: track?.codec)?.let { add(InfoRow(info.codec, CodecNames.audio(it, track?.codecProfile))) }
            CodecNames.channelLayout(stats?.channelLayout, audio?.channels ?: track?.channels)?.let { add(InfoRow(info.channels, it)) }
            audio?.sampleRate?.takeIf { it > 0 }?.let { add(InfoRow(info.sampleRate, info.kilohertz(PlayerText.decimal(it / 1000.0, 0, 1)))) }
            stats?.let { add(InfoRow(info.bitrate, it.audioBitrate?.let(::bitrate) ?: UNKNOWN)) }
            track?.let { add(InfoRow(info.track, TrackLabels.name(it, state.tracks, locale))) }
            stats?.let { audioOutput(it, state.audioDevice, state.audioDevices) }?.let { add(InfoRow(info.output, it)) }
            stats?.audioRendering?.let { add(InfoRow(info.rendering, audioRendering(it))) }
        }
    }

    private fun subtitles(state: EngineState, locale: String): List<InfoRow> {
        if (state.tracks.none { it.kind == TrackKind.SUBTITLE }) return emptyList()
        val info = T.player.info
        val track = state.selected(TrackKind.SUBTITLE)
        val second = state.tracks.firstOrNull { it.kind == TrackKind.SUBTITLE && it.secondary }
        return buildList {
            add(InfoRow(info.track, track?.let { TrackLabels.name(it, state.tracks, locale) } ?: T.player.track.off))
            track?.let { subtitleFormat(it) }?.let { add(InfoRow(info.format, it)) }
            second?.let { add(InfoRow(info.secondTrack, listOfNotNull(TrackLabels.name(it, state.tracks, locale), subtitleFormat(it)).joinToString(SEPARATOR))) }
            if ((track != null || second != null) && abs(state.subtitleDelaySeconds) >= DELAY_EPSILON) {
                add(InfoRow(info.delay, PlayerText.delay(state.subtitleDelaySeconds)))
            }
        }
    }

    private fun network(state: EngineState, stream: PlayingStream?): List<InfoRow> {
        if (stream?.downloaded == true) return emptyList()
        val stats = state.stats
        val info = T.player.info
        return buildList {
            val seconds = state.cacheEndSeconds?.let { T.player.format.seconds(PlayerText.decimal((it - state.positionSeconds).coerceAtLeast(0.0), 0, 0)) }
            val size = stats?.cacheBytes?.let { formatByteSize(it, keepZero = true) }
            when {
                seconds != null || size != null -> add(InfoRow(info.cache, listOfNotNull(seconds, size).joinToString(SEPARATOR)))
                stats != null -> add(InfoRow(info.cache, UNKNOWN))
            }
            stats?.let { add(InfoRow(info.downloadSpeed, it.downloadBytesPerSecond?.let(::speed) ?: UNKNOWN)) }
            if (state.buffering && state.loaded) {
                stats?.bufferingPercent?.takeIf { it in 0..99 }?.let { add(InfoRow(info.buffering, info.percent(it.toString()))) }
            }
        }
    }

    /** "3840×2160 · 23.976 fps". */
    private fun resolution(video: VideoInfo): String {
        val size = T.player.info.size(video.width.toString(), video.height.toString())
        val fps = video.fps?.takeIf { it > 0 } ?: return size
        return size + SEPARATOR + T.player.info.fps(PlayerText.decimal(fps, 0, 3))
    }

    private fun dynamicRange(video: VideoInfo): String =
        if (video.dynamicRange == DynamicRange.DOLBY_VISION) {
            video.doviProfile?.let { T.player.info.dolbyVisionProfile(it.toString()) } ?: T.player.info.dolbyVision
        } else {
            dynamicRangeName(video.dynamicRange)
        }

    private fun dynamicRangeName(range: DynamicRange): String = when (range) {
        DynamicRange.SDR -> "SDR"
        DynamicRange.HDR10 -> "HDR10"
        DynamicRange.HLG -> "HLG"
        DynamicRange.DOLBY_VISION -> T.player.info.dolbyVision
    }

    /**
     * "10-bit · 4:2:0" from the frames' pixel format (the one inside hardware frames first); without one, the bit
     * depth a 10-bit profile names ("Main 10", "High 10").
     */
    private fun pixelFormat(stats: PlaybackStats?, track: Track?): String? {
        val format = listOfNotNull(stats?.hwPixelFormat, stats?.pixelFormat).firstNotNullOfOrNull(CodecNames::pixelFormat)
        if (format != null) return listOfNotNull(T.player.info.bitDepth(format.bits.toString()), format.chroma).joinToString(SEPARATOR)
        val profileBits = track?.codecProfile?.let { PROFILE_BITS.find(it) }?.groupValues?.get(1) ?: return null
        return T.player.info.bitDepth(profileBits)
    }

    /** "BT.2020 · PQ": primaries and transfer, then the matrix where it isn't the primaries' own ("· Dolby Vision"). */
    private fun color(stats: PlaybackStats): String? {
        val parts = buildList {
            stats.primaries?.let(CodecNames::primaries)?.let(::add)
            stats.transfer?.let(CodecNames::transfer)?.let(::add)
            val matrix = stats.colorMatrix
            if (matrix != null && (stats.primaries == null || !CodecNames.matrixGoesWith(matrix, stats.primaries))) {
                CodecNames.colorMatrix(matrix)?.let(::add)
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    /** "1000 / 400 nits" (MaxCLL / MaxFALL), when the file has either. */
    private fun lightLevel(stats: PlaybackStats): String? {
        val cll = stats.maxCll?.takeIf { it > 0 }
        val fall = stats.maxFall?.takeIf { it > 0 }
        if (cll == null && fall == null) return null
        return T.player.info.lightLevelValue(nits(cll), nits(fall))
    }

    /** "0.005–1000 nits": the mastering display's luminance range. */
    private fun mastering(stats: PlaybackStats): String? {
        val max = stats.masteringMaxLuminance?.takeIf { it > 0 } ?: return null
        val min = stats.masteringMinLuminance?.coerceAtLeast(0.0) ?: 0.0
        return T.player.info.nitsRange(PlayerText.decimal(min, 0, 4), PlayerText.decimal(max, 0, 0))
    }

    private fun nits(value: Double?): String = value?.let { PlayerText.decimal(it, 0, 0) } ?: UNKNOWN

    /** "Hardware (VideoToolbox)"; mpv's `hwdec-current` "no" (or empty) is software decoding. */
    private fun decoder(hwdec: String): String =
        if (hwdec.isBlank() || hwdec == "no") T.player.info.softwareDecoder else T.player.info.hardwareDecoder(CodecNames.hardwareDecoder(hwdec))

    /** "3 (decoder 1)": frames the output dropped, then the decoder's. */
    private fun droppedFrames(stats: PlaybackStats): String {
        val output = stats.droppedFrames ?: return UNKNOWN
        return T.player.info.droppedFramesValue(output.toString(), (stats.decoderDroppedFrames ?: 0).toString())
    }

    /** "3840×2160 · 60 Hz · HDR10": the display's mode and what goes out to it, as far as the platform says. */
    private fun output(stats: PlaybackStats, range: DynamicRange?): String? {
        val parts = buildList {
            val width = stats.displayWidth?.takeIf { it > 0 }
            val height = stats.displayHeight?.takeIf { it > 0 }
            if (width != null && height != null) add(T.player.info.size(width.toString(), height.toString()))
            stats.displayRefreshRate?.takeIf { it > 0 }?.let { add(T.player.info.hertz(PlayerText.decimal(it, 0, 3))) }
            range?.let { add(dynamicRangeName(it)) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    /** The system's rendering by the name Apple gives it. */
    private fun audioRendering(rendering: AudioRendering): String = when (rendering) {
        AudioRendering.STEREO -> T.player.track.stereo
        AudioRendering.SURROUND -> T.player.info.surround
        AudioRendering.SPATIAL_AUDIO -> T.player.info.spatialAudio
        AudioRendering.DOLBY_AUDIO -> "Dolby Audio"
        AudioRendering.DOLBY_ATMOS -> "Dolby Atmos"
    }

    /**
     * "Core Audio" (the system's output), "MacBook Pro Speakers · Core Audio" (one the viewer picked, its name isolated
     * beside the rest), then "Passthrough (TrueHD)" while the receiver gets the audio undecoded.
     */
    private fun audioOutput(stats: PlaybackStats, device: String, devices: List<AudioDevice>): String? {
        val description = devices.firstOrNull { it.name == device && device != PlayerOptions.AUTO_DEVICE }?.description?.takeIf { it.isNotBlank() }
        val rest = buildList {
            stats.audioOutput?.takeIf { it.isNotBlank() }?.let { add(CodecNames.audioDriver(it)) }
            stats.passthroughFormat?.takeIf { it.isNotBlank() }?.let { add(T.player.info.passthrough(CodecNames.audio(it))) }
        }
        val parts = listOfNotNull(description?.let { if (rest.isEmpty()) it else isolate(it) }) + rest
        return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
    }

    /** "SRT · External", "PGS · Embedded". */
    private fun subtitleFormat(track: Track): String {
        val where = if (track.external) T.player.track.external else T.player.info.embedded
        return listOfNotNull(track.codec?.takeIf { it.isNotBlank() }?.let(CodecNames::subtitle), where).joinToString(SEPARATOR)
    }

    /** "38.6 Mb/s", one decimal from 1 Mb/s; "640 kb/s" below. */
    fun bitrate(bitsPerSecond: Double): String = when {
        bitsPerSecond >= 999_950 -> T.player.info.megabitsPerSecond(PlayerText.decimal(bitsPerSecond / 1_000_000, 1, 1))
        else -> T.player.info.kilobitsPerSecond((bitsPerSecond.coerceAtLeast(0.0) / 1000).roundToLong().toString())
    }

    /** "12.3 MB/s": bytes a second, in the units of the app's sizes. */
    fun speed(bytesPerSecond: Long): String = T.player.info.perSecond(formatByteSize(bytesPerSecond, keepZero = true))

    private fun EngineState.selected(kind: TrackKind): Track? = tracks.firstOrNull { it.kind == kind && it.selected }

    /** "Main 10", "High 10", "Main 12": a profile that names a bit depth. */
    private val PROFILE_BITS = Regex("""\b(10|12)\b""")

    private const val DELAY_EPSILON = 0.0005
}
