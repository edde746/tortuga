import Foundation
import TortugaKit

/// A player app's process (`MacPlayerApps`). Its input and output go nowhere: a player may print the URL. The process
/// keeps this object until it exits, so it is reaped and `exited` runs, once, on a background queue, with whether the
/// process failed: a failing exit status, or the signal that ended it (a crash, or a broken install dying as it loads).
final class PlayerProcess: @unchecked Sendable {
    private let process = Process()

    init(executable: URL, arguments: [String], directory: URL? = nil) {
        process.executableURL = executable
        process.arguments = arguments
        if let directory { process.currentDirectoryURL = directory }
        process.standardInput = FileHandle.nullDevice
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
    }

    var isRunning: Bool { process.isRunning }

    func run(exited: @escaping @Sendable (_ failed: Bool) -> Void) throws {
        process.terminationHandler = { [self] ended in
            process.terminationHandler = nil
            exited(ended.terminationStatus != 0)
        }
        do {
            try process.run()
        } catch {
            process.terminationHandler = nil
            throw error
        }
    }
}

/// Follows one mpv, or IINA's, through its JSON IPC (`MpvIpcSession`): connects to the Unix socket mpv creates once it
/// runs, writes the session's commands and passes every line it reads to the session, and the events on. The session's
/// `Ended` follows once the socket closed and the process exited (saying whether it failed), and the socket's private
/// folder goes. One thread reads, so the session has one reader.
final class MpvIpcFollower: @unchecked Sendable {
    private let session = MpvIpcSession()
    private let socketPath: String
    private let directory: URL
    private let events: any PlayerAppEvents
    private let lock = NSLock()
    /// What has yet to end before `Ended`: the process and the reading.
    private var pending = 2
    /// Whether the process failed (`processExited`).
    private var failed = false

    init(socketPath: String, directory: URL, events: any PlayerAppEvents) {
        self.socketPath = socketPath
        self.directory = directory
        self.events = events
    }

    /// Starts reading once the process runs; `running` says whether it still does.
    func start(running: @escaping @Sendable () -> Bool) {
        let thread = Thread { [self] in
            read(running: running)
            finished()
        }
        thread.name = "Player app mpv IPC"
        thread.start()
    }

    func processExited(failed: Bool) {
        lock.lock()
        self.failed = failed
        lock.unlock()
        finished()
    }

    private func finished() {
        lock.lock()
        pending -= 1
        let last = pending == 0
        let failed = failed
        lock.unlock()
        guard last else { return }
        events.onEvent(event: session.ended(failed: failed))
        try? FileManager.default.removeItem(at: directory)
    }

    private func read(running: () -> Bool) {
        guard let socket = connect(running: running) else { return }
        defer { close(socket) }
        for command in session.commands {
            guard send(socket, command + "\n") else { return }
        }
        var buffer: [UInt8] = []
        var chunk = [UInt8](repeating: 0, count: 16 * 1024)
        while true {
            let count = Darwin.read(socket, &chunk, chunk.count)
            if count < 0, errno == EINTR { continue }
            guard count > 0 else { return }
            buffer.append(contentsOf: chunk[..<count])
            var start = 0
            while let newline = buffer[start...].firstIndex(of: UInt8(ascii: "\n")) {
                let line = String(decoding: buffer[start..<newline], as: UTF8.self)
                start = newline + 1
                for event in session.onLine(line: line) { events.onEvent(event: event) }
            }
            buffer.removeFirst(start)
        }
    }

    /// The socket once mpv listens on it, trying every 100 ms while the process runs; nil when it never did.
    private func connect(running: () -> Bool) -> Int32? {
        var address = sockaddr_un()
        let path = Array(socketPath.utf8CString)
        guard path.count <= MemoryLayout.size(ofValue: address.sun_path) else { return nil }
        address.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
        address.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &address.sun_path) { $0.copyBytes(from: path.map { UInt8(bitPattern: $0) }) }
        while running() {
            let socket = Darwin.socket(AF_UNIX, SOCK_STREAM, 0)
            guard socket >= 0 else { return nil }
            var on: Int32 = 1
            setsockopt(socket, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
            let connected = withUnsafePointer(to: &address) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    Darwin.connect(socket, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) == 0
                }
            }
            if connected { return socket }
            close(socket)
            Thread.sleep(forTimeInterval: 0.1)
        }
        return nil
    }

    private func send(_ socket: Int32, _ text: String) -> Bool {
        let bytes = Array(text.utf8)
        var sent = 0
        while sent < bytes.count {
            let count = bytes[sent...].withUnsafeBytes { write(socket, $0.baseAddress, $0.count) }
            if count < 0, errno == EINTR { continue }
            guard count > 0 else { return false }
            sent += count
        }
        return true
    }
}

/// Follows one VLC through its HTTP interface (`VlcHttpSession`): asks for `status.json` once a second, one request
/// at a time, and passes each answer to the session and its events on; once the process exited, the session's
/// `Ended`, saying whether it failed. The session is only touched on this follower's queue.
final class VlcHttpFollower: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.edde746.tortuga.player-apps.vlc")
    private let session = VlcHttpSession()
    private let events: any PlayerAppEvents
    private let request: URLRequest
    private let client: URLSession
    private var timer: (any DispatchSourceTimer)?
    private var asking = false
    private var ended = false

    init(port: Int, password: String, events: any PlayerAppEvents) {
        self.events = events
        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)\(VlcHttpSession.companion.STATUS_PATH)")!)
        request.setValue(VlcHttpSession.companion.authorization(password: password), forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 2
        self.request = request
        client = URLSession(configuration: .ephemeral)
    }

    /// Starts asking once the process runs.
    func start() {
        queue.async { [self] in
            guard !ended else { return }
            let timer = DispatchSource.makeTimerSource(queue: queue)
            timer.schedule(deadline: .now() + 1, repeating: 1)
            timer.setEventHandler { [weak self] in self?.ask() }
            self.timer = timer
            timer.resume()
        }
    }

    func processExited(failed: Bool) {
        queue.async { [self] in
            guard !ended else { return }
            ended = true
            timer?.cancel()
            timer = nil
            client.invalidateAndCancel()
            events.onEvent(event: session.ended(failed: failed))
        }
    }

    private func ask() {
        guard !ended, !asking else { return }
        asking = true
        client.dataTask(with: request) { [self] data, response, _ in
            queue.async { [self] in
                asking = false
                // Before VLC's interface listens, and after it stopped, the request fails: nothing to read.
                guard !ended, let data, (response as? HTTPURLResponse)?.statusCode == 200 else { return }
                for event in session.onStatus(json: String(decoding: data, as: UTF8.self)) { events.onEvent(event: event) }
            }
        }.resume()
    }
}
