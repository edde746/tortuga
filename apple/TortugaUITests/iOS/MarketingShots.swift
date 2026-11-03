import XCTest

/// The App Store shots on iPhone and iPad, by touch, one test per shot of the store screenshot list, each from a
/// launch of its own (`MarketingTestCase`) in the pass's language:
/// Home with Continue Watching full (`home`), the configured title's Detail screen with its progress (`detail`), the
/// configured video's Streams sheet over its Detail screen with every addon's streams (`streams`), the player paused on
/// the configured subtitle line, or at the configured frame it resumed at, with the controls up (`player`), Discover on
/// the addon's first catalog with the configured genre picked (`discover`), Search's results for the configured query
/// (`search`), the Library (`library`) and Watch together in the configured room, joined by its invite (`watch`). The
/// iPad lies in landscape throughout; the iPhone stands in portrait but for the player.
final class MarketingShots: MarketingTestCase {
    func testHome() throws {
        try launchMarketing(progress: true)
        orient()
        let first = require("board.cw.0", timeout: 90, "Home shows Continue Watching")
        // The cards' posters load.
        marketingShot("home", "Home: Continue Watching from \(first.label), the catalogs below", landscape: isPad)
    }

    func testDetail() throws {
        try launchMarketingDetail()
        orient()
        let play = require("detail.play", timeout: 60, "the title's Detail screen shows Play")
        marketingShot("detail", "the Detail screen: \(play.label), the episode's progress", landscape: isPad)
    }

    func testStreams() throws {
        try launchMarketingStreams()
        orient()
        let row = require("streams.row.0", timeout: 90, "the Streams sheet lists the addons' streams")
        try requireEveryAddonsStreams("the Streams sheet lists every addon's streams")
        marketingShot("streams", "the Streams sheet over the title's Detail screen, first \(row.label)", landscape: isPad)
    }

    func testPlayer() throws {
        if let frame = try marketingFrame { return try shootPlayer(at: frame) }
        let line = try marketingLine
        guard let seconds = line.shownSeconds else {
            throw HarnessError("the line at \(line.label) holds no whole second of the position label")
        }
        let video = try openPlayer()
        requirePlayback(1, timeout: 120)
        pause(video)
        if !marketingSubtitleNames.isEmpty { pickSubtitle(video) }
        pause(video)
        if !seek(into: seconds) {
            fail("taps on the timeline didn't land within \(line.label) (at \(playerPosition().map(Self.clock) ?? "no position"))")
        }
        // The frame decoded and its line drawn, lifted above the controls.
        marketingShot("player", "paused on the line at \(line.label) with the controls up", at: playerPosition(), landscape: true)
    }

    /// The player resumed at the capture's frame (`TORTUGA_MARKETING_PLAY_PROGRESS`), paused while its stream still
    /// loads, so the picture is the resume point's, within `frame`, the shot that holds it; fails where it stands outside.
    private func shootPlayer(at frame: ClosedRange<Double>) throws {
        let video = try openPlayer()
        // The controls stay up while the stream opens: play/pause, tapped then, opens it paused at the resume point.
        ready(require("player.playPause", timeout: 10, "the player shows play/pause while the stream opens")).tap()
        if !waitUntil(60, { self.playerPaused }) { fail("play/pause, tapped while the stream opened, doesn't pause it") }
        // The paused position, once the stream has opened there.
        requirePlayback(frame.lowerBound - 1, timeout: 120)
        guard let position = playerPosition(), frame.contains(position) else {
            return fail("paused at \(playerPosition().map(Self.clock) ?? "no position"), outside the frame's shot \(Self.clock(frame.lowerBound))–\(Self.clock(frame.upperBound))")
        }
        if !marketingSubtitleNames.isEmpty { pickSubtitle(video) }
        marketingShot("player", "paused at the frame resumed at \(Self.clock(frame.lowerBound)) with the controls up", at: position, landscape: true)
    }

    /// Opens the player on the Streams sheet's first stream, the iPhone turned to landscape.
    private func openPlayer() throws -> XCUIElement {
        try launchMarketingStreams()
        orient(player: true)
        require("streams.row.0", timeout: 90, "the Streams sheet lists the addons' streams")
        try requireEveryAddonsStreams("the Streams sheet lists every addon's streams")
        ready(require("streams.row.0", "the Streams sheet's first stream")).tap()
        return require("player.video", timeout: 30, "the stream opens the player")
    }

    func testDiscover() throws {
        let genre = try marketingDiscover
        try launchMarketing(open: ["discover"], progress: false)
        orient()
        requireCard("discover.item.0", timeout: 60, "Discover shows the addon's first catalog")
        ready(require("discover.filter.0", "Discover shows the Genre menu")).tap()
        require("discover.filter.0.option.0", timeout: 5, "the Genre menu lists its genres")
        let options = (0..<40).map { element("discover.filter.0.option.\($0)") }
        guard let option = options.prefix(while: { $0.exists }).first(where: { $0.label.contains(genre) }) else {
            throw HarnessError("the Genre menu offers no \(genre)")
        }
        pickMenuEntry(option)
        if !waitUntil(30, { menuText("discover.filter.0").contains(genre) && element("discover.item.0").exists }) {
            fail("picking \(genre) leaves the Genre menu on \(menuText("discover.filter.0"))")
        }
        marketingShot("discover", "Discover on the addon's first catalog, \(genre) picked", landscape: isPad)
    }

    func testSearch() throws {
        let query = try marketingSearch
        try launchMarketing(open: ["search"], progress: false)
        orient()
        let field = require("search.field", timeout: 30, "a launch on Search shows its field")
        ready(field).tap()
        field.typeText(query)
        requireCard("search.group.0.item.0", timeout: 30, "typing \(query) lists results")
        // The keyboard's Search key keeps the query, and the keyboard goes.
        field.typeText("\n")
        hideKeyboard()
        marketingShot("search", "Search's results for \"\(query)\"", landscape: isPad)
    }

    func testLibrary() throws {
        try launchMarketing(open: ["library"], progress: true)
        orient()
        require("library.item.0", timeout: 60, "the Library lists its titles")
        marketingShot("library", "the Library", landscape: isPad)
    }

    func testWatch() throws {
        let room = try marketingRoom
        try launchMarketing(open: ["watch"], progress: false, settings: ["watch.relay=\(room.relay)"])
        orient()
        require("watch.join.field", timeout: 30, "Watch together shows its join field")
        requireRelayReached("Watch together reaches the run's relay")
        openFromOutside(WatchScenario.invite(code: room.code, relay: room.relay))
        requireRoomCode("the invite joins the room")
        requireRoom(of: room.names, "the invite joins the room")
        let guests = room.names.dropFirst().joined(separator: ", ")
        marketingShot("watch", "Watch together in \(room.names[0])'s room" + (guests.isEmpty ? "" : " with \(guests)"), landscape: isPad)
        // Out of the room, so the next language's pass joins it without this app listed as away (`requireRoom`). Leave
        // room ends the room's form, below the fold in iPhone portrait.
        ready(swipeUntilShown("watch.room.leave", "the room ends in Leave room")).tap()
        ready(require("watch.room.leave.confirm", timeout: 5, "Leave room asks first")).tap()
        require("watch.join.field", timeout: 20, "Leave room, confirmed, goes back to the join field")
    }

    /// The iPad in landscape; the iPhone in portrait, or in landscape for the `player`.
    private func orient(player: Bool = false) {
        let landscape = isPad || player
        XCUIDevice.shared.orientation = landscape ? .landscapeLeft : .portrait
        _ = waitUntil(5) { (app.frame.width > app.frame.height) == landscape }
    }

    /// Pauses with the controls up (paused, they stay), as Now Playing reads it (`playerPaused`).
    private func pause(_ video: XCUIElement) {
        if !playerPaused {
            tapPlayerControl("player.playPause", video: video, until: { self.playerPaused }, "play/pause doesn't pause the video")
        }
    }

    /// Picks the subtitle menu's entry naming the configured subtitle language (`marketingSubtitleNames`), the player
    /// paused: its controls stay up, and a tap on the video brings them back should they have gone. On the iPad in a
    /// right-to-left language XCUITest's hit test misses the subtitle button in the top bar's left corner ("there but
    /// not hittable"; ar, the button at x 104 of 1376, while a tap at its centre opened the menu), so there the
    /// tap goes to its centre; the menu's entry below confirms it opened.
    private func pickSubtitle(_ video: XCUIElement) {
        if !element("player.subtitles").exists { ready(video).tap() }
        let button = require("player.subtitles", timeout: 5, "the controls have the subtitle menu")
        if isPad && Self.rightToLeft && !waitUntil(5, { button.isHittable }) {
            button.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        } else {
            ready(button).tap()
        }
        let names = marketingSubtitleNames
        let entry = app.buttons
            .matching(NSCompoundPredicate(orPredicateWithSubpredicates: names.map { NSPredicate(format: "label CONTAINS[c] %@", $0) }))
            .firstMatch
        if !entry.exists(within: 5) { fail("the subtitle menu has no entry naming \(names.joined(separator: " or "))") }
        pickMenuEntry(entry)
        if !waitUntil(5, { !entry.exists }) { fail("picking the \(names[0]) subtitles doesn't close the menu") }
    }

    /// Taps the timeline (paused, the controls up) until the position label reads a second within `seconds`, each
    /// tap corrected by where the one before landed (the bar's ends sit inside its element); whether it got there.
    private func seek(into seconds: ClosedRange<Double>) -> Bool {
        guard let duration = playerDuration(), duration > 0 else { return false }
        let target = (seconds.lowerBound + seconds.upperBound) / 2 + 0.5
        var fraction = target / duration
        for _ in 0..<6 {
            let scrub = element("player.scrub")
            guard scrub.exists(within: 5) else { return false }
            ready(scrub).coordinate(withNormalizedOffset: CGVector(dx: min(max(fraction, 0), 1), dy: 0.5)).tap()
            // Paused, the position holds once the seek has landed: the same for 0.7 s.
            var position: Double?
            _ = waitUntil(6) {
                position = playerPosition()
                return position != nil && holds(for: 0.7) { playerPosition() == position }
            }
            guard let landed = position else { continue }
            if seconds.contains(landed) { return true }
            fraction += (target - landed) / duration
        }
        return false
    }

    /// Puts the software keyboard away (a scroll dismisses it).
    private func hideKeyboard() {
        guard app.keyboards.firstMatch.exists else { return }
        let scroll = app.scrollViews.firstMatch
        ready(scroll.exists ? scroll : app.windows.firstMatch).swipeDown()
        _ = waitUntil(3) { !app.keyboards.firstMatch.exists }
    }
}
