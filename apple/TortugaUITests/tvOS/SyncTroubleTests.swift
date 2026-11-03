import XCTest

/// Sync trouble on Apple TV, on the dev stack behind the test's own relay (`ServerRelay`). Each test has the run's hook
/// make the Dex user's account afresh
/// with one profile, Ana; the app signs in on the relay's address by Dex's device flow (the hook completes it), opens Ana
/// and syncs: Settings → Account reads "Up to date". Signed out at the end, the hook deletes the account.
///
/// The sync status: relaunched with the relay closed (the server out of reach), the app's first sync fails, and Settings
/// → Account's status says so (Apple TV's top tab bar has no profile and no sync status, and no line shows over its
/// screens). The relay open again, Account's Sync now gets through: the status reads "Up to date".
///
/// The profile picker by the remote, the account with a second profile, Ben, that the other session adds: the picker
/// opens with focus on Ana; Right and Left go across Ana, Ben and Add profile a tile a press; Down from Ana and from Add
/// profile reaches Manage profiles, centred under the tiles, and Up from it lands on Ana: not on Ben, the tile nearest
/// above it, nor on the tile it came from.
final class SyncTroubleTests: TortugaUITestCase {
    /// The sign-ins `signInThroughRelay` has made in this run. The run's watcher takes each event's name once, so
    /// each sign-in after the run's first hands over its device code and its asks of the other session under names of
    /// its own (`device-code-2`, `peer-reset-2`, …): the count goes on from test to test.
    private static var signIns = 0
    /// This test's sign-in among them (`signIns`): its events' number.
    private var signIn = 1

    func testSyncTroubleShowsInAccountUntilSyncNowGetsThrough() throws {
        continueAfterFailure = false
        executionTimeAllowance = 1200
        let (relay, exchange) = try signInThroughRelay()

        // A running app's open connections would end with the relay too, but its sync would wait for its next turn.
        app.terminate()
        relay.close()
        if relay.answers() { fail("the server still answers at \(relay.url) once the relay is closed") }
        launchProduct()
        openProfile("relaunched with the server out of reach")
        openSection("account")
        requireAccountStatus(SyncStatusText.offline, timeout: 90, "relaunched with the server out of reach")
        shot("account, server out of reach")

        try relay.open()
        moveFocus(to: "settings.account.syncNow", with: .up, limit: 10, "Up reaches Sync now")
        press(.select)
        requireAccountStatus(SyncStatusText.idle, timeout: 60, "the server back, Sync now")
        shot("account, synced again")

        signOut()
        peer("delete", exchange, "the other session deletes the account", number: signIn)
    }

    func testProfilePickerFocusStartsAndComesBackOnTheFirstProfile() throws {
        continueAfterFailure = false
        executionTimeAllowance = 1200
        var problems: [String] = []
        let (_, exchange) = try signInThroughRelay(adding: ["Ben"]) { [self] in
            let ben = element("profiles.tile.1")
            if !waitUntil(60, { ben.exists && ben.label == "Ben" }) {
                fail("the picker's second profile is \(ben.exists ? "\"\(ben.label)\"" : "missing"), not Ben, the other session's")
            }
            require("profiles.manage", "the picker offers Manage profiles")
            screenSettles()
            shot("profiles, Ana and Ben")
            if !waitUntil(5, { holdsFocus("profiles.tile.0") }) {
                problems.append("the picker opens with focus on \(focusedId ?? "nothing"), not profiles.tile.0 (Ana)")
                walkFocus(to: "profiles.tile.0", "the remote reaches Ana")
            }
            // One press of `button` (`name`) from `from` lands on `to`; else the problem, and the remote walks there.
            @MainActor func step(_ button: XCUIRemote.Button, _ name: String, from: String, to: String) {
                press(button)
                if waitUntil(3, { holdsFocus(to) }) { return }
                problems.append("\(name) from \(from) leaves focus on \(focusedId ?? "nothing"), not \(to)")
                walkFocus(to: to, "the remote reaches \(to)")
            }
            step(.right, "Right", from: "profiles.tile.0", to: "profiles.tile.1")
            step(.right, "Right", from: "profiles.tile.1", to: "profiles.add")
            step(.left, "Left", from: "profiles.add", to: "profiles.tile.1")
            step(.left, "Left", from: "profiles.tile.1", to: "profiles.tile.0")
            step(.down, "Down", from: "profiles.tile.0", to: "profiles.manage")
            step(.up, "Up", from: "profiles.manage", to: "profiles.tile.0")
            walkFocus(to: "profiles.add", "the remote reaches Add profile")
            step(.down, "Down", from: "profiles.add", to: "profiles.manage")
            step(.up, "Up", from: "profiles.manage (reached from profiles.add)", to: "profiles.tile.0")
        }
        signOut()
        peer("delete", exchange, "the other session deletes the account", number: signIn)
        if !problems.isEmpty { fail(problems.joined(separator: "\n")) }
    }

    /// The relay to the dev stack's server, open, and the run's exchange directory, once the other session has made the
    /// account afresh, with Ana and then `others`, and the app has signed in to it on the relay's address by Dex's
    /// device flow, opened Ana and read "Up to date" in Settings → Account. `atPicker` runs while the profile picker
    /// shows, before Ana is opened. The relay closes as the test ends.
    private func signInThroughRelay(adding others: [String] = [], atPicker: (() -> Void)? = nil) throws -> (relay: ServerRelay, exchange: URL) {
        let server = try AccountScenario.variable("TORTUGA_SERVER")
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        let relay = try ServerRelay(to: server)
        try relay.open()
        addTeardownBlock { relay.close() }
        if !relay.answers() { fail("the dev stack's server doesn't answer through the relay at \(relay.url)") }
        Self.signIns += 1
        signIn = Self.signIns
        peer("reset", exchange, "the other session makes the account afresh", number: signIn)
        for name in others {
            peer("profile \(name)", exchange, "the other session adds the profile \(name)", number: signIn)
        }

        launchProduct()
        enterOwnServer(relay.url)
        walkFocus(to: "signIn.option.oidc", "the remote reaches the OpenID Connect provider")
        press(.select)
        let code = requireCode("deviceCode.code", timeout: 60, "Dex's device flow shows its code")
        publish(signIn == 1 ? "device-code" : "device-code-\(signIn)", code: code, to: exchange)
        if let atPicker {
            require("profiles.tile.0", timeout: 180, "signed in with Dex, the profile picker shows")
            atPicker()
        }
        openProfile("signed in with Dex")
        openSection("account")
        requireAccountStatus(SyncStatusText.idle, timeout: 60, "signed in, the account syncs")
        return (relay, exchange)
    }

    /// Settings → Account's Sign out, confirmed, back to the sign-in screen.
    private func signOut() {
        // Down, as GitHubTests does: the form lays out rows below the screen only as focus nears them.
        moveFocus(to: "settings.account.signOut", with: .down, limit: 20, "Down reaches Sign out")
        press(.select)
        require("settings.account.dialog.confirm", timeout: 10, "Sign out asks first")
        focusDialogButton("settings.account.dialog.confirm", "the remote reaches the confirmation")
        press(.select)
        if !waitUntil(60, { element("signIn.option.oidc").exists || element("signIn.server.edit").exists }) {
            fail("signing out doesn't return to the sign-in screen")
        }
    }
}
