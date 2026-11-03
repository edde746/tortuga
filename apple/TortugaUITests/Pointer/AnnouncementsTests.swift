import XCTest

/// Messages that show away from VoiceOver's focus are announced as they show, on iPhone, iPad and the Mac (what was
/// announced: `requireAnnounced`): the PIN pad's mismatch when a new PIN's second entry differs from the first, and a
/// card menu's toast when Mark as watched fails (the test addon's series in Search, whose details don't load: the test
/// addon with `--search`).
final class AnnouncementsTests: TortugaUITestCase {
    func testAMismatchedPinIsAnnounced() throws {
        try launchDev()
        activate(navItem("nav.settings", "Settings is a destination"))
        activate(require("settings.profile.edit", timeout: 30, "Settings offers Edit profile"))
        require("editor.name", "Edit profile opens the editor")
        activateShown("editor.pin.set", "the editor offers Set a PIN")
        enterPin([1, 1, 1, 1])
        enterPin([2, 2, 2, 2])
        requireAnnounced("The PINs didn't match. Enter a new one.", "a second PIN that differs from the first")
        shot("pin mismatch")
    }

    func testAFailedMarkIsAnnouncedAsItsToastShows() throws {
        try launchDev(addon: true, arguments: ["--dev-addons-only"])
        activate(navItem("nav.search", "Search is a destination"))
        let field = require("search.field", "Search shows its field")
        activate(field)
        field.typeText("matrix\n")
        // The addon's catalogs alone: its movies, then its series.
        let card = requireCard("search.group.1.item.0", timeout: 30, "typing matrix lists the test addon's series")
        openItemMenu(card)
        pickMenuEntry(require("search.group.1.item.0.menu.markWatched", timeout: 5, "a \(Self.menuGesture) on the series offers Mark as watched"))
        let toast = require("app.menuNotice", timeout: 30, "Mark as watched on a series whose details don't load shows why")
        let said = text(of: toast)
        shot("mark failed")
        if !said.contains("Tortuga Search 1") { fail("the toast says \(said.debugDescription), not why Tortuga Search 1 isn't marked") }
        requireAnnounced(said, "the toast of a failed Mark as watched")
    }
}
