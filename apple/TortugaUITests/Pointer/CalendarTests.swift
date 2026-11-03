import XCTest

/// The Calendar and the new-episode badges on iPhone, iPad and the Mac (`CalendarStore`, the shared `EpisodeFeed`), on
/// the test addon's episodes addon (`CalendarTestSupport`): a series watched before its episode 4 came out counts it on
/// its Continue Watching and Library cards ("1 new episode"); Library's Calendar button and Home's Calendar shortcut
/// open the Calendar, which lists the episode on its day, says when a month has none or the library is empty, and
/// opens the series' Detail from an episode. A series caught up on comes back to Continue Watching on its next
/// episode's Up next once that is out.
final class CalendarTests: TortugaUITestCase {
    func testANewEpisodeCountsOnItsCardsAndTheCalendarListsIt() throws {
        let data = freshCalendarData()
        // The series watched now, without the episodes addon: no badge, as no addon was asked.
        try launchCalendar(data: data, episodes: false, watched: true)
        let watched = require("board.cw.0", timeout: 60, "Home shows the series in Continue Watching")
        if watched.label != Self.calendarSeriesName { fail("Continue Watching's card is \(watched.label), not the series") }
        if let value = watched.value as? String, value.contains(Self.newEpisodesWords) {
            fail("without an addon to ask, the series' card reads \(value)")
        }
        app.terminate()

        let out = try episodeComesOut()
        try launchCalendar(data: data, episodes: true)
        requireValue("board.cw.0", Self.newEpisodeValue, timeout: 60, "Home's Continue Watching card counts the episode out since")
        shot("home badge")
        activate(navItem("nav.library", "the sidebar lists Library"))
        requireValue("library.item.0", Self.newEpisodeValue, "Library's card counts the episode out since")
        shot("library badge")

        activate(require("library.calendar", timeout: 10, "Library shows the Calendar button"))
        requireCalendarMonth(day: out.day, nextDay: out.nextDay, "Library's Calendar button")
        shot("calendar")

        for step in 0..<2 {
            let month = element("calendar.month").label
            activate(element("calendar.next"))
            if !waitUntil(10, { element("calendar.month").label != month }) { fail("Next month leaves the Calendar on \(month)") }
            if step == 0 {
                shot("next month")
                requireTodayButton("next month")
            }
        }
        requireCalendarEmpty(Self.emptyMonthText, "two months on")
        shot("empty month")
        activate(require("calendar.today", "away from this month, the Calendar offers Today"))
        requireCalendarMonth(day: out.day, nextDay: out.nextDay, "Today")

        let row = scrollTo(element("calendar.episode.\(Self.newEpisode)"), "episode 4 comes into view")
        shot("calendar episodes")
        activate(row)
        let title = require("detail.title", timeout: 30, "episode 4 opens its series' Detail")
        if title.label != Self.calendarSeriesName { fail("episode 4 opens \(title.label), not its series") }
        scrollTo("detail.episode.3", "episode 4's row on its series' Detail comes into view")
        shot("detail from calendar")
        goBack()
        require("calendar.month", timeout: 10, "back from Detail shows the Calendar")
    }

    func testACaughtUpSeriesComesBackOnItsNextEpisodeOnceItIsOut() throws {
        // Episode 4 listed ahead of its release, which comes while Home shows: no new launch brings the series back.
        let outIn: TimeInterval = 60
        try episodeComesOut(in: outIn)
        try launchCaughtUp(data: freshCalendarData())
        let first = require("board.cw.0", timeout: 45, "Home shows Continue Watching")
        if first.label == Self.calendarSeriesName || element("board.cw.1").exists {
            fail("caught up on, the series is in Continue Watching before episode 4 is out")
        }
        shot("caught up")
        if !waitUntil(outIn + 30, { element("board.cw.0").label == Self.calendarSeriesName }) {
            fail("episode 4 out, Continue Watching still leads with \(element("board.cw.0").label)")
        }
        requireValue("board.cw.0", "S1E4 · Up next", "the series comes back on episode 4, Up next")
        shot("back on its next episode")
    }

    func testHomesShortcutOpensTheCalendar() throws {
        try launchDev(addon: true)
        let row = require("board.shortcuts", timeout: 30, "Home shows its shortcuts")
        let chip = require("board.shortcut.calendar", "Home's shortcuts offer the Calendar")
        if chip.label != "Calendar" { fail("Home's Calendar shortcut reads \(chip.label)") }
        // The last chip of the row, past an iPhone's width: the row scrolled along to it.
        scrollAlong(row, toShow: chip)
        activate(chip)
        requireCalendarEmpty(Self.emptyLibraryText, timeout: 30, "the Calendar with nothing in the library")
        shot("calendar empty library")
        goBack()
        require("board.shortcut.calendar", timeout: 10, "back from the Calendar shows Home")
    }
}
