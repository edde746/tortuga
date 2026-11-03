import XCTest

/// The App Store shots on Apple TV, with the remote, one test per shot of the store screenshot list, each from a
/// launch of its own (`MarketingTestCase`) in the pass's language: Home with
/// Continue Watching full and the configured card focused over its backdrop (`home`), the configured title's Detail
/// screen with Play focused (`detail`), the configured video's Streams panel with every addon's streams and its first
/// stream focused (`streams`), the player paused on the configured subtitle line with the controls up (`player`),
/// Discover on the addon's first catalog with the configured genre picked, its first poster focused (`discover`),
/// Search's results for the configured query, the first focused, or the keyboard where a focused result scrolls the tab
/// bar off the screen's top (`search`), the Library, its first title focused (`library`), and Watch together in the
/// configured room, joined by its invite (`watch`).
final class MarketingShots: MarketingTestCase {
    func testHome() throws {
        try launchMarketing(progress: true)
        // Home's shelves may load and take focus before Continue Watching's cards do; focus stays where it went.
        require("board.cw.0", timeout: 90, "Home shows Continue Watching")
        if !waitUntil(10, { focusedId?.hasPrefix("board.") == true }) {
            fail("Home doesn't take focus once it loads: focus is on \(focusedId ?? "nothing")")
        }
        walkFocus(to: "board.cw.0", "the remote reaches Continue Watching's first card")
        if let home = marketingHome { focusCard(home) }
        // The focused card's backdrop and the posters load.
        marketingShot("home", "Home: Continue Watching full, \(focused.label) focused over its backdrop", landscape: false)
    }

    func testDetail() throws {
        try launchMarketingDetail()
        requireFocus("detail.play", timeout: 60, "Play has focus once the title's Detail screen shows")
        if marketingDetailOnEpisodes {
            // Down from Play to the episode row, the current episode focused, its details above the row.
            for _ in 0..<3 where focusedId?.hasPrefix("detail.episode.") != true { press(.down) }
            _ = requireFocus(prefix: "detail.episode.", "Down from Play reaches the episodes")
            marketingShot("detail", "the Detail screen on its episodes, \(focused.label) focused", landscape: false)
            return
        }
        marketingShot("detail", "the Detail screen, \(element("detail.play").label) focused, the episode's progress", landscape: false)
    }

    func testStreams() throws {
        try launchMarketingStreams()
        requireFocus("streams.row.0", timeout: 90, "the first stream has focus once the Streams panel lists them")
        try requireEveryAddonsStreams("the Streams panel lists every addon's streams")
        walkFocus(to: "streams.row.0", "the first stream has focus once every addon has answered")
        marketingShot("streams", "the Streams panel over the title's Detail screen, \(focused.label) focused", landscape: false)
    }

    func testPlayer() throws {
        let line = try marketingLine
        guard let seconds = line.shownSeconds else {
            throw HarnessError("the line at \(line.label) holds no whole second of the position label")
        }
        try launchMarketingStreams()
        requireFocus("streams.row.0", timeout: 90, "the stream has focus once the Streams panel lists it")
        try requireEveryAddonsStreams("the Streams panel lists every addon's streams")
        walkFocus(to: "streams.row.0", "the first stream has focus once every addon has answered")
        press(.select)
        require("player.video", timeout: 30, "Select on the stream opens the player")
        requirePlayback(1, timeout: 120)
        pause()
        if !marketingSubtitleNames.isEmpty { pickSubtitle() }
        // Where the player is already, else played into from 2–12 s before the line and paused as soon as the position
        // label reaches it; three tries.
        var at: Double?
        for _ in 0..<3 {
            pause()
            at = playerPosition()
            if let at, seconds.contains(at) { break }
            guard step(toAtMost: seconds.lowerBound - 2) != nil else { continue }
            press(.select)
            _ = waitUntil(20) { (playerPosition() ?? 0) >= seconds.lowerBound }
            press(.select)
            _ = waitUntil(5) { paused }
            at = playerPosition()
            if let at, seconds.contains(at), paused { break }
        }
        guard let at, seconds.contains(at), paused else {
            return fail("pausing on the line at \(line.label) missed it three times (at \(at.map(Self.clock) ?? "no position"))")
        }
        // The frame decoded and its line drawn, lifted above the controls.
        marketingShot("player", "paused on the line at \(line.label) with the controls up", at: at, landscape: false)
    }

    func testDiscover() throws {
        let genre = try marketingDiscover
        try launchMarketing(open: ["discover"], progress: false)
        requireCard("discover.item.0", timeout: 60, "Discover shows the addon's first catalog")
        for _ in 0..<4 where focusedId?.hasPrefix("discover.item.") != true { press(.down) }
        if focusedId?.hasPrefix("discover.item.") != true { fail("Down doesn't reach Discover's posters: focus is on \(focusedId ?? "nothing")") }
        // Up from the grid to the filters above it, then along them to Genre, whose panel lists the genres.
        press(.up)
        walkFocus(to: "discover.filter.0", limit: 6, "Up and along the filters reach Genre")
        press(.select)
        require("discover.filter.0.panel", timeout: 5, "Select on Genre opens its panel")
        let inPanel = { self.focusedId?.hasPrefix("discover.filter.0.option.") == true }
        for _ in 0..<40 where !(inPanel() && focused.label.contains(genre)) { press(.down) }
        if !(inPanel() && focused.label.contains(genre)) { throw HarnessError("the Genre panel offers no \(genre): focus is on \(focusedId ?? "nothing")") }
        press(.select)
        requireGone("discover.filter.0.panel", "picking a genre closes the panel")
        if !waitUntil(30, { menuText("discover.filter.0").contains(genre) && element("discover.item.0").exists }) {
            fail("picking \(genre) leaves the Genre filter on \(menuText("discover.filter.0"))")
        }
        for _ in 0..<4 where focusedId?.hasPrefix("discover.item.") != true { press(.down) }
        if focusedId?.hasPrefix("discover.item.") != true { fail("Down doesn't reach the genre's posters: focus is on \(focusedId ?? "nothing")") }
        marketingShot("discover", "Discover on the addon's first catalog, \(genre) picked, \(focused.label) focused", landscape: false)
    }

    func testSearch() throws {
        let query = try marketingSearch
        // From Home by the tab bar, as a viewer gets there: Down from the Search tab puts the keyboard's focus in the
        // field.
        try launchMarketing(progress: false)
        require("board.row.0.item.0", timeout: 90, "Home shows the addon's catalogs")
        if !waitUntil(10, { focusedId?.hasPrefix("board.") == true }) {
            fail("Home doesn't take focus once its shelves load: focus is on \(focusedId ?? "nothing")")
        }
        focusTabBar()
        walkFocus(to: "nav.search", "the remote reaches the Search tab")
        let field = app.searchFields.firstMatch
        if !field.exists(within: 10) { fail("Search shows no search field") }
        press(.down)
        field.typeText(query)
        requireCard("search.group.0.item.0", timeout: 30, "typing \(query) lists results")
        for _ in 0..<6 where focusedId?.hasPrefix("search.group.") != true { press(.down) }
        if focusedId?.hasPrefix("search.group.") != true { fail("Down doesn't reach the results: focus is on \(focusedId ?? "nothing")") }
        // A keyboard taller than one row of letters (French, Hindi, Japanese, …) leaves a focused result low enough that
        // the screen scrolls it up and the tab bar off its top edge: the shot then has the keyboard focused, the results
        // under it, nothing scrolled.
        let tabBar = app.tabBars.firstMatch
        if waitUntil(3, { tabBar.frame.minY < 0 }) {
            for _ in 0..<4 where focusedId?.hasPrefix("search.group.") == true { press(.up) }
            if !waitUntil(5, { tabBar.frame.minY >= 0 }) {
                fail("Up from the results doesn't bring the tab bar back whole: its top is at \(tabBar.frame.minY)")
            }
            marketingShot("search", "Search's results for \"\(query)\" under the keyboard, \(focused.label) focused", landscape: false)
            return
        }
        marketingShot("search", "Search's results for \"\(query)\", \(focused.label) focused", landscape: false)
    }

    func testLibrary() throws {
        try launchMarketing(open: ["library"], progress: true)
        require("library.item.0", timeout: 60, "the Library lists its titles")
        // Down from the tab bar lands on the row above the grid, then on a poster under it.
        for _ in 0..<4 where focusedId?.hasPrefix("library.item.") != true { press(.down) }
        walkFocus(to: "library.item.0", "the remote reaches the Library's first title")
        marketingShot("library", "the Library, \(focused.label) focused", landscape: false)
    }

    func testWatch() throws {
        let room = try marketingRoom
        try launchMarketing(open: ["watch"], progress: false, settings: ["watch.relay=\(room.relay)"])
        require("watch.join.field", timeout: 30, "Watch together shows its join field")
        requireRelayReached("Watch together reaches the run's relay")
        openFromOutside(WatchScenario.invite(code: room.code, relay: room.relay))
        requireRoomCode("the invite joins the room")
        requireRoom(of: room.names, "the invite joins the room")
        // A guest's only button in the room is Leave, which would show focused (white): focus goes up to the tab bar.
        for _ in 0..<3 where !focusInTabBar { press(.up) }
        if !focusInTabBar { fail("Up doesn't reach the tab bar from the room; focus is on \(focusedId ?? "nothing")") }
        let guests = room.names.dropFirst().joined(separator: ", ")
        marketingShot("watch", "Watch together in \(room.names[0])'s room" + (guests.isEmpty ? "" : " with \(guests)"), landscape: false)
        // Out of the room, so the next language's pass joins it without this app listed as away (`requireRoom`). Down
        // from the tab bar: Leave room is the room's one button, whatever column its label leaves it in.
        moveFocus(to: "watch.room.leave", with: .down, "Down from the tab bar reaches Leave room")
        press(.select)
        focusDialogButton("watch.room.leave.confirm", "Leave room asks first, and the remote reaches its answer")
        press(.select)
        require("watch.join.field", timeout: 20, "Leave room, confirmed, goes back to the join field")
    }

    /// Moves focus along Continue Watching to the card whose label contains `text` (the shelf makes its cards as
    /// focus moves along it): towards the card once it is made, else on in the shelf's reading direction.
    private func focusCard(_ text: String) {
        let card = continueWatchingCard(text)
        let onCard = { self.focused.identifier.hasPrefix("board.cw.") && self.focused.label.contains(text) }
        for _ in 0..<16 where !onCard() {
            press(card.exists ? Self.direction(from: focused.frame, to: card.frame) : Self.rightToLeft ? .left : .right)
        }
        if !onCard() {
            fail("Continue Watching has no card with \(text): focus is on \(focusedId ?? "nothing")")
        }
    }

    /// Whether the player is paused with the controls up: the timeline there, Now Playing paused.
    private var paused: Bool {
        element("player.scrub").exists && playerPaused
    }

    /// Pauses, the controls up and the timeline focused: Select on the timeline while they show, on the video when
    /// they have gone.
    private func pause() {
        if !paused { press(.select) }
        if !waitUntil(5, { paused }) {
            fail("Select doesn't pause: Now Playing reads \(element("player.nowPlaying").value as? String ?? "nothing")")
        }
        requireFocus("player.scrub", "the timeline has focus once paused")
    }

    /// Picks, in the panel's subtitle tab, the entry naming the configured subtitle language
    /// (`namesMarketingSubtitle`), then moves focus from the subtitle button, where the pick leaves it, down to the
    /// timeline.
    private func pickSubtitle() {
        walkControls(to: "player.subtitles", "the remote reaches the subtitle button")
        press(.select)
        require("player.menu", timeout: 5, "Select on the subtitle button opens the panel")
        _ = waitUntil(5) { focusedId?.hasPrefix("player.menu.item.") == true }
        var items: [XCUIElement] = []
        while element("player.menu.item.\(items.count)").exists { items.append(element("player.menu.item.\(items.count)")) }
        let names = marketingSubtitleNames.joined(separator: " or ")
        guard let target = items.firstIndex(where: { namesMarketingSubtitle($0.label) || namesMarketingSubtitle($0.value as? String ?? "") }) else {
            fail("the subtitle panel has no entry naming \(names): \(items.map { "\($0.label) \($0.value as? String ?? "")" })")
            return
        }
        let focusedIndex = focusedId.flatMap { Int($0.dropFirst("player.menu.item.".count)) } ?? 0
        moveFocus(to: "player.menu.item.\(target)", with: target > focusedIndex ? .down : .up, limit: 12, "the remote reaches the \(names) subtitles")
        press(.select)
        requireGone("player.menu", timeout: 5, "picking the subtitles closes the panel")
        requireFocus("player.subtitles", "the subtitle button has focus again once the panel is gone")
        press(.down)
        requireFocus("player.scrub", "Down from the subtitle button reaches the timeline")
    }

    /// Steps the paused timeline by the remote's 10 s presses to the last step at or before `target` seconds and
    /// waits for the seek, which commits a second after the last press (the position then reads its target) and lands
    /// when the paused frame is redrawn there; the position reached.
    private func step(toAtMost target: Double) -> Double? {
        guard let from = playerPosition() else { return nil }
        let steps = Int(((target - from) / 10).rounded(.down))
        guard steps != 0 else { return from }
        for _ in 0..<abs(steps) { press(steps > 0 ? .right : .left) }
        let expected = from + Double(steps) * 10
        _ = waitUntil(10) { playerPosition().map { abs($0 - expected) <= 2 } ?? false }
        screenSettles()
        return playerPosition()
    }
}
