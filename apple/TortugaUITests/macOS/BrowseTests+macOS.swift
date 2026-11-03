import XCTest

/// The browse screens with the pointer and keyboard, the Mac's own (the shared ones: `BrowseTests`): a click on Search
/// in the sidebar puts the insertion point in the field: from another destination, and again while Search shows; a
/// launch on Search and Back from a result's Detail leave the field unfocused, its text kept.
extension BrowseTests {
    func testSearchInTheSidebarPutsTheInsertionPointInTheField() throws {
        try launchDev(open: ["search"])
        let field = requireHeaderSearchField("a launch on Search shows its field")
        requireNoFieldFocus(field, "a launch on Search")
        ready(require("nav.board", "the sidebar lists Home")).click()
        require("board.row.0.item.0", timeout: 90, "the Board shows its shelves")
        ready(require("nav.search", "the sidebar lists Search")).click()
        requireTyping("matrix", into: field, "a click on Search from Home")
        shot("search clicked")
        ready(requireCard("search.group.0.item.0", timeout: 30, "matrix typed after the click lists results")).click()
        requireDetail("a click on a result opens its Detail screen")
        goBack()
        requireCard("search.group.0.item.0", "back returns to the results")
        requireNoFieldFocus(field, "back from a result's Detail")
        if (field.value as? String) != "matrix" { fail("back from Detail, the field shows \(String(describing: field.value)), not matrix") }
        ready(require("nav.search", "the sidebar lists Search")).click()
        requireTyping("dune", into: field, "a click on Search while Search shows")
        shot("search clicked again")
    }

    /// Types `text` into the window as the user would, without clicking: fails with `what` unless it lands in `field`,
    /// which XCUITest then reports focused (`requireNoFieldFocus` reads the same).
    private func requireTyping(_ text: String, into field: XCUIElement, _ what: String) {
        // The field takes focus a moment after the screen shows.
        _ = waitUntil(3, { hasFocus(field) })
        app.typeText(text)
        if !waitUntil(5, { (field.value as? String)?.contains(text) == true }) {
            fail("\(what): typed \(text), the field shows \(String(describing: field.value))")
        }
        if !hasFocus(field) { fail("\(what): XCUITest doesn't report the field focused while it takes the keys") }
    }

    /// Fails with `what` while `field` has the keyboard focus.
    private func requireNoFieldFocus(_ field: XCUIElement, _ what: String) {
        // Held for 2 s: the field would take focus a moment after the screen shows.
        if waitUntil(2, { hasFocus(field) }) { fail("\(what) puts the insertion point in Search's field") }
    }

    /// XCUITest's own attribute, which its API doesn't name.
    private func hasFocus(_ field: XCUIElement) -> Bool { field.value(forKey: "hasKeyboardFocus") as? Bool == true }
}
