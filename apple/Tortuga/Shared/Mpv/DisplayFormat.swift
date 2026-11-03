import TortugaKit

/// What the screen should be set up for while a video plays (display-mode and HDR switching are native, not shared).
/// The engine derives it from mpv once a file's first frame is up and hands it to its `VideoSurface`, which shows it as
/// the shared `OutputPolicy`'s plan says: EDR on iOS and the Mac, `AVDisplayManager` criteria on tvOS.
struct DisplayFormat: Equatable, Sendable {
    var dynamicRange: DynamicRange
    var width: Int
    var height: Int
    /// The video's frame rate (the track's `demux-fps`, `VideoInfo.fps`) snapped to the standard rate it stands for
    /// (`OutputPolicy.contentRate`), in Hz; 0 when the track declares none.
    var refreshRate: Double
    /// The Dolby Vision profile and level the decoder outputs; nil unless `dynamicRange` is Dolby Vision.
    var doviProfile: Int?
    var doviLevel: Int?
    /// The Dolby Vision base layer's signal compatibility (1 = HDR10), when known.
    var doviCompatibility: Int?
    /// The picture as it shows, its rotation counted, is taller than wide (the shared `VideoInfo.tall`, Android's rule
    /// too): the iPhone's player follows the device for it (`PlayerOrientation`).
    var tall: Bool

    /// The shown `video` (`MpvState.videoInfo`) and its track's Dolby Vision level (`MpvState.videoTracks`), which counts
    /// only for Dolby Vision.
    init(video: VideoInfo, doviLevel: Int?) {
        dynamicRange = video.dynamicRange
        width = Int(video.width)
        height = Int(video.height)
        tall = video.tall
        refreshRate = video.fps.flatMap { OutputPolicy.shared.contentRate(fps: $0.doubleValue)?.doubleValue } ?? 0
        let doviProfile = video.doviProfile?.intValue
        if doviProfile == 7 {
            // mpv-build's FFmpeg rewrites profile 7 to 8.1 (HDR10-compatible base) before VideoToolbox decodes it
            // (PLEZY_DV_CONVERSION_MODE unset means `auto`), so the display is asked for what the decoder emits.
            self.doviProfile = 8
            doviCompatibility = 1
        } else {
            self.doviProfile = doviProfile
            doviCompatibility = nil
        }
        self.doviLevel = video.dynamicRange == .dolbyVision ? doviLevel : nil
    }
}

/// The screen as the platform reports it: its mode in pixels and its refresh rate (`PlaybackStats`' display fields),
/// and the dynamic range the video goes out in (`EngineState.outputDynamicRange` where mpv doesn't say); nil where
/// nothing reports it. The engine reads it from its surface (`VideoSurface.displayOutput()`) with each stats reading,
/// and on iOS and tvOS whenever the surface's range may have changed (`onOutputRangeChange`): vo_avfoundation doesn't
/// tell mpv the display's mode, so mpv's `display-width`, `display-height` and `display-fps` stay unavailable.
struct DisplayOutput: Equatable, Sendable {
    var width: Int?
    var height: Int?
    var refreshRate: Double?
    var dynamicRange: DynamicRange?
}
