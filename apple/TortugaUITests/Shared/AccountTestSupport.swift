import Network
import XCTest

/// What the account tests read, after an Android phone signed up first: `TEST_RUNNER_TORTUGA_*` variables of
/// xcodebuild, which reach the test runner without the prefix.
struct AccountScenario {
    /// The self-hosted server the phone signed up on (`TORTUGA_SERVER`), typed into "Use your own server".
    let server: String
    /// The name of the phone's PIN-locked profile (`TORTUGA_PROFILE`).
    let profile: String
    /// That profile's PIN, digit by digit (`TORTUGA_PIN`).
    let pin: [Int]
    /// A title of the test addon's catalog, which the phone moved to the top (`TORTUGA_SYNCED_TITLE`).
    let syncedTitle: String
    /// The directory the codes the app shows go to, one file per event (`TORTUGA_EXCHANGE`: the run's
    /// `<run dir>/exchange`); the run's watcher hands each to its hook.
    let exchange: URL

    /// A run of the account tests takes minutes of waiting for the phone: the device code's login or the link's
    /// approval, then the profiles' sync.
    static let timeAllowance: TimeInterval = 1200

    init() throws {
        server = try Self.variable("TORTUGA_SERVER")
        profile = try Self.variable("TORTUGA_PROFILE")
        let typedPin = try Self.variable("TORTUGA_PIN")
        let digits = typedPin.compactMap { $0.isASCII ? $0.wholeNumberValue : nil }
        guard digits.count == typedPin.count else { throw HarnessError("TORTUGA_PIN is \"\(typedPin)\", not digits") }
        pin = digits
        syncedTitle = try Self.variable("TORTUGA_SYNCED_TITLE")
        exchange = try URL(fileURLWithPath: Self.variable("TORTUGA_EXCHANGE"), isDirectory: true)
    }

    /// The test runner's environment variable `name`; throws when it is missing or empty.
    static func variable(_ name: String) throws -> String {
        guard let value = ProcessInfo.processInfo.environment[name], !value.isEmpty else {
            throw HarnessError("\(name) is not set: the test needs the run's server, account, relay or folder, passed with xcodebuild's TEST_RUNNER_ prefix")
        }
        return value
    }
}

/// The dev stack's server behind a TCP relay of the test's own on 127.0.0.1, which the simulators share with the Mac
/// they run on (the Mac app runs on it): the app signs in on the relay's address (`url`), so the test takes the server
/// out of the app's reach and brings it back without touching the stack or its tunnel, which other runs share. Closed,
/// the port refuses connections and the relayed ones end, as when the server is down; opened again, it listens on the
/// same port, the one the app's session holds.
final class ServerRelay: @unchecked Sendable {
    /// The server, as `TORTUGA_SERVER` names it.
    private let server: NWEndpoint
    /// Where the handlers run; `connections` and `failure` are touched only there.
    private let queue = DispatchQueue(label: "ServerRelay")
    private var connections: [NWConnection] = []
    /// Why the listener failed to start.
    private var failure: NWError?
    /// The listener while the relay is open (`open()` and `close()` run on the test's thread).
    private var listener: NWListener?
    /// 127.0.0.1's port the relay listens on: the system's pick at the first `open()`, the same one after.
    private var port = NWEndpoint.Port.any

    init(to server: String) throws {
        guard let url = URL(string: server), let host = url.host,
              let port = url.port.flatMap({ NWEndpoint.Port(rawValue: UInt16($0)) }) else {
            throw HarnessError("TORTUGA_SERVER is \(server), not http://<host>:<port>")
        }
        self.server = .hostPort(host: NWEndpoint.Host(host), port: port)
    }

    /// The relay's address, for "Use your own server".
    var url: String { "http://127.0.0.1:\(port.rawValue)" }

    /// Listens (the first time on a port the system picks, then on that one), relaying each connection to the server;
    /// throws when it doesn't listen within 10 s (another process took the port meanwhile). Open already: nothing.
    func open() throws {
        guard listener == nil else { return }
        queue.sync { failure = nil }
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: port)
        let listener = try NWListener(using: parameters)
        let settled = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { [self] state in
            switch state {
            case .ready: settled.signal()
            case .failed(let error):
                failure = error
                settled.signal()
            default: break
            }
        }
        listener.newConnectionHandler = { [self] in relay($0) }
        listener.start(queue: queue)
        let ready = settled.wait(timeout: .now() + 10) == .success
        let failed = queue.sync { failure }
        guard ready, failed == nil, let bound = listener.port else {
            listener.cancel()
            throw HarnessError("the relay doesn't listen on 127.0.0.1:\(port.rawValue): \(failed.map { "\($0)" } ?? "not ready in 10 s")")
        }
        port = bound
        self.listener = listener
    }

    /// Stops listening and ends every relayed connection: the port refuses connections until `open()`. Closed
    /// already: nothing.
    func close() {
        guard let listener else { return }
        self.listener = nil
        let cancelled = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { if case .cancelled = $0 { cancelled.signal() } }
        listener.cancel()
        _ = cancelled.wait(timeout: .now() + 10)
        queue.sync {
            for connection in connections { connection.cancel() }
            connections = []
        }
    }

    /// Whether the server answers through the relay now: its `/v2/info`, which the run's stack check asks for too.
    func answers() -> Bool {
        guard let info = URL(string: "\(url)/v2/info") else { return false }
        return (try? Data(contentsOf: info)) != nil
    }

    /// Relays `inbound` (the app's connection) to a connection of its own to the server, both ways, until either ends.
    private func relay(_ inbound: NWConnection) {
        let outbound = NWConnection(to: server, using: .tcp)
        connections += [inbound, outbound]
        // The server out of reach leaves `outbound` waiting to retry: the app's connection ends instead.
        let end: @Sendable (NWConnection.State) -> Void = { state in
            switch state {
            case .failed, .waiting, .cancelled:
                inbound.cancel()
                outbound.cancel()
            default: break
            }
        }
        inbound.stateUpdateHandler = end
        outbound.stateUpdateHandler = end
        inbound.start(queue: queue)
        outbound.start(queue: queue)
        pump(inbound, to: outbound)
        pump(outbound, to: inbound)
    }

    /// Sends on to `sink` what `source` receives, and the end of `source`'s stream as the end of `sink`'s.
    private func pump(_ source: NWConnection, to sink: NWConnection) {
        source.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [self] data, _, isComplete, error in
            if let data, !data.isEmpty { sink.send(content: data, completion: .idempotent) }
            if isComplete {
                sink.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .idempotent)
            } else if error == nil {
                pump(source, to: sink)
            }
        }
    }
}

/// The account's sync status as the tests read it (shared/i18n/en.i18n.json's `account.status`; the app runs in
/// English).
enum SyncStatusText {
    static let idle = "Up to date"
    /// The server out of reach (`SyncStatus.Kind.Offline`).
    static let offline = "Offline. Changes sync when the connection is back."
    /// Records refused for clock drift (`SyncStatus.Failure.CLOCK_WRONG`).
    static let clockWrong = "Some changes can't sync. Check the date and time on your devices."
}

/// The account tests' steps both platforms share: handing a code to the run's hook, reading a code off the screen,
/// "Use your own server", the profile tile and the Board's first shelf; the account's other session on the dev stack
/// and the steps of signing in there, opening Ana and reading Settings → Account's sync status (the notices, GitHub and
/// sync tests); on tvOS, moving focus by the remote towards a control on screen.
extension TortugaUITestCase {
    /// Publishes `code` as `event` (`device-code`, `link-code`): the file `<exchange>/<event>`, written beside it as
    /// `.<event>.tmp` and moved into place, so the run's watcher never reads half a code.
    func publish(_ event: String, code: String, in scenario: AccountScenario) {
        publish(event, code: code, to: scenario.exchange)
    }

    /// Publishes `code` as `event` in the run's `exchange` directory (created if it isn't there yet), for the
    /// run's watcher, which hands it to its hook.
    func publish(_ event: String, code: String, to exchange: URL) {
        let temporary = exchange.appendingPathComponent(".\(event).tmp")
        let published = exchange.appendingPathComponent(event)
        do {
            try FileManager.default.createDirectory(at: exchange, withIntermediateDirectories: true)
            try Data(code.utf8).write(to: temporary)
        } catch {
            fail("can't write \(temporary.path): \(error)")
            return
        }
        if rename(temporary.path, published.path) != 0 {
            fail("can't move \(temporary.path) to \(published.path): \(String(cString: strerror(errno)))")
        }
    }

    /// Asks the run's hook for the account's other session's `command` (`reset`, `add 1`, `delete`, which the run's
    /// sync peer runs) as the event `peer-<command>` (`peer-add-1`), or with a `number` past 1
    /// `peer-<command>-<number>` (`peer-reset-2`: the run's watcher takes each event's name once, so a run's later
    /// ask of a command needs a name of its own); its answer comes to the returned file.
    func askPeer(_ command: String, _ exchange: URL, number: Int = 1) -> URL {
        let event = "peer-" + command.replacingOccurrences(of: " ", with: "-") + (number == 1 ? "" : "-\(number)")
        publish(event, code: "\(exchange.path) \(command)", to: exchange)
        return exchange.appendingPathComponent("replies/\(event)")
    }

    /// The other session's answer at `reply` (`ok <JSON>`) as JSON; nil while it hasn't come. Fails with `what` on any
    /// other answer (`error <why>`).
    func peerAnswer(_ reply: URL, _ what: String) -> [String: Any]? {
        guard let text = try? String(contentsOf: reply, encoding: .utf8) else { return nil }
        guard text.hasPrefix("ok "),
              let json = try? JSONSerialization.jsonObject(with: Data(text.dropFirst(3).utf8)) as? [String: Any] else {
            fail("\(what): the other session answered \(text)")
            return [:]
        }
        return json
    }

    /// The other session's `command` (as `askPeer`'s, with its `number`), answered within 60 s.
    @discardableResult
    func peer(_ command: String, _ exchange: URL, _ what: String, number: Int = 1) -> [String: Any] {
        let reply = askPeer(command, exchange, number: number)
        var json: [String: Any]?
        if !waitUntil(60, { json = peerAnswer(reply, what); return json != nil }) { fail("\(what): no answer in 60 s") }
        return json ?? [:]
    }

    /// The sign-in named `provider` ("GitHub") in Settings → Account's list of the account's sign-ins.
    func listedSignIn(_ provider: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label == %@ OR value == %@", provider, provider)).firstMatch
    }

    /// Waits for the code `id` (`deviceCode.code`, `link.code`) and returns it as shown (`ABCD-EFGH`): its label, or
    /// its value where the label is empty; fails with `what` when it doesn't show one.
    func requireCode(_ id: String, timeout: TimeInterval, _ what: String) -> String {
        let text = require(id, timeout: timeout, what)
        var code = ""
        let shown = waitUntil(10) {
            let label = text.label.isEmpty ? (text.value as? String ?? "") : text.label
            code = label.trimmingCharacters(in: .whitespacesAndNewlines)
            return !code.isEmpty
        }
        if !shown { fail("\(what): \(id) shows no code") }
        return code
    }

    /// Waits for the first profile tile to be `profile` (a tile is named by the profile's name alone, its state, locked
    /// or current, in its value); fails with `what` when no tile shows within `timeout` or the first one is another
    /// profile.
    @discardableResult
    func requireProfileTile(_ profile: String, timeout: TimeInterval, _ what: String) -> XCUIElement {
        let tile = require("profiles.tile.0", timeout: timeout, what)
        if !waitUntil(30, { tile.label == profile }) {
            fail("\(what): the first profile is \"\(tile.label)\", not \(profile)")
        }
        return tile
    }

    /// Waits for a card labelled `title` on the Board's first catalog shelf (`board.row.0.item.<i>`); fails with `what`
    /// and the shelf's titles when none shows.
    func requireFirstShelfItem(labelled title: String, timeout: TimeInterval = 120, _ what: String) {
        let items = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier MATCHES %@", #"board\.row\.0\.item\.[0-9]+"#))
        if !items.matching(NSPredicate(format: "label == %@", title)).firstMatch.exists(within: timeout) {
            fail("\(what): the first shelf shows \(items.allElementsBoundByIndex.map { $0.label }), not \"\(title)\"")
        }
    }

    /// The test addon's fake of the hosted sync server (test_addon.py's `/signin`): the product starts its sign-in screen
    /// there with `--dev-sign-in-server`, and it holds the screen's sign-in options (Apple's web flow and GitHub, with
    /// client IDs of its own: fewer than the hosted server's, so the room the screen keeps for those meets a shorter
    /// answer) from `holdSignInOptions()` until
    /// `releaseSignInOptions()`.
    var signInServer: URL {
        get throws {
            guard let manifest = URL(string: try addonURL) else { throw HarnessError("TORTUGA_ADDON is no URL") }
            return manifest.deletingLastPathComponent().appendingPathComponent("signin")
        }
    }

    /// From now on the fake sign-in server holds its sign-in options (at most two minutes).
    func holdSignInOptions() throws {
        _ = try Data(contentsOf: signInServer.appendingPathComponent("hold"))
    }

    /// The fake sign-in server answers the requests it holds, and the next ones at once.
    func releaseSignInOptions() throws {
        _ = try Data(contentsOf: signInServer.appendingPathComponent("release"))
    }

    /// The splash of a product start held 3 s at its account step (`--dev-slow-start`), the screen sampled from the
    /// launch (`ScreenSampler`): no frame before the line is a light screen of one colour (a system launch screen, or a
    /// window not drawn yet, in the system's light appearance, before the black splash; macOS: the app asked for the
    /// light appearance, the Mac's being dark), "Loading your account…" shows under the loader, then the sign-in
    /// screen without it. Attached: the light frame where one shows, the last sampled frame before the line that shows
    /// the splash (the window black and wordless: the loader alone, while the start is under half a second; none when
    /// no frame caught it), the line, and the sign-in screen.
    func requireSplashStatus() throws {
        // An app an earlier test left up goes first, so that every frame showing the splash is this launch's.
        app.terminate()
        let sampler = ScreenSampler()
        sampler.start(limit: 30)
        var arguments = ["--dev-sign-in-server", try signInServer.absoluteString, "--dev-slow-start", "3"]
        #if os(macOS)
        arguments += ["-NSRequiresAquaSystemAppearance", "YES"]
        #endif
        launch(arguments)
        let status = app.staticTexts["startup.status"]
        if !status.waitForExistence(timeout: 30) { fail("the splash names no step while the start is held") }
        let line = text(of: status)
        if line != "Loading your account…" { fail("the splash's line reads \"\(line)\", not the account step's") }
        let frames = sampler.stop()
        shot("splash status")
        let window = app.windows.firstMatch.frame
        let named = frames.firstIndex { $0.texts(in: window).contains { $0.text.contains("Loading your account") } } ?? frames.count
        if let alone = frames[..<named].last(where: { $0.brightness(in: window) < 24 && $0.texts(in: window).isEmpty }) {
            let attachment = XCTAttachment(screenshot: alone)
            attachment.name = "splash, loader alone"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        // The home screen and the desktop behind the window vary; a launch screen or an undrawn window is one colour but
        // for the status bar's or the title bar's few glyphs.
        if let light = frames[..<named].first(where: { $0.brightness(in: window) > 100 && $0.contrast(in: window) < 24 }) {
            let attachment = XCTAttachment(screenshot: light)
            attachment.name = "launch, light screen"
            attachment.lifetime = .keepAlways
            add(attachment)
            fail("the launch shows a light screen before the black splash")
        }
        require("signIn.server.edit", timeout: 60, "the sign-in screen shows once the held start is done")
        if status.exists { fail("the status line stays on the sign-in screen") }
        shot("sign-in")
    }

    /// Launches the product on the fake sign-in server (`signInServer`) holding its options, while `ScreenSampler`
    /// takes the screen, then lets them come; fails with `what` where "Use your own server" stands anywhere but where it
    /// stays once they have come above it (a tap, click or press aimed at it while they load lands on it): on the screen
    /// taken while they are held, or in a frame sampled from the launch until they show. The screens while they load and
    /// once they came are attached, and the first frame with the link away from its place.
    func requireServerLineStaysPut(_ what: String) throws {
        // An app an earlier test left up goes first, so that every frame showing the screen is this launch's.
        app.terminate()
        try holdSignInOptions()
        let sampler = ScreenSampler()
        sampler.start(limit: 60)
        launch(["--dev-sign-in-server", try signInServer.absoluteString])
        let edit = require("signIn.server.edit", timeout: 30, "\(what): the sign-in screen shows")
        let options = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'signIn.option.'"))
        // The options are held, so the screen is the one while they load: they stay away through the launch's first
        // second.
        if !holds(for: 1, { !options.firstMatch.exists }) { fail("\(what): the sign-in options show while the server holds them") }
        let loading = XCUIScreen.main.screenshot()
        try releaseSignInOptions()
        let firstOption = options.firstMatch
        if !firstOption.exists(within: 30) { fail("\(what): the sign-in options don't show once the server answers") }
        // The sampler runs on until the screen with the options has settled: its frames reach past their coming.
        screenSettles()
        let frames = sampler.stop()
        let loaded = XCUIScreen.main.screenshot()
        let title = edit.label
        let option = firstOption.label
        // The column of the link and the first option down the whole screen, with room for Vision's reading of edges.
        let across = edit.frame.union(firstOption.frame)
        let column = CGRect(x: across.minX - 40, y: 0, width: across.width + 80, height: 10_000)
        func attach(_ frame: XCUIScreenshot, _ name: String) {
            let attachment = XCTAttachment(screenshot: frame)
            attachment.name = name
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        attach(loading, "sign-in while its options load")
        attach(loaded, "sign-in once its options came")
        // Where Vision reads the link on a screen, and whether the first option is drawn there too.
        func read(_ frame: XCUIScreenshot) -> (y: CGFloat, loaded: Bool)? {
            let lines = frame.texts(in: column)
            guard let line = lines.first(where: { $0.text.contains(title) }) else { return nil }
            return (line.frame.midY, lines.contains { $0.text.contains(option) })
        }
        guard let place = read(loaded), place.loaded else {
            fail("\(what): the screen once the options came doesn't show \(title) under \(option)")
            return
        }
        guard let load = read(loading), !load.loaded else {
            fail("\(what): the screen while the options load doesn't show \(title) without \(option)")
            return
        }
        // Vision reads a line drawn in one place to within a point or two.
        if abs(load.y - place.y) > 3 {
            fail("\(what): \(title) stands at y \(Int(load.y)) while the options load, at y \(Int(place.y)) once they came")
        }
        for (index, frame) in frames.enumerated() {
            guard let seen = read(frame), abs(seen.y - place.y) > 3 else { continue }
            attach(frame, "use your own server away from its place")
            fail("\(what): frame \(index + 1) of \(frames.count) shows \(title) at y \(Int(seen.y)), its place is y \(Int(place.y))")
            return
        }
    }

    /// Waits for Settings → Account's sync status (the page open) to read `status` (`SyncStatusText`); fails with
    /// `what` when it doesn't within `timeout`. The status's text carries its row's label on iOS and tvOS ("Sync, Up to
    /// date"). iPhone's sync line and the avatar's sync mark (`nav.sync`) read the same words and don't count.
    func requireAccountStatus(_ status: String, timeout: TimeInterval, _ what: String) {
        let row = app.staticTexts.matching(NSPredicate(
            format: "(label == %@ OR label ENDSWITH %@ OR value == %@) AND identifier != 'nav.sync'", status, ", " + status, status
        )).firstMatch
        if !row.exists(within: timeout) { fail("\(what): Settings → Account's status doesn't read \"\(status)\" after \(Int(timeout)) s") }
    }

    #if os(tvOS)
    /// "Use your own server" by the remote: its sheet, the server typed on the keyboard, and the check that closes the
    /// sheet, until the server's OpenID Connect provider (`signIn.option.oidc`) is offered.
    func enterOwnServer(_ server: String) {
        require("signIn.server.edit", timeout: 60, "the sign-in screen shows")
        shot("sign-in")
        walkFocus(to: "signIn.server.edit", "the remote reaches Use your own server")
        press(.select)
        require("signIn.server.field", "Use your own server opens the server sheet")
        walkFocus(to: "signIn.server.field", "the server sheet's field takes focus")
        press(.select)
        app.typeText(server + "\n")
        shot("server entered")
        // Return submits the address (the field's onSubmit); the keyboard can stay up after it.
        if !waitUntil(3, { !app.keyboards.firstMatch.exists }) { putKeyboardAway("the server typed") }
        let field = element("signIn.server.field")
        if !waitUntil(15, { !field.exists }) {
            // Still open: Check server submits what the field holds.
            let submit = element("signIn.server.submit")
            if submit.exists, submit.isEnabled {
                walkFocus(to: "signIn.server.submit", "the remote reaches Check server")
                press(.select)
            }
        }
        requireGone("signIn.server.field", timeout: 30, "the server check passes and the sheet closes")
        require("signIn.option.oidc", timeout: 60, "the server's sign-in options list its OpenID Connect provider")
        shot("server options")
    }

    /// Enters `pin` on the PIN pad by the remote: focus walks to each digit's key (`pin.digit.<n>`), and Select.
    func enterPin(_ pin: [Int]) {
        for digit in pin {
            walkFocus(to: "pin.digit.\(digit)", "the remote reaches \(digit) on the PIN pad")
            press(.select)
        }
    }

    /// Presses the arrow towards `id` from the element that has focus, frame to frame, until `id` has focus; fails with
    /// `what` when nothing has focus or `limit` presses don't reach it.
    func walkFocus(to id: String, limit: Int = 12, _ what: String) {
        let target = require(id, what)
        for _ in 0..<limit {
            if holdsFocus(id) { return }
            let here = focused
            if !here.exists(within: 5) {
                fail("\(what): nothing has focus")
                return
            }
            press(Self.direction(from: here.frame, to: target.frame))
        }
        if !waitUntil(2, { holdsFocus(id) }) {
            fail("\(what): \(limit) presses left focus on \(focusedId ?? "nothing"), not \(id)")
        }
    }

    /// The arrow from frame `here` towards frame `there` (the Android UI tests' `direction`, the same rule): sideways
    /// when they share a row, or share no column and lie further apart across than down; left when `there` is wholly
    /// to the left; else up or down.
    static func direction(from here: CGRect, to there: CGRect) -> XCUIRemote.Button {
        let columnsOverlap = there.minX < here.maxX && here.minX < there.maxX
        let rowsOverlap = there.minY < here.maxY && here.minY < there.maxY
        let across = there.midX - here.midX
        let down = there.midY - here.midY
        if rowsOverlap || (!columnsOverlap && abs(across) > abs(down)) { return across > 0 ? .right : .left }
        if !columnsOverlap && there.maxX <= here.minX { return .left }
        return down > 0 ? .down : .up
    }

    /// The account's one profile, Ana, once signed in: its tile in the picker, opened, or Home (the tab bar) at once.
    func openProfile(_ what: String) {
        let tile = element("profiles.tile.0")
        let tabBar = app.tabBars.firstMatch
        if !waitUntil(180, { tile.exists || tabBar.exists }) {
            fail("\(what): the app shows neither the profile picker nor Home")
        }
        guard tile.exists else { return }
        if tile.label != "Ana" { fail("\(what): the picker's first profile is \"\(tile.label)\", not Ana") }
        walkFocus(to: "profiles.tile.0", "the remote reaches Ana")
        press(.select)
        if !tabBar.exists(within: 60) { fail("\(what): Ana's profile doesn't open Home") }
    }
    #elseif os(iOS)
    /// "Use your own server" by touch: its sheet, the server typed into the field, and Check server, until the
    /// server's OpenID Connect provider (`signIn.option.oidc`) is offered.
    func enterOwnServer(_ server: String) {
        let edit = require("signIn.server.edit", timeout: 60, "the sign-in screen shows")
        shot("sign-in")
        ready(edit).tap()
        let field = requireTypingFocus("signIn.server.field", "Use your own server opens the server sheet")
        field.typeText(server)
        let submit = require("signIn.server.submit", "the server sheet has Check server")
        if !waitUntil(5, { submit.isEnabled }) { fail("Check server stays disabled with \(server) typed") }
        shot("server entered")
        ready(submit).tap()
        requireGone("signIn.server.field", timeout: 30, "the server check passes and the sheet closes")
        require("signIn.option.oidc", timeout: 60, "the server's sign-in options list its OpenID Connect provider")
        shot("server options")
    }
    #endif

    #if !os(tvOS)
    /// What the account tests' shots show: the app's window on the Mac (the screen holds the browser's window and others
    /// too), the screen on iOS.
    var shotSource: (any XCUIScreenshotProviding)? {
        #if os(macOS)
        app.windows.firstMatch
        #else
        nil
        #endif
    }

    /// The sign-in screen with the dev stack's options (its Dex, `signIn.option.oidc`): `server` typed into "Use your
    /// own server" where the screen shows another server's. `what` is the step that should have led there.
    func showStackOptions(_ server: String, _ what: String) {
        if !waitUntil(90, { element("signIn.option.oidc").exists || element("signIn.server.edit").exists }) {
            fail("\(what): no sign-in screen after 90 s")
        }
        if element("signIn.option.oidc").exists(within: 10) { return }
        activate(element("signIn.server.edit"))
        let field = requireTypingFocus("signIn.server.field", "Use your own server opens the server sheet")
        #if os(macOS)
        // The field keeps the last server typed there.
        field.typeKey("a", modifierFlags: .command)
        #endif
        field.typeText(server)
        let submit = require("signIn.server.submit", "the server sheet has Check server")
        if !waitUntil(5, { submit.isEnabled }) { fail("Check server stays disabled with \(server) typed") }
        activate(submit)
        requireGone("signIn.server.field", timeout: 30, "the server check passes and the sheet closes")
        require("signIn.option.oidc", timeout: 60, "the dev stack's sign-in options list its OpenID Connect provider")
    }

    /// The account's one profile, Ana, once signed in: its tile in the picker, opened, or Home at once.
    func openProfile(_ what: String) {
        let tile = element("profiles.tile.0")
        if !waitUntil(180, { tile.exists || element("nav.settings").exists || app.tabBars.firstMatch.exists }) {
            fail("\(what): the app shows neither the profile picker nor Home")
        }
        guard tile.exists else { return }
        if tile.label != "Ana" { fail("\(what): the picker's first profile is \"\(tile.label)\", not Ana") }
        activate(tile)
    }

    /// Scrolls the page until `id` is inside the window (`scrollTo`), then taps or clicks it: Account's Sign out and
    /// Delete account end a long form, whose rows below the screen come as it scrolls (iOS: the wait for `id` is 5 s
    /// before the page scrolls), and on the Mac a click lands in the element's middle, which can lie past the window's
    /// bottom edge while the element still counts as hittable.
    func activateShown(_ id: String, timeout: TimeInterval = 30, _ what: String, file: StaticString = #filePath, line: UInt = #line) {
        let target = element(id)
        #if os(macOS)
        _ = target.exists(within: timeout)
        #else
        _ = target.exists(within: 5)
        #endif
        activate(scrollTo(target, what, file: file, line: line))
    }

    /// Enters `pin` on the PIN pad by touch or the pointer: a tap or a click on each digit's key (`pin.digit.<n>`).
    func enterPin(_ pin: [Int]) {
        for digit in pin {
            activate(require("pin.digit.\(digit)", "the PIN pad has \(digit)"))
        }
    }

    /// A tap on iOS, a click on the Mac.
    func activate(_ element: XCUIElement) {
        #if os(macOS)
        ready(element).click()
        #else
        ready(element).tap()
        #endif
    }

    /// Lets the web authentication session the app just opened (`AppleSignInPlatform.openBrowser`) reach its page: the
    /// session first asks whether Tortuga may use the site to sign in ("“Tortuga” Wants to Use “127.0.0.1” to Sign
    /// In"), in an alert of another process, answered with Continue: SpringBoard's on iOS, UserNotificationCenter's on
    /// the Mac (macOS 27, whatever the default browser; the alert outlives the app, so a test that fails before
    /// answering cancels it). The dev stack's fakes then approve at once, and the server's return page redirects the
    /// session back to the app (`tortuga://signin`, with the state and a one-time proof), which ends it. Take no
    /// screenshot meanwhile: the browser's address bar shows the return page's proof.
    func continueToWebSignIn(_ what: String) {
        #if os(iOS)
        let asker = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        #else
        let asker = XCUIApplication(bundleIdentifier: "com.apple.UserNotificationCenter")
        addTeardownBlock {
            let cancel = asker.buttons["Cancel"]
            if cancel.exists { cancel.click() }
        }
        #endif
        let continueButton = asker.buttons["Continue"]
        if !continueButton.exists(within: 30) {
            fail("\(what): the system doesn't ask whether Tortuga may use the site to sign in within 30 s")
        }
        #if os(macOS)
        ready(continueButton, ofAnotherApp: true).click()
        #else
        ready(continueButton, ofAnotherApp: true).tap()
        #endif
    }

    /// The Mac's browser can stay in front once the web sign-in is done (the session's end closed nothing
    /// there): the app comes back to the front, as a person switches back, so the next clicks land on it. iOS's sheet
    /// closes with the session.
    func returnFromBrowser() {
        #if os(macOS)
        app.activate()
        #endif
    }
    #endif
}
