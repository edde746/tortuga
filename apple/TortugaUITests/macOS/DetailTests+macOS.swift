import AppKit
import XCTest

/// Detail with the pointer alone (the tests iOS has too are in Pointer/DetailTests.swift):
/// - the user's own rating (`testRateAndRemoveARating`, UserRatingTestSupport.swift): The Shawshank Redemption unrated,
///   no score of the user's own in the ratings row; ⋯'s "Rate this title" opens the picker in a sheet, a line for each
///   of the three trackers, no star lit; a click on the 4th star's right half gives 8: the fake holds 8 on all three,
///   the row's score reads 8, four stars lit; Close; Remove rating in the picker opened again from ⋯'s "Your rating:
///   8": the fake holding none, the row's score gone, no star lit, ⋯'s entry "Rate this title" again (the Mac's
///   XCUITest reads no value of the lines or the stars, so their words aren't checked here);
/// - the test addon's series left at 12 s of its first episode: the episode's right-click menu offers Play from the
///   beginning above Mark as watched and, auto-play off, no Choose a stream; Play from the beginning, no stream played
///   before, opens the episode's streams, whose pick plays from 0; with auto-play on (First match, set in Settings, the
///   session launched again on the series), the menu offers Choose a stream, which opens the episode's streams;
/// - the series' round buttons (+, watched, the download's menu, the trailer, Share, those the window has room for;
///   Share behind ⋯ otherwise): one size, one fill and one symbol colour, the buttons and the menus alike, in an active
///   window and an inactive one;
/// - the top of the details on the test addon's hero titles (`testTheMetadataLineSitsUnderTheTitle`,
///   DetailHeroSupport.swift), in the window at its first size and in one narrowed to 760 pt (the narrow page);
/// - the sidebar over the backdrop of the test addon's movie without a logo (the bright frame: an overcast sky's
///   whites), in the window at its first size and narrowed to 760 pt (`testTheSidebarReadsOverTheBackdrop`);
/// - a season row longer than the episodes' column, scrolled to its end, draws nothing over the details in the window
///   at its first size (`testTheSeasonRowStaysInItsColumn`);
/// - full screen as the window: the bright backdrop under the toolbar's back button and sidebar toggle, both hittable,
///   and Home's band as black as in the window (`testFullScreenRunsTheArtUpUnderTheToolbar`).
extension DetailTests {
    func testRoundButtonsShareOneLook() throws {
        try launchDev(addon: true, open: ["board", "detail:series:tt7366338"])
        require("detail.play", timeout: 60, "the series' details show Play")
        require("detail.library", "the action row has the library button")
        require("detail.download", "the series' action row has the download menu")
        // The glass shows the backdrop behind it: drawn first; then the button's press effect settles.
        requireArtDrawn("the series' details")
        screenSettles()
        let ids = ["detail.library", "detail.watched", "detail.download", "detail.trailer", "detail.share"].filter { element($0).exists }
        if !ids.contains("detail.share") {
            // A narrow window puts Share behind ⋯.
            ready(require("detail.overflow", "the series' action row has neither Share nor ⋯")).click()
            require("detail.overflow.share", timeout: 5, "⋯ lists Share for Cinemeta's share link")
            shot("share in more")
            app.typeKey(.escape, modifierFlags: [])
        }
        shot("round buttons")
        requireOneLook(ids, "in the active window")
        withAnotherAppInFront { XCUIApplication(bundleIdentifier: "com.apple.finder").activate() }
        // Finder in front, the window redraws inactive.
        if waitUntil(10, { app.state == .runningBackground }) { screenSettles(app.windows.firstMatch) }
        shot("round buttons, window inactive", of: app.windows.firstMatch)
        requireOneLook(ids, "in an inactive window")
        app.activate()
    }

    func testRateAndRemoveARating() throws {
        try seedFakeRatings()
        try launchDev(trackersConnected: true, open: ["board", Self.rateTitle])
        require("detail.play", timeout: 60, "The Shawshank Redemption's details show Play")
        openRatingPicker("the unrated title", label: "Rate this title", timeout: 60)
        requireNoRatingItem("an unrated title")
        requireRatingLines(Self.lines("Not rated"), "an unrated title's picker")
        requireStars(nil, "an unrated title's picker")
        shot("rate, picker")

        giveScore(8)
        requireFakeRatings(Self.rateImdb, ["trakt": [8], "simkl": [8], "mdblist": [8]], "8 given")
        requireRatingItem("Your rating: 8", "the item, under the picker, once 8 is given")
        requireRatingLines(Self.lines("Saved"), "8 given")
        requireStars(8, "8 given")
        require("detail.userRating.clear", timeout: 10, "Remove rating once there is a score")
        shot("rate, 8 saved")
        closeRatingPicker("8 given")
        requireRatingItem("Your rating: 8", "the rated title's item")
        shot("rate, rated")

        openRatingPicker("the rated title", label: "Your rating: 8")
        ready(require("detail.userRating.clear", "Remove rating once there is a score")).click()
        requireFakeRatings(Self.rateImdb, ["trakt": [], "simkl": [], "mdblist": []], "Remove rating")
        requireGone("detail.userRating", "the row's score, under the picker, after Remove rating")
        requireGone("detail.userRating.clear", "Remove rating goes with the score")
        requireRatingLines(Self.lines("Removed"), "Remove rating")
        requireStars(nil, "Remove rating")
        shot("rate, removed")
        closeRatingPicker("Remove rating")
        requireRateAction("Rate this title", "⋯'s Rate after Remove rating")
    }

    func testTheMetadataLineSitsUnderTheTitle() throws {
        try requireHeroTitles("wide window", narrow: false) { try launchDev(addon: true, open: ["board", $0]) }
        try requireHeroTitles("narrow window", narrow: true, logoLate: false) { screen in
            try launchDev(addon: true, open: ["board", screen])
            require("detail.play", timeout: 60, "narrow window: the details show Play before the window narrows")
            narrowWindow(to: 760)
        }
    }

    /// The backdrop runs under the sidebar to the window's leading edge, dimmed there, the sidebar drawn over it, in the
    /// window as it opens (the backdrop behind the whole window) and narrowed by its corner to the one-column page (the
    /// backdrop across its top): the details start beside the sidebar, each of the sidebar's destinations stands out
    /// from what is behind it by at least 4.5:1 (WCAG's contrast for text), and in the narrowed window the sidebar is
    /// at least 30 % brighter beside the picture than beside the black page below it. The sidebar's glass lets little
    /// through: on macOS 27 the picture dimmed under it reads 1.47 times the page, and a picture that stops at the
    /// sidebar's edge 1.12 times (what the glass blurs in from beyond its edge). The readings go to the log.
    func testTheSidebarReadsOverTheBackdrop() throws {
        try launchDev(addon: true, open: ["board", "detail:movie:tortuga-hero-plain"])
        require("detail.title", timeout: 60, "the bright-backdrop movie's details show")
        let window = app.windows.firstMatch
        requireArtDrawn("in the window as it opens")
        // Nothing darkens the backdrop at the window's trailing top.
        let opened = window.frame
        let trailingTop = CGRect(x: opened.maxX - 120, y: opened.minY + 80, width: 80, height: 80)
        if (ScreenPixels()?.medianLuminance(in: trailingTop) ?? 0) <= 0.5 {
            fail("the bright backdrop doesn't show at the window's trailing top")
        }
        shot("bright backdrop under the sidebar")
        requireSidebarOverTheBackdrop("in the window as it opens")

        narrowWindow(to: 760)
        requireArtDrawn("in the narrowed window")
        shot("bright backdrop under the sidebar, narrowed window")
        requireSidebarOverTheBackdrop("in the narrowed window")
        let frame = window.frame
        let home = element("nav.board").frame
        // A strip in the sidebar's middle, left of Home's trailing edge, clear of the labels and of the glass's edge
        // (which blurs in the picture beyond it): below Home's row down to the metadata line, where the picture is; and
        // as far below the picture, over the black page.
        let top = home.maxY + 4
        let beside = CGRect(x: home.maxX - 40, y: top, width: 32, height: max(require("detail.infoLine", timeout: 5, "the narrowed details show the metadata line").frame.minY - top, 8))
        let below = CGRect(x: beside.minX, y: frame.maxY - 150, width: beside.width, height: 50)
        guard let screen = ScreenPixels() else { return fail("no screenshot") }
        let (picture, page) = (screen.medianLuminance(in: beside), screen.medianLuminance(in: below))
        print("sidebar in the narrowed window: luminance \(String(format: "%.4f", picture)) beside the picture \(beside), \(String(format: "%.4f", page)) beside the page \(below)")
        if picture < page * 1.3 {
            fail("in the narrowed window the picture doesn't show under the sidebar: luminance \(picture) beside it, \(page) beside the black page")
        }
    }

    /// The window at its first size (the wide page): the test addon's series of 24 seasons, its season row scrolled to
    /// its last chip, draws no chip left of the episodes' column, over the details (`requireSeasonRowInItsColumn`).
    func testTheSeasonRowStaysInItsColumn() throws {
        try launchDev(addon: true, open: ["board", Self.longSeasonRowTitle])
        requireSeasonRowInItsColumn("in the window as it opens")
    }

    /// Full screen looks as the window does (`FullScreenTitleBar`): the bright-backdrop movie's backdrop runs up to the
    /// window's top, the toolbar's back button and sidebar toggle over it, so beside them and just under them it shows
    /// the backdrop, not a band of the toolbar's own, both still hittable, and the sidebar's Home sits as far down from
    /// the window's top as in the window; back on Home, the band reads as Home's black does in the window. Home and the
    /// details are kept as shots, in the window and full screen.
    func testFullScreenRunsTheArtUpUnderTheToolbar() throws {
        addTeardownBlock { [weak self] in
            guard let self, self.app.state == .runningForeground, self.isFullScreen else { return }
            self.app.typeKey("f", modifierFlags: .function)
        }
        try launchDev(addon: true, open: ["board", "detail:movie:tortuga-hero-plain"])
        require("detail.title", timeout: 60, "the bright-backdrop movie's details show")
        requireArtDrawn("the movie's details in the window")
        screenSettles()
        shot("details, window", of: XCUIScreen.main)
        let windowed = requireArtUnderTheToolbar("in the window")

        ready(app.menuBars.menuBarItems["View"]).click()
        pickMenuEntry(app.menuBars.menuItems["Enter Full Screen"])
        requireFullScreen(true, "the View menu's Enter Full Screen on the details")
        screenSettles()
        shot("details, full screen", of: XCUIScreen.main)
        let full = requireArtUnderTheToolbar("in full screen")
        if abs(full - windowed) > 2 {
            fail("in full screen the sidebar's Home sits \(Int(full)) pt below the window's top, in the window \(Int(windowed)) pt")
        }

        goBack()
        require("board.shortcuts", timeout: 10, "Back returns to Home")
        screenSettles()
        shot("Home, full screen", of: XCUIScreen.main)
        let fullHome = bandLuminance("Home in full screen")
        // Fn-F, the View menu's Exit Full Screen: the menu bar hides in full screen.
        app.typeKey("f", modifierFlags: .function)
        requireFullScreen(false, "Fn-F on Home in full screen")
        screenSettles()
        shot("Home, window", of: XCUIScreen.main)
        let windowHome = bandLuminance("Home in the window")
        if abs(fullHome - windowHome) > 0.005 {
            fail("in full screen Home's toolbar band reads luminance \(String(format: "%.4f", fullHome)), in the window \(String(format: "%.4f", windowHome)): a band of the toolbar's own")
        }
    }

    /// The toolbar's back button and sidebar toggle are hittable, over the bright backdrop: on their band and just under
    /// them, towards the window's trailing edge, the screen reads bright (the bright frame's whites), as the window's
    /// trailing top does (`testTheSidebarReadsOverTheBackdrop`), not as a band of the toolbar's own (a uniform dark
    /// grey). Fails with `what`; returns how far below the window's top the sidebar's Home sits.
    private func requireArtUnderTheToolbar(_ what: String) -> CGFloat {
        let back = app.toolbars.buttons.matching(NSPredicate(format: "label == 'Back' OR identifier == 'Back'")).firstMatch
        for (control, name) in [(back, "back button"), (sidebarToggle, "sidebar toggle")] where !(control.exists(within: 5) && control.isHittable) {
            fail("\(what): the toolbar's \(name) isn't hittable")
        }
        let home = require("nav.board", timeout: 5, "\(what): the sidebar shows Home").frame
        let luminance = bandLuminance(what)
        if luminance <= 0.5 {
            fail("\(what): on the toolbar's band and just under it the screen reads luminance \(String(format: "%.4f", luminance)), not the bright backdrop")
        }
        return home.minY - app.windows.firstMatch.frame.minY
    }

    /// The median luminance from the middle of the toolbar's sidebar toggle to a few points under it, over the window's
    /// trailing 360 pt, where no toolbar item is; logged with the frames it was read from.
    private func bandLuminance(_ what: String) -> Double {
        let window = app.windows.firstMatch.frame
        let toggle = sidebarToggle.frame
        let strip = CGRect(x: window.maxX - 400, y: toggle.midY, width: 360, height: toggle.height / 2 + 6)
        let luminance = ScreenPixels()?.medianLuminance(in: strip) ?? 0
        print("\(what): luminance \(String(format: "%.4f", luminance)) in \(strip); window \(window), sidebar toggle \(toggle), screen \(NSScreen.screens.first.map { "\($0.frame), safe area \($0.safeAreaInsets)" } ?? "none")")
        return luminance
    }

    /// The toolbar's sidebar toggle, the system's.
    private var sidebarToggle: XCUIElement {
        app.toolbars.buttons.matching(NSPredicate(format: "label IN %@", ["Hide Sidebar", "Show Sidebar"])).firstMatch
    }

    /// Drags the window's bottom-right corner, once the screen is still, until the window is about `width` points wide
    /// (it narrows to 680 pt). The window falls behind a synthesized drag by what the steps the app was busy for missed,
    /// and the drag ends where the pointer stops, nothing moving it after: dragged while the app still opened Detail,
    /// the window stopped up to 46 pt short (806 pt for 760); with the page shown and still, at most 13 pt.
    private func narrowWindow(to width: CGFloat) {
        screenSettles()
        let window = app.windows.firstMatch
        let before = window.frame
        let corner = window.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 1)).withOffset(CGVector(dx: -1, dy: -1))
        corner.click(forDuration: 0.3, thenDragTo: corner.withOffset(CGVector(dx: width - before.width, dy: 0)))
        if !waitUntil(5, { abs(window.frame.width - width) < 40 }) {
            fail("dragging the window's corner leaves it at \(window.frame), not \(width) pt wide")
        }
        // The page lays out again at its new width.
        screenSettles()
    }

    func testEpisodeInProgressPlaysFromTheStartAndChoosesAStream() throws {
        // Two launches, a trip to Settings and a play.
        executionTimeAllowance = 600
        let data = freshDataName()
        let addon = try addonURL
        launchDev(keepingData: data, addon: addon, open: ["board", StreamFilterTexts.series], progress: [Self.seriesEpisodeInProgress])
        require("detail.play", timeout: 60, "the series' details show Play")
        ready(scrollTo("detail.episode.0", "the series lists its first episode")).rightClick()
        let fromStart = require("detail.menu.item.playFromStart", timeout: 5, "a right click on the episode in progress offers Play from the beginning")
        let watched = require("detail.menu.item.markWatched", timeout: 5, "the episode's menu offers Mark as watched")
        if fromStart.frame.minY >= watched.frame.minY { fail("Play from the beginning isn't above Mark as watched in the episode's menu") }
        if element("detail.menu.item.chooseStream").exists { fail("with auto-play off, the episode's menu offers Choose a stream") }
        shot("episode in progress menu")
        pickMenuEntry(fromStart)
        ready(require("streams.row.0", timeout: 60, "Play from the beginning (auto-play off, no stream played before) opens the episode's streams")).click()
        let video = require("player.video", timeout: 20, "a click on the stream opens the player")
        requirePlayingFromTheStart("Play from the beginning in the episode's menu")
        shot("episode played from the beginning")
        pointerOn(ready(video), at: CGVector(dx: 0.3, dy: 0.4))
        pointerOn(ready(video), at: CGVector(dx: 0.5, dy: 0.5))
        ready(require("player.back", timeout: 5, "the pointer over the video shows the controls")).click()
        requireGone("player.video", "the back button closes the player")
        require("detail.play", "the back button returns to the series' details")

        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.firstMatch)
        launchDev(keepingData: data, addon: addon, open: ["board", StreamFilterTexts.series], progress: [Self.seriesEpisodeInProgress])
        require("detail.play", timeout: 60, "the series' details show Play")
        ready(scrollTo("detail.episode.0", "the series lists its first episode")).rightClick()
        let choose = require("detail.menu.item.chooseStream", timeout: 5, "with auto-play on, the episode's menu offers Choose a stream")
        shot("episode menu with auto-play on")
        pickMenuEntry(choose)
        require("streams.row.0", timeout: 60, "Choose a stream in the episode's menu opens its streams")
        if element("player.video").exists { fail("Choose a stream in the episode's menu played auto-play's pick, not the episode's streams") }
        shot("choose a stream from the episode")
    }

    /// The round buttons `ids` look alike: one size, and from each one's snapshot one fill (the circle's ring outside
    /// the symbol) and one symbol colour (the symbol's brightest part), within what the backdrop behind the glass moves
    /// them. Fails with `what` and the readings.
    private func requireOneLook(_ ids: [String], _ what: String) {
        let looks = ids.map { (id: $0, frame: element($0).frame, look: RoundLook(element($0).screenshot())) }
        let readings = looks.map { "\($0.id) \(Int($0.frame.width))×\(Int($0.frame.height)) fill \($0.look.fill) symbol \($0.look.symbol)" }
        print("round buttons \(what): \(readings.joined(separator: "; "))")
        guard let first = looks.first else { return fail("\(what): no round buttons") }
        for other in looks.dropFirst() {
            if abs(other.frame.width - first.frame.width) > 1 || abs(other.frame.height - first.frame.height) > 1 {
                fail("\(what): \(other.id) is \(other.frame.size), \(first.id) \(first.frame.size): \(readings)")
            }
            if abs(other.look.fill - first.look.fill) > Self.fillTolerance {
                fail("\(what): \(other.id)'s fill (\(other.look.fill)) isn't \(first.id)'s (\(first.look.fill)): \(readings)")
            }
            if abs(other.look.symbol - first.look.symbol) > Self.symbolTolerance {
                fail("\(what): \(other.id)'s symbol (\(other.look.symbol)) isn't \(first.id)'s colour (\(first.look.symbol)): \(readings)")
            }
        }
    }

    /// How far apart two round buttons' fills and symbols may read (luminance, 0–255). The glass shows a little of the
    /// backdrop behind each: one look's fills read up to 15 apart (Chernobyl's and Frieren's, active and inactive),
    /// its symbols alike. The symbol tells the solid circles from the glass ones: 30 apart in an active window, 110 in
    /// an inactive one; their fills, over a dark backdrop, no further apart than the glass circles'.
    private static let fillTolerance = 20
    private static let symbolTolerance = 12
}

/// A round button's look from its snapshot: the luminance (0–255) of its fill, the median of a ring inside the circle
/// clear of the symbol, and of its symbol, the brightest part of the circle's middle.
private struct RoundLook {
    let fill: Int
    let symbol: Int

    init(_ screenshot: XCUIScreenshot) {
        guard let pixels = ScreenPixels(screenshot) else {
            fill = -1000
            symbol = -1000
            return
        }
        let width = pixels.width, height = pixels.height
        func luminance(_ x: Int, _ y: Int) -> Int {
            let (red, green, blue) = pixels.colour(x: x, y: y)
            return (Int(red) * 299 + Int(green) * 587 + Int(blue) * 114) / 1000
        }
        let centreX = Double(width) / 2, centreY = Double(height) / 2, radius = Double(min(width, height)) / 2
        var ring: [Int] = []
        var brightest = 0
        for y in 0..<height {
            for x in 0..<width {
                let distance = ((Double(x) + 0.5 - centreX) * (Double(x) + 0.5 - centreX) + (Double(y) + 0.5 - centreY) * (Double(y) + 0.5 - centreY)).squareRoot() / radius
                if distance > 0.78, distance < 0.9 { ring.append(luminance(x, y)) }
                if distance < 0.4 { brightest = max(brightest, luminance(x, y)) }
            }
        }
        ring.sort()
        fill = ring.isEmpty ? -1000 : ring[ring.count / 2]
        symbol = brightest
    }
}
