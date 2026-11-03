import XCTest

/// What every platform's `ScreenshotTour` shares: a screenshot per screen, named by its screen id (`tour-board`) with
/// a note of the state it shows (`tour-board-note`); the dev session every test of
/// the tour's dev part shares; and the account part's device-flow codes, which the run's hook signs in at the dev
/// stack's Dex.
class TourTestCase: TortugaUITestCase {
    /// The dev database the tour's dev tests share (`--dev-data`), fresh for each run of the test process: what one
    /// test leaves (the library item, the progress, the watched episode) the next one finds.
    static let tourData = "tour-" + UUID().uuidString.prefix(8)

    /// A tour launch of the dev session: the tour's database (`tourData`), the test addon, the fake Trakt, and the
    /// `open` screens.
    func launchTour(open: [String] = [], trackers: Bool = true) throws {
        // A tour test waits on many screens: more than the run's 300 s default.
        executionTimeAllowance = 900
        var arguments = ["--dev", "--dev-data", Self.tourData, "--dev-addon", try addonURL]
        if trackers { arguments += ["--dev-trackers", try trackersURL] }
        for screen in open { arguments += ["--dev-open", screen] }
        launch(arguments)
    }

    /// The screenshot of `screen` (a screen id of the tour) once the screen shows its content and has
    /// stopped moving; `note` says what state it shows. Its content: nothing on it loading (a shelf, the details, the
    /// streams, the player), for up to 40 s, past the addons' 30 s timeout. Stopped moving: two pictures alike
    /// (`screenSettles`) within the 1.5 s a shot used to wait, so a screen that keeps moving (a video playing under a
    /// menu) is shot no later than it was. A screen that moves on its own clock (up next's fill as it counts down) is
    /// shot at once (`still` false), its caller waiting for what it shows.
    func tourShot(_ screen: String, _ note: String, still: Bool = true) {
        if still {
            let loading = app.descendants(matching: .any).matching(NSPredicate(format: "identifier ENDSWITH '.loading'")).firstMatch
            _ = waitUntil(40) { !loading.exists }
            screenSettles(timeout: 1.5)
        }
        let shot = XCTAttachment(data: tourPNG(), uniformTypeIdentifier: "public.png")
        shot.name = "tour-\(screen)"
        shot.lifetime = .keepAlways
        add(shot)
        let text = XCTAttachment(data: Data(note.utf8), uniformTypeIdentifier: "public.plain-text")
        text.name = "tour-\(screen)-note"
        text.lifetime = .keepAlways
        add(text)
    }

    /// Records that the tour leaves `screen` out on this platform, and why (the run lists it in the tour's manifest).
    func tourSkip(_ screen: String, _ reason: String) {
        let text = XCTAttachment(data: Data(reason.utf8), uniformTypeIdentifier: "public.plain-text")
        text.name = "tour-\(screen)-skipped"
        text.lifetime = .keepAlways
        add(text)
    }

    /// The app as a viewer would see it: the whole screen on iPhone (on the Duo the screen the app is on), iPad and
    /// Apple TV; on the Mac the window, with any menu the app has open.
    private func tourPNG() -> Data {
        #if os(macOS)
        let screen = XCUIScreen.main.screenshot()
        var area = app.windows.firstMatch.frame
        for menu in app.menus.allElementsBoundByIndex where menu.exists && menu.frame.width > 1 && menu.frame.height > 1 {
            area = area.union(menu.frame)
        }
        guard let image = screen.image.cgImage(forProposedRect: nil, context: nil, hints: nil), !area.isEmpty else {
            return screen.pngRepresentation
        }
        let scale = CGFloat(image.width) / screen.image.size.width
        let pixels = CGRect(x: area.minX * scale, y: area.minY * scale, width: area.width * scale, height: area.height * scale)
            .integral.intersection(CGRect(x: 0, y: 0, width: image.width, height: image.height))
        guard let cropped = image.cropping(to: pixels),
              let png = NSBitmapImageRep(cgImage: cropped).representation(using: .png, properties: [:]) else {
            return screen.pngRepresentation
        }
        return png
        #elseif os(iOS)
        return screenPNG()
        #else
        return XCUIScreen.main.screenshot().pngRepresentation
        #endif
    }

    // MARK: The account part

    /// The dev stack's sync server (`TORTUGA_SERVER`).
    var tourServer: String {
        get throws { try AccountScenario.variable("TORTUGA_SERVER") }
    }

    /// The device-flow codes this test has handed to the run's hook.
    private var deviceCodes = 0

    /// Hands a device-flow code to the run's hook (`device-code`, then `device-code-2`, …), which signs it in at the
    /// stack's Dex as the tour's user. On the Mac the exchange lies in the sandboxed runner's container, which the
    /// first code creates.
    func publishDeviceCode(_ code: String) throws {
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        try FileManager.default.createDirectory(at: exchange, withIntermediateDirectories: true)
        deviceCodes += 1
        let event = deviceCodes == 1 ? "device-code" : "device-code-\(deviceCodes)"
        let temporary = exchange.appendingPathComponent(".\(event).tmp")
        try Data(code.utf8).write(to: temporary)
        if rename(temporary.path, exchange.appendingPathComponent(event).path) != 0 {
            fail("can't publish \(event): \(String(cString: strerror(errno)))")
        }
    }

    /// The profile tile named `name` (its state, locked or current, is its value).
    func profileTile(_ name: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'profiles.tile.' AND label == %@", name))
            .firstMatch
    }
}
