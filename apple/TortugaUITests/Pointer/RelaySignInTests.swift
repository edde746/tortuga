import XCTest

/// MyAnimeList and AniList on iPhone, iPad and the Mac through the tracker sign-in relay of the dev stack's server
/// (server/compose.dev.yaml), whose relay sends the
/// browser to the stack's fake tracker server, which approves at once. A `--dev` session with that relay
/// (`--dev-relay`) and the run's fake tracker server, which stores each connection and answers the service's user:
/// Settings → Trackers → Connect on each shows the relay's link as a QR code with Open in browser, which opens it in
/// the system's web authentication session (iOS: a sheet over the app; the Mac: the default browser) once the system
/// has asked whether Tortuga may use the site (Continue). The relay's callback sends the session straight back to the
/// app (`tortuga://signin` with a one-time proof), no page asking to confirm, and the app collects the tokens with that
/// proof: the service connected as the fake's user.
final class RelaySignInTests: TortugaUITestCase {
    func testEachServiceSignsInThroughThisDevicesBrowser() throws {
        try launchDev(trackers: true, relay: try AccountScenario.variable("TORTUGA_SERVER"))
        openSection("trackers")
        for service in ["anilist", "mal"] {
            activateShown("settings.trackers.\(service).connect", "Trackers offers Connect on \(service)'s card")
            require("settings.trackers.connect.qr", timeout: 30, "Connect on \(service) shows the relay's link as a QR code")
            let open = require("settings.trackers.connect.openInBrowser", timeout: 5, "\(service)'s link opens in this device's browser")
            shot("\(service) link", of: shotSource)
            withAnotherAppInFront {
                activate(open)
                continueToWebSignIn("\(service)'s sign-in")
                requireGone("settings.trackers.connect.qr", timeout: 90, "the browser comes back to the app and \(service)'s flow ends")
                returnFromBrowser()
            }
            requireTracker(service, connectedAs: Self.fakeTraktUser, "\(service) is connected through this device's browser")
            shot("\(service) connected", of: shotSource)
        }
    }
}
