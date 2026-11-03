import AVFoundation
import SwiftUI
import TortugaKit
import UIKit

#if os(tvOS)
import AVKit
#endif

/// The view mpv's avfoundation VO presents into (iOS/tvOS). The `AVSampleBufferDisplayLayer` is mpv's `wid`; it sits
/// in a plain container because vo_avfoundation adds its subtitle/OSD layer to the display layer's superlayer.
/// The container exists before playback starts, so that superlayer is in place when the VO comes up.
///
/// The screen follows the shared output policy (`apply`, from the playback's `PlaybackOutputHost`) for the format of
/// the video on it (`present`, from the engine): EDR on iOS, `AVDisplayManager` criteria on tvOS.
final class VideoSurface: UIView {
    private let displayLayer: AVSampleBufferDisplayLayer
    #if os(tvOS)
    /// The TV's criteria, one for the app: the TV has one mode, and a playback that replaces another starts from the
    /// mode that one asked for.
    private static let displayCriteria = DisplayCriteria()
    /// The surfaces made so far, one per playback: the criteria are the latest one's (`endPlayback`).
    private static var made = 0
    /// This surface's place among them.
    private let number: Int
    /// Who waits for the display since a format was shown before the surface was in a window (`didMoveToWindow`).
    private var waitingForWindow: [@Sendable () -> Void] = []
    #endif
    #if os(iOS)
    /// Picture in picture from the display layer.
    let pictureInPicture: PictureInPicture
    #endif

    /// The shared policy's latest plan for this playback; nil until the device settings are read.
    private var plan: OutputPlan?
    /// The format of the video on the surface, since the engine presented it.
    private var format: DisplayFormat?
    #if os(iOS)
    /// The dynamic range the display layer shows the video in (EDR on for an HDR one), since a plan showed it.
    private var shownRange: DynamicRange?
    #endif
    /// Called when the dynamic range `displayOutput()` reports may have changed (a format shown, a plan applied, the
    /// display handed back): the engine keeps what the display gets from it.
    var onOutputRangeChange: (@MainActor () -> Void)?

    /// The layer handed to mpv as `wid`.
    var renderLayer: CALayer { displayLayer }

    init() {
        let displayLayer = AVSampleBufferDisplayLayer()
        self.displayLayer = displayLayer
        #if os(iOS)
        pictureInPicture = PictureInPicture(layer: displayLayer)
        #endif
        #if os(tvOS)
        Self.made += 1
        number = Self.made
        #endif
        super.init(frame: .zero)
        backgroundColor = .black
        // Input belongs to the SwiftUI overlay above.
        isUserInteractionEnabled = false
        // Smart Invert leaves the video as it is.
        accessibilityIgnoresInvertColors = true
        displayLayer.videoGravity = .resizeAspect
        displayLayer.isOpaque = true
        displayLayer.backgroundColor = UIColor.black.cgColor
        layer.addSublayer(displayLayer)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func layoutSubviews() {
        super.layoutSubviews()
        displayLayer.place(in: bounds, scale: traitCollection.displayScale)
    }

    /// The shared policy decided how this playback's video shows (`OutputPlan`). A format shows by the plan's HDR
    /// output alone (`show`), which every plan of a playback shares (the device settings and the display are read once
    /// per playback), so a plan shows the video on the surface again only when it changes: the first plan, if the
    /// engine presented a format before it. A plan for the next file's video, which comes before that file's format,
    /// leaves the screen to its `present`, rather than showing the last file's format by it.
    func apply(_ plan: OutputPlan) {
        let shown = self.plan
        self.plan = plan
        guard let format else { return }
        guard plan.hdrOutput != shown?.hdrOutput else { return }
        show(format, completion: {})
    }

    /// Sets the screen up for `format` and calls `completion` once it is ready to show it (the engine starts the file
    /// then). On iOS picture in picture reads the layer again, the video being up, and the iPhone's player turns for
    /// the video's shape (`PlayerOrientation`).
    func present(_ format: DisplayFormat, completion: @escaping @Sendable () -> Void) {
        self.format = format
        #if os(iOS)
        pictureInPicture.videoShown()
        PlayerOrientation.videoShown(tall: format.tall)
        #endif
        #if DEBUG && os(tvOS)
        let completion = DisplaySwitchReading.countingReady(completion)
        #endif
        show(format, completion: completion)
    }

    /// Hands the screen back when the playback's output is restored (`ApplePlaybackOutput.restore`): EDR off on iOS;
    /// on tvOS the TV's criteria go once the playback ends (`endPlayback`). A format the engine presents after this
    /// changes nothing.
    func resetDisplay() {
        plan = nil
        format = nil
        #if os(iOS)
        shownRange = nil
        displayLayer.showExtendedDynamicRange(false)
        #endif
        onOutputRangeChange?()
    }

    #if os(tvOS)
    /// The playback ended (`ApplePlayback.release`), whether or not a plan came: the TV gets its own mode back a
    /// main-queue turn later, unless another playback's surface was made meanwhile. A playback that replaces this one
    /// is made in the same turn (`PlaybackController.open`), so the TV goes from this one's mode straight to the next
    /// one's: one switch, none when they match.
    func endPlayback() {
        let number = number
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                guard number == Self.made else { return }
                Self.displayCriteria.clear(reason: "playback ended")
            }
        }
    }
    #endif

    /// The screen as the playback info shows it. iOS: the screen's size in pixels the way it is turned, its refresh
    /// rate (`maximumFramesPerSecond`, 120 Hz on a ProMotion screen) and the dynamic range the display layer shows the
    /// video in (an HDR format with EDR on, else SDR). tvOS: the TV's resolution (`currentMode`; Match Content leaves
    /// it alone) and, while Match Content is on and criteria are out, the refresh rate and dynamic range they asked the
    /// TV for; otherwise the rate of the video format chosen in the tvOS settings (`maximumFramesPerSecond`) and no
    /// dynamic range, which no API reports. Nothing before the surface is in a window.
    func displayOutput() -> DisplayOutput {
        guard let window else { return DisplayOutput() }
        #if os(tvOS)
        let screen = window.screen
        let size = screen.currentMode?.size
        let requested = displayManager(window).isDisplayCriteriaMatchingEnabled ? Self.displayCriteria.requested : nil
        return DisplayOutput(
            width: size.map { Int($0.width) },
            height: size.map { Int($0.height) },
            refreshRate: requested?.refreshRate ?? Double(screen.maximumFramesPerSecond),
            dynamicRange: requested?.range
        )
        #else
        guard let screen = window.windowScene?.screen else { return DisplayOutput() }
        // `nativeBounds` is the panel upright; `bounds` turns with the interface.
        let pixels = screen.nativeBounds.size
        let long = Int(max(pixels.width, pixels.height))
        let short = Int(min(pixels.width, pixels.height))
        let landscape = screen.bounds.width > screen.bounds.height
        return DisplayOutput(
            width: landscape ? long : short,
            height: landscape ? short : long,
            refreshRate: Double(screen.maximumFramesPerSecond),
            dynamicRange: shownRange
        )
        #endif
    }

    /// Shows the picture with `fit` and `zoom` (the player's `VideoLayout`, from the engine) on the display layer
    /// (`AVSampleBufferDisplayLayer.show`), the container clipping it only while zoomed.
    func show(fit: VideoFit, zoom: Double, stretchX: Double, stretchY: Double) {
        displayLayer.show(fit: fit, zoom: zoom, stretchX: stretchX, stretchY: stretchY) { clipsToBounds = $0 }
    }

    /// `format` as the plan has it: HDR only where the plan lets the playback present HDR (the Hdr setting is on and
    /// the screen can show it) and the video is HDR. tvOS: `AVDisplayManager` criteria for the dynamic range and the
    /// video's rate (the TV's current rate for a video that reports none), whatever the app's settings: the TV switches
    /// only as its own Match Content setting allows. Ready once the display has settled for them; before the surface
    /// is in a window, once it is (`didMoveToWindow`). iOS: EDR on the display layer, ready at once. Before the first
    /// plan nothing changes.
    private func show(_ format: DisplayFormat, completion: @escaping @Sendable () -> Void) {
        guard let plan else { return completion() }
        let range = plan.hdrOutput ? format.dynamicRange : .sdr
        #if os(tvOS)
        guard let window else { return waitingForWindow.append(completion) }
        let refreshRate = format.refreshRate > 0 ? format.refreshRate : Double(window.screen.maximumFramesPerSecond)
        Self.displayCriteria.apply(
            format,
            range: range,
            refreshRate: refreshRate,
            manager: displayManager(window),
            completion: completion
        )
        onOutputRangeChange?()
        #else
        shownRange = range
        displayLayer.showExtendedDynamicRange(range != .sdr)
        onOutputRangeChange?()
        completion()
        #endif
    }

    #if os(tvOS)
    /// The player's cover comes up only once the Streams sheet under it has gone, and a file can be ready sooner: the
    /// format shows, and whoever waits for the display (`waitingForWindow`) is called, once the surface is in the
    /// window, so the file still starts after the display has settled for it.
    override func didMoveToWindow() {
        super.didMoveToWindow()
        guard window != nil, let format, !waitingForWindow.isEmpty else { return }
        let waiting = waitingForWindow
        waitingForWindow = []
        show(format) { waiting.forEach { $0() } }
    }

    /// The window's display manager; in debug builds started with `-simulateMatchContent YES`, the simulated one.
    private func displayManager(_ window: UIWindow) -> any DisplayManaging {
        #if DEBUG
        if let simulated = SimulatedMatchContent.shared { return simulated }
        #endif
        return window.avDisplayManager
    }
    #endif
}

/// Hosts the long-lived [VideoSurface] in SwiftUI.
struct VideoSurfaceHost: UIViewRepresentable {
    let surface: VideoSurface

    func makeUIView(context: Context) -> VideoSurface { surface }
    func updateUIView(_ uiView: VideoSurface, context: Context) {}
}
