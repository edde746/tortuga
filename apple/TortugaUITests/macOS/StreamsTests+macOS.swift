import XCTest

/// Streams and the player with the pointer alone (the tests iOS has too are in Pointer/StreamsTests.swift):
/// - auto-play on the movie (4K hidden), each pick named by the player's info (Name, Picked by): First match plays the
///   1080p stream from Play with no sheet; the remux picked from Choose a stream's list (Show) is what Last stream
///   replays from Resume, filters or not, and what Off's Resume replays, with no Choose a stream on the details;
/// - auto-play on the series' episode rows: Off opens the episode's streams, and Next episode from its 720p stream (no
///   binge group) asks with the next episode's streams over the player; First match plays episode 2's 720p from its row
///   with no sheet, and Next episode plays episode 3's 720p; Last stream (episode 2's 720p picked by hand under Off,
///   then episode 1's 1080p from Choose a stream in its ⋯ menu) plays episode 3's 1080p from its row (the binge group
///   the title last played from) and episode 2's 720p (its own last stream).
/// - Continue Watching on Home resumes the movie as the details' Resume does, over its details (Back from the player
///   returns there), with no sheet: the movie left a few seconds into the 1080p stream picked by hand, its card plays
///   that stream with auto-play off (the stream history's), and auto-play's pick, the 2160p remux, with First match (no
///   filters set), each from where it was left; a movie in progress (`--dev-progress`) that never played from a stream
///   here opens its Streams sheet over its details with auto-play off.
extension StreamsTests {
    func testAutoPlayOnTheMovieFromPlayAndResume() throws {
        // Four trips to Settings and four plays.
        executionTimeAllowance = 600
        try launchDev(addon: true, open: ["board", StreamFilterTexts.movie])
        addMovieToLibrary()
        openStreamsSettings()
        setQuality("4k", shown: false)
        shotAutoPlayOptions("auto-play's options")
        pickAutoPlay(StreamFilterTexts.firstMatch)
        shot("auto-play on the first match")

        // First match: Play plays the 1080p stream, the first the filters leave, with no sheet.
        openMovieFromLibrary()
        require("detail.chooseStream", "with auto-play on, the details offer Choose a stream")
        shot("movie details with auto-play on")
        ready("detail.play").click()
        requireAutoPlayed("First match: the movie's Play")
        var video = requirePlayer(playing: "1080p", pickedBy: StreamFilterTexts.pickedFirstMatch, "First match: the movie's Play", shotName: "movie: first match from Play")
        leavePlayer(video)

        // Choose a stream lists the streams; the remux, shown by Show, is picked by hand.
        ready("detail.chooseStream").click()
        requireStreams(["1080p"], filter: StreamFilterTexts.hidden(1), "Choose a stream lists the streams, 4K hidden")
        ready("streams.filter.toggle").click()
        requireStreams(["2160p", "1080p"], filter: StreamFilterTexts.showing(1), noted: [0], "Show lists the remux again")
        ready("streams.row.0").click()
        video = requirePlayer(playing: "2160p", pickedBy: nil, "the remux picked by hand")
        leavePlayer(video)

        // Last stream: Resume replays the remux, though the filters hide it.
        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.reuseLastLink)
        openMovieFromLibrary()
        ready("detail.play").click()
        requireAutoPlayed("Last stream: the movie's Resume")
        video = requirePlayer(playing: "2160p", pickedBy: StreamFilterTexts.pickedLastLink, "Last stream: the movie's Resume", shotName: "movie: last stream from Resume")
        leavePlayer(video)

        // Off: no Choose a stream; Resume replays the remux from the stream history, not as auto-play's pick.
        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.off)
        openMovieFromLibrary()
        requireGone("detail.chooseStream", "with auto-play off, the details offer no Choose a stream")
        ready("detail.play").click()
        requireAutoPlayed("Off: the movie's Resume")
        video = requirePlayer(playing: "2160p", pickedBy: nil, "Off: the movie's Resume", shotName: "movie: Off's Resume")
        leavePlayer(video)
    }

    func testEpisodeRowOpensTheStreamsWithAutoPlayOffAndPlaysTheFirstMatch() throws {
        executionTimeAllowance = 600
        try launchDev(addon: true, open: ["board", StreamFilterTexts.series])
        // In the Library: the way back to the series after Settings.
        addMovieToLibrary()

        // Off (the default): episode 1's row opens its streams; the 720p stream picked there plays.
        ready(scrollTo("detail.episode.0", "the series lists its first episode")).click()
        requireStreamsSheet("Off: episode 1's row")
        requireText("streams.row.0", contains: "720p", "episode 1's first stream")
        shot("Off: episode 1's streams")
        ready("streams.row.0").click()
        var video = requirePlayer(playing: "720p", episode: "S1E1", pickedBy: nil, "Off: episode 1's 720p picked by hand")

        // Next episode from a stream without a binge group asks: episode 2's streams over the player.
        playNextEpisode(video)
        require("streams.row.0", timeout: 60, "Off: Next episode from 720p brings episode 2's streams")
        if !element("player.video").exists { fail("Off: episode 2's streams came up without the player under them") }
        shot("Off: next episode's streams over the player")
        ready("streams.row.0").click()
        requireGone("streams.row.0", "picking a stream closes the sheet over the player")
        video = requirePlayer(playing: "720p", episode: "S1E2", pickedBy: nil, "Off: episode 2's 720p picked over the player")
        leavePlayer(video)

        // First match: episode 2's row plays the 720p stream, the first that passes, with no sheet.
        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.firstMatch)
        openMovieFromLibrary()
        let row = scrollTo("detail.episode.1", "the series lists its second episode")
        ready(row).click()
        // The row says so while auto-play finds the stream: shot when it's caught.
        let finding = { self.allText(of: row).contains(StreamFilterTexts.findingStream) }
        if waitUntil(3, { finding() || self.element("player.video").exists }), finding() {
            shot("First match: episode 2's row finding a stream")
        }
        requireAutoPlayed("First match: episode 2's row")
        video = requirePlayer(
            playing: "720p", episode: "S1E2", pickedBy: StreamFilterTexts.pickedFirstMatch, "First match: episode 2's row",
            shotName: "series: first match from episode 2's row"
        )

        // Next episode: episode 3's 720p, auto-play's first match, with no sheet.
        playNextEpisode(video)
        video = requirePlayer(playing: "720p", episode: "S1E3", pickedBy: StreamFilterTexts.pickedFirstMatch, "First match: Next episode from 720p")
        if element("streams.row.0").exists { fail("First match: Next episode from 720p opened episode 3's streams") }
        leavePlayer(video)
    }

    func testEpisodeRowPlaysTheStreamTheTitleLastPlayedFrom() throws {
        executionTimeAllowance = 600
        try launchDev(addon: true, open: ["board", StreamFilterTexts.series])
        // In the Library: the way back to the series after Settings.
        addMovieToLibrary()

        // Off: episode 2's 720p picked by hand, its own last stream.
        ready(scrollTo("detail.episode.1", "the series lists its second episode")).click()
        requireStreamsSheet("Off: episode 2's row")
        requireText("streams.row.0", contains: "720p", "episode 2's first stream")
        ready("streams.row.0").click()
        leavePlayer(requirePlayer(playing: "720p", episode: "S1E2", pickedBy: nil, "Off: episode 2's 720p picked by hand"))

        // Last stream: Choose a stream in episode 1's ⋯ menu lists its streams, whatever auto-play would pick; its 1080p
        // stream (in a binge group) picked there is the stream the title last played from.
        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.reuseLastLink)
        openMovieFromLibrary()
        let more = scrollTo("detail.episode.0.menu", "the first episode has its ⋯ button")
        ready(more).hover()
        ready(more).click()
        let choose = element("detail.menu.item.chooseStream")
        if !choose.exists(within: 3) { ready(more).click() }
        require("detail.menu.item.chooseStream", timeout: 5, "with auto-play on, episode 1's ⋯ menu offers Choose a stream")
        shot("episode menu with Choose a stream")
        pickMenuEntry(choose)
        requireStreamsSheet("Last stream: Choose a stream in episode 1's menu")
        requireText("streams.row.1", contains: "1080p", "episode 1's second stream")
        ready("streams.row.1").click()
        leavePlayer(requirePlayer(playing: "1080p", episode: "S1E1", pickedBy: nil, "Last stream: episode 1's 1080p picked by hand"))

        // Episode 3, never played: the 1080p stream, in the binge group the title last played from.
        ready(scrollTo("detail.episode.2", "the series lists its third episode")).click()
        requireAutoPlayed("Last stream: episode 3's row")
        leavePlayer(requirePlayer(
            playing: "1080p", episode: "S1E3", pickedBy: StreamFilterTexts.pickedLastLink, "Last stream: episode 3's row",
            shotName: "series: last stream from episode 3's row"
        ))

        // Episode 2: its own last stream, the 720p one.
        ready(scrollTo("detail.episode.1", "the series lists its second episode")).click()
        requireAutoPlayed("Last stream: episode 2's row")
        leavePlayer(requirePlayer(
            playing: "720p", episode: "S1E2", pickedBy: StreamFilterTexts.pickedLastLink, "Last stream: episode 2's row",
            shotName: "series: last stream from episode 2's row"
        ))
    }

    func testContinueWatchingResumesAsTheDetailsResumeDoes() throws {
        // Three plays and a trip to Settings.
        executionTimeAllowance = 600
        try launchDev(addon: true, open: ["board", StreamFilterTexts.movie])
        // Into Continue Watching: the movie, never played, lists its streams from Play (auto-play off, the default); the
        // 1080p stream picked there plays a few seconds and is closed paused, the stream the movie last played from.
        ready(require("detail.play", timeout: 60, "the movie's details show Play")).click()
        requireStreamsSheet("Off: the movie's Play, never played")
        requireText("streams.row.1", contains: "1080p", "the movie's second stream")
        ready("streams.row.1").click()
        var video = require("player.video", timeout: 20, "a click on the 1080p stream opens the player")
        // Where it is left: here or a little past it.
        let left = requirePlayback(4, timeout: 90)
        showControls(video)
        let playPause = element("player.playPause")
        if playPause.label != "Play" { ready(playPause).click() }
        if !waitUntil(5, { playPause.exists && playPause.label == "Play" }) { fail("play/pause doesn't pause the 1080p stream") }
        leavePlayer(video)
        goBack()

        // Off: the card plays the stream the movie last played from at once, from where it was left.
        require("board.cw.0", timeout: 30, "Home's Continue Watching lists the movie")
        shot("home: the movie in Continue Watching")
        ready("board.cw.0").click()
        requireAutoPlayed("Off: the movie's Continue Watching card")
        requireResumed(from: left, "Off: the movie's Continue Watching card")
        video = requirePlayer(
            playing: "1080p", pickedBy: nil, "Off: the movie's Continue Watching card",
            shotName: "Off: the last stream from Continue Watching"
        )
        leavePlayer(video)
        goBack()

        // First match (no filters set): the card plays auto-play's pick, the 2160p remux, at once, from where it was left.
        openStreamsSettings()
        pickAutoPlay(StreamFilterTexts.firstMatch)
        ready(require("nav.board", "the sidebar lists Home")).click()
        ready(require("board.cw.0", timeout: 30, "Home's Continue Watching lists the movie")).click()
        requireAutoPlayed("First match: the movie's Continue Watching card")
        requireResumed(from: left, "First match: the movie's Continue Watching card")
        video = requirePlayer(
            playing: "2160p", pickedBy: StreamFilterTexts.pickedFirstMatch, "First match: the movie's Continue Watching card",
            shotName: "First match: auto-play's pick from Continue Watching"
        )
        leavePlayer(video)
        shot("First match: back from the player on the details")
    }

    func testContinueWatchingListsTheStreamsWithNoStreamToResume() throws {
        // The movie part-way in Continue Watching, never played from a stream here: no stream history.
        let addon = try addonURL
        launch(
            ["--dev", "--dev-addon", addon, "--dev-progress", "movie:tt0111161:tt0111161:600:6000"]
                + Self.harnessSettings.flatMap { ["--dev-setting", $0] }
        )
        ready(require("board.cw.0", timeout: 90, "Home shows the movie in progress in Continue Watching")).click()
        requireStreamsSheet("Off: the Continue Watching card of a movie with no stream to resume")
        requireText("streams.row.1", contains: "1080p", "the movie's streams")
        shot("Off: the streams over the details from Continue Watching")
        app.typeKey(.escape, modifierFlags: [])
        requireGone("streams.row.0", "Escape closes the Streams sheet")
        require("detail.play", timeout: 10, "the movie's details under the Streams sheet")
    }

    /// The auto-play row's menu open (the row scrolled into view: `showOnScreen`), shot as `name`, then closed by Escape.
    private func shotAutoPlayOptions(_ name: String) {
        ready(showOnScreen("settings.playback.streams.autoPlay", "the Streams group has Play automatically")).click()
        let entry = menuEntry(StreamFilterTexts.reuseLastLink.label, "the auto-play menu")
        shot(name)
        app.typeKey(.escape, modifierFlags: [])
        if !waitUntil(5, { !entry.exists || !entry.isHittable }) { fail("Escape doesn't close the auto-play menu") }
    }

    /// After a click auto-play answers (Play, Resume, an episode's row): the player opens, not the Streams sheet.
    /// Fails with `what` and what opened instead.
    private func requireAutoPlayed(_ what: String) {
        let video = element("player.video")
        let streams = element("streams.row.0")
        if !waitUntil(60, { video.exists || streams.exists }) { fail("\(what): neither the player nor the Streams sheet opens in 60 s") }
        if !video.exists { fail("\(what): the Streams sheet opened instead of the player, its first row \"\(allText(of: streams))\"") }
    }

    /// After a click that lists the streams (an episode's row with auto-play off, Choose a stream): the Streams sheet
    /// opens, not the player. Fails with `what`.
    private func requireStreamsSheet(_ what: String) {
        let video = element("player.video")
        let streams = element("streams.row.0")
        if !waitUntil(60, { video.exists || streams.exists }) { fail("\(what): neither the Streams sheet nor the player opens in 60 s") }
        if video.exists { fail("\(what): the player opened instead of the Streams sheet") }
    }

    /// After the player opened on a video left `left` s in: its first position is there or past it, not the start.
    /// Fails with `what`.
    private func requireResumed(from left: Double, _ what: String) {
        let start = requirePlayback(0.5, timeout: 90)
        if start < left - 1 { fail("\(what): the player started at \(start) s, not from where the movie was left (\(left) s)") }
    }

    /// The player playing the test addon's `stream` ("720p": "Tortuga Test 720p"), on the series' episode `episode`
    /// ("S1E2", the title beside the controls) where given: it plays, is paused (a 20 s episode's up next comes at
    /// 10 s), and its info, opened from the options menu, names the stream and says auto-play picked it as `pickedBy`,
    /// or (nil) has no Picked by row (a stream picked by hand, Off's resume); shot as `shotName` where given. Fails with
    /// `what`; the video.
    @discardableResult
    private func requirePlayer(
        playing stream: String, episode: String? = nil, pickedBy: String?, _ what: String, shotName: String? = nil
    ) -> XCUIElement {
        let video = require("player.video", timeout: 60, "\(what): the player opens")
        if let episode {
            // Each read of the position moves the pointer, which brings the controls and the title up.
            let title = element("player.title")
            let shown = waitUntil(60) {
                _ = playerPosition()
                return title.exists && fullText(of: title).contains(episode)
            }
            if !shown { fail("\(what): the player's title reads \"\(title.exists ? fullText(of: title) : "nothing")\", not \(episode)") }
        }
        requirePlaying()
        showControls(video)
        let playPause = element("player.playPause")
        if playPause.label != "Play" { ready(playPause).click() }
        if !waitUntil(5, { playPause.exists && playPause.label == "Play" }) { fail("\(what): play/pause doesn't pause the video") }
        showControls(video)
        togglePlaybackInfo()
        require("player.info.panel", timeout: 5, "\(what): Playback info in the options menu opens the info")
        requireInfoRow("Name", contains: "Tortuga Test \(stream)", what)
        if let pickedBy {
            requireInfoRow("Picked by", contains: pickedBy, what)
        } else if infoRow("Picked by").exists {
            fail("\(what): the info's Picked by row reads \"\(fullText(of: infoRow("Picked by")))\", as if auto-play picked it")
        }
        if let shotName { shot(shotName) }
        return video
    }

    /// The player's back button, back on the details.
    private func leavePlayer(_ video: XCUIElement) {
        showControls(video)
        ready(require("player.back", timeout: 5, "the controls offer back")).click()
        requireGone("player.video", "the back button closes the player")
        require("detail.play", "the back button returns to the details")
    }
}
