#if os(iOS)
import AVKit
#endif
import XCTest

/// Watch together by touch (iOS) and with the pointer (the Mac), against the run's Open Watch Party relay and the
/// scripted participants (`WatchScenario`), reached through Home's Watch together shortcut
/// (`board.shortcut.watchTogether`) or an invite link:
/// - the screen from Home's shortcut, the relay reached: text that is no room code says so under the field with Join
///   disabled, and a code with no room behind it (typed with its hyphen) fails as the relay answers; the relay row
///   names the run's relay, and its editor refuses text that isn't a server's address, Cancel leaving the relay as it
///   was; on the Mac an invite link to a room on another relay asks first, naming the room's code and that relay,
///   Cancel leaving the screen out of a room;
/// - hosting: a room started from the player's options (Only I control) shows its code (`K7M-Q2X`) with its QR code,
///   Copy code and Share in the room panel (on the Mac a sheet), then the host's own: who controls playback, the lock,
///   the offset (a +0.5 s step, then Reset), Leave and End room; the scripted guest Anna, started in it by the run,
///   joins with Make host and Remove on her row, Remove asking first (Cancel keeps her); the session indicator counts
///   two and opens the room panel again, where Make host hands her the room (End room goes); her movie comes to the
///   player, which offers the remux, 12 s shorter (the mismatch prompt: Play anyway, Choose another stream, Don't
///   play): on the Mac played anyway; on iOS not played, the prompt offers another stream, whose Streams sheet comes up
///   over the player, and the stream picked there plays; the controls say who controls playback, with play/pause
///   disabled; the player closed, Anna hands the room back, Home's shortcut reads "2 watching together" and opens the
///   room, hosted by this device again, where an invite link to another room (Sam's) asks the room's Leave
///   question, Cancel keeping the room; the lock goes on and off again, and End room asks first, then ends the room,
///   the shortcut back to "Watch Together";
/// - a guest's side: an invite link to the scripted host Sam's room joins it, and the player finds a file matching
///   his (the matching line in the spinner's place while the test addon's slow streams load), the remux, which plays
///   with no prompt and follows Sam: paused, at 0:02, then playing at 1.5× and paused again, where on iOS AVKit has
///   picture in picture (the iPad, not the iPhone simulators) both in its window over the home screen, and on the Mac
///   with Finder in front of the window (which still shows); the controls say who controls playback, with play/pause
///   disabled; the indicator opens the room panel, with no End room for a guest, where the offset steps by +0.5 s;
///   Sam removes this device, which the panel says; the player closed, the code typed as the relay prints it joins
///   the room again, whose title opens the player; Leave room asks first, then leaves.
/// On the Mac alone, a live stream can't start a room (`WatchTests+macOS`).
final class WatchTests: TortugaUITestCase {
    func testJoinErrorsAndTheRelay() throws {
        let scenario = try WatchScenario()
        try launchDev(addon: true, settings: scenario.settings)
        #if os(macOS)
        require("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        #else
        requireTabScreen("nav.board", "Home shows at launch")
        #endif
        let shortcut = requireWatchTogetherShortcut(reading: "Watch Together", "Home's shortcuts offer Watch together")
        activate(shortcut)
        let field = require("watch.join.field", timeout: 60, "Home's Watch together opens the screen with its join field")
        requireRelayReached("the screen reaches the relay")
        shot("watch together")

        activate(field)
        field.typeText("AB1")
        require("watch.join.error", timeout: 5, "text that is no room code says so")
        if require("watch.join", timeout: 5, "the screen has Join").isEnabled { fail("Join is enabled for text that is no room code") }
        shot("join field not a code")
        #if os(macOS)
        field.typeKey("a", modifierFlags: .command)
        field.typeText(WatchScenario.missingRoom)
        field.typeKey(.return, modifierFlags: [])
        #else
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 3) + WatchScenario.missingRoom + "\n")
        #endif
        require("watch.failure", timeout: 30, "a code with no room behind it fails")
        shot("join error")
        activate(require("watch.failure.dismiss", "the failure can be dismissed"))
        requireGone("watch.failure", "Dismiss clears the failure")

        let row = showOnScreen("watch.relay.setting", "the screen lists the relay")
        let host = URL(string: scenario.relay)?.host ?? scenario.relay
        if !row.label.contains(host) { fail("the relay row reads \"\(row.label)\", not the run's relay \(host)") }
        activate(row)
        let relay = require("watch.relay.field", timeout: 10, "the relay row opens its editor")
        activate(relay)
        #if os(macOS)
        relay.typeKey("a", modifierFlags: .command)
        relay.typeText("not a server")
        #else
        let old = relay.value as? String ?? ""
        relay.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old.count) + "not a server")
        #endif
        activate(require("watch.relay.save", "the editor has Save"))
        require("watch.relay.error", timeout: 5, "Save refuses text that isn't a server's address")
        shot("relay refused")
        activate(require("watch.relay.cancel", "the editor has Cancel"))
        requireGone("watch.relay.field", "Cancel closes the editor")
        if !element("watch.relay.setting").label.contains(host) { fail("Cancel changed the relay") }

        #if os(macOS)
        // An invite to a room on another relay asks first, naming its code and the relay; Cancel stays out of rooms.
        var link = URLComponents()
        link.scheme = "tortuga"
        link.host = "watch"
        link.queryItems = [URLQueryItem(name: "code", value: "K7MQ2X"), URLQueryItem(name: "relay", value: "http://192.0.2.1:9")]
        openFromOutside(link.url!)
        let cancel = require("watch.invite.cancel", timeout: 10, "an invite on another relay asks first")
        require("watch.invite.confirm", timeout: 5, "the question offers Join")
        let named = app.staticTexts.matching(NSPredicate(format: "(label CONTAINS 'K7M-Q2X' AND label CONTAINS '192.0.2.1') OR (value CONTAINS 'K7M-Q2X' AND value CONTAINS '192.0.2.1')"))
        if !named.firstMatch.exists(within: 5) { fail("the question doesn't name the room K7M-Q2X and the relay 192.0.2.1") }
        shot("invite question for another relay")
        ready(cancel).click()
        requireGone("watch.invite.cancel", "Cancel takes the question away")
        require("watch.join.field", timeout: 5, "Cancel leaves the screen out of a room")
        #endif
    }

    func testHostStartsARoomFromThePlayerAndAGuestJoins() throws {
        executionTimeAllowance = WatchScenario.timeAllowance
        #if os(iOS)
        addTeardownBlock { XCUIDevice.shared.orientation = .portrait }
        #endif
        let scenario = try WatchScenario()
        try launchDev(addon: true, open: ["board", "detail:movie:tortuga-remux"], settings: scenario.settings)
        activate(require("detail.play", timeout: 60, "the movie's details show Play"))
        activate(require("streams.row.0", timeout: 60, "Play opens the Streams sheet with the remux"))
        let video = require("player.video", timeout: 30, "a tap or click on the remux opens the player")
        #if os(macOS)
        showControls(video)
        #else
        // Paused, the room keeps the host's pause. Started playing, the 30 s remux ends first, and its end keeps the
        // controls up with Pause, which showPlayerControls reads as playing. By the button's place, as a person taps:
        // while the remux buffers, play/pause holds a spinner and still acts, and XCUITest's hit test on it can fail
        // ("Failed to determine hittability").
        let paused = { (try? self.element("player.playPause").snapshot().label) == "Play" }
        tapPlayerControl("player.playPause", video: video, until: paused, "play/pause pauses the remux")
        #endif
        activate(element("player.more"))
        let watch = menuEntry("Watch Together", "the options menu offers Watch together")
        shot("options menu")
        #if os(macOS)
        ready(watch).hover()
        let start = menuEntry("Only I control", "Watch together offers to start a room")
        // A closed submenu's entries have an empty frame. XCUITest's click finds the open submenu's two-line entries
        // not hittable: the keys go into the submenu instead, onto its first entry (its title is none), and Return
        // picks it.
        if !waitUntil(5, { !start.frame.isEmpty }) { fail("hovering Watch together doesn't open its submenu") }
        shot("watch together start choices")
        app.typeKey(.rightArrow, modifierFlags: [])
        if !waitUntil(1, { start.isSelected }) { app.typeKey(.downArrow, modifierFlags: []) }
        if !waitUntil(1, { start.isSelected }) { fail("the arrow keys don't reach Only I control") }
        app.typeKey(.return, modifierFlags: [])
        #else
        ready(watch).tap()
        let start = menuEntry("Only I control", "Watch together offers to start a room")
        shot("watch together start choices")
        pickMenuEntry(start)
        #endif
        require("player.watch.panel", timeout: 10, "starting a room opens the room panel")
        let code = requireRoomCode("the room panel shows the room's code")
        publishRoom(code, in: scenario)
        require("watch.room.qr", timeout: 5, "the room panel shows the invite's QR code")
        require("watch.room.copy", timeout: 5, "the room panel offers Copy code")
        require("watch.room.share", timeout: 5, "the room panel offers Share")
        #if os(macOS)
        let panel = element("player.watch.panel")
        require("watch.room.control", timeout: 5, "the room panel offers the host who controls playback")
        require("watch.room.lock", timeout: 5, "the room panel offers the host the room's lock")
        require("watch.room.leave", timeout: 5, "the host may leave the room, which goes on without it")
        require("watch.room.end", timeout: 5, "the host may end the room for everyone")
        let step = require(WatchScenario.offsetStep, timeout: 5, "the room panel shows the host's offset while it plays the room's title")
        ready(scrollTo(step, over: panel, "the room panel scrolls to the offset")).click()
        requireOffset(WatchScenario.offsetStepped, "a step moves the offset")
        // The relay's report adds the offset under this device's row, moving Reset down: the click waits for it.
        requireOffsetReported(WatchScenario.offsetStepped, "the room reports the host's offset")
        ready(element(WatchScenario.offsetReset)).click()
        requireOffset(WatchScenario.offsetNone, "Reset puts the offset back")
        requireOffsetReported(WatchScenario.offsetNone, "the room reports the host's offset back at 0 s")
        #else
        // The sheet opens on the code: down the list, End room, the host's own controls above it, the participants
        // above them. The iPhone's player is in landscape, where the sheet shows a few rows at a time: each row is
        // brought on screen before it is read.
        revealInRoomPanel("watch.room.end", "the room panel ends in End room")
        revealInRoomPanel("watch.room.leave", "the host may leave the room too, which goes on without it")
        revealInRoomPanel("watch.room.lock", "the room panel offers the host the room's lock")
        revealInRoomPanel("watch.room.control", "the room panel offers the host who controls playback")
        ready(revealInRoomPanel(WatchScenario.offsetStep, "the room panel shows the host's offset while it plays the room's title")).tap()
        requireOffset(WatchScenario.offsetStepped, "a step moves the offset")
        // The relay's report adds the offset under this device's row, moving Reset down: the tap waits for it.
        revealInRoomPanel("watch.room.participant.0", "the room panel lists its participants")
        requireOffsetReported(WatchScenario.offsetStepped, "the room reports the host's offset")
        ready(revealInRoomPanel(WatchScenario.offsetReset, "the room panel offers the offset's Reset")).tap()
        requireOffset(WatchScenario.offsetNone, "Reset puts the offset back")
        revealInRoomPanel("watch.room.participant.0", "the room panel lists its participants")
        requireOffsetReported(WatchScenario.offsetNone, "the room reports the host's offset back at 0 s")
        #endif
        let guest = requireParticipant(named: WatchScenario.guestName, "the scripted guest joins the room")
        requireParticipantStatus(guest, timeout: 60, "the room panel shows the guest's file")
        let makeHost = "watch.room.participant.\(guest).makeHost"
        require(makeHost, timeout: 10, "the host may hand the room to the guest")
        #if os(macOS)
        let remove = require("watch.room.participant.\(guest).kick", timeout: 5, "the host may remove the guest")
        shot("room panel as host")
        ready(scrollTo(remove, over: panel, up: true, "the room panel scrolls back to the guest's row")).click()
        #else
        let remove = revealInRoomPanel("watch.room.participant.\(guest).kick", "the host may remove the guest")
        shot("room panel as host")
        ready(remove).tap()
        #endif
        let keep = require("watch.room.kick.cancel", timeout: 5, "Remove asks first")
        shot("remove question")
        activate(keep)
        requireGone("watch.room.kick.cancel", "Cancel takes the question away")
        requireParticipant(named: WatchScenario.guestName, timeout: 5, "Cancel keeps the guest in the room")
        closeSheet("player.watch.close", showing: "player.watch.panel", "Close (an iPhone: a pull down) closes the room panel")
        showControls(video)
        requireIndicator(count: 2, "the controls show the room's two participants")
        shot("player with the indicator")
        openRoomPanel(video)
        #if os(macOS)
        ready(scrollTo(element(makeHost), over: panel, "the room panel shows Make host on the guest's row")).click()
        requireGone("watch.room.end", timeout: 20, "Make host hands the room to the guest: End room goes with it")
        #else
        ready(revealInRoomPanel(makeHost, "the room panel offers Make host on the guest's row again")).tap()
        requireGone(makeHost, timeout: 20, "Make host hands the room to the guest: her row offers it no more")
        revealInRoomPanel("watch.room.leave", "the room panel still ends in Leave room")
        requireGone("watch.room.end", timeout: 5, "Make host hands the room to the guest: End room goes with it")
        #endif
        shot("room panel with the guest hosting")
        closeSheet("player.watch.close", showing: "player.watch.panel", "Close (an iPhone: a pull down) closes the room panel")

        // Anna, hosting, plays a movie of her own, 42 s long: the player finds the remux for it, 12 s shorter.
        require("player.watch.prompt", timeout: 120, "the new host's movie comes to the player, which offers a file 12 s shorter")
        require("player.watch.prompt.2", timeout: 5, "the prompt offers Don't play after Play anyway and Choose another stream")
        shot("guest mismatch prompt")
        #if os(macOS)
        ready(require("player.watch.prompt.0", "the prompt offers Play anyway")).click()
        requireGone("player.watch.prompt", "Play anyway takes the prompt away")
        #else
        // The prompt takes the middle alone (no transport, no bottom bar) in landscape too: the shot once the app has
        // turned and its picture stopped moving. The screen's own shot: the app's, in landscape, holds its portrait
        // frame cropped to the landscape canvas.
        XCUIDevice.shared.orientation = .landscapeLeft
        _ = waitUntil(10) { app.frame.width > app.frame.height }
        _ = screenSettles(XCUIScreen.main)
        shot("guest mismatch prompt in landscape", of: XCUIScreen.main)
        // Upright again on the iPad; the iPhone's player keeps to landscape.
        XCUIDevice.shared.orientation = .portrait
        _ = waitUntil(10) { (app.frame.height > app.frame.width) == isPad }
        // Declined, the file doesn't play: the prompt offers another stream alone, which is picked by hand.
        ready("player.watch.prompt.2").tap()
        requireGone("player.watch.prompt.2", "Don't play takes the offered file's choices away")
        let choose = require("player.watch.prompt.0", timeout: 5, "the declined file's prompt offers another stream")
        if element("player.watch.prompt.1").exists { fail("the declined file's prompt offers more than another stream") }
        shot("guest declined the file")
        ready(choose).tap()
        ready(require("streams.row.0", timeout: 60, "Choose another stream opens the movie's streams over the player")).tap()
        requireGone("player.watch.prompt", timeout: 60, "the stream picked by hand plays, the prompt gone")
        #endif
        showControls(video)
        require("player.watch.locked", timeout: 10, "the controls say who controls playback")
        if element("player.playPause").isEnabled { fail("play/pause is enabled for a guest in a host-only room") }
        shot("guest controls locked")

        // The room outlasts the player: Anna hands it back, and Home's shortcut counts it and opens it.
        #if os(macOS)
        showControls(video)
        ready(require("player.back", timeout: 5, "the controls offer back")).click()
        #else
        closePlayer(video, "the player's back button closes it")
        #endif
        require("detail.play", timeout: 10, "closing the player returns to the movie's details")
        goBack()
        let shortcut = requireWatchTogetherShortcut(reading: "2 watching together", "back on Home, Watch together counts the room's two")
        shot("home in a room")
        activate(shortcut)
        if requireRoomCode("Home's Watch together opens the room") != code { fail("Home's Watch together opens another room than \(code)") }
        requireRoomRole(WatchScenario.youHost, "the guest hands the room back once this device left its player")
        requireParticipant(named: WatchScenario.guestName, "the room lists the guest")
        shot("watch together in a room")
        openFromOutside(scenario.hostInvite)
        let cancel = require("watch.room.leave.cancel", timeout: 10, "an invite to another room asks the room's Leave question")
        shot("leave question for an invite")
        activate(cancel)
        requireGone("watch.room.leave.cancel", "Cancel takes the question away")
        if requireRoomCode("Cancel keeps the room") != code { fail("Cancel left room \(code)") }
        // The lock's switch follows the room's lock as the relay sets it.
        let lock = showOnScreen("watch.room.lock", "the host's room offers its lock")
        flipSwitch(lock)
        if !waitUntil(10, { switchValue(lock) == "1" }) { fail("the room's lock reads \(switchValue(lock)) after a flip, not on") }
        shot("room locked")
        flipSwitch(lock)
        if !waitUntil(10, { switchValue(lock) == "0" }) { fail("the room's lock reads \(switchValue(lock)) after a second flip, not off") }
        activate(showOnScreen("watch.room.end", "the host's room ends in End room"))
        activate(require("watch.room.end.confirm", timeout: 5, "End room asks first"))
        require("watch.join.field", timeout: 20, "End room ends the room, the screen back to joining one")
        goBack()
        requireWatchTogetherShortcut(reading: "Watch Together", "with the room ended, Home's shortcut reads Watch together")
    }

    func testGuestFindsItsFileAndPlaysWithTheHostsControls() throws {
        executionTimeAllowance = WatchScenario.timeAllowance
        let scenario = try WatchScenario()
        try launchDev(addon: true, settings: scenario.settings)
        #if os(macOS)
        require("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        #else
        requireTabScreen("nav.board", "Home shows at launch")
        #endif
        requireWatchTogetherShortcut(reading: "Watch Together", "Home's shortcuts offer Watch together")
        openFromOutside(scenario.hostInvite)
        require("player.watch.matching", timeout: 60, "the invite joins the room, whose title opens the player, which finds a file matching the host's")
        shot("guest finding a file")
        // The remux matches Sam's file: no prompt, and the player follows his pause, seek and speed.
        requirePlayer(playing: true, timeout: 120, "the remux matches the host's file and plays with the room")
        if element("player.watch.prompt").exists { fail("a matching file brings up the prompt") }
        requirePlayer(playing: false, timeout: 30, "the host pauses: the player pauses with the room")
        requirePosition(near: 2, timeout: 30, "the host seeks to 0:02: the player follows")
        requirePlayer(playing: false, timeout: 5, "the seek keeps the room paused")
        shot("guest paused at 0:02")
        #if os(macOS)
        requirePlayer(playing: true, rate: "1.5", timeout: 30, "the host plays at 1.5×: the player follows")
        // Finder in front of the window, which still shows: the player stays in the room, plays on, and follows Sam's
        // next pause (+42 s, near 0:17). The Mac's video exposes no position (`playerPosition`), so the pointer rests on
        // the control bar's position, which keeps the controls up (a pointer on them) with no further move while the
        // app isn't frontmost, and the position beside the timeline is read there.
        let video = require("player.video", "the player plays the guest's file")
        showControls(video)
        let shown = ready(require("player.position", timeout: 5, "the controls show the position"))
        shown.hover()
        let position = { shown.exists ? Self.seconds(self.text(of: shown)) : nil }
        withAnotherAppInFront {
            XCUIApplication(bundleIdentifier: "com.apple.finder").activate()
            if !waitUntil(10, { app.state == .runningBackground }) { fail("Finder doesn't come in front of the app") }
        }
        guard let start = position() else {
            return fail("with Finder in front the controls show no position: \(shown.exists ? text(of: shown) : "no controls")")
        }
        if !waitUntil(5, { (position() ?? start) > start }) { fail("the player stopped at \(start) s before the host's pause, Finder in front") }
        shot("guest playing with Finder in front")
        var last = position(), still = 0
        let held = waitUntil(25) {
            let now = position()
            still = now == last ? still + 1 : 0
            last = now
            return still >= 6
        }
        if !held || abs((position() ?? 0) - 17) > 2 {
            fail("with Finder in front of the window the player doesn't follow the host's pause near 0:17: at \(position() ?? 0) s")
        }
        app.activate()
        if !app.wait(for: .runningForeground, timeout: 10) { fail("the app doesn't come back in front of Finder") }
        #else
        let video = require("player.video", "the player plays the guest's file")
        if AVPictureInPictureController.isPictureInPictureSupported() {
            requireFollowingInPictureInPicture(video)
            requirePlayer(playing: false, rate: "1.5", timeout: 10, "back from picture in picture the player is paused with the room, at its 1.5×")
        } else {
            requirePlayer(playing: true, rate: "1.5", timeout: 30, "the host plays at 1.5×: the player follows")
            requirePlayer(playing: false, timeout: 30, "the host pauses again: the player follows")
        }
        #endif
        showControls(video)
        require("player.watch.locked", timeout: 10, "the controls say who controls playback")
        if element("player.playPause").isEnabled { fail("play/pause is enabled for a guest in a host-only room") }
        shot("guest controls locked")
        openRoomPanel(video)
        #if os(macOS)
        let panel = element("player.watch.panel")
        requireParticipant(named: WatchScenario.hostName, "the room panel lists the host")
        require("watch.room.leave", timeout: 5, "the room panel offers Leave room")
        if element("watch.room.end").exists { fail("a guest's room panel offers End room") }
        let step = require(WatchScenario.offsetStep, timeout: 5, "the room panel shows the guest's offset while it plays the room's title")
        ready(scrollTo(step, over: panel, "the room panel scrolls to the offset")).click()
        #else
        revealInRoomPanel("watch.room.leave", "the room panel ends in Leave room")
        if element("watch.room.end").exists { fail("a guest's room panel offers End room") }
        revealInRoomPanel("watch.room.participant.0", "the room panel lists its participants")
        requireParticipant(named: WatchScenario.hostName, "the room panel lists the host")
        ready(revealInRoomPanel(WatchScenario.offsetStep, "the room panel shows the guest's offset while it plays the room's title")).tap()
        #endif
        requireOffset(WatchScenario.offsetStepped, "a step moves the offset")
        shot("room panel as guest")
        // The host removes this device once he sees the offset.
        requireKicked("the host removes this device from the room")
        shot("removed from the room")
        closeSheet("player.watch.close", showing: "player.watch.panel", "Close (an iPhone: a pull down) closes the room panel")
        #if os(macOS)
        showControls(video)
        ready(require("player.back", timeout: 5, "the controls offer back")).click()
        #else
        closePlayer(video, "the player's back button closes it")
        #endif

        // The code as the relay prints it joins the room again; its title opens the player, which then leaves it.
        let field = require("watch.join.field", timeout: 20, "closing the player returns to Watch together, out of the room")
        activate(field)
        #if os(macOS)
        field.typeKey("a", modifierFlags: .command)
        field.typeText(WatchScenario.relayCode(scenario.hostCode))
        field.typeKey(.return, modifierFlags: [])
        #else
        field.typeText(WatchScenario.relayCode(scenario.hostCode) + "\n")
        #endif
        let again = require("player.video", timeout: 60, "the code joins the room again, whose title opens the player")
        requirePlayer(playing: false, timeout: 120, "the player finds the remux again, paused with the room")
        openRoomPanel(again)
        if WatchScenario.relayCode(requireRoomCode("the room panel shows the room's code")) != WatchScenario.relayCode(scenario.hostCode) {
            fail("the code \(scenario.hostCode) joins another room")
        }
        #if os(macOS)
        // Leave room ends the sheet's list, under the window's bottom edge at first; Close sits under the list.
        ready(scrollTo(element("watch.room.leave"), over: panel, "the room panel ends with Leave room")).click()
        #else
        ready(revealInRoomPanel("watch.room.leave", "Leave room at the room panel's end")).tap()
        #endif
        activate(require("watch.room.leave.confirm", timeout: 5, "Leave room asks first"))
        requireGone("player.watch.panel", timeout: 20, "Leave room, confirmed, closes the room panel")
    }

    /// Brings the player's controls up: a tap on the video (iOS, `showPlayerControls`), the pointer over it (the Mac).
    func showControls(_ video: XCUIElement) {
        #if os(macOS)
        pointerOn(ready(video), at: CGVector(dx: 0.3, dy: 0.4))
        pointerOn(ready(video), at: CGVector(dx: 0.5, dy: 0.5))
        require("player.playPause", timeout: 5, "the pointer over the video shows the controls")
        #else
        showPlayerControls(video, "a tap on the video shows the controls")
        #endif
    }

    /// Opens the room panel (`player.watch.panel`) by the controls' session indicator (`player.watch`).
    private func openRoomPanel(_ video: XCUIElement) {
        #if os(macOS)
        showControls(video)
        ready("player.watch").click()
        require("player.watch.panel", timeout: 10, "the indicator opens the room panel")
        #else
        tapPlayerControl("player.watch", video: video, until: { element("player.watch.panel").exists },
                         "the indicator opens the room panel")
        #endif
    }

    #if os(iOS)
    /// `id` in the room panel, the panel pulled up to full height by its top, as a person does (a swipe in it lifts an
    /// iPad's half-height sheet part of the way and lets it spring back), then dragged either way until it shows (Leave
    /// room at its end, the host's End room under it, the host's controls, the offset and the participants above them);
    /// returns it, or fails with `what`. Scoped: a guest who joined by an invite has the Watch together screen's own
    /// room behind the player, never hittable there.
    @discardableResult
    private func revealInRoomPanel(_ id: String, _ what: String) -> XCUIElement {
        let panel = ready("player.watch.panel")
        return revealInPlayerSheet(panel.descendants(matching: .any)[id].firstMatch, panel: "player.watch.panel", what)
    }

    /// Picture in picture from the controls' button while the room is paused at 0:02, then Home: the window over the
    /// home screen follows Sam's play at 1.5× (Pause on it, 12 s after his seek) and his next pause 10 s later (Play
    /// on it), still in the room; Restore fullscreen brings the app back to the player. Started before his play, the
    /// window has his whole 10 s of playing to show it plays (started after it, the window caught up with the
    /// pause first on a slow iPad simulator).
    private func requireFollowingInPictureInPicture(_ video: XCUIElement) {
        let window = XCUIApplication(bundleIdentifier: "com.apple.springboard").windows.matching(identifier: "PIP-SBInteractionPassThroughView").firstMatch
        tapPlayerControl("player.pip", video: video, until: { window.exists }, "the controls' picture in picture button opens its window")
        XCUIDevice.shared.press(.home)
        // After Home XCUITest still read the app as in the foreground (state 4) behind its PiP window.
        if !window.exists(within: 5) { fail("the picture in picture window went with the app at Home") }
        if [.notRunning, .runningBackgroundSuspended].contains(app.state) {
            fail("in picture in picture the app was suspended at Home (state \(app.state.rawValue))")
        }
        // The window's buttons show for a few seconds after a tap on it; a tap while they show could land on one.
        let shows = { (label: String) in window.buttons[label].exists && window.buttons[label].isHittable }
        let reads = { (label: String) -> Bool in
            if !shows("Pause"), !shows("Play") {
                window.tap()
                _ = self.waitUntil(2, { shows("Pause") || shows("Play") })
            }
            return shows(label)
        }
        if !waitUntil(30, { reads("Pause") }) { fail("in picture in picture over the home screen the player doesn't follow the host's play") }
        shot("guest in picture in picture over the home screen", of: XCUIScreen.main)
        if !waitUntil(20, { reads("Play") }) { fail("in picture in picture over the home screen the player doesn't follow the host's pause") }
        if !reads("Restore fullscreen") { fail("the picture in picture window shows no Restore fullscreen") }
        window.buttons["Restore fullscreen"].tap()
        if !app.wait(for: .runningForeground, timeout: 10) { fail("Restore fullscreen doesn't bring the app back") }
    }
    #endif
}
