import XCTest

/// Sign in with GitHub on Apple TV, by GitHub's device flow on the dev stack (server/compose.dev.yaml) and its fake
/// GitHub (server/dev/fake_github.py). The run's hook makes the
/// Dex user's account afresh with one profile, Ana. The stack's sign-in options list GitHub; the
/// app signs in by Dex's device flow (the hook completes it) and links GitHub from Settings → Account by GitHub's
/// device flow, whose code the hook approves at the fake as the run's GitHub user; the
/// account's sign-ins then list GitHub. Signed out, the app signs in with GitHub alone, its code approved for the same
/// user, into that account: its sign-ins list both. The hook then deletes the account.
final class GitHubTests: TortugaUITestCase {
    func testLinksGitHubThenSignsInWithIt() throws {
        continueAfterFailure = false
        executionTimeAllowance = 1200
        let server = try AccountScenario.variable("TORTUGA_SERVER")
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        peer("reset", exchange, "the other session makes the account afresh")

        launchProduct()
        enterOwnServer(server)
        require("signIn.option.github", "the dev stack's sign-in options list GitHub")
        // The server panel is still fading out as the options come: the shot once the screen has settled.
        screenSettles()
        shot("sign-in with GitHub")
        walkFocus(to: "signIn.option.oidc", "the remote reaches the OpenID Connect provider")
        press(.select)
        publish("device-code", code: requireCode("deviceCode.code", timeout: 60, "Dex's device flow shows its code"), to: exchange)
        openProfile("signed in with Dex")

        openSection("account")
        let github = listedSignIn("GitHub")
        require("settings.account.linkIdentity", timeout: 30, "the account offers Add a sign-in method")
        if github.exists { fail("the new account lists GitHub among its sign-ins before it is linked") }
        moveFocus(to: "settings.account.linkIdentity", with: .down, limit: 20, "Down reaches Add a sign-in method")
        press(.select)
        require("identityLink.option.github", timeout: 30, "Add a sign-in method offers GitHub")
        walkFocus(to: "identityLink.option.github", "the remote reaches GitHub")
        shot("add a sign-in method, GitHub focused")
        press(.select)
        // The code only goes to the hook: no screenshot shows it.
        publish("github-code", code: requireCode("deviceCode.code", timeout: 60, "GitHub's device flow shows its code"), to: exchange)
        if !github.exists(within: 120) { fail("once the hook approves the code, the account's sign-ins don't list GitHub") }
        shot("GitHub linked")

        // Down, as the tour does: the form lays out rows below the screen only as focus nears them (Sign out's frame is
        // empty until then), so walking towards its frame goes the wrong way.
        moveFocus(to: "settings.account.signOut", with: .down, limit: 20, "Down reaches Sign out")
        press(.select)
        require("settings.account.dialog.confirm", timeout: 10, "Sign out asks first")
        focusDialogButton("settings.account.dialog.confirm", "the remote reaches the confirmation")
        press(.select)
        if !waitUntil(60, { element("signIn.option.oidc").exists || element("signIn.server.edit").exists }) {
            fail("signing out doesn't return to the sign-in screen")
        }
        if !element("signIn.option.oidc").exists(within: 10) { enterOwnServer(server) }
        walkFocus(to: "signIn.option.github", "the remote reaches GitHub")
        press(.select)
        publish("github-code-2", code: requireCode("deviceCode.code", timeout: 60, "GitHub's device flow shows its code"), to: exchange)
        openProfile("signed in with GitHub")
        // The tab bar a sign-in brings after the first in one process carries no `nav.*` identifiers on tvOS (as the one
        // a stack's first top-level destination brings, DetailTests): a relaunch, still signed in, brings Home with them.
        app.terminate()
        launchProduct()
        openProfile("relaunched after signing in with GitHub")
        openSection("account")
        if !github.exists(within: 30) { fail("signed in with GitHub, the account's sign-ins don't list GitHub") }
        // Two sign-ins, each removable: GitHub's and Dex's, the account GitHub was linked to.
        require("settings.account.identity.1.unlink", timeout: 10, "signed in with GitHub, the account lists a second sign-in")
        shot("signed in with GitHub")

        peer("delete", exchange, "the other session deletes the account")
    }
}
