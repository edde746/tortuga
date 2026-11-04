import AppKit
import XCTest

/// The player with the pointer and keyboard alone (the tests iOS has too are in Pointer/PlayerTests.swift), on the test
/// addon's streams for a Cinemeta movie (tools/media/test_addon.py): the remux (HEVC HDR10 with three audio
/// tracks, five subtitle tracks and chapters) through its native subtitle, audio and speed menus, the subtitle delay's
/// popover, the info panel from the options menu (hardware decoding switched off: the decoder row reads software) and a
/// seek, then Escape closing the open menu before the back button leaves the player; the window shows no toolbar or
/// title of its own while the player is up, and the control bar's accessibility frame (`player.bar.bottom`) holds each
/// of its controls.
/// The H.264 file with Space pausing and resuming, the right arrow seeking forward and Escape backing out (the controls
/// while they are up, then the player); a click on its video pausing and playing, and with Settings → Playback's
/// Clicking the video set to Show or hide the controls hiding and showing them while it plays on, a double click
/// entering and leaving full screen without pausing (`testClickOnTheVideoPausesOrShowsAndHidesTheControls`). The remux
/// in picture in picture: the button beside back moves the video into
/// the system's PiP window, where it plays on, and back into the player; in PiP again with the app hidden, the window
/// stays on the desktop and its own pause and play act on the player; then the player closed while the video is in
/// PiP. In full screen the window's buttons stay on the title row with the controls, where the window has them; the
/// green button, Escape and the player's button leave it (`testFullScreenKeepsTheWindowButtonsWithTheControls`). The
/// title row's picture in picture, green and back buttons take clicks posted through the window server, as a mouse
/// makes them, in a window and in full screen, with the player opened in full screen and with the menu bar kept there
/// (`testFullScreenTitleRowTakesClicks…`). The pointer resting on picture in picture and on the speed menu shows each
/// one's name in a tooltip; the scroll wheel over the video turns the volume both ways, and over the controls nothing.
/// The 4:3 clip through the options menu: each fit (the first from this device's setting) and a zoom, shot with its
/// subtitle line, the zoom message of Option-= clear of the title row's controls, loudness normalization switched on
/// and off, and the fit carried over to the next video; a movie's controls offer no episode buttons. On the test
/// addon's own series up next counts down and plays the next episode by itself; a click on the video stops that, the
/// plain button staying past the episode's end beside the controls' Next episode, clear of the controls, and a click on
/// it plays the next episode. From the series' last episode Previous episode plays the one before, each in turn, both
/// episode buttons around back, play/pause and forward, each disabled at its end of the series. On the test addon's
/// tracks series the German audio and the addon's English subtitles, which arrive after the file opened, picked in the
/// first episode carry to the second by up next, which shows its own English until the addon's arrive; subtitles
/// turned off there stay off back in the first under the English audio picked there, and the French subtitles and
/// German audio picked then carry past the player's close to the second episode started from the details. Over the
/// intro of its chapters series' first episode the skip button skips it by a click and, back in the intro by the
/// timeline, by Return; the credits bring up next (`SkipTestSupport`).
/// With Force frame rate switching on, the H.264 file switches a simulated MacBook Pro display to 47.95 Hz in full
/// screen only, and the display goes back on leaving full screen and on the player's close
/// (`testFullScreenSwitchesTheDisplayToTheVideosRateAndBack`).
/// The test addon's SDR, HDR10 and Dolby Vision files play with their subtitles, fits, zoom, full screen and picture in
/// picture (`testPlaysSDRHDR10AndDolbyVision`).
/// While the H.264 file plays the app holds a PreventUserIdleDisplaySleep assertion (this Mac's `pmset -g assertions`,
/// through the run's hook), and none once it is paused or the player has closed. The live test addon's 24/7
/// channel plays live (`LiveTestSupport`): the live label right after play/pause in place of the timeline, the arrows
/// seeking nothing, and nothing kept once it closed (no Resume, no Continue Watching); its Watch together room sheet
/// stays up on Return and closes on Escape.
/// (The Mac's accessibility tree doesn't carry the debug Now Playing probe's value, so Now Playing isn't read here.)
extension PlayerTests {
    func testRemuxMenusInfoSeekAndEscapeClosesTheMenuFirst() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"], settings: ["video.hardwareDecoding=false"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the test addon's streams")).click()
        let video = require("player.video", "a click on the remux stream opens the player")
        requirePlayback(2, timeout: 90)
        if app.toolbars.buttons.count > 0 { fail("the window keeps its toolbar over the player: \(app.toolbars.buttons.count) buttons") }

        // Paused, the French subtitles picked; then paused again on one of their lines with the controls up: the line
        // sits above them.
        showControls(video)
        ready("player.playPause").click()
        if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Play" }) {
            fail("play/pause did not pause the video: it reads \(element("player.playPause").label)")
        }
        shot("paused")
        requireEpisodeButtons(nil, "a movie's controls")
        requireTransportInOrder("a movie's controls")
        // The control bar's accessibility frame holds each of its controls.
        let bar = element("player.bar.bottom").frame
        let outside = controlBarControls("a movie's controls").filter { !bar.insetBy(dx: -0.5, dy: -0.5).contains($0.1) }
        if !outside.isEmpty {
            fail("the control bar's frame \(bar) leaves out \(outside.map { "\($0.0) \($0.1)" }.joined(separator: ", "))")
        }
        pick(from: "player.subtitles", entry: "French", video: video, "the French subtitles")
        shot("subtitles: French")
        pauseOnSubtitleLine(video)
        shot("paused, controls up over a subtitle")

        // The subtitle menu's delay entry opens the delay's popover; its stepper moves the delay (Reset turns on), and
        // Escape closes it.
        showControls(video)
        ready("player.subtitles").click()
        pickMenuEntry(menuEntry("Subtitle delay", "the subtitle menu ends with the delay entry"))
        require("player.delay", timeout: 5, "the delay entry opens the subtitle delay")
        let reset = require("player.delay.reset", timeout: 5, "the subtitle delay has Reset")
        if reset.isEnabled { fail("the subtitle delay offers Reset before it was moved") }
        ready(app.steppers.firstMatch.incrementArrows.firstMatch).click()
        if !waitUntil(5, { reset.isEnabled }) { fail("the stepper didn't move the subtitle delay: Reset stays off") }
        shot("subtitle delay")
        app.typeKey(.escape, modifierFlags: [])
        requireGone("player.delay", timeout: 5, "Escape closes the subtitle delay")
        if !element("player.video").exists { fail("Escape on the subtitle delay left the player") }

        pick(from: "player.audio", entry: "German", video: video, "the German audio")
        shot("audio: German")
        pick(from: "player.speed", entry: "1.5×", video: video, "the 1.5× speed")
        shot("speed 1.5×")

        showControls(video)
        togglePlaybackInfo()
        require("player.info.panel", timeout: 5, "Playback info in the options menu opens the playback info")
        let decoder = infoRow("Decoder")
        if !waitUntil(5, { decoder.exists && fullText(of: decoder).contains("Software") }) {
            fail("with hardware decoding off, the decoder row reads '\(decoder.exists ? fullText(of: decoder) : "nothing")', not Software")
        }
        shot("info")
        showControls(video)
        togglePlaybackInfo()
        requireGone("player.info.panel", timeout: 5, "Playback info in the options menu again closes the playback info")

        let before = playerPosition() ?? 0
        showControls(video)
        ready("player.fastForward").click()
        if !waitUntil(5, { (playerPosition() ?? 0) >= before + 8 }) {
            fail("fast forward from \(before) s reached \(playerPosition() ?? 0) s; expected ≥ \(before + 8) s")
        }
        shot("after fast forward")

        showControls(video)
        ready("player.audio").click()
        let german = menuEntry("German", "the audio button opens its menu")
        shot("audio menu open")
        app.typeKey(.escape, modifierFlags: [])
        if !waitUntil(5, { !german.exists }) { fail("Escape doesn't close the open menu") }
        if !element("player.video").exists { fail("Escape with a menu open left the player") }
        let back = require("player.back", timeout: 5, "the controls stay up once the menu closes (paused)")
        shot("menu closed")
        ready(back).click()
        requireGone("player.video", "the back button closes the player")
        require("streams.row.0", "the back button returns to the Streams screen")
        shot("back on streams")
    }

    /// The options menu (`player.more`) on the 4:3 clip, its English SRT line on, paused with the controls up: the fit
    /// this device's setting gives (`video.fit=cover`: Fill screen), then Letterbox and Stretch from the menu's Aspect
    /// ratio, 150 % from its Zoom over Stretch and over Letterbox, then Reset; the window shot in each, the line on it.
    /// Option-= over Letterbox shows the zoom message clear of the title row's controls. Normalize loudness turns on
    /// and off. Back in the Streams screen and into the clip again, the fit carries over and the zoom doesn't.
    func testOptionsMenuFitZoomAndCarryOver() throws {
        executionTimeAllowance = 600
        try launchDev(addon: true, open: ["streams:movie:tortuga-4x3:tortuga-4x3"], settings: ["video.fit=cover"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the 4:3 clip")).click()
        let video = require("player.video", "a click on the 4:3 clip opens the player")
        requirePlayback(1, timeout: 90)
        pause(video)
        pick(from: "player.subtitles", entry: "English", video: video, "the English subtitles")
        // A track picked while paused shows its line once the player seeks (on into the clip's second line).
        app.typeKey(.rightArrow, modifierFlags: [])
        requireOption("Aspect ratio", reads: "Fill screen", video: video, "with the device setting video.fit=cover")
        shotOfThePicture(video, "fit: Fill screen (device setting)")
        for fit in ["Letterbox", "Stretch"] {
            pickOption("Aspect ratio", fit, video: video)
            shotOfThePicture(video, "fit: \(fit)")
        }
        pickOption("Zoom", "150%", video: video)
        shotOfThePicture(video, "zoom 150%, Stretch")
        pickOption("Aspect ratio", "Letterbox", video: video)
        shotOfThePicture(video, "zoom 150%, Letterbox")
        requireZoomMessageClearOfTheTitleRow(video, "Option-= over Letterbox")
        pickOption("Zoom", "Reset", reads: "100%", video: video)
        shotOfThePicture(video, "zoom reset, Letterbox")
        for state in ["On", "Off"] {
            showControls(video)
            ready("player.more").click()
            pickMenuEntry(openMenuEntry("Normalize loudness", "the options menu lists Normalize loudness"))
            requireOption("Normalize loudness", reads: state, video: video, "switched \(state.lowercased())")
        }

        // The next video: the fit stays Letterbox, the zoom is back at 100%.
        showControls(video)
        ready("player.back").click()
        ready(require("streams.row.0", timeout: 20, "the back button returns to the Streams screen")).click()
        // It resumes where the first play was left, which its position reads before the player has opened.
        requirePlaying()
        pause(video)
        requireOption("Aspect ratio", reads: "Letterbox", video: video, "in the next video")
        requireOption("Zoom", reads: "100%", video: video, "in the next video")
        shotOfThePicture(video, "next video: Letterbox")
    }

    /// Up next on the test addon's own series (three 20 s episodes; the stream in a binge group, so the next episode
    /// plays on without asking), 10 s before an episode's end, the pointer out of the window (a pointer moving over the
    /// player stops a countdown): on the first episode it offers "S1E2 · Episode 2", read as "Play the next episode:
    /// S1E2 · Episode 2" with its seconds left as its value ("Automatically in 8 seconds"), counting down (shot as
    /// "upnext-counting"), and with nothing touched plays the second episode, whose own offers the third. There a click
    /// on the video (which pauses it) stops the countdown: played on by Space, the episode ends with the plain button
    /// still up, no seconds left in its value, and no other following (shot as "upnext-cancelled"), beside the controls'
    /// Next episode (both episode buttons enabled) and clear of them; a click on it plays the third episode.
    func testUpNextCountsDownToTheNextEpisodeAndAClickStopsIt() throws {
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"])
        ready(require("streams.row.1", timeout: 60, "the first episode's Streams sheet lists the stream in a binge group")).click()
        let video = require("player.video", timeout: 20, "a click on the stream opens the player")
        // Out of the window: the controls coming and going under a pointer over the video count as its moves.
        pointerOutOfTheWindow()
        // Up next comes 10 s before the end, on the clock of the playback, which starts once the episode has loaded.
        requirePlayerLoaded("a click on the stream plays the first episode")
        require("player.upNext", timeout: 60, "the first episode's last 10 s bring up next")
        let first = promptLabel("player.upNext")
        if !first.hasPrefix(Self.playNextEpisode + "S1E2") { fail("up next on the first episode reads '\(first)', not '\(Self.playNextEpisode)S1E2 · …'") }
        if !waitUntil(3, { promptCountdown("player.upNext").hasPrefix("Automatically in") }) {
            fail("up next counting down says '\(promptCountdown("player.upNext"))' as its value, not its seconds left ('Automatically in 8 seconds')")
        }
        // Shot as its fill grows, once the button has come up (its frame still).
        _ = settledFrame(of: element("player.upNext"))
        shot("upnext-counting")
        // The countdown's end opens the second episode, whose up next comes 10 s before its end, on the clock of its
        // playback, which starts once it has loaded.
        if !waitUntil(30, { !promptLabel("player.upNext").contains("S1E2") }) {
            fail("with nothing touched up next didn't play the second episode: it reads '\(promptLabel("player.upNext"))'")
        }
        requirePlayerLoaded("up next's countdown plays the second episode")
        if !waitUntil(40, { promptLabel("player.upNext").contains("S1E3") }) {
            fail("the second episode doesn't offer the third: up next reads '\(promptLabel("player.upNext"))'")
        }
        // The second episode's up next counts down to its end at 20 s: a click on the video stops it, and pauses.
        ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)).click()
        let playPause = element("player.playPause")
        if !waitUntil(5, { playPause.exists && playPause.label == "Play" }) { fail("a click on the video doesn't pause the second episode") }
        app.typeKey(" ", modifierFlags: [])
        var ended = requirePlayback(19, timeout: 20)
        // The episode's end passes (20 s) unless the third follows, which takes the position back; then up next is the
        // plain S1E3 button and the player is at the end, read once as they show there (the button is made anew as the
        // countdown turns into it, so it is briefly gone in between).
        _ = holds(for: 3) { (playerPosition() ?? ended) >= 19 }
        ended = playerPosition() ?? ended
        let label = promptLabel("player.upNext"), countdown = promptCountdown("player.upNext")
        if !label.contains("S1E3") { fail("after a click and the episode's end up next reads '\(label)', not the plain S1E3 button") }
        if !countdown.isEmpty { fail("the plain up next at the episode's end still says '\(countdown)' as its value") }
        if ended < 19 { fail("after a click on the video up next still played the next episode (the player at \(ended) s)") }
        let title = require("player.title", timeout: 5, "at the end of the second episode the controls show the title")
        if !fullText(of: title).contains("S1E2") { fail("at the end of the second episode the title reads '\(fullText(of: title))', not S1E2") }
        requireEpisodeButtons((previous: true, next: true), "the second episode's controls beside up next")
        requireClearOfTheControls("player.upNext", "the plain up next at the episode's end")
        shot("upnext-cancelled")
        ready("player.upNext").click()
        requireEpisode("S1E3", video, "a click on up next")
    }

    /// The test addon's own series from its last episode (three 20 s episodes; the stream in a binge group, so the
    /// episode before plays from the same group without asking): Previous episode, then a disabled Next episode, around
    /// back, play/pause and forward (shot as "last episode"); Previous plays the second episode, both enabled (shot as
    /// "middle episode"), and again the first, its Previous disabled (shot as "first episode"), each paused with the
    /// controls up.
    func testEpisodeButtonsPlayThePreviousEpisode() throws {
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:3"])
        ready(require("streams.row.1", timeout: 60, "the last episode's Streams sheet lists the stream in a binge group")).click()
        let video = require("player.video", timeout: 20, "a click on the stream opens the player")
        requirePlayback(1, timeout: 90)
        // Each episode paused as it starts: before its 10 s, where up next would come up.
        requireEpisode("S1E3", video, "the stream picked")
        pause(video)
        requireEpisodeButtons((previous: true, next: false), "the series' last episode")
        requireTransportInOrder("the series' last episode")
        shot("last episode")
        showControls(video)
        ready("player.previous").click()
        requireEpisode("S1E2", video, "Previous episode on the last episode")
        pause(video)
        requireEpisodeButtons((previous: true, next: true), "the series' second episode")
        requireTransportInOrder("the series' second episode")
        shot("middle episode")
        showControls(video)
        ready("player.previous").click()
        requireEpisode("S1E1", video, "Previous episode on the second episode")
        pause(video)
        requireEpisodeButtons((previous: false, next: true), "the series' first episode")
        requireTransportInOrder("the series' first episode")
        shot("first episode")
    }

    /// The test addon's tracks series (episode 1 remux.mkv, episode 2 tracks-next.mkv: the remux's languages in another
    /// order; the stream in a binge group, so each episode plays on without asking; for each episode the addon's own
    /// English subtitles, answered 6 s late, so they arrive after the file opened). The playback info's Subtitles section
    /// tells the addon's English ("English · Tortuga Test") from a file's own ("English"). In episode 1 the German audio
    /// and, once they arrive, the addon's English subtitles picked (shot as "episode 1 picked"); episode 2, played from up
    /// next once episode 1 played past its watched threshold (70 %: 21 s), has the German audio and first its own full
    /// English, the best stand-in while the addon's are on their way, then the addon's once they arrive (the info's
    /// subtitles in turn kept as a note; shot as "episode 2 carried"). Subtitles turned off and the English audio picked
    /// there carry back to episode 1, played by Previous episode, where the languages would show the forced English
    /// subtitles under English audio, and stay off once the addon's English arrives (shot as "episode 1 off"). The French
    /// subtitles and German audio picked there carry past the player's close to episode 2 started from the details, which
    /// resumes it from its stream (the library's pointer rests on it: episode 1 counts as watched, so its replay leaves
    /// the pointer where it was), as that file's own French and German tracks, which the addon's late English leaves
    /// (shot as "episode 2 from the details").
    func testPickedTracksCarryToTheNextEpisode() throws {
        executionTimeAllowance = 600
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-tracks", "streams:series:tortuga-tracks:tortuga-tracks:1:1"])
        ready(require("streams.row.0", timeout: 60, "the first episode's Streams sheet lists its stream")).click()
        let video = require("player.video", timeout: 20, "a click on the stream opens the player")
        requirePlayback(1, timeout: 90)
        requireEpisode("S1E1", video, "the stream picked")
        pause(video)
        pick(from: "player.audio", entry: "German", video: video, "the German audio")
        let addons = openTheAddonsSubtitles(video, "episode 1")
        shot("episode 1 subtitle menu, the addon's English in")
        pickMenuEntry(addons)
        if !waitUntil(5, { !addons.exists }) { fail("picking the addon's English subtitles doesn't close the menu") }
        requireTracks(subtitles: "English", audio: "German", video, "episode 1")
        if !waitUntil(10, { subtitleInfo(video).contains(Self.addonSubtitles) }) {
            fail("episode 1: the playback info names the subtitles '\(subtitleInfo(video))', not the addon's English (\(Self.addonSubtitles))")
        }
        shot("episode 1 picked")
        // Played on past episode 1's watched threshold (70 %: 21 s), the playback info left up, then on to up next. Up
        // next comes 10 s before the end (20 s): a click as it comes lands on either side of 21 s, which would decide
        // whether the library's pointer stays on episode 2 when Previous replays episode 1.
        showControls(video)
        ready("player.playPause").click()
        requirePlayback(22, timeout: 45)
        ready(require("player.upNext", timeout: 5, "the end of episode 1 offers the next one")).click()
        // Episode 2's subtitles as the info names them in turn, from when it names episode 2's file (episode 1's stay
        // until then).
        let file = infoRow("File")
        if !waitUntil(60, { showPlaybackInfo(video); return file.exists && fullText(of: file).contains("tracks-next.mkv") }) {
            fail("Play on up next: the playback info's file reads '\(file.exists ? fullText(of: file) : "nothing")', not episode 2's tracks-next.mkv")
        }
        var named: [String] = []
        let arrived = waitUntil(15) {
            let name = subtitleInfo(video)
            if !name.isEmpty, name != named.last { named.append(name) }
            return name.contains(Self.addonSubtitles)
        }
        attachNote("episode 2's subtitles in turn: \(named.joined(separator: " → "))")
        let first = named.first { $0 != "Off" } ?? "none"
        if !(first.contains("English") && !first.contains("·")) {
            fail("episode 2: the subtitles named first are '\(first)', not the file's own English (in turn: \(named))")
        }
        if !arrived { fail("episode 2: 15 s after it opened the subtitles are '\(named.last ?? "none")', not the addon's English (\(Self.addonSubtitles))") }
        requireEpisode("S1E2", video, "Play on up next")
        pause(video)
        requireTracks(subtitles: "English", audio: "German", video, "episode 2")
        shot("episode 2 carried")
        showControls(video)
        togglePlaybackInfo()
        requireGone("player.info.panel", timeout: 5, "Playback info in the options menu again closes the playback info")
        pick(from: "player.subtitles", entry: "Off", video: video, "subtitles off")
        pick(from: "player.audio", entry: "English", video: video, "the English audio")
        showControls(video)
        ready("player.previous").click()
        requireEpisode("S1E1", video, "Previous episode on episode 2")
        pause(video)
        requireTracks(subtitles: "Off", audio: "English", video, "episode 1 again")
        awaitTheAddonsSubtitles(video, "episode 1 again")
        requireTracks(subtitles: "Off", audio: "English", holding: 2, video, "episode 1 again, the addon's English in")
        shot("episode 1 off")

        // The player closed, the picks carry to the series' next player: episode 2 started from the details.
        pick(from: "player.subtitles", entry: "French", video: video, "the French subtitles")
        pick(from: "player.audio", entry: "German", video: video, "the German audio")
        showControls(video)
        ready("player.back").click()
        requireGone("player.video", "the back button closes the player")
        require("detail.play", "the back button returns to the series' details")
        ready(scrollTo("detail.episode.1", "the series' details list its second episode")).click()
        require("player.video", timeout: 20, "a click on the second episode, left paused, resumes it from its stream")
        requireEpisode("S1E2", video, "the second episode from the details")
        pause(video)
        requireTracks(subtitles: "French", audio: "German", video, "episode 2 from the details")
        awaitTheAddonsSubtitles(video, "episode 2 from the details")
        requireTracks(subtitles: "French", audio: "German", holding: 2, video, "episode 2 from the details, the addon's English in")
        shot("episode 2 from the details")
    }

    /// Settings → Playback → Show subtitles set to Always (shot as "show subtitles always"), then the test addon's movie,
    /// added to the library to come back to it, played from its details on remux.mkv: English audio, whose English
    /// subtitles come forced (the file's default), full and SDH (Matroska's hearing-impaired flag). With no subtitle
    /// language set, Follow the audio would start the forced ones; Always starts the SDH ones, which the playback info's
    /// Subtitles section names (shot as "remux with SDH").
    func testShowSubtitlesAlwaysStartsEnglishAudioWithItsSdhSubtitles() throws {
        try launchDev(addon: true, open: ["board", StreamFilterTexts.movie])
        addMovieToLibrary()
        openSection("playback")
        pickChoice("settings.playback.showSubtitles", option: (id: "ALWAYS", label: "Always"))
        shot("show subtitles always")
        openMovieFromLibrary()
        ready("detail.play").click()
        ready(require("streams.row.0", timeout: 60, "Play opens the Streams screen with the remux")).click()
        let video = require("player.video", "a click on the remux opens the player")
        requirePlayback(1, timeout: 90)
        pause(video)
        requireTracks(subtitles: "English", audio: "English", video, "Show subtitles Always")
        if !waitUntil(10, { subtitleInfo(video).contains("SDH") }) {
            fail("Show subtitles Always: the playback info names the subtitles '\(subtitleInfo(video))', not the English SDH")
        }
        shot("remux with SDH")
    }

    /// The chapters series' first episode (`SkipTestSupport`): over the intro (4–12 s) the skip button offers "Skip
    /// intro" (shot as "skip-button") and a click on it lands at the intro's end; a click on the timeline back in the
    /// intro brings the button again, and Return (the store's Enter) skips it too; played on into the credits (44 s),
    /// up next comes up in the button's place (shot as "credits-upnext"). Reading the position moves the pointer over
    /// the video, which stops a countdown: the skip button here doesn't count down, and up next is shot before it.
    func testSkipButtonSkipsTheIntroByClickAndReturnAndTheCreditsBringUpNext() throws {
        try launchDev(addon: true, open: Self.chaptersFirstEpisode)
        playFirstChaptersEpisode()
        let video = element("player.video")
        requireSkipButton(Self.skipIntroLabel, timeout: 90, "the intro (4 s) brings the skip button up")
        shot("skip-button")
        var start = Date()
        var before = playerPosition() ?? 0
        ready(steadySkipButton()).click()
        requireIntroSkipped(from: before, since: start, "a click on Skip intro")

        showControls(video)
        ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 6 / 60, dy: 0.5)).click()
        requireSkipButton(Self.skipIntroLabel, timeout: 10, "a click on the timeline back in the intro brings the skip button again")
        start = Date()
        before = playerPosition() ?? 0
        app.typeKey(.return, modifierFlags: [])
        requireIntroSkipped(from: before, since: start, "Return over Skip intro")
        requireUpNextAtTheCredits()
    }

    func testH264SpaceAndArrowSeek() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).click()
        require("player.video", "a click on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        shot("playing")

        app.typeKey(" ", modifierFlags: [])
        if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Play" }) {
            fail("Space doesn't pause: play/pause reads \(element("player.playPause").label)")
        }
        let paused = playerPosition() ?? 0
        var now = paused
        if !holds(for: 2, { now = playerPosition() ?? now; return now <= paused + 0.5 }) {
            fail("paused by Space, the position still moved from \(paused) s to \(now) s")
        }
        shot("paused by space")
        app.typeKey(" ", modifierFlags: [])
        if !waitUntil(5, { (playerPosition() ?? 0) > paused + 0.5 }) { fail("Space again doesn't resume from \(paused) s") }

        let before = playerPosition() ?? 0
        app.typeKey(.rightArrow, modifierFlags: [])
        if !waitUntil(5, { (playerPosition() ?? 0) >= before + 4 }) {
            fail("the right arrow from \(before) s reached \(playerPosition() ?? 0) s; expected ≥ \(before + 4) s")
        }
        shot("after the right arrow")
        // Escape backs out as the store's Back does: the controls first while they are up (the position reads above
        // brought them up), then the player.
        for _ in 0..<3 where element("player.video").exists {
            app.typeKey(.escape, modifierFlags: [])
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Escape closes the player")
        require("streams.row.1", "Escape returns to the Streams screen")
    }

    /// The H.264 file on the AVFoundation output, seeking by the arrows forward and back while it plays and back while
    /// paused: no seek moves the display layer's clock (`player.displayClock`, mpv's host clock, which only runs on).
    /// On media time each seek set it to the new position, and on macOS 27 a clock set back behind frames already taken
    /// for display stopped the screen until it caught up with them.
    func testSeeksLeaveTheDisplayLayersClockAlone() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).click()
        let video = require("player.video", "a click on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        let clock = require("player.displayClock", timeout: 5, "the player has the display clock probe")
        let before = clock.label
        func seek(_ key: XCUIKeyboardKey, _ what: String) {
            let from = playerPosition() ?? 0
            app.typeKey(key, modifierFlags: [])
            if !waitUntil(5, { abs((playerPosition() ?? from) - from) >= 3 }) {
                fail("the \(what) arrow from \(from) s left the position at \(playerPosition() ?? 0) s")
            }
        }
        seek(.rightArrow, "right")
        seek(.leftArrow, "left")
        seek(.rightArrow, "right")
        pause(video)
        seek(.leftArrow, "left (paused)")
        app.typeKey(" ", modifierFlags: [])
        _ = holds(for: 2) { true }
        let after = clock.label
        if after != before { fail("the seeks moved the display layer's clock: \(before) before them, \(after) after") }
    }

    /// The H.264 file on the AVFoundation output closed three times: while it plays, paused, and right after a seek.
    /// Each time its display layer leaves the window hidden, and only once mpv is gone (`app.videoTeardown`): on macOS
    /// 27 a visible display layer leaving while mpv tore its video output down could stop the display for good.
    func testClosedPlayerKeepsItsHiddenVideoUntilMpvIsGone() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        for cycle in 1...3 {
            ready(require("streams.row.1", timeout: 60, "cycle \(cycle): the Streams screen lists the H.264 stream")).click()
            let video = require("player.video", timeout: 20, "cycle \(cycle): a click on the H.264 stream opens the player")
            requirePlayback(1, timeout: 60)
            if cycle == 2 { pause(video) }
            if cycle == 3 { app.typeKey(.rightArrow, modifierFlags: []) }
            showControls(video)
            ready("player.back").click()
            requireGone("player.video", "cycle \(cycle): the back button closes the player")
        }
        let teardown = require("app.videoTeardown", timeout: 5, "the app has the video teardown probe")
        let expected = Array(repeating: "hidden mpv=gone", count: 3).joined(separator: "\n")
        if !waitUntil(5, { teardown.label == expected }) {
            fail("the display layer left the window as '\(teardown.label)'; expected 'hidden mpv=gone' for each of 3 closes")
        }
    }

    /// The live test addon's 24/7 channel (`LiveTestSupport`), live from the start: with the controls up no timeline,
    /// times or back and forward, play/pause in the bar's middle as for a video and the live label (● LIVE and the time
    /// watched) right after it, level with it; the left and the right arrow seek nothing (neither the position nor the
    /// time watched goes back); once Escape closed the player, the channel's details offer Play, not Resume, and Home
    /// has no Continue Watching.
    func testLiveChannelShowsLiveIgnoresSeeksAndKeepsNothing() throws {
        try launchDev(addon: true, open: Self.liveChannelOpen)
        ready(livePlainStream("the channel's Streams sheet")).click()
        let video = require("player.video", timeout: 20, "a click on the plain stream opens the player")
        // The Mac's video carries no value: 2 s watched read off the live label, the pointer moving to keep the
        // controls up.
        var low = false
        _ = waitUntil(90) {
            low.toggle()
            pointerOn(video, at: CGVector(dx: 0.5, dy: low ? 0.45 : 0.4))
            let label = element("player.live")
            return label.exists && (Self.seconds(text(of: label)) ?? 0) >= 2
        }
        showControls(video)
        requireNoTimeline("the controls up")
        let before = (position: requireLivePosition("the controls up"), watched: requireLiveLabel("the controls up"))
        let play = element("player.playPause").frame, label = element("player.live").frame
        let bar = element("player.bar.bottom").frame
        if label.minX <= play.maxX || label.minX - play.maxX > 40 || abs(label.midY - play.midY) > 2 {
            fail("the live label at \(label) isn't right after play/pause at \(play), level with it")
        }
        if abs(play.midX - bar.midX) > 4 { fail("play/pause at x \(Int(play.midX)) isn't in the bar's middle (x \(Int(bar.midX)))") }
        shot("live")

        // A second for a seek the left arrow started to land in, then two for the right arrow's: neither the position
        // (the live position probe) nor the time watched (the live label, read while the controls show it) going back
        // meanwhile.
        var now = before
        func seeksIgnored() -> Bool {
            if let value = (try? element("player.livePosition").snapshot())?.label, value.hasPrefix("position="),
               let position = Double(value.dropFirst("position=".count)) {
                now.position = position
            }
            if let live = try? element("player.live").snapshot() {
                let read = live.label.isEmpty ? live.value as? String ?? "" : live.label
                if read.hasPrefix("Live · watched "), let watched = Self.seconds(read) { now.watched = watched }
            }
            return now.position >= before.position && now.watched >= before.watched
        }
        app.typeKey(.leftArrow, modifierFlags: [])
        if !holds(for: 1, seeksIgnored) {
            fail("after the left arrow the live position went from \(before.position) s to \(now.position) s, the time watched from \(before.watched) s to \(now.watched) s")
        }
        app.typeKey(.rightArrow, modifierFlags: [])
        if !holds(for: 2, seeksIgnored) {
            fail("after the left and the right arrow the live position went from \(before.position) s to \(now.position) s, the time watched from \(before.watched) s to \(now.watched) s")
        }
        showControls(video)
        requireSeeksIgnored(since: before, "after the left and the right arrow")
        shot("live after the arrows")

        for _ in 0..<3 where element("player.video").exists {
            app.typeKey(.escape, modifierFlags: [])
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Escape closes the player")
        if element("streams.row.0").exists(within: 3) { app.typeKey(.escape, modifierFlags: []) }
        let details = require("detail.play", timeout: 10, "closing the player returns to the channel's details")
        requireLiveNotResumed(details, "after the player")
        goBack()
        requireNoContinueWatching("Home after the live channel")
        shot("home after live")
    }

    /// The live test addon's match (`LiveTestSupport`), whose playlist keeps a 90-s window: with the controls up the
    /// window's timeline under the control bar, where it starts on the left, no time left or ±10 s buttons, and the
    /// go-to-live button (● LIVE and the time watched) right after play/pause, level with it; a click on the timeline
    /// 30 s back plays the match that far behind the edge, the stream reopened at an earlier segment (● LIVE · −0:36);
    /// a click on the label plays it at the edge again; three presses of the left arrow play it 30 s back again within
    /// what mpv holds, no reopen; once Escape closed the player, the match's details offer Play, not Resume.
    func testLiveMatchRewindsInItsWindowAndGoesToLive() throws {
        try launchDev(addon: true, open: Self.liveMatchOpen)
        ready(require("streams.row.0", timeout: 60, "the match's Streams sheet lists its stream")).click()
        let video = require("player.video", timeout: 20, "a click on the match's stream opens the player")
        // The Mac's video carries no value: the live label read with the pointer moving to keep the controls up.
        _ = waitUntil(90) { liveBehind() == 0 }
        requireAtLiveEdge("the match opened")
        showControls(video)
        requireLiveWindow("the controls up")
        let play = element("player.playPause").frame, label = element("player.live").frame
        if label.minX <= play.maxX || label.minX - play.maxX > 40 || abs(label.midY - play.midY) > 2 {
            fail("the live label at \(label) isn't right after play/pause at \(play), level with it")
        }
        shot("live match at the edge")

        requireSeekBack(reopened: true, "a click on the timeline 30 s back") {
            showControls(video)
            ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 1 - 30 / Self.liveMatchWindow, dy: 0.5)).click()
        }
        showControls(video)
        shot("live match behind the edge")

        showControls(video)
        ready("player.live").click()
        requireAtLiveEdge("after a click on the live label")
        showControls(video)
        shot("live match back at the edge")
        requireSeekBack(reopened: false, "three presses of the left arrow") {
            for _ in 0..<3 { app.typeKey(.leftArrow, modifierFlags: []) }
        }

        for _ in 0..<3 where element("player.video").exists {
            app.typeKey(.escape, modifierFlags: [])
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Escape closes the player")
        if element("streams.row.0").exists(within: 3) { app.typeKey(.escape, modifierFlags: []) }
        let details = require("detail.play", timeout: 10, "closing the player returns to the match's details")
        requireLiveNotResumed(details, "after the player")
    }

    /// The watch-together room sheet's one button, Close, loses nothing and sits in its bar's cancellation slot: Return,
    /// the default button's key, leaves the sheet up, and Escape closes it with nothing in it focused, the player
    /// staying. The live channel's sheet, which says a live stream can't start a room, needs no relay.
    func testRoomSheetClosesByEscapeNotByReturn() throws {
        try launchDev(addon: true, open: Self.liveChannelOpen)
        ready(livePlainStream("the channel's Streams list its plain stream")).click()
        let video = require("player.video", timeout: 30, "the plain stream opens the player")
        showControls(video)
        ready("player.more").click()
        pickMenuEntry(menuEntry("Watch Together", "the options menu offers Watch together"))
        require("player.watch.panel", timeout: 10, "Watch together opens the room sheet")
        require("player.watch.close", "the room sheet has Close")
        shot("room sheet")
        app.typeKey(.return, modifierFlags: [])
        if !holds(for: 2, { element("player.watch.panel").exists }) { fail("Return closes the room sheet: Close is its default button") }
        app.typeKey(.escape, modifierFlags: [])
        requireGone("player.watch.panel", timeout: 5, "Escape closes the room sheet")
        if !element("player.video").exists { fail("Escape on the room sheet left the player") }
    }

    /// The display stays awake while the video plays and may sleep once it is paused or the player is closed: this
    /// Mac's power assertions (`displayAssertions`) list the app's PreventUserIdleDisplaySleep while the H.264 file
    /// plays, none once Space has paused it, one again once Space plays it on, and none once Escape closed the player.
    func testPlayingKeepsTheDisplayAwakeAndPausingLetsItSleep() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).click()
        require("player.video", "a click on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        if try displayAssertions("playing").isEmpty { fail("the video plays and the app holds no display assertion") }

        app.typeKey(" ", modifierFlags: [])
        if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Play" }) {
            fail("Space doesn't pause: play/pause reads \(element("player.playPause").label)")
        }
        let held = try displayAssertions("paused")
        if !held.isEmpty { fail("paused, the app still holds a display assertion: \(held)") }

        let paused = playerPosition() ?? 0
        app.typeKey(" ", modifierFlags: [])
        if !waitUntil(5, { (playerPosition() ?? 0) > paused + 0.5 }) { fail("Space again doesn't resume from \(paused) s") }
        if try displayAssertions("playing again").isEmpty { fail("the video plays on and the app holds no display assertion") }

        for _ in 0..<3 where element("player.video").exists {
            app.typeKey(.escape, modifierFlags: [])
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Escape closes the player")
        let left = try displayAssertions("player closed")
        if !left.isEmpty { fail("the player closed and the app still holds a display assertion: \(left)") }
    }

    /// A click on the H.264 file's video follows Settings → Playback's Clicking the video, the pointer left where
    /// `showControls` puts it (a move would bring the controls up). By default a click pauses once the double-click
    /// interval has passed, and another plays. The row reads Pause and play (shot as "video click row", its menu open as
    /// "video click menu"); set to Show or hide the controls (the dev profile kept for the next launch, `--dev-data`), a
    /// click with the controls up hides them within 1.5 s, where their own timer takes 3 s from the pointer's last move,
    /// the video playing on, and another click brings them back; a double click enters full screen and another leaves
    /// it, neither pausing; Space still pauses, and a click while paused leaves the controls up and the video paused.
    func testClickOnTheVideoPausesOrShowsAndHidesTheControls() throws {
        let url = try addonURL
        let data = freshDataName()
        addTeardownBlock { [weak self] in
            guard let self, self.app.state == .runningForeground, self.isFullScreen else { return }
            try? self.leaveFullScreen()
        }
        let video = element("player.video")
        let playPause = element("player.playPause")
        func middle() -> XCUICoordinate { ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)) }
        func requirePlayPause(_ label: String, _ what: String) {
            if !waitUntil(5, { playPause.exists && playPause.label == label }) {
                fail("\(what): play/pause reads \(playPause.exists ? playPause.label : "nothing (no controls)"), not \(label)")
            }
        }
        func playTheH264File(_ what: String) throws {
            launchDev(keepingData: data, addon: url, open: ["streams:movie:tt0111161:tt0111161"])
            if isFullScreen {
                try leaveFullScreen()
                requireFullScreen(false, "\(what): the window launched full screen")
            }
            ready(require("streams.row.1", timeout: 60, "\(what): the Streams screen lists the H.264 stream")).click()
            require("player.video", "\(what): a click on the H.264 stream opens the player")
            requirePlayback(1, timeout: 90)
        }

        try playTheH264File("by default")
        showControls(video)
        middle().click()
        requirePlayPause("Play", "a click on the video, by default")
        middle().click()
        requirePlayPause("Pause", "a second click on the paused video, by default")
        app.terminate()

        // Settings → Playback, from a launch on the Board (the Streams screen opened by itself has no sidebar):
        // Clicking the video, last in the Playback group, shown with the Skipping group's first row below it (the
        // pointer scrolls over the page's form; the window's centre may lie outside it).
        launchDev(keepingData: data, addon: url)
        openSection("playback")
        let row = "settings.playback.videoClick"
        let intros = require("settings.playback.skipIntros", "the Playback page has the Skipping group")
        let form = app.scrollViews.containing(.any, identifier: "settings.playback.audioLanguages").firstMatch
        scrollTo(intros, over: form, steps: 10, "the Playback page scrolled down to the Skipping group")
        let picker = scrollTo(require(row, "the Playback page lists Clicking the video"), over: form, "the Playback page shows Clicking the video")
        requireText(row, contains: "Pause and play", "Clicking the video starts at Pause and play")
        shot("video click row")
        ready(picker).click()
        let controls = menuEntry("Show or hide the controls", "Clicking the video's menu")
        menuEntry("Pause and play", "Clicking the video's menu")
        shot("video click menu")
        pickMenuEntry(controls)
        if !waitUntil(5, { !controls.exists }) { fail("picking Show or hide the controls doesn't close the menu") }
        requireText(row, contains: "Show or hide the controls", "Clicking the video shows the choice picked")
        app.terminate()

        // Show or hide the controls: from the file's start (Home), so the 20 s file doesn't run out meanwhile.
        try playTheH264File("set to show or hide the controls")
        app.typeKey(.home, modifierFlags: [])
        showControls(video)
        let position = require("player.position", timeout: 5, "the controls show the position")
        let before = Self.seconds(text(of: position)) ?? 0
        middle().click()
        if !waitUntil(1.5, { !playPause.exists }) { fail("a click on the video with the controls up leaves them up 1.5 s later") }
        if !holds(for: 1, { !playPause.exists }) { fail("the controls a click hid came back without a move or a click") }
        middle().click()
        if !playPause.exists(within: 1.5) { fail("a click on the video with the controls hidden doesn't bring them back") }
        requirePlayPause("Pause", "two clicks on the video, set to show or hide the controls")
        let after = Self.seconds(text(of: require("player.position", timeout: 5, "the controls a click brought back show the position"))) ?? 0
        if after < before + 1 { fail("the video didn't play on while a click hid the controls: at \(before) s, then \(after) s") }

        app.typeKey(.home, modifierFlags: [])
        middle().doubleClick()
        requireFullScreen(true, "a double click on the video")
        showControls(video)
        requirePlayPause("Pause", "in full screen by a double click")
        middle().doubleClick()
        requireFullScreen(false, "a double click on the video in full screen")
        showControls(video)
        requirePlayPause("Pause", "out of full screen by a double click")

        app.typeKey(" ", modifierFlags: [])
        requirePlayPause("Play", "Space, with a click set to show or hide the controls")
        middle().click()
        if !holds(for: 1.5, { (try? playPause.snapshot())?.label == "Play" }) {
            fail("a click on the paused video changed it: play/pause reads \(playPause.exists ? playPause.label : "nothing (no controls)")")
        }
    }

    /// Picture in picture by the button beside back: the button reads as selected (VoiceOver: "selected") while PiP
    /// runs, and not once the same button has ended it; then PiP with the app hidden, the PiP window's pause and play
    /// acting on the player, and Escape closing the player in PiP, the window going with it and the video's display
    /// layer leaving the app's window hidden and only once mpv is gone (`app.videoTeardown`, as for a player closed
    /// outside PiP).
    func testPictureInPicture() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the remux")).click()
        let video = require("player.video", "a click on the remux opens the player")
        requirePlayback(2, timeout: 90)
        showControls(video)
        let pip = require("player.pip", timeout: 5, "the controls have a picture in picture button")
        let back = require("player.back", timeout: 5, "the controls have the back button").frame
        if abs(pip.frame.midY - back.midY) > 4 || pip.frame.minX < back.maxX {
            fail("the picture in picture button (\(pip.frame)) isn't beside the back button (\(back))")
        }
        if !waitUntil(10, { pip.exists && pip.isEnabled }) { fail("picture in picture stays unavailable with the video playing") }
        shot("player with the picture in picture button")

        ready(pip).click()
        if !waitUntil(10, { pip.exists && pip.isSelected }) {
            fail("the button doesn't start picture in picture: it doesn't read as selected")
        }
        let entered = playerPosition() ?? 0
        if !waitUntil(10, { (playerPosition() ?? 0) >= entered + 2 }) {
            fail("in picture in picture the video went from \(entered) s only to \(playerPosition() ?? 0) s")
        }
        screenShot("picture in picture over the player")

        // Back into the player by the same button.
        stopPictureInPicture(video, "the first picture in picture")
        if pictureInPictureSelected(video) == true { fail("once picture in picture ended the button still reads as selected") }
        shot("player after picture in picture")

        // In PiP again, the app hidden: the PiP window stays on the desktop, and its own pause and play act on the
        // player.
        showControls(video)
        if !waitUntil(5, { element("player.pip").exists && element("player.pip").isEnabled }) { fail("picture in picture is unavailable after returning from it") }
        ready("player.pip").click()
        if !waitUntil(10, { element("player.pip").exists && element("player.pip").isSelected }) { fail("picture in picture doesn't start again") }
        app.typeKey("h", modifierFlags: .command)
        // Out of the front, hidden; the PiP window plays on over the desktop, so the screen doesn't settle.
        _ = waitUntil(5) { app.state == .runningBackground }
        screenShot("picture in picture over the desktop")
        let window = pictureInPictureWindow()
        attachTree(window, "picture in picture window")
        app.activate()
        showControls(video)
        // A click on the PiP window brings PIPAgent, the window's program, to the front.
        withAnotherAppInFront {
            ready(pictureInPictureButton(in: window, "Pause"), ofAnotherApp: true).click()
            if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Play" }) {
                fail("the PiP window's pause doesn't pause the player: play/pause reads \(element("player.playPause").label)")
            }
            ready(pictureInPictureButton(in: window, "Play"), ofAnotherApp: true).click()
            if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Pause" }) {
                fail("the PiP window's play doesn't resume the player: play/pause reads \(element("player.playPause").label)")
            }
        }

        // The player closed while the video is in PiP (Escape backs out of the controls, then the player): the
        // window goes with the playback, and the app stays up.
        app.activate()
        for _ in 0..<3 where element("player.video").exists {
            app.typeKey(.escape, modifierFlags: [])
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Escape closes the player in picture in picture")
        require("streams.row.0", "closing the player returns to the Streams screen")
        if !holds(for: 3, { app.state == .runningForeground }) {
            fail("the app quit after the player closed in picture in picture (state \(app.state.rawValue))")
        }
        if pictureInPictureWindow().exists { fail("the picture in picture window stays after the player closed") }
        let teardown = require("app.videoTeardown", timeout: 5, "the app has the video teardown probe")
        if !waitUntil(5, { teardown.label == "hidden mpv=gone" }) {
            fail("closed in picture in picture, the display layer left the window as '\(teardown.label)'; expected 'hidden mpv=gone'")
        }
        screenShot("player closed in picture in picture")
    }

    /// The controls drawn as a symbol alone show their names in a tooltip (XCUITest gives a tooltip's frame, not its
    /// text: the shots show the text): the pointer resting on back and picture in picture (on the title row), mute and
    /// full screen (buttons of the control bar) and the speed menu brings one up beside each; mute still mutes by a
    /// click. Before the player: Detail's Share (plain glass), an episode's ⋯ (a custom style, as the player's controls),
    /// the Streams screen's Refresh (the window's toolbar) and a stream's download button. Share and the library button
    /// (plain glass) with the pointer resting where each comes up as Detail opens again: its tooltip shows, goes once
    /// the pointer leaves (no tooltip stays up anywhere), and comes back with the pointer.
    func testSymbolControlsShowTheirNamesInTooltips() throws {
        var seen: [(String, Bool)] = []
        var last = "nothing"
        func probe(_ control: XCUIElement, _ name: String) {
            seen.append((name, tooltipShows(on: ready(control), after: last)))
            last = name
        }
        try launchDev(addon: true, open: ["detail:movie:tt0111161"])
        probe(require("detail.share", timeout: 60, "Detail shows Share"), "detail.share")
        shot("tooltip of detail.share")
        for id in ["detail.share", "detail.library"] {
            ready(id).hover()
            try launchDev(addon: true, open: ["detail:movie:tt0111161"])
            let control = require(id, timeout: 60, "Detail shows \(id) again")
            seen.append(("\(id) as Detail opens under the pointer", waitUntil(10, { tooltipBeside(control) })))
            last = id
            probe(control, "\(id) once the pointer comes back")
            shot("tooltip of \(id) once the pointer comes back")
        }
        try launchDev(addon: true, open: ["detail:series:tortuga-series"])
        require("detail.play", timeout: 60, "the series' details show Play")
        probe(scrollTo("detail.episode.0.menu", "the series lists its first episode with its ⋯ button"), "detail.episode.0.menu")
        shot("tooltip of detail.episode.0.menu")
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        probe(require("streams.row.1.download", timeout: 60, "the Streams screen offers to download the H.264 stream"), "streams.row.1.download")
        probe(require("streams.refresh", "the Streams screen's toolbar has Refresh"), "streams.refresh")
        shot("tooltip of streams.refresh")
        ready("streams.row.1").click()
        let video = require("player.video", "a click on the H.264 stream opens the player")
        requirePlayback(1, timeout: 90)
        // Paused, the controls stay up.
        pause(video)
        for id in ["player.back", "player.pip", "player.mute", "player.fullscreen", "player.speed"] {
            probe(element(id), id)
            shot("tooltip of \(id)")
        }
        attachTree(app, "player with its tooltips")
        let mute = ready("player.mute")
        let muteLabel = mute.label
        mute.click()
        if !waitUntil(5, { element("player.mute").label != muteLabel }) { fail("a click on mute leaves it reading \(muteLabel)") }
        let missing = seen.filter { !$0.1 }.map(\.0)
        if !missing.isEmpty { fail("no tooltip comes up beside \(missing.joined(separator: ", ")) (beside \(seen.filter(\.1).map(\.0).joined(separator: ", ")) one does)") }
    }

    /// The scroll wheel over the video turns the volume, as on Windows and Linux: scrolled one way, then the other, the
    /// volume message (`player.osd`) reads a level each time, the second another than the first (from full volume the
    /// way up stays there); over the controls the wheel shows nothing.
    func testScrollWheelOverTheVideoTurnsTheVolume() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).click()
        let video = require("player.video", "a click on the H.264 stream opens the player")
        requirePlayback(1, timeout: 90)
        let message = element("player.osd")
        let level = { message.exists ? Int(self.text(of: message).filter(\.isNumber)) : nil }
        let spot = ready(video).coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.4))
        pointerOn(video, at: CGVector(dx: 0.3, dy: 0.4))
        spot.scroll(byDeltaX: 0, deltaY: -100)
        var first: Int?
        if !waitUntil(5, { first = level(); return first != nil }) { fail("the wheel over the video shows no volume message") }
        spot.scroll(byDeltaX: 0, deltaY: 100)
        var second: Int?
        if !waitUntil(5, { second = level(); return second != nil && second != first }) {
            fail("the wheel the other way over the video leaves the volume message at \(second.map(String.init) ?? "nothing"), as the first way had it (\(first ?? 0))")
        }
        requireGone("player.osd", timeout: 5, "the volume message")
        showControls(video)
        let controls = ready("player.playPause")
        controls.hover()
        controls.scroll(byDeltaX: 0, deltaY: -100)
        if !holds(for: 2, { !message.exists }) { fail("the wheel over play/pause shows \(text(of: message))") }
    }

    /// Picture in picture shows the whole picture, letterboxed to fit (its shape kept, nothing cut off): the 16:9 H.264
    /// file (`tortuga-hdr`'s SDR) in the
    /// PiP window at its first size, made larger and smaller by its corner, with the player's window narrowed by its
    /// corner and moved, and started from full screen; the 4:3 clip with the player at Fill screen and 150 % zoom, at the
    /// window's first size and made larger. The test pattern's first row of colour bars tells where the picture lies
    /// (`pictureOfTheTestPattern`). Back in the player the 16:9 picture shows whole again in Letterbox, and the 4:3
    /// clip's paused picture as it was at Fill screen and 150 % before. The PiP window's corner and the player's window
    /// are dragged through the run's hook (`drag-<n>`), which posts the drags from an ssh session: XCUITest's own drags
    /// don't move windows.
    func testPictureInPictureShowsTheWholePicture() throws {
        executionTimeAllowance = 1500
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        addTeardownBlock { [weak self] in
            guard let self, self.app.state == .runningForeground, self.isFullScreen else { return }
            try? self.leaveFullScreen()
        }
        let wide = TestPattern(aspect: 16.0 / 9, barsTop: 0.2444)
        let narrow = TestPattern(aspect: 4.0 / 3, barsTop: 0.1833)
        try launchDev(addon: true, open: ["streams:movie:tortuga-hdr:tortuga-hdr"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the SDR stream")).click()
        var video = require("player.video", "a click on the SDR stream opens the player")
        requirePlayback(0.5, timeout: 90)
        pick(from: "player.subtitles", entry: "English", video: video, "the addon's English subtitles")
        requireWholePicture(wide, inPlayer: video, "16:9: the player before picture in picture")

        playFromNearTheStart(video)
        startPictureInPicture(video, "16:9")
        requireWholePicture(wide, inPictureInPicture: "16:9: the PiP window at its first size")
        pause(video)
        requireWholePicture(wide, inPictureInPicture: "16:9: the PiP window paused")
        resizePictureInPicture(by: 160, exchange, "16:9")
        requireWholePicture(wide, inPictureInPicture: "16:9: the PiP window made larger")
        resizePictureInPicture(by: -240, exchange, "16:9")
        requireWholePicture(wide, inPictureInPicture: "16:9: the PiP window made smaller")
        narrowWindow(video)
        requireWholePicture(wide, inPictureInPicture: "16:9: the player's window narrowed")
        moveWindow(exchange, "16:9")
        requireWholePicture(wide, inPictureInPicture: "16:9: the player's window moved")
        stopPictureInPicture(video, "16:9")
        playFromNearTheStart(video)
        requireWholePicture(wide, inPlayer: video, "16:9: back in the narrowed player")
        startPictureInPicture(video, "16:9 from the narrowed player")
        requireWholePicture(wide, inPictureInPicture: "16:9: the PiP window from the narrowed player")
        stopPictureInPicture(video, "16:9 from the narrowed player")

        showControls(video)
        ready("player.fullscreen").click()
        requireFullScreen(true, "16:9: the player's full screen button")
        playFromNearTheStart(video)
        startPictureInPicture(video, "16:9 from full screen")
        requireWholePicture(wide, inPictureInPicture: "16:9: the PiP window from full screen")
        stopPictureInPicture(video, "16:9 from full screen")
        playFromNearTheStart(video)
        requireWholePicture(wide, inPlayer: video, "16:9: back in the full-screen player")
        showControls(video)
        ready("player.fullscreen").click()
        requireFullScreen(false, "16:9: the player's full screen button again")
        app.terminate()

        try launchDev(addon: true, open: ["streams:movie:tortuga-4x3:tortuga-4x3"], settings: ["video.fit=cover"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the 4:3 clip")).click()
        video = require("player.video", "a click on the 4:3 clip opens the player")
        requirePlayback(0.5, timeout: 90)
        pick(from: "player.subtitles", entry: "English", video: video, "the 4:3 clip's English subtitles")
        pickOption("Zoom", "150%", video: video)
        pause(video)
        let zoomed = pictureGrid(video)
        shot("4:3: Fill screen at 150 % before picture in picture")
        playFromNearTheStart(video)
        startPictureInPicture(video, "4:3")
        requireWholePicture(narrow, inPictureInPicture: "4:3 at Fill screen and 150 %: the PiP window at its first size")
        pause(video)
        resizePictureInPicture(by: 160, exchange, "4:3")
        requireWholePicture(narrow, inPictureInPicture: "4:3 at Fill screen and 150 %: the PiP window made larger")
        stopPictureInPicture(video, "4:3")
        let back = pictureGrid(video)
        shot("4:3: back from picture in picture")
        let share = changedShare(zoomed, back, rows: 0...1)
        attachNote("4:3: \(Int(share * 100)) % of the paused picture changed from before picture in picture")
        if share > 0.1 {
            fail("4:3: back in the player \(Int(share * 100)) % of the paused picture differs from Fill screen at 150 % before picture in picture")
        }
        app.terminate()
    }

    /// The test addon's HDR movie (`tortuga-hdr`): its H.264 SDR, HEVC HDR10 and Dolby Vision 8.1 files
    /// in turn, each with the addon's English SRT picked. The window is narrowed by its corner while the first one plays,
    /// so no fit matches the picture's shape. In each file the English line shows (the paused picture differs from the
    /// one with subtitles Off below its top half, and again once they are picked back),
    /// Fill screen, Stretch and 150 % zoom from the options menu each change the paused picture from Letterbox, the
    /// frames advance (two moments of the playing picture differ above its subtitles) in the window, picture in picture,
    /// where the button offers it, plays the video on in the system's window and gives it back, and the frames advance in
    /// full screen; Escape then closes the player, and the app is still up. A failure in full screen leaves full screen in
    /// teardown, so the next launch finds a window.
    func testPlaysSDRHDR10AndDolbyVision() throws {
        executionTimeAllowance = 900
        addTeardownBlock { [weak self] in
            guard let self, self.app.state == .runningForeground, self.isFullScreen else { return }
            try? self.leaveFullScreen()
        }
        try launchDev(addon: true, open: ["streams:movie:tortuga-hdr:tortuga-hdr"])
        let files = [(row: 0, name: "SDR"), (row: 1, name: "HDR10"), (row: 2, name: "Dolby Vision")]
        for file in files {
            ready(require("streams.row.\(file.row)", timeout: 60, "the Streams screen lists the \(file.name) stream")).click()
            let video = require("player.video", "a click on the \(file.name) stream opens the player")
            requirePlayback(0.5, timeout: 90)
            if narrowedBy == 0 { narrowWindow(video) }
            // Paused while the menus are worked, so the 20 s file keeps its lines ahead and the controls stay up.
            pause(video)
            pick(from: "player.subtitles", entry: "English", video: video, "the addon's English subtitles")

            // Paused on a line: the line is on the picture until the subtitles are switched off, and back once they are
            // picked again.
            pauseOnSubtitleLine(video, lines: [1.0...4.0, 5.5...9.0, 10.5...14.0])
            let withLine = pictureGrid(video)
            shot("\(file.name): paused on a subtitle line")
            pick(from: "player.subtitles", entry: "Off", video: video, "subtitles off")
            let off = pictureGrid(video)
            let changed = changedCells(withLine, off, rows: 0.5...1)
            if changed < 4 { fail("\(file.name): switching the subtitles off changed nothing where the line was (\(changed) cells)") }
            pick(from: "player.subtitles", entry: "English", video: video, "the addon's English subtitles again")

            // Each fit and the zoom against Letterbox, on the paused picture.
            let letterbox = pictureGrid(video)
            shot("\(file.name): Letterbox")
            let back = changedCells(off, letterbox, rows: 0.5...1)
            if back < 4 { fail("\(file.name): the subtitles picked again while paused show no line (\(back) cells)") }
            for (option, choice) in [("Aspect ratio", "Fill screen"), ("Aspect ratio", "Stretch"), ("Zoom", "150%")] {
                if option == "Zoom" { pickOption("Aspect ratio", "Letterbox", video: video) }
                pickOption(option, choice, video: video)
                let share = changedShare(letterbox, pictureGrid(video), rows: 0...1)
                shot("\(file.name): \(choice)")
                if share < 0.03 { fail("\(file.name): \(choice) changed \(Int(share * 100)) % of the picture from Letterbox") }
            }
            pickOption("Zoom", "Reset", reads: "100%", video: video)

            // Playing from near the start in the window.
            playFromNearTheStart(video)
            requireFramesAdvance(video, "\(file.name) in the window")
            shot("\(file.name): playing with subtitles")

            // Picture in picture, where the button offers it.
            playFromNearTheStart(video)
            showControls(video)
            if waitUntil(10, { element("player.pip").exists && element("player.pip").isEnabled }) {
                ready("player.pip").click()
                if !waitUntil(10, { pictureInPictureSelected(video) == true }) {
                    fail("\(file.name): the button doesn't start picture in picture: it doesn't read as selected")
                }
                if !pictureInPictureWindow().exists(within: 10) { fail("\(file.name): no picture in picture window") }
                let entered = playerPosition() ?? 0
                if !waitUntil(10, { (playerPosition() ?? 0) >= entered + 2 }) {
                    fail("\(file.name): in picture in picture the video went from \(entered) s only to \(playerPosition() ?? 0) s")
                }
                screenShot("\(file.name): picture in picture")
                stopPictureInPicture(video, file.name)
                shot("\(file.name): back from picture in picture")
            } else {
                attachNote("\(file.name): picture in picture not offered (the button stays disabled)")
            }

            // Full screen and back.
            playFromNearTheStart(video)
            showControls(video)
            let windowed = video.frame
            ready("player.fullscreen").click()
            if !waitUntil(10, { element("player.video").exists && element("player.video").frame.width > windowed.width + 100 }) {
                fail("\(file.name): full screen leaves the video at \(element("player.video").frame) (windowed \(windowed))")
            }
            requireFullScreen(true, "\(file.name): the player's full screen button")
            playFromNearTheStart(video)
            requireFramesAdvance(video, "\(file.name) in full screen")
            shot("\(file.name): full screen")
            showControls(video)
            ready("player.fullscreen").click()
            if !waitUntil(10, { element("player.video").exists && abs(element("player.video").frame.width - windowed.width) < 2 }) {
                fail("\(file.name): leaving full screen leaves the video at \(element("player.video").frame), not \(windowed)")
            }
            requireFullScreen(false, "\(file.name): the player's full screen button again")

            for _ in 0..<3 where element("player.video").exists {
                app.typeKey(.escape, modifierFlags: [])
                _ = waitUntil(3) { !element("player.video").exists }
            }
            requireGone("player.video", "Escape closes the \(file.name) player")
            if app.state != .runningForeground { fail("the app quit when the \(file.name) player closed (state \(app.state.rawValue))") }
        }
    }

    /// Full screen on the H.264 file, the screen shot at each step. With the controls up the window's buttons stay where
    /// the title bar has them in a window, on the title row before the back button, which stays where it was too;
    /// minimize is greyed out. Playing on without a move they fade out with the controls, and a move brings both back.
    /// The top edge pushed (the run's pointer hook) brings down the menu bar and the title bar with its own buttons
    /// over the title row (shot paused and playing). The green button, Escape and the player's button leave full screen
    /// (entered by the player's button, F and the View menu), each time with the buttons where the window had
    /// them; the player closed in full screen hands the window back with its toolbar. The green button on the title row
    /// in full screen takes a click as a mouse makes it (`clickPointer`): XCUITest reports that copy of it not hittable
    /// from the moment the window is full screen, while it does the close, back and picture in picture buttons beside
    /// it under the same windows.
    func testFullScreenKeepsTheWindowButtonsWithTheControls() throws {
        executionTimeAllowance = 600
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        // macOS brings a window killed in full screen back full screen: this run starts in a window, whatever the one
        // before left, and leaves full screen should it stop there.
        addTeardownBlock { [weak self] in
            guard let self, self.app.state == .runningForeground, self.isFullScreen else { return }
            try? self.leaveFullScreen()
        }
        if isFullScreen {
            try leaveFullScreen()
            requireFullScreen(false, "the window launched full screen")
        }
        let row = require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")
        let toolbar = app.toolbars.firstMatch.exists
        ready(row).click()
        let video = require("player.video", "a click on the H.264 stream opens the player")
        requirePlayback(1, timeout: 90)
        // Paused, the controls stay up.
        pause(video)
        requireWindowButtons(nil, "in a window with the controls up")
        let windowed = windowButtonsOnScreen()
        let back = relativeFrame(of: require("player.back", timeout: 5, "in a window the controls have the back button"))
        shot("window, controls up", of: XCUIScreen.main)

        ready("player.fullscreen").click()
        requireFullScreen(true, "the player's full-screen button")
        requireWindowButtons(windowed, "in full screen with the controls up")
        let fullScreenBack = relativeFrame(of: require("player.back", timeout: 5, "in full screen the controls have the back button"))
        if abs(fullScreenBack.minX - back.minX) > 1 || abs(fullScreenBack.midY - back.midY) > 1 {
            fail("in full screen the back button sits at \(fullScreenBack), in a window at \(back)")
        }
        requireCloseButtonsShown(below: 0, "in full screen with the controls up")
        shot("full screen, controls up", of: XCUIScreen.main)

        // Playing without a move, the controls hide after a few seconds; the buttons go with them (shot as they fade).
        // A move brings both back. Each time it plays, the 20 s file starts over first (Home), so it doesn't run out
        // meanwhile.
        app.typeKey(.home, modifierFlags: [])
        app.typeKey(" ", modifierFlags: [])
        let playPause = element("player.playPause")
        if !waitUntil(15, { !playPause.exists }) { fail("in full screen the controls stay up while the video plays without a move") }
        shotsOfTheFade("full screen, controls fading out")
        // The fade over: the window's buttons gone from the screen with the controls.
        _ = waitUntil(2) { windowButtonsOnScreen().values.allSatisfy(\.isEmpty) && closeButtonsShown().isEmpty }
        let hidden = windowButtonsOnScreen()
        if hidden.values.contains(where: { !$0.isEmpty }) { fail("in full screen with the controls hidden, the window's buttons stay: \(hidden)") }
        let shown = closeButtonsShown()
        if !shown.isEmpty { fail("in full screen with the controls hidden, the screen shows close buttons at \(shown)") }
        shot("full screen, controls hidden", of: XCUIScreen.main)
        pointerOn(ready(video), at: CGVector(dx: 0.4, dy: 0.5))
        shotsOfTheFade("full screen, controls fading in")
        // Paused, the controls stay up while XCUITest reads the buttons.
        pause(video)
        requireWindowButtons(windowed, "in full screen with the controls brought back by a move")

        // The top edge pushed brings down the menu bar and the title bar, which shows its own buttons over the title
        // row: paused with the controls up, then playing without them; never an empty bar, never a second set.
        try pushTopEdge()
        attachWindowButtons("the top edge pushed, paused")
        requireCloseButtonsShown(below: Self.menuBarBottom, "at the top edge pushed, paused")
        shot("full screen, the top edge pushed, paused", of: XCUIScreen.main)
        app.typeKey(.home, modifierFlags: [])
        app.typeKey(" ", modifierFlags: [])
        if !waitUntil(15, { !element("player.playPause").exists }) { fail("at the top edge, playing, the controls stay up") }
        // The controls' copies of the window's buttons fade out after them, leaving the title bar's close button.
        _ = waitUntil(2) { closeButtonsShown().count == 1 }
        requireCloseButtonsShown(below: Self.menuBarBottom, "at the top edge pushed, playing")
        shot("full screen, the top edge pushed, playing", of: XCUIScreen.main)
        showControls(video)
        // The pointer off the top edge: the title bar goes back up (no close button below the menu bar).
        _ = waitUntil(5) { !closeButtonsShown().contains { $0.y > Self.menuBarBottom } }

        // The green button (zoom) leaves full screen.
        pause(video)
        let green = requireWindowButtons(windowed, "in full screen, back from the top edge")
        var missed: [String] = []
        try clickPointer(on: green.frame, "in full screen, back from the top edge: the green button", &missed, fallback: { self.app.typeKey(.escape, modifierFlags: []) }) { !isFullScreen }
        if !missed.isEmpty { fail(missed.joined(separator: "\n")) }
        requireFullScreen(false, "the green button in full screen")
        // The pointer off the green button, whose menu would come up on hover.
        showControls(video)
        requireWindowButtons(windowed, "in a window again, after the green button")
        shot("back in a window", of: XCUIScreen.main)

        // F and Escape: in and out again.
        app.typeKey("f", modifierFlags: [])
        requireFullScreen(true, "F")
        requireWindowButtons(windowed, "in full screen from F")
        app.typeKey(.escape, modifierFlags: [])
        requireFullScreen(false, "Escape in full screen")
        if !element("player.video").exists { fail("Escape in full screen closed the player") }
        requireWindowButtons(windowed, "in a window again, after Escape")

        // The View menu's full screen, left by the player's button.
        ready(app.menuBars.menuBarItems["View"]).click()
        pickMenuEntry(app.menuBars.menuItems["Enter Full Screen"])
        requireFullScreen(true, "the View menu's Enter Full Screen")
        requireWindowButtons(windowed, "in full screen from the View menu")
        ready("player.fullscreen").click()
        requireFullScreen(false, "the player's exit full screen button")
        requireWindowButtons(windowed, "in a window again, after the player's button")

        // The player closed in full screen leaves it (the app's PlaybackController): the window is the screens' again,
        // with one set of buttons and its toolbar.
        ready("player.fullscreen").click()
        requireFullScreen(true, "the player's full-screen button again")
        requireWindowButtons(windowed, "in full screen again")
        ready("player.back").click()
        requireGone("player.video", "the back button closes the player in full screen")
        require("streams.row.1", "the back button in full screen returns to the Streams screen")
        requireFullScreen(false, "the player closed in full screen")
        attachWindowButtons("the player closed in full screen")
        let screens = windowButtonsOnScreen()
        if screens.values.contains(where: { $0.count != 1 }) { fail("the Streams screen's window, out of full screen, has buttons \(screens), not one set") }
        if toolbar, !app.toolbars.firstMatch.exists { fail("the Streams screen's window, out of full screen, has lost its toolbar") }
        shot("player closed in full screen, streams in a window", of: XCUIScreen.main)
    }

    /// Force frame rate switching on, on the simulated display of a MacBook Pro (`-simulateDisplayModes YES`: ProMotion
    /// at 120 Hz with fixed 60, 59.94, 50, 48 and 47.95 Hz variants), whose rates make This device list the switch:
    /// the H.264 file (23.976 fps) leaves the display at ProMotion in a window, switches it to 47.95 Hz in full screen
    /// and back to ProMotion out of it, back again once the player closes in full screen, and opened on a full-screen
    /// window switches it as it loads.
    func testFullScreenSwitchesTheDisplayToTheVideosRateAndBack() throws {
        executionTimeAllowance = 600
        let settings = ["video.matchFrameRate=true"]
        let simulated = ["-simulateDisplayModes", "YES"]
        try launchDev(settings: settings, arguments: simulated)
        openSection("device")
        require("settings.device.matchFrameRate", "with a display of several rates, This device lists the frame-rate switch")
        shot("settings: force frame rate switching")
        app.terminate()

        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"], settings: settings, arguments: simulated)
        addTeardownBlock { [weak self] in
            guard let self, self.app.state == .runningForeground, self.isFullScreen else { return }
            try? self.leaveFullScreen()
        }
        if isFullScreen {
            try leaveFullScreen()
            requireFullScreen(false, "the window launched full screen")
        }
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).click()
        let video = require("player.video", "a click on the H.264 stream opens the player")
        requirePlayback(1, timeout: 90)
        pause(video)
        requireDisplayModes(switches: 0, restores: 0, rate: 120_000, "the H.264 file in a window")

        ready("player.fullscreen").click()
        requireFullScreen(true, "the player's full-screen button")
        requireDisplayModes(switches: 1, restores: 0, rate: 47_950, "the H.264 file in full screen")
        try leaveFullScreen()
        requireFullScreen(false, "Escape in full screen")
        requireDisplayModes(switches: 1, restores: 1, rate: 120_000, "the H.264 file out of full screen")

        ready("player.fullscreen").click()
        requireFullScreen(true, "the player's full-screen button again")
        requireDisplayModes(switches: 2, restores: 1, rate: 47_950, "the H.264 file in full screen again")
        ready("player.back").click()
        requireGone("player.video", "the back button closes the player in full screen")
        requireFullScreen(false, "the player closed in full screen")

        ready(app.menuBars.menuBarItems["View"]).click()
        pickMenuEntry(app.menuBars.menuItems["Enter Full Screen"])
        requireFullScreen(true, "the View menu's Enter Full Screen on the Streams screen")
        ready(require("streams.row.1", "the Streams screen in full screen lists the H.264 stream")).click()
        require("player.video", "a click on the H.264 stream opens the player in full screen")
        requirePlayback(1, timeout: 90)
        // The close before restored the display (2 restores); the playback opened in full screen switched it.
        requireDisplayModes(switches: 3, restores: 2, rate: 47_950, "the H.264 file opened in full screen")
        try leaveFullScreen()
        requireFullScreen(false, "Escape in full screen, the player opened there")
        requireDisplayModes(switches: 3, restores: 3, rate: 120_000, "the H.264 file out of full screen, opened there")
    }

    /// Waits up to 10 s for the simulated display's probe (`player.display`: `switches=<n> restores=<n> rate=<mHz>`) to
    /// read these counts since the launch and this rate; fails with `what` and its reading.
    private func requireDisplayModes(switches: Int, restores: Int, rate: Int, _ what: String) {
        let probe = require("player.display", timeout: 10, "\(what): the display probe")
        let expected = "switches=\(switches) restores=\(restores) rate=\(rate)"
        var reading = ""
        let matched = waitUntil(10) {
            reading = probe.label
            return reading == expected
        }
        print("player.display \(what): \(reading)")
        if !matched { fail("\(what): the display probe reads '\(reading)', not '\(expected)'") }
    }

    /// The pointer over the title row's top-left corner, where the window's buttons sit beside the player's back and
    /// picture in picture buttons and its title, keeps the controls up as anywhere over the window: in a window (the title
    /// bar's own buttons) and in full screen (their copies on the title row), the H.264 file playing. The pointer goes
    /// from the video to each place through every point between, as a hand moves it (the run's pointer hook), and
    /// rests there: on a button of either, the controls stay (the window's buttons are part of the controls). Resting on
    /// the zoom button brings up its Move & Resize menu, a window of its own over the player; the pointer moving on into
    /// it keeps them too. In full screen the top edge pushed at the window's corner brings the title bar down over the
    /// title row, which doesn't take the controls with it. The pointer moving out of the window hides them at once. Each
    /// place is tried on its own and every failing one reported.
    func testPointerOverTheTitleRowKeepsTheControls() throws {
        executionTimeAllowance = 900
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        addTeardownBlock { [weak self] in
            guard let self, self.app.state == .runningForeground, self.isFullScreen else { return }
            try? self.leaveFullScreen()
        }
        if isFullScreen {
            try leaveFullScreen()
            requireFullScreen(false, "the window launched full screen")
        }
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).click()
        let video = require("player.video", "a click on the H.264 stream opens the player")
        requirePlayback(1, timeout: 90)
        var failures: [String] = []
        try hoverTitleRow(video, "in a window", &failures)

        // Out of the window, beside it (the window narrowed where the screen has no room).
        let window = app.windows.firstMatch
        let screen = NSScreen.screens.first?.frame ?? .zero
        if window.frame.maxX + 60 > screen.width, window.frame.minX < 60 { narrowWindow(video) }
        playFromNearTheStart(video)
        showControls(video)
        let frame = window.frame
        try movePointer(to: CGPoint(x: frame.maxX + 60 <= screen.width ? frame.maxX + 40 : frame.minX - 40, y: frame.midY))
        if !waitUntil(1.2, { !element("player.playPause").exists }) {
            failures.append("in a window: the pointer out of the window leaves the controls up after 1.2 s")
        }
        shot("window, the pointer out of it", of: XCUIScreen.main)

        app.typeKey("f", modifierFlags: [])
        requireFullScreen(true, "F")
        try hoverTitleRow(video, "in full screen", &failures)

        // The top edge pushed at the corner: the title bar comes down over the title row with its own buttons.
        playFromNearTheStart(video)
        showControls(video)
        try pushTopEdge(at: window.frame.minX + 16)
        if !element("player.playPause").exists { failures.append("in full screen: the top edge pushed at the corner hides the controls at once") }
        shot("full screen, the top edge pushed at the corner", of: XCUIScreen.main)
        if !failures.isEmpty { fail(failures.joined(separator: "\n")) }
    }

    /// The title row's buttons take a click as a mouse makes it, posted through the window server by the run's pointer
    /// hook (`clickPointer`), which XCUITest's own click goes around: the player opened from the movie's
    /// details, so the window has the screens' toolbar, as a person has it (its full-screen title bar window lay over
    /// the title row and took the clicks there, `PlayerWindowChrome`). In the window picture in picture starts and
    /// stops and the green button takes the window full screen; in full screen picture in picture starts and stops
    /// and the green button leaves; F back in, the title bar the top edge brings down leaves by its own green button;
    /// F once more, and the back button closes the player.
    func testFullScreenTitleRowTakesClicks() throws {
        executionTimeAllowance = 600
        try launchFromTheDetails()
        var failures: [String] = []
        let video = playFromTheDetails()
        try pictureInPictureByClicks(video, "in a window", &failures)
        let zoom = app.buttons.matching(NSPredicate(format: "identifier IN %@", Self.windowButtonKinds[2].1)).firstMatch
        try clickPointer(on: ready(zoom).frame, "in a window: the green button", &failures, fallback: { self.ready(zoom).click() }) { isFullScreen }
        requireFullScreen(true, "the green button")
        try fullScreenClicks(video, "in full screen", &failures)
        app.typeKey("f", modifierFlags: [])
        requireFullScreen(true, "F")
        try pushTopEdge()
        let close = closeButtonsShown().filter { $0.y > Self.menuBarBottom }
        let copies = windowButtonsOnScreen()
        if close.count == 1, let red = copies["close"]?.first, let green = copies["zoom"]?.first {
            shot("full screen, the top edge pushed", of: XCUIScreen.main)
            let point = CGPoint(x: close[0].x + green.midX - red.midX, y: close[0].y)
            try clickPointer(on: CGRect(origin: point, size: .zero), "in full screen: the green button of the title bar the top edge brought down", &failures, fallback: { self.app.typeKey(.escape, modifierFlags: []) }) { !isFullScreen }
            requireFullScreen(false, "the title bar's green button")
        } else {
            failures.append("in full screen: the top edge brought down no title bar with one close button (\(close), \(copies))")
            shot("full screen, the top edge pushed, no title bar", of: XCUIScreen.main)
            app.typeKey(.escape, modifierFlags: [])
            requireFullScreen(false, "Escape")
        }
        app.typeKey("f", modifierFlags: [])
        requireFullScreen(true, "F")
        try backByClick(video, "in full screen", &failures)
        if !failures.isEmpty { fail(failures.joined(separator: "\n")) }
    }

    /// `testFullScreenTitleRowTakesClicks` with the window full screen before the player opens (from the View menu on
    /// the details): picture in picture starts and stops, the green button leaves full screen and, F back in, the back
    /// button closes the player.
    func testFullScreenTitleRowTakesClicksWhenThePlayerOpensInFullScreen() throws {
        executionTimeAllowance = 600
        try launchFromTheDetails()
        var failures: [String] = []
        let video = playFromTheDetails(fullScreen: true)
        try fullScreenClicks(video, "opened in full screen", &failures)
        app.typeKey("f", modifierFlags: [])
        requireFullScreen(true, "F")
        try backByClick(video, "opened in full screen", &failures)
        if !failures.isEmpty { fail(failures.joined(separator: "\n")) }
    }

    /// `testFullScreenTitleRowTakesClicksWhenThePlayerOpensInFullScreen` with the menu bar kept in full screen (System
    /// Settings → Menu Bar), as a MacBook's camera housing keeps a band for it: the window starts below the menu bar,
    /// the title row with it.
    func testFullScreenTitleRowTakesClicksBelowTheMenuBar() throws {
        executionTimeAllowance = 600
        try keepMenuBarInFullScreen(true)
        addTeardownBlock { [weak self] in try? self?.keepMenuBarInFullScreen(false) }
        try launchFromTheDetails()
        var failures: [String] = []
        let video = playFromTheDetails(fullScreen: true)
        try fullScreenClicks(video, "below the menu bar", &failures)
        app.typeKey("f", modifierFlags: [])
        requireFullScreen(true, "F")
        try backByClick(video, "below the menu bar", &failures)
        if !failures.isEmpty { fail(failures.joined(separator: "\n")) }
    }

    /// Tortuga on the Board with the movie's details pushed (the screens' toolbar in the window), out of full screen,
    /// which the teardown leaves too.
    private func launchFromTheDetails() throws {
        addTeardownBlock { [weak self] in
            guard let self, self.app.state == .runningForeground, self.isFullScreen else { return }
            try? self.leaveFullScreen()
        }
        try launchDev(addon: true, open: ["board", "detail:movie:tt0111161"])
        if isFullScreen {
            try leaveFullScreen()
            requireFullScreen(false, "the window launched full screen")
        }
        require("detail.play", timeout: 60, "the movie's details show Play")
    }

    /// From the details (full screen first by the View menu where `fullScreen`), Play and the H.264 stream: the
    /// player, paused once it plays.
    private func playFromTheDetails(fullScreen: Bool = false) -> XCUIElement {
        if fullScreen {
            ready(app.menuBars.menuBarItems["View"]).click()
            pickMenuEntry(app.menuBars.menuItems["Enter Full Screen"])
            requireFullScreen(true, "the View menu's Enter Full Screen on the details")
        }
        ready("detail.play").click()
        ready(require("streams.row.1", timeout: 60, "Play opens the Streams screen with the H.264 stream")).click()
        let video = require("player.video", "a click on the H.264 stream opens the player")
        requirePlayback(1, timeout: 90)
        pause(video)
        return video
    }

    /// In full screen (`how`): picture in picture started and stopped by clicks, then the copy of the green button on
    /// the title row leaves full screen. The pointer goes back to the video then: F would bring the green button back
    /// under it, and the button resting under the pointer opens its menu (Exit Full Screen, the tiles), which takes
    /// the keys and keeps the menu bar from coming down for the top edge.
    private func fullScreenClicks(_ video: XCUIElement, _ how: String, _ failures: inout [String]) throws {
        try pictureInPictureByClicks(video, how, &failures)
        showControls(video)
        let window = app.windows.firstMatch.frame
        guard let green = windowButtonsOnScreen()["zoom"]?.first else {
            failures.append("\(how): the title row shows no green button")
            app.typeKey(.escape, modifierFlags: [])
            requireFullScreen(false, "Escape")
            return
        }
        try clickPointer(on: green.offsetBy(dx: window.minX, dy: window.minY), "\(how): the green button", &failures, fallback: { self.app.typeKey(.escape, modifierFlags: []) }) { !isFullScreen }
        requireFullScreen(false, "\(how): the green button")
        let left = app.windows.firstMatch.frame
        try movePointer(to: CGPoint(x: left.midX, y: left.midY))
    }

    /// Picture in picture started, then stopped, by clicks on its button on the title row (`how`: where the window is).
    private func pictureInPictureByClicks(_ video: XCUIElement, _ how: String, _ failures: inout [String]) throws {
        let pip = element("player.pip")
        guard waitUntil(10, { pip.exists && pip.isEnabled }) else {
            failures.append("\(how): picture in picture stays unavailable")
            return
        }
        for (selected, verb) in [(true, "start"), (false, "stop")] {
            showControls(video)
            try clickPointer(on: require("player.pip", timeout: 5, "\(how): the controls have the picture in picture button").frame, "\(how): the picture in picture button to \(verb) it", &failures, fallback: { self.ready(pip).click() }) {
                pip.exists && pip.isSelected == selected
            }
            if selected { shot("\(how), picture in picture", of: XCUIScreen.main) }
        }
        // The video back in the window (the PiP window gone) before the next click.
        _ = waitUntil(10) { !pictureInPictureWindow().exists }
        pause(video)
    }

    /// A click on the back button on the title row closes the player (in full screen, which it leaves too).
    private func backByClick(_ video: XCUIElement, _ how: String, _ failures: inout [String]) throws {
        showControls(video)
        // Escape leaves full screen first, then closes the player.
        let fallback = {
            for _ in 0..<3 where self.element("player.video").exists {
                self.app.typeKey(.escape, modifierFlags: [])
                _ = self.waitUntil(3) { !self.element("player.video").exists }
            }
        }
        try clickPointer(on: require("player.back", timeout: 5, "\(how): the controls have the back button").frame, "\(how): the back button", &failures, fallback: fallback) {
            !element("player.video").exists
        }
        requireFullScreen(false, "\(how): the back button")
    }

    /// A click by the pointer hook (`clickPointer`) on the middle of `frame`; when `done` doesn't follow within 10 s, a
    /// failure for `what` with a screenshot and the windows under the point, then `fallback`, so the test goes on.
    private func clickPointer(on frame: CGRect, _ what: String, _ failures: inout [String], fallback: () -> Void, until done: () -> Bool) throws {
        let point = CGPoint(x: frame.midX, y: frame.midY)
        try clickPointer(at: point)
        guard !waitUntil(10, done) else { return }
        failures.append("\(what): a click at \(point) did nothing")
        shot("\(what), the click missed", of: XCUIScreen.main)
        let windows = XCTAttachment(string: windowsUnder(point))
        windows.name = "windows under \(what)"
        windows.lifetime = .keepAlways
        add(windows)
        fallback()
        _ = waitUntil(10, done)
    }

    /// How many clicks `clickPointer` has published in this run (the watcher takes each event's name once).
    private static var pointerClicks = 0

    /// A left click at `point` (screen points from the main display's top left) as a mouse makes it: the pointer moved
    /// there as a hand moves it (`movePointer`), then the click published for the run's pointer hook (`click-<n>`:
    /// `<x> <y>`), which posts it through the window server.
    private func clickPointer(at point: CGPoint) throws {
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        try movePointer(to: point)
        Self.pointerClicks += 1
        publish("click-\(Self.pointerClicks)", code: "\(Int(point.x)) \(Int(point.y))", to: exchange)
    }

    /// The windows on screen at `point` (screen points from the main display's top left), front to back, and those
    /// along the screen's top: whose they are, their layer, alpha and frame.
    private func windowsUnder(_ point: CGPoint) -> String {
        guard let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] else { return "no window list" }
        return list.compactMap { info -> String? in
            guard let bounds = info[kCGWindowBounds as String] as? NSDictionary, let rect = CGRect(dictionaryRepresentation: bounds) else { return nil }
            let line = "\(info[kCGWindowOwnerName as String] ?? "?")(\(info[kCGWindowOwnerPID as String] ?? "?")) #\(info[kCGWindowNumber as String] ?? "?") layer \(info[kCGWindowLayer as String] ?? "?") alpha \(info[kCGWindowAlpha as String] ?? "?") \(rect) \(info[kCGWindowName as String] ?? "")"
            return rect.contains(point) ? "HIT " + line : (rect.minY < 60 ? "top " + line : nil)
        }.joined(separator: "\n")
    }

    /// How many times `keepMenuBarInFullScreen` has asked in this run.
    private static var menuBarSettings = 0

    private func keepMenuBarInFullScreen(_ kept: Bool) throws {
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        Self.menuBarSettings += 1
        let event = "menu-bar-\(Self.menuBarSettings)"
        publish(event, code: "\(kept ? "shown" : "hidden") \(exchange.path)", to: exchange)
        let reply = exchange.appendingPathComponent("replies/\(event)")
        if !waitUntil(15, { FileManager.default.fileExists(atPath: reply.path) }) {
            fail("the pointer hook didn't set the menu bar \(kept ? "kept" : "hidden") in full screen")
        }
    }

    /// `testPointerOverTheTitleRowKeepsTheControls`' places on the title row: from the video, playing with the controls
    /// up, the pointer moves to each (`movePointer`); a failure for each place the controls go from at once (within
    /// 1 s), or, on a button, within 4.5 s at rest (the few points between the window's buttons and back, and the row's
    /// empty part, are only checked at once). From the zoom button at rest the pointer goes on into its menu, below it,
    /// where the controls must stay up 1 s. A screenshot of the screen at each.
    private func hoverTitleRow(_ video: XCUIElement, _ how: String, _ failures: inout [String]) throws {
        playFromNearTheStart(video)
        showControls(video)
        let buttons = windowButtonsOnScreen()
        let back = relativeFrame(of: require("player.back", timeout: 5, "\(how): the controls have the back button"))
        var places: [(name: String, at: CGPoint, rests: Bool, then: CGPoint?)] = []
        for kind in ["close", "minimize", "zoom"] {
            if let button = buttons[kind]?.first {
                let menu = kind == "zoom" ? CGPoint(x: button.midX + 60, y: button.maxY + 50) : nil
                places.append(("the \(kind) button", CGPoint(x: button.midX, y: button.midY), true, menu))
            } else {
                failures.append("\(how): no \(kind) button on screen with the controls up (\(buttons))")
            }
        }
        if let zoom = buttons["zoom"]?.first {
            places.append(("between the zoom and back buttons", CGPoint(x: (zoom.maxX + back.minX) / 2, y: back.midY), false, nil))
        }
        places.append(("the back button", CGPoint(x: back.midX, y: back.midY), true, nil))
        if element("player.pip").exists {
            let pip = relativeFrame(of: element("player.pip"))
            places.append(("the picture in picture button", CGPoint(x: pip.midX, y: pip.midY), true, nil))
        }
        let title = relativeFrame(of: require("player.title", timeout: 5, "\(how): the controls show the title"))
        places.append(("the title", CGPoint(x: title.midX, y: title.midY), true, nil))
        let window = app.windows.firstMatch.frame
        places.append(("the title row's empty part", CGPoint(x: max(title.maxX + 40, window.width * 0.75), y: back.midY), false, nil))
        let note = XCTAttachment(string: places.map { "\($0.name) \($0.at)" }.joined(separator: "\n"))
        note.name = "title row places \(how)"
        note.lifetime = .keepAlways
        add(note)

        for place in places {
            playFromNearTheStart(video)
            showControls(video)
            try movePointer(to: CGPoint(x: window.minX + place.at.x, y: window.minY + place.at.y))
            let stayed = holds(for: 1) { element("player.playPause").exists }
            shot("\(how), the pointer on \(place.name)", of: XCUIScreen.main)
            guard stayed else {
                failures.append("\(how): the pointer on \(place.name) \(place.at) hides the controls at once")
                continue
            }
            guard place.rests else { continue }
            let rested = holds(for: 3.5) { element("player.playPause").exists }
            shot("\(how), the pointer resting on \(place.name)", of: XCUIScreen.main)
            if !rested { failures.append("\(how): the pointer resting on \(place.name) \(place.at) lets the controls hide") }
            guard let then = place.then else { continue }
            try movePointer(to: CGPoint(x: window.minX + then.x, y: window.minY + then.y))
            let kept = holds(for: 1) { element("player.playPause").exists }
            shot("\(how), the pointer from \(place.name) into its menu", of: XCUIScreen.main)
            if !kept { failures.append("\(how): the pointer from \(place.name) into its menu \(then) hides the controls at once") }
        }
    }

    /// How many readings `displayAssertions` has asked for in this run.
    private static var powerReadings = 0

    /// The app's PreventUserIdleDisplaySleep assertions in this Mac's `pmset -g assertions`, its lines
    /// `pid <n>(Tortuga): … PreventUserIdleDisplaySleep named: …`: the reading is published for the run's hook
    /// (`power-<n>`: the exchange's path), which writes pmset's output to `<exchange>/replies/power-<n>`. Waits up to
    /// 15 s for it; prints the lines and keeps the output as `pmset <when>`.
    private func displayAssertions(_ when: String) throws -> [String] {
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        Self.powerReadings += 1
        let event = "power-\(Self.powerReadings)"
        let reply = exchange.appendingPathComponent("replies/\(event)")
        publish(event, code: exchange.path, to: exchange)
        var output = ""
        let answered = waitUntil(15) {
            guard let text = try? String(contentsOf: reply, encoding: .utf8) else { return false }
            output = text
            return true
        }
        if !answered { throw HarnessError("the run's hook wrote no \(reply.path) in 15 s (\(when))") }
        let note = XCTAttachment(string: output)
        note.name = "pmset \(when)"
        note.lifetime = .keepAlways
        add(note)
        let lines = output.split(separator: "\n").map(String.init)
            .filter { $0.contains("(Tortuga):") && $0.contains("PreventUserIdleDisplaySleep") }
        print("pmset \(when): \(lines.isEmpty ? "no display assertion of the app" : lines.joined(separator: " | "))")
        return lines
    }

    /// Fails with `what` unless, within 10 s, the subtitle and audio menus' buttons read `subtitles` and `audio` (the
    /// selected tracks' labels), and then keep reading them through `holding` seconds: the time the player takes to act
    /// on a late track (one that matches the carried pick better takes its place, any other leaves it).
    private func requireTracks(subtitles: String, audio: String, holding: TimeInterval = 0, _ video: XCUIElement, _ what: String) {
        showControls(video)
        let wanted = [(button: "player.subtitles", want: subtitles), (button: "player.audio", want: audio)]
        func value(_ button: String) -> String { element(button).exists ? element(button).value as? String ?? "nothing" : "nothing (no button)" }
        for (button, want) in wanted {
            if !waitUntil(10, { value(button) == want }) { fail("\(what): \(button) reads '\(value(button))', not '\(want)'") }
        }
        guard holding > 0 else { return }
        var moved: (button: String, read: String, want: String)?
        let held = holds(for: holding) {
            moved = wanted.map { (button: $0.button, read: value($0.button), want: $0.want) }.first { $0.read != $0.want }
            return moved == nil
        }
        if !held, let moved { fail("\(what): \(moved.button) reads '\(moved.read)', not '\(moved.want)'") }
    }

    /// Opens the subtitle menu until it lists the test addon's English, which arrive 6 s after the file opened: a seventh
    /// entry, after Off and the file's own five tracks (the Mac's menu entries carry AppKit's `menuAction:` and their
    /// first line alone, "English", so the place tells them apart; the menu's own entries, not those of its submenus,
    /// Second subtitle's tracks among them). The menu closed by Escape and opened again every 2 s for up to 20 s.
    /// Returns that entry, the menu open; fails with `what`.
    private func openTheAddonsSubtitles(_ video: XCUIElement, _ what: String) -> XCUIElement {
        let entries = element("player.subtitles").menus.firstMatch.children(matching: .menuItem).matching(identifier: "menuAction:")
        let deadline = Date().addingTimeInterval(20)
        while true {
            showControls(video)
            ready("player.subtitles").click()
            let off = menuEntry("Off", "\(what): the subtitle button opens its menu")
            if waitUntil(2, { entries.count == 7 }) { return entries.element(boundBy: 6) }
            if Date() >= deadline { break }
            app.typeKey(.escape, modifierFlags: [])
            if !waitUntil(5, { !off.exists }) { fail("\(what): Escape doesn't close the subtitle menu") }
        }
        fail("\(what): in 20 s the subtitle menu lists \(entries.allElementsBoundByIndex.map(\.title)), not Off, the file's five tracks and the test addon's English")
        return entries.element(boundBy: 6)
    }

    /// Waits for the test addon's English to arrive (`openTheAddonsSubtitles`) and closes the menu by Escape.
    private func awaitTheAddonsSubtitles(_ video: XCUIElement, _ what: String) {
        let entry = openTheAddonsSubtitles(video, what)
        app.typeKey(.escape, modifierFlags: [])
        if !waitUntil(5, { !entry.exists }) { fail("\(what): Escape doesn't close the subtitle menu") }
    }

    /// Opens the playback info from the options menu while it isn't up.
    private func showPlaybackInfo(_ video: XCUIElement) {
        guard !element("player.info.panel").exists else { return }
        showControls(video)
        togglePlaybackInfo()
        require("player.info.panel", timeout: 5, "Playback info in the options menu opens the playback info")
    }

    /// The subtitle track the playback info names (`showPlaybackInfo`): its Subtitles section's Track row without the
    /// label (`TrackLabels.name`: "English" for a file's own full English, "English · Tortuga Test" for the test addon's,
    /// "Off"); "" while the section has no such row.
    private func subtitleInfo(_ video: XCUIElement) -> String {
        showPlaybackInfo(video)
        let row = element("player.info.subtitles").descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'player.info.row' AND (label BEGINSWITH 'Track' OR value BEGINSWITH 'Track')"))
            .firstMatch
        guard row.exists else { return "" }
        var text = fullText(of: row)
        if text.hasPrefix("Track") { text.removeFirst("Track".count) }
        return text.trimmingCharacters(in: CharacterSet(charactersIn: ",").union(.whitespaces))
    }

    /// Option-= (zoom in) with the controls up: its message (`player.osd`, up for 1.5 s: read as it shows, then shot)
    /// meets none of the title row's controls, back, picture in picture and the title, and sits level with back and as
    /// tall (the app's window has room for it on the row). Fails with `what`.
    private func requireZoomMessageClearOfTheTitleRow(_ video: XCUIElement, _ what: String) {
        showControls(video)
        let message = element("player.osd")
        app.typeKey("=", modifierFlags: .option)
        var shown: CGRect?
        _ = waitUntil(3) {
            shown = try? message.snapshot().frame
            return shown != nil
        }
        guard let frame = shown else { return fail("\(what): no zoom message") }
        shot("zoom message, controls up")
        let row = ["player.back", "player.pip", "player.title"].compactMap { id in (try? element(id).snapshot()).map { (id, $0.frame) } }
        for id in ["player.back", "player.title"] where !row.contains(where: { $0.0 == id }) {
            fail("\(what): the title row shows no \(id) beside the zoom message")
        }
        let met = row.filter { $0.1.intersects(frame) }.map { "\($0.0) \($0.1)" }
        if !met.isEmpty { fail("\(what): the zoom message (\(frame)) meets \(met.joined(separator: ", "))") }
        if let back = row.first(where: { $0.0 == "player.back" })?.1, abs(frame.minY - back.minY) > 1 || abs(frame.maxY - back.maxY) > 1 {
            fail("\(what): the zoom message (\(frame)) isn't level with back (\(back)) nor as tall")
        }
    }

    /// Plays from about a second in (a click near the timeline's start), so a 20 s file has time left to show.
    private func playFromNearTheStart(_ video: XCUIElement) {
        showControls(video)
        ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.5)).click()
        if element("player.playPause").label == "Play" { ready("player.playPause").click() }
        if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Pause" }) {
            fail("play/pause doesn't play the video: it reads \(element("player.playPause").label)")
        }
    }

    /// Fails with `what` unless the playing picture moves on: with the controls hidden (the pointer left still over the
    /// picture for their 3 s), a shot of the video and one within the next second differ in its top three quarters,
    /// where the clip's frame counter and sync marker run (the subtitles sit below them).
    private func requireFramesAdvance(_ video: XCUIElement, _ what: String) {
        pointerOn(ready(video), at: CGVector(dx: 0.5, dy: 0.3))
        if !waitUntil(8, { !element("player.playPause").exists }) { fail("\(what): the controls stay up while the video plays") }
        let first = pictureGrid(video, settle: false)
        var changed = 0
        let advanced = waitUntil(1) {
            changed = changedCells(first, pictureGrid(video, settle: false), rows: 0...0.75)
            return changed >= 2
        }
        if !advanced { fail("\(what): the picture didn't change in a second of playback (\(changed) cells)") }
    }

    /// Narrows the window by dragging its bottom-right corner left while the video plays, to 1.2 times as wide as it is
    /// high, so neither Letterbox nor Fill screen matches a 16:9 picture; fails unless the video follows.
    private func narrowWindow(_ video: XCUIElement) {
        let window = app.windows.firstMatch
        let before = window.frame
        let videoBefore = video.frame
        let by = max(before.width - before.height * 1.2, 0)
        let corner = window.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 1)).withOffset(CGVector(dx: -1, dy: -1))
        corner.click(forDuration: 0.3, thenDragTo: corner.withOffset(CGVector(dx: -by, dy: 0)))
        if !waitUntil(5, { window.frame.width < before.width - by / 2 }) { fail("dragging the window's corner leaves it at \(window.frame)") }
        narrowedBy = before.width - window.frame.width
        if !waitUntil(5, { video.frame.width < videoBefore.width - narrowedBy / 2 }) {
            fail("the narrowed window (\(window.frame)) leaves the video at \(video.frame)")
        }
        // The video done following the window (its frame still).
        _ = settledFrame(of: video)
        shot("window narrowed while playing")
    }

    /// The video as it shows, a grid of 160×90 grey levels (its pixels' luma, 0–255), each the mean of its cell, top row
    /// first: where `settle`, the paused picture with the controls up once a change is drawn (the video's picture still,
    /// `screenSettles`), else as it is now.
    private func pictureGrid(_ video: XCUIElement, settle: Bool = true) -> [UInt8] {
        if settle {
            showControls(video)
            screenSettles(video)
        }
        let columns = 160, rows = 90
        guard let pixels = ScreenPixels(video.screenshot()), pixels.width >= columns, pixels.height >= rows else {
            fail("no screenshot of the video to read")
            return []
        }
        var grid = [UInt8](repeating: 0, count: columns * rows)
        for row in 0..<rows {
            let ys = row * pixels.height / rows ..< (row + 1) * pixels.height / rows
            for column in 0..<columns {
                let xs = column * pixels.width / columns ..< (column + 1) * pixels.width / columns
                var sum = 0
                for y in ys {
                    for x in xs {
                        let (red, green, blue) = pixels.colour(x: x, y: y)
                        sum += 299 * Int(red) + 587 * Int(green) + 114 * Int(blue)
                    }
                }
                grid[row * columns + column] = UInt8(sum / (1000 * xs.count * ys.count))
            }
        }
        return grid
    }

    /// How many cells of `a` and `b` (`pictureGrid`) in `rows` (fractions of the height, from the top) differ by more than
    /// 24 grey levels.
    private func changedCells(_ a: [UInt8], _ b: [UInt8], rows: ClosedRange<Double>) -> Int {
        guard a.count == 160 * 90, b.count == a.count else { return 0 }
        let range = Int(rows.lowerBound * 90)..<Int(rows.upperBound * 90)
        return range.reduce(0) { count, row in
            count + (0..<160).filter { abs(Int(a[row * 160 + $0]) - Int(b[row * 160 + $0])) > 24 }.count
        }
    }

    /// `changedCells` as a share of the cells in `rows`.
    private func changedShare(_ a: [UInt8], _ b: [UInt8], rows: ClosedRange<Double>) -> Double {
        Double(changedCells(a, b, rows: rows)) / Double(160 * (Int(rows.upperBound * 90) - Int(rows.lowerBound * 90)))
    }

    /// A note kept with the run.
    private func attachNote(_ note: String) {
        let attachment = XCTAttachment(string: note)
        attachment.name = "note: \(note)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Waits up to 60 s for the player to play the episode `code` (`S1E2`): the episode line beside the title tells it
    /// and play/pause offers Pause (each read moves the pointer, which keeps the controls up). Fails with `what`.
    func requireEpisode(_ code: String, _ video: XCUIElement, _ what: String) {
        let title = element("player.title")
        let playPause = element("player.playPause")
        let playing = waitUntil(60) {
            _ = playerPosition()
            return title.exists && fullText(of: title).contains(code) && playPause.exists && playPause.label != "Play"
        }
        if !playing { fail("\(what): the player's title reads '\(title.exists ? fullText(of: title) : "nothing")', not \(code) playing") }
    }

    /// Whether the picture in picture button reads as selected (picture in picture runs), the controls brought up to
    /// read it; nil while it isn't there.
    private func pictureInPictureSelected(_ video: XCUIElement) -> Bool? {
        pointerOn(ready(video), at: CGVector(dx: 0.3, dy: 0.4))
        pointerOn(ready(video), at: CGVector(dx: 0.5, dy: 0.5))
        let pip = element("player.pip")
        return pip.exists(within: 2) ? pip.isSelected : nil
    }

    /// The system's picture in picture window, which macOS shows in its own process, PIPAgent.
    private func pictureInPictureWindow() -> XCUIElement {
        XCUIApplication(bundleIdentifier: "com.apple.PIPAgent").windows.firstMatch
    }

    /// A clip of tools/media's test pattern (make-matrix.sh): its shape, and where its first row of colour bars starts,
    /// as a share of its height. The row runs from 1/24 to 23/24 of its width, white at its left end and blue at its
    /// right.
    private struct TestPattern {
        let aspect: Double
        let barsTop: Double
    }

    /// Starts picture in picture by the player's button and waits for the PiP window to open. Fails with `what`.
    private func startPictureInPicture(_ video: XCUIElement, _ what: String) {
        showControls(video)
        if !waitUntil(10, { element("player.pip").exists && element("player.pip").isEnabled }) { fail("\(what): picture in picture stays unavailable") }
        showControls(video)
        ready("player.pip").click()
        if !waitUntil(10, { pictureInPictureSelected(video) == true }) {
            fail("\(what): the button doesn't start picture in picture: it doesn't read as selected")
        }
        if !pictureInPictureWindow().exists(within: 10) { fail("\(what): no picture in picture window") }
        // The PiP window done flying out to its place (its frame still).
        _ = settledFrame(of: pictureInPictureWindow())
    }

    /// Ends picture in picture by the player's button and waits for the PiP window to close, then for the button to stop
    /// reading as selected. Fails with `what`. The window flies back over the player and stays there about a second before it
    /// closes: an event sent meanwhile (the pointer over the video, which brings the controls up to read the button) has
    /// XCUITest check PIPAgent's window as one interrupting it, and the window closing during that check fails the test
    /// ("Failed to get matching snapshot"), so nothing is sent until the window has gone.
    private func stopPictureInPicture(_ video: XCUIElement, _ what: String) {
        showControls(video)
        ready("player.pip").click()
        if !waitUntil(10, { !pictureInPictureWindow().exists }) { fail("\(what): the picture in picture window stays") }
        if !waitUntil(10, { pictureInPictureSelected(video) == false }) {
            fail("\(what): the button doesn't end picture in picture: it still reads as selected")
        }
    }

    /// The PiP window as it shows on the screen: its frame in the accessibility tree runs 24 pt past its sides and 4 pt
    /// past its top and bottom (macOS 27; the window PIPAgent reports against the panel AppKit places for it).
    private func pictureInPictureContent() -> CGRect {
        pictureInPictureWindow().frame.insetBy(dx: 24, dy: 4)
    }

    /// Makes the PiP window `by` points wider (narrower when negative) by a drag of its corner nearest the screen's
    /// middle, from 3 pt inside it, through the run's hook. Fails with `what` unless the window follows.
    private func resizePictureInPicture(by delta: CGFloat, _ exchange: URL, _ what: String) {
        guard pictureInPictureWindow().exists(within: 5), let screen = NSScreen.screens.first?.frame else { return fail("\(what): no picture in picture window to resize") }
        let before = pictureInPictureContent()
        let left = before.midX > screen.midX
        let top = before.midY > screen.midY
        let corner = CGPoint(x: left ? before.minX + 3 : before.maxX - 3, y: top ? before.minY + 3 : before.maxY - 3)
        let dx = left ? -delta : delta
        let dy = (top ? -delta : delta) * before.height / max(before.width, 1)
        Self.windowDrags += 1
        publish("drag-\(Self.windowDrags)", code: "\(Int(corner.x)) \(Int(corner.y)) \(Int(corner.x + dx)) \(Int(corner.y + dy))", to: exchange)
        if !waitUntil(20, { abs(pictureInPictureContent().width - (before.width + delta)) < abs(delta) / 2 }) {
            fail("\(what): a drag of the PiP window's corner by \(Int(delta)) pt leaves it at \(pictureInPictureContent()), from \(before)")
        }
        // The drag's last steps landed (the PiP window's frame still).
        _ = settledFrame(of: pictureInPictureWindow())
        attachNote("\(what): the PiP window resized from \(before) to \(pictureInPictureContent())")
    }

    /// Moves the player's window by 120 pt left and 80 pt down by a drag of its title row's empty middle, through
    /// the run's hook. Fails with `what` unless the window follows.
    private func moveWindow(_ exchange: URL, _ what: String) {
        let window = app.windows.firstMatch
        let before = window.frame
        let from = CGPoint(x: before.midX + 120, y: before.minY + 14)
        Self.windowDrags += 1
        publish("drag-\(Self.windowDrags)", code: "\(Int(from.x)) \(Int(from.y)) \(Int(from.x - 120)) \(Int(from.y + 80))", to: exchange)
        if !waitUntil(20, { abs(window.frame.minX - (before.minX - 120)) <= 5 && abs(window.frame.minY - (before.minY + 80)) <= 5 }) {
            fail("\(what): a drag of the title row leaves the player's window at \(window.frame), from \(before)")
        }
        // The drag's last steps landed (the window's frame still).
        _ = settledFrame(of: window)
    }

    /// Fails with `what` unless the PiP window shows the whole of `pattern`'s picture, letterboxed to fit
    /// (`requireWholePicture(_:in:_:)`), the pointer moved off it first so its own controls don't dim it.
    private func requireWholePicture(_ pattern: TestPattern, inPictureInPicture what: String) {
        let window = pictureInPictureWindow()
        guard window.exists(within: 5) else { return fail("\(what): no picture in picture window") }
        pointerOn(app.windows.firstMatch, at: CGVector(dx: 0.5, dy: 0.5))
        // Its controls fade once the pointer has left it: the colour bars show undimmed.
        _ = waitUntil(5) { pictureOfTheTestPattern(pattern, in: pictureInPictureContent()) != nil }
        screenShot(what)
        requireWholePicture(pattern, in: pictureInPictureContent(), what)
    }

    /// Fails with `what` unless the player's video shows the whole of `pattern`'s picture, letterboxed to fit, the
    /// controls hidden first where the video plays (paused, they stay up, clear of the bars).
    private func requireWholePicture(_ pattern: TestPattern, inPlayer video: XCUIElement, _ what: String) {
        pointerOn(ready(video), at: CGVector(dx: 0.5, dy: 0.6))
        if require("player.playPause", timeout: 5, "the pointer over the video shows the controls").label != "Play" { _ = waitUntil(8) { !element("player.playPause").exists } }
        // The controls' fade over (playing) or clear of the bars (paused): the colour bars show whole.
        _ = waitUntil(3) { pictureOfTheTestPattern(pattern, in: video.frame) != nil }
        screenShot(what)
        requireWholePicture(pattern, in: video.frame, what)
    }

    /// Fails with `what` unless the picture of `pattern` on the screen in `area` (points from the main display's top
    /// left) shows whole and letterboxed to fit it: within it, filling its width or its height, centred, all within
    /// 3 % of its size (6 pt at least).
    private func requireWholePicture(_ pattern: TestPattern, in area: CGRect, _ what: String) {
        guard let picture = pictureOfTheTestPattern(pattern, in: area) else {
            return fail("\(what): the test pattern's colour bars aren't whole in \(area): the picture is cut off or missing")
        }
        attachNote("\(what): picture \(picture.integral) in \(area.integral)")
        let x = max(8, area.width * 0.03), y = max(8, area.height * 0.03)
        let within = picture.minX >= area.minX - x && picture.maxX <= area.maxX + x && picture.minY >= area.minY - y && picture.maxY <= area.maxY + y
        let fits = abs(picture.width - area.width) <= x || abs(picture.height - area.height) <= y
        let centred = abs(picture.midX - area.midX) <= x && abs(picture.midY - area.midY) <= y
        if !(within && fits && centred) {
            fail("\(what): the picture lies at \(picture.integral) in \(area.integral), not whole and letterboxed to fit it")
        }
    }

    /// Where `pattern`'s picture lies on the screen, from its first row of colour bars within `area` (points from the
    /// main display's top left): the rows whose leftmost white run (4 px or more) comes before a blue run ending past
    /// the area's middle are the row's band, its left end at 1/24 of the picture's width and its right end at 23/24, its
    /// top at `barsTop` of its height. Nil without the band, or with the band reaching the area's edge (the picture runs
    /// past it).
    private func pictureOfTheTestPattern(_ pattern: TestPattern, in area: CGRect) -> CGRect? {
        guard let screen = ScreenPixels() else { return nil }
        let scale = screen.scale
        let crop = CGRect(x: area.minX * scale, y: area.minY * scale, width: area.width * scale, height: area.height * scale).integral
            .intersection(CGRect(x: 0, y: 0, width: screen.width, height: screen.height))
        guard crop.width > 8, crop.height > 8 else { return nil }
        let originX = Int(crop.minX), originY = Int(crop.minY), width = Int(crop.width), height = Int(crop.height)
        func white(_ colour: (red: UInt8, green: UInt8, blue: UInt8)) -> Bool {
            let (red, green, blue) = (Int(colour.red), Int(colour.green), Int(colour.blue))
            return min(red, green, blue) > 140 && max(red, green, blue) - min(red, green, blue) < 40
        }
        func blue(_ colour: (red: UInt8, green: UInt8, blue: UInt8)) -> Bool { colour.blue > 100 && colour.red < 80 && colour.green < 80 }
        var band: (top: Int, bottom: Int, left: Int, right: Int)?
        for row in 0..<height {
            var left: Int?, right: Int?, whiteRun = 0, blueRun = 0
            for column in 0..<width {
                let colour = screen.colour(x: originX + column, y: originY + row)
                whiteRun = white(colour) ? whiteRun + 1 : 0
                blueRun = blue(colour) ? blueRun + 1 : 0
                if left == nil, whiteRun == 4 { left = column - 3 }
                if blueRun >= 4 { right = column }
            }
            guard let left, let right, left < right, right > width / 2 else {
                if band != nil { break }
                continue
            }
            if var found = band {
                found.bottom = row
                found.left = min(found.left, left)
                found.right = max(found.right, right)
                band = found
            } else {
                band = (row, row, left, right)
            }
        }
        guard let band, band.left > 1, band.right < width - 2, band.bottom > band.top else { return nil }
        let pictureWidth = CGFloat(band.right + 1 - band.left) * 24 / 22
        let pictureHeight = pictureWidth / pattern.aspect
        let left = CGFloat(band.left) - pictureWidth / 24
        let top = CGFloat(band.top) - pattern.barsTop * pictureHeight
        return CGRect(
            x: area.minX + left / scale, y: area.minY + top / scale, width: pictureWidth / scale, height: pictureHeight / scale
        )
    }

    /// The PiP window's button labelled `label` (`Pause`, `Play`). The window shows its controls while the pointer
    /// moves over it and hides them again soon after, so the pointer goes from point to point across it until the button
    /// is there (up to about 15 s); fails with the window's elements when it never comes.
    private func pictureInPictureButton(in window: XCUIElement, _ label: String) -> XCUIElement {
        let button = window.buttons.matching(NSPredicate(format: "label ==[c] %@ OR identifier ==[c] %@ OR title ==[c] %@", label, label, label)).firstMatch
        let points = [(0.3, 0.3), (0.7, 0.6), (0.5, 0.45), (0.25, 0.7), (0.75, 0.3)]
        for round in 0..<3 where !button.exists {
            for (x, y) in points where !button.exists {
                pointerOn(window, at: CGVector(dx: x + 0.02 * Double(round), dy: y))
                _ = button.exists(within: 1)
            }
        }
        if !button.exists {
            attachTree(window, "picture in picture window without \(label)")
            fail("the picture in picture window shows no \(label) button")
        }
        return button
    }

    /// `element`'s tree, kept with the run.
    private func attachTree(_ element: XCUIElement, _ name: String) {
        let tree = XCTAttachment(string: element.exists ? element.debugDescription : "\(name): not there")
        tree.name = name
        tree.lifetime = .keepAlways
        add(tree)
    }

    /// A tooltip comes up beside `control` once the pointer rests on it (moved onto it, then within it: AppKit times a
    /// tooltip from the pointer's last move), within 5 s (`tooltipBeside`). The pointer rests in the window's middle
    /// first until no tooltip shows, so the one found isn't the last control's (back's starts within 60 pt of picture
    /// in picture's middle); a tooltip still up there after 5 s is `last`'s (the control the pointer was on), left
    /// behind, which fails the test.
    private func tooltipShows(on control: XCUIElement, after last: String) -> Bool {
        pointerOn(app.windows.firstMatch, at: CGVector(dx: 0.5, dy: 0.5))
        if !waitUntil(5, { app.helpTags.count == 0 }) { fail("the tooltip of \(last) stays up 5 s after the pointer left it") }
        control.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.3)).hover()
        control.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).hover()
        return waitUntil(5) { tooltipBeside(control) }
    }

    /// A tooltip starts within 60 pt of `control`'s middle; each tag's frame comes from its snapshot, which a tooltip
    /// going meanwhile leaves out, not as an error.
    private func tooltipBeside(_ control: XCUIElement) -> Bool {
        let middle = CGPoint(x: control.frame.midX, y: control.frame.midY)
        return app.helpTags.allElementsBoundByIndex.contains { tag in
            guard let frame = try? tag.snapshot().frame else { return false }
            return abs(frame.minX - middle.x) < 60 && abs(frame.minY - middle.y) < 60
        }
    }

    /// The window is full screen: it spans the screen's width down to its bottom edge, from the top edge or from below
    /// a band the menu bar keeps (`keepMenuBarInFullScreen`); a window here is narrower.
    private var isFullScreen: Bool {
        let window = app.windows.firstMatch
        guard window.exists, let screen = NSScreen.screens.first?.frame else { return false }
        return window.frame.minX == 0 && window.frame.width == screen.width && window.frame.maxY == screen.height
    }

    /// Waits up to 15 s for the window to be full screen (`on`) or not, then for AppKit's transition to end (the frame
    /// unchanged for a second); fails with `what` when it doesn't come.
    private func requireFullScreen(_ on: Bool, _ what: String) {
        let window = app.windows.firstMatch
        var last = CGRect.null
        var since = Date()
        let settled = waitUntil(15) {
            let frame = window.frame
            if frame != last || isFullScreen != on { (last, since) = (frame, Date()) }
            return Date().timeIntervalSince(since) >= 1
        }
        if !settled { fail("\(what): the window \(on ? "isn't" : "is still") full screen (\(window.frame))") }
    }

    /// Leaves full screen. With the player up, by Escape, which leaves full screen first (after closing an open menu):
    /// XCUITest reports the player's copy of the zoom button on the title row not hittable, before the top edge is
    /// pushed and after. Elsewhere by the zoom button: where the window has it, else in the title bar full screen hides,
    /// once the top edge has brought it down.
    private func leaveFullScreen() throws {
        for _ in 0..<2 where element("player.video").exists && isFullScreen {
            app.typeKey(.escape, modifierFlags: [])
            _ = waitUntil(5) { !isFullScreen }
        }
        guard isFullScreen else { return }
        let zoom = app.buttons.matching(NSPredicate(format: "identifier IN %@", Self.windowButtonKinds[2].1)).firstMatch
        if !(zoom.exists && zoom.isHittable) { try pushTopEdge() }
        if zoom.exists, zoom.isHittable { ready(zoom).click() }
        _ = waitUntil(10) { !isFullScreen }
    }

    /// How many times `pushTopEdge` has pushed in this run.
    private static var topEdgePushes = 0

    /// The menu bar's bottom edge on a full-screen window's screen, in points from its top: a close button below it is
    /// in the title bar that came down with it.
    private static let menuBarBottom: CGFloat = 24

    /// Pushes the pointer up against the top edge at `x` (screen points; the window's middle by default), as a person
    /// reaching for the menu bar does, which XCUITest's hover can't: the push is published for the run's pointer hook
    /// (`top-edge-<n>`: `<x>`), which posts it, the pointer going up to the edge from 40 pt below it. The hook alone
    /// brings the pointer there: a hover onto the edge first starts the menu bar and the title bar
    /// down, and the push's way up then crosses the title bar on its way down rather than the video (the controls, last
    /// under the pointer at the edge, then stayed up). Then waits up to 10 s for the title bar to come down under the
    /// menu bar with a close button (once more when it doesn't).
    private func pushTopEdge(at x: CGFloat? = nil) throws {
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        let at = x ?? app.windows.firstMatch.frame.midX
        for _ in 0..<2 {
            Self.topEdgePushes += 1
            publish("top-edge-\(Self.topEdgePushes)", code: "\(Int(at))", to: exchange)
            if waitUntil(10, { closeButtonsShown().contains { $0.y > Self.menuBarBottom } }) { return }
        }
    }

    /// With the window full screen: exactly one close button on the screen, below `below` points from its top (the
    /// title bar's under the menu bar), at the window's leading edge. Fails with `what`.
    private func requireCloseButtonsShown(below: CGFloat, _ what: String) {
        let shown = closeButtonsShown()
        if shown.count != 1 { fail("\(what): the screen shows \(shown.count) close buttons (at \(shown)), not one") }
        for point in shown where point.y < below || point.x > 30 {
            fail("\(what): the close button shows at \(point), not at the leading edge below \(Int(below)) pt")
        }
    }

    /// The close buttons the screen shows in its top-left corner, as their red circles (AppKit draws the window's
    /// buttons in colour while the window is key; the copies and the title bar's own alike): their centres in points
    /// from the screen's top-left corner. The accessibility tree can't count them: it reports only the window's
    /// current ones.
    private func closeButtonsShown() -> [CGPoint] {
        guard let screen = ScreenPixels() else { return [] }
        let scale = screen.scale
        var circles: [(sum: CGPoint, count: CGFloat)] = []
        for y in 0..<min(Int(90 * scale), screen.height) {
            for x in 0..<min(Int(120 * scale), screen.width) {
                let (red, green, blue) = screen.colour(x: x, y: y)
                guard red > 200, (60...140).contains(green), (50...130).contains(blue) else { continue }
                let point = CGPoint(x: CGFloat(x) / scale, y: CGFloat(y) / scale)
                let near = circles.firstIndex {
                    abs($0.sum.x / $0.count - point.x) < 10 && abs($0.sum.y / $0.count - point.y) < 10
                }
                if let near {
                    circles[near].sum.x += point.x
                    circles[near].sum.y += point.y
                    circles[near].count += 1
                } else {
                    circles.append((point, 1))
                }
            }
        }
        // A circle, not a stray red pixel.
        return circles.filter { $0.count >= 20 }.map { CGPoint(x: $0.sum.x / $0.count, y: $0.sum.y / $0.count) }
    }

    /// The window's buttons by kind, as XCUITest names them: the zoom button is the full-screen button where the window
    /// can go full screen.
    private static var windowButtonKinds: [(String, [String])] {
        [
            ("close", [XCUIIdentifierCloseWindow]), ("minimize", [XCUIIdentifierMinimizeWindow]),
            ("zoom", [XCUIIdentifierZoomWindow, XCUIIdentifierFullScreenWindow]),
        ]
    }

    /// The window's buttons on screen (on the window's part of it, not above its top edge), each kind's frames
    /// relative to the window, left to right.
    private func windowButtonsOnScreen() -> [String: [CGRect]] {
        let window = app.windows.firstMatch.frame
        var found: [String: [CGRect]] = [:]
        for (kind, ids) in Self.windowButtonKinds {
            found[kind] = windowButtonSnapshots(ids).map(\.frame)
                .filter { !$0.isEmpty && window.contains($0) }
                .map { $0.offsetBy(dx: -window.minX, dy: -window.minY) }
                .sorted { $0.minX < $1.minX }
        }
        return found
    }

    /// Snapshots of the window's buttons with the identifiers `ids`: read at once, so a button gone in the meantime
    /// (the controls hiding) is left out rather than failing the read.
    private func windowButtonSnapshots(_ ids: [String]) -> [any XCUIElementSnapshot] {
        app.buttons.matching(NSPredicate(format: "identifier IN %@", ids)).allElementsBoundByIndex.compactMap { try? $0.snapshot() }
    }

    /// `element`'s frame relative to the window.
    private func relativeFrame(of element: XCUIElement) -> CGRect {
        let window = app.windows.firstMatch.frame
        return element.frame.offsetBy(dx: -window.minX, dy: -window.minY)
    }

    /// With the controls up, one close, one minimize and one zoom button on screen, left to right on the title row
    /// before the back button (its middle within a point of theirs); minimize greyed out in full screen. Where `at`
    /// gives the frames the window had them at, each within a point of its own. Their frames (attached), and the zoom
    /// button; fails with `what`.
    @discardableResult
    private func requireWindowButtons(_ at: [String: [CGRect]]?, _ what: String) -> XCUIElement {
        attachWindowButtons(what)
        let found = windowButtonsOnScreen()
        let frames = Self.windowButtonKinds.compactMap { entry in found[entry.0]?.count == 1 ? found[entry.0]?[0] : nil }
        guard frames.count == Self.windowButtonKinds.count else {
            fail("\(what): the window's buttons on screen are \(found), not one set")
            return app.buttons.firstMatch
        }
        let back = relativeFrame(of: require("player.back", timeout: 5, "\(what): the controls have the back button"))
        if frames[1].minX < frames[0].maxX || frames[2].minX < frames[1].maxX || frames[2].maxX > back.minX {
            fail("\(what): the window's buttons \(frames) aren't left to right before the back button \(back)")
        }
        for frame in frames where abs(frame.midY - back.midY) > 1 {
            fail("\(what): the window's buttons \(frames) aren't on the back button's row (\(back))")
        }
        if let at {
            for (kind, _) in Self.windowButtonKinds {
                guard let was = at[kind]?.first, let now = found[kind]?.first else { continue }
                if abs(was.minX - now.minX) > 1 || abs(was.midY - now.midY) > 1 {
                    fail("\(what): the \(kind) button sits at \(now), in a window at \(was)")
                }
            }
        }
        let minimize = app.buttons.matching(identifier: XCUIIdentifierMinimizeWindow).firstMatch
        if isFullScreen, minimize.isEnabled { fail("\(what): minimize is enabled in full screen") }
        return app.buttons.matching(NSPredicate(format: "identifier IN %@", Self.windowButtonKinds[2].1)).firstMatch
    }

    /// Every button of the window's kinds with its frame and enabled state, and the back button's and the window's
    /// frames, kept with the run as `window buttons: what`.
    private func attachWindowButtons(_ what: String) {
        var lines = windowButtonSnapshots(Self.windowButtonKinds.flatMap(\.1)).map { "\($0.identifier) \($0.frame) enabled \($0.isEnabled)" }
        let back = element("player.back")
        lines.append("player.back \(back.exists ? "\(back.frame)" : "none")")
        lines.append("window \(app.windows.firstMatch.frame)")
        let attachment = XCTAttachment(string: lines.joined(separator: "\n"))
        attachment.name = "window buttons: \(what)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Three screenshots of the screen one right after the other, while the controls fade.
    private func shotsOfTheFade(_ name: String) {
        for index in 1...3 { shot("\(name) \(index)", of: XCUIScreen.main) }
    }
}
