import XCTest

/// The destinations and the chrome around them: on iPhone and iPad the tabs (`TabsTests+iOS`), on the Mac the window's
/// sidebar and toolbar (`TabsTests+macOS`). On both a screen's filters are its header row, which stays at the top
/// while the screen's content scrolls under it (Discover's and Library's filters). On the Mac that row sits on the
/// toolbar's band, level with the window's buttons, the page scrolled too (shot with the window active and inactive),
/// and a click on its control over the scrolled page opens it. Library's row shows its filters whole, the Calendar
/// button at its end, in English and in the language whose Continue Watching is the longest. Each destination keeps a
/// back stack of its own: a Detail opened from Home is there again when Home is picked after Discover, and Home picked
/// while it shows goes back to the Board. Launched again on the same data, the app reopens on the tab and the screens
/// it was left on, without the player or a sheet that was over them. On the Mac and iPad ⌘1–⌘5 pick the destinations
/// as the sidebar or a tab does. Home's shortcut chips lead with the symbol of the screen they open, in their label's
/// colour, and a tracker's list with its name.
final class TabsTests: TortugaUITestCase {
    func testHeadersStayAtTheTopWhileTheScreensScroll() throws {
        // Continue Watching with enough titles for Library's grid to scroll.
        launch(["--dev"] + Self.scrollingProgress.flatMap { ["--dev-progress", $0] })
        // The Board first: with 48 titles to record the dev session comes up later than the tab check waits.
        requireCard("board.row.1.item.0", timeout: 90, "the Board shows its second shelf")
        #if os(iOS)
        requireTabScreen("nav.board", "Home shows at launch")
        shot("home")
        #endif
        for (destination, header, content, name) in Self.scrollingScreens {
            #if os(iOS)
            selectTab(destination)
            #else
            ready(destination).click()
            #endif
            if destination == "nav.library" {
                activate(require("library.view", timeout: 30, "Library shows its view menu"))
                pickMenuEntry(require("library.view.option.1", timeout: 5, "a tap or click on Library's view menu opens it"))
            }
            require(content, timeout: 30, "\(name) shows \(content)")
            #if os(macOS)
            requireOnToolbarBand(header, "\(name)'s header row")
            #endif
            shot(name)
            requireHeaderStays(header, scrolling: content, "\(name)'s header row")
            #if os(macOS)
            requireOnToolbarBand(header, "\(name)'s header row, the page scrolled")
            #endif
            shot("\(name) scrolled")
            #if os(macOS)
            inactiveScreen("\(name) scrolled")
            // A click on the row's control over the scrolled page reaches it.
            ready(header).click()
            require("\(header).option.0", timeout: 30, "a click on \(name)'s \(header) over the scrolled page opens it")
            app.typeKey(.escape, modifierFlags: [])
            #endif
        }
    }

    /// Library's Continue Watching row, in English and in Czech (its "Pokračovat ve sledování" the longest Continue
    /// Watching of the app's languages), on iOS in Czech at the largest text size short of the accessibility ones too:
    /// each filter shows its label whole and the Calendar button ends the row. On iPhone each filter is as wide upright
    /// as with the phone turned, where the row has room for all of them on one line (upright, those that don't fit beside
    /// the Calendar go on a line under it), and the Calendar, as tall as the filters, ends their first line, as on iPad
    /// beside the sidebar, where the row is the top of the screen, above the sidebar's first destination (no empty bar
    /// above it); on iPad under the floating tab bar it is in the navigation bar above the row; on the Mac it ends the
    /// row on the toolbar's band, past the filters, and the toolbar, which holds navigation alone, doesn't hold it.
    func testLibrarysFiltersShowWholeWithTheCalendarAtTheirEnd() throws {
        #if os(iOS)
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        let runs: [(language: String, textSize: String?)] = [("en", nil), ("cs", nil), ("cs", "UICTContentSizeCategoryXXXL")]
        #else
        let runs: [(language: String, textSize: String?)] = [("en", nil), ("cs", nil)]
        #endif
        var problems: [String] = []
        for (language, textSize) in runs {
            let what = "Library in \(language)" + (textSize.map { " at \($0)" } ?? "")
            #if os(iOS)
            // Upright again after the run before, which ended turned; the last one stays turned, the app running for a
            // failure's shot.
            if XCUIDevice.shared.orientation.isLandscape { Self.quitAndTurn(to: .portrait) }
            #endif
            let upright = try continueWatchingFilters(language: language, textSize: textSize, what)
            problems += calendarPlacementProblems(what)
            shot(what)
            #if os(iOS)
            Self.quitAndTurn(to: .landscapeLeft)
            let turned = try continueWatchingFilters(language: language, textSize: textSize, "\(what), turned")
            if isPad { problems += calendarPlacementProblems("\(what), turned") }
            shot("\(what), turned")
            for (id, width) in upright.sorted(by: { $0.key < $1.key }) {
                guard let whole = turned[id] else { continue }
                print("library row, \(what): \(id) \(width) pt wide upright, \(whole) pt turned")
                if width < whole - 1 {
                    problems.append("\(what): \(id) is \(Int(width)) pt wide upright, cut short of the \(Int(whole)) pt it takes turned")
                }
            }
            #endif
        }
        if !problems.isEmpty { fail(problems.joined(separator: "; ")) }
    }

    /// Launches Tortuga in `language` (at `textSize` on iOS) with a series in Continue Watching, shows Library's Continue
    /// Watching and gives the widths of its filters by identifier. Fails with `what` where the row doesn't show.
    private func continueWatchingFilters(language: String, textSize: String?, _ what: String) throws -> [String: CGFloat] {
        try launchDev(addon: true, progress: [Self.seriesEpisodeInProgress], textSize: textSize, languages: [language])
        activate(navItem("nav.library", "\(what): the tabs list Library"))
        activate(require("library.view", timeout: 30, "\(what): Library shows its view menu"))
        pickMenuEntry(require("library.view.option.1", timeout: 5, "\(what): Library's view menu opens"))
        require("library.item.0", timeout: 30, "\(what): Continue Watching lists the series")
        require("library.calendar", timeout: 10, "\(what): Library shows the Calendar button")
        screenSettles()
        var widths: [String: CGFloat] = [:]
        for id in Self.libraryFilters where element(id).exists {
            widths[id] = element(id).frame.width
        }
        return widths
    }

    /// Library's filters, in their order along the row.
    private static let libraryFilters = ["library.view", "library.type", "library.sort"]

    /// What is wrong with where Library's Calendar button sits (`what` names the run): on iPhone and on iPad beside the
    /// sidebar it ends the row's first line, level with the view menu and as tall, past every filter, and beside the
    /// sidebar the row ends above the sidebar's first destination; on iPad under the floating tab bar it is in the
    /// navigation bar above the row, at the screen's trailing side; on the Mac it is out of the toolbar and ends the row
    /// on the toolbar's band, past every filter.
    private func calendarPlacementProblems(_ what: String) -> [String] {
        let calendar = element("library.calendar").frame
        let view = element("library.view").frame
        let filtersEnd = Self.libraryFilters.map { element($0) }.filter(\.exists).map(\.frame.maxX).max() ?? view.maxX
        var problems: [String] = []
        #if os(iOS)
        let home = element("nav.board")
        let besideSidebar = isPad && home.exists && home.isHittable && home.frame.maxX <= view.minX
        if isPad && !besideSidebar {
            if calendar.maxY > view.minY || calendar.midX < app.frame.midX {
                problems.append("\(what): the Calendar at \(calendar) isn't in the bar above the row (the view menu at \(view)), at its trailing side")
            }
            return problems
        }
        if besideSidebar && view.maxY > home.frame.minY {
            problems.append("\(what): the row (the view menu at \(view)) reaches down past the sidebar's Home at \(home.frame): an empty bar above it")
        }
        if abs(calendar.midY - view.midY) > 2 || abs(calendar.height - view.height) > 1 {
            problems.append("\(what): the Calendar at \(calendar) isn't level with the view menu at \(view) and as tall")
        }
        #else
        let inToolbar = app.toolbars.descendants(matching: .any).matching(identifier: "library.calendar")
        if inToolbar.count > 0 { problems.append("\(what): the toolbar holds the Calendar") }
        let buttons = app.windows.firstMatch.buttons[XCUIIdentifierCloseWindow].frame
        if abs(calendar.midY - buttons.midY) > 4 {
            problems.append("\(what): the Calendar (y \(Int(calendar.midY))) isn't on the toolbar's band, the line of the window's buttons (y \(Int(buttons.midY)))")
        }
        #endif
        if calendar.minX < filtersEnd {
            problems.append("\(what): the Calendar at \(calendar) doesn't come past the filters, which end at x \(Int(filtersEnd))")
        }
        return problems
    }

    /// With every tracker connected, each of Home's shortcut chips from Watch together to the first list of each
    /// tracker, read all on the screen along the row (`scrollAlong`): each leads with its icon in its label's colour, the
    /// symbol of the screen it opens or a tracker's list's service's mark (`shortcutLeadProblem`).
    func testHomesShortcutsLeadWithTheirIcons() throws {
        try launchDev(trackersConnected: true)
        let row = require("board.shortcuts", timeout: 30, "Home shows its shortcuts")
        let ids = shortcutChipIdsThroughEachTracker("with every tracker connected")
        #if os(iOS)
        let screen = appScreen
        #else
        let screen = XCUIScreen.main
        #endif
        screenSettles(screen)
        shot("home shortcuts")
        var problems: [String] = []
        for id in ids {
            let chip = element(id)
            scrollAlong(row, toShow: chip)
            screenSettles(screen)
            if let problem = shortcutLeadProblem(chip, on: screen.screenshot()) { problems.append(problem) }
            if id.hasPrefix(Self.shortcutListPrefix) { shot("home shortcuts: \(id.dropFirst("board.shortcut.".count))") }
        }
        if !problems.isEmpty { fail(problems.joined(separator: "; ")) }
    }

    func testEachDestinationKeepsItsBackStack() throws {
        try launchDev()
        activate(requireCard("board.row.0.item.0", timeout: 90, "the Board shows its first shelf"))
        let title = require("detail.title", timeout: 60, "a card on the Board opens its Detail over Home").label
        shot("detail over home")
        activate(navItem("nav.discover", "the sidebar lists Discover"))
        require("discover.catalog", timeout: 30, "Discover shows its catalog menu")
        requireGone("detail.title", "Discover shows its own screen, not Home's Detail")
        shot("discover")
        activate(navItem("nav.board", "the sidebar lists Home"))
        if !waitUntil(10, { element("detail.title").exists && element("detail.title").label == title }) {
            fail("Home picked after Discover doesn't show the Detail it was left on (\(title))")
        }
        shot("home again: its detail")
        activate(navItem("nav.board", "the sidebar lists Home"))
        requireGone("detail.title", "Home picked while it shows its Detail goes back to the Board")
        requireCard("board.row.0.item.0", "Home picked while it shows its Detail shows the Board")
        shot("home picked while it shows: the board")
    }

    /// The test addon's series opened over Library, its first episode playing from the Streams sheet as the app ends:
    /// launched again on the same data, as a profile's session starts (`--dev-open restored`), the app shows the series'
    /// Detail, with no player and no sheet, and Back from it shows Library.
    func testARelaunchReopensLibrarysDetailWithoutThePlayer() throws {
        let data = freshDataName()
        let addon = try addonURL
        launchDev(keepingData: data, addon: addon, open: ["library", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"])
        // The test addon's name for the series.
        let title = "Tortuga Series Test"
        activate(require("streams.row.0", timeout: 60, "the first episode's Streams sheet lists its streams"))
        require("player.video", timeout: 20, "\(Self.tapping) on the stream opens the player")
        shot("player over library's detail")
        app.terminate()
        launchDev(keepingData: data, addon: addon, open: ["restored"])
        if !waitUntil(60, { element("detail.title").exists && element("detail.title").label == title }) {
            fail("launched again, the app doesn't show the Detail it was left on under Library (\(title))")
        }
        if element("player.video").exists { fail("launched again, the app opens the player it was left in") }
        if element("streams.row.0").exists { fail("launched again, the app opens the Streams sheet it was left with") }
        shot("launched again: library's detail")
        goBack()
        require("library.view", timeout: 30, "Back from the Detail shown again shows Library")
        shot("back: library")
    }

    /// ⌘1–⌘5 (the View menu; on iPad the list shown while ⌘ is held) pick the five destinations as a tab or the sidebar
    /// does: from Library ⌘1 shows Home and ⌘2 Discover. iPhone has no such list. Named to run last: once a key has
    /// been typed the simulator counts a hardware keyboard as attached and brings no software keyboard up any more, and
    /// `testTabsGoAcrossTheFiveScreens` looks for Search's.
    func testTypedCommandDigitsPickTheDestinations() throws {
        #if os(iOS)
        guard isPad else { throw XCTSkip("the destinations' ⌘ shortcuts are the Mac's and iPad's") }
        #endif
        try launchDev()
        #if os(iOS)
        requireTabScreen("nav.board", "Home shows at launch")
        selectTab("nav.library")
        #else
        ready(require("nav.library", timeout: 90, "the sidebar lists Library")).click()
        #endif
        require("library.view", timeout: 30, "Library shows its view menu")
        app.typeKey("1", modifierFlags: .command)
        #if os(iOS)
        if !waitUntil(5, { phoneTab("nav.board").isSelected }) { fail("⌘1 doesn't select Home") }
        requireTabScreen("nav.board", "⌘1 shows Home")
        #else
        require("board.row.0.item.0", timeout: 30, "⌘1 shows Home")
        requireGone("library.view", "⌘1 leaves Library")
        #endif
        app.typeKey("2", modifierFlags: .command)
        #if os(iOS)
        if !waitUntil(5, { phoneTab("nav.discover").isSelected }) { fail("⌘2 doesn't select Discover") }
        #endif
        require("discover.catalog", timeout: 30, "⌘2 shows Discover")
    }

    /// The destinations whose content scrolls under their header row: the header's control, the content scrolled over
    /// and the screen's name.
    private static let scrollingScreens = [
        ("nav.discover", "discover.catalog", "discover.item.0", "discover"), ("nav.library", "library.view", "library.item.0", "library"),
    ]

    #if os(macOS)
    /// The header row's control `id` sits on the toolbar's band: its centre on the line of the window's buttons, so
    /// nothing is left empty above it.
    func requireOnToolbarBand(_ id: String, _ what: String) {
        let control = require(id, timeout: 30, what).frame
        let buttons = app.windows.firstMatch.buttons[XCUIIdentifierCloseWindow].frame
        if abs(control.midY - buttons.midY) > 4 {
            fail("\(what): \(id) (y \(Int(control.midY))) isn't on the toolbar's band, the line of the window's buttons (y \(Int(buttons.midY)))")
        }
    }

    /// The screen with another app in front, kept as the shot `what` (the window inactive); the app is in front again,
    /// its window drawn active, once it returns.
    @discardableResult
    func inactiveScreen(_ what: String) -> XCUIScreenshot {
        let finder = XCUIApplication(bundleIdentifier: "com.apple.finder")
        withAnotherAppInFront {
            for _ in 0..<3 where app.state == .runningForeground {
                finder.activate()
                _ = waitUntil(5) { app.state != .runningForeground }
            }
        }
        if app.state == .runningForeground { fail("\(what): another app didn't come to the front") }
        // The window drawn inactive.
        screenSettles(XCUIScreen.main)
        let frame = XCUIScreen.main.screenshot()
        shot("\(what), window inactive", of: XCUIScreen.main)
        app.activate()
        _ = app.windows.firstMatch.exists(within: 5)
        screenSettles()
        return frame
    }
    #endif
}
