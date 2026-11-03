import XCTest

/// The account an Android phone signed up for, joined with the remote: signed out on the phone's self-hosted server,
/// the Apple TV links from another device, and the run has the phone approve the code on screen; signed in by the
/// link, the Apple TV has the account's data: the phone's PIN-locked profile shows, opens with its PIN on the pad, and
/// the Board's first shelf is the catalog of the addon the phone installed and moved to the top.
final class AccountTests: TortugaUITestCase {
    func testLinksByCodeAndOpensTheSyncedProfile() throws {
        executionTimeAllowance = AccountScenario.timeAllowance
        let scenario = try AccountScenario()
        launchProduct()
        enterOwnServer(scenario.server)

        walkFocus(to: "signIn.link", "the remote reaches Link from another device")
        press(.select)
        let linkCode = requireCode("link.code", timeout: 60, "the device link shows its code")
        shot("link code")
        publish("link-code", code: linkCode, in: scenario)

        requireProfileTile(scenario.profile, timeout: 360, "once the phone approves the link, the account's profiles show")
        shot("profiles")
        walkFocus(to: "profiles.tile.0", "the remote reaches the profile")
        press(.select)
        require("pin.digit.1", "the locked profile asks for its PIN")
        shot("PIN prompt")
        enterPin(scenario.pin)
        requireGone("pin.digit.1", timeout: 30, "the profile's PIN opens it")

        requireFirstShelfItem(labelled: scenario.syncedTitle, "the Board's first shelf is the synced addon's catalog")
        shot("board with the synced addon")
    }
}
