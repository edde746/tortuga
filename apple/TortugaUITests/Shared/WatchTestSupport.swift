import XCTest

/// What the watch tests read (`TEST_RUNNER_TORTUGA_*` variables of xcodebuild, which reach the test runner without the
/// prefix): the Open Watch Party relay the app and the scripted participants share
/// (`TORTUGA_WATCH_RELAY`, `http://127.0.0.1:<port>`, the run's own relay tunnelled to this machine); the room the
/// scripted host Sam keeps for the guest's test (`TORTUGA_WATCH_CODE`, as the relay prints it, `K7MQ2X`; as rooms
/// show it, `K7M-Q2X`, it works the same); and the exchange the room the app starts goes to (`TORTUGA_EXCHANGE`), from
/// which the run's hook starts the scripted guest Anna in it. Each of them runs a fixed script that waits on what
/// the app does, so the tests keep its order:
/// - Anna, in the room the app hosts: her file matches the app's remux; once the app makes her host, she shares a movie
///   by its IMDb id alone, 42 s long, which the app's player opens by itself and finds the remux for, 12 s shorter
///   (the mismatch prompt); once the app plays it and then leaves its player, she hands the host back; she is done
///   when the app ends the room.
/// - Sam, host only, made before the app starts: once the app joins, he shares the test addon's slow-streams movie
///   with the remux's 30.058 s (a file search, then the remux, matched, playing); from when the app plays: at +8 s he
///   pauses, at +20 s seeks to 0:02 (paused), at +32 s plays at 1.5×, at +42 s pauses; once the app's offset reads
///   +0.5 s, he removes the app; the app joins again by the code, and 10 s after its player is ready there (paused
///   near 0:16, which the test checks first) he seeks to 0:00 and plays at 0.5×; the app leaves, and he ends the room.
struct WatchScenario {
    let relay: String
    let hostCode: String
    let exchange: URL

    init() throws {
        relay = try AccountScenario.variable("TORTUGA_WATCH_RELAY")
        hostCode = try AccountScenario.variable("TORTUGA_WATCH_CODE")
        exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
    }

    /// The launch's device setting: this relay.
    var settings: [String] { ["watch.relay=\(relay)"] }

    /// The invite to the scripted host's room.
    var hostInvite: URL { Self.invite(code: hostCode, relay: relay) }

    /// The invite to room `code` on `relay`, as the room's Share sends it from a self-hosted relay:
    /// `tortuga://watch?code=K7MQ2X&relay=…`.
    static func invite(code: String, relay: String) -> URL {
        var link = URLComponents()
        link.scheme = "tortuga"
        link.host = "watch"
        link.queryItems = [URLQueryItem(name: "code", value: relayCode(code)), URLQueryItem(name: "relay", value: relay)]
        return link.url!
    }

    /// A room code as the relay knows it: the code as rooms show it (`K7M-Q2X`) without its hyphen.
    static func relayCode(_ code: String) -> String {
        code.replacingOccurrences(of: "-", with: "")
    }

    /// The scripted guest the run starts in the app's room, and the scripted host of `hostCode`.
    static let guestName = "Anna"
    static let hostName = "Sam"

    /// The room's line saying who hosts (`watch.room.role`) when this device does.
    static let youHost = "You're the host"

    /// What the failure says once the host removed this device.
    static let kicked = "removed you"

    /// A well-formed code with no room behind it (the alphabet's last symbol; the run's rooms are random), as rooms
    /// show codes: the join field takes it with its hyphen or without.
    static let missingRoom = "ZZZ-ZZZ"

    /// The offset's +0.5 s step (`player.watch.offset.<i>`: -5 s, -0.5 s, +0.5 s, +5 s, Reset) and what the offset
    /// reads after it; Reset, and what it reads after that.
    static let offsetStep = "player.watch.offset.2"
    static let offsetStepped = "+0.5 s"
    static let offsetReset = "player.watch.offset.4"
    static let offsetNone = "0 s"

    /// The guest's and the host's way to the room's player can take a while: the test addon's streams answer 10 s
    /// late, then the guest opens each candidate.
    static let timeAllowance: TimeInterval = 600
}

/// The watch tests' steps the platforms share.
extension TortugaUITestCase {
    /// Publishes the room the app started (`code`, as the room shows it) as `watch-room`, as the relay knows it, from
    /// which the run's hook starts the scripted guest.
    func publishRoom(_ code: String, in scenario: WatchScenario) {
        publish("watch-room", code: WatchScenario.relayCode(code), to: scenario.exchange)
    }

    /// Home's Watch together shortcut (`board.shortcut.watchTogether`) once it reads `label` ("Watch Together" outside a
    /// room, "2 watching together" in one). Fails with `what` otherwise.
    @discardableResult
    func requireWatchTogetherShortcut(reading label: String, _ what: String) -> XCUIElement {
        let shortcut = require("board.shortcut.watchTogether", timeout: 60, what)
        if !waitUntil(30, { shortcut.label == label }) { fail("\(what): it reads \"\(shortcut.label)\", not \"\(label)\"") }
        return shortcut
    }

    /// Waits for the check of the relay on Watch together's screen to end with the relay reached; fails with `what` and
    /// what the screen says otherwise.
    func requireRelayReached(_ what: String) {
        let status = element("watch.relay.status")
        if !waitUntil(30, { !status.exists }) { fail("\(what): the screen says \"\(text(of: status))\"") }
    }

    /// The room's code as the room shows it: two groups of three symbols (`K7M-Q2X`). Fails with `what` otherwise.
    func requireRoomCode(_ what: String) -> String {
        let code = requireCode("watch.room.code", timeout: 30, what)
        if code.range(of: "^[0-9A-Z]{3}-[0-9A-Z]{3}$", options: .regularExpression) == nil {
            fail("\(what): the room shows its code as \"\(code)\", not as two groups of three symbols")
        }
        return code
    }

    /// Waits until the room lists `name` (`watch.room.participant.<i>.name`); its index. Fails with `what`.
    @discardableResult
    func requireParticipant(named name: String, timeout: TimeInterval = 60, _ what: String) -> Int {
        var found: Int?
        let listed = waitUntil(timeout) {
            found = nil
            for index in 0..<8 {
                let row = element("watch.room.participant.\(index).name")
                guard row.exists else { break }
                if text(of: row).contains(name) {
                    found = index
                    break
                }
            }
            return found != nil
        }
        if !listed { fail("\(what): the room doesn't list \(name)") }
        return found ?? 0
    }

    /// Waits until participant `index` shows a status line; its text. Fails with `what`.
    @discardableResult
    func requireParticipantStatus(_ index: Int, timeout: TimeInterval = 60, _ what: String) -> String {
        let status = element("watch.room.participant.\(index).status")
        if !waitUntil(timeout, { status.exists }) { fail("\(what): participant \(index) shows no status") }
        return text(of: status)
    }

    /// Waits for the session indicator (`player.watch`) to count `count` participants; fails with `what`.
    func requireIndicator(count: Int, _ what: String) {
        let indicator = require("player.watch", timeout: 20, what)
        let label = "\(count) watching"
        if !waitUntil(30, { indicator.exists && indicator.label.hasPrefix(label) }) { fail("\(what): the indicator reads \"\(indicator.label)\"") }
    }

    /// Waits for the room panel's offset (`player.watch.offset.value`) to read `value` ("+0.5 s", "0 s"); fails with
    /// `what` otherwise.
    func requireOffset(_ value: String, _ what: String) {
        let shown = require("player.watch.offset.value", timeout: 10, what)
        if !waitUntil(10, { text(of: shown) == value }) { fail("\(what): the offset reads \"\(text(of: shown))\", not \"\(value)\"") }
    }

    /// Waits for the room to report this device's offset back: its row among the participants gains the line "Offset
    /// +0.5 s" (`watch.room.participant.<i>.offset`; no line at 0 s, the other participants' at 0 s too) once the relay
    /// returns its status, a moment after the panel's own value (`requireOffset`) changed. The line moves what lies under
    /// the row, the offset's buttons among them: a click or tap aimed there before it comes lands beside them. Fails
    /// with `what` otherwise. Not on Apple TV, whose rows carry no such element and whose remote aims by focus.
    func requireOffsetReported(_ value: String, _ what: String) {
        let lines = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'watch.room.participant.' AND identifier ENDSWITH '.offset'"))
        let shown = { lines.allElementsBoundByIndex.map { self.text(of: $0) } }
        let reported = waitUntil(10) {
            value == WatchScenario.offsetNone ? shown().isEmpty : shown().contains(where: { $0.hasSuffix(value) })
        }
        if !reported { fail("\(what): this device's row reads \(shown()), not its offset \(value)") }
    }

    /// Waits for the room's line saying who hosts (`watch.room.role`, in the Watch together screen) to read `role`
    /// (`WatchScenario.youHost`); fails with `what` otherwise. On Apple TV the line's symbol carries the
    /// identifier too ("crown"), so any of its elements may read it.
    func requireRoomRole(_ role: String, timeout: TimeInterval = 30, _ what: String) {
        _ = require("watch.room.role", timeout: 10, what)
        let parts = app.descendants(matching: .any).matching(identifier: "watch.room.role")
        let reads = { parts.allElementsBoundByIndex.map { self.text(of: $0) } }
        if !waitUntil(timeout, { reads().contains(role) }) { fail("\(what): the room reads \(reads()), not \"\(role)\"") }
    }

    /// Waits for the room panel's failure (`player.watch.failure`) to say the host removed this device; fails with
    /// `what` otherwise.
    func requireKicked(_ what: String) {
        let failure = element("player.watch.failure")
        if !waitUntil(60, { failure.exists && text(of: failure).contains(WatchScenario.kicked) }) {
            fail("\(what): the room panel says \"\(failure.exists ? text(of: failure) : "nothing")\"")
        }
    }

    /// Waits until the player plays (`playing`) or is paused, at `rate` ("1.5") when given; fails with `what` and what
    /// the player reads otherwise. Playing means advancing: the position (`playerPosition`) moves on while it reads
    /// so, since a player opening or buffering reads as playing too. iOS and tvOS read the system's Now Playing
    /// (`player.nowPlaying`: `title=…; state=playing; rate=1.5; …`, `rate=0.0` while opening or buffering); the Mac
    /// reads the controls the pointer brings up (`playerPosition`): play/pause's label ("Play" while paused). The rate
    /// is the room's speed, the speed menu's value ("1.5×"), on iOS and tvOS with the controls brought up for it (a tap
    /// on the video, Up): Now Playing's rate is the engine's, which the room nudges off its speed (1.44, 1.56) while
    /// this device catches up.
    func requirePlayer(playing: Bool, rate: String? = nil, timeout: TimeInterval, _ what: String) {
        var reading = "nothing"
        var from: Double?
        let reached = waitUntil(timeout) {
            let read = playbackReading(playing: playing, rate: rate)
            reading = read.reading
            guard read.met else {
                from = nil
                return false
            }
            guard playing else { return true }
            guard let position = playerPosition() else { return false }
            reading += ", at \(Int(position)) s"
            if let from, position > from { return true }
            if from == nil { from = position }
            return false
        }
        if !reached { fail("\(what): the player reads \(reading)") }
    }

    /// Whether the player plays as `requirePlayer` asks, and what it reads.
    private func playbackReading(playing: Bool, rate: String?) -> (met: Bool, reading: String) {
        #if os(macOS)
        _ = playerPosition()
        let playPause = element("player.playPause")
        let speed = element("player.speed")
        guard playPause.exists else { return (false, "no controls") }
        let shown = speed.exists ? speed.value as? String ?? "" : ""
        let met = (playPause.label != "Play") == playing && (rate.map { shown == "\($0)×" } ?? true)
        return (met, "play/pause \"\(playPause.label)\", speed \"\(shown)\"")
        #else
        let probe = element("player.nowPlaying")
        guard probe.exists else { return (false, "no Now Playing probe") }
        let reading = probe.value as? String ?? ""
        let state = playing ? reading.contains("state=playing") && !reading.contains("rate=0.0;") : reading.contains("state=paused")
        guard let rate else { return (state, reading) }
        let speed = element("player.speed")
        if !speed.exists {
            #if os(tvOS)
            press(.up)
            #else
            let video = element("player.video")
            if video.exists { video.tap() }
            #endif
        }
        let shown = speed.exists ? speed.value as? String ?? "" : ""
        return (state && shown == "\(rate)×", "\(reading); speed \"\(shown)\"")
        #endif
    }

    /// Waits for the player's position (`playerPosition`, whole seconds) to read `seconds`, give or take a second;
    /// fails with `what` and the position read otherwise.
    func requirePosition(near seconds: Double, timeout: TimeInterval, _ what: String) {
        var position: Double?
        let reached = waitUntil(timeout) {
            position = playerPosition() ?? position
            return position.map { abs($0 - seconds) <= 1 } ?? false
        }
        if !reached { fail("\(what): the player is at \(position.map { "\($0) s" } ?? "no position"), not \(seconds) s") }
    }
}
