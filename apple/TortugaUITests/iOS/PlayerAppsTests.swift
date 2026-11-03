import XCTest

/// Player apps by touch. The simulator has no player app from the App Store, so a custom URL-template player stands in
/// for one: Handoff, whose template is the test addon's
/// `/handoff?url={url}` (`handoffPlayerSettings`), chosen as the player every play opens in. The hand-off opens Safari
/// on that page, and the addon's log (the run's addon.log) keeps the request with the stream's link.
/// - Settings → Playback → Player: the page lists Tortuga and Handoff, checked; the row names Handoff;
/// - the movie's Streams sheet: a stream row's context menu offers Play with… Tortuga and Handoff;
/// - the H.264 stream picked in the movie's Streams sheet opens Safari on the addon's page; back in the app, the sheet
///   has closed and the details ask whether the movie was finished (the custom player reports nothing back), and Mark
///   as watched marks it watched;
/// - the redirect movie's stream (tortuga-dl-redirect: three 302s to h264-sdr.mkv) opens Safari too, on the link the
///   redirects end at: addon.log's `handoff url=` names matrix/h264-sdr.mkv, not the addon's redirect/3 link.
final class PlayerAppsTests: TortugaUITestCase {
    func testPlayerPageListsTheCustomPlayerChosen() throws {
        try launchDev(settings: handoffPlayerSettings)
        openPlayerPage("Settings → Playback → Player")
        let custom = require("settings.player.option.\(Self.handoffPlayer)", "the Player page lists the custom player")
        if !waitUntil(5, { custom.isSelected }) { fail("the custom player chosen isn't the one checked") }
        shot("\(platformName)-player-page")
        closeSheet("settings.player.close", showing: "settings.player.option.tortuga", "Close (an iPhone: a pull down) closes the Player page")
        requireText("settings.playback.player", contains: Self.handoffPlayerName, "Playback's Player row names the custom player chosen")
    }

    func testStreamRowOffersPlayWith() throws {
        try launchDev(addon: true, open: ["detail:movie:tt0111161"], settings: handoffPlayerSettings)
        ready(require("detail.play", timeout: 60, "the movie's details show Play")).tap()
        let row = require("streams.row.1", timeout: 60, "Play opens the Streams sheet with the H.264 stream")
        ready(row).press(forDuration: 1.2)
        // The submenu's entry, by its identifier where the context menu keeps it, else by its title.
        let playWith = app.buttons
            .matching(NSPredicate(format: "identifier == 'streams.menu.playWith' OR label BEGINSWITH 'Play with'"))
            .firstMatch
        if !playWith.exists(within: 5) { fail("a long press on the stream offers no Play with…") }
        ready(playWith).tap()
        playWithEntry("tortuga", "Tortuga", "Play with… lists Tortuga")
        playWithEntry(Self.handoffPlayer, Self.handoffPlayerName, "Play with… lists the custom player")
        shot("\(platformName)-play-with")
    }

    func testHandOffOpensSafariAndThePromptMarksTheMovieWatched() throws {
        try launchDev(addon: true, open: ["detail:movie:tt0111161"], settings: handoffPlayerSettings)
        let watched = require("detail.watched", timeout: 60, "the movie's details show the watched button")
        let unwatched = watched.label
        ready("detail.play").tap()
        ready(require("streams.row.1", timeout: 60, "Play opens the Streams sheet with the H.264 stream")).tap()
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        if !safari.wait(for: .runningForeground, timeout: 60) { fail("the hand-off to the custom player doesn't open Safari") }
        // The test addon's /handoff page (tools/media/test_addon.py's HANDOFF_PAGE), once Safari has drawn it.
        if !safari.staticTexts["The test addon got the hand-off."].exists(within: 30) {
            fail("Safari doesn't show the test addon's hand-off page")
        }
        shot("\(platformName)-handoff-safari", of: XCUIScreen.main)

        XCUIApplication().activate()
        require("detail.playerApp", timeout: 30, "back in the app, the details ask whether the movie was finished")
        if element("streams.row.1").exists { fail("back from the hand-off, the Streams sheet is still open") }
        shot("\(platformName)-finished-prompt")
        ready(require("detail.playerApp.markWatched", "the prompt offers Mark as watched")).tap()
        requireGone("detail.playerApp", "Mark as watched answers the prompt")
        if !waitUntil(10, { element("detail.watched").exists && element("detail.watched").label != unwatched }) {
            fail("after Mark as watched the movie isn't watched: its watched button still reads \(unwatched)")
        }
        shot("\(platformName)-marked-watched")
    }

    func testHandOffGivesThePlayerTheRedirectsEnd() throws {
        try launchDev(addon: true, open: ["detail:movie:tortuga-dl-redirect"], settings: handoffPlayerSettings)
        ready(require("detail.play", timeout: 60, "the redirect movie's details show Play")).tap()
        ready(require("streams.row.0", timeout: 60, "Play opens the Streams sheet with the redirect's stream")).tap()
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        if !safari.wait(for: .runningForeground, timeout: 60) { fail("the hand-off to the custom player doesn't open Safari") }
        if !safari.staticTexts["The test addon got the hand-off."].exists(within: 30) {
            fail("Safari doesn't show the test addon's hand-off page")
        }
        shot("\(platformName)-handoff-redirect-safari", of: XCUIScreen.main)
    }

    /// The open Play with… submenu's entry `streams.menu.playWith.<playerId>`, or its entry labelled `label`: on iOS 27
    /// a context menu's submenu lists its entries without their identifiers.
    private func playWithEntry(_ playerId: String, _ label: String, _ what: String) {
        let entry = app.buttons
            .matching(NSPredicate(format: "identifier == %@ OR label == %@", "streams.menu.playWith.\(playerId)", label))
            .firstMatch
        if !entry.exists(within: 5) { fail("\(what): no streams.menu.playWith.\(playerId) or '\(label)' after 5 s") }
    }
}
