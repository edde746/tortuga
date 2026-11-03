import Observation
import OSLog
import TortugaKit

#if os(macOS)
import AppKit
import IOKit.pwr_mgt
#else
import UIKit
#endif

private let log = Logger(subsystem: "com.edde746.tortuga", category: "player")

/// The Apple side of one playback (the shared `PlatformPlayback`), made by `AppleAppPlatform` for each one: mpv's
/// surface and the engine on it, the OS media controls (`nowPlaying`), the display following the shared output policy
/// and kept awake while the store says so (`keepDisplayAwake`), on iOS and tvOS the audio session, and on iOS the
/// video decoded only while someone sees it (`decodeVideoWhileSeen`). The shared
/// `PlaybackController` builds the session and the player store over it and ends it in order: the media controls and
/// the display first (which may sleep again), then `release()` (picture in picture stops, the audio session ends, on
/// tvOS the TV gets its own mode back unless another playback took the player's place, and on the Mac the surface
/// hides its layer and takes it out of the view tree, so nothing makes mpv draw again), then the store, and last the
/// session, which releases the engine (mpv tears down on its own queue).
///
/// Not an NSObject subclass: NSObject's (ARC-unavailable) `-release` would make the protocol's `release()` ambiguous.
@MainActor
final class ApplePlayback: @preconcurrency PlatformPlayback {
    let surface: VideoSurface
    let nowPlaying: NowPlayingCenter
    let mpv: MpvPlayerEngine
    private let outputHost: ApplePlaybackOutput
    #if os(iOS) || os(tvOS)
    private var audio: PlaybackAudioSession?
    #endif
    /// The playback's store while it runs, which hears the player go out of sight and come back and, on iOS and macOS,
    /// picture in picture come and go.
    private weak var attachedStore: PlayerStore?
    /// The observers of the player going out of sight and coming back (`followVisibility`), while the playback runs.
    private var visibilityObservers: [any NSObjectProtocol] = []
    #if os(iOS)
    /// The app is in the background (`followVisibility`, from the attach on: a playback that starts there, the next
    /// episode, starts so): mpv decodes the video only while picture in picture shows it (`decodeVideoWhileSeen`).
    private var inBackground = false
    #endif
    #if os(macOS)
    /// The PreventUserIdleDisplaySleep assertion while the display is kept awake (`keepDisplayAwake`).
    private var displayAssertion: IOPMAssertionID?
    #endif
    #if DEBUG && !os(macOS)
    let idleTimer = IdleTimerReading()
    #endif

    /// - Parameter seekSeconds: the Now Playing skip buttons' step, the player's own seek step.
    init(seekSeconds: Double) {
        surface = VideoSurface()
        mpv = MpvPlayerEngine(surface: surface)
        nowPlaying = NowPlayingCenter(seekSeconds: seekSeconds)
        outputHost = ApplePlaybackOutput(surface: surface, engine: mpv)
        #if os(iOS) || os(macOS)
        surface.pictureInPicture.follow(nowPlaying)
        #endif
    }

    var engine: any PlayerEngine { mpv }

    /// VideoToolbox; the simulators have none and always decode in software.
    var hardwareDecoder: HardwareDecoder {
        #if targetEnvironment(simulator)
        .appleSimulator
        #else
        .apple
        #endif
    }

    var mediaControls: (any MediaControlsHost)? { nowPlaying }

    var output: (any PlaybackOutputHost)? { outputHost }

    func attach(store: PlayerStore) {
        #if os(iOS) || os(tvOS)
        audio = PlaybackAudioSession(store: store) { [mpv] in mpv.reloadAudioOutput() }
        #endif
        attachedStore = store
        followVisibility()
        #if os(iOS) || os(macOS)
        followPictureInPicture()
        #endif
    }

    func release() {
        attachedStore = nil
        visibilityObservers.forEach(NotificationCenter.default.removeObserver)
        visibilityObservers = []
        // The controller lets the display sleep before this; a playback released without that holds nothing either.
        keepDisplayAwake(awake: false)
        #if os(iOS)
        surface.pictureInPicture.stop()
        #else
        surface.endPlayback()
        #endif
        #if os(iOS) || os(tvOS)
        audio?.end()
        audio = nil
        #endif
    }

    /// On the Mac one PreventUserIdleDisplaySleep assertion while `awake`, released once it isn't; on iOS and tvOS the
    /// app's idle timer off while `awake`. Each change is logged with what the OS holds.
    func keepDisplayAwake(awake: Bool) {
        #if os(macOS)
        if awake, displayAssertion == nil {
            var id = IOPMAssertionID(0)
            let result = IOPMAssertionCreateWithName(
                kIOPMAssertionTypePreventUserIdleDisplaySleep as CFString,
                IOPMAssertionLevel(kIOPMAssertionLevelOn),
                "Tortuga is playing a video" as CFString,
                &id
            )
            guard result == kIOReturnSuccess else {
                return log.error("keep awake: IOPMAssertionCreateWithName failed: 0x\(UInt32(bitPattern: result), format: .hex, privacy: .public)")
            }
            displayAssertion = id
            log.notice("keep awake: display sleep assertion \(id, privacy: .public) created")
        } else if !awake, let id = displayAssertion {
            displayAssertion = nil
            let result = IOPMAssertionRelease(id)
            guard result == kIOReturnSuccess else {
                return log.error("keep awake: IOPMAssertionRelease(\(id, privacy: .public)) failed: 0x\(UInt32(bitPattern: result), format: .hex, privacy: .public)")
            }
            log.notice("keep awake: display sleep assertion \(id, privacy: .public) released")
        }
        #else
        let application = UIApplication.shared
        if application.isIdleTimerDisabled != awake {
            application.isIdleTimerDisabled = awake
            log.notice("keep awake: \(awake, privacy: .public), isIdleTimerDisabled=\(application.isIdleTimerDisabled, privacy: .public)")
        }
        #if DEBUG
        idleTimer.summary = "idleTimerDisabled=\(application.isIdleTimerDisabled)"
        #endif
        #endif
    }

    /// The store hears the player go out of sight and come back (`PlayerStore.onVisibleChanged`) until the playback is
    /// released: on the Mac its window's occlusion (minimized, hidden, covered whole, the screen asleep), on iOS and
    /// tvOS the app going to the background and coming back.
    private func followVisibility() {
        let center = NotificationCenter.default
        #if os(macOS)
        visibilityObservers = [
            center.addObserver(forName: NSWindow.didChangeOcclusionStateNotification, object: nil, queue: .main) { [weak self] notification in
                let changed = notification.object.map { ObjectIdentifier($0 as AnyObject) }
                MainActor.assumeIsolated {
                    guard let self, let window = self.surface.window, changed == ObjectIdentifier(window) else { return }
                    self.attachedStore?.onVisibleChanged(visible: window.occlusionState.contains(.visible))
                }
            },
        ]
        #else
        #if os(iOS)
        inBackground = UIApplication.shared.applicationState == .background
        #endif
        visibilityObservers = [
            (UIApplication.didEnterBackgroundNotification, false),
            (UIApplication.willEnterForegroundNotification, true),
        ].map { name, visible in
            center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self, let store = self.attachedStore else { return }
                    store.onVisibleChanged(visible: visible)
                    #if os(iOS)
                    self.inBackground = !visible
                    self.decodeVideoWhileSeen()
                    #endif
                }
            }
        }
        #endif
    }

    #if os(iOS)
    /// Video nobody sees isn't decoded: in the background without picture in picture showing it (or starting to: it
    /// starts on its own as the app leaves), mpv plays the sound alone; back in the app, or with picture in picture up,
    /// the video again.
    private func decodeVideoWhileSeen() {
        mpv.setVideoSuspended(inBackground && !surface.pictureInPicture.showsVideo)
    }
    #endif

    #if os(iOS) || os(macOS)
    /// The store hears picture in picture come and go, the window showing the whole picture meanwhile
    /// (`PlayerStore.onPictureInPictureChanged`), until the playback is released. On iOS mpv decodes the video in the
    /// background while picture in picture shows it (`decodeVideoWhileSeen`).
    private func followPictureInPicture() {
        guard let store = attachedStore else { return }
        let pictureInPicture = surface.pictureInPicture
        let active = withObservationTracking {
            #if os(iOS)
            _ = pictureInPicture.showsVideo
            #endif
            return pictureInPicture.active
        } onChange: { [weak self] in
            // Called as `active` is about to change: the new value is read once it has.
            Task { @MainActor [weak self] in self?.followPictureInPicture() }
        }
        store.onPictureInPictureChanged(active: active)
        #if os(iOS)
        decodeVideoWhileSeen()
        #endif
    }
    #endif
}
