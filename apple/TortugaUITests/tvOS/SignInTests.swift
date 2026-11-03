import XCTest

/// Sign-in with the remote on the product's first launch, on the test addon's fake of the hosted server
/// (`signInServer`), which answers when the test says: the splash before it names the start's step once the start takes
/// over half a second; "Use your own server" stays where it first shows as the server's options come above it (sampled
/// from a thread of its own); Down moves focus to it once they came, Select opens the server panel, which shows whole
/// (its title, the address field and Check server inside it; Check server, focused, a white platter with its label dark
/// on it) and Menu closes it.
final class SignInTests: TortugaUITestCase {
    func testTheSplashNamesTheStepOfASlowStart() throws {
        try requireSplashStatus()
    }

    func testUseYourOwnServerStaysPutAsTheOptionsLoad() throws {
        try requireServerLineStaysPut("the sign-in screen as it opens")
    }

    func testServerPanelShowsWholeAndMenuClosesIt() throws {
        try releaseSignInOptions()
        launch(["--dev-sign-in-server", try signInServer.absoluteString])
        let edit = require("signIn.server.edit", timeout: 60, "the sign-in screen shows")
        if !app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'signIn.option.'")).firstMatch.exists(within: 30) {
            fail("the sign-in options don't show")
        }
        shot("sign-in")
        moveFocus(to: "signIn.server.edit", with: .down, "Down reaches Use your own server")
        let title = edit.label
        shot("use your own server focused")
        press(.select)
        let field = require("signIn.server.field", "Select opens the server panel")
        requireFocus("signIn.server.field", "the address field has focus once the panel opens")
        // An address, so Check server is enabled (and focusable); Menu puts the keyboard away and keeps the text.
        press(.select)
        app.typeText("tortuga.invalid")
        putKeyboardAway("the address typed")
        let submit = require("signIn.server.submit", "the panel has Check server")
        moveFocus(to: "signIn.server.submit", with: .down, "Down from the field reaches Check server")
        shot("check server focused")
        if submit.label.isEmpty { fail("Check server has no label") }
        if let problem = Self.unreadableFocusedLabel(submit) { fail("Check server, focused: \(problem)") }

        let panel = require("signIn.server.panel", "the server form is a panel")
        let heading = require("signIn.server.title", "the panel has a title")
        if heading.label != title { fail("the panel is titled \"\(heading.label)\", not \"\(title)\"") }
        for (name, element) in [("the title", heading), ("the address field", field), ("Check server", submit)] {
            if !panel.frame.contains(element.frame) { fail("\(name) lies outside the panel: \(element.frame) in \(panel.frame)") }
        }
        if !app.frame.contains(panel.frame) { fail("the panel runs off the screen: \(panel.frame) in \(app.frame)") }

        press(.menu)
        requireGone("signIn.server.field", "Menu closes the server panel")
        requireFocus("signIn.server.edit", "focus returns to Use your own server")
        shot("panel closed")
    }

    /// Why the focused `element`'s label can't be read off the screen, or nil when it can: across the middle half of its
    /// height, the element is a light platter with dark pixels (the label) between its light ones in each row. A white
    /// bar whose label is white or clipped away has none there.
    private static func unreadableFocusedLabel(_ element: XCUIElement) -> String? {
        guard let pixels = ScreenPixels(element.screenshot()) else { return "its screenshot can't be read" }
        let width = pixels.width, height = pixels.height
        func luminance(_ x: Int, _ y: Int) -> Int {
            let (red, green, blue) = pixels.colour(x: x, y: y)
            return (299 * Int(red) + 587 * Int(green) + 114 * Int(blue)) / 1000
        }
        var light = 0, dark = 0, band = 0
        for y in height / 4 ..< height * 3 / 4 {
            let row = (0..<width).map { luminance($0, y) }
            band += width
            guard let first = row.firstIndex(where: { $0 > 160 }), let last = row.lastIndex(where: { $0 > 160 }) else { continue }
            light += row[first...last].filter { $0 > 160 }.count
            dark += row[first...last].filter { $0 < 100 }.count
        }
        if band == 0 { return "it has no size (\(width)×\(height) px)" }
        if light * 2 < band { return "no light platter (\(light) of \(band) px light)" }
        if dark < 150 { return "no dark label on its platter (\(dark) dark px among \(light) light)" }
        return nil
    }
}
