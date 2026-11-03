#if os(macOS)
import AppKit
#endif
import XCTest

/// Crash reports against the fake Sentry (tools/media/fake_sentry.py, its URL passed as
/// `TEST_RUNNER_TORTUGA_SENTRY`): a dev session sends to it (`--dev-crash-reports <its DSN>`) while
/// This device's "Send crash reports" is on. Switched off, a Kotlin crash (the dev link `tortuga://dev-crash`, the dev
/// module's crash probe) leaves nothing to send, at the crash or at the next launch; switched on again, the next crash
/// reaches the fake as one event at the following launch: the Kotlin exception's class and message, unhandled, its frames
/// in the app's binary (where Kotlin's code is linked), and nothing the scrub takes out (the addon URL's path and its
/// API key, a user, the device's id and name).
final class CrashReportTests: TortugaUITestCase {
    /// The crash probe's made-up key, in the path of the addon URL its message carries.
    private static let apiKey = "TORTUGACRASHPROBEABCDEFGHIJKLMNOPQRSTUVWXYZ234567ABC"
    /// What the scrub must take out of every event.
    private static let secrets = [apiKey, "apikey="]
    private static let kotlinException = "com.edde746.tortuga.app.DevCrashException"
    /// How XCTest reports the app's crash: "<bundle id> crashed", or "… crashed in <symbol>".
    nonisolated private static let appCrashed = "com.edde746.tortuga crashed"

    /// The crashes this test caused on purpose, and how many of them XCTest has reported, which it does once the crash
    /// report reaches it, seconds later, at a call that runs the main run loop.
    nonisolated(unsafe) private var causedCrashes = 0
    nonisolated(unsafe) private var reportedCrashes = 0

    /// XCTest's report of a crash this test caused is no failure; any other crash, and anything else, is.
    override func record(_ issue: XCTIssue) {
        let description = issue.compactDescription
        let crash = description == Self.appCrashed || description.hasPrefix(Self.appCrashed + " in ")
        if crash && reportedCrashes < causedCrashes {
            reportedCrashes += 1
            print("crash \(reportedCrashes) of \(causedCrashes) caused on purpose: \(description)")
            return
        }
        super.record(issue)
    }

    func testCrashReportsFollowTheSetting() throws {
        let sentry = try fakeSentryURL()
        let dsn = "http://public@\(sentry.host() ?? "127.0.0.1"):\(sentry.port ?? 80)/1"
        let data = freshDataName()
        let start = { self.launchDev(keepingData: data, arguments: ["--dev-crash-reports", dsn]) }

        start()
        openDevice()
        switchCrashReports(on: false)
        // Whatever the session sent before the switch (a report an earlier run's process left) doesn't count.
        try fakeSentry("/_reset", post: true)
        crash("with crash reports off")
        start()
        // This device read the setting with the session: by the time it shows, a report would be on its way.
        openDevice()
        require("settings.device.crashReports", timeout: 30, "after the crash, This device lists Send crash reports")
        if !holds(for: 10, { ((try? fakeEvents()) ?? []).isEmpty }) {
            fail("with crash reports off the fake got \((try? fakeEvents())?.count ?? -1) event(s) after the crash")
        }

        switchCrashReports(on: true)
        crash("with crash reports on")
        start()
        let received = { (try? self.fakeEvents()) ?? [] }
        if !waitUntil(60, { received().contains { Self.exceptionType($0) == Self.kotlinException } }) {
            fail("with crash reports on, the fake got no \(Self.kotlinException) within 60 s of the launch after the crash")
        }
        // One event, the crash, sent once: a few more seconds bring no second copy and nothing else.
        if !holds(for: 5, { received().count == 1 }) {
            fail("the fake got \(received().count) events: \(received().map { Self.exceptionType($0) ?? "no exception" })")
        }
        let events = try fakeEvents()
        for event in events { requireScrubbed(event) }
        if let crash = events.first(where: { Self.exceptionType($0) == Self.kotlinException }) { requireKotlinCrash(crash) }
    }

    /// Settings' This device, once the session is up.
    private func openDevice() {
        #if os(tvOS)
        // The tab bar has focus until the Board's shelves load and their first card takes it (SettingsTests).
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        #endif
        openSection("device")
    }

    /// Switches Send crash reports on This device (open) `on` or off.
    private func switchCrashReports(on: Bool) {
        let id = "settings.device.crashReports"
        let wanted = on ? "1" : "0"
        #if os(tvOS)
        moveFocus(to: id, with: .down, limit: 12, "Down reaches Send crash reports")
        let row = element(id)
        if switchValue(row) != wanted { press(.select) }
        #else
        let row = showOnScreen(id, "This device lists Send crash reports")
        if switchValue(row) != wanted { flipSwitch(row) }
        #endif
        if !waitUntil(5, { switchValue(row) == wanted }) { fail("Send crash reports doesn't switch \(on ? "on" : "off")") }
        shot("crash reports \(on ? "on" : "off")")
    }

    /// The dev session's crash: the crash probe, a Kotlin exception that nothing catches (`tortuga://dev-crash`), which
    /// ends the app; on the Mac the system's "quit unexpectedly" alert is put away. XCTest's wait runs the main run loop,
    /// which takes the app's end in.
    private func crash(_ what: String) {
        causedCrashes += 1
        openFromOutside(URL(string: "tortuga://dev-crash")!)
        if !app.wait(for: .notRunning, timeout: 30) { fail("\(what): the dev-crash link doesn't end the app") }
        #if os(macOS)
        let alert = XCUIApplication(bundleIdentifier: "com.apple.UserNotificationCenter")
        let ignore = alert.buttons["Ignore"]
        if ignore.waitForExistence(timeout: 10) { ignore.click() }
        #endif
    }

    /// No secret of the crash's message, no user, no IP address, no device id or name, no server name and no request
    /// anywhere in `event`.
    private func requireScrubbed(_ event: [String: Any]) {
        let json = (try? JSONSerialization.data(withJSONObject: event)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
        for secret in Self.secrets where json.contains(secret) {
            fail("an event sent carries \(secret): \(json.prefix(2000))")
        }
        for key in ["user", "server_name", "request"] {
            if let value = event[key] { fail("an event sent carries \(key): \(value)") }
        }
        for key in ["ip_address", "device_app_hash"] where json.contains(key) {
            fail("an event sent carries \(key): \(json.prefix(2000))")
        }
        let device = (event["contexts"] as? [String: Any])?["device"] as? [String: Any] ?? [:]
        for key in ["id", "name"] {
            if let value = device[key] { fail("an event sent carries the device's \(key): \(value)") }
        }
        let settings = (event["sdk"] as? [String: Any])?["settings"] as? [String: Any]
        if let inferIp = settings?["infer_ip"] as? String, inferIp != "never" {
            fail("an event sent lets the server take the sender's IP address (infer_ip \(inferIp))")
        }
    }

    /// The Kotlin crash as Sentry gets it: its class, its message scrubbed (the addon URL's origin kept), unhandled, and
    /// its frames in the app's binary, whose image the event lists for symbolication.
    private func requireKotlinCrash(_ event: [String: Any]) {
        let exception = Self.exceptions(event).last ?? [:]
        let value = exception["value"] as? String ?? ""
        if !value.hasPrefix("Crash probe: the stream at https://addon.example/") {
            fail("the crash's message reads \"\(value)\", not the scrubbed probe message with the addon's origin")
        }
        if (exception["mechanism"] as? [String: Any])?["handled"] as? Bool != false {
            fail("the crash isn't marked unhandled: \(exception["mechanism"] ?? "no mechanism")")
        }
        let frames = (exception["stacktrace"] as? [String: Any])?["frames"] as? [[String: Any]] ?? []
        // Sentry lists frames outermost first: the last is the throw site, in Kotlin's code in the app's binary.
        if !Self.isAppCode(frames.last?["package"]) {
            fail("the crash's frames don't start in the app's binary: \(frames.map { $0["package"] ?? "?" })")
        }
        let images = (event["debug_meta"] as? [String: Any])?["images"] as? [[String: Any]] ?? []
        if !images.contains(where: { Self.isAppCode($0["code_file"]) && ($0["debug_id"] as? String)?.isEmpty == false }) {
            fail("the crash lists no image of the app's binary to symbolicate its frames with")
        }
    }

    /// Whether `path` is the app's code: its executable, or in a Debug build the debug dylib beside it that holds the
    /// code (Xcode's ENABLE_DEBUG_DYLIB), the Kotlin framework linked in either way.
    private static func isAppCode(_ path: Any?) -> Bool {
        #if os(macOS)
        let executable = "/Tortuga.app/Contents/MacOS/Tortuga"
        #else
        let executable = "/Tortuga.app/Tortuga"
        #endif
        guard let path = path as? String else { return false }
        return path.hasSuffix(executable) || path.hasSuffix(executable + ".debug.dylib")
    }

    private static func exceptions(_ event: [String: Any]) -> [[String: Any]] {
        (event["exception"] as? [String: Any])?["values"] as? [[String: Any]] ?? []
    }

    private static func exceptionType(_ event: [String: Any]) -> String? {
        exceptions(event).last?["type"] as? String
    }

    /// The fake Sentry: the test expects it served, its URL passed as `TEST_RUNNER_TORTUGA_SENTRY`, which
    /// reaches the test runner as `TORTUGA_SENTRY`.
    private func fakeSentryURL() throws -> URL {
        guard let value = ProcessInfo.processInfo.environment["TORTUGA_SENTRY"], let url = URL(string: value), !value.isEmpty else {
            throw HarnessError("TORTUGA_SENTRY is not set: the test needs the fake Sentry's URL as TEST_RUNNER_TORTUGA_SENTRY")
        }
        return url
    }

    /// The event items the fake got since its last `/_reset`, as Sentry's JSON.
    private func fakeEvents() throws -> [[String: Any]] {
        try fakeSentry("/_events") as? [[String: Any]] ?? []
    }

    /// The fake's answer to `path`, POSTed when `post` else read, as JSON.
    @discardableResult
    private func fakeSentry(_ path: String, post: Bool = false) throws -> Any {
        let url = try fakeSentryURL().appending(path: path)
        var request = URLRequest(url: url, timeoutInterval: 10)
        request.httpMethod = post ? "POST" : "GET"
        let answer = SentryAnswer()
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { data, response, error in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if let data, status == 200 {
                answer.data = data
            } else {
                answer.failure = "\(request.httpMethod ?? "") \(path): \(error.map { "\($0)" } ?? "HTTP \(status)")"
            }
            done.signal()
        }.resume()
        done.wait()
        guard let data = answer.data else { throw HarnessError("the fake Sentry didn't answer: \(answer.failure ?? "")") }
        return data.isEmpty ? [:] : try JSONSerialization.jsonObject(with: data)
    }
}

/// What the fake Sentry answered, written by URLSession's queue before the semaphore is signalled.
private final class SentryAnswer: @unchecked Sendable {
    var data: Data?
    var failure: String?
}
