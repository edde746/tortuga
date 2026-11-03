import XCTest

/// The account's notice stream in the product on iPhone, iPad and the Mac, over the shared core's URLSession engine,
/// on the dev stack (server/compose.dev.yaml). The run's hook is the account's other session: it makes the account
/// afresh with one profile, Ana,
/// and the app signs in to it by the provider's device flow (the hook completes it at Dex) and waits on the Library. An
/// item the other session adds shows within seconds, and so does a second one once the stream has stayed open past two
/// keepalives and the 75 s read timeout; then the app leaves the front, and the hook reads the app's and the server's
/// logs: one stream, open throughout, closed as the app left.
final class NoticesTests: TortugaUITestCase {
    /// How long after the sign-in the second item comes: past two keepalives (60 s) and the 75 s read timeout, and
    /// past the 150 s the check asks of the stream.
    private static let held: TimeInterval = 160

    /// An item shows within this long of the server accepting its push, in milliseconds.
    private static let shownWithin = 10_000.0

    func testAnotherSessionsChangesArriveThroughTheNoticeStream() throws {
        continueAfterFailure = false
        executionTimeAllowance = 900
        let server = try AccountScenario.variable("TORTUGA_SERVER")
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        let account = peer("reset", exchange, "the other session makes the account afresh")["accountId"] as? String ?? ""

        launchProduct()
        let codeShown = signIn(server, exchange)
        // The account's one profile: its tile in the picker, or Home at once.
        let tile = element("profiles.tile.0")
        if !waitUntil(180, { tile.exists || homeShows }) { fail("signed in, the app shows neither the profile picker nor Home") }
        let signedIn = Date()
        if tile.exists {
            if tile.label != "Ana" { fail("the picker's first profile is \"\(tile.label)\", not Ana") }
            activate(tile)
        }
        openLibrary()
        shot("library")

        let first = addAndAwait(1, exchange)
        // The second item once the stream has been open longer than the keepalives and the read timeout allow for: the
        // app stays in front meanwhile, its stream open.
        let wait = Self.held - Date().timeIntervalSince(signedIn)
        if wait > 0, !holds(for: wait, { app.state == .runningForeground }) {
            fail("the app left the front while its notice stream was to stay open (state \(app.state.rawValue))")
        }
        let second = addAndAwait(2, exchange)

        let left = leaveFront()
        // The app's closed line and the server's line for the stream, which it writes as the stream ends, both logged
        // before the hook reads the logs: 8 s with the app running out of the front (iOS's simulator reads it as in
        // front after Home, `leaveFront`, so there only its running is seen).
        let away = holds(for: 8) {
            #if os(macOS)
            return app.state == .runningBackground
            #else
            return app.state != .notRunning
            #endif
        }
        if !away { fail("the app didn't stay running out of the front while its stream's end was logged (state \(app.state.rawValue))") }
        let marks: [String: Any] = [
            "accountId": account,
            "codeAt": codeShown.timeIntervalSince1970,
            "pushes": [first, second],
            "backgroundAt": left.timeIntervalSince1970,
        ]
        let notices = exchange.appendingPathComponent("notices", isDirectory: true)
        try FileManager.default.createDirectory(at: notices, withIntermediateDirectories: true)
        try JSONSerialization.data(withJSONObject: marks).write(to: notices.appendingPathComponent("marks.json"), options: .atomic)
        publish("notices-check", code: exchange.path, to: exchange)
        let reply = exchange.appendingPathComponent("replies/notices-check")
        var report = ""
        if !waitUntil(180, { report = (try? String(contentsOf: reply, encoding: .utf8)) ?? ""; return !report.isEmpty }) {
            fail("the run's hook wrote no notices check in 180 s")
        }
        print(report)
        let attachment = XCTAttachment(string: report)
        attachment.name = "notices check"
        attachment.lifetime = .keepAlways
        add(attachment)
        if !report.hasPrefix("PASS") { fail("the app's and the server's logs don't show one stream held and closed:\n\(report)") }

        _ = peer("delete", exchange, "the other session deletes the account")
    }

    /// "Use your own server" with `server`, then the provider's device flow, whose code goes to the run's hook. The
    /// moment the code showed: the app held no session of the account before it. On the Mac, where the product keeps
    /// its data from run to run, a session an earlier run left belongs to an account the other session has deleted
    /// since, which signs the app out at its first call.
    private func signIn(_ server: String, _ exchange: URL) -> Date {
        activate(require("signIn.server.edit", timeout: 90, "the sign-in screen shows"))
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
        activate(require("signIn.option.oidc", timeout: 60, "the server's sign-in options list its OpenID Connect provider"))
        let code = requireCode("deviceCode.code", timeout: 60, "the provider's device flow shows its code")
        let shown = Date()
        publish("device-code", code: code, to: exchange)
        return shown
    }

    /// Home's destinations show: the iPhone's tab bar, the iPad's tabs or the sidebar.
    private var homeShows: Bool {
        element("nav.library").exists || app.tabBars.firstMatch.exists
    }

    /// The Library: the iPhone's tab, the iPad's tab or the Mac's sidebar row.
    private func openLibrary() {
        #if os(iOS)
        if !isPad {
            ready(phoneTab("nav.library")).tap()
            return
        }
        #endif
        activate(require("nav.library", timeout: 60, "Home lists Library"))
    }

    /// Has the other session add item `n` and watches the Library for its card. Returns when the server accepted the
    /// push (the other session's clock: the Mac's, which the simulators share) and when the card showed, in epoch
    /// milliseconds; fails unless it showed within `shownWithin`.
    private func addAndAwait(_ n: Int, _ exchange: URL) -> [String: Any] {
        let title = "Tortuga Notice \(n)"
        let card = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'library.item.' AND label == %@", title)).firstMatch
        let reply = askPeer("add \(n)", exchange)
        var accepted: Double?
        var shown: Double?
        _ = waitUntil(60) {
            if shown == nil, card.exists { shown = Date().timeIntervalSince1970 * 1000 }
            if accepted == nil { accepted = peerAnswer(reply, "adding \(title)")?["acceptedAtMillis"] as? Double }
            return accepted != nil && shown != nil
        }
        guard let accepted else {
            fail("the other session didn't add \(title) in 60 s")
            return [:]
        }
        guard let shown else {
            fail("the Library doesn't show \(title) 60 s after the other session added it")
            return [:]
        }
        shot("item \(n)")
        let delay = shown - accepted
        print("notices: \(title) shown \(Int(delay)) ms after the server accepted it")
        if delay > Self.shownWithin {
            fail("the Library showed \(title) \(Int(delay)) ms after the server accepted it, not within \(Int(Self.shownWithin)) ms")
        }
        return ["n": n, "acceptedAtMillis": accepted, "shownAtMillis": shown]
    }

    /// Takes the app out of the front: Home on iOS; on the Mac Finder in front, which makes the scene inactive, the
    /// app's background there. The moment it went. On iOS 27's simulator XCUITest went on reporting the app as
    /// `runningForeground` for the 10 s it was given after Home, while the app had logged its stream closed
    /// 1.1 s after Home, so only the Mac's state is checked here; the app's closed line (which the hook reads) shows
    /// the background on both.
    private func leaveFront() -> Date {
        let left = Date()
        #if os(macOS)
        // As TabsTests' inactive window: Finder's first activation doesn't always take the front.
        let finder = XCUIApplication(bundleIdentifier: "com.apple.finder")
        withAnotherAppInFront {
            for _ in 0..<3 where app.state == .runningForeground {
                finder.activate()
                _ = waitUntil(5) { app.state != .runningForeground }
            }
        }
        if app.state == .runningForeground { fail("Finder didn't come to the front") }
        #else
        XCUIDevice.shared.press(.home)
        #endif
        shot("left the front", of: XCUIScreen.main)
        return left
    }
}
