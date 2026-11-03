import XCTest

extension TrackersTests {
    /// The Board with every tracker connected to the fake shows no failed shelf (a route the fake lacks reads "Simkl
    /// didn't answer as expected."; shot as "board"). My Hero Academia 2 (MyAnimeList and AniList) opens My Hero
    /// Academia on season 2, whose first episode lists the test addon's streams (its `--title`s); Black Lagoon: The
    /// Second Barrage opens Black Lagoon on season 1 brought to episode 13, where it starts; Your Name. opens the film.
    /// The anime-id datasets download on first use.
    func testAnimeListEntriesOpenAsTheirTitlesOnTheirSeason() throws {
        try launchDev(addon: true, trackersConnected: true)
        if !shelfSources("MyAnimeList").firstMatch.exists(within: 90) { fail("the Board has no shelf from MyAnimeList") }
        requireShelvesSettled("board.row", "the Board's shelves with every tracker connected")
        let failed = app.descendants(matching: .any).matching(NSPredicate(format: "identifier MATCHES %@", #"board\.row\.[0-9]+\.retry"#))
        if failed.count > 0 { fail("the Board's shelves \(failed.allElementsBoundByIndex.map(\.identifier)) failed with every tracker connected") }
        shot("board")

        openEntry("Boku no Hero Academia 2nd Season", from: "MyAnimeList")
        requireTitle("My Hero Academia", season: "Season 2", "MyAnimeList's My Hero Academia 2")
        if element("streams.title").exists { fail("opening the entry opens a Streams sheet") }
        shot("mal sequel")
        ready(scrollTo("detail.episode.0", "season 2 lists its episodes")).click()
        require("streams.row.0", timeout: 30, "season 2's first episode lists the test addon's streams")
        shot("mal sequel streams")
        app.typeKey(.escape, modifierFlags: [])
        requireGone("streams.title", "Escape closes the Streams sheet")
        goBack()

        openEntry("Black Lagoon: The Second Barrage", from: "MyAnimeList")
        requireTitle("Black Lagoon", season: "Season 1", "MyAnimeList's Black Lagoon: The Second Barrage")
        let thirteen = element("detail.episode.12")
        let window = windowFrame
        let shown = { thirteen.exists && thirteen.frame.minY >= window.minY && thirteen.frame.maxY <= window.maxY && thirteen.isHittable }
        if !waitUntil(10, shown) {
            fail("Black Lagoon's episode 13 isn't brought into the window (\(window)): it is at \(thirteen.exists ? "\(thirteen.frame)" : "nothing")")
        }
        shot("mal second cour")
        goBack()

        openEntry("Kimi no Na wa.", from: "MyAnimeList")
        let title = require("detail.title", timeout: 120, "MyAnimeList's Your Name. opens its film")
        if !waitUntil(30, { title.exists && text(of: title).hasPrefix("Your Name") }) { fail("Your Name. opens \"\(text(of: title))\"") }
        require("detail.play", timeout: 30, "the film shows Play")
        if element("detail.seasons").exists { fail("the film shows seasons") }
        shot("mal film")
        goBack()

        openEntry("Boku no Hero Academia 2nd Season", from: "AniList")
        requireTitle("My Hero Academia", season: "Season 2", "AniList's My Hero Academia 2")
        shot("anilist sequel")
    }

    /// Clicks the card `title` on the Board's shelf from `source`, the page scrolled to it (down, back up where it went
    /// past).
    private func openEntry(_ title: String, from source: String) {
        require("nav.board", "the sidebar lists Home")
        let sources = shelfSources(source)
        if !sources.firstMatch.exists(within: 90) { fail("the Board has no shelf from \(source)") }
        scrollTo(sources.firstMatch, steps: 30, "the Board's shelf from \(source)")
        requireShelf(from: source, listing: title, timeout: 90, "the Board shows \(source)'s plan-to-watch shelf")
        let row = String(sources.firstMatch.identifier.dropLast(".source".count))
        let card = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label == %@", "\(row).item.", title)).firstMatch
        ready(scrollTo(card, steps: 30, "\(title) on \(source)'s shelf")).click()
    }

    /// Waits for Detail of `name` (the entry's own screen first looks it up: the datasets may still download) on
    /// `season`; fails with `what` when it doesn't come.
    private func requireTitle(_ name: String, season: String, _ what: String) {
        let title = element("detail.title")
        if !waitUntil(120, { title.exists && text(of: title) == name }) {
            fail("\(what): Detail shows \"\(title.exists ? text(of: title) : "no title")\", not \(name)")
        }
        scrollTo("detail.season.0", "\(name) has season chips")
        if !waitUntil(10, { selectedSeasonText() == season }) { fail("\(what): the selected season's chip reads \"\(selectedSeasonText())\", not \(season)") }
    }
}
