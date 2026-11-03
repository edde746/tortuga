import XCTest

/// Detail and Library with the remote, on Cinemeta titles (the test addon has streams for the movie):
/// - the movie: Play has focus first; the ratings row above it holds the addon's IMDb rating alone (no trackers), taking
///   no focus; Select on Play opens its streams' sheet with focus on the first stream; Menu closes it, focus back on
///   Play;
/// - the ratings row with the five trackers connected to the fake: an item for every source on Spirited Away, none
///   taking focus; Rotten Tomatoes rotten and spilled on Batman & Robin;
/// - the user's own rating (`testRateATitleOnTheTrackers`, UserRatingTestSupport.swift), with the remote: The Shawshank
///   Redemption unrated, no score of the user's own in the ratings row, Right from
///   Play reaching the row's "Rate this title" (`detail.rate`); Select opens the rating panel with focus on the stars,
///   each tracker "Not rated", no star lit, no Remove rating; Right eight times and Select give 8, saved on all three,
///   four stars lit; Menu closes the panel, focus back on Rate, "Your rating: 8", the row's score "8"; Simkl's rating
///   writes failing, Left twice and Select give 6: three stars lit, Simkl's line waiting to retry, the others saved;
///   Simkl back, the app sent Home and back: Simkl holds 6 and its line, read again, says so; Down to Remove rating and
///   Select: every line "Removed", no star lit, focus back on the stars, the row's score gone and Rate "Rate this
///   title" again, the fake holding none; Batman & Robin rated 7 on Trakt and 8 on Simkl: the score "7–8", in the panel
///   no star lit and each tracker's own; a launch without trackers: IMDb alone, no score of the user's own, no Rate;
/// - the series (Chernobyl): Down from Play lands on the selected season's chip; Right onto the next chip selects its
///   season, moving no chip, Left back the first; Down lands on the first episode; a long press of Select on an episode
///   opens its menu, whose first entry marks it watched ("Watched" in its value, not "selected"), and opened again
///   marks it unwatched; Select on an episode opens its streams' sheet, titled as the series, and Menu returns focus to
///   the episode, from where Play/Pause opens the same sheet;
/// - the page's sections (`testDownMovesThroughTheSectionsAndMenuLeaves`, Chernobyl over the Board, the fake Trakt
///   relating no titles to it): the page opens where it does alone, no tab bar over it; Down goes from Play to the
///   seasons, the episodes and Cast & crew, Up comes back through each to the element it left, and Menu from the
///   episodes leaves the page for the Board in one press, its first card focused and the tab bar back; the page rests
///   where the focused section shows whole: the seasons moved up under the title's name, the focused episode and name
///   on screen, the title back at the top with Play;
/// - One Piece left in season 21's 60th episode (`testALongSeasonsRangesThenCastAndCrew`, no related titles either):
///   Down goes from Play to the seasons, the current episode's range, the episodes and Cast & crew, each showing
///   whole, and Up comes back to the episode;
/// - a series whose More like this has no card (`testUpFromCastPassesOverMoreLikeThisWithoutCards`, two test addon
///   series: similar titles none at once, and none that come while the app waits): once the row says none were found,
///   and while it loads, Down from the episodes passes over it to Cast & crew, and Up from there comes back past it to
///   the episode it left;
/// - "More like this" (`testMoreLikeThis`), from the test addon's similar catalogs and the fake Trakt's related titles:
///   on the test addon's movie over the Board, Down from Play lands on the row's first card, shown whole; the addon's
///   titles then Trakt's, each once; Right moves along it, Up comes back to Play and Down to the card it left; Select
///   opens a card's title and Menu comes back to the card; Menu from the row leaves the page for the Board in one
///   press; on the test addon's series Down goes from the episodes to the row (its two series) and Up comes back to
///   the episode it left;
/// - the test addon's series whose episodes carry no season or episode numbers, so no chips show over them and it has
///   no Play, with Cast & crew under them (`testDownReachesTheEpisodesOfASeriesWithoutSeasons`): Down from the title
///   block's focused action lands on an episode and the page moves up to the episodes, Up comes back to that action;
///   in its More panel (`testDownAndUpReachEachLinkGroupOfTheMorePanel`), Down from a group's last name and Up from a
///   group's first name land on the next or previous group's first name, however far along its line the name sits;
/// - the movie handed off to a player app (a custom URL-template player, Handoff, whose link reopens this app: the
///   simulator has no player app) asks whether it was finished under the actions; Down from every action lands on Mark
///   as watched (`testDownFromEveryActionLandsOnMarkAsWatched`);
/// - Breaking Bad left at 12:34 of S2E7 (`testOpensAtTheCurrentEpisode`): the page opens with Play focused, on Season 2
///   with that episode's card at the middle of the row; Down lands on Season 2's chip, Down again on that episode's
///   card, and Up and Down come back to both;
/// - the top of the details on the test addon's hero titles (`testTheMetadataLineSitsUnderTheTitle`,
///   DetailHeroSupport.swift);
/// - the library: Right from Play reaches the round library button, which adds the movie; Up reaches More, whose side
///   panel Menu closes, focus back on More; in the panel opened again focus reaches a genre link, which opens
///   Discover, whose tab bar leads to the Library, which lists the movie; the view menu (the row's first capsule, as
///   tall as the sort filter, where the posters start) opens its panel, whose Continue Watching doesn't list it; the
///   movie's menu, held Select, marks it watched ("Watched" in its value, not "selected"); Play/Pause on its poster
///   does nothing; held Select again, its menu removes it from the library;
/// - a kids profile (`testKidsProfileAsksAParentThenBlocksTheTitle`): a title suggested above its cap shows the blocked
///   page, saying "Suggested 12+", Ask a parent focused, which takes the parent's PIN and loads the page; the action
///   row's last button, Block on this profile, takes the PIN again and brings the blocked page back;
/// - a kids profile's suggested ages (`testKidsProfileShowsSuggestedSixAndHidesTwelve`): Paddington (6+) and Toy Story
///   (0+) open their pages, The Shawshank Redemption (12+) the blocked page; About's agerating notice shows its texts.
final class DetailTests: TortugaUITestCase {
    func testMoviePlayOpensTheStreamsSheetAndMenuReturnsToPlay() throws {
        try launchDev(addon: true, open: ["detail:movie:tt0111161"])
        requireFocus("detail.play", timeout: 60, "Play has focus once the movie's details show")
        requireRatings(["imdb"], "the movie's ratings row (the addon's IMDb rating)")
        shot("movie")
        press(.select)
        requireFocus("streams.row.0", timeout: 60, "Select on Play opens the movie's streams")
        shot("movie streams sheet")
        press(.menu)
        requireGone("streams.row.0", "Menu closes the Streams sheet")
        requireFocus("detail.play", "Menu returns to the details with focus on Play")
        shot("back on the movie")
    }

    /// On a kids profile (`--dev-kids`, the cap Kids) whose age database the fake tracker server (`kidsAgesFromFake`)
    /// serves, The Shawshank Redemption (suggested 12+) is above the cap: the movie's page is the blocked page, Ask a
    /// parent focused, why ("Suggested 12+"), nothing of the title and no spinner. Select asks for the parent's PIN,
    /// which allows the title: its page loads, its action row ending in Block on this profile, past every other round
    /// button. Select on it asks for the PIN again and then shows the blocked page again.
    func testKidsProfileAsksAParentThenBlocksTheTitle() throws {
        try launchDev(addon: true, trackers: kidsAgesFromFake, open: ["detail:movie:tt0111161"], arguments: ["--dev-kids", "2468"])
        require("detail.kidsBlock", timeout: 60, "a title suggested above the kids profile's cap shows the blocked page")
        requireText("detail.kidsBlock", contains: "Suggested 12+", "the blocked page says the title's suggested age")
        requireFocus("detail.kidsBlock.action", "Ask a parent has focus on the blocked page")
        for id in ["detail.loading", "detail.play"] where element(id).exists { fail("the blocked page shows \(id) too") }
        shot("blocked title")
        press(.select)
        require("pin.digit.1", "Ask a parent asks for the parent's PIN")
        shot("parent's PIN")
        enterPin([2, 4, 6, 8])
        requireGone("pin.digit.1", timeout: 30, "the parent's PIN closes the pad")
        require("detail.play", timeout: 60, "allowed by the parent, the title's page loads")
        requireGone("detail.kidsBlock", "allowed by the parent, the blocked page goes")
        let block = require("detail.kidsBlockAction", "the action row has Block on this profile").frame
        let others = ["detail.play", "detail.chooseStream", "detail.trailer", "detail.library", "detail.watched"].filter { element($0).exists }
        for id in others where element(id).frame.maxX > block.minX {
            fail("Block on this profile (\(block)) isn't the row's last button: \(id) ends at x \(Int(element(id).frame.maxX))")
        }
        walkFocus(to: "detail.kidsBlockAction", "the remote reaches Block on this profile")
        shot("block on this profile in the row")
        press(.select)
        require("pin.digit.1", "Block on this profile asks for the parent's PIN")
        enterPin([2, 4, 6, 8])
        requireGone("pin.digit.1", timeout: 30, "the parent's PIN closes the pad")
        requireFocus("detail.kidsBlock.action", timeout: 30, "blocked by the parent, the blocked page is back, Ask a parent focused")
        requireGone("detail.play", "blocked by the parent, the title's page goes")
        shot("blocked by the parent")
    }

    /// On a kids profile at the cap Kids (`--dev-kids`) whose age database the fake tracker server (`kidsAgesFromFake`)
    /// serves (test_trackers.py's AGES), each title opened in a launch of its own: Paddington (suggested 6+) and Toy
    /// Story (0+) open their pages, Play focused, no blocked page; The Shawshank Redemption (12+) shows the blocked page,
    /// saying "Suggested 12+". Settings → About lists agerating, which Down reaches and Select opens: its page shows its
    /// licence texts (LICENSE first).
    func testKidsProfileShowsSuggestedSixAndHidesTwelve() throws {
        let kids = ["--dev-kids", "2468"]
        for (id, name) in [("tt1109624", "Paddington"), ("tt0114709", "Toy Story")] {
            try launchDev(addon: true, trackers: kidsAgesFromFake, open: ["detail:movie:\(id)"], arguments: kids)
            requireFocus("detail.play", timeout: 60, "\(name), suggested within the cap, shows its page, Play focused")
            if element("detail.kidsBlock").exists { fail("\(name) shows the blocked page") }
            shot(name)
        }
        try launchDev(addon: true, trackers: kidsAgesFromFake, open: ["detail:movie:tt0111161"], arguments: kids)
        require("detail.kidsBlock", timeout: 60, "The Shawshank Redemption, suggested 12+, shows the blocked page")
        requireText("detail.kidsBlock", contains: "Suggested 12+", "the blocked page says the title's suggested age")
        if element("detail.play").exists { fail("the blocked page shows Play too") }
        shot("The Shawshank Redemption blocked")

        try launchDev(arguments: kids)
        openSection("about")
        moveFocus(to: "settings.about.notice.11", with: .down, limit: 18, "Down reaches agerating in About")
        press(.select)
        requireText("settings.notice.text", contains: "LICENSE", timeout: 10, "agerating's page starts with its LICENSE")
        if !app.staticTexts["agerating"].exists { fail("agerating's page isn't titled agerating") }
        shot("agerating's page")
    }

    func testRatingsRowFromTheTrackers() throws {
        try launchDev(trackersConnected: true, open: [Self.fullRatingsTitle])
        requireFocus("detail.play", timeout: 60, "Play has focus once Spirited Away's details show")
        // The anime sources wait for the anime-id datasets, downloaded on first use.
        requireRatings(Self.ratingSources.map { $0.key }, timeout: 90, "Spirited Away's ratings row")
        shot("ratings row, every source")

        try launchDev(trackersConnected: true, open: [Self.rottenRatingsTitle])
        requireFocus("detail.play", timeout: 60, "Play has focus once Batman & Robin's details show")
        requireRatings(Self.nonAnimeRatingKeys, timeout: 60, "Batman & Robin's ratings row")
        requireRotten("Batman & Robin's Rotten Tomatoes scores")
        shot("ratings row, rotten and spilled")
    }

    func testRateATitleOnTheTrackers() throws {
        try seedFakeRatings()
        try launchDev(trackersConnected: true, open: [Self.rateTitle])
        requireFocus("detail.play", timeout: 60, "Play has focus once The Shawshank Redemption's details show")
        requireRateAction("Rate this title", timeout: 60, "an unrated title's Rate")
        requireNoRatingItem("an unrated title")
        moveFocus(to: "detail.rate", with: .right, limit: 8, "Right from Play reaches Rate")
        shot("rate, unrated")

        press(.select)
        require("detail.userRating.sheet", timeout: 5, "Select on Rate opens the rating panel")
        requireFocus("detail.userRating.stars", "the rating panel opens with focus on the stars")
        requireRatingLines(Self.lines("Not rated"), "an unrated title's panel")
        if element("detail.userRating.clear").exists { fail("the panel offers Remove rating before any score") }
        requireStars(nil, "an unrated title's panel")
        shot("rate, picker")

        press(.right, count: 8)
        press(.select)
        requireRatingLines(Self.lines("Saved"), "8 given")
        requireStars(8, "8 given")
        requireFakeRatings(Self.rateImdb, ["trakt": [8], "simkl": [8], "mdblist": [8]], "8 given")
        require("detail.userRating.clear", timeout: 10, "Remove rating once there is a score")
        shot("rate, 8 saved")
        press(.menu)
        requireGone("detail.userRating.sheet", "Menu closes the rating panel")
        requireFocus("detail.rate", "focus back on Rate once its panel closes")
        requireRateAction("Your rating: 8", "the rated title's Rate")
        requireRatingItem("Your rating: 8", "the rated title's score")
        shot("rate, rated")

        try fakeTrackers("/_fail?ratings=simkl", post: true)
        press(.select)
        requireFocus("detail.userRating.stars", "the rating panel opens again with focus on the stars")
        press(.left, count: 2)
        press(.select)
        requireRatingLines(["trakt": "Saved", "simkl": "Not sent yet, will retry", "mdblist": "Saved"], "6 given, Simkl's writes failing")
        requireStars(6, "6 given, Simkl's writes failing")
        requireFakeRatings(Self.rateImdb, ["trakt": [6], "simkl": [8], "mdblist": [6]], "6 given, Simkl's writes failing")
        shot("rate, Simkl's write queued")

        try fakeTrackers("/_fail?ratings=", post: true)
        press(.menu)
        requireGone("detail.userRating.sheet", "Menu closes the rating panel")
        // XCUITest goes on reporting the app in the foreground after Home: the replay below, which only the app's
        // coming back starts, is what shows it went. The app has left the front once the home screen shows, still.
        let inApp = XCUIScreen.main.screenshot().pngRepresentation
        XCUIRemote.shared.press(.home)
        if waitUntil(10, { XCUIScreen.main.screenshot().pngRepresentation != inApp }) { screenSettles(XCUIScreen.main) }
        app.activate()
        requireFakeRatings(Self.rateImdb, ["trakt": [6], "simkl": [6], "mdblist": [6]], timeout: 60, "the queued 6 replayed to Simkl once the app is back")
        requireRatingItem("Your rating: 6", "the score after the replay")
        requireFocus("detail.rate", "focus on Rate once the app is back")
        press(.select)
        requireFocus("detail.userRating.stars", "the rating panel opens with focus on the stars after the replay")
        requireRatingLines(["trakt": "Saved", "simkl": "6", "mdblist": "Saved"], "Simkl's line, read again once its write has gone")
        shot("rate, Simkl's write retried")

        moveFocus(to: "detail.userRating.clear", with: .down, limit: 2, "Down from the stars reaches Remove rating")
        press(.select)
        requireRatingLines(Self.lines("Removed"), "Remove rating")
        requireStars(nil, "Remove rating")
        requireFakeRatings(Self.rateImdb, ["trakt": [], "simkl": [], "mdblist": []], "Remove rating")
        requireGone("detail.userRating.clear", "Remove rating goes with the score")
        requireFocus("detail.userRating.stars", "focus back on the stars once Remove rating goes")
        shot("rate, removed")
        press(.menu)
        requireGone("detail.userRating.sheet", "Menu closes the rating panel")
        requireFocus("detail.rate", "focus back on Rate once its panel closes")
        requireNoRatingItem("after Remove rating")
        requireRateAction("Rate this title", "Rate after Remove rating")

        try launchDev(trackersConnected: true, open: [Self.rateRangeTitle])
        requireFocus("detail.play", timeout: 60, "Play has focus once Batman & Robin's details show")
        requireRatingItem("Your rating: 7–8", timeout: 60, "the score where the trackers' scores differ")
        requireRateAction("Your rating: 7–8", "Rate where the trackers' scores differ")
        moveFocus(to: "detail.rate", with: .right, limit: 8, "Right from Play reaches Rate")
        shot("rate, range")
        press(.select)
        requireFocus("detail.userRating.stars", "the rating panel opens with focus on the stars")
        requireRatingLines(["trakt": "7", "simkl": "8", "mdblist": "Not rated"], "the trackers' scores differing")
        requireStars(nil, "the trackers' scores differing")
        require("detail.userRating.clear", timeout: 5, "Remove rating where the trackers' scores differ")
        shot("rate, range picker")
        press(.menu)
        requireGone("detail.userRating.sheet", "Menu closes the rating panel")

        try launchDev(addon: true, open: [Self.rateTitle])
        requireFocus("detail.play", timeout: 60, "Play has focus once The Shawshank Redemption's details show without trackers")
        requireRatings(["imdb"], "the ratings row without trackers (the addon's IMDb rating)")
        requireNoRatingItem("without trackers")
        if element("detail.rate").exists { fail("the action row has Rate without trackers") }
        shot("rate, no trackers")
    }

    /// In Arabic the stars run from the right, and the remote follows them, as the settings' steppers do: on The
    /// Shawshank Redemption, unrated, eight Lefts on the stars move the score to 8, then two Rights to 6 (read from the
    /// stars' value; Menu closes the panel without giving it).
    func testRatingStarsFollowTheRemoteRightToLeft() throws {
        try seedFakeRatings()
        try launchDev(trackersConnected: true, open: [Self.rateTitle], languages: ["ar"])
        requireFocus("detail.play", timeout: 60, "Play has focus once The Shawshank Redemption's details show")
        require("detail.rate", timeout: 60, "the action row has Rate")
        walkFocus(to: "detail.rate", "the remote reaches Rate from Play")
        press(.select)
        requireFocus("detail.userRating.stars", timeout: 5, "the rating panel opens with focus on the stars")
        let stars = element("detail.userRating.stars")
        press(.left, count: 8)
        if !waitUntil(5, { stars.value as? String == "8" }) { fail("in Arabic eight Lefts on the unrated stars leave them at \(stars.value as? String ?? "nothing"), not 8") }
        shot("rate in arabic: 8 by Left")
        press(.right, count: 2)
        if !waitUntil(5, { stars.value as? String == "6" }) { fail("in Arabic two Rights from 8 leave the stars at \(stars.value as? String ?? "nothing"), not 6") }
        press(.menu)
        requireGone("detail.userRating.sheet", "Menu closes the rating panel")
    }

    func testTheMetadataLineSitsUnderTheTitle() throws {
        try requireHeroTitles("tv", narrow: false) { try launchDev(addon: true, open: [$0]) }
    }

    /// The test addon's tortuga-spoilers with "Blur unwatched episodes" on, episodes 1 and 2 watched and 3 started:
    /// Down from Play reaches the episodes, each card under its title, the watched two reading "Watched"; focus walked
    /// along them to the last, the episode details above them reading episode 1's description and, on episode 4,
    /// "Description hidden" and nothing of its own. The shots show the stills and the focused episode's description of
    /// 1 to 3 plain, those of 4 to 8 (unwatched, 7 and 8 upcoming) blurred.
    func testUnwatchedEpisodesHideStillsAndDescriptions() throws {
        try launchDev(
            addon: true, open: ["detail:series:tortuga-spoilers"],
            progress: ["series:tortuga-spoilers:tortuga-spoilers:1:3:600:1800"],
            arguments: [
                "--dev-watched", "series:tortuga-spoilers:tortuga-spoilers:1:1",
                "--dev-watched", "series:tortuga-spoilers:tortuga-spoilers:1:2",
                "--dev-blur-unwatched",
            ]
        )
        requireFocus("detail.play", timeout: 60, "Play has focus once the spoilers series' details show")
        for _ in 0..<3 where focusedId?.hasPrefix("detail.episode.") != true { press(.down) }
        _ = requireFocus(prefix: "detail.episode.", "Down from Play reaches the episodes")
        moveFocus(to: "detail.episode.0", with: .left, "Left moves back to the first episode")
        for index in 0..<8 {
            if index > 0 { moveFocus(to: "detail.episode.\(index)", with: .right, limit: 1, "Right moves to episode \(index + 1)") }
            let card = element("detail.episode.\(index)")
            if !text(of: card).contains("Episode \(index + 1)") { fail("card \(index) reads '\(text(of: card))', not Episode \(index + 1)") }
            if index < 2, !isWatched(card) { fail("episode \(index + 1), marked watched, reads \(itemState(card))") }
            let details = element("detail.episodeDetails")
            if index == 0, !waitUntil(5, { text(of: details).contains("its still shows 1") }) {
                fail("watched episode 1's details hide its description: '\(text(of: details))'")
            }
            if index == 3, !waitUntil(5, { text(of: details).contains("Episode 4") }) || !text(of: details).contains("Description hidden")
                || text(of: details).contains("its still shows 4") {
                fail("unwatched episode 4's details read '\(text(of: details))', not \"Description hidden\" alone")
            }
            if [0, 3, 7].contains(index) { shot("episodes at \(index + 1)") }
        }
    }

    func testSeriesSeasonsEpisodeMenusAndStreams() throws {
        try launchDev(addon: true, open: ["detail:series:tt7366338"])
        requireFocus("detail.play", timeout: 60, "Play has focus once the series' details show")
        let title = require("detail.title", timeout: 5, "the series' details show its title").label
        shot("series")

        press(.down)
        requireFocus("detail.season.0", "Down from Play lands on the selected season's chip")
        let first = selectedSeasonText()
        if first != "Season 1" { fail("the selected season's chip reads '\(first)', not 'Season 1'") }
        shot("seasons")
        let chips = settledSeasonChipFrames()
        press(.right)
        requireFocus("detail.season.1", "Right moves along the seasons")
        if !waitUntil(5, { !selectedSeasonText().isEmpty && selectedSeasonText() != first }) {
            fail("focus on the second season's chip doesn't select its season")
        }
        requireNoSeasonChipMoved(since: chips, "Right onto the second season's chip")
        shot("other season")
        press(.left)
        requireFocus("detail.season.0", "Left moves back along the seasons")
        if !waitUntil(5, { selectedSeasonText() == first }) { fail("focus back on the first season's chip doesn't select it") }

        focusIntoShelf("detail.episode", with: .down, "Down from the seasons reaches the episodes")
        requireFocus("detail.episode.0", "Down from the seasons lands on the first episode")
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        if !waitUntil(5, { focusedId != "detail.episode.0" }) { fail("a long press of Select on an episode doesn't open its menu") }
        shot("episode menu (long press)")
        press(.select)
        let episode = element("detail.episode.0")
        if !waitUntil(10, { episode.exists && isWatched(episode) }) {
            fail("the menu's first entry doesn't mark the episode watched: its value reads \(itemState(episode))")
        }
        if episode.isSelected { fail("the watched episode reads as selected; watched is its value") }
        requireFocus("detail.episode.0", "focus returns to the episode after its menu")
        shot("episode watched")
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        if !waitUntil(5, { focusedId != "detail.episode.0" }) { fail("a long press of Select on the watched episode doesn't open its menu") }
        shot("watched episode menu (long press)")
        press(.select)
        if !waitUntil(10, { episode.exists && !isWatched(episode) }) {
            fail("the menu's first entry doesn't mark the watched episode unwatched: its value reads \(itemState(episode))")
        }
        shot("episode unwatched")

        moveFocus(to: "detail.episode.1", with: .right, "Right moves to the next episode")
        press(.select)
        // The test addon has no streams for the series: the panel says so, headed by the series' title.
        let heading = require("streams.title", "Select on an episode opens its streams' panel")
        if heading.label != title { fail("the Streams panel is headed \"\(heading.label)\", not \"\(title)\"") }
        shot("episode streams sheet")
        press(.menu)
        requireFocus("detail.episode.1", "Menu returns to the details with focus on the episode opened")
        shot("back on the series")
        // Play/Pause plays an episode as Select does.
        press(.playPause)
        if require("streams.title", timeout: 10, "Play/Pause on an episode opens its streams' panel").label != title {
            fail("Play/Pause on an episode opens a Streams panel not headed \"\(title)\"")
        }
        if element("detail.menu.item.markWatched").exists { fail("Play/Pause on an episode opens its menu") }
        press(.menu)
        requireFocus("detail.episode.1", "Menu returns to the details with focus on the episode Play/Pause opened")
    }

    func testDownMovesThroughTheSectionsAndMenuLeaves() throws {
        // Opened alone first: where Play lies with nothing under the page.
        // The fake Trakt relates no titles to Chernobyl: the sections without More like this.
        try launchDev(addon: true, trackers: true, open: ["detail:series:tt7366338"])
        requireFocus("detail.play", timeout: 60, "Play has focus once the series' details show")
        require("detail.cast.0", timeout: 10, "the series' details show Cast & crew")
        let alone = element("detail.play").frame
        try launchDev(addon: true, trackers: true, open: ["board", "detail:series:tt7366338"])
        requireFocus("detail.play", timeout: 60, "Play has focus once the series' details show over the Board")
        require("detail.cast.0", timeout: 10, "the series' details show Cast & crew over the Board")
        if app.tabBars.firstMatch.exists { fail("the tab bar shows over the details opened from the Board") }
        if !waitUntil(5, { abs(element("detail.play").frame.minY - alone.minY) < 1 }) {
            fail("opened from the Board the page lies elsewhere than alone: Play at \(element("detail.play").frame), alone at \(alone)")
        }
        shot("landing")
        press(.down)
        let season = requireFocus(prefix: "detail.season.", "Down from Play reaches the seasons")
        requireOnScreen(season, bottom: 400, "Down from Play moves the page up to the seasons under the title's name")
        shot("seasons stop")
        let episode = focusIntoShelf("detail.episode", with: .down, "Down from the seasons reaches the episodes")
        requireOnScreen(episode, "the focused episode shows whole")
        requireOnScreen(season, bottom: 400, "the page stays on the seasons while the episodes have focus")
        shot("episodes stop")
        let name = focusIntoShelf("detail.cast", with: .down, "Down from the episodes reaches Cast & crew")
        requireOnScreen(name, "the focused name of Cast & crew shows whole")
        shot("cast stop")
        press(.up)
        requireFocus(episode, "Up from Cast & crew comes back to the episode it left")
        requireOnScreen(episode, "the episode Up came back to shows whole")
        press(.up)
        requireFocus(season, "Up from the episodes comes back to the season it left")
        press(.up)
        requireFocus("detail.play", "Up from the seasons comes back to Play")
        requireOnScreen("detail.title", "the page moves back down to the title with Play")
        requireOnScreen("detail.play", "Play shows whole with focus")
        requireShelfContent("detail.episode", "the episodes before Down twice from Play")
        press(.down, count: 2)
        requireFocus(episode, "Down twice from Play reaches the episode again")
        press(.menu)
        requireFocus("board.row.0.item.0", timeout: 90, "Menu on the episodes leaves the details for the Board in one press")
        if element("detail.title").exists { fail("the details still show after Menu on the episodes") }
        if !app.tabBars.firstMatch.exists(within: 10) { fail("the tab bar doesn't show again on the Board after Menu") }
    }

    func testMoreLikeThis() throws {
        try launchDev(addon: true, trackers: true, open: ["board", "detail:movie:tortuga-related"])
        requireFocus("detail.play", timeout: 60, "Play has focus once the movie's details show")
        let first = focusIntoShelf("detail.related", with: .down, "Down from Play reaches More like this")
        if first != "detail.related.item.0" { fail("Down from Play lands on \(first), not More like this's first card") }
        requireOnScreen(first, "the focused card of More like this shows whole")
        requireRelatedTitles(["Tortuga Hero Plain Test", "The Godfather", "Cool Hand Luke", "Goodfellas"], "the movie with an IMDb id")
        shot("more like this, movie")
        press(.right)
        requireFocus("detail.related.item.1", "Right moves along More like this")
        press(.up)
        requireFocus("detail.play", "Up from More like this comes back to Play")
        requireOnScreen("detail.title", "the page moves back down to the title with Play")
        press(.down)
        requireFocus("detail.related.item.1", "Down from Play comes back to the card it left")
        press(.left)
        requireFocus("detail.related.item.0", "Left moves back along More like this")
        press(.select)
        let opened = app.descendants(matching: .any).matching(identifier: "detail.title")
            .matching(NSPredicate(format: "label == %@", "Tortuga Hero Plain Test")).firstMatch
        if !opened.exists(within: 30) { fail("Select on the first card opens Tortuga Hero Plain Test's details") }
        shot("more like this, card opened")
        press(.menu)
        requireFocus("detail.related.item.0", timeout: 10, "Menu from the opened title comes back to its card")
        press(.menu)
        requireFocus("board.row.0.item.0", timeout: 90, "Menu on More like this leaves the details for the Board in one press")
        if element("detail.title").exists { fail("the details still show after Menu on More like this") }

        try launchDev(addon: true, trackers: true, open: ["detail:series:tortuga-series"])
        requireFocus("detail.play", timeout: 60, "Play has focus once the series' details show")
        require("detail.episode.0", timeout: 10, "the series' details list its episodes")
        for _ in 0..<3 where focusedId?.hasPrefix("detail.episode.") != true { press(.down) }
        guard let episode = focusedId, episode.hasPrefix("detail.episode.") else {
            return fail("Down from Play doesn't reach the series' episodes: focus is on \(focusedId ?? "nothing")")
        }
        let card = focusIntoShelf("detail.related", with: .down, "Down from the episodes reaches More like this")
        requireOnScreen(card, "the focused card of the series' More like this shows whole")
        requireRelatedTitles(["Tortuga Hero Wide Test", "Tortuga Seasons Test"], "the series without an IMDb id")
        shot("more like this, series")
        press(.up)
        requireFocus(episode, "Up from More like this comes back to the episode it left")
    }

    /// Fails with `what` unless More like this's cards are labelled `titles`, in order, and no more.
    private func requireRelatedTitles(_ titles: [String], _ what: String) {
        let shown = (0...titles.count).compactMap { index -> String? in
            let card = element("detail.related.item.\(index)")
            return card.exists ? card.label : nil
        }
        if shown != titles { fail("\(what): More like this shows \(shown), not \(titles)") }
    }

    /// Fails with `what` unless `id` lies whole on the 1080-point screen, its bottom at or above `bottom`, once the
    /// page's move (320 ms) has ended.
    private func requireOnScreen(_ id: String, bottom: CGFloat = 1080, _ what: String) {
        if !waitUntil(3, { let frame = element(id).frame; return frame.minY >= 0 && frame.maxY <= bottom }) {
            fail("\(what): \(id) is at \(element(id).frame)")
        }
    }

    func testOpensAtTheCurrentEpisode() throws {
        try launchDev(open: [Self.currentEpisodeTitle], progress: [Self.currentEpisodeProgress])
        requireFocus("detail.play", timeout: 60, "Play has focus once Breaking Bad's details show")
        let shown = currentEpisodeShown()
        press(.down)
        let season = requireFocus(prefix: "detail.season.", "Down from Play reaches the seasons")
        if text(of: element(season)) != "Season 2" {
            fail("Down from Play lands on the chip '\(text(of: element(season)))', not the selected season's 'Season 2'")
        }
        focusIntoShelf("detail.episode", with: .down, "Down from the seasons reaches the episodes")
        shot("focus in the episodes")
        if let shown { fail(shown) }
        requireFocus(Self.currentEpisodeRow, "Down from the seasons lands on the current episode")
        press(.up)
        requireFocus(season, "Up from the current episode returns to Season 2's chip")
        focusIntoShelf("detail.episode", with: .down, "Down from the seasons reaches the episodes again")
        requireFocus(Self.currentEpisodeRow, "Down from the seasons lands on the current episode again")
    }

    func testALongSeasonsRangesThenCastAndCrew() throws {
        // The fake Trakt relates no titles to One Piece: the sections without More like this.
        try launchDev(trackers: true, open: [Self.rangesTitle], progress: [Self.rangesProgress])
        requireFocus("detail.play", timeout: 60, "Play has focus once One Piece's details show")
        require("detail.cast.0", timeout: 10, "One Piece's details show Cast & crew")
        press(.down)
        let season = requireFocus(prefix: "detail.season.", "Down from Play reaches the seasons")
        requireOnScreen(season, bottom: 400, "Down from Play moves the page up to the seasons under the title's name")
        press(.down)
        requireFocus("detail.range.1", "Down from the seasons lands on the current episode's range (51–100)")
        let episode = focusIntoShelf("detail.episode", with: .down, "Down from the ranges reaches the episodes")
        requireOnScreen(episode, "the focused episode shows whole")
        shot("long season, episodes stop")
        let name = focusIntoShelf("detail.cast", with: .down, "Down from the episodes reaches Cast & crew")
        requireOnScreen(name, "the focused name of Cast & crew shows whole")
        shot("long season, cast stop")
        press(.up)
        requireFocus(episode, "Up from Cast & crew comes back to the episode it left")
        requireOnScreen(episode, "the episode Up came back to shows whole")
    }

    func testUpFromCastPassesOverMoreLikeThisWithoutCards() throws {
        try launchDev(addon: true, open: ["detail:series:tortuga-related-none"])
        walkPastCardlessRelated("empty", "with no titles")
        // The app waits 30 s for an addon's answer, so this page opens by a link once the app is up and the test drives
        // it, not at the launch, whose start-up would take much of that time.
        try launchDev(addon: true)
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openFromOutside(URL(string: "stremio:///detail/series/tortuga-related-loading")!)
        walkPastCardlessRelated("loading", "still loading")
    }

    /// On a series' page whose More like this shows `state` (`detail.related.<state>`, `what` in the failures): Down
    /// from Play to the seasons and the episodes, Down past the row to Cast & crew and Up back past it to the episode
    /// left, with the row still `state` at the end.
    private func walkPastCardlessRelated(_ state: String, _ what: String) {
        requireFocus("detail.play", timeout: 60, "Play has focus once the series' details show")
        require("detail.related.\(state)", timeout: 10, "the series' More like this shows \(what)")
        require("detail.cast.0", timeout: 10, "the series' details show Cast & crew")
        press(.down)
        requireFocus(prefix: "detail.season.", "Down from Play reaches the seasons")
        let episode = focusIntoShelf("detail.episode", with: .down, "Down from the seasons reaches the episodes")
        focusIntoShelf("detail.cast", with: .down, "Down from the episodes passes over More like this \(what) to Cast & crew")
        shot("cast stop, more like this \(state)")
        press(.up)
        requireFocus(episode, "Up from Cast & crew passes over More like this \(what) to the episode it left")
        if !element("detail.related.\(state)").exists { fail("More like this changed from \(what) before the walk over it ended") }
    }

    func testDownReachesTheEpisodesOfASeriesWithoutSeasons() throws {
        try launchDev(addon: true, open: ["detail:series:tortuga-unnumbered"])
        require("detail.episode.0", timeout: 60, "the series' details list its episodes")
        require("detail.cast.0", timeout: 10, "the series' details show Cast & crew under the episodes")
        if !waitUntil(10, { focusedId?.hasPrefix("detail.") == true }) { fail("the series' details open with focus on \(focusedId ?? "nothing")") }
        let start = focusedId ?? ""
        if start.hasPrefix("detail.episode.") { fail("the series' details open with focus on an episode, not in the title block") }
        if element("detail.season.0").exists { fail("a series without season numbers shows season chips") }
        shot("series without seasons")
        let episode = focusIntoShelf("detail.episode", with: .down, "Down from \(start) reaches the episodes of a series without seasons")
        requireOnScreen(episode, "the focused episode shows whole")
        if !waitUntil(3, { element("detail.title").frame.minY < 0 }) {
            fail("the page doesn't move up to the episodes: the title is at \(element("detail.title").frame)")
        }
        shot("episodes stop without seasons")
        press(.up)
        requireFocus(start, "Up from the episodes comes back to \(start)")
        requireOnScreen("detail.title", "the page moves back down to the title")
    }

    func testDownAndUpReachEachLinkGroupOfTheMorePanel() throws {
        try launchDev(addon: true, open: ["detail:series:tortuga-unnumbered"])
        require("detail.cast.0", timeout: 60, "the series' details show Cast & crew")
        moveFocus(to: "detail.more", with: .up, limit: 4, "Up from the title block's action reaches More")
        press(.select)
        require("detail.morePanel", timeout: 5, "Select on More opens the side panel")
        // The panel's groups of names (`detail.link.<group>.<name>`): genres, cast, directors.
        let groups = (0..<6).filter { element("detail.link.\($0).0").exists(within: $0 == 0 ? 5 : 0) }
        if groups.count < 2 { return fail("the panel shows \(groups.count) groups of names, not two or more") }
        shot("more panel's names")
        var problems: [String] = []
        for (group, next) in zip(groups, groups.dropFirst()) {
            let names = (0..<20).prefix { element("detail.link.\(group).\($0)").exists }.count
            walkFocus(to: "detail.link.\(group).\(names - 1)", limit: 14, "the remote reaches group \(group)'s last name")
            press(.down)
            if !waitUntil(3, { holdsFocus("detail.link.\(next).0") }) {
                problems.append("Down from detail.link.\(group).\(names - 1) leaves focus on \(focusedId ?? "nothing"), not detail.link.\(next).0")
                continue
            }
            press(.up)
            if !waitUntil(3, { holdsFocus("detail.link.\(group).0") }) {
                problems.append("Up from detail.link.\(next).0 leaves focus on \(focusedId ?? "nothing"), not detail.link.\(group).0")
            }
        }
        if !problems.isEmpty { fail(problems.joined(separator: "\n")) }
    }

    func testDownFromEveryActionLandsOnMarkAsWatched() throws {
        try launchDev(
            addon: true,
            open: ["detail:movie:tt0111161"],
            settings: ["playback.customPlayer=\(Self.handoffPlayerName);tortuga://handoff?url={url}", "playback.player=\(Self.handoffPlayer)"]
        )
        requireFocus("detail.play", timeout: 60, "Play has focus once the movie's details show")
        press(.select)
        requireFocus("streams.row.0", timeout: 60, "Select on Play opens the movie's streams")
        moveFocus(to: "streams.row.1", with: .down, "Down reaches the H.264 stream")
        press(.select)
        require("detail.playerApp.markWatched", timeout: 30, "handed off to the custom player, the details ask whether the movie was finished")
        requireGone("streams.row.1", "the hand-off closes the Streams panel")
        walkFocus(to: "detail.play", "the remote reaches Play")
        // The action row, Play first.
        var actions = ["detail.play"]
        for _ in 0..<8 {
            press(.right)
            guard let id = focusedId, id.hasPrefix("detail."), !actions.contains(id) else { break }
            actions.append(id)
        }
        shot("player app question under the actions")
        var problems: [String] = []
        for action in actions {
            walkFocus(to: action, "the remote reaches \(action)")
            press(.down)
            if !waitUntil(3, { holdsFocus("detail.playerApp.markWatched") }) {
                problems.append("Down from \(action) leaves focus on \(focusedId ?? "nothing"), not detail.playerApp.markWatched")
            }
        }
        if !problems.isEmpty { fail(problems.joined(separator: "\n")) }
    }

    func testAddToLibraryShowsInLibraryAndItsMenuRemovesIt() throws {
        try launchDev(addon: true, open: ["detail:movie:tt0111161"])
        requireFocus("detail.play", timeout: 60, "Play has focus once the movie's details show")
        moveFocus(to: "detail.library", with: .right, "Right from Play reaches the library button")
        let add = element("detail.library").label
        press(.select)
        if !waitUntil(10, { element("detail.library").exists && element("detail.library").label != add }) { fail("Add to library doesn't turn into its undo") }
        shot("added to the library")

        moveFocus(to: "detail.more", with: .up, limit: 3, "Up from the actions reaches More")
        press(.select)
        require("detail.morePanel", timeout: 5, "Select on More opens the side panel")
        shot("more panel")
        press(.menu)
        requireGone("detail.morePanel", "Menu closes the side panel")
        requireFocus("detail.more", "Menu returns focus to More")
        press(.select)
        require("detail.morePanel", timeout: 5, "Select on More opens the side panel again")
        walkFocus(to: "detail.link.0.0", limit: 14, "focus reaches the panel's first genre link")
        press(.select)
        // The genre's Discover screen comes under the tab bar. (Tab bar buttons that appear once the stack gets a
        // top-level destination carry no `nav.*` identifiers on tvOS: the bar is found by focus.)
        if !app.tabBars.firstMatch.exists(within: 20) { fail("a genre link doesn't open Discover under the tab bar") }
        for _ in 0..<8 where !focusInTabBar { press(.up) }
        if !focusInTabBar { fail("Up doesn't reach the tab bar; focus is on \(focusedId ?? "nothing")") }
        for _ in 0..<6 where !element("library.view").exists { press(.right) }
        require("library.view", timeout: 5, "Right along the tab bar reaches Library")
        // One row above the grid, looked at while the tab bar has focus (a focused capsule or poster grows): the view
        // menu as tall as the sort filter beside it, starting where the posters do, read once each holds still.
        require("library.item.0", "the Library lists the movie")
        require("library.sort", "the Library shows its sort filter")
        let poster = settledFrame(of: element("library.item.0"))
        let sort = settledFrame(of: element("library.sort"))
        let view = settledFrame(of: element("library.view"))
        if abs(view.height - sort.height) > 1 {
            fail("the view menu is \(Int(view.height)) pt tall, the sort filter beside it \(Int(sort.height)) pt")
        }
        if abs(view.minX - poster.minX) > 2 {
            fail("the row above the grid starts at x \(Int(view.minX)), the posters at \(Int(poster.minX))")
        }
        moveFocus(to: "library.item.0", with: .down, "Down reaches the movie in the library")
        shot("library")

        for _ in 0..<8 where !holdsFocus("library.view") { press(focusedId == "library.item.0" ? .up : .left) }
        if !holdsFocus("library.view") { fail("Up and Left don't reach the view menu: focus is on \(focusedId ?? "nothing")") }
        let libraryView = menuText("library.view")
        press(.select)
        require("library.view.panel", timeout: 5, "Select on the view menu opens its panel")
        requireFocus("library.view.option.0", "the view shown (Library) has focus in the panel")
        moveFocus(to: "library.view.option.1", with: .down, "Down moves focus to Continue Watching")
        press(.select)
        requireGone("library.view.panel", "picking Continue Watching closes the panel")
        if !waitUntil(5, { !menuText("library.view").isEmpty && menuText("library.view") != libraryView }) {
            fail("picking Continue Watching leaves the view menu on \(menuText("library.view"))")
        }
        requireGone("library.item.0", "Continue Watching lists nothing: the movie was never played")
        shot("continue watching")
        // Focus comes back to the view menu as the panel closes; were it on the tab bar, Down reaches the row.
        for _ in 0..<3 where focusInTabBar { press(.down) }
        walkFocus(to: "library.view", "focus is back on the view menu")
        press(.select)
        requireFocus("library.view.option.1", "the view shown (Continue Watching) has focus in the panel")
        moveFocus(to: "library.view.option.0", with: .up, "Up moves focus to Library")
        press(.select)
        requireGone("library.view.panel", "picking Library closes the panel")
        if !waitUntil(5, { menuText("library.view") == libraryView }) { fail("picking Library in the panel doesn't show it") }
        moveFocus(to: "library.item.0", with: .down, "Down reaches the movie in the library again")

        XCUIRemote.shared.press(.select, forDuration: 1.5)
        let watched = "library.item.0.menu.markWatched"
        require(watched, timeout: 5, "holding Select on a library item opens its menu")
        moveFocus(to: watched, with: .up, limit: 3, "Mark as watched, the menu's first entry, has focus")
        shot("library item menu (held Select)")
        press(.select)
        let item = element("library.item.0")
        if !waitUntil(10, { item.exists && isWatched(item) }) { fail("Mark as watched doesn't mark the library's movie watched: its value reads \(itemState(item))") }
        if item.isSelected { fail("the library's watched movie reads as selected; watched is its value") }
        requireFocus("library.item.0", "focus returns to the library item after its menu")
        // Play/Pause plays only a Continue Watching card or an episode: on a poster it does nothing.
        press(.playPause)
        let posterMenu = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "library.item.0.menu.")).firstMatch
        _ = holds(for: 3) { !posterMenu.exists && !element("player.video").exists }
        if posterMenu.exists { fail("Play/Pause on a library item opens its menu (\(posterMenu.identifier))") }
        if element("player.video").exists { fail("Play/Pause on a library item opens the player") }
        requireFocus("library.item.0", "Play/Pause on a library item leaves focus on it")
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        require("library.item.0.menu.removeFromLibrary", timeout: 5, "holding Select on the library item opens its menu again")
        moveFocus(to: "library.item.0.menu.removeFromLibrary", with: .down, limit: 6, "Down moves along the menu to Remove from library")
        shot("library item menu (held Select again)")
        press(.select)
        requireGone("library.item.0", "the menu's Remove from library removes the movie from the library")
        shot("library empty")
    }
}
