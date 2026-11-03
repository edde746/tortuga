import SwiftUI
import TortugaKit

#if os(macOS) || os(tvOS)
import Combine
#endif

#if os(macOS)
import Carbon.HIToolbox
#endif

#if os(tvOS)
/// A remote button as a key press and release (tvOS reports buttons whole, without repeats).
@MainActor
enum RemotePress {
    /// Whether the store's keymap used the press.
    @discardableResult
    static func send(_ key: InputKey, to store: PlayerStore) -> Bool {
        let handled = store.onInput(event: InputEventKeyDown(key: key, modifiers: KeyModifiers.companion.NONE, repeat: false))
        _ = store.onInput(event: InputEventKeyUp(key: key))
        return handled
    }
}

/// No highlight, scale or focus lift: the layer never moves over the video.
struct VideoLayerButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label }
}

/// Swipes on the Siri Remote's touch surface, as the system player takes them. Horizontal: a drag along the timeline,
/// the preview following the finger from where the bar stands (a swipe across the whole surface crosses the whole
/// video), until Select commits it or Back drops it, as the store's scrub does; only while `scrubs`. Down: the playback
/// info (the panel's Info tab); only while `opensInfo`. Everything else stays with the focus engine.
struct RemoteSwipe: UIViewRepresentable {
    let store: PlayerStore
    let scrubs: Bool
    let opensInfo: Bool

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> SwipeHost {
        let view = SwipeHost()
        view.isUserInteractionEnabled = false
        view.recognizers = [context.coordinator.scrub, context.coordinator.info]
        return view
    }

    func updateUIView(_ view: SwipeHost, context: Context) {
        context.coordinator.store = store
        context.coordinator.scrub.isEnabled = scrubs
        context.coordinator.info.isEnabled = opensInfo
    }

    static func dismantleUIView(_ view: SwipeHost, coordinator: Coordinator) {
        view.recognizers = []
    }

    /// Puts the recognizers on its window: the remote's touches go to the focused view's window, not to this view.
    final class SwipeHost: UIView {
        var recognizers: [UIGestureRecognizer] = [] {
            didSet {
                for recognizer in oldValue { recognizer.view?.removeGestureRecognizer(recognizer) }
                attach()
            }
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            attach()
        }

        private func attach() {
            guard let window else { return }
            for recognizer in recognizers where recognizer.view !== window {
                window.addGestureRecognizer(recognizer)
            }
        }
    }

    @MainActor
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        let scrub = UIPanGestureRecognizer()
        let info = UISwipeGestureRecognizer()
        var store: PlayerStore?
        /// The bar's position when the swipe began, as a fraction.
        private var start = 0.0

        override init() {
            super.init()
            let indirect = [NSNumber(value: UITouch.TouchType.indirect.rawValue)]
            scrub.allowedTouchTypes = indirect
            scrub.cancelsTouchesInView = false
            scrub.delegate = self
            scrub.addTarget(self, action: #selector(pan(_:)))
            info.direction = .down
            info.allowedTouchTypes = indirect
            info.cancelsTouchesInView = false
            info.delegate = self
            info.addTarget(self, action: #selector(swipeDown(_:)))
        }

        @objc private func pan(_ recognizer: UIPanGestureRecognizer) {
            guard let store, let window = recognizer.view else { return }
            switch recognizer.state {
            case .began:
                let timeline = store.timeline.value
                start = Double(timeline.scrub?.progress ?? timeline.progress)
            case .changed:
                let fraction = start + Double(recognizer.translation(in: window).x / max(window.bounds.width, 1))
                store.dispatch(command: PlayerCommandScrubTo(fraction: min(max(fraction, 0), 1)))
            default:
                break
            }
        }

        @objc private func swipeDown(_ recognizer: UISwipeGestureRecognizer) {
            guard let store, recognizer.state == .ended, !store.state.value.infoVisible else { return }
            store.dispatch(command: PlayerCommandToggleInfo.shared)
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard let pan = gestureRecognizer as? UIPanGestureRecognizer else { return true }
            let velocity = pan.velocity(in: pan.view)
            return abs(velocity.x) > abs(velocity.y)
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool { true }
    }
}
#endif
