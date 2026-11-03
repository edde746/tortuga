import XCTest

/// The Top Shelf on the tvOS home screen (the system's HeadBoard), as a person reaches it with the remote: the test
/// addon's first episode played for a few seconds and closed, Menu leaves the app for the home screen, where focus on
/// its icon in the top row shows the shelf above with the episode ("S1E1 · Tortuga Series Test", the running profile's
/// Continue Watching as the app wrote it); Up moves focus onto it (shot as "top shelf"); Select (the item's
/// display action) opens the series' Detail in the app, and Play/Pause on it (its play action) resumes the episode
/// where it stopped.
final class TopShelfTests: TortugaUITestCase {
    /// The tvOS home screen.
    private let home = XCUIApplication(bundleIdentifier: "com.apple.HeadBoard")
    /// The shelf item's label: the episode's code, then the series' name inside its direction marks.
    private let episodeCode = "S1E1 · "
    private let seriesName = "Tortuga Series Test"
    /// The app's name under its icon on the home screen (project.yml's CFBundleDisplayName).
    private let appName = "Tortuga"

    func testAPlayedEpisodeShowsOnTheTopShelfWhereSelectOpensItsDetailAndPlayResumesIt() throws {
        try launchDev(addon: true, open: ["board", "detail:series:tortuga-series", "streams:series:tortuga-series:tortuga-series:1:1"])
        requireFocus("streams.row.0", timeout: 60, "the first episode's Streams panel focuses its first stream")
        press(.select)
        require("player.video", timeout: 20, "Select on the stream opens the player")
        let played = requirePlayback(4, timeout: 90)
        for _ in 0..<3 where element("player.video").exists {
            press(.menu)
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Menu closes the player")

        focusTheShelfItem("the episode played and the player closed")
        shot("top shelf", of: XCUIScreen.main)
        XCUIRemote.shared.press(.select)
        require("detail.title", timeout: 30, "Select on the shelf's item opens its title's Detail in the app")
        if element("player.video").exists { fail("Select on the shelf's item started playback; it opens the title's Detail") }
        shot("detail from the top shelf")

        focusTheShelfItem("back from the shelf's Detail")
        XCUIRemote.shared.press(.playPause)
        require("player.video", timeout: 30, "Play/Pause on the shelf's item opens the player")
        let resumed = requirePlayback(1, timeout: 90)
        if resumed < played - 2 {
            fail("Play/Pause on the shelf's item played the episode from \(Int(resumed)) s, not where it stopped (\(Int(played)) s)")
        }
    }

    /// Leaves the app for the home screen, moves focus onto its icon (the test launched the app, so the home screen's
    /// focus stays where it was, on its first icon), waits for the shelf's item above it and moves focus onto it with Up.
    private func focusTheShelfItem(_ what: String) {
        leaveForTheHomeScreen(what)
        focusTheAppIcon(what)
        let item = requireShelfItem(what)
        pressOnHome(.up)
        if !waitUntil(5, { (try? item.snapshot())?.hasFocus == true }) {
            failOnHome("\(what): Up from the app's icon leaves focus on \(homeFocusLabel), not the shelf's \(episodeCode)\(seriesName)")
        }
    }

    /// Moves the home screen's focus onto the app's icon in the top row: Down off the shelf, then Left or Right to it.
    private func focusTheAppIcon(_ what: String) {
        let icon = home.descendants(matching: .any).matching(NSPredicate(format: "label == %@", appName)).firstMatch
        if !icon.waitForExistence(timeout: 10) { failOnHome("\(what): the home screen shows no \(appName) icon") }
        for _ in 0..<10 {
            guard let target = try? icon.snapshot(), let focused = try? homeFocused.snapshot() else { break }
            if target.hasFocus { return }
            if focused.frame.maxY < target.frame.minY {
                pressOnHome(.down)
            } else {
                pressOnHome(focused.frame.midX < target.frame.midX ? .right : .left)
            }
        }
        failOnHome("\(what): ten presses don't move the home screen's focus onto the \(appName) icon (on \(homeFocusLabel))")
    }

    /// Presses Menu until the app hands focus to the home screen (from a screen pushed on Home, through its tab bar):
    /// nothing in the app has focus, something on the home screen has.
    private func leaveForTheHomeScreen(_ what: String) {
        let onHomeScreen = { !self.focused.exists && self.homeFocused.exists }
        for _ in 0..<8 where !onHomeScreen() {
            press(.menu)
        }
        if !waitUntil(5, onHomeScreen) { failOnHome("\(what): eight presses of Menu don't leave the app for the home screen") }
    }

    /// Waits for the shelf's item, `episodeCode` and `seriesName` in its label, above the app's focused icon; fails,
    /// with what the home screen shows, when it doesn't come.
    private func requireShelfItem(_ what: String) -> XCUIElement {
        let item = home.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", episodeCode, seriesName)).firstMatch
        if !item.waitForExistence(timeout: 30) {
            failOnHome("\(what): the Top Shelf doesn't show \(episodeCode)\(seriesName) (focus on \(homeFocusLabel))")
        }
        return item
    }

    /// The home screen's focused element.
    private var homeFocused: XCUIElement {
        home.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
    }

    private var homeFocusLabel: String {
        (try? homeFocused.snapshot()).map { "'\($0.label)' (\($0.elementType.rawValue))" } ?? "nothing"
    }

    /// Presses `button` on the home screen, once its focus has settled from the press (`press` waits on the app's).
    private func pressOnHome(_ button: XCUIRemote.Button) {
        XCUIRemote.shared.press(button)
        var last: CGRect??
        _ = waitUntil(3) {
            let frame = (try? homeFocused.snapshot())?.frame
            defer { last = .some(frame) }
            return last == .some(frame)
        }
    }

    /// Fails with `message`, keeping the home screen's element tree and the screen.
    private func failOnHome(_ message: String) {
        let tree = XCTAttachment(string: home.debugDescription)
        tree.name = "home screen element tree"
        tree.lifetime = .keepAlways
        add(tree)
        shot("home screen", of: XCUIScreen.main)
        fail(message)
    }
}
