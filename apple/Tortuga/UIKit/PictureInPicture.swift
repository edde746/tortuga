#if os(iOS)
import AVKit
import Observation
import os
import OSLog
import TortugaKit

private let log = Logger(subsystem: "com.edde746.tortuga", category: "player")

/// iOS's picture in picture over mpv's avfoundation VO (PiP is native, not shared): an `AVPictureInPictureController`
/// over the surface's `AVSampleBufferDisplayLayer`, the layer mpv's avfoundation VO enqueues into. The VO presents on
/// the media timeline and owns the layer's control timebase, which is what the PiP window's progress reads. The Mac's
/// counterpart, under the same name, is AppKit/PictureInPicture.swift.
///
/// PiP can start (`possible`) once AVKit says it can and the layer shows mpv's video: the VO's timebase is on it and,
/// from iOS 17.4, its first frame is ready for display. AVKit says PiP is possible
/// as soon as the layer is on screen, before mpv has shown anything, and a start then fails (`PGPegasusErrorDomain`
/// -1003) with nothing to see, so the player's button stays disabled until the video is up, while a stream loads. It
/// also stays disabled where AVKit doesn't support PiP at all (the iPhone simulators). The layer is read again when
/// AVKit's answer changes, when the engine has shown a format (`videoShown`) and before a start; where the layer lags
/// behind the video, a few times more, 100 ms apart. Nothing reads it while no video shows (a stream that loads, fails
/// or has no video).
///
/// PiP shows the samples on the display layer alone, not the VO's subtitle layer beside it: from its start (the
/// player's button, or AVKit's will-start for one by Home) until it ends or fails to start, mpv draws the subtitles
/// into the video's frames instead (`onSubtitlesInPicture`).
///
/// The PiP window's controls are OS media controls like Now Playing: they show the shared `NowPlaying` (paused, the
/// duration) and act through its `MediaCommand`s (play, pause, skip), so they behave exactly like the player's own.
/// PiP starts from the player's button, or on its own when the app goes to the background while the video plays. The
/// button ends it again, as does the window's way back to the app, and the playback goes on; closed in the window
/// instead, the playback pauses, as the system player's does. The end of the playback closes it. While it runs the
/// player stays up under AVKit's placeholder with close and the PiP button alone (`TouchControls`): the window's own
/// controls play, pause and skip.
///
/// AVKit doesn't promise the thread it calls the delegates on, and KVO reports a change on the thread that made it,
/// so they read a copy of the state under a lock (`playback`) and hop to the main actor to act.
@MainActor
@Observable
final class PictureInPicture: NSObject {
    /// PiP can start now: the device supports it, AVKit says it can and the layer shows mpv's video.
    private(set) var possible = false
    private(set) var active = false
    /// AVKit is starting PiP (from its will-start until it started or failed to).
    private(set) var starting = false
    /// The engine's: draws the subtitles into the video's frames (true) or on their own layer again (false).
    @ObservationIgnored var onSubtitlesInPicture: ((Bool) -> Void)?

    @ObservationIgnored private let layer: AVSampleBufferDisplayLayer
    @ObservationIgnored private var controller: AVPictureInPictureController?
    /// AVKit's `isPictureInPicturePossible`.
    @ObservationIgnored private var observation: NSKeyValueObservation?
    /// The engine has shown a format (`videoShown`): until then the layer has nothing to be ready with.
    @ObservationIgnored private var videoShownOnce = false
    /// The next read of the layer while AVKit says PiP is possible but the layer isn't ready yet, and how many are left.
    @ObservationIgnored private var recheck: Task<Void, Never>?
    @ObservationIgnored private var rechecksLeft = 0
    /// The subtitles go into the video's frames (`onSubtitlesInPicture`), as last asked.
    @ObservationIgnored private var subtitlesInPicture = false
    @ObservationIgnored private weak var nowPlaying: NowPlayingCenter?
    /// The user returned to the app from the PiP window (rather than closing it).
    @ObservationIgnored private var restoring = false
    /// What the delegates report, copied from Now Playing on the main actor and read on AVKit's threads.
    @ObservationIgnored private let playback = OSAllocatedUnfairLock(initialState: PlaybackReport())

    /// The PiP window's playback state: paused, and the duration (0 while unknown) or a live playback.
    private struct PlaybackReport {
        var paused = true
        var duration = 0.0
        var live = false
    }

    /// Reads of the layer after a trigger while it lags behind the video, 100 ms apart.
    private static let rechecks = 20

    /// PiP shows the video, or is about to: mpv must keep decoding it (`ApplePlayback`).
    var showsVideo: Bool { active || starting }

    init(layer: AVSampleBufferDisplayLayer) {
        self.layer = layer
        super.init()
        guard AVPictureInPictureController.isPictureInPictureSupported() else {
            log.info("picture in picture: not supported on this device")
            return
        }
        let controller = AVPictureInPictureController(contentSource: .init(sampleBufferDisplayLayer: layer, playbackDelegate: self))
        controller.delegate = self
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        self.controller = controller
        observation = controller.observe(\.isPictureInPicturePossible) { @Sendable [weak self] _, _ in
            Task { @MainActor in self?.refresh() }
        }
        refresh()
    }

    /// Takes the PiP window's state from `nowPlaying` and sends its commands there.
    func follow(_ nowPlaying: NowPlayingCenter) {
        self.nowPlaying = nowPlaying
        nowPlaying.onChange = { [weak self, weak nowPlaying] in
            guard let self else { return }
            let current = nowPlaying?.current
            let report = PlaybackReport(
                paused: !(current?.playing ?? false),
                duration: current?.durationSeconds ?? 0,
                live: current?.live ?? false
            )
            playback.withLock { $0 = report }
            controller?.invalidatePlaybackState()
        }
    }

    /// Starts PiP, or stops it when it is up.
    func toggle() {
        guard let controller else { return }
        if controller.isPictureInPictureActive {
            controller.stopPictureInPicture()
        } else {
            refresh()
            guard possible else { return }
            drawSubtitlesInPicture(true)
            controller.startPictureInPicture()
        }
    }

    /// The engine has shown the video in a format (the first frame of a file, or another format): the layer can be ready.
    func videoShown() {
        videoShownOnce = true
        refresh()
    }

    /// The playback ended: PiP closes and no longer starts on its own.
    func stop() {
        guard let controller else { return }
        controller.canStartPictureInPictureAutomaticallyFromInline = false
        if controller.isPictureInPictureActive { controller.stopPictureInPicture() }
        observation = nil
        self.controller = nil
        recheck?.cancel()
        recheck = nil
        possible = false
    }

    /// Reads the layer again after a trigger, with a fresh allowance of rechecks.
    private func refresh() {
        rechecksLeft = Self.rechecks
        update()
    }

    /// Reads again whether PiP can start; a change is logged with what made it. While AVKit says PiP is possible, the
    /// engine has shown the video and the layer isn't ready yet, it reads again in 100 ms, while rechecks are left.
    private func update() {
        guard let controller else { return }
        let system = controller.isPictureInPicturePossible
        let timebase = layer.controlTimebase != nil
        let frame: Bool
        if #available(iOS 17.4, *) {
            frame = layer.isReadyForDisplay
        } else {
            frame = true
        }
        let now = system && timebase && frame
        if system, !now, videoShownOnce, recheck == nil, rechecksLeft > 0 {
            rechecksLeft -= 1
            recheck = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(100))
                guard !Task.isCancelled else { return }
                self?.recheck = nil
                self?.update()
            }
        }
        guard now != possible else { return }
        possible = now
        log.info("picture in picture \(now ? "possible" : "not possible", privacy: .public): AVKit \(system, privacy: .public), timebase \(timebase, privacy: .public), first frame \(frame, privacy: .public)")
    }

    private func drawSubtitlesInPicture(_ inPicture: Bool) {
        guard inPicture != subtitlesInPicture else { return }
        subtitlesInPicture = inPicture
        onSubtitlesInPicture?(inPicture)
    }

    private func perform(_ command: any MediaCommand) {
        nowPlaying?.commands?.perform(command: command)
    }

    /// `command` on the main actor, from a delegate on any thread.
    private nonisolated func send(_ command: @escaping @Sendable () -> any MediaCommand) {
        Task { @MainActor in
            let value = command()
            self.perform(value)
        }
    }
}

extension PictureInPicture: AVPictureInPictureControllerDelegate {
    nonisolated func pictureInPictureControllerWillStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        Task { @MainActor in
            self.starting = true
            self.drawSubtitlesInPicture(true)
        }
    }

    nonisolated func pictureInPictureControllerDidStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        log.info("picture in picture started")
        Task { @MainActor in
            self.restoring = false
            self.starting = false
            self.active = true
        }
    }

    nonisolated func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void
    ) {
        // The player stays presented under PiP, so there is nothing to rebuild.
        Task { @MainActor in self.restoring = true }
        completionHandler(true)
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        Task { @MainActor in
            log.info("picture in picture stopped: \(self.restoring ? "back to the player" : "closed, pausing", privacy: .public)")
            self.active = false
            self.drawSubtitlesInPicture(false)
            if !self.restoring { self.perform(MediaCommandPause.shared) }
            self.restoring = false
        }
    }

    nonisolated func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        failedToStartPictureInPictureWithError error: any Error
    ) {
        log.error("picture in picture failed to start: \(String(describing: error), privacy: .public)")
        Task { @MainActor in
            self.starting = false
            self.drawSubtitlesInPicture(false)
        }
    }
}

extension PictureInPicture: AVPictureInPictureSampleBufferPlaybackDelegate {
    nonisolated func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, setPlaying playing: Bool) {
        send { playing ? MediaCommandPlay.shared as any MediaCommand : MediaCommandPause.shared }
    }

    nonisolated func pictureInPictureControllerTimeRangeForPlayback(_ pictureInPictureController: AVPictureInPictureController) -> CMTimeRange {
        let report = playback.withLock { $0 }
        // A live playback, or one of unknown duration, shows as live: no progress, no skipping.
        guard report.duration > 0, !report.live else { return CMTimeRange(start: .negativeInfinity, duration: .positiveInfinity) }
        return CMTimeRange(start: .zero, duration: CMTime(seconds: report.duration, preferredTimescale: 1000))
    }

    nonisolated func pictureInPictureControllerIsPlaybackPaused(_ pictureInPictureController: AVPictureInPictureController) -> Bool {
        playback.withLock { $0.paused }
    }

    nonisolated func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        didTransitionToRenderSize newRenderSize: CMVideoDimensions
    ) {}

    nonisolated func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        skipByInterval skipInterval: CMTime,
        completion completionHandler: @escaping () -> Void
    ) {
        let seconds = skipInterval.seconds
        send { MediaCommandSeekBy(seconds: seconds) }
        completionHandler()
    }
}
#endif
