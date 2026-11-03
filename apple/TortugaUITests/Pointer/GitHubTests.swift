import XCTest

/// Sign in with GitHub on iPhone, iPad and the Mac, on the dev stack (server/compose.dev.yaml) and its fake GitHub
/// (server/dev/fake_github.py), which approves every
/// authorization at once as the browser's GitHub user. The run's hook makes the Dex user's account afresh with one
/// profile, Ana. The stack's sign-in options list GitHub; the app signs in by Dex's device flow
/// (the hook completes it) and links GitHub from Settings → Account through GitHub's web flow in the system's web
/// authentication session (iOS: a sheet over the app; the Mac: the default browser), after the system asks whether
/// Tortuga may use the site to sign in, which the test answers with Continue; the server's return page redirects the
/// session back to the app (`tortuga://signin`), the app collects the result, and the account's sign-ins list GitHub.
/// Signed out, the app signs in with GitHub alone, through the same session and the same GitHub user (the fake's cookie
/// in the browser), into that account: its sign-ins list both. The hook then deletes the account.
final class GitHubTests: TortugaUITestCase {
    func testLinksGitHubThenSignsInWithIt() throws {
        continueAfterFailure = false
        executionTimeAllowance = 900
        let server = try AccountScenario.variable("TORTUGA_SERVER")
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        peer("reset", exchange, "the other session makes the account afresh")

        // On the Mac a session an earlier run left belongs to an account the reset deleted: its first call signs it out.
        launchProduct()
        showStackOptions(server, "the sign-in screen shows")
        require("signIn.option.github", "the dev stack's sign-in options list GitHub")
        shot("sign-in with GitHub", of: shotSource)
        activate(element("signIn.option.oidc"))
        publish("device-code", code: requireCode("deviceCode.code", timeout: 60, "Dex's device flow shows its code"), to: exchange)
        openProfile("signed in with Dex")

        openSection("account")
        let github = listedSignIn("GitHub")
        require("settings.account.linkIdentity", timeout: 30, "the account offers Add a sign-in method")
        if github.exists { fail("the new account lists GitHub among its sign-ins before it is linked") }
        activateShown("settings.account.linkIdentity", "the account offers Add a sign-in method")
        let option = require("identityLink.option.github", timeout: 30, "Add a sign-in method offers GitHub")
        shot("add a sign-in method", of: shotSource)
        // GitHub's page in the browser, in front of the app until it comes back.
        withAnotherAppInFront {
            activate(option)
            continueToWebSignIn("linking GitHub")
            if !github.exists(within: 90) { fail("once GitHub's page is done, the account's sign-ins don't list GitHub") }
            returnFromBrowser()
        }
        shot("GitHub linked", of: shotSource)

        activateShown("settings.account.signOut", "the account offers Sign out")
        activate(require("settings.account.dialog.confirm", timeout: 10, "Sign out asks first"))
        showStackOptions(server, "signing out returns to the sign-in screen")
        withAnotherAppInFront {
            activate(require("signIn.option.github", "the sign-in options list GitHub"))
            continueToWebSignIn("signing in with GitHub")
            openProfile("signed in with GitHub")
            returnFromBrowser()
        }
        openSection("account")
        if !github.exists(within: 30) { fail("signed in with GitHub, the account's sign-ins don't list GitHub") }
        // Two sign-ins, each removable: GitHub's and Dex's, the account GitHub was linked to.
        require("settings.account.identity.1.unlink", timeout: 10, "signed in with GitHub, the account lists a second sign-in")
        shot("signed in with GitHub", of: shotSource)

        peer("delete", exchange, "the other session deletes the account")
    }
}
