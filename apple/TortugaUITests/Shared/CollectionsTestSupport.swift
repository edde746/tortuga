import XCTest

/// What the collections tests share: the test addon's collection pack (test_addon.py `--collections`) and what
/// importing it gives. Its "Studios" collection (tabs, the All tab, six folders of the addon's
/// studio catalogs) and "Mixed" (rows: Mixed Sources with Moss Works' movies and a catalog of an addon that isn't
/// installed; a Trakt list; TMDB Only, whose TMDB sources leave it out) become Home's first two rows.
extension TortugaUITestCase {
    /// The pack's link: the test addon's `/collections.json`, beside its manifest.
    var packURL: String {
        get throws {
            let manifest = try addonURL
            guard manifest.hasSuffix("/manifest.json") else { throw HarnessError("the test addon's URL \(manifest) doesn't end in /manifest.json") }
            return manifest.dropLast("manifest.json".count) + "collections.json"
        }
    }

    /// A `--dev` session that keeps its database under `name`, with the test addon and the fake trackers.
    func launchCollections(keepingData name: String) throws {
        launchDev(keepingData: name, addon: try addonURL, arguments: ["--dev-trackers", try trackersURL])
    }

    /// The summary's collections, in the pack's order.
    static let packCollections = ["Studios", "Mixed"]
    /// The collections' rows on Home and in Settings → Home after the import: first, in the pack's order.
    static let studiosRow = 0, mixedRow = 1
    /// The first folders of the two collections: Studios' poster tile with its cover, Mixed's rows folder.
    static let studiosFirstFolder = "Ember Pictures"
    static let mixedFirstFolder = "Mixed Sources"
    /// The note of Mixed Sources' catalog of an addon that isn't installed.
    static let missingAddonNote = "Needs the addon Missing Addon"
    /// The summary's line for the TMDB folder, all of whose sources are left out.
    static let tmdbDroppedLine = "Folder “TMDB Only” in “Mixed” left out"

    /// Whether some element on the screen reads `text`, whole or as part (a text, or a button that takes its texts as
    /// its label: tvOS's summary rows, a tile).
    func showsText(_ text: String) -> Bool {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", text, text)).firstMatch.exists
    }

    /// Waits for the import's summary: the pack's collections as its rows, in order, and the TMDB folder's line; fails
    /// with `what`.
    func requireImportSummary(_ what: String) {
        for (index, title) in Self.packCollections.enumerated() {
            let row = require("collections.import.collection.\(index)", timeout: 30, "\(what): the summary lists collection \(index)")
            if !fullText(of: row).contains(title) { fail("\(what): the summary's collection \(index) reads \"\(fullText(of: row))\", not \(title)") }
        }
        if !waitUntil(5, { showsText(Self.tmdbDroppedLine) }) { fail("\(what): the summary doesn't say \"\(Self.tmdbDroppedLine)\"") }
    }

    /// Waits for Home's first two rows to be the pack's collections (`studiosRow`, `mixedRow`), their first tiles their
    /// first folders; fails with `what`.
    func requireCollectionRows(_ what: String) {
        let studios = require("board.row.\(Self.studiosRow).folder.0", timeout: 60, "\(what): Studios' row shows a folder")
        if studios.label != Self.studiosFirstFolder { fail("\(what): Studios' row starts with \"\(studios.label)\", not \(Self.studiosFirstFolder)") }
        let mixed = require("board.row.\(Self.mixedRow).folder.0", timeout: 10, "\(what): Mixed's row shows a folder")
        if mixed.label != Self.mixedFirstFolder { fail("\(what): Mixed's row starts with \"\(mixed.label)\", not \(Self.mixedFirstFolder)") }
    }
}
