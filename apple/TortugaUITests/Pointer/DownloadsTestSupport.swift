import XCTest

/// What the iOS and macOS downloads tests share: the dev session they keep their downloads in, the test addon's
/// download items (tools/media/test_addon.py --downloads), and reading the Downloads screen's rows.
extension TortugaUITestCase {
    /// The test addon's download movies, by id: their names as Detail and the Downloads rows show them. The slow one
    /// is throttled (--download-rate) and held (--download-hold: it gets only what `downloadHold` grants), the lazy
    /// one a lazy hosted link whose CDN link expires (--link-expire), held too (--download-hold-lazy), the cut one a
    /// lazy hosted link whose CDN link drops its transfer partway and answers 403 from then on (--link-bytes, 8 MiB of
    /// the file's 14.6 MB).
    static let slowMovie = ("tortuga-dl-slow", "Tortuga Slow Download Test")
    static let lazyMovie = ("tortuga-dl-lazy", "Tortuga Lazy Link Test")
    static let cutMovie = ("tortuga-dl-cut", "Tortuga Expiring Link Test")
    static let redirectMovie = ("tortuga-dl-redirect", "Tortuga Redirect Test")
    /// How long the lazy link's CDN links last: test_addon.py's default --link-expire.
    static let linkLifetime: TimeInterval = 60
    /// A grant to a held movie (`downloadHold`): 2 MiB, 13.7 % of its 15,296,153 bytes, 16 s at 128 KiB/s.
    static let holdStep = 2 << 20
    /// The season series (two seasons of four episodes): its id, name and seasons' episode titles.
    static let seasonSeries = "tortuga-dl-season"
    static let seasonSeriesName = "Tortuga Season Test"
    static let seasonOneTitles = ["Low Tide", "Driftwood", "Undertow: Part 1", "Riptide"]
    static let seasonTwoTitles = ["Landfall", "Shoals", "Nesting", "Hatchlings"]
    /// The series whose two episodes download as slowly as the slow movie (--download-rate).
    static let slowSeries = "tortuga-dl-slow-season"
    /// The test addon's other series (three episodes): its id and name.
    static let otherSeries = "tortuga-series"
    static let otherSeriesName = "Tortuga Series Test"

    /// Launches a `--dev` session with the test addon that keeps its database under `data` (`--dev-data`): the
    /// downloads outlast a relaunch, and the system's transfers find their downloads again. `open`, `settings`,
    /// `progress` (`--dev-progress` specs, recorded at this launch) and `textSize` as `launchDev`'s.
    func launchDownloads(
        _ data: String,
        open: [String] = [],
        settings: [String] = [],
        progress: [String] = [],
        textSize: String? = nil
    ) throws {
        var arguments = ["--dev", "--dev-data", data, "--dev-addon", try addonURL]
        for screen in open { arguments += ["--dev-open", screen] }
        for setting in settings { arguments += ["--dev-setting", setting] }
        for spec in progress { arguments += ["--dev-progress", spec] }
        if let textSize { arguments += ["-UIPreferredContentSizeCategoryName", textSize] }
        launch(arguments)
    }

    /// The Downloads row of the download whose title holds `title` (a movie's name, an episode's "3. Title"): a button
    /// (on the Mac a progress indicator while the download has bytes), whose label reads the title, the status
    /// ("Downloading · 45% · 1.2 MB/s") and the bytes.
    func downloadRow(_ title: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH 'downloads.row.' AND NOT (identifier ENDSWITH '.menu') AND label CONTAINS %@", title
        )).firstMatch
    }

    /// Any Downloads row.
    var anyDownloadRow: XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'downloads.row.' AND NOT (identifier ENDSWITH '.menu')")).firstMatch
    }

    /// The row's text: its title, status and bytes, and its progress as a percentage ("" while it isn't there). A Mac
    /// row that is a progress indicator gives the percentage as its value's fraction.
    func downloadText(_ title: String) -> String {
        let row = downloadRow(title)
        guard row.exists else { return "" }
        if row.elementType == .progressIndicator, let fraction = row.value as? NSNumber {
            return "\(row.label) \(Int((fraction.doubleValue * 100).rounded()))%"
        }
        return fullText(of: row)
    }

    /// The row's percentage while it downloads ("Downloading · 45%"), nil otherwise.
    func downloadPercent(_ title: String) -> Int? {
        let text = downloadText(title)
        guard let range = text.range(of: #"Downloading · (\d+)%"#, options: .regularExpression) else { return nil }
        return Int(text[range].dropFirst("Downloading · ".count).dropLast())
    }

    /// The row's last percentage in any state ("Paused, 4 MB of 14.6 MB 28%"), nil when it shows none.
    func downloadShownPercent(_ title: String) -> Int? {
        Self.lastPercent(in: downloadText(title))
    }

    static func lastPercent(in text: String) -> Int? {
        let parts = text.components(separatedBy: "%")
        guard parts.count > 1 else { return nil }
        return Int(String(parts[parts.count - 2].reversed().prefix { $0.isNumber }.reversed()))
    }

    /// Waits for the download of `title`, resumed from `percent`, to read Downloaded; fails with `what` when its row
    /// meanwhile shows less than that (it started over rather than resuming) or it doesn't complete within `timeout`.
    func requireResumed(_ title: String, from percent: Int, timeout: TimeInterval, _ what: String) {
        var restarted: String?
        let completed = waitUntil(timeout) {
            let text = downloadText(title)
            if text.contains("Downloaded") { return true }
            if restarted == nil, let shown = Self.lastPercent(in: text), shown < percent - 1 { restarted = text }
            return false
        }
        if let restarted { fail("\(what): resumed from \(percent)%, its row went back to \"\(restarted)\": it started over") }
        if !completed { fail("\(what): \(title)'s row reads \"\(downloadText(title))\", not Downloaded, after \(Int(timeout)) s") }
    }

    /// Waits for the row of `title` to read `status` ("Downloaded", "Paused", "Queued"); fails with `what` and the row's
    /// text when it doesn't within `timeout`.
    func requireDownload(_ title: String, _ status: String, timeout: TimeInterval, _ what: String) {
        if !waitUntil(timeout, { downloadText(title).contains(status) }) {
            fail("\(what): \(title)'s row reads \"\(downloadText(title))\", not \(status), after \(Int(timeout)) s")
        }
    }

    /// The ⋯ button of the row of `title` (`downloads.row.<id>.menu`).
    func downloadMenuButton(_ title: String) -> XCUIElement {
        let row = downloadRow(title)
        if !row.exists(within: 5) { fail("Downloads lists no row of \(title)") }
        return element(row.identifier + ".menu")
    }

    /// What the held transfers of `movie` ("slow", "lazy") may send (serve.py's /hold/<name>?bytes=): `bytes` more,
    /// `all` (the hold lifted) or `none` (held again from the next byte, whatever was granted before). The holds'
    /// URLs come as `TEST_RUNNER_TORTUGA_DOWNLOAD_HOLD_SLOW` and `_LAZY`, which reach the test runner without the
    /// `TEST_RUNNER_` prefix.
    func downloadHold(_ movie: String, _ bytes: String) throws {
        let variable = "TORTUGA_DOWNLOAD_HOLD_\(movie.uppercased())"
        guard let hold = ProcessInfo.processInfo.environment[variable], !hold.isEmpty,
              let url = URL(string: "\(hold)?bytes=\(bytes)") else {
            throw HarnessError("\(variable) is not set: the test needs the hold's URL, passed with xcodebuild's TEST_RUNNER_ prefix")
        }
        let answer = String(decoding: try Data(contentsOf: url), as: UTF8.self)
        if answer != "ok\n" { throw HarnessError("\(url) answered \"\(answer)\", not ok") }
    }
}
