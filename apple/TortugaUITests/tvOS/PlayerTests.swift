import XCTest

/// The player with the Siri Remote, on the test addon's streams for a Cinemeta movie (tools/media/test_addon.py):
/// the remux (HEVC HDR10 with three audio tracks, five subtitle tracks and chapters) through the transport bar's
/// buttons, which open their menu above them as AVKit's do (Subtitles, Audio, Playback speed, Options), and the playback
/// info, AVKit's info panel at the bottom, from the options menu and by Down from the timeline, with no Info tab under
/// the timeline (hardware decoding switched off: the decoder row reads software; no episode buttons, for a movie), the
/// subtitle delay in its menu, and
/// a click-pad scrub on the focused timeline, then Menu closing the open menu before it leaves the player; Menu closes
/// each menu with focus back on its button, as AVKit's do (a pick too), and the playback info with focus back on what
/// opened it; the H.264 file's Now Playing info and a click-pad seek with the controls down. The live test
/// addon's 24/7 channel plays live (`LiveTestSupport`): Now Playing a live stream, play/pause focused in the timeline's
/// place with the live label right after it, the click-pad seeking nothing, and nothing kept once it closed. The 4:3 clip
/// through the panel's Options tab: each fit (the first from this device's setting) and a zoom, shot with its
/// subtitle line, Menu from an option's choices back to the options, loudness normalization switched on and off in
/// place, and the fit carried over to the next video. The H.264 file's Shaders page on the Options tab: NVScaler and
/// Anime4K Fast A each picked and shot over the paused frame, then Off. From the test addon's own series' last episode
/// the transport bar's Previous episode plays the one before, each in turn, Previous and Next episode before the menus,
/// each disabled (and out of focus's way) at its end of the series; on its first episode up next takes focus as it
/// counts down, a move of the remote stops the countdown (no episode follows the end), and Select on it plays the next
/// episode (the countdown running out by itself: `StreamsTests`). Over the intro of the test addon's chapters
/// series' first episode the skip button takes focus and Select skips it, the credits bring up next, and with intros
/// set to skip automatically in Settings → Playback the button counts down and skips by itself (`SkipTestSupport`);
/// over the second episode's credits the button gone by itself after its 8 s comes back with the controls Up brings
/// up, focus on the timeline rather than on it.
/// The remux's French SRT line and Spanish ASS line show in the default and in a changed subtitle style. On the test
/// addon's subtitles movie the subtitle tab's More subtitles lists the languages of the subtitles not added, each with
/// its count, and the subtitle on Japanese's page is added and selected: the tab then has it checked, its line on the
/// picture. On the remux's subtitle tab a held Select on Spanish shows it as the second subtitle, the panel open, its
/// line at the top of the picture with the French SRT line at the bottom. The remux's
/// first chapter, Opening (0–10 s), reads as an intro: Menu puts its skip button away (`putAwaySkipButton`) before
/// Select on the video pauses.
/// While the H.264 file plays the app's idle timer is off (the debug probe `player.keepAwake`), and on again once it is
/// paused. On the simulated display of an HDR TV with Match Content on (`-simulateMatchContent YES`: each criteria
/// change switches the display for 2 s; the debug probe `player.display` counts the criteria written and cleared, the
/// switches, and the times the player was told the display was ready during one) each file's criteria go out once, the
/// video starts only once the switch they started has ended, up next from an HDR10 episode to an SDR one switches once,
/// and a stream picked over the player for the next episode keeps the display's mode.
final class PlayerTests: TortugaUITestCase {
    func testRemuxMenusInfoSeekAndMenuClosesTheMenuFirst() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"], settings: ["video.hardwareDecoding=false"])
        requireFocus("streams.row.0", timeout: 60, "the remux stream has focus once the Streams screen lists them")
        press(.select)
        require("player.video", "Select on the remux stream opens the player")
        requirePlayback(2, timeout: 90)
        putAwaySkipButton()

        // Select on the video pauses, which brings the controls up with focus on the timeline, whose Select plays again.
        press(.select)
        if !waitUntil(5, { element("player.scrub").exists && element("player.scrub").label == "Play" }) {
            fail("Select did not pause the video: the timeline reads \(element("player.scrub").label)")
        }
        requireFocus("player.scrub", "the timeline has focus once the controls are up")
        requireNowPlaying("rate=0.0", "Now Playing follows the pause: no rate")
        shot("paused")
        requireEpisodeButtons(nil, "a movie's transport bar")
        if element("player.infoTab").exists { fail("the transport bar has an Info tab under the timeline: Playback info belongs in the options menu") }

        let subtitle = pick(from: "player.subtitles", label: "French", "the French subtitles")
        shot("subtitles: \(subtitle)")
        pauseOnSubtitleLine()
        shot("paused, controls up over a subtitle")

        // The subtitle tab ends with its delay; Reset shows disabled until + moves the delay; Menu closes the panel.
        walkControls(to: "player.subtitles", "the remote reaches the subtitle button")
        press(.select)
        require("player.menu", timeout: 5, "Select on player.subtitles opens the panel")
        moveFocus(to: "player.menu.delay", with: .down, limit: 12, "Down reaches the subtitle delay entry")
        press(.select)
        requireFocus("player.delay.decrease", "the subtitle delay opens with focus on −")
        shot("subtitle delay at zero")
        let reset = require("player.delay.reset", timeout: 5, "the subtitle delay has Reset")
        if reset.isEnabled { fail("the subtitle delay offers Reset before it was moved") }
        moveFocus(to: "player.delay.increase", with: .right, "Right reaches +")
        press(.select)
        if !waitUntil(5, { reset.isEnabled }) { fail("+ didn't move the subtitle delay: Reset stays off") }
        shot("subtitle delay")
        press(.menu)
        requireGone("player.menu", timeout: 5, "Menu closes the subtitle delay")
        if !element("player.video").exists { fail("Menu on the delay control left the player") }

        let audio = pick(from: "player.audio", label: "German", "the German audio")
        shot("audio: \(audio)")
        pick(from: "player.speed", label: "1.5×", "the 1.5× speed")
        shot("speed 1.5×")

        // Info opens from the options menu's Playback info, as AVKit's info panel at the bottom with focus on its tab;
        // Up from the tab closes it, Down from the timeline opens it again (AVKit's Down), Menu closes it.
        walkControls(to: "player.more", "the remote reaches the options button")
        press(.select)
        require("player.menu", timeout: 5, "Select on the options button opens the options menu")
        requireFocus("player.more.info", "the options open with focus on Playback info")
        shot("options menu")
        press(.select)
        require("player.info.panel", timeout: 5, "Select on Playback info opens the playback info")
        requireGone("player.menu", timeout: 5, "the info panel replaces the options menu")
        requireFocus("player.panel.info", "the playback info opens with focus on its tab")
        let decoder = infoRow("Decoder")
        if !waitUntil(5, { decoder.exists && fullText(of: decoder).contains("Software") }) {
            fail("with hardware decoding off, the decoder row reads '\(decoder.exists ? fullText(of: decoder) : "nothing")', not Software")
        }
        shot("info")
        press(.up)
        requireGone("player.info.panel", timeout: 5, "Up from the Info tab closes the info panel")
        requireFocus("player.more", "the options button has focus again once the info panel it opened is gone")
        press(.down)
        requireFocus("player.scrub", "Down from the options button reaches the timeline")
        press(.down)
        require("player.info.panel", timeout: 5, "Down from the timeline opens the info panel")
        requireFocus("player.panel.info", "the info panel opens with focus on its tab")
        press(.menu)
        requireGone("player.info.panel", timeout: 5, "Menu closes the info panel")
        requireFocus("player.scrub", "the timeline has focus again once the info panel is gone")

        // The focused timeline: the click-pad's right scrubs, Select commits.
        let before = playerPosition() ?? 0
        press(.right)
        press(.select)
        if !waitUntil(6, { (playerPosition() ?? 0) >= before + 8 }) {
            fail("a click-pad scrub from \(before) s reached \(playerPosition() ?? 0) s; expected ≥ \(before + 8) s")
        }
        shot("after a click-pad scrub")

        walkControls(to: "player.audio", "the remote reaches the audio button")
        press(.select)
        require("player.menu", timeout: 5, "Select on the audio button opens the panel")
        press(.menu)
        requireGone("player.menu", timeout: 5, "the first Menu closes the open panel")
        if !element("player.video").exists { fail("the first Menu left the player instead of closing the panel") }
        shot("panel closed, still in the player")
        // Paused, the controls stay up: the next Menu leaves the player.
        for _ in 0..<2 where element("player.video").exists {
            press(.menu)
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Menu with no panel open closes the player")
        require("streams.row.0", "Menu returns to the Streams screen")
        shot("back on streams")
    }

    /// Menu closes each of the transport bar's menus with focus back on its button, as AVKit's menus do: Playback
    /// speed, Options, Subtitles and Audio over the paused remux (shot on Audio's). The playback info gives focus back
    /// to what opened it: the options button when the options' Playback info opened it, the timeline when Down from it
    /// did.
    func testMenuClosesEachMenuWithFocusOnItsButton() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        requireFocus("streams.row.0", timeout: 60, "the remux stream has focus once the Streams screen lists them")
        press(.select)
        require("player.video", "Select on the remux stream opens the player")
        requirePlayback(2, timeout: 90)
        putAwaySkipButton()
        pauseWithSelect()
        for button in ["player.speed", "player.more", "player.subtitles", "player.audio"] {
            walkControls(to: button, "the remote reaches \(button)")
            press(.select)
            require("player.menu", timeout: 5, "Select on \(button) opens its menu")
            press(.menu)
            requireGone("player.menu", timeout: 5, "Menu closes \(button)'s menu")
            requireFocus(button, "Menu closes \(button)'s menu with focus on its button")
        }
        shot("menu closed, focus on audio")

        openOptions()
        press(.select)
        require("player.info.panel", timeout: 5, "Select on Playback info opens the playback info")
        requireFocus("player.panel.info", "the playback info opens with focus on its tab")
        press(.menu)
        requireGone("player.info.panel", timeout: 5, "Menu closes the playback info")
        requireFocus("player.more", "Menu closes the playback info opened from the options with focus on the options button")
        press(.down)
        requireFocus("player.scrub", "Down from the options button reaches the timeline")
        press(.down)
        require("player.info.panel", timeout: 5, "Down from the timeline opens the playback info")
        press(.menu)
        requireGone("player.info.panel", timeout: 5, "Menu closes the playback info")
        requireFocus("player.scrub", "Menu closes the playback info opened from the timeline with focus on the timeline")
    }

    /// Up from the timeline lands on the first button of the row above it, whichever buttons a title has: Speed on a
    /// movie, Next episode on a series' first episode (its Previous episode disabled).
    func testUpFromTheTimelineLandsOnTheRowsFirstButton() throws {
        var problems: [String] = []
        let titles = [
            (open: ["streams:movie:tt0111161:tt0111161"], row: 1, first: "player.speed"),
            (open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"], row: 0, first: "player.next"),
        ]
        for title in titles {
            let name = title.open.last ?? ""
            try launchDev(addon: true, open: title.open)
            requireFocus("streams.row.0", timeout: 60, "\(name) lists its streams")
            if title.row > 0 { moveFocus(to: "streams.row.\(title.row)", with: .down, "Down reaches the H.264 stream") }
            press(.select)
            require("player.video", "Select on the H.264 stream of \(name) opens the player")
            requirePlayback(1, timeout: 90)
            let scrub = element("player.scrub")
            if !scrub.exists { press(.up) }
            requireFocus("player.scrub", timeout: 10, "\(name): the controls come up with focus on the timeline")
            press(.select)
            if !waitUntil(5, { scrub.exists && scrub.label == "Play" }) { fail("\(name): Select on the timeline doesn't pause") }
            press(.up)
            _ = waitUntil(3) { focusedId != "player.scrub" }
            shot("up from the timeline, \(name)")
            if focusedId != title.first {
                problems.append("\(name): Up from the timeline lands on \(focusedId ?? "nothing"), not \(title.first), the row's first button")
            }
        }
        if !problems.isEmpty { fail(problems.joined(separator: "\n")) }
    }

    /// The title with its episode line, the buttons and the times over near black (tortuga-frames-series: one episode on
    /// the dark still frame), shot for their colours.
    func testControlsOverADarkEpisodeFrame() throws {
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-frames-series", "streams:series:tortuga-frames-series:tortuga-frames-series:1:1"])
        requireFocus("streams.row.0", timeout: 60, "the episode's Streams panel focuses the dark frame")
        press(.select)
        require("player.video", timeout: 20, "Select on the dark frame opens the player")
        require("player.scrub", timeout: 30, "the controls stay up over the dark frame")
        // The controls drawn in over the frame.
        screenSettles()
        shot("controls over the dark episode frame")
    }

    /// The test addon's expired link (tortuga-expired: its resolved link serves the app's resolve and answers the
    /// player 403, so mpv fails to open it): the player's error comes up full screen (`requireErrorFillsScreen`) under the
    /// title alone: no timeline, transport or menu buttons behind it. It says the link expired (the engine hands the
    /// store mpv's HTTP status with the error), not that playback failed.
    func testExpiredLinkBringsUpThePlayersError() throws {
        try launchDev(addon: true, open: ["streams:movie:tortuga-expired:tortuga-expired"])
        requireFocus("streams.row.0", timeout: 60, "the Streams screen focuses the expired link")
        press(.select)
        let error = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier IN {'player.error.retry', 'player.error.next', 'player.error.close'}")).firstMatch
        if !error.exists(within: 60) { fail("the expired link doesn't bring up the player's error") }
        require("player.title", timeout: 5, "the title stays with the error")
        shot("the player's error for the expired link")
        if !app.staticTexts["The stream link has expired."].exists(within: 5) {
            fail("the expired link's error doesn't say it expired: \(app.staticTexts.allElementsBoundByIndex.map(\.label))")
        }
        let behind = ["player.scrub", "player.position", "player.remaining", "player.playPause", "player.previous", "player.next",
                      "player.audio", "player.subtitles", "player.speed", "player.more"].filter { element($0).exists }
        if !behind.isEmpty { fail("\(behind.joined(separator: ", ")) show with the error") }
        requireErrorFillsScreen("the expired link's error")
    }

    /// VoiceOver running (debug builds take the launch argument `-assistiveTechnology YES` for it: XCUITest can't turn
    /// it on): the controls stay up while the H.264 file plays on, past the 5 s that hide them otherwise, the video's
    /// value following the position as the playhead probe reads it. VoiceOver moves through them one at a time, at its
    /// own pace.
    func testControlsStayUpWhileAnAssistiveTechnologyRuns() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"], arguments: ["-assistiveTechnology", "YES"])
        requireFocus("streams.row.0", timeout: 60, "the first stream has focus once the Streams screen lists them")
        press(.down)
        requireFocus("streams.row.1", "Down moves focus to the H.264 stream")
        press(.select)
        require("player.video", "Select on the H.264 stream opens the player")
        requirePlayerLoaded("the H.264 stream")
        let start = requirePlayback(1, timeout: 30)
        let scrub = element("player.scrub")
        if !holds(for: 8, { scrub.exists }) { fail("with an assistive technology running the controls hide while the video plays") }
        if (playerPosition() ?? 0) < start + 5 { fail("the H.264 file didn't play on while the controls were watched: \(start) s, then \(playerPosition() ?? 0) s") }
        let video = element("player.video")
        if !waitUntil(3, { video.value as? String == playhead() && Self.seconds(playhead()) != nil }) {
            fail("with an assistive technology running the video's value reads '\(video.value as? String ?? "nothing")', not the position '\(playhead())'")
        }
        shot("controls up under an assistive technology")
    }

    /// In Arabic, laid out right to left, the timeline runs left to right, as the remote's Left and Right and its swipes
    /// scrub (playback controls aren't mirrored): on the test addon's series' first episode the time left at the
    /// timeline's right end, and Previous episode left of Next episode (shot as "controls in arabic").
    func testTimelineAndEpisodeButtonsRunLeftToRightInArabic() throws {
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"], languages: ["ar"])
        requireFocus("streams.row.0", timeout: 60, "the first episode's Streams panel focuses its first stream")
        press(.select)
        require("player.video", timeout: 20, "Select on the stream opens the player")
        requirePlayback(1, timeout: 90)
        let ids = ["player.scrub", "player.remaining", "player.previous", "player.next"]
        var frames: [String: CGRect] = [:]
        _ = waitUntil(20) {
            if !element("player.scrub").exists { press(.up) }
            frames = self.frames(of: ids)
            return frames.count == ids.count
        }
        shot("controls in arabic")
        guard let scrub = frames["player.scrub"], let remaining = frames["player.remaining"],
              let previous = frames["player.previous"], let next = frames["player.next"] else {
            return fail("in Arabic the controls showed without \(ids.filter { frames[$0] == nil }.joined(separator: ", "))")
        }
        if remaining.midX < scrub.midX { fail("in Arabic the time left (\(remaining)) sits at the timeline's left end (\(scrub))") }
        if previous.midX > next.midX { fail("in Arabic Previous episode (\(previous)) sits right of Next episode (\(next))") }
    }

    func testH264NowPlayingAndClickPadSeek() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        requireFocus("streams.row.0", timeout: 60, "the first stream has focus once the Streams screen lists them")
        press(.down)
        requireFocus("streams.row.1", "Down moves focus to the H.264 stream")
        press(.select)
        let video = require("player.video", "Select on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        requireNowPlaying("title=\(video.label);", "Now Playing shows the title")
        requireNowPlaying("rate=1.0", "Now Playing shows the video playing at normal speed")
        requireNowPlaying("duration=20", "Now Playing shows the 20 s duration")
        shot("playing")

        requireGone("player.scrub", timeout: 15, "the controls hide while the video plays")
        let before = playerPosition() ?? 0
        press(.right)
        if !waitUntil(6, { (playerPosition() ?? 0) >= before + 8 }) {
            fail("the click-pad's right from \(before) s reached \(playerPosition() ?? 0) s; expected ≥ \(before + 8) s")
        }
        shot("after the click-pad's right")

        for _ in 0..<3 where element("player.video").exists {
            press(.menu)
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Menu closes the player")
        require("streams.row.1", "Menu returns to the Streams screen")
    }

    /// The live test addon's 24/7 channel (`LiveTestSupport`), live from the start: Now Playing shows a live stream; Up
    /// brings the controls up with no timeline or times and focus on play/pause in the timeline's place, the live label
    /// (● LIVE and the time watched) right after it, level with it; with the controls down the click-pad's left and
    /// right and a click-and-hold on its left seek nothing (neither the position nor the time watched goes back); once
    /// Menu closed the player, the channel's details offer Play, not Resume, and Home has no Continue Watching.
    func testLiveChannelShowsLiveIgnoresSeeksAndKeepsNothing() throws {
        try launchDev(addon: true, open: Self.liveChannelOpen)
        let row = livePlainStream("the channel's Streams screen").identifier
        requireFocus(prefix: "streams.row.", "the Streams screen focuses a stream")
        moveFocus(to: row, with: .down, "Down moves focus to the plain stream")
        press(.select)
        require("player.video", "Select on the plain stream opens the player")
        requirePlayback(2, timeout: 90)
        requireNowPlaying("live=true", "Now Playing shows a live stream")
        requireGone("player.playPause", timeout: 15, "the controls hide while the channel plays")
        press(.up)
        requireFocus("player.playPause", "Up brings the controls up with focus on play/pause")
        requireNoTimeline("the controls up")
        let before = (position: requireLivePosition("the controls up"), watched: requireLiveLabel("the controls up"))
        let play = element("player.playPause").frame, label = element("player.live").frame
        if label.minX <= play.maxX || label.minX - play.maxX > 60 || abs(label.midY - play.midY) > 2 {
            fail("the live label at \(label) isn't right after play/pause at \(play), level with it")
        }
        shot("live")

        requireGone("player.playPause", timeout: 15, "the controls hide again while the channel plays")
        press(.left)
        press(.right)
        XCUIRemote.shared.press(.left, forDuration: 1.5)
        // A second for a seek the inputs started to land in, the time watched (`playhead`, read with the controls down:
        // `Live · watched 0:07`) never going back meanwhile.
        var watched = before.watched
        let kept = holds(for: 1) {
            let read = playhead()
            if read.hasPrefix("Live · watched "), let now = Self.seconds(read) { watched = now }
            return watched >= before.watched
        }
        if !kept { fail("after the click-pad's inputs the time watched went from \(before.watched) s to \(watched) s") }
        press(.up)
        requireSeeksIgnored(since: before, "after the click-pad's left, right and a click-and-hold on its left")
        shot("live after the click-pad")

        for _ in 0..<3 where element("player.video").exists {
            press(.menu)
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Menu closes the player")
        // Over the details the Streams screen goes with the stream picked: Menu returns to the details.
        let details = require("detail.play", timeout: 10, "Menu returns to the channel's details")
        requireLiveNotResumed(details, "after the player")
        press(.menu)
        requireNoContinueWatching("Home after the live channel")
        shot("home after live")
    }

    /// The live test addon's match (`LiveTestSupport`), whose playlist keeps a 90-s window: Up brings the controls up
    /// with focus on the window's timeline, where the window starts on the left, no time left, and the go-to-live
    /// button (● LIVE and the time watched) first among the transport bar's buttons, level with them; three presses
    /// of the click-pad's left preview 30 s back, as a video's ±10 s do, and a second on play the match that far behind
    /// the edge, the stream reopened at an earlier segment (● LIVE · −0:36); the label, reached by Up, and Select play
    /// it at the edge again; Down to the timeline and three presses of left play it 30 s back again within what mpv
    /// holds, no reopen; once Menu closed the player, the match's details offer Play, not Resume.
    func testLiveMatchRewindsInItsWindowAndGoesToLive() throws {
        try launchDev(addon: true, open: Self.liveMatchOpen)
        requireFocus("streams.row.0", timeout: 60, "the match's Streams screen focuses its stream")
        press(.select)
        require("player.video", "Select on the match's stream opens the player")
        requirePlayback(2, timeout: 90)
        requireAtLiveEdge("the match opened")
        requireGone("player.scrub", timeout: 15, "the controls hide while the match plays")
        press(.up)
        requireFocus("player.scrub", "Up brings the controls up with focus on the window's timeline")
        requireLiveWindow("the controls up")
        let label = element("player.live").frame, more = element("player.more")
        if more.exists, label.maxX >= more.frame.minX || abs(label.midY - more.frame.midY) > 2 {
            fail("the live label at \(label) isn't before the options button at \(more.frame), level with it")
        }
        shot("live match at the edge")

        requireSeekBack(reopened: true, "three presses of the click-pad's left on the timeline") {
            if !element("player.scrub").exists { press(.up) }
            requireFocus("player.scrub", timeout: 5, "the controls up, the timeline has focus")
            press(.left, count: 3)
        }
        if !element("player.scrub").exists { press(.up) }
        require("player.scrub", timeout: 5, "Up brings the controls up behind the edge")
        shot("live match behind the edge")

        walkControls(to: "player.live", "behind the edge, the remote reaches the live label")
        press(.select)
        requireAtLiveEdge("after Select on the live label")
        if !element("player.scrub").exists { press(.up) }
        require("player.scrub", timeout: 5, "Up brings the controls up at the edge again")
        shot("live match back at the edge")
        requireSeekBack(reopened: false, "three presses of the click-pad's left on the timeline again") {
            if !element("player.scrub").exists { press(.up) }
            if !element("player.scrub").hasFocus { press(.down) }
            requireFocus("player.scrub", timeout: 5, "Down from the live label focuses the timeline")
            press(.left, count: 3)
        }

        for _ in 0..<3 where element("player.video").exists {
            press(.menu)
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Menu closes the player")
        let details = require("detail.play", timeout: 10, "Menu returns to the match's details")
        requireLiveNotResumed(details, "after the player")
    }

    /// The screen stays on while the video plays and may dim once it is paused: the app's idle timer (read back by the
    /// debug probe) is off while the H.264 file plays, on once Select has paused it, off again once Select on the
    /// timeline plays it on. The probe goes with the player; the run's app.log has the timer on again after Menu
    /// closed it (`keep awake: false, isIdleTimerDisabled=false`).
    func testPlayingKeepsTheScreenAwakeAndPausingLetsItSleep() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        requireFocus("streams.row.0", timeout: 60, "the first stream has focus once the Streams screen lists them")
        press(.down)
        requireFocus("streams.row.1", "Down moves focus to the H.264 stream")
        press(.select)
        require("player.video", "Select on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        requireIdleTimerDisabled(true, "while the video plays")
        pauseWithSelect()
        requireIdleTimerDisabled(false, "once Select has paused the video")
        press(.select)
        requireIdleTimerDisabled(true, "once Select on the timeline plays the video again")
        for _ in 0..<3 where element("player.video").exists {
            press(.menu)
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Menu closes the player")
    }

    /// Match Content (simulated): the test addon's HDR movie's HDR10 stream asks the display for HDR10 once and starts
    /// only once the switch that started has ended; the plan the shared policy decides for the video, which comes
    /// meanwhile, doesn't end the wait.
    func testMatchContentStartsTheVideoOnceTheDisplayHasSwitched() throws {
        try launchDev(addon: true, open: ["streams:movie:tortuga-hdr:tortuga-hdr"], matchContent: true)
        requireFocus("streams.row.0", timeout: 60, "the SDR stream has focus once the Streams screen lists them")
        moveFocus(to: "streams.row.1", with: .down, "Down reaches the HDR10 stream")
        press(.select)
        require("player.video", timeout: 20, "Select on the HDR10 stream opens the player")
        requirePlayback(1, timeout: 90)
        requireDisplay(["writes": 1, "switches": 1], "the HDR10 stream playing")
    }

    /// Match Content (simulated), the app's settings as they come: up next through the test addon's ranges series,
    /// from its HDR10 episode (23.976 fps) to its SDR one (24 fps) and on to another HDR10 one (one binge group, so the
    /// same player plays on), asks the display for each next episode's mode once, the video's own frame rate included,
    /// straight from the one before (not first for the last file's format under the next episode's plan), and starts
    /// each after that switch. Nothing is pressed until the third episode plays: any input while up next counts down
    /// stops it, and the episode would end there.
    func testUpNextToAnotherDynamicRangeSwitchesTheDisplayOnce() throws {
        try launchDev(
            addon: true,
            open: ["board", "detail:series:tortuga-ranges", "streams:series:tortuga-ranges:tortuga-ranges:1:1"],
            matchContent: true
        )
        requireFocus("streams.row.0", timeout: 60, "the HDR10 episode's Streams panel focuses its stream")
        press(.select)
        require("player.video", timeout: 20, "Select on the stream opens the player")
        requirePlayback(1, timeout: 90)
        let first = requireDisplay(["writes": 1, "switches": 1], rate: 23976, "the HDR10 episode playing")
        requireNextFilePlaying(length: "0:10", timeout: 60, "up next from the HDR10 episode to the 10 s SDR one")
        let second = requireDisplay(["writes": 1, "switches": 1], since: first, rate: 24000, "up next playing the SDR episode")
        requireNextFilePlaying(length: "0:20", timeout: 40, "up next from the SDR episode to the second HDR10 one")
        requireDisplay(["writes": 1, "switches": 1], since: second, rate: 23976, "up next playing the second HDR10 episode")
        pauseOnEpisode("S1E3", "up next after the SDR episode")
    }

    /// Match Content (simulated): the test addon's series' first episode from its stream without a binge group, then
    /// the second episode's stream picked in the Streams panel that up next brings up over the player: the playback
    /// that replaces the first starts from the mode the first left, in the same format, so the criteria are neither
    /// cleared nor written again and the display doesn't switch.
    func testAStreamPickedOverThePlayerKeepsTheDisplayMode() throws {
        try launchDev(
            addon: true,
            open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"],
            matchContent: true
        )
        requireFocus("streams.row.0", timeout: 60, "the first episode's Streams panel focuses the stream without a binge group")
        press(.select)
        require("player.video", timeout: 20, "Select on the stream opens the player")
        requirePlayback(1, timeout: 90)
        let first = requireDisplay(["writes": 1, "switches": 1], "the first episode playing")
        requireFocus("streams.row.0", timeout: 60, "up next's countdown brings the second episode's Streams panel up over the player")
        press(.select)
        requireGone("streams.row.0", "picking the stream closes the panel")
        pauseOnEpisode("S1E2", "the stream picked over the player")
        requireDisplay([:], since: first, "the second episode playing in the player that replaced the first")
    }

    /// The panel's Options tab (the transport bar's `player.more`) on the 4:3 clip, its English SRT line on, paused with
    /// the controls up: the fit this device's setting gives (`video.fit=cover`: Fill screen), then Letterbox and Stretch
    /// from Aspect ratio's choices, 150 % from Zoom's over Stretch and over Letterbox; Menu on Zoom's choices goes back
    /// to the options with focus on Zoom, and Reset puts it back; the screen shot in each, the line on it. Normalize
    /// loudness flips in place. Back in the Streams screen and into the clip again, the fit carries over and the zoom
    /// doesn't.
    func testOptionsPanelFitZoomAndCarryOver() throws {
        executionTimeAllowance = 600
        try launchDev(addon: true, open: ["streams:movie:tortuga-4x3:tortuga-4x3"], settings: ["video.fit=cover"])
        requireFocus("streams.row.0", timeout: 60, "the 4:3 clip has focus once the Streams screen lists it")
        press(.select)
        require("player.video", "Select on the 4:3 clip opens the player")
        requirePlayback(1, timeout: 90)
        pauseWithSelect()
        pick(from: "player.subtitles", label: "English", "the English subtitles")
        requireOption("videoFit", reads: "Fill screen", "with the device setting video.fit=cover")
        shotOfThePicture("fit: Fill screen (device setting)")
        for fit in ["Letterbox", "Stretch"] {
            pickOption("videoFit", fit)
            shotOfThePicture("fit: \(fit)")
        }
        pickOption("zoom", "150%")
        shotOfThePicture("zoom 150%, Stretch")
        pickOption("videoFit", "Letterbox")
        shotOfThePicture("zoom 150%, Letterbox")

        // Menu on an option's choices goes back to the options, on that option.
        openOption("zoom")
        press(.menu)
        requireFocus("player.option.zoom", "Menu on Zoom's choices goes back to the options with focus on Zoom")
        if !element("player.menu").exists { fail("Menu on Zoom's choices closed the panel instead of going back to the options") }
        shot("panel: back to the options")
        press(.menu)
        requireGone("player.menu", timeout: 5, "Menu on the options closes the panel")
        pickOption("zoom", "Reset", reads: "100%")
        shotOfThePicture("zoom reset, Letterbox")

        // A switch flips where it is, focus staying on it.
        openOptions()
        let normalization = "player.option.audioNormalization"
        moveFocus(to: normalization, with: .down, limit: 10, "the remote reaches Normalize loudness")
        for state in ["On", "Off"] {
            press(.select)
            if !waitUntil(5, { element(normalization).exists && element(normalization).value as? String == state }) {
                fail("Select on Normalize loudness reads '\(element(normalization).value as? String ?? "nothing")', not \(state)")
            }
            requireFocus(normalization, "focus stays on Normalize loudness as it switches \(state.lowercased())")
            shot("panel: Normalize loudness \(state)")
        }
        press(.menu)
        requireGone("player.menu", timeout: 5, "Menu on the options closes the panel")

        // The next video: the fit stays Letterbox, the zoom is back at 100%.
        for _ in 0..<3 where element("player.video").exists {
            press(.menu)
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Menu closes the player")
        requireFocus("streams.row.0", "Menu returns to the Streams screen, on the 4:3 clip")
        press(.select)
        require("player.video", "Select on the 4:3 clip opens the player again")
        // It resumes where the first play was left, which its position reads before the player has opened.
        requirePlaying()
        pauseWithSelect()
        requireOption("videoFit", reads: "Letterbox", "in the next video")
        requireOption("zoom", reads: "100%", "in the next video")
        shotOfThePicture("next video: Letterbox")
    }

    /// The panel's Options tab on the H.264 file, paused: Shaders' choices list the presets (shot), and NVScaler, then
    /// Anime4K Fast A, picked there become the option's value and run on the same paused frame, shot each time, then
    /// Off again.
    func testShaderPresetsPickedFromTheOptionsPanel() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        requireFocus("streams.row.0", timeout: 60, "the remux stream has focus once the Streams screen lists them")
        moveFocus(to: "streams.row.1", with: .down, "Down reaches the H.264 stream")
        press(.select)
        require("player.video", "Select on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        pauseWithSelect()
        for preset in ["NVScaler", "Anime4K Fast A", "Off"] {
            pickOption("shaders", preset)
            shotOfThePicture("shaders: \(preset)")
        }
    }

    /// Select pressed again at once, as the player comes up over the Streams screen, is the player's: it pauses the
    /// video the pick opened, the controls up with focus on the timeline. The Streams screen under the player takes no
    /// press: a second pick of the stream would restart the playback, which then plays on.
    func testSelectAsThePlayerComesUpPausesIt() throws {
        try launchDev(addon: true, open: ["streams:movie:tortuga-4x3:tortuga-4x3"])
        requireFocus("streams.row.0", timeout: 60, "the 4:3 clip has focus once the Streams screen lists it")
        // Pressed as a hand presses twice, the second without waiting for the player to come up (`press` waits for
        // focus to settle).
        XCUIRemote.shared.press(.select)
        XCUIRemote.shared.press(.select)
        require("player.video", "Select on the 4:3 clip opens the player")
        let scrub = element("player.scrub")
        if !waitUntil(10, { scrub.exists && scrub.label == "Play" }) {
            fail("the second Select did not pause the player it came up in: the timeline reads \(scrub.exists ? scrub.label : "nothing")")
        }
        requireFocus("player.scrub", "the timeline has focus with the paused video's controls up")
    }

    /// The Options tab's sleep timer at 5 minutes on the paused 4:3 clip, which the dev session's fast timers
    /// (`player.fastTimers`) make 30 s: the option counts it down, and once it runs out "Still watching?" comes up as a
    /// card in up next's place with focus on Continue, counting down to a pause; Continue puts it away and starts the 5
    /// minutes again.
    func testSleepTimerAsksStillWatching() throws {
        try launchDev(addon: true, open: ["streams:movie:tortuga-4x3:tortuga-4x3"], settings: ["player.fastTimers=true"])
        requireFocus("streams.row.0", timeout: 60, "the 4:3 clip has focus once the Streams screen lists it")
        press(.select)
        require("player.video", "Select on the 4:3 clip opens the player")
        requirePlayback(1, timeout: 90)
        pauseWithSelect()
        pickOption("sleepTimer", "5 minutes", reads: "left")
        require("player.stillWatching", timeout: 90, "the sleep timer's 5 minutes run out into Still watching?")
        requireFocus("player.stillWatching.keepWatching", "Still watching? comes up with focus on Continue")
        let countdown = element("player.stillWatching.countdown")
        if !waitUntil(3, { countdown.exists && countdown.label.hasPrefix("Pausing in") }) { fail("Still watching? tells '\(countdown.label)', not its countdown") }
        require("player.stillWatching.pause", "Still watching? offers Pause")
        shot("still watching")
        press(.select)
        requireGone("player.stillWatching", timeout: 5, "Continue puts Still watching? away")
        requireOption("sleepTimer", reads: "left", "after Continue")
    }

    /// The chapters series' first episode (`SkipTestSupport`): over the intro (4–12 s) the skip button offers "Skip
    /// intro" with focus on it (shot as "skip-button"), and Select lands at the intro's end; played on into the credits
    /// (44 s), up next comes up in the button's place (shot as "credits-upnext").
    func testSkipButtonTakesFocusAndSelectSkipsTheIntroAndTheCreditsBringUpNext() throws {
        try launchDev(addon: true, open: Self.chaptersFirstEpisode)
        playFirstChaptersEpisode()
        requireSkipButton(Self.skipIntroLabel, timeout: 90, "the intro (4 s) brings the skip button up")
        requireFocus("player.skip", timeout: 5, "the skip button takes focus as it shows")
        shot("skip-button")
        let start = Date()
        let before = playerPosition() ?? 0
        press(.select)
        requireIntroSkipped(from: before, since: start, "Select on Skip intro")
        requireUpNextAtTheCredits()
    }

    /// The chapters series' second episode resumed at 40 s, 4 s before its credits (44 s to its end at 60 s, no episode
    /// after it): the credits bring up the skip button with focus, which goes by itself 8 s later. Up then brings the
    /// controls up and the button back with them, focus on the timeline, not on the button: Select there pauses rather
    /// than skips.
    func testSkipButtonBackWithTheControlsLeavesFocusOnTheTimeline() throws {
        try launchDev(
            addon: true,
            open: ["board", "detail:series:tortuga-chapters", "streams:series:tortuga-chapters:tortuga-chapters:1:2"],
            progress: ["series:tortuga-chapters:tortuga-chapters:1:2:40:60"]
        )
        requireFocus("streams.row.0", timeout: 60, "the episode's stream has focus once its Streams panel lists it")
        press(.select)
        require("player.video", timeout: 20, "Select on the stream opens the player")
        requireSkipButton("Skip credits", timeout: 60, "the credits (44 s) bring the skip button up")
        requireFocus("player.skip", timeout: 5, "the skip button takes focus as it shows")
        requireGone("player.skip", timeout: 15, "the skip button goes by itself 8 s after it came up, the controls down")
        press(.up)
        require("player.skip", timeout: 5, "the skip button comes back with the controls")
        requireFocus("player.scrub", timeout: 5, "Up brings the controls up with focus on the timeline, the skip button back beside them")
        shot("skip button back with the controls")
        let scrub = element("player.scrub")
        press(.select)
        if !waitUntil(5, { scrub.exists && scrub.label == "Play" }) {
            fail("Select on the timeline doesn't pause the episode: the timeline reads \(scrub.label)")
        }
    }

    /// Up next on the test addon's own series (three 20 s episodes; the stream in a binge group, so the next episode
    /// plays on without asking), 10 s before the first episode's end: it offers "S1E2 · Episode 2" with focus on it,
    /// read as "Play the next episode: S1E2 · Episode 2" with its seconds left as its value ("Automatically in 8
    /// seconds"), counting down (shot as "upnext-counting"). Up on it, a move the store hears, stops the countdown: the
    /// plain button stays, no seconds left in its value, the episode ends and the second doesn't follow (shot as
    /// "upnext-cancelled"); focus moved back to it by the remote, Select plays the second episode.
    func testUpNextTakesFocusAndAMoveStopsItsCountdown() throws {
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"])
        requireFocus("streams.row.0", timeout: 60, "the first episode's Streams panel focuses its first stream")
        moveFocus(to: "streams.row.1", with: .down, "Down reaches the stream in a binge group")
        press(.select)
        require("player.video", timeout: 20, "Select on the stream opens the player")
        requirePlayback(1, timeout: 90)
        require("player.upNext", timeout: 40, "the first episode's last 10 s bring up next")
        requireFocus("player.upNext", timeout: 5, "up next takes focus as it shows")
        let first = promptLabel("player.upNext")
        if !first.hasPrefix(Self.playNextEpisode + "S1E2") { fail("up next on the first episode reads '\(first)', not '\(Self.playNextEpisode)S1E2 · …'") }
        if !waitUntil(3, { promptCountdown("player.upNext").hasPrefix("Automatically in") }) {
            fail("up next counting down says '\(promptCountdown("player.upNext"))' as its value, not its seconds left ('Automatically in 8 seconds')")
        }
        // Shot as its fill grows, once the button has come up (its frame still).
        _ = settledFrame(of: element("player.upNext"))
        shot("upnext-counting")
        press(.up)
        var ended = requirePlayback(19, timeout: 20)
        // The episode's end passes (20 s) unless the second follows, which takes the position back; then up next is the
        // plain S1E2 button and the player is at the end, read once as they show there (the button is made anew as the
        // countdown turns into it, so it is briefly gone in between).
        _ = holds(for: 3) { (playerPosition() ?? ended) >= 19 }
        ended = playerPosition() ?? ended
        let label = promptLabel("player.upNext"), countdown = promptCountdown("player.upNext")
        if !label.contains("S1E2") { fail("after Up and the episode's end up next reads '\(label)', not the plain S1E2 button") }
        if !countdown.isEmpty { fail("the plain up next at the episode's end still says '\(countdown)' as its value") }
        if ended < 19 { fail("after Up on up next it still played the next episode (the player at \(ended) s)") }
        shot("upnext-cancelled")
        moveFocus(to: "player.upNext", with: .up, limit: 4, "Up from the controls at the episode's end reaches up next")
        press(.select)
        pauseOnEpisode("S1E2", "Select on up next")
    }

    /// The test addon's own series from its last episode (three 20 s episodes; the stream in a binge group, so the
    /// episode before plays from the same group without asking): the transport bar's row offers Previous episode and a
    /// disabled Next episode before the menus (shot as "last episode"); Previous, reached by the remote, plays the
    /// second episode, both enabled (shot as "middle episode"), and again the first, its Previous disabled under focus
    /// with the controls up: focus moves on to another of the controls within 5 s, not to nothing nor to the video,
    /// which takes focus only as the controls go (shot as "first episode"), and Previous stays out of focus's way: Left
    /// from Next stays there. Each paused with the controls up.
    func testEpisodeButtonsPlayThePreviousEpisode() throws {
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:3"])
        requireFocus("streams.row.0", timeout: 60, "the last episode's Streams panel focuses its first stream")
        moveFocus(to: "streams.row.1", with: .down, "Down reaches the stream in a binge group")
        press(.select)
        require("player.video", timeout: 20, "Select on the stream opens the player")
        requirePlayback(1, timeout: 90)
        // Each episode paused as it starts: before its 10 s, where up next would come up.
        pauseOnEpisode("S1E3", "the stream picked")
        requireEpisodeButtons((previous: true, next: false), "the series' last episode")
        shot("last episode")
        walkControls(to: "player.previous", "the remote reaches Previous episode")
        press(.select)
        pauseOnEpisode("S1E2", "Previous episode on the last episode")
        requireEpisodeButtons((previous: true, next: true), "the series' second episode")
        shot("middle episode")
        walkControls(to: "player.previous", "the remote reaches Previous episode")
        press(.select)
        // Previous turns off under focus as the first episode loads, which keeps the controls up.
        _ = waitUntil(60) { (try? element("player.previous").snapshot().isEnabled) == false }
        var seen: [String] = []
        let moved = waitUntil(5) {
            let id = focusedId.map { $0.isEmpty ? "an element without an identifier" : $0 } ?? "nothing"
            if seen.last != id { seen.append(id) }
            return id.hasPrefix("player.") && id != "player.previous" && id != "player.video"
        }
        if !moved { fail("Previous episode turning off under focus leaves focus on \(seen.joined(separator: ", then ")), not another of the controls") }
        print("player.previous off: focus on \(seen.joined(separator: ", then "))")
        pauseOnEpisode("S1E1", "Previous episode on the second episode")
        requireEpisodeButtons((previous: false, next: true), "the series' first episode")
        shot("first episode")
        walkControls(to: "player.next", "the remote reaches Next episode")
        press(.left)
        if focusedId == "player.previous" { fail("Left from Next episode puts focus on the disabled Previous episode") }
        if !holdsFocus("player.next") { fail("Left from Next episode moves focus to \(focusedId ?? "nothing"), past the disabled Previous episode") }
    }

    /// Settings → Playback → Skipping with Intros set to skip automatically by the remote (shot as "skip-settings"),
    /// kept for the next launch (`--dev-data`): over the chapters series' first episode's intro the skip button counts
    /// down with focus on it (shot as "skip-auto") and the intro skips without a press.
    func testAutomaticSkipFromTheSettingsSkipsTheIntro() throws {
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
    /// Thai, Devanagari, Cyrillic, Greek) and pauses for a shot of them, checked by eye: a script libass finds no font
    /// for draws boxes. The track is forced and in the audio's language, so it shows with no pick.
    func testTheScriptsClipPlaysForItsShot() throws {
        try launchDev(addon: true, open: ["streams:movie:tortuga-scripts:tortuga-scripts"])
        requireFocus("streams.row.0", timeout: 60, "the scripts clip has focus once the Streams screen lists it")
        press(.select)
        require("player.video", "Select on the scripts clip opens the player")
        requirePlayback(1, timeout: 90)
        pauseWithSelect()
        shotOfThePicture("every script: the forced SRT line")
    }

    /// The remux's French SRT line (line 2 of 6, 5–8 s) and its Spanish ASS line 3 (11–14 s), paused with the controls
    /// up: once in this device's default subtitle style, with the size then doubled while the SRT line is on the paused
    /// picture (`changeDevSetting`), and once in a changed style (`subtitleStyles`). The style restyles the SRT line
    /// and leaves the ASS line its own look. (A switch of track while paused shows the new track's line only once the
    /// player moves on, so the ASS line is the next one played into.)
    func testSubtitleStyleOnATextLineAndAStyledLine() throws {
        executionTimeAllowance = 900
        for (index, (style, settings)) in Self.subtitleStyles.enumerated() {
            let live = index == 0
            try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"], settings: ["video.hardwareDecoding=false"] + settings)
            requireFocus("streams.row.0", timeout: 60, "the remux stream has focus once the Streams screen lists them")
            press(.select)
            require("player.video", "Select on the remux stream opens the player")
            requirePlayback(1, timeout: 90)
            putAwaySkipButton()
            press(.select)
            if !waitUntil(5, { element("player.scrub").exists && element("player.scrub").label == "Play" }) {
                fail("Select did not pause the video: the timeline reads \(element("player.scrub").label)")
            }
            pick(from: "player.subtitles", label: "French", "the French subtitles")
            pauseOnSubtitleLine(lines: [5.4...7.2])
            // The paused frame redrawn with its line.
            screenSettles()
            shot("\(style): French SRT line")
            if live {
                changeDevSetting("subtitles.sizePercent=200")
                shot("\(style), size changed to 200 % while paused: French SRT line")
            }
            pick(from: "player.subtitles", label: "Spanish", "the Spanish subtitles")
            pauseOnSubtitleLine(lines: [11.4...13.2])
            screenSettles()
            shot("\(style)\(live ? ", size 200 %" : ""): Spanish ASS line")
            app.terminate()
        }
    }

    /// The test addon's subtitles movie (`tortuga-subtitles`: the stream's own English and French, and from the addon's
    /// subtitles five English, Spanish, German, Italian, Japanese and one named "Português"), paused: the subtitle tab
    /// ends with More subtitles, reading the 8 not added (shot); its page lists their languages with their counts, the
    /// tab's own language first, then by name, Other last (English 3, German, Italian, Japanese, Spanish, Other 1; shot),
    /// focus on the first. Japanese's page (shot) lists its subtitle, named by the addon (the test addon's subtitles
    /// carry no title), whose Select adds it and selects it, closing the panel: the button reads Japanese and the tab,
    /// opened again, has it checked (shot). Its line ("日本語, line N") shows once the movie plays on into one, paused
    /// there (shot).
    func testMoreSubtitlesAddTheJapaneseAndSelectIt() throws {
        try launchDev(addon: true, open: ["streams:movie:tortuga-subtitles:tortuga-subtitles"])
        requireFocus("streams.row.0", timeout: 60, "the subtitles movie's stream has focus once the Streams screen lists it")
        press(.select)
        require("player.video", "Select on the stream opens the player")
        requirePlayback(1, timeout: 90)
        pauseWithSelect()
        walkControls(to: "player.subtitles", "the remote reaches the subtitle button")
        press(.select)
        require("player.menu", timeout: 5, "Select on player.subtitles opens the panel")
        moveFocus(to: "player.menu.more", with: .down, limit: 12, "the remote reaches More subtitles")
        let more = element("player.menu.more")
        if more.value as? String != "8" { fail("More subtitles reads '\(more.value as? String ?? "nothing")', not the 8 subtitles not added") }
        shot("panel: subtitles with More subtitles")

        press(.select)
        if !waitUntil(5, { focusedId == "player.menu.item.0" }) {
            fail("More subtitles opens with focus on \(focusedId ?? "nothing"), not its first language")
        }
        var rows: [XCUIElement] = []
        while element("player.menu.item.\(rows.count)").exists { rows.append(element("player.menu.item.\(rows.count)")) }
        let languages = rows.map { "\($0.label) \($0.value as? String ?? "")" }
        let expected = ["English 3", "German 1", "Italian 1", "Japanese 1", "Spanish 1", "Other 1"]
        if languages != expected { fail("More subtitles lists \(languages), not \(expected)") }
        shot("panel: More subtitles")
        moveFocus(to: "player.menu.item.3", with: .down, "the remote reaches Japanese")
        press(.select)
        let subtitle = element("player.menu.item.0")
        if !waitUntil(5, { subtitle.exists && subtitle.label == "Tortuga Test" && holdsFocus("player.menu.item.0") }) {
            fail("Japanese opens with focus on \(focusedId ?? "nothing"), not its subtitle (\(subtitle.exists ? subtitle.label : "none"))")
        }
        shot("panel: More subtitles, Japanese")
        press(.select)
        requireGone("player.menu", timeout: 5, "picking the Japanese subtitle closes the panel")
        let button = element("player.subtitles")
        if !waitUntil(10, { button.exists && button.value as? String == "Japanese" }) {
            fail("after picking the Japanese subtitle, player.subtitles shows '\(button.value as? String ?? "nothing")'")
        }

        walkControls(to: "player.subtitles", "the remote reaches the subtitle button again")
        press(.select)
        require("player.menu", timeout: 5, "Select on player.subtitles opens the panel again")
        var tracks: [XCUIElement] = []
        while element("player.menu.item.\(tracks.count)").exists { tracks.append(element("player.menu.item.\(tracks.count)")) }
        guard let track = tracks.first(where: { $0.label == "Japanese" }) else {
            return fail("the subtitle tab lists \(tracks.map(\.label)), without the Japanese subtitle added")
        }
        if !track.isSelected { fail("the subtitle tab lists the Japanese subtitle unchecked") }
        shot("panel: subtitles with Japanese")
        press(.menu)
        requireGone("player.menu", timeout: 5, "Menu closes the panel")
        requireFocus("player.subtitles", "Menu closes the panel with focus on the subtitle button")
        pauseOnSubtitleLine(lines: [5.4...8.5, 10.4...13.5])
        // The paused frame redrawn with its line.
        screenSettles()
        shot("Japanese subtitle line")
    }

    /// The remux with its French subtitles, paused: the subtitle tab opens with focus on French, checked. A held Select
    /// there (the subtitle's own row) does nothing: the panel stays open, no row badged. On Spanish it shows Spanish as
    /// the second subtitle: the panel stays open with focus on Spanish, its row badged (VoiceOver's "Second subtitle"
    /// in its value), French still checked (shot). Held again it removes the second subtitle, and a third time shows it
    /// again; Menu then closes the panel with focus on the subtitle button, which still reads French. Both line 2s
    /// (5–8 s) then show once the movie plays on into them, paused there: the Spanish at the top, the French at the
    /// bottom (shot).
    func testSecondSubtitleShowsAtTheTopWithTheFirst() throws {
        executionTimeAllowance = 600
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"], settings: ["video.hardwareDecoding=false"])
        requireFocus("streams.row.0", timeout: 60, "the remux stream has focus once the Streams screen lists them")
        press(.select)
        require("player.video", "Select on the remux stream opens the player")
        requirePlayback(1, timeout: 90)
        putAwaySkipButton()
        pauseWithSelect()
        pick(from: "player.subtitles", label: "French", "the French subtitles")

        press(.select)
        require("player.menu", timeout: 5, "Select on player.subtitles opens the panel")
        var rows: [XCUIElement] = []
        while element("player.menu.item.\(rows.count)").exists { rows.append(element("player.menu.item.\(rows.count)")) }
        let labels = rows.map(\.label)
        guard let french = labels.firstIndex(of: "French"), let spanish = labels.firstIndex(of: "Spanish") else {
            return fail("the subtitle tab lists \(labels), without French and Spanish")
        }
        let frenchId = "player.menu.item.\(french)", spanishId = "player.menu.item.\(spanish)"
        requireFocus(frenchId, "the subtitle tab opens with focus on French, the subtitle")
        let badge = "Second subtitle"
        func badged() -> [String] {
            rows.filter { ($0.value as? String ?? "").contains(badge) }.map(\.label)
        }
        func requireHeld(on id: String, second: String?, _ what: String) {
            XCUIRemote.shared.press(.select, forDuration: 1)
            let expected = second.map { [$0] } ?? []
            if !waitUntil(5, { badged() == expected }) { fail("\(what): the rows badged as the second subtitle are \(badged()), not \(expected)") }
            require("player.menu", timeout: 2, "\(what): the panel stays open")
            requireFocus(id, "\(what): focus stays on its row")
            if !rows[french].isSelected { fail("\(what): French is no longer checked") }
        }
        requireHeld(on: frenchId, second: nil, "a held Select on French, the subtitle")

        moveFocus(to: spanishId, with: spanish > french ? .down : .up, limit: 12, "the remote reaches Spanish")
        requireHeld(on: spanishId, second: "Spanish", "a held Select on Spanish")
        shot("panel: Spanish as the second subtitle")
        requireHeld(on: spanishId, second: nil, "a held Select on Spanish, the second subtitle")
        requireHeld(on: spanishId, second: "Spanish", "a held Select on Spanish again")
        press(.menu)
        requireGone("player.menu", timeout: 5, "Menu closes the panel")
        requireFocus("player.subtitles", "Menu closes the panel with focus on the subtitle button")
        let button = element("player.subtitles")
        if button.value as? String != "French" { fail("with a second subtitle, player.subtitles shows '\(button.value as? String ?? "nothing")', not French") }

        pauseOnSubtitleLine(lines: [5.4...7.2])
        // The paused frame redrawn with its lines.
        screenSettles()
        shot("French SRT line with the second subtitle's Spanish ASS line")
    }

    /// Waits for the debug Now Playing probe (what `MPNowPlayingInfoCenter` holds) to contain `text`.
    private func requireNowPlaying(_ text: String, _ what: String) {
        let probe = element("player.nowPlaying")
        if !waitUntil(10, { probe.exists && (probe.value as? String)?.contains(text) == true }) {
            fail("\(what): Now Playing holds '\(probe.value as? String ?? "nothing")', not '\(text)'")
        }
    }

    /// Waits for the debug keep-awake probe (the app's idle timer as the playback read it back) to read `disabled`, and
    /// prints what it reads.
    private func requireIdleTimerDisabled(_ disabled: Bool, _ what: String) {
        let probe = element("player.keepAwake")
        let expected = "idleTimerDisabled=\(disabled)"
        if !waitUntil(10, { probe.exists && probe.value as? String == expected }) {
            fail("\(what): the keep-awake probe reads '\(probe.value as? String ?? "nothing")', not \(expected)")
        }
        print("player.keepAwake \(what): \(probe.value as? String ?? "nothing")")
    }

    /// Waits up to 10 s for the simulated display's counts (the debug probe `player.display`: writes, clears, switches,
    /// readyWhileSwitching) to have gone up from `before` by `change` (a count not named by none), and its criteria to
    /// ask for `rate` (millihertz) when given; prints them and returns them; fails with `what` and what the probe reads.
    @discardableResult
    private func requireDisplay(_ change: [String: Int], since before: [String: Int] = [:], rate: Int? = nil, _ what: String) -> [String: Int] {
        let probe = require("player.display", timeout: 10, "\(what): the display probe")
        var counts: [String: Int] = [:]
        let matched = waitUntil(10) {
            counts = Dictionary(uniqueKeysWithValues: (probe.value as? String ?? "").split(separator: " ").compactMap { pair in
                let parts = pair.split(separator: "=")
                return parts.count == 2 ? (String(parts[0]), Int(parts[1]) ?? -1) : nil
            })
            return ["writes", "clears", "switches", "readyWhileSwitching"].allSatisfy { name in
                counts[name].map { $0 - (before[name] ?? 0) == (change[name] ?? 0) } ?? false
            } && (rate == nil || counts["rate"] == rate)
        }
        print("player.display \(what): \(probe.value as? String ?? "nothing")")
        if !matched {
            fail("\(what): the display probe reads '\(probe.value as? String ?? "nothing")', not \(change) more than \(before)")
        }
        return counts
    }

    /// Waits up to `timeout` s, pressing nothing, for the player to be 1–5 s into a file that runs `length` (the
    /// store's position label, `playhead`: `0:03 / 0:10`): the next file playing from its start, so its start gate has
    /// opened. Fails with `what` and the value then.
    private func requireNextFilePlaying(length: String, timeout: TimeInterval, _ what: String) {
        var value = ""
        let playing = waitUntil(timeout) {
            value = playhead()
            return value.hasSuffix(" / \(length)") && Self.seconds(value).map { (1...5).contains($0) } == true
        }
        if !playing { fail("\(what): the player reads '\(value)', not 1–5 s into a \(length) file") }
    }

    /// Waits up to 60 s for the episode `code` (`S1E2`) to play, as the episode line under the title and the
    /// timeline's label tell (Up brings the controls up while they are down), then pauses it by Select on the timeline:
    /// focus settles first (a new episode's controls give it to the timeline; Down from the bar's buttons reaches it,
    /// while Down from the timeline would open the info panel, as AVKit's does). Fails with `what`.
    private func pauseOnEpisode(_ code: String, _ what: String) {
        let title = element("player.title")
        let scrub = element("player.scrub")
        let playing = waitUntil(60) {
            guard let label = try? title.snapshot().label else {
                if element("player.video").exists { press(.up) }
                return false
            }
            return label.contains(code) && (try? scrub.snapshot().label).map { $0 != "Play" } == true
        }
        if !playing { fail("\(what): the player's title reads '\(title.exists ? title.label : "nothing")', not \(code) playing") }
        // The episode opens before it plays: its controls are rebuilt meanwhile, so focus settles once it moves.
        let start = playerPosition() ?? 0
        if !waitUntil(30, { (playerPosition() ?? 0) > start }) { fail("\(what): \(code) doesn't start playing") }
        let buttons = ["player.previous", "player.next", "player.speed", "player.more", "player.subtitles", "player.audio"]
        _ = waitUntil(5) {
            if !scrub.exists { press(.up) }
            return scrub.hasFocus || buttons.contains(focusedId ?? "")
        }
        if !scrub.hasFocus, buttons.contains(focusedId ?? "") { press(.down) }
        // The element tree while the controls are still up, for the report when XCUITest sees no focus on the timeline:
        // `requireFocus` gives up only after they have gone.
        if !waitUntil(2, { scrub.hasFocus }) {
            let tree = XCTAttachment(string: app.debugDescription)
            tree.name = "element tree after down"
            tree.lifetime = .keepAlways
            add(tree)
        }
        requireFocus("player.scrub", timeout: 5, "\(what): the timeline has focus")
        press(.select)
        if !waitUntil(5, { (try? scrub.snapshot().label) == "Play" }) { fail("\(what): Select on the timeline doesn't pause \(code)") }
    }

    /// Plays (Select on the timeline, reached by Down from the subtitle button, where a subtitle menu closing leaves
    /// focus) until a bottom line of the remux's subtitles is on screen (lines at 1–4, 5–8, 11–14, 21–24 and 25–28 s;
    /// the one at 15–18 s is a sign at the top, `{\an8}`), one of `lines`, then pauses there: Select on the timeline, or
    /// on the video once the controls have gone.
    private func pauseOnSubtitleLine(lines: [ClosedRange<Double>] = [5.4...7.2, 11.4...13.2, 21.4...23.2]) {
        press(.down)
        requireFocus("player.scrub", "Down from the subtitle button reaches the timeline")
        press(.select)
        if !waitUntil(5, { !element("player.scrub").exists || element("player.scrub").label != "Play" }) {
            fail("Select on the timeline doesn't play again")
        }
        if !waitUntil(30, { playerPosition().map { position in lines.contains { $0.contains(position) } } ?? false }) {
            fail("the player never reached a subtitle line (at \(playerPosition() ?? 0) s)")
        }
        // Select pauses on the timeline while the controls are up, on the video once they have gone.
        press(.select)
        if !waitUntil(5, { element("player.scrub").exists && element("player.scrub").label == "Play" }) {
            fail("Select did not pause the video: the timeline reads \(element("player.scrub").label)")
        }
        requireFocus("player.scrub", "the timeline has focus once the controls are up")
    }

    /// Opens the panel on `button`'s tab, moves focus to the entry labelled `label` and selects it; the entry's label,
    /// which the button's value then shows.
    @discardableResult
    private func pick(from button: String, label: String, _ what: String) -> String {
        walkControls(to: button, "the remote reaches \(button)")
        press(.select)
        require("player.menu", timeout: 5, "Select on \(button) opens the panel")
        if !waitUntil(5, { focusedId?.hasPrefix("player.menu.item.") == true }) {
            fail("the \(button) panel opened with focus on \(focusedId ?? "nothing"), not its selected entry")
        }
        shot("\(button) panel")
        var items: [XCUIElement] = []
        while element("player.menu.item.\(items.count)").exists { items.append(element("player.menu.item.\(items.count)")) }
        guard let target = items.lastIndex(where: { $0.label == label }) else {
            fail("the \(button) panel has no entry for \(what): \(items.map(\.label))")
            return ""
        }
        let focusedIndex = focusedId.flatMap { Int($0.dropFirst("player.menu.item.".count)) } ?? 0
        let id = "player.menu.item.\(target)"
        moveFocus(to: id, with: target > focusedIndex ? .down : .up, limit: 12, "the remote reaches \(what)")
        let picked = element(id).label
        press(.select)
        requireGone("player.menu", timeout: 5, "picking \(what) closes the panel")
        requireFocus(button, "picking \(what) closes the panel with focus on \(button)")
        if !waitUntil(5, { element(button).exists && element(button).value as? String == picked }) {
            fail("after picking '\(picked)', \(button) shows '\(element(button).value as? String ?? "nothing")'")
        }
        return picked
    }

    /// Select on the playing video pauses it, the controls up with focus on the timeline.
    private func pauseWithSelect() {
        press(.select)
        if !waitUntil(5, { element("player.scrub").exists && element("player.scrub").label == "Play" }) {
            fail("Select did not pause the video: the timeline reads \(element("player.scrub").label)")
        }
        requireFocus("player.scrub", "the timeline has focus once the controls are up")
    }

    /// Opens the panel on its Options tab by the options button, focus on Playback info.
    private func openOptions() {
        walkControls(to: "player.more", "the remote reaches the options button")
        press(.select)
        require("player.menu", timeout: 5, "Select on the options button opens the panel")
        requireFocus("player.more.info", "the options open with focus on Playback info")
    }

    /// Opens the options and the choices of the option `id` (`videoFit`, `zoom`), focus on its selected choice.
    private func openOption(_ id: String) {
        openOptions()
        moveFocus(to: "player.option.\(id)", with: .down, limit: 10, "the remote reaches the option \(id)")
        press(.select)
        if !waitUntil(5, { focusedId?.hasPrefix("player.menu.item.") == true }) {
            fail("the option \(id)'s choices opened with focus on \(focusedId ?? "nothing"), not the selected choice")
        }
    }

    /// Picks the choice labelled `label` of the option `id`, which closes the panel; the option then reads `value`
    /// (else the label).
    private func pickOption(_ id: String, _ label: String, reads value: String? = nil) {
        openOption(id)
        shot("panel: \(id) choices")
        var items: [XCUIElement] = []
        while element("player.menu.item.\(items.count)").exists { items.append(element("player.menu.item.\(items.count)")) }
        guard let target = items.firstIndex(where: { $0.label == label }) else {
            return fail("the option \(id) has no choice \(label): \(items.map(\.label))")
        }
        let focusedIndex = focusedId.flatMap { Int($0.dropFirst("player.menu.item.".count)) } ?? 0
        moveFocus(to: "player.menu.item.\(target)", with: target > focusedIndex ? .down : .up, limit: 12, "the remote reaches \(label)")
        press(.select)
        requireGone("player.menu", timeout: 5, "picking \(label) closes the panel")
        requireFocus("player.more", "picking \(label) closes the panel with focus on the options button")
        requireOption(id, reads: value ?? label, "after picking \(label)")
    }

    /// Fails with `what` unless the option `id` reads `value` (or a value with it, `4:59 left` for `left`) on the Options
    /// tab; the tab is shot, then closed by Menu.
    private func requireOption(_ id: String, reads value: String, _ what: String) {
        openOptions()
        let option = element("player.option.\(id)")
        if !waitUntil(3, { option.exists && (option.value as? String)?.contains(value) == true }) {
            fail("\(what): the option \(id) reads '\(option.value as? String ?? "nothing")', not \(value)")
        }
        shot("panel: \(id) reads \(value)")
        press(.menu)
        requireGone("player.menu", timeout: 5, "Menu on the options closes the panel")
    }

    /// The screen as the paused picture shows in the current fit and zoom, once it has been drawn again.
    private func shotOfThePicture(_ name: String) {
        screenSettles()
        shot(name)
    }
}
