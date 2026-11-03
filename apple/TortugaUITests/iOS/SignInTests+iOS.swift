import XCTest

/// Sign-in with a hardware keyboard, what iOS alone needs a test for: Escape presses a page's back button with nothing
/// on the page focused (on the Mac the window's Escape always reached it). The simulator never hands XCUITest's Escape
/// key to the app, so the tests type Command-period, which iOS delivers as Escape.
extension SignInTests {
    /// The link page (Link from another device), a full-screen step with Back in its bar: Escape leaves it, back to the
    /// sign-in options, whatever the link's request to the fake server came to.
    func testEscapeLeavesTheLinkPage() throws {
        try releaseSignInOptions()
        launch(["--dev-sign-in-server", try signInServer.absoluteString])
        ready(require("signIn.link", timeout: 60, "the sign-in screen offers Link from another device")).tap()
        require("signIn.back", "the link page has Back in its bar")
        if element("signIn.link").exists { fail("Link from another device stays on the screen: the link page didn't open") }
        shot("link page")
        app.typeKey(".", modifierFlags: .command)
        requireGone("signIn.back", "Escape (Command-period) leaves the link page")
        require("signIn.link", "Escape goes back to the sign-in options")
        shot("sign-in after Escape")
    }
}
