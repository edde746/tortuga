import XCTest

/// The account an Android phone signed up for, joined by touch: the iPhone signs in on the phone's self-hosted server
/// with its OpenID Connect provider's device flow, and the run completes the provider's login with the code on screen;
/// signed in, the iPhone has the account's data: the phone's PIN-locked profile shows, opens with its PIN on the pad,
/// and the Board's first shelf is the catalog of the addon the phone installed and moved to the top.
final class AccountTests: TortugaUITestCase {
    func testSignsInAndOpensTheSyncedProfile() throws {
        executionTimeAllowance = AccountScenario.timeAllowance
        let scenario = try AccountScenario()
        launchProduct()
        enterOwnServer(scenario.server)

        ready("signIn.option.oidc").tap()
        let deviceCode = requireCode("deviceCode.code", timeout: 60, "the provider's device flow shows its code")
        shot("device code")
        publish("device-code", code: deviceCode, in: scenario)

        let tile = requireProfileTile(scenario.profile, timeout: 180, "once the provider's login completes, the account's profiles show")
        shot("profiles")
        ready(tile).tap()
        require("pin.digit.1", "the locked profile asks for its PIN")
        shot("PIN prompt")
        enterPin(scenario.pin)
        requireGone("pin.digit.1", timeout: 30, "the profile's PIN opens it")

        requireFirstShelfItem(labelled: scenario.syncedTitle, "the Board's first shelf is the synced addon's catalog")
        shot("board with the synced addon")
    }
}
