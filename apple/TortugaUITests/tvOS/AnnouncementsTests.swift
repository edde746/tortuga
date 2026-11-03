import XCTest

/// Messages that show away from VoiceOver's focus are announced as they show, on Apple TV by the remote (what was
/// announced: `requireAnnounced`): the PIN pad's mismatch when a new PIN's second entry differs from the first, and a
/// card menu's toast when Mark as watched fails (the test addon's series in Search, whose details don't load: the
/// test addon with `--search`).
final class AnnouncementsTests: TortugaUITestCase {
    func testAMismatchedPinIsAnnounced() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        focusTabBar()
        moveFocus(to: "nav.settings", with: .right, "the remote reaches the Settings tab")
        require("settings.section.playback", timeout: 10, "Settings shows")
        press(.down)
        moveFocus(to: "settings.section.playback", with: .down, limit: 6, "Down reaches Playback")
        // The dev profile's card has Edit profile alone, above the first section.
        press(.up)
        requireFocus("settings.profile.edit", "Up from Playback reaches Edit profile")
        press(.select)
        require("editor.name", "Edit profile opens the editor")
        walkFocus(to: "editor.pin.set", "the remote reaches Set a PIN")
        press(.select)
        require("pin.digit.1", "Set a PIN opens the pad")
        enterPin([1, 1, 1, 1])
        enterPin([2, 2, 2, 2])
        requireAnnounced("The PINs didn't match. Enter a new one.", "a second PIN that differs from the first")
        shot("pin mismatch")
    }

    func testAFailedMarkIsAnnouncedAsItsToastShows() throws {
        try launchDev(addon: true, arguments: ["--dev-addons-only"])
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        focusTabBar()
        moveFocus(to: "nav.search", with: .right, "the remote reaches the Search tab")
        let field = app.searchFields.firstMatch
        if !field.exists(within: 10) { fail("Search shows no search field") }
        press(.down)
        field.typeText("matrix")
        // The addon's catalogs alone: its movies, then its series.
        focusIntoShelf("search.group.0", with: .down, "Down moves focus from the keyboard to the test addon's movies")
        focusIntoShelf("search.group.1", with: .down, "Down moves focus to the test addon's series")
        moveFocus(to: "search.group.1.item.0", with: .left, limit: 6, "Left reaches the first series")
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        walkFocus(to: "search.group.1.item.0.menu.markWatched", "holding Select on the series opens its menu with Mark as watched")
        press(.select)
        let toast = require("app.menuNotice", timeout: 30, "Mark as watched on a series whose details don't load shows why")
        let said = text(of: toast)
        shot("mark failed")
        if !said.contains("Tortuga Search 1") { fail("the toast says \(said.debugDescription), not why Tortuga Search 1 isn't marked") }
        requireAnnounced(said, "the toast of a failed Mark as watched")
    }
}
