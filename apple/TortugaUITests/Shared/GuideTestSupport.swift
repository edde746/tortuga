import XCTest

/// What the Apple TV and the pointer guide tests share (`GuideTests`): the test addon's guide
/// (tools/media/test_addon.py --guide): four channels, Tortuga
/// One to Four, each a row of programmes 30, 60, 90 and 120 minutes long back to back (Four's every other slot empty),
/// which play the media server's looping live HLS.
extension TortugaUITestCase {
    /// A programme's cell of the guide as one snapshot of the app read it: `guide.programme.<channel>.<index>`, its
    /// label the title, its value the times ("On now, 9:30 PM – 10:00 PM" for the one on air).
    struct GuideCellRead {
        let id: String
        let index: Int
        let title: String
        let value: String
        let frame: CGRect

        var onNow: Bool { value.hasPrefix("On now") }
    }

    /// The programmes' cells of the `channel`th channel, in the order of their index, read from one snapshot.
    func guideCells(channel: Int) -> [GuideCellRead] {
        guard let root = try? app.snapshot() else { return [] }
        let prefix = "guide.programme.\(channel)."
        var found: [GuideCellRead] = []
        func walk(_ node: any XCUIElementSnapshot) {
            if node.identifier.hasPrefix(prefix), let index = Int(node.identifier.dropFirst(prefix.count)) {
                found.append(GuideCellRead(id: node.identifier, index: index, title: node.label, value: node.value as? String ?? "", frame: node.frame))
                return
            }
            node.children.forEach(walk)
        }
        walk(root)
        return found.sorted { $0.index < $1.index }
    }

    /// The programme on now of the `channel`th channel once the guide shows it, waiting up to `timeout`; fails with
    /// `what` when none shows by then.
    @discardableResult
    func requireOnNow(channel: Int, timeout: TimeInterval = 30, _ what: String) -> GuideCellRead? {
        var onNow: GuideCellRead?
        if !waitUntil(timeout, {
            onNow = guideCells(channel: channel).first(where: \.onNow)
            return onNow != nil
        }) {
            let cells = guideCells(channel: channel)
            fail("\(what): the guide's channel \(channel) shows no programme on now (\(cells.count) programmes: \(cells.prefix(4).map(\.value)))")
        }
        return onNow
    }

    /// Fails with `what` while a programme of the `channel`th channel reads as on now, after up to `timeout` for the
    /// guide to show another day.
    func requireNothingOnNow(channel: Int, timeout: TimeInterval = 15, _ what: String) {
        if !waitUntil(timeout, { let cells = guideCells(channel: channel); return !cells.isEmpty && !cells.contains(where: \.onNow) }) {
            fail("\(what): the guide's channel \(channel) still shows a programme on now, or none at all")
        }
    }

    /// How many of Tortuga One's programmes (half-hours to midnight) come after the one on now today, by the device's
    /// clock: none in the day's last half hour, where the tests move to earlier ones instead.
    var guideProgrammesLeftToday: Int {
        Int(Calendar.current.dateInterval(of: .day, for: Date())!.end.timeIntervalSinceNow / 1800)
    }

    /// The guide's details (`guide.details.title`) once they name `title`, waiting up to 5 s; fails with `what` when
    /// they don't.
    func requireDetailsTitle(_ title: String, _ what: String) {
        let shown = element("guide.details.title")
        if !waitUntil(5, { shown.exists && text(of: shown) == title }) {
            fail("\(what): the details read \(shown.exists ? "'\(text(of: shown))'" : "nothing"), not '\(title)'")
        }
    }

    /// The day chip `index` (`guide.day.<index>`: Yesterday, Today, Tomorrow, …) once it reads as selected, waiting up to
    /// 15 s; fails with `what` when it doesn't.
    func requireDaySelected(_ index: Int, _ what: String) {
        let chip = element("guide.day.\(index)")
        if !waitUntil(15, { chip.exists && chip.isSelected }) { fail("\(what): the day chip \(index) isn't selected") }
    }

    /// The guide's channel playing: the player up and the live label's time watched at 2 s within 90 s (iOS and tvOS
    /// read the playhead probe, `Live · watched 0:07`; the Mac, where it carries no value, the live label, the pointer
    /// moving over the video to keep the controls up), and on iOS and tvOS Now Playing holding a live stream. Fails
    /// with `what`.
    func requireChannelPlaying(_ what: String) {
        let video = require("player.video", timeout: 30, "\(what): the player opens")
        var read = ""
        #if os(macOS)
        var low = false
        #endif
        let played = waitUntil(90) {
            #if os(macOS)
            low.toggle()
            pointerOn(video, at: CGVector(dx: 0.5, dy: low ? 0.45 : 0.4))
            let label = element("player.live")
            read = label.exists(within: 1) ? text(of: label) : read
            #else
            let shown = playhead()
            read = shown.isEmpty ? read : shown
            #endif
            return (Self.seconds(read) ?? 0) >= 2
        }
        if !played { fail("\(what): the live channel didn't play 2 s in 90 s (the player read '\(read)')") }
        #if !os(macOS)
        let probe = element("player.nowPlaying")
        if !waitUntil(10, { probe.exists && (probe.value as? String)?.contains("live=true") == true }) {
            fail("\(what): Now Playing holds '\(probe.value as? String ?? "nothing")', not a live stream")
        }
        #endif
    }
}
