import XCTest

/// The player opened and closed many times in a row on the test addon's streams for a Cinemeta movie
/// (tools/media/test_addon.py), from the Streams screen: the remux (HEVC HDR10) and the H.264 file, each closed
/// while playing and while paused, and the remux as the screenshot tour leaves it (French subtitles on, paused, the
/// playback info opened and closed). Every close ends the playback, so mpv tears its video output down while the app
/// goes on; a crash there ends the app within moments of the close. Each test goes on after a crash (XCTest reports
/// the crash as a failure; the app is launched again for the next cycle), counts the crashes and fails when there was
/// any.
final class PlayerStressTests: TortugaUITestCase {
    /// Open-and-close cycles per test.
    private static let cycles = 10

    func testRemuxClosedWhilePlaying() throws {
        try openAndClose(row: 0) { _, _ in }
    }

    func testRemuxClosedWhilePaused() throws {
        try openAndClose(row: 0) { video, cycle in pause(video, cycle) }
    }

    func testH264ClosedWhilePlaying() throws {
        try openAndClose(row: 1) { _, _ in }
    }

    func testH264ClosedWhilePaused() throws {
        try openAndClose(row: 1) { video, cycle in pause(video, cycle) }
    }

    func testRemuxWithSubtitlesAndInfoClosedWhilePaused() throws {
        try openAndClose(row: 0) { video, cycle in
            showControls(video)
            ready("player.subtitles").click()
            pickMenuEntry(menuEntry("French", "cycle \(cycle): the subtitle button opens its menu"))
            pause(video, cycle)
            togglePlaybackInfo()
            require("player.info.panel", timeout: 5, "cycle \(cycle): Playback info in the options menu opens the playback info")
            showControls(video)
            togglePlaybackInfo()
            requireGone("player.info.panel", timeout: 5, "cycle \(cycle): Playback info in the options menu closes the playback info")
        }
    }

    /// `cycles` times: opens the Streams screen's stream `row`, does `then` once it plays, and closes the player. The
    /// app is launched again before a cycle when it crashed after the one before.
    private func openAndClose(row: Int, then: (XCUIElement, Int) -> Void) throws {
        let open = ["streams:movie:tt0111161:tt0111161"]
        try launchDev(addon: true, open: open)
        // A crash is a failure XCTest reports by itself; the test goes on to count them all.
        continueAfterFailure = true
        var crashed: [Int] = []
        for cycle in 1...Self.cycles {
            if app.state != .runningForeground {
                crashed.append(cycle - 1)
                try launchDev(addon: true, open: open)
                continueAfterFailure = true
            }
            ready(require("streams.row.\(row)", timeout: 60, "cycle \(cycle): the Streams screen lists stream \(row)")).click()
            let video = require("player.video", timeout: 20, "cycle \(cycle): a click on stream \(row) opens the player")
            requirePlayback(1, timeout: 60)
            then(video, cycle)
            showControls(video)
            ready("player.back").click()
            requireGone("player.video", "cycle \(cycle): the back button closes the player")
            // The engine tears mpv down on its own queue once the player is gone: 2 s for a crash there to end the app,
            // which the next cycle's check (or the count after the last) then counts.
            _ = holds(for: 2) { app.state == .runningForeground }
        }
        if app.state != .runningForeground { crashed.append(Self.cycles) }
        let report = XCTAttachment(
            string: "\(name): \(Self.cycles) cycles, \(crashed.count) crashes"
                + (crashed.isEmpty ? "" : " (after cycles \(crashed.map(String.init).joined(separator: ", ")))")
        )
        report.name = "stress result"
        report.lifetime = .keepAlways
        add(report)
        if !crashed.isEmpty {
            XCTFail("the app crashed after closing the player in \(crashed.count) of \(Self.cycles) cycles: \(crashed)")
        }
    }

    private func pause(_ video: XCUIElement, _ cycle: Int) {
        showControls(video)
        if require("player.playPause", timeout: 5, "cycle \(cycle): the controls show play/pause").label != "Play" { ready("player.playPause").click() }
        if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Play" }) {
            fail("cycle \(cycle): play/pause did not pause the video: it reads \(element("player.playPause").label)")
        }
    }

    /// Brings the controls up, or keeps them up for a while longer, by moving the pointer over the video.
    private func showControls(_ video: XCUIElement) {
        pointerOn(video, at: CGVector(dx: 0.3, dy: 0.4))
        pointerOn(video, at: CGVector(dx: 0.5, dy: 0.5))
        require("player.back", timeout: 5, "the pointer over the video shows the controls")
    }
}
