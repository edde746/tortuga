import XCTest

extension WatchTests {
    /// A live stream (the test addon's 24/7 channel): its options' Watch together opens the room sheet, which says a
    /// live stream can't be watched together and offers no way to start a room.
    func testALiveStreamCantStartARoom() throws {
        let scenario = try WatchScenario()
        try launchDev(addon: true, open: Self.liveChannelOpen, settings: scenario.settings)
        ready(livePlainStream("the channel's Streams list its plain stream")).click()
        let video = require("player.video", timeout: 30, "the plain stream opens the player")
        showControls(video)
        ready("player.more").click()
        pickMenuEntry(menuEntry("Watch Together", "the options menu offers Watch together"))
        require("player.watch.start.none", timeout: 10, "Watch together opens the room sheet, which says a live stream can't be watched together")
        if element("player.watch.start.hostOnly").exists { fail("the room sheet offers to start a room with a live stream") }
        shot("live stream room sheet")
        ready(require("player.watch.close", "the room sheet has Close")).click()
        requireGone("player.watch.panel", "Close closes the room sheet")
    }
}
