import XCTest

/// What the Apple TV and iPhone trackers tests share: what the fake Trakt (tools/media/test_trackers.py) answers, a
/// service's connection as Settings → Trackers shows it, and the Board's shelves by where they come from.
extension TortugaUITestCase {
    /// The fake's device code.
    static let fakeTraktCode = "TORTUGA1"
    /// The fake's account (`--user`).
    static let fakeTraktUser = "tortuga-tester"
    /// A title on the fake's watchlist.
    static let fakeTraktWatchlistTitle = "The Shawshank Redemption"

    /// Waits for `service`'s status (`settings.trackers.<service>.status`: its name and connection) to name `user`;
    /// fails with `what` when it doesn't.
    func requireTracker(_ service: String, connectedAs user: String, timeout: TimeInterval = 20, _ what: String) {
        let status = require("settings.trackers.\(service).status", what)
        if !waitUntil(timeout, { text(of: status).contains(user) }) {
            fail("\(what): \(service)'s status is \"\(text(of: status))\"")
        }
    }

    /// Waits for `service` to offer Connect again and no Disconnect, its status no longer naming `user`; fails with
    /// `what` when it doesn't.
    func requireTracker(_ service: String, disconnectedFrom user: String, timeout: TimeInterval = 20, _ what: String) {
        require("settings.trackers.\(service).connect", timeout: timeout, what)
        requireGone("settings.trackers.\(service).disconnect", what)
        let status = require("settings.trackers.\(service).status", timeout: 5, what)
        if text(of: status).contains(user) { fail("\(what): \(service)'s status is still \"\(text(of: status))\"") }
    }

    /// The source labels (`board.row.<n>.source`) of the Board's shelves that come from `source` ("Trakt").
    func shelfSources(_ source: String) -> XCUIElementQuery {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier MATCHES %@ AND (label == %@ OR value == %@)", #"board\.row\.[0-9]+\.source"#, source, source))
    }

    /// Waits for a Board shelf from `source` that lists `title` (a card `board.row.<n>.item.<i>` of the row whose
    /// `board.row.<n>.source` it is); fails with `what` when none does.
    func requireShelf(from source: String, listing title: String, timeout: TimeInterval = 60, _ what: String) {
        let sources = shelfSources(source)
        if !sources.firstMatch.exists(within: timeout) { fail("\(what): no shelf from \(source) after \(Int(timeout)) s") }
        let row = String(sources.firstMatch.identifier.dropLast(".source".count))
        let items = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@", "\(row).item.", title))
        if !items.firstMatch.exists(within: 20) { fail("\(what): \(row) from \(source) doesn't list \(title)") }
    }

    /// Waits until the Board has no shelf from `source`; fails with `what` when one stays.
    func requireNoShelf(from source: String, timeout: TimeInterval = 30, _ what: String) {
        let sources = shelfSources(source)
        if !waitUntil(timeout, { sources.count == 0 }) { fail("\(what): \(sources.count) shelf from \(source) left") }
    }
}
