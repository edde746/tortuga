import XCTest

/// Collections on iPhone, iPad and the Mac (`CollectionImportStore`, `BoardRow.folders`, `FolderStore`,
/// `CollectionEditorStore`, Settings → Home), on the test addon's collection pack (`CollectionsTestSupport`): imported
/// from its link after its summary, its collections are Home's first rows of folder tiles; a tile opens its folder (a
/// tabs folder's chips over its grid, a rows folder's shelves with a missing addon's note); the editor asks before Cancel
/// drops a change; a collection's row moved up in Settings → Home leads Home.
final class CollectionsTests: TortugaUITestCase {
    func testImportFromALinkPutsItsCollectionsOnHome() throws {
        try launchCollections(keepingData: freshDataName())
        try importPack()
        activate(navItem("nav.board", "the app lists Home"))
        requireCollectionRows("after the import")
        shot("home studios")
    }

    func testAFolderShowsItsTabsGridAndARowsFolderItsMissingAddonNote() throws {
        try launchCollections(keepingData: freshDataName())
        try importPack()
        activate(navItem("nav.board", "the app lists Home"))
        requireCollectionRows("after the import")

        activate(scrollTo("board.row.\(Self.studiosRow).folder.0", "Home shows \(Self.studiosFirstFolder)'s tile"))
        let all = require("folder.tab.0", timeout: 20, "\(Self.studiosFirstFolder)'s tile opens its folder on its tabs")
        if all.label != "All" { fail("the folder's first tab is \"\(all.label)\", not All") }
        if !all.isSelected { fail("the folder opens on another tab than All") }
        require("folder.tab.2", "after All, a tab for each of the folder's two sources")
        require("folder.grid.item.0", timeout: 30, "All's grid shows the sources' titles")
        shot("folder tabs")
        activate(element("folder.tab.1"))
        if !waitUntil(5, { element("folder.tab.1").isSelected && !element("folder.tab.0").isSelected }) {
            fail("\(Self.tapping) on the second tab doesn't select it")
        }
        require("folder.grid.item.0", timeout: 30, "the second tab's grid shows its source's titles")
        goBack()

        activate(scrollTo("board.row.\(Self.mixedRow).folder.0", "Home shows \(Self.mixedFirstFolder)'s tile"))
        require("folder.row.0.item.0", timeout: 30, "\(Self.mixedFirstFolder)'s first row shows its catalog's titles")
        require("folder.row.1.note", timeout: 10, "the missing addon's row shows its note")
        if !waitUntil(5, { showsText(Self.missingAddonNote) }) { fail("the missing addon's row doesn't say \"\(Self.missingAddonNote)\"") }
        require("folder.row.1.note.action", "the missing addon's note offers its action")
        shot("folder rows")
        goBack()
        require("board.row.\(Self.mixedRow).folder.0", timeout: 10, "Back from the folder shows Home")
    }

    func testTheEditorAsksBeforeDroppingAChange() throws {
        try launchCollections(keepingData: freshDataName())
        try importPack()
        openSection("home")
        requireHomeRow(Self.studiosRow, titled: "Studios", "Settings → Home lists Studios after Mixed")

        let title = openEditor("Studios")
        shot("editor")
        activate(element("collections.editor.back"))
        requireGone("collections.editor.title", "with nothing changed, Cancel closes the editor at once")

        let edited = openEditor("Studios once more")
        // At the field's trailing end, so the text typed follows the title.
        #if os(macOS)
        ready(edited).coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).click()
        #else
        ready(edited).coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        #endif
        edited.typeText(" Picks")
        activate(element("collections.editor.back"))
        require("collections.editor.discard.keep", timeout: 5, "Cancel after a change asks first")
        require("collections.editor.discard.confirm", timeout: 5, "the question offers Discard")
        shot("discard question")
        activate(element("collections.editor.discard.keep"))
        requireGone("collections.editor.discard.keep", "Keep editing answers the question")
        if !title.exists(within: 5) { fail("Keep editing closes the editor") }
        if !fullText(of: title).contains("Picks") { fail("Keep editing leaves the title \"\(fullText(of: title))\", not the one typed") }
        activate(element("collections.editor.back"))
        activate(require("collections.editor.discard.confirm", timeout: 5, "Cancel asks again"))
        requireGone("collections.editor.title", "Discard closes the editor")
        requireHomeRow(Self.studiosRow, titled: "Studios", "Discard keeps the collection as it was")
    }

    func testACollectionRowMovedUpInSettingsLeadsHome() throws {
        try launchCollections(keepingData: freshDataName())
        try importPack()
        openSection("home")
        requireHomeRow(Self.studiosRow, titled: "Studios", "Settings → Home lists Studios first")
        requireHomeRow(Self.mixedRow, titled: "Mixed", "Mixed follows Studios")
        shot("settings home")
        activate(require("settings.home.row.\(Self.mixedRow).more", "Mixed's row shows ⋯"))
        pickMenuEntry(require("settings.home.row.\(Self.mixedRow).up", timeout: 5, "⋯ on Mixed offers Move up"))
        requireHomeRow(0, titled: "Mixed", "Move up puts Mixed first")
        activate(navItem("nav.board", "the app lists Home"))
        if !waitUntil(30, { element("board.row.0.folder.0").label == Self.mixedFirstFolder }) {
            fail("with Mixed moved up, Home's first tile is \"\(element("board.row.0.folder.0").label)\", not \(Self.mixedFirstFolder)")
        }
        if element("board.row.1.folder.0").label != Self.studiosFirstFolder {
            fail("with Mixed moved up, Home's second row starts with \"\(element("board.row.1.folder.0").label)\", not \(Self.studiosFirstFolder)")
        }
        shot("home mixed first")
    }

    /// Settings → Home → Import collections, the pack's link typed and fetched, its summary checked and imported, and
    /// Done.
    private func importPack() throws {
        openSection("home")
        activate(require("settings.home.import", timeout: 30, "Settings → Home offers Import collections"))
        let field = require("collections.import.url", "Import collections asks for a link")
        activate(field)
        field.typeText(try packURL)
        activate(require("collections.import.fetch", "Import collections offers Get"))
        requireImportSummary("the pack's link fetched")
        shot("import summary")
        activate(require("collections.import.confirm", "the summary offers Import"))
        activate(require("collections.import.done", timeout: 30, "the import ends with Done"))
        requireGone("collections.import.done", "Done closes Import collections")
    }

    /// Settings → Home's Studios row's ⋯, then Edit: the editor's title field, which reads Studios; fails with `what`.
    private func openEditor(_ what: String) -> XCUIElement {
        let row = "settings.home.row.\(Self.studiosRow)"
        activate(require("\(row).more", "\(what): the collection's row shows ⋯"))
        pickMenuEntry(require("\(row).edit", timeout: 5, "\(what): ⋯ on the collection offers Edit"))
        let title = require("collections.editor.title", timeout: 20, "\(what): Edit opens the editor")
        if !waitUntil(5, { fullText(of: title).contains("Studios") }) {
            fail("\(what): the editor's title reads \"\(fullText(of: title))\", not Studios")
        }
        return title
    }
}
