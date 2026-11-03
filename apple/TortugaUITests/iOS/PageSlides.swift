import XCTest

/// A title's Detail opened from a card slides in over the screen, and goes back the way it came, on iPhone and iPad: a
/// movie from Home's first row, a series from Discover's grid, a movie from Search's results, a series from the Library
/// (Cinemeta's), and Home's AniList entry (the fake trackers' Kimi no Na wa.), which Detail shows in place as the title
/// it is, Cinemeta's Your Name., once it is found (the anime ids' mapping, downloaded at the first open). Each is opened
/// twice: the first time gone back from by the back button, the second by a swipe from the leading edge
/// (`requirePushAndPop`; the signed-in app's Home card too: `SyncTroubleTests`). The entry opened again, its title found
/// at once, came up in place with no slide (iOS, 30 Oct); every page popped by the back button left blank, its store
/// gone with its route, which read as the screen under pushed in from the side.
extension TabsTests {
    func testAHomeCardSlidesInAndBack() throws {
        try launchDev()
        requireTabScreen("nav.board", "Home shows at launch")
        let card = requireCard("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        requireOpenedTwice("Home's first card") { ready(card).tap() }
    }

    func testADiscoverPosterSlidesInAndBack() throws {
        try launchDev()
        selectTab("nav.discover")
        ready(require("discover.type", timeout: 30, "Discover shows its type filter")).tap()
        pickMenuEntry(require("discover.type.option.1", timeout: 5, "Discover's type filter opens"))
        if !waitUntil(30, { menuText("discover.type").hasSuffix("Series") }) {
            return fail("Discover's type filter reads \(menuText("discover.type")), not Series")
        }
        let poster = require("discover.item.0", timeout: 30, "Discover shows its series")
        requireOpenedTwice("Discover's first series") { ready(poster).tap() }
    }

    func testASearchResultSlidesInAndBack() throws {
        try launchDev()
        selectTab("nav.search")
        let field = require("search.field", "Search shows its field")
        ready(field).tap()
        field.typeText("matrix\n")
        let result = requireCard("search.group.0.item.0", timeout: 30, "typing matrix lists results")
        if !waitUntil(5, { !app.keyboards.firstMatch.exists }) { return fail("the keyboard's Search key leaves the keyboard up") }
        requireOpenedTwice("Search's first result") { ready(result).tap() }
    }

    func testALibraryItemSlidesInAndBack() throws {
        try launchDev(arguments: ["--dev-library", "series:tt0903747"])
        selectTab("nav.library")
        let item = require("library.item.0", timeout: 30, "Library shows the series")
        requireOpenedTwice("the Library's series") { ready(item).tap() }
    }

    func testAnAnimeListEntrySlidesInAndBack() throws {
        try launchDev(trackersConnected: true)
        requireTabScreen("nav.board", "Home shows at launch")
        let entry = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'Kimi no Na'")).firstMatch
        let window = app.windows.firstMatch
        for _ in 0..<8 where !(entry.exists && entry.isHittable) {
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.7))
                .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.4)))
        }
        if !entry.isHittable { return fail("Home shows no AniList entry Kimi no Na wa.") }
        // The first open waits for the mapping's download.
        requireOpenedTwice("the AniList entry", firstTimeout: 90) { ready(entry).tap() }
    }
}

extension TortugaUITestCase {
    /// Opens the page twice with `open`, each time once the screen under it has settled, then goes back from it: by the
    /// back button the first time, by a swipe from the leading edge the second (`requirePushAndPop`). Fails with every
    /// problem seen at the end.
    func requireOpenedTwice(_ what: String, firstTimeout: TimeInterval = 30, open: () -> Void) {
        let problems = openedTwice(what, firstTimeout: firstTimeout, open: open)
        if !problems.isEmpty { fail(problems.joined(separator: "; ")) }
    }

    /// `requireOpenedTwice`'s problems, for a test that has more to do before it fails.
    func openedTwice(_ what: String, firstTimeout: TimeInterval = 30, open: () -> Void) -> [String] {
        requirePushAndPop("\(what) opened", timeout: firstTimeout, swipe: false, open: open)
            + requirePushAndPop("\(what) opened again", timeout: 30, swipe: true, open: open)
    }

    /// Opens a Detail page with `open` (shown once its Play is there, within `timeout`) and goes back from it by the back
    /// button, or with `swipe` by a swipe from the leading edge, reading how the screens moved meanwhile from the Debug
    /// build's log (`page.motion`, `moves`): screenshots taken from here catch a third of a second's slide in two frames
    /// at best, on the iPhone Duo often in none. As iOS moves pages, the push slides the page in from the trailing side
    /// (its leading edge 8 pt or more short of its place as it starts, over 6 frames or more) while the screen under
    /// moves aside; the pop slides it the other way, drawn as it was (out by half the width or more, over 6 frames or
    /// more), while the screen under comes back from the leading side, and neither ever moves the other way (the page
    /// to the leading side, the screen under in from the trailing one: a pop played as a push). The problems seen.
    private func requirePushAndPop(_ what: String, timeout: TimeInterval, swipe: Bool, open: () -> Void) -> [String] {
        let width = app.windows.firstMatch.frame.width
        screenSettles(appScreen)
        var problems: [String] = []
        var seen = moves().last?.number ?? 0

        open()
        let play = element("detail.play")
        guard play.exists(within: timeout) else { return ["\(what): no detail.play after \(Int(timeout)) s"] }
        screenSettles(appScreen)
        let pushed = moves().filter { $0.number > seen }
        seen = pushed.last?.number ?? seen
        if !pushed.contains(where: { $0.page && $0.from - $0.to >= 8 && $0.frames >= 6 }) {
            problems.append("\(what): the page didn't slide in from the trailing side (\(Self.described(pushed))): it came up in place")
        }

        if swipe {
            let edge = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0.5)).withOffset(CGVector(dx: 2, dy: 0))
            edge.press(forDuration: 0.05, thenDragTo: app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)))
        } else {
            ready(backButton).tap()
        }
        let way = swipe ? "swiped back" : "back by its button"
        guard waitUntil(10, { !play.exists }) else { return problems + ["\(what), \(way): Detail stays up"] }
        screenSettles(appScreen)
        let popped = moves().filter { $0.number > seen }
        if popped.contains(where: { $0.page && $0.to < $0.from - 8 || !$0.page && $0.from > $0.to + 8 }) {
            problems.append("\(what), \(way): the page moved to the leading side or the screen under came in from the trailing side (\(Self.described(popped))): the pop played as a push")
        }
        if !popped.contains(where: { $0.page && $0.to - $0.from >= width / 2 && $0.frames >= 6 }) {
            problems.append("\(what), \(way): the page, drawn as it was, didn't slide out to the trailing side (\(Self.described(popped))): it went in place or blank")
        }
        if !popped.contains(where: { !$0.page && $0.to - $0.from >= 8 }) {
            problems.append("\(what), \(way): the screen under didn't come back from the leading side (\(Self.described(popped)))")
        }
        return problems
    }

    /// A sideways movement of a route's screen, as the Debug build's `page.motion` logs it (`<n> <root|page> <from>><to>
    /// <frames>[ gone]`): a pushed page's or a stack's root's, its leading edge from and to (window points), over how many
    /// display frames.
    private struct Move {
        let number: Int
        let page: Bool
        let from: CGFloat
        let to: CGFloat
        let frames: Int
        let line: String
    }

    /// Every movement logged so far (`page.motion`), in order.
    private func moves() -> [Move] {
        let probe = require("page.motion", timeout: 5, "the Debug build logs how its screens move")
        return probe.label.split(separator: "\n").compactMap { line in
            let parts = line.split(separator: " ")
            let ends = parts.count >= 4 ? parts[2].split(separator: ">") : []
            guard ends.count == 2, let number = Int(parts[0]), let from = Double(ends[0]), let to = Double(ends[1]),
                  let frames = Int(parts[3]) else { return nil }
            return Move(number: number, page: parts[1] == "page", from: from, to: to, frames: frames, line: String(line))
        }
    }

    private static func described(_ moves: [Move]) -> String {
        moves.isEmpty ? "nothing moved" : moves.map(\.line).joined(separator: ", ")
    }
}
