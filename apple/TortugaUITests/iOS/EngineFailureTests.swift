import XCTest

/// The engine's own failures reach the player: launched with one startup option mpv doesn't know
/// (`-mpvOption no-such-option=yes`, a DEBUG build's launch argument), the test addon's 4:3 clip brings up the player's
/// error, instead of playing on an mpv set up without it.
final class EngineFailureTests: TortugaUITestCase {
    func testRejectedMpvOptionBringsUpThePlayersError() throws {
        try launchDev(addon: true, open: ["streams:movie:tortuga-4x3:tortuga-4x3"], arguments: ["-mpvOption", "no-such-option=yes"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists the 4:3 clip")).tap()
        require("player.error", timeout: 60, "the startup option mpv rejected brings up the player's error")
        shot("the player's error for the rejected mpv option")
    }
}
