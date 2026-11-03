import XCTest

/// What the iOS and macOS tests of the screens' header rows share (`screenHeader`): a screen's filters and actions stay
/// at the top of the screen while its content scrolls under them.
extension TortugaUITestCase {
    /// Watch progress for 48 Cinemeta movies (`--dev-progress`): Continue Watching's grid, taller than an iPad's screen.
    static let scrollingProgress = [
        "tt0111161", "tt0068646", "tt0468569", "tt0071562", "tt0050083", "tt0108052", "tt0167260", "tt0110912",
        "tt0120737", "tt0060196", "tt0109830", "tt0137523", "tt0167261", "tt1375666", "tt0080684", "tt0133093",
        "tt0099685", "tt0073486", "tt0114369", "tt0038650", "tt0047478", "tt0102926", "tt0120815", "tt0317248",
        "tt0816692", "tt0245429", "tt0120689", "tt0076759", "tt0103064", "tt0088763", "tt0253474", "tt0172495",
        "tt0110413", "tt0114814", "tt0407887", "tt0482571", "tt0095765", "tt0034583", "tt0054215", "tt0027977",
        "tt0021749", "tt1675434", "tt0064116", "tt0047396", "tt0078788", "tt0082971", "tt0209144", "tt0078748",
    ].map { "movie:\($0):\($0):600:6000" }

    /// Scrolls the page down by about a third of the window (on iOS a slow drag from below the middle of the screen,
    /// which doesn't fling) and requires `content` (an element of the page under the header) to move (up, or out of
    /// the window: the Mac's lists then report it anywhere) while the header's control `id` stays where it was, on
    /// screen and hittable. Fails with `what`.
    func requireHeaderStays(_ id: String, scrolling content: String, _ what: String) {
        let control = require(id, timeout: 30, "\(what): the screen shows \(id)")
        let page = require(content, timeout: 30, "\(what): the page shows \(content)")
        let before = control.frame
        let pageBefore = page.frame.minY
        #if os(macOS)
        page.scroll(byDeltaX: 0, deltaY: -300)
        #else
        let window = app.windows.firstMatch
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
            .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35)))
        #endif
        if !waitUntil(5, { !page.exists || abs(page.frame.minY - pageBefore) > 40 }) {
            fail("\(what): the page doesn't scroll over \(content) (it stays at y \(Int(page.frame.minY)))")
        }
        let after = control.frame
        if !control.exists || !control.isHittable || abs(after.minY - before.minY) > 2 || abs(after.minX - before.minX) > 2 {
            fail("\(what): \(id) moves from \(before) to \(after) while the page scrolls under it, not staying at the top")
        }
    }
}
