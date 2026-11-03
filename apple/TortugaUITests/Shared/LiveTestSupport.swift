import XCTest

/// What the iPhone, iPad, Mac and Apple TV live tests share (each PlayerTests' `testLive…`): the live test addon's
/// channels (tools/media/test_addon.py --live, served on
/// tools/media/serve.py's looping live HLS), `tv` titles and so live from the start. The 24/7 channel's playlist keeps
/// 12 s, too little to rewind in (layout C); the match's keeps 90 s, its rewind window. The reads of the player need
/// its controls up, which the test brings up first.
extension TortugaUITestCase {
    /// The channel's details with its Streams over Home (`--dev-open`).
    static let liveChannelOpen = ["board", "detail:tv:tortuga-live-247", "streams:tv:tortuga-live-247:tortuga-live-247"]

    /// The Streams row of the channel's plain stream (its description "Live / Plain": no headers, no failure); fails with
    /// `what` when the list doesn't show it.
    func livePlainStream(_ what: String) -> XCUIElement {
        let rows = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'streams.row.'"))
        let plain = rows.matching(NSPredicate(format: "label CONTAINS 'Plain'")).firstMatch
        if !plain.exists(within: 60) {
            fail("\(what): no stream row reads Plain among \(rows.allElementsBoundByIndex.map { "\($0.identifier) '\($0.label)'" })")
        }
        return plain
    }

    /// The live label (`player.live`) the controls show: its time watched in seconds. Fails with `what` unless it reads
    /// the store's live text with a time (`Live · watched 0:07`; drawn as ● LIVE · 0:07).
    @discardableResult
    func requireLiveLabel(_ what: String) -> Double {
        let label = element("player.live")
        guard label.exists(within: 5) else {
            fail("\(what): the controls show no live label (player.live)")
            return 0
        }
        let read = text(of: label)
        guard read.hasPrefix("Live · watched "), let watched = Self.seconds(read) else {
            fail("\(what): the live label reads '\(read)', not 'Live · watched <time>'")
            return 0
        }
        return watched
    }

    /// Fails with `what` when the controls show any part of a video's timeline: the bar, its times, back or forward.
    func requireNoTimeline(_ what: String) {
        let shown = ["player.scrub", "player.position", "player.remaining", "player.rewind", "player.fastForward"]
            .filter { element($0).exists }
        if !shown.isEmpty { fail("\(what): the live controls show \(shown.joined(separator: ", "))") }
    }

    /// The engine's position as the timeline has it, which a live playback shows nowhere: the Debug build's probe
    /// `player.livePosition` (`position=12.3`). Fails with `what` without it.
    func requireLivePosition(_ what: String) -> Double {
        let probe = element("player.livePosition")
        let value = probe.exists(within: 5) ? probe.label : "(no live position probe)"
        guard value.hasPrefix("position="), let position = Double(value.dropFirst("position=".count)) else {
            fail("\(what): the live position probe reads '\(value)', not position=<seconds>")
            return 0
        }
        return position
    }

    /// The live state read once the platform's seek inputs went in, after `before` (position, time watched): fails with
    /// `what` when the position is behind where it was (a seek back of the step puts it there) or the label reads a
    /// time watched below the one before (something went back or started over). A position still where it was is the
    /// stream stalling (the player buffering a late segment), not a seek.
    func requireSeeksIgnored(since before: (position: Double, watched: Double), _ what: String) {
        let position = requireLivePosition(what)
        let watched = requireLiveLabel(what)
        if position < before.position {
            fail("\(what): the live position went back from \(before.position) s to \(position) s: a seek moved it")
        }
        if watched < before.watched { fail("\(what): the time watched went from \(before.watched) s to \(watched) s") }
    }

    /// The channel's details once the player closed (`play`: their Play): fails with `what` when Play offers to resume
    /// the channel, as a video left partway would, at any time in the 1.5 s after the close.
    func requireLiveNotResumed(_ play: XCUIElement, _ what: String) {
        var read = ""
        if !holds(for: 1.5, { read = text(of: play); return !read.hasPrefix("Resume") }) {
            fail("\(what): the channel's details read '\(read)': a live playback left a position")
        }
    }

    /// Home once the player closed: fails with `what` when Continue Watching lists anything (the session's one
    /// playback was the live channel's) at any time in the 3 s after Home's shelves show. Its shelves show first, so
    /// an empty Continue Watching isn't a Home still loading.
    func requireNoContinueWatching(_ what: String) {
        if !app.scrollViews.staticTexts.firstMatch.exists(within: 30) { fail("\(what): Home shows no shelf") }
        let card = element("board.cw.0")
        if !holds(for: 3, { !card.exists }) { fail("\(what): Continue Watching lists '\(card.label)'") }
    }

    /// The match's details with its Streams over Home (`--dev-open`): its one stream's playlist keeps
    /// `liveMatchWindow` s (test_addon.py's LIVE_MATCH_WINDOW) to rewind in.
    static let liveMatchOpen = ["board", "detail:tv:tortuga-live-match", "streams:tv:tortuga-live-match:tortuga-live-match"]
    static let liveMatchWindow = 90.0
    /// What the window's start reads (s): the playlist lists the segments that started in the last 90 s up to the
    /// newest complete one, 43 or 44 of its 2.002-s and 1.968-s segments (45 span more than 90 s), 85.92 to 87.95 s,
    /// shown in whole seconds rounded down (the iPad: −1:25).
    static let liveWindowRange = 85.0...90.0
    /// How far behind the edge a seek 30 s back from it lands (s): a reopen starts at a segment's start (2 s), and the
    /// edge moves on while it opens (the Android tests' LIVE_BEHIND_RANGE).
    static let liveBehindRange = 26.0...50.0
    /// How far the time behind may drift while the match plays behind the edge (s): it reads whole seconds, and the
    /// playlist's end moves on by whole segments.
    static let liveDrift = 2.5
    /// How far mpv's position in the open file must be for a seek 30 s back to lie within what it holds (s: the 30 s,
    /// the window's length changing by a segment meanwhile, and a segment more). Going to live seeks within what mpv
    /// read ahead, or reopens the stream at the edge when that falls short of a segment past it
    /// (`PlaybackSession.seekLive`), and the file then holds only what played since.
    static let liveHeldSeconds = 35.0

    /// The match's place in its window as the store gives it (`TimelineState.positionLabel`): 0 at the live edge
    /// (`Live · watched 0:07`), else how far behind it, in seconds (`Live · 0:38 behind`); nil when nothing reads so.
    /// iOS and tvOS read the playhead probe (`playhead`), there with the controls down too; the Mac the go-to-live
    /// button's value (`player.live`), the pointer moved over the video to keep the controls up.
    func liveBehind() -> Double? {
        #if os(macOS)
        _ = playerPosition()
        guard let read = (try? element("player.live").snapshot())?.value as? String else { return nil }
        #else
        let read = playhead()
        #endif
        if read.hasPrefix("Live · watched ") { return 0 }
        return read.hasSuffix(" behind") ? Self.seconds(read) : nil
    }

    /// The engine's loads so far (a rewind's reopen is one) and mpv's position in the open file (`EngineReading`), off
    /// the probe `player.engine`. Fails with `what` when it doesn't read so.
    func requireEngine(_ what: String) -> (loads: Int, position: Double) {
        let read = (try? element("player.engine").snapshot())?.label ?? "nothing"
        var fields: [Substring: Substring] = [:]
        for field in read.split(separator: " ") {
            let pair = field.split(separator: "=", maxSplits: 1)
            if pair.count == 2 { fields[pair[0]] = pair[1] }
        }
        guard let loads = fields["loads"].flatMap({ Int($0) }), let position = fields["position"].flatMap({ Double($0) }) else {
            fail("\(what): the engine reads '\(read)', not loads=<n> position=<s>")
            return (0, 0)
        }
        return (loads, position)
    }

    /// The controls, up, with the match's window: its timeline (`player.scrub`) from where the window starts on the
    /// left (`player.position`, `liveWindowRange`), the go-to-live button (`player.live`), no time left and no ±10 s
    /// buttons. Fails with `what`.
    func requireLiveWindow(_ what: String) {
        if !element("player.scrub").exists(within: 15) { fail("\(what): the controls show no timeline for the window") }
        let start = element("player.position")
        let read = start.exists ? text(of: start) : "nothing"
        let window = read.hasPrefix("−") ? Self.seconds(String(read.dropFirst())) : nil
        if window.map({ !Self.liveWindowRange.contains($0) }) ?? true {
            fail("\(what): the window's start reads '\(read)', not −1:25 to −1:30")
        }
        let shown = ["player.remaining", "player.rewind", "player.fastForward"].filter { element($0).exists }
        if !shown.isEmpty { fail("\(what): the window's controls show \(shown.joined(separator: ", "))") }
        let live = element("player.live")
        if !live.exists || live.label != "Go to live" {
            fail("\(what): the live label reads '\(live.exists ? live.label : "nothing")', not the go-to-live button")
        }
    }

    /// Waits up to 15 s for the match to read at the live edge (`liveBehind` 0) and play there (`requirePlayingAgain`),
    /// then for it to stay there 5 s while mpv's position moves on. Fails with `what`.
    func requireAtLiveEdge(_ what: String) {
        var behind: Double?
        if !waitUntil(15, { behind = liveBehind(); return behind == 0 }) {
            fail("\(what): the match reads \(behind.map { "\($0) s behind the edge" } ?? "nothing"), not live at the edge")
            return
        }
        requirePlayingAgain(what)
        let from = requireEngine(what)
        var now: Double?
        if !holds(for: 5, { now = liveBehind(); return now.map { $0 == 0 } ?? true }) {
            fail("\(what): the match fell \(now ?? 0) s behind the edge as it played")
            return
        }
        let to = requireEngine(what)
        if to.position < from.position + 3 { fail("\(what): mpv went from \(from.position) s to \(to.position) s in 5 s at the edge") }
    }

    /// After a seek 30 s back from the edge: waits up to 15 s for the match to read `liveBehindRange` s behind the edge
    /// and play there (`requirePlayingAgain`), then for the time behind to hold 5 s (within `liveDrift`) while mpv's
    /// position moves on. Fails with `what`.
    func requireBehindLiveEdge(_ what: String) {
        let range = "\(Int(Self.liveBehindRange.lowerBound))-\(Int(Self.liveBehindRange.upperBound))"
        var behind: Double?
        if !waitUntil(15, { behind = liveBehind(); return behind.map(Self.liveBehindRange.contains) ?? false }) {
            fail("\(what): the match reads \(behind.map { "\($0) s behind the edge" } ?? "nothing"), not \(range) s behind")
            return
        }
        requirePlayingAgain(what)
        behind = liveBehind()
        guard let first = behind, Self.liveBehindRange.contains(first) else {
            fail("\(what): playing again, the match reads \(behind.map { "\($0) s behind the edge" } ?? "nothing"), not \(range) s behind")
            return
        }
        let from = requireEngine(what)
        var later = behind
        let held = holds(for: 5) {
            later = liveBehind()
            return later.map { abs($0 - first) <= Self.liveDrift } ?? true
        }
        let to = requireEngine(what)
        if !held || later == nil {
            fail("\(what): the time behind went from \(first) s to \(later.map { "\($0) s" } ?? "nothing") within 5 s")
        }
        if to.position < from.position + 3 { fail("\(what): mpv went from \(from.position) s to \(to.position) s in 5 s behind the edge") }
    }

    /// Waits up to 15 s for mpv's position to move on between two reads (`requireEngine`): a seek or a reopen has
    /// landed and plays. The store reads the place asked for at once, while a reopen opens the stream and buffers, the
    /// clock running on (the Mac: 29 s behind read during the reopen became 33 s once it played). Fails with
    /// `what`.
    func requirePlayingAgain(_ what: String) {
        var last = requireEngine(what).position
        var now = last
        let moving = waitUntil(15) {
            now = requireEngine(what).position
            defer { last = now }
            return now > last + 0.3
        }
        if !moving { fail("\(what): mpv's position stays at \(now) s: the match doesn't play") }
    }

    /// A seek 30 s back from the edge by the platform's input (`seek`), the engine read before it: the match plays
    /// behind the edge (`requireBehindLiveEdge`), the engine having reopened the stream at an earlier segment when
    /// `reopened` (the first seek, before what mpv read since the file opened), else, once mpv's position in the open
    /// file is `liveHeldSeconds` (up to 60 s), seeked within what mpv holds, no reopen, its position back by at least
    /// 20 s of the 30 (the time since counted in). Fails with `what`.
    func requireSeekBack(reopened: Bool, _ what: String, seek: () -> Void) {
        if !reopened, !waitUntil(60, { requireEngine("\(what), before it").position >= Self.liveHeldSeconds }) {
            fail("\(what): mpv's position in the open file stays short of \(Self.liveHeldSeconds) s")
        }
        let before = requireEngine("\(what), before it")
        let asked = Date()
        seek()
        requireBehindLiveEdge(what)
        let after = requireEngine(what)
        let back = before.position + Date().timeIntervalSince(asked) - after.position
        let moves = "loads \(before.loads), then \(after.loads); mpv went from \(before.position) s to \(after.position) s"
        if reopened, after.loads <= before.loads { fail("\(what): no reopen: \(moves)") }
        if !reopened, after.loads != before.loads || back < 20 {
            fail("\(what): not a seek within what mpv holds: \(moves), \(Int(back)) s back")
        }
    }
}
