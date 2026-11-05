import AppKit
import SwiftUI

/// The window's title bar on the toolbar's band where nothing there takes a click: under a header row's empty parts on
/// the band (`screenHeader`) and over the band of a screen without one (`windowToolbarBand`). What the screen draws over
/// it on the band (a filter, an action, Search's field) takes its own clicks: SwiftUI's hit test gives them to the
/// control first. A drag moves the window (`performDrag`), a click in an inactive window acts at once, as on the title
/// bar, and a double click does what the title bar's does (`TitleBarDoubleClick`).
struct WindowDragArea: NSViewRepresentable {
    func makeNSView(context: Context) -> DragView {
        DragView()
    }

    func updateNSView(_ nsView: DragView, context: Context) {}

    final class DragView: NSView {
        override var mouseDownCanMoveWindow: Bool { true }

        override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

        override func mouseDown(with event: NSEvent) {
            // A double click's second press moves nothing: its release is the title bar's double click.
            guard event.clickCount != 2 else { return }
            window?.performDrag(with: event)
        }

        override func isAccessibilityElement() -> Bool { false }
    }
}

/// A double click on the toolbar's band where nothing takes it does what a double click on the title bar does, as
/// System Settings → Desktop & Dock has it (`AppleActionOnDoubleClick`): zoom (`Maximize`), fill, which has no public
/// API and zooms too, minimize, or nothing; unset, the older `AppleMiniaturizeOnDoubleClick` minimizes, else it zooms.
///
/// The window's content reaches up under its transparent title bar, so a click on the band lands on the content's views
/// (`WindowDragArea`, a screen's scroll view, a toolbar's space). Through macOS 27.0 a gesture on the window's frame view
/// took every double click there, whatever view it landed on. On macOS 27.2 the title bar's own view has that gesture,
/// which a click on the content never reaches, and no double click on the band's empty parts did anything, the toolbar's
/// spaces included. So the app acts once the double click's release has gone through the window, and only where macOS
/// hasn't: macOS's zoom and fill have changed the window's frame by then, and its minimize, which starts a moment later,
/// and the app's make one.
@MainActor
enum TitleBarDoubleClick {
    private static var monitor: Any?

    /// Watches the app's clicks from launch on.
    static func install() {
        monitor = NSEvent.addLocalMonitorForEvents(matching: .leftMouseUp) { event in
            // On the main thread, before the event goes to its window.
            MainActor.assumeIsolated { released(event) }
            return event
        }
    }

    private static func released(_ event: NSEvent) {
        guard event.clickCount == 2, let window = event.window, onEmptyBand(event.locationInWindow, of: window) else { return }
        let frame = window.frame
        // After the release has gone through the window, where macOS's own double click acts.
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                if window.frame == frame { act(on: window) }
            }
        }
    }

    /// Whether a click at `point` (in the window) is on the band, the title bar's height of a window whose content
    /// reaches up under it, outside full screen, and on nothing there that takes a click: on a `WindowDragArea`, or on
    /// the title bar's own views but the window's buttons and the toolbar's items. Anywhere else in the content (a
    /// control on the band, the player's video) the click is the content's.
    private static func onEmptyBand(_ point: NSPoint, of window: NSWindow) -> Bool {
        guard window.styleMask.contains(.fullSizeContentView), !window.styleMask.contains(.fullScreen),
              point.y >= window.contentLayoutRect.maxY,
              let content = window.contentView, let frameView = content.superview,
              // The frame view is the window's top view: its hit test takes the point in the window.
              let hit = frameView.hitTest(point)
        else { return false }
        if hit is WindowDragArea.DragView { return true }
        if hit.isDescendant(of: content) { return false }
        if window.toolbar?.items.contains(where: { $0.view.map(hit.isDescendant(of:)) ?? false }) == true { return false }
        // The window's buttons take their clicks; its title, a label, is the title bar's.
        return !sequence(first: hit, next: \.superview).contains { view in
            guard let control = view as? NSControl else { return false }
            guard let label = control as? NSTextField else { return true }
            return label.isEditable || label.isSelectable
        }
    }

    private static func act(on window: NSWindow) {
        let defaults = UserDefaults.standard
        switch defaults.string(forKey: "AppleActionOnDoubleClick") {
        case "Minimize": window.performMiniaturize(nil)
        case "None": break
        case nil where defaults.bool(forKey: "AppleMiniaturizeOnDoubleClick"): window.performMiniaturize(nil)
        default: window.performZoom(nil)
        }
    }
}

/// The title bar in full screen as in a window: see-through, the screen's content (Home's and Detail's artwork, the
/// sidebar) running up under the toolbar's band and the toolbar's items over it. In full screen AppKit moves the title
/// bar and the toolbar into a window of their own over the top of the screen (`NSToolbarFullScreenWindow`) and draws
/// the title bar's background there, opaque, in the window's background colour (a dark grey in the app's dark
/// appearance), whatever `titlebarAppearsTransparent` and SwiftUI's toolbar background say: a band as tall as the
/// toolbar across the whole screen, the content laid out under it hidden (macOS 27.2). AppKit's own switch for it,
/// `NSWindow`'s `titlebarAppearsTransparentWhenFullscreen`, has no public API; the Mac app ships outside the Mac App
/// Store, where private API is acceptable (Tortuga-Bridging-Header.h). Where AppKit has no such switch nothing changes.
/// AppKit reads it as it sets up the title bar's transparency: on the way into full screen, and in full screen as
/// `titlebarAppearsTransparent` is set.
@MainActor
enum FullScreenTitleBar {
    private static let key = "titlebarAppearsTransparentWhenFullscreen"

    /// Whether `window`'s title bar is see-through in full screen; nil where AppKit has no such switch.
    static func isTransparent(_ window: NSWindow) -> Bool? {
        window.responds(to: NSSelectorFromString(key)) ? window.value(forKey: key) as? Bool : nil
    }

    /// Makes `window`'s title bar see-through in full screen (`transparent`) or opaque, at once where the window is full
    /// screen.
    static func setTransparent(_ transparent: Bool, _ window: NSWindow) {
        guard let current = isTransparent(window), current != transparent else { return }
        window.setValue(transparent, forKey: key)
        guard window.styleMask.contains(.fullScreen) else { return }
        let shown = window.titlebarAppearsTransparent
        window.titlebarAppearsTransparent = !shown
        window.titlebarAppearsTransparent = shown
    }
}

/// Makes the window's title bar see-through in full screen (`FullScreenTitleBar`) under a screen of `RouteStack`, whose
/// toolbar draws no background (`windowToolbarBand`), while the window shows that toolbar: the player hides it and
/// keeps its own title bar opaque in full screen (`PlayerWindowChrome`).
struct FullScreenTitleBarTransparency: NSViewRepresentable {
    func makeNSView(context: Context) -> WindowView {
        WindowView()
    }

    func updateNSView(_ nsView: WindowView, context: Context) {}

    final class WindowView: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard let window, window.toolbar?.isVisible != false else { return }
            FullScreenTitleBar.setTransparent(true, window)
        }

        override func hitTest(_ point: NSPoint) -> NSView? { nil }

        override func isAccessibilityElement() -> Bool { false }
    }
}
