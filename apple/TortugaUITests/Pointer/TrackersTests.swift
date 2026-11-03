import XCTest

/// Trackers by touch (iOS) and with the pointer (the Mac), against the fake Trakt (the test server
/// tools/media/test_trackers.py, which approves its device code by itself after some polls): Settings → Trackers →
/// Connect on Trakt shows the code and its QR code until the fake approves, then Trakt is connected as the fake's user;
/// the Board shows Trakt's watchlist shelf; Disconnect, confirmed, leaves Trakt unconnected and the Board without
/// Trakt's shelves. On the Mac, with every tracker connected to the fake, the Board shows no failed shelf, and the
/// MyAnimeList and AniList shelves' entries (one per season or cour) open as the Cinemeta titles they are, on their
/// season, nothing opened over them (`TrackersTests+macOS`).
final class TrackersTests: TortugaUITestCase {
    func testTraktConnectsShowsItsWatchlistAndDisconnects() throws {
        try launchDev(trackers: true)
        openSection("trackers")
        let connect = require("settings.trackers.trakt.connect", "Trackers offers Connect on Trakt's card")
        shot("trackers")

        activate(connect)
        let code = require("settings.trackers.connect.code", "Connect shows a device code")
        #if os(macOS)
        let shownCode = code.label == Self.fakeTraktCode || (code.value as? String) == Self.fakeTraktCode
        #else
        let shownCode = code.label == Self.fakeTraktCode
        #endif
        if !shownCode { fail("the code shown is \"\(code.label)\", not the fake's \(Self.fakeTraktCode)") }
        require("settings.trackers.connect.qr", timeout: 5, "the code comes with its QR code")
        require("settings.trackers.connect.expires", timeout: 5, "the code shows its countdown")
        require("settings.trackers.connect.openInBrowser", timeout: 5, "the code's page can open in the browser")
        shot("code")
        requireGone("settings.trackers.connect.code", timeout: 60, "the fake approves the code and the flow closes")
        requireTracker("trakt", connectedAs: Self.fakeTraktUser, "Trakt is connected")
        require("settings.trackers.notice", timeout: 5, "a notice says Trakt connected")
        require("settings.trackers.trakt.scrobble", timeout: 5, "a connected Trakt has its switches")
        shot("connected")

        activate(navItem("nav.board", "the sidebar lists Home"))
        requireShelf(from: "Trakt", listing: Self.fakeTraktWatchlistTitle, "the Board shows Trakt's watchlist")
        shot("board with Trakt")

        openSection("trackers")
        activate(require("settings.trackers.trakt.disconnect", "a connected Trakt offers Disconnect"))
        require("settings.trackers.dialog.confirm", timeout: 5, "Disconnect asks first")
        shot("disconnect asked")
        activate(element("settings.trackers.dialog.confirm"))
        requireTracker("trakt", disconnectedFrom: Self.fakeTraktUser, "confirming disconnects Trakt")
        shot("disconnected")

        activate(navItem("nav.board", "the sidebar lists Home"))
        requireNoShelf(from: "Trakt", "the Board drops Trakt's shelves")
        shot("board without Trakt")
    }
}
