import XCTest

/// Detail by touch alone, on iPhone and iPad (the tests both platforms have are in Pointer/DetailTests.swift):
/// - the user's own rating (`testRateATitleOnTheTrackers`, UserRatingTestSupport.swift), by touch: The Shawshank
///   Redemption unrated (no score of the user's own in the ratings row, ⋯'s "Rate this title" opening the picker,
///   each tracker "Not rated" there, no star lit, no Remove rating); 8 given by a tap on the
///   4th star's right half, saved on all three, four stars lit; the row's score "8" once the picker closes; Simkl's
///   rating writes failing, 6 given: three stars lit, Simkl's line waiting to retry, the others saved; Simkl back, the
///   app sent to the background and back: Simkl holds 6 and its line, read again, says so; Remove rating: every line
///   "Removed", no star lit, the row's score gone and ⋯'s entry "Rate this title" again, the fake holding none; Batman
///   & Robin rated 7 on Trakt and 8 on Simkl: the score "7–8", in the picker no star lit and each tracker's own; a
///   launch without trackers: IMDb alone, no score of the user's own and no Rate in ⋯. iPhone's picker is a sheet a
///   tap above closes, iPad's a form sheet with Close;
/// - a rating Trakt refuses (the fake answering 422) when it is given and at each of its five retries, one each time
///   the app comes back to the front: Settings → Trackers then says under Trakt's switches that a change couldn't be
///   sent and what Trakt answered (`testARatingTraktKeepsRefusingShowsInTrackers`);
/// - the test addon's series left at 12 s of its first episode: the episode's long-press menu offers Play from the
///   beginning and Mark as watched and, auto-play off, no Choose a stream; with auto-play on (First match, set in
///   Settings, the session launched again on the series), it offers Choose a stream too, which opens the episode's
///   streams, and Play from the beginning plays auto-play's pick from 0;
/// - a resumed episode (the test addon's series, played a few seconds) at a large text size: its action row, Play
///   reading "S1E1 · 0:05" (spoken "Resume S1E1 · 0:05"), stays inside the screen's margins;
/// - the test addon's series at the largest text size: the system's audit finds no text clipped or cut short and none
///   that keeps its size, at the top of the details (the description opened by Show more) and at the first episode,
///   whose row puts its text under its thumbnail (`testDetailsReadWholeAtTheLargestTextSize`); on iPad's wide page the
///   title grows with the text (`testTheTitleGrowsWithTheTextOnTheWidePage`);
/// - the test addon's series whose meta carries its streams: Share is offered, and an episode's Streams sheet lists the
///   meta's own stream first;
/// - the test addon's series left at 12 s of its first episode, Türkçe picked in Settings while the system stays in
///   English: the episode's progress reads as Turkish writes a percentage, "%60", not English's "60%";
/// - in Arabic the test addon's series with Latin episode titles that end in punctuation: the first episode's numbered
///   title starts with a right-to-left mark and holds its title whole inside isolates, as does its ⋯'s label
///   (`testALatinEpisodeTitleKeepsItsOrderInArabic`);
/// - the top of the details (`testTheMetadataLineSitsUnderTheTitleOverTheBackdrop`, DetailHeroSupport.swift): the
///   metadata line close under what shows of the title, whatever the logo's shape, still while the logo comes in, and
///   the actions well clear under its block; on iPhone's page readable over a bright backdrop whose picture reaches it,
///   also at an accessibility text size, and in landscape and on iPad's wide page; a long name whole, also at the
///   largest accessibility text size, Play reached under it;
/// - the action row at the device's width with every action (`testActionRowFitsTheWidth`): its round buttons at their
///   full size, the actions without room behind ⋯, each of whose entries works;
/// - iPad in landscape: the sidebar over the backdrop of the test addon's movie without a logo (the bright frame: an
///   overcast sky's whites) (`testTheSidebarReadsOverTheBackdrop`);
/// - iPad's wide page: a season row longer than the episodes' column, scrolled to its end, draws nothing over the
///   details (`testTheSeasonRowStaysInItsColumn`).
extension DetailTests {
    /// iPad in landscape, launched in it (`quitAndTurn`), the sidebar shown: the backdrop runs under the sidebar to the
    /// screen's leading edge, dimmed there, the sidebar drawn over it (shot `bright backdrop under the sidebar`, the
    /// screen's own, turned a quarter); the details start beside the sidebar, and each of its destinations stands out
    /// from what is behind it by at least 4.5:1. iPhone has no sidebar.
    func testTheSidebarReadsOverTheBackdrop() throws {
        guard isPad else { throw XCTSkip("iPhone has no sidebar") }
        Self.quitAndTurn(to: .landscapeLeft)
        addTeardownBlock { TortugaUITestCase.quitAndTurn(to: .portrait) }
        try launchDev(addon: true, open: ["board", "detail:movie:tortuga-hero-plain"])
        require("detail.title", timeout: 60, "the bright-backdrop movie's details show")
        openSidebar("in landscape")
        requireArtDrawn("in landscape")
        // Nothing darkens the backdrop at the screen's trailing top.
        let trailingTop = CGRect(x: app.frame.maxX - 120, y: 80, width: 80, height: 80)
        if (ScreenPixels()?.medianLuminance(in: trailingTop) ?? 0) <= 0.5 {
            fail("the bright backdrop doesn't show at the screen's trailing top")
        }
        shot("bright backdrop under the sidebar", of: XCUIScreen.main)
        requireSidebarOverTheBackdrop("in landscape")
    }

    /// iPad's wide page, in portrait, then in landscape (launched in it, `quitAndTurn`): the test addon's series of 24
    /// seasons, its season row scrolled to its last chip, draws no chip left of the episodes' column, over the details
    /// (`requireSeasonRowInItsColumn`). iPhone has one column.
    func testTheSeasonRowStaysInItsColumn() throws {
        guard isPad else { throw XCTSkip("iPhone has one column") }
        try launchDev(addon: true, open: [Self.longSeasonRowTitle])
        requireSeasonRowInItsColumn("in portrait")
        Self.quitAndTurn(to: .landscapeLeft)
        addTeardownBlock { TortugaUITestCase.quitAndTurn(to: .portrait) }
        try launchDev(addon: true, open: [Self.longSeasonRowTitle])
        requireSeasonRowInItsColumn("in landscape")
    }

    func testRateATitleOnTheTrackers() throws {
        try seedFakeRatings()
        try launchDev(trackersConnected: true, open: [Self.rateTitle])
        require("detail.play", timeout: 60, "The Shawshank Redemption's details show Play")
        shot("rate, unrated")

        openRatingPicker("the unrated title", label: "Rate this title", timeout: 60)
        requireNoRatingItem("an unrated title")
        requireRatingLines(Self.lines("Not rated"), "an unrated title's picker")
        if element("detail.userRating.clear").exists { fail("the picker offers Remove rating before any score") }
        requireStars(nil, "an unrated title's picker")
        shot("rate, picker")

        giveScore(8)
        requireRatingLines(Self.lines("Saved"), "8 given")
        requireStars(8, "8 given")
        requireFakeRatings(Self.rateImdb, ["trakt": [8], "simkl": [8], "mdblist": [8]], "8 given")
        require("detail.userRating.clear", timeout: 10, "Remove rating once there is a score")
        shot("rate, 8 saved")
        closeRatingPicker("8 given")
        requireRatingItem("Your rating: 8", "the rated title's item")
        shot("rate, rated")

        try fakeTrackers("/_fail?ratings=simkl", post: true)
        openRatingPicker("the rated title", label: "Your rating: 8")
        giveScore(6)
        requireRatingLines(["trakt": "Saved", "simkl": "Not sent yet, will retry", "mdblist": "Saved"], "6 given, Simkl's writes failing")
        requireStars(6, "6 given, Simkl's writes failing")
        requireFakeRatings(Self.rateImdb, ["trakt": [6], "simkl": [8], "mdblist": [6]], "6 given, Simkl's writes failing")
        shot("rate, Simkl's write queued")

        try fakeTrackers("/_fail?ratings=", post: true)
        closeRatingPicker("Simkl's write queued")
        // iOS 27's simulator goes on reporting the app in the foreground after Home (NoticesTests.leaveFront): the
        // replay below, which only the app's coming back starts, is what shows it went. The app has left the front
        // once the home screen shows, still.
        let inApp = screenPNG()
        XCUIDevice.shared.press(.home)
        if waitUntil(10, { screenPNG() != inApp }) { screenSettles(appScreen) }
        app.activate()
        requireFakeRatings(Self.rateImdb, ["trakt": [6], "simkl": [6], "mdblist": [6]], timeout: 60, "the queued 6 replayed to Simkl once the app is back")
        requireRatingItem("Your rating: 6", "the item after the replay")
        openRatingPicker("after the replay")
        requireRatingLines(["trakt": "Saved", "simkl": "6", "mdblist": "Saved"], "Simkl's line, read again once its write has gone")
        shot("rate, Simkl's write retried")

        ready(require("detail.userRating.clear", "Remove rating")).tap()
        requireRatingLines(Self.lines("Removed"), "Remove rating")
        requireStars(nil, "Remove rating")
        requireFakeRatings(Self.rateImdb, ["trakt": [], "simkl": [], "mdblist": []], "Remove rating")
        requireGone("detail.userRating.clear", "Remove rating goes with the score")
        shot("rate, removed")
        closeRatingPicker("Remove rating")
        requireNoRatingItem("after Remove rating")
        requireRateAction("Rate this title", "⋯'s Rate after Remove rating")

        try launchDev(trackersConnected: true, open: [Self.rateRangeTitle])
        require("detail.play", timeout: 60, "Batman & Robin's details show Play")
        requireRatingItem("Your rating: 7–8", timeout: 60, "the item where the trackers' scores differ")
        shot("rate, range")
        openRatingPicker("the trackers' scores differing", label: "Your rating: 7–8")
        requireRatingLines(["trakt": "7", "simkl": "8", "mdblist": "Not rated"], "the trackers' scores differing")
        requireStars(nil, "the trackers' scores differing")
        require("detail.userRating.clear", timeout: 5, "Remove rating where the trackers' scores differ")
        shot("rate, range picker")
        closeRatingPicker("the trackers' scores differing")

        try launchDev(addon: true, open: [Self.rateTitle])
        require("detail.play", timeout: 60, "The Shawshank Redemption's details show Play without trackers")
        requireRatings(["imdb"], centred: !isPad, "the ratings row without trackers (the addon's IMDb rating)")
        requireNoRatingItem("without trackers")
        requireNoRateAction("without trackers")
        shot("rate, no trackers")
    }

    func testARatingTraktKeepsRefusingShowsInTrackers() throws {
        try seedFakeRatings()
        try fakeTrackers("/_fail?ratings=trakt&status=422", post: true)
        addTeardownBlock { _ = try? self.fakeTrackers("/_fail?ratings=", post: true) }
        func refusals() throws -> Int {
            let writes = try fakeTrackers("/_log")["ratingWrites"] as? [[String: Any]] ?? []
            return writes.filter { $0["service"] as? String == "trakt" && $0["status"] as? Int == 422 }.count
        }
        // The Board under the details: the tab bar, the way to Settings.
        try launchDev(trackersConnected: true, open: ["board", Self.rateTitle])
        openRatingPicker("the unrated title", label: "Rate this title", timeout: 60)
        giveScore(8)
        requireRatingLines(["trakt": "Not sent yet, will retry", "simkl": "Saved", "mdblist": "Saved"], "8 given, Trakt refusing it")
        closeRatingPicker("Trakt's rating queued")
        // Refused once when given; each return to the front replays it, until the fifth retry gives it up. As in
        // testRateATitleOnTheTrackers, the app has left the front once the home screen shows.
        var asked = try refusals()
        for _ in 1...8 where asked < 6 {
            let inApp = screenPNG()
            XCUIDevice.shared.press(.home)
            if waitUntil(10, { screenPNG() != inApp }) { screenSettles(appScreen) }
            app.activate()
            let before = asked
            _ = waitUntil(60) {
                asked = (try? refusals()) ?? before
                return asked > before
            }
        }
        if asked != 6 { fail("Trakt refused the rating \(asked) times, not when given and at each of its 5 retries") }
        openSection("trackers")
        _ = scrollTo("settings.trackers.trakt.anime", "Trakt's switches")
        shot("trackers after Trakt refused")
        let failed = require("settings.trackers.trakt.failed", timeout: 10, "Trackers shows Trakt's change that couldn't be sent")
        if !text(of: failed).contains("1 change couldn’t be sent (last: Trakt answered error 422)") {
            fail("Trakt's failed line reads \"\(text(of: failed))\"")
        }
    }

    func testEpisodeInProgressPlaysFromTheStartAndChoosesAStream() throws {
        // Two launches, a trip to Settings and a play.
        executionTimeAllowance = 600
        let data = freshDataName()
        let addon = try addonURL
        // The Board under the details: the tab bar, the way to Settings.
        let open = ["board", StreamFilterTexts.series]
        launchDev(keepingData: data, addon: addon, open: open, progress: [Self.seriesEpisodeInProgress])
        require("detail.play", timeout: 60, "the series' details show Play")
        ready(scrollTo("detail.episode.0", "the series lists its first episode")).press(forDuration: 1.2)
        require("detail.menu.item.playFromStart", timeout: 5, "a long press on the episode in progress offers Play from the beginning")
        let watched = require("detail.menu.item.markWatched", timeout: 5, "the episode's menu offers Mark as watched")
        // No check of the entries' order: iOS lists them from the item outwards, reversed when the menu opens above it.
        if element("detail.menu.item.chooseStream").exists { fail("with auto-play off, the episode's menu offers Choose a stream") }
        shot("episode in progress menu")
        dismissMenu(watched)

        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.firstMatch)
        launchDev(keepingData: data, addon: addon, open: open, progress: [Self.seriesEpisodeInProgress])
        require("detail.play", timeout: 60, "the series' details show Play")
        let title = require("detail.title", timeout: 5, "the series' details show its title").label
        let episode = scrollTo("detail.episode.0", "the series lists its first episode")
        ready(episode).press(forDuration: 1.2)
        require("detail.menu.item.playFromStart", timeout: 5, "with auto-play on, the episode's menu offers Play from the beginning")
        let choose = require("detail.menu.item.chooseStream", timeout: 5, "with auto-play on, the episode's menu offers Choose a stream")
        shot("episode menu with auto-play on")
        pickMenuEntry(choose)
        require("streams.row.0", timeout: 60, "Choose a stream in the episode's menu opens its streams")
        if element("player.video").exists { fail("Choose a stream in the episode's menu played auto-play's pick, not the episode's streams") }
        shot("choose a stream from the episode")
        closeStreamsSheet(title, "the episode's streams from Choose a stream")

        // Last, so the 20 s episode can't play out before a relaunch reads its progress again.
        ready(episode).press(forDuration: 1.2)
        pickMenuEntry(require("detail.menu.item.playFromStart", timeout: 5, "the episode's menu offers Play from the beginning"))
        require("player.video", timeout: 60, "Play from the beginning with auto-play on plays auto-play's pick")
        if element("streams.row.0").exists { fail("with auto-play on, Play from the beginning opened the episode's streams") }
        requirePlayingFromTheStart("Play from the beginning in the episode's menu (auto-play on)")
        shot("episode played from the beginning")
    }

    /// A resumed episode's action row, Play reading "S1E1 · 0:05" (spoken "Resume S1E1 · 0:05"), at the largest text size
    /// short of the accessibility sizes, where the label is wider than the room the round buttons leave it on iPhone:
    /// the row stays inside the screen's margins.
    func testResumedEpisodeKeepsTheActionRowOnTheScreen() throws {
        // test_addon.py's own series: 20 s episodes.
        try launchDev(addon: true, open: ["detail:series:tortuga-series"], textSize: "UICTContentSizeCategoryXXXL")
        let play = require("detail.play", timeout: 60, "the series' details show Play")
        let start = play.label
        ready(play).tap()
        ready(require("streams.row.0", timeout: 60, "Play opens the episode's streams")).tap()
        let video = require("player.video", timeout: 20, "a tap on the stream opens the player")
        requirePlayback(5, timeout: 90)
        closePlayer(video, "the controls' back button closes the player")
        if !waitUntil(10, { element("detail.play").exists && element("detail.play").label != start }) {
            fail("back on the details, Play still reads \(start)")
        }
        // The row lays out again around Play's new label.
        screenSettles()
        shot("resumed episode")
        requireActionRowOnScreen("the resumed episode's action row (\(element("detail.play").label))", playAtItsWidth: false)
    }

    /// The test addon's series at the default text size, then at the largest (accessibility-extra-extra-extra-large),
    /// shot at the top of the details and scrolled to the first episode: at the largest the system's audit
    /// (`textAuditIssues`) finds no text clipped or cut short and none that keeps its size, at the top with the
    /// description opened by Show more (three lines until then) and at the first episode.
    func testDetailsReadWholeAtTheLargestTextSize() throws {
        var issues: [String] = []
        for (textSize, name) in [(nil, "default text"), (Self.largestTextSize, "largest text")] as [(String?, String)] {
            try launchDev(addon: true, open: ["detail:series:tortuga-series"], textSize: textSize)
            require("detail.play", timeout: 60, "\(name): the series' details show Play")
            screenSettles()
            shot("details, \(name)")
            if textSize != nil {
                let more = element("detail.more")
                if more.exists(within: 5) {
                    activate(more)
                    screenSettles()
                    shot("details, description opened, \(name)")
                }
                issues += textAuditIssues("the top of the details at the largest text size")
            }
            scrollTo("detail.episode.0", "\(name): the season lists its episodes")
            screenSettles()
            shot("first episode, \(name)")
            if textSize != nil { issues += textAuditIssues("the first episode at the largest text size") }
        }
        if !issues.isEmpty { fail("the audit finds \(issues.count) issues: \(issues.joined(separator: "; "))") }
    }

    /// iPad's wide page: the title, 40 pt at the default text size, grows with the text as the large title does. The
    /// test addon's long-named movie (no logo: the name shows) has a taller title at accessibility-extra-extra-large
    /// than at the default size, by at least the large title's growth there short of a few points per line, and taller
    /// than a line of body text there. iPhone's title is the large title text style.
    func testTheTitleGrowsWithTheTextOnTheWidePage() throws {
        guard isPad else { throw XCTSkip("iPhone's title is the large title text style, which follows the text size itself") }
        let large = "UICTContentSizeCategoryAccessibilityXXL"
        var heights: [CGFloat] = []
        for (textSize, name) in [(nil, "default text"), (large, "accessibility text")] as [(String?, String)] {
            try launchDev(addon: true, open: ["detail:movie:tortuga-hero-long"], textSize: textSize)
            require("detail.play", timeout: 60, "\(name): the long-named movie's details show Play")
            let title = require("detail.title", timeout: 5, "\(name): the details show the title")
            screenSettles()
            shot("wide page title, \(name)")
            heights.append(title.frame.height)
        }
        let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(rawValue: large))
        let body = UIFont.preferredFont(forTextStyle: .body, compatibleWith: traits).lineHeight
        let growth = UIFontMetrics(forTextStyle: .largeTitle).scaledValue(for: 40, compatibleWith: traits) / 40
        print("title, wide page: \(heights[0]) pt at the default text size, \(heights[1]) pt at \(large); the large title grows \(growth)×, a line of body text is \(body) pt")
        if heights[1] < heights[0] * (1 + (growth - 1) / 2) || heights[1] < body {
            fail("the title is \(heights[1]) pt tall at accessibility-extra-extra-large, \(heights[0]) pt at the default text size: it doesn't grow with the text (the large title grows \(growth)×, a line of body text is \(body) pt)")
        }
    }

    /// test_addon.py's series whose meta carries its episodes' streams: the Detail offers Share (a round button or ⋯'s
    /// entry), and an episode's Streams sheet lists the meta's own stream first, under the addon's name, before the
    /// addon's `/stream` answer.
    func testTheMetasOwnStreamsComeFirstAndShareShows() throws {
        try launchDev(addon: true, open: ["detail:series:tortuga-meta-streams"])
        require("detail.play", timeout: 60, "the series' details show Play")
        if !element("detail.share").exists(within: 10) {
            ready(require("detail.overflow", "the row has neither Share nor ⋯")).tap()
            let share = require("detail.overflow.share", timeout: 5, "⋯ lists Share")
            shot("share in more")
            dismissMenu(share)
        }
        shot("meta streams series")
        ready(scrollTo("detail.episode.0", "the season lists its episodes")).tap()
        let first = require("streams.row.0", timeout: 60, "a tap on an episode opens its streams")
        if !first.label.contains("carried in the meta") { fail("the first stream reads '\(first.label)', not the meta's own") }
        require("streams.row.1", timeout: 30, "the addon's /stream answer follows the meta's stream")
        shot("meta streams first")
    }

    /// The interface language the profile picks in Settings, not the system's, formats what the app writes with
    /// numbers: the system stays in English (the run's language), Türkçe is picked under Appearance, and the episode in
    /// progress (12 s of 20) reads its progress as Turkish writes a percentage, "%60", where English reads "60%".
    func testAnEpisodesProgressReadsInTheProfilesLanguage() throws {
        let data = freshDataName()
        let addon = try addonURL
        launchDev(keepingData: data, addon: addon)
        openSection("appearance")
        pickChoice("settings.appearance.interfaceLanguage", option: (id: "tr", label: "Türkçe"))
        launchDev(keepingData: data, addon: addon, open: ["board", StreamFilterTexts.series], progress: [Self.seriesEpisodeInProgress])
        require("detail.play", timeout: 60, "the series' details show Play")
        let episode = scrollTo("detail.episode.0", "the series lists its first episode")
        func value() -> String { episode.exists ? episode.value as? String ?? "" : "nothing (no row)" }
        if !waitUntil(10, { value().contains("%60") }) {
            fail("in Turkish, the episode in progress reads '\(value())' as its value, not Turkish's %60")
        }
        shot("episode in progress, Turkish")
    }

    /// In Arabic, laid out right to left, a Latin episode title keeps its own order inside the app's numbered title:
    /// the test addon's tortuga-bidi lists its first episode, "What If...?", as U+200F, "1. ", then the title whole
    /// between U+2068 and U+2069, so the line reads from its number on the right and the title keeps "...?" at its
    /// end; its ⋯ is "More actions for" that numbered title, isolated whole. The row is shot (`bidi-detail-ar`) before
    /// it is read. Its cast shows as links, each name a text of its own: iOS composes no cast line.
    func testALatinEpisodeTitleKeepsItsOrderInArabic() throws {
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-bidi"], languages: ["ar"])
        require("detail.play", timeout: 60, "the series' details show Play")
        let episode = scrollTo("detail.episode.0", "the series lists its first episode")
        let menu = require("detail.episode.0.menu", "the first episode's row has its ⋯")
        shot("bidi-detail-ar")
        let numbered = "\u{200F}1. \u{2068}What If...?\u{2069}"
        if !episode.label.contains(numbered) {
            fail("in Arabic the first episode's row reads '\(episode.label)': no right-to-left mark before '1. ' and 'What If...?' whole inside U+2068 … U+2069 after it")
        }
        if !menu.label.contains("\u{2068}\(numbered)\u{2069}") {
            fail("in Arabic the first episode's ⋯ reads '\(menu.label)', not its numbered title inside U+2068 … U+2069")
        }
    }

    /// The top of the details on the test addon's hero titles (`requireHeroTitles`): iPad's wide page; iPhone's page in
    /// portrait, then Cinemeta's Chainsmoker Cat (its own logo and backdrop) at the default text size and at the
    /// largest short of the accessibility sizes, the name without a logo and the long name at the largest accessibility
    /// size (each whole, the title's box growing downwards where the name is taller: the long name's metadata line on
    /// the black page under the backdrop, Play dragged into view), then in landscape, still the narrow page (a phone's
    /// window is under 1000 pt wide), the long name also at the largest accessibility size.
    func testTheMetadataLineSitsUnderTheTitleOverTheBackdrop() throws {
        if isPad {
            try requireHeroTitles("ipad", narrow: false) { try launchDev(addon: true, open: [$0]) }
            return
        }
        try requireHeroTitles("portrait", narrow: true) { try launchDev(addon: true, open: [$0]) }

        for (textSize, label) in [(nil, ""), ("UICTContentSizeCategoryXXXL", ", largest text")] as [(String?, String)] {
            try launchDev(open: ["detail:series:tt39551330"], textSize: textSize)
            require("detail.play", timeout: 60, "Chainsmoker Cat's details show Play")
            requireHero("Chainsmoker Cat\(label)", shot: "hero, chainsmoker cat\(label)", art: true, overBrightBackdrop: false)
        }

        let largest = "UICTContentSizeCategoryAccessibilityXXXL"
        try launchDev(addon: true, open: ["detail:movie:tortuga-hero-plain"], textSize: largest)
        require("detail.play", timeout: 60, "the movie without a logo shows Play at the largest text size")
        requireName("the name at the largest text size", shot: "hero, no logo, accessibility text, name", textSize: largest)
        requireHero("the name at the largest text size", shot: "hero, no logo, accessibility text", art: false, overBrightBackdrop: true)

        try launchDev(addon: true, open: ["detail:movie:tortuga-hero-long"], textSize: largest)
        require("detail.play", timeout: 60, "the long-named movie shows Play at the largest text size")
        requireName("the long name at the largest text size", shot: "hero, long name, accessibility text, name", textSize: largest)
        requireHero("the long name at the largest text size", shot: "hero, long name, accessibility text", art: false, overBrightBackdrop: false)

        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        func openInLandscape(_ screen: String, textSize: String? = nil) throws {
            try launchDev(addon: true, open: [screen], textSize: textSize)
            XCUIDevice.shared.orientation = .landscapeLeft
            if !waitUntil(10, { app.frame.width > app.frame.height }) { fail("the app doesn't turn to landscape: \(app.frame)") }
            // The page lays out again at its new width.
            screenSettles()
        }
        try requireHeroTitles("landscape", narrow: true) { try openInLandscape($0) }

        try openInLandscape("detail:movie:tortuga-hero-long", textSize: largest)
        require("detail.play", timeout: 60, "landscape: the long-named movie shows Play at the largest text size")
        requireName(
            "landscape: the long name at the largest text size", shot: "hero, landscape, long name, accessibility text, name",
            textSize: largest
        )
        requireHero(
            "landscape: the long name at the largest text size", shot: "hero, landscape, long name, accessibility text", art: false,
            overBrightBackdrop: false
        )
    }

    /// The round buttons of the action row in its order, and the order a row without room for them all keeps them in
    /// (`fitActions`, shared/…/presentation/detail/DetailActions.kt).
    private static let roundActions = ["chooseStream", "library", "watched", "download", "trailer", "share"]
    private static let importance = ["chooseStream", "library", "download", "watched", "trailer", "share"]

    /// The action row at this device's width with every action (auto-play on: Choose a stream, the library, watched,
    /// the download, the trailer, Share) on the movie (two trailers), The Simpsons resumed at S12E21 · 1:23:45 (a long
    /// Play label, one trailer) and, with the profile's interface language German, Chernobyl ("S1 F1 abspielen"): the
    /// round buttons at their design size (the row's height square: Play's height, 48 pt on iPhone), side by side
    /// inside the screen's margins and, on iPad, inside the details' column beside the episodes; the actions without
    /// room are exactly those behind ⋯ (`detail.overflow`), the most important kept in the row, and each entry of ⋯
    /// does what its button does. On iPad the movie's row, with nothing beside the details, shows every action without
    /// ⋯, in portrait and in landscape. The titles' problems with the row are reported together at the end, so one run
    /// shows them all; the row's frames and problems go to the log as found.
    func testActionRowFitsTheWidth() throws {
        // Settings twice, three titles and the trips ⋯'s entries take (the Streams sheet, a trailer in Safari).
        executionTimeAllowance = 1200
        let data = freshDataName()
        let addon = try addonURL
        launchDev(keepingData: data, addon: addon)
        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.firstMatch)

        var problems: [String] = []
        openTitle("detail:movie:tt0111161", data: data, addon: addon)
        problems += actionRowProblems("the movie", shotName: "movie", everyActionShown: isPad)
        // In landscape, launched in it (`quitAndTurn`), then in portrait again for the series.
        if isPad {
            addTeardownBlock { TortugaUITestCase.quitAndTurn(to: .portrait) }
            Self.quitAndTurn(to: .landscapeLeft)
            openTitle("detail:movie:tt0111161", data: data, addon: addon)
            problems += actionRowProblems("the movie in landscape", shotName: "movie landscape", everyActionShown: true)
            Self.quitAndTurn(to: .portrait)
        }
        openTitle("detail:series:tt0096697", data: data, addon: addon, progress: "series:tt0096697:tt0096697:12:21:5025:9000")
        if !element("detail.play").label.contains("S12E21") { fail("The Simpsons' Play reads \(element("detail.play").label), not S12E21's resume") }
        problems += actionRowProblems("The Simpsons resumed at S12E21", shotName: "long resume")

        launchDev(keepingData: data, addon: addon)
        openSection("appearance")
        pickChoice("settings.appearance.interfaceLanguage", option: (id: "de", label: "Deutsch"))
        openTitle("detail:series:tt7366338", data: data, addon: addon)
        problems += actionRowProblems("Chernobyl in German", shotName: "german")
        if !problems.isEmpty { fail(problems.joined(separator: "\n")) }
    }

    /// Launches the kept session `data` (auto-play on) on `screen`, with `progress` (`--dev-progress`) where given, and
    /// waits for its action row to settle: every action a button or ⋯ there; Choose a stream, the most important, a
    /// button, and the download, the third, one too where the row has room for three round buttons.
    private func openTitle(_ screen: String, data: String, addon: String, progress: String? = nil) {
        launchDev(keepingData: data, addon: addon, open: [screen], progress: progress.map { [$0] } ?? [])
        require("detail.play", timeout: 60, "\(screen) shows Play")
        // The trailers come with the title, the download's state and auto-play's Choose a stream just after it, each
        // taking the row's place of a less important action (or its own, where the row has room).
        _ = waitUntil(30) {
            let round = Self.importance.filter { element("detail.\($0)").exists }
            return (element("detail.overflow").exists || round.count == Self.roundActions.count)
                && (round.isEmpty || round.contains("chooseStream")) && (round.count < 3 || round.contains("download"))
        }
    }

    /// What is wrong with the action row on screen (`what` names it), shot as `shotName`: a round button not at its
    /// design size, items overlapping, the row past the screen's margins or into the episodes' column, an action neither
    /// a button nor behind ⋯, ⋯ listing other actions than the row has no room for, or the row keeping less important
    /// ones; with `everyActionShown`, ⋯ at all. Then each of ⋯'s entries is picked (`useMoreEntry`).
    private func actionRowProblems(_ what: String, shotName: String, everyActionShown: Bool = false) -> [String] {
        shot(shotName)
        var problems: [String] = []
        let play = element("detail.play").frame
        let shown = Self.roundActions.filter { element("detail.\($0)").exists }
        let more = element("detail.overflow")
        let round = shown.map { ("detail.\($0)", element("detail.\($0)").frame) } + (more.exists ? [("detail.overflow", more.frame)] : [])
        let items = ([("detail.play", play)] + round).sorted { $0.1.minX < $1.1.minX }
        let readings = items.map { "\($0.0) \(Int($0.1.minX))+\(Int($0.1.width))×\(Int($0.1.height))" }.joined(separator: ", ")
        print("action row, \(what): \(readings)")
        for (id, frame) in round where abs(frame.width - play.height) > 1 || abs(frame.height - play.height) > 1 {
            problems.append("\(what): \(id) is \(Int(frame.width))×\(Int(frame.height)) pt, not the row's \(Int(play.height)) pt square (\(readings))")
        }
        for (left, right) in zip(items, items.dropFirst()) where right.1.minX < left.1.maxX - 0.5 {
            problems.append("\(what): \(left.0) and \(right.0) overlap (\(readings))")
        }
        let row = items.map { $0.1 }.reduce(play) { $0.union($1) }
        let screen = app.windows.firstMatch.frame
        if row.minX < screen.minX + 15 || row.maxX > screen.maxX - 15 {
            problems.append("\(what): the row runs from x \(Int(row.minX)) to \(Int(row.maxX)), past the screen's margins (\(readings))")
        }
        // Wide layouts: the episodes' column, beside the details, starts past the row's end.
        let episode = element("detail.episode.0")
        if episode.exists, episode.frame.minX > play.maxX, row.maxX > episode.frame.minX {
            problems.append("\(what): the row runs to x \(Int(row.maxX)), into the episodes' column at x \(Int(episode.frame.minX)) (\(readings))")
        }
        let hidden = Self.roundActions.filter { !shown.contains($0) }
        if everyActionShown, more.exists || !hidden.isEmpty {
            problems.append("\(what): the row shows \(shown)\(more.exists ? " and ⋯" : ""), not every action without ⋯")
        }
        guard more.exists else {
            if !hidden.isEmpty { problems.append("\(what): no button and no ⋯ for \(hidden) (\(readings))") }
            return problems
        }
        let kept = Array(Self.importance.prefix(shown.count))
        if Set(shown) != Set(kept) { problems.append("\(what): the row keeps \(shown), not the most important \(kept)") }
        ready(more).tap()
        let firstEntry = element("detail.overflow.\(hidden.first ?? "")")
        _ = firstEntry.exists(within: 5)
        let entries = Self.roundActions.filter { element("detail.overflow.\($0)").exists }
        shot("\(shotName), more")
        dismissMenu(firstEntry)
        if entries != hidden { problems.append("\(what): ⋯ lists \(entries), but the row has no room for \(hidden)") }
        // In the log before the entries' trips, which stop the test where one fails.
        for problem in problems { print("action row problem, \(problem)") }
        // What changes the title (the library, watched) last: it may change Play's label, and with it the row.
        for id in ["chooseStream", "download", "trailer", "library", "watched"] where entries.contains(id) {
            useMoreEntry(id, what)
        }
        return problems
    }

    /// Picks ⋯'s entry for `id` and checks it does what the button does, undoing what it changed: Choose a stream
    /// opens the Streams sheet; the download a movie's Streams sheet to pick the stream to download, or a series'
    /// Download menu; the trailer (the first of several) opens outside the app; the library and watched turn into
    /// their undo, picked again to restore the title.
    private func useMoreEntry(_ id: String, _ what: String) {
        let title = require("detail.title", timeout: 5, "\(what): the details show the title").label
        switch id {
        case "chooseStream":
            pickMenuEntry(moreEntry(id, what))
            require("streams.row.0", timeout: 60, "\(what): ⋯'s Choose a stream opens the streams")
            closeStreamsSheet(title, what)
        case "download":
            pickMore(id, what)
            let seriesMenu = app.buttons.matching(
                NSPredicate(format: "identifier BEGINSWITH 'detail.download.' OR label IN %@", ["All episodes", "Alle Folgen"])
            ).firstMatch
            if seriesMenu.exists(within: 5) {
                shot("download menu from more, \(what)")
                dismissMenu(seriesMenu)
            } else {
                require("streams.notice", timeout: 60, "\(what): ⋯'s Download opens the Streams sheet to pick the stream to download")
                closeStreamsSheet(title, what)
            }
        case "trailer":
            pickMore(id, what)
            // Several trailers: ⋯'s entry opens their menu, whose entries have only their labels on iOS.
            let first = app.buttons.matching(NSPredicate(format: "label == 'Trailer 1'")).firstMatch
            if first.exists(within: 3) { pickMenuEntry(first) }
            if !app.wait(for: .runningBackground, timeout: 20) { fail("\(what): ⋯'s trailer doesn't open outside the app") }
            app.activate()
            require("detail.play", timeout: 20, "\(what): back in the app after the trailer")
        default:
            let before = actionLabel(id, what)
            pickMenuEntry(moreEntry(id, what))
            var after = before
            _ = waitUntil(10) {
                after = actionLabel(id, what)
                return after != before
            }
            if after == before { fail("\(what): ⋯'s \(before) doesn't change the title: it still reads \(after)") }
            if element("detail.\(id)").exists { ready("detail.\(id)").tap() } else { pickMenuEntry(moreEntry(id, what)) }
            if !waitUntil(10, { actionLabel(id, what) == before }) { fail("\(what): \(after) doesn't undo \(before)") }
        }
    }

    /// Opens ⋯ and taps its entry for `id`, Download or the trailer: where the title has several choices (a series'
    /// Download menu, several trailers) the entry opens a submenu and the menu stays open, which `pickMenuEntry` would
    /// take for a dropped tap.
    private func pickMore(_ id: String, _ what: String) {
        ready(moreEntry(id, what)).tap()
    }

    /// The words of the action `id`: its button's label, or its entry's in ⋯.
    private func actionLabel(_ id: String, _ what: String) -> String {
        if element("detail.\(id)").exists { return element("detail.\(id)").label }
        let entry = moreEntry(id, what)
        let label = entry.label
        dismissMenu(entry)
        return label
    }

    /// Opens ⋯ and waits for its entry for `id`. A tap the moment the app is back from the trailer's Safari is dropped
    /// (the menu never opens): with none of ⋯'s entries 2 s later, ⋯ is tapped again.
    private func moreEntry(_ id: String, _ what: String) -> XCUIElement {
        let more = require("detail.overflow", "\(what): the row has ⋯")
        ready(more).tap()
        let entries = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'detail.overflow.'"))
        if !entries.firstMatch.exists(within: 2) { ready(more).tap() }
        return require("detail.overflow.\(id)", timeout: 5, "\(what): ⋯ lists \(id)")
    }

    /// Closes the Streams sheet over the title `title`: iPad's Close, iPhone's sheet dragged down by its bar.
    private func closeStreamsSheet(_ title: String, _ what: String) {
        if isPad {
            ready(require("streams.close", "\(what): the iPad's Streams form sheet has Close")).tap()
        } else {
            let bar = app.navigationBars[title]
            (bar.exists ? ready(bar).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)) : app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3))).press(
                forDuration: 0.05,
                thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.99)),
                withVelocity: .fast,
                thenHoldForDuration: 0
            )
        }
        requireGone("streams.row.0", "\(what): the Streams sheet closes")
        require("detail.play", "\(what): closing the sheet returns to the details")
    }

    /// Closes the open menu showing `entry` with a tap at the screen's trailing edge, away from its entries, and waits
    /// for `entry` to go with it.
    private func dismissMenu(_ entry: XCUIElement) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.75)).tap()
        _ = waitUntil(5) { !entry.exists }
    }
}
