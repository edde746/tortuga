import XCTest

/// Collections on Apple TV by the remote (`CollectionImportStore`, `BoardRow.folders`, `FolderStore`,
/// `CollectionEditorStore`, Settings → Home), on the test addon's collection pack (`CollectionsTestSupport`): imported
/// from its link, its summary walked by Down to Import and Up back onto its last line; launched again, Home opens with
/// focus on the first collection's first tile; a tile opens its folder (a rows folder shows the missing addon's note
/// with its action in the remote's reach; in a tabs folder Up from the grid reaches the tabs; Menu comes back to the
/// tile); the editor asks before Menu drops a change (Keep editing focused first); a collection's row moved up in
/// Settings → Home leads Home.
final class CollectionsTests: TortugaUITestCase {
    func testImportFromALinkWalksItsSummaryAndHomeOpensOnItsFirstTile() throws {
        let data = freshDataName()
        try launchCollections(keepingData: data)
        try importPack(walking: true)
        app.terminate()
        try launchCollections(keepingData: data)
        requireCollectionRows("launched again after the import")
        requireFocus("board.row.0.folder.0", timeout: 30, "Home opens with focus on the first collection's first tile")
        shot("home collections")
        moveFocus(to: "board.row.\(Self.studiosRow).folder.0", with: .down, limit: 2, "Down reaches \(Self.studiosFirstFolder)'s tile")
        shot("home studios")
    }

    func testAFolderShowsItsTabsGridAndARowsFolderItsMissingAddonNote() throws {
        let data = freshDataName()
        try launchCollections(keepingData: data)
        try importPack(walking: false)
        app.terminate()
        try launchCollections(keepingData: data)
        requireFocus("board.row.0.folder.0", timeout: 60, "Home opens on its first tile")
        moveFocus(to: "board.row.\(Self.mixedRow).folder.0", with: Self.mixedRow > Self.studiosRow ? .down : .up, limit: 2, "the remote reaches \(Self.mixedFirstFolder)'s tile")

        press(.select)
        require("folder.row.0.item.0", timeout: 30, "\(Self.mixedFirstFolder)'s first row shows its catalog's titles")
        requireFocus(prefix: "folder.row.0.item.", "the rows folder opens with focus on its first row")
        require("folder.row.1.note", timeout: 10, "the missing addon's row shows its note")
        if !waitUntil(5, { showsText(Self.missingAddonNote) }) { fail("the missing addon's row doesn't say \"\(Self.missingAddonNote)\"") }
        moveFocus(to: "folder.row.1.note.action", with: .down, limit: 2, "Down reaches the note's action")
        shot("folder rows")
        press(.menu)
        requireFocus("board.row.\(Self.mixedRow).folder.0", timeout: 10, "Menu goes back to Home, focus on the tile that opened the folder")

        moveFocus(to: "board.row.\(Self.studiosRow).folder.0", with: Self.studiosRow > Self.mixedRow ? .down : .up, limit: 2, "the remote reaches \(Self.studiosFirstFolder)'s tile")
        press(.select)
        require("folder.grid.item.0", timeout: 30, "\(Self.studiosFirstFolder)'s folder shows All's grid")
        requireFocus(prefix: "folder.grid.item.", "the folder opens with focus on its grid")
        if !element("folder.tab.0").isSelected { fail("the folder opens on another tab than All") }
        shot("folder tabs")
        moveFocus(to: "folder.tab.0", with: .up, limit: 3, "Up from the grid's top row reaches the tabs, on the selected one")
        moveFocus(to: "folder.tab.1", with: .right, limit: 1, "Right moves along the tabs")
        press(.select)
        if !waitUntil(5, { element("folder.tab.1").isSelected }) { fail("Select on the second tab doesn't select it") }
        require("folder.grid.item.0", timeout: 30, "the second tab's grid shows its source's titles")
        press(.menu)
        requireFocus("board.row.\(Self.studiosRow).folder.0", timeout: 10, "Menu goes back to Home, focus on the tile that opened the folder")
    }

    func testTheEditorAsksBeforeDroppingAChange() throws {
        try launchCollections(keepingData: freshDataName())
        try importPack(walking: false)
        openHomeRows()
        let edit = "settings.home.row.\(Self.studiosRow).edit"
        moveFocus(to: edit, with: .down, limit: 8, "Down reaches Studios' Edit")
        press(.select)
        requireFocus("collections.editor.title", timeout: 20, "Edit opens the editor, its title field focused")
        if !fullText(of: element("collections.editor.title")).contains("Studios") {
            fail("the editor's title reads \"\(fullText(of: element("collections.editor.title")))\", not Studios")
        }
        shot("editor")
        press(.menu)
        requireGone("collections.editor.title", "with nothing changed, Menu closes the editor at once")

        requireFocus(edit, timeout: 10, "focus comes back to Edit")
        press(.select)
        requireFocus("collections.editor.title", timeout: 20, "Edit opens the editor again")
        press(.select)
        app.typeText(" Picks")
        putKeyboardAway("the title typed")
        if !waitUntil(5, { fullText(of: element("collections.editor.title")).contains("Picks") }) {
            fail("the title reads \"\(fullText(of: element("collections.editor.title")))\" once typed")
        }
        press(.menu)
        requireDialogFocus("collections.editor.discard.keep", "Menu after a change asks first")
        if !holdsFocus("collections.editor.discard.keep") { fail("the question opens with focus on \(focusedId ?? "nothing"), not Keep editing") }
        require("collections.editor.discard.confirm", "the question offers Discard")
        shot("discard question")
        press(.select)
        requireGone("collections.editor.discard.keep", "Keep editing answers the question")
        if !element("collections.editor.title").exists(within: 5) { fail("Keep editing closes the editor") }
        press(.menu)
        focusDialogButton("collections.editor.discard.confirm", "Menu asks again; the remote reaches Discard")
        press(.select)
        requireGone("collections.editor.title", "Discard closes the editor")
        requireHomeRow(Self.studiosRow, titled: "Studios", "Discard keeps the collection as it was")
    }

    func testACollectionRowMovedUpInSettingsLeadsHome() throws {
        try launchCollections(keepingData: freshDataName())
        try importPack(walking: false)
        openHomeRows()
        requireHomeRow(Self.studiosRow, titled: "Studios", "Settings → Home lists Studios first")
        requireHomeRow(Self.mixedRow, titled: "Mixed", "Mixed follows Studios")
        moveFocus(to: "settings.home.row.\(Self.mixedRow).edit", with: .down, limit: 8, "Down reaches Mixed's row")
        shot("settings home")
        moveFocus(to: "settings.home.row.\(Self.mixedRow).up", with: .right, limit: 3, "Right along Mixed's row reaches Move up")
        press(.select)
        requireHomeRow(0, titled: "Mixed", "Move up puts Mixed first")
        focusTabBar()
        moveFocus(to: "nav.board", with: Self.rightToLeft ? .right : .left, limit: 6, "Left along the tab bar reaches Home")
        if !waitUntil(30, { element("board.row.0.folder.0").label == Self.mixedFirstFolder }) {
            fail("with Mixed moved up, Home's first tile is \"\(element("board.row.0.folder.0").label)\", not \(Self.mixedFirstFolder)")
        }
        if element("board.row.1.folder.0").label != Self.studiosFirstFolder {
            fail("with Mixed moved up, Home's second row starts with \"\(element("board.row.1.folder.0").label)\", not \(Self.studiosFirstFolder)")
        }
        shot("home mixed first")
    }

    /// Settings → Home with its rows listed: the page the import leaves the app on.
    private func openHomeRows() {
        if !element("settings.home.row.0").exists { openSection("home") }
        require("settings.home.row.0", timeout: 30, "Settings → Home lists its rows")
    }

    /// Settings → Home → Import collections: the pack's link typed and fetched, its summary checked (`walking`: Down
    /// from its first row walks every row and line of it to Import, and Up from Import comes back onto its last line),
    /// imported, and Done.
    private func importPack(walking: Bool) throws {
        openSection("home")
        moveFocus(to: "settings.home.import", with: .down, limit: 4, "Down reaches Import collections")
        press(.select)
        requireFocus("collections.import.url", timeout: 10, "Import collections opens on its link field")
        press(.select)
        app.typeText(try packURL + "\n")
        requireImportSummary("the pack's link fetched")
        // A form's row takes focus as a whole on tvOS; the button inside it keeps the identifier (`holdsFocus`).
        let stops = summaryStops()
        if !waitUntil(5, { focusedStop(stops) == stops.first }) {
            fail("the summary takes focus on \(focusedStop(stops) ?? focusedId ?? "nothing"), not its first collection")
        }
        shot("import summary")
        if walking {
            var visited: [String] = []
            for _ in 0..<20 where !holdsFocus("collections.import.confirm") {
                if let id = focusedStop(stops), visited.last != id { visited.append(id) }
                press(.down)
            }
            if !holdsFocus("collections.import.confirm") { fail("Down through the summary doesn't reach Import; focus is on \(focusedId ?? "nothing")") }
            if visited != stops { fail("Down through the summary stops on \(visited), not each of \(stops) in turn") }
            shot("import summary end")
            press(.up)
            if focusedStop(stops) != stops.last { fail("Up from Import goes to \(focusedStop(stops) ?? focusedId ?? "nothing"), not the summary's last line \(stops.last ?? "")") }
            moveFocus(to: "collections.import.confirm", with: .down, limit: 1, "Down comes back to Import")
        } else {
            moveFocus(to: "collections.import.confirm", with: .down, limit: 20, "Down through the summary reaches Import")
        }
        press(.select)
        requireFocus("collections.import.done", timeout: 30, "the import ends with Done, focused")
        press(.select)
        requireGone("collections.import.done", "Done closes Import collections")
    }

    /// The summary's rows and lines, in order, by their identifiers.
    private func summaryStops() -> [String] {
        let ids = Set(
            app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'collections.import.'")).allElementsBoundByIndex.map(\.identifier)
        )
        let collections = (0..<ids.count).map { "collections.import.collection.\($0)" }.filter(ids.contains)
        let lines = (0..<ids.count).map { "collections.import.line.\($0)" }.filter(ids.contains)
        return collections + lines
    }

    /// Which of `stops` holds focus.
    private func focusedStop(_ stops: [String]) -> String? {
        stops.first(where: holdsFocus)
    }
}
