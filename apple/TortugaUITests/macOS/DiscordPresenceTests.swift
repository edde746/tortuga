import AppKit
import XCTest

/// Discord's Rich Presence on the Mac, against a fake of the Discord app (`FakeDiscord`) on a Unix socket in a folder of
/// the test's own, which the app takes for the user's temporary folder (`TMPDIR`, where the Mac's Discord listens):
/// - As a test Discord application (`--dev-discord`), Discord's switch on (`--dev-setting discord.presence=true`), the
///   test addon's H.264 stream of The Shawshank Redemption plays before Discord runs. Once the fake listens, the app
///   connects (it tries again every 15 s), shakes hands (protocol 1, the application's id), reads READY split over two
///   writes with a PING right behind it, answers the PING with a PONG of the same payload, and shows the movie:
///   SET_ACTIVITY for its own process, the activity's first line the movie's title. Space pausing takes the activity
///   down (SET_ACTIVITY without one) and disconnects; Space again connects and shows it again; Discord dropping the
///   connection, the app connects again and shows it anew; Escape closing the player takes it down and disconnects.
/// - As a test Discord application, Settings → This device lists Discord's switch, off until a click switches it on
///   (shots "discord switch" and "discord switch row").
/// - Without `--dev-discord`, a dev session has no Discord application, whatever the build's (Tortuga's by default): the
///   movie plays with the switch's setting on and Discord listening from the start, and the app never connects; This
///   device lists no Discord switch (shot "no discord switch").
final class DiscordPresenceTests: TortugaUITestCase {
    /// The movie's title, the activity's first line.
    private static let title = "The Shawshank Redemption"
    /// A test Discord application (`--dev-discord`): the fake takes any.
    private static let application = "424242424242424242"
    /// How many of the fake's events the test has gone past (`next`).
    private var taken = 0

    func testPresenceFollowsThePlayerAndDiscord() throws {
        let discord = try FakeDiscord()
        addTeardownBlock { discord.stop() }
        app.launchEnvironment["TMPDIR"] = discord.folder
        try launchDev(
            addon: true, open: ["streams:movie:tt0111161:tt0111161"], settings: ["discord.presence=true"],
            arguments: ["--dev-discord", Self.application]
        )
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).click()
        require("player.video", "a click on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        let running = NSRunningApplication.runningApplications(withBundleIdentifier: Self.bundleIdentifier).first { !$0.isTerminated }
        guard let pid = running?.processIdentifier else { return fail("the app under test isn't running") }

        try discord.listen()
        requireShown(discord, pid, "Discord started while the movie plays")

        app.typeKey(" ", modifierFlags: [])
        if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Play" }) {
            fail("Space doesn't pause: play/pause reads \(element("player.playPause").label)")
        }
        requireTakenDown(discord, pid, "Space paused the movie")

        app.typeKey(" ", modifierFlags: [])
        requireShown(discord, pid, "Space played the movie on")

        discord.drop()
        requireShown(discord, pid, "Discord dropped the connection")

        for _ in 0..<3 where element("player.video").exists {
            app.typeKey(.escape, modifierFlags: [])
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Escape closes the player")
        requireTakenDown(discord, pid, "the player closed")
    }

    func testThisDeviceOffersTheDiscordSwitchOffUntilSwitchedOn() throws {
        try launchDev(arguments: ["--dev-discord", Self.application])
        openSection("device")
        let toggle = require("settings.device.discordPresence", "This device lists Discord's switch")
        if switchValue(toggle) != "0" { fail("Discord's switch reads \(switchValue(toggle)) before it was switched on") }
        shot("discord switch")
        shot("discord switch row", of: toggle)
        flipSwitch(toggle)
        if !waitUntil(5, { switchValue(toggle) == "1" }) { fail("a click doesn't switch Discord on: it reads \(switchValue(toggle))") }
    }

    func testWithoutADiscordApplicationNothingConnects() throws {
        let discord = try FakeDiscord()
        addTeardownBlock { discord.stop() }
        try discord.listen()
        app.launchEnvironment["TMPDIR"] = discord.folder
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"], settings: ["discord.presence=true"])
        ready(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream")).click()
        require("player.video", "a click on the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        // With an application the app connects as the movie starts: the fake was listening before the launch.
        if waitUntil(5, { !discord.events.isEmpty }) {
            fail("without a Discord application the app connected to Discord: \(discord.events.map(\.description))")
        }
    }

    func testWithoutADiscordApplicationThisDeviceOffersNoSwitch() throws {
        try launchDev()
        openSection("device")
        require("settings.device.hardwareDecoding", "This device lists its rows")
        if element("settings.device.discordPresence").exists {
            fail("without a Discord application This device lists Discord's switch")
        }
        shot("no discord switch")
    }

    /// Discord showing the movie from a new connection: the app connects, shakes hands (protocol 1 and the test
    /// application's id), answers the fake's PING with a PONG of its payload, and its first SET_ACTIVITY shows the
    /// movie's title for the app's process `pid`. Fails with `what`.
    private func requireShown(_ discord: FakeDiscord, _ pid: pid_t, _ what: String) {
        next(discord, "\(what): the app doesn't connect to Discord") { $0 == .connected }
        let handshake = next(discord, "\(what): the app doesn't shake hands") { $0.op == 0 }?.json ?? [:]
        if handshake["v"] as? Int != 1 || handshake["client_id"] as? String != Self.application {
            fail("\(what): the handshake isn't protocol 1 with the application's id, \(Self.application): \(handshake)")
        }
        next(discord, "\(what): the app doesn't answer Discord's PING with a PONG of its payload") {
            $0 == .frame(op: 4, payload: FakeDiscord.ping)
        }
        let shown = next(discord, "\(what): the app doesn't show the movie on Discord") { $0.command == "SET_ACTIVITY" }
        let details = shown?.activity?["details"] as? String
        if details != Self.title || shown?.pid != Int(pid) {
            fail("\(what): Discord was sent \(shown?.description ?? "nothing"); expected '\(Self.title)' for the app's process, \(pid)")
        }
    }

    /// Discord's activity taken down: a SET_ACTIVITY without one for the app's process `pid`, then the app disconnects.
    /// Fails with `what`.
    private func requireTakenDown(_ discord: FakeDiscord, _ pid: pid_t, _ what: String) {
        let cleared = next(discord, "\(what): the app doesn't take the activity down") {
            $0.command == "SET_ACTIVITY" && $0.activity == nil
        }
        if cleared?.pid != Int(pid) { fail("\(what): the activity was taken down for process \(cleared?.pid ?? 0), not the app's, \(pid)") }
        next(discord, "\(what): the app stays connected to Discord") { $0 == .closed }
    }

    /// The first of the fake's events past those taken that `matches`, waiting up to 25 s for it (Discord takes at most
    /// 5 updates per 20 s, so one may wait for its turn); the events up to it are taken. Fails with `what` and the
    /// events that came instead when none does.
    @discardableResult
    private func next(_ discord: FakeDiscord, _ what: String, _ matches: (FakeDiscord.Event) -> Bool) -> FakeDiscord.Event? {
        var found: (index: Int, event: FakeDiscord.Event)?
        _ = waitUntil(25) {
            found = discord.events.enumerated().dropFirst(taken).first { matches($0.element) }.map { (index: $0.offset, event: $0.element) }
            return found != nil
        }
        guard let found else {
            fail("\(what) within 25 s; Discord got \(discord.events.dropFirst(taken).map(\.description))")
            return nil
        }
        taken = found.index + 1
        return found.event
    }
}

/// A fake of the Discord app's local RPC: frames of a little-endian 32-bit opcode and length, then that many bytes of
/// UTF-8 JSON, on the Unix socket `discord-ipc-0` in a new folder under `/private/tmp/tortuga-discord`, the one place
/// outside its container the runner's sandbox lets it make a socket (Tortuga-macOS-UITests.entitlements); the app
/// can't reach into the runner's container. One thread serves one connection at a time, as the app holds one: it
/// answers the handshake with READY in two writes, the second with a PING right behind it, and each command with an
/// answer carrying its nonce, and notes what the app does (`events`).
final class FakeDiscord: @unchecked Sendable {
    /// What happened on the socket, in order.
    enum Event: Equatable, CustomStringConvertible {
        /// The app connected.
        case connected
        /// A frame from the app: its opcode (0 handshake, 1 command, 2 close, 3 ping, 4 pong) and its JSON.
        case frame(op: UInt32, payload: String)
        /// The app closed its end.
        case closed

        var op: UInt32? {
            guard case .frame(let op, _) = self else { return nil }
            return op
        }

        var json: [String: Any]? {
            guard case .frame(_, let payload) = self else { return nil }
            return (try? JSONSerialization.jsonObject(with: Data(payload.utf8))) as? [String: Any]
        }

        /// A command's name (`SET_ACTIVITY`).
        var command: String? { op == 1 ? json?["cmd"] as? String : nil }

        /// A SET_ACTIVITY's activity; nil when it takes the activity down.
        var activity: [String: Any]? { (json?["args"] as? [String: Any])?["activity"] as? [String: Any] }

        /// The process a SET_ACTIVITY is for.
        var pid: Int? { (json?["args"] as? [String: Any])?["pid"] as? Int }

        var description: String {
            switch self {
            case .connected: "connected"
            case .frame(let op, let payload): "op \(op) \(payload)"
            case .closed: "closed"
            }
        }
    }

    /// The PING the fake sends right behind READY.
    static let ping = #"{"ping":"tortuga"}"#
    private static let ready = #"{"cmd":"DISPATCH","evt":"READY","data":{"v":1,"user":{"id":"1","username":"tortuga"}},"nonce":null}"#
    private static let root = "/private/tmp/tortuga-discord"

    /// The folder the socket is in, for the app's `TMPDIR`.
    let folder: String
    private let lock = NSLock()
    private var recorded: [Event] = []
    private var dropping = false
    private var stopping = false

    init() throws {
        guard mkdir(Self.root, 0o700) == 0 || errno == EEXIST else { throw HarnessError("mkdir \(Self.root): \(Self.lastError)") }
        var template = Array("\(Self.root)/XXXXXX".utf8CString)
        guard let made = template.withUnsafeMutableBufferPointer({ mkdtemp($0.baseAddress).map { String(cString: $0) } }) else {
            throw HarnessError("mkdtemp in \(Self.root): \(Self.lastError)")
        }
        folder = made
    }

    /// What happened so far.
    var events: [Event] { lock.withLock { recorded } }

    /// Starts listening, as Discord does once it runs.
    func listen() throws {
        let path = folder + "/discord-ipc-0"
        var address = sockaddr_un()
        let bytes = Array(path.utf8CString)
        guard bytes.count <= MemoryLayout.size(ofValue: address.sun_path) else { throw HarnessError("\(path) is too long for a socket") }
        address.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
        address.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &address.sun_path) { $0.copyBytes(from: bytes.map { UInt8(bitPattern: $0) }) }
        let server = Darwin.socket(AF_UNIX, SOCK_STREAM, 0)
        guard server >= 0 else { throw HarnessError("socket: \(Self.lastError)") }
        let bound = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(server, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
        }
        guard bound == 0, Darwin.listen(server, 4) == 0 else {
            let error = Self.lastError
            Darwin.close(server)
            throw HarnessError("the fake Discord can't listen on \(path): \(error)")
        }
        let thread = Thread { [self] in serve(server) }
        thread.name = "Fake Discord"
        thread.start()
    }

    /// Drops the app's connection, as Discord quitting does.
    func drop() {
        lock.withLock { dropping = true }
    }

    /// Stops serving and removes the folder.
    func stop() {
        lock.withLock { stopping = true }
        try? FileManager.default.removeItem(atPath: folder)
    }

    private func serve(_ server: Int32) {
        var client: Int32 = -1
        var reader = FrameReader()
        var chunk = [UInt8](repeating: 0, count: 64 * 1024)
        while !lock.withLock({ stopping }) {
            if client >= 0, lock.withLock({ defer { dropping = false }; return dropping }) {
                Darwin.close(client)
                client = -1
                continue
            }
            var watched = pollfd(fd: client >= 0 ? client : server, events: Int16(POLLIN), revents: 0)
            guard poll(&watched, 1, 100) > 0 else { continue }
            if client < 0 {
                client = accept(server, nil, nil)
                guard client >= 0 else { continue }
                var on: Int32 = 1
                setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
                reader = FrameReader()
                note(.connected)
                continue
            }
            let count = read(client, &chunk, chunk.count)
            if count < 0, errno == EINTR { continue }
            guard count > 0 else {
                note(.closed)
                Darwin.close(client)
                client = -1
                continue
            }
            for (op, payload) in reader.add(chunk[..<count]) {
                note(.frame(op: op, payload: payload))
                answer(op, payload, on: client)
            }
        }
        if client >= 0 { Darwin.close(client) }
        Darwin.close(server)
    }

    private func note(_ event: Event) {
        lock.withLock { recorded.append(event) }
    }

    /// Discord's answer: to the handshake READY, its first 12 bytes alone, then the rest with a PING behind it in one
    /// write, as a socket may deliver them; to a command an answer with its nonce.
    private func answer(_ op: UInt32, _ payload: String, on client: Int32) {
        switch op {
        case 0:
            let ready = Self.frame(1, Self.ready)
            send(Array(ready[..<12]), to: client)
            Thread.sleep(forTimeInterval: 0.2)
            send(Array(ready[12...]) + Self.frame(3, Self.ping), to: client)
        case 1:
            let nonce = ((try? JSONSerialization.jsonObject(with: Data(payload.utf8))) as? [String: Any])?["nonce"] as? String ?? ""
            send(Self.frame(1, #"{"cmd":"SET_ACTIVITY","data":null,"evt":null,"nonce":"\#(nonce)"}"#), to: client)
        default:
            break
        }
    }

    /// Writes `bytes`; an app gone meanwhile shows as `closed` at the next read.
    private func send(_ bytes: [UInt8], to client: Int32) {
        var sent = 0
        while sent < bytes.count {
            let count = bytes[sent...].withUnsafeBytes { write(client, $0.baseAddress, $0.count) }
            if count < 0, errno == EINTR { continue }
            guard count > 0 else { return }
            sent += count
        }
    }

    /// The frame of `op` and `json`.
    private static func frame(_ op: UInt32, _ json: String) -> [UInt8] {
        let payload = Array(json.utf8)
        var bytes = [UInt8]()
        for value in [op, UInt32(payload.count)] {
            withUnsafeBytes(of: value.littleEndian) { bytes.append(contentsOf: $0) }
        }
        return bytes + payload
    }

    private static var lastError: String { String(cString: strerror(errno)) }

    /// The app's frames as they arrive, a frame split over reads or several in one.
    private struct FrameReader {
        private var pending: [UInt8] = []

        mutating func add(_ bytes: ArraySlice<UInt8>) -> [(op: UInt32, payload: String)] {
            pending.append(contentsOf: bytes)
            var frames: [(op: UInt32, payload: String)] = []
            while pending.count >= 8 {
                let length = Int(number(at: 4))
                guard pending.count >= 8 + length else { break }
                frames.append((number(at: 0), String(decoding: pending[8..<(8 + length)], as: UTF8.self)))
                pending.removeFirst(8 + length)
            }
            return frames
        }

        /// The little-endian 32-bit number at `index`.
        private func number(at index: Int) -> UInt32 {
            pending[index..<(index + 4)].reversed().reduce(0) { $0 << 8 | UInt32($1) }
        }
    }
}
