import XCTest

/// The player by touch (iOS) and with the pointer and keyboard (the Mac), on the test addon's titles
/// (tools/media/test_addon.py):
/// - the controls (the title with its episode line, the buttons and the times) over a dark episode frame, shot for
///   their colours;
/// - the expired link: the player's error says the link expired, not that playback failed (on the Mac with back and the
///   title alone);
/// - the options menu's sleep timer run out into "Still watching?" in up next's place, counting down to a pause:
///   Continue puts it away and starts the timer again (iOS); Pause puts it away and turns the timer off (the Mac, the
///   card clear of the controls);
/// - subtitles: the scripts clip's line drawn in nine scripts; the remux's French SRT line and Spanish ASS line in the
///   default and in a changed subtitle style, the size changed while the player is paused; on the subtitles movie the
///   subtitle menu's More subtitles adds the Japanese subtitle from its language's submenu and selects it: the menu then
///   lists it, its line on the picture; its Second subtitle submenu shows the remux's Spanish ASS line at the top of the
///   picture with the French SRT line at the bottom, the menu's Spanish then badged;
/// - the test addon's series' first episode, taken to 90 % (past the watched threshold, 70 %) by the timeline and
///   closed, counts as watched: back on the series' details the episode shows watched without a progress bar and Play
///   offers S1E2, and Home's Continue Watching leads with the series, "S1E2 · Up next"; the episode played again from
///   its row and closed short of the threshold leaves all of that as it was;
/// - a host's 2-min still error video answering for an episode in progress (`ErrorVideoTestSupport`) is
///   never taken for the episode: nothing recorded (watched, progress, Continue Watching, up next, a Trakt scrobble)
///   and the player's error-video panel shown;
/// - intros set to skip automatically in Settings → Playback: over the chapters series' first episode's intro the skip
///   button counts down and skips by itself (`SkipTestSupport`).
/// Each platform's own tests are in iOS/PlayerTests+iOS.swift and macOS/PlayerTests+macOS.swift.
final class PlayerTests: TortugaUITestCase {
    /// The title with its episode line, the buttons and the times over near black (tortuga-frames-series: one episode on
    /// the dark still frame), shot for their colours.
    func testControlsOverADarkEpisodeFrame() throws {
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-frames-series", "streams:series:tortuga-frames-series:tortuga-frames-series:1:1"])
        activate(require("streams.row.0", timeout: 60, "the episode's Streams sheet lists the dark frame"))
        require("player.video", timeout: 20, "\(Self.tapping) on the dark frame opens the player")
        #if os(macOS)
        require("player.scrub", timeout: 30, "the controls stay up over the dark frame")
        #else
        require("player.transport", timeout: 30, "the controls stay up over the dark frame")
        #endif
        // The glass settles over the frame.
        screenSettles()
        #if os(macOS)
        shot("controls over the dark episode frame")
        #else
        screenShot("controls over the dark episode frame")
        #endif
    }

    /// The test addon's expired link (tortuga-expired: its resolved link serves the app's resolve and answers the
    /// player 403, so mpv fails to open it): the player's error comes up full screen (`requireErrorFillsScreen`) and says
    /// the link expired (the engine hands the store mpv's HTTP status with the error), not that playback failed. On the
    /// Mac it comes with back and the title alone: no control bar (timeline, transport, menus) and no picture in picture
    /// behind it.
    func testExpiredLinkBringsUpThePlayersError() throws {
        try launchDev(addon: true, open: ["streams:movie:tortuga-expired:tortuga-expired"])
        activate(require("streams.row.0", timeout: 60, "the Streams screen lists the expired link"))
        #if os(macOS)
        let error = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier IN {'player.error.retry', 'player.error.next', 'player.error.close'}")).firstMatch
        if !error.exists(within: 60) { fail("the expired link doesn't bring up the player's error") }
        require("player.back", timeout: 5, "back stays with the error")
        shot("the player's error for the expired link")
        let texts = app.staticTexts
        #else
        let error = require("player.error", timeout: 60, "the expired link brings up the player's error")
        screenShot("the player's error for the expired link")
        let texts = error.staticTexts
        #endif
        if !texts["The stream link has expired."].exists(within: 5) {
            fail("the expired link's error doesn't say it expired: \(texts.allElementsBoundByIndex.map { text(of: $0) })")
        }
        requireErrorFillsScreen("the expired link's error")
        #if os(macOS)
        let behind = ["player.scrub", "player.position", "player.remaining", "player.playPause", "player.pip", "player.audio",
                      "player.subtitles", "player.speed", "player.more", "player.mute"].filter { element($0).exists }
        if !behind.isEmpty { fail("\(behind.joined(separator: ", ")) show with the error") }
        #endif
    }

    /// VoiceOver or Switch Control running (debug builds take the launch argument `-assistiveTechnology YES` for them:
    /// XCUITest can't turn either on): the controls stay up while the H.264 file plays on, past the 5 s that hide them
    /// otherwise, the Mac's pointer out of the window (which hides them at once otherwise). Those move through the
    /// controls one at a time, at their own pace. On iOS the video's value follows the position meanwhile, as the
    /// playhead probe reads it.
    func testControlsStayUpWhileAnAssistiveTechnologyRuns() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"], arguments: ["-assistiveTechnology", "YES"])
        activate(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream"))
        require("player.video", timeout: 20, "\(Self.tapping) on the H.264 stream opens the player")
        #if os(macOS)
        pointerOutOfTheWindow()
        #endif
        requirePlayerLoaded("the H.264 stream")
        let playPause = element("player.playPause")
        // Read without a touch or a pointer move (the Mac's position read moves the pointer): play/pause, which goes
        // with the controls (the Mac's back stays in the title row), offers Pause while the video plays.
        let up = holds(for: 8) { playPause.exists }
        if !up { fail("with an assistive technology running the controls hide while the video plays") }
        if playPause.label == "Play" { fail("the H.264 file stopped playing while the controls were watched: play/pause reads Play") }
        #if !os(macOS)
        let video = element("player.video")
        if !waitUntil(3, { video.value as? String == playhead() && Self.seconds(playhead()) != nil }) {
            fail("with an assistive technology running the video's value reads '\(video.value as? String ?? "nothing")', not the position '\(playhead())'")
        }
        #endif
        shot("controls up under an assistive technology")
    }

    /// The timeline takes a touch (the Mac: a click) anywhere in a band a button tall around its drawn bar, 44 pt on
    /// iOS and 28 on the Mac, the bar drawn as thin as before: on the paused H.264 file (20 s) a tap 17 pt above the
    /// bar's middle (the Mac: a click 11 pt above), four fifths along it, seeks to 16 s. On iOS the timeline, the
    /// element VoiceOver and Switch Control land on, is the band: 44 pt tall (a touch lands on the bar a little past its
    /// frame too, so the tap alone can't tell the band's height there).
    func testTimelineTakesATouchAcrossAButtonTallBand() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        activate(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream"))
        let video = require("player.video", timeout: 20, "\(Self.tapping) on the H.264 stream opens the player")
        requirePlayback(1, timeout: 90)
        pause(video, "play/pause doesn't pause the H.264 file")
        showControls(video)
        #if os(macOS)
        let above: CGFloat = 11
        #else
        let above: CGFloat = 17
        #endif
        let scrub = ready("player.scrub")
        let band = scrub.frame
        let target = scrub.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)).withOffset(CGVector(dx: 0, dy: -above))
        #if os(macOS)
        target.click()
        #else
        target.tap()
        #endif
        if !waitUntil(8, { playerPosition().map { (14.5...17.5).contains($0) } ?? false }) {
            fail("\(Self.tapping) \(Int(above)) pt above the timeline's middle (its band \(band.height) pt tall) left the video at \(playerPosition() ?? 0) s, not 16 s")
        }
        #if os(iOS)
        if band.height < 44 { fail("the timeline's band is \(band.height) pt tall, not a button's 44 pt") }
        #endif
    }

    /// In Arabic, laid out right to left, the playback controls run left to right, as the keys and the video's taps that
    /// seek do (playback controls aren't mirrored): on the test addon's series' first episode the position, the timeline
    /// and the time left, then Previous episode, Back, play/pause, Forward and Next episode, each from left to right
    /// (shot as "controls in arabic"); on iOS a double tap on the screen's right side seeks forward.
    func testPlaybackControlsRunLeftToRightInArabic() throws {
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"], languages: ["ar"])
        activate(require("streams.row.0", timeout: 60, "the first episode's Streams sheet lists its streams"))
        let video = require("player.video", timeout: 20, "\(Self.tapping) on the stream opens the player")
        let before = requirePlayback(1, timeout: 90)
        #if os(iOS)
        ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)).doubleTap()
        if !waitUntil(5, { (playerPosition() ?? 0) >= before + 8 }) {
            fail("in Arabic a double tap on the screen's right side from \(before) s reached \(playerPosition() ?? 0) s; expected ≥ \(before + 8) s")
        }
        #endif
        let rows = [["player.position", "player.scrub", "player.remaining"], Self.transport]
        let ids = Array(rows.joined())
        var frames: [String: CGRect] = [:]
        _ = waitUntil(30) {
            showControls(video)
            frames = self.frames(of: ids)
            return frames.count == ids.count
        }
        shot("controls in arabic")
        let missing = ids.filter { frames[$0] == nil }
        if !missing.isEmpty { return fail("in Arabic the controls showed without \(missing.joined(separator: ", "))") }
        for row in rows {
            let order = row.sorted { frames[$0, default: .zero].midX < frames[$1, default: .zero].midX }
            if order != row { fail("in Arabic the controls run \(order.joined(separator: ", ")) from left to right, not \(row.joined(separator: ", "))") }
        }
    }

    /// The options menu's sleep timer at 5 minutes on the paused 4:3 clip, which the dev session's fast timers
    /// (`player.fastTimers`) make 30 s: the menu counts it down, and once it runs out "Still watching?" comes up as a
    /// card in up next's place, counting down to a pause. iOS: the card lies above the timeline, and Continue puts it
    /// away and starts the 5 minutes again. The Mac: the card lies clear of the controls once they are up, and Pause puts
    /// it away and turns the timer off.
    func testSleepTimerAsksStillWatching() throws {
        try launchDev(addon: true, open: ["streams:movie:tortuga-4x3:tortuga-4x3"], settings: ["player.fastTimers=true"])
        activate(require("streams.row.0", timeout: 60, "the Streams screen lists the 4:3 clip"))
        let video = require("player.video", timeout: 20, "\(Self.tapping) on the 4:3 clip opens the player")
        requirePlayback(1, timeout: 90)
        pause(video, "play/pause doesn't pause the 4:3 clip")
        pickOption("Sleep timer", "5 minutes", reads: "left", video: video)
        require("player.stillWatching", timeout: 90, "the sleep timer's 5 minutes run out into Still watching?")
        let countdown = element("player.stillWatching.countdown")
        if !waitUntil(3, { countdown.exists && text(of: countdown).hasPrefix("Pausing in") }) { fail("Still watching? tells '\(text(of: countdown))', not its countdown") }
        #if os(macOS)
        require("player.stillWatching.keepWatching", "Still watching? offers Continue")
        showControls(video)
        shot("still watching")
        requireClearOfTheControls("player.stillWatching", "Still watching? with the controls up")
        activate(element("player.stillWatching.pause"))
        requireGone("player.stillWatching", timeout: 5, "Pause puts Still watching? away")
        requireOption("Sleep timer", reads: "Off", video: video, "after Pause")
        #else
        require("player.stillWatching.pause", "Still watching? offers Pause")
        shot("still watching")
        let card = element("player.stillWatching")
        let timeline = require("player.scrub", timeout: 5, "Still watching? comes up with the timeline").frame
        if card.frame.maxY > timeline.minY { fail("Still watching? (\(card.frame)) reaches down onto the timeline (\(timeline))") }
        activate(element("player.stillWatching.keepWatching"))
        requireGone("player.stillWatching", timeout: 5, "Continue puts Still watching? away")
        requireOption("Sleep timer", reads: "left", video: video, "after Continue")
        #endif
    }

    /// The test addon's series' first episode (20 s) from its stream without a binge group, paused a second in, taken
    /// by a tap (the Mac: a click) on the timeline to 18 s (90 %), past the profile's watched threshold (70 %: 14 s), and
    /// closed: the threshold counts by position (`PlaybackRules.countsAsWatched`), so the episode counts as watched with
    /// a second of it played, and the series moves on to S1E2 at its start (`requireSeriesOnS1E2`, shots
    /// "detail-after-threshold" and "home-after-threshold"). Then a rewatch: S1E1 played again from its row on the
    /// series' details (opened from the Library's Continue Watching; its Streams sheet), paused a few seconds in, short
    /// of the threshold, and closed leaves the series on S1E2 and S1E1 watched without a progress bar (shots
    /// "detail-after-rewatch", "home-after-rewatch"). One launch: the dev profile lives in memory.
    func testEpisodeTakenPastTheWatchedThresholdIsWatchedAndHomeOffersTheNext() throws {
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"])
        activate(require("streams.row.0", timeout: 60, "the first episode's Streams sheet lists the stream without a binge group"))
        let video = require("player.video", timeout: 20, "\(Self.tapping) on the stream opens the player")
        requirePlayback(1, timeout: 90)
        // Paused before the seek: the 20 s episode doesn't run on to its end, where the next episode's streams open.
        pause(video, "play/pause doesn't pause the first episode")
        let ninetyPercent = ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5))
        #if os(macOS)
        ninetyPercent.click()
        #else
        ninetyPercent.tap()
        #endif
        if !waitUntil(10, { (playerPosition() ?? 0) >= 18 }) {
            fail("\(Self.tapping) on the timeline at 18 s left the episode at \(playerPosition() ?? 0) s, short of 90 %")
        }
        #if os(macOS)
        showControls(video)
        ready("player.back").click()
        requireGone("player.video", "the back button closes the player")
        // The details lie over Home's Board: Back returns there.
        requireSeriesOnS1E2("after-threshold", home: goBack)
        #else
        closePlayer(video, "the controls' back button closes the player")
        requireSeriesOnS1E2("after-threshold") { self.activate(self.phoneTab("nav.board")) }
        #endif

        // The series' details again: the Library's Continue Watching view, the series its one item (the Library's own
        // view lists only what was added to it).
        #if os(macOS)
        activate(require("nav.library", "the sidebar lists Library"))
        #else
        activate(phoneTab("nav.library"))
        #endif
        activate(require("library.view", timeout: 30, "the Library shows its view menu"))
        pickMenuEntry(require("library.view.option.1", timeout: 5, "the view menu lists Continue Watching"))
        activate(require("library.item.0", timeout: 30, "Continue Watching lists the series"))
        // Continue Watching opens the details with the Streams sheet of the episode the series is on (S1E2, not started:
        // with auto-play off it has no stream to resume): closed by Escape on the Mac, by Close on the iPad's form sheet,
        // and on iPhone by a tap on the dimmed details above the half-height sheet.
        require("streams.row.0", timeout: 60, "Continue Watching opens the series' details with S1E2's Streams sheet")
        #if os(macOS)
        app.typeKey(.escape, modifierFlags: [])
        requireGone("streams.row.0", "Escape closes S1E2's Streams sheet")
        #else
        if isPad {
            ready(require("streams.close", "the iPad's Streams form sheet has Close")).tap()
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15)).tap()
        }
        requireGone("streams.row.0", "closing S1E2's Streams sheet")
        #endif
        require("detail.play", timeout: 10, "the series' details under the Streams sheet")
        activate(scrollTo("detail.episode.0", "the series' details list S1E1"))
        activate(require("streams.row.0", timeout: 60, "\(Self.tapping) on S1E1's row opens its Streams sheet"))
        let again = require("player.video", timeout: 20, "\(Self.tapping) on the stream plays S1E1 again")
        #if os(macOS)
        requireEpisode("S1E1", again, "S1E1's row")
        requirePlayback(3, timeout: 90)
        pause(again)
        #else
        requirePlayback(3, timeout: 90)
        pauseOnEpisode("S1E1", again, "S1E1's row")
        #endif
        let left = playerPosition() ?? 0
        if left >= 14 { fail("S1E1 played again is left at \(left) s, not short of the threshold's 14 s") }
        #if os(macOS)
        showControls(again)
        ready("player.back").click()
        requireGone("player.video", "the back button closes the player")
        requireSeriesOnS1E2("after-rewatch") { self.ready("nav.board").click() }
        #else
        closePlayer(again, "the controls' back button closes the player")
        requireSeriesOnS1E2("after-rewatch") { self.activate(self.phoneTab("nav.board")) }
        #endif
    }

    /// The test addon's series on S1E2 with S1E1 watched, checked `when`: back on the series' details Play offers S1E2
    /// (it reads "S1E2"; its accessibility label, checked, is "Play S1E2") and S1E1's row reads "Watched" without a
    /// progress (its value, `itemState`; shot as "detail-<when>"); on Home, reached by `home`, Continue Watching's first
    /// card is the series on its next episode, its value led by its caption's "S1E2 · Up next" (shot as "home-<when>").
    /// What is amiss on either screen fails the test once both are shot.
    private func requireSeriesOnS1E2(_ when: String, home: () -> Void) {
        var problems: [String] = []
        let play = require("detail.play", timeout: 10, "closing the player returns to the series' details")
        if !waitUntil(10, { text(of: play) == "Play S1E2" }) { problems.append("Play is labelled '\(text(of: play))', not Play S1E2") }
        #if os(macOS)
        let episode = scrollTo("detail.episode.0", "the series' details list S1E1")
        if !waitUntil(10, { episode.exists && isWatched(episode) }) { problems.append("S1E1 doesn't show watched: its value reads \(itemState(episode))") }
        if !progressState(episode).isEmpty { problems.append("S1E1's row shows a progress bar (\(progressState(episode)))") }
        #else
        // Lazy rows: the episodes under the action row come as they scroll in.
        let episode = element("detail.episode.0")
        scrolledInside(episode, steps: 5)
        if !episode.exists(within: 10) {
            problems.append("S1E1 isn't listed")
        } else {
            if !waitUntil(10, { isWatched(episode) }) { problems.append("S1E1 doesn't show watched: its value reads \(itemState(episode))") }
            if !progressState(episode).isEmpty { problems.append("S1E1's row shows a progress bar (\(progressState(episode)))") }
        }
        #endif
        shot("detail-\(when)")

        home()
        let card = require("board.cw.0", timeout: 30, "Home shows Continue Watching")
        if card.label != "Tortuga Series Test" { problems.append("Continue Watching's first card is '\(card.label)', not Tortuga Series Test") }
        // The caption under the card is hidden from accessibility: its value reads it.
        if !waitUntil(5, { itemState(card).first == "S1E2 · Up next" }) {
            problems.append("Continue Watching's first card reads \(itemState(card)), not S1E2 · Up next")
        }
        shot("home-\(when)")
        if !problems.isEmpty { fail("\(when): \(problems.joined(separator: "; "))") }
    }

    /// A host's still error video taken for the episode (`ErrorVideoTestSupport`): the test addon's
    /// tortuga-errvid episode 1, seeded at 12:00 of 24:00 with every tracker connected to the fake Trakt, played
    /// from its one stream, whose lazy link answers with a 2-min still slate (shot as
    /// "errorvideo-slate" once it has opened); the player left to settle without a tap, and on the Mac with the pointer
    /// out of the window (a tap or a pointer over the player stops up next's countdown; `watchErrorVideo`, shot as
    /// "errorvideo-settled"), then closed by the error panel's close, else the controls' back button (the title read
    /// first): the series' details (shot as "errorvideo-detail") and Home (shot as "errorvideo-home"), each read once
    /// what it shows has held for a moment (a record of the slate landing late would change it). Nothing of the slate
    /// may be recorded and the error-video panel must show (`requireNothingRecorded`, each check its own failure), its
    /// four buttons side by side and whole on the screen (a phone on its side too).
    func testErrorVideoIsNotTakenForTheEpisode() throws {
        let traktBefore = try errorVideoTrakt()
        try launchErrorVideo()
        activate(require("streams.row.0", timeout: 60, "episode 1's Streams sheet lists its stream"))
        let video = require("player.video", timeout: 20, "\(Self.tapping) on the stream opens the player")
        #if os(macOS)
        pointerOutOfTheWindow()
        #endif
        // The slate drawn: its link opened (read without a touch or a pointer move).
        _ = waitUntil(30) { !element("player.loading").exists }
        screenSettles()
        shot("errorvideo-slate")
        let seen = watchErrorVideo()
        shot("errorvideo-settled")
        var title = ""
        if seen.panel {
            #if os(macOS)
            let window = app.windows.firstMatch.frame
            #else
            let window = app.frame
            #endif
            let frames = errorButtons.map(\.frame)
            if frames.count != 4 || Set(frames.map { $0.midY.rounded() }).count > 1 || !frames.allSatisfy(window.contains) {
                fail("the error video's buttons aren't four side by side, whole within \(window): \(frames)")
            }
            activate(element("player.error.close"))
        } else {
            showControls(video)
            let shown = require("player.title", timeout: 5, "the controls show the title")
            #if os(macOS)
            title = fullText(of: shown)
            ready("player.back").click()
            #else
            title = text(of: shown)
            closePlayer(video, "the controls' back button closes the player")
            #endif
        }
        requireGone("player.video", "closing the player")
        if element("streams.row.0").exists(within: 3) {
            #if os(macOS)
            app.typeKey(.escape, modifierFlags: [])
            #else
            if isPad {
                ready(require("streams.close", "the iPad's Streams form sheet has Close")).tap()
            } else {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15)).tap()
            }
            #endif
        }
        let play = require("detail.play", timeout: 10, "closing the player returns to the series' details")
        #if os(macOS)
        let episode = scrollTo("detail.episode.0", "the series' details list S1E1")
        #else
        // Lazy rows: the episodes under the action row come as they scroll in.
        let episode = element("detail.episode.0")
        scrolledInside(episode, steps: 5)
        if !episode.exists(within: 10) { fail("the series' details don't list S1E1") }
        #endif
        // What the row and Play read must hold a moment: a record of the slate landing late would change it.
        func detail() -> (Bool, String, String) { (isWatched(episode), progressState(episode), text(of: play)) }
        let shownFirst = detail()
        _ = holds(for: 1.5) { detail() == shownFirst }
        let (watched, bar, playText) = detail()
        shot("errorvideo-detail")
        #if os(macOS)
        goBack()
        #else
        activate(phoneTab("nav.board"))
        #endif
        // An empty Continue Watching is a check's failure, not the test's end.
        let card = element("board.cw.0")
        let cardLabel = card.exists(within: 30) ? card.label : "(none)"
        // Home's captions must hold a moment too.
        let captionsFirst = continueWatchingCaptions()
        _ = holds(for: 1.5) { continueWatchingCaptions() == captionsFirst }
        let caption = continueWatchingCaptions()
        shot("errorvideo-home")
        let trakt = Array(try errorVideoTrakt().dropFirst(traktBefore.count))
        requireNothingRecorded(seen, ErrorVideoAfter(
            title: title, play: playText, watched: watched, bar: bar, card: cardLabel, caption: caption, trakt: trakt
        ))
    }

    /// Settings → Playback → Skipping with Intros set to skip automatically (shot as "skip-settings"), kept for the
    /// next launch (`--dev-data`): over the chapters series' first episode's intro the skip button counts down (shot as
    /// "skip-auto") and the intro skips without a tap, a click or a key.
    func testAutomaticSkipFromTheSettingsSkipsTheIntro() throws {
        // Two launches: longer than the default 5 minutes on a slow simulator.
        executionTimeAllowance = 600
        let url = try addonURL
        let data = freshDataName()
        launchDev(keepingData: data, addon: url)
        setIntrosToSkipAutomatically()
        app.terminate()
        launchDev(keepingData: data, addon: url, open: Self.chaptersFirstEpisode)
        playFirstChaptersEpisode()
        requireAutomaticIntroSkip()
    }

    /// The scripts clip plays with its forced subtitle line in nine scripts (Japanese, Chinese, Korean, Arabic, Hebrew,
    /// Thai, Devanagari, Cyrillic, Greek) and pauses (on iOS in landscape) for a shot of them, checked by eye: a script
    /// libass finds no font for draws boxes. The track is forced and in the audio's language, so it shows with no pick.
    func testTheScriptsClipPlaysForItsShot() throws {
        #if os(iOS)
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        #endif
        try launchDev(addon: true, open: ["streams:movie:tortuga-scripts:tortuga-scripts"])
        #if os(iOS)
        XCUIDevice.shared.orientation = .landscapeLeft
        #endif
        activate(require("streams.row.0", timeout: 60, "the Streams screen lists the scripts clip"))
        let video = require("player.video", timeout: 20, "\(Self.tapping) on the scripts clip opens the player")
        requirePlayback(1, timeout: 90)
        pause(video, "play/pause doesn't pause the scripts clip")
        shotOfThePicture(video, "every script: the forced SRT line")
    }

    /// The remux's French SRT line (line 2 of 6, 5–8 s) and its Spanish ASS line (on iOS at the same moment, on the Mac
    /// line 3, 11–14 s), paused with the controls up (on iOS in landscape): once in this device's default subtitle style,
    /// with the size then doubled while the SRT line is on the paused picture (`changeDevSetting`), and once in a changed
    /// style (`subtitleStyles`). The style restyles the SRT line and leaves the ASS line its own look. A switch of track
    /// while paused shows the new track's line only once the player seeks, so the ASS line is shot after a seek
    /// (`pauseOnSubtitleLine`).
    func testSubtitleStyleOnATextLineAndAStyledLine() throws {
        // Two launches of the player (on iOS in landscape): longer than the default 5 minutes on a slow simulator.
        executionTimeAllowance = 900
        #if os(iOS)
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        #endif
        for (index, (style, settings)) in Self.subtitleStyles.enumerated() {
            let live = index == 0
            try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"], settings: ["video.hardwareDecoding=false"] + settings)
            #if os(iOS)
            XCUIDevice.shared.orientation = .landscapeLeft
            #endif
            activate(require("streams.row.0", timeout: 60, "the Streams screen lists the test addon's streams"))
            let video = require("player.video", timeout: 20, "\(Self.tapping) on the remux stream opens the player")
            requirePlayback(1, timeout: 90)
            pause(video, "play/pause doesn't pause the remux")
            pick(from: "player.subtitles", entry: "French", video: video, "the French subtitles", landscape: true)
            pauseOnSubtitleLine(video, lines: [5.0...8.0])
            shotOfThePicture(video, "\(style): French SRT line")
            if live {
                changeDevSetting("subtitles.sizePercent=200")
                shotOfThePicture(video, "\(style), size changed to 200 % while paused: French SRT line")
            }
            pick(from: "player.subtitles", entry: "Spanish", video: video, "the Spanish subtitles", landscape: true)
            pauseOnSubtitleLine(video, lines: [11.0...14.0])
            shotOfThePicture(video, "\(style)\(live ? ", size 200 %" : ""): Spanish ASS line")
            #if os(iOS)
            Self.quitAndTurn(to: .portrait)
            #else
            app.terminate()
            #endif
        }
    }

    /// The test addon's subtitles movie (`tortuga-subtitles`: the stream's own English and French, and from the addon's
    /// subtitles five English, Spanish, German, Italian, Japanese and one named "Português"), paused: the subtitle
    /// menu's More subtitles (shot open), its Japanese (shot open) and the subtitle in it, named by the addon
    /// (`addonSubtitles`), a tap (a click) on which adds the subtitle and selects it: the button reads Japanese, the
    /// checked track's label, and the menu, opened again, lists it, checked on iOS (the Mac's menus don't report an
    /// entry's check to XCUITest) (shot). Its line ("日本語, line 2", 5–9.5 s) then shows on the paused picture once a
    /// tap (a click) on the timeline at 6.5 s has moved it (shot).
    func testMoreSubtitlesAddTheJapaneseAndSelectIt() throws {
        try launchDev(addon: true, open: ["streams:movie:tortuga-subtitles:tortuga-subtitles"])
        activate(require("streams.row.0", timeout: 60, "the Streams screen lists the subtitles movie's stream"))
        let video = require("player.video", timeout: 20, "\(Self.tapping) on the stream opens the player")
        requirePlayback(1, timeout: 90)
        pause(video, "play/pause doesn't pause the subtitles movie")
        // The open menu's shots: the Mac's menus are windows of their own, shot with the screen.
        let shotOfTheMenu: (String) -> Void
        #if os(macOS)
        shotOfTheMenu = screenShot
        #else
        shotOfTheMenu = { self.shot($0) }
        #endif
        showControls(video)
        activate(element("player.subtitles"))
        activate(openMenuEntry("More subtitles", "the subtitle menu offers More subtitles"))
        let language = openMenuEntry("Japanese", "More subtitles lists Japanese")
        shotOfTheMenu("more subtitles")
        activate(language)
        let subtitle = openMenuEntry(Self.addonSubtitles, "Japanese lists its subtitle")
        shotOfTheMenu("more subtitles: Japanese")
        pickMenuEntry(subtitle)
        if !waitUntil(5, { menuClosed(subtitle) }) { fail("picking the Japanese subtitle doesn't close the menu") }
        showControls(video)
        let button = element("player.subtitles")
        func value() -> String { button.exists ? button.value as? String ?? "nothing" : "nothing (no button)" }
        if !waitUntil(10, { value() == "Japanese" }) { fail("after picking the Japanese subtitle, player.subtitles shows '\(value())'") }

        activate(button)
        let track = openMenuEntry("Japanese", "the subtitle menu lists the Japanese subtitle added")
        #if os(iOS)
        if !waitUntil(3, { track.isSelected }) { fail("the subtitle menu lists the Japanese subtitle unchecked: \(track.debugDescription)") }
        #endif
        shotOfTheMenu("subtitle menu: Japanese")
        #if os(macOS)
        app.typeKey(.escape, modifierFlags: [])
        if !waitUntil(5, { menuClosed(track) }) { fail("Escape doesn't close the subtitle menu") }
        #else
        video.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.5)).tap()
        if !waitUntil(5, { menuClosed(track) }) { fail("a tap beside the subtitle menu didn't close it") }
        #endif

        // A track picked while paused shows its line once the player seeks.
        showControls(video)
        let line = ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 6.5 / 20, dy: 0.5))
        #if os(macOS)
        line.click()
        #else
        line.tap()
        #endif
        if !waitUntil(10, { playerPosition().map { (5.5...7.5).contains($0) } ?? false }) {
            fail("\(Self.tapping) on the timeline at 6.5 s left the movie at \(playerPosition() ?? 0) s")
        }
        // The paused frame redrawn with its line.
        showControls(video)
        screenSettles()
        shot("Japanese subtitle line")
    }

    /// The remux with its French subtitles, paused (on iOS in landscape): the subtitle menu's Second subtitle (its
    /// submenu shot open; a system menu's entries take no long press or right-click) picks the Spanish ASS track, which
    /// closes the menu. The subtitle menu, opened again, still has French checked and Spanish carries the second
    /// subtitle's badge (shot; on iOS the badge's image named "Second subtitle", the menu dragged on to the two; the Mac,
    /// whose menus draw no symbol, shows the name in the entry's second line, which its menus don't report), and its
    /// Second subtitle reads Spanish (on the Mac the playback info's Subtitles section has it as its Second track). Both
    /// line 2s (5–8 s) then show on the paused picture: the Spanish at the top, the French at the bottom (shot).
    func testSecondSubtitleShowsAtTheTopWithTheFirst() throws {
        // The 4K remux decoded in software (on iOS in landscape): longer than the default 5 minutes on a slow simulator.
        executionTimeAllowance = 600
        #if os(iOS)
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        #endif
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"], settings: ["video.hardwareDecoding=false"])
        #if os(iOS)
        XCUIDevice.shared.orientation = .landscapeLeft
        #endif
        activate(require("streams.row.0", timeout: 60, "the Streams screen lists the test addon's streams"))
        let video = require("player.video", timeout: 20, "\(Self.tapping) on the remux stream opens the player")
        requirePlayback(1, timeout: 90)
        pause(video, "play/pause doesn't pause the remux")
        pick(from: "player.subtitles", entry: "French", video: video, "the French subtitles", landscape: true)

        showControls(video)
        activate(element("player.subtitles"))
        activate(openMenuEntry("Second subtitle", "the subtitle menu offers Second subtitle"))
        #if os(macOS)
        // The submenu's entries share their labels with the subtitle menu's tracks, which stay within reach while it is
        // open: the submenu's come after them.
        let matches = app.menuItems.matching(NSPredicate(format: "title == %@ OR label == %@ OR title BEGINSWITH %@", "Spanish", "Spanish", "Spanish\n"))
        var found: XCUIElement?
        _ = waitUntil(5) {
            found = matches.allElementsBoundByIndex.last { $0.isHittable }
            return found != nil
        }
        guard let spanish = found else { return fail("Second subtitle lists no Spanish track") }
        screenShot("second subtitle menu")
        #else
        // The submenu takes the menu's place (scrolled to its Spanish where a landscape iPhone's is too short for it).
        let spanish = menuEntry("Spanish", "Second subtitle lists the Spanish track")
        screenShot("landscape: second subtitle menu")
        #endif
        pickMenuEntry(spanish)
        if !waitUntil(5, { menuClosed(spanish) }) { fail("picking the Spanish second subtitle doesn't close the menu") }
        showControls(video)
        if !waitUntil(5, { (element("player.subtitles").value as? String) == "French" }) {
            fail("after picking a second subtitle, player.subtitles shows '\(element("player.subtitles").value as? String ?? "nothing")', not French")
        }

        activate(element("player.subtitles"))
        let badged = menuEntry("Spanish", "the subtitle menu lists Spanish")
        #if os(macOS)
        screenShot("subtitle menu with the second subtitle")
        app.typeKey(.escape, modifierFlags: [])
        if !waitUntil(5, { menuClosed(badged) }) { fail("Escape doesn't close the subtitle menu") }
        togglePlaybackInfo()
        require("player.info.panel", timeout: 5, "Playback info in the options menu opens the info")
        let row = infoRow("Second track")
        if !waitUntil(5, { row.exists && fullText(of: row).contains("Spanish") }) {
            fail("the playback info's Second track reads '\(row.exists ? fullText(of: row) : "nothing (no row)")', not Spanish")
        }
        shot("playback info: second track")
        showControls(video)
        togglePlaybackInfo()
        requireGone("player.info.panel", timeout: 5, "Playback info in the options menu closes the info again")
        #else
        let badges = badged.images.matching(NSPredicate(format: "label == %@", "Second subtitle"))
        if !waitUntil(3, { badges.count > 0 }) {
            let images = badged.images.allElementsBoundByIndex.map { "\($0.identifier)|\($0.label)" }
            fail("the subtitle menu's Spanish ('\(fullText(of: badged))') has images \(images): no second subtitle's badge")
        }
        // A landscape iPhone's menu shows its first four entries: dragged up by about two, slowly and held so it
        // doesn't fling on, Spanish and French show for the shot. An iPad's shows them all; the narrowest list there is
        // the Streams screen's under the player, where the drag lands on the picture and closes the menu.
        if !isPad, let list = app.collectionViews.allElementsBoundByIndex
            .filter({ $0.exists && $0.frame.width < app.frame.width }).min(by: { $0.frame.width < $1.frame.width }) {
            list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8)).press(
                forDuration: 0.05,
                thenDragTo: list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)),
                withVelocity: .slow,
                thenHoldForDuration: 0.3
            )
        }
        screenShot("landscape: subtitle menu with the second subtitle")
        let second = menuEntry("Second subtitle", "the subtitle menu offers Second subtitle again")
        if !waitUntil(3, { fullText(of: second).contains("Spanish") }) {
            fail("the subtitle menu's Second subtitle reads '\(fullText(of: second))', not Spanish")
        }
        video.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.5)).tap()
        if !waitUntil(5, { menuClosed(second) }) { fail("a tap beside the subtitle menu didn't close it") }
        #endif

        pauseOnSubtitleLine(video, lines: [5.0...8.0])
        shotOfThePicture(video, "French SRT line with the second subtitle's Spanish ASS line")
    }

    /// The test addon's English subtitles' title in the player: the addon's name (its subtitles carry no label).
    static let addonSubtitles = "Tortuga Test"

    #if os(macOS)
    /// How much `narrowWindow` (macOS/PlayerTests+macOS.swift) narrowed the window; the next launch opens it at the
    /// app's size again (`launch`). Stored here: an extension can't hold it.
    var narrowedBy: CGFloat = 0
    #endif

    /// Brings the controls up: a tap on the video (iOS, `showPlayerControls`); on the Mac the pointer moved over the
    /// video, which also keeps them up a while longer (a hover where the pointer already is moves nothing, so the
    /// auto-hide timer would run on).
    func showControls(_ video: XCUIElement) {
        #if os(macOS)
        pointerOn(video, at: CGVector(dx: 0.3, dy: 0.4))
        pointerOn(video, at: CGVector(dx: 0.5, dy: 0.5))
        require("player.playPause", timeout: 5, "the pointer over the video shows the controls")
        #else
        showPlayerControls(video, "a tap on the video shows the controls")
        #endif
    }

    /// Pauses the video by play/pause unless it is paused (then the controls stay up, `Play` on the button); fails with
    /// `what`. iOS: `tapPlayerControl`, as the controls of a playing video can hide on a slow simulator before a tap
    /// lands. The Mac: the controls brought up first.
    func pause(_ video: XCUIElement, _ what: String = "play/pause did not pause the video") {
        #if os(macOS)
        showControls(video)
        if element("player.playPause").label != "Play" { ready("player.playPause").click() }
        if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Play" }) {
            fail("\(what): it reads \(element("player.playPause").label)")
        }
        #else
        let paused = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'player.playPause' AND label == 'Play'")).firstMatch
        if !paused.exists { tapPlayerControl("player.playPause", video: video, until: { paused.exists }, what) }
        #endif
    }

    /// Opens the native menu of `button` and picks its entry `entry`, which the button's value then shows (on the Mac
    /// the controls brought up again, as they hide on their timer while a video plays). The open menu is shot; on iOS as
    /// "landscape: <button> menu" with the screen where `landscape`, and no software keyboard may come up with it.
    func pick(from button: String, entry: String, video: XCUIElement, _ what: String, landscape: Bool = false) {
        showControls(video)
        activate(element(button))
        let item = menuEntry(entry, "\(button) opens its menu with \(what)")
        #if os(iOS)
        if landscape { screenShot("landscape: \(button) menu") } else { shot("\(button) menu") }
        requireNoKeyboard("\(button)'s menu")
        #else
        shot("\(button) menu")
        #endif
        pickMenuEntry(item)
        if !waitUntil(5, { !item.exists }) { fail("picking \(what) doesn't close the menu") }
        #if os(macOS)
        showControls(video)
        #endif
        func value() -> String { element(button).exists ? element(button).value as? String ?? "nothing" : "nothing (no button)" }
        if !waitUntil(5, { value() == entry }) { fail("after picking '\(entry)', \(button) shows '\(value())'") }
    }

    /// The entry `label` of the open menu (a pop-up menu or its submenu): on iOS `menuEntry`'s; on the Mac not one of
    /// the menu bar's menus (the Window menu's Zoom), of which only the open menu's entries can be clicked: a menu item
    /// titled `label` or with `label` as its first line. Waits up to 5 s; fails with `what`.
    func openMenuEntry(_ label: String, _ what: String) -> XCUIElement {
        #if os(macOS)
        let matches = app.menuItems.matching(NSPredicate(format: "title == %@ OR label == %@ OR title BEGINSWITH %@", label, label, "\(label)\n"))
        var found: XCUIElement?
        _ = waitUntil(5) {
            found = (0..<matches.count).lazy.map { matches.element(boundBy: $0) }.first { $0.isHittable }
            return found != nil
        }
        guard let found else {
            fail("\(what): no open menu entry '\(label)'")
            return matches.firstMatch
        }
        return found
        #else
        return menuEntry(label, what)
        #endif
    }

    /// Opens the options menu's `option` (its submenu) and picks its choice `choice`; the menu then reads `value` (else
    /// the choice) for the option (`requireOption`).
    func pickOption(_ option: String, _ choice: String, reads value: String? = nil, video: XCUIElement) {
        showControls(video)
        activate(element("player.more"))
        activate(openMenuEntry(option, "the options menu lists \(option)"))
        let item = openMenuEntry(choice, "\(option) offers \(choice)")
        #if os(macOS)
        shot("options menu: \(option)")
        #else
        screenShot("options menu: \(option)")
        #endif
        pickMenuEntry(item)
        if !waitUntil(5, { menuClosed(item) }) { fail("picking \(option) \(choice) doesn't close the options menu") }
        requireOption(option, reads: value ?? choice, video: video, "after picking \(choice)")
    }

    /// Fails with `what` unless the options menu's `option` reads `value`; the open menu is shot, then closed. iOS: the
    /// entry's second line, the menu closed by a tap beside it. The Mac, whose menus don't report an entry's second
    /// line: the Debug build's probe `player.options`, which lists each option with its value (`Aspect ratio=Letterbox;
    /// Zoom=100%`), a value with `value` in it counting (`4:59 left` for `left`); the menu closed by Escape.
    func requireOption(_ option: String, reads value: String, video: XCUIElement, _ what: String) {
        showControls(video)
        #if os(macOS)
        let probe = element("player.options")
        let current = { (probe.exists ? probe.label : "").components(separatedBy: "; ").first { $0.hasPrefix("\(option)=") } ?? "" }
        if !waitUntil(3, { current().dropFirst(option.count + 1).contains(value) }) {
            fail("\(what): the options menu's \(option) reads '\(current())' (\(probe.exists ? probe.label : "no options probe")), not \(value)")
        }
        let button = element("player.more")
        ready(button).click()
        let entry = openMenuEntry(option, "the options menu lists \(option)")
        shot("options menu: \(option) reads \(value)")
        app.typeKey(.escape, modifierFlags: [])
        if !waitUntil(5, { menuClosed(entry) }) { fail("Escape doesn't close the options menu") }
        #else
        ready("player.more").tap()
        let entry = menuEntry(option, "the options menu lists \(option)")
        let text = { [entry.label, entry.value as? String ?? ""].joined(separator: " ") }
        if !waitUntil(3, { text().contains(value) }) { fail("\(what): the options menu's \(option) reads '\(text())', not \(value)") }
        screenShot("options menu: \(option) reads \(value)")
        video.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.5)).tap()
        if !waitUntil(5, { menuClosed(entry) }) { fail("a tap beside the options menu didn't close it") }
        #endif
    }

    /// A screenshot of the whole screen, kept as `name` (on the Mac as "screen: <name>"): on iOS complete in landscape
    /// too (content sideways on a portrait canvas; the app's own screenshot in landscape holds its portrait frame cropped
    /// to a landscape canvas); on the Mac the desktop, the PiP window over it.
    func screenShot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        #if os(macOS)
        attachment.name = "screen: \(name)"
        #else
        attachment.name = name
        #endif
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// The paused picture as it shows (its fit and zoom, its subtitle line), shot once the screen has settled: the
    /// caller has waited for what it is drawn again after (the menu closed, the pause, the seek). iOS: the screen
    /// (`screenShot`); the Mac: the app with the controls up (`shot`).
    func shotOfThePicture(_ video: XCUIElement, _ name: String) {
        #if os(macOS)
        showControls(video)
        screenSettles()
        shot(name)
        #else
        screenSettles()
        screenShot(name)
        #endif
    }

    /// Pauses the remux with the controls up on a bottom line of its subtitles (lines at 1–4, 5–8, 11–14, 21–24 and
    /// 25–28 s; the one at 15–18 s is a sign at the top, `{\an8}`, which stays there).
    /// iOS: line 2 (5–8 s) whatever `lines`, reached by a tap on the timeline at 6.5 s of its 30 s (waiting for a line
    /// while it plays lands late where position reads are slow).
    /// The Mac: plays on to one of `lines` (the seconds a line is on screen) and pauses there. Play/pause's click leaves
    /// the pointer on the controls, which keeps them up while the video plays, so the position beside the timeline is
    /// read at each of `waitUntil`'s checks without moving the pointer (`playerPosition` hovers and takes up to 3.5 s a
    /// read, longer than a line's first second). It shows whole seconds: a read of `s` with [s, s + 2) inside a line
    /// pauses it with Space, early in the line however fast the video plays (software decoding of the 4K remux runs far
    /// slower than real time on a loaded Mac).
    func pauseOnSubtitleLine(_ video: XCUIElement, lines: [ClosedRange<Double>] = [5.0...8.0, 11.0...14.0, 21.0...24.0]) {
        #if os(macOS)
        pause(video)
        let position = element("player.position")
        func read() -> Double? { position.exists ? Self.seconds(text(of: position)) : nil }
        func early(_ seconds: Double) -> Bool { lines.contains { $0.lowerBound <= seconds && seconds + 2 <= $0.upperBound } }
        guard let start = read() else { return fail("the paused player shows no position") }
        ready("player.playPause").click()
        var at = start
        let reached = waitUntil(30) {
            at = read() ?? at
            return early(at)
        }
        if !reached { return fail("from \(start) s the player never reached a subtitle line of \(lines) (at \(at) s)") }
        app.typeKey(" ", modifierFlags: [])
        showControls(video)
        if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Play" }) {
            fail("play/pause did not pause the video: it reads \(element("player.playPause").label)")
        }
        if let paused = read(), !lines.contains(where: { $0.lowerBound <= paused && paused + 1 <= $0.upperBound }) {
            fail("paused at \(paused) s, read at \(at) s, off the subtitle lines \(lines)")
        }
        #else
        pause(video, "play/pause doesn't pause the remux")
        ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 6.5 / 30, dy: 0.5)).tap()
        if !waitUntil(10, { playerPosition().map { (5.5...7.5).contains($0) } ?? false }) {
            fail("a tap on the timeline at 6.5 s left the remux at \(playerPosition() ?? 0) s")
        }
        showControls(video)
        #endif
    }
}
