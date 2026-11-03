import XCTest

/// Detail and Library by touch (iOS) and with the pointer (the Mac), on Cinemeta titles (the test addon has streams for
/// the movie): on iPhone one page, the backdrop across the top, the actions one compact group centred under the title;
/// on iPad and in the Mac's window the details and the episodes side by side. The Mac opens each title over the Board.
/// - the movie: the ratings row under the metadata line holds the addon's IMDb rating alone (no trackers), a button,
///   its lines centred on iPhone; Play opens its streams in a sheet over the details; on iPhone the sheet has the
///   grabber and no close button, and a swipe down on it returns to the details; on iPad and the Mac the sheet's Close
///   does (`testMoviePlayOpensTheStreamsSheetAndCloseReturns`);
/// - the ratings row with the five trackers connected to the fake: an item for every source on Spirited Away (centred
///   lines on iPhone), Rotten Tomatoes rotten and spilled on Batman & Robin;
/// - the series (Chernobyl): a tap (on the Mac a click) on the second season's chip selects it, moving no chip, one on
///   the first's back; a long press (a right click) on an episode opens its menu, whose first entry marks it watched
///   (VoiceOver reads "Watched" in its value, and not "selected", which is a chosen season's), and its ⋯ button opens
///   the same menu, marking it unwatched again; on iOS a long press on
///   the first season's
///   chip opens its menu, whose first entry marks the season watched, which the chip's value says ("Watched") and the
///   other season's doesn't; a tap (a click) on an episode opens its streams' sheet, headed by the series' title, which
///   a tap on the dimmed details above it closes (iPhone), its Close (iPad) or Escape (the Mac);
/// - a chip shows its episodes (`testAChipShowsItsEpisodes`): Breaking Bad's Season 3 chip lists that season's
///   episodes in place of Season 1's; One Piece left in season 21's second range (episodes 51–100) lists that range
///   alone, and a tap (a click) on the first range's chip lists episodes 1–50 alone;
/// - Breaking Bad left at 12:34 of S2E7, over the Board (`testOpensAtTheCurrentEpisode`): the page opens on Season 2,
///   the episodes' column (iPad, the Mac's window at its first size) with that episode's row at the middle of what shows
///   of it, iPhone's one page at its top (a swipe down leaves Play where it was);
/// - the library: the round + button adds the movie (its label turns into the undo), then the Library lists the movie:
///   on iOS reached by a genre link, which opens Discover, whose tabs lead to the Library, its filters fitting the
///   screen; on the Mac from the sidebar; Continue Watching, picked in the view menu, doesn't; the movie's long-press
///   (right-click) menu removes it from the library;
/// - "More like this" (`testMoreLikeThis`), on iPhone's page, iPad's and the Mac's wide page: the addon's similar
///   titles first, then the fake Trakt's, each title once, Trakt asked once without an account; a card's menu marks its
///   title watched and a card opens its title; the addon's alone for a title without an IMDb id; the empty line; no
///   row where no source answers;
/// - a kids profile (`testKidsProfileAsksAParentThenBlocksTheTitle`): a title suggested above its cap shows the blocked
///   page, saying "Suggested 12+", whose Ask a parent takes the parent's PIN and loads the page; its ⋯ offers Block on
///   this profile, which takes the PIN again and brings the blocked page back;
/// - a kids profile's suggested ages (`testKidsProfileShowsSuggestedSixAndHidesTwelve`): Paddington (6+) and Toy Story
///   (0+) open their pages, The Shawshank Redemption (12+) the blocked page; About's agerating notice shows its texts.
/// Each platform's own tests are in iOS/DetailTests+iOS.swift and macOS/DetailTests+macOS.swift.
final class DetailTests: TortugaUITestCase {
    func testMoviePlayOpensTheStreamsSheetAndCloseReturns() throws {
        try launchDev(addon: true, open: Self.opened("detail:movie:tt0111161"))
        let play = require("detail.play", timeout: 60, "the movie's details show Play")
        require("detail.library", "the action row has the library button")
        require("detail.watched", "the action row has the watched button")
        #if os(iOS)
        let title = require("detail.title", timeout: 5, "the movie's details show its title").label
        requireActionRowOnScreen("the movie's action row", playAtItsWidth: true)
        #endif
        requireRatings(["imdb"], centred: isPhone, "the movie's ratings row (the addon's IMDb rating)")
        shot("movie")
        activate(play)
        require("streams.row.0", timeout: 60, "Play opens the movie's streams")
        shot("movie streams sheet")
        if isPhone {
            #if os(iOS)
            if element("streams.close").exists { fail("the iPhone's Streams sheet shows a close button beside its grabber") }
            // A drag from the sheet's bar down to the bottom of the screen.
            ready(app.navigationBars[title]).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(
                forDuration: 0.05,
                thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.99)),
                withVelocity: .fast,
                thenHoldForDuration: 0
            )
            #endif
        } else {
            activate(require("streams.close", "the Streams sheet has Close"))
        }
        requireGone("streams.row.0", isPhone ? "a swipe down closes the Streams sheet" : "Close closes the Streams sheet")
        require("detail.play", "closing the sheet returns to the details")
        shot("back on the movie")
    }

    /// On a kids profile (`--dev-kids`, the cap Kids) whose age database the fake tracker server (`kidsAgesFromFake`)
    /// serves, The Shawshank Redemption (suggested 12+) is above the cap: the movie's page is the blocked page, its lock,
    /// why ("Suggested 12+") and Ask a parent, nothing of the title and no spinner. Ask a parent asks for the parent's
    /// PIN, which allows the title: its page loads. Its ⋯ (always there with Block on this profile, which never has a
    /// round button of its own) lists Block on this profile, which asks for the PIN again and then shows the blocked
    /// page again.
    func testKidsProfileAsksAParentThenBlocksTheTitle() throws {
        try launchDev(addon: true, trackers: kidsAgesFromFake, open: Self.opened("detail:movie:tt0111161"), arguments: ["--dev-kids", "2468"])
        require("detail.kidsBlock", timeout: 60, "a title suggested above the kids profile's cap shows the blocked page")
        requireText("detail.kidsBlock", contains: "Suggested 12+", "the blocked page says the title's suggested age")
        let ask = require("detail.kidsBlock.action", "the blocked page offers Ask a parent")
        for id in ["detail.loading", "detail.play"] where element(id).exists { fail("the blocked page shows \(id) too") }
        shot("blocked title")
        activate(ask)
        require("pin.digit.1", "Ask a parent asks for the parent's PIN")
        shot("parent's PIN")
        enterPin([2, 4, 6, 8])
        requireGone("pin.digit.1", timeout: 30, "the parent's PIN closes the pad")
        require("detail.play", timeout: 60, "allowed by the parent, the title's page loads")
        requireGone("detail.kidsBlock", "allowed by the parent, the blocked page goes")
        if element("detail.kidsBlockAction").exists { fail("Block on this profile has a round button of its own beside ⋯") }
        activate(require("detail.overflow", "with Block on this profile the action row has ⋯"))
        let block = require("detail.overflow.kidsBlock", timeout: 5, "⋯ lists Block on this profile")
        shot("block on this profile in more")
        pickMenuEntry(block)
        require("pin.digit.1", "Block on this profile asks for the parent's PIN")
        enterPin([2, 4, 6, 8])
        requireGone("pin.digit.1", timeout: 30, "the parent's PIN closes the pad")
        require("detail.kidsBlock.action", timeout: 30, "blocked by the parent, the title shows the blocked page again")
        requireGone("detail.play", "blocked by the parent, the title's page goes")
        shot("blocked by the parent")
    }

    /// On a kids profile at the cap Kids (`--dev-kids`) whose age database the fake tracker server (`kidsAgesFromFake`)
    /// serves (test_trackers.py's AGES), each title opened in a launch of its own: Paddington (suggested 6+) and Toy
    /// Story (0+) open their pages, no blocked page; The Shawshank Redemption (12+) shows the blocked page, saying
    /// "Suggested 12+". Settings → About lists agerating, whose page shows its licence texts (LICENSE first).
    func testKidsProfileShowsSuggestedSixAndHidesTwelve() throws {
        let kids = ["--dev-kids", "2468"]
        for (id, name) in [("tt1109624", "Paddington"), ("tt0114709", "Toy Story")] {
            try launchDev(addon: true, trackers: kidsAgesFromFake, open: Self.opened("detail:movie:\(id)"), arguments: kids)
            require("detail.play", timeout: 60, "\(name), suggested within the cap, shows its page")
            if element("detail.kidsBlock").exists { fail("\(name) shows the blocked page") }
            shot(name)
        }
        try launchDev(addon: true, trackers: kidsAgesFromFake, open: Self.opened("detail:movie:tt0111161"), arguments: kids)
        require("detail.kidsBlock", timeout: 60, "The Shawshank Redemption, suggested 12+, shows the blocked page")
        requireText("detail.kidsBlock", contains: "Suggested 12+", "the blocked page says the title's suggested age")
        if element("detail.play").exists { fail("the blocked page shows Play too") }
        shot("The Shawshank Redemption blocked")

        try launchDev(arguments: kids)
        openSection("about")
        activate(scrollTo("settings.about.notice.11", "About lists agerating"))
        requireText("settings.notice.text", contains: "LICENSE", timeout: 10, "agerating's page starts with its LICENSE")
        if !app.staticTexts["agerating"].exists { fail("agerating's page isn't titled agerating") }
        shot("agerating's page")
    }

    func testRatingsRowFromTheTrackers() throws {
        try launchDev(trackersConnected: true, open: Self.opened(Self.fullRatingsTitle))
        require("detail.play", timeout: 60, "Spirited Away's details show Play")
        // The anime sources wait for the anime-id datasets, downloaded on first use.
        requireRatings(Self.ratingSources.map { $0.key }, timeout: 90, centred: isPhone, "Spirited Away's ratings row")
        shot("ratings row, every source")

        try launchDev(trackersConnected: true, open: Self.opened(Self.rottenRatingsTitle))
        require("detail.play", timeout: 60, "Batman & Robin's details show Play")
        requireRatings(Self.nonAnimeRatingKeys, timeout: 60, centred: isPhone, "Batman & Robin's ratings row")
        requireRotten("Batman & Robin's Rotten Tomatoes scores")
        shot("ratings row, rotten and spilled")
    }

    func testSeriesSeasonsEpisodeMenusAndStreams() throws {
        try launchDev(addon: true, open: Self.opened("detail:series:tt7366338"))
        require("detail.play", timeout: 60, "the series' details show Play")
        let title = text(of: require("detail.title", timeout: 5, "the series' details show its title"))
        shot("series")

        let first = "Season 1"
        if !waitUntil(10, { selectedSeasonText() == first }) { fail("the selected season's chip reads '\(selectedSeasonText())', not '\(first)'") }
        let second = ready(scrollTo("detail.season.1", "the series has a second season's chip"))
        let chips = settledSeasonChipFrames()
        activate(second)
        if !waitUntil(5, { !selectedSeasonText().isEmpty && selectedSeasonText() != first }) {
            fail("\(Self.tapping) on the second season's chip doesn't select it")
        }
        requireNoSeasonChipMoved(since: chips, "\(Self.tapping) on the second season's chip")
        shot("other season")
        activate(scrollTo("detail.season.0", "the series has the first season's chip"))
        if !waitUntil(5, { selectedSeasonText() == first }) { fail("\(Self.tapping) on the first season's chip doesn't select it") }

        openItemMenu(scrollTo("detail.episode.0", "the season lists its episodes"))
        let entry = require("detail.menu.item.markWatched", timeout: 5, "a \(Self.menuGesture) on an episode opens its menu")
        shot("episode menu (\(Self.menuGesture))")
        pickMenuEntry(entry)
        let episode = element("detail.episode.0")
        if !waitUntil(10, { episode.exists && isWatched(episode) }) {
            fail("the menu's first entry doesn't mark the episode watched: its value reads \(itemState(episode))")
        }
        if episode.isSelected { fail("the watched episode reads as selected; watched is its value") }
        shot("episode watched")
        let more = element("detail.episode.0.menu")
        #if os(macOS)
        // The right-click menu just closed: the pointer goes over the ⋯ first, and a click lost to the menu's closing
        // is given once more.
        ready(more).hover()
        ready(more).click()
        if !element("detail.menu.item.markUnwatched").exists(within: 3) { ready(more).click() }
        #else
        activate(more)
        #endif
        pickMenuEntry(require("detail.menu.item.markUnwatched", timeout: 5, "the episode's ⋯ button opens its menu"))
        if !waitUntil(10, { episode.exists && !isWatched(episode) }) {
            fail("the menu's first entry doesn't mark the watched episode unwatched: its value reads \(itemState(episode))")
        }
        shot("episode unwatched")

        #if os(iOS)
        // A season marked watched from its chip's menu: VoiceOver reads its chip's value as "Watched", the other's not.
        openItemMenu(scrollTo(element("detail.season.0"), up: true, "the series shows its first season's chip"))
        pickMenuEntry(require("detail.menu.item.markSeasonWatched", timeout: 5, "a long press on the first season's chip opens its menu"))
        let watchedSeason = element("detail.season.0")
        if !waitUntil(10, { watchedSeason.exists && (watchedSeason.value as? String) == "Watched" }) {
            fail("the first season marked watched reads \"\(watchedSeason.value as? String ?? "nothing")\" as its chip's value, not \"Watched\"")
        }
        let otherSeason = element("detail.season.1").value as? String ?? ""
        if !otherSeason.isEmpty { fail("the second season, not watched, reads \"\(otherSeason)\" as its chip's value") }
        shot("season watched")
        #endif

        activate(scrollTo("detail.episode.1", "the season lists a second episode"))
        // The test addon has no streams for the series: the sheet says so, headed by the series' title.
        #if os(macOS)
        let sheetTitle = require("streams.title", timeout: 20, "a click on an episode opens its streams' sheet")
        if text(of: sheetTitle) != title { fail("the Streams sheet is headed \"\(text(of: sheetTitle))\", not \"\(title)\"") }
        shot("episode streams sheet")
        app.typeKey(.escape, modifierFlags: [])
        requireGone("streams.title", "Escape closes the Streams sheet")
        #else
        requireScreen(title, "a tap on an episode opens its streams' sheet")
        shot("episode streams sheet")
        if isPad {
            activate(require("streams.close", "the iPad's Streams form sheet has Close"))
        } else {
            if element("streams.close").exists { fail("the iPhone's Streams sheet shows a close button beside its grabber") }
            // The sheet stands at half height: the dimmed details show above it.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15)).tap()
        }
        if !waitUntil(10, { !app.navigationBars[title].exists }) {
            fail(isPad ? "Close doesn't close the Streams sheet" : "a tap on the dimmed details doesn't close the Streams sheet")
        }
        #endif
        require("detail.episode.1", "closing the sheet returns to the series")
        shot("back on the series")
    }

    func testAChipShowsItsEpisodes() throws {
        try launchDev(open: ["board", Self.currentEpisodeTitle])
        require("detail.play", timeout: 60, "Breaking Bad's details show Play")
        if !waitUntil(30, { selectedSeasonText() == "Season 1" }) { fail("Breaking Bad opens on '\(selectedSeasonText())', not Season 1") }
        let third = seasonChip("Season 3")
        if !third.exists(within: 10) { return fail("Breaking Bad shows no Season 3 chip") }
        // On iOS the page scrolls to the chips; the Mac's window shows them.
        #if os(iOS)
        let chip = scrollTo(third.identifier, "Season 3's chip")
        #else
        let chip = third
        #endif
        let pilot = text(of: require("detail.episode.0", timeout: 5, "Season 1 lists its first episode"))
        activate(chip)
        if !waitUntil(5, { selectedSeasonText() == "Season 3" }) { fail("\(Self.tapping) on Season 3's chip leaves '\(selectedSeasonText())' selected") }
        if !waitUntil(5, { element("detail.episode.0").exists && text(of: element("detail.episode.0")) != pilot }) {
            fail("Season 3's chip leaves Season 1's first episode listed (\(pilot))")
        }
        shot("season 3 picked")

        try launchDev(open: ["board", Self.rangesTitle], progress: [Self.rangesProgress])
        require("detail.play", timeout: 60, "One Piece's details show Play")
        #if os(iOS)
        let second = scrollTo("detail.range.1", "One Piece's long season has range chips")
        #else
        let second = require("detail.range.1", timeout: 30, "One Piece's long season has range chips")
        #endif
        if !waitUntil(30, { second.isSelected }) { fail("the current episode's range (51–100) isn't selected") }
        if let wrong = listedEpisodesWrong(50..<100) { fail("on the current episode's range: \(wrong)") }
        shot("current episode's range")
        #if os(iOS)
        activate(scrollTo("detail.range.0", "One Piece's first range chip"))
        #else
        activate(element("detail.range.0"))
        #endif
        if !waitUntil(5, { element("detail.range.0").isSelected }) { fail("\(Self.tapping) on the first range's chip doesn't select it") }
        if let wrong = listedEpisodesWrong(0..<50) { fail("after \(Self.tapping) on the first range's chip: \(wrong)") }
        shot("first range picked")
    }

    func testOpensAtTheCurrentEpisode() throws {
        try launchDev(open: ["board", Self.currentEpisodeTitle], progress: [Self.currentEpisodeProgress])
        require("detail.play", timeout: 60, "Breaking Bad's details show Play")
        #if os(iOS)
        if !isPad {
            let play = element("detail.play")
            _ = waitUntil(30) { selectedSeasonText() == "Season 2" }
            let opened = settledFrame(of: play)
            shot("current episode")
            if let wrong = currentSeasonWrong() { fail(wrong) }
            // A page brought down to the episode would scroll back up.
            appWindow.swipeDown()
            let swiped = settledFrame(of: play)
            print("current episode, Play opened at \(opened), after a swipe down at \(swiped)")
            if abs(swiped.minY - opened.minY) > 1 {
                fail("the page opened scrolled down: a swipe down moves Play from y \(opened.minY) to \(swiped.minY)")
            }
            return
        }
        #endif
        if let failure = currentEpisodeShown() { fail(failure) }
    }

    /// The test addon's tortuga-spoilers with "Blur unwatched episodes" on, episodes 1 and 2 watched and 3 started:
    /// every row listed under its title, the watched two reading "Watched", episode 1's row reading its description,
    /// episode 4's "Description hidden" and nothing of its own; the shots show the stills and descriptions of 1 to 3
    /// plain, those of 4 to 8 (unwatched, 7 and 8 upcoming) blurred. iPhone: the page scrolled to its middle and last
    /// rows; iPad and the Mac: the episodes' column at its last row.
    func testUnwatchedEpisodesHideStillsAndDescriptions() throws {
        try launchDev(
            addon: true, open: Self.opened("detail:series:tortuga-spoilers"),
            progress: ["series:tortuga-spoilers:tortuga-spoilers:1:3:600:1800"],
            arguments: [
                "--dev-watched", "series:tortuga-spoilers:tortuga-spoilers:1:1",
                "--dev-watched", "series:tortuga-spoilers:tortuga-spoilers:1:2",
                "--dev-blur-unwatched",
            ]
        )
        require("detail.play", timeout: 60, "Tortuga Spoilers Test's details show Play")
        for index in 0..<8 {
            let row = scrollTo("detail.episode.\(index)", "the spoilers series lists episode \(index + 1)")
            let label = text(of: row)
            if !label.contains("Episode \(index + 1)") { fail("row \(index) reads '\(label)', not Episode \(index + 1)") }
            if index < 2, !isWatched(row) { fail("episode \(index + 1), marked watched, reads \(itemState(row))") }
            if index == 0, !label.contains("its still shows 1") { fail("watched episode 1's row hides its description: '\(label)'") }
            if index == 3, !label.contains("Description hidden") || label.contains("its still shows 4") {
                fail("unwatched episode 4's row reads '\(label)', not \"Description hidden\" alone")
            }
            if (isPhone && index == 4) || index == 7 { shot("episodes at \(index + 1)") }
        }
    }

    func testAddToLibraryShowsInLibraryAndItsMenuRemovesIt() throws {
        try launchDev(addon: true, open: Self.opened("detail:movie:tt0111161"))
        let library = require("detail.library", timeout: 60, "the movie's details show the library button")
        let add = library.label
        activate(library)
        if !waitUntil(10, { element("detail.library").exists && element("detail.library").label != add }) { fail("Add to library doesn't turn into its undo") }
        shot("added to the library")

        #if os(iOS)
        activate(scrollTo("detail.link.0.0", "the details' information lists the movie's genres as links"))
        // The genre's Discover screen comes with the tabs (iPhone's tab bar, iPad's floating one).
        if !waitUntil(20, { !tabIds().isEmpty }) { fail("a genre link doesn't open Discover with the tabs") }
        activate(phoneTab("nav.library"))
        #else
        activate(element("nav.library"))
        #endif
        require("library.item.0", "the Library lists the movie")
        #if os(iOS)
        requireFiltersOnScreen(Self.libraryFilters, "the Library's filters")
        #endif
        shot("library")

        // The view menu, the row's first capsule, names the view shown and switches to the other.
        #if os(macOS)
        require("library.view", timeout: 5, "the Library shows its view menu")
        #endif
        let libraryView = menuText("library.view")
        activate(element("library.view"))
        pickMenuEntry(require("library.view.option.1", timeout: 5, "the view menu lists Continue Watching"))
        if !waitUntil(5, { !menuText("library.view").isEmpty && menuText("library.view") != libraryView }) {
            fail("picking Continue Watching in the view menu leaves it on \(menuText("library.view"))")
        }
        requireGone("library.item.0", "Continue Watching lists nothing: the movie was never played")
        #if os(iOS)
        requireFiltersOnScreen(Self.libraryFilters, "Continue Watching's filters")
        #endif
        shot("continue watching")
        activate(element("library.view"))
        pickMenuEntry(require("library.view.option.0", timeout: 5, "the view menu lists Library"))
        if !waitUntil(5, { menuText("library.view") == libraryView }) { fail("picking Library in the view menu doesn't show it") }
        let item = require("library.item.0", "back on Library, the movie is listed")

        openItemMenu(item)
        let remove = require("library.item.0.menu.removeFromLibrary", timeout: 5, "a \(Self.menuGesture) on the movie opens its menu")
        shot("library item menu")
        pickMenuEntry(remove)
        requireGone("library.item.0", "the menu's second entry removes the movie from the library")
        shot("library empty")
    }

    /// "More like this" (`detail.related`), from the test addon's similar catalogs (`--similar`) and the fake Trakt's
    /// related titles (`--dev-trackers`, no account connected); iPad and the Mac show it in the details' column, a
    /// movie's across the page:
    /// - the test addon's movie whose meta names 12 Angry Men's IMDb id: the addon's two titles (the bright-backdrop
    ///   movie, The Godfather), then Trakt's without The Godfather again or 12 Angry Men itself (Cool Hand Luke,
    ///   Goodfellas), Trakt asked once with the app's client id and no account; The Godfather's menu (a long press, a
    ///   right click) marks it watched; the first card opens its title's details;
    /// - the test addon's series, which has no IMDb id: the addon's two series alone, Trakt never asked;
    /// - a movie whose similar catalog answers none, and no IMDb id: the heading over "No similar titles found.";
    /// - the remux movie, which no similar catalog answers (404) and has no IMDb id: no row at all;
    /// - iOS: the first movie at the largest accessibility text size (shot).
    func testMoreLikeThis() throws {
        let movie = ["Tortuga Hero Plain Test", "The Godfather", "Cool Hand Luke", "Goodfellas"]
        // The fake keeps every request of the run: this test's are those after the ones already there.
        let earlier = try relatedRequests().count
        try launchDev(addon: true, trackers: true, open: Self.opened("detail:movie:tortuga-related"))
        requireRelated(movie, "the movie with an IMDb id")
        scrollTo("detail.related.item.0", "the movie's More like this")
        showCaptions()
        shot("more like this, movie")
        let asked = try relatedRequests().dropFirst(earlier)
        if asked.map(\.path) != ["/movies/tt0050083/related"] || asked.contains(where: { !$0.clientId || $0.bearer }) {
            fail("Trakt is asked once for the movie's related titles, with the client id and no account: \(Array(asked))")
        }
        let godfather = element("detail.related.item.1")
        openItemMenu(godfather)
        pickMenuEntry(require("detail.related.item.1.menu.markWatched", timeout: 5, "a \(Self.menuGesture) on The Godfather's card opens its menu"))
        if !waitUntil(10, { godfather.exists && isWatched(godfather) }) {
            fail("Mark as watched in a More like this card's menu doesn't mark The Godfather watched: its value reads \(itemState(godfather))")
        }
        shot("more like this, card marked watched")
        activate(element("detail.related.item.0"))
        let opened = app.descendants(matching: .any).matching(identifier: "detail.title")
            .matching(NSPredicate(format: "label == %@ OR value == %@", "Tortuga Hero Plain Test", "Tortuga Hero Plain Test")).firstMatch
        if !opened.exists(within: 30) { fail("the first card opens Tortuga Hero Plain Test's details") }

        let beforeSeries = try relatedRequests().count
        try launchDev(addon: true, trackers: true, open: Self.opened("detail:series:tortuga-series"))
        requireRelated(["Tortuga Hero Wide Test", "Tortuga Seasons Test"], "the series without an IMDb id")
        // iPad and the Mac: in the details' column beside the episodes, scrolled there (not at the window's middle,
        // which is the episodes').
        let card = element("detail.related.item.0")
        for _ in 0..<6 where !liesInside(card) { showCaptions() }
        if !liesInside(card) { fail("the series' More like this doesn't come into the window") }
        showCaptions()
        shot("more like this, series")
        if try relatedRequests().count != beforeSeries {
            fail("Trakt isn't asked for a title without an IMDb id")
        }

        try launchDev(addon: true, trackers: true, open: Self.opened("detail:movie:tortuga-related-empty"))
        require("detail.title", timeout: 60, "the empty movie's details show")
        let empty = text(of: scrollTo("detail.related.empty", "the empty row"))
        if !empty.contains("No similar titles found.") { fail("the empty row says so, not '\(empty)'") }
        shot("more like this, empty")

        try launchDev(addon: true, trackers: true, open: Self.opened("detail:movie:tortuga-remux"))
        require("detail.title", timeout: 60, "the remux movie's details show")
        let related = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'detail.related'")).firstMatch
        if !waitUntil(30, { !related.exists }) || !holds(for: 3, { !related.exists }) {
            fail("no row where no source answers: \(related.identifier) shows")
        }

        #if os(iOS)
        try launchDev(addon: true, trackers: true, open: ["detail:movie:tortuga-related"], textSize: "UICTContentSizeCategoryAccessibilityXXXL")
        requireRelated(movie, "the movie at the largest text size")
        scrollTo("detail.related.item.0", "the movie's More like this at the largest text size")
        showCaptions()
        shot("more like this, largest text")
        #endif
    }

    /// Waits for "More like this" to show `titles` (its cards' labels, in order) and no more; fails with `what`
    /// otherwise.
    private func requireRelated(_ titles: [String], _ what: String) {
        require("detail.title", timeout: 60, "\(what): the details show")
        require("detail.related.item.0", timeout: 60, "\(what): More like this shows a card")
        requireShelvesSettled("detail.related", what)
        let shown = (0...titles.count).compactMap { index -> String? in
            let card = element("detail.related.item.\(index)")
            return card.exists ? card.label : nil
        }
        if shown != titles { fail("\(what): More like this shows \(shown), not \(titles)") }
    }

    /// The page (iPad's and the Mac's details column) a third of the window further, so the cards' captions show too
    /// where it goes on below the row.
    private func showCaptions() {
        let window = appWindow
        #if os(macOS)
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.6)).scroll(byDeltaX: 0, deltaY: -window.frame.height / 3)
        #else
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.7)).press(
            forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.37)),
            withVelocity: .slow, thenHoldForDuration: 0.3
        )
        #endif
    }

    /// The fake Trakt's calls for related titles so far (its /_log's requests): path, whether the client id came and
    /// whether a bearer token did.
    private func relatedRequests() throws -> [(path: String, clientId: Bool, bearer: Bool)] {
        let requests = try fakeTrackers("/_log")["requests"] as? [[String: Any]] ?? []
        return requests.compactMap { request in
            guard let path = request["path"] as? String, path.hasSuffix("/related") else { return nil }
            return (path, request["clientId"] as? Bool ?? false, request["bearer"] as? Bool ?? false)
        }
    }

    // MARK: What the two platforms' steps differ in

    /// The `--dev-open` screens of the shared tests' title `screen`: on the Mac over the Board, as every Mac test opens
    /// its title, on iOS alone.
    private static func opened(_ screen: String) -> [String] {
        #if os(macOS)
        ["board", screen]
        #else
        [screen]
        #endif
    }

    /// An iPhone: one page, the ratings row's lines centred, the Streams sheet without Close.
    private var isPhone: Bool {
        #if os(iOS)
        !isPad
        #else
        false
        #endif
    }

    #if os(iOS)
    /// The Library's filter capsules: the view, the type and the sort.
    private static let libraryFilters = ["library.view", "library.type", "library.sort"]

    /// The action row inside the screen's margins (the page's 16 pt), and on iPhone one compact group: every item close
    /// beside the one before it, the whole row centred, and, `playAtItsWidth`, Play no wider than it needs (not
    /// stretched across the row to the margins: a row that reaches them with Play at its least width, 140 pt, is one
    /// whose items fill it, as a movie's with the download and the trailer). The row's items in order: Play, choosing
    /// the stream, the library, the watched mark, the download, the trailer and ⋯, each where it shows.
    func requireActionRowOnScreen(_ what: String, playAtItsWidth: Bool) {
        let play = element("detail.play")
        let rest = ["detail.chooseStream", "detail.library", "detail.watched", "detail.download", "detail.trailer", "detail.share", "detail.overflow"].map { element($0) }
        let items = ([play] + rest).filter(\.exists).map(\.frame)
        let row = items.reduce(play.frame) { $0.union($1) }
        let screen = app.windows.firstMatch.frame
        if row.minX < screen.minX + 15 || row.maxX > screen.maxX - 15 {
            fail("\(what) runs from x \(Int(row.minX)) to \(Int(row.maxX)), past the screen's margins (\(Int(screen.minX + 16))–\(Int(screen.maxX - 16)))")
        }
        // On iPhone, one compact group.
        if !isPad {
            if playAtItsWidth, row.minX - screen.minX < 24, play.frame.width > 141 {
                fail("\(what) runs out to x \(Int(row.minX)): Play stretches to \(Int(play.frame.width)) pt")
            }
            for (left, right) in zip(items, items.dropFirst()) where right.minX - left.maxX > 12 {
                fail("\(what): \(Int(right.minX - left.maxX)) pt between its items at x \(Int(left.maxX)) and \(Int(right.minX))")
            }
            if abs(row.midX - screen.midX) > 2 { fail("\(what): its centre is at x \(Int(row.midX)), the screen's at \(Int(screen.midX))") }
        }
    }
    #endif
}
