import XCTest

/// Watch together with the remote, against the run's Open Watch Party relay and the scripted participants
/// (`WatchScenario`), reached through Home's Watch together shortcut (`board.shortcut.watchTogether`) or an invite
/// link:
/// - the screen from Home's shortcut (Up from the first shelf lands on it), the relay reached; joining is the field
///   alone, no Join button; text that is no room code says so under the field, and a code with no room behind it,
///   joined by the keyboard's Join key, fails as the relay answers; the relay row names the run's relay;
/// - hosting: a room started from the player's options (Only I control; the movie's skip button put away: the remux
///   opens in its Opening chapter, an intro; paused by Select on the timeline) shows the room's code (`K7M-Q2X`) beside
///   its QR code, then the host's own: who controls playback, the lock, the offset (a +0.5 s step, then Reset), Leave
///   and End room; the scripted guest Anna, started in it by the run, joins with Make host on her row and Remove
///   under it, which asks first (Menu takes the question away, Anna kept); the session indicator beside the menu
///   buttons counts two and opens the room panel again, where Make host hands her the room (End room goes); her movie
///   comes to the player, which offers the remux, 12 s shorter, with Play anyway focused and Don't play beside it (the
///   mismatch prompt); played anyway, the controls say who controls playback; the player and the details closed by
///   Menu, Anna hands the room back, Home's shortcut reads "2 watching together" and opens the room, hosted by this
///   device again, where End room asks first, then ends it, focus on the join field; Menu returns to Home with focus on
///   the shortcut, back to "Watch Together";
/// - a guest's side: an invite link to the scripted host Sam's room joins it, and the player finds a file matching
///   his (the matching line in the spinner's place while the test addon's slow streams load), the remux, which plays
///   with no prompt and follows Sam: paused (the controls, up, say who controls playback), at 0:02, playing at
///   1.5×; the indicator opens the room panel, with no End room for a guest, where the offset steps by +0.5 s; Sam
///   removes this device, which the panel says; the panel and the player closed by Menu, the code typed as the relay
///   prints it, joined by the keyboard's Join key, joins the room again, whose title opens the player; Leave room asks
///   first, then leaves;
/// - a live stream (the test addon's 24/7 channel): Watch together in the Options tab opens the room panel on the line
///   that says a live stream can't be watched together, with focus on Close, whose Select closes the panel, focus back
///   on the options button;
/// - the room panel of a room the app hosts: Down from the lock and Up from the row under the offset land on its first
///   step, −5 s (`testDownAndUpIntoTheOffsetLandOnItsFirstStep`).
final class WatchTests: TortugaUITestCase {
    func testJoinErrorsAndTheRelay() throws {
        let scenario = try WatchScenario()
        try launchDev(addon: true, settings: scenario.settings)
        requireFocus("board.row.0.item.0", timeout: 90, "the Board's first card has focus once its shelves load")
        press(.up)
        requireFocus("board.shortcut.watchTogether", "Up from the first shelf lands on Home's first shortcut, Watch together")
        requireWatchTogetherShortcut(reading: "Watch Together", "Home's shortcuts offer Watch together")
        shot("home: watch together focused")
        press(.select)
        require("watch.join.field", timeout: 60, "Home's Watch together opens the screen with its join field")
        requireRelayReached("the screen reaches the relay")
        shot("watch together")

        if element("watch.join").exists { fail("the screen has a Join button apart from the join field") }
        walkFocus(to: "watch.join.field", "the remote reaches the join field")
        press(.select)
        app.typeText("AB1")
        putKeyboardAway("the code typed")
        require("watch.join.error", timeout: 5, "text that is no room code says so")
        shot("join field not a code")
        walkFocus(to: "watch.join.field", "the remote is back on the join field")
        press(.select)
        // Typed without its hyphen, as the keyboard's letters and digits take it.
        joinOnKeyboard(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 3) + WatchScenario.relayCode(WatchScenario.missingRoom))
        require("watch.failure", timeout: 30, "a code with no room behind it fails once the keyboard's Join key joins it")
        shot("join error")

        walkFocus(to: "watch.relay.setting", "the remote reaches the relay row")
        let host = URL(string: scenario.relay)?.host ?? scenario.relay
        let row = element("watch.relay.setting")
        if !row.label.contains(host) { fail("the relay row reads \"\(row.label)\", not the run's relay \(host)") }
        shot("relay row focused")
    }

    func testDownAndUpIntoTheOffsetLandOnItsFirstStep() throws {
        executionTimeAllowance = WatchScenario.timeAllowance
        let scenario = try WatchScenario()
        try launchDev(addon: true, open: ["board", "detail:movie:tortuga-remux"], settings: scenario.settings)
        requireFocus("detail.play", timeout: 60, "Play has focus once the movie's details show")
        press(.select)
        requireFocus("streams.row.0", timeout: 60, "Play opens the Streams sheet with the remux focused")
        press(.select)
        require("player.video", timeout: 30, "Select on the remux opens the player")
        let scrub = element("player.scrub")
        requirePlayback(1, timeout: 60)
        putAwaySkipButton()
        requireGone("player.scrub", "the controls go as the remux plays")
        bringUpControls()
        requireFocus("player.scrub", "the timeline has focus as the controls come up")
        press(.select)
        if !waitUntil(5, { scrub.exists && scrub.label == "Play" }) { fail("Select on the timeline doesn't pause the remux") }
        walkControls(to: "player.more", "the remote reaches the options")
        press(.select)
        walkFocus(to: "player.option.watchTogether", limit: 12, "the remote reaches Watch together in the Options tab")
        press(.select)
        requireFocus("player.watch.start.hostOnly", "Watch together's page focuses its first start choice")
        press(.select)
        _ = requireRoomCode("the room panel shows the room's code")
        walkFocus(to: "watch.room.lock", "the remote reaches the room's lock")
        var problems: [String] = []
        press(.down)
        if !waitUntil(3, { holdsFocus("player.watch.offset.0") }) {
            problems.append("Down from the lock leaves focus on \(focusedId ?? "nothing"), not player.watch.offset.0")
        }
        shot("offset from the lock")
        walkFocus(to: WatchScenario.offsetReset, "the remote reaches the offset's Reset, its last step")
        press(.down)
        let below = focusedId ?? "nothing"
        press(.up)
        if !waitUntil(3, { holdsFocus("player.watch.offset.0") }) {
            problems.append("Up from \(below) leaves focus on \(focusedId ?? "nothing"), not player.watch.offset.0")
        }
        if !problems.isEmpty { fail(problems.joined(separator: "\n")) }
    }

    func testHostStartsARoomFromThePlayerAndAGuestJoins() throws {
        executionTimeAllowance = WatchScenario.timeAllowance
        let scenario = try WatchScenario()
        try launchDev(addon: true, open: ["board", "detail:movie:tortuga-remux"], settings: scenario.settings)
        requireFocus("detail.play", timeout: 60, "Play has focus once the movie's details show")
        press(.select)
        requireFocus("streams.row.0", timeout: 60, "Play opens the Streams sheet with the remux focused")
        press(.select)
        require("player.video", timeout: 30, "Select on the remux opens the player")
        // The remux opens in its Opening chapter, an intro: its skip button takes focus as it plays, where Select skips
        // to 10 s. Put away, the controls go as the remux plays; Up brings them back with the timeline focused, whose
        // Select (named by play/pause) pauses: the controls stay up, and the room keeps the host's pause.
        let scrub = element("player.scrub")
        requirePlayback(1, timeout: 60)
        putAwaySkipButton()
        requireGone("player.scrub", "the controls go as the remux plays")
        bringUpControls()
        requireFocus("player.scrub", "the timeline has focus as the controls come up")
        press(.select)
        if !waitUntil(5, { scrub.exists && scrub.label == "Play" }) { fail("Select on the timeline doesn't pause the remux") }
        walkControls(to: "player.more", "the remote reaches the options")
        press(.select)
        walkFocus(to: "player.option.watchTogether", limit: 12, "the remote reaches Watch together in the Options tab")
        press(.select)
        requireFocus("player.watch.start.hostOnly", "Watch together's page focuses its first start choice")
        shot("player watch together start choices")
        press(.select)
        let code = requireRoomCode("the room panel shows the room's code")
        publishRoom(code, in: scenario)
        require("watch.room.control.hostOnly", timeout: 5, "the room panel offers the host who controls playback")
        require("watch.room.lock", timeout: 5, "the room panel offers the host the room's lock")
        require("watch.room.leave", timeout: 5, "the host may leave the room, which goes on without it")
        require("watch.room.end", timeout: 5, "the host may end the room for everyone")
        walkFocus(to: WatchScenario.offsetStep, "the remote reaches the offset's +0.5 s")
        press(.select)
        requireOffset(WatchScenario.offsetStepped, "a step moves the offset")
        walkFocus(to: WatchScenario.offsetReset, "the remote reaches the offset's Reset")
        press(.select)
        requireOffset(WatchScenario.offsetNone, "Reset puts the offset back")
        let makeHost = app.descendants(matching: .any).matching(NSPredicate(format: "identifier ENDSWITH '.makeHost'")).firstMatch
        if !makeHost.exists(within: 60) { fail("the room panel doesn't list the scripted guest with Make host") }
        // Its value is the action and the guest's status ("Make host, Ready"): VoiceOver reads both.
        if !waitUntil(30, { makeHost.label.contains(WatchScenario.guestName) && (makeHost.value as? String ?? "").hasPrefix("Make host, ") }) {
            fail("the guest's Make host row reads \"\(makeHost.value as? String ?? "")\" as its value, without the guest's status")
        }
        // `watch.room.participant.<i>`: the guest's row, Make host its button, Remove on the line under it.
        let guest = makeHost.identifier.replacingOccurrences(of: ".makeHost", with: "")
        shot("room panel as host")
        walkFocus(to: "\(guest).kick", "the remote reaches Remove under the guest's row")
        press(.select)
        requireDialogFocus("watch.room.kick.cancel", "Remove asks first")
        shot("remove question")
        press(.menu)
        requireGone("watch.room.kick.cancel", "Menu takes the question away")
        require("\(guest).makeHost", timeout: 5, "Menu on the question keeps the room panel and the guest in the room")
        press(.menu)
        requireGone("player.watch.panel", "Menu closes the room panel")
        bringUpControls()
        requireIndicator(count: 2, "the controls show the room's two participants")
        // The host's indicator sits left of Speed, the first of the menu buttons. Focused there after the Remove
        // question, XCUITest reports nothing focused in about half the runs (the screen shows the indicator focused), so
        // the remote reaches Speed, then Left, and the room panel opening on Select shows the indicator had focus.
        walkControls(to: "player.speed", "the remote reaches Speed, beside the session indicator")
        press(.left)
        shot("player with the indicator")
        press(.select)
        require("player.watch.panel", timeout: 10, "Left from Speed reaches the session indicator, which opens the room panel")
        walkFocus(to: "\(guest).makeHost", "the remote reaches Make host on the guest's row")
        press(.select)
        requireGone("watch.room.end", timeout: 20, "Make host hands the room to the guest: End room goes with it")
        shot("room panel with the guest hosting")
        press(.menu)
        requireGone("player.watch.panel", "Menu closes the room panel")

        // Anna, hosting, plays a movie of her own, 42 s long: the player finds the remux for it, 12 s shorter.
        require("player.watch.prompt", timeout: 120, "the new host's movie comes to the player, which offers a file 12 s shorter")
        requireFocus("player.watch.prompt.0", "the prompt focuses Play anyway")
        require("player.watch.prompt.2", timeout: 5, "the prompt offers Don't play after Play anyway and Choose another stream")
        shot("guest mismatch prompt")
        press(.select)
        requireGone("player.watch.prompt", "Play anyway takes the prompt away")
        // The controls stay up while the remux loads, and go 5 s after it plays; Up brings them back, with the line
        // over the timeline.
        requirePlayerLoaded("Play anyway plays the remux")
        _ = waitUntil(15) { !element("player.scrub").exists }
        bringUpControls()
        require("player.watch.locked", timeout: 2, "the controls say who controls playback")
        shot("guest controls locked")

        // The room outlasts the player: Anna hands it back, and Home's shortcut counts it and opens it.
        for _ in 0..<3 where element("player.video").exists { press(.menu) }
        requireGone("player.video", "Menu closes the player")
        require("detail.play", timeout: 10, "closing the player returns to the movie's details")
        press(.menu)
        // The Board's first card takes focus: Continue Watching's once the remux played long enough to be in progress,
        // above the shortcuts, else the first shelf's, under them.
        if !waitUntil(30, { ["board.cw.", "board.row."].contains { focusedId?.hasPrefix($0) == true } }) {
            fail("Menu on the details doesn't return to the Board's cards: focus is on \(focusedId ?? "nothing")")
        }
        press(focusedId?.hasPrefix("board.cw.") == true ? .down : .up)
        requireFocus("board.shortcut.watchTogether", "the remote moves from the Board's first card to Watch together")
        requireWatchTogetherShortcut(reading: "2 watching together", "back on Home, Watch together counts the room's two")
        shot("home in a room: watch together focused")
        press(.select)
        if requireRoomCode("Home's Watch together opens the room") != code { fail("Home's Watch together opens another room than \(code)") }
        requireRoomRole(WatchScenario.youHost, "the guest hands the room back once this device left its player")
        shot("watch together in a room")
        focusRoomButton("watch.room.end", "End room: the remote reaches it")
        press(.select)
        focusDialogButton("watch.room.end.confirm", "End room asks first, and the remote reaches its answer")
        press(.select)
        requireFocus("watch.join.field", "End room ends the room, the screen back to joining one with the join field focused")
        press(.menu)
        requireFocus("board.shortcut.watchTogether", timeout: 10, "Menu returns to Home with focus on Watch together")
        requireWatchTogetherShortcut(reading: "Watch Together", "with the room ended, Home's shortcut reads Watch together")
    }

    func testGuestFindsItsFileAndPlaysWithTheHostsControls() throws {
        executionTimeAllowance = WatchScenario.timeAllowance
        let scenario = try WatchScenario()
        try launchDev(addon: true, settings: scenario.settings)
        requireFocus("board.row.0.item.0", timeout: 90, "the Board's first card has focus once its shelves load")
        requireWatchTogetherShortcut(reading: "Watch Together", "Home's shortcuts offer Watch together")
        openFromOutside(scenario.hostInvite)
        require("player.watch.matching", timeout: 60, "the invite joins the room, whose title opens the player, which finds a file matching the host's")
        shot("guest finding a file")
        // The remux matches Sam's file: no prompt, and the player follows his pause, seek and speed.
        requirePlayer(playing: true, timeout: 120, "the remux matches the host's file and plays with the room")
        if element("player.watch.prompt").exists { fail("a matching file brings up the prompt") }
        requirePlayer(playing: false, timeout: 30, "the host pauses: the player pauses with the room")
        // Paused, the controls stay up once Up brings them, with the line over the timeline.
        bringUpControls()
        require("player.watch.locked", timeout: 5, "the controls say who controls playback")
        shot("guest controls locked")
        requirePosition(near: 2, timeout: 30, "the host seeks to 0:02: the player follows")
        requirePlayer(playing: false, timeout: 5, "the seek keeps the room paused")
        shot("guest paused at 0:02")
        requirePlayer(playing: true, rate: "1.5", timeout: 30, "the host plays at 1.5×: the player follows")
        bringUpControls()
        walkControls(to: "player.watch", "the remote reaches the session indicator")
        press(.select)
        require("player.watch.panel", timeout: 10, "the indicator opens the room panel")
        require("watch.room.leave", timeout: 5, "the room panel offers Leave room")
        if element("watch.room.end").exists { fail("a guest's room panel offers End room") }
        walkFocus(to: WatchScenario.offsetStep, "the remote reaches the offset's +0.5 s")
        press(.select)
        requireOffset(WatchScenario.offsetStepped, "a step moves the offset")
        shot("room panel as guest")
        // The host removes this device once he sees the offset.
        requireKicked("the host removes this device from the room")
        shot("removed from the room")
        press(.menu)
        requireGone("player.watch.panel", "Menu closes the room panel")
        for _ in 0..<3 where element("player.video").exists { press(.menu) }
        requireGone("player.video", "Menu closes the player")

        // The code as the relay prints it joins the room again; its title opens the player, which then leaves it.
        require("watch.join.field", timeout: 20, "closing the player returns to Watch together, out of the room")
        walkFocus(to: "watch.join.field", "the remote reaches the join field")
        press(.select)
        joinOnKeyboard(WatchScenario.relayCode(scenario.hostCode))
        require("player.video", timeout: 60, "the code joins the room again, whose title opens the player")
        requirePlayer(playing: false, timeout: 120, "the player finds the remux again, paused with the room")
        bringUpControls()
        walkControls(to: "player.watch", "the remote reaches the session indicator")
        press(.select)
        require("player.watch.panel", timeout: 10, "the indicator opens the room panel")
        if WatchScenario.relayCode(requireRoomCode("the room panel shows the room's code")) != WatchScenario.relayCode(scenario.hostCode) {
            fail("the code \(scenario.hostCode) joins another room")
        }
        focusRoomButton("watch.room.leave", "Leave room: the remote reaches it")
        press(.select)
        focusDialogButton("watch.room.leave.confirm", "Leave room asks first, and the remote reaches its answer")
        press(.select)
        requireGone("player.watch.panel", timeout: 20, "Leave room, confirmed, closes the room panel")
    }

    func testALiveStreamCantStartARoom() throws {
        let scenario = try WatchScenario()
        try launchDev(addon: true, open: Self.liveChannelOpen, settings: scenario.settings)
        let row = livePlainStream("the channel's Streams screen").identifier
        requireFocus(prefix: "streams.row.", "the Streams screen focuses a stream")
        moveFocus(to: row, with: .down, "Down moves focus to the plain stream")
        press(.select)
        require("player.video", timeout: 30, "Select on the plain stream opens the player")
        requirePlayback(2, timeout: 90)
        // The controls the player opened with hide on their own; Up brings them back with focus on play/pause, and Up
        // again moves it into the buttons' row above it (a focus section), along which the options are: right of play/
        // pause there is nothing to move to.
        requireGone("player.playPause", timeout: 15, "the controls hide while the channel plays")
        press(.up)
        requireFocus("player.playPause", "Up brings the controls up with focus on play/pause")
        press(.up)
        walkFocus(to: "player.more", "the remote reaches the options")
        press(.select)
        walkFocus(to: "player.option.watchTogether", limit: 12, "the remote reaches Watch together in the Options tab")
        press(.select)
        require("player.watch.start.none", timeout: 10, "Watch together's page says a live stream can't be watched together")
        requireFocus("player.watch.close", "the live stream's room panel focuses Close")
        shot("live stream room panel")
        press(.select)
        requireGone("player.watch.close", timeout: 5, "Close closes the room panel")
        requireFocus("player.more", "focus goes back to the options button once Close closed the panel")
    }

    /// Types `text` on the keyboard the join field opened and joins with the keyboard's Join key: the return key, or
    /// Select on the keyboard's Join (or Done) button where return only moves focus there. Fails when the keyboard stays.
    private func joinOnKeyboard(_ text: String) {
        app.typeText(text + "\n")
        let keyboard = app.keyboards.firstMatch
        let key = app.buttons.matching(NSPredicate(format: "label ==[c] 'join' OR label ==[c] 'done'")).firstMatch
        for _ in 0..<4 where keyboard.exists {
            if key.exists, key.hasFocus {
                press(.select)
                _ = waitUntil(3) { !keyboard.exists }
            } else {
                press(.down)
            }
        }
        if keyboard.exists { fail("the keyboard stays up after typing \(text): focus is on \(focusedEntry)") }
    }

    /// Up brings the player's controls up (the timeline focused).
    private func bringUpControls() {
        for _ in 0..<3 where !element("player.scrub").exists { press(.up) }
        require("player.scrub", timeout: 5, "Up brings the controls up")
    }

    /// Down until the room's button `id` (Leave room, the host's End room under it) has focus: the room's form builds
    /// its rows as they scroll in, so there is no frame to walk towards before; fails with `what` otherwise.
    private func focusRoomButton(_ id: String, _ what: String) {
        for _ in 0..<16 where !holdsFocus(id) { press(.down) }
        if !waitUntil(2, { holdsFocus(id) }) {
            fail("\(what): focus is on \(focusedId ?? "nothing"), not \(id)")
        }
    }
}
