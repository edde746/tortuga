import XCTest

/// The current episode on Detail (`DetailUiState.currentEpisode`, `shownEpisode`): Breaking Bad, a Cinemeta series,
/// left at 12:34 of S2E7, the seventh of season 2's thirteen episodes (`detail.episode.6`). The episodes' column (iPad,
/// the Mac's wide window) and Apple TV's row of episode cards are brought to it; iPhone's one page opens at its top, on
/// its season.
extension TortugaUITestCase {
    static let currentEpisodeTitle = "detail:series:tt0903747"
    static let currentEpisodeProgress = "series:tt0903747:tt0903747:2:7:754:2820"
    static let currentEpisodeRow = "detail.episode.6"
    /// One Piece left in season 21's 60th episode: a season of more than 50 episodes, so it has range chips
    /// (`detail.range.<i>`, 50 episodes each), the second (51–100) holding the current episode.
    static let rangesTitle = "detail:series:tt0388629"
    static let rangesProgress = "series:tt0388629:tt0388629:21:60:600:1440"

    /// How far S2E7's middle may sit from the middle of what shows of the list. The column takes the anchor that puts
    /// it there from a row height estimated as its thumbnail and insets (`DetailShownEpisode`), off by (anchor - 0.5) ×
    /// the difference: 0 on iPad, whose rows are as estimated (123 pt), and on the Mac, centred (anchor 0.5); TV's row
    /// of cards centres the card. Measured: iPad 0, Mac 0.5; the tolerance leaves 2 pt more for the frames' rounding.
    static let centringTolerance: CGFloat = 3

    /// Waits for the page opened on `currentEpisodeTitle` to select a season and its list to settle (S2E7 still for half
    /// a second), shoots it (`current episode`), prints what it measures (a `current episode, ` line) and says what is
    /// wrong, nil when nothing is: the selected season's chip reads other than Season 2, or S2E7's middle is more than
    /// `centringTolerance` from the middle of what shows of the scroll view holding it (`visibleListArea`), across on
    /// TV (a row of cards), down elsewhere (a column of rows).
    func currentEpisodeShown() -> String? {
        let row = element(Self.currentEpisodeRow)
        _ = waitUntil(30) { selectedSeasonText() == "Season 2" }
        _ = settledFrame(of: row)
        shot("current episode")
        if let wrong = currentSeasonWrong() { return wrong }
        guard row.exists else { return "S2E7 (\(Self.currentEpisodeRow)) doesn't show" }
        guard let visible = visibleListArea(holding: Self.currentEpisodeRow) else {
            return "no scroll view holds S2E7 (\(Self.currentEpisodeRow))"
        }
        let frame = row.frame
        #if os(tvOS)
        let (axis, middle, shows) = ("x", frame.midX, visible.midX)
        #else
        let (axis, middle, shows) = ("y", frame.midY, visible.midY)
        #endif
        let off = middle - shows
        print("current episode, row \(frame) middle \(middle), list shows \(visible) middle \(shows), off by \(off)")
        if abs(off) > Self.centringTolerance {
            return "S2E7's middle is at \(axis) \(middle), \(off) pt from the middle of what shows of the list "
                + "(\(visible), middle \(shows)), more than \(Self.centringTolerance) pt"
        }
        return nil
    }

    /// What is wrong with the season the page shows: nil when the selected season's chip reads S2E7's Season 2.
    func currentSeasonWrong() -> String? {
        let season = selectedSeasonText()
        return season == "Season 2" ? nil : "the selected season's chip reads '\(season)', not the current episode's 'Season 2'"
    }

    /// `element`'s frame once it has held still for half a second (two polls alike), within 15 s; null while it doesn't
    /// exist.
    func settledFrame(of element: XCUIElement) -> CGRect {
        var last = CGRect.null
        _ = waitUntil(15) {
            let frame = element.exists ? element.frame : .null
            defer { last = frame }
            return !frame.isNull && frame == last
        }
        return last
    }

    /// What the selected season's chip reads (`detail.season.<i>`, the one reporting itself selected); empty while there
    /// is none.
    func selectedSeasonText() -> String {
        let chips = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "detail.season."))
        guard let chip = chips.allElementsBoundByIndex.first(where: { $0.isSelected }) else { return "" }
        return text(of: chip)
    }

    /// The season chip named `name` (`detail.season.<i>`).
    func seasonChip(_ name: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@", "detail.season.", name)).firstMatch
    }

    /// The season chips' frames (`detail.season.<i>`) by identifier, once two reads in a row agree (within 5 s).
    func settledSeasonChipFrames() -> [String: CGRect] {
        let chips = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "detail.season."))
        func read() -> [String: CGRect] {
            Dictionary(chips.allElementsBoundByIndex.map { ($0.identifier, $0.frame) }, uniquingKeysWith: { first, _ in first })
        }
        var last = read()
        _ = waitUntil(5) {
            let now = read()
            defer { last = now }
            return now == last
        }
        return last
    }

    /// A pick moves no chip: each season chip of `before` (`settledSeasonChipFrames`, read before the pick) starts where
    /// it did and is as wide, 0.5 pt apart at most, the picked one and those after it too (a selected name is semibold).
    /// Fails with `what` and the chips that moved.
    func requireNoSeasonChipMoved(since before: [String: CGRect], _ what: String) {
        let after = settledSeasonChipFrames()
        let moved = before.compactMap { id, frame -> String? in
            guard let now = after[id], abs(now.minX - frame.minX) > 0.5 || abs(now.width - frame.width) > 0.5 else { return nil }
            return "\(id) from \(frame) to \(now)"
        }
        if !moved.isEmpty { fail("\(what) moves chips: \(moved.sorted().joined(separator: ", "))") }
    }

    /// What is wrong with the episode rows listed (`detail.episode.<i>`, `i` an index of the season's episodes) after up
    /// to 10 s: nil once some show and every one is in `range`.
    func listedEpisodesWrong(_ range: Range<Int>) -> String? {
        let rows = app.descendants(matching: .any).matching(NSPredicate(format: "identifier MATCHES %@", #"detail\.episode\.\d+"#))
        var listed: [Int] = []
        let right = waitUntil(10) {
            listed = rows.allElementsBoundByIndex.compactMap { Int($0.identifier.dropFirst("detail.episode.".count)) }
            return !listed.isEmpty && listed.allSatisfy(range.contains)
        }
        return right ? nil : "the list holds episodes \(listed.sorted()), not only those of \(range)"
    }

    /// What shows of the innermost scroll view holding `id`: its frame inside the window (which reaches under the
    /// bottom safe area: iPad's home indicator, TV's overscan margin), less the bars over it (the navigation bar and the
    /// tab bar on iOS, the toolbar on the Mac, the tab bar on TV), a bar over its upper half bringing its top down to
    /// the bar's bottom, one over its lower half its bottom up to the bar's top. iOS lists the tab view as a tab bar as
    /// tall as the window too: what is taller than half the window is no bar. Nil when no scroll view holds it. Prints
    /// the frames read (a `current episode, ` line).
    func visibleListArea(holding id: String) -> CGRect? {
        let scrollViews = app.scrollViews.containing(.any, identifier: id).allElementsBoundByIndex.map(\.frame)
        guard let frame = scrollViews.min(by: { $0.width * $0.height < $1.width * $1.height }) else { return nil }
        let window = app.windows.firstMatch.frame
        var area = frame.intersection(window)
        let bars = [app.navigationBars, app.tabBars, app.toolbars].flatMap { $0.allElementsBoundByIndex.map(\.frame) }
        print("current episode, scroll view \(frame), window \(window), bars \(bars)")
        for bar in bars where !bar.isEmpty && bar.height < window.height / 2 && bar.intersects(area) {
            if bar.midY < area.midY {
                area = CGRect(x: area.minX, y: bar.maxY, width: area.width, height: area.maxY - bar.maxY)
            } else {
                area = CGRect(x: area.minX, y: area.minY, width: area.width, height: bar.minY - area.minY)
            }
        }
        return area
    }
}
