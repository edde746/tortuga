import XCTest

/// DownloadsTests' Keep synced steps, on a database of their own (`keepSyncedData`), so they run alone too, in order:
/// 1. Keep synced 3 on the season series and 1 on the test addon's other series, each from its Detail's Download menu,
///    each rule queueing what it keeps; the menu's Manage Keep synced opens Keep synced listing both, and so does
///    Downloads' row, which reads 2 series.
/// 2. The other series' rule paused from its row: the row says Paused and offers Resume, the season's keeps running.
/// 3. S1E1 marked watched on the season series' Detail: the rule tops itself up with S1E4, and the app's notice says so
///    over Detail.
/// 4. iPhone at the largest accessibility text size: the rows and their Stop within reach, Stop's question with its three
///    answers, Cancel keeping the rule.
/// 5. The season rule's Stop asks first (Cancel keeps it), naming the four episodes it queued; Delete downloads stops it
///    and deletes them, the paused rule and its download stay, and Downloads' row reads 1 series.
extension DownloadsTests {
    /// The database the Keep synced steps share, fresh for each run of the test process.
    static let keepSyncedData = "keep-synced-" + UUID().uuidString.prefix(8)

    func test8KeepSynced1RulesFromDetailListed() throws {
        executionTimeAllowance = 600
        let season = Self.seasonSeries, other = Self.otherSeries
        try launchDownloads(Self.keepSyncedData, open: ["board", "detail:series:\(other)", "detail:series:\(season)"])
        let seasonTitle = requireDetail(of: Self.seasonSeriesName)
        openDownloadMenu()
        pickMenuEntry(downloadMenuEntry("sync:3", "Keep 3 unwatched episodes", "the season series' Download menu offers Keep 3 unwatched episodes"))
        requireEpisodesQueued(3, "Keep 3 unwatched episodes")
        goBack()
        if !waitUntil(10, { !seasonTitle.exists }) { fail("Back doesn't leave the season series' Detail") }
        requireDetail(of: Self.otherSeriesName)
        openDownloadMenu()
        pickMenuEntry(downloadMenuEntry("sync:1", "Keep 1 unwatched episode", "the other series' Download menu offers Keep 1 unwatched episode"))
        requireEpisodesQueued(1, "Keep 1 unwatched episode")
        if element("app.keepSyncedNotice").exists { fail("a rule the user set shows the notice") }

        // Detail's way in: the Download menu's Manage Keep synced (the menu's shot taken first, for the code before it).
        openDownloadMenu()
        _ = downloadMenuEntry("sync:1", "Keep 1 unwatched episode", "the Download menu opens")
        shot("detail: the download menu")
        let manage = menuEntry("Manage Keep synced", "with a rule, the Download menu offers Manage Keep synced")
        #if os(iOS)
        // The menu runs past the screen's bottom, the entry below it: its list scrolled for the shot. On an iPhone the
        // narrowest list on the screen (the screens' own span it), else the screen's middle, which the menu covers; on
        // an iPad the list holding the entry (Detail's details column is narrower than the screen too).
        if isPad {
            let list = app.collectionViews.containing(NSPredicate(format: "label == %@", "Manage Keep synced")).firstMatch
            if list.exists { list.swipeUp(velocity: .slow) }
        } else {
            let menu = app.collectionViews.allElementsBoundByIndex.filter { $0.exists && $0.frame.width < app.frame.width }
                .min { $0.frame.width < $1.frame.width }
            (menu ?? app).swipeUp(velocity: .slow)
        }
        #endif
        shot("detail: the download menu's Manage Keep synced")
        pickMenuEntry(manage)
        requireRules([(season, Self.seasonSeriesName, "Keep 3 unwatched episodes"), (other, Self.otherSeriesName, "Keep 1 unwatched episode")])
        goBack()
        requireDetail(of: Self.otherSeriesName)
        goBack()

        // Downloads' way in: its row, reading how many series are kept.
        openDownloads()
        _ = element("downloads.keepSynced").exists(within: 10)
        shot("downloads")
        let link = require("downloads.keepSynced", "Downloads leads to Keep synced")
        requireText("downloads.keepSynced", contains: "2 series", "Downloads' Keep synced row")
        activate(link)
        requireRules([(season, Self.seasonSeriesName, "Keep 3 unwatched episodes"), (other, Self.otherSeriesName, "Keep 1 unwatched episode")])
        require("keepSynced.note", "Keep synced says what a rule does")
        shot("keep synced: two rules")
    }

    func test8KeepSynced2PauseARule() throws {
        executionTimeAllowance = 300
        let rule = "keepSynced.rule.\(Self.otherSeries)"
        try openKeepSynced()
        let pause = require("\(rule).pause", "the other series' rule offers Pause")
        if pause.label.withoutDirectionMarks != "Pause \(Self.otherSeriesName)" { fail("the rule's Pause is labelled \"\(pause.label)\"") }
        activate(pause)
        let resume = require("\(rule).resume", timeout: 10, "paused, the rule offers Resume")
        if resume.label.withoutDirectionMarks != "Resume \(Self.otherSeriesName)" { fail("the rule's Resume is labelled \"\(resume.label)\"") }
        requireText(rule, contains: "Paused", "the paused rule's row")
        require("keepSynced.rule.\(Self.seasonSeries).pause", "the season series' rule keeps running")
        if allText(of: element("keepSynced.rule.\(Self.seasonSeries)")).contains("Paused") {
            fail("pausing one rule pauses the season series' too")
        }
        shot("keep synced: one rule paused")
    }

    func test8KeepSynced3NoticeAfterATopUp() throws {
        executionTimeAllowance = 300
        try launchDownloads(Self.keepSyncedData, open: ["board", "detail:series:\(Self.seasonSeries)"])
        requireDetail(of: Self.seasonSeriesName)
        if element("app.keepSyncedNotice").exists { fail("the notice shows before any rule ran on its own") }
        // S1E1 watched: two unwatched episodes kept of three, so the rule queues S1E4.
        openItemMenu(scrollTo("detail.episode.0", "the season series lists S1E1"))
        pickMenuEntry(require("detail.menu.item.markWatched", timeout: 5, "the episode's menu offers Mark as watched"))
        let notice = require("app.keepSyncedNotice", timeout: 30, "a rule topping itself up shows the notice")
        shot("detail: the Keep synced notice")
        let text = fullText(of: notice).withoutDirectionMarks
        if text != "Keep synced added 1 episode of \(Self.seasonSeriesName)" { fail("the notice reads \"\(text)\"") }
        requireGone("app.keepSyncedNotice", timeout: 15, "the notice goes once it has shown")
        requireEpisodesQueued(4, "the rule topped up")
    }

    #if os(iOS)
    func test8KeepSynced4LargestText() throws {
        try XCTSkipIf(isPad, "the iPhone's check")
        executionTimeAllowance = 300
        let season = "keepSynced.rule.\(Self.seasonSeries)"
        try openKeepSynced(textSize: "UICTContentSizeCategoryAccessibilityXXXL")
        require(season, "Keep synced lists the season series' rule")
        shot("keep synced: largest text")
        let stop = scrollTo("\(season).stop", "at the largest text size, the season series' Stop is within reach")
        shot("keep synced: largest text, the season series' rule")
        activate(stop)
        let cancel = require("keepSynced.stop.cancel", timeout: 5, "Stop asks first")
        require("keepSynced.stop.keep", "the question offers Keep downloads")
        require("keepSynced.stop.delete", "the question offers Delete downloads")
        shot("keep synced: largest text, the stop question")
        activate(cancel)
        requireGone("keepSynced.stop.cancel", "Cancel closes the question")
        require(season, timeout: 5, "Cancel keeps the rule")
        scrollTo("keepSynced.rule.\(Self.otherSeries).resume", "at the largest text size, the paused rule's Resume is within reach")
        shot("keep synced: largest text, the paused rule")
    }
    #endif

    func test8KeepSynced5StopDeletesItsDownloads() throws {
        executionTimeAllowance = 300
        let season = Self.seasonSeries, other = Self.otherSeries
        try openKeepSynced()

        // Stop asks first; Cancel keeps the rule.
        activate(require("keepSynced.rule.\(season).stop", "the season series' rule offers Stop"))
        activate(require("keepSynced.stop.cancel", timeout: 5, "Stop asks first"))
        requireGone("keepSynced.stop.cancel", "Cancel closes the question")
        require("keepSynced.rule.\(season)", timeout: 5, "Cancel keeps the rule")

        // Asked again: the four episodes the rule queued (three, then S1E4), Keep downloads or Delete downloads.
        activate(require("keepSynced.rule.\(season).stop", "the season series' rule offers Stop"))
        require("keepSynced.stop.cancel", timeout: 5, "Stop asks again")
        require("keepSynced.stop.keep", "the question offers Keep downloads")
        let delete = require("keepSynced.stop.delete", "the question offers Delete downloads")
        // The question names the series inside its direction marks: the texts holding the name, read without them.
        let question = "Stop keeping \(Self.seasonSeriesName) synced?"
        let named = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", Self.seasonSeriesName, Self.seasonSeriesName))
        if !waitUntil(5, { named.allElementsBoundByIndex.contains { self.fullText(of: $0).withoutDirectionMarks.contains(question) } }) {
            fail("the stop question doesn't say \"\(question)\"")
        }
        let count = "4 episodes in Downloads came from Keep synced"
        if !app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", count, count)).firstMatch.exists(within: 5) {
            fail("the stop question doesn't say \"\(count)\"")
        }
        shot("keep synced: the stop question")
        activate(delete)
        requireGone("keepSynced.rule.\(season)", "Delete downloads stops the season series' rule")
        requireText("keepSynced.rule.\(other)", contains: "Paused", "the other series' paused rule stays")
        shot("keep synced: stopped with its downloads deleted")

        // Downloads: the season series' downloads gone, the other series' one there, one series kept.
        goBack()
        requireText("downloads.keepSynced", contains: "1 series", "Downloads' Keep synced row after Stop")
        requireGone("downloads.group.\(season).menu", "Delete downloads deletes the season series' downloads")
        require("downloads.group.\(other).menu", "the paused rule's download stays")
        shot("downloads: after Stop and Delete downloads")
    }

    /// Launches on the Keep synced steps' database and opens Keep synced from Downloads' row.
    private func openKeepSynced(textSize: String? = nil) throws {
        try launchDownloads(Self.keepSyncedData, open: Self.homeOpen, textSize: textSize)
        openDownloads()
        activate(require("downloads.keepSynced", timeout: 10, "Downloads leads to Keep synced"))
        require("keepSynced.list", timeout: 10, "Downloads' Keep synced row opens Keep synced")
    }

    /// Keep synced listing a row per rule in `rules` (series id, name, count) and no other, each with its Pause and Stop.
    private func requireRules(_ rules: [(id: String, name: String, count: String)]) {
        require("keepSynced.list", timeout: 10, "Keep synced shows its rules")
        for rule in rules {
            let row = "keepSynced.rule.\(rule.id)"
            require(row, timeout: 10, "Keep synced lists \(rule.name)'s rule")
            requireText(row, contains: rule.name, "\(rule.name)'s rule")
            requireText(row, contains: rule.count, "\(rule.name)'s rule")
            require("\(row).pause", "\(rule.name)'s rule offers Pause")
            require("\(row).stop", "\(rule.name)'s rule offers Stop")
        }
        let ids = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'keepSynced.rule.'"))
            .allElementsBoundByIndex.map(\.identifier)
        let listed = Set(ids.filter { id in ![".pause", ".resume", ".stop", ".menu"].contains { id.contains($0) } })
        if listed != Set(rules.map { "keepSynced.rule.\($0.id)" }) { fail("Keep synced lists \(listed.sorted()), not \(rules.map(\.id))") }
    }

    /// Waits for Detail to show the title `name`; its title.
    @discardableResult
    private func requireDetail(of name: String) -> XCUIElement {
        let title = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'detail.title' AND label == %@", name)).firstMatch
        if !title.exists(within: 60) { fail("\(name)'s Detail doesn't show after 60 s") }
        return title
    }

    /// Waits for the first `count` episodes of the season shown to show a download (queued, downloading or downloaded):
    /// on iOS its state's line (`detail.episode.<n>.download`), on the Mac its row's text.
    private func requireEpisodesQueued(_ count: Int, _ what: String) {
        for index in 0..<count {
            #if os(macOS)
            let row = scrollTo("detail.episode.\(index)", "\(what): episode \(index + 1) is listed")
            if !waitUntil(30, { row.exists && ["Queued", "Downloading", "Downloaded"].contains { fullText(of: row).contains($0) } }) {
                fail("\(what): episode \(index + 1) reads \"\(fullText(of: row))\" after 30 s, not a download's state")
            }
            #else
            scrollTo("detail.episode.\(index)", "\(what): episode \(index + 1) is listed")
            require("detail.episode.\(index).download", timeout: 30, "\(what): episode \(index + 1) shows its download's state")
            #endif
        }
    }
}
