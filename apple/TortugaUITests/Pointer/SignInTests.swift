import XCTest

/// Sign-in by touch (iOS) and with the pointer (the Mac) on the product's first launch, on the test addon's fake of the
/// hosted server (`signInServer`), which answers when the test says: the splash before it names the start's step once
/// the start takes over half a second; "Use your own server" stays where it first shows as the server's options come
/// above it (sampled from a thread of its own), opens the server sheet, which stays up as the options come, and its
/// Cancel closes it.
final class SignInTests: TortugaUITestCase {
    func testTheSplashNamesTheStepOfASlowStart() throws {
        try requireSplashStatus()
    }

    func testUseYourOwnServerStaysPutAsTheOptionsLoad() throws {
        try requireServerLineStaysPut("the sign-in screen as it opens")
    }

    func testOwnServerOpensTheServerSheetAndCancelClosesIt() throws {
        try holdSignInOptions()
        launch(["--dev-sign-in-server", try signInServer.absoluteString])
        let edit = require("signIn.server.edit", timeout: 60, "the sign-in screen shows")
        shot("sign-in")
        activate(edit)
        let field = require("signIn.server.field", "Use your own server opens the server sheet")
        shot("server sheet")
        try releaseSignInOptions()
        // The options come under the sheet at once; the sheet stays up.
        if waitUntil(3, { !field.exists }) { fail("the server sheet closes as the sign-in options come") }
        activate(element("signIn.server.cancel"))
        requireGone("signIn.server.field", "Cancel closes the server sheet")
        require("signIn.server.edit", "the sign-in screen shows again")
        if !app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'signIn.option.'")).firstMatch.exists(within: 10) {
            fail("the sign-in options that came under the sheet don't show")
        }
        shot("sheet closed")
    }
}
