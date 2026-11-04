import Foundation
import MediaPlayer
import Observation
import OSLog
import TortugaKit

#if os(macOS)
import AppKit
#else
import UIKit
#endif

private let log = Logger(subsystem: "com.edde746.tortuga", category: "player")

/// The OS media controls for one playback (Now Playing is native, what it shows is shared): the shared
/// `MediaControls` publishes `NowPlaying` here, which goes to `MPNowPlayingInfoCenter` (Control Center, the lock screen,
/// the Apple TV's Now Playing, the Mac's Now Playing menu), and `MPRemoteCommandCenter`'s commands (headphones, the Siri
/// Remote from Control Center, keyboard media keys, the lock screen's scrubber) go back to it as `MediaCommand`s, which
/// the shared side turns into the store's commands. Picture in picture follows the same values (`onChange`).
///
/// The system extrapolates the position from the elapsed time and the rate, so `show` only comes on real changes. A live
/// playback (`NowPlaying.live`) shows as a live stream, without a seek bar or the skip buttons (`canSeek` is off then).
@MainActor
@Observable
final class NowPlayingCenter: @preconcurrency MediaControlsHost {
    /// What the controls show; nil before the first `show` and after `hide`.
    private(set) var current: NowPlaying?
    /// Where the OS's commands go while the controls are up.
    @ObservationIgnored private(set) var commands: (any MediaCommandHandler)?
    /// Called after every change of `current`.
    @ObservationIgnored var onChange: (() -> Void)?

    /// What `MPNowPlayingInfoCenter` holds, read back after each write in debug builds: the UI tests' view of Now
    /// Playing, which XCUITest can't read from the system. Empty in release builds.
    private(set) var published = ""

    @ObservationIgnored private let seekSeconds: Double
    @ObservationIgnored private var targets: [(MPRemoteCommand, Any)] = []
    @ObservationIgnored private var artwork: (url: String, image: MPMediaItemArtwork?)?
    @ObservationIgnored private var artworkTask: Task<Void, Never>?

    /// - Parameter seekSeconds: the skip buttons' step, the player's own seek step.
    init(seekSeconds: Double) {
        self.seekSeconds = seekSeconds
    }

    func show(nowPlaying: NowPlaying, commands: any MediaCommandHandler) {
        if self.commands == nil { register() }
        self.commands = commands
        current = nowPlaying
        let center = MPRemoteCommandCenter.shared()
        center.changePlaybackPositionCommand.isEnabled = nowPlaying.canSeek
        center.skipForwardCommand.isEnabled = nowPlaying.canSeek
        center.skipBackwardCommand.isEnabled = nowPlaying.canSeek
        center.nextTrackCommand.isEnabled = nowPlaying.canSkipNext
        center.previousTrackCommand.isEnabled = nowPlaying.canSkipPrevious
        loadArtwork(nowPlaying.artworkUrl)
        publish()
        onChange?()
    }

    func hide() {
        for (command, target) in targets { command.removeTarget(target) }
        targets = []
        commands = nil
        current = nil
        artworkTask?.cancel()
        artworkTask = nil
        artwork = nil
        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = nil
        center.playbackState = .stopped
        #if DEBUG
        published = ""
        #endif
        onChange?()
    }

    // MARK: Commands

    private func register() {
        let center = MPRemoteCommandCenter.shared()
        add(center.playCommand) { _ in MediaCommandPlay.shared }
        add(center.pauseCommand) { _ in MediaCommandPause.shared }
        add(center.togglePlayPauseCommand) { _ in MediaCommandTogglePause.shared }
        add(center.stopCommand) { _ in MediaCommandStop.shared }
        add(center.nextTrackCommand) { _ in MediaCommandNext.shared }
        add(center.previousTrackCommand) { _ in MediaCommandPrevious.shared }
        center.skipForwardCommand.preferredIntervals = [NSNumber(value: seekSeconds)]
        center.skipBackwardCommand.preferredIntervals = [NSNumber(value: seekSeconds)]
        add(center.skipForwardCommand) { event in
            MediaCommandSeekBy(seconds: (event as? MPSkipIntervalCommandEvent)?.interval ?? 0)
        }
        add(center.skipBackwardCommand) { event in
            MediaCommandSeekBy(seconds: -((event as? MPSkipIntervalCommandEvent)?.interval ?? 0))
        }
        add(center.changePlaybackPositionCommand) { event in
            (event as? MPChangePlaybackPositionCommandEvent).map { MediaCommandSeekTo(seconds: $0.positionTime) }
        }
    }

    /// Routes `command` to the playback's handler as what `translate` makes of its event. MediaPlayer doesn't promise
    /// the thread it calls targets on: the event is read there, the handler runs on the main actor.
    private func add(_ command: MPRemoteCommand, _ translate: @escaping @Sendable (MPRemoteCommandEvent) -> (any MediaCommand)?) {
        command.isEnabled = true
        let target = command.addTarget { @Sendable [weak self] event in
            guard let mediaCommand = translate(event) else { return .commandFailed }
            let box = UncheckedBox(mediaCommand)
            Task { @MainActor [weak self] in self?.commands?.perform(command: box.value) }
            return .success
        }
        targets.append((command, target))
    }

    // MARK: Info

    private func publish() {
        guard let nowPlaying = current else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: nowPlaying.title,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue,
            MPNowPlayingInfoPropertyIsLiveStream: nowPlaying.live,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: nowPlaying.positionSeconds,
            // The rate the system advances the position at: none while paused, buffering or stopped.
            MPNowPlayingInfoPropertyPlaybackRate: nowPlaying.status == .playing ? nowPlaying.rate : 0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
        ]
        if let subtitle = nowPlaying.subtitle { info[MPMediaItemPropertyArtist] = subtitle }
        if nowPlaying.durationSeconds > 0 { info[MPMediaItemPropertyPlaybackDuration] = nowPlaying.durationSeconds }
        if let artwork = artwork?.image { info[MPMediaItemPropertyArtwork] = artwork }
        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = info
        center.playbackState = switch nowPlaying.status {
        case .playing, .buffering: .playing
        case .paused: .paused
        case .stopped: .stopped
        }
        #if DEBUG
        published = Self.describe(center)
        #endif
    }

    /// The most the artwork's download may hold: the URL is an addon's, and its answer may be anything (a video, an
    /// endless body), which would otherwise be read whole into memory.
    private nonisolated static let maxArtworkBytes = 8 * 1024 * 1024

    /// Loads the artwork once per URL and publishes again when it arrives.
    private func loadArtwork(_ url: String?) {
        guard artwork?.url != url else { return }
        artworkTask?.cancel()
        artworkTask = nil
        guard let url, let location = URL(string: url) else {
            artwork = nil
            return
        }
        artwork = (url, nil)
        artworkTask = Task { [weak self] in
            do {
                guard let data = try await Self.download(location), let image = Self.artwork(from: data) else { return }
                guard let self, !Task.isCancelled, artwork?.url == url else { return }
                artwork = (url, image)
                publish()
            } catch {
                log.debug("now playing artwork: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    /// The body at `location`, read as it arrives; nil for an answer other than 2xx, or one that says or turns out to
    /// be over `maxArtworkBytes`, whose download stops there.
    private nonisolated static func download(_ location: URL) async throws -> Data? {
        let (bytes, response) = try await URLSession.shared.bytes(from: location)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status), response.expectedContentLength <= maxArtworkBytes else {
            bytes.task.cancel()
            log.debug("now playing artwork: HTTP \(status, privacy: .public), \(response.expectedContentLength, privacy: .public) bytes, not loaded")
            return nil
        }
        var data = Data()
        if response.expectedContentLength > 0 { data.reserveCapacity(Int(response.expectedContentLength)) }
        for try await byte in bytes {
            guard data.count < maxArtworkBytes else {
                bytes.task.cancel()
                log.debug("now playing artwork: over \(maxArtworkBytes, privacy: .public) bytes, not loaded")
                return nil
            }
            data.append(byte)
        }
        return data
    }

    private nonisolated static func artwork(from data: Data) -> MPMediaItemArtwork? {
        #if os(macOS)
        guard let image = NSImage(data: data) else { return nil }
        #else
        guard let image = UIImage(data: data) else { return nil }
        #endif
        let box = UncheckedBox(image)
        return MPMediaItemArtwork(boundsSize: image.size) { _ in box.value }
    }
}
