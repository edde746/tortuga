import XCTest

/// Streams and the player by touch alone (the tests the Mac has too are in Pointer/StreamsTests.swift):
/// - the Streams sheet over an episode's details: its bar carries the series' title and under it the episode line, and
///   the first stream starts right under the bar, past only its addon's name; the episode line, secondary text, stands
///   out more with Increase Contrast on, as the system's secondary label does (`requireFollowsIncreaseContrast`);
/// - auto-play (4K hidden): First match plays the 1080p stream from Play with no sheet, the player's info naming the
///   stream and auto-play's pick; the remux picked from Choose a stream's list (Show) is what Last stream played
///   replays; an include pattern matching nothing (First match again) opens the Streams sheet with the notice instead;
/// - auto-play off on the test addon's slow-streams movie (its streams answer 25 s late): the 1080p stream picked from
///   Play's sheet plays a few seconds and is closed; Resume asks that stream's addon again, Play reading "Finding a
///   stream…" with its spinner while the addon answers (shot "resume finding the stream"), then the player resumes the
///   movie from where it was left, with no sheet.
extension StreamsTests {
    func testSheetBarCarriesTheEpisodeLine() throws {
        try launchDev(addon: true, open: ["detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"])
        let row = require("streams.row.0", timeout: 60, "the episode's Streams sheet lists its streams")
        let bar = app.navigationBars.containing(.any, identifier: "streams.title").firstMatch
        if !bar.exists { fail("the Streams sheet's bar doesn't carry the title (streams.title)") }
        let subtitle = bar.descendants(matching: .any).matching(identifier: "streams.subtitle").firstMatch
        if !subtitle.exists { fail("the Streams sheet's bar doesn't carry the episode line (streams.subtitle)") }
        if !text(of: subtitle).withoutDirectionMarks.hasPrefix("S1E1") { fail("the bar's episode line reads \"\(text(of: subtitle))\", not S1E1's") }
        // Between the bar and the first stream only the addon's name, no episode row or section spacing.
        let gap = row.frame.minY - bar.frame.maxY
        if gap > 90 { fail("the first stream starts \(Int(gap)) pt under the bar") }
        shot("episode streams sheet")
    }

    func testTheEpisodeLineFollowsIncreaseContrast() throws {
        try launchDev(addon: true, open: ["detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"])
        require("streams.row.0", timeout: 60, "the episode's Streams sheet lists its streams")
        requireFollowsIncreaseContrast(require("streams.subtitle", "the Streams sheet's bar carries the episode line"), "the episode line")
    }

    func testAutoPlayPlaysTheFirstMatchThenTheLastStreamAndListsWhenNothingMatches() throws {
        // Three trips to Settings and three plays: longer than the default 5 minutes on a slow simulator.
        executionTimeAllowance = 600
        try launchDev(addon: true, open: ["board", StreamFilterTexts.movie])
        addMovieToLibrary()
        openStreamsSettings()
        setQuality("4k", shown: false)
        pickAutoPlay(StreamFilterTexts.firstMatch)
        shot("auto-play on the first match")

        // First match: the 1080p stream, the first left by the filters, plays at once.
        openMovieFromLibrary()
        require("detail.chooseStream", "with auto-play on, the details offer Choose a stream")
        shot("details with auto-play on")
        ready("detail.play").tap()
        var video = require("player.video", timeout: 60, "Play starts auto-play's pick in the player")
        if element("streams.row.0").exists { fail("with auto-play on, Play opened the Streams sheet") }
        requirePlaying()
        openPlayerInfo(video)
        requireInfoRow("Name", contains: "Tortuga Test 1080p", "auto-play's first match")
        requireInfoRow("Picked by", contains: StreamFilterTexts.pickedFirstMatch, "auto-play's first match")
        shot("info of the first match")
        leavePlayer(video)

        // Choose a stream lists them; the remux, shown by Show, is picked by hand.
        ready(require("detail.chooseStream", "the details offer Choose a stream")).tap()
        requireStreams(["1080p"], filter: StreamFilterTexts.hidden(1), "Choose a stream lists the streams, 4K hidden")
        ready("streams.filter.toggle").tap()
        requireStreams(["2160p", "1080p"], filter: StreamFilterTexts.showing(1), noted: [0], "Show lists the remux again")
        ready("streams.row.0").tap()
        video = require("player.video", timeout: 20, "a tap on the remux opens the player")
        requirePlaying()
        openPlayerInfo(video)
        requireInfoRow("Name", contains: "Tortuga Test 2160p", "the remux picked from the list")
        if infoRow("Picked by").exists { fail("a stream picked from the list says auto-play picked it") }
        leavePlayer(video)

        // Last stream played: the remux, though the filters hide it.
        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.reuseLastLink)
        openMovieFromLibrary()
        ready("detail.play").tap()
        video = require("player.video", timeout: 60, "Play starts auto-play's pick in the player")
        requirePlaying()
        openPlayerInfo(video)
        requireInfoRow("Name", contains: "Tortuga Test 2160p", "auto-play's last stream played")
        requireInfoRow("Picked by", contains: StreamFilterTexts.pickedLastLink, "auto-play's last stream played")
        shot("info of the last stream played")
        leavePlayer(video)

        // Nothing passes: the Streams sheet opens with the notice.
        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.firstMatch)
        setPattern("settings.playback.streams.include", to: "no-such-stream")
        shot("an include pattern matching nothing")
        openMovieFromLibrary()
        ready("detail.play").tap()
        require("streams.notice", timeout: 60, "with nothing passing the filters, Play opens the Streams sheet")
        requireText("streams.notice", contains: StreamFilterTexts.noMatch, "the Streams sheet's notice")
        requireStreams([], filter: StreamFilterTexts.hidden(2), "the filters hide both streams")
        if element("player.video").exists { fail("with nothing passing the filters, the player opened") }
        shot("streams with auto-play's notice")
    }

    func testResumeFindsTheLastStreamAgainWhileItsAddonAnswers() throws {
        // Two answers 25 s late and two plays: longer than the default 5 minutes on a slow simulator.
        executionTimeAllowance = 600
        try launchDev(addon: true, open: ["detail:movie:tortuga-slow-streams"])
        ready(require("detail.play", timeout: 60, "the slow-streams movie's details show Play")).tap()
        requireText("streams.row.1", contains: "1080p", timeout: 90, "Play opens the Streams sheet once the addon answers")
        ready("streams.row.1").tap()
        let video = require("player.video", timeout: 20, "a tap on the 1080p stream opens the player")
        // Where it is left: here or a little past it.
        let left = requirePlayback(4, timeout: 90)
        closePlayer(video, "the controls' back button closes the player")
        let play = require("detail.play", "the back button returns to the details")

        // Auto-play off (the default): Resume finds the stream again in its addon's new answer, busy until it comes.
        ready(play).tap()
        if !waitUntil(10, { play.exists && play.label == StreamFilterTexts.findingStream }) {
            fail("Resume doesn't read \"\(StreamFilterTexts.findingStream)\" while the addon answers: it reads \"\(play.exists ? play.label : "nothing")\"")
        }
        shot("resume finding the stream")
        require("player.video", timeout: 60, "Resume plays the stream once its addon answers")
        if element("streams.row.0").exists { fail("Resume opened the Streams sheet over the player") }
        let start = requirePlayback(0.5, timeout: 90)
        if start < left - 1 { fail("Resume started at \(start) s, not from where the movie was left (\(left) s)") }
        shot("resumed")
    }

    /// Brings the controls up and opens the playback info's sheet from the options menu.
    private func openPlayerInfo(_ video: XCUIElement) {
        showPlayerControls(video, "a tap on the video shows the controls")
        togglePlaybackInfo()
        require("player.info.panel", timeout: 5, "Playback info in the options menu opens the playback info")
    }

    /// Closes the info's sheet, then the player by its back button, back on the movie's details.
    private func leavePlayer(_ video: XCUIElement) {
        if element("player.info.panel").exists {
            closeSheet("player.info.close", showing: "player.info.panel", "Close (an iPhone: a pull down) closes the playback info")
        }
        closePlayer(video, "the controls' back button closes the player")
        require("detail.play", "the back button returns to the details")
    }
}
