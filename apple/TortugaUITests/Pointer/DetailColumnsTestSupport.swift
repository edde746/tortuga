import XCTest

/// What the iPad and Mac Detail tests share about the wide page's columns: the details at the leading side, a series'
/// episodes in a column beside them under its season row (`DetailEpisodesPane`).
extension TortugaUITestCase {
    /// The test addon's series of 24 seasons, its wide logo over the bright frame: a season row far longer than the
    /// episodes' column.
    static let longSeasonRowTitle = "detail:series:tortuga-seasons"

    /// The season row of the page open on `longSeasonRowTitle`, scrolled from its first chip until its last (24th) ends
    /// inside it (at most 8 scrolls: swipes on iPad, the wheel turned sideways on the Mac), draws nothing left of the
    /// episodes' column: over the row's height, from the window's leading edge to 8 pt (the focus ring's room) short of
    /// the episode rows' leading edge, at most 0.5 % of the pixels change between the screen with the row at rest and
    /// with it at its end. Shot `season row at its last chip`; the reading goes to the log. Fails with `what`.
    func requireSeasonRowInItsColumn(_ what: String) {
        require("detail.season.0", timeout: 60, "\(what): the series shows its first season's chip")
        let episode = require("detail.episode.0", timeout: 10, "\(what): the first season lists its episode")
        let row = require("detail.seasons", "\(what): the season row")
        let last = element("detail.season.23")
        if !last.exists(within: 5) { return fail("\(what): the season row has no 24th chip") }
        if last.frame.minX < row.frame.maxX {
            return fail("\(what): the 24th season's chip shows at rest, at \(last.frame): the row isn't longer than the column at \(row.frame)")
        }
        requireArtDrawn(what)
        // The art fades in: the screen read at rest once it has stopped changing.
        screenSettles()
        let window = windowFrame
        let edge = episode.frame.minX - 8
        let band = CGRect(x: window.minX, y: row.frame.minY, width: edge - window.minX, height: row.frame.height)
        guard let atRest = ScreenPixels() else { return fail("\(what): no screenshot") }
        for _ in 0..<8 where last.frame.maxX > row.frame.maxX {
            #if os(macOS)
            row.scroll(byDeltaX: -row.frame.width, deltaY: 0)
            #else
            row.swipeLeft()
            #endif
            _ = settledFrame(of: last)
        }
        if last.frame.maxX > row.frame.maxX {
            return fail("\(what): 8 scrolls leave the 24th season's chip at \(last.frame), past the row's end at x \(Int(row.frame.maxX))")
        }
        shot("season row at its last chip")
        guard let atEnd = ScreenPixels() else { return fail("\(what): no screenshot") }
        let changed = atEnd.changedShare(in: band, from: atRest) * 100
        print("season row \(what): \(String(format: "%.2f", changed)) % of the pixels changed over \(band); the row at \(row.frame), the episodes from x \(episode.frame.minX)")
        if changed > 0.5 {
            fail("\(what): scrolled to its last chip, the season row draws left of the episodes' column (x \(Int(edge))): \(String(format: "%.1f", changed)) % of the pixels over the row from x \(Int(band.minX)) changed")
        }
    }
}
