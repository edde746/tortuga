import XCTest

/// What the iPad and Mac tests share about the sidebar: opening iPad's, and the sidebar over a title's backdrop, which
/// runs under it to the window's leading edge, dimmed there (`DetailBackdrop`).
extension TortugaUITestCase {
    /// The details start beside the sidebar, and each of the sidebar's destinations stands out from what is behind it
    /// by at least 4.5:1 (WCAG's contrast for text), read over its label and 8 pt around it: the brightest 2 % there is
    /// the label, the median what is behind it. (iPad's sidebar row is 248 pt wide, and Search's label and icon are
    /// under 2 % of it; the Mac's destination is its icon and label.) Fails with `what` and the readings, which go to
    /// the log with the rects read.
    func requireSidebarOverTheBackdrop(_ what: String) {
        let sidebarEnd = require("nav.board", "the sidebar lists Home").frame.maxX
        for id in ["detail.title", "detail.play"] where require(id, timeout: 5, "\(what): the details show \(id)").frame.minX < sidebarEnd {
            fail("\(what): \(id) starts at x \(Int(element(id).frame.minX)), under the sidebar (to x \(Int(sidebarEnd)))")
        }
        guard let screen = ScreenPixels() else { return fail("\(what): no screenshot") }
        let readings = ["nav.board", "nav.discover", "nav.search", "nav.library", "nav.settings"].map { id in
            #if os(iOS)
            let label = require(id, timeout: 5, "\(what): the sidebar lists \(id)").staticTexts.firstMatch.frame
            #else
            let label = require(id, timeout: 5, "\(what): the sidebar lists \(id)").frame
            #endif
            let around = label.insetBy(dx: -8, dy: -8)
            return (id: id, around: around, contrast: screen.contrast(in: around))
        }
        print("sidebar \(what): \(readings.map { "\($0.id) \(String(format: "%.2f", $0.contrast)):1 over \($0.around)" }.joined(separator: ", "))")
        for reading in readings where reading.contrast < 4.5 {
            fail("\(what): \(reading.id) stands out from the backdrop by \(String(format: "%.2f", reading.contrast)):1, under 4.5:1")
        }
    }

    #if os(iOS)
    /// Opens iPad's sidebar where the floating tab bar shows instead, by the bar's toggle (`ToggleSideBar`). The open
    /// sidebar lists the destinations as rows (cells), the bar as buttons. The open sidebar's own toggle (`ToggleSidebar`,
    /// Hide Sidebar) differs by case only: matched by label or case-insensitively, a tap could hit it and leave the
    /// sidebar closed or close it. Fails with `what` while the sidebar doesn't show.
    func openSidebar(_ what: String) {
        let row = app.cells.matching(identifier: "nav.board").firstMatch
        let toggle = app.buttons.matching(NSPredicate(format: "identifier == 'ToggleSideBar'")).firstMatch
        if !waitUntil(10, { row.exists || toggle.exists }) {
            return fail("\(what): neither the sidebar nor the floating tab bar shows")
        }
        if row.exists { return }
        ready(toggle).tap()
        if !waitUntil(10, { row.exists }) { fail("\(what): the floating tab bar's toggle doesn't open the sidebar") }
    }
    #endif
}
