#if os(iOS)
import SwiftUI
import UIKit

/// Debug builds: each sideways movement of a route's screen since the launch (`PageMotionTracker`), in order, as
/// `<n> <root|page> <from>><to> <frames>[ gone]`: the screen's leading edge in window points where the movement started
/// and where it came to rest (or last was, `gone`: the screen left the window, or was drawn empty, first), and how many
/// display frames it moved over. A push slides the page in from the trailing side and the root aside; a pop the other
/// way. Screenshots taken from the tests catch a push or pop that lasts a third of a second in two frames at best.
@MainActor
@Observable
final class PageMotionLog {
    static let shared = PageMotionLog()

    private(set) var entries: [String] = []

    func add(_ who: String, from: CGFloat, to: CGFloat, frames: Int, gone: Bool) {
        entries.append("\(entries.count + 1) \(who) \(Int(from.rounded()))>\(Int(to.rounded())) \(frames)\(gone ? " gone" : "")")
    }
}

/// Debug builds: the movements (`PageMotionLog`), one per line (`none` before the first), as the label of an element the
/// UI tests read (`page.motion`).
struct PageMotionLogProbe: View {
    var body: some View {
        let entries = PageMotionLog.shared.entries
        Color.clear
            .frame(width: 1, height: 1)
            .accessibilityElement()
            .accessibilityLabel(Text(verbatim: entries.isEmpty ? "none" : entries.suffix(16).joined(separator: "\n")))
            .accessibilityIdentifier("page.motion")
            .allowsHitTesting(false)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
    }
}

/// Debug builds: follows the screen it is the background of, frame by frame while it is in a window, and logs each
/// sideways movement (`PageMotionLog`): one ends once the screen has stood still for six frames, or when it leaves the
/// window. Being the screen's background, it goes when the screen draws nothing.
struct PageMotionTracker: UIViewRepresentable {
    let isRoot: Bool

    func makeUIView(context: Context) -> TrackerView {
        let view = TrackerView()
        view.who = isRoot ? "root" : "page"
        return view
    }

    func updateUIView(_ view: TrackerView, context: Context) {}

    final class TrackerView: UIView {
        var who = "page"
        private var link: CADisplayLink?
        private var last: CGFloat?
        private var start: CGFloat?
        private var frames = 0
        private var still = 0

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
        }

        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window != nil {
                guard link == nil else { return }
                let link = CADisplayLink(target: self, selector: #selector(tick))
                link.add(to: .main, forMode: .common)
                self.link = link
            } else {
                link?.invalidate()
                link = nil
                finish(gone: true)
                last = nil
            }
        }

        @objc private func tick() {
            guard let presented = layer.presentation() else { return }
            let x = presented.convert(presented.bounds, to: nil).minX
            defer { last = x }
            guard let last else { return }
            if abs(x - last) > 0.5 {
                if start == nil { start = last }
                frames += 1
                still = 0
            } else if start != nil {
                still += 1
                if still >= 6 { finish(gone: false) }
            }
        }

        private func finish(gone: Bool) {
            guard let start, let last else { return }
            PageMotionLog.shared.add(who, from: start, to: last, frames: frames, gone: gone)
            self.start = nil
            frames = 0
            still = 0
        }
    }
}
#endif
