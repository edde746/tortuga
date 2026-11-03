import XCTest

/// Player apps with the remote on an Apple TV that has none (the simulator has neither VLC nor Infuse):
/// - Settings → Playback → Player: the page lists Tortuga alone, chosen; Menu closes it;
/// - a stream row's long press of Select opens no Play with… (none is offered without a player app; with no menu the
///   press plays the stream, and Menu leaves the player).
final class PlayerAppsTests: TortugaUITestCase {
    func testPlayerPageListsTortugaAlone() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openPlayerPage("Settings → Playback → Player")
        let options = playerPageOptions
        if options != ["tortuga"] { fail("the Player page lists \(options.sorted()), not Tortuga alone") }
        if !waitUntil(5, { element("settings.player.option.tortuga").isSelected }) { fail("Tortuga, the only player, isn't the one checked") }
        shot("\(platformName)-player-page")
        press(.menu)
        requireGone("settings.player.option.tortuga", "Menu closes the Player page")
        require("settings.playback.player", "Menu returns to Playback")
    }

    func testStreamRowsOfferNoPlayWith() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        requireFocus("streams.row.0", timeout: 60, "the first stream has focus once the Streams screen lists them")
        press(.down)
        requireFocus("streams.row.1", "Down moves focus to the H.264 stream")
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        let playWith = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'streams.menu.playWith' OR label BEGINSWITH 'Play with'"))
        if waitUntil(3, { playWith.count > 0 }) { fail("with no player app on this Apple TV, a stream's long press offers Play with…") }
        shot("\(platformName)-streams-no-play-with")
        // A row without a menu takes the long press for Select: the stream plays, and Menu (a first one may close the
        // controls) returns to the list.
        if element("player.video").exists(within: 10) {
            for _ in 0..<4 where element("player.video").exists {
                press(.menu)
                _ = waitUntil(3) { !element("player.video").exists }
            }
            requireGone("player.video", "Menu leaves the player")
        }
        require("streams.row.1", "the Streams screen still lists the streams")
    }
}
