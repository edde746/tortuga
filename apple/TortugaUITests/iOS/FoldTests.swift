import XCTest

/// The iPhone Duo folded and unfolded while the app runs (`pose`: the hook turns the simulator's hinge): the app
/// moves to the other screen and lays out for it within the 2 s `pose` waits. On a movie's details, over its Streams
/// sheet and in its player, each fold and unfold leaves what the screen shows inside the new window, none of it at the
/// old screen's size: the details on the one-column page centred in the new window (both screens are under
/// `DetailMetrics.wideWindowWidth`), the sheet's first stream, the video over the whole window and the controls' bottom
/// bar; and the playback goes on through each. A list scrolled to its end keeps its place, its last row in view. Each
/// test puts the phone back in the run's pose (`poseBack`). Skipped where the phone doesn't fold.
final class FoldTests: TortugaUITestCase {
    /// The test addon's chapters movie: 60 s of video (tools/media/test_addon.py), time for three folds.
    private let movie = "tortuga-chapters-movie"

    override func tearDownWithError() throws {
        if isFoldable { poseBack() }
        try super.tearDownWithError()
    }

    func testDetailsSheetAndPlayerFollowTheFold() throws {
        try XCTSkipUnless(isFoldable, "only a foldable iPhone folds")
        pose("portrait")
        pose("open")
        try launchDev(addon: true, open: ["board", "detail:movie:\(movie)"])
        let play = require("detail.play", timeout: 60, "the movie's details show Play")
        requireDetailsCentred("on the inner screen")
        shot("details, inner screen")
        for (fold, name) in [("closed", "folded"), ("open", "unfolded")] {
            pose(fold)
            requireDetailsCentred(name)
            requireInside(play, "Play \(name)")
            shot("details, \(name)")
        }

        activate(play)
        let row = require("streams.row.0", timeout: 60, "Play opens the Streams sheet")
        for (fold, name) in [("closed", "folded"), ("open", "unfolded")] {
            pose(fold)
            requireInside(row, "the Streams sheet's first stream \(name)")
            shot("streams, \(name)")
        }

        activate(row)
        let video = require("player.video", timeout: 20, "the stream opens the player")
        var position = requirePlayback(2, timeout: 90)
        for (fold, name) in [("closed", "folded"), ("open", "unfolded"), ("closed", "folded again")] {
            pose(fold)
            let window = windowFrame
            if !waitUntil(3, { abs(video.frame.width - window.width) < 1 && abs(video.frame.height - window.height) < 1 }) {
                fail("\(name), the video covers \(video.frame), not the window at \(window)")
            }
            position = requirePlayback(position + 2, timeout: 10)
            showPlayerControls(video, "\(name), a tap brings the controls up")
            requireInside(element("player.bar.bottom"), "the player's bottom bar \(name)")
            shot("player, \(name)")
        }
    }

    func testScrolledListKeepsItsPlaceThroughTheFold() throws {
        try XCTSkipUnless(isFoldable, "only a foldable iPhone folds")
        pose("portrait")
        pose("open")
        try launchDev(addon: true, open: ["settings"])
        require("settings.section.playback", timeout: 30, "Settings lists its sections")
        // About, the last row; then a swipe, so that the list is at its very end.
        let last = scrollTo(element("settings.section.about"), "Settings scrolls to About, its last row")
        appWindow.swipeUp()
        requireInside(last, "About, Settings scrolled to its end")
        for (fold, name) in [("closed", "folded"), ("open", "unfolded")] {
            pose(fold)
            // Still at its end on the narrower or wider page: not back near its top, where the navigation bar, its large
            // title expanded again on the new screen, took it (iOS 27.1).
            requireInside(last, "About, Settings scrolled to its end, \(name)")
            shot("settings scrolled, \(name)")
        }
    }

    /// The details on the one-column page of the window they are in now, told by the action row (Play to the last round
    /// button) centred on the page: within 60 pt of the window's middle, the page being narrower than the window by the
    /// vertical bar's column.
    private func requireDetailsCentred(_ what: String) {
        let window = windowFrame
        let row = ["detail.play", "detail.library", "detail.watched", "detail.download", "detail.trailer", "detail.share", "detail.overflow"]
            .map(element).filter(\.exists).map(\.frame)
        let middle = ((row.map(\.minX).min() ?? 0) + (row.map(\.maxX).max() ?? 0)) / 2
        if abs(middle - window.midX) > 60 {
            fail("\(what), the action row's middle is at x \(middle) in the window at \(window): not centred on the page")
        }
    }

    /// `element` whole inside the window as it is now.
    private func requireInside(_ element: XCUIElement, _ what: String) {
        let window = windowFrame
        guard element.exists else { return fail("\(what): it isn't on the screen, the window at \(window)") }
        let frame = element.frame
        if !window.insetBy(dx: -0.5, dy: -0.5).contains(frame) {
            fail("\(what): it lies at \(frame), not inside the window at \(window)")
        }
    }
}
