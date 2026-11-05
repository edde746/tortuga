import AppKit
import XCTest

/// The Mac window's sidebar: the running profile and the five destinations (Home, Discover, Search, Library,
/// Settings; Addons is a section of Settings), Home selected at launch; a click on each destination shows its
/// screen, which titles the window. The toolbar holds navigation and the screen's actions: over a destination's screen
/// the sidebar's toggle, over a pushed screen (Settings → Playback) Back as well, the window titled after it. Each
/// other screen's filters are its header row, which sits on the toolbar's band, level with the window's buttons, with
/// no empty band above it (Discover's and Library's filters, Search's field); a click on a filter there opens it
/// (Library's view menu). Home holds nothing in the toolbar but the sidebar's toggle, its shortcuts (Watch
/// together, Downloads, Library) right under the window's buttons above the first shelf, and a click on Library's
/// opens Library. Settings → Playback, pushed over Settings, shows no box over its centred column while
/// another app is in front, and Back returns to Settings, whose toolbar never shows "Settings" as a title, not even
/// for the frames the pop takes to reach the navigator. Rows scrolled up under the toolbar fade there: on
/// Settings → Playback, Short skip's title scrolled up into the band shows at most four fifths as bright as below it,
/// with the window active and inactive; at rest the band is the page's black.
/// The toolbar's sidebar toggle hides the sidebar, the header row then starting past it on the band, and ⌃⌘S shows it
/// again; View → Hide Sidebar and View → Show Sidebar do the same. The band's empty parts are the window's title bar: a
/// drag on Home's band, on Settings' band (no header row) and on a pushed screen's toolbar moves the window, and a
/// double click on Settings' band zooms it and another puts it back, as on Home's, on Detail's and on Discover's header
/// row past its filters; a double click on a control there is the control's, the window staying as it is (Discover's
/// catalog menu, Search's field, the toolbar's sidebar toggle). The pointer coming down from the band into Home,
/// Settings, Discover or Library leaves the screen lit: no frame of it darker than the rest
/// (`windowToolbarBand`). Asked for the light appearance (a Mac in light mode), the system's chrome stays dark: the
/// title bar's band and the View menu. The App menu lists Settings…, whose ⌘, shows Settings but leaves the player up
/// while a video plays; the Playback menu acts on the player and on nothing else. Full screen keeps the window's
/// buttons where the window has them, over Home and a pushed Detail, one set even with the menu bar down, and its zoom
/// leaves full screen.
extension TabsTests {
    func testSidebarListsEveryDestinationAndAClickShowsItsScreen() throws {
        // Continue Watching's titles, for posters on Library's grid.
        launch(["--dev"] + Self.scrollingProgress.flatMap { ["--dev-progress", $0] })
        require("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        let ids = ["nav.board", "nav.discover", "nav.search", "nav.library", "nav.settings"]
        for id in ids { require(id, "the sidebar lists \(id)") }
        require("nav.profile", "the sidebar shows the running profile at launch")
        let window = app.windows.firstMatch
        var titles = [window.title]
        if titles[0].isEmpty { fail("the window has no title at launch") }
        shot("home")
        // Each destination titles the window with its own name.
        for id in ids.dropFirst() {
            ready(id).click()
            if !waitUntil(10, { !window.title.isEmpty && !titles.contains(window.title) }) {
                fail("a click on \(id) doesn't show a screen of its own: the window stays titled \"\(window.title)\"")
            }
            titles.append(window.title)
            if let header = Self.firstHeaderControl[id] {
                requireOnToolbarBand(header, "\(id)'s screen shows its header row")
            }
            requireOnlyNavigationInToolbar("\(id)'s screen")
            shot(id)
        }
        // Library's view menu, on the band, opens from a click there.
        ready("nav.library").click()
        ready(require("library.view", timeout: 30, "Library shows its view menu")).click()
        pickMenuEntry(require("library.view.option.1", timeout: 5, "a click on Library's view menu, on the toolbar's band, opens it"))
        require("library.item.0", timeout: 30, "Continue Watching lists its titles")
        requireOnToolbarBand("library.view", "Continue Watching's view menu")
        ready("nav.board").click()
        require("board.row.0.item.0", timeout: 30, "a click on Home shows the Board again")
    }

    func testHomeHoldsNoToolbarItemsAndBackFromAPushedScreenShowsNoTitle() throws {
        try launchDev()
        require("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        let window = app.windows.firstMatch
        requireOnlyNavigationInToolbar("Home")
        // The shortcuts' line sits a little lower than the band; a row of buttons under the band would add 40 pt.
        let buttonsRow = window.buttons[XCUIIdentifierCloseWindow].frame
        let row = require("board.shortcuts", "Home shows its shortcuts")
        let gap = row.frame.minY - buttonsRow.maxY
        if gap > 24 { fail("Home's shortcuts start \(Int(gap)) pt under the window's buttons: an empty band above them") }
        let labels = ["watchTogether", "downloads", "library"].map { require("board.shortcut.\($0)", "Home's shortcuts offer \($0)").label }
        if labels != ["Watch Together", "Downloads", "Library"] { fail("Home's shortcuts read \(labels)") }
        if require("board.row.0.seeAll", "the first shelf shows See all").frame.minY < row.frame.maxY {
            fail("Home's first shelf isn't under its shortcuts")
        }
        shot("home toolbar")
        ready(element("board.shortcut.library")).click()
        require("library.view", timeout: 30, "Home's Library opens Library")
        shot("library from home")
        ready(require("nav.settings", "the sidebar lists Settings")).click()
        require("settings.section.playback", timeout: 30, "Settings lists its sections")
        let settings = window.title
        // The toolbar's band between the sidebar and the window's trailing end, where a title would show.
        let toolbar = app.toolbars.firstMatch.frame
        let start = require("nav.board", timeout: 5, "the sidebar lists Home").frame.maxX + 4
        let band = CGRect(x: start, y: toolbar.minY, width: window.frame.maxX - 8 - start, height: toolbar.height)
        for round in 1...Self.backRounds {
            ready(require("settings.section.playback", timeout: 10, "Settings lists Playback")).click()
            require("settings.playback.seekShort", timeout: 30, "Playback opens over Settings with Short skip")
            if round == 1 {
                // A pushed screen: Back and the screen's title in the toolbar.
                if !waitUntil(5, { !window.title.isEmpty && window.title != settings }) {
                    fail("Playback doesn't title the window: it stays titled \"\(window.title)\"")
                }
                if !toolbarBack.exists { fail("Playback shows no Back in the toolbar") }
                requireOnlyNavigationInToolbar("Playback", pushed: true)
                shot("playback")
                requireEvenToolbarWhileInactive("Playback's centred column", column: "settings.playback.seekShort")
            }
            let sampler = ScreenSampler()
            sampler.start()
            goBack()
            require("settings.section.playback", timeout: 30, "Back returns to Settings")
            // The frames the pop takes to reach the navigator: three more, about 0.3 s.
            let shown = sampler.count
            _ = waitUntil(5) { sampler.count >= shown + 3 }
            let frames = sampler.stop()
            // Playback's own title reads "Playback" there until the pop.
            let titled = frames.firstIndex { frame in
                let lines = frame.textLines(in: band).map { $0.trimmingCharacters(in: .whitespaces) }
                return lines.contains(settings) && !lines.contains { $0.contains("Playback") }
            }
            if let titled {
                let attachment = XCTAttachment(screenshot: frames[titled])
                attachment.name = "settings titled after back"
                attachment.lifetime = .keepAlways
                add(attachment)
                fail("Back from Playback showed \"\(settings)\" in the toolbar (round \(round), frame \(titled + 1) of \(frames.count))")
            }
        }
        requireOnlyNavigationInToolbar("Settings after Back")
    }

    func testRowsScrolledUnderTheToolbarFadeThere() throws {
        try launchDev()
        ready(require("nav.settings", timeout: 90, "the sidebar lists Settings")).click()
        ready(require("settings.section.playback", timeout: 30, "Settings lists Playback")).click()
        let stepper = require("settings.playback.seekShort", timeout: 30, "Playback lists Short skip")
        let title = app.staticTexts.matching(NSPredicate(format: "value == 'Short skip' OR label == 'Short skip'")).firstMatch
        if !title.exists(within: 5) { fail("Playback shows no \"Short skip\"") }
        screenSettles()
        // At rest the band past the toolbar's title is the page's black.
        let toolbar = app.toolbars.firstMatch.frame
        if !toolbarBack.exists(within: 5) { fail("Playback shows no Back in the toolbar") }
        let past = toolbarBack.frame.maxX + 120
        let resting = XCUIScreen.main.screenshot()
        let rest = resting.brightness(in: CGRect(x: past, y: toolbar.minY + 4, width: stepper.frame.maxX - past, height: toolbar.height - 8))
        if rest > 4 { fail("at rest the band over Playback is \(Int(rest)) bright, not the page's black") }
        let lit = resting.brightness(in: title.frame)
        // Short skip's title up toward the band's top, as far as the page scrolls (its end stops it below the line of the
        // window's buttons); the fade is the more the higher it is.
        let window = app.windows.firstMatch
        let high = toolbar.minY + 8
        let column = window.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: title.frame.midX - window.frame.minX, dy: window.frame.height * 0.6))
        for _ in 0..<4 where title.exists && title.frame.midY - high > 4 {
            column.scroll(byDeltaX: 0, deltaY: high - title.frame.midY)
            screenSettles()
        }
        if !title.exists || title.frame.midY > toolbar.maxY - 8 {
            fail("Short skip's title (y \(Int(title.frame.midY))) doesn't scroll up into the band (y \(Int(toolbar.minY)) to \(Int(toolbar.maxY)))")
        }
        let faded = title.frame
        // What the band shows there at rest: the toolbar's title, where it reaches over the column.
        let toolbarTitle = resting.brightness(in: faded)
        shot("playback scrolled under the toolbar")
        for (frame, state) in [(XCUIScreen.main.screenshot(), "active"), (inactiveScreen("playback scrolled under the toolbar"), "inactive")] {
            let level = frame.brightness(in: faded) - toolbarTitle
            if level > lit * 0.8 {
                fail("with the window \(state), Short skip's title in the band (y \(Int(faded.midY))) is \(Int(level)) bright, not dimmer than four fifths of its \(Int(lit)) below the band")
            }
        }
    }

    func testSidebarToggleAndTheViewMenuHideTheSidebar() throws {
        try launchDev()
        ready(require("nav.discover", timeout: 30, "the sidebar lists Discover")).click()
        require("discover.catalog", timeout: 30, "Discover shows its header row")
        // Whatever happens, the sidebar shows again: the window keeps its state for the next launch.
        addTeardownBlock { [self] in
            if sidebarToggle.exists, sidebarToggle.label == Self.showSidebar { app.typeKey("s", modifierFlags: [.command, .control]) }
        }
        let toggle = sidebarToggle
        if !toggle.exists(within: 5) { fail("the toolbar shows no sidebar toggle") }
        if toggle.label != Self.hideSidebar { fail("the toolbar's sidebar toggle reads \"\(toggle.label)\", not \(Self.hideSidebar)") }
        ready(toggle).click()
        requireSidebar(shown: false, "a click on the toolbar's sidebar toggle")
        requireOnToolbarBand("discover.catalog", "with the sidebar hidden, Discover's header row")
        // The row's first control: the type's menu where the catalogs have more than one type.
        let first = ["discover.type", "discover.catalog"].map(element).first(where: \.exists) ?? element("discover.catalog")
        if first.frame.minX < sidebarToggle.frame.maxX {
            fail("with the sidebar hidden, Discover's header row (x \(Int(first.frame.minX))) starts under the sidebar's toggle (to x \(Int(sidebarToggle.frame.maxX)))")
        }
        shot("sidebar hidden")
        app.typeKey("s", modifierFlags: [.command, .control])
        requireSidebar(shown: true, "⌃⌘S")
        pickMenuEntry(viewMenuSidebarItem())
        requireSidebar(shown: false, "View → Hide Sidebar")
        pickMenuEntry(viewMenuSidebarItem())
        requireSidebar(shown: true, "View → Show Sidebar")
        require("nav.profile", timeout: 5, "shown again, the sidebar shows the running profile")
        shot("sidebar shown")
    }

    /// Full screen keeps the window's buttons where the window has them (`FullScreenWindowButtons`): over Detail pushed
    /// on Home and over Home, with the pointer away from the top edge (the menu bar hidden), close and zoom are hittable
    /// and minimize drawn between them (greyed out, as in AppKit's full-screen title bar, which leaves it out of the
    /// accessibility tree), the close button within 3 pt of where it sits in the window, one red close button on the
    /// screen, and the toolbar's sidebar toggle and Back to their right. The top edge pushed brings the title bar down
    /// under the menu bar with the buttons, still one close button; the pointer away, they are back at the top. A click
    /// on zoom leaves full screen.
    func testFullScreenKeepsTheWindowButtons() throws {
        addTeardownBlock { [weak self] in
            guard let self, self.app.state == .runningForeground, self.isFullScreen else { return }
            self.app.typeKey("f", modifierFlags: .function)
        }
        try launchDev(addon: true, open: ["board", "detail:movie:tortuga-hero-plain"])
        require("detail.title", timeout: 60, "the movie's details show")
        let window = app.windows.firstMatch
        let close = window.buttons[XCUIIdentifierCloseWindow].frame
        let windowed = close.offsetBy(dx: -window.frame.minX, dy: -window.frame.minY)

        ready(app.menuBars.menuBarItems["View"]).click()
        pickMenuEntry(app.menuBars.menuItems["Enter Full Screen"])
        requireFullScreen(true, "the View menu's Enter Full Screen on the details")
        requireWindowButtonsInFullScreen(at: windowed, "Detail in full screen", pushed: true)
        shot("Detail, full screen", of: XCUIScreen.main)

        goBack()
        require("board.shortcuts", timeout: 10, "Back returns to Home")
        requireWindowButtonsInFullScreen(at: windowed, "Home in full screen", pushed: false)
        shot("Home, full screen", of: XCUIScreen.main)
        try pushTopEdge()
        requireCloseButtonsShown(below: Self.menuBarBottom, "Home in full screen, the top edge pushed")
        shot("Home, full screen, the top edge pushed", of: XCUIScreen.main)
        let zoom = requireWindowButtonsInFullScreen(at: windowed, "Home in full screen, the pointer back from the top edge", pushed: false)
        ready(zoom).click()
        requireFullScreen(false, "the zoom button in full screen")
    }

    /// With the window full screen and the pointer moved to its middle, away from the top edge (the title bar back up):
    /// the window's close and zoom buttons hittable and minimize drawn between them, the close button within 3 pt of
    /// `at` (its frame in the window, relative to the window), one red close button on the screen, and the toolbar's
    /// sidebar toggle (and Back where `pushed`) right of zoom. Fails with `what`; returns the zoom button.
    @discardableResult
    private func requireWindowButtonsInFullScreen(at: CGRect, _ what: String, pushed: Bool) -> XCUIElement {
        let window = app.windows.firstMatch
        pointerOn(window, at: CGVector(dx: 0.5, dy: 0.5))
        _ = waitUntil(5) { !closeButtonsShown().contains { $0.y > Self.menuBarBottom } }
        screenSettles(XCUIScreen.main)
        let close = app.buttons.matching(identifier: XCUIIdentifierCloseWindow).firstMatch
        let zoom = app.buttons.matching(NSPredicate(format: "identifier IN %@", [XCUIIdentifierZoomWindow, XCUIIdentifierFullScreenWindow])).firstMatch
        let shown = closeButtonsShown()
        let frames = [close, zoom].map { $0.exists ? "\($0.frame) hittable \($0.isHittable)" : "none" }
        print("\(what): window \(window.frame), close \(frames[0]), zoom \(frames[1]), red close buttons at \(shown)")
        for (button, kind) in [(close, "close"), (zoom, "zoom")] where !(button.exists && button.isHittable) {
            fail("\(what): the window's \(kind) button isn't hittable")
        }
        let closeFrame = close.frame.offsetBy(dx: -window.frame.minX, dy: -window.frame.minY)
        if abs(closeFrame.minX - at.minX) > 3 || abs(closeFrame.midY - at.midY) > 3 {
            fail("\(what): the close button sits at \(closeFrame), in the window at \(at)")
        }
        if shown.count != 1 { fail("\(what): the screen shows \(shown.count) close buttons (at \(shown)), not one") }
        // Minimize's circle, midway between close and zoom, against the band just right of zoom.
        let middle = CGPoint(x: (close.frame.midX + zoom.frame.midX) / 2, y: close.frame.midY)
        let screen = ScreenPixels()
        let minimize = screen?.medianLuminance(in: CGRect(x: middle.x - 3, y: middle.y - 3, width: 6, height: 6)) ?? 0
        let beside = screen?.medianLuminance(in: CGRect(x: zoom.frame.maxX + 6, y: middle.y - 3, width: 6, height: 6)) ?? 0
        if abs(minimize - beside) < 0.02 {
            fail("\(what): no minimize button drawn between close and zoom (luminance \(minimize) there, \(beside) beside zoom)")
        }
        for (control, name) in [(sidebarToggle, "sidebar toggle")] + (pushed ? [(toolbarBack, "Back")] : [])
            where control.frame.minX < zoom.frame.maxX {
            fail("\(what): the toolbar's \(name) (\(control.frame)) isn't right of the zoom button (\(zoom.frame))")
        }
        return zoom
    }

    func testPointerFromTheToolbarIntoAScreenLeavesItLit() throws {
        // Continue Watching's titles, for posters on Library's grid.
        launch(["--dev"] + Self.scrollingProgress.flatMap { ["--dev-progress", $0] })
        require("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        for (id, shown) in Self.litScreens {
            ready(id).click()
            let screen = require(shown, timeout: 30, "a click on \(id) shows its screen")
            if id == "nav.library" {
                ready(screen).click()
                pickMenuEntry(require("library.view.option.1", timeout: 5, "a click on Library's view menu opens it"))
                require("library.item.0", timeout: 30, "Continue Watching lists its titles")
            }
            requireLitWhilePointerComesDown("\(id)'s screen")
        }
    }

    /// XCUITest's drags don't move windows and the sandboxed runner may not post mouse events, so each drag goes to
    /// the run's hook (`drag-<n>` in `TORTUGA_EXCHANGE`), which posts it from an ssh session.
    func testTheBandsEmptyPartsDragTheWindow() throws {
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        try launchDev()
        require("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        let window = app.windows.firstMatch
        let band = window.buttons[XCUIIdentifierCloseWindow].frame.midY
        // Home's band, empty: between the sidebar and the window's trailing end.
        let sidebarEnd = require("nav.board", "the sidebar lists Home").frame.maxX
        let home = CGPoint(x: (sidebarEnd + window.frame.maxX) / 2, y: band)
        requireDragMovesWindow(from: home, "Home's band", exchange)
        shot("home band dragged")
        // Settings' band over its column, where no header row is: the toolbar's, its background hidden.
        ready(require("nav.settings", "the sidebar lists Settings")).click()
        require("settings.section.playback", timeout: 30, "Settings lists Playback")
        requireDragMovesWindow(from: CGPoint(x: window.frame.maxX - 160, y: band), "Settings' band", exchange)
        shot("settings band dragged")
        // A pushed screen's toolbar (Settings → Playback), past its title.
        ready(require("settings.section.playback", "Settings lists Playback")).click()
        require("settings.playback.seekShort", timeout: 30, "Playback opens over Settings")
        requireDragMovesWindow(from: CGPoint(x: window.frame.maxX - 160, y: band), "Playback's toolbar", exchange)
        goBack()
        require("settings.section.playback", timeout: 30, "Back returns to Settings")
        // A double click on the band over Settings' column (the window's middle: zoomed, its trailing end is where
        // notifications show) zooms the window and another puts it back where it was, and the same on Home's band.
        let bandFromTop = band - window.frame.minY
        requireDoubleClickZoomsAndRestores("Settings' band") {
            window.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: window.frame.width / 2, dy: bandFromTop)).doubleClick()
        }
        ready(require("nav.board", "the sidebar lists Home")).click()
        require("board.row.0.item.0", timeout: 30, "a click on Home shows the Board again")
        let homeFromLeading = home.x - window.frame.minX
        requireDoubleClickZoomsAndRestores("Home's band") {
            window.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: homeFromLeading, dy: bandFromTop)).doubleClick()
        }
        // Detail pushed over Home, past Back on its toolbar's band, over its backdrop.
        activate(requireCard("board.row.0.item.0", "the Board shows its first shelf"))
        require("detail.title", timeout: 60, "a card on the Board opens its Detail over Home")
        requireDoubleClickZoomsAndRestores("Detail's band") {
            window.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: window.frame.width / 2, dy: bandFromTop)).doubleClick()
        }
        // Discover's header row past its last filter: the row's empty part.
        ready(require("nav.discover", "the sidebar lists Discover")).click()
        require("discover.catalog", timeout: 30, "Discover shows its header row")
        let filters = (["discover.type", "discover.catalog"] + (0..<6).map { "discover.filter.\($0)" }).map(element)
        requireDoubleClickZoomsAndRestores("Discover's header row") {
            let rowEnd = filters.filter(\.exists).map(\.frame.maxX).max() ?? window.frame.minX
            window.coordinate(withNormalizedOffset: .zero)
                .withOffset(CGVector(dx: (rowEnd + window.frame.maxX) / 2 - window.frame.minX, dy: bandFromTop)).doubleClick()
        }
        // Controls on the band take their double clicks, the window staying as it is: Discover's catalog menu (drawn by
        // SwiftUI on the row, over its empty part) opens, Search's field selects, and the toolbar's sidebar toggle hides
        // the sidebar (it moves as the sidebar goes: the second click lands on the row's start, not on the toggle).
        let catalog = require("discover.catalog", "Discover shows its catalog menu")
        requireDoubleClickLeavesTheWindow("Discover's catalog menu") { ready(catalog).doubleClick() }
        if element("discover.catalog.option.0").exists(within: 2) { app.typeKey(.escape, modifierFlags: []) }
        ready(require("nav.search", "the sidebar lists Search")).click()
        let field = requireHeaderSearchField("Search shows its field on the band")
        requireDoubleClickLeavesTheWindow("Search's field") { ready(field).doubleClick() }
        // Whatever happens, the sidebar shows again: the window keeps its state for the next launch.
        addTeardownBlock { [self] in
            if sidebarToggle.exists, sidebarToggle.label == Self.showSidebar { app.typeKey("s", modifierFlags: [.command, .control]) }
        }
        requireDoubleClickLeavesTheWindow("the toolbar's sidebar toggle") { ready(sidebarToggle).doubleClick() }
        if sidebarToggle.label == Self.showSidebar { app.typeKey("s", modifierFlags: [.command, .control]) }
        requireSidebar(shown: true, "⌃⌘S after a double click on the toolbar's sidebar toggle")
    }

    /// The Mac is in the dark appearance; the app asked for the light one is what a Mac in light mode gets.
    func testTheChromeStaysDarkInTheLightAppearance() throws {
        launch(["--dev", "-NSRequiresAquaSystemAppearance", "YES"])
        require("nav.settings", timeout: 90, "the sidebar lists Settings")
        screenSettles()
        let window = app.windows.firstMatch.frame
        let band = app.toolbars.firstMatch.frame
        // Home's band holds no title: its middle is the title bar's own background.
        let middle = CGRect(x: window.midX - 40, y: band.minY + 4, width: 80, height: band.height - 8)
        shot("window, light appearance", of: XCUIScreen.main)
        let bar = XCUIScreen.main.screenshot().brightness(in: middle)
        if bar > 80 { fail("asked for the light appearance, the title bar is \(Int(bar)) bright, not dark") }
        let view = app.menuBars.menuBarItems["View"]
        ready(view).click()
        let menu = view.menus.firstMatch
        if !menu.exists(within: 5) { fail("the View menu doesn't open") }
        shot("View menu, light appearance", of: XCUIScreen.main)
        // The menu is translucent: what lies behind it (another app's window, the desktop) shows through and sets its
        // brightness. Its own pixels say which appearance it has: light text on its ground in the dark one, dark text
        // on it in the light one, wherever it is.
        let shotOfMenu = XCUIScreen.main.screenshot()
        let word = shotOfMenu.frame(ofWord: "Discover", in: menu.frame)
        app.typeKey(XCUIKeyboardKey.escape, modifierFlags: [])
        guard let word else { return fail("Vision reads no Discover in the View menu at \(menu.frame)") }
        let text = shotOfMenu.brightness(in: word)
        let ground = shotOfMenu.brightness(in: CGRect(x: word.maxX + 6, y: word.minY, width: 24, height: word.height))
        if text <= ground {
            fail("asked for the light appearance, the View menu's Discover is \(Int(text)) bright on its \(Int(ground)) ground: dark text, a light menu")
        }
    }

    /// The App menu lists Settings…; outside the player every Playback item is disabled, and in it Playback's first
    /// item pauses the video. ⌘, with the player up leaves it up and Settings unshown; once the player has closed, ⌘,
    /// shows Settings.
    func testSettingsCommandAndThePlaybackMenu() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        let row = require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")
        let appMenu = app.menuBars.menuBarItems.element(boundBy: 1)
        ready(appMenu).click()
        if !appMenu.menuItems["Settings…"].exists(within: 5) { fail("the App menu \(appMenu.title) lists no Settings…") }
        app.typeKey(.escape, modifierFlags: [])
        let playback = app.menuBars.menuBarItems["Playback"]
        ready(playback).click()
        shot("playback menu outside the player")
        let enabled = playback.menuItems.allElementsBoundByIndex.filter { $0.isEnabled && !$0.title.isEmpty }.map(\.title)
        app.typeKey(.escape, modifierFlags: [])
        if !enabled.isEmpty { fail("outside the player the Playback menu has \(enabled) enabled") }

        ready(row).click()
        require("player.video", "a click on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        ready(playback).click()
        let playPause = playback.menuItems.element(boundBy: 0)
        if !waitUntil(5, { playPause.exists && playPause.isEnabled }) { fail("in the player Playback's first item is disabled") }
        shot("playback menu in the player")
        pickMenuEntry(playPause)
        if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Play" }) {
            fail("Playback's \(playPause.title) doesn't pause: play/pause reads \(element("player.playPause").label)")
        }
        app.typeKey(",", modifierFlags: .command)
        if !holds(for: 3, { element("player.video").exists && !element("settings.section.playback").exists }) {
            fail("⌘, with the player up closed it or showed Settings")
        }
        // Escape backs out as the store's Back does: the controls first while they are up, then the player.
        for _ in 0..<3 where element("player.video").exists {
            app.typeKey(.escape, modifierFlags: [])
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Escape closes the player")
        app.typeKey(",", modifierFlags: .command)
        require("settings.section.playback", timeout: 10, "⌘, once the player has closed shows Settings")
    }

    /// With another app in front, the toolbar's band over the window's content is one shade from the column in the
    /// middle of the window (`column`, a control at its rows' trailing end) to beside the column on its trailing side:
    /// no box over the centred column (`windowToolbarBand`). Short of the window's trailing end, where the system's
    /// notification banners show over it. Fails with `what`.
    private func requireEvenToolbarWhileInactive(_ what: String, column id: String) {
        let window = app.windows.firstMatch.frame
        let band = app.toolbars.firstMatch.frame.insetBy(dx: 0, dy: 8)
        let column = require(id, what).frame
        let frame = inactiveScreen(what)
        let middle = frame.brightness(in: CGRect(x: window.midX - 40, y: band.minY, width: 80, height: band.height))
        let beside = frame.brightness(in: CGRect(x: column.maxX + 16, y: band.minY, width: 20, height: band.height))
        if abs(middle - beside) > 3 {
            fail("\(what): with the window inactive the toolbar is \(Int(middle)) bright over it and \(Int(beside)) beside it")
        }
    }

    /// The pointer from the toolbar's band down into the screen under it, `pointerRounds` times, sampled from a thread
    /// of its own: no frame of the screen (right of the sidebar, under the band) darker than the others by more than a
    /// little, once its images have loaded. Fails with `what`.
    private func requireLitWhilePointerComesDown(_ what: String) {
        let window = app.windows.firstMatch
        let toolbar = app.toolbars.firstMatch.frame
        let start = require("nav.board", timeout: 5, "the sidebar lists Home").frame.maxX + 24
        let screen = CGRect(x: start, y: toolbar.maxY + 8, width: window.frame.maxX - 8 - start, height: window.frame.maxY - toolbar.maxY - 16)
        // The images have loaded once two looks half a second apart are alike.
        var last = -1.0
        _ = waitUntil(20) {
            let level = XCUIScreen.main.screenshot().brightness(in: screen)
            defer { last = level }
            return abs(level - last) < 0.5
        }
        let point = { (y: Double) in window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: y)) }
        for round in 1...Self.pointerRounds {
            point(0.03).hover()
            screenSettles()
            let sampler = ScreenSampler()
            sampler.start()
            // A few frames before the pointer moves, and about a second's after (ten a second).
            _ = waitUntil(5) { sampler.count >= 3 }
            point(0.15).hover()
            point(0.6).hover()
            let moved = sampler.count
            _ = waitUntil(5) { sampler.count >= moved + 10 }
            let frames = sampler.stop()
            let levels = frames.map { $0.brightness(in: screen) }
            guard let darkest = levels.indices.min(by: { levels[$0] < levels[$1] }) else { return fail("\(what): no frames sampled") }
            let median = levels.sorted()[levels.count / 2]
            if levels[darkest] < median * 0.85 {
                let attachment = XCTAttachment(screenshot: frames[darkest])
                attachment.name = "darkened under the pointer"
                attachment.lifetime = .keepAlways
                add(attachment)
                let trace = levels.map { String(Int($0.rounded())) }.joined(separator: " ")
                fail("\(what): the pointer coming down from the toolbar darkened it to \(Int(levels[darkest])) from \(Int(median)) (round \(round), frames \(trace))")
            }
        }
    }

    /// Back from Playback, sampled: a frame showing a title lasts about one sampling interval, so a few rounds catch it.
    private static let backRounds = 5

    /// The pointer down from the toolbar, sampled: a darkening lasts about three sampling intervals.
    private static let pointerRounds = 3

    /// The destinations whose screens the pointer comes down into, each with what its screen shows once there.
    private static let litScreens = [
        ("nav.board", "board.row.0.item.0"), ("nav.settings", "settings.section.playback"), ("nav.discover", "discover.item.0"),
        ("nav.library", "library.view"),
    ]

    /// The first control of a destination's header row.
    private static let firstHeaderControl = ["nav.discover": "discover.catalog", "nav.search": "search.field", "nav.library": "library.view"]

    /// The toolbar holds navigation only: the sidebar's toggle, and on a `pushed` screen Back; no field, menu or control
    /// of the screen's own.
    private func requireOnlyNavigationInToolbar(_ what: String, pushed: Bool = false) {
        let toolbar = app.toolbars
        let own = toolbar.descendants(matching: .any).matching(
            NSPredicate(format: "identifier BEGINSWITH 'board.' OR identifier BEGINSWITH 'discover.' OR identifier BEGINSWITH 'library.' OR identifier BEGINSWITH 'search.' OR identifier BEGINSWITH 'downloads.' OR identifier BEGINSWITH 'addons.' OR identifier BEGINSWITH 'watch.'")
        )
        if own.count > 0 { fail("\(what): the toolbar holds the screen's \(own.firstMatch.identifier)") }
        if toolbar.searchFields.count + toolbar.textFields.count > 0 { fail("\(what): the toolbar holds a field") }
        if toolbar.menuButtons.count + toolbar.popUpButtons.count > 0 { fail("\(what): the toolbar holds a menu") }
        let expected = pushed ? [Self.hideSidebar, "Back"] : [Self.hideSidebar]
        let buttons = toolbar.buttons.allElementsBoundByIndex.map(\.label)
        if buttons != expected { fail("\(what): the toolbar holds \(buttons), not \(expected)") }
    }

    private static let hideSidebar = "Hide Sidebar"
    private static let showSidebar = "Show Sidebar"

    /// The toolbar's sidebar toggle, the system's.
    private var sidebarToggle: XCUIElement {
        app.toolbars.buttons.matching(NSPredicate(format: "label IN %@", [Self.hideSidebar, Self.showSidebar])).firstMatch
    }

    /// The toolbar's Back.
    private var toolbarBack: XCUIElement {
        app.toolbars.buttons.matching(NSPredicate(format: "label == 'Back'")).firstMatch
    }

    /// The View menu's Show Sidebar or Hide Sidebar (the system's `toggleSidebar:`, whose title the menu shows as the
    /// sidebar is; the accessibility title can lag behind it), the menu open.
    private func viewMenuSidebarItem() -> XCUIElement {
        let view = app.menuBars.menuBarItems["View"]
        ready(view).click()
        let item = view.menuItems["toggleSidebar:"]
        if !item.exists(within: 5) { fail("the View menu has no Show Sidebar: \(view.menuItems.allElementsBoundByIndex.map(\.title))") }
        return item
    }

    /// Waits for the sidebar to show (`shown`) or to be hidden after `what`: its destinations clickable or not, and the
    /// toolbar's toggle offering the opposite.
    private func requireSidebar(shown: Bool, _ what: String) {
        let home = element("nav.board")
        let label = shown ? Self.hideSidebar : Self.showSidebar
        if !waitUntil(5, { sidebarToggle.exists && sidebarToggle.label == label && (home.exists && home.isHittable) == shown }) {
            fail("\(what): the sidebar is \(shown ? "still hidden" : "still shown") (the toggle reads \"\(sidebarToggle.label)\")")
        }
    }

    /// Drags the window from `start` (screen points) on `what` by 80 pt and 60 pt toward the screen's roomier sides,
    /// through the run's hook, waiting for the window to follow, then back from where the drag let go. The window
    /// stays within the screen's area for windows (a drag stops it at the Dock), hence the sides with room. A title
    /// bar's drag starts from the first move after the press, so the window may end a step of the drag (the hook's
    /// steps are 2 pt) or two short of it.
    private func requireDragMovesWindow(from start: CGPoint, _ what: String, _ exchange: URL) {
        let window = app.windows.firstMatch
        let frame = settledWindowFrame()
        let area = windowArea
        let (left, right) = (frame.minX - area.minX, area.maxX - frame.maxX)
        let (up, down) = (frame.minY - area.minY, area.maxY - frame.maxY)
        let offset = CGVector(dx: right >= left ? 80 : -80, dy: down >= up ? 60 : -60)
        if max(left, right) < 80 || max(up, down) < 60 {
            fail("the window (\(frame)) has no room to be dragged by 80 by 60 pt within the screen's area for windows (\(area))")
        }
        let back = CGPoint(x: start.x + offset.dx, y: start.y + offset.dy)
        for (from, by) in [(start, offset), (back, CGVector(dx: -offset.dx, dy: -offset.dy))] {
            let before = settledWindowFrame().origin
            Self.windowDrags += 1
            publish("drag-\(Self.windowDrags)", code: "\(Int(from.x)) \(Int(from.y)) \(Int(from.x + by.dx)) \(Int(from.y + by.dy))", to: exchange)
            let moved = waitUntil(20) {
                abs(window.frame.minX - (before.x + by.dx)) <= 5 && abs(window.frame.minY - (before.y + by.dy)) <= 5
            }
            if !moved {
                fail("a drag on \(what) from (\(Int(from.x)), \(Int(from.y))) by (\(Int(by.dx)), \(Int(by.dy))) doesn't move the window with it: it is at \(window.frame.origin), from \(before)")
            }
        }
    }

    /// The main display's area for windows, without the menu bar and the Dock, in the coordinates of XCUITest's frames
    /// (points from the main display's top left).
    private var windowArea: CGRect {
        guard let screen = NSScreen.screens.first else { return .zero }
        let visible = screen.visibleFrame
        return CGRect(x: visible.minX, y: screen.frame.maxY - visible.maxY, width: visible.width, height: visible.height)
    }

    /// The window's frame once it stops changing (a zoom animates): the same for half a second, within 5 s.
    private func settledWindowFrame() -> CGRect {
        let window = app.windows.firstMatch
        var last = window.frame
        var since = Date()
        _ = waitUntil(5) {
            let now = window.frame
            if now != last { (last, since) = (now, Date()) }
            return Date().timeIntervalSince(since) >= 0.5
        }
        return last
    }

    /// A double click (`doubleClick`) on `what` zooms the window, and another puts it back exactly where it was.
    private func requireDoubleClickZoomsAndRestores(_ what: String, _ doubleClick: () -> Void) {
        let before = settledWindowFrame()
        doubleClick()
        let zoomed = settledWindowFrame()
        if zoomed.size == before.size { fail("a double click on \(what) doesn't zoom the window: it stays \(zoomed)") }
        doubleClick()
        let restored = settledWindowFrame()
        if restored != before {
            fail("a second double click on \(what) puts the window at \(restored), not back at \(before) (zoomed: \(zoomed))")
        }
    }

    /// A double click (`doubleClick`) on `what`, a control on the band, is the control's: the window stays where it is,
    /// as large as it was.
    private func requireDoubleClickLeavesTheWindow(_ what: String, _ doubleClick: () -> Void) {
        let before = settledWindowFrame()
        doubleClick()
        let after = settledWindowFrame()
        if after != before { fail("a double click on \(what) moves or zooms the window: it is at \(after), from \(before)") }
    }
}
