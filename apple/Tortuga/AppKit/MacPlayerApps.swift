import AppKit
import OSLog
import TortugaKit

/// The Mac's player apps (`ApplePlayerApps`): IINA, VLC, mpv and Infuse, found by their bundle identifiers (mpv also
/// as Homebrew's program), and custom players as programs. The app isn't sandboxed (Tortuga-macOS.entitlements), so
/// it runs the players' own executables:
/// - mpv (`Contents/MacOS/mpv`, or Homebrew's) with `PlayerAppCommands.mpv` and its JSON IPC on a Unix socket in a
///   private temporary folder, read into `MpvIpcSession` (`MpvIpcFollower`).
/// - IINA through its `iina-cli` with `PlayerAppCommands.iina`: IINA hands every `--mpv-` option to its mpv, the IPC
///   socket included, so it reports as mpv does.
/// - VLC (`Contents/MacOS/VLC`) with `PlayerAppCommands.vlc` (without `--no-one-instance`, an option the Mac's VLC
///   doesn't have): its HTTP interface on 127.0.0.1, a free port and a new password each time, polled once a second
///   into `VlcHttpSession` (`VlcHttpFollower`).
/// - Infuse through its URL scheme (`SchemePlayer.infuse`), or a download's file opened with it; nothing comes back.
/// - A custom program with `PlayerAppTemplates.arguments`; nothing comes back.
///
/// The players' output goes nowhere, and nothing here logs a URL or a command line: both carry the stream's keys.
@MainActor
enum MacPlayerApps {
    /// The known players on this Mac, in the order Settings lists them.
    static func detect() -> [PlayerApp] {
        MacPlayer.allCases.filter { $0.location() != nil }.map(\.app)
    }

    static func launch(app: PlayerApp, media: PlayerAppMedia, events: any PlayerAppEvents) async -> any PlayerAppLaunch {
        if let custom = app.custom {
            guard let program = program(named: custom.command) else { return PlayerAppLaunchFailed(failure: .notInstalled) }
            let arguments = PlayerAppTemplates.shared.arguments(template: custom.arguments, media: media)
            return run(app, PlayerProcess(executable: program, arguments: arguments), progress: .none) { _ in }
        }
        guard let player = MacPlayer.allCases.first(where: { $0.id == app.id }), let location = player.location() else {
            return PlayerAppLaunchFailed(failure: .notInstalled)
        }
        switch player {
        case .iina, .mpv:
            guard let directory = privateDirectory() else { return PlayerAppLaunchFailed(failure: .other) }
            let socket = directory.appending(path: "mpv.sock").path
            let arguments = player == .iina
                ? PlayerAppCommands.shared.iina(media: media, ipcPath: socket)
                : PlayerAppCommands.shared.mpv(media: media, ipcPath: socket)
            let process = PlayerProcess(executable: location, arguments: arguments, directory: directory)
            let follower = MpvIpcFollower(socketPath: socket, directory: directory, events: events)
            let launch = run(app, process, progress: .live) { follower.processExited(failed: $0) }
            if launch is PlayerAppLaunchLaunched {
                follower.start { process.isRunning }
            } else {
                try? FileManager.default.removeItem(at: directory)
            }
            return launch
        case .vlc:
            guard let port = freeLoopbackPort() else { return PlayerAppLaunchFailed(failure: .other) }
            let password = UUID().uuidString
            let arguments = PlayerAppCommands.shared.vlc(
                media: media,
                httpPort: KotlinInt(int: Int32(port)),
                httpPassword: password,
                oneInstanceOption: false
            )
            let follower = VlcHttpFollower(port: port, password: password, events: events)
            let launch = run(app, PlayerProcess(executable: location, arguments: arguments), progress: .live) {
                follower.processExited(failed: $0)
            }
            if launch is PlayerAppLaunchLaunched { follower.start() }
            return launch
        case .infuse:
            // A download is a file Infuse opens as any app does; a stream goes through its scheme.
            let url = media.local ? URL(filePath: media.url) : SchemePlayer.infuse.url(for: media)
            guard let url else { return PlayerAppLaunchFailed(failure: .other) }
            do {
                try await NSWorkspace.shared.open([url], withApplicationAt: location, configuration: NSWorkspace.OpenConfiguration())
                return PlayerAppLaunchLaunched(progress: .none)
            } catch {
                log.notice("infuse didn't open: \((error as NSError).domain, privacy: .public) \((error as NSError).code)")
                return PlayerAppLaunchFailed(failure: .other)
            }
        }
    }

    /// Starts `process`; `exited` runs once it ends, on a background queue, with whether it failed.
    private static func run(
        _ app: PlayerApp,
        _ process: PlayerProcess,
        progress: PlayerAppProgress,
        exited: @escaping @Sendable (_ failed: Bool) -> Void
    ) -> any PlayerAppLaunch {
        do {
            try process.run(exited: exited)
            return PlayerAppLaunchLaunched(progress: progress)
        } catch {
            log.notice("\(app.id, privacy: .public) didn't start: \((error as NSError).domain, privacy: .public) \((error as NSError).code)")
            return PlayerAppLaunchFailed(failure: .other)
        }
    }

    /// A custom player's program: a path (`~` for the home folder), or a name looked up on `PATH` and in Homebrew's
    /// folders, which an app's `PATH` leaves out.
    private static func program(named command: String) -> URL? {
        let command = (command.trimmingCharacters(in: .whitespaces) as NSString).expandingTildeInPath
        guard !command.isEmpty else { return nil }
        if command.contains("/") {
            return FileManager.default.isExecutableFile(atPath: command) ? URL(filePath: command) : nil
        }
        let path = ProcessInfo.processInfo.environment["PATH"] ?? "/usr/bin:/bin:/usr/sbin:/sbin"
        let folders = path.split(separator: ":").map(String.init) + MacPlayer.homebrewFolders
        return folders.lazy
            .map { URL(filePath: $0).appending(path: command) }
            .first { FileManager.default.isExecutableFile(atPath: $0.path) }
    }

    /// A new folder only this user can read (`mkdtemp`), for one mpv's IPC socket; short enough for a socket's path.
    private static func privateDirectory() -> URL? {
        var template = Array((NSTemporaryDirectory() as NSString).appendingPathComponent("tortuga-player.XXXXXX").utf8CString)
        let path = template.withUnsafeMutableBufferPointer { buffer in mkdtemp(buffer.baseAddress).map { String(cString: $0) } }
        return path.map { URL(filePath: $0, directoryHint: .isDirectory) }
    }

    /// A TCP port on 127.0.0.1 that nothing listens on now, for VLC's HTTP interface.
    private static func freeLoopbackPort() -> Int? {
        let socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socket >= 0 else { return nil }
        defer { close(socket) }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        address.sin_port = 0
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(socket, $0, length) == 0 && getsockname(socket, $0, &length) == 0
            }
        }
        return bound ? Int(UInt16(bigEndian: address.sin_port)) : nil
    }

    private static let log = Logger(subsystem: "com.edde746.tortuga", category: "player-apps")
}

/// A player Tortuga knows on the Mac.
private enum MacPlayer: CaseIterable {
    case iina, vlc, mpv, infuse

    /// Where Homebrew installs programs: Apple silicon's prefix, then Intel's.
    static let homebrewFolders = ["/opt/homebrew/bin", "/usr/local/bin"]

    var id: String {
        switch self {
        case .iina: "iina"
        case .vlc: "vlc"
        case .mpv: "mpv"
        case .infuse: "infuse"
        }
    }

    var app: PlayerApp {
        switch self {
        case .iina: PlayerApp(id: id, name: "IINA", kind: .detected, capabilities: Self.liveCapabilities, custom: nil)
        case .vlc: PlayerApp(id: id, name: "VLC", kind: .detected, capabilities: Self.liveCapabilities, custom: nil)
        case .mpv: PlayerApp(id: id, name: "mpv", kind: .detected, capabilities: Self.liveCapabilities, custom: nil)
        case .infuse: SchemePlayer.infuse.app
        }
    }

    /// IINA, VLC and mpv (`PlayerAppCommands.iina`, `.vlc`, `.mpv`): the start and the subtitles, reporting live.
    private static var liveCapabilities: PlayerAppCapabilities {
        PlayerAppCapabilities(startPosition: true, subtitles: true, progress: .live)
    }

    /// What runs: the executable for IINA (its command-line tool), VLC and mpv, Infuse's app; nil when it isn't here.
    func location() -> URL? {
        let workspace = NSWorkspace.shared
        switch self {
        case .iina:
            return workspace.urlForApplication(withBundleIdentifier: "com.colliderli.iina")
                .flatMap { Self.executable($0.appending(path: "Contents/MacOS/iina-cli")) }
        case .vlc:
            return workspace.urlForApplication(withBundleIdentifier: "org.videolan.vlc")
                .flatMap { Self.executable($0.appending(path: "Contents/MacOS/VLC")) }
        case .mpv:
            // mpv's app bundle (its release, or Homebrew's cask), else Homebrew's program.
            let bundled = workspace.urlForApplication(withBundleIdentifier: "io.mpv")
                .flatMap { Self.executable($0.appending(path: "Contents/MacOS/mpv")) }
            return bundled ?? Self.homebrewFolders.lazy.compactMap { Self.executable(URL(filePath: $0).appending(path: "mpv")) }.first
        case .infuse:
            return workspace.urlForApplication(withBundleIdentifier: "com.firecore.infuse")
        }
    }

    private static func executable(_ url: URL) -> URL? {
        FileManager.default.isExecutableFile(atPath: url.path) ? url : nil
    }
}
