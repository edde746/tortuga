import AppKit
import OSLog
import TortugaKit

/// The Mac's side of the forced frame-rate switch: the shared `OutputPolicy` picks a mode (with the MatchFrameRate
/// setting on, and only while the player is full screen) among the modes read here, and one playback's switch
/// (`VideoSurface`) sets it on the display its window is on, and puts the display back.
///
/// The modes offered are the refresh-rate variants of the display's current mode: the same size in points and in
/// pixels, so a switch never changes the desktop's resolution or scaling. On a MacBook Pro's built-in display these are
/// ProMotion (reported as 120 Hz) and its fixed rates (60, 59.94, 50, 48 and 47.95 Hz): ProMotion can't hold a frame
/// longer than 24 Hz's, so 23.976 fps film plays evenly only at the fixed 47.95 Hz. On an external display they are the
/// rates it offers at its current resolution. CoreGraphics reports each rate exactly (59.94 as 59.9399…), which is
/// what the policy compares; a mode whose rate it doesn't know (0) is left out.
///
/// A switch is app-scoped (`.forAppOnly`): macOS gives the display back to the user's configuration when the app quits
/// or crashes, and a restore asks for that configuration (`CGRestorePermanentDisplayConfiguration`). The player reports
/// full screen as soon as it asks for it, while the window still animates into its space: a switch asked for then
/// waits until the window is in (or drops when it leaves instead), so the display isn't reconfigured under the
/// transition.
///
/// Debug builds started with `-simulateDisplayModes YES` read and switch the simulated display
/// (`SimulatedDisplayModes`) instead.
@MainActor
final class DisplayModeSwitch: NSObject {
    /// One mode as the policy takes it: the id is CoreGraphics' `ioDisplayModeID`, the size in pixels.
    struct Mode: Equatable, Sendable {
        let id: Int32
        let width: Int32
        let height: Int32
        let refreshRate: Double

        var displayMode: DisplayMode {
            DisplayMode(id: id, width: width, height: height, refreshRate: refreshRate)
        }
    }

    /// A display's current mode and its refresh-rate variants (the current one included).
    struct Modes: Sendable {
        let current: Mode
        let variants: [Mode]
    }

    private let window: () -> NSWindow?
    /// The display shows a mode this playback set.
    private var switched = false
    /// The window is on its way into full screen.
    private var entering = false
    /// The mode to set once the window is in full screen.
    private var pending: DisplayMode?

    private static let log = Logger(subsystem: "com.edde746.tortuga", category: "display")

    /// Switches the display of `window()`'s window, read when a mode is set.
    init(window: @escaping () -> NSWindow?) {
        self.window = window
        super.init()
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(willEnterFullScreen), name: NSWindow.willEnterFullScreenNotification, object: nil)
        center.addObserver(self, selector: #selector(didEnterFullScreen), name: NSWindow.didEnterFullScreenNotification, object: nil)
        center.addObserver(self, selector: #selector(willExitFullScreen), name: NSWindow.willExitFullScreenNotification, object: nil)
    }

    /// Sets `mode` (one of `modes(on:)`'s variants), once the window is in full screen; nil puts the display back.
    func apply(_ mode: DisplayMode?) {
        guard let mode else {
            restore()
            return
        }
        if entering {
            pending = mode
        } else {
            pending = nil
            set(mode)
        }
    }

    /// Puts the display back as it was before this playback switched it.
    func restore() {
        pending = nil
        guard switched else { return }
        switched = false
        #if DEBUG
        if let simulated = SimulatedDisplayModes.shared {
            simulated.restore()
            return
        }
        #endif
        CGRestorePermanentDisplayConfiguration()
        Self.log.notice("display mode restored")
    }

    /// The current mode of `screen`'s display and its refresh-rate variants; nil where the display can't be read.
    static func modes(on screen: NSScreen?) -> Modes? {
        #if DEBUG
        if let simulated = SimulatedDisplayModes.shared { return simulated.modes(on: screen) }
        #endif
        guard let display = screen.flatMap(displayID), let current = CGDisplayCopyDisplayMode(display) else { return nil }
        return Modes(current: describe(current), variants: rateVariants(of: current, on: display).map(describe))
    }

    private func set(_ mode: DisplayMode) {
        #if DEBUG
        if let simulated = SimulatedDisplayModes.shared {
            if simulated.set(mode) { switched = true }
            return
        }
        #endif
        guard let display = window()?.screen.flatMap(Self.displayID), let current = CGDisplayCopyDisplayMode(display) else {
            Self.log.error("display mode \(Self.describe(mode), privacy: .public) not set: no display")
            return
        }
        // The window may have moved to another display since the playback read the modes: the mode must be one of
        // this display's.
        let wanted = Mode(id: mode.id, width: mode.width, height: mode.height, refreshRate: mode.refreshRate)
        guard let target = Self.rateVariants(of: current, on: display).first(where: { Self.describe($0) == wanted }) else {
            Self.log.error("display mode \(Self.describe(mode), privacy: .public) not set: the display doesn't offer it")
            return
        }
        if target.ioDisplayModeID == current.ioDisplayModeID { return }
        var config: CGDisplayConfigRef?
        guard CGBeginDisplayConfiguration(&config) == .success, let config else {
            Self.log.error("display mode \(Self.describe(mode), privacy: .public) not set: no configuration")
            return
        }
        guard CGConfigureDisplayWithDisplayMode(config, display, target, nil) == .success else {
            CGCancelDisplayConfiguration(config)
            Self.log.error("display mode \(Self.describe(mode), privacy: .public) not set: refused")
            return
        }
        let result = CGCompleteDisplayConfiguration(config, .forAppOnly)
        guard result == .success else {
            Self.log.error("display mode \(Self.describe(mode), privacy: .public) not set: error \(result.rawValue)")
            return
        }
        switched = true
        Self.log.notice("display mode \(current.refreshRate) Hz -> \(target.refreshRate) Hz")
    }

    @objc private func willEnterFullScreen(_ notification: Notification) {
        guard notification.object as? NSWindow === window() else { return }
        entering = true
    }

    @objc private func didEnterFullScreen(_ notification: Notification) {
        guard notification.object as? NSWindow === window() else { return }
        entering = false
        guard let mode = pending else { return }
        pending = nil
        set(mode)
    }

    @objc private func willExitFullScreen(_ notification: Notification) {
        guard notification.object as? NSWindow === window() else { return }
        entering = false
        pending = nil
    }

    private static func displayID(of screen: NSScreen) -> CGDirectDisplayID? {
        (screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value
    }

    /// The modes with `current`'s size in points and in pixels and a known rate. A HiDPI mode and its rate variants are
    /// listed only with the duplicate low-resolution modes shown; the pixel size tells them from their 1x twins.
    private static func rateVariants(of current: CGDisplayMode, on display: CGDirectDisplayID) -> [CGDisplayMode] {
        let options = [kCGDisplayShowDuplicateLowResolutionModes: true] as CFDictionary
        let modes = (CGDisplayCopyAllDisplayModes(display, options) as? [CGDisplayMode]) ?? []
        return modes.filter {
            $0.isUsableForDesktopGUI() && $0.refreshRate > 0 && $0.width == current.width && $0.height == current.height
                && $0.pixelWidth == current.pixelWidth && $0.pixelHeight == current.pixelHeight
        }
    }

    private static func describe(_ mode: CGDisplayMode) -> Mode {
        Mode(id: Int32(mode.ioDisplayModeID), width: Int32(mode.pixelWidth), height: Int32(mode.pixelHeight), refreshRate: mode.refreshRate)
    }

    private static func describe(_ mode: DisplayMode) -> String {
        "\(mode.width)x\(mode.height)@\(mode.refreshRate)"
    }
}
