#if os(macOS)
import AppKit
#endif
import XCTest

/// What every Tortuga UI test shares: launching the app (the product, or a DEBUG `--dev` session with the test addon
/// and fake tracker server), finding controls by accessibility identifier (the Compose UI's probe names: `nav.search`,
/// `streams.row.0`, `player.back`), reading the player's position, and screenshots kept in the result bundle.
@MainActor
class TortugaUITestCase: XCTestCase {
    lazy var app = XCUIApplication()
    private var shots = 0

    /// The test addon (tools/media/test_addon.py): the test expects it served, its manifest URL passed as
    /// `TEST_RUNNER_TORTUGA_ADDON`, which reaches the test runner as `TORTUGA_ADDON`.
    var addonURL: String {
        get throws {
            guard let url = ProcessInfo.processInfo.environment["TORTUGA_ADDON"], !url.isEmpty else {
                throw HarnessError("TORTUGA_ADDON is not set: the test needs the test addon's manifest URL as TEST_RUNNER_TORTUGA_ADDON")
            }
            return url
        }
    }

    /// The fake Trakt (tools/media/test_trackers.py): the test expects it served, its URL passed as
    /// `TEST_RUNNER_TORTUGA_TRACKERS`, which reaches the test runner as `TORTUGA_TRACKERS`.
    var trackersURL: String {
        get throws {
            guard let url = ProcessInfo.processInfo.environment["TORTUGA_TRACKERS"], !url.isEmpty else {
                throw HarnessError("TORTUGA_TRACKERS is not set: the test needs the fake Trakt's URL as TEST_RUNNER_TORTUGA_TRACKERS")
            }
            return url
        }
    }

    /// Whether a kids test takes its age database from the fake tracker server (`launchDev(trackers: true)`), which
    /// serves its fixture as agerating's release; false with `TEST_RUNNER_TORTUGA_AGERATING=github`, when the app takes
    /// it from agerating's latest release on github.com, as the product does.
    var kidsAgesFromFake: Bool {
        ProcessInfo.processInfo.environment["TORTUGA_AGERATING"] != "github"
    }

    /// Launches the product: the account shell, as a user starts the app.
    func launchProduct() {
        launch([])
    }

    /// Launches a `--dev` session (one local profile in memory, no account): with the test addon installed when
    /// `addon`, Trakt against the fake tracker server when `trackers` (`--dev-trackers`), and then all five trackers
    /// connected to it from the start when `trackersConnected` (`--dev-trackers-connected`), or with `trackers`
    /// MyAnimeList and AniList beside Trakt signing in through the tracker sign-in relay of the Tortuga server at `relay`
    /// (`--dev-relay`), opened on `open` (`--dev-open` screens, each on top of the one before) instead of the Board, with
    /// the watch progress `progress` (`--dev-progress` specs, Continue Watching in their order), with this device's
    /// `settings` (`--dev-setting <key>=<value>`, e.g. `video.hardwareDecoding=false`) after the run's own
    /// (`harnessSettings`), at the text size `textSize` (iOS, a content size category: `UICTContentSizeCategoryXXXL`)
    /// where given, in `languages` (most preferred first: `["fr"]`) where given, else the run's (`languages`), on tvOS
    /// with `matchContent` on the simulated display of an HDR TV with Match Content on (`-simulateMatchContent YES`), and
    /// with `arguments` (DEBUG builds' launch arguments: `-mpvOption name=value`) last.
    func launchDev(
        addon: Bool = false,
        trackers: Bool = false,
        trackersConnected: Bool = false,
        relay: String? = nil,
        open: [String] = [],
        progress: [String] = [],
        settings: [String] = [],
        textSize: String? = nil,
        languages: [String]? = nil,
        matchContent: Bool = false,
        arguments extra: [String] = []
    ) throws {
        var arguments = ["--dev"]
        if addon { arguments += ["--dev-addon", try addonURL] }
        if trackers || trackersConnected { arguments += ["--dev-trackers", try trackersURL] }
        if trackersConnected { arguments.append("--dev-trackers-connected") }
        if let relay { arguments += ["--dev-relay", relay] }
        for screen in open { arguments += ["--dev-open", screen] }
        for spec in progress { arguments += ["--dev-progress", spec] }
        for setting in Self.harnessSettings + settings { arguments += ["--dev-setting", setting] }
        if let textSize { arguments += ["-UIPreferredContentSizeCategoryName", textSize] }
        if matchContent { arguments += ["-simulateMatchContent", "YES"] }
        launch(arguments + extra, languages: languages)
    }

    /// test_addon.py's series (20 s episodes) with its first episode left at 12 s, as a `--dev-progress` spec: the
    /// episode in progress on Detail, and Home's Continue Watching card.
    static let seriesEpisodeInProgress = "series:tortuga-series:tortuga-series:1:1:12:20"

    /// test_addon.py's 60 Tortuga Pages titles (`--pages`) in the library, as `--dev-library`
    /// launch arguments for `launchDev(arguments:)`: more than a screen of Library's grid on every device.
    static let pagesInLibrary = (1...60).flatMap { ["--dev-library", "movie:tortuga-page-\($0)"] }

    /// The same 60 titles in Continue Watching, each at 1 minute of 10, as `--dev-progress` specs.
    static let pagesInProgress = (1...60).map { "movie:tortuga-page-\($0):tortuga-page-\($0):60:600" }

    /// Device settings every `--dev` launch of the run starts with: `TORTUGA_DEV_SETTINGS` (`video.hdr=false`, a run
    /// with HDR output off), passed as `TEST_RUNNER_TORTUGA_DEV_SETTINGS`, split at spaces. A test's
    /// own settings come after them and win.
    static var harnessSettings: [String] {
        (ProcessInfo.processInfo.environment["TORTUGA_DEV_SETTINGS"] ?? "").split(separator: " ").map(String.init)
    }

    /// The subtitle styles the player tests show the same lines in, as `launchDev` settings: this device's default,
    /// and one that changes each part of it (larger, yellow, bold, a thick outline, a half-opaque box, raised).
    static let subtitleStyles: [(name: String, settings: [String])] = [
        ("default style", []),
        ("changed style", [
            "subtitles.sizePercent=150", "subtitles.textColor=yellow", "subtitles.bold=true", "subtitles.outline=thick",
            "subtitles.backgroundPercent=50", "subtitles.liftPercent=30",
        ]),
    ]

    /// Changes a device setting of the running `--dev` session, as `--dev-setting` sets one at the launch (`spec`,
    /// `<key>=<value>`: `subtitles.sizePercent=200`): opens `tortuga://dev-setting?<spec>` in the app under test
    /// (`openFromOutside`), which applies it at once (DEBUG builds' `AppLaunch.openLink`), then waits up to 10 s for the
    /// screen to show it (the player restyling its subtitles over the paused picture: the picture changes, then
    /// settles).
    func changeDevSetting(_ spec: String) {
        guard let query = spec.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "tortuga://dev-setting?\(query)") else {
            return fail("no dev-setting link for '\(spec)'")
        }
        #if os(iOS)
        let picture = { self.screenPNG() }, screen = appScreen
        #else
        let picture = { XCUIScreen.main.screenshot().pngRepresentation }, screen = XCUIScreen.main
        #endif
        let before = picture()
        openFromOutside(url)
        if waitUntil(10, { picture() != before }) { screenSettles(screen) }
    }

    /// Opens `url` in the running app under test through the system, as a link opened elsewhere (a browser, another
    /// app) would reach it: `XCUIApplication.open` launches the app again with it, which ends the player. On the Mac
    /// it goes to the running app's own bundle by path, since a Mac may have other builds registered for the app's
    /// schemes.
    func openFromOutside(_ url: URL) {
        #if os(macOS)
        guard let bundle = NSRunningApplication.runningApplications(withBundleIdentifier: Self.bundleIdentifier).first?.bundleURL else {
            return fail("no running app to send \(url.scheme ?? "the") link to")
        }
        NSWorkspace.shared.open([url], withApplicationAt: bundle, configuration: NSWorkspace.OpenConfiguration())
        #else
        XCUIDevice.shared.system.open(url)
        #endif
    }

    /// The languages the app starts with: `TORTUGA_LANGUAGE`'s (a tag, or a list most preferred first such as
    /// en-US,es-US, passed as `TEST_RUNNER_TORTUGA_LANGUAGE`), else English: the labels the tests look for are English,
    /// and a simulator takes its languages from the Mac it runs on.
    static var languages: [String] {
        let environment = ProcessInfo.processInfo.environment["TORTUGA_LANGUAGE"] ?? ""
        return environment.isEmpty ? ["en"] : environment.split(separator: ",").map(String.init)
    }

    /// Whether the app lays out right to left: its first language is written that way (Arabic, Hebrew).
    static var rightToLeft: Bool {
        Locale.Language(identifier: languages[0]).characterDirection == .rightToLeft
    }

    /// Launches the app with `arguments` in `languages` (default: the run's, `Self.languages`), stopping the test at its
    /// first failure. On the Mac, AppKit would take the dev options' values (`detail:movie:tt0111161`) for documents to
    /// open and open no window, and would restore the window as the last run left it (in full screen after a player test
    /// that ended there, without its buttons), so each launch ignores the saved state; and it would open the window at
    /// the frame the last launch saved (zoomed, narrowed or at a screen's edge after a test left it there) and its sidebar
    /// as the last launch saved it (macOS 27.2 saved it hidden after Settings' export test, which hides it, shows it again
    /// and narrows the window: every later launch lacked the sidebar's destinations), so each launch opens it at
    /// `launchWindowFrame` with the sidebar shown (`launchSidebar`). With `TORTUGA_MUTE=1` (passed as
    /// `TEST_RUNNER_TORTUGA_MUTE` for a run on the Mac someone works at) the player starts muted (`-mpvMute YES`). On the
    /// Mac the test watches the front from its first launch (`watchTheFront`); on iOS an app whose count of running
    /// animations stays above zero stops holding up every action (`AnimationCountLeak`).
    func launch(_ arguments: [String], languages: [String]? = nil) {
        continueAfterFailure = false
        // The app starts with the languages as the system's, its region the first one's.
        let languages = languages ?? Self.languages
        var arguments = arguments + [
            "-AppleLanguages", "(\(languages.joined(separator: ",")))",
            "-AppleLocale", languages[0].replacingOccurrences(of: "-", with: "_"),
        ]
        if ProcessInfo.processInfo.environment["TORTUGA_MUTE"] == "1" { arguments += ["-mpvMute", "YES"] }
        #if os(macOS)
        watchTheFront()
        app.launchArguments = [
            "-NSTreatUnknownArgumentsAsOpen", "NO", "-ApplePersistenceIgnoreState", "YES",
            "-NSWindow Frame main", Self.launchWindowFrame,
            "-NSSplitView Subview Frames main, SidebarNavigationSplitView", Self.launchSidebar,
        ] + arguments
        #else
        app.launchArguments = arguments
        #endif
        #if os(iOS)
        if let missing = AnimationCountLeak.install() { XCTFail(missing); return }
        #endif
        app.launch()
    }

    #if os(macOS)
    /// The window's frame at every launch: the app's size (1280×720, the scene's `defaultSize`) in the middle of the main
    /// display's area for windows. It is the window's saved frame as AppKit writes it (`NSWindow Frame main`, the
    /// window scene's id: x y width height from the display's bottom left, then the display without the menu bar), passed
    /// as an argument, which AppKit reads before the app's saved defaults.
    private static var launchWindowFrame: String {
        let screen = NSScreen.screens[0]
        let visible = screen.visibleFrame
        let display = screen.frame
        return "\(Int(visible.midX - 640)) \(Int(visible.midY - 360)) 1280 720 "
            + "\(Int(display.minX)) \(Int(display.minY)) \(Int(display.width)) \(Int(visible.maxY - display.minY))"
    }

    /// The window's split at every launch: the sidebar shown at its width (200 points) beside the content, in the
    /// 1280-point window (`launchWindowFrame`). It is the split as AppKit saves it (`NSSplitView Subview Frames main,
    /// SidebarNavigationSplitView`: each column's frame, then its collapsed flags), passed as an argument, which AppKit
    /// reads before the app's saved defaults.
    private static let launchSidebar = #"("0.000000, 0.000000, 200.000000, 720.000000, NO, NO", "200.000000, 0.000000, 1080.000000, 720.000000, NO, NO")"#

    /// The app under test's bundle identifier, which other builds of it on the Mac share.
    static let bundleIdentifier = "com.edde746.tortuga"

    /// Each program that came to the front from the app, not judged yet (`noteTakers`): its process, the app's process
    /// it came from, and its line `<time> <name> (<bundle id>, pid <pid>)`. Written on the main actor, and read there
    /// by `record`, which XCTest declares outside it.
    nonisolated(unsafe) private var arrivals: [(process: pid_t, from: pid_t, line: String)] = []
    /// Each program that took the front from the app during the test (`noteTakers`): its process and its line.
    nonisolated(unsafe) private var frontTakers: [(process: pid_t, line: String)] = []
    /// The programs the test brought to the front itself (`withAnotherAppInFront`), until the app is in front again.
    private var broughtUp: Set<pid_t> = []
    /// Whether `withAnotherAppInFront`'s step is running.
    private var bringingUp = false
    /// The app's process while it is the last program macOS brought to the front, else nil.
    private var frontApp: pid_t?
    /// `watchTheFront`'s observers of the activations and of the app hiding.
    private var frontObservers: [any NSObjectProtocol] = []
    /// The time of day in the lines of `arrivals`, on the 24-hour clock whatever the Mac's setting.
    private static let clock: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter
    }()

    /// Watches the front, which other programs can take while the tests run: on the Mac a browser opens sign-in pages
    /// (Brave came in front of the player, and the menu the test had opened closed under it). From
    /// the test's first launch to its end it notes each program macOS brings to the front from the app
    /// (`noteActivation`, from `NSWorkspace`'s notifications, which the main run loop delivers: at once while XCUITest
    /// waits, which runs it, and after a `Thread.sleep` at the next action) and judges them before each action on the
    /// app and at the test's end (`noteTakers`). Each failure then names the programs that took the front from the app
    /// (`record`), the test keeps them as the attachment `front taken`, and each action on the app brings the app back
    /// to the front first (`takeBackTheFront`).
    private func watchTheFront() {
        guard frontObservers.isEmpty else { return }
        // The main queue delivers each notification, on the main actor.
        nonisolated(unsafe) weak let test = self
        let center = NSWorkspace.shared.notificationCenter
        frontObservers = [
            center.addObserver(forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { notification in
                guard let activated = notification.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication else { return }
                MainActor.assumeIsolated { test?.noteActivation(of: activated) }
            },
            center.addObserver(forName: NSWorkspace.didHideApplicationNotification, object: nil, queue: .main) { notification in
                guard let hidden = notification.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication else { return }
                MainActor.assumeIsolated { test?.noteHiding(of: hidden) }
            },
        ]
        addTeardownBlock { [weak self] in
            guard let self else { return }
            RunLoop.current.run(until: Date())
            self.noteTakers()
            self.frontObservers.forEach(NSWorkspace.shared.notificationCenter.removeObserver)
            guard !self.frontTakers.isEmpty else { return }
            let attachment = XCTAttachment(string: self.frontTakers.map(\.line).joined(separator: "\n"))
            attachment.name = "front taken"
            attachment.lifetime = .keepAlways
            self.add(attachment)
        }
    }

    /// Notes `activated`, the program macOS just brought to the front: the app, which ends the turn of the programs the
    /// test brought up (`withAnotherAppInFront`) once its step is over, or another program, which came to the front
    /// from the app (`arrivals`) where the app was in front before it and the test didn't bring it up.
    private func noteActivation(of activated: NSRunningApplication) {
        if activated.bundleIdentifier == Self.bundleIdentifier {
            frontApp = activated.processIdentifier
            if !bringingUp { broughtUp.removeAll() }
            return
        }
        let from = frontApp
        frontApp = nil
        if bringingUp { broughtUp.insert(activated.processIdentifier) }
        guard let from, !broughtUp.contains(activated.processIdentifier) else { return }
        arrivals.append((activated.processIdentifier, from, Self.frontLine(activated)))
    }

    /// Notes `hidden`, a program that just hid: where it is the app (⌘H, the test's or its own), the program macOS hands
    /// the front on to took nothing, whichever of the two notifications came first.
    private func noteHiding(of hidden: NSRunningApplication) {
        guard hidden.bundleIdentifier == Self.bundleIdentifier else { return }
        if frontApp == hidden.processIdentifier { frontApp = nil }
        arrivals.removeAll { $0.from == hidden.processIdentifier }
    }

    /// Judges `arrivals`, in the test's log at once: each took the front from the app, unless the app has quit since
    /// (macOS hands the front on as an app quits: XCUITest's terminate, and its launch, which quits the app first).
    private func noteTakers() {
        for arrival in arrivals where Self.runs(arrival.from) {
            frontTakers.append((arrival.process, arrival.line))
            print("front taken from the app: \(arrival.line)")
        }
        arrivals.removeAll()
    }

    /// `program`'s line in `arrivals` and `frontTakers`: `<time> <name> (<bundle id>, pid <pid>)`.
    private static func frontLine(_ program: NSRunningApplication) -> String {
        "\(clock.string(from: Date())) \(program.localizedName ?? "a program") "
            + "(\(program.bundleIdentifier ?? "no bundle id"), pid \(program.processIdentifier))"
    }

    /// Whether `process` runs.
    nonisolated private static func runs(_ process: pid_t) -> Bool {
        kill(process, 0) == 0 || errno != ESRCH
    }

    /// Before an action on the app: judges `arrivals` (`noteTakers`), then, where a program the test didn't bring up
    /// (`withAnotherAppInFront`) is in front of the app, which runs and isn't hidden, notes it (when the arrivals
    /// haven't: it took the front from another program) and brings the app back to the front; fails at the caller's
    /// line when the app doesn't come back. The app leaving the front itself (hidden, quit) is left for the test to
    /// find.
    private func takeBackTheFront(file: StaticString, line: UInt) {
        guard !frontObservers.isEmpty, !bringingUp else { return }
        // The notifications macOS posted since XCUITest last ran the main run loop.
        RunLoop.current.run(until: Date())
        noteTakers()
        guard let front = NSWorkspace.shared.frontmostApplication, front.bundleIdentifier != Self.bundleIdentifier,
              !broughtUp.contains(front.processIdentifier), app.state == .runningBackground,
              NSRunningApplication.runningApplications(withBundleIdentifier: Self.bundleIdentifier).first?.isHidden == false
        else { return }
        if !frontTakers.contains(where: { $0.process == front.processIdentifier }) {
            let taker = Self.frontLine(front)
            frontTakers.append((front.processIdentifier, taker))
            print("front taken from the app: \(taker)")
        }
        let name = front.localizedName ?? "another program"
        print("front: bringing the app back in front of \(name)")
        app.activate()
        if !app.wait(for: .runningForeground, timeout: 10) {
            fail("\(name) is in front of the app, which didn't come back to the front in 10 s", file: file, line: line)
        }
    }

    /// Each failure of a test during which another program took the front from the app names that program
    /// (`watchTheFront`; also one not judged yet, while the app it came from runs), so a failure it caused is told apart
    /// from the app's own. Read on the main thread only, where the test's state is: a failure recorded on another
    /// thread goes as it is.
    override func record(_ issue: XCTIssue) {
        var issue = issue
        let takers = Thread.isMainThread ? frontTakers.map(\.line) + arrivals.filter { Self.runs($0.from) }.map(\.line) : []
        if !takers.isEmpty {
            issue.compactDescription += " (meanwhile another program took the front from the app: \(takers.joined(separator: "; ")))"
        }
        super.record(issue)
    }
    #endif

    /// Runs `step`, which brings another program to the front on purpose: Finder, the browser the app opens for a
    /// sign-in, a player app the app hands the video to, the picture in picture window a click lands on. On the Mac the
    /// programs that come to the front during it, and the one in front as it ends, are the test's until the app is in
    /// front again: no action brings the app back in front of them, and no failure names them (`watchTheFront`). The
    /// app hiding (⌘H) needs none: the program macOS hands the front on to took nothing.
    func withAnotherAppInFront(_ step: () throws -> Void) rethrows {
        #if os(macOS)
        bringingUp = true
        defer {
            RunLoop.current.run(until: Date())
            bringingUp = false
            if let front = NSWorkspace.shared.frontmostApplication, front.bundleIdentifier != Self.bundleIdentifier {
                broughtUp.insert(front.processIdentifier)
            } else {
                broughtUp.removeAll()
            }
        }
        #endif
        try step()
    }

    /// The playback info panel's row labelled `label` (`Decoder`): one element (`player.info.row`) whose label holds the
    /// row's label and its value; never a section's heading ("Stream"). The first such row where sections share a
    /// label (`Bitrate`).
    func infoRow(_ label: String) -> XCUIElement {
        element("player.info.panel").descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'player.info.row' AND (label BEGINSWITH %@ OR value BEGINSWITH %@)", label, label))
            .firstMatch
    }

    /// An element's label and value together (a row that shows both).
    func fullText(of element: XCUIElement) -> String {
        [element.label, element.value as? String ?? ""].filter { !$0.isEmpty }.joined(separator: " ")
    }

    #if !os(tvOS)
    /// The entry `label` of the open native menu (the player's audio, subtitle and speed menus): a menu item on the
    /// Mac; on iOS a button labelled with it, its detail line after a comma, scrolled to where the menu is taller than
    /// the screen (an iPhone's in landscape lists its later entries only once scrolled to them). Waits up to 5 s; fails
    /// with `what`.
    @discardableResult
    func menuEntry(_ label: String, _ what: String) -> XCUIElement {
        #if os(macOS)
        let entry = app.menuItems.matching(NSPredicate(format: "title == %@ OR label == %@ OR title BEGINSWITH %@", label, label, "\(label)\n")).firstMatch
        #else
        let entry = app.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, "\(label),")).firstMatch
        #endif
        var found = entry.exists(within: 5)
        #if os(iOS)
        // The menu's list: on an iPhone the narrowest list on the screen (the screens' own span it). An iPad's menu holds
        // every entry in the tree, below the screen's edge too, and the narrowest list there can be a screen's (the
        // Streams screen's, kept under the player): a swipe on it lands on what covers it.
        if !found, !isPad, let menu = app.collectionViews.allElementsBoundByIndex
            .filter({ $0.exists && $0.frame.width < app.frame.width }).min(by: { $0.frame.width < $1.frame.width }) {
            for _ in 0..<3 where !found {
                menu.swipeUp(velocity: .slow)
                found = entry.exists(within: 1)
            }
        }
        #endif
        if !found { fail("\(what): no menu entry '\(label)'") }
        return entry
    }

    /// Picks the open menu's entry `entry` (a tap; a click on the Mac), tapping it again where the menu dropped the
    /// tap. iOS 26 and later's menus sometimes cancel a tap on an entry (the stretch of their Liquid Glass takes the
    /// touch): nothing is picked and the menu stays open. A pick closes the menu (`menuClosed`: on iOS its entries leave
    /// the tree within about 2 s, on the Mac they go out of reach at once), so an entry still there 5 s after a tap,
    /// hittable where it was tapped, had its tap dropped: it is tapped again, 3 taps at most, each retry an activity in
    /// the run's log ("the menu dropped tap 1 on …"). On iOS the entry counts as gone only once it leaves the tree: in
    /// the open menu it can be out of reach or moved for a moment (iOS 27.0: one check 4 s after a dropped tap,
    /// the menu open throughout). A pick the app ignores closes the menu too, so a retry never turns one into a pass:
    /// the caller still checks what the pick does. Only for an entry that closes its menu: not one that opens a
    /// submenu, nor a multi-select filter's option on iOS (that menu stays open). Fails at the caller's line when the
    /// menu drops the third tap too.
    func pickMenuEntry(_ entry: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        ready(entry, file: file, line: line)
        let name = entry.identifier.isEmpty ? "'\(entry.label)'" : entry.identifier
        let place = entry.frame
        // Still in the open menu: hittable where it was tapped, not a control of the page under the menu that the same
        // query finds once the menu has gone.
        let inPlace = {
            guard let frame = try? entry.snapshot().frame, entry.isHittable else { return false }
            return abs(frame.minX - place.minX) <= 1 && abs(frame.minY - place.minY) <= 1
                && abs(frame.width - place.width) <= 1 && abs(frame.height - place.height) <= 1
        }
        let taps = 3
        for tap in 1...taps {
            #if os(macOS)
            entry.click()
            #else
            entry.tap()
            #endif
            if waitUntil(5, { menuClosed(entry) }) || !waitUntil(2, inPlace) { return }
            if tap < taps {
                XCTContext.runActivity(named: "the menu dropped tap \(tap) on \(name): tapping it again") { _ in }
            }
        }
        fail("the menu kept \(name) open through \(taps) taps, picking nothing", file: file, line: line)
    }

    /// Whether the open menu's `entry` has gone, picked or closed: on the Mac it may stay in the tree, out of reach.
    func menuClosed(_ entry: XCUIElement) -> Bool {
        #if os(macOS)
        return !entry.exists || !entry.isHittable
        #else
        return !entry.exists
        #endif
    }

    /// Opens the playback info from the player's options menu (`player.more`), the controls up; on the Mac the same
    /// entry closes it again.
    func togglePlaybackInfo() {
        #if os(macOS)
        ready("player.more").click()
        #else
        ready("player.more").tap()
        #endif
        pickMenuEntry(menuEntry("Playback info", "the options menu offers the playback info"))
    }

    /// With the controls up, `id` (the prompt button: up next `player.upNext`, the skip button `player.skip`; the sleep
    /// timer's card `player.stillWatching`) sits above the bottom controls (the timeline) and over none of the player's
    /// buttons or its title: never mid-screen over the transport nor on the controls. On the Mac each of the control
    /// bar's controls counts (`controlBarControls`). Fails with `what` when it doesn't show or lies over one.
    func requireClearOfTheControls(_ id: String, _ what: String) {
        let card = require(id, timeout: 5, what).frame
        let timeline = element("player.scrub").frame
        if card.maxY > timeline.minY { fail("\(what): \(id) reaches down to y \(Int(card.maxY)), onto the timeline at \(Int(timeline.minY))") }
        #if os(macOS)
        for (control, frame) in [("player.title", element("player.title").frame)] + controlBarControls(what) where card.intersects(frame) {
            fail("\(what): \(id) (\(card)) lies over \(control) (\(frame))")
        }
        #else
        for control in ["player.title"] + Self.transport where element(control).exists {
            let frame = element(control).frame
            if card.intersects(frame) { fail("\(what): \(id) (\(card)) lies over \(control) (\(frame))") }
        }
        #endif
    }

    #if os(macOS)
    /// The Mac's control bar's controls (`player.bar.bottom`) with their frames, read at once: each element in it with an
    /// identifier (the transport's buttons, the menus, full screen, the timeline and its times) and each button or
    /// slider without one (the session indicator, the volume). Fails with `what` where the bar doesn't show, or where
    /// what is read leaves out the options menu or the timeline, which the check would then pass over.
    func controlBarControls(_ what: String) -> [(String, CGRect)] {
        guard let bar = try? element("player.bar.bottom").snapshot() else {
            fail("\(what): no control bar (player.bar.bottom) to read")
            return []
        }
        var controls: [(String, CGRect)] = []
        func read(_ snapshot: any XCUIElementSnapshot) {
            for child in snapshot.children {
                if !child.identifier.isEmpty {
                    controls.append((child.identifier, child.frame))
                } else if [.button, .slider, .popUpButton, .menuButton].contains(child.elementType) {
                    controls.append(("the \(child.elementType == .slider ? "slider" : "button") '\(child.label)'", child.frame))
                }
                read(child)
            }
        }
        read(bar)
        for id in ["player.more", "player.scrub"] where !controls.contains(where: { $0.0 == id }) {
            fail("\(what): the control bar's controls (\(controls.map(\.0))) leave out \(id)")
        }
        return controls
    }
    #endif

    /// The transport's buttons in order, left to right (the episode buttons for an episode of a series).
    static let transport = ["player.previous", "player.rewind", "player.playPause", "player.fastForward", "player.next"]

    /// With the controls up, the transport's buttons that show sit in `transport`'s order, left to right, side by side
    /// on the window (`windowFrame`) without one over another. Fails with `what`.
    func requireTransportInOrder(_ what: String) {
        let ids = Self.transport.filter { element($0).exists }
        let frames = ids.map { element($0).frame }
        let window = windowFrame
        for (index, frame) in frames.enumerated() {
            if !window.insetBy(dx: -0.5, dy: -0.5).contains(frame) { fail("\(what): \(ids[index]) (\(frame)) reaches past the window (\(window))") }
            if index > 0, frame.minX < frames[index - 1].maxX {
                fail("\(what): \(ids[index]) (\(frame)) isn't right of \(ids[index - 1]) (\(frames[index - 1]))")
            }
        }
    }
    #endif

    /// The player's previous and next episode buttons (`player.previous`, `player.next`), the controls up: for an episode
    /// of a series both, each enabled as `enabled` has it (disabled at the series' ends); for a movie (nil) neither.
    /// Fails with `what`.
    func requireEpisodeButtons(_ enabled: (previous: Bool, next: Bool)?, _ what: String) {
        let buttons = [("Previous", element("player.previous"), enabled?.previous), ("Next", element("player.next"), enabled?.next)]
        for (name, button, on) in buttons {
            guard let on else {
                if button.exists { fail("\(what): the controls offer \(name) episode") }
                continue
            }
            if !button.exists(within: 5) { fail("\(what): the controls offer no \(name) episode") }
            if !waitUntil(5, { button.isEnabled == on }) { fail("\(what): \(name) episode is \(on ? "disabled" : "enabled")") }
        }
    }

    /// The element with accessibility identifier `id`, of any type.
    func element(_ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// The frames of the elements with the identifiers `ids`, read from one snapshot of the app: controls that hide on a
    /// timer are read together, in one query. An identifier missing from the snapshot has no entry.
    func frames(of ids: [String]) -> [String: CGRect] {
        guard let root = try? app.snapshot() else { return [:] }
        var found: [String: CGRect] = [:]
        func walk(_ node: any XCUIElementSnapshot) {
            if ids.contains(node.identifier), found[node.identifier] == nil { found[node.identifier] = node.frame }
            node.children.forEach(walk)
        }
        walk(root)
        return found
    }

    /// What the menu `id` names ("Genre: All", "Continue Watching": what VoiceOver says, where the capsule shows
    /// "Genre"): on the Mac the menu button's title, label or value; elsewhere its label, else the text inside it,
    /// without its direction marks. Empty while there is no such menu.
    func menuText(_ id: String) -> String {
        let menu = element(id)
        guard menu.exists else { return "" }
        #if os(macOS)
        return ([menu.title, menu.label, menu.value as? String ?? ""].first { !$0.isEmpty } ?? "").withoutDirectionMarks
        #else
        if !menu.label.isEmpty { return menu.label.withoutDirectionMarks }
        let text = menu.staticTexts.firstMatch
        return text.exists ? text.label.withoutDirectionMarks : ""
        #endif
    }

    #if os(iOS)
    /// Fails with `what` when one of the filter capsules `ids` that show reaches past a side of the screen (a filter
    /// row fits the screen, never scrolling sideways), or when none of them shows.
    func requireFiltersOnScreen(_ ids: [String], _ what: String) {
        let screen = app.windows.firstMatch.frame
        let shown = ids.filter { element($0).exists }
        if shown.isEmpty { fail("\(what): none of \(ids) shows") }
        for id in shown {
            let frame = element(id).frame
            if frame.minX < screen.minX - 0.5 || frame.maxX > screen.maxX + 0.5 {
                fail("\(what): \(id) lies from x \(Int(frame.minX)) to \(Int(frame.maxX)), past the screen's \(Int(screen.minX))–\(Int(screen.maxX))")
            }
        }
    }
    #endif

    /// Waits for `id` to exist; fails the test with `what` (and a screenshot and the element tree) when it doesn't.
    @discardableResult
    func require(_ id: String, timeout: TimeInterval = 20, _ what: String) -> XCUIElement {
        let found = element(id)
        if !found.exists(within: timeout) { fail("\(what): no \(id) after \(Int(timeout)) s") }
        return found
    }

    /// Waits for `id` to be gone; fails the test with `what` when it stays.
    func requireGone(_ id: String, timeout: TimeInterval = 10, _ what: String) {
        if !waitUntil(timeout, { !element(id).exists }) { fail("\(what): \(id) still there after \(Int(timeout)) s") }
    }

    /// `target` once a tap, click, hover or swipe can reach it, waiting up to `timeout`; fails the test at the caller's
    /// line when it can't by then. XCUITest's own actions look once: one aimed at an element that a sheet, a menu or the
    /// controls bring up a moment later fails ("No matches found"), and one aimed at an element still under a sheet or
    /// menu on its way out lands on that. Every tap, click, hover and swipe aimed at an element goes through here
    /// (`ready(element).tap()`, also after `require`, which waits only for the element to be there); typing into a
    /// field goes through `requireTypingFocus`. On the Mac the app first comes back in front of a program that took the
    /// front from it (`takeBackTheFront`), but for an element of another app.
    ///
    /// Reachable is XCUITest's hittable (its hit test at the element's hit point finds the element), but where that
    /// test can't see what the action reaches:
    /// - nothing of the element lies inside its ancestors' frames (`visiblePart`): XCUITest's tap and click scroll it
    ///   in first (off the window; an entry below the visible part of a phone's menu);
    /// - a list, collection view, table or scroll view with part of it inside them: a swipe drags across it from its
    ///   centre, where XCUITest's hit test can find a row it doesn't count as the list's (iPad's Settings → Playback,
    ///   its centre on the Play the next episode toggle). A sheet on its way out over the list takes that swipe;
    /// - with `ofAnotherApp`, an element in another app's window that XCUITest's hit test misses, once it is there (the
    ///   Mac's picture in picture controls, PIPAgent's): `visiblePart` reads only Tortuga's tree. Another app's element
    ///   without it waits for XCUITest's hittable (SpringBoard's picture in picture window on iOS).
    @discardableResult
    func ready(
        _ target: XCUIElement, ofAnotherApp: Bool = false, timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line
    ) -> XCUIElement {
        #if os(macOS)
        if !ofAnotherApp { takeBackTheFront(file: file, line: line) }
        #endif
        let reachable = waitUntil(timeout) {
            guard let snapshot = try? target.snapshot() else { return false }
            if target.isHittable || ofAnotherApp { return true }
            guard let visible = visiblePart(of: snapshot) else { return false }
            return visible.isEmpty || [.collectionView, .table, .scrollView].contains(snapshot.elementType)
        }
        if !reachable {
            let state = target.exists ? "there but not hittable" : "not there"
            fail("\(target.description) is \(state) after \(Int(timeout)) s", file: file, line: line)
        }
        return target
    }

    /// The part of `element`'s frame inside the frames of its ancestors, its window's among them, as XCUITest clips an
    /// element before its hit test; an open menu starts afresh (the Mac's menus hang off their button, outside its
    /// frame). Nil where the app's tree, read again, no longer holds `element`.
    private func visiblePart(of element: any XCUIElementSnapshot) -> CGRect? {
        guard let root = try? app.snapshot() else { return nil }
        func clip(_ node: any XCUIElementSnapshot, _ inside: CGRect) -> CGRect? {
            let visible = (node.elementType == .menu ? CGRect.infinite : inside).intersection(node.frame)
            if node.elementType == element.elementType, node.identifier == element.identifier, node.frame == element.frame,
               node.label == element.label {
                return visible
            }
            return node.children.lazy.compactMap { clip($0, visible) }.first
        }
        return root.children.lazy.compactMap { clip($0, .infinite) }.first
    }

    #if os(iOS)
    /// The frontmost list: the last collection view the app's tree holds. XCUITest's query also returns the lists a
    /// sheet covers, which the tree leaves out, and not always before the sheet's: on the iPhone Duo's cover screen the
    /// list of the screen under a sheet comes after the sheet's, and a swipe aimed at it lands on the sheet.
    var frontmostList: XCUIElement {
        let lists = app.collectionViews.allElementsBoundByIndex
        let shown = lists.filter { list in (try? list.snapshot()).flatMap { visiblePart(of: $0) } != nil }
        return shown.last ?? app.collectionViews.element(boundBy: max(lists.count - 1, 0))
    }
    #endif

    /// `ready(element(id))`: the element with accessibility identifier `id` once a tap, click or hover can reach it.
    @discardableResult
    func ready(_ id: String, timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        ready(element(id), timeout: timeout, file: file, line: line)
    }

    /// Waits for the shelf card `id` (`search.group.<n>.item.<i>`, `board.row.<n>.item.<i>`), then for its screen's
    /// shelves to settle (`requireShelvesSettled`), and returns the card the settled screen has at `id`; fails with
    /// `what` when there is none.
    @discardableResult
    func requireCard(_ id: String, timeout: TimeInterval = 20, _ what: String) -> XCUIElement {
        require(id, timeout: timeout, what)
        requireShelvesSettled(id.split(separator: ".").prefix(2).joined(separator: "."), what)
        return require(id, timeout: 5, "\(what), once the shelves settled")
    }

    /// Waits until none of the shelves `prefix` names (`search.group`: Search's, `board.row`: the Board's) is loading
    /// (`<prefix>.<n>.loading`); fails with `what` when one still is after 40 s, past the addons' 30 s timeout. Search
    /// and the Board leave out a shelf whose catalog answers empty, which moves every shelf after it up an index: an
    /// index read while a shelf above it loads can name another shelf, or none (iOS 26.5: Search's Tortuga
    /// Pages shelf was `search.group.1` under Cinemeta's loading one, then `.0` once Cinemeta found nothing).
    func requireShelvesSettled(_ prefix: String, _ what: String) {
        let loading = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@ AND identifier ENDSWITH '.loading'", prefix + "."))
            .firstMatch
        if !waitUntil(40, { !loading.exists }) { fail("\(what): \(loading.identifier) still loads after 40 s") }
    }

    /// Waits for the shelf `shelf` (`board.row.0`, `search.group.1`; Continue Watching's `board.cw`, Detail's
    /// `detail.episode`) to show a card (`<shelf>.item.<n>`, or `<shelf>.<n>`: a row brought to its current episode
    /// shows a card mid-row first), then for its screen's shelves to settle (`requireShelvesSettled`); fails with `what`
    /// when it shows none within `timeout`.
    func requireShelfContent(_ shelf: String, timeout: TimeInterval = 60, _ what: String) {
        let pattern = NSRegularExpression.escapedPattern(for: shelf) + "\\.(item\\.)?[0-9]+"
        let card = app.descendants(matching: .any).matching(NSPredicate(format: "identifier MATCHES %@", pattern)).firstMatch
        if !card.exists(within: timeout) { fail("\(what): \(shelf) shows no card after \(Int(timeout)) s") }
        requireShelvesSettled(shelf.split(separator: ".").prefix(2).joined(separator: "."), what)
    }

    #if !os(tvOS)
    /// The text field `id` of a sheet that focuses it as it opens (the server sheet's address, Addons' install link),
    /// ready to type into; fails with `what` when it doesn't take the focus. On iOS it isn't tapped: the focus comes as
    /// the sheet settles (up to a second after it shows on iOS 26.5), and the keyboard it raises lifts the sheet, so a
    /// tap aimed at the field meanwhile lands where the field no longer is and can close the sheet (iOS 26.5:
    /// the tour's server sheet closed under a tap above its field). The Mac has no keyboard to wait for: a click puts
    /// the insertion point there.
    @discardableResult
    func requireTypingFocus(_ id: String, _ what: String) -> XCUIElement {
        let field = require(id, what)
        #if os(macOS)
        ready(field).click()
        #else
        // XCUITest's own attribute, which its API doesn't name.
        if !waitUntil(10, { field.value(forKey: "hasKeyboardFocus") as? Bool == true }) {
            fail("\(what): \(id) doesn't take the keyboard focus")
        }
        #endif
        return field
    }
    #endif

    #if !os(tvOS)
    /// Closes the sheet that shows `content` (`player.info.panel`, `settings.languages.search`) and waits for it to go;
    /// fails with `what` when it stays. Where the sheet has its Close (`close`: the Mac, iPad, the iPhone Duo's inner
    /// screen) a tap or click on it. An iPhone's resizable sheet has the grabber and no Close: there its bar, the
    /// lowest navigation bar on the screen (the sheet's, over the screen's own where it has one), is dragged to the
    /// screen's foot, as a person pulls the sheet down.
    func closeSheet(_ close: String, showing content: String, _ what: String) {
        require(content, what)
        #if os(iOS)
        if !isPad, !element(close).exists(within: 2) {
            let bars = app.navigationBars.allElementsBoundByIndex.filter { $0.exists && !$0.frame.isEmpty }
            guard let bar = bars.max(by: { $0.frame.minY < $1.frame.minY }) else {
                return fail("\(what): the sheet showing \(content) has no bar to pull it down by")
            }
            bar.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(
                forDuration: 0.05,
                thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.99)),
                withVelocity: .fast,
                thenHoldForDuration: 0
            )
            requireGone(content, what)
            return
        }
        #endif
        activate(require(close, what))
        requireGone(content, what)
    }
    #endif

    /// Waits for the screen titled `title` (its navigation bar); fails with `what` when it doesn't show.
    func requireScreen(_ title: String, _ what: String) {
        if !app.navigationBars[title].exists(within: 5) { fail("\(what): no \(title) screen") }
    }

    /// Checks `condition` every quarter second until it holds or `timeout` passes; whether it held. The one poll the
    /// tests have: a step waits for what it needs, never for a fixed time.
    func waitUntil(_ timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while true {
            if condition() { return true }
            if Date() >= deadline { return false }
            Thread.sleep(forTimeInterval: 0.25)
        }
    }

    /// Whether `condition` held at every check (`waitUntil`'s) through `duration`; false at the first check it fails.
    /// For a span where something must not happen or must keep holding: the controls staying up while paused, the
    /// position staying put, a timer not firing yet.
    func holds(for duration: TimeInterval, _ condition: () -> Bool) -> Bool {
        !waitUntil(duration) { !condition() }
    }

    /// Whether the picture has stopped changing within `timeout`: two screenshots of `source` (the app's by default) in
    /// a row came out the same. For a picture that finishes drawing before a screenshot is kept (a paused frame
    /// redrawn, an animation ended); wait first for the event the redraw follows, since the old picture, still, settles
    /// too.
    @discardableResult
    func screenSettles(_ source: (any XCUIScreenshotProviding)? = nil, timeout: TimeInterval = 5) -> Bool {
        let provider = source ?? app
        var last = provider.screenshot().pngRepresentation
        return waitUntil(timeout) {
            let next = provider.screenshot().pngRepresentation
            defer { last = next }
            return next == last
        }
    }

    /// The player's position in seconds: the first timecode (`1:23`, `1:02:03`) of the store's position label
    /// (`1:23 / 45:00`), which the Debug build's probe `player.playhead` reads with the controls down. Nil while there is
    /// no player or no position. The Mac doesn't expose a plain element's value: there the pointer brings the controls
    /// up (`pointerOn`) and the position beside their timeline (`player.position`) is read. Each read moves the pointer to
    /// the other of two points: a move to where the pointer already is moves nothing, and the controls would hide on their
    /// timer.
    func playerPosition() -> Double? {
        let video = element("player.video")
        guard video.exists else { return nil }
        #if os(macOS)
        positionReadLow.toggle()
        pointerOn(video, at: CGVector(dx: 0.5, dy: positionReadLow ? 0.45 : 0.4))
        let position = element("player.position")
        if position.exists(within: 1) { return Self.seconds(text(of: position)) }
        return nil
        #else
        return Self.seconds(playhead())
        #endif
    }

    /// The store's position label (`1:23 / 45:00`, `Live · watched 12 s`), as the Debug build's probe
    /// `player.playhead` reads it, the controls up or down; empty without a player. The product's video reads it as
    /// its value only while an assistive technology runs.
    func playhead() -> String {
        let probe = element("player.playhead")
        return probe.exists ? text(of: probe) : ""
    }

    #if os(macOS)
    /// Which of its two points `playerPosition` moved the pointer to last.
    private var positionReadLow = false
    /// When and where this test's last pointer move (`pointerOn`, `movePointer`) ended.
    private var lastPointerMove: (end: Date, at: CGPoint)?

    /// Whether the run's hook moves the pointer where XCUITest can't (`pointerOn`): on macOS 27.2 and later, where the
    /// run sets `TORTUGA_POINTER_EVENTS`.
    private var hookMovesPointer: Bool {
        ProcessInfo.processInfo.environment["TORTUGA_POINTER_EVENTS"] == "1"
            && ProcessInfo.processInfo.isOperatingSystemAtLeast(OperatingSystemVersion(majorVersion: 27, minorVersion: 2, patchVersion: 0))
    }

    /// Puts the pointer on `offset` of `element` (as `coordinate(withNormalizedOffset:)` takes it); over the player's
    /// video that brings the controls up. The player hides the pointer with its controls until it moves
    /// (`NSCursor.setHiddenUntilMouseMoves`). On macOS 27.2 only an event on the very point where the hidden pointer is
    /// shows it again; one that puts it anywhere else (XCUITest's hover and click, or a posted move) leaves it hidden, and
    /// the player takes no move from it, until the player hides it afresh. So there, while the player is up, XCUITest
    /// hovers only where the controls can't hide during the hover: they are up, this test's last pointer move ended
    /// in the window less than 1.5 s ago (their timer runs 3 s from a move), and the hover ends in the window too
    /// (leaving it hides them at once). Otherwise the pointer moves as a mouse moves it, through the run's hook
    /// (`movePointer(to:)`), whose moves start on the pointer's own point; 80 pt on along the x axis when it is already
    /// on the point (a move to where it is moves nothing). A move through the hook takes about a second.
    func pointerOn(_ element: XCUIElement, at offset: CGVector) {
        let point = element.coordinate(withNormalizedOffset: offset)
        guard hookMovesPointer, self.element("player.video").exists else { return point.hover() }
        var to = point.screenPoint
        if hoverEndsWithTheControlsUp(at: to) {
            point.hover()
            lastPointerMove = (Date(), to)
            return
        }
        if hypot(to.x - pointerLocation.x, to.y - pointerLocation.y) < 2 { to.x += offset.dx < 0.5 ? 80 : -80 }
        do {
            try movePointer(to: to)
        } catch {
            fail("\(error)")
        }
    }

    /// Whether the player's controls stay up through a hover to `point` (`pointerOn`): this test's last pointer move
    /// ended in the window less than 1.5 s ago, `point` lies in it too, and the controls are up.
    private func hoverEndsWithTheControlsUp(at point: CGPoint) -> Bool {
        guard let last = lastPointerMove, Date().timeIntervalSince(last.end) < 1.5 else { return false }
        let window = app.windows.firstMatch.frame.insetBy(dx: 1, dy: 1)
        return window.contains(last.at) && window.contains(point) && self.element("player.playPause").exists
    }

    /// Moves the pointer out of the app's window, onto the point just above the middle of its top edge. Leaving the
    /// window hides the player's controls, and the pointer with them, at once (`pointerOn`), so where the run's hook
    /// moves the pointer the move ends on its first point out of the window: not moved again while hidden, the pointer
    /// shows with the next move.
    func pointerOutOfTheWindow() {
        let above = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0)).withOffset(CGVector(dx: 0, dy: -1))
        guard hookMovesPointer else { return above.hover() }
        do {
            try movePointer(to: above.screenPoint)
        } catch {
            fail("\(error)")
        }
    }

    /// How many moves `movePointer` has published in this run: the run's watcher takes each event's name once, so
    /// the count goes on from test to test.
    private static var pointerMoves = 0

    /// Moves the pointer from where it is to `point` (screen points from the main display's top left) through every
    /// point between, as a hand moves it, which XCUITest's hover (a jump) doesn't: published for the run's pointer
    /// hook (`move-<n>`: `<x> <y>`), which posts it, starting on the pointer's own point. Waits up to 15 s
    /// for the pointer to get there.
    func movePointer(to point: CGPoint) throws {
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        Self.pointerMoves += 1
        publish("move-\(Self.pointerMoves)", code: "\(Int(point.x)) \(Int(point.y))", to: exchange)
        if !waitUntil(15, { hypot(pointerLocation.x - CGFloat(Int(point.x)), pointerLocation.y - CGFloat(Int(point.y))) < 2 }) {
            fail("the pointer hook didn't move the pointer to \(point): it is at \(pointerLocation)")
        }
        lastPointerMove = (Date(), pointerLocation)
    }

    /// Where the pointer is, in screen points from the main display's top left (XCUITest's frames').
    var pointerLocation: CGPoint {
        let location = NSEvent.mouseLocation
        return CGPoint(x: location.x, y: (NSScreen.screens.first?.frame.height ?? 0) - location.y)
    }
    #endif

    /// An element's text: its label, else its value (the Mac reports a text's words as its value).
    func text(of element: XCUIElement) -> String {
        element.label.isEmpty ? (element.value as? String ?? "") : element.label
    }

    /// What VoiceOver reads after a card's or an episode's name, its value's parts in order, those it has, without their
    /// direction marks: its caption's second line ("2024 · ★ 7.8", "S1E2 · Up next"), its progress ("45%") and
    /// "Watched". The caption under a card is hidden from accessibility, so this is the one place a test reads it.
    func itemState(_ element: XCUIElement) -> [String] {
        (element.value as? String ?? "").withoutDirectionMarks.components(separatedBy: ", ").filter { !$0.isEmpty }
    }

    /// Whether a card or an episode reads as watched: "Watched" in its value (`itemState`). Watched is never the
    /// selected trait, which is a chosen season's or tab's.
    func isWatched(_ element: XCUIElement) -> Bool {
        itemState(element).contains("Watched")
    }

    /// A card's or an episode's progress as its value reads it ("45%"), or "" with none.
    func progressState(_ element: XCUIElement) -> String {
        itemState(element).first { $0.hasSuffix("%") } ?? ""
    }

    /// The first timecode in `label` in seconds.
    static func seconds(_ label: String) -> Double? {
        guard let timecode = label.split(separator: " ").first(where: { $0.contains(":") }) else { return nil }
        var seconds = 0.0
        for part in timecode.split(separator: ":") {
            guard let value = Double(part) else { return nil }
            seconds = seconds * 60 + value
        }
        return seconds
    }

    /// Waits for the player to report `seconds` of playback, within `timeout`; the position reached.
    @discardableResult
    func requirePlayback(_ seconds: Double, timeout: TimeInterval) -> Double {
        var position: Double?
        let played = waitUntil(timeout) {
            position = playerPosition() ?? position
            return (position ?? 0) >= seconds
        }
        if !played {
            fail("the player reached \(position.map { "\($0) s" } ?? "no position") in \(Int(timeout)) s; expected ≥ \(seconds) s")
        }
        return position ?? 0
    }

    /// Waits until the player has loaded: no loading text (`player.loading`, while the stream resolves, opens or
    /// buffers). Until the stream opens the controls stay up; their 5 s start once it has opened and run on while it
    /// buffers, and up next's countdown starts only once it plays: a deadline on one of those starts after this. Fails
    /// with `what` when it still loads after 90 s.
    func requirePlayerLoaded(_ what: String) {
        let loading = element("player.loading")
        if !waitUntil(90, { !loading.exists }) { fail("\(what): the player still loads after 90 s (\(text(of: loading)))") }
    }

    /// The player plays `seriesEpisodeInProgress`'s episode from the beginning, not from where it was left: once it has
    /// played 2 s, the position reads under 8 s (resumed, it would read 12 s or more). Fails with `what`.
    func requirePlayingFromTheStart(_ what: String) {
        let position = requirePlayback(2, timeout: 90)
        if position >= 8 { fail("\(what): the player is at \(Int(position)) s, resuming the episode's 12 s, not playing from the beginning") }
    }

    /// A screenshot of the app (or of `source`: the whole screen while another app is in front; on a foldable always the
    /// screen the app is on, `screenPNG`), kept in the result bundle as `NN name` (numbered in the order taken). On iOS
    /// in landscape the screen's, turned a quarter: the app's own holds its portrait frame cropped to the landscape
    /// canvas.
    func shot(_ name: String, of source: (any XCUIScreenshotProviding)? = nil) {
        shots += 1
        var provider: any XCUIScreenshotProviding = source ?? app
        #if os(iOS)
        if source == nil, app.frame.width > app.frame.height { provider = XCUIScreen.main }
        let attachment = isFoldable
            ? XCTAttachment(data: screenPNG(), uniformTypeIdentifier: "public.png")
            : XCTAttachment(screenshot: provider.screenshot())
        #else
        let attachment = XCTAttachment(screenshot: provider.screenshot())
        #endif
        attachment.name = String(format: "%02d %@", shots, name)
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Fails the test with `message`, keeping the app's threads (`keepAppThreads`), a screenshot and the app's element
    /// tree for the run's report.
    func fail(_ message: String, file: StaticString = #filePath, line: UInt = #line) {
        keepAppThreads()
        let tree = XCTAttachment(string: app.debugDescription)
        tree.name = "element tree"
        tree.lifetime = .keepAlways
        add(tree)
        shot("failure")
        XCTFail(message, file: file, line: line)
    }

    /// How many samples of the app's threads `keepAppThreads` has asked for in this run.
    private static var threadSamples = 0

    /// How many window drags (`drag-<n>`) the macOS tests have published in this run: the run's watcher takes each
    /// event's name once, so PlayerTests' and TabsTests' drags count on from one another's, as `click-<n>` and
    /// `move-<n>` do.
    static var windowDrags = 0

    /// Keeps the app's threads as `app threads`, so a hang shows where it waits: the event `sample-<n>` (the exchange's
    /// path) asks the run's hook (`TORTUGA_EXCHANGE`) for `sample` of the app's process for 3 s on the Mac it runs
    /// on (a simulator's app is one of that Mac's processes), which it writes to `<exchange>/replies/sample-<n>`. Waits
    /// up to 60 s for it; without it, what kept it from coming is kept instead. Written with `.atomic`, not `publish`,
    /// whose own failure would come back here.
    private func keepAppThreads() {
        var threads = "TORTUGA_EXCHANGE is not set: sampling the app's threads needs the hook's exchange folder as TEST_RUNNER_TORTUGA_EXCHANGE"
        if let path = ProcessInfo.processInfo.environment["TORTUGA_EXCHANGE"], !path.isEmpty {
            let exchange = URL(fileURLWithPath: path, isDirectory: true)
            Self.threadSamples += 1
            let event = "sample-\(Self.threadSamples)"
            let reply = exchange.appendingPathComponent("replies/\(event)")
            do {
                try FileManager.default.createDirectory(at: exchange, withIntermediateDirectories: true)
                try Data(exchange.path.utf8).write(to: exchange.appendingPathComponent(event), options: .atomic)
                threads = "the run's hook wrote no \(reply.path) in 60 s"
                _ = waitUntil(60) {
                    guard let text = try? String(contentsOf: reply, encoding: .utf8) else { return false }
                    threads = text
                    return true
                }
            } catch {
                threads = "can't publish \(event) in \(exchange.path): \(error)"
            }
        }
        let attachment = XCTAttachment(string: threads)
        attachment.name = "app threads"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    #if os(iOS)
    /// The tabs' destinations, in order (Addons is a section of Settings).
    static let phoneTabs = ["nav.board", "nav.discover", "nav.search", "nav.library", "nav.settings"]

    /// The tab `id` (`nav.settings`): by its identifier (iPhone's tab bar, iPad's floating one), or on iPhone by its
    /// place among the five when the tab bar came up without its buttons' identifiers (it sometimes does on iOS 27, most
    /// often after a relaunch). On iPad the floating tab bar is first paged to it where it lies behind the bar's arrow
    /// (`pageFloatingTabBar`).
    func phoneTab(_ id: String) -> XCUIElement {
        let byId = element(id)
        if byId.exists(within: 5) {
            if isPad { pageFloatingTabBar(to: id) }
            return byId
        }
        let buttons = app.tabBars.firstMatch.buttons
        guard let index = Self.phoneTabs.firstIndex(of: id), buttons.firstMatch.exists(within: 15), buttons.count == 5 else {
            fail("the tab bar has no \(id) (\(app.tabBars.firstMatch.buttons.count) tabs)")
            return byId
        }
        return buttons.element(boundBy: index)
    }

    /// Pages iPad's floating tab bar to the tab `id` by the bar's arrows, as a person does, where the tab lies behind
    /// one. The bar is as wide as its tabs up to a width of its own (590 pt on the 13" iPad in portrait), and its labels
    /// grow with the text size (a fifth wider at XXL): the tabs that don't fit go behind an arrow at its end (Next page;
    /// Previous page at the other end once paged), a button without an identifier on the tabs' row, over the tab it cuts.
    /// XCUITest reports a tab behind the arrow as hittable, at its middle beyond the bar, where a tap reaches nothing.
    /// Each arrow pages toward its end of the bar; the tab is within reach once it lies wholly between the arrows. Fails
    /// when four pages don't bring it there.
    private func pageFloatingTabBar(to id: String) {
        let middle = app.windows.firstMatch.frame.midX
        for _ in 0..<4 {
            guard let root = try? app.snapshot() else { return }
            var tabs: [String: CGRect] = [:]
            var unnamed: [CGRect] = []
            func walk(_ node: any XCUIElementSnapshot) {
                if node.elementType == .button, Self.phoneTabs.contains(node.identifier) {
                    if tabs[node.identifier] == nil { tabs[node.identifier] = node.frame }
                    return
                }
                if node.elementType == .button, node.identifier.isEmpty { unnamed.append(node.frame) }
                node.children.forEach(walk)
            }
            walk(root)
            // Sidebar rows are cells: no floating bar shows.
            guard let tab = tabs[id] else { return }
            let arrows = unnamed.filter { button in
                tabs.values.contains { $0.intersects(button) && abs($0.midY - button.midY) < 1 && abs($0.height - button.height) < 1 }
            }
            let arrow = arrows.first { $0.midX > middle ? tab.maxX > $0.minX : tab.minX < $0.maxX }
            guard let arrow else { return }
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: arrow.midX, dy: arrow.midY)).tap()
            _ = waitUntil(3) { element(id).frame != tab }
        }
        fail("\(id) stays behind the floating tab bar's arrow after four pages")
    }

    /// The tabs' identifiers, in order: the iPhone tab bar's buttons (an empty one where iOS 27 left a button without
    /// its identifier), or iPad's floating tab bar's, which XCUITest doesn't see as a tab bar, left to right. Empty
    /// while no tabs show.
    func tabIds() -> [String] {
        let bar = app.tabBars.firstMatch
        if bar.exists, bar.buttons.count > 0 {
            return (0..<bar.buttons.count).map { bar.buttons.element(boundBy: $0).identifier }
        }
        let tabs = app.buttons.matching(NSPredicate(format: "identifier IN %@", Self.phoneTabs)).allElementsBoundByIndex
        var seen = Set<String>()
        return tabs.sorted { $0.frame.minX < $1.frame.minX }.map(\.identifier).filter { seen.insert($0).inserted }
    }

    /// An iPad (the tests run on iPhone and iPad alike).
    var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    /// Quits the app and turns the device to `orientation`: the test launches it again, in that orientation. Turned while
    /// it runs, the iPad with iOS 27.0 can leave XCUITest waiting on the app for good: a view UIKit makes for the turn's
    /// animation and drops before the animation commits (the floating tab bar's items, the sidebar's scroll indicators,
    /// the sidebar under the player's cover) has one of its animations reported started twice and stopped once, and
    /// every later query then waits 60 s while the app is still: XCUITest counts the app's running animations from
    /// those reports, so its count stays above zero for the rest of the app's run.
    static func quitAndTurn(to orientation: UIDeviceOrientation) {
        XCUIApplication().terminate()
        XCUIDevice.shared.orientation = orientation
    }

    /// A foldable iPhone (the iPhone Duo), whose cover and inner screens XCUIScreen lists both.
    var isFoldable: Bool { XCUIScreen.screens.count > 1 }

    /// The screen the app is on, for reading its pixels: XCUIScreen.main, but on a foldable, whose main screen is the
    /// cover whichever screen the app is on, the screen whose screenshot is the app's window's size, upright.
    var appScreen: XCUIScreen {
        guard isFoldable else { return XCUIScreen.main }
        let window = windowFrame.size
        return XCUIScreen.screens.first { screen in
            let size = screen.screenshot().image.size
            return abs(size.width - window.width) < 1 && abs(size.height - window.height) < 1
        } ?? XCUIScreen.main
    }

    /// The system's back button of the page on top (`BackButton`): in its navigation bar, or on the iPhone Duo in the
    /// bar the system lays along the screen's side, a toolbar there (iOS 27.1).
    var backButton: XCUIElement { app.buttons.matching(identifier: "BackButton").firstMatch }

    /// The part of the window beside iOS 27.1's vertical bar, which the content runs under: the window short of the bar's
    /// tabs where they stand one under another along a side (the iPhone Duo), else the whole window. Under the bar's
    /// status bar, above the tabs, a shortcut chip's label read cut (the cover in portrait).
    var besideVerticalBar: CGRect {
        let window = windowFrame
        let tabs = Self.phoneTabs.map { app.buttons.matching(identifier: $0).firstMatch }.filter(\.exists).map(\.frame)
        guard tabs.count > 1, abs(tabs[0].midX - tabs[1].midX) < 1 else { return window }
        let bar = tabs.reduce(CGRect.null) { $0.union($1) }
        return bar.midX > window.midX
            ? CGRect(x: window.minX, y: window.minY, width: bar.minX - window.minX, height: window.height)
            : CGRect(x: bar.maxX, y: window.minY, width: window.maxX - bar.maxX, height: window.height)
    }

    /// How many poses, screenshots and contrast settings (`pose-<n>`, `screen-<n>`, `contrast-<n>`) the tests have asked
    /// the hook for in this run: the run's watcher takes each event's name once.
    private static var simulatorAsks = 0

    /// Asks the run's hook (`TORTUGA_EXCHANGE`) for `what` of the simulator (its pose or `screen`, or an
    /// Increase Contrast setting) as the event `<kind>-<n>` (`<what> <exchange>`) and waits up to 60 s for its answer in
    /// `<exchange>/replies/<kind>-<n>`: that file once it reads `ok …`, else why not. Written with `.atomic`, not
    /// `publish`, whose own failure would come back here through the failure's screenshot.
    private func askSimulatorHook(_ kind: String, _ what: String) -> Result<URL, HarnessError> {
        guard let path = ProcessInfo.processInfo.environment["TORTUGA_EXCHANGE"], !path.isEmpty else {
            return .failure(HarnessError("TORTUGA_EXCHANGE is not set: the simulator's \(kind) needs the hook's exchange folder as TEST_RUNNER_TORTUGA_EXCHANGE"))
        }
        let exchange = URL(fileURLWithPath: path, isDirectory: true)
        Self.simulatorAsks += 1
        let event = "\(kind)-\(Self.simulatorAsks)"
        do {
            try FileManager.default.createDirectory(at: exchange, withIntermediateDirectories: true)
            try Data("\(what) \(exchange.path)".utf8).write(to: exchange.appendingPathComponent(event), options: .atomic)
        } catch {
            return .failure(HarnessError("can't publish \(event) in \(exchange.path): \(error)"))
        }
        let reply = exchange.appendingPathComponent("replies/\(event)")
        var answer = ""
        let answered = waitUntil(60) {
            answer = (try? String(contentsOf: reply, encoding: .utf8)) ?? ""
            return !answer.isEmpty
        }
        if !answered { return .failure(HarnessError("the run's hook didn't answer \(event) (\(what)) within 60 s")) }
        if !answer.hasPrefix("ok ") { return .failure(HarnessError("the simulator didn't take \(what): \(answer)")) }
        return .success(reply)
    }

    /// Puts the foldable simulator (the iPhone Duo) in `pose`, as Device Hub's pose controls do: `open` (the app on the
    /// inner screen), `closed` (on the cover screen), or the device turned, `portrait`, `landscape-left` or
    /// `landscape-right` (XCUIDevice's orientation turns nothing on the Duo), once the simulator shows it
    /// (`askSimulatorHook`); then, while the app runs, waits up to 10 s for it to follow: the screen it is on settled. A
    /// test that poses before launching has no app to wait for, whose windows a query can't resolve.
    func pose(_ pose: String) {
        if case .failure(let error) = askSimulatorHook("pose", pose) { return fail(error.description) }
        if app.state == .runningForeground { screenSettles(appScreen, timeout: 10) }
    }

    /// Puts the foldable back in the run's pose (`TORTUGA_POSE`; else open and upright), for the
    /// tests after one that posed it.
    func poseBack() {
        let poses = ProcessInfo.processInfo.environment["TORTUGA_POSE"].flatMap { $0.isEmpty ? nil : $0 } ?? "open,portrait"
        for pose in poses.split(separator: ",") { self.pose(String(pose)) }
    }

    /// The screen as a viewer sees it, upright, as a PNG: on a foldable the simulator's screenshot of the screen the app
    /// is on (`askSimulatorHook`'s `screen`), since XCUIScreen.main is the cover screen there whichever screen the app
    /// is on and the app's and the inner screen's own screenshots often read black; elsewhere, or when the hook can't,
    /// the main screen's.
    func screenPNG() -> Data {
        if isFoldable, case .success(let reply) = askSimulatorHook("screen", "screen"),
           let png = try? Data(contentsOf: reply.appendingPathExtension("png")) {
            return png
        }
        return XCUIScreen.main.screenshot().pngRepresentation
    }

    /// Waits for the screen of the tab `id`; fails with `what` when it doesn't show. The tabs name the destination
    /// (iPhone's tab bar, iPad's floating tab bar or sidebar), so its screen is told by what only it shows (the Board's
    /// shelf headings, loaded or not, Discover's catalog menu, Search's field, Library's view menu, Settings' Playback
    /// row), with no title repeating the tab's name and on iPhone no navigation bar at all; but Settings, a settings page
    /// as iOS has them, whose navigation bar is titled with the tab's name.
    func requireTabScreen(_ id: String, _ what: String) {
        // By its place when iOS 27 left the tab bar's buttons without their identifiers (`phoneTab`).
        let label = phoneTab(id).label
        let screens = [
            "nav.discover": "discover.catalog", "nav.search": "search.field", "nav.library": "library.view",
            "nav.settings": "settings.section.playback",
        ]
        if let content = screens[id] {
            require(content, timeout: 30, what)
        } else if !app.scrollViews.staticTexts.firstMatch.exists(within: 30) {
            fail("\(what): the Board shows no shelf")
        }
        let titled = app.navigationBars.staticTexts.matching(NSPredicate(format: "label == %@", label)).count > 0
        if id == "nav.settings" {
            if !titled { fail("\(what): Settings' navigation bar isn't titled \(label)") }
            return
        }
        if titled {
            fail("\(what): the screen repeats the tab's name \(label) as its title")
        }
        if !isPad, app.navigationBars.firstMatch.exists {
            fail("\(what): the screen shows a navigation bar under the status bar")
        }
    }

    /// Opens a page over the screen shown with `open` and goes back, once by the page's back button and once by a swipe
    /// from the screen's leading edge, and requires the screen where it was before: `anchor`'s line, which Vision reads
    /// as holding `label`, at its height in every frame sampled from just before going back until the screen has
    /// settled (`ScreenSampler`) that shows it near its place, and `anchor` at its frame once back. A page whose
    /// navigation bar comes and goes over a screen without one moved that screen's content as it went (iPhone:
    /// Settings back from a section). A screen without a title of its own (`untitled`: the tab's name) shows it in none
    /// of those frames: the bar going over the screen kept in place showed it across the line. The first frames of
    /// each, and every one that shows the line moved or the name, are kept as `<what> <button|swipe> <n>`.
    func requireStaysPut(_ anchor: XCUIElement, label: String, _ what: String, untitled name: String? = nil, open: () -> Void) {
        screenSettles(appScreen)
        let place = anchor.frame
        let window = app.windows.firstMatch
        // The line's band across the screen: the page's bar above it, whose title may read as the line does, is out.
        let band = CGRect(x: 0, y: place.minY - 80, width: window.frame.width, height: place.height + 160)
        let read = { (frame: XCUIScreenshot) in frame.texts(in: band).filter { $0.text.contains(label) }.map(\.frame.midY) }
        let screen = appScreen
        guard let rest = read(screen.screenshot()).min(by: { abs($0 - place.midY) < abs($1 - place.midY) }) else {
            return fail("\(what): \(label) isn't read near y \(Int(place.midY)) before the page opens")
        }
        var problems: [String] = []
        for way in ["button", "swipe"] {
            open()
            let back = ready(backButton)
            screenSettles(screen)
            let sampler = ScreenSampler(of: screen)
            sampler.start()
            if way == "button" {
                back.tap()
            } else {
                let edge = window.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0.5)).withOffset(CGVector(dx: 2, dy: 0))
                edge.press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)), withVelocity: .slow, thenHoldForDuration: 0)
            }
            if !waitUntil(5, { !back.exists }) || !anchor.exists(within: 5) {
                return fail("\(what): a \(way) back from the page doesn't return to \(anchor)")
            }
            screenSettles(screen)
            let frames = sampler.stop()
            var moved: [String] = []
            var named: [Int] = []
            for (index, frame) in frames.enumerated() {
                let texts = frame.texts(in: band)
                let away = texts.filter { $0.text.contains(label) }.map(\.frame.midY).filter { abs($0 - rest) > 3 }
                if !away.isEmpty { moved.append("frame \(index + 1) at y \(away.map { Int($0) })") }
                let titled = name.map { name in texts.contains { $0.text.contains(name) } } ?? false
                if titled { named.append(index + 1) }
                if index < 16 || !away.isEmpty || titled {
                    let attachment = XCTAttachment(screenshot: frame)
                    attachment.name = String(format: "%@ %@ %02d", what, way, index + 1)
                    attachment.lifetime = .keepAlways
                    add(attachment)
                }
            }
            if !moved.isEmpty {
                problems.append("back by a \(way), \(label) (y \(Int(rest)) before) moved: \(moved.joined(separator: ", ")) of \(frames.count) frames")
            }
            if let name, !named.isEmpty {
                problems.append("back by a \(way), \(name) shows over the screen in frames \(named) of \(frames.count)")
            }
            if abs(anchor.frame.minY - place.minY) > 1 {
                problems.append("back by a \(way), \(label)'s line is at y \(Int(anchor.frame.minY)), not at y \(Int(place.minY)) where it was")
            }
        }
        if !problems.isEmpty { fail("\(what): \(problems.joined(separator: "; "))") }
    }

    /// Drags a sheet over the player (`panel`: the playback info's `player.info.panel`, the room's
    /// `player.watch.panel`) a step at a time until `target` (an `infoRow`, a section's heading, a room's row) shows
    /// whole, not cut off at the list's edges. Its list makes rows only as they scroll in, and in landscape (the iPhone's
    /// player) the sheet shows a few at a time: a row the list has made is dragged toward from where it lies; one it
    /// hasn't is looked for down the list first (a drag up), then, once the list's end stops it moving, up the list.
    /// The half-height sheet first goes to full height, dragged by its top to the screen's, as a person pulls it up: a
    /// drag or a swipe in its list would only lift it part of the way, and an iPad's sheet springs back from there.
    /// Slow drags held at their end, so the list doesn't fling past what it looks for. Fails with `what` when it doesn't
    /// come.
    @discardableResult
    func revealInPlayerSheet(_ target: XCUIElement, panel id: String, _ what: String) -> XCUIElement {
        let panel = element(id)
        let shown = { target.exists && target.isHittable && panel.frame.contains(target.frame) }
        if !shown(), panel.frame.minY > app.frame.height / 3 {
            panel.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0)).withOffset(CGVector(dx: 0, dy: 14)).press(
                forDuration: 0.05,
                thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.02)),
                withVelocity: .slow,
                thenHoldForDuration: 0.2
            )
        }
        // Where the list's rows lie: the same after a drag the list's end stopped.
        let rows = { (try? panel.snapshot().children.map(\.frame)) ?? [] }
        var downTheList = true
        // Stops once it shows: a `where` on the loop would still look at it once per step left, a second each.
        for _ in 0..<30 {
            if shown() { break }
            if target.exists { downTheList = target.frame.midY > panel.frame.midY }
            let before = rows()
            let (from, to): (CGFloat, CGFloat) = downTheList ? (0.8, 0.45) : (0.45, 0.8)
            panel.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: from)).press(
                forDuration: 0.05,
                thenDragTo: panel.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: to)),
                withVelocity: .slow,
                thenHoldForDuration: 0.2
            )
            if !target.exists, rows() == before {
                // Both ends met: the list doesn't hold it.
                if !downTheList { break }
                downTheList = false
            }
        }
        if !shown() { fail("\(what): not on screen in \(id) after dragging its list both ways") }
        return target
    }

    /// Brings the player's controls up with a tap on the video; fails with `what` when they don't come. While the
    /// video plays or buffers they hide 5 s after the last input, which can fall between a look at them and the next
    /// tap: shown over a playing video, they are let go first, so the tap brings them up for the full 5 s. Paused, they
    /// stay. While the stream resolves or opens they stay too, though play/pause reads Pause, and their 5 s start once
    /// it has opened: the wait for them to go starts once `player.loading` (resolving, opening or buffering) has gone.
    func showPlayerControls(_ video: XCUIElement, _ what: String) {
        let upWhilePlaying = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'player.playPause' AND label != 'Play'")).firstMatch
        if upWhilePlaying.exists {
            requirePlayerLoaded(what)
            if !waitUntil(8, { !upWhilePlaying.exists }) { fail("\(what): the controls stay up while the video plays") }
        }
        if !element("player.playPause").exists { ready(video).tap() }
        require("player.playPause", timeout: 5, what)
    }

    /// Taps the player's control `id`, the controls brought up right before the tap, until `done` holds. While the video
    /// plays they hide 5 s after the last input, which the queries of a slow simulator can outlast: the tap then lands
    /// on the video where the control was and brings the controls up instead, and it is tried again. Fails with `what`
    /// when three tries don't do it.
    ///
    /// The tap goes to the control's centre, where a finger lands, not to XCUITest's hit point: while the player loads
    /// or buffers, play/pause shows a spinner whose accessibility elements cover its centre, and XCUITest's own tap
    /// then aims at the button's top left corner (a tenth of its size in), outside its round shape: onto the video,
    /// where a tap shows or hides the controls instead (`testOptionsMenuFitZoomAndPinchInLandscape`, the clip
    /// buffering).
    func tapPlayerControl(_ id: String, video: XCUIElement, until done: () -> Bool, _ what: String) {
        for _ in 0..<3 {
            showPlayerControls(video, what)
            // Read once: a control gone by now has no frame, and the next try brings it back.
            guard let frame = try? element(id).snapshot().frame else { continue }
            app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX, dy: frame.midY)).tap()
            if waitUntil(5, done) { return }
        }
        fail(what)
    }

    /// Pauses the playing video with the controls' play/pause button (`tapPlayerControl`). Fails with `what` when three
    /// tries don't pause.
    func pauseWithTheControls(_ video: XCUIElement, _ what: String) {
        // Read by a snapshot: after a tap that missed, the controls hide again while this waits.
        let paused = { (try? self.element("player.playPause").snapshot().label) == "Play" }
        tapPlayerControl("player.playPause", video: video, until: paused, what)
    }

    /// Closes the player by its back button (`tapPlayerControl`); fails with `what` when the player stays.
    func closePlayer(_ video: XCUIElement, _ what: String) {
        tapPlayerControl("player.back", video: video, until: { !video.exists }, what)
    }

    /// Back from a pushed page: its back button (`backButton`).
    func goBack() {
        ready(backButton).tap()
    }
    #endif

    #if !os(tvOS)
    /// The gestures as the failures name them: a tap (a click), and the long press (right click) that opens an item's
    /// menu.
    #if os(macOS)
    static let tapping = "a click", menuGesture = "right click"
    #else
    static let tapping = "a tap", menuGesture = "long press"
    #endif

    /// An item's menu: a long press on iOS, a right click on the Mac.
    func openItemMenu(_ element: XCUIElement) {
        #if os(macOS)
        ready(element).rightClick()
        #else
        ready(element).press(forDuration: 1.2)
        #endif
    }
    #endif

    #if !os(macOS)
    /// Turns Increase Contrast (Settings → Accessibility) on or off: on iOS the simulator's setting through the hook
    /// (`askSimulatorHook`, `simctl ui … increase_contrast`); on tvOS, whose simulator runtime `simctl` can't set it on,
    /// the app's own contrast trait through the DEBUG link `tortuga://dev-increase-contrast?on=<true|false>`
    /// (`openFromOutside`). Then waits up to 5 s for the screen to change and settle: the app redrawn in the setting's
    /// colours (a screen with nothing that follows the setting stays as it was, and the wait ends at 5 s). A test that
    /// turns it on turns it off in its teardown.
    func increaseContrast(_ on: Bool) {
        let before = XCUIScreen.main.screenshot().pngRepresentation
        #if os(tvOS)
        openFromOutside(URL(string: "tortuga://dev-increase-contrast?on=\(on)")!)
        #else
        if case .failure(let error) = askSimulatorHook("contrast", on ? "enabled" : "disabled") { return fail(error.description) }
        #endif
        if waitUntil(5, { XCUIScreen.main.screenshot().pngRepresentation != before }) { screenSettles(XCUIScreen.main) }
    }

    /// Fails unless `text`, secondary text (`Palette.onSurfaceVariant`), stands out more from what is behind it with
    /// Increase Contrast on than off (`ScreenPixels.contrast` over its frame, a shot each way), as the system's secondary
    /// label does (60 % white, 70 % with Increase Contrast: about 1.35 times the contrast over black). At least 1.2
    /// times: the iOS sheet's own background darkens with Increase Contrast too, about 1.1 times with the text as it is
    /// (6.01:1 against 5.47:1). Keeps both values and both shots with the run; Increase Contrast goes off again
    /// at the test's end.
    func requireFollowsIncreaseContrast(_ text: XCUIElement, _ what: String) {
        addTeardownBlock { self.increaseContrast(false) }
        let rect = text.frame
        shot("\(what), Increase Contrast off")
        guard let off = ScreenPixels() else { return fail("\(what): no screenshot to measure the text on") }
        increaseContrast(true)
        shot("\(what), Increase Contrast on")
        guard let on = ScreenPixels() else { return fail("\(what): no screenshot with Increase Contrast to measure the text on") }
        let (before, after) = (off.contrast(in: rect), on.contrast(in: rect))
        let values = "\(what) in \(rect): " + String(format: "%.2f:1 with Increase Contrast, %.2f:1 without", after, before)
        let attachment = XCTAttachment(string: values)
        attachment.name = "contrast: \(what)"
        attachment.lifetime = .keepAlways
        add(attachment)
        if after < before * 1.2 { fail("\(values): not 1.2 times as much") }
    }
    #endif

    #if os(macOS)
    /// Back from a pushed screen: the window toolbar's back button, else Command-[.
    func goBack() {
        let toolbar = app.toolbars.buttons.matching(NSPredicate(format: "label == 'Back' OR identifier == 'Back'")).firstMatch
        if toolbar.exists {
            ready(toolbar).click()
        } else {
            app.typeKey("[", modifierFlags: .command)
        }
    }

    /// The window is full screen: it spans the screen's width down to its bottom edge, from the top edge or from below
    /// a band the menu bar keeps (`keepMenuBarInFullScreen`); a window here is narrower.
    var isFullScreen: Bool {
        let window = app.windows.firstMatch
        guard window.exists, let screen = NSScreen.screens.first?.frame else { return false }
        return window.frame.minX == 0 && window.frame.width == screen.width && window.frame.maxY == screen.height
    }

    /// Waits up to 15 s for the window to be full screen (`on`) or not, then for AppKit's transition to end (the frame
    /// unchanged for a second); fails with `what` when it doesn't come.
    func requireFullScreen(_ on: Bool, _ what: String) {
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

    /// Search's field, which is the screen's header row on the toolbar's band (its centre on the line of the window's
    /// buttons, no band of its own above the content) and not in the toolbar; fails with `what` when it doesn't show or
    /// sits elsewhere.
    @discardableResult
    func requireHeaderSearchField(_ what: String) -> XCUIElement {
        let field = require("search.field", what)
        let buttons = app.windows.firstMatch.buttons[XCUIIdentifierCloseWindow].frame
        if abs(field.frame.midY - buttons.midY) > 4 {
            fail("\(what): the search field (y \(Int(field.frame.midY))) isn't on the toolbar's band, the line of the window's buttons (y \(Int(buttons.midY)))")
        }
        if app.toolbars.searchFields.count + app.toolbars.textFields.count > 0 { fail("\(what): the toolbar holds a field") }
        return field
    }

    /// Empties the search field `field` from the keyboard (Command-A, Delete).
    func clearSearchField(_ field: XCUIElement) {
        ready(field).click()
        field.typeKey("a", modifierFlags: .command)
        field.typeKey(.delete, modifierFlags: [])
    }

    #endif

    #if !os(tvOS)
    /// The app's window: its largest (on iPad the first one XCUITest lists can be an empty one).
    var windowFrame: CGRect {
        app.windows.allElementsBoundByIndex.map(\.frame).max { $0.width * $0.height < $1.width * $1.height } ?? .zero
    }

    /// The app's window as an element, the largest as `windowFrame` reads it. A gesture on iOS that isn't aimed at an
    /// element goes to it, not to the app: on the iPhone Duo's inner screen XCUITest (Xcode 27.1) sends a swipe on the
    /// app to the cover screen, where it moves nothing, and one on the window to the screen the app is on.
    var appWindow: XCUIElement {
        let frames = app.windows.allElementsBoundByIndex.map(\.frame)
        let largest = frames.indices.max { frames[$0].width * frames[$0].height < frames[$1].width * frames[$1].height }
        return app.windows.element(boundBy: largest ?? 0)
    }

    /// Scrolls until `target` lies inside the window, where a tap, click or drag aimed at it lands on it, and returns it;
    /// fails at the caller's line with `what`, naming `target`, its frame and the window's, when `steps` scrolls don't
    /// bring it there. Inside: all of its frame between the window's top and bottom edges, within `container`'s too and
    /// above iPhone's tab bar where the bar floats over `target`'s screen (`tabBarTop`), and XCUITest finds it hittable.
    /// Hittable alone isn't inside: on the Mac it holds past the window's bottom edge, where a click lands on the
    /// window's resize edge (a Board card at y 784–977 in a window ending at 883), and an element can be in
    /// the tree below the window (Home's shelves are), where a drag at it misses the screen (at y 1038 on an
    /// 874 pt iPhone). Under iPhone's tab bar the bar takes the tap or press (Settings' rows past Trackers lay
    /// under the bar's 791–874 or below the window once the list's large title moved them down). Sideways it neither
    /// checks nor scrolls: an element a row cuts at the window's side (a shelf's card, a season chip) is that row's to
    /// scroll, which XCUITest's tap and click do themselves.
    /// Each scroll goes toward `target` where it is there (up while it lies above, or on iOS under a bar over the top of
    /// its screen, which takes the tap: its navigation bar, iPad's tab bar), else down (up with `up`). Where it is
    /// there, by the distance it lies outside or under the bar, a margin added, at most half the part it must lie in:
    /// on iOS a drag held at its end on `container` (default: the app's window, `appWindow`), on the Mac the wheel
    /// turned that much. A swipe flings, and the wheel's fixed steps carry, past a target in a list shorter than their
    /// throw, to the list's other end (a landscape iPhone's Streams sheet over the player, a 276 pt list: its
    /// row 4 above the list's top at one end, out of the tree at the other, for 15 swipes). Where it isn't there yet:
    /// on iOS a swipe on `container`, on the Mac the wheel turned 300 pt over `container` (default: the middle of the
    /// window's content, right of the sidebar; a scroll sent to the window element scrolls nothing).
    @discardableResult
    func scrollTo(
        _ target: XCUIElement, over container: XCUIElement? = nil, up: Bool = false, steps: Int = 15, _ what: String,
        file: StaticString = #filePath, line: UInt = #line
    ) -> XCUIElement {
        if scrolledInside(target, over: container, up: up, steps: steps) { return target }
        let window = windowFrame
        let shown = try? target.snapshot()
        let inside = clearBand(over: shown, within: container, in: window)
        let name = shown.map { $0.identifier.isEmpty ? "'\($0.label)'" : $0.identifier } ?? target.description
        let at = shown.map { "at \($0.frame)" } ?? "not there"
        fail(
            "\(what): \(name) isn't inside the window after \(steps) scrolls: it is \(at), the window at \(window), "
                + "the part it must lie in from y \(Int(inside.lowerBound)) to \(Int(inside.upperBound))",
            file: file, line: line
        )
        return target
    }

    /// `scrollTo` without its failure: whether `steps` scrolls brought `target` inside the window.
    @discardableResult
    func scrolledInside(_ target: XCUIElement, over container: XCUIElement? = nil, up: Bool = false, steps: Int = 15) -> Bool {
        let window = windowFrame

        for step in 0...steps {
            // Read at each step: a sheet's list moves as it is pulled up.
            let shown = try? target.snapshot()
            let inside = clearBand(over: shown, within: container, in: window)
            var towardTop = up
            // How far the target lies outside `inside`, toward the scroll; nil while it isn't there.
            var outside: CGFloat?
            if let shown {
                if lies(target, shown, inside: inside) { return true }
                if shown.frame.minY < inside.lowerBound {
                    towardTop = true
                    outside = inside.lowerBound - shown.frame.minY
                } else if shown.frame.maxY > inside.upperBound {
                    towardTop = false
                    outside = shown.frame.maxY - inside.upperBound
                }
                #if os(iOS)
                // Settings → Playback scrolled to its end left the Streams group's first row under the
                // navigation bar, inside the window but out of a tap's reach.
                if outside == nil, let bar = topBarsBottom(over: shown, in: window), shown.frame.minY < bar {
                    towardTop = true
                    outside = bar - shown.frame.minY
                }
                #endif
            }
            if step == steps { break }
            // The margin covers the few points a scroll view takes before it moves.
            let span = inside.upperBound - inside.lowerBound
            let distance = outside.map { min($0 + 24, span / 2) }
            #if os(macOS)
            let delta = (towardTop ? 1 : -1) * (distance ?? 300)
            if let container {
                container.scroll(byDeltaX: 0, deltaY: delta)
            } else {
                app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.6)).scroll(byDeltaX: 0, deltaY: delta)
            }
            #else
            let surface = container.map { ready($0) } ?? appWindow
            if let distance {
                // Centred on the band's middle, clear of a navigation bar over its top; held, so the list doesn't fling on.
                let middle = (inside.lowerBound + inside.upperBound) / 2 - surface.frame.minY
                let half = (towardTop ? distance : -distance) / 2
                let origin = surface.coordinate(withNormalizedOffset: .zero)
                let start = origin.withOffset(CGVector(dx: surface.frame.width / 2, dy: middle - half))
                let end = origin.withOffset(CGVector(dx: surface.frame.width / 2, dy: middle + half))
                start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.3)
            } else if towardTop {
                surface.swipeDown()
            } else {
                surface.swipeUp()
            }
            #endif
        }
        return false
    }

    /// Whether `target` lies inside the window as `scrollTo` leaves it, where a tap, click or drag aimed at it lands on
    /// it; false while it isn't there.
    func liesInside(_ target: XCUIElement) -> Bool {
        guard let shown = try? target.snapshot() else { return false }
        return lies(target, shown, inside: clearBand(over: shown, within: nil, in: windowFrame))
    }

    /// `scrollTo`'s inside: all of `target`'s frame (read as `shown`) within `band`, and XCUITest finds it hittable. The
    /// frame first: isHittable took 18 s on a row a form's edge cut.
    private func lies(_ target: XCUIElement, _ shown: any XCUIElementSnapshot, inside band: ClosedRange<CGFloat>) -> Bool {
        shown.frame.minY >= band.lowerBound - 0.5 && shown.frame.maxY <= band.upperBound + 0.5 && target.isHittable
    }

    /// The window's part, top to bottom, where all of an element (read as `shown`) must lie: within `container`'s frame
    /// too, above iPhone's tab bar where the bar floats over the element's screen (`tabBarTop`), and on iOS above the
    /// software keyboard, which takes a tap aimed at what lies under it (the iPhone Duo's cover: the languages' first
    /// search result).
    private func clearBand(over shown: (any XCUIElementSnapshot)?, within container: XCUIElement?, in window: CGRect) -> ClosedRange<CGFloat> {
        var top = window.minY, bottom = window.maxY
        if let container, container.exists {
            top = max(top, container.frame.minY)
            bottom = min(bottom, container.frame.maxY)
        }
        #if os(iOS)
        if let shown, let bar = tabBarTop(over: shown, in: window) { bottom = min(bottom, bar) }
        let keyboard = app.keyboards.firstMatch
        if keyboard.exists, keyboard.frame.minY > window.minY { bottom = min(bottom, keyboard.frame.minY) }
        #endif
        return top...max(top, bottom)
    }

    #if os(iOS)
    /// The top of iPhone's tab bar where it floats over `shown`'s screen, else nil: the tab bar in the window's layer
    /// that holds `shown` (`screenLayer`), its top below the window's middle (iPad's tab bar floats at the top).
    private func tabBarTop(over shown: any XCUIElementSnapshot, in window: CGRect) -> CGFloat? {
        guard let layer = screenLayer(holding: shown), let bar = firstNode(in: layer, where: { $0.elementType == .tabBar }),
              bar.frame.minY > window.midY
        else { return nil }
        return bar.frame.minY
    }

    /// The bottom of the bars over the top of `shown`'s screen, else nil: its navigation bar and iPad's floating tab
    /// bar, those in the window's layer that holds `shown` (`screenLayer`) whose bottom lies above the window's middle.
    /// A tap aimed at what lies under them lands on them.
    private func topBarsBottom(over shown: any XCUIElementSnapshot, in window: CGRect) -> CGFloat? {
        guard let layer = screenLayer(holding: shown) else { return nil }
        var bottom: CGFloat?
        func visit(_ node: any XCUIElementSnapshot) {
            if [.navigationBar, .tabBar].contains(node.elementType) {
                if node.frame.maxY < window.midY { bottom = max(bottom ?? node.frame.maxY, node.frame.maxY) }
                return
            }
            node.children.forEach(visit)
        }
        visit(layer)
        return bottom
    }

    /// The layer of the window that holds `shown`, read afresh: a tab's screen and the pages pushed in it; a sheet or
    /// the player is a layer of its own, over the tab bar.
    private func screenLayer(holding shown: any XCUIElementSnapshot) -> (any XCUIElementSnapshot)? {
        guard let root = try? app.snapshot() else { return nil }
        return root.children.flatMap(\.children).first { layer in
            firstNode(in: layer) {
                $0.elementType == shown.elementType && $0.identifier == shown.identifier && $0.frame == shown.frame
                    && $0.label == shown.label
            } != nil
        }
    }

    /// The first node of `node`'s tree, `node` itself first, that `match` takes.
    private func firstNode(in node: any XCUIElementSnapshot, where match: (any XCUIElementSnapshot) -> Bool) -> (any XCUIElementSnapshot)? {
        if match(node) { return node }
        for child in node.children {
            if let found = firstNode(in: child, where: match) { return found }
        }
        return nil
    }
    #endif

    /// `scrollTo(element(id))`: the element with accessibility identifier `id`, scrolled inside the window.
    @discardableResult
    func scrollTo(_ id: String, _ what: String, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        scrollTo(element(id), what, file: file, line: line)
    }

    /// Scrolls `row`, a row of chips scrolling sideways (Home's shortcuts, the Streams list's addons), until all of
    /// `target` lies inside it where it runs past an end (an iPhone's chips; on iOS the part beside the vertical bar,
    /// `besideVerticalBar`), by as much as it lies past that end, a margin added, at most half the row: a drag held at
    /// its end on iOS, the wheel turned sideways over the row on the Mac (a swipe flings, and fixed steps carry, past a
    /// chip in a row shorter than their throw: `scrollTo`).
    func scrollAlong(_ row: XCUIElement, toShow target: XCUIElement) {
        for _ in 0..<6 {
            #if os(iOS)
            let visible = row.frame.intersection(besideVerticalBar)
            #else
            let visible = row.frame.intersection(windowFrame)
            #endif
            let frame = target.frame
            if frame.minX >= visible.minX - 0.5, frame.maxX <= visible.maxX + 0.5, target.isHittable { return }
            // Past the row's trailing end: its chips move toward the leading one.
            let pastTheEnd = frame.midX > visible.midX
            let outside = pastTheEnd ? frame.maxX - visible.maxX : visible.minX - frame.minX
            let distance = min(max(outside, 0) + 24, visible.width / 2)
            #if os(macOS)
            row.scroll(byDeltaX: pastTheEnd ? -distance : distance, deltaY: 0)
            #else
            let middle = row.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0.5))
                .withOffset(CGVector(dx: visible.midX - row.frame.minX, dy: 0))
            let half = (pastTheEnd ? -distance : distance) / 2
            middle.withOffset(CGVector(dx: -half, dy: 0))
                .press(forDuration: 0.05, thenDragTo: middle.withOffset(CGVector(dx: half, dy: 0)), withVelocity: .slow, thenHoldForDuration: 0.3)
            #endif
        }
    }
    #endif

    #if os(tvOS)
    /// The element that has focus.
    var focused: XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
    }

    /// The identifier of the element that has focus, or nil when nothing has.
    var focusedId: String? {
        (try? focused.snapshot())?.identifier
    }

    /// Whether `id` has focus: the element itself, or the focused element around it (a form's row takes focus as a
    /// whole on tvOS, the control inside it keeps the identifier), or one inside it. Each element is read once, as a
    /// snapshot: one that goes while it is read (the skip button as its chapter ends) has no focus, not a failed query.
    func holdsFocus(_ id: String) -> Bool {
        guard let target = try? element(id).snapshot() else { return false }
        if target.hasFocus || target.focusInside { return true }
        guard let here = try? focused.snapshot() else { return false }
        return here.frame.insetBy(dx: -4, dy: -4).contains(target.frame) && !here.frame.isEmpty
            && here.frame.height < app.frame.height / 2
    }

    /// Focus is on one of the tab bar's buttons.
    var focusInTabBar: Bool {
        app.tabBars.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).count > 0
    }

    /// Waits for the system alert up (`confirmation`), which shows `id`, to take focus. It takes it about half a second
    /// after it shows; a press of the remote before then moves focus on the screen under it, where it stays, behind the
    /// alert and out of the remote's reach (tvOS 26.5: Down to Delete account, Select, then Up 0.8 s later).
    /// Fails with `what` when `id` doesn't show or the alert doesn't take focus.
    func requireDialogFocus(_ id: String, _ what: String) {
        require(id, timeout: 5, what)
        let inAlert = app.alerts.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
        if !inAlert.exists(within: 5) { fail("\(what): the question doesn't take focus, which stays on \(focusedId ?? "nothing")") }
    }

    /// Moves focus to `id`, a button of the system alert up, once the alert has taken focus (`requireDialogFocus`):
    /// through its buttons, side by side or one above the other.
    func focusDialogButton(_ id: String, _ what: String) {
        requireDialogFocus(id, what)
        for button in [XCUIRemote.Button.right, .right, .down, .down, .up, .up, .left, .left] where !holdsFocus(id) {
            press(button)
        }
        if !holdsFocus(id) { fail("\(what): focus is on \(focusedId ?? "nothing")") }
    }

    /// Presses the remote's `button` `count` times, each once the focus engine has settled from the one before: the
    /// focused element's frame (or no focus) read the same twice in a row, up to 3 s.
    func press(_ button: XCUIRemote.Button, count: Int = 1) {
        for _ in 0..<count {
            XCUIRemote.shared.press(button)
            var last: CGRect??
            _ = waitUntil(3) {
                let frame = (try? focused.snapshot())?.frame
                defer { last = .some(frame) }
                return last == .some(frame)
            }
        }
    }

    /// Puts the system keyboard away with Menu, which keeps the text typed, and presses again while the keyboard stays
    /// up: a press can go astray (one went as tvOS 26's "Hold ◉ for Control Center" hint faded out after a launch, and
    /// the keyboard stayed). Nothing is pressed once the keyboard is gone, so no press closes the screen under it. Fails
    /// with `what` when three presses leave the keyboard up.
    func putKeyboardAway(_ what: String) {
        let keyboard = app.keyboards.firstMatch
        for _ in 0..<3 where keyboard.exists {
            press(.menu)
            _ = waitUntil(3) { !keyboard.exists }
        }
        if keyboard.exists { fail("\(what): three presses of Menu leave the keyboard up") }
    }

    /// Waits up to `timeout` for `id` to exist, then a few seconds for it to have focus; fails with `what` and the
    /// element that has focus when it doesn't get it.
    func requireFocus(_ id: String, timeout: TimeInterval = 20, _ what: String) {
        require(id, timeout: timeout, what)
        if !waitUntil(5, { holdsFocus(id) }) {
            fail("\(what): focus is on \(focusedId ?? "nothing"), not \(id)")
        }
    }

    /// Waits a few seconds for focus on an element whose identifier starts with `prefix`; its identifier.
    @discardableResult
    func requireFocus(prefix: String, _ what: String) -> String {
        if !waitUntil(5, { focusedId?.hasPrefix(prefix) == true }) {
            fail("\(what): focus is on \(focusedId ?? "nothing"), not \(prefix)…")
        }
        return focusedId ?? ""
    }

    /// Presses `button` until `id` has focus, at most `limit` times; how many presses it took. The element may not be
    /// there yet: a form's rows below the screen come as focus scrolls them in.
    @discardableResult
    func moveFocus(to id: String, with button: XCUIRemote.Button, limit: Int = 8, _ what: String) -> Int {
        _ = element(id).exists(within: 10)
        for presses in 0...limit {
            if holdsFocus(id) { return presses }
            if presses < limit { press(button) }
        }
        fail("\(what): \(limit) presses left focus on \(focusedId ?? "nothing"), not \(id)")
        return limit
    }

    /// Moves focus into the shelf `shelf` (`board.row.0`, `search.group.1`, `board.cw`) with one press of `button`, once
    /// the shelf shows its cards (`requireShelfContent`): pressed before its catalog answers, the press finds no card
    /// there and focus goes past it or stays (Down from Home's shortcuts before Popular's shelf loaded). Fails
    /// with `what` when focus doesn't land on one of its cards; the focused card's identifier.
    @discardableResult
    func focusIntoShelf(_ shelf: String, with button: XCUIRemote.Button, _ what: String) -> String {
        requireShelfContent(shelf, what)
        press(button)
        let isCard = { (id: String) in
            id.hasPrefix("\(shelf).item.") || (id.hasPrefix("\(shelf).") && Int(id.dropFirst(shelf.count + 1)) != nil)
        }
        if !waitUntil(5, { focusedId.map(isCard) == true }) {
            fail("\(what): focus is on \(focusedId ?? "nothing"), not a card of \(shelf)")
        }
        return focusedId ?? ""
    }

    /// Walks focus over the player's controls to `id` by the remote, frame to frame as `walkFocus` does (Up from the
    /// timeline onto the transport bar, then along it), and returns with `id` focused for the press that follows. The
    /// controls hide 5 s after the last press while the video plays or buffers, which the reads between two presses
    /// can outlast on a loaded simulator; hidden, Left and Right seek, Up and Down only bring them back and Select
    /// pauses. So before each press, and before it returns, controls that have gone are brought back by Up, which puts
    /// focus on the timeline (a live channel without a window: play/pause), and the walk goes on from there. Fails with
    /// `what` when Up doesn't bring them back there or `limit` presses don't reach `id`.
    func walkControls(to id: String, limit: Int = 12, _ what: String) {
        let scrub = element("player.scrub"), playPause = element("player.playPause"), target = element(id)
        for presses in 0...limit {
            if !scrub.exists, !playPause.exists {
                press(.up)
                let live = waitUntil(5, { scrub.exists || playPause.exists }) && playPause.exists
                requireFocus(live ? "player.playPause" : "player.scrub", timeout: 1, "\(what): Up brings the controls back")
            }
            _ = target.exists(within: 5)
            if holdsFocus(id) { return }
            if presses == limit { break }
            // Each frame read once: controls that hide meanwhile take both away, and the next turn brings them back.
            guard focused.exists(within: 5), let here = try? focused.snapshot().frame, let there = try? target.snapshot().frame else { continue }
            press(Self.direction(from: here, to: there))
        }
        fail("\(what): \(limit) presses left focus on \(focusedId ?? "nothing"), not \(id)")
    }
    #endif
}

@MainActor
extension XCUIElement {
    /// Whether the element exists now or comes within `timeout`. XCUITest's `waitForExistence` looks first a second
    /// after it starts, even for an element already there (about a quarter of the tests' time went to that second);
    /// `exists` answers at once, so the wait starts only for an element that isn't there yet. Use this, not
    /// `waitForExistence`.
    func exists(within timeout: TimeInterval) -> Bool {
        exists || waitForExistence(timeout: timeout)
    }
}

#if os(tvOS)
@MainActor
extension XCUIElementSnapshot {
    /// An element inside this one has focus.
    var focusInside: Bool {
        children.contains { $0.hasFocus || $0.focusInside }
    }
}
#endif

extension String {
    /// The text without the marks that set its direction and change nothing it says: the isolates around text the app
    /// didn't write inside its own strings (U+2066–U+2069) and the left-to-right and right-to-left marks (U+200E,
    /// U+200F) that start a line in its language's direction. What a test compares with the words it expects.
    var withoutDirectionMarks: String {
        String(unicodeScalars.filter { !(0x2066...0x2069).contains($0.value) && $0.value != 0x200E && $0.value != 0x200F })
    }
}

/// The test run can't run the test as asked (a missing environment variable).
struct HarnessError: Error, CustomStringConvertible {
    let description: String

    init(_ description: String) {
        self.description = description
    }
}
