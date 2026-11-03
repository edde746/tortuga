import AVKit
import XCTest

/// The player by touch alone (the tests the Mac has too are in Pointer/PlayerTests.swift), on the test addon's streams
/// for a Cinemeta movie (tools/media/test_addon.py): the remux (HEVC HDR10 with three audio tracks, five subtitle
/// tracks and chapters) through its native subtitle, audio and speed menus, with no software keyboard coming up with
/// them, the subtitle delay's popover, the info sheet from the options menu (hardware decoding switched off: the decoder
/// row reads software) and a seek, then a tap beside the open menu closing it before the close button leaves the
/// player; the H.264 file's Now Playing info, double taps on the right and the left of the video seeking forward and
/// back, and playback going on in the background (picture in picture, background audio). Picture in picture starts from
/// the controls' button (its window over the player and the home screen, the window's pause, skip and play, Restore
/// fullscreen), ends by the button, pauses the video when its window is closed, starts with Home and ends with the
/// player; while it runs the player keeps close and the PiP button alone, a tap on the video bringing none of the rest
/// back (the iPhone simulators have no PiP: there the button stays disabled and the rest is skipped). The remux runs
/// in landscape too, and in landscape from the start, as a phone turned for the video, none of the native menus (the
/// options menu among them) brings the software keyboard up. The 4:3 clip in landscape through the options menu: each
/// fit (the first from this device's setting) and a zoom, shot with its subtitle line, a pinch zooming the picture, and
/// the fit carried over to the next video; a movie's controls offer no episode buttons.
/// On the test addon's own series up next counts down and plays the next episode by itself; a tap on the video stops
/// that, the plain button staying past the episode's end beside the controls' Next episode, clear of the controls in
/// portrait and in landscape, and a tap on it plays the next episode. From the series' last episode Previous episode
/// plays the one before, each in turn, both episode buttons around back, play/pause and forward, each disabled at its
/// end of the series, in portrait and in landscape. Over the intro of the test addon's chapters series' first episode
/// the skip button sits clear of the controls brought up over it and skips it by a tap, and the credits bring up next
/// (`SkipTestSupport`). The remux's French SRT line moves up for the controls only where they cover the picture.
/// On the test addon's slow stream the loading text lies clear of the bars and the transport with the controls up, in
/// portrait and in landscape; the error for the test addon's missing file takes the middle alone, without the
/// transport and the bottom bar and clear of the top bar, in both orientations; over a bright and a dark still frame
/// the controls' symbols and position (and, upright, the title) stand out from what is behind them by at least 3:1;
/// at the largest text size the controls' text grows no further than the first accessibility size, the position
/// fitting the timeline's 44 pt row, and the system's audit finds no text clipped or keeping its size.
/// While the H.264 file plays the app's idle timer is off (the debug probe `player.keepAwake`), and on again once it is
/// paused. The H.264 file pauses with the controls up when the headphones go away (an audio route change posted by the
/// DEBUG link `tortuga://dev-route-change`), not when headphones come. The live test addon's 24/7 channel plays live
/// (`LiveTestSupport`): Now Playing a live stream, the live label in place of the timeline, double taps seeking nothing,
/// and nothing kept once it closed (no Resume, no Continue Watching). Swipes up and down the video change the brightness
/// and the volume, showing their levels, a finger held on it plays at 2× until it lifts, and taps and double taps still
/// act after them (`testSwipesSetBrightnessAndVolumeAndAHoldPlaysAt2x`). The screen lock takes every touch until it is
/// unlocked: swipes, taps, double taps and a pinch do nothing, and a tap on the armed unlock button unlocks
/// (`testScreenLockIgnoresTouchesUntilUnlocked`).
/// Back in the app from Home and from Notification Center while the remux plays (mpv decodes no video meanwhile), its
/// picture moves on at once: within a second of the return its frame counter reads twice, half a second apart, and
/// advances, where the video waiting out the audio queued meanwhile froze it for about 3 s
/// (`testPictureMovesOnAtOnceBackFromHomeAndNotificationCenter`).
/// On iPhone the player opens in landscape, held upright or turned either way, and closed gives the upright screen back;
/// a video taller than wide (the test addon's portrait movie) follows the device instead (skipped on iPad); the iPad's
/// player opens as the iPad is held. Where a test speaks of portrait and landscape, the iPhone's player shows a wide
/// video in landscape alone (`playerOrientations`).
/// In Arabic, the first episode of the test addon's series with Latin episode titles that end in punctuation plays with
/// its episode line holding "What If...?" whole inside isolates (`testTheEpisodeLineKeepsALatinTitleWholeInArabic`).
/// Last (XCTest runs a class's tests in name order), the Playback menu's items are enabled while the player is up, its
/// play/pause pausing on a typed Space, a hardware keyboard's Space pauses and K plays again, and K on a
/// Russian layout (typing "л") pauses as the key in its place on a US keyboard: once a hardware key has been typed the
/// simulator counts a hardware keyboard as attached and brings no software keyboard up any more, so a test before them
/// can see one come up and one after them couldn't.
extension PlayerTests {
    func testRemuxMenusInfoSeekAndTapClosesTheMenuFirst() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"], settings: ["video.hardwareDecoding=false"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the test addon's streams")).tap()
        let video = require("player.video", "a tap on the remux stream opens the player")
        requirePlayback(2, timeout: 90)

        // Paused, the French subtitles picked; then paused again on one of their lines with the controls up (where the
        // line goes for the controls: testSubtitlesMoveOnlyForControlsOverThePicture).
        pauseWithTheControls(video, "play/pause did not pause the video")
        requireNowPlaying("rate=0.0", "Now Playing follows the pause: no rate")
        shot("paused")
        requireEpisodeButtons(nil, "a movie's controls")
        requireTransportInOrder("a movie's controls")
        pick(from: "player.subtitles", entry: "French", video: video, "the French subtitles")
        shot("subtitles: French")
        pauseOnSubtitleLine(video)
        shot("paused, controls up over a subtitle")

        // The subtitle menu's delay entry opens the delay's popover; + moves the delay (Reset turns on), and a tap
        // beside the popover closes it.
        ready("player.subtitles").tap()
        pickMenuEntry(menuEntry("Subtitle delay", "the subtitle menu ends with the delay entry"))
        require("player.delay", timeout: 5, "the delay entry opens the subtitle delay")
        let reset = require("player.delay.reset", timeout: 5, "the subtitle delay has Reset")
        if reset.isEnabled { fail("the subtitle delay offers Reset before it was moved") }
        ready("player.delay-Increment").tap()
        if !waitUntil(5, { reset.isEnabled }) { fail("+ didn't move the subtitle delay: Reset stays off") }
        shot("subtitle delay")
        video.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.5)).tap()
        requireGone("player.delay", timeout: 5, "a tap beside the popover closes the subtitle delay")

        pick(from: "player.audio", entry: "German", video: video, "the German audio")
        shot("audio: German")
        pick(from: "player.speed", entry: "1.5×", video: video, "the 1.5× speed")
        shot("speed 1.5×")

        showControls(video)
        togglePlaybackInfo()
        require("player.info.panel", timeout: 5, "Playback info in the options menu opens the playback info")
        // In the Video section, below the half-height sheet's fold.
        let decoder = revealInPlayerSheet(infoRow("Decoder"), panel: "player.info.panel", "the decoder row")
        if !waitUntil(5, { decoder.exists && fullText(of: decoder).contains("Software") }) {
            fail("with hardware decoding off, the decoder row reads '\(decoder.exists ? fullText(of: decoder) : "nothing")', not Software")
        }
        shot("info")
        // An iPhone's half-height sheet has the grabber alone, an iPad's form sheet Close.
        if !isPad, element("player.info.close").exists { fail("the iPhone's info sheet shows Close besides its grabber") }
        closeSheet("player.info.close", showing: "player.info.panel", "Close (an iPhone: a pull down) closes the playback info")

        let before = playerPosition() ?? 0
        showControls(video)
        ready("player.fastForward").tap()
        if !waitUntil(5, { (playerPosition() ?? 0) >= before + 8 }) {
            fail("fast forward from \(before) s reached \(playerPosition() ?? 0) s; expected ≥ \(before + 8) s")
        }
        shot("after fast forward")

        // Landscape: the same controls along the long side; a menu opens there too.
        turn(to: .landscapeLeft)
        showControls(video)
        screenShot("landscape")
        ready("player.subtitles").tap()
        let french = menuEntry("French", "the subtitle menu opens in landscape")
        screenShot("landscape: subtitle menu")
        requireNoKeyboard("the subtitle menu in landscape")
        video.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.5)).tap()
        if !waitUntil(5, { !french.exists }) { fail("a tap beside the open menu didn't close it") }
        let back = require("player.back", timeout: 5, "the controls stay up once the menu closes (paused)")
        screenShot("landscape: menu closed")
        attachKeyboardProbe("portrait, then landscape")
        ready(back).tap()
        XCUIDevice.shared.orientation = .portrait
        requireGone("player.video", "the close button closes the player")
        require("streams.row.0", "the close button returns to the Streams screen")
        shot("back on streams")
    }

    /// The screenshot tour's way in, on a device that brings the software keyboard up (no hardware keyboard attached)
    /// and has had it up before: the Board, the movie's details, Play, its Streams sheet, the phone turned to landscape,
    /// the remux, paused; then the subtitle menu (French picked), the audio menu and the options menu (each closed by a
    /// tap beside it) and the speed menu (1.5× picked) in turn, none of them, nor the controls after them, bringing the
    /// software keyboard up.
    func testNoSoftwareKeyboardWithTheMenusInLandscape() throws {
        // Two launches and a landscape player: longer than the default 5 minutes on iPad.
        executionTimeAllowance = 600
        // The device has had the software keyboard up before, as in the tour (its account's text fields; here Search's
        // field): a keyboard asked for later slides in at once instead of a fresh device's first-use card. Its coming
        // up shows that no hardware keyboard keeps it away, so the player's menus would bring it up too.
        try launchDev(open: ["search"])
        let field = require("search.field", timeout: 20, "Search shows its field")
        ready(field).tap()
        if !app.keyboards.firstMatch.exists(within: 10) {
            fail("the software keyboard doesn't come up for Search's field (a hardware keyboard attached?): the test can't see one")
        }
        field.typeText("matrix")
        app.terminate()

        try launchDev(addon: true, open: ["board", "detail:movie:tt0111161"])
        ready(require("detail.play", timeout: 60, "the movie's details show Play")).tap()
        let row = require("streams.row.0", timeout: 60, "Play opens the Streams sheet with the test addon's streams")
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        turn(to: .landscapeLeft)
        ready(row).tap()
        let video = require("player.video", timeout: 20, "a tap on the remux stream opens the player")
        requirePlayback(1, timeout: 90)
        // Paused at once, as the tour has it, so the 30 s remux doesn't end meanwhile.
        pauseWithTheControls(video, "play/pause doesn't pause in landscape")
        requireNoKeyboard("the player opened in landscape")
        pick(from: "player.subtitles", entry: "French", video: video, "the French subtitles", landscape: true)
        showControls(video)
        ready("player.audio").tap()
        let german = menuEntry("German", "the audio menu opens in landscape")
        screenShot("landscape: audio menu")
        requireNoKeyboard("the audio menu in landscape, after the subtitle menu")
        video.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.5)).tap()
        if !waitUntil(5, { !german.exists }) { fail("a tap beside the audio menu didn't close it") }
        showControls(video)
        ready("player.more").tap()
        let fit = menuEntry("Aspect ratio", "the options menu opens in landscape")
        screenShot("landscape: options menu")
        requireNoKeyboard("the options menu in landscape, after the audio menu")
        video.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.5)).tap()
        if !waitUntil(5, { !fit.exists }) { fail("a tap beside the options menu didn't close it") }
        pick(from: "player.speed", entry: "1.5×", video: video, "the 1.5× speed", landscape: true)
        showControls(video)
        requireNoKeyboard("the controls after the menus in landscape")
        screenShot("landscape: after the menus")
        attachKeyboardProbe("landscape from the start")
    }

    /// The options menu (`player.more`) on the 4:3 clip in landscape, its English SRT line on, paused with the controls
    /// up: the fit this device's setting gives (`video.fit=cover`: Fill screen), then Letterbox and Stretch from the
    /// menu's Aspect ratio, 150 % from its Zoom over Stretch and over Letterbox, then Reset; the screen shot in each, the
    /// line on it. Playing with the controls gone, a pinch zooms the picture: the zoom message shows and the menu's
    /// Zoom reads it. Back in the Streams screen and into the clip again, the fit carries over and the zoom doesn't.
    /// (Named to run after `testNoSoftwareKeyboardWithTheMenusInLandscape`, so its own check for the keyboard counts.)
    func testOptionsMenuFitZoomAndPinchInLandscape() throws {
        // Two plays of the clip in landscape and many menus: longer than the default 5 minutes on a slow simulator.
        executionTimeAllowance = 600
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        try launchDev(addon: true, open: ["streams:movie:tortuga-4x3:tortuga-4x3"], settings: ["video.fit=cover"])
        XCUIDevice.shared.orientation = .landscapeLeft
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the 4:3 clip")).tap()
        let video = require("player.video", timeout: 20, "a tap on the 4:3 clip opens the player")
        requirePlayback(1, timeout: 90)
        pauseWithTheControls(video, "play/pause doesn't pause the 4:3 clip")
        requireEpisodeButtons(nil, "a movie's controls in landscape")
        requireTransportInOrder("a movie's controls in landscape")
        shotOfThePicture(video, "movie controls in landscape")
        pick(from: "player.subtitles", entry: "English", video: video, "the English subtitles", landscape: true)
        // A track picked while paused shows its line once the player seeks.
        showControls(video)
        ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 7.5 / 20, dy: 0.5)).tap()
        requireOption("Aspect ratio", reads: "Fill screen", video: video, "with the device setting video.fit=cover")
        requireNoKeyboard("the options menu")
        shotOfThePicture(video, "fit: Fill screen (device setting)")
        for fit in ["Letterbox", "Stretch"] {
            pickOption("Aspect ratio", fit, video: video)
            shotOfThePicture(video, "fit: \(fit)")
        }
        // A landscape iPhone's menu shows the zoom's first choices only, out of XCUITest's reach below them.
        pickOption("Zoom", "75%", video: video)
        shotOfThePicture(video, "zoom 75%, Stretch")
        pickOption("Aspect ratio", "Letterbox", video: video)
        shotOfThePicture(video, "zoom 75%, Letterbox")
        pickOption("Zoom", "Reset", reads: "100%", video: video)
        shotOfThePicture(video, "zoom reset, Letterbox")

        // A pinch on the video, the controls gone (they would take the fingers): the zoom follows it, and its message
        // shows as it ends. From near the 20 s clip's start, so it doesn't reach its end meanwhile.
        showControls(video)
        ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 1 / 20, dy: 0.5)).tap()
        if !waitUntil(10, { (playerPosition() ?? .infinity) < 3 }) { fail("a tap on the timeline at 1 s left the clip at \(playerPosition() ?? 0) s") }
        ready("player.playPause").tap()
        requireGone("player.playPause", timeout: 10, "the controls go once the clip plays")
        ready(video).pinch(withScale: 1.6, velocity: 2)
        // The message shows for 1.5 s from the pinch's end (`PlayerConfig.osdMillis`): its label is read once, as it
        // shows.
        let message = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Zoom '")).firstMatch
        var label: String?
        _ = waitUntil(3) {
            label = try? message.snapshot().label
            return label != nil
        }
        guard let label else { return fail("a pinch on the video shows no zoom message") }
        let zoom = label.dropFirst("Zoom ".count)
        screenShot("pinch: \(label)")
        if zoom == "100%" { fail("a pinch on the video left the zoom at 100%") }
        // Paused at once, the clip perhaps buffering: play/pause's spinner then (`tapPlayerControl`).
        pauseWithTheControls(video, "play/pause doesn't pause the clip after the pinch")
        requireOption("Zoom", reads: String(zoom), video: video, "after the pinch")

        // The next video: the fit stays Letterbox, the zoom is back at 100%.
        ready(require("player.back", "the paused controls offer close")).tap()
        ready(require("streams.row.0", timeout: 20, "the close button returns to the Streams screen")).tap()
        // It resumes where the first play was left, which its position reads before the player has opened.
        requirePlaying()
        pauseWithTheControls(video, "play/pause doesn't pause the clip played again")
        requireOption("Aspect ratio", reads: "Letterbox", video: video, "in the next video")
        requireOption("Zoom", reads: "100%", video: video, "in the next video")
        shotOfThePicture(video, "next video: Letterbox")
    }

    /// The loading text ("Resolving stream…", "Preparing stream…", "Buffering…") with the controls, on the test addon's
    /// slow stream (tortuga-slow-play: h264-sdr.mkv at 4 KiB/s, so the player stays loading with its controls up): in
    /// each orientation the player shows it in (`playerOrientations`) it lies clear of the top bar's buttons, the
    /// transport and the bottom bar. Shot in each.
    func testLoadingTextClearOfTheControlsInBothOrientations() throws {
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        try launchDev(addon: true, open: ["streams:movie:tortuga-slow-play:tortuga-slow-play"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the slow stream")).tap()
        require("player.video", "a tap on the slow stream opens the player")
        for (name, orientation) in playerOrientations {
            turn(to: orientation)
            let text = require("player.loading", timeout: 10, "\(name): the player shows its loading text")
            require("player.transport", timeout: 5, "\(name): the controls are up while the stream loads")
            screenShot("loading text with the controls in \(name)")
            requireClearOfTheControls(text, "\(name): the loading text")
        }
    }

    /// Play/pause while the stream loads, its spinner in the symbol's place (the test addon's slow stream, as above):
    /// the button still acts. Tapped at its centre, where a finger lands, it pauses (it reads Play), then plays again
    /// (Pause), the stream loading all the while.
    func testPlayPauseActsWhileTheStreamLoads() throws {
        try launchDev(addon: true, open: ["streams:movie:tortuga-slow-play:tortuga-slow-play"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the slow stream")).tap()
        require("player.video", "a tap on the slow stream opens the player")
        require("player.loading", timeout: 10, "the player shows its loading text")
        let playPause = require("player.playPause", timeout: 5, "the controls are up while the stream loads")
        screenShot("play/pause's spinner while the stream loads")
        // At its centre, as `tapPlayerControl` taps (whose look at the controls waits for the stream to load):
        // XCUITest's own tap aims beside the spinner, outside the button.
        let frame = playPause.frame
        let centre = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX, dy: frame.midY))
        for (label, action) in [("Play", "pause"), ("Pause", "play")] {
            centre.tap()
            if !waitUntil(5, { playPause.exists && playPause.label == label }) {
                fail("a tap on play/pause's spinner doesn't \(action) the loading stream: it reads \(playPause.exists ? playPause.label : "nothing")")
            }
        }
        require("player.loading", "the stream still loads after the taps")
    }

    /// The controls over the test addon's still frames (tortuga-frames: an overcast sky's whites, then near black),
    /// each from a launch of its own: the frame ends at once, paused with the controls up. Held as the player first
    /// shows (an iPad upright; an iPhone turned to the landscape its player opens in) the close and play/pause symbols
    /// and the position stand out from what is behind them by at least 3:1 (WCAG's contrast for symbols and large text),
    /// measured on the screen (`ScreenPixels.contrast`, whose screenshot turns with the device), and so does the title
    /// upright, where it sits above the picture; in landscape it lies on the picture just above the scrim's bottom fifth,
    /// at 1.22:1 over the white frame. The values go with the run. Shot in each orientation the player shows it in.
    /// With Increase Contrast on, the
    /// position stands out by at least 4.5:1, WCAG's contrast for text (`requireLegibleWithIncreaseContrast`).
    func testControlsLegibleOverABrightAndADarkFrame() throws {
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        addTeardownBlock { self.increaseContrast(false) }
        for (row, frame) in ["bright", "dark"].enumerated() {
            try launchDev(addon: true, open: ["streams:movie:tortuga-frames:tortuga-frames"])
            ready(require("streams.row.\(row)", timeout: 60, "the Streams screen lists the \(frame) frame")).tap()
            require("player.video", "a tap on the \(frame) frame opens the player")
            require("player.transport", timeout: 30, "the controls stay up over the \(frame) frame")
            for (index, (name, orientation)) in playerOrientations.enumerated() {
                turn(to: orientation)
                // The glass settles over the frame.
                screenSettles(XCUIScreen.main)
                screenShot("controls over the \(frame) frame\(name == "landscape" ? " in landscape" : "")")
                if index == 0 {
                    requireLegible(over: frame)
                    requireLegibleWithIncreaseContrast(over: frame)
                }
            }
            XCUIDevice.shared.orientation = .portrait
            app.terminate()
        }
    }

    /// Over the test addon's dark still frame (the controls stay up over it) at the default text size, then at the
    /// largest (accessibility-extra-extra-extra-large), each shot: the controls' text grows to the first accessibility
    /// size and no further, so at the largest the position is taller than at the default and at most the timeline's
    /// 44 pt row, and the system's audit (`textAuditIssues`, the controls keeping their size past it) finds no text
    /// clipped or cut short.
    func testControlsReadWholeAtTheLargestTextSize() throws {
        var heights: [CGFloat] = []
        for (textSize, name) in [(nil, "default text"), (Self.largestTextSize, "largest text")] as [(String?, String)] {
            try launchDev(addon: true, open: ["streams:movie:tortuga-frames:tortuga-frames"], textSize: textSize)
            ready(require("streams.row.1", timeout: 60, "\(name): the Streams screen lists the dark frame")).tap()
            require("player.video", "\(name): a tap on the dark frame opens the player")
            require("player.transport", timeout: 30, "\(name): the controls stay up over the dark frame")
            let position = require("player.position", timeout: 10, "\(name): the controls show the position")
            screenSettles(XCUIScreen.main)
            shot("controls, \(name)")
            heights.append(position.frame.height)
        }
        print("player, the position: \(heights[0]) pt tall at the default text size, \(heights[1]) pt at the largest")
        var issues = textAuditIssues("the controls at the largest text size", keepingItsSize: ["player."])
        if heights[1] > 44 { issues.append("at the largest text size the position is \(heights[1]) pt tall, past the timeline's 44 pt row") }
        if heights[1] <= heights[0] { issues.append("the position is \(heights[1]) pt tall at the largest text size, \(heights[0]) pt at the default: it doesn't grow") }
        if !issues.isEmpty { fail(issues.joined(separator: "; ")) }
    }

    /// The test addon's missing file (tortuga-missing: the media server answers 404): the player's error takes the
    /// middle alone, in each orientation the player shows it in (`playerOrientations`): no transport, no bottom bar, no
    /// menus and no picture in picture show with it, close alone stays in the top bar, and the error lies clear of the
    /// top bar, full screen (`requireErrorFillsScreen`), its buttons side by side. Shot in each orientation.
    func testErrorTakesTheMiddleAloneInBothOrientations() throws {
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        try launchDev(addon: true, open: ["streams:movie:tortuga-missing:tortuga-missing"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the missing file")).tap()
        for (name, orientation) in playerOrientations {
            turn(to: orientation)
            let error = require("player.error", timeout: 60, "\(name): the missing file brings up the player's error")
            let bar = require("player.bar.top", timeout: 5, "\(name): the top bar stays up with the error")
            screenShot("error in \(name)")
            let behind = ["player.transport", "player.bar.bottom", "player.scrub", "player.pip", "player.audio", "player.subtitles",
                          "player.speed", "player.more"].filter { element($0).exists }
            if !behind.isEmpty { fail("\(name): \(behind.joined(separator: ", ")) show with the error") }
            if !element("player.back").exists { fail("\(name): close went with the error") }
            if error.frame.intersects(bar.frame) { fail("\(name): the error (\(error.frame)) meets the top bar (\(bar.frame))") }
            let buttons = errorButtons
            if Set(buttons.map { $0.frame.midY.rounded() }).count > 1 {
                fail("\(name): the error's buttons aren't side by side: \(buttons.map(\.frame))")
            }
            requireErrorFillsScreen(name)
        }
    }

    /// An iPhone's full-screen error in both orientations: the test addon's portrait series (tortuga-portrait-series)
    /// plays its first episode, taller than wide, with the phone upright, so the player is upright and follows the
    /// device; up next plays the second, whose binge-group file is missing, and its full-screen error offers the next
    /// stream. Upright: close alone in the top bar and clear of the error, the error within the screen and full screen
    /// (`requireErrorFillsScreen`: the last frame of the first episode doesn't show beside it), its three buttons one
    /// above the other, each as wide as the others. Turned to landscape: the same, the buttons side by side. Shot in
    /// each.
    func testErrorFillsScreenInBothOrientations() throws {
        try XCTSkipIf(isPad, "an upright iPad is wide: its error's buttons stay side by side (testErrorTakesTheMiddleAloneInBothOrientations)")
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        XCUIDevice.shared.orientation = .portrait
        try launchDev(addon: true, open: ["streams:series:tortuga-portrait-series:tortuga-portrait-series:1:1"])
        ready(require("streams.row.0", timeout: 60, "the first episode's Streams sheet lists its portrait stream")).tap()
        require("player.video", timeout: 20, "a tap on the portrait stream opens the player")
        requirePlayback(1, timeout: 90)
        requireUpright(true, "the player over the portrait episode, the phone upright")
        require("player.error.next", timeout: 60, "up next's second episode, its file missing, brings up the error with Try next stream")
        for (name, orientation) in [("upright", UIDeviceOrientation.portrait), ("landscape", .landscapeLeft)] {
            turn(to: orientation)
            requireUpright(name == "upright", "the second episode's error, the phone \(name)")
            let error = require("player.error", timeout: 5, "\(name): the player's error shows")
            let bar = require("player.bar.top", timeout: 5, "\(name): the top bar stays up with the error")
            screenShot("full-screen error \(name)")
            if !element("player.back").exists { fail("\(name): close went with the error") }
            if error.frame.intersects(bar.frame) { fail("\(name): the error (\(error.frame)) meets the top bar (\(bar.frame))") }
            if !app.frame.contains(error.frame) { fail("\(name): the error (\(error.frame)) runs off the screen (\(app.frame))") }
            let frames = ["player.error.retry", "player.error.next", "player.error.close"].map { element($0) }.filter(\.exists).map(\.frame)
            if frames.count != 3 { fail("\(name): the error shows \(frames.count) of its three buttons"); continue }
            let stacked = Set(frames.map { $0.minX.rounded() }).count == 1 && Set(frames.map { $0.width.rounded() }).count == 1
                && !zip(frames, frames.dropFirst()).contains(where: { $0.maxY > $1.minY })
            let row = Set(frames.map { $0.midY.rounded() }).count == 1
            if name == "upright" ? !stacked : !row {
                fail("\(name): the error's buttons aren't \(name == "upright" ? "stacked as wide as one another" : "side by side"): \(frames)")
            }
            requireErrorFillsScreen(name)
        }
    }

    /// The player's orientation on a phone over the test addon's 4:3 movie (a picture wider than tall),
    /// the device held upright: the Streams screen is upright (the app's frame taller than wide); on iPhone the player
    /// opens in landscape and stays there with the phone upright and turned to either side, and closed it gives the
    /// upright Streams screen back. The iPad's player opens upright, as the iPad is held. The screen's own shots of each.
    func testPlayerOpensInLandscapeAndBackGivesPortraitBack() throws {
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        XCUIDevice.shared.orientation = .portrait
        try launchDev(addon: true, open: ["streams:movie:tortuga-4x3:tortuga-4x3"])
        let row = ready(require("streams.row.0", timeout: 60, "the Streams screen lists the 4:3 clip"))
        requireUpright(true, "the Streams screen, the device upright")
        screenShot("upright: streams before the player")
        row.tap()
        let video = require("player.video", timeout: 20, "a tap on the 4:3 clip opens the player")
        requirePlayback(1, timeout: 90)
        if isPad {
            requireUpright(true, "the iPad's player, the iPad upright")
            screenShot("upright: the iPad's player")
        } else {
            requireUpright(false, "the iPhone's player as it opens, the phone upright")
            screenShot("landscape: the player, the phone upright")
            for (name, orientation) in [("turned left", UIDeviceOrientation.landscapeLeft), ("turned right", .landscapeRight),
                                        ("upright again", .portrait)] {
                XCUIDevice.shared.orientation = orientation
                // The player keeps to landscape through the turn.
                if !holds(for: 1.5, { app.frame.width > app.frame.height }) {
                    fail("the iPhone's player, the phone \(name): the app's frame is \(app.frame), not in landscape")
                }
                screenShot("landscape: the player, the phone \(name)")
            }
        }
        showControls(video)
        ready("player.back").tap()
        requireGone("player.video", "the close button closes the player")
        require("streams.row.0", "the close button returns to the Streams screen")
        requireUpright(true, "the Streams screen after the player")
        screenShot("upright: streams after the player")
    }

    /// A video taller than wide (the test addon's portrait movie, tortuga-portrait: 720x1280, as a phone films upright)
    /// on iPhone: the player releases its landscape lock and follows the device: upright with the phone upright,
    /// landscape with it turned, upright again with it upright; closed, the Streams screen is upright. Shot in each. The
    /// iPad's player follows the iPad for every video, and a turn under the player stalls XCUITest there
    /// (`testEpisodeButtonsPlayThePreviousEpisode`): iPhone only.
    func testTallVideoFollowsTheDevice() throws {
        try XCTSkipIf(isPad, "the iPad's player follows the iPad for every video, and a turn under the player stalls XCUITest there: iPhone only")
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        XCUIDevice.shared.orientation = .portrait
        try launchDev(addon: true, open: ["streams:movie:tortuga-portrait:tortuga-portrait"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the portrait movie")).tap()
        let video = require("player.video", timeout: 20, "a tap on the portrait movie opens the player")
        requirePlayback(1, timeout: 90)
        for (name, orientation, upright) in [("upright", UIDeviceOrientation.portrait, true), ("turned", .landscapeLeft, false),
                                             ("upright again", .portrait, true)] {
            XCUIDevice.shared.orientation = orientation
            requireUpright(upright, "the tall video's player, the phone \(name)")
            screenShot("tall video: the player, the phone \(name)")
        }
        showControls(video)
        ready("player.back").tap()
        requireGone("player.video", "the close button closes the player")
        require("streams.row.0", "the close button returns to the Streams screen")
        requireUpright(true, "the Streams screen after the tall video")
    }

    /// Picture in picture from the controls' button over the H.264 file (20 s): the button reads as selected and
    /// SpringBoard shows the PiP window, over the player and then over the home screen, while the playback goes on (as
    /// the player's position and Now Playing tell: XCUITest doesn't see the video in the window, the screenshots do);
    /// the window's Pause, Skip back and Play act on the player; Restore fullscreen brings the player back with the
    /// button no longer selected, the playback going on and the controls working. The button reads as selected (VoiceOver:
    /// "selected") while PiP runs, and not once it has ended. Where AVKit reports no PiP support (the iPhone simulators)
    /// the button stays disabled, which is checked, and the rest is skipped.
    func testPictureInPictureFromTheButtonAndRestored() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).tap()
        let video = require("player.video", "a tap on the H.264 stream opens the player")
        requirePlayback(1, timeout: 90)
        guard pictureInPictureSupported(video) else {
            throw XCTSkip("AVKit reports no picture in picture on this device (an iPhone simulator): the button stays disabled, which is checked")
        }
        shot("player with the picture in picture button")

        startPictureInPicture(video, "the button doesn't start picture in picture")
        let window = pictureInPictureWindow()
        if !window.exists(within: 5) { fail("the button reads as selected, but SpringBoard shows no picture in picture window") }
        if !waitUntil(5, { pictureInPictureSelected(video) }) { fail("in picture in picture the button doesn't read as selected") }
        let entered = playerPosition() ?? 0
        if !waitUntil(10, { (playerPosition() ?? 0) >= entered + 2 }) {
            fail("in picture in picture the video went from \(entered) s only to \(playerPosition() ?? 0) s")
        }
        screenShot("picture in picture over the player")

        // The window's own controls (in picture in picture the player shows close and its button alone): Pause, Skip
        // back twice, to the start of the 20 s file, which leaves the rest of it for what follows (on a slow simulator
        // the window's controls take a while, and the file must not end before the checks after the return), Play.
        ready(pictureInPictureButton("Pause")).tap()
        requireNowPlaying("rate=0.0", "the PiP window's Pause pauses the player")
        let paused = playerPosition() ?? 0
        ready(pictureInPictureButton("Skip back 10 seconds")).tap()
        if !waitUntil(5, { (playerPosition() ?? .infinity) <= max(paused - 5, 0.5) }) {
            fail("the PiP window's Skip back from \(paused) s left the video at \(playerPosition() ?? 0) s")
        }
        ready(pictureInPictureButton("Skip back 10 seconds")).tap()
        if !waitUntil(5, { (playerPosition() ?? .infinity) <= 1 }) {
            fail("the PiP window's Skip back again left the video at \(playerPosition() ?? 0) s, not at its start")
        }
        ready(pictureInPictureButton("Play")).tap()
        requireNowPlaying("rate=1.0", "the PiP window's Play plays the player again")

        // Home from the start: the playback goes on (as the store saw it meanwhile).
        XCUIDevice.shared.press(.home)
        // The window stays over the home screen and the app runs on, all the time away.
        var shown = true, state = app.state
        _ = holds(for: 3) {
            shown = window.exists
            state = app.state
            return shown && ![.notRunning, .runningBackgroundSuspended].contains(state)
        }
        if !shown { fail("the picture in picture window went with the app") }
        if [.notRunning, .runningBackgroundSuspended].contains(state) {
            fail("in picture in picture the app was suspended in the background (state \(state.rawValue))")
        }
        screenShot("picture in picture over the home screen")

        // Restore fullscreen: back in the player, out of PiP, still playing, the controls working.
        ready(pictureInPictureButton("Restore fullscreen")).tap()
        if !app.wait(for: .runningForeground, timeout: 10) { fail("Restore fullscreen doesn't bring the app back") }
        require("player.video", timeout: 10, "Restore fullscreen returns to the player")
        if !waitUntil(5, { !window.exists }) { fail("the picture in picture window stays after Restore fullscreen") }
        requireBackgroundPlayback("restored from picture in picture", seen: true)
        if !waitUntil(10, { !pictureInPictureSelected(video) }) {
            fail("after Restore fullscreen the picture in picture button still reads as selected")
        }
        requireNowPlaying("rate=1.0", "the playback goes on after Restore fullscreen")
        let restored = playerPosition() ?? 0
        if !waitUntil(10, { (playerPosition() ?? 0) >= restored + 1 }) {
            fail("after Restore fullscreen the video stays at \(playerPosition() ?? 0) s")
        }
        shot("player restored from picture in picture")
        pauseWithTheControls(video, "after Restore fullscreen play/pause doesn't pause the video")
    }

    /// Picture in picture's other ways in and out, over the H.264 file: the button ends it as it started it, and the
    /// video plays on; closing the PiP window pauses it, as the system player does; Home while the video plays starts
    /// it by itself; and closing the player ends it. Where AVKit reports no PiP support the button stays disabled,
    /// and the rest is skipped.
    func testPictureInPictureOffByTheButtonClosedHomeAndPlayerClosed() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).tap()
        let video = require("player.video", "a tap on the H.264 stream opens the player")
        requirePlayback(1, timeout: 90)
        guard pictureInPictureSupported(video) else {
            throw XCTSkip("AVKit reports no picture in picture on this device (an iPhone simulator): the button stays disabled, which is checked")
        }
        let window = pictureInPictureWindow()

        startPictureInPicture(video, "the button doesn't start picture in picture")
        stopPictureInPicture(video, "the button doesn't end picture in picture")
        if !waitUntil(5, { !window.exists }) { fail("the picture in picture window stays after the button ended it") }
        requireNowPlaying("rate=1.0", "the video plays on once the button ended picture in picture")
        let ended = playerPosition() ?? 0
        if !waitUntil(10, { (playerPosition() ?? 0) >= ended + 1 }) {
            fail("once the button ended picture in picture the video stays at \(playerPosition() ?? 0) s")
        }

        startPictureInPicture(video, "the button doesn't start picture in picture again")
        ready(pictureInPictureButton("Close Picture in Picture")).tap()
        if !waitUntil(5, { !window.exists }) { fail("the PiP window's Close leaves the window up") }
        requireNowPlaying("rate=0.0", "closing the PiP window pauses the video")
        if !waitUntil(10, { !pictureInPictureSelected(video) }) {
            fail("after the PiP window closed the picture in picture button still reads as selected")
        }
        shot("player after the picture in picture window closed")

        // Home while the video plays (from near its start again): PiP starts by itself.
        playFromTheStart(video)
        XCUIDevice.shared.press(.home)
        if !window.exists(within: 10) { fail("Home while the video plays doesn't start picture in picture") }
        // Away long enough for the record of the background (`requireBackgroundPlayback`): the window stays and the app
        // runs on.
        var shown = true, state = app.state
        _ = holds(for: 2) {
            shown = window.exists
            state = app.state
            return shown && ![.notRunning, .runningBackgroundSuspended].contains(state)
        }
        if !shown { fail("the picture in picture window Home started went away") }
        if [.notRunning, .runningBackgroundSuspended].contains(state) {
            fail("in picture in picture started by Home the app was suspended in the background (state \(state.rawValue))")
        }
        screenShot("picture in picture started by Home")
        app.activate()
        require("player.video", timeout: 10, "back in the app, the player is still up")
        requireBackgroundPlayback("in picture in picture started by Home", seen: true)
        if pictureInPictureSelected(video) { stopPictureInPicture(video, "the button doesn't end picture in picture started by Home") }

        // Closing the player ends picture in picture with the playback: close is up all along in it.
        playFromTheStart(video)
        startPictureInPicture(video, "the button doesn't start picture in picture before the player closes")
        if !window.exists(within: 5) { fail("SpringBoard shows no picture in picture window") }
        ready("player.back").tap()
        requireGone("player.video", "the close button closes the player in picture in picture")
        require("streams.row.1", "closing the player returns to the Streams screen")
        if !waitUntil(5, { !window.exists }) { fail("the picture in picture window stays after the player closed") }
        if app.state != .runningForeground { fail("the app left the foreground after the player closed in picture in picture (state \(app.state.rawValue))") }
        screenShot("player closed in picture in picture")
    }

    /// While picture in picture runs the player stays up under AVKit's placeholder with close and the PiP button
    /// alone, the window having the playback's own controls: over the chapters movie (60 s) playing they stay past the
    /// 5 s the controls hide after, and a tap on the video brings none of the transport, the timeline, the bottom bar
    /// or the menus back. Where AVKit reports no PiP support the button stays disabled, and the rest is skipped.
    func testPictureInPictureKeepsCloseAndItsButtonAlone() throws {
        try launchDev(addon: true, open: ["streams:movie:tortuga-chapters-movie:tortuga-chapters-movie"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the chapters movie")).tap()
        let video = require("player.video", "a tap on the stream opens the player")
        requirePlayback(1, timeout: 90)
        guard pictureInPictureSupported(video) else {
            throw XCTSkip("AVKit reports no picture in picture on this device (an iPhone simulator): the button stays disabled, which is checked")
        }
        startPictureInPicture(video, "the button doesn't start picture in picture")
        if !pictureInPictureWindow().exists(within: 5) { fail("the button reads as selected, but SpringBoard shows no picture in picture window") }
        requireNowPlaying("rate=1.0", "the video plays on in picture in picture")
        requireCloseAndItsButtonAlone(for: 7, "in picture in picture with the video playing")
        ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.4)).tap()
        requireCloseAndItsButtonAlone(for: 2, "after a tap on the video in picture in picture")
        screenShot("picture in picture: close and its button alone")
    }

    /// The screen stays on while the video plays and may lock once it is paused: the app's idle timer (read back by the
    /// debug probe) is off while the H.264 file plays, on once play/pause has paused it, off again once it plays on.
    /// The probe goes with the player; the run's app.log has the timer on again after the close button
    /// (`keep awake: false, isIdleTimerDisabled=false`).
    func testPlayingKeepsTheScreenAwakeAndPausingLetsItSleep() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).tap()
        let video = require("player.video", "a tap on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        requireIdleTimerDisabled(true, "while the video plays")
        pauseWithTheControls(video, "play/pause doesn't pause the H.264 file")
        requireIdleTimerDisabled(false, "once play/pause has paused the video")
        ready("player.playPause").tap()
        requireIdleTimerDisabled(true, "once play/pause plays the video again")
        showControls(video)
        ready("player.back").tap()
        requireGone("player.video", "the close button closes the player")
    }

    /// Named to run after the tests that look for a software keyboard (see the class's comment). The keys are typed after
    /// a tap on the video: on iOS 26.5 SpringBoard hands the hardware keyboard to the system's Now Playing activity
    /// (MediaRemoteUI) a moment after the app becomes Now Playing, so no key reaches the app, whatever its first
    /// responder, until a touch in the app takes the keyboard back (the simulator's log: SpringBoard's KeyboardFocus
    /// "outbound target changed" from the app's scene to `ActivityUIServices:activity`, then back on the tap). iOS 27
    /// leaves the keyboard with the app.
    func testTypedKeysOfAHardwareKeyboardPauseAndPlay() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).tap()
        let video = require("player.video", "a tap on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        requireNowPlaying("rate=1.0", "Now Playing shows the video playing")
        showControls(video)
        app.typeKey(" ", modifierFlags: [])
        requireNowPlaying("rate=0.0", "Space on a hardware keyboard pauses the video")
        app.typeKey("k", modifierFlags: [])
        requireNowPlaying("rate=1.0", "K on a hardware keyboard plays the video again")
        requireNoKeyboard("the hardware keyboard's keys")
    }

    /// K on a Russian hardware keyboard types "л"; the player takes a key typing a letter of another script than Latin as
    /// the key in its place on a US keyboard, K, which pauses. The layout is this launch's alone (`-AppleKeyboards`, the
    /// launch's arguments domain). Named to run after the tests that look for a software keyboard (the class's comment).
    func testTypedKeysOfANonLatinLayoutActAsTheKeysInTheirPlace() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"], arguments: [
            "-AppleKeyboards", "(\"ru_RU@sw=Russian;hw=Russian\")", "-AppleKeyboardsExpanded", "1",
        ])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).tap()
        let video = require("player.video", "a tap on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        requireNowPlaying("rate=1.0", "Now Playing shows the video playing")
        showControls(video)
        app.typeKey("k", modifierFlags: [])
        requireNowPlaying("rate=0.0", "K on a Russian hardware keyboard pauses the video")
    }

    /// While the player is up the Playback menu takes its keys (the debug probe `player.playbackMenu`, the menu as UIKit
    /// holds it: XCUITest reaches neither iPadOS's menu bar nor the shortcuts listed while ⌘ is held): play/pause on
    /// Space, rewind and fast forward on the arrows, the next subtitle and audio track on S and A, all enabled. Space,
    /// typed, pauses the video, and the item reads Play. Named to run after the tests that look for a software keyboard
    /// (the class's comment).
    func testTypedKeysAreThePlaybackMenusShortcutsWhileThePlayerIsUp() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).tap()
        let video = require("player.video", "a tap on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        requireNowPlaying("rate=1.0", "Now Playing shows the video playing")
        let menu = require("player.playbackMenu", timeout: 5, "the player has its Playback menu probe")
        let items = [
            "Pause (Space): enabled", "Rewind (←): enabled", "Fast forward (→): enabled", "Next subtitle track (S): enabled",
            "Next audio track (A): enabled",
        ]
        if !waitUntil(5, { items.allSatisfy { (menu.value as? String)?.contains($0) == true } }) {
            fail("while the video plays the Playback menu holds '\(menu.value as? String ?? "nothing")', not \(items)")
        }
        showControls(video)
        app.typeKey(" ", modifierFlags: [])
        requireNowPlaying("rate=0.0", "Space, the Playback menu's play/pause, pauses the video")
        if !waitUntil(5, { (menu.value as? String)?.contains("Play (Space): enabled") == true }) {
            fail("once paused the Playback menu holds '\(menu.value as? String ?? "nothing")', not 'Play (Space): enabled'")
        }
    }

    /// Up next on the test addon's own series (three 20 s episodes; the stream in a binge group, so the next episode
    /// plays on without asking), 10 s before an episode's end: on the first episode it offers "S1E2 · Episode 2", read
    /// as "Play the next episode: S1E2 · Episode 2" with its seconds left as its value ("Automatically in 8 seconds"),
    /// counting down (shot as "upnext-counting"), and with nothing touched plays the second episode, whose own offers the
    /// third. There a tap on the video stops the countdown: the plain button stays, no seconds left in its value, the
    /// episode ends and no other follows (shot as "upnext-cancelled"), the controls' Next episode beside it (both episode
    /// buttons enabled), the button clear of the controls; a tap on it plays the third episode. On iPad, launched again
    /// in landscape, the second episode's plain up next is clear of the controls there too (the iPhone's player was in
    /// landscape already). The device never turns while the player is up (the iPad with iOS 27.0 stalls XCUITest after
    /// such a turn: `testEpisodeButtonsPlayThePreviousEpisode`).
    func testUpNextCountsDownToTheNextEpisodeAndATapStopsIt() throws {
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"])
        ready(require("streams.row.1", timeout: 60, "the first episode's Streams sheet lists the stream in a binge group")).tap()
        let video = require("player.video", timeout: 20, "a tap on the stream opens the player")
        requirePlayback(1, timeout: 90)
        require("player.upNext", timeout: 40, "the first episode's last 10 s bring up next")
        let first = promptLabel("player.upNext")
        if !first.hasPrefix(Self.playNextEpisode + "S1E2") { fail("up next on the first episode reads '\(first)', not '\(Self.playNextEpisode)S1E2 · …'") }
        if !waitUntil(3, { promptCountdown("player.upNext").hasPrefix("Automatically in") }) {
            fail("up next counting down says '\(promptCountdown("player.upNext"))' as its value, not its seconds left ('Automatically in 8 seconds')")
        }
        // A second of the countdown filled in: the position two on in its whole seconds, over a second played.
        requirePlayback((playerPosition() ?? 0) + 2, timeout: 10)
        shot("upnext-counting")
        // The countdown's end opens the second episode, whose up next comes 10 s before its end, on the clock of its
        // playback, which starts once it has loaded.
        if !waitUntil(30, { !promptLabel("player.upNext").contains("S1E2") }) {
            fail("with nothing touched up next didn't play the second episode: it reads '\(promptLabel("player.upNext"))' at \(playerPosition() ?? 0) s")
        }
        requirePlayerLoaded("up next's countdown plays the second episode")
        if !waitUntil(40, { promptLabel("player.upNext").contains("S1E3") }) {
            fail("the second episode doesn't offer the third: up next reads '\(promptLabel("player.upNext"))' at \(playerPosition() ?? 0) s")
        }
        // The second episode's up next counts down to its end at 20 s: a tap on the video, clear of the controls
        // should they show, stops it.
        video.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap()
        requirePlayback(19, timeout: 20)
        // Past the episode's end (20 s), unless the stopped countdown plays the next episode anyway, which takes the
        // position back; then the plain button and the player at the end are read once, as they show there (the
        // button is made anew as the countdown turns into it, so it is briefly gone in between).
        _ = holds(for: 3) { (playerPosition() ?? 0) >= 19 }
        let label = promptLabel("player.upNext"), countdown = promptCountdown("player.upNext"), ended = playerPosition() ?? 0
        if !label.contains("S1E3") { fail("after a tap and the episode's end up next reads '\(label)', not the plain S1E3 button") }
        if !countdown.isEmpty { fail("the plain up next at the episode's end still says '\(countdown)' as its value") }
        if ended < 19 { fail("after a tap on the video up next still played the next episode (the player at \(ended) s)") }
        let title = require("player.title", timeout: 5, "the controls show the title at the second episode's end")
        if !text(of: title).contains("S1E2") { fail("at the end of the second episode the title reads '\(text(of: title))', not S1E2") }
        requireEpisodeButtons((previous: true, next: true), "the second episode's controls beside up next")
        requireClearOfTheControls("player.upNext", "the plain up next at the episode's end")
        shot("upnext-cancelled")
        ready(require("player.upNext", timeout: 5, "the plain up next stays at the episode's end")).tap()
        pauseOnEpisode("S1E3", video, "a tap on up next")
        // The iPhone's player was in landscape already.
        guard isPad else { return }
        // In landscape the button may reach up beside the transport: clear of it, the second episode's countdown
        // stopped the same way.
        Self.quitAndTurn(to: .landscapeLeft)
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:2"])
        ready(require("streams.row.1", timeout: 60, "the second episode's Streams sheet lists the stream in a binge group")).tap()
        let landscapeVideo = require("player.video", timeout: 20, "a tap on the stream opens the player in landscape")
        requirePlayback(1, timeout: 90)
        require("player.upNext", timeout: 40, "the second episode's last 10 s bring up next in landscape")
        landscapeVideo.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap()
        requirePlayback(19, timeout: 20)
        // Past the episode's end, as above.
        _ = holds(for: 3) { (playerPosition() ?? 0) >= 19 }
        requireEpisodeButtons((previous: true, next: true), "the second episode's controls beside up next in landscape")
        requireClearOfTheControls("player.upNext", "the plain up next at the episode's end in landscape")
        screenShot("upnext-cancelled in landscape")
        Self.quitAndTurn(to: .portrait)
    }

    /// The test addon's own series from its last episode (three 20 s episodes; the stream in a binge group, so the
    /// episode before plays from the same group without asking): Previous episode, then a disabled Next episode, around
    /// back, play/pause and forward (shot as "last episode"); Previous plays the second episode, both enabled (shot as
    /// "middle episode"), and again the first, its Previous disabled (shot as "first episode"); each paused with the
    /// controls up, in each orientation the player shows them in (`playerOrientations`: on iPad in portrait and then,
    /// launched again, in landscape, "… in landscape"). The device never turns while the player
    /// is up: on the iPad with iOS 27.0 a turn under the player's cover can take the tab sidebar under it out of the
    /// hierarchy mid-animation, Core Animation then calls `animationDidStart:` twice for one of its animations and
    /// `animationDidStop` once, and XCUITest, counting those, never sees the app idle again: every query waits 60 s
    /// while the episodes play out.
    func testEpisodeButtonsPlayThePreviousEpisode() throws {
        // Three episodes in each orientation, two launches: longer than the default 5 minutes on a slow simulator.
        executionTimeAllowance = 600
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        for landscape in playerOrientations.map({ $0.device.isLandscape }) {
            if landscape {
                Self.quitAndTurn(to: .landscapeLeft)
            }
            try launchDev(addon: true, open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:3"])
            ready(require("streams.row.1", timeout: 60, "the last episode's Streams sheet lists the stream in a binge group")).tap()
            let video = require("player.video", timeout: 20, "a tap on the stream opens the player")
            requirePlayback(1, timeout: 90)
            // Each episode paused as it starts: before its 10 s, where up next would come up.
            pauseOnEpisode("S1E3", video, "the stream picked")
            requireEpisodeButtons((previous: true, next: false), "the series' last episode")
            shotOfTheTransport("last episode", landscape: landscape)
            ready("player.previous").tap()
            pauseOnEpisode("S1E2", video, "Previous episode on the last episode")
            requireEpisodeButtons((previous: true, next: true), "the series' second episode")
            shotOfTheTransport("middle episode", landscape: landscape)
            ready("player.previous").tap()
            pauseOnEpisode("S1E1", video, "Previous episode on the second episode")
            requireEpisodeButtons((previous: false, next: true), "the series' first episode")
            shotOfTheTransport("first episode", landscape: landscape)
        }
        Self.quitAndTurn(to: .portrait)
    }

    /// In Arabic, laid out right to left, the player's episode line under the series' name ("م1:ح1 · What If...?") holds
    /// the test addon's Latin episode title whole between U+2068 and U+2069, so the title keeps "...?" at its end beside
    /// the code (tortuga-bidi's first episode; the controls shot as `bidi-player-ar` before the line is read).
    func testTheEpisodeLineKeepsALatinTitleWholeInArabic() throws {
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-bidi", "streams:series:tortuga-bidi:tortuga-bidi:1:1"], languages: ["ar"])
        ready(require("streams.row.0", timeout: 60, "the first episode's Streams sheet lists its stream")).tap()
        let video = require("player.video", timeout: 20, "a tap on the stream opens the player")
        requirePlayback(1, timeout: 90)
        showControls(video)
        let title = require("player.title", timeout: 5, "the controls show the title")
        shot("bidi-player-ar")
        let label = (try? title.snapshot().label) ?? ""
        if !label.contains("\u{2068}What If...?\u{2069}") {
            fail("in Arabic the player's title reads '\(label)': 'What If...?' isn't whole inside U+2068 … U+2069")
        }
    }

    /// The chapters series' first episode (`SkipTestSupport`): over the intro (4–12 s) the skip button offers "Skip
    /// intro" (shot as "skip-button"); with the controls brought up over it and paused it sits above them, over none of
    /// them (shot as "skip with controls"), and a tap on it lands at the intro's end; played on into the credits
    /// (44 s), up next comes up in the button's place (shot as "credits-upnext").
    func testSkipButtonSkipsTheIntroAndTheCreditsBringUpNext() throws {
        try launchDev(addon: true, open: Self.chaptersFirstEpisode)
        playFirstChaptersEpisode()
        let video = element("player.video")
        requireSkipButton(Self.skipIntroLabel, timeout: 90, "the intro (4 s) brings the skip button up")
        shot("skip-button")
        // Paused in the intro: no time runs out on the skip meanwhile.
        pauseWithTheControls(video, "play/pause doesn't pause the intro")
        requireSkipButton(Self.skipIntroLabel, timeout: 5, "the skip button shows with the controls up")
        _ = steadySkipButton()
        requireClearOfTheControls("player.skip", "the skip button with the controls up")
        requireEpisodeButtons((previous: false, next: true), "the chapters series' first episode")
        shot("skip with controls")
        let start = Date()
        let before = playerPosition() ?? 0
        ready(steadySkipButton()).tap()
        requireIntroSkipped(from: before, since: start, paused: true, "a tap on Skip intro")
        ready("player.playPause").tap()
        if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label != "Play" }) { fail("play/pause doesn't play on after the skip") }
        requireUpNextAtTheCredits()
    }

    /// The test addon's numbered-chapters movie (tools/media's chapters-numbered.mkv, 7 min: Chapter 1 0–30 s, Chapter
    /// 2 30–120, Chapter 3 120–330, Chapter 4 330–420), whose titles name no intro or credits: the chapter classifier
    /// reads Chapter 2 as the intro and Chapter 4 as the credits. Resumed at 26 s, the skip button offers "Skip intro"
    /// from 30 s (shot as "numbered-skip-intro") and a tap on it lands at 2:00.
    func testNumberedChaptersBringSkipIntro() throws {
        try playNumberedChapters(from: 26)
        requireSkipButton(Self.skipIntroLabel, timeout: 60, "Chapter 2 (30 s) brings the skip button up")
        shot("numbered-skip-intro")
        let start = Date()
        ready(steadySkipButton()).tap()
        let after = requirePlayback(120, timeout: 6)
        if after > 120 + Date().timeIntervalSince(start) + 3 { fail("a tap on Skip intro landed at \(after) s, past Chapter 2's end (2:00)") }
    }

    /// The numbered-chapters movie resumed at 326 s: the skip button offers "Skip credits" from Chapter 4 (5:30) (shot
    /// as "numbered-skip-credits").
    func testNumberedChaptersBringSkipCredits() throws {
        try playNumberedChapters(from: 326)
        requireSkipButton("Skip credits", timeout: 60, "Chapter 4 (5:30) brings the skip button up")
        shot("numbered-skip-credits")
    }

    /// Opens the numbered-chapters movie's Streams screen with the movie left at `seconds`, and plays its one stream.
    private func playNumberedChapters(from seconds: Int) throws {
        let id = "tortuga-chapters-numbered"
        try launchDev(addon: true, open: ["streams:movie:\(id):\(id)"], progress: ["movie:\(id):\(id):\(seconds):420"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the numbered-chapters movie")).tap()
        require("player.video", timeout: 20, "a tap on the stream opens the player")
    }

    func testH264NowPlayingDoubleTapSeekAndBackground() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).tap()
        let video = require("player.video", "a tap on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        requireNowPlaying("title=\(video.label);", "Now Playing shows the title")
        requireNowPlaying("rate=1.0", "Now Playing shows the video playing at normal speed")
        requireNowPlaying("duration=20", "Now Playing shows the 20 s duration")
        shot("playing")

        let before = playerPosition() ?? 0
        ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)).doubleTap()
        if !waitUntil(5, { (playerPosition() ?? 0) >= before + 8 }) {
            fail("a double tap on the right from \(before) s reached \(playerPosition() ?? 0) s; expected ≥ \(before + 8) s")
        }
        shot("after a double tap on the right")
        let ahead = playerPosition() ?? 0
        ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)).doubleTap()
        if !waitUntil(5, { (playerPosition() ?? .infinity) <= ahead - 6 }) {
            fail("a double tap on the left from \(ahead) s reached \(playerPosition() ?? 0) s; expected ≤ \(ahead - 6) s")
        }

        // Home while the video plays: picture in picture or background audio keeps the playback going (a suspended
        // app's mpv stops), so back in the app the position has moved on by about the time away; without picture in
        // picture mpv decoded no video meanwhile, and decodes it again back in the app.
        showControls(video)
        if !element("player.pip").exists(within: 5) { fail("no picture in picture button") }
        // Bringing the controls up can take a while: back near the 20 s file's start by a tap on the timeline, and
        // playing (it stays paused where it ended), so it doesn't end while the app is away.
        showControls(video)
        ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 2 / 20, dy: 0.5)).tap()
        if !waitUntil(10, { (playerPosition() ?? .infinity) < 5 }) {
            fail("a tap on the timeline at 2 s left the H.264 file at \(playerPosition() ?? 0) s")
        }
        if !element("player.playPause").exists { showControls(video) }
        if element("player.playPause").label == "Play" { ready("player.playPause").tap() }
        requireNowPlaying("rate=1.0", "the video plays before Home")
        let atHome = playerPosition() ?? 0
        XCUIDevice.shared.press(.home)
        // Five seconds away, the app running on all the while.
        var state = app.state
        let running = holds(for: 5) {
            state = app.state
            return ![.notRunning, .runningBackgroundSuspended].contains(state)
        }
        if !running { fail("in the background the app is in state \(state.rawValue): the playback didn't keep it running") }
        app.activate()
        require("player.video", timeout: 10, "back in the app, the player is still up")
        let immediate = playerPosition() ?? 0
        // Where AVKit supports picture in picture (the iPads), Home while the video plays starts it.
        requireBackgroundPlayback(
            "back in the app", seen: AVPictureInPictureController.isPictureInPictureSupported(),
            note: "the position read at once: \(immediate) s, before going: \(atHome) s"
        )
        if !waitUntil(5, { (playerPosition() ?? 0) >= atHome + 3 }) {
            fail("back in the app the video shows \(playerPosition() ?? 0) s, not past \(atHome + 3) s")
        }
        shot("back from the background")
    }

    /// The remux playing near its start, the app goes to the background, by Home and then by Notification Center pulled
    /// down over it, for 3 s each, and comes back (`app.activate()`; Notification Center pushed up again). mpv decodes
    /// no video while the app is away (`decodeVideoWhileSeen`; the iPads' picture in picture keeps it decoding) and the
    /// sound plays on, so the audio output holds seconds of queued sound as the video comes back. The screen is sampled
    /// from the step back: the first screenshot whose frame counter ("frame 0123 / 0720") reads is the return, the
    /// system's snapshot of the app as it left. Half a second and a whole second after it the counter reads again, and
    /// the second of those is the larger. A video that rejoined the queued sound late stood on its first frame for
    /// about 3 s.
    func testPictureMovesOnAtOnceBackFromHomeAndNotificationCenter() throws {
        try XCTSkipIf(
            AVPictureInPictureController.isPictureInPictureSupported(),
            "Home starts picture in picture here, which keeps the video decoded: the iPhone simulators alone leave it off"
        )
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the test addon's streams")).tap()
        let video = require("player.video", timeout: 20, "a tap on the remux stream opens the player")
        requirePlayback(1, timeout: 90)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for away in ["Home", "Notification Center"] {
            // Back near the 30 s clip's start by a tap on the timeline, and playing, so it doesn't end meanwhile.
            showControls(video)
            ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 1 / 30, dy: 0.5)).tap()
            if !waitUntil(10, { (playerPosition() ?? .infinity) < 4 }) {
                fail("a tap on the timeline at 1 s left the remux at \(playerPosition() ?? 0) s")
            }
            if !element("player.playPause").exists { showControls(video) }
            if element("player.playPause").label == "Play" { ready("player.playPause").tap() }
            requireNowPlaying("rate=1.0", "the remux plays before \(away)")
            if away == "Home" {
                XCUIDevice.shared.press(.home)
            } else {
                springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0)).press(
                    forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.8))
                )
            }
            if !app.wait(for: .runningBackground, timeout: 5) { fail("\(away) leaves the app in state \(app.state.rawValue), not in the background") }
            screenShot("away at \(away)")
            var state = app.state
            let running = holds(for: 3) {
                state = app.state
                return state == .runningBackground
            }
            if !running { fail("at \(away) the app is in state \(state.rawValue): the playback didn't keep it running") }
            // Sampled on another thread: `app.activate()` returns only once the app idles, a while after its picture
            // shows.
            let sampler = ScreenSampler(of: appScreen)
            let stepBack = Date()
            sampler.start(limit: 2.5)
            if away == "Home" {
                app.activate()
            } else {
                springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.995)).press(
                    forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2))
                )
            }
            let shots = sampler.framesAtLimit()
            if !app.wait(for: .runningForeground, timeout: 5) { fail("back from \(away) the app is in state \(app.state.rawValue)") }
            // The counter lies at 70–78 % of the picture's height: the band from 60 % to 90 %, the picture's width.
            let picture = video.frame
            let band = CGRect(x: picture.minX, y: picture.minY + picture.height * 0.6, width: picture.width, height: picture.height * 0.3)
            let reads = shots.compactMap { taken, shot in
                Self.frameCounter(shot.textLines(in: band)).map { (at: taken.timeIntervalSince(stepBack), frame: $0) }
            }
            let seen = reads.map { String(format: "%.2f s: %d", $0.at, $0.frame) }.joined(separator: ", ")
            guard let shown = reads.first, shown.at <= 1.5 else {
                fail("back from \(away), the frame counter reads in none of \(shots.count) screenshots within 1.5 s of the step back (read: \(seen))")
                continue
            }
            guard let early = reads.first(where: { $0.at >= shown.at + 0.4 }),
                  let late = reads.first(where: { $0.at >= early.at + 0.5 }), late.at <= shown.at + 1.1 else {
                fail("back from \(away), the frame counter reads at \(String(format: "%.2f", shown.at)) s but not twice 0.5 s apart within the next second (read: \(seen))")
                continue
            }
            if late.frame <= early.frame {
                fail("back from \(away), the picture stands still: frame \(early.frame) at \(String(format: "%.2f", early.at)) s, \(late.frame) at \(String(format: "%.2f", late.at)) s (read: \(seen))")
            }
            screenShot("back from \(away)")
        }
    }

    /// The remux's frame counter in `lines` (Vision's reading of the picture: "00:00:05.214 · frame 0126 / 0720"), an O
    /// read for a zero taken as one.
    private static func frameCounter(_ lines: [String]) -> Int? {
        for line in lines {
            guard let match = line.firstMatch(of: /frame\s*([0-9Oo]{4})\s*\/\s*[0-9Oo]{4}/) else { continue }
            return Int(match.1.replacingOccurrences(of: "O", with: "0").replacingOccurrences(of: "o", with: "0"))
        }
        return nil
    }

    /// The H.264 file, through ao_avfoundation, paused, set to 2× and played again, as a watch-together room changes
    /// its speed: by the engine's position it plays on at 2× from where it paused, from the tap on play/pause on. Each
    /// reading over 3 s lies within 2 s behind and 1 s ahead of that pace (the tap itself takes a moment). That output
    /// holds about 4 s of audio filtered at the old speed, and a speed change alone had mpv's clock count it at the new
    /// one: the picture stayed on the paused frame for a second or more, and played on about 4 s behind where 2× puts
    /// it. The readings go into the run's record.
    func testSpeedChangedWhilePausedPlaysOnFromWhereItPaused() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).tap()
        let video = require("player.video", timeout: 20, "a tap on the H.264 stream opens the player")
        requirePlayback(5, timeout: 90)
        let output = audioProbe()["ao"] ?? "none"
        if output != "avfoundation" { fail("the H.264 file's audio plays through \(output), not avfoundation") }

        pauseWithTheControls(video, "play/pause did not pause the video")
        var paused: Double?
        let settled = waitUntil(5) {
            let before = paused
            paused = enginePosition()
            return paused != nil && paused == before
        }
        if !settled { fail("paused, the engine's position still moves: \(paused.map { "\($0) s" } ?? "none")") }
        let from = paused ?? 0
        pick(from: "player.speed", entry: "2×", video: video, "the 2× speed")
        let playing = { (try? self.element("player.playPause").snapshot().label).map { $0 != "Play" } ?? false }
        showPlayerControls(video, "the controls come up for play/pause")
        let button = try XCTUnwrap(try? element("player.playPause").snapshot().frame, "play/pause has no frame")
        let tapped = Date()
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: button.midX, dy: button.midY)).tap()
        if !waitUntil(5, playing) { fail("a tap on play/pause did not play the video again") }
        var readings: [String] = []
        var off: [String] = []
        _ = holds(for: 3) {
            guard let now = enginePosition() else { return true }
            let pace = from + 2 * Date().timeIntervalSince(tapped)
            let reading = String(format: "%.1f s at %.1f s of 2×", now, pace)
            readings.append(reading)
            if now < pace - 2 || now > pace + 1 { off.append(reading) }
            return true
        }
        XCTContext.runActivity(named: "paused at \(from) s, then at 2×: \(readings.joined(separator: "; "))") { _ in }
        if !off.isEmpty { fail("played on at 2× from \(from) s, the position strays from that pace: \(off.joined(separator: "; "))") }
    }

    /// The engine's position in the open file (`player.engine`'s `position`), nil without one.
    private func enginePosition() -> Double? {
        audioProbe()["position"].flatMap(Double.init)
    }

    /// Swipes and a hold on the video of the test addon's 7-minute numbered-chapters movie, playing from 2:30 (no skip
    /// button there) with the controls gone. A swipe up the left half shows the brightness's level (`player.level`), a
    /// swipe down the right half the volume's, each moved from the level before it by the share of the video's height
    /// the finger went, within 8 of a hundred (the levels before: the debug probe `player.gestures`), and the screen's
    /// brightness follows where the simulator reads it back; neither brings the controls up. Where the system's volume
    /// control has no slider (the simulators: no volume to set) the volume's level shows the system's volume as it is.
    /// A finger held on the middle for 3 s plays the movie at 2× with "2×" showing, and at 1× again once it lifts,
    /// which brings no controls up (read afterwards from the probe: XCUITest does nothing while it holds a finger
    /// down). A double tap on the right still seeks forward and a tap still brings the controls up. Shot: each level,
    /// and "2×" in a frame sampled during the hold. What the simulator does with the screen's brightness and the
    /// system's volume goes into the run's record.
    func testSwipesSetBrightnessAndVolumeAndAHoldPlaysAt2x() throws {
        try playNumberedChapters(from: 150)
        let video = element("player.video")
        requirePlayback(152, timeout: 90)
        requireGone("player.playPause", timeout: 10, "the controls go once the movie plays")
        require("player.gestures", timeout: 5, "the player has its gestures probe")
        let noControls = { !self.element("player.playPause").exists }

        for (kind, x, from, to) in [("Brightness", 0.25, 0.75, 0.35), ("Volume", 0.75, 0.3, 0.6)] as [(String, CGFloat, CGFloat, CGFloat)] {
            let key = kind == "Brightness" ? "brightness" : "volume"
            let before = gestures(key).flatMap(Double.init) ?? -1
            ready(video).coordinate(withNormalizedOffset: CGVector(dx: x, dy: from))
                .press(forDuration: 0.05, thenDragTo: video.coordinate(withNormalizedOffset: CGVector(dx: x, dy: to)))
            // The level shows for 1.5 s from the last move (`PlayerConfig.osdMillis`): its label is read once, as it
            // shows.
            var label: String?
            _ = waitUntil(3) {
                label = try? element("player.level").snapshot().label
                return label != nil
            }
            guard let label else {
                fail("a swipe \(from > to ? "up" : "down") the video at \(x) shows no level: \(gestures(nil) ?? "no probe")")
                continue
            }
            screenShot("swipe: \(label)")
            let percent = label.firstMatch(of: /(\d+)%/).flatMap { Int($0.1) }
            let moves = kind == "Brightness" || gestures("slider") == "yes"
            let expected = Int((min(max(before + (moves ? Double(from - to) : 0), 0), 1) * 100).rounded())
            if !label.hasPrefix("\(kind) ") || percent.map({ abs($0 - expected) > 8 }) != false {
                fail("a swipe of \(from - to) of the video's height from \(key) \(before) shows '\(label)', not \(kind) about \(expected)%")
            }
            if !holds(for: 1, noControls) { fail("a swipe for the \(key) brought the controls up") }
            let after = gestures(key).flatMap(Double.init) ?? -1
            if kind == "Brightness", after != before, let percent, abs(after * 100 - Double(percent)) > 2 {
                fail("the screen's brightness reads \(after) after the swipe, not the \(percent)% shown")
            }
            keep("\(before) before the swipe, \(after) after it, '\(label)' shown; the probe: \(gestures(nil) ?? "none")", "\(key) swipe")
        }

        // The hold, sampled from another thread: XCUITest is back only once the finger has lifted.
        let sampler = ScreenSampler(of: appScreen)
        sampler.start(limit: 8)
        ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 3)
        let back = Date()
        // The press's last second and a half, the finger still down, "2×" up.
        if let held = sampler.stop(before: back.addingTimeInterval(-1.5)) {
            let attachment = XCTAttachment(screenshot: held)
            attachment.name = "hold: 2x"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        if !waitUntil(5, { gestures("after") == "1.00" }) {
            fail("once the held finger lifted the engine plays at \(gestures("after") ?? "?"), not 1×: \(gestures(nil) ?? "no probe")")
        }
        if gestures("holds") != "1" || gestures("held") != "2×" || gestures("heldSpeed") != "2.00" {
            fail("a finger held for 3 s isn't one hold showing 2× at 2×: \(gestures(nil) ?? "no probe")")
        }
        if !holds(for: 1, noControls) { fail("the held finger lifting brought the controls up") }
        keep(gestures(nil) ?? "none", "the probe after the hold")

        let before = playerPosition() ?? 0
        ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)).doubleTap()
        if !waitUntil(5, { (playerPosition() ?? 0) >= before + 8 }) {
            fail("a double tap on the right from \(before) s reached \(playerPosition() ?? 0) s; expected ≥ \(before + 8) s")
        }
        requireGone("player.playPause", timeout: 10, "the controls go again after the double tap")
        ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        require("player.playPause", timeout: 5, "a tap on the video brings the controls up after the swipes and the hold")
    }

    /// The screen lock on the test addon's 7-minute numbered-chapters movie, playing from 2:30. Lock screen in the
    /// options menu (`player.more`) hides the controls and shows the unlock button at the top (`player.unlock`), which
    /// goes after 3 s. Locked, a swipe up the left half, a double tap on the right, a tap and a pinch change nothing: no
    /// controls, the brightness and the hold count as they were (`player.gestures`), no seek past the time played. A
    /// tap shows the unlock button again, a tap on it arms it ("Tap again to unlock"), a second unlocks and the controls
    /// show; once they hide, a tap on the video brings them up. Shot: the options menu with Lock screen, the locked
    /// screen with its unlock button, the armed button, the locked screen bare.
    func testScreenLockIgnoresTouchesUntilUnlocked() throws {
        try playNumberedChapters(from: 150)
        let video = element("player.video")
        requirePlayback(152, timeout: 90)
        require("player.gestures", timeout: 5, "the player has its gestures probe")
        let noControls = { !self.element("player.playPause").exists }
        if noControls() { ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        ready("player.more").tap()
        let entry = menuEntry("Lock screen", "the options menu offers Lock screen")
        screenShot("options menu: lock screen")
        pickMenuEntry(entry)
        requireGone("player.playPause", timeout: 5, "locking hides the controls")
        let shield = require("player.lockShield", timeout: 5, "locking covers the player")
        let unlock = require("player.unlock", timeout: 5, "locking shows the unlock button")
        let tapLabel = unlock.label
        screenShot("locked: unlock button")
        requireGone("player.unlock", timeout: 6, "the unlock button goes after 3 s")
        screenShot("locked")

        let brightness = gestures("brightness")
        let heldCount = gestures("holds")
        let start = Date()
        let before = playerPosition() ?? 0
        shield.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.75))
            .press(forDuration: 0.05, thenDragTo: shield.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.35)))
        shield.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)).doubleTap()
        shield.pinch(withScale: 2, velocity: 1)
        shield.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        if !holds(for: 1, noControls) { fail("a touch on the locked screen brought the controls up") }
        if gestures("brightness") != brightness { fail("a swipe on the locked screen changed the brightness from \(brightness ?? "?") to \(gestures("brightness") ?? "?")") }
        if gestures("holds") != heldCount { fail("a touch on the locked screen held for 2×: \(gestures(nil) ?? "no probe")") }
        let played = Date().timeIntervalSince(start)
        if let after = playerPosition(), after > before + played + 5 { fail("the locked screen seeked: \(before) s to \(after) s in \(played) s") }

        // The last tap showed the unlock button again.
        let shown = require("player.unlock", timeout: 3, "a tap on the locked screen shows the unlock button")
        shown.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        if !waitUntil(3, { (try? self.element("player.unlock").snapshot().label).map { $0 != tapLabel } == true }) {
            fail("a tap on the unlock button didn't arm it: '\((try? element("player.unlock").snapshot().label) ?? "gone")'")
        }
        screenShot("locked: armed")
        require("player.unlock", timeout: 1, "the armed button").coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        requireGone("player.lockShield", timeout: 5, "the second tap on the armed button unlocks")
        require("player.playPause", timeout: 5, "unlocking shows the controls")
        requireGone("player.playPause", timeout: 10, "the controls go again while the movie plays")
        ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        require("player.playPause", timeout: 5, "unlocked, a tap on the video brings the controls up")
    }

    /// The debug probe `player.gestures`'s `key` (`brightness`, `holds`, …), or its whole value for nil.
    private func gestures(_ key: String?) -> String? {
        guard let value = (try? element("player.gestures").snapshot())?.value as? String else { return nil }
        guard let key else { return value }
        return value.components(separatedBy: "; ").first { $0.hasPrefix("\(key)=") }.map { String($0.dropFirst(key.count + 1)) }
    }

    /// The live test addon's 24/7 channel (`LiveTestSupport`), live from the start: Now Playing shows a live stream;
    /// with the controls up no timeline, times or back and forward, and the live label (● LIVE and the time watched)
    /// where the timeline starts, at the bottom bar's leading edge; double taps on the left and the right of the video
    /// seek nothing (neither the position nor the time watched goes back); once closed, the channel's details offer
    /// Play, not Resume, and Home has no Continue Watching.
    func testLiveChannelShowsLiveIgnoresSeeksAndKeepsNothing() throws {
        try launchDev(addon: true, open: Self.liveChannelOpen)
        ready(livePlainStream("the channel's Streams sheet")).tap()
        let video = require("player.video", timeout: 20, "a tap on the plain stream opens the player")
        requirePlayback(2, timeout: 90)
        requireNowPlaying("live=true", "Now Playing shows a live stream")
        showControls(video)
        requireNoTimeline("the controls up")
        let before = (position: requireLivePosition("the controls up"), watched: requireLiveLabel("the controls up"))
        let bar = element("player.bar.bottom").frame, label = element("player.live").frame
        if abs(label.minX - bar.minX) > 2 {
            fail("the live label starts at x \(Int(label.minX)), not at the bottom bar's leading edge (x \(Int(bar.minX)))")
        }
        shot("live")

        // A seek a double tap started lands within a second or two: meanwhile the time watched (`playhead`, read with
        // the controls up or down: `Live · watched 0:07`) never goes back.
        var watched = before.watched
        func watchedKept() -> Bool {
            let read = playhead()
            if read.hasPrefix("Live · watched "), let now = Self.seconds(read) { watched = now }
            return watched >= before.watched
        }
        ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)).doubleTap()
        if !holds(for: 1, watchedKept) {
            fail("after a double tap on the left: the time watched went from \(before.watched) s to \(watched) s")
        }
        ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5)).doubleTap()
        if !holds(for: 2, watchedKept) {
            fail("after double taps on the left and the right: the time watched went from \(before.watched) s to \(watched) s")
        }
        showControls(video)
        requireSeeksIgnored(since: before, "after double taps on the left and the right")
        shot("live after the double taps")

        closePlayer(video, "the controls' back button closes the player")
        let play = require("detail.play", timeout: 10, "closing the player returns to the channel's details")
        requireLiveNotResumed(play, "after the player")
        ready(phoneTab("nav.board")).tap()
        requireNoContinueWatching("Home after the live channel")
        shot("home after live")
    }

    /// The live test addon's match (`LiveTestSupport`), whose playlist keeps a 90-s window: with the controls up the
    /// window's timeline, where it starts on the left, no time left or ±10 s buttons, and the go-to-live button (● LIVE
    /// and the time watched) in the time left's place, level with the bar; a finger dragging the bar 30 s back plays
    /// the match that far behind the edge, the stream reopened at an earlier segment (● LIVE · −0:36); a tap on the
    /// label plays it at the edge again; a tap on the bar 30 s back plays it there again within what mpv holds, no
    /// reopen; once closed, the match's details offer Play, not Resume.
    func testLiveMatchRewindsInItsWindowAndGoesToLive() throws {
        try launchDev(addon: true, open: Self.liveMatchOpen)
        ready(require("streams.row.0", timeout: 60, "the match's Streams sheet lists its stream")).tap()
        let video = require("player.video", timeout: 20, "a tap on the match's stream opens the player")
        requirePlayback(2, timeout: 90)
        requireAtLiveEdge("the match opened")
        showControls(video)
        requireLiveWindow("the controls up")
        let bar = element("player.scrub").frame, label = element("player.live").frame
        if label.minX < bar.maxX || abs(label.midY - bar.midY) > 2 {
            fail("the live label at \(label) isn't after the timeline at \(bar), level with it")
        }
        shot("live match at the edge")

        requireSeekBack(reopened: true, "a finger dragging the timeline 30 s back") {
            showControls(video)
            let at = 1 - (liveBehind() ?? 0) / Self.liveMatchWindow
            let scrub = ready("player.scrub")
            scrub.coordinate(withNormalizedOffset: CGVector(dx: min(at, 0.98), dy: 0.5))
                .press(forDuration: 0.1, thenDragTo: scrub.coordinate(withNormalizedOffset: CGVector(dx: at - 30 / Self.liveMatchWindow, dy: 0.5)))
        }
        showControls(video)
        shot("live match behind the edge")

        tapPlayerControl("player.live", video: video, until: { self.liveBehind() == 0 }, "a tap on the live label goes to live")
        requireAtLiveEdge("after the tap on the live label")
        showControls(video)
        shot("live match back at the edge")
        requireSeekBack(reopened: false, "a tap on the timeline 30 s back") {
            showControls(video)
            ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 1 - 30 / Self.liveMatchWindow, dy: 0.5)).tap()
        }

        closePlayer(video, "the controls' back button closes the player")
        let play = require("detail.play", timeout: 10, "closing the player returns to the match's details")
        requireLiveNotResumed(play, "after the player")
    }

    /// The audio route changing under the playing H.264 file as the system reports it (the DEBUG link
    /// `tortuga://dev-route-change`: a simulator has no headphones to plug in or out). Headphones plugged in
    /// (`newDeviceAvailable`) leave it playing, the controls going on their timer; the headphones going away
    /// (`oldDeviceUnavailable`: unplugged, AirPods or a Bluetooth or AirPlay device gone) pause it short of its end and
    /// bring the controls up; plugged in again, it stays paused where it was.
    func testHeadphonesGoingAwayPauseAndBringUpTheControls() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).tap()
        require("player.video", "a tap on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)

        changeRoute(.newDeviceAvailable)
        requireNowPlaying("rate=1.0", "headphones plugged in: the video plays on")
        requireGone("player.playPause", timeout: 10, "headphones plugged in: the controls go on their timer while the video plays")

        changeRoute(.oldDeviceUnavailable)
        let playPause = element("player.playPause")
        if !waitUntil(10, { (try? playPause.snapshot().label) == "Play" }) {
            fail("the headphones gone: the controls don't come up with the video paused (play/pause: \(playPause.exists ? playPause.label : "not there"))")
        }
        requireNowPlaying("rate=0.0", "the headphones gone: Now Playing has the video paused")
        let paused = playerPosition() ?? 0
        if paused >= 18 { fail("the headphones went at \(paused) s of the 20 s file: its end may be what paused it") }
        shot("headphones gone")

        changeRoute(.newDeviceAvailable)
        // Through the link's coming and after it, the video stays paused where it was.
        var later = paused
        let kept = holds(for: 3) {
            later = playerPosition() ?? 0
            return (try? playPause.snapshot().label) == "Play" && abs(later - paused) <= 0.5
        }
        if !kept {
            fail("headphones plugged in again: the video went on from \(paused) s to \(later) s (play/pause: \(playPause.exists ? playPause.label : "not there"))")
        }
    }

    /// The remux's French SRT line 2 (5–8 s) with the controls up and with them gone, in each orientation the player
    /// shows it in (`playerOrientations`: on iPad portrait, then landscape). mpv places text subtitles by the picture
    /// here, so they move up only by the part of the controls over it (`requireSubtitlePosition`): in portrait, and on
    /// iPad in landscape, the controls lie in the band below the picture and the line stays where it is; on iPhone in
    /// landscape the picture fills the height and the line moves above the controls. Playing on once the controls have
    /// gone, the line is in its own place.
    func testSubtitlesMoveOnlyForControlsOverThePicture() throws {
        // One launch, the line played into twice per orientation: longer than the default 5 minutes on a slow simulator.
        executionTimeAllowance = 600
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"], settings: ["video.hardwareDecoding=false"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the test addon's streams")).tap()
        let video = require("player.video", timeout: 20, "a tap on the remux stream opens the player")
        requirePlayback(1, timeout: 90)
        pauseWithTheControls(video, "play/pause doesn't pause the remux")
        pick(from: "player.subtitles", entry: "French", video: video, "the French subtitles")
        for (orientation, device) in playerOrientations {
            if device != .portrait {
                turn(to: device)
            }
            pauseOnSubtitleLine(video)
            // The paused frame redrawn with its line.
            shotOfThePicture(video, "\(orientation): French SRT line, controls up")
            requireSubtitlePosition("\(orientation), the controls up", lifted: orientation == "landscape" && !isPad)
            playIntoSubtitleLine(video, "\(orientation): French SRT line, controls gone")
            requireSubtitlePosition("\(orientation), the controls gone", lifted: false)
        }
    }

    /// The orientations the player shows a wide video in, by name and as the device is held: an iPad's portrait and
    /// landscape; an iPhone's landscape alone, which its player turns to as it opens.
    private var playerOrientations: [(name: String, device: UIDeviceOrientation)] {
        isPad ? [("portrait", .portrait), ("landscape", .landscapeLeft)] : [("landscape", .landscapeLeft)]
    }

    /// Turns the device to `orientation` and waits up to 10 s for the app's frame to take its shape (wider than tall in
    /// landscape), which it has once the turn starts; XCUITest's next query waits for the turn's animations to end. The
    /// iPhone's player, in landscape already, keeps its frame.
    private func turn(to orientation: UIDeviceOrientation) {
        XCUIDevice.shared.orientation = orientation
        _ = waitUntil(10) { (app.frame.width > app.frame.height) == orientation.isLandscape }
    }

    /// Fails with `what` unless within 10 s the app's frame is taller than wide (`upright`) or wider than tall.
    private func requireUpright(_ upright: Bool, _ what: String) {
        if !waitUntil(10, { (self.app.frame.height > self.app.frame.width) == upright }) {
            fail("\(what): the app's frame is \(app.frame), not \(upright ? "upright" : "in landscape")")
        }
    }

    /// Waits up to 60 s for the episode `code` (`S1E2`) to play, as the episode line under the title and play/pause
    /// tell (the controls brought up by a tap while they're down), and pauses it at once with play/pause. Fails with
    /// `what`.
    func pauseOnEpisode(_ code: String, _ video: XCUIElement, _ what: String) {
        let title = element("player.title")
        let playPause = element("player.playPause")
        let playing = waitUntil(60) {
            guard let label = try? title.snapshot().label else {
                if video.exists { ready(video).tap() }
                return false
            }
            return label.contains(code) && (try? playPause.snapshot().label).map { $0 != "Play" } == true
        }
        if !playing { fail("\(what): the player's title reads '\(title.exists ? title.label : "nothing")', not \(code) playing") }
        guard let frame = try? playPause.snapshot().frame else {
            pauseWithTheControls(video, "\(what): play/pause doesn't pause \(code)")
            return
        }
        // Taps at play/pause's place until it reads Play: with the controls up a tap pauses; with them gone (they hide
        // 5 s after the last input, which slow queries outlast) it brings them up, and the next tap pauses. Quick
        // tries: the episode is 20 s long, and `pauseWithTheControls`' waits for a full 5 s of controls can outlast it,
        // after which the next episode plays.
        let point = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX, dy: frame.midY))
        for _ in 0..<4 {
            point.tap()
            if waitUntil(3, { (try? playPause.snapshot().label) == "Play" }) { return }
        }
        fail("\(what): play/pause doesn't pause \(code)")
    }

    /// The paused controls with the transport in order, shot as `name` in portrait and as "`name` in landscape".
    private func shotOfTheTransport(_ name: String, landscape: Bool) {
        if landscape {
            requireTransportInOrder("\(name) in landscape")
            screenShot("\(name) in landscape")
            return
        }
        requireTransportInOrder("\(name) in portrait")
        shot(name)
    }

    /// Whether this device shows picture in picture, as AVKit reports it here in the test runner (on the same device).
    /// The iPhone simulators report no support, their device types lacking the capability (MobileGestalt's
    /// `MedusaPIPCapability`, `PiPOverlay` false in their capabilities.plist), whatever the app does; there the
    /// controls' button must stay disabled, which is checked, and the reason goes with the run.
    private func pictureInPictureSupported(_ video: XCUIElement) -> Bool {
        guard !AVPictureInPictureController.isPictureInPictureSupported() else { return true }
        showControls(video)
        let button = require("player.pip", timeout: 5, "the controls have a picture in picture button")
        if button.isEnabled { fail("AVKit reports no picture in picture on this device, yet the button is enabled") }
        keep("AVKit reports no picture in picture support on this device (an iPhone simulator): the button stays disabled, the rest is left out", "picture in picture unsupported")
        return false
    }

    /// The picture in picture window, which SpringBoard shows.
    private func pictureInPictureWindow() -> XCUIElement {
        XCUIApplication(bundleIdentifier: "com.apple.springboard").windows.matching(identifier: "PIP-SBInteractionPassThroughView").firstMatch
    }

    /// The PiP window's button `label` (Pause, Play, Skip back 10 seconds, Restore fullscreen, Close Picture in
    /// Picture). The window shows its controls for a few seconds after a tap on it: tapped, up to three times, until
    /// the button is there; fails with the window's elements when it never comes.
    private func pictureInPictureButton(_ label: String) -> XCUIElement {
        let window = pictureInPictureWindow()
        let button = window.buttons[label]
        for _ in 0..<3 where !(button.exists && button.isHittable) {
            ready(window).tap()
            _ = button.exists(within: 2)
        }
        if !button.exists {
            keep(window.exists ? window.debugDescription : "no picture in picture window", "picture in picture window without \(label)")
            fail("the picture in picture window shows no \(label) button")
        }
        return button
    }

    /// Whether the controls' picture in picture button reads as selected (picture in picture runs), the controls
    /// brought up for it where they are down (in picture in picture the button is up all along). Read by a snapshot, as
    /// the button's other reads are: out of picture in picture the controls can hide between a look at the button and a
    /// read of it.
    private func pictureInPictureSelected(_ video: XCUIElement) -> Bool {
        if !element("player.pip").exists { showControls(video) }
        return (try? element("player.pip").snapshot())?.isSelected == true
    }

    /// Starts picture in picture with the controls' button (`toggleTheButton`), until it reads as selected.
    private func startPictureInPicture(_ video: XCUIElement, _ what: String) {
        toggleTheButton(video, to: true, what)
    }

    /// Ends picture in picture with the controls' button (`toggleTheButton`), until it no longer reads as selected.
    private func stopPictureInPicture(_ video: XCUIElement, _ what: String) {
        toggleTheButton(video, to: false, what)
    }

    /// Taps the controls' picture in picture button until it reads as `selected`. Out of picture in picture the controls
    /// are brought up right before the tap: on a slow simulator they can hide before the tap lands, which then brings
    /// them up instead (three tries). In picture in picture the button is up all along, with close.
    private func toggleTheButton(_ video: XCUIElement, to selected: Bool, _ what: String) {
        let button = element("player.pip")
        for _ in 0..<3 {
            if (try? button.snapshot())?.isSelected != true { showControls(video) }
            // Read once: a button gone by now has no frame, and the next try brings it back.
            var shown: (any XCUIElementSnapshot)?
            guard waitUntil(5, {
                shown = try? button.snapshot()
                return shown?.isEnabled == true
            }), let frame = shown?.frame else { continue }
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX, dy: frame.midY)).tap()
            if waitUntil(10, { pictureInPictureSelected(video) == selected }) { return }
        }
        fail("\(what): the picture in picture button \(selected ? "doesn't read" : "still reads") as selected")
    }

    /// In picture in picture, all along for `seconds`: close and the PiP button up; the transport, play/pause, the
    /// timeline, the bottom bar and the menus not. Fails with `what` and what went or showed.
    private func requireCloseAndItsButtonAlone(for seconds: TimeInterval, _ what: String) {
        let kept = ["player.back", "player.pip"]
        let hidden = ["player.transport", "player.playPause", "player.scrub", "player.bar.bottom", "player.audio",
                      "player.subtitles", "player.speed", "player.more"]
        var gone: [String] = [], shown: [String] = []
        _ = holds(for: seconds) {
            gone = kept.filter { !element($0).exists }
            shown = hidden.filter { element($0).exists }
            return gone.isEmpty && shown.isEmpty
        }
        if !gone.isEmpty { fail("\(what): \(gone.joined(separator: ", ")) went") }
        if !shown.isEmpty { fail("\(what): \(shown.joined(separator: ", ")) show") }
    }

    /// Plays the H.264 file (20 s) from near its start: paused, a tap at the start of the timeline, then play/pause.
    private func playFromTheStart(_ video: XCUIElement) {
        pause(video, "play/pause doesn't pause the video")
        ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.5)).tap()
        if !waitUntil(10, { (playerPosition() ?? .infinity) < 2 }) {
            fail("a tap at the start of the timeline left the video at \(playerPosition() ?? 0) s")
        }
        showControls(video)
        ready("player.playPause").tap()
        requireNowPlaying("rate=1.0", "play/pause plays the video again")
    }

    /// Fails with `what` unless the playback went on while the app was last in the background, as the store saw it
    /// twice a second (the debug probe `player.background`, read back in the app: the video's own value only refreshes
    /// once the app draws again, which a read straight after the return can come before): from the app's going to its
    /// coming back (the system tells it a moment after Home), the position moved on by most of the time away. mpv
    /// decoded the video meanwhile only where picture in picture showed it (`seen`), and decodes it again back in the
    /// app (the probe `player.engine`). The record goes with the run, with `note`.
    private func requireBackgroundPlayback(_ what: String, seen: Bool, note: String = "") {
        let probe = element("player.background")
        if !waitUntil(5, { probe.exists && (probe.value as? String)?.hasPrefix("from=") == true }) {
            fail("\(what): the player has no record of the background: \(probe.value as? String ?? "no probe")")
        }
        let record = probe.value as? String ?? ""
        keep("background: \(record)\(note.isEmpty ? "" : "; \(note)")", "background record: \(what)")
        let seconds = { (key: String) in
            record.split(separator: " ").first { $0.hasPrefix("\(key)=") }.flatMap { Double($0.dropFirst(key.count + 1)) } ?? -1
        }
        let away = seconds("away")
        let played = seconds("to") - seconds("from")
        if away < 1.5 || played < 0.75 * away {
            fail("\(what): in the background the playback moved \(played) s in \(away) s away: \(record)")
        }
        // The samples as the app went and as it came back may come before mpv let the video go and took it back.
        let samples = seconds("samples")
        let video = seconds("video")
        if seen ? video < samples - 1 : video > 2 {
            fail("\(what): mpv decoded the video in \(Int(video)) of \(Int(samples)) samples in the background, \(seen ? "with picture in picture showing it" : "with nobody seeing it"): \(record)")
        }
        let engine = element("player.engine")
        if !waitUntil(10, { (engine.value as? String).map { !$0.split(separator: " ").contains("video=no") } ?? false }) {
            fail("\(what): back in the app mpv doesn't decode the video: the engine reads '\(engine.value as? String ?? "nothing")'")
        }
    }

    /// Keeps `text` with the run as `name`.
    private func keep(_ text: String, _ name: String) {
        let attachment = XCTAttachment(string: text)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Fails with `what` where `status`'s frame meets one of the top bar's buttons (its two groups: the room between
    /// them is the messages'), the transport or the bottom bar.
    private func requireClearOfTheControls(_ status: XCUIElement, _ what: String) {
        let frame = status.frame
        let bar = require("player.bar.top", timeout: 5, "\(what): the top bar shows")
        let buttons = bar.buttons.allElementsBoundByIndex.map { ($0.identifier, $0.frame) }
        // The menus count among the buttons: without them the check would pass over them.
        if bar.exists, !buttons.contains(where: { $0.0 == "player.more" }) {
            fail("\(what): the top bar's buttons (\(buttons.map(\.0))) leave out its options menu")
        }
        let parts = ["player.transport", "player.bar.bottom"].map { element($0) }.filter(\.exists).map { ($0.identifier, $0.frame) }
        let met = (buttons + parts).filter { $0.1.intersects(frame) }.map { "\($0.0) \($0.1)" }
        if !met.isEmpty { fail("\(what) (\(frame)) meets \(met.joined(separator: ", "))") }
    }

    /// Fails where the close and play/pause symbols, the position or, upright, the title stand out from what is behind
    /// them by less than 3:1 over the `frame` frame (the title in landscape:
    /// `testControlsLegibleOverABrightAndADarkFrame`); keeps each value with the run. The buttons are measured inside
    /// their middle half, clear of their glass's rim.
    private func requireLegible(over frame: String) {
        guard let pixels = ScreenPixels() else {
            return fail("no screenshot to measure the controls on")
        }
        var values = [String]()
        for (id, inset) in [("player.back", 0.25), ("player.playPause", 0.25), ("player.position", 0.0), ("player.title", 0.0)] {
            let part = element(id)
            guard part.exists else {
                fail("over the \(frame) frame there is no \(id) to measure")
                continue
            }
            let rect = part.frame.insetBy(dx: part.frame.width * inset, dy: part.frame.height * inset)
            let contrast = pixels.contrast(in: rect)
            values.append("\(id): \(String(format: "%.2f", contrast)):1")
            let required = id != "player.title" || app.frame.height > app.frame.width
            if contrast < 3, required { fail("over the \(frame) frame \(id) stands out by \(String(format: "%.2f", contrast)):1, under 3:1") }
        }
        keep(values.joined(separator: "\n"), "contrast over the \(frame) frame")
    }

    /// With Increase Contrast on, fails where the position, text on the player's glass, stands out from what is behind it
    /// by less than 4.5:1 over the `frame` frame; keeps the value and a shot with the run, and turns Increase Contrast off
    /// again.
    private func requireLegibleWithIncreaseContrast(over frame: String) {
        increaseContrast(true)
        guard let pixels = ScreenPixels() else {
            return fail("no screenshot to measure the controls on with Increase Contrast")
        }
        screenShot("controls with Increase Contrast over the \(frame) frame")
        let contrast = pixels.contrast(in: element("player.position").frame)
        let value = String(format: "%.2f", contrast)
        keep("player.position: \(value):1", "contrast with Increase Contrast over the \(frame) frame")
        if contrast < 4.5 { fail("with Increase Contrast over the \(frame) frame player.position stands out by \(value):1, under 4.5:1") }
        increaseContrast(false)
    }

    /// Plays the remux into line 2 of its subtitles (5–8 s) from a tap on the timeline near its start and shoots the
    /// screen as `name` while the line shows with the controls gone (they go 5 s after Play); three tries. The video
    /// plays on.
    private func playIntoSubtitleLine(_ video: XCUIElement, _ name: String) {
        var missed = "never tried"
        for _ in 0..<3 {
            pause(video, "play/pause doesn't pause the remux")
            ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 0.3 / 30, dy: 0.5)).tap()
            guard waitUntil(10, { playerPosition().map { $0 < 2 } ?? false }) else {
                missed = "a tap on the timeline at 0.3 s left the remux at \(playerPosition() ?? 0) s"
                continue
            }
            ready("player.playPause").tap()
            var position: Double?
            _ = waitUntil(15) {
                position = playerPosition()
                return (position ?? 0) >= 5.3 && !element("player.playPause").exists
            }
            if let position, (5.3...7.8).contains(position), !element("player.playPause").exists {
                screenShot(name)
                return
            }
            missed = "the position read \(position.map { "\($0) s" } ?? "nothing") with the controls \(element("player.playPause").exists ? "up" : "gone")"
        }
        fail("\(name): playing into the line missed it three times: \(missed)")
    }

    /// Waits for the debug Now Playing probe (what `MPNowPlayingInfoCenter` holds) to contain `text`.
    private func requireNowPlaying(_ text: String, _ what: String) {
        let probe = element("player.nowPlaying")
        if !waitUntil(10, { probe.exists && (probe.value as? String)?.contains(text) == true }) {
            fail("\(what): Now Playing holds '\(probe.value as? String ?? "nothing")', not '\(text)'")
        }
    }

    /// The audio route changing for `reason` as the system reports it: the DEBUG link
    /// `tortuga://dev-route-change?reason=<raw value>` opened in the running app (`openFromOutside`), which posts the
    /// audio session's route change notification with that reason (`PlaybackAudioSession.followDevRouteChangeLink`).
    private func changeRoute(_ reason: AVAudioSession.RouteChangeReason) {
        openFromOutside(URL(string: "tortuga://dev-route-change?reason=\(reason.rawValue)")!)
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

    /// Fails with `what` unless mpv holds the subtitles where the controls leave them (the debug probe
    /// `player.subtitlePosition`: mpv's `sub-pos`, the frame they are placed in and the controls' lift): `lifted`, moved
    /// up by the controls' share of the picture, more than nothing and well under half of it (`sub-pos` 55–95);
    /// otherwise in their own place (100), within a point of it where the gap above controls in the band below the
    /// picture reaches over its edge. The probe's value goes with the run.
    private func requireSubtitlePosition(_ what: String, lifted: Bool) {
        let probe = element("player.subtitlePosition")
        let value = { probe.value as? String ?? "" }
        // `sub-pos=<value>; frame=…`
        let position = { value().split(separator: ";").first.flatMap { Double($0.dropFirst("sub-pos=".count)) } ?? -1 }
        let expected: ClosedRange<Double> = lifted ? 55...95 : 99...100
        if !waitUntil(3, { probe.exists && expected.contains(position()) }) {
            fail("\(what): mpv holds the subtitles at \(value().isEmpty ? "nothing (no player.subtitlePosition probe)" : value()), not "
                + (lifted ? "above the controls (sub-pos 55–95)" : "in their own place (sub-pos 99–100)"))
        }
        let attachment = XCTAttachment(string: value())
        attachment.name = "subtitle position: \(what)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Keeps the debug probe `player.keyboard`'s value (keyboards and first responders) with the run.
    private func attachKeyboardProbe(_ name: String) {
        let probe = XCTAttachment(string: require("player.keyboard", timeout: 5, "\(name): the player has its keyboard probe").value as? String ?? "no player.keyboard probe")
        probe.name = "keyboard probe: \(name)"
        probe.lifetime = .keepAlways
        add(probe)
    }

    /// Fails with `what` once the software keyboard has come up in the player (nothing there takes text): watched for
    /// 2 s, as a keyboard takes a moment to slide in, through the debug probe `player.keyboard`, which counts UIKit's
    /// announcements of a keyboard since the player opened and lists the first responders meanwhile, and the keyboard
    /// query.
    func requireNoKeyboard(_ what: String) {
        let probe = require("player.keyboard", timeout: 5, "\(what): the player has its keyboard probe")
        if waitUntil(2, { (probe.value as? String)?.hasPrefix("keyboards=0;") != true || app.keyboards.firstMatch.exists }) {
            fail("\(what): the software keyboard came up (\(probe.exists ? probe.value as? String ?? "?" : "no player.keyboard probe"))")
        }
    }
}
