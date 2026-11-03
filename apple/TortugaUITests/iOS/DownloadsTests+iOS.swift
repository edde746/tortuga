import XCTest

extension DownloadsTests {
    func test5OfflineMarksFromContinueWatchingWithoutTheAddon() throws {
        executionTimeAllowance = 300
        try launchDownloads(Self.data, settings: ["offline=true"], progress: [Self.seasonEpisodeInProgress, Self.seriesEpisodeInProgress])
        require("offline.state", timeout: 30, "offline, Home shows the offline state")
        requireContinueWatchingCaption("S1E3", "Home's Continue Watching shows the season series' S1E3 in progress")
        shot("home offline: two series in progress")
        removeTheTestAddon()
        ready(phoneTab("nav.board")).tap()
        require("offline.state", timeout: 30, "back on Home, it shows the offline state")

        // The two checks each take their shot whatever the other found: on code without the saved details' fallback
        // neither the card's move nor the toast comes.
        var problems: [String] = []
        markWatchedOnContinueWatching(Self.seasonSeriesName)
        if !continueWatchingCaption("S1E4").exists(within: 15) {
            problems.append("Mark as watched on the season series' S1E3 card leaves it at \(continueWatchingCaptions()), not S1E4")
        }
        shot("season series marked from its saved details: S1E4 up next")
        markWatchedOnContinueWatching(Self.otherSeriesName)
        let notice = element("app.menuNotice")
        if notice.exists(within: 10) {
            shot("nothing saved: the toast")
            if !notice.label.withoutDirectionMarks.hasPrefix("Couldn't mark \(Self.otherSeriesName)") { problems.append("the toast reads \"\(notice.label)\"") }
        } else {
            shot("nothing saved: no toast")
            problems.append("Mark as watched on \(Self.otherSeriesName), whose details didn't load, shows no toast (app.menuNotice)")
        }
        if !continueWatchingCaption("S1E1").exists {
            problems.append("\(Self.otherSeriesName)'s card moved on without its details: \(continueWatchingCaptions())")
        }
        if !problems.isEmpty { fail(problems.joined(separator: "; ")) }
    }

    /// The season series' S1E3 at 10 s of 30 (S1E1 is watched: test 3), a `--dev-progress` spec. The other series
    /// (`seriesEpisodeInProgress`) has nothing downloaded in this database.
    static let seasonEpisodeInProgress = "series:tortuga-dl-season:tortuga-dl-season:1:3:10:30"

    /// Settings → Addons: the test addon's ⋯ → Remove, confirmed.
    private func removeTheTestAddon() {
        openSection("addons")
        let row = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier MATCHES %@ AND label CONTAINS %@", #"addons\.row\.[0-9]+"#, "Tortuga Test")).firstMatch
        if !row.exists(within: 20) { return fail("Settings → Addons doesn't list the test addon") }
        let id = row.identifier
        let name = row.label
        ready(require("\(id).more", "the test addon's row shows ⋯")).tap()
        pickMenuEntry(require("\(id).remove", timeout: 5, "⋯ on the test addon offers Remove"))
        ready(require("addons.remove.confirm", timeout: 5, "Remove asks first")).tap()
        requireNoAddonRow(labelled: name, "confirming removes the test addon")
    }

    /// Mark as watched from the menu of Home's Continue Watching card titled `title` (a long press).
    private func markWatchedOnContinueWatching(_ title: String) {
        let card = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier MATCHES %@ AND label == %@", #"board\.cw\.[0-9]+"#, title)).firstMatch
        if !card.exists(within: 30) { return fail("Home's Continue Watching has no card for \(title)") }
        let id = card.identifier
        ready(card).press(forDuration: 1.2)
        pickMenuEntry(require("\(id).menu.markWatched", timeout: 5, "a long press on \(title)'s card offers Mark as watched"))
    }

    /// A Continue Watching card whose caption starts with the episode `code` ("S1E4 · Up next"), read in its value (the
    /// caption under the card is hidden from accessibility).
    private func continueWatchingCaption(_ code: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier MATCHES %@ AND value BEGINSWITH %@", #"board\.cw\.[0-9]+"#, "\(code) ")).firstMatch
    }

    /// Waits for the caption starting with `code`; fails with `what` and the captions shown when it doesn't come.
    private func requireContinueWatchingCaption(_ code: String, _ what: String) {
        if !continueWatchingCaption(code).exists(within: 30) { fail("\(what): Home shows \(continueWatchingCaptions())") }
    }
}
