import XCTest

/// The tabs on iPhone and iPad: five (Home, Discover, Search, Library, Settings) with no More, Home first, each showing
/// its screen, a tap on Search with the keyboard up for its field. On iPhone the tab bar is at the bottom; on iPad the
/// floating tab bar is at the top, and the sidebar it opens into (in landscape) ends with the running profile, a button
/// at the destinations' leading edge and clear of the screen's bottom edge: opened by the bar's toggle, and when the app
/// launches with the sidebar shown, then once it is hidden and shown again. The floating tab bar holds the five whole in
/// every language at the default text size, none behind its scroll arrow.
/// The tabs name the destination: its screen shows no title of its own (on iPhone no navigation bar at all) and is told
/// by its content; but Settings, a settings page as iOS has them, its navigation bar titled. (Addons is in Settings:
/// SettingsTests.) Home has no header on either: its shortcuts (Watch together, Downloads, Library) are one line of
/// chips above the first shelf, on iPhone right under the status bar (no profile row above them); Library's chip opens
/// the Library tab. On iPhone a page pushed over a destination (Home's Watch together, Detail from Home, Discover,
/// Search and Library) leaves the destination where it was, its name nowhere over it, as it goes back by its back
/// button or a swipe from the edge: nothing moves while its bar goes. Held halfway, a swipe back shows Library's and
/// Discover's header rows, and what lies right under them, as bright and as sharp as at rest. A page opened from a card
/// slides in and goes back the way it came (`PageSlides`). Search's results stay put while a group loads its next
/// page and once it has, the spinner centred on the group's heading, and Back from a result reloads nothing. On iPhone,
/// content scrolled up under the top (Settings' navigation bar, Discover's filters row, Search's field) is faded or
/// blurred there: not cut off at an opaque row, not as sharp as below it; at rest the top shows it as it is (Settings'
/// profile, Discover's first poster). From iOS 26 Search's field is the filters' glass: a result scrolled up under its
/// capsule shows through it, faded. A tab shown again is as it was left: Discover on the catalog picked, scrolled where
/// it was, and on iPhone Home scrolled where it was.
extension TabsTests {
    func testTabsGoAcrossTheFiveScreens() throws {
        try launchDev()
        let home = phoneTab("nav.board")
        if !home.isSelected { fail("Home is not the selected tab at launch") }
        requireTabScreen("nav.board", "Home shows at launch")
        let tabs = tabIds()
        if tabs.count != 5 { fail("the tab bar has \(tabs.count) buttons, not 5") }
        shot("home")
        for id in Self.phoneTabs.dropFirst() {
            selectTab(id)
            shot(id)
        }
        guard isPad else { return }
        // iPad in landscape: the sidebar, the running profile at its bottom, under the destinations; launched in it
        // (`quitAndTurn`), opened by the floating tab bar's toggle where the launch left it hidden.
        Self.quitAndTurn(to: .landscapeLeft)
        addTeardownBlock { TortugaUITestCase.quitAndTurn(to: .portrait) }
        try launchDev()
        requireTabScreen("nav.board", "Home shows at launch in landscape")
        openSidebar("in landscape")
        requireSidebarProfile("in landscape")
        for id in Self.phoneTabs {
            selectTab(id)
            shot("sidebar \(id)")
        }
    }

    func testSidebarShownAtLaunchOffersTheProfile() throws {
        try XCTSkipUnless(isPad, "iPhone has no sidebar")
        // iPad keeps the sidebar shown or hidden across launches: a launch that finds it hidden opens it and launches
        // again.
        Self.quitAndTurn(to: .landscapeLeft)
        addTeardownBlock { TortugaUITestCase.quitAndTurn(to: .portrait) }
        try launchDev()
        requireTabScreen("nav.board", "Home shows at launch in landscape")
        let row = app.cells.matching(identifier: "nav.board").firstMatch
        if !row.waitForExistence(timeout: 5) {
            openSidebar("in landscape, before the launch with it shown")
            try launchDev()
            requireTabScreen("nav.board", "Home shows at the launch with the sidebar shown")
            if !row.waitForExistence(timeout: 10) { return fail("launched again once opened, the sidebar isn't shown") }
        }
        requireSidebarProfile("launched with the sidebar shown")
        shot("sidebar shown at launch")
        ready(app.buttons.matching(NSPredicate(format: "identifier == 'ToggleSidebar'")).firstMatch).tap()
        if !waitUntil(10, { !row.exists }) { return fail("the sidebar's toggle doesn't hide it") }
        openSidebar("hidden after a launch with it shown")
        requireSidebarProfile("launched with the sidebar shown, then hidden and shown again")
    }

    /// The open sidebar ends with the running profile: a button named after it, under the destinations, starting at
    /// their icons (not centred in the sidebar), above the home indicator by the sidebar's margin.
    private func requireSidebarProfile(_ what: String) {
        let profile = require("nav.profile", timeout: 10, "\(what): the sidebar offers the running profile")
        if profile.elementType != .button || profile.label.isEmpty {
            fail("\(what): the sidebar's profile is a \(profile.elementType.rawValue) labelled '\(profile.label)', not a named button")
        }
        let lastTab = element("nav.settings")
        if lastTab.exists, profile.frame.minY < lastTab.frame.maxY {
            fail("\(what): the sidebar's profile isn't at its bottom: it is at y \(Int(profile.frame.minY)), above Settings' \(Int(lastTab.frame.maxY))")
        }
        if lastTab.exists, profile.frame.minX - lastTab.frame.minX > 40 {
            fail("\(what): the sidebar's profile starts at x \(Int(profile.frame.minX)), not at the destinations' \(Int(lastTab.frame.minX))")
        }
        let screenBottom = app.windows.firstMatch.frame.maxY
        if screenBottom - profile.frame.maxY < 32 {
            fail("\(what): the sidebar's profile ends \(Int(screenBottom - profile.frame.maxY)) pt above the screen's bottom edge")
        }
    }

    func testHomeHasNoHeaderAndItsShortcutsOpenTheirScreens() throws {
        try launchDev()
        requireTabScreen("nav.board", "Home shows at launch")
        let row = require("board.shortcuts", timeout: 30, "Home shows its shortcuts")
        let chips = ["watchTogether", "downloads", "library"].map { require("board.shortcut.\($0)", "Home's shortcuts offer \($0)") }
        let frames = chips.map(\.frame)
        if frames != frames.sorted(by: { $0.minX < $1.minX }) || frames.contains(where: { abs($0.midY - frames[0].midY) > 1 }) {
            fail("Home's shortcuts aren't one line of Watch together, Downloads and Library in that order: \(frames)")
        }
        if chips.map(\.label) != ["Watch Together", "Downloads", "Library"] {
            fail("Home's shortcuts read \(chips.map(\.label))")
        }
        // Without Continue Watching (a dev session has none) the shortcuts are Home's first content: on iPhone as high
        // as Discover's filters, a screen's first row under the top safe area (62 pt on an iPhone 17, 82 pt on the iPhone
        // Duo's inner screen turned), where the profile's row put them 44 pt lower.
        if !isPad {
            selectTab("nav.discover")
            let top = require("discover.catalog", "Discover shows its catalog menu").frame.minY
            selectTab("nav.board")
            if row.frame.minY > top + 20 {
                fail("Home's shortcuts start at y \(Int(row.frame.minY)), under Discover's filters at y \(Int(top)): not at the top of the screen")
            }
        }
        let shelf = require("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        if shelf.frame.minY < frames[0].maxY { fail("the first shelf (y \(Int(shelf.frame.minY))) isn't under the shortcuts") }
        shot("home top")
        ready(chips[2]).tap()
        if !waitUntil(10, { phoneTab("nav.library").isSelected }) { fail("Home's Library doesn't select the Library tab") }
        requireTabScreen("nav.library", "Home's Library opens Library")
        shot("library from home")
    }

    func testHomeStaysPutUnderItsPages() throws {
        try XCTSkipIf(isPad, Self.padShowsThePagesBar)
        try launchDev()
        // Downloads, the second chip, shows whole on every iPhone: the third, Library, runs past a narrow screen's edge
        // and under the iPhone Duo's vertical bar on its cover.
        let downloads = require("board.shortcut.downloads", timeout: 30, "Home shows its Downloads shortcut")
        requireCard("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        // Downloads and Library open the Library tab; Watch together and a card push a page.
        requireStaysPut(downloads, label: "Downloads", "home under watch together", untitled: "Home") {
            ready(require("board.shortcut.watchTogether", "Home's shortcuts offer Watch together")).tap()
        }
        requireStaysPut(downloads, label: "Downloads", "home under detail", untitled: "Home") {
            ready(requireCard("board.row.0.item.0", "the Board shows its first shelf")).tap()
        }
    }

    func testDiscoverStaysPutUnderDetail() throws {
        try XCTSkipIf(isPad, Self.padShowsThePagesBar)
        try launchDev()
        selectTab("nav.discover")
        let poster = require("discover.item.0", timeout: 30, "Discover shows its grid")
        let catalog = require("discover.catalog", "Discover shows its catalog menu")
        // The capsule shows the catalog alone; its label names the menu too.
        let selection = menuText("discover.catalog").components(separatedBy: ": ").last ?? ""
        requireStaysPut(catalog, label: selection, "discover under detail", untitled: "Discover") { ready(poster).tap() }
    }

    func testSearchStaysPutUnderDetail() throws {
        try XCTSkipIf(isPad, Self.padShowsThePagesBar)
        try launchDev(addon: true)
        selectTab("nav.search")
        let field = require("search.field", "Search shows its field")
        ready(field).tap()
        field.typeText("matrix\n")
        let result = requireCard("search.group.0.item.0", timeout: 30, "typing matrix lists results")
        if !waitUntil(5, { !app.keyboards.firstMatch.exists }) { return fail("the keyboard's Search key leaves the keyboard up") }
        requireStaysPut(field, label: "matrix", "search under detail", untitled: "Search") { ready(result).tap() }
    }

    func testLibraryStaysPutUnderDetail() throws {
        try XCTSkipIf(isPad, Self.padShowsThePagesBar)
        try launchDev(addon: true, progress: [Self.seriesEpisodeInProgress])
        selectTab("nav.library")
        let view = require("library.view", timeout: 30, "Library shows its view menu")
        ready(view).tap()
        pickMenuEntry(require("library.view.option.1", timeout: 5, "a tap on Library's view menu opens it"))
        let item = require("library.item.0", timeout: 30, "Continue Watching lists the series")
        let selection = menuText("library.view").components(separatedBy: ": ").last ?? ""
        requireStaysPut(view, label: selection, "library under detail", untitled: "Library") {
            ready(item).tap()
            // Continue Watching resumes: Detail opens under the episode's Streams sheet, dragged down by its bar.
            require("streams.row.0", timeout: 30, "a Continue Watching card opens the episode's streams")
            ready(app.navigationBars["Tortuga Series Test"]).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(
                forDuration: 0.05,
                thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.99)),
                withVelocity: .fast,
                thenHoldForDuration: 0
            )
            requireGone("streams.row.0", "the Streams sheet closes")
        }
    }

    /// The destination under a page shows as at rest while a swipe back is held halfway: on the iPhone Duo's cover
    /// (iOS 27.1) Library's header row and the posters' tops under it went dark and blurred under the navigation bar's
    /// own edge effect, which the covered screen took until the swipe ended (30 Oct).
    func testHeaderRowsStaySharpUnderASwipeBack() throws {
        try XCTSkipIf(isPad, Self.padShowsThePagesBar)
        let titles = ["movie:tortuga-chapters-movie", "movie:tortuga-hero-tall", "series:tortuga-series"]
        try launchDev(addon: true, arguments: titles.flatMap { ["--dev-library", $0] })
        selectTab("nav.library")
        let item = require("library.item.0", timeout: 30, "Library shows its grid")
        requireSharpUnderSwipeBack(require("library.view", "Library shows its view menu"), "Library") { ready(item).tap() }
        selectTab("nav.discover")
        let poster = require("discover.item.0", timeout: 30, "Discover shows its grid")
        requireSharpUnderSwipeBack(require("discover.catalog", "Discover shows its catalog menu"), "Discover") { ready(poster).tap() }
    }

    /// The test addon's Tortuga Search group loading its next page (the addon answers it 6 s late with the first page's
    /// titles, as Cinemeta's search answers a skip): its heading and first card where they were before the spinner came
    /// and after it went, the spinner centred on the heading's text; that page added nothing, so Back from a result's
    /// Detail asks for none (30 Oct: a spinner on the group's heading after every Back, its bottom on the text's
    /// baseline, pushing the results down).
    func testSearchResultsStayPutWhileAGroupLoadsMore() throws {
        try launchDev(addon: true)
        selectTab("nav.search")
        let field = require("search.field", "Search shows its field")
        ready(field).tap()
        field.typeText("matrix\n")
        requireCard("search.group.0.item.0", timeout: 30, "typing matrix lists results")
        if !waitUntil(5, { !app.keyboards.firstMatch.exists }) { return fail("the keyboard's Search key leaves the keyboard up") }
        // The first card of the test addon's first group (after Cinemeta's), dragged up into view.
        let card = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH 'search.group.' AND identifier ENDSWITH '.item.0' AND label BEGINSWITH 'Tortuga Search'"
        )).firstMatch
        let window = app.windows.firstMatch
        for _ in 0..<6 where !(card.exists && card.isHittable && card.frame.maxY < window.frame.maxY - 120) {
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
                .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)))
        }
        if !(card.exists && card.isHittable) { return fail("Search shows no Tortuga Search group for matrix") }
        // Where the drag left it: the screen itself doesn't settle while the spinner turns.
        var last = card.frame
        if !waitUntil(3, { let now = card.frame; defer { last = now }; return now == last }) { return fail("Search's results keep moving after the drag") }
        let place = card.frame
        // The group's heading: the text right above its first card, at its leading edge, then found by its label (an
        // element bound by index turns into another as texts before it come and go).
        let found = app.staticTexts.allElementsBoundByIndex.first {
            $0.frame.maxY <= place.minY + 1 && $0.frame.maxY > place.minY - 60 && abs($0.frame.minX - place.minX) < 4
        }
        guard let found else { return fail("Search's Tortuga Search group shows no heading above its first card") }
        let heading = app.staticTexts.matching(NSPredicate(format: "label == %@", found.label)).firstMatch
        let headingPlace = heading.frame
        let spinner = { self.spinnerFrames().first { abs($0.midY - headingPlace.midY) < 30 } }
        if !waitUntil(5, { spinner() != nil }) { return fail("the group's heading shows no spinner while its next page loads") }
        shot("search group loading more")
        if let shown = spinner(), abs(shown.midY - headingPlace.midY) > 3 {
            fail("the spinner's centre is at y \(Int(shown.midY)), the heading's text's at y \(Int(headingPlace.midY))")
        }
        let loading = (heading: heading.frame, card: card.frame)
        if !waitUntil(10, { spinner() == nil }) { return fail("the spinner stays once the next page has come") }
        shot("search group loaded more")
        if abs(heading.frame.minY - loading.heading.minY) > 1 || abs(card.frame.minY - loading.card.minY) > 1 {
            fail("the heading at y \(Int(loading.heading.minY)) and the first card at y \(Int(loading.card.minY)) while the page loaded move to y \(Int(heading.frame.minY)) and y \(Int(card.frame.minY)) once it came")
        }
        ready(card).tap()
        ready(backButton).tap()
        if waitUntil(4, { spinner() != nil }) {
            shot("search group loading again after back")
            fail("Back from the result's Detail loads the group's next page again, though the last one added nothing")
        }
        if abs(heading.frame.minY - loading.heading.minY) > 1 || abs(card.frame.minY - loading.card.minY) > 1 {
            fail("back from the result's Detail, the heading is at y \(Int(heading.frame.minY)) and the first card at y \(Int(card.frame.minY)), not at y \(Int(loading.heading.minY)) and y \(Int(loading.card.minY))")
        }
    }

    func testContentScrolledUnderTheTopFadesThere() throws {
        try XCTSkipIf(isPad, Self.padTabBarOverTheTop)
        // The test addon: its Search catalogs (`--search`, in the tabs and all scenarios) put Search's results past the
        // screen.
        try launchDev(addon: true)
        requireTabScreen("nav.board", "Home shows at launch")
        selectTab("nav.discover")
        require("discover.item.5", timeout: 30, "Discover shows its grid")
        let filters = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier MATCHES %@", #"discover\.(type|catalog|filter\.[0-9]+)"#))
        guard filters.firstMatch.exists else { return fail("Discover's header row shows no filter") }
        // The safe area's top: the row's, its padding above the filters.
        let safeTop = filters.firstMatch.frame.minY - 8

        // A poster up under Discover's filters row, past its last filter, where the row is empty.
        let controls = filters.allElementsBoundByIndex
        let rowEnd = controls.map(\.frame.maxX).max() ?? 0
        let poster = (0..<6).map { require("discover.item.\($0)", timeout: 5, "Discover shows its grid") }.first { $0.frame.maxX - max($0.frame.minX, rowEnd + 8) >= 40 }
        guard let poster else { return fail("no poster of Discover's first row lies past its filters (they end at x \(Int(rowEnd)))") }
        let start = max(poster.frame.minX, rowEnd + 8)
        let band = filters.firstMatch.frame
        let row = CGRect(x: start, y: band.minY, width: poster.frame.maxX - start, height: band.height)
        requireFaded(poster, under: row, "Discover's poster under its filters row", rowOver: true)
        shot("discover under the row")

        // A result up under Search's field, in the row's margin above the field's capsule (as tall as the system's
        // search field, 38 pt, around the text), with the keyboard away.
        selectTab("nav.search")
        let field = require("search.field", "Search shows its field")
        ready(field).tap()
        field.typeText("matrix\n")
        let result = requireCard("search.group.0.item.0", timeout: 30, "typing matrix lists results")
        if !waitUntil(5, { !app.keyboards.firstMatch.exists }) { return fail("the keyboard's Search key leaves the keyboard up") }
        let capsuleTop = field.frame.midY - 19
        let margin = CGRect(x: result.frame.minX, y: safeTop, width: result.frame.width, height: capsuleTop - safeTop)
        requireFaded(result, under: margin, "Search's result under its field's row", rowOver: true)
        shot("search under the row")

        // Settings' Edit profile up under its navigation bar, in the status bar's part of it, just above the safe area of
        // the other screens. At the default text size the page ends first on a tall iPhone; at the largest text size
        // short of the accessibility sizes its sections make it far longer than the screen.
        try launchDev(textSize: "UICTContentSizeCategoryXXXL")
        requireTabScreen("nav.board", "Home shows at launch")
        selectTab("nav.settings")
        let edit = require("settings.profile.edit", timeout: 30, "Settings shows Edit profile")
        let top = CGRect(x: edit.frame.minX, y: safeTop - 12, width: edit.frame.width, height: 12)
        requireFaded(edit, under: top, "Settings' Edit profile under its navigation bar", rowOver: false)
        shot("settings under the navigation bar")
    }

    func testSearchFieldShowsWhatScrollsUnderIt() throws {
        try XCTSkipIf(isPad, "iPad's results for matrix end above the bottom of its screen: nothing scrolls under the field")
        try XCTSkipUnless(
            ProcessInfo.processInfo.isOperatingSystemAtLeast(OperatingSystemVersion(majorVersion: 26, minorVersion: 0, patchVersion: 0)),
            "before iOS 26 Search's field is the surface fill"
        )
        try launchDev(addon: true)
        requireTabScreen("nav.board", "Home shows at launch")
        selectTab("nav.search")
        let field = require("search.field", "Search shows its field")
        ready(field).tap()
        field.typeText("matrix\n")
        let result = requireCard("search.group.0.item.0", timeout: 30, "typing matrix lists results")
        if !waitUntil(5, { !app.keyboards.firstMatch.exists }) { return fail("the keyboard's Search key leaves the keyboard up") }
        let clear = require("search.clear", "a query shows the field's clear button")
        // Inside the capsule, clear of its rim, from the field's middle (past the query) to the clear button.
        let band = CGRect(x: field.frame.midX, y: field.frame.midY - 9, width: clear.frame.minX - 8 - field.frame.midX, height: 18)
        requireFaded(result, under: band, "Search's result under its field's capsule", rowOver: true)
        shot("search under the field")
    }

    func testTheTopIsClearAtRest() throws {
        try XCTSkipIf(isPad, Self.padTabBarOverTheTop)
        try launchDev(addon: true)
        requireTabScreen("nav.board", "Home shows at launch")
        selectTab("nav.discover")
        let first = require("discover.item.0", timeout: 30, "Discover shows its grid")
        // The catalog's live art: a poster's top can be black (a title on a dark sky). Of the first row's posters, the
        // one whose top is brightest at rest.
        let row = (0..<4).map { element("discover.item.\($0)") }.filter { $0.exists && abs($0.frame.minY - first.frame.minY) < 1 }
        screenSettles(appScreen)
        let resting = appScreen.screenshot()
        let tops = row.map { CGRect(x: $0.frame.minX, y: $0.frame.minY, width: $0.frame.width, height: 16) }
        let top = tops.max { resting.brightness(in: $0) < resting.brightness(in: $1) }!
        requireClearAtRest(top, "Discover's brightest poster of its first row, under its filters row")
        shot("discover at rest")
        selectTab("nav.settings")
        let edit = require("settings.profile.edit", timeout: 30, "Settings shows Edit profile")
        requireClearAtRest(
            CGRect(x: edit.frame.minX, y: edit.frame.minY, width: edit.frame.width, height: 16),
            "Settings' profile under its title"
        )
        shot("settings at rest")
    }

    func testATabShownAgainIsAsItWasLeft() throws {
        try launchDev()
        requireCard("board.row.1.item.0", timeout: 90, "the Board shows its second shelf")
        // An iPad in portrait holds the dev profile's shelves whole: only an iPhone's Home scrolls.
        let shelves = (0..<8).map { "board.row.\($0).item.0" }
        let home = isPad ? nil : scrollDown(shelves, under: nil, "Home")
        shot("home scrolled")
        let homeY = home.map { element($0).frame.minY }
        selectTab("nav.discover")
        require("discover.item.0", timeout: 30, "Discover shows its grid")
        let first = menuText("discover.catalog")
        ready(require("discover.catalog", "Discover shows its catalog menu")).tap()
        pickMenuEntry(require("discover.catalog.option.1", timeout: 5, "the catalog menu lists a second catalog"))
        if !waitUntil(30, { menuText("discover.catalog") != first && element("discover.item.0").exists }) {
            fail("picking the second catalog leaves Discover on \(menuText("discover.catalog"))")
        }
        let catalog = menuText("discover.catalog")
        let discover = scrollDown((0..<60).map { "discover.item.\($0)" }, under: "discover.catalog", "Discover")
        shot("discover \(catalog) scrolled")
        let discoverY = discover.map { element($0).frame.minY }
        selectTab("nav.board")
        shot("home")
        selectTab("nav.discover")
        if menuText("discover.catalog") != catalog {
            fail("Discover shown again is on \(menuText("discover.catalog")), not the catalog picked (\(catalog))")
        }
        if let discover, let discoverY { requireKept(discover, at: discoverY, "Discover") }
        shot("discover again")
        selectTab("nav.board")
        if let home, let homeY { requireKept(home, at: homeY, "Home") }
        shot("home again")
    }

    /// iPad's floating tab bar, in portrait with the sidebar hidden, holds the five tabs whole in each of the app's
    /// languages at the default text size: each tab inside the bar and on screen, within a tap's reach, and no other
    /// button in the bar's row (its scroll arrow). iPadOS 26.5's 13" iPad put Settings behind that arrow in de, fr and
    /// pl while the bar showed the destinations' full names. Larger text sizes grow the bar's labels, and the tabs that
    /// don't fit then go behind the arrow (`pageFloatingTabBar`). The sidebar names them in full (shots in en, de, fr
    /// and pl).
    func testFloatingTabBarHoldsTheFiveTabsInEveryLanguage() throws {
        try XCTSkipUnless(isPad, "iPhone has no floating tab bar")
        // 21 launches: longer than the default 5 minutes.
        executionTimeAllowance = 900
        Self.quitAndTurn(to: .portrait)
        var crowded: [String] = []
        for language in Self.interfaceLanguages {
            try launchDev(languages: [language])
            requireTabScreen("nav.board", "\(language): Home shows at launch")
            hideSidebar(language)
            // The tabs take their short names once the tab view knows where they show.
            var problem: String?
            _ = waitUntil(10) {
                problem = floatingBarProblem()
                return problem == nil
            }
            shot("\(language) floating tab bar")
            if let problem { crowded.append("\(language): \(problem)") }
            guard ["en", "de", "fr", "pl"].contains(language) else { continue }
            openSidebar(language)
            shot("\(language) sidebar")
        }
        if !crowded.isEmpty { fail("the floating tab bar doesn't hold the five tabs whole: \(crowded.joined(separator: "; "))") }
    }

    /// Why the tests of a destination staying put under the pages over it run on iPhone alone.
    private static let padShowsThePagesBar = "iPad's destinations show the navigation bar the pages pushed over them show: nothing comes or goes"

    /// Why the top's fade is measured on iPhone alone.
    private static let padTabBarOverTheTop = "iPad's floating tab bar lies over the bar's region, its glass measured with the content"

    /// Drags the page up by about two thirds of the window and returns the first of `items` (the page's, in order) that
    /// then shows whole below `header` (a control of its header row; the window's top for a screen without one). Fails
    /// with `what` when that is still the first.
    private func scrollDown(_ items: [String], under header: String?, _ what: String) -> String? {
        let window = app.windows.firstMatch
        for _ in 0..<2 {
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
                .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)))
        }
        screenSettles(appScreen)
        let top = header.map { require($0, "\(what) shows its header row").frame.maxY } ?? window.frame.minY
        guard let shown = items.first(where: { element($0).exists && element($0).frame.minY >= top }) else {
            fail("\(what): none of its items shows below its header row after a scroll")
            return nil
        }
        if shown == items[0] { fail("\(what): a drag up leaves the page at its top") }
        return shown
    }

    /// Requires the page shown again where it was left: `shown` (what `scrollDown` returned) at `y`, where it was as the
    /// page was left, within the 2 pt of a rounding (an item restored to the top of the page, not to the offset, came
    /// back a row of posters higher, or a whole shelf).
    private func requireKept(_ shown: String, at y: CGFloat, _ what: String) {
        let item = require(shown, timeout: 10, "\(what) shown again shows \(shown), where it was left")
        if abs(item.frame.minY - y) > 2 {
            fail("\(what) shown again has \(shown) at y \(Int(item.frame.minY)), not at y \(Int(y)) where it was left")
        }
    }

    /// Taps the tab `id` and waits for it to be selected and its screen to show. A tap on Search puts the cursor in its
    /// field, the keyboard up (`TortugaNavigator.pick`), which the next tap waits for (on iPad a sidebar tap as the
    /// keyboard came up was lost twice in 16 runs). On iPhone the keyboard covers the tab bar: a tap on Search's page
    /// under the field (blank in a dev session: no recent searches) puts it away, as a person reaching the tabs would.
    func selectTab(_ id: String) {
        let tab = phoneTab(id)
        ready(tab).tap()
        if !waitUntil(5, { tab.isSelected }) { fail("a tap on \(id) doesn't select it") }
        requireTabScreen(id, "\(id)'s screen shows")
        guard id == "nav.search" else { return }
        if !app.keyboards.firstMatch.exists(within: 5) { fail("a tap on the Search tab leaves the keyboard down") }
        if isPad { return }
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap()
        if !waitUntil(5, { !app.keyboards.firstMatch.exists }) { fail("a tap on Search's page leaves the keyboard over the tab bar") }
    }

    /// Opens a page over the screen with `open`, then swipes back from the screen's leading edge and holds the swipe at
    /// 60 % of the width for a second, frames sampled (`ScreenSampler`). In each frame that shows the screen beside the
    /// page (a part of it under its header row, matched where it has slid to), the header row `row` and the 30 pt under
    /// it are as bright as at rest (at least four fifths) and as sharp (at least three fifths of the contrast, where
    /// there is some): not under a dark, blurred band. Fails with `what`, the frames judged kept.
    private func requireSharpUnderSwipeBack(_ row: XCUIElement, _ what: String, open: () -> Void) {
        let screen = appScreen
        screenSettles(screen)
        let rest = screen.screenshot()
        let window = app.windows.firstMatch.frame
        let x = window.minX + window.width * 0.35, width = window.width * 0.25
        let bands = [
            ("its header row", CGRect(x: x, y: row.frame.minY, width: width, height: row.frame.height)),
            ("the 30 pt under its header row", CGRect(x: x, y: row.frame.maxY, width: width, height: 30)),
        ]
        let probe = CGRect(x: x, y: row.frame.maxY + 60, width: width, height: 120)
        open()
        let back = ready(backButton)
        screenSettles(screen)
        let sampler = ScreenSampler(of: screen)
        sampler.start()
        let edge = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0.5)).withOffset(CGVector(dx: 2, dy: 0))
        edge.press(forDuration: 0.05, thenDragTo: app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.5)),
                   withVelocity: .slow, thenHoldForDuration: 1)
        let frames = sampler.stop()
        if !waitUntil(5, { !back.exists }), back.exists { back.tap() }
        screenSettles(screen)
        var judged = 0
        var problems: [String] = []
        for (index, frame) in frames.enumerated() {
            guard let slid = rest.sideways(of: probe, in: frame, by: -window.width * 0.35...0), slid.difference < 12 else { continue }
            judged += 1
            var dark: [String] = []
            for (name, band) in bands {
                let moved = band.offsetBy(dx: slid.offset, dy: 0)
                let lit = rest.brightness(in: band), sharp = rest.contrast(in: band)
                let level = frame.brightness(in: moved), flat = frame.contrast(in: moved)
                if level < lit * 0.8 || sharp >= 8 && flat < sharp * 0.6 {
                    dark.append(String(format: "%@ %.0f bright and %.1f contrast against %.0f and %.1f at rest", name, level, flat, lit, sharp))
                }
            }
            if !dark.isEmpty { problems.append("frame \(index + 1) (slid \(Int(slid.offset)) pt): \(dark.joined(separator: ", "))") }
            if !dark.isEmpty || judged <= 3 {
                let attachment = XCTAttachment(screenshot: frame)
                attachment.name = String(format: "%@ swipe held %02d", what, index + 1)
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
        if judged == 0 { return fail("\(what): none of \(frames.count) frames of the swipe shows the screen beside the page") }
        if !problems.isEmpty { fail("\(what) under a swipe back held halfway: \(problems.joined(separator: "; "))") }
    }

    /// The frames of the spinners showing, from one snapshot of the app: a query's spinner can go between its count and
    /// its frame, which fails the test.
    private func spinnerFrames() -> [CGRect] {
        let root: XCUIElementSnapshot
        do {
            root = try app.snapshot()
        } catch {
            fail("no snapshot of the app to find its spinners: \(error)")
            return []
        }
        var frames: [CGRect] = [], left = [root]
        while let node = left.popLast() {
            if node.elementType == .activityIndicator { frames.append(node.frame) }
            left += node.children
        }
        return frames
    }

    /// Drags `content` up until it covers `region` (its top about 10 pt above it), then requires the part of it in
    /// `region` faded or blurred there, compared with the same part at rest: dimmer (at most four fifths as bright) or
    /// flatter (at most three fifths of the contrast), and where a row lies over `region` (`rowOver`) still showing (at
    /// least a sixth as bright), not cut off by an opaque row. What `region` shows at rest (the status bar's black) is
    /// taken off.
    private func requireFaded(_ content: XCUIElement, under region: CGRect, _ what: String, rowOver: Bool) {
        let screen = appScreen
        screenSettles(screen)
        let resting = screen.screenshot()
        let rest = content.frame
        let target = region.minY - 10
        let covers = { content.frame.minY <= region.minY - 4 && content.frame.maxY >= region.maxY + 4 }
        let window = app.windows.firstMatch
        for _ in 0..<4 where !covers() {
            // A drag moves the page by its length less the first few points, which only start the scroll.
            let distance = target - content.frame.minY
            let from = window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
            let to = from.withOffset(CGVector(dx: 0, dy: distance + (distance < 0 ? -12 : 12)))
            from.press(forDuration: 0.1, thenDragTo: to, withVelocity: .slow, thenHoldForDuration: 0.5)
            screenSettles(screen)
        }
        let moved = content.frame
        if !covers() { return fail("\(what): it doesn't scroll over y \(Int(region.minY)) to \(Int(region.maxY)) (it stops at \(Int(moved.minY)) to \(Int(moved.maxY)))") }
        let part = CGRect(x: region.minX, y: rest.minY + region.minY - moved.minY, width: region.width, height: region.height)
        let lit = resting.brightness(in: part)
        let litContrast = resting.contrast(in: part)
        if lit < 12 { return fail("\(what): the part measured is \(Int(lit)) bright at rest, too dark to tell a fade") }
        let frame = screen.screenshot()
        let level = frame.brightness(in: region) - resting.brightness(in: region)
        let flat = frame.contrast(in: region)
        if rowOver, level < lit / 6 {
            fail("\(what): \(Int(level)) bright under the row, not showing its \(Int(lit)) at rest: cut off at an opaque row")
        }
        if level > lit * 0.8, flat > litContrast * 0.6 {
            fail("\(what): \(Int(level)) bright and \(Int(flat)) contrast under the top, against \(Int(lit)) and \(Int(litContrast)) at rest: not faded")
        }
    }

    /// Requires what `region` shows at rest as bright and as sharp as when the page is pulled down a little (at least
    /// 24 pt, held there): nothing faded or blurred at the top while nothing is scrolled under it. The pulled frame is
    /// taken while the drag holds (`ScreenSampler`); how far the page moved is where `region` and the 48 pt under it
    /// match best.
    private func requireClearAtRest(_ region: CGRect, _ what: String) {
        let screen = appScreen
        screenSettles(screen)
        let resting = screen.screenshot()
        let window = app.windows.firstMatch
        let sampler = ScreenSampler(of: screen)
        sampler.start()
        let from = window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35))
        from.press(forDuration: 0.1, thenDragTo: from.withOffset(CGVector(dx: 0, dy: 160)), withVelocity: .slow, thenHoldForDuration: 1)
        let frames = sampler.stop()
        screenSettles(screen)
        let match = CGRect(x: region.minX, y: region.minY, width: region.width, height: region.height + 48)
        let pulled = frames.map { (frame: $0, offset: resting.offset(of: match, in: $0, upTo: 120)) }.max { $0.offset < $1.offset }
        guard let pulled, pulled.offset >= 24 else {
            return fail("\(what): a pull down moves the page \(Int(pulled?.offset ?? 0)) pt in \(frames.count) frames, not 24")
        }
        let lower = region.offsetBy(dx: 0, dy: pulled.offset)
        let lit = resting.brightness(in: region), litLower = pulled.frame.brightness(in: lower)
        let sharp = resting.contrast(in: region), sharpLower = pulled.frame.contrast(in: lower)
        print("top at rest, \(what): \(lit) bright, \(sharp) contrast; \(pulled.offset) pt lower \(litLower), \(sharpLower)")
        if litLower < 12 { return fail("\(what): the part measured is \(Int(litLower)) bright pulled down, too dark to tell a fade") }
        if lit < litLower * 0.95 || sharp < sharpLower * 0.95 {
            fail("\(what): \(String(format: "%.1f", lit)) bright and \(String(format: "%.1f", sharp)) contrast at rest, against \(String(format: "%.1f", litLower)) and \(String(format: "%.1f", sharpLower)) \(Int(pulled.offset)) pt lower: faded at rest")
        }
    }

    /// The app's interface languages (shared/i18n/).
    private static let interfaceLanguages = [
        "ar", "cs", "de", "en", "es", "fr", "hi", "id", "it", "ja", "ko", "nl", "pl", "pt-BR", "ro", "ru", "sv", "th", "tr", "uk", "vi",
    ]

    /// Hides iPad's sidebar where the launch shows it (iPad keeps it shown or hidden across launches) by its own toggle
    /// (`ToggleSidebar`, Hide Sidebar; the floating tab bar's is `ToggleSideBar`), leaving the floating tab bar.
    private func hideSidebar(_ what: String) {
        let row = app.cells.matching(identifier: "nav.board").firstMatch
        guard row.waitForExistence(timeout: 2) else { return }
        ready(app.buttons.matching(NSPredicate(format: "identifier == 'ToggleSidebar'")).firstMatch).tap()
        if !waitUntil(10, { !row.exists }) { fail("\(what): the sidebar's toggle doesn't hide it") }
    }

    /// What keeps iPad's floating tab bar from holding the five tabs whole, or nil: a tab or the bar's sidebar toggle
    /// (`ToggleSideBar`) missing, a tab's frame not all inside the frames around it (the bar, its scroll view, the
    /// window: as XCUITest clips an element before its hit test), another button in the row from the toggle to the last
    /// tab (the bar's scroll arrow), or a tab a tap can't reach.
    private func floatingBarProblem() -> String? {
        guard let root = try? app.snapshot() else { return "no element tree" }
        let ids = Self.phoneTabs + ["ToggleSideBar"]
        var found: [String: (frame: CGRect, visible: CGRect)] = [:]
        var others: [(name: String, frame: CGRect)] = []
        func walk(_ node: any XCUIElementSnapshot, _ inside: CGRect) {
            let visible = inside.intersection(node.frame)
            if node.elementType == .button {
                if ids.contains(node.identifier) {
                    if found[node.identifier] == nil { found[node.identifier] = (node.frame, visible) }
                    return
                }
                others.append((node.identifier.isEmpty ? node.label : node.identifier, node.frame))
            }
            for child in node.children { walk(child, visible) }
        }
        walk(root, .infinite)
        let missing = ids.filter { found[$0] == nil }
        if !missing.isEmpty { return "no \(missing.joined(separator: ", ")) in the bar" }
        var problems: [String] = []
        for id in Self.phoneTabs {
            guard let tab = found[id] else { continue }
            if tab.frame.isEmpty || tab.visible.isNull || tab.visible.width < tab.frame.width - 0.5
                || tab.visible.height < tab.frame.height - 0.5 {
                problems.append("\(id) at \(tab.frame) shows \(tab.visible.isNull ? "nothing" : "only \(tab.visible)")")
            }
        }
        // Whichever way the language reads.
        let row = found.values.reduce(CGRect.null) { $0.union($1.frame) }
        let extra = others.filter { row.contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY)) }
        if !extra.isEmpty { problems.append("the row holds \(extra.map { "'\($0.name)' at \($0.frame)" }.joined(separator: ", ")) beside the tabs") }
        if problems.isEmpty {
            let unreachable = Self.phoneTabs.filter { !app.buttons.matching(identifier: $0).firstMatch.isHittable }
            if !unreachable.isEmpty { problems.append("\(unreachable.joined(separator: ", ")) out of a tap's reach") }
        }
        return problems.isEmpty ? nil : problems.joined(separator: ", ")
    }
}
