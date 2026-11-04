package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputKey
import com.edde746.tortuga.playback.AudioDevice
import com.edde746.tortuga.playback.AudioInfo
import com.edde746.tortuga.playback.AudioRendering
import com.edde746.tortuga.playback.DynamicRange
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.PlaybackStats
import com.edde746.tortuga.playback.Track
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.VideoInfo
import com.edde746.tortuga.playback.session.AutoPlayPick
import com.edde746.tortuga.playback.session.PlayingStream
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackInfoTest {
    @Test
    fun anHdr10HevcRemuxWithTrueHdAtmos() {
        val state = EngineState(
            loaded = true,
            durationSeconds = 8000.0,
            cacheEndSeconds = 42.4,
            tracks = listOf(
                Track(1, TrackKind.VIDEO, null, null, "hevc", selected = true, external = false, codecProfile = "Main 10"),
                Track(1, TrackKind.AUDIO, "TrueHD Atmos 7.1", "eng", "truehd", selected = true, external = false, channels = 8, codecProfile = "Dolby TrueHD + Dolby Atmos"),
                Track(2, TrackKind.AUDIO, "Commentary", "eng", "ac3", selected = false, external = false, channels = 2),
                Track(1, TrackKind.SUBTITLE, null, "eng", "hdmv_pgs_subtitle", selected = true, external = false),
                Track(2, TrackKind.SUBTITLE, null, "fre", "subrip", selected = false, external = true),
            ),
            video = VideoInfo(3840, 2160, "hevc", 23.976, DynamicRange.HDR10),
            outputDynamicRange = DynamicRange.HDR10,
            audio = AudioInfo("truehd", 8, 48000),
            hwdec = "videotoolbox",
            stats = PlaybackStats(
                fileName = "Movie.2160p.UHD.BluRay.REMUX.HDR.HEVC.TrueHD.7.1.Atmos.mkv",
                fileFormat = "mkv",
                fileSizeBytes = 62_600_000_000,
                pixelFormat = "videotoolbox",
                hwPixelFormat = "p010",
                primaries = "bt.2020",
                transfer = "pq",
                colorMatrix = "bt.2020-ncl",
                maxCll = 1000.0,
                maxFall = 400.0,
                masteringMinLuminance = 0.005,
                masteringMaxLuminance = 1000.0,
                videoBitrate = 48_512_000.0,
                audioBitrate = 4_210_000.0,
                droppedFrames = 0,
                decoderDroppedFrames = 0,
                delayedFrames = 2,
                displayWidth = 3840,
                displayHeight = 2160,
                displayRefreshRate = 60.0,
                channelLayout = "7.1",
                audioOutput = "coreaudio",
                cacheBytes = 251_658_240,
                downloadBytesPerSecond = 12_897_484,
                bufferingPercent = 100,
            ),
        )
        val stream = PlayingStream(addonName = "Example Streams", name = "4k HDR", autoPlay = AutoPlayPick.FIRST_MATCH)
        assertEquals(
            listOf(
                InfoSection(
                    InfoSectionId.STREAM,
                    "Stream",
                    listOf(
                        InfoRow("Name", "4k HDR"),
                        InfoRow("Addon", "Example Streams"),
                        InfoRow("Picked by", "Auto-play, first match"),
                        InfoRow("File", "Movie.2160p.UHD.BluRay.REMUX.HDR.HEVC.TrueHD.7.1.Atmos.mkv"),
                        InfoRow("Container", "MKV"),
                        InfoRow("Size", "58.3 GB"),
                        InfoRow("Bitrate", "62.6 Mb/s"),
                    ),
                ),
                InfoSection(
                    InfoSectionId.VIDEO,
                    "Video",
                    listOf(
                        InfoRow("Codec", "HEVC Main 10"),
                        InfoRow("Resolution", "3840×2160 · 23.976 fps"),
                        InfoRow("Pixel format", "10-bit · 4:2:0"),
                        InfoRow("Dynamic range", "HDR10"),
                        InfoRow("Color", "BT.2020 · PQ"),
                        InfoRow("MaxCLL / MaxFALL", "1,000 / 400 nits"),
                        InfoRow("Mastering display", "0.005–1,000 nits"),
                        InfoRow("Bitrate", "48.5 Mb/s"),
                        InfoRow("Decoder", "Hardware (VideoToolbox)"),
                        InfoRow("Dropped frames", "0 (decoder 0)"),
                        InfoRow("Delayed frames", "2"),
                        InfoRow("Output", "3840×2160 · 60 Hz · HDR10"),
                    ),
                ),
                InfoSection(
                    InfoSectionId.AUDIO,
                    "Audio",
                    listOf(
                        InfoRow("Codec", "TrueHD Atmos"),
                        InfoRow("Channels", "7.1"),
                        InfoRow("Sample rate", "48 kHz"),
                        InfoRow("Bitrate", "4.2 Mb/s"),
                        InfoRow("Track", "English · \u2068TrueHD Atmos 7.1\u2069"),
                        InfoRow("Output", "Core Audio"),
                    ),
                ),
                InfoSection(
                    InfoSectionId.SUBTITLES,
                    "Subtitles",
                    listOf(InfoRow("Track", "English"), InfoRow("Format", "PGS · Embedded")),
                ),
                InfoSection(
                    InfoSectionId.NETWORK,
                    "Network",
                    listOf(InfoRow("Cache", "42 s · 240 MB"), InfoRow("Download speed", "12.3 MB/s")),
                ),
            ),
            PlaybackInfo.sections(state, stream, "en"),
        )
    }

    /** mpv's file name of a stream URL keeps its query, and is the whole URL when the path ends in `/`. */
    @Test
    fun theFileRowShowsNothingOfTheLinkButTheFileName() {
        fun fileRow(fileName: String) = PlaybackInfo.sections(EngineState(loaded = true, stats = PlaybackStats(fileName = fileName)), null, "en")
            .firstOrNull { it.id == InfoSectionId.STREAM }?.rows?.singleOrNull { it.label == "File" }?.value
        assertEquals("Example.Film.mkv", fileRow("Example.Film.mkv?token=s3cr3t&k=fakeApiKey42"))
        assertEquals(null, fileRow("https://cdn.example.com/dl/fakeApiKey42/"))
    }

    @Test
    fun aDolbyVisionDownloadOnAndroidWithAtmosPassthrough() {
        val state = EngineState(
            loaded = true,
            durationSeconds = 3000.0,
            tracks = listOf(
                Track(1, TrackKind.VIDEO, null, null, "hevc", selected = true, external = false, codecProfile = "Main 10"),
                Track(1, TrackKind.AUDIO, null, "eng", "eac3", selected = true, external = false, channels = 6, codecProfile = "Dolby Digital Plus + Dolby Atmos"),
            ),
            video = VideoInfo(3840, 2160, "hevc", 23.976, DynamicRange.DOLBY_VISION, doviProfile = 8),
            outputDynamicRange = DynamicRange.HDR10,
            audio = AudioInfo("eac3", 6, 48000),
            hwdec = "mediacodec",
            audioPassthrough = true,
            stats = PlaybackStats(
                fileName = "Show.S01E01.2160p.WEB-DL.DV.mkv",
                fileFormat = "mkv",
                fileSizeBytes = 4_831_838_208,
                pixelFormat = "mediacodec",
                primaries = "bt.2020",
                transfer = "pq",
                colorMatrix = "bt.2020-ncl",
                videoBitrate = 15_120_000.0,
                audioBitrate = 768_000.0,
                droppedFrames = 3,
                decoderDroppedFrames = 1,
                delayedFrames = 0,
                displayWidth = 2400,
                displayHeight = 1080,
                displayRefreshRate = 90.0,
                channelLayout = "5.1(side)",
                audioOutput = "audiotrack",
                passthroughFormat = "eac3",
            ),
        )
        val sections = PlaybackInfo.sections(state, PlayingStream("Example Streams", "4k DV", autoPlay = null, downloaded = true), "en")
        assertEquals(listOf(InfoSectionId.STREAM, InfoSectionId.VIDEO, InfoSectionId.AUDIO), sections.map { it.id }, "a download has no network")
        assertEquals(
            listOf(
                InfoRow("Name", "4k DV"),
                InfoRow("Addon", "Example Streams"),
                InfoRow("Source", "Downloaded"),
                InfoRow("File", "Show.S01E01.2160p.WEB-DL.DV.mkv"),
                InfoRow("Container", "MKV"),
                InfoRow("Size", "4.5 GB"),
                InfoRow("Bitrate", "12.9 Mb/s"),
            ),
            sections[0].rows,
        )
        assertEquals(
            listOf(
                InfoRow("Codec", "HEVC Main 10"),
                InfoRow("Resolution", "3840×2160 · 23.976 fps"),
                // MediaCodec says nothing of its frames' format: the profile names the depth.
                InfoRow("Pixel format", "10-bit"),
                InfoRow("Dynamic range", "Dolby Vision profile 8"),
                InfoRow("Color", "BT.2020 · PQ"),
                InfoRow("Bitrate", "15.1 Mb/s"),
                InfoRow("Decoder", "Hardware (MediaCodec)"),
                InfoRow("Dropped frames", "3 (decoder 1)"),
                InfoRow("Delayed frames", "0"),
                InfoRow("Output", "2400×1080 · 90 Hz · HDR10"),
            ),
            sections[1].rows,
        )
        assertEquals(
            listOf(
                InfoRow("Codec", "E-AC3 Atmos"),
                InfoRow("Channels", "5.1 (side)"),
                InfoRow("Sample rate", "48 kHz"),
                InfoRow("Bitrate", "768 kb/s"),
                InfoRow("Track", "English"),
                InfoRow("Output", "AudioTrack · Passthrough (E-AC3)"),
            ),
            sections[2].rows,
        )
    }

    @Test
    fun aDtsHdMaTrackOnWindowsWithAPickedOutput() {
        val state = EngineState(
            loaded = true,
            durationSeconds = 10.016,
            tracks = listOf(
                Track(1, TrackKind.VIDEO, null, null, "h264", selected = true, external = false, codecProfile = "High"),
                Track(1, TrackKind.AUDIO, "DTS-HD MA 7.1", "eng", "dts", selected = true, external = false, channels = 8, codecProfile = "DTS-HD MA"),
            ),
            video = VideoInfo(1920, 1080, "h264", 23.976, DynamicRange.SDR),
            outputDynamicRange = DynamicRange.SDR,
            audio = AudioInfo("dts", 8, 48000),
            hwdec = "d3d11va-copy",
            audioDevices = listOf(AudioDevice("auto", "Autoselect device"), AudioDevice("wasapi/{1}", "Speakers (Realtek(R) Audio)")),
            audioDevice = "wasapi/{1}",
            stats = PlaybackStats(
                fileName = "dts-hdma.mkv",
                fileFormat = "mkv",
                fileSizeBytes = 5_376_581,
                pixelFormat = "nv12",
                primaries = "bt.709",
                transfer = "bt.1886",
                colorMatrix = "bt.709",
                displayWidth = 2560,
                displayHeight = 1440,
                displayRefreshRate = 59.94,
                channelLayout = "7.1",
                audioOutput = "wasapi",
                passthroughFormat = "dtshd",
            ),
        )
        val sections = PlaybackInfo.sections(state, PlayingStream("Tortuga Test", "Tortuga Test DTS-HD MA", autoPlay = null), "en")
        val video = sections.single { it.id == InfoSectionId.VIDEO }.rows
        assertEquals(InfoRow("Codec", "H.264 High"), video[0])
        assertEquals(InfoRow("Pixel format", "8-bit · 4:2:0"), video[2])
        assertEquals(InfoRow("Color", "BT.709 · BT.1886"), video[4])
        assertEquals(InfoRow("Bitrate", "—"), video[5], "a live value mpv has none for yet keeps its row")
        assertEquals(InfoRow("Decoder", "Hardware (D3D11VA copy)"), video[6])
        assertEquals(InfoRow("Output", "2560×1440 · 59.94 Hz · SDR"), video.last())
        assertEquals(
            listOf(
                InfoRow("Codec", "DTS-HD MA"),
                InfoRow("Channels", "7.1"),
                InfoRow("Sample rate", "48 kHz"),
                InfoRow("Bitrate", "—"),
                InfoRow("Track", "English · \u2068DTS-HD MA 7.1\u2069"),
                InfoRow("Output", "\u2068Speakers (Realtek(R) Audio)\u2069 · WASAPI · Passthrough (DTS-HD)"),
            ),
            sections.single { it.id == InfoSectionId.AUDIO }.rows,
        )
        assertEquals(InfoRow("Bitrate", "4.3 Mb/s"), sections.single { it.id == InfoSectionId.STREAM }.rows.last())
        assertEquals("DTS-HD MA + DTS:X", CodecNames.audio("dts", "DTS-HD MA + DTS:X"))
        assertEquals("DTS", CodecNames.audio("dts", null))
    }

    @Test
    fun planarRgbFormatsReadAsTheirDepthWithoutSubsampling() {
        assertEquals(CodecNames.PixelFormat(8, null), CodecNames.pixelFormat("gbrp"))
        assertEquals(CodecNames.PixelFormat(10, null), CodecNames.pixelFormat("gbrp10le"))
        assertEquals(CodecNames.PixelFormat(12, null), CodecNames.pixelFormat("gbrap12"))
        assertEquals(CodecNames.PixelFormat(10, "4:2:0"), CodecNames.pixelFormat("yuv420p10"))
    }

    @Test
    fun aStereoAacWebFileStillBuffering() {
        val state = EngineState(
            loaded = true,
            buffering = true,
            durationSeconds = 0.0,
            cacheEndSeconds = 1.6,
            tracks = listOf(
                Track(1, TrackKind.VIDEO, null, null, "h264", selected = true, external = false, codecProfile = "High"),
                Track(1, TrackKind.AUDIO, null, null, "aac", selected = true, external = false, channels = 2, codecProfile = "LC"),
            ),
            video = VideoInfo(1920, 1080, "h264", 25.0, DynamicRange.SDR),
            audio = AudioInfo("aac", 2, 44100),
            hwdec = "no",
            stats = PlaybackStats(
                fileName = "index.m3u8",
                fileFormat = "hls,applehttp",
                pixelFormat = "yuv420p",
                primaries = "bt.709",
                transfer = "bt.1886",
                colorMatrix = "bt.709",
                audioBitrate = 128_000.0,
                droppedFrames = 0,
                decoderDroppedFrames = 0,
                delayedFrames = 0,
                channelLayout = "stereo",
                audioOutput = "pipewire",
                cacheBytes = 524_288,
                downloadBytesPerSecond = 4 * 1024 * 1024,
                bufferingPercent = 45,
            ),
        )
        val sections = PlaybackInfo.sections(state, PlayingStream("YouTube", null, autoPlay = null), "en")
        assertEquals(
            listOf(InfoRow("Name", "YouTube"), InfoRow("Addon", "YouTube"), InfoRow("File", "index.m3u8"), InfoRow("Container", "HLS")),
            sections.single { it.id == InfoSectionId.STREAM }.rows,
            "a live stream has no size, so no overall bitrate",
        )
        assertEquals(
            listOf(
                InfoRow("Codec", "H.264 High"),
                InfoRow("Resolution", "1920×1080 · 25 fps"),
                InfoRow("Pixel format", "8-bit · 4:2:0"),
                InfoRow("Dynamic range", "SDR"),
                InfoRow("Color", "BT.709 · BT.1886"),
                InfoRow("Bitrate", "—"),
                InfoRow("Decoder", "Software"),
                InfoRow("Dropped frames", "0 (decoder 0)"),
                InfoRow("Delayed frames", "0"),
            ),
            sections.single { it.id == InfoSectionId.VIDEO }.rows,
            "no output row where the platform reports none",
        )
        assertEquals(
            listOf(
                InfoRow("Codec", "AAC LC"),
                InfoRow("Channels", "Stereo"),
                InfoRow("Sample rate", "44.1 kHz"),
                InfoRow("Bitrate", "128 kb/s"),
                InfoRow("Track", "Track 1"),
                InfoRow("Output", "PipeWire"),
            ),
            sections.single { it.id == InfoSectionId.AUDIO }.rows,
        )
        assertEquals(
            listOf(InfoRow("Cache", "2 s · 512 KB"), InfoRow("Download speed", "4.0 MB/s"), InfoRow("Buffering", "45%")),
            sections.single { it.id == InfoSectionId.NETWORK }.rows,
        )
    }

    @Test
    fun liveValuesUpdateInTheirRowsWhileTheInfoShows() = playerTest {
        playing()
        engineState {
            it.copy(
                tracks = listOf(Track(1, TrackKind.VIDEO, null, null, "hevc", selected = true, external = false)),
                video = VideoInfo(3840, 2160, "hevc", 23.976, DynamicRange.HDR10),
                hwdec = "videotoolbox",
                cacheEndSeconds = 110.0,
            )
        }
        press(InputKey.I)
        val before = state.info
        // The engine's first reading: the bitrate isn't known yet.
        engineState { it.copy(stats = PlaybackStats(droppedFrames = 0, decoderDroppedFrames = 0, delayedFrames = 0, cacheBytes = 1_048_576)) }
        val first = state.info
        assertEquals("—", first.row(InfoSectionId.VIDEO, "Bitrate"))
        assertEquals("10 s · 1.0 MB", first.row(InfoSectionId.NETWORK, "Cache"))
        engineState {
            it.copy(
                cacheEndSeconds = 111.0,
                stats = it.stats!!.copy(videoBitrate = 38_600_000.0, droppedFrames = 2, cacheBytes = 2_097_152, downloadBytesPerSecond = 1_048_576),
            )
        }
        val second = state.info
        assertEquals("38.6 Mb/s", second.row(InfoSectionId.VIDEO, "Bitrate"))
        assertEquals("2 (decoder 0)", second.row(InfoSectionId.VIDEO, "Dropped frames"))
        assertEquals("11 s · 2.0 MB", second.row(InfoSectionId.NETWORK, "Cache"))
        assertEquals("1.0 MB/s", second.row(InfoSectionId.NETWORK, "Download speed"))
        assertEquals(first.map { s -> s.id to s.rows.map { it.label } }, second.map { s -> s.id to s.rows.map { it.label } }, "live values change no row")
        assertEquals(before.map { it.id }, second.map { it.id })
        // Another file: the last one's stats go until the engine reads the new one's.
        engineState { it.copy(stats = null) }
        assertEquals(null, state.info.rowOrNull(InfoSectionId.VIDEO, "Bitrate"))
    }

    @Test
    fun columnsTakeSectionsInOrderWhereTheyAreShortest() {
        fun section(id: InfoSectionId, rows: Int) = InfoSection(id, id.name, List(rows) { InfoRow("$it", "") })
        val sections = listOf(
            section(InfoSectionId.STREAM, 7),
            section(InfoSectionId.VIDEO, 12),
            section(InfoSectionId.AUDIO, 6),
            section(InfoSectionId.SUBTITLES, 2),
            section(InfoSectionId.NETWORK, 2),
        )
        fun ids(columns: Int) = InfoColumns.split(sections, columns).map { column -> column.map { it.id } }
        assertEquals(listOf(sections.map { it.id }), ids(1))
        assertEquals(
            listOf(
                listOf(InfoSectionId.STREAM, InfoSectionId.AUDIO, InfoSectionId.NETWORK),
                listOf(InfoSectionId.VIDEO, InfoSectionId.SUBTITLES),
            ),
            ids(2),
        )
        assertEquals(
            listOf(
                listOf(InfoSectionId.STREAM, InfoSectionId.NETWORK),
                listOf(InfoSectionId.VIDEO),
                listOf(InfoSectionId.AUDIO, InfoSectionId.SUBTITLES),
            ),
            ids(3),
        )
        assertEquals(ids(2), InfoColumns.split(sections.dropLast(1) + section(InfoSectionId.NETWORK, 3), 2).map { c -> c.map { it.id } }, "the network's own rows move nothing")
        assertEquals(3, InfoColumns.split(sections.take(3), 5).size, "no empty columns")
    }

    @Test
    fun bitratesAndSpeedsKeepTheirShape() {
        assertEquals("640 kb/s", PlaybackInfo.bitrate(640_000.0))
        assertEquals("999 kb/s", PlaybackInfo.bitrate(999_400.0))
        assertEquals("1.0 Mb/s", PlaybackInfo.bitrate(999_960.0))
        assertEquals("120.0 Mb/s", PlaybackInfo.bitrate(120_000_000.0))
        assertEquals("512 B/s", PlaybackInfo.speed(512))
        assertEquals("2.0 MB/s", PlaybackInfo.speed(2 * 1024 * 1024))
        assertEquals("150 MB/s", PlaybackInfo.speed(150L * 1024 * 1024))
    }

    /**
     * The system's rendering of the audio (Apple's audio session) is the Audio section's last row, by Apple's name for
     * it; where the platform reports none (Android, desktop, a route the system doesn't render itself) there is no row.
     */
    @Test
    fun theSystemsRenderingShowsWhereThePlatformReportsIt() {
        fun rendering(rendering: AudioRendering?): String? {
            val state = EngineState(
                loaded = true,
                audio = AudioInfo("truehd", 6, 48000),
                stats = PlaybackStats(audioOutput = "avfoundation", audioRendering = rendering),
            )
            val audio = PlaybackInfo.sections(state, null, "en").first { it.id == InfoSectionId.AUDIO }.rows
            return audio.last().takeIf { it.label == "Rendering" }?.value
        }
        assertEquals("Dolby Atmos", rendering(AudioRendering.DOLBY_ATMOS))
        assertEquals("Dolby Audio", rendering(AudioRendering.DOLBY_AUDIO))
        assertEquals("Spatial Audio", rendering(AudioRendering.SPATIAL_AUDIO))
        assertEquals("Surround", rendering(AudioRendering.SURROUND))
        assertEquals("Stereo", rendering(AudioRendering.STEREO))
        assertEquals(null, rendering(null))
    }

    private fun List<InfoSection>.rowOrNull(id: InfoSectionId, label: String): String? =
        firstOrNull { it.id == id }?.rows?.firstOrNull { it.label == label }?.value

    private fun List<InfoSection>.row(id: InfoSectionId, label: String): String =
        rowOrNull(id, label) ?: error("no $label row in $id: $this")
}
