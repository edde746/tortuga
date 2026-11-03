import XCTest

/// What the iPhone, iPad and Mac error video tests share (PlayerTests' `testErrorVideoIsNotTakenForTheEpisode`), on the
/// test addon's `tortuga-errvid` series (tools/media/test_addon.py: 24-min episodes, videos `tt9900001:1:<n>` of a
/// made-up IMDb title the fake Trakt hears of): episode 1's one stream is a host's lazy link that answers with
/// a 2-min still error slate (tools/media/make-error-video.sh) under the episode's release file name and a 1 GiB size,
/// in the binge group of episode 2's h264-sdr.mkv (20 s), whose own up next offers S1E3. The launch, the player left to
/// settle without a touch, the fake Trakt's log, Home's Continue Watching captions and the checks, each its own failure.
extension TortugaUITestCase {
    /// The series' details with episode 1's Streams sheet over them, over Home (`--dev-open`).
    static let errorVideoOpen = ["board", "detail:series:tortuga-errvid", "streams:series:tortuga-errvid:tt9900001:1:1"]
    /// The made-up IMDb title of the series' videos.
    static let errorVideoImdb = "tt9900001"

    /// What the player showed while it settled: up next's labels in the order they came, whether the error-video panel
    /// (its "Watch the message" button, `player.error.watch`) and its close button showed, and whether up next offered
    /// S1E3, which only episode 2 does.
    struct ErrorVideoWatch {
        var prompts: [String] = []
        var panel = false
        var panelClose = false
        var episode2 = false
    }

    /// A `--dev` launch on `errorVideoOpen`, episode 1 seeded at 12:00 of 24:00 (`--dev-progress`), every tracker
    /// connected to the fake Trakt, the sleep timer's minutes short (`player.fastTimers`) and auto-play next at its
    /// default (on).
    func launchErrorVideo() throws {
        try launchDev(
            addon: true,
            trackersConnected: true,
            open: Self.errorVideoOpen,
            progress: ["series:tortuga-errvid:tt9900001:1:1:720:1440"],
            settings: ["player.fastTimers=true"]
        )
    }

    /// The player left to settle, at most 150 s (longer than the slate), read twice a second without a touch or a
    /// pointer move (either stops up next's countdown): until the error-video panel shows or up next offers S1E3.
    func watchErrorVideo() -> ErrorVideoWatch {
        var seen = ErrorVideoWatch()
        _ = waitUntil(150) {
            let prompt = promptLabel("player.upNext")
            if !prompt.isEmpty, !seen.prompts.contains(prompt) { seen.prompts.append(prompt) }
            seen.episode2 = seen.episode2 || prompt.contains("S1E3")
            if element("player.error.watch").exists {
                seen.panel = true
                seen.panelClose = element("player.error.close").exists
            }
            return seen.panel || seen.episode2
        }
        return seen
    }

    /// The fake Trakt's scrobbles and history marks of the series' title (its /_log), one line each, in the order it
    /// received them.
    func errorVideoTrakt() throws -> [String] {
        guard let url = URL(string: try trackersURL + "/_log") else { throw HarnessError("TORTUGA_TRACKERS is no URL") }
        let log = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any] ?? [:]
        let ours = { (key: String) in (log[key] as? [[String: Any]] ?? []).filter { $0["imdb"] as? String == Self.errorVideoImdb } }
        // The fake keeps an episode as [season, number].
        let episode = { (entry: [String: Any]) in (entry["episode"] as? [Any] ?? []).map { "\($0)" }.joined(separator: "x") }
        return ours("scrobbles").map { "scrobble \($0["action"] ?? "?") of \(episode($0)) at \($0["progress"] ?? "?") %" }
            + ours("history").map { "history mark of \(episode($0))" }
    }

    /// Home's Continue Watching captions, as the cards' values read them (`itemState`; the caption under a card is
    /// hidden from accessibility): an episode code ("S1E1 · …"), the time left ("12 min left") or "Up next". A card
    /// names its episode only when the video id extends the meta id (BrowseFormat's `episodeCode`); the series' videos
    /// are `tt9900001:1:<n>` under `tortuga-errvid`, so S1E1 at 12:00 of 24:00 reads "12 min left" and the next episode
    /// "Up next".
    func continueWatchingCaptions() -> [String] {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier MATCHES %@", #"board\.cw\.[0-9]+"#))
            .allElementsBoundByIndex.flatMap { itemState($0) }
            .filter { $0.hasPrefix("S1E") || $0.hasSuffix(" left") || $0.contains("Up next") }
    }

    /// What the run left once the player closed, read on each platform its own way: the player's title once settled
    /// (empty when it closed from the error panel), Detail's Play, whether S1E1's row reads watched and its progress
    /// (`itemState`), Continue Watching's first card and Home's captions (`continueWatchingCaptions`), and the fake
    /// Trakt's lines of the title that came during the test (`errorVideoTrakt`).
    struct ErrorVideoAfter {
        var title: String
        var play: String
        var watched: Bool
        var bar: String
        var card: String
        var caption: [String]
        var trakt: [String]
    }

    /// Judges what the player showed (`seen`) and what the run left (`after`), every check read before any is judged:
    /// each line kept (attached as "error video checks"), each that fails its own failure, named: episode 1 not
    /// watched; its row still at 50 % (12:00 of 24:00); Continue Watching's first card the series on S1E1 ("12 min
    /// left", or a caption led by S1E1), not up next; episode 2 never played and up next never shown; no Trakt scrobble
    /// or history mark of the title; the error-video panel with its close button shown.
    func requireNothingRecorded(_ seen: ErrorVideoWatch, _ after: ErrorVideoAfter) {
        let percent = Int(after.bar.filter(\.isNumber)) ?? -1
        let captionOnS1E1 = after.caption.contains { $0 == "12 min left" || $0.hasPrefix("S1E1 ") }
            && !after.caption.contains { $0.contains("Up next") }
        let checks: [(name: String, ok: Bool, detail: String)] = [
            ("episode 1 not watched", !after.watched, "S1E1's row \(after.watched ? "shows watched" : "unwatched"), Play reads '\(after.play)'"),
            ("episode 1 still at 50 %", (49...51).contains(percent), "S1E1's progress bar reads '\(after.bar)', seeded 12:00 of 24:00"),
            ("Continue Watching on S1E1", after.card == "Tortuga Error Video Test" && captionOnS1E1,
             "the first card is '\(after.card)', Home's captions \(after.caption)"),
            ("episode 2 never played, no up next", seen.prompts.isEmpty && !seen.episode2 && !after.title.contains("S1E2"),
             "up next offered \(seen.prompts), the player's title read '\(after.title)'"),
            ("no Trakt scrobble", after.trakt.isEmpty, "the fake Trakt received \(after.trakt)"),
            ("error-video panel shown", seen.panel && seen.panelClose,
             "Watch the message \(seen.panel ? "shown" : "never shown"), its close button \(seen.panelClose ? "shown" : "not shown")"),
        ]
        let attachment = XCTAttachment(string: checks.map { "\($0.ok ? "ok" : "FAIL") \($0.name): \($0.detail)" }.joined(separator: "\n"))
        attachment.name = "error video checks"
        attachment.lifetime = .keepAlways
        add(attachment)
        continueAfterFailure = true
        for check in checks where !check.ok {
            XCTFail("error video: \(check.name): \(check.detail)")
        }
    }
}
