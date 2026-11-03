import XCTest

/// What the player app tests share: a custom URL-template player that hands the video to the test addon, Settings →
/// Playback → Player opened by touch (iOS), the remote (tvOS) or the pointer (macOS), and screenshots named
/// `<platform>-<what>`.
extension TortugaUITestCase {
    /// The player id of the custom player the dev settings add (`playback.customPlayer`, stored with the id `dev`).
    static let handoffPlayer = "custom:dev"
    /// The custom player's name, as Settings and Play with… list it.
    static let handoffPlayerName = "Handoff"

    /// The platform's name, the prefix of the test's screenshot names: `ios`, `ipados`, `tvos` or `macos`.
    var platformName: String {
        #if os(tvOS)
        "tvos"
        #elseif os(iOS)
        isPad ? "ipados" : "ios"
        #else
        "macos"
        #endif
    }

    /// This device's settings (`launchDev(settings:)`) for a custom player named `handoffPlayerName` whose URL template
    /// is the test addon's `/handoff?url={url}` (at `TORTUGA_ADDON`'s host and port, as the simulator reaches it; the
    /// addon's log, the run's addon.log, keeps the request, and Safari opens the page), chosen as the player every play
    /// opens in.
    var handoffPlayerSettings: [String] {
        get throws {
            let manifest = try addonURL
            guard let base = URL(string: manifest)?.deletingLastPathComponent().absoluteString else {
                throw HarnessError("TORTUGA_ADDON is not a URL: \(manifest)")
            }
            return ["playback.customPlayer=\(Self.handoffPlayerName);\(base)handoff?url={url}", "playback.player=\(Self.handoffPlayer)"]
        }
    }

    /// The player rows the open Player page lists (`settings.player.option.<player id>`), by player id.
    var playerPageOptions: Set<String> {
        let prefix = "settings.player.option."
        let rows = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix))
        return Set(rows.allElementsBoundByIndex.map { String($0.identifier.dropFirst(prefix.count)) }.filter { !$0.hasSuffix(".delete") })
    }

    /// Settings → Playback, then its Player row (`settings.playback.player`, scrolled into view on iOS and the Mac:
    /// `showOnScreen`), which opens the Player page; waits until the page lists Tortuga and has stopped looking for the
    /// players on this device. Fails with `what` when it doesn't.
    func openPlayerPage(_ what: String) {
        openSection("playback")
        #if os(tvOS)
        // The Player row heads the page; focus may land lower once the page loads.
        moveFocus(to: "settings.playback.player", with: .up, limit: 6, "\(what): the remote reaches Playback's Player row")
        press(.select)
        #else
        activate(showOnScreen("settings.playback.player", "\(what): Playback lists Player"))
        #endif
        require("settings.player.option.tortuga", "\(what): Player opens its page with Tortuga")
        requireGone("settings.player.detecting", timeout: 30, "\(what): the page stops looking for players")
    }
}
