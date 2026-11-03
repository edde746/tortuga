import Foundation
import XCTest

/// What the Calendar tests share (`CalendarTests`, Pointer and tvOS): the test addon's episodes addon
/// (tools/media/test_addon.py's `/episodes/`: the calendar's and the badges' catalogs) and its series, whose
/// episodes 1-3 came out long ago, episode 4 once the test calls `/episodes/out`, and episode 5 a day after that.
extension TortugaUITestCase {
    static let calendarSeries = "tortuga-calendar"
    static let calendarSeriesName = "Tortuga Calendar Test"
    static let newEpisode = "tortuga-calendar:1:4"
    static let nextEpisode = "tortuga-calendar:1:5"
    static let newEpisodeValue = "1 new episode"
    /// What any count of new episodes says in a card's value ("1 new episode", "3 new episodes").
    static let newEpisodesWords = "new episode"
    static let emptyLibraryText = "Add series to your library to see their episodes here."
    static let emptyMonthText = "No episodes."

    /// The episodes addon's manifest, served beside the test addon's.
    var episodesAddonURL: String {
        get throws { try addonURL.replacingOccurrences(of: "/manifest.json", with: "/episodes/manifest.json") }
    }

    /// Launches a `--dev` session that keeps its database under `data` (`--dev-data`), with the test addon and, with
    /// `episodes`, the episodes addon installed; with `watched`, the series added to the library and its episode 1
    /// played 6 s of 20 min now (`--dev-library`, `--dev-progress`), so it is in Continue Watching, last watched now.
    func launchCalendar(data: String, episodes: Bool, watched: Bool = false) throws {
        var arguments = ["--dev", "--dev-data", data, "--dev-addon", try addonURL]
        if episodes { arguments += ["--dev-addon", try episodesAddonURL] }
        if watched {
            arguments += [
                "--dev-library", "series:\(Self.calendarSeries)",
                "--dev-progress", "series:\(Self.calendarSeries):\(Self.calendarSeries):1:1:6:1200",
            ]
        }
        launch(arguments + Self.harnessSettings.flatMap { ["--dev-setting", $0] })
    }

    /// Launches like `launchCalendar(data:episodes: true)`, the series caught up on: added to the library and its
    /// episode 3, the last out, played to its end now (`--dev-progress` at its duration), with the test addon's own
    /// series part-way through its episode 1, so Continue Watching holds that one alone.
    func launchCaughtUp(data: String) throws {
        launch([
            "--dev", "--dev-data", data, "--dev-addon", try addonURL, "--dev-addon", try episodesAddonURL,
            "--dev-library", "series:\(Self.calendarSeries)",
            "--dev-progress", "series:\(Self.calendarSeries):\(Self.calendarSeries):1:3:1200:1200",
            "--dev-progress", "series:tortuga-series:tortuga-series:1:1:12:20",
        ] + Self.harnessSettings.flatMap { ["--dev-setting", $0] })
    }

    /// Makes the series' episode 4 come out now, or `seconds` from now, listed ahead of its release
    /// (`/episodes/out?in=`); its release day of the month in UTC, as the Calendar files an episode, and the next day's
    /// where it is in the same month (episode 5's). The app's clock is this Mac's (a simulator, or the Mac itself), and
    /// the addon's too, so no margin is needed past the launch before.
    @discardableResult
    func episodeComesOut(in seconds: TimeInterval = 0) throws -> (day: Int, nextDay: Int?) {
        let out = try addonURL.replacingOccurrences(of: "/manifest.json", with: "/episodes/out?in=\(Int(seconds))")
        guard let url = URL(string: out) else { throw HarnessError("TORTUGA_ADDON is not a URL") }
        _ = try Data(contentsOf: url)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let now = Date().addingTimeInterval(seconds)
        let next = now.addingTimeInterval(86_400)
        let sameMonth = utc.component(.month, from: next) == utc.component(.month, from: now)
        return (utc.component(.day, from: now), sameMonth ? utc.component(.day, from: next) : nil)
    }

    /// A fresh name for `launchCalendar(data:)`'s database.
    func freshCalendarData() -> String {
        "calendar-" + UUID().uuidString.prefix(8)
    }

    /// Waits for `id`'s accessibility value to contain `value` (a card's value also carries its subtitle and progress);
    /// fails with `what` and what it reads instead.
    func requireValue(_ id: String, _ value: String, timeout: TimeInterval = 30, _ what: String) {
        let found = require(id, timeout: timeout, what)
        if !waitUntil(timeout, { (found.value as? String ?? "").contains(value) }) {
            fail("\(what): \(id) reads \"\(found.value as? String ?? "")\", without \"\(value)\"")
        }
    }

    /// Waits for the Calendar's empty text to say `text`; fails with `what` and what it says instead. The Mac reports
    /// the combined text element's text as its value, iOS and tvOS as its label.
    func requireCalendarEmpty(_ text: String, timeout: TimeInterval = 20, _ what: String) {
        let empty = require("calendar.empty", timeout: timeout, what)
        #if os(macOS)
        let said = { empty.value as? String ?? "" }
        #else
        let said = { empty.label }
        #endif
        if !waitUntil(timeout, { said().contains(text) }) {
            fail("\(what): the Calendar says \"\(said())\", not \"\(text)\"")
        }
    }

    /// Waits for the Calendar to show this month with episode 4 under its day's heading (`day`) and, where it is in the
    /// month, episode 5 under the next day's; on the device's today the heading reads "Today".
    func requireCalendarMonth(day: Int, nextDay: Int?, _ what: String) {
        require("calendar.month", timeout: 20, "\(what): the Calendar shows its month")
        require("calendar.episode.\(Self.newEpisode)", timeout: 30, "\(what): the Calendar lists episode 4")
        let heading = require("calendar.agenda.\(day)", timeout: 5, "\(what): episode 4's day has its heading")
        if Calendar.current.component(.day, from: Date()) == day, heading.label != "Today" {
            fail("\(what): episode 4's day, today, is headed \"\(heading.label)\", not Today")
        }
        if let nextDay {
            require("calendar.episode.\(Self.nextEpisode)", timeout: 5, "\(what): the Calendar lists episode 5")
            require("calendar.agenda.\(nextDay)", timeout: 5, "\(what): episode 5's day has its heading")
        }
        if element("calendar.today").exists { fail("\(what): the Calendar opens away from this month (Today shows)") }
    }

    /// Away from this month the month's bar offers the way back as "Today", not as wording that names the month shown.
    func requireTodayButton(_ what: String) {
        let today = require("calendar.today", timeout: 5, "\(what): away from this month, the Calendar offers Today")
        if today.label != "Today" { fail("\(what): the way back to this month reads \"\(today.label)\", not Today") }
    }
}
