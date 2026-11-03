import XCTest

/// AniList on Apple TV through the tracker sign-in relay of the dev stack's server, in a `--dev` session with that
/// relay (`--dev-relay`) and the run's fake
/// tracker server, which stores the connection and answers the service's user. Settings → Trackers → Connect on AniList
/// shows the relay's link as a QR code and as text, without Open in browser (Apple TV has none). The test publishes the
/// link (`relay-code`), and the run's hook opens it on the Mac as the phone that scanned the code would: through
/// the relay and the stack's fake authorization page to the relay's page, which asks to
/// connect AniList to Tortuga on this device, and Connect there. The app, polling the relay, gets the tokens: AniList
/// connected as the fake's user, focus still on its button, now Disconnect.
final class RelaySignInTests: TortugaUITestCase {
    func testAniListSignsInByItsQRCodeConfirmedOnAnotherDevice() throws {
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        try launchDev(trackers: true, relay: try AccountScenario.variable("TORTUGA_SERVER"))
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openSection("trackers")
        moveFocus(to: "settings.trackers.anilist.connect", with: .down, limit: 8, "Down reaches Connect on AniList's card")
        press(.select)
        require("settings.trackers.connect.qr", timeout: 30, "Connect on AniList shows the relay's link as a QR code")
        let link = requireCode("settings.trackers.connect.link", timeout: 5, "the link shows under its QR code")
        if element("settings.trackers.connect.openInBrowser").exists { fail("Apple TV offers Open in browser") }
        shot("anilist link")

        publish("relay-code", code: link, to: exchange)
        requireGone("settings.trackers.connect.qr", timeout: 90, "confirmed on the other device, AniList's flow ends")
        requireTracker("anilist", connectedAs: Self.fakeTraktUser, "AniList is connected through the other device")
        requireFocus("settings.trackers.anilist.disconnect", "focus stays on AniList's button, now Disconnect")
        shot("anilist connected")
    }
}
