import XCTest

/// Putting a title on the trackers' watchlists, and taking it off, against the fake trackers
/// (tools/media/test_trackers.py) with every tracker connected (`--dev-trackers-connected`): Spirited Away, an anime,
/// which all five services take, so its Detail's Watchlist reads "Tracker watchlists" and opens the app's sheet
/// (`app.watchlist.sheet`) with a row per tracker (`app.watchlist.<service>`). iOS and the Mac reach Watchlist behind ⋯
/// (`detail.overflow.watchlist`), Apple TV as the row's round `detail.watchlist` before Rate. Each row is tapped
/// (clicked, selected) once to put the title on that list, then once more to take it off; the fake's `/_log` then
/// holds an add and a remove for each service, both answered 200. Off Apple TV a Continue Watching card's menu offers
/// the same sheet over Home (`board.cw.0.menu.watchlists`). Shots: `watchlist, entry`, `watchlist, sheet three on`,
/// `watchlist, sheet all on`, `watchlist, card menu`, `watchlist, sheet over Home`.
extension DetailTests {
    /// Spirited Away and its services, in the sheet's order.
    static let watchlistTitle = "detail:movie:tt0245429"
    static let watchlistServices = ["trakt", "simkl", "mdblist", "mal", "anilist"]

    func testWatchlistATitleOnEveryTracker() throws {
        try fakeTrackers("/_fail?watchlist=", post: true)
        let before = try watchlistWrites().count
        try launchDev(trackersConnected: true, open: [Self.watchlistTitle])
        openWatchlistSheet()
        for (index, service) in Self.watchlistServices.enumerated() {
            toggleWatchlistRow(service)
            requireWatchlistWrite(service, op: "add", after: before, "\(service)'s row puts Spirited Away on its list")
            if index == 2 { shot("watchlist, sheet three on") }
        }
        shot("watchlist, sheet all on")
        for service in Self.watchlistServices {
            toggleWatchlistRow(service)
            requireWatchlistWrite(service, op: "remove", after: before, "\(service)'s row takes Spirited Away off its list")
        }
        closeWatchlistSheet("the sheet")

        #if !os(tvOS)
        // A card's menu opens the same sheet over Home.
        try launchDev(trackersConnected: true, progress: ["movie:tt0245429:tt0245429:600:6000"])
        let card = require("board.cw.0", timeout: 90, "Home shows Spirited Away in Continue Watching")
        openItemMenu(card)
        let entry = require("board.cw.0.menu.watchlists", timeout: 10, "the card's menu offers Tracker watchlists")
        shot("watchlist, card menu")
        pickMenuEntry(entry)
        require("app.watchlist.sheet", timeout: 10, "the card menu's Tracker watchlists opens the sheet over Home")
        for service in Self.watchlistServices {
            require("app.watchlist.\(service)", timeout: 10, "the sheet over Home has \(service)'s row")
        }
        shot("watchlist, sheet over Home")
        closeWatchlistSheet("the sheet over Home")
        #endif
    }

    /// The fake's watchlist writes since it started: service, op (add or remove), title, status.
    func watchlistWrites() throws -> [[String: Any]] {
        try fakeTrackers("/_log")["watchlistWrites"] as? [[String: Any]] ?? []
    }

    /// Waits up to `timeout` for the fake to log `service`'s `op` answered 200 after its first `after` writes; fails
    /// with `what` and what it logged when it doesn't.
    func requireWatchlistWrite(_ service: String, op: String, after: Int, timeout: TimeInterval = 30, _ what: String) {
        var writes: [[String: Any]] = []
        if !waitUntil(timeout, {
            writes = Array(((try? watchlistWrites()) ?? []).dropFirst(after))
            return writes.contains { $0["service"] as? String == service && $0["op"] as? String == op && $0["status"] as? Int == 200 }
        }) {
            fail("\(what): the fake logged \(writes), no \(op) by \(service) answered 200")
        }
    }

    /// Opens the sheet from Detail's Watchlist, once the trackers have answered: Apple TV the row's round button, else
    /// ⋯'s entry (⋯ opened again while it doesn't list it yet). Shot `watchlist, entry` with the entry showing.
    func openWatchlistSheet() {
        #if os(tvOS)
        requireFocus("detail.play", timeout: 60, "Play has focus once Spirited Away's details show")
        moveFocus(to: "detail.watchlist", with: .right, limit: 8, "Right from Play reaches Watchlist")
        let button = element("detail.watchlist")
        if !waitUntil(30, { button.label == "Tracker watchlists" }) {
            fail("detail.watchlist is labelled \"\(button.label)\", not \"Tracker watchlists\"")
        }
        shot("watchlist, entry")
        press(.select)
        require("app.watchlist.sheet", timeout: 10, "Watchlist opens the sheet")
        requireFocus(prefix: "app.watchlist.", "focus moves into the sheet")
        #else
        require("detail.play", timeout: 60, "Spirited Away's details show Play")
        let entry = element("detail.overflow.watchlist")
        let entries = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'detail.overflow.'"))
        let deadline = Date().addingTimeInterval(60)
        var found = false
        repeat {
            let more = ready(require("detail.overflow", "the action row has ⋯"))
            #if os(macOS)
            more.click()
            #else
            more.tap()
            #endif
            if entry.exists(within: 5), waitUntil(5, { (entry.label.isEmpty ? entry.title : entry.label) == "Tracker watchlists" }) {
                found = true
                break
            }
            closeOverflow()
            _ = waitUntil(5) { !entries.firstMatch.exists }
        } while Date() < deadline
        if !found { return fail("⋯ doesn't list detail.overflow.watchlist reading \"Tracker watchlists\" after 60 s") }
        shot("watchlist, entry")
        pickMenuEntry(entry)
        require("app.watchlist.sheet", timeout: 10, "⋯'s Tracker watchlists opens the sheet")
        #endif
        for service in Self.watchlistServices {
            require("app.watchlist.\(service)", timeout: 10, "the sheet has \(service)'s row")
        }
    }

    /// Toggles `service`'s row: a tap, a click, or on Apple TV focus moved to it (Down, else Up) and Select.
    func toggleWatchlistRow(_ service: String) {
        let id = "app.watchlist.\(service)"
        #if os(tvOS)
        let target = Self.watchlistServices.firstIndex(of: service) ?? 0
        let current = focusedId.flatMap { Self.watchlistServices.firstIndex(of: String($0.dropFirst("app.watchlist.".count))) } ?? 0
        moveFocus(to: id, with: target >= current ? .down : .up, limit: 6, "focus reaches \(service)'s row")
        press(.select)
        #elseif os(macOS)
        ready(require(id, "the sheet has \(service)'s row")).click()
        #else
        ready(require(id, "the sheet has \(service)'s row")).tap()
        #endif
    }

    /// Closes the sheet: the remote's Menu, Close (iPad, the Mac), else a tap on the dimmed page above iPhone's sheet.
    func closeWatchlistSheet(_ what: String) {
        #if os(tvOS)
        press(.menu)
        #else
        let close = element("app.watchlist.close")
        if close.exists {
            #if os(macOS)
            ready(close).click()
            #else
            ready(close).tap()
            #endif
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
        }
        #endif
        requireGone("app.watchlist.sheet", "\(what) closes")
    }

    #if !os(tvOS)
    /// Closes the open ⋯ menu: Escape on the Mac; on iOS a tap at the screen's trailing edge, away from its entries.
    private func closeOverflow() {
        #if os(macOS)
        app.typeKey(.escape, modifierFlags: [])
        #else
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.75)).tap()
        #endif
    }
    #endif
}
