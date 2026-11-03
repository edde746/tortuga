import XCTest

/// Streams and the player with the remote, on the test addon's streams for a Cinemeta movie, for its own series and for
/// its movie of an addon's custom formatting:
/// - the Streams screen on its own (a dev start): focus starts on the first stream and Down and Up move it between the
///   rows, Select plays the first one for at least 8 s, and Menu backs out of the player to the Streams screen;
/// - the Streams sheet over the movie's details: Select on Play opens it with focus on the first stream, Select on the
///   H.264 stream plays it in the player that replaces the sheet, and Menu backs out of the player to the details, the
///   sheet closed and focus on Play;
/// - the next episode's Streams panel over the player, on the series under the Home tab: Select on its first episode's
///   row opens that episode's panel, whose stream without a binge group plays; up next comes up with focus on it,
///   offering the second episode, and with nothing pressed its countdown brings the second episode's panel up over the
///   player with focus on its first stream, and the stream picked there plays in the same player; Menu backs out of
///   the player to the details with focus back on the first episode's row, which opened the streams, and the remote
///   moves it on to the second episode;
/// - an addon's custom formatting: the Streams panel over the movie's details lists the remux's name, three lines
///   padded with no-break spaces and zero-width joiners, as one tidy line, its row focused;
/// - the episode's Streams panel: its episode line, secondary text, stands out more with Increase Contrast on, as the
///   system's secondary label does (`requireFollowsIncreaseContrast`);
/// - the stream filters, set in Settings → Playback → Streams (4K switched off in Qualities, "remux" typed as the
///   pattern to hide): the movie's Streams panel lists the 1080p stream alone, focused, under "1 stream hidden by your
///   filters"; Up reaches Show, which lists the 2160p remux again with the filters' note, focus staying on it;
/// - auto-play (4K hidden): First match plays the 1080p stream from Play with no panel, the player's info naming the
///   stream and auto-play's pick; the remux picked from Choose a stream's panel (Right from Play; Show) is what Last
///   stream played replays; an include pattern matching nothing (First match again) opens the Streams panel with the
///   notice instead, Show focused; an auto-play link (`stremio:///detail/…?autoPlay=true`) to an episode whose addon
///   answers no streams (test_addon.py's tortuga-unnumbered) opens the Streams panel with the notice over the empty
///   list's text;
/// - Continue Watching on Home, Select on its focused card, resumes the movie as the details' Resume does, over its
///   details (Menu from the player returns there), with no panel: the movie left a few seconds into the 1080p stream
///   picked by hand, the card plays that stream with auto-play off (the stream history's, no Picked by row), and
///   auto-play's pick, the 2160p remux, with First match (no filters set), each from where it was left; a movie in
///   progress (`--dev-progress`) that never played from a stream here opens its Streams panel over its details with
///   auto-play off, and Menu closes it with focus on Play; Play/Pause on the card plays it as Select does (First match:
///   the player at once), opening no menu, and so does Play/Pause on the movie in Library's Continue Watching.
/// - the row of addons over the list, with test_addon.py's three stream addons installed after the test addon (Tortuga
///   Second, Tortuga Slow answering 6 s late, Tortuga Broken failing), by the remote: over the remux movie's details Up
///   from the first stream enters the row on the selected chip, Left and Right stop at its ends, Select on a chip lists
///   that addon's streams alone with focus staying on it, Retry asks a failed addon again, All lists every group again,
///   and Refresh, Up from the row in the panel's corner, asks the addons again past the app's cache, focus staying on
///   it; over the player after up next Tortuga Second's chip lists its streams alone; and shots of the list over the
///   details and over the player (`testAddonRowShots`);
/// - one addon listing streams: the panel has no row of addons, and Up from the first stream reaches Refresh in the
///   panel's top trailing corner, level with the title, which asks the addon again past the app's cache
///   (`testRefreshInThePanelsCornerAsksAgain`);
/// - stream suggestions over a phone user's mocked picks (`--dev-picks phone`, which seeds them on every kind of device
///   and turns the profile's "Suggest a stream" on): Down along the test addon's sorted movie moves one row a press,
///   and one row, focused, says "Suggested" above its name and "Like your recent picks: …" under it; Down along its
///   movie of real addons' texts reaches a row marked so; with the setting off (`--dev-suggestions false`) no row is
///   marked (`testTheSuggestedStreamIsMarkedWithWhy`);
/// - the sort button before Refresh, over the test addon's sorted movie: its options' panel picks keys that sort the
///   list, which then has no addon heading and starts at its top, the button filled, and Menu closes the options
///   before the list (`testSortButtonSortsTheListFromItsTop`); and a shot of Settings → Playback → Streams with the
///   stream filters' rows (`testStreamSettingsShots`); shots of the rows' text over recorded and common addon texts
///   (`testStreamRowShots`).
final class StreamsTests: TortugaUITestCase {
    func testSelectPlaysTheFirstStreamAndMenuReturnsToStreams() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        requireFocus("streams.row.0", timeout: 60, "the first stream has focus once the Streams screen lists them")
        shot("streams")
        press(.down)
        requireFocus("streams.row.1", "Down moves focus to the next stream")
        press(.up)
        requireFocus("streams.row.0", "Up moves focus back to the first stream")
        press(.select)
        require("player.video", "Select on the first stream opens the player")
        let position = requirePlayback(8, timeout: 90)
        shot("playing at \(Int(position)) s")
        leavePlayer()
        require("streams.row.0", "Menu returns to the Streams screen")
        if !waitUntil(5, { focusedId?.hasPrefix("streams.row.") == true }) {
            fail("back on the Streams screen, focus is on \(focusedId ?? "nothing"), not a stream")
        }
        shot("back on streams")
    }

    func testSheetOverDetailPlaysAndMenuReturnsToDetail() throws {
        try launchDev(addon: true, open: ["detail:movie:tt0111161"])
        requireFocus("detail.play", timeout: 60, "Play has focus once the movie's details show")
        press(.select)
        requireFocus("streams.row.0", timeout: 60, "Select on Play opens the Streams sheet with focus on the first stream")
        press(.down)
        requireFocus("streams.row.1", "Down moves focus to the H.264 stream")
        shot("streams sheet over the details")
        press(.select)
        require("player.video", timeout: 20, "Select on the H.264 stream opens the player")
        requireGone("streams.row.1", "the player replaces the Streams sheet")
        requirePlayback(2, timeout: 90)
        shot("playing")
        leavePlayer()
        requireFocus("detail.play", "Menu returns to the details with focus on Play")
        if element("streams.row.1").exists { fail("back from the player, the Streams sheet is open again") }
        shot("back on the details")
    }

    func testFormattedNameReadsAsOneLine() throws {
        try launchDev(addon: true, open: ["detail:movie:tortuga-formatted", "streams:movie:tortuga-formatted:tortuga-formatted"])
        requireFocus("streams.row.0", timeout: 60, "the formatted movie's Streams panel focuses its first stream")
        requireText("streams.row.0", contains: "4K ⚡ 〈Remux〉 ★★★★★", "the remux's name")
        shot("formatted streams")
    }

    func testTheEpisodeLineFollowsIncreaseContrast() throws {
        try launchDev(addon: true, open: ["detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"])
        requireFocus("streams.row.0", timeout: 60, "the episode's Streams panel focuses its first stream")
        requireFollowsIncreaseContrast(require("streams.subtitle", "the Streams panel heads its list with the episode line"), "the episode line")
    }

    func testNextEpisodesPanelOverThePlayerPlaysAndMenuReturnsFocusToDetail() throws {
        // test_addon.py's own series: three 20 s episodes, each with a stream without a binge group, then one in one.
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-series"])
        requireFocus("detail.play", timeout: 60, "Play has focus once the series' details show")
        requireShelfContent("detail.episode", "the series' details list its episodes")
        walkFocus(to: "detail.episode.0", "the remote reaches the first episode")
        press(.select)
        requireFocus("streams.row.0", timeout: 60, "Select on the episode opens its Streams panel, the stream without a binge group focused")
        press(.select)
        require("player.video", timeout: 20, "Select on the stream opens the player")
        requirePlayback(1, timeout: 90)
        require("player.upNext", timeout: 40, "the end of the 20 s episode offers the next one")
        requireFocus("player.upNext", timeout: 5, "up next has focus as it comes up")
        let label = promptLabel("player.upNext")
        if !label.contains("S1E2") { fail("up next reads '\(label)', not the next episode, S1E2") }
        shot("up next")
        requireFocus("streams.row.0", timeout: 30, "with nothing pressed up next's countdown brings the next episode's Streams panel over the player, its first stream focused")
        if !element("player.video").exists { fail("the next episode's Streams panel came up without the player under it") }
        moveFocus(to: "streams.row.1", with: .down, "Down moves focus to the stream in a binge group")
        shot("the next episode's streams over the player")
        press(.select)
        requireGone("streams.row.1", "picking the stream closes the panel")
        require("player.video", "the stream picked plays in the player")
        requirePlayback(1, timeout: 60)
        shot("the next episode playing")
        leavePlayer()
        requireFocus("detail.episode.0", "Menu returns to the series' details with focus on the episode that opened the streams")
        shot("back on the details")
        walkFocus(to: "detail.episode.1", "back on the details, the remote moves focus to the second episode")
    }

    func testFiltersHideStreamsAndShowListsThem() throws {
        try launchDev(addon: true, open: ["board", StreamFilterTexts.movie])
        addMovieToLibrary()
        leaveDetail()
        openStreamsSettings(at: "settings.playback.streams.qualities")
        moveFocus(to: "settings.playback.streams.exclude", with: .down, limit: 8, "Down reaches the Streams group's last row")
        shot("streams settings")
        setQuality("4k", shown: false, shotName: "qualities with 4K off")
        requireText("settings.playback.streams.qualities", contains: "All but 4K", "Qualities names the one hidden")
        setPattern("settings.playback.streams.exclude", to: "remux")
        requireText("settings.playback.streams.exclude", contains: "remux", "Hide streams matching shows its pattern")
        shot("streams settings with 4K and remux hidden")
        press(.menu)

        openMovieFromLibrary()
        press(.select)
        requireFocus("streams.row.0", timeout: 60, "Select on Play opens the Streams panel with focus on the stream left")
        requireStreams(["1080p"], filter: StreamFilterTexts.hidden(1), "the filters hide the 2160p remux")
        requireNoFilteredNote("the test addon's group, the only one hiding streams")
        press(.up)
        requireFocus("streams.filter.toggle", "Up from the first stream reaches Show")
        shot("streams with the remux hidden, Show focused")
        press(.select)
        requireStreams(["2160p", "1080p"], filter: StreamFilterTexts.showing(1), noted: [0], "Show lists the remux again")
        requireFocus("streams.filter.toggle", "focus stays on the filter bar's button, Hide now")
        shot("streams with the hidden remux shown")
        press(.down)
        requireFocus("streams.row.0", "Down from Hide reaches the remux")
    }

    func testAutoPlayPlaysTheFirstMatchThenTheLastStreamAndListsWhenNothingMatches() throws {
        // Three trips to Settings and three plays: longer than the default 5 minutes on a slow simulator.
        executionTimeAllowance = 600
        try launchDev(addon: true, open: ["board", StreamFilterTexts.movie])
        addMovieToLibrary()
        leaveDetail()
        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.firstMatch)
        setQuality("4k", shown: false)
        shot("auto-play on the first match")
        press(.menu)

        // First match: the 1080p stream, the first left by the filters, plays at once.
        openMovieFromLibrary()
        require("detail.chooseStream", "with auto-play on, the details offer Choose a stream")
        shot("details with auto-play on")
        press(.select)
        require("player.video", timeout: 60, "Select on Play starts auto-play's pick in the player")
        if element("streams.row.0").exists { fail("with auto-play on, Play opened the Streams panel") }
        requirePlaying()
        openPlayerInfo()
        requireInfoRow("Name", contains: "Tortuga Test 1080p", "auto-play's first match")
        requireInfoRow("Picked by", contains: StreamFilterTexts.pickedFirstMatch, "auto-play's first match")
        shot("info of the first match")
        leavePlayer()

        // Choose a stream lists them; the remux, shown by Show, is picked by hand.
        requireFocus("detail.play", "Menu returns to the details with focus on Play")
        moveFocus(to: "detail.chooseStream", with: .right, "Right from Play reaches Choose a stream")
        press(.select)
        requireFocus("streams.row.0", timeout: 60, "Choose a stream opens the Streams panel with focus on the first stream")
        requireStreams(["1080p"], filter: StreamFilterTexts.hidden(1), "Choose a stream lists the streams, 4K hidden")
        press(.up)
        requireFocus("streams.filter.toggle", "Up from the first stream reaches Show")
        press(.select)
        requireStreams(["2160p", "1080p"], filter: StreamFilterTexts.showing(1), noted: [0], "Show lists the remux again")
        moveFocus(to: "streams.row.0", with: .down, "Down from Hide reaches the remux")
        press(.select)
        require("player.video", timeout: 20, "Select on the remux opens the player")
        requirePlaying()
        // It resumes where the 1080p stream stopped, a few seconds into the remux's Opening chapter: the skip button is
        // up as the player opens, with focus.
        if element("player.skip").exists {
            requireFocus("player.skip", timeout: 5, "the skip button up as the player resumes in the intro has focus")
            shot("skip button focused on resume")
        }
        openPlayerInfo()
        requireInfoRow("Name", contains: "Tortuga Test 2160p", "the remux picked from the list")
        if infoRow("Picked by").exists { fail("a stream picked from the list says auto-play picked it") }
        leavePlayer()
        require("detail.play", "Menu returns to the details")
        leaveDetail()

        // Last stream played: the remux, though the filters hide it.
        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.reuseLastLink)
        press(.menu)
        openMovieFromLibrary()
        press(.select)
        require("player.video", timeout: 60, "Select on Play starts auto-play's pick in the player")
        requirePlaying()
        openPlayerInfo()
        requireInfoRow("Name", contains: "Tortuga Test 2160p", "auto-play's last stream played")
        requireInfoRow("Picked by", contains: StreamFilterTexts.pickedLastLink, "auto-play's last stream played")
        shot("info of the last stream played")
        leavePlayer()
        require("detail.play", "Menu returns to the details")
        leaveDetail()

        // Nothing passes: the Streams panel opens with the notice, Show focused.
        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.firstMatch)
        setPattern("settings.playback.streams.include", to: "no-such-stream")
        shot("an include pattern matching nothing")
        press(.menu)
        openMovieFromLibrary()
        press(.select)
        require("streams.notice", timeout: 60, "with nothing passing the filters, Play opens the Streams panel")
        requireText("streams.notice", contains: StreamFilterTexts.noMatch, "the Streams panel's notice")
        requireStreams([], filter: StreamFilterTexts.hidden(2), "the filters hide both streams")
        requireFocus("streams.filter.toggle", "with every stream hidden, Show has focus")
        if element("player.video").exists { fail("with nothing passing the filters, the player opened") }
        shot("streams with auto-play's notice")
    }

    func testAutoPlayNoticeShowsOverAnEmptyList() throws {
        try launchDev(addon: true)
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openFromOutside(URL(string: "stremio:///detail/series/tortuga-unnumbered/tortuga-unnumbered%3A1?autoPlay=true")!)
        require("streams.empty", timeout: 60, "with no streams to pick, the auto-play link opens the Streams panel with its empty text")
        require("streams.notice", timeout: 5, "the Streams panel's notice shows over the empty list")
        requireText("streams.notice", contains: StreamFilterTexts.noMatch, "the Streams panel's notice over the empty list")
        if element("player.video").exists { fail("with no streams, the player opened") }
        shot("auto-play's notice over an empty list")
    }

    func testContinueWatchingResumesAsTheDetailsResumeDoes() throws {
        // Three plays and a trip to Settings.
        executionTimeAllowance = 600
        try launchDev(addon: true, open: ["board", StreamFilterTexts.movie])
        // Into Continue Watching: the movie, never played, lists its streams from Play (auto-play off, the default); the
        // 1080p stream picked there plays a few seconds and is closed paused, the stream the movie last played from.
        requireFocus("detail.play", timeout: 60, "Play has focus once the movie's details show")
        press(.select)
        requireFocus("streams.row.0", timeout: 60, "Off: Play on the movie, never played, opens the Streams panel")
        press(.down)
        requireFocus("streams.row.1", "Down moves focus to the 1080p stream")
        requireText("streams.row.1", contains: "1080p", "the movie's second stream")
        press(.select)
        require("player.video", timeout: 20, "Select on the 1080p stream opens the player")
        // Where it is left: here or a little past it.
        let left = requirePlayback(4, timeout: 90)
        press(.select)
        require("player.scrub", timeout: 5, "Select pauses and brings the controls up")
        leavePlayer()
        require("detail.play", "Menu returns to the details")
        leaveDetail()

        // Off: Select on the card plays the stream the movie last played from at once, from where it was left.
        requireShelfContent("board.cw", "Home lists the movie in Continue Watching")
        walkFocus(to: "board.cw.0", "the remote reaches the movie's Continue Watching card")
        shot("home: the movie's Continue Watching card focused")
        press(.select)
        require("player.video", timeout: 60, "Off: Select on the movie's Continue Watching card opens the player")
        if element("streams.row.0").exists { fail("Off: the movie's Continue Watching card opened the Streams panel") }
        var start = requirePlayback(0.5, timeout: 90)
        if start < left - 1 { fail("Off: the player started at \(start) s, not from where the movie was left (\(left) s)") }
        requirePlayback(start + 2, timeout: 30)
        openPlayerInfo()
        requireInfoRow("Name", contains: "Tortuga Test 1080p", "Off: the movie's Continue Watching card")
        if infoRow("Picked by").exists { fail("Off: the stream the movie last played from says auto-play picked it") }
        shot("Off: the last stream from Continue Watching")
        leavePlayer()
        require("detail.play", "Off: Menu returns to the details under the player")
        leaveDetail()

        // First match (no filters set): the card plays auto-play's pick, the 2160p remux, at once, from where it was left.
        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.firstMatch)
        press(.menu)
        focusTabBar()
        moveFocus(to: "nav.board", with: .left, "Left reaches the Home tab")
        // Down into Home first: from the Home tab the card lies down and to the left, and the walk's Left stays in the tab
        // bar.
        requireShelfContent("board.cw", "Home lists the movie in Continue Watching")
        press(.down)
        walkFocus(to: "board.cw.0", "the remote reaches the movie's Continue Watching card")
        press(.select)
        require("player.video", timeout: 60, "First match: Select on the movie's Continue Watching card opens the player")
        if element("streams.row.0").exists { fail("First match: the movie's Continue Watching card opened the Streams panel") }
        start = requirePlayback(0.5, timeout: 90)
        if start < left - 1 { fail("First match: the player started at \(start) s, not from where the movie was left (\(left) s)") }
        requirePlayback(start + 2, timeout: 30)
        openPlayerInfo()
        requireInfoRow("Name", contains: "Tortuga Test 2160p", "First match: the movie's Continue Watching card")
        requireInfoRow("Picked by", contains: StreamFilterTexts.pickedFirstMatch, "First match: the movie's Continue Watching card")
        shot("First match: auto-play's pick from Continue Watching")
        leavePlayer()
        require("detail.play", "First match: Menu returns to the details under the player")
        shot("First match: back from the player on the details")
    }

    func testContinueWatchingListsTheStreamsWithNoStreamToResume() throws {
        // The movie part-way in Continue Watching, never played from a stream here: no stream history.
        let addon = try addonURL
        launch(
            ["--dev", "--dev-addon", addon, "--dev-progress", "movie:tt0111161:tt0111161:600:6000"]
                + Self.harnessSettings.flatMap { ["--dev-setting", $0] }
        )
        requireShelfContent("board.cw", timeout: 90, "Home shows the movie in progress in Continue Watching")
        walkFocus(to: "board.cw.0", "the remote reaches the movie's Continue Watching card")
        press(.select)
        requireFocus("streams.row.0", timeout: 60, "Off: Select on the card of a movie with no stream to resume opens the Streams panel")
        if element("player.video").exists { fail("Off: the card of a movie with no stream to resume opened the player") }
        requireText("streams.row.1", contains: "1080p", "the movie's streams")
        shot("Off: the streams over the details from Continue Watching")
        press(.menu)
        requireGone("streams.row.0", "Menu closes the Streams panel")
        requireFocus("detail.play", "Menu returns to the movie's details under the Streams panel with focus on Play")
    }

    func testPlayPauseOnAContinueWatchingCardPlaysItAndOpensNoMenu() throws {
        // Two plays and a trip to Settings. The movie part-way in Continue Watching, 5 s into the remux's own 30 s (a
        // known length of 600 s or more would make the 30 s file an error video); with First match the card's Select
        // plays auto-play's pick at once.
        executionTimeAllowance = 600
        try launchDev(addon: true, progress: ["movie:tt0111161:tt0111161:5:30"])
        requireShelfContent("board.cw", timeout: 90, "Home shows the movie in progress in Continue Watching")
        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.firstMatch)
        press(.menu)
        focusTabBar()
        moveFocus(to: "nav.board", with: .left, "Left reaches the Home tab")
        requireShelfContent("board.cw", "Home lists the movie in Continue Watching")
        press(.down)
        walkFocus(to: "board.cw.0", "the remote reaches the movie's Continue Watching card")
        press(.playPause)
        requirePlayerWithoutMenu("board.cw.0", "Play/Pause on the Continue Watching card")
        shot("Play/Pause on the Continue Watching card")
        leavePlayer()
        require("detail.play", "Menu returns to the movie's details under the player")
        leaveDetail()

        // Library's Continue Watching lists the same movie: Play/Pause on its poster plays it too.
        focusTabBar()
        moveFocus(to: "nav.library", with: .right, "Right reaches the Library tab")
        press(.down)
        walkFocus(to: "library.view", "Down reaches the Library's view menu")
        press(.select)
        require("library.view.panel", timeout: 5, "Select on the view menu opens its panel")
        moveFocus(to: "library.view.option.1", with: .down, "Down moves focus to Continue Watching")
        press(.select)
        requireGone("library.view.panel", "picking Continue Watching closes the panel")
        require("library.item.0", timeout: 10, "Library's Continue Watching lists the movie")
        for _ in 0..<3 where focusInTabBar { press(.down) }
        moveFocus(to: "library.item.0", with: .down, "Down reaches the movie in Library's Continue Watching")
        press(.playPause)
        requirePlayerWithoutMenu("library.item.0", "Play/Pause on the movie in Library's Continue Watching")
        shot("Play/Pause in Library's Continue Watching")
    }

    /// After a Play/Pause on `id`: the player opens and plays, without `id`'s menu or the Streams panel. Fails with `what`.
    private func requirePlayerWithoutMenu(_ id: String, _ what: String) {
        let menu = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "\(id).menu.")).firstMatch
        let video = element("player.video")
        _ = waitUntil(60) { video.exists || menu.exists }
        if menu.exists { fail("\(what) opens its menu (\(menu.identifier))") }
        if !video.exists { fail("\(what) doesn't open the player") }
        // Past the file's first frames, where a stream that didn't start would bring the Streams panel up.
        requirePlayback(5.5, timeout: 90)
        if element("streams.row.0").exists { fail("\(what) opens the Streams panel") }
    }

    /// The row of addons over the remux movie's Streams panel with the four addons, by the remote. As the panel opens:
    /// focus on the first stream, Refresh in the panel's corner, All selected, and once Tortuga Slow answered (6 s late)
    /// a chip per addon in their order. Up from the first stream reaches the selected chip (All); Right to Tortuga
    /// Test's chip, in view, leaves the row where it is (All doesn't move); Left on All, the row's start, stays. Select
    /// on Tortuga Second's chip lists its two streams alone, without its name over them, the chip selected and focused,
    /// where it was and as wide (a pick moves no chip); Down reaches the first stream and Up the chip again. Right on
    /// Tortuga Broken's chip, the row's last, stays; Select lists its error and Retry; Retry asks it again: Broken stays
    /// selected, its error and Retry come back, and focus, which leaves Retry while Broken is asked, is on Retry again; Up
    /// from it reaches the chip. Left back to All puts the row back at rest (All where it was as the panel opened);
    /// Select on it lists every addon's group again, each under its name. Under Tortuga Slow's chip, Up reaches Refresh,
    /// and Select on it asks Slow again past the app's cache: its group loads under its chip, still selected, its first
    /// stream then reads a later answer, and focus stays on Refresh; Down from it reaches the selected chip.
    func testAddonRowListsOneAddonAndRefreshAsksAgain() throws {
        try launchWithStreamAddons(open: ["board", "detail:movie:tortuga-remux", "streams:movie:tortuga-remux:tortuga-remux"])
        requireFocus("streams.row.0", timeout: 60, "the first stream has focus once the Streams panel over the movie's details lists them")
        require("streams.refresh", "the panel offers Refresh")
        require("streams.bar", timeout: 30, "the panel shows the row of addons once two addons answered")
        let all = require("streams.bar.all", "the row offers All")
        requireSelectedChip("streams.bar.all", "the list opens on All")
        requireEveryAddonsChip("the row once Tortuga Slow answered")
        requireFocus("streams.row.0", "the first stream keeps focus as the chips come")
        // All's place across as the row rests (by its middle: focus may draw a chip larger around it).
        let rest = all.frame.midX
        shot("addon row")

        press(.up)
        requireFocus("streams.bar.all", "Up from the first stream reaches the selected chip, All")
        press(.right)
        requireFocus("streams.bar.addon.0", "Right from All reaches Tortuga Test's chip")
        if abs(all.frame.midX - rest) > 1 {
            fail("Right onto Tortuga Test's chip, in view, scrolled the row: All from x \(Int(rest)) to \(Int(all.frame.midX))")
        }
        shot("addon row focused")
        moveFocus(to: "streams.bar.all", with: .left, limit: 2, "Left along the row reaches All")
        press(.left)
        if !holdsFocus("streams.bar.all") { fail("Left on All, the row's start, moves focus to \(focusedId ?? "nothing")") }

        moveFocus(to: "streams.bar.addon.1", with: .right, limit: 4, "Right along the row reaches Tortuga Second's chip")
        let picked = element("streams.bar.addon.1").frame
        press(.select)
        requireSelectedChip("streams.bar.addon.1", "Select on Tortuga Second's chip")
        requireChipStill("streams.bar.addon.1", at: picked, "Select on Tortuga Second's chip")
        requireSecondAlone("Select on Tortuga Second's chip")
        requireFocus("streams.bar.addon.1", "focus stays on Tortuga Second's chip once it is selected")
        shot("Tortuga Second's streams")
        press(.down)
        requireFocus("streams.row.0", "Down from the row reaches the first stream")
        press(.up)
        requireFocus("streams.bar.addon.1", "Up from the first stream reaches the selected chip, Tortuga Second's")

        moveFocus(to: "streams.bar.addon.3", with: .right, limit: 3, "Right along the row reaches Tortuga Broken's chip")
        press(.right)
        if !holdsFocus("streams.bar.addon.3") { fail("Right on Tortuga Broken's chip, the row's end, moves focus to \(focusedId ?? "nothing")") }
        press(.select)
        requireBrokenAlone("Select on Tortuga Broken's chip")
        requireFocus("streams.bar.addon.3", "focus stays on Tortuga Broken's chip once it is selected")
        shot("Tortuga Broken's error")
        moveFocus(to: "streams.group.0.retry", with: .down, limit: 2, "Down from the row reaches Tortuga Broken's Retry")
        press(.select)
        requireBrokenAlone("Select on Tortuga Broken's Retry")
        // Retry goes while Broken is asked again; once Broken has failed again, focus is back on it.
        requireFocus("streams.group.0.retry", "focus is back on Retry once Tortuga Broken failed again")
        moveFocus(to: "streams.bar.addon.3", with: .up, limit: 2, "Up from Retry reaches the selected chip, Tortuga Broken's")

        moveFocus(to: "streams.bar.all", with: .left, limit: 5, "Left along the row reaches All")
        if !waitUntil(2, { abs(all.frame.midX - rest) <= 1 }) {
            fail("Left back to All leaves it at x \(Int(all.frame.midX)), not where the row rests, x \(Int(rest))")
        }
        press(.select)
        requireSelectedChip("streams.bar.all", "Select on All")
        requireFocus("streams.bar.all", "focus stays on All once it is selected")
        require("streams.group.0.name", timeout: 10, "Select on All lists Tortuga Test's group again, under its name")
        require("streams.group.1.name", timeout: 5, "Select on All lists Tortuga Second's group again, under its name")
        // The panel's column makes its rows as focus scrolls them in.
        press(.down, until: "streams.row.4", "Down from All along the list reaches Tortuga Slow's first stream")
        require("streams.group.2.name", timeout: 5, "Select on All lists Tortuga Slow's group again, under its name")
        press(.down, until: "streams.group.3.retry", limit: 3, "Down past Tortuga Slow's streams reaches Tortuga Broken's Retry")
        require("streams.group.3.name", timeout: 5, "Select on All lists Tortuga Broken's group again, under its name")
        moveFocus(to: "streams.bar.all", with: .up, limit: 8, "Up along the list reaches the selected chip, All")

        moveFocus(to: "streams.bar.addon.2", with: .right, limit: 4, "Right along the row reaches Tortuga Slow's chip")
        press(.select)
        requireSelectedChip("streams.bar.addon.2", "Select on Tortuga Slow's chip")
        let answer = requireSlowAnswer("Select on Tortuga Slow's chip")
        press(.up)
        requireFocus("streams.refresh", "Up from Tortuga Slow's chip reaches Refresh in the panel's corner")
        shot("Refresh focused")
        press(.select)
        requireSlowAskedAgain(after: answer, "Select on Refresh under Tortuga Slow's chip")
        requireFocus("streams.refresh", "focus stays on Refresh once it asked again")
        shot("Tortuga Slow asked again")
        press(.down)
        requireFocus("streams.bar.addon.2", "Down from Refresh reaches the selected chip, Tortuga Slow's")
    }

    /// The list's first focus with an addon above it answering late: Tortuga Slow installed first, the test addon after
    /// it. The test addon's first stream takes focus as it answers; once Slow answers, 6 s later, and lists its streams
    /// above, focus moves on to Slow's first stream, the list's new first. Moved by the remote before Slow answers (a
    /// launch of its own, on the series' first episode, which the app hasn't asked Slow for), focus stays on the stream
    /// it was moved to.
    func testFirstFocusFollowsALateAddonAbove() throws {
        try launchWithStreamAddons(["slow", "test"], open: ["board", "detail:movie:tortuga-remux", "streams:movie:tortuga-remux:tortuga-remux"])
        requireText("streams.row.0", contains: "Tortuga Test", timeout: 60, "the test addon's stream is first while Tortuga Slow answers")
        requireFocus("streams.row.0", "the test addon's first stream has focus as it answers")
        requireText("streams.row.0", contains: "Tortuga Slow", timeout: 30, "Tortuga Slow's streams come above the test addon's")
        requireFocus("streams.row.0", "the list's own focus moves on to the new first stream, Tortuga Slow's")

        try launchWithStreamAddons(["slow", "test"], open: ["streams:series:tortuga-series:tortuga-series:1:1"])
        requireText("streams.row.0", contains: "Tortuga Test", timeout: 60, "the first episode's test addon stream is first while Tortuga Slow answers")
        requireFocus("streams.row.0", "the test addon's first stream of the episode has focus as it answers")
        press(.down)
        requireFocus("streams.row.1", "Down moves focus to the test addon's second stream")
        requireText("streams.row.0", contains: "Tortuga Slow", timeout: 30, "Tortuga Slow's streams of the episode come above the test addon's")
        requireFocus("streams.row.3", "focus the remote moved stays on the test addon's second stream, now below Tortuga Slow's two")
    }

    /// Refresh in the panel's corner, by the remote, with one addon listing streams: of the test addon, which has no
    /// stream for the unnumbered series' episodes, and Tortuga Second only Second lists streams, so no addon has a chip
    /// and the panel has no row of addons. Focus starts on the first stream; Up from it reaches Refresh, in the panel's
    /// top trailing corner, level with the title; Up on it stays, Left reaches the sort button before it and Right
    /// comes back. Select asks Tortuga Second again past the app's cache: its first stream then reads a later answer,
    /// focus staying on Refresh; Down reaches the first stream.
    func testRefreshInThePanelsCornerAsksAgain() throws {
        try launchWithStreamAddons(
            ["test", "second"],
            open: ["board", "detail:series:tortuga-unnumbered", "streams:series:tortuga-unnumbered:tortuga-unnumbered:1"]
        )
        requireText("streams.row.0", contains: "Tortuga Second", timeout: 60, "the episode's Streams panel lists Tortuga Second's streams")
        requireFocus("streams.row.0", "the first stream has focus once the panel lists it")
        shot("one addon")
        let before = requireFirstAnswer("the panel as it opens")

        press(.up)
        shot("Up from the first stream")
        requireFocus("streams.refresh", "Up from the first stream reaches Refresh")
        requireGone("streams.bar", timeout: 2, "with one addon listing streams the panel has no row of addons")
        let refresh = element("streams.refresh").frame
        let title = require("streams.title", "the panel's heading").frame
        let panel = require("streams.panel", "the Streams panel").frame
        if refresh.minY > title.maxY || refresh.maxY < title.minY {
            fail("Refresh spans y \(Int(refresh.minY))-\(Int(refresh.maxY)), not level with the title's \(Int(title.minY))-\(Int(title.maxY))")
        }
        if refresh.minX < max(title.maxX, panel.midX) {
            fail("Refresh starts at x \(Int(refresh.minX)), not in the panel's trailing half past the title (title ends at \(Int(title.maxX)), the panel's middle is \(Int(panel.midX)))")
        }
        press(.up)
        if !holdsFocus("streams.refresh") { fail("Up on Refresh moves focus to \(focusedId ?? "nothing")") }
        moveFocus(to: "streams.sort", with: .left, limit: 1, "Left from Refresh reaches the sort button before it")
        moveFocus(to: "streams.refresh", with: .right, limit: 1, "Right from the sort button comes back to Refresh")

        press(.select)
        requireFirstAnsweredAgain(after: before, "Select on Refresh: Tortuga Second's")
        requireFocus("streams.refresh", "focus stays on Refresh once it asked again")
        requireGone("streams.bar", timeout: 2, "after Refresh the panel still has no row of addons")
        press(.down)
        requireFocus("streams.row.0", "Down from Refresh reaches the first stream")
    }

    /// The sort button, by the remote, over the test addon's sorted movie, whose seven releases come out of order: its
    /// Streams panel over the details lists them in the addon's order under the addon's name, the first focused; Up
    /// reaches Refresh and Left the sort button before it, as tall and level with it, "Sort by: Addon order", not
    /// filled (not selected). Select opens its options in a panel (`streams.sort.panel`) with focus on Addon order;
    /// Select on Resolution, then on Largest size first, checks each (selected) and leaves the panel open; Menu closes
    /// the panel alone, focus back on the sort button, now "Sort by: Resolution, Largest size first" and filled. The
    /// list is one group without a heading in that order, and Down from the button reaches its first stream, the
    /// 2160p remux.
    func testSortButtonSortsTheListFromItsTop() throws {
        try launchDev(addon: true, open: ["detail:movie:tortuga-sorted", "streams:movie:tortuga-sorted:tortuga-sorted"])
        requireFocus("streams.row.0", timeout: 60, "the first stream has focus once the sorted movie's Streams panel lists them")
        shot("sort-addon-order")
        requireRows(Array(StreamSortTexts.addonOrder.prefix(2)), "the list as it opens, in the addon's order")
        require("streams.group.0.name", "the addon's order heads the list with the addon's name")
        press(.up)
        requireFocus("streams.refresh", "Up from the first stream reaches Refresh")
        moveFocus(to: "streams.sort", with: .left, limit: 1, "Left from Refresh reaches the sort button")
        requireSortButton(StreamSortTexts.addonOrderLabel, selected: false, "the sort button in the addon's order")
        requireSortBesideRefresh("the panel's sort button")

        press(.select)
        require("streams.sort.panel", timeout: 5, "Select on the sort button opens its options' panel")
        requireFocus("streams.sort.option.0", "the options' panel opens with focus on the sort in effect, Addon order")
        moveFocus(to: "streams.sort.option.\(StreamSortTexts.resolution)", with: .down, limit: 1, "Down reaches Resolution")
        press(.select)
        requireOptionSelected(StreamSortTexts.resolution, "Select on Resolution")
        moveFocus(to: "streams.sort.option.\(StreamSortTexts.largest)", with: .down, limit: 2, "Down reaches Largest size first")
        press(.select)
        requireOptionSelected(StreamSortTexts.largest, "Select on Largest size first")
        requireOptionSelected(StreamSortTexts.resolution, "Largest size first picked after Resolution")
        shot("sort-panel")
        press(.menu)
        requireGone("streams.sort.panel", "Menu closes the options' panel")
        requireFocus("streams.sort", "Menu leaves the Streams panel open with focus back on the sort button")
        requireSortButton(StreamSortTexts.twoKeysLabel, selected: true, "the sort button with two keys picked")

        requireRows(Array(StreamSortTexts.byResolutionThenSize.prefix(3)), "the list sorted by resolution, then largest size first")
        requireGone("streams.group.0.name", timeout: 2, "the sorted list has no addon heading")
        press(.down)
        requireFocus("streams.row.0", "Down from the sort button reaches the sorted list's first stream, at its top")
        // Focus off the button: its fill is the sort's, not focus's white.
        shot("sort-sorted")
    }

    func testTheSuggestedStreamIsMarkedWithWhy() throws {
        let picks = ["--dev-picks", "phone"]
        let reason = "Like your recent picks: "
        try launchDev(addon: true, open: ["streams:movie:tortuga-sorted:tortuga-sorted"], arguments: picks)
        requireFocus("streams.row.0", timeout: 60, "the first stream has focus once the sorted movie's Streams screen lists them")
        let rows = (0..<7).map { element("streams.row.\($0)") }
        _ = waitUntil(10) { rows.contains { allText(of: $0).contains("Suggested") } }
        // Down along the seven, one row a press: the mark and its reason are the row's, so focus never stops on them.
        var marked: [Int] = []
        for index in 0..<7 {
            moveFocus(to: "streams.row.\(index)", with: .down, limit: 1, "Down reaches the sorted movie's row \(index)")
            let text = allText(of: rows[index])
            guard text.contains("Suggested") else { continue }
            marked.append(index)
            if !text.contains(reason) { fail("the suggested row says \"\(text)\", without its reason") }
            shot("suggested stream, focused")
        }
        if marked.count != 1 { fail("\(marked.count) of the sorted movie's rows are marked Suggested, not one") }

        try launchDev(addon: true, open: ["streams:movie:tortuga-real-streams:tortuga-real-streams"], arguments: picks)
        requireFocus("streams.row.0", timeout: 60, "the first stream has focus once the real texts' Streams screen lists them")
        for _ in 0..<80 where !allText(of: focused).contains(reason) { press(.down) }
        if !allText(of: focused).contains(reason) { fail("no row of the real texts' list is marked Suggested") }
        shot("suggested stream among real addons' rows")

        try launchDev(addon: true, open: ["streams:movie:tortuga-sorted:tortuga-sorted"], arguments: picks + ["--dev-suggestions", "false"])
        requireFocus("streams.row.0", timeout: 60, "the first stream has focus once the sorted movie's Streams screen lists them")
        if rows.contains(where: { allText(of: $0).contains("Suggested") }) { fail("a row is marked Suggested with the setting off") }
    }

    /// Shots of Settings → Playback → Streams with focus on the group's last row (`streams-settings`). It checks
    /// nothing of the rows, so it runs as it is on a build without them.
    func testStreamSettingsShots() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openStreamsSettings()
        moveFocus(to: "settings.playback.streams.exclude", with: .down, limit: 10, "Down reaches the Streams group's last row")
        shot("streams-settings")
    }

    /// The sort's option at `index` in its panel is checked (selected) and the panel still open; fails with `what`.
    private func requireOptionSelected(_ index: Int, _ what: String) {
        let option = element("streams.sort.option.\(index)")
        if !waitUntil(10, { (try? option.snapshot())?.isSelected == true }) { fail("\(what): the option isn't checked in the open panel") }
    }

    /// The row over the player: the series' first episode playing from the test addon's stream without a binge group,
    /// with auto-play off (the default) Select on up next brings the second episode's Streams panel up over the player
    /// with the row of addons; Up from its first stream reaches All, Right Tortuga Second's chip, and Select on it lists
    /// its two streams alone, focus staying on the chip.
    func testAddonRowOverThePlayerListsOneAddon() throws {
        try askForTheNextEpisodesStream()
        require("streams.bar", "the Streams panel over the player shows the row of addons")
        requireChip(1, named: "Tortuga Second", "the row over the player")
        press(.up)
        requireFocus("streams.bar.all", "Up from the first stream over the player reaches the selected chip, All")
        moveFocus(to: "streams.bar.addon.1", with: .right, limit: 3, "Right along the row over the player reaches Tortuga Second's chip")
        press(.select)
        requireSelectedChip("streams.bar.addon.1", "Select on Tortuga Second's chip over the player")
        requireSecondAlone("Select on Tortuga Second's chip over the player")
        requireFocus("streams.bar.addon.1", "focus stays on Tortuga Second's chip over the player once it is selected")
        shot("Tortuga Second's streams over the player")
    }

    /// Shots of the stream rows' text: the test addon's anime episode of an addon's recorded
    /// answer with its second stream played, the episode's last played, focused (`stream-rows-anime`), the recorded
    /// answer with the longest descriptions (`stream-rows-long`), and the texts most people see, two formatters'
    /// emoji-led texts, plain text and an addon's plain rows (`stream-rows-common`, then focus moved down to the plain
    /// rows, `stream-rows-plain`). It checks nothing of the rows' look, so it runs as it is on a build without it.
    func testStreamRowShots() throws {
        // Four launches and a play.
        executionTimeAllowance = 600
        let addon = try addonURL
        let data = "rows-" + UUID().uuidString.prefix(8)
        let episode = ["board", "detail:series:tortuga-real-anime", "streams:series:tortuga-real-anime:tortuga-real-anime:1:1"]
        launchDev(keepingData: data, addon: addon, open: episode)
        requireFocus("streams.row.0", timeout: 60, "the anime episode's Streams panel focuses its first stream")
        press(.down)
        requireFocus("streams.row.1", "Down moves focus to the second stream")
        press(.select)
        require("player.video", timeout: 20, "Select on the second stream opens the player")
        requirePlayback(1, timeout: 90)
        launchDev(keepingData: data, addon: addon, open: episode)
        requireFocus("streams.row.0", timeout: 60, "the anime episode's Streams panel focuses its first stream again")
        requireText("streams.row.1", contains: "Last played", "the stream played is the episode's last played")
        press(.down)
        requireFocus("streams.row.1", "Down moves focus to the last played stream")
        shot("stream-rows-anime")

        try launchDev(addon: true, open: ["board", "detail:movie:tortuga-real-long", "streams:movie:tortuga-real-long:tortuga-real-long"])
        requireFocus("streams.row.0", timeout: 60, "the long descriptions' Streams panel focuses its first stream")
        shot("stream-rows-long")

        try launchDev(addon: true, open: ["board", "detail:movie:tortuga-common-streams", "streams:movie:tortuga-common-streams:tortuga-common-streams"])
        requireFocus("streams.row.0", timeout: 60, "the common texts' Streams panel focuses its first stream")
        shot("stream-rows-common")
        _ = moveFocus(to: "streams.row.6", with: .down, limit: 8, "Down reaches the plain rows' first one")
        shot("stream-rows-plain")
    }

    /// Shots of the row of addons, with the four addons, each once every addon answered: the Streams
    /// panel over the remux movie's details (`streams-row-detail`) and the second episode's panel over the player after
    /// up next on the series' first (`streams-row-player`), focus on the first stream (the column scrolled down to
    /// Tortuga Slow's first stream and back to see it listed). Apple TV has no downloads, so no `streams-row-download`.
    /// It checks nothing of the row, so it runs as it is on a build without it.
    func testAddonRowShots() throws {
        // Two launches and a play.
        executionTimeAllowance = 600
        try launchWithStreamAddons(open: ["board", "detail:movie:tortuga-remux", "streams:movie:tortuga-remux:tortuga-remux"])
        require("streams.panel", timeout: 60, "the Streams panel opens over the remux movie's details")
        requireTortugaSlowListed("the Streams panel over the remux movie's details")
        shot("streams-row-detail")

        try askForTheNextEpisodesStream()
        require("streams.panel", "the second episode's Streams panel is up over the player")
        requireTortugaSlowListed("the second episode's Streams panel over the player")
        shot("streams-row-player")
    }

    /// With the four addons, the series' first episode from its Streams panel over the details, its first stream the
    /// test addon's without a binge group, played; up next comes up with focus 10 s before its end, and Select on it:
    /// with auto-play off (the default) the second episode's Streams panel comes up over the player, its first stream
    /// focused.
    private func askForTheNextEpisodesStream() throws {
        try launchWithStreamAddons(open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"])
        requireText("streams.row.0", contains: "720p", timeout: 60, "the first episode's Streams panel lists the stream without a binge group first")
        requireFocus("streams.row.0", "the first episode's Streams panel focuses its first stream")
        press(.select)
        require("player.video", timeout: 20, "Select on the stream opens the player")
        requirePlayback(1, timeout: 90)
        require("player.upNext", timeout: 40, "the end of the 20 s episode offers the next one")
        requireFocus("player.upNext", timeout: 5, "up next has focus as it comes up")
        press(.select)
        requireFocus("streams.row.0", timeout: 60, "Select on up next brings the second episode's Streams panel over the player, its first stream focused")
        if !element("player.video").exists { fail("the second episode's Streams panel came up without the player under it") }
    }

    /// Menu until the player has closed: the first ones may only close the info or put the controls away; each backs
    /// out of one thing.
    private func leavePlayer() {
        for _ in 0..<4 where element("player.video").exists {
            press(.menu)
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Menu closes the player")
    }
}
