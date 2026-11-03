import XCTest

/// The iPhone Duo's screens laid out for what shows of them (iOS 27.1, `pose`: the test hook folds and turns the
/// simulator; each test puts it back in the run's pose, `poseBack`). Skipped where the phone doesn't fold.
/// - On the cover in portrait, the vertical bar (the status bar, the back button and the tab bar in a column at the
///   right) beside it, a movie's action row centres on what shows of the page left of the bar, also under a backdrop
///   that runs under the bar.
/// - On the cover the player's controls, over which no bar shows, centre on the window, its top bar at the window's
///   top in landscape, and sheets keep no empty strip for the bar: the playback info's rows over the player and the
///   Streams sheet's title over the shell, beside the bar, centre on the window.
/// - On the cover in landscape a movie's details show Play whole on their first screen, under a hero no taller than
///   half the window.
/// - On the cover in portrait Addons' cards line up with the type filter's chip, short of the bar; the splash's loader
///   sits in the middle of the screen.
/// - On the cover in landscape Addons keeps the bar's five tabs whole, its Install in the page's header row, and
///   Install opens the install sheet: an extra button in the bar folds the tabs into the selected one.
/// - On the cover in landscape the pages beside the bar (Home, Discover, Library, Addons, a series' Detail) start their
///   content as far past the bar's column as Settings' form starts its cards.
/// - On the inner screen, regular width as on iPad, the Streams sheet has Close; in landscape (951 pt) a series'
///   details show the one-column page, as on the cover.
/// - On the inner screen in portrait the subtitle style's preview sits above the form, which beside it got 270 pt.
final class DuoLayoutTests: TortugaUITestCase {
    /// The test addon's chapters movie (tools/media/test_addon.py): streams that play.
    private let movie = "tortuga-chapters-movie"
    /// The test addon's movie with a tall backdrop and a logo (`DetailHeroSupport`).
    private let heroMovie = "tortuga-hero-tall"

    override func setUpWithError() throws {
        try super.setUpWithError()
        try XCTSkipUnless(isFoldable, "only a foldable iPhone has the vertical bar and two screens")
    }

    override func tearDownWithError() throws {
        if isFoldable { poseBack() }
        try super.tearDownWithError()
    }

    func testCoverPortraitDetailsCentreLeftOfTheBar() throws {
        pose("closed")
        pose("portrait")
        try launchDev(addon: true, open: ["board", "detail:movie:\(heroMovie)"])
        require("detail.play", timeout: 60, "the movie's details show Play")
        let bar = backButton
        if !bar.waitForExistence(timeout: 10) { return fail("the details have no back button in the vertical bar") }
        // The page laid out, its hero and the bar in place.
        screenSettles(appScreen)
        let barEdge = bar.frame.minX
        let row = ["detail.play", "detail.library", "detail.watched", "detail.download", "detail.trailer", "detail.share", "detail.overflow"]
            .map(element).filter(\.exists).map(\.frame)
        let left = row.map(\.minX).min() ?? 0, right = row.map(\.maxX).max() ?? 0
        shot("cover portrait, details")
        if right > barEdge || abs((left + right) / 2 - barEdge / 2) > 12 {
            fail("the action row spans x \(left)–\(right): not centred left of the vertical bar from x \(barEdge)")
        }
    }

    /// The player turns the cover to landscape as it opens.
    func testCoverPlayerCentresWithoutTheBar() throws {
        pose("closed")
        pose("portrait")
        try launchDev(addon: true, open: ["board", "detail:movie:\(movie)"])
        let play = require("detail.play", timeout: 60, "the movie's details show Play")
        activate(play)
        activate(require("streams.row.0", timeout: 60, "Play opens the Streams sheet"))
        let video = require("player.video", timeout: 20, "the stream opens the player")
        requirePlayback(1, timeout: 90)
        showPlayerControls(video, "a tap brings the player's controls up")
        let window = windowFrame
        let controls = require("player.bar.bottom", "the player shows its bottom bar")
        if abs(controls.frame.midX - window.midX) > 2 {
            fail("the player's bottom bar at \(controls.frame) isn't centred in the window at \(window)")
        }
        let top = require("player.bar.top", "the player shows its top bar")
        if window.width > window.height, top.frame.minY - window.minY > 12 {
            fail("the player's top bar starts at y \(top.frame.minY), not at the top of the window at \(window)")
        }
        shot("cover, player")

        togglePlaybackInfo()
        require("player.info.panel", timeout: 5, "the options menu opens the playback info")
        requireBothSides("player.info.row", "the playback info's first row")
        shot("cover, playback info")
    }

    /// A sheet over the shell, whose vertical bar shows beside the page under it, in portrait at the right.
    func testCoverStreamsSheetHasNoStrip() throws {
        pose("closed")
        pose("portrait")
        try launchDev(addon: true, open: ["board", "detail:movie:\(movie)"])
        activate(require("detail.play", timeout: 60, "the movie's details show Play"))
        require("streams.row.0", timeout: 60, "Play opens the Streams sheet")
        // The sheet up.
        screenSettles(appScreen)
        requireBothSides("streams.title", "the Streams sheet's title")
        shot("cover portrait, streams")
    }

    /// Fails when the element `id`'s gaps to the window's left and right edges differ by more than 4 pt: the empty strip
    /// a vertical bar leaves in a sheet widens one of them.
    private func requireBothSides(_ id: String, _ what: String) {
        let frame = require(id, "\(what) shows").frame, window = windowFrame
        let left = frame.minX - window.minX, right = window.maxX - frame.maxX
        if abs(left - right) > 4 {
            fail("\(what) at x \(frame.minX)–\(frame.maxX) leaves \(left) pt on the left and \(right) pt on the right of the window at \(window)")
        }
    }

    /// The cards at the chip's inset, short of the vertical bar beside them (in portrait at the right): within the gap
    /// between the bar's buttons and its edge (18 pt) and the screen padding (16).
    func testCoverAddonCardsLineUpWithTheChip() throws {
        pose("closed")
        pose("portrait")
        try launchDev(addon: true, open: ["settings"])
        activate(scrollTo("settings.section.addons", "Settings lists Addons"))
        let chip = require("addons.filter", timeout: 30, "Addons shows its type filter")
        require("addons.row.0", timeout: 30, "Addons lists the installed addons")
        let card = app.cells.firstMatch.frame
        let bar = backButton.frame
        shot("cover portrait, addons")
        if abs(card.minX - chip.frame.minX) > 1 {
            fail("the first card starts at x \(card.minX), not under the chip at x \(chip.frame.minX)")
        }
        if card.maxX > bar.minX || bar.minX - card.maxX > 40 {
            fail("the first card ends at x \(card.maxX), not just short of the vertical bar's back button at x \(bar.minX)")
        }
    }

    /// Every tab hittable: folded into the selected one, the others are laid out but not hittable (Install in the bar).
    func testCoverLandscapeAddonsKeepsTheTabs() throws {
        pose("closed")
        pose("landscape-left")
        try launchDev(addon: true, open: ["settings"])
        activate(scrollTo("settings.section.addons", "Settings lists Addons"))
        let install = require("addons.install", timeout: 30, "Addons shows Install")
        require("addons.row.0", timeout: 30, "Addons lists the installed addons")
        screenSettles(appScreen)
        shot("cover landscape, addons")
        let folded = Self.phoneTabs.filter { !phoneTab($0).isHittable }
        if !folded.isEmpty { fail("with Addons open the vertical bar folds the tabs: \(folded) aren't hittable") }
        activate(install)
        require("addons.install.link", timeout: 10, "Install opens the install sheet")
    }

    /// The gap from the tabs' column to each page's first chip, card or row, against the form's: the system starts the
    /// form's cards at the safe area beside the bar, and a page that added its screen padding there started 16 pt
    /// further.
    func testCoverLandscapePagesStartAtTheFormsGap() throws {
        pose("closed")
        pose("landscape-left")
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-series"])
        require("detail.play", timeout: 60, "the series' details show Play")
        let column = phoneTab("nav.settings").frame.maxX
        var starts = [("Detail's season chip", require("detail.season.0", "the series' details show its seasons").frame.minX)]
        activate(backButton)
        starts.append(("Home's shortcut chip", require("board.shortcut.watchTogether", timeout: 30, "Home shows its shortcuts").frame.minX))
        activate(phoneTab("nav.discover"))
        starts.append(("Discover's type filter", require("discover.type", timeout: 30, "Discover shows its type filter").frame.minX))
        starts.append(("Discover's first poster", require("discover.item.0", timeout: 30, "Discover shows the catalog").frame.minX))
        activate(phoneTab("nav.library"))
        starts.append(("Library's view menu", require("library.view", timeout: 30, "Library shows its view menu").frame.minX))
        activate(phoneTab("nav.settings"))
        let form = require("settings.section.playback", timeout: 30, "Settings lists Playback").frame.minX
        shot("cover landscape, settings")
        activate(scrollTo("settings.section.addons", "Settings lists Addons"))
        starts.append(("Addons' type filter", require("addons.filter", timeout: 30, "Addons shows its type filter").frame.minX))
        require("addons.row.0", timeout: 30, "Addons lists the installed addons")
        starts.append(("Addons' first card", app.cells.firstMatch.frame.minX))
        shot("cover landscape, addons")
        let off = starts.filter { abs(($0.1 - column) - (form - column)) > 1 }
        if !off.isEmpty {
            let gaps = off.map { "\($0.0) \($0.1 - column) pt" }.joined(separator: ", ")
            fail("past the tabs' column at x \(column) the form's cards start \(form - column) pt on, but \(gaps)")
        }
    }

    /// The inner screen in landscape (951 pt, the phone upright) shows a series' details on the one-column page, as the
    /// cover does: centred on the page, not the details' column at the left beside the episodes.
    func testInnerLandscapeSeriesDetailsInOneColumn() throws {
        pose("open")
        pose("portrait")
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-series"])
        require("detail.play", timeout: 60, "the series' details show Play")
        screenSettles(appScreen)
        let window = windowFrame
        let row = ["detail.play", "detail.library", "detail.watched", "detail.download", "detail.trailer", "detail.share", "detail.overflow"]
            .map(element).filter(\.exists).map(\.frame)
        let middle = ((row.map(\.minX).min() ?? 0) + (row.map(\.maxX).max() ?? 0)) / 2
        shot("inner landscape, series details")
        if abs(middle - window.midX) > 60 {
            fail("the action row's middle is at x \(middle) in the window at \(window): not the one-column page")
        }
    }

    /// The splash's loader in the middle of the cover, the vertical bar off before the shell (a product start held at
    /// its account step, `--dev-slow-start`).
    func testCoverSplashLoaderInTheMiddle() throws {
        pose("closed")
        pose("portrait")
        app.terminate()
        launch(["--dev-sign-in-server", try signInServer.absoluteString, "--dev-slow-start", "3"])
        require("startup.status", timeout: 30, "the splash names its step while the start is held")
        let loader = app.activityIndicators.firstMatch.frame, window = windowFrame
        shot("cover portrait, splash")
        if abs(loader.midY - window.midY) > 4 || abs(loader.midX - window.midX) > 4 {
            fail("the splash's loader at \(loader) isn't in the middle of the window at \(window)")
        }
    }

    func testCoverLandscapeShowsPlayOnTheFirstScreen() throws {
        pose("closed")
        pose("landscape-left")
        try launchDev(addon: true, open: ["board", "detail:movie:\(heroMovie)"])
        let play = require("detail.play", timeout: 60, "the movie's details show Play")
        screenSettles(appScreen)
        let window = windowFrame
        if play.frame.maxY > window.maxY - 8 {
            fail("Play lies at \(play.frame), not whole on the first screen of the window at \(window)")
        }
        shot("cover landscape, details")
    }

    func testInnerScreenStreamsSheetHasClose() throws {
        pose("open")
        pose("portrait")
        try launchDev(addon: true, open: ["board", "detail:movie:\(movie)"])
        activate(require("detail.play", timeout: 60, "the movie's details show Play"))
        require("streams.row.0", timeout: 60, "Play opens the Streams sheet")
        shot("inner screen, streams")
        activate(require("streams.close", timeout: 5, "the Streams sheet on the inner screen has Close, as on iPad"))
        requireGone("streams.row.0", "Close closes the Streams sheet")
    }

    func testInnerPortraitSubtitlePreviewAboveTheForm() throws {
        pose("open")
        // The inner screen is upright, 669 pt wide, with the phone turned (the cover's landscape).
        pose("landscape-left")
        try launchDev(addon: true, open: ["settings"])
        activate(scrollTo("settings.section.subtitles", "Settings lists Subtitles"))
        let preview = require("settings.subtitles.preview", "Subtitles shows its preview")
        let size = require("settings.subtitles.size", "Subtitles shows the size stepper")
        if preview.frame.maxY > size.frame.minY {
            fail("the preview at \(preview.frame) isn't above the form's size row at \(size.frame)")
        }
        shot("inner portrait, subtitles")
    }
}
