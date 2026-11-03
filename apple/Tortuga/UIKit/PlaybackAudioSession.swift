import AVFoundation
import OSLog
import TortugaKit

private let log = Logger(subsystem: "com.edde746.tortuga", category: "player")

/// The audio session (audio session and focus are native, not shared): movie playback audio for the app's whole life
/// (`setUpForTheApp`), and for one playback active while it lasts, so the video's sound takes the route and other apps'
/// audio stops. An interruption (a call, an alarm, Siri) pauses the playback through the store; when it ends and the
/// system says to resume, the playback resumes if the interruption was what paused it. The route's output going away
/// (headphones unplugged, AirPods, a Bluetooth or an AirPlay device disconnected: `.oldDeviceUnavailable`) pauses the
/// playback rather than letting it go on from the speaker (Apple's Human Interface Guidelines, Playing audio: "when
/// disconnecting headphones, they expect playback to pause immediately"); the paused player shows its controls, and
/// nothing resumes it when the device comes back, nor when an interruption under way ends. The route is logged as the
/// playback starts and at each change (`describeRoute`): what passthrough and multichannel audio reach depends on it.
///
/// A media-services reset (rare; Settings > Developer > Reset Media Services makes one) drops the session's setup and
/// every audio object of the app (Apple's `mediaServicesWereResetNotification`): the category and mode are set again,
/// and a playback under way pauses (Apple: nothing restarts until the user does), its session active again and mpv's
/// audio output opened again, so Play has sound.
///
/// These pauses are the system's, not the viewer's (`PlayerCommandSystemPause`, the resume `PlayerCommandSystemResume`):
/// in a watch-together room a guest's device pauses alone and the room plays on, where the lock screen's Pause stays
/// refused. macOS has none of this: no audio session, interruptions or route rule.
@MainActor
final class PlaybackAudioSession {
    private let store: PlayerStore
    private let reloadAudioOutput: () -> Void
    private var observers: [any NSObjectProtocol] = []
    private var pausedByInterruption = false

    /// At launch: movie playback audio, which plays with the silent switch on and tells the system what kind of app
    /// this is, set again after each media-services reset.
    static func setUpForTheApp() {
        setCategory()
        _ = NotificationCenter.default.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated {
                log.notice("media services were reset: the audio session's category again")
                setCategory()
            }
        }
    }

    private static func setCategory() {
        let session = AVAudioSession.sharedInstance()
        #if os(tvOS)
        // The long-form profile Dolby's guide for Atmos on Apple TV prescribes, in the one call that sets it:
        // the system takes the policy only with the playback category, a movie or default mode and no options.
        do {
            try session.setCategory(.playback, mode: .moviePlayback, policy: .longFormAudio, options: [])
        } catch {
            log.error("audio session long-form profile: \(error.localizedDescription, privacy: .public)")
        }
        #endif
        if session.category != .playback {
            do {
                try session.setCategory(.playback, mode: .moviePlayback)
            } catch {
                log.error("audio session: \(error.localizedDescription, privacy: .public)")
            }
        }
        #if os(iOS)
        // 5.1 and 7.1 tracks and Dolby are content the system offers multichannel Spatial Audio for on AirPods:
        // mpv's AVFoundation output declares it only for a track above stereo.
        do {
            try session.setSupportsMultichannelContent(true)
        } catch {
            log.error("audio session multichannel content: \(error.localizedDescription, privacy: .public)")
        }
        #endif
    }

    /// - Parameter reloadAudioOutput: has mpv open its audio output again.
    init(store: PlayerStore, reloadAudioOutput: @escaping () -> Void) {
        self.store = store
        self.reloadAudioOutput = reloadAudioOutput
        activate()
        let center = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        observers = [
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] notification in
                let info = notification.userInfo
                let type = (info?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap { AVAudioSession.InterruptionType(rawValue: $0) }
                let options = AVAudioSession.InterruptionOptions(rawValue: info?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
                MainActor.assumeIsolated { self?.interrupted(type, options) }
            },
            center.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { [weak self] notification in
                let reason = (notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap { AVAudioSession.RouteChangeReason(rawValue: $0) }
                MainActor.assumeIsolated { self?.routeChanged(reason) }
            },
            center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.mediaServicesReset() }
            },
        ]
    }

    /// The playback ended: other apps' audio may resume.
    func end() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            // mpv may still be closing its audio output; the session then goes inactive with it.
            log.debug("audio session deactivation: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func activate() {
        do {
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            log.error("audio session activation: \(error.localizedDescription, privacy: .public)")
        }
        log.notice("audio route: \(Self.describeRoute(), privacy: .public)")
    }

    private func interrupted(_ type: AVAudioSession.InterruptionType?, _ options: AVAudioSession.InterruptionOptions) {
        switch type {
        case .began:
            guard !store.state.value.paused else { return }
            pausedByInterruption = true
            store.dispatch(command: PlayerCommandSystemPause.shared)
        case .ended:
            let resume = pausedByInterruption && options.contains(.shouldResume)
            pausedByInterruption = false
            guard resume else { return }
            activate()
            store.dispatch(command: PlayerCommandSystemResume.shared)
        default:
            break
        }
    }

    /// The category is set here as well as for the app (`setUpForTheApp`), whichever observer hears the reset first.
    private func mediaServicesReset() {
        log.notice("media services were reset: pausing, the audio session and output set up again")
        pausedByInterruption = false
        if !store.state.value.paused { store.dispatch(command: PlayerCommandSystemPause.shared) }
        Self.setCategory()
        activate()
        reloadAudioOutput()
    }

    private func routeChanged(_ reason: AVAudioSession.RouteChangeReason?) {
        log.notice("audio route changed (\(Self.name(reason), privacy: .public)): \(Self.describeRoute(), privacy: .public)")
        guard reason == .oldDeviceUnavailable else { return }
        pausedByInterruption = false
        guard !store.state.value.paused else { return }
        log.notice("audio route: the output device went away, pausing")
        store.dispatch(command: PlayerCommandSystemPause.shared)
    }

    /// The route as the session reports it, for the log: each output's port type, channels and Spatial Audio, the
    /// session's output channels and rate, the system's rendering mode (Dolby Atmos, Spatial Audio, surround…; iOS and
    /// tvOS 17.2 and later, and only where the system knows it: AirPlay, CarPlay) and the multichannel declaration.
    /// Without the ports' names, which can be a person's.
    private static func describeRoute() -> String {
        let session = AVAudioSession.sharedInstance()
        let outputs = session.currentRoute.outputs.map { port in
            "\(port.portType.rawValue) channels=\(port.channels?.count ?? 0) spatial=\(port.isSpatialAudioEnabled)"
        }
        var line = "outputs=[\(outputs.joined(separator: "; "))] outputChannels=\(session.outputNumberOfChannels)"
            + " maxOutputChannels=\(session.maximumOutputNumberOfChannels) sampleRate=\(Int(session.sampleRate))"
            + " multichannelContent=\(session.supportsMultichannelContent)"
        if #available(iOS 17.2, tvOS 17.2, *) {
            line += " renderingMode=\(name(session.renderingMode))"
        }
        return line
    }

    /// How the system renders the audio now (`AVAudioSession.renderingMode`, iOS and tvOS 17.2 and later), for the
    /// playback info, as the player shows it: nil while the system doesn't know (`notApplicable`: it knows only
    /// for routes it renders itself, as headphones, AirPlay and CarPlay, not HDMI) and before 17.2. Any thread.
    nonisolated static var rendering: AudioRendering? {
        guard #available(iOS 17.2, tvOS 17.2, *) else { return nil }
        return switch AVAudioSession.sharedInstance().renderingMode {
        case .monoStereo: .stereo
        case .surround: .surround
        case .spatialAudio: .spatialAudio
        case .dolbyAudio: .dolbyAudio
        case .dolbyAtmos: .dolbyAtmos
        default: nil
        }
    }

    @available(iOS 17.2, tvOS 17.2, *)
    private static func name(_ mode: AVAudioSession.RenderingMode) -> String {
        switch mode {
        case .notApplicable: "notApplicable"
        case .monoStereo: "monoStereo"
        case .surround: "surround"
        case .spatialAudio: "spatialAudio"
        case .dolbyAudio: "dolbyAudio"
        case .dolbyAtmos: "dolbyAtmos"
        @unknown default: "unknown(\(mode.rawValue))"
        }
    }

    private static func name(_ reason: AVAudioSession.RouteChangeReason?) -> String {
        guard let reason else { return "none" }
        return switch reason {
        case .newDeviceAvailable: "newDeviceAvailable"
        case .oldDeviceUnavailable: "oldDeviceUnavailable"
        case .categoryChange: "categoryChange"
        case .override: "override"
        case .wakeFromSleep: "wakeFromSleep"
        case .noSuitableRouteForCategory: "noSuitableRouteForCategory"
        case .routeConfigurationChange: "routeConfigurationChange"
        case .unknown: "unknown"
        @unknown default: "unknown(\(reason.rawValue))"
        }
    }
}
