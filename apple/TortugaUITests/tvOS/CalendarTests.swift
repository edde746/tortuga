import XCTest

/// The Calendar and the new-episode badges on Apple TV (`CalendarStore`, the shared `EpisodeFeed`), on the test addon's
/// episodes addon (`CalendarTestSupport`): a series watched before its episode 4 came out counts it on its Continue
/// Watching and Library cards ("1 new episode"); Library's Calendar button, at the end of its row of filters, and
/// Home's Calendar shortcut open the Calendar. There focus lands where the screen states: on the entry day as it opens,
/// on a day's first episode by Select, back on the episode's day from the agenda, on a day's episodes from the grid, on
/// Previous month from the grid; the Calendar says when a month has none or the library is empty, and an episode opens
/// its series' Detail. A series caught up on comes back to Continue Watching on its next episode's Up next once that is
/// out.
final class CalendarTests: TortugaUITestCase {
    func testANewEpisodeCountsOnItsCardsAndTheCalendarListsIt() throws {
        let data = freshCalendarData()
        // The series watched now, without the episodes addon: no badge, as no addon was asked.
        try launchCalendar(data: data, episodes: false, watched: true)
        let watched = require("board.cw.0", timeout: 90, "Home shows the series in Continue Watching")
        if watched.label != Self.calendarSeriesName { fail("Continue Watching's card is \(watched.label), not the series") }
        if let value = watched.value as? String, value.contains(Self.newEpisodesWords) {
            fail("without an addon to ask, the series' card reads \(value)")
        }
        app.terminate()

        let out = try episodeComesOut()
        let episodeDays = [out.day] + (out.nextDay.map { [$0] } ?? [])
        try launchCalendar(data: data, episodes: true)
        requireValue("board.cw.0", Self.newEpisodeValue, timeout: 90, "Home's Continue Watching card counts the episode out since")
        shot("home badge")

        require("nav.board", "the tab bar shows")
        focusTabBar()
        for _ in 0..<6 where !element("library.view").exists { press(forward) }
        require("library.view", timeout: 5, "Right along the tab bar reaches Library")
        requireValue("library.item.0", Self.newEpisodeValue, "Library's card counts the episode out since")
        press(.down)
        moveFocus(to: "library.calendar", with: forward, limit: 6, "Down and Right reach the Calendar button at the end of Library's filters")
        shot("library badge")
        press(.select)

        // As it opens, the first day with episodes from today on.
        requireCalendarMonth(day: out.day, nextDay: out.nextDay, "Library's Calendar button")
        let today = Calendar.current.component(.day, from: Date())
        let entry = episodeDays.first { $0 >= today } ?? out.day
        requireFocus("calendar.day.\(entry)", timeout: 10, "the Calendar opens on the first day with episodes from today on")
        shot("calendar")
        press(.select)
        requireFocus("calendar.episode.\(firstEpisode(on: entry, out))", "Select on a day moves focus to its first episode")
        shot("calendar episode focused")
        press(back)
        requireFocus("calendar.day.\(entry)", "from the agenda, focus lands back on the episode's day")

        // Along the grid a day at a time until the agenda: it lands on the first episode of the day left, else of the
        // next day with episodes after it, else of the last.
        var left = entry
        for _ in 0..<7 where focusedId?.hasPrefix("calendar.episode.") != true {
            if let id = focusedId, id.hasPrefix("calendar.day."), let day = Int(id.dropFirst("calendar.day.".count)) { left = day }
            press(forward)
        }
        let landing = episodeDays.first { $0 >= left } ?? episodeDays[episodeDays.count - 1]
        requireFocus("calendar.episode.\(firstEpisode(on: landing, out))", "from day \(left), focus lands on the agenda's episodes of day \(landing)")
        press(back)
        requireFocus("calendar.day.\(landing)", "from the agenda, focus lands back on the episode's day")

        for _ in 0..<7 where focusedId?.hasPrefix("calendar.day.") == true { press(.up) }
        requireFocus("calendar.previous", "Up from the grid lands on Previous month in this month")
        press(forward)
        requireFocus("calendar.next", "Right of Previous month is Next month")
        for step in 0..<2 {
            let month = element("calendar.month").label
            press(.select)
            if !waitUntil(10, { element("calendar.month").label != month }) { fail("Next month leaves the Calendar on \(month)") }
            if step == 0 {
                shot("next month")
                requireTodayButton("next month")
            }
        }
        requireCalendarEmpty(Self.emptyMonthText, "two months on")
        shot("empty month")
        moveFocus(to: "calendar.today", with: back, limit: 3, "Left along the month's bar reaches Today")
        press(.select)
        requireCalendarMonth(day: out.day, nextDay: out.nextDay, "Today")
        requireFocus("calendar.previous", "Today, gone with the month it brought back, hands focus to Previous month")

        // Into the grid on the entry day again (a month shown afresh), Select to its episodes, and on to episode 4.
        for _ in 0..<3 where focusedId?.hasPrefix("calendar.day.") != true { press(.down) }
        requireFocus("calendar.day.\(entry)", "Down from the month's bar lands on the entry day")
        press(.select)
        walkFocus(to: "calendar.episode.\(Self.newEpisode)", limit: 3, "focus reaches episode 4")
        press(.select)
        let title = require("detail.title", timeout: 30, "episode 4 opens its series' Detail")
        if title.label != Self.calendarSeriesName { fail("episode 4 opens \(title.label), not its series") }
        shot("detail from calendar")
        press(.menu)
        require("calendar.month", timeout: 10, "Menu on Detail shows the Calendar again")
    }

    func testACaughtUpSeriesComesBackOnItsNextEpisodeOnceItIsOut() throws {
        // Episode 4 listed ahead of its release, which comes while Home shows: no new launch brings the series back.
        let outIn: TimeInterval = 90
        try episodeComesOut(in: outIn)
        try launchCaughtUp(data: freshCalendarData())
        let first = require("board.cw.0", timeout: 75, "Home shows Continue Watching")
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
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once Home's shelves load")
        press(.up)
        if !waitUntil(5, { focusedId?.hasPrefix("board.shortcut.") == true }) {
            fail("Up from the first shelf leaves focus on \(focusedId ?? "nothing"), not Home's shortcuts")
        }
        moveFocus(to: "board.shortcut.calendar", with: forward, limit: 6, "Right along Home's shortcuts reaches the Calendar")
        if element("board.shortcut.calendar").label != "Calendar" { fail("Home's Calendar shortcut reads \(element("board.shortcut.calendar").label)") }
        press(.select)
        requireCalendarEmpty(Self.emptyLibraryText, timeout: 30, "the Calendar with nothing in the library")
        let today = Calendar.current.component(.day, from: Date())
        requireFocus("calendar.day.\(today)", timeout: 10, "a Calendar without episodes opens on today")
        shot("calendar empty library")
        press(.menu)
        requireFocus("board.shortcut.calendar", timeout: 10, "Menu goes back to Home, on the Calendar shortcut")
    }

    /// Along a row in the reading direction, and against it.
    private var forward: XCUIRemote.Button { Self.rightToLeft ? .left : .right }
    private var back: XCUIRemote.Button { Self.rightToLeft ? .right : .left }

    /// The first episode of `day`: 4 on the day it came out, else 5.
    private func firstEpisode(on day: Int, _ out: (day: Int, nextDay: Int?)) -> String {
        day == out.day ? Self.newEpisode : Self.nextEpisode
    }
}
