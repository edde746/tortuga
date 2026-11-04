import XCTest

/// Streams and the player by touch (iOS) and with the pointer (the Mac), on the test addon's streams for a Cinemeta
/// movie, its series and its movie of an addon's custom formatting:
/// - the Streams screen on its own (a dev start): it lists the two streams the app plays and none of the rest (the
///   peer-to-peer one; the `file:`, `search-ms:` and share-path links, which must never open), a tap (a click) on the
///   first stream plays it for at least 8 s; on iOS the controls hide on their own and a tap on the video brings them
///   back, on the Mac moving the pointer over the video brings them up; their back button returns to the Streams
///   screen;
/// - the Streams sheet over the movie's details: Play opens it, a tap (a click) on the H.264 stream plays it in the
///   player that replaces the sheet, and the player's back button returns to the details with the sheet closed;
/// - an addon's custom formatting: the movie's Streams sheet lists the remux's name, three lines padded with no-break
///   spaces and zero-width joiners, as one tidy line;
/// - the stream filters, set in Settings → Playback → Streams (4K switched off in Qualities, "remux" as the pattern to
///   hide): the movie's Streams sheet lists the 1080p stream alone under "1 stream hidden by your filters", whose Show
///   lists the 2160p remux again with the filters' note, and on iOS Hide hides it again;
/// - the row of addons over the list, with test_addon.py's three stream addons installed after the test addon (Tortuga
///   Second, Tortuga Slow answering 6 s late, Tortuga Broken failing): over the remux movie's details an addon's chip
///   lists its streams alone, Retry asks a failed addon again, All lists every group again and Refresh asks the addons
///   again past the app's cache; over the player after Next episode Tortuga Second's chip lists its streams alone; and
///   shots of the list over the details, in download mode and over the player (`testAddonRowShots`);
/// - one addon listing streams: the sheet has no row of addons, and Refresh, in its bar's trailing corner (on the Mac at
///   the trailing end of the sheet's title line, and on the Streams screen on its own in the toolbar's trailing side),
///   asks the addon again past the app's cache (`testOneAddonHasNoRowAndRefreshAsksAgain`);
/// - an addon whose every stream is malformed: its group shows the error and Retry, not an empty list
///   (`testAnAnswerOfMalformedStreamsShowsItsErrorAndRetry`);
/// - the sort button before Refresh, over the test addon's sorted movie: its menu's keys sort the list, which then has
///   no addon heading and starts at its top, the button filled (`testSortButtonSortsTheListFromItsTop`);
/// - stream suggestions over a phone user's mocked picks (`--dev-picks phone`, which seeds them on every kind of device
///   and turns the profile's "Suggest a stream" on): the test addon's sorted movie marks one stream "Suggested" above
///   its name with "Like your recent picks: …" under it, the list in the addon's order, and so does its movie of real
///   addons' texts (shots scrolled to the mark); with the setting off (`--dev-suggestions false`) no row is
///   marked (`testTheSuggestedStreamIsMarkedWithWhy`);
/// - shots of the rows' text over recorded and common addon texts (`testStreamRowShots`).
/// Each platform's own tests are in iOS/StreamsTests+iOS.swift and macOS/StreamsTests+macOS.swift.
final class StreamsTests: TortugaUITestCase {
    func testTapPlaysTheFirstStreamAndBackReturnsToStreams() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        let row = require("streams.row.0", timeout: 60, "the Streams screen lists the test addon's streams")
        require("streams.row.1", "the Streams screen lists the H.264 stream")
        requireGone("streams.row.2", timeout: 5, "the Streams screen lists no stream past the two it plays")
        shot("streams")
        activate(row)
        let video = require("player.video", "\(Self.tapping) on the first stream opens the player")
        let position = requirePlayback(8, timeout: 90)
        shot("playing at \(Int(position)) s")
        #if os(macOS)
        pointerOn(ready(video), at: CGVector(dx: 0.5, dy: 0.5))
        let back = require("player.back", timeout: 5, "the pointer over the video shows the controls")
        shot("controls")
        showControls(video)
        ready(back).click()
        requireGone("player.video", "the back button closes the player")
        #else
        requireGone("player.back", "the controls hide while the video plays")
        ready(video).tap()
        require("player.back", timeout: 5, "a tap on the video shows the controls")
        shot("controls")
        closePlayer(video, "the back button closes the player")
        #endif
        require("streams.row.0", "the back button returns to the Streams screen")
        shot("back on streams")
    }

    func testSheetOverDetailPlaysAndBackReturnsToDetail() throws {
        #if os(macOS)
        try launchDev(addon: true, open: ["board", "detail:movie:tt0111161"])
        #else
        try launchDev(addon: true, open: ["detail:movie:tt0111161"])
        #endif
        activate(require("detail.play", timeout: 60, "the movie's details show Play"))
        let row = require("streams.row.1", timeout: 60, "Play opens the Streams sheet with the H.264 stream")
        shot("streams sheet over the details")
        activate(row)
        let video = require("player.video", timeout: 20, "\(Self.tapping) on the H.264 stream opens the player")
        requireGone("streams.row.1", "the player replaces the Streams sheet")
        requirePlayback(2, timeout: 90)
        shot("playing")
        #if os(macOS)
        pointerOn(ready(video), at: CGVector(dx: 0.3, dy: 0.4))
        pointerOn(ready(video), at: CGVector(dx: 0.5, dy: 0.5))
        ready(require("player.back", timeout: 5, "the pointer over the video shows the controls")).click()
        requireGone("player.video", "the back button closes the player")
        #else
        closePlayer(video, "the controls' back button closes the player")
        #endif
        require("detail.play", "the back button returns to the details")
        if element("streams.row.1").exists { fail("back from the player, the Streams sheet is open again") }
        shot("back on the details")
    }

    func testFormattedNameReadsAsOneLine() throws {
        // iOS opens the Streams sheet over the details at the launch; the Mac by Play.
        #if os(macOS)
        try launchDev(addon: true, open: ["board", "detail:movie:tortuga-formatted"])
        ready(require("detail.play", timeout: 60, "the movie's details show Play")).click()
        require("streams.row.0", timeout: 60, "Play opens the formatted movie's Streams sheet")
        #else
        try launchDev(addon: true, open: ["detail:movie:tortuga-formatted", "streams:movie:tortuga-formatted:tortuga-formatted"])
        require("streams.row.0", timeout: 60, "the formatted movie's Streams sheet lists its streams")
        #endif
        requireText("streams.row.0", contains: "4K ⚡ 〈Remux〉 ★★★★★", "the remux's name")
        shot("formatted streams")
    }

    func testFiltersHideStreamsAndShowListsThem() throws {
        try launchDev(addon: true, open: ["board", StreamFilterTexts.movie])
        addMovieToLibrary()
        openStreamsSettings()
        shot("streams settings")
        setQuality("4k", shown: false, shotName: "qualities with 4K off")
        requireText("settings.playback.streams.qualities", contains: "All but 4K", "Qualities names the one hidden")
        setPattern("settings.playback.streams.exclude", to: "remux")
        requireText("settings.playback.streams.exclude", contains: "remux", "Hide streams matching shows its pattern")
        shot("streams settings with 4K and remux hidden")

        openMovieFromLibrary()
        activate(element("detail.play"))
        requireStreams(["1080p"], filter: StreamFilterTexts.hidden(1), "the filters hide the 2160p remux")
        requireNoFilteredNote("the test addon's group, the only one hiding streams")
        shot("streams with the remux hidden")
        activate(element("streams.filter.toggle"))
        requireStreams(["2160p", "1080p"], filter: StreamFilterTexts.showing(1), noted: [0], "Show lists the remux again")
        shot("streams with the hidden remux shown")
        #if os(iOS)
        activate(element("streams.filter.toggle"))
        requireStreams(["1080p"], filter: StreamFilterTexts.hidden(1), "Hide hides the remux again")
        #endif
    }

    /// The row of addons over the remux movie's Streams sheet with the four addons, by touch (by clicks): Refresh in the
    /// sheet's bar (the Mac: its heading), and All selected as the list opens; once Tortuga Slow answered (6 s late), a
    /// chip per addon in their order. Tortuga Second's chip lists its two streams alone, without its name over them, and
    /// is the one selected, where it was tapped (clicked) and as wide (a pick moves no chip); Tortuga Broken's (the row
    /// swiped, on the Mac scrolled, sideways to it where it runs past the sheet) lists its error and Retry, which asks it
    /// again: Broken stays selected and its error and Retry come back. All lists every addon's group again, each under
    /// its name. Under Tortuga Slow's chip Refresh asks it again past the app's cache: its group loads under its chip,
    /// still selected, and its first stream then reads a later answer.
    func testAddonRowListsOneAddonAndRefreshAsksAgain() throws {
        try launchWithStreamAddons(open: ["board", "detail:movie:tortuga-remux", "streams:movie:tortuga-remux:tortuga-remux"])
        require("streams.bar", timeout: 60, "the Streams sheet over the movie's details shows the row of addons")
        require("streams.refresh", "the sheet offers Refresh")
        require("streams.bar.all", timeout: 30, "the row offers All once two addons answered")
        requireSelectedChip("streams.bar.all", "the list opens on All")
        requireEveryAddonsChip("the row once Tortuga Slow answered")
        shot("addon row")

        let picked = pressInAddonRow("streams.bar.addon.1", "the row offers Tortuga Second's chip")
        requireSelectedChip("streams.bar.addon.1", "\(Self.tapping) on Tortuga Second's chip")
        requireChipStill("streams.bar.addon.1", at: picked, "\(Self.tapping) on Tortuga Second's chip")
        requireSecondAlone("\(Self.tapping) on Tortuga Second's chip")
        shot("Tortuga Second's streams")

        pressInAddonRow("streams.bar.addon.3", "the row offers Tortuga Broken's chip")
        requireBrokenAlone("\(Self.tapping) on Tortuga Broken's chip")
        shot("Tortuga Broken's error")
        activate(element("streams.group.0.retry"))
        requireBrokenAlone("\(Self.tapping) on Tortuga Broken's Retry")

        pressInAddonRow("streams.bar.all", "the row offers All")
        requireSelectedChip("streams.bar.all", "\(Self.tapping) on All")
        for (index, name) in Self.streamAddonNames.enumerated() {
            #if os(macOS)
            require("streams.group.\(index).name", timeout: 10, "a click on All lists \(name)'s group again, under its name")
            #else
            swipeUntilShown("streams.group.\(index).name", "a tap on All lists \(name)'s group again, under its name")
            #endif
        }

        pressInAddonRow("streams.bar.addon.2", "the row offers Tortuga Slow's chip")
        requireSelectedChip("streams.bar.addon.2", "\(Self.tapping) on Tortuga Slow's chip")
        let answer = requireSlowAnswer("\(Self.tapping) on Tortuga Slow's chip")
        activate(element("streams.refresh"))
        requireSlowAskedAgain(after: answer, "\(Self.tapping) on Refresh under Tortuga Slow's chip")
        shot("Tortuga Slow asked again")
    }

    /// The row over the player: the series' first episode playing from the test addon's stream without a binge group,
    /// with auto-play off (the default) the controls' Next episode brings the second episode's Streams sheet up over the
    /// player with the row of addons, and a tap (a click) on Tortuga Second's chip lists its two streams alone.
    func testAddonRowOverThePlayerListsOneAddon() throws {
        try askForTheNextEpisodesStream()
        require("streams.bar", "the Streams sheet over the player shows the row of addons")
        requireChip(1, named: "Tortuga Second", "the row over the player")
        pressInAddonRow("streams.bar.addon.1", "the row over the player offers Tortuga Second's chip")
        requireSelectedChip("streams.bar.addon.1", "\(Self.tapping) on Tortuga Second's chip over the player")
        requireSecondAlone("\(Self.tapping) on Tortuga Second's chip over the player")
        shot("Tortuga Second's streams over the player")
    }

    /// One addon listing streams, by touch (by clicks): of the test addon, which has no stream for the unnumbered
    /// series' episodes, and Tortuga Second only Second lists streams, so no addon has a chip. The episode's Streams
    /// sheet over its details has no row of addons, and Refresh in the sheet's bar (on the Mac at the trailing end of its
    /// heading's title line) asks Tortuga Second again past the app's cache: its first stream then reads a later answer,
    /// still with no row. On the Mac the episode's Streams screen on its own (a dev start) has Refresh in the toolbar's
    /// trailing side, which asks again alike.
    func testOneAddonHasNoRowAndRefreshAsksAgain() throws {
        try launchWithStreamAddons(
            ["test", "second"],
            open: ["board", "detail:series:tortuga-unnumbered", "streams:series:tortuga-unnumbered:tortuga-unnumbered:1"]
        )
        requireText("streams.row.0", contains: "Tortuga Second", timeout: 60, "the episode's Streams sheet lists Tortuga Second's streams")
        requireGone("streams.bar", timeout: 5, "with one addon listing streams the sheet has no row of addons")
        shot("one addon")
        let refresh = require("streams.refresh", "the sheet offers Refresh with one addon")
        #if os(macOS)
        let title = require("streams.title", "the sheet's heading").frame
        if refresh.frame.minY > title.maxY || refresh.frame.maxY < title.minY || refresh.frame.minX < title.maxX {
            fail("Refresh at \(refresh.frame) is not at the trailing end of the title's line, \(title)")
        }
        #endif
        var before = requireFirstAnswer("the sheet as it opens")
        activate(refresh)
        requireFirstAnsweredAgain(after: before, "\(Self.tapping) on Refresh in the sheet: Tortuga Second's")
        requireGone("streams.bar", timeout: 2, "after Refresh the sheet still has no row of addons")
        #if os(macOS)
        try launchWithStreamAddons(["test", "second"], open: ["streams:series:tortuga-unnumbered:tortuga-unnumbered:1"])
        requireText("streams.row.0", contains: "Tortuga Second", timeout: 60, "the episode's Streams screen lists Tortuga Second's streams")
        shot("one addon on the Streams screen")
        let inToolbar = app.toolbars.descendants(matching: .any).matching(identifier: "streams.refresh").firstMatch
        if !inToolbar.exists(within: 10) { fail("the Streams screen's toolbar doesn't offer Refresh (streams.refresh)") }
        if inToolbar.frame.midX < windowFrame.midX {
            fail("Refresh in the toolbar at x \(Int(inToolbar.frame.midX)), not on its trailing side (the window's middle is x \(Int(windowFrame.midX)))")
        }
        before = requireFirstAnswer("the Streams screen as it opens")
        ready(inToolbar).click()
        requireFirstAnsweredAgain(after: before, "a click on Refresh in the toolbar: Tortuga Second's")
        #endif
    }

    /// An addon whose every stream is malformed (test_addon.py's /malformed/ addon, after the test addon): the remux
    /// movie's Streams sheet over its details lists the test addon's streams, then Tortuga Malformed's group with the
    /// error that its answer can't be read and Retry, where an empty group would hide the failure.
    func testAnAnswerOfMalformedStreamsShowsItsErrorAndRetry() throws {
        let manifest = try addonURL
        var arguments = ["--dev", "--dev-addon", manifest, "--dev-addon", manifest.replacingOccurrences(of: "/manifest.json", with: "/malformed/manifest.json")]
        for screen in ["board", "detail:movie:tortuga-remux", "streams:movie:tortuga-remux:tortuga-remux"] { arguments += ["--dev-open", screen] }
        for setting in Self.harnessSettings { arguments += ["--dev-setting", setting] }
        launch(arguments)
        require("streams.row.0", timeout: 60, "the remux movie's Streams sheet lists the test addon's streams")
        #if os(iOS)
        // The list makes a group's rows as they scroll in: on the iPhone Duo's cover the second group lies below the
        // half-height sheet.
        swipeUntilShown("streams.group.1.name", "the Streams sheet lists Tortuga Malformed's group")
        #endif
        require("streams.group.1.error", timeout: 30, "Tortuga Malformed's group shows its error")
        require("streams.group.1.retry", "Tortuga Malformed's group offers Retry")
        shot("malformed answer")
    }

    /// The sort button, by touch (by clicks), over the test addon's sorted movie, whose seven releases come out of
    /// order. Its Streams sheet over the details lists them in the addon's order under the addon's name, the sort button
    /// (`streams.sort`) just before Refresh, as tall and level with it, "Sort by: Addon order", not filled (not
    /// selected). With the list scrolled to its end (iOS), the button's menu picks Resolution, then Largest size first
    /// (iOS: the menu stays open between them and a tap outside closes it; the Mac's closes on each pick and opens
    /// again): the list is then one group without a heading, from its top, in that order, and the button reads "Sort by:
    /// Resolution, Largest size first", filled. On the Mac the Streams screen on its own has the sort in the toolbar
    /// before Refresh, whose Source quality lists the remux and then the Blu-ray first.
    func testSortButtonSortsTheListFromItsTop() throws {
        try launchDev(addon: true, open: StreamSortTexts.open)
        require("streams.row.0", timeout: 60, "the sorted movie's Streams sheet over its details lists its streams")
        shot("sort-addon-order")
        requireRows(Array(StreamSortTexts.addonOrder.prefix(2)), "the list as it opens, in the addon's order")
        require("streams.group.0.name", "the addon's order heads the list with the addon's name")
        let sort = require("streams.sort", "the sheet offers the sort button")
        requireSortButton(StreamSortTexts.addonOrderLabel, selected: false, "the sort button in the addon's order")
        requireSortBesideRefresh("the sheet's sort button")
        #if os(iOS)
        swipeUntilShown("streams.row.6", "the list scrolls to its last stream")
        #endif

        activate(sort)
        activate(require("streams.sort.option.\(StreamSortTexts.resolution)", timeout: 5, "the sort's menu lists Resolution"))
        requireSortButton(StreamSortTexts.resolutionLabel, selected: true, "\(Self.tapping) on Resolution")
        #if os(macOS)
        activate(sort)
        let largest = require("streams.sort.option.\(StreamSortTexts.largest)", timeout: 5, "the sort's menu, opened again, lists Largest size first")
        #else
        let largest = require("streams.sort.option.\(StreamSortTexts.largest)", timeout: 5, "the sort's menu, still open, lists Largest size first")
        #endif
        activate(largest)
        requireSortButton(StreamSortTexts.twoKeysLabel, selected: true, "\(Self.tapping) on Largest size first")
        #if os(iOS)
        shot("sort-menu")
        // Outside the menu, which covers the title on an iPhone: the bar's empty leading side, level with the button,
        // where the rows' text starts.
        let leading = CGVector(dx: element("streams.row.0").frame.minX, dy: sort.frame.midY)
        app.coordinate(withNormalizedOffset: .zero).withOffset(leading).tap()
        requireGone("streams.sort.option.0", timeout: 5, "a tap outside the sort's menu closes it")
        #endif

        requireRows(Array(StreamSortTexts.byResolutionThenSize.prefix(3)), "the list sorted by resolution, then largest size first")
        requireGone("streams.group.0.name", timeout: 2, "the sorted list has no addon heading")
        let first = element("streams.row.0").frame
        if first.minY < sort.frame.maxY - 1 {
            fail("the sorted list doesn't start at its top: its first row at y \(Int(first.minY)), the sort button's foot at y \(Int(sort.frame.maxY))")
        }
        shot("sort-sorted")
        #if os(iOS)
        swipeUntilShown("streams.row.6", "the sorted list scrolls to its last stream")
        #endif
        requireText("streams.row.6", contains: "WEBRip", "the sorted list's last row, the WEBRip, whose resolution its text doesn't say")

        #if os(macOS)
        try launchDev(addon: true, open: ["streams:movie:tortuga-sorted:tortuga-sorted"])
        require("streams.row.0", timeout: 60, "the sorted movie's Streams screen lists its streams")
        let inToolbar = app.toolbars.descendants(matching: .any).matching(identifier: "streams.sort").firstMatch
        let refresh = app.toolbars.descendants(matching: .any).matching(identifier: "streams.refresh").firstMatch
        if !inToolbar.exists(within: 10) { return fail("the Streams screen's toolbar doesn't offer the sort (streams.sort)") }
        if inToolbar.frame.maxX > refresh.frame.minX + 1 {
            fail("the toolbar's sort at \(inToolbar.frame) isn't before Refresh at \(refresh.frame)")
        }
        requireSortButton(StreamSortTexts.addonOrderLabel, selected: false, "the toolbar's sort in the addon's order")
        ready(inToolbar).click()
        pickMenuEntry(require("streams.sort.option.\(StreamSortTexts.sourceQuality)", timeout: 5, "the toolbar's sort menu lists Source quality"))
        requireSortButton("Sort by: Source quality", selected: true, "a click on Source quality in the toolbar's menu")
        requireRows(["2160p REMUX", "1080p BluRay"], "the Streams screen sorted by source quality")
        shot("sort-toolbar-sorted")
        #endif
    }

    func testTheSuggestedStreamIsMarkedWithWhy() throws {
        let picks = ["--dev-picks", "phone"]
        let reason = "Like your recent picks: "
        try launchDev(addon: true, open: ["streams:movie:tortuga-sorted:tortuga-sorted"], arguments: picks)
        require("streams.row.6", timeout: 60, "the sorted movie's Streams screen lists its seven streams")
        let rows = (0..<7).map { element("streams.row.\($0)") }
        _ = waitUntil(10) { rows.contains { allText(of: $0).contains("Suggested") } }
        let marked = rows.filter { allText(of: $0).contains("Suggested") }
        if marked.count != 1 { fail("\(marked.count) of the sorted movie's rows are marked Suggested, not one") }
        if let row = marked.first, !allText(of: row).contains(reason) {
            fail("the suggested row says \"\(allText(of: row))\", without its reason")
        }
        shot("suggested stream")

        try launchDev(addon: true, open: ["streams:movie:tortuga-real-streams:tortuga-real-streams"], arguments: picks)
        require("streams.row.0", timeout: 60, "the real texts' Streams screen lists its streams")
        let suggested = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", reason)).firstMatch
        // A Mac's row out of sight reads as a zero-size frame at the screen's bottom: the wheel's short steps there.
        scrollTo(suggested, steps: 40, "a row of the real texts' list is marked Suggested")
        shot("suggested stream among real addons' rows")

        try launchDev(addon: true, open: ["streams:movie:tortuga-sorted:tortuga-sorted"], arguments: picks + ["--dev-suggestions", "false"])
        require("streams.row.6", timeout: 60, "the sorted movie's Streams screen lists its seven streams")
        if rows.contains(where: { allText(of: $0).contains("Suggested") }) { fail("a row is marked Suggested with the setting off") }
    }

    /// Shots of the stream rows' text, on the iPad in landscape: the test addon's anime episode of an addon's
    /// recorded answer with its second stream played, the episode's last played
    /// (`stream-rows-anime`), the recorded answer with the longest descriptions (`stream-rows-long`), and the texts most
    /// people see, two formatters' emoji-led texts, plain text and an addon's plain rows (`stream-rows-common`, then
    /// scrolled to the plain rows, `stream-rows-plain`). It checks nothing of the rows' look, so it runs as it is
    /// on a build without it.
    func testStreamRowShots() throws {
        // Four launches and a play.
        executionTimeAllowance = 600
        #if os(iOS)
        if isPad {
            Self.quitAndTurn(to: .landscapeLeft)
            addTeardownBlock { Self.quitAndTurn(to: .portrait) }
        }
        #endif
        let addon = try addonURL
        let data = "rows-" + UUID().uuidString.prefix(8)
        let episode = ["board", "detail:series:tortuga-real-anime", "streams:series:tortuga-real-anime:tortuga-real-anime:1:1"]
        launchDev(keepingData: data, addon: addon, open: episode)
        activate(require("streams.row.1", timeout: 60, "the anime episode's Streams sheet lists its recorded streams"))
        require("player.video", timeout: 20, "\(Self.tapping) on the second stream opens the player")
        requirePlayback(1, timeout: 90)
        #if os(iOS)
        // The iPhone's player turned it to landscape.
        if !isPad { Self.quitAndTurn(to: .portrait) }
        #endif
        launchDev(keepingData: data, addon: addon, open: episode)
        requireText("streams.row.1", contains: "Last played", timeout: 60, "the stream played is the episode's last played")
        shot("stream-rows-anime")

        try launchDev(addon: true, open: ["board", "detail:movie:tortuga-real-long", "streams:movie:tortuga-real-long:tortuga-real-long"])
        require("streams.row.0", timeout: 60, "the long descriptions' Streams sheet lists its recorded streams")
        shot("stream-rows-long")

        try launchDev(addon: true, open: ["board", "detail:movie:tortuga-common-streams", "streams:movie:tortuga-common-streams:tortuga-common-streams"])
        require("streams.row.0", timeout: 60, "the common texts' Streams sheet lists its streams")
        shot("stream-rows-common")
        _ = scrollTo(element("streams.row.9"), steps: 40, "the common texts' list reaches the plain rows' last one")
        shot("stream-rows-plain")
    }

    /// Shots of the row of addons, with the four addons, each once every addon answered: the Streams
    /// sheet over the remux movie's details (`streams-row-detail`), the sheet Download opens on them, in download mode
    /// (`streams-row-download`), and the second episode's sheet over the player after Next episode on the series' first
    /// (`streams-row-player`). It checks nothing of the row, so it runs as it is on a build without it.
    func testAddonRowShots() throws {
        // Three launches and a play: longer than the default 5 minutes on a slow simulator.
        executionTimeAllowance = 600
        try launchWithStreamAddons(open: ["board", "detail:movie:tortuga-remux", "streams:movie:tortuga-remux:tortuga-remux"])
        require("streams.row.0", timeout: 60, "the Streams sheet over the remux movie's details lists its streams")
        requireTortugaSlowListed("the Streams sheet over the remux movie's details")
        shot("streams-row-detail")

        try launchWithStreamAddons(open: ["board", "detail:movie:tortuga-remux"])
        #if os(macOS)
        ready(require("detail.download", timeout: 60, "the remux movie's details offer Download")).click()
        #else
        require("detail.play", timeout: 60, "the remux movie's details show Play")
        // Download, or ⋯'s Download where the action row has no room for it.
        if element("detail.download").exists(within: 5) {
            ready("detail.download").tap()
        } else {
            ready(require("detail.overflow", "the action row has Download or ⋯")).tap()
            pickMenuEntry(require("detail.overflow.download", timeout: 5, "the action row's ⋯ lists Download"))
        }
        #endif
        require("streams.notice", timeout: 30, "Download opens the Streams sheet in download mode with its notice")
        requireTortugaSlowListed("the Streams sheet in download mode")
        shot("streams-row-download")

        try askForTheNextEpisodesStream()
        requireTortugaSlowListed("the second episode's Streams sheet over the player")
        shot("streams-row-player")
    }

    /// With the four addons, the series' first episode from its Streams sheet over the details, its first stream the
    /// test addon's without a binge group, played a second; then the controls' Next episode: with auto-play off (the
    /// default) the second episode's Streams sheet comes up over the player.
    private func askForTheNextEpisodesStream() throws {
        try launchWithStreamAddons(open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"])
        requireText("streams.row.0", contains: "720p", timeout: 60, "the first episode's Streams sheet lists the stream without a binge group first")
        activate(element("streams.row.0"))
        let video = require("player.video", timeout: 20, "\(Self.tapping) on the stream opens the player")
        requirePlayback(1, timeout: 90)
        #if os(macOS)
        playNextEpisode(video)
        require("streams.row.0", timeout: 60, "Next episode from a stream without a binge group brings the second episode's Streams sheet")
        if !video.exists { fail("the second episode's Streams sheet came up without the player under it") }
        #else
        tapPlayerControl(
            "player.next", video: video, until: { element("streams.title").exists || element("streams.row.0").exists },
            "Next episode from a stream without a binge group brings the second episode's Streams sheet over the player"
        )
        require("streams.row.0", timeout: 60, "the second episode's Streams sheet lists its streams")
        if element("detail.play").exists, element("detail.play").isHittable {
            fail("the second episode's Streams sheet came up over the series' details, not over the player")
        }
        #endif
    }

    #if os(macOS)
    /// Next episode from the controls, the info closed first (its entry in the options menu again).
    func playNextEpisode(_ video: XCUIElement) {
        if element("player.info.panel").exists {
            showControls(video)
            togglePlaybackInfo()
            requireGone("player.info.panel", timeout: 5, "Playback info again closes the info")
        }
        showControls(video)
        ready(require("player.next", timeout: 5, "the controls offer Next episode")).click()
    }

    /// Brings the controls up, or keeps them up a while longer, by moving the pointer over the video (a hover where the
    /// pointer already is moves nothing, so the auto-hide timer would run on).
    func showControls(_ video: XCUIElement) {
        pointerOn(video, at: CGVector(dx: 0.3, dy: 0.4))
        pointerOn(video, at: CGVector(dx: 0.5, dy: 0.5))
        require("player.playPause", timeout: 5, "the pointer over the video shows the controls")
    }
    #endif
}
