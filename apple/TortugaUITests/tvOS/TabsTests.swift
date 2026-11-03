import XCTest

/// The top tab bar with the remote: at launch the Board's first card has focus (Home has no button above its shelves:
/// its shortcuts are a row of chips above them, Up from the first shelf landing on the first) and Up moves it to the
/// selected tab (Home), Right and Left move it across the five tabs (Settings last: Right there stays on it; no Watch
/// together tab), each tab's screen showing as it takes focus, Down moves it into the screen and Up back to the bar.
/// (tvOS tab bar buttons don't report which one is selected; the screen under the bar shows it: the tab bar names it,
/// so the screens show no title of their own, and each is told by its content.) Home's shortcuts under Continue
/// Watching: Down from its card lands on the first (Watch together), Right on Library (no Downloads), Down on the next
/// shelf, Up back on the first shortcut, not the one left; Library's opens the Library tab, where Menu moves focus to
/// the tab bar. Home's shortcut chips lead with the symbol of the screen they open, in their label's colour (black with
/// it on a focused chip's white), and a tracker's list with its name. Apple TV has no downloads: Settings lists no
/// Downloads section (Addons follows Subtitles), a movie's Detail offers no Download beside Play and the Library's view
/// panel lists Library and Continue Watching alone. Library's Continue Watching row ends with the Calendar button, in
/// English and in the language whose Continue Watching is the longest.
/// Each tab keeps a back stack of its own: a Detail opened from Home (which hides the tab bar) is there again, focused,
/// when focus moves back to Home's tab after a link has shown Discover, and Menu then goes back to the Board. Launched
/// again on the same data, the app reopens on the tab and the screens it was left on, without the player or a sheet
/// that was over them.
final class TabsTests: TortugaUITestCase {
    func testDpadMovesFocusAcrossTheTabsAndIntoTheScreen() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the Board's first card has focus once its shelves load")
        shot("home")
        moveFocus(to: "nav.board", with: .up, limit: 3, "Up moves focus from the Board's first shelf past its shortcuts to the tab bar")
        shot("home tab focused")
        let tabs = app.tabBars.firstMatch.buttons.count
        if tabs != 5 { fail("the tab bar has \(tabs) tabs, not 5") }
        for id in ["nav.discover", "nav.search", "nav.library", "nav.settings"] {
            press(.right)
            requireFocus(id, "Right moves focus to the next tab")
            requireTabScreen(id)
            if id == "nav.library" { shot("library tab") }
            if id == "nav.settings" {
                require("settings.section.addons", "Settings lists Addons")
                if element("settings.section.downloads").exists { fail("Settings lists Downloads; Apple TV has no downloads") }
                shot("settings tab: no downloads")
            }
        }
        press(.right)
        if !holds(for: 1, { holdsFocus("nav.settings") }) {
            fail("Right from Settings moves focus to \(focusedId ?? "nothing"): a tab after Settings")
        }
        press(.left, count: 3)
        requireFocus("nav.discover", "Left moves focus back across the tabs")
        requireTabScreen("nav.discover")
        press(.down)
        // A menu's focus lands on the view inside its button, which has no identifier: below the bar is what counts.
        if !waitUntil(5, { focused.exists && !(focusedId ?? "").hasPrefix("nav.") && focused.frame.minY > app.tabBars.firstMatch.frame.maxY }) {
            fail("Down moves focus from the tab bar into the screen: focus is on \(focusedId ?? "nothing")")
        }
        shot("discover screen focused")
        press(.up)
        requireFocus("nav.discover", "Up moves focus back to the tab bar")
        shot("discover tab focused again")
    }

    func testHomesShortcutsTakeFocusUnderContinueWatching() throws {
        try launchDev(progress: ["movie:tt0111161:tt0111161:600:6000"])
        requireFocus("board.cw.0", timeout: 90, "Continue Watching's card has focus once Home shows the movie in progress")
        press(.down)
        requireFocus("board.shortcut.watchTogether", "Down from Continue Watching lands on Home's first shortcut, Watch together")
        let labels = ["watchTogether", "library"].map { require("board.shortcut.\($0)", "Home's shortcuts offer \($0)").label }
        if labels != ["Watch Together", "Library"] { fail("Home's first shortcuts read \(labels)") }
        if element("board.shortcut.downloads").exists { fail("Home's shortcuts offer Downloads; Apple TV has no downloads") }
        shot("home: first shortcut focused")
        press(.right)
        requireFocus("board.shortcut.library", "Right moves focus from Watch together to Library")
        shot("home: library shortcut focused")
        focusIntoShelf("board.row.0", with: .down, "Down from the shortcuts lands on the next shelf")
        press(.up)
        requireFocus("board.shortcut.watchTogether", "Up from the next shelf lands on the first shortcut")
        press(.right)
        requireFocus("board.shortcut.library", "Right moves focus to Library")
        press(.select)
        requireTabScreen("nav.library")
        shot("library from home")
        // As from a See all card: Menu on the tab's first screen moves focus to the tab bar, on Library's tab (pressed
        // only from the screen: Menu on the tab bar leaves the app).
        if !focusInTabBar { press(.menu) }
        requireFocus("nav.library", "Library's tab is the selected one")
    }

    /// With every tracker connected, Up from the first shelf onto Watch together, then Right along Home's shortcuts to
    /// the first list of each tracker, each chip read with focus and the one before it at rest: each leads with its icon
    /// in its label's colour, the symbol of the screen it opens or a tracker's list's service's mark
    /// (`shortcutLeadProblem`).
    func testHomesShortcutsLeadWithTheirIcons() throws {
        try launchDev(trackersConnected: true)
        requireFocus("board.row.0.item.0", timeout: 90, "the Board's first card has focus once its shelves load")
        let ids = shortcutChipIdsThroughEachTracker("with every tracker connected")
        guard let first = ids.first else { return }
        moveFocus(to: first, with: .up, limit: 3, "Up from the first shelf lands on Home's first shortcut")
        var problems: [String] = []
        for (index, id) in ids.enumerated() {
            if index > 0 { moveFocus(to: id, with: .right, limit: 1, "Right moves focus along Home's shortcuts to \(id)") }
            screenSettles(XCUIScreen.main)
            let screen = XCUIScreen.main.screenshot()
            if let problem = shortcutLeadProblem(element(id), on: screen) { problems.append("with focus, \(problem)") }
            if index > 0, let problem = shortcutLeadProblem(element(ids[index - 1]), on: screen) { problems.append("at rest, \(problem)") }
            shot("home: \(id.dropFirst("board.shortcut.".count)) focused")
        }
        if !problems.isEmpty { fail(problems.joined(separator: "; ")) }
    }

    func testNoDownloadsOnDetailOrInTheLibrary() throws {
        try launchDev(addon: true, open: ["library", "detail:movie:tt0111161"])
        requireFocus("detail.play", timeout: 60, "Play has focus once the movie's details show")
        require("detail.library", "the movie's action row shows its library button")
        if element("detail.download").exists { fail("the movie's Detail offers Download; Apple TV has no downloads") }
        shot("movie detail: no download")
        press(.menu)
        require("library.view", timeout: 10, "Menu goes back to the Library")
        for _ in 0..<3 where focusInTabBar { press(.down) }
        walkFocus(to: "library.view", "focus reaches the Library's view menu")
        press(.select)
        require("library.view.panel", timeout: 5, "Select on the view menu opens its panel")
        require("library.view.option.1", "the panel lists Continue Watching")
        if element("library.view.option.2").exists {
            fail("the view panel lists a third view, \(fullText(of: element("library.view.option.2"))); Apple TV has no downloads")
        }
        shot("library views: no downloads")
        press(.menu)
        requireGone("library.view.panel", "Menu closes the panel")
    }

    /// Library's Continue Watching row in English and in Czech (its "Pokračovat ve sledování" the longest Continue
    /// Watching of the app's languages), looked at once focus is on the grid (a focused capsule grows): the Calendar
    /// button ends the row's first line, past every filter.
    func testLibrarysRowEndsWithTheCalendar() throws {
        var problems: [String] = []
        for language in ["en", "cs"] {
            let what = "Library in \(language)"
            try launchDev(addon: true, open: ["library"], progress: [Self.seriesEpisodeInProgress], languages: [language])
            require("library.view", timeout: 60, "\(what): Library shows its view menu")
            for _ in 0..<3 where focusInTabBar { press(.down) }
            walkFocus(to: "library.view", "\(what): focus reaches the view menu")
            press(.select)
            require("library.view.panel", timeout: 5, "\(what): Select on the view menu opens its panel")
            moveFocus(to: "library.view.option.1", with: .down, "\(what): Down moves focus to Continue Watching")
            press(.select)
            requireGone("library.view.panel", "\(what): picking Continue Watching closes the panel")
            require("library.item.0", timeout: 30, "\(what): Continue Watching lists the series")
            moveFocus(to: "library.item.0", with: .down, "\(what): Down reaches the series")
            let calendar = settledFrame(of: require("library.calendar", "\(what): Library shows the Calendar button"))
            let view = settledFrame(of: element("library.view"))
            let filtersEnd = ["library.view", "library.type", "library.sort"].map { element($0) }.filter(\.exists).map(\.frame.maxX).max() ?? view.maxX
            if calendar.minX < filtersEnd || calendar.minY >= view.maxY || calendar.maxY <= view.minY {
                problems.append("\(what): the Calendar at \(calendar) doesn't end the view menu's line (\(view)) past the filters, which end at x \(Int(filtersEnd))")
            }
            shot(what)
        }
        if !problems.isEmpty { fail(problems.joined(separator: "; ")) }
    }

    func testHomeShownAgainKeepsItsDetail() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the Board's first card has focus once its shelves load")
        press(.select)
        let title = require("detail.title", timeout: 60, "Select on the Board's first card opens its Detail over Home").label
        requireFocus("detail.play", timeout: 30, "Play has focus once the Detail shows")
        shot("detail over home")
        guard let discover = URL(string: "stremio:///discover") else { return fail("no Discover link") }
        openFromOutside(discover)
        require("discover.catalog", timeout: 30, "the Discover link shows Discover")
        requireGone("detail.title", "Discover shows its own screen, not Home's Detail")
        focusTabBar()
        shot("tab bar focused over discover")
        // Home's tab is next to Discover's; focus on it shows Home, whose Detail takes focus as it shows.
        if !holdsFocus("nav.board") { press(Self.rightToLeft ? .right : .left) }
        if !waitUntil(10, { element("detail.title").exists && element("detail.title").label == title }) {
            fail("focus moved to Home's tab after Discover doesn't show the Detail Home was left on (\(title))")
        }
        requireFocus("detail.play", "the Detail shown again takes focus, Play focused")
        shot("home again: its detail")
        press(.menu)
        requireGone("detail.title", "Menu leaves the Detail")
        requireFocus("board.row.0.item.0", "Menu goes back to the Board, its first card focused")
        shot("menu: the board")
    }

    /// The test addon's series opened over Library, its first episode playing from the Streams sheet as the app ends:
    /// launched again on the same data, as a profile's session starts (`--dev-open restored`), the app shows the series'
    /// Detail, with no player and no sheet, and Menu from it shows Library.
    func testARelaunchReopensLibrarysDetailWithoutThePlayer() throws {
        let data = freshDataName()
        let addon = try addonURL
        launchDev(keepingData: data, addon: addon, open: ["library", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"])
        requireFocus("streams.row.0", timeout: 60, "the first episode's Streams sheet lists its streams, the first focused")
        // The test addon's name for the series.
        let title = "Tortuga Series Test"
        press(.select)
        require("player.video", timeout: 20, "Select on the stream opens the player")
        shot("player over library's detail")
        app.terminate()
        launchDev(keepingData: data, addon: addon, open: ["restored"])
        if !waitUntil(60, { element("detail.title").exists && element("detail.title").label == title }) {
            fail("launched again, the app doesn't show the Detail it was left on under Library (\(title))")
        }
        if element("player.video").exists { fail("launched again, the app opens the player it was left in") }
        if element("streams.row.0").exists { fail("launched again, the app opens the Streams sheet it was left with") }
        requireFocus("detail.play", timeout: 30, "the Detail shown again takes focus, Play focused")
        shot("launched again: library's detail")
        press(.menu)
        require("library.view", timeout: 10, "Menu from the Detail shown again shows Library")
        shot("menu: library")
    }

    /// Waits for the screen of the tab `id`, told by what only it shows: Search's field, Discover's catalog menu,
    /// Library's view menu, Settings' Playback row. None of them repeats the tab's name as a title.
    private func requireTabScreen(_ id: String) {
        if id == "nav.search" {
            if !app.searchFields.firstMatch.exists(within: 5) { fail("the Search tab shows no search field") }
            return
        }
        let screens = [
            "nav.discover": "discover.catalog", "nav.library": "library.view", "nav.settings": "settings.section.playback",
        ]
        guard let content = screens[id] else { return }
        require(content, timeout: 10, "the focused tab's screen shows")
        if app.navigationBars[element(id).label].exists { fail("\(id)'s screen repeats the tab's name as its title") }
    }
}
