import XCTest

/// Trackers with the remote, against the fake Trakt (tools/media/test_trackers.py, which approves its device code by
/// itself after some polls): Settings → Trackers → Connect on Trakt shows the code and its
/// QR code until the fake approves, then Trakt is connected as the fake's user with focus still on its button, now
/// Disconnect; the Board shows Trakt's watchlist shelf; Disconnect, confirmed, leaves Trakt unconnected (focus on
/// Connect) and the Board without Trakt's shelves.
final class TrackersTests: TortugaUITestCase {
    func testTraktConnectsShowsItsWatchlistAndDisconnects() throws {
        try launchDev(trackers: true)
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openSection("trackers")
        moveFocus(to: "settings.trackers.trakt.connect", with: .down, limit: 4, "Down reaches Connect on Trakt's card")
        shot("trackers")

        press(.select)
        let code = require("settings.trackers.connect.code", "Connect shows a device code")
        if code.label != Self.fakeTraktCode { fail("the code shown is \"\(code.label)\", not the fake's \(Self.fakeTraktCode)") }
        require("settings.trackers.connect.qr", timeout: 5, "the code comes with its QR code")
        require("settings.trackers.connect.expires", timeout: 5, "the code shows its countdown")
        shot("code")
        requireGone("settings.trackers.connect.code", timeout: 60, "the fake approves the code and the flow closes")
        requireTracker("trakt", connectedAs: Self.fakeTraktUser, "Trakt is connected")
        requireFocus("settings.trackers.trakt.disconnect", "focus stays on Trakt's button, now Disconnect")
        require("settings.trackers.notice", timeout: 5, "a notice says Trakt connected")
        require("settings.trackers.trakt.scrobble", timeout: 5, "a connected Trakt has its switches")
        shot("connected")

        // Menu goes back to the list of sections; the Board's tab shows Trakt's watchlist.
        press(.menu)
        focusTabBar()
        moveFocus(to: "nav.board", with: .left, "Left reaches the Board tab")
        requireShelf(from: "Trakt", listing: Self.fakeTraktWatchlistTitle, "the Board shows Trakt's watchlist")
        shot("board with Trakt")

        openSection("trackers")
        // Focus can enter the page under Trakt's card (on a switch).
        moveFocus(to: "settings.trackers.trakt.disconnect", with: .up, limit: 6, "Up reaches Disconnect on Trakt's card")
        press(.select)
        require("settings.trackers.dialog.confirm", timeout: 5, "Disconnect asks first")
        shot("disconnect asked")
        focusDialogButton("settings.trackers.dialog.confirm", "the confirmation's Disconnect takes focus")
        press(.select)
        requireTracker("trakt", disconnectedFrom: Self.fakeTraktUser, "confirming disconnects Trakt")
        requireFocus("settings.trackers.trakt.connect", "focus stays on Trakt's button, now Connect")
        shot("disconnected")

        press(.menu)
        focusTabBar()
        moveFocus(to: "nav.board", with: .left, "Left reaches the Board tab")
        requireNoShelf(from: "Trakt", "the Board drops Trakt's shelves")
        shot("board without Trakt")
    }
}
