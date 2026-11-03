import AppKit
import XCTest

/// Player apps with the pointer on the Mac:
/// - Settings → Playback → Player: the page lists Tortuga and the players found on this Mac; Close closes it;
/// - mpv and VLC live, each in turn: with it chosen (`playback.player=mpv`, `vlc`) and every tracker connected to the
///   fake Trakt, the H.264 stream picked in the movie's Streams sheet opens in it, which reports back while it plays:
///   the details show "Playing in mpv …" and its position moving on (its window shot in front of the app's), and once
///   it quits at the 20 s file's end the line goes and the movie is watched (the fake's scrobbles, the run's
///   trackers.json, follow the playback). A player's own one-time questions on its first starts on a Mac (VLC's album
///   art and metadata, then Sparkle's automatic update checks) are answered as a user would, keeping it offline, and
///   shot first. Each player is skipped, saying so, when the Player page doesn't find it on this Mac (`brew install
///   mpv`, `brew install --cask vlc`).
final class PlayerAppsTests: TortugaUITestCase {
    func testPlayerPageListsThePlayersFound() throws {
        try launchDev()
        openPlayerPage("Settings → Playback → Player")
        shot("\(platformName)-player-page")
        ready(require("settings.player.close", "the Player page has Close")).click()
        requireGone("settings.player.option.tortuga", "Close closes the Player page")
    }

    func testMpvPlaysAndTheDetailsFollowIt() throws {
        try playsLive("mpv", name: "mpv", install: "`brew install mpv`")
    }

    func testVlcPlaysAndTheDetailsFollowIt() throws {
        // VLC's album art question on its very first start, Sparkle's update question on its second.
        try playsLive("vlc", name: "VLC", install: "`brew install --cask vlc`", firstRun: ("org.videolan.vlc", ["No, Thanks", "Don’t Check"]))
    }

    /// The player app `id`, named `name`, playing the movie's H.264 stream to its end while the details follow it (the
    /// class's comment); skipped, naming `install`, when the Player page doesn't find it. `firstRun`: the player's app
    /// (bundle identifier) and the answers to the questions it may ask on its first starts on a Mac.
    private func playsLive(_ id: String, name: String, install: String, firstRun: (bundle: String, answers: [String])? = nil) throws {
        try launchDev()
        openPlayerPage("Settings → Playback → Player")
        let found = playerPageOptions
        guard found.contains(id) else {
            throw XCTSkip("\(name) isn't installed on this Mac (the Player page found \(found.sorted())); \(install) runs this step")
        }
        app.terminate()

        try launchDev(addon: true, trackersConnected: true, open: ["detail:movie:tt0111161"], settings: ["playback.player=\(id)"])
        let unwatched = allText(of: require("detail.watched", timeout: 60, "the movie's details show the watched button"))
        ready(require("detail.play", timeout: 60, "the movie's details show Play")).click()
        // The hand-off brings the player to the front, in front of the app, until the app comes back.
        try withAnotherAppInFront {
            ready(require("streams.row.1", timeout: 60, "Play opens the Streams sheet with the H.264 stream")).click()
            let line = require("detail.playerApp", timeout: 60, "the hand-off to \(name) shows on the details")
            if !waitUntil(20, { allText(of: line).contains(name) && allText(of: line).contains(":") }) {
                fail("the details' line reads \"\(allText(of: line))\", not \(name)'s playback with its position")
            }
            if let firstRun { try answerFirstRunQuestions(of: firstRun.bundle, with: firstRun.answers, id) }
            // The line follows the player's position.
            let reported = allText(of: line)
            if !waitUntil(20, { let now = allText(of: line); return now.contains(name) && now != reported }) {
                fail("the details' line stays at \"\(reported)\": it doesn't follow \(name)'s position")
            }
            // The player's window comes up in front of the app's: the screen with it playing, then the app's own window.
            shot("\(platformName)-\(id)-window", of: XCUIScreen.main)
            app.activate()
        }
        shot("\(platformName)-playing-in-\(id)")
        // The player quits at the file's end, which ends the hand-off.
        requireGone("detail.playerApp", timeout: 90, "the line goes once \(name) has played the file to its end")
        if !waitUntil(10, { allText(of: element("detail.watched")) != unwatched }) {
            fail("after \(name) played the file to its end the movie isn't watched: its watched button still reads \(unwatched)")
        }
        shot("\(platformName)-after-\(id)")
    }

    /// The questions the app `bundle` asks on its first starts on a Mac, answered by `answers` (whichever is on screen)
    /// as a user would, each shot first (`<platform>-<id>-first-run-<n>`). A question comes up within seconds of the
    /// start or of the one before it; once answered, the app keeps the answer and doesn't ask again.
    ///
    /// A question asked before the app's event loop runs (VLC's album art question: an alert its main window runs as it
    /// loads) leaves the app deaf to accessibility queries until it's answered, so XCUITest can't find its buttons, and
    /// its queries time out. Such an alert shows as the app's only visible window, at the modal panel level, in the
    /// window server's list (which doesn't ask the app). Its last button, the alternate one (No, Thanks), lies 30 points
    /// above its bottom edge; XCUITest would bring this app forward over the alert to click there, and the sandboxed
    /// runner may not post a click itself, so the click goes to the run's hook (`click-<n>` in `TORTUGA_EXCHANGE`),
    /// which posts it from an ssh session. The app's questions once it's up (Sparkle's, in a window of its own) are
    /// found by their buttons.
    private func answerFirstRunQuestions(of bundle: String, with answers: [String], _ id: String) throws {
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        let player = XCUIApplication(bundleIdentifier: bundle)
        let buttons = answers.map { player.buttons[$0] }
        let alertLevel = Int(CGWindowLevelForKey(.modalPanelWindow))
        var answered = 0
        var clicks = 0
        // Until 6 s pass without a question.
        while true {
            var alert: (frame: CGRect, level: Int)?
            var button: XCUIElement?
            let asked = waitUntil(6) {
                let windows = visibleWindows(of: bundle)
                let up = windows.contains { $0.level == 0 }
                alert = up ? nil : windows.first { $0.level == alertLevel }
                button = up ? buttons.first { $0.exists } : nil
                return alert != nil || button != nil
            }
            guard asked else { return }
            answered += 1
            shot("\(platformName)-\(id)-first-run-\(answered)", of: XCUIScreen.main)
            if let alert {
                clicks += 1
                publish("click-\(clicks)", code: "\(Int(alert.frame.midX)) \(Int(alert.frame.maxY) - 30)", to: exchange)
                if !waitUntil(20, { !visibleWindows(of: bundle).contains { $0.frame == alert.frame && $0.level == alertLevel } }) {
                    fail("\(bundle)'s alert at \(alert.frame), up before its event loop, stays after the hook's click on its last button")
                    return
                }
            } else if let button {
                ready(button).click()
                if !waitUntil(5, { !button.exists }) {
                    fail("\(bundle)'s \"\(button.label)\" doesn't answer its question")
                    return
                }
            }
        }
    }

    /// The windows of the app `bundle` on screen and not fully transparent, with their frames (points from the screen's
    /// top left, as XCUITest's) and levels, from the window server: no query reaches the app.
    private func visibleWindows(of bundle: String) -> [(frame: CGRect, level: Int)] {
        let pids = Set(NSRunningApplication.runningApplications(withBundleIdentifier: bundle).map(\.processIdentifier))
        guard !pids.isEmpty, let list = CGWindowListCopyWindowInfo(.optionOnScreenOnly, kCGNullWindowID) as? [[String: Any]] else { return [] }
        return list.compactMap { window in
            guard let pid = window[kCGWindowOwnerPID as String] as? pid_t, pids.contains(pid),
                  (window[kCGWindowAlpha as String] as? Double ?? 1) > 0,
                  let level = window[kCGWindowLayer as String] as? Int,
                  let bounds = window[kCGWindowBounds as String] as? NSDictionary,
                  let frame = CGRect(dictionaryRepresentation: bounds as CFDictionary) else { return nil }
            return (frame, level)
        }
    }
}
