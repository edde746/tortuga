import XCTest

/// The player's error, full screen on every platform (`PlayerErrorPanel`): the player black from edge to edge behind
/// it, its texts and buttons on that with no card around them.
extension TortugaUITestCase {
    /// The error's buttons that show, by identifier (`ErrorAction.identifier`).
    var errorButtons: [XCUIElement] {
        ["player.error.chooseStream", "player.error.watch", "player.error.retry", "player.error.next", "player.error.close"]
            .map { element($0) }
            .filter(\.exists)
    }

    /// The error shows full screen, as the screen at its buttons' height tells: from the window's leading edge to the
    /// first button and from the last button to the trailing edge (8 pt clear of each, past a button's glass) it is
    /// black (no channel above 4: a dark glass card over black reads about 12), so neither a card around the buttons nor
    /// the video beside the error shows. Fails with `what`.
    func requireErrorFillsScreen(_ what: String) {
        let buttons = errorButtons
        guard let first = buttons.min(by: { $0.frame.minX < $1.frame.minX }),
              let last = buttons.max(by: { $0.frame.maxX < $1.frame.maxX }) else {
            return fail("\(what): the error shows none of its buttons")
        }
        #if os(macOS)
        let window = app.windows.firstMatch.frame
        #else
        let window = app.frame
        #endif
        guard let screen = ScreenPixels() else { return fail("\(what): no screenshot of the error") }
        let gap: CGFloat = 8
        let sides = [
            ("before the first button", CGRect(x: window.minX + gap, y: first.frame.minY, width: first.frame.minX - window.minX - 2 * gap, height: first.frame.height)),
            ("past the last button", CGRect(x: last.frame.maxX + gap, y: last.frame.minY, width: window.maxX - last.frame.maxX - 2 * gap, height: last.frame.height)),
        ]
        for (side, rect) in sides where rect.width > 0 {
            let black = screen.share(in: rect) { red, green, blue in max(red, green, blue) <= 4 }
            if black < 0.97 {
                fail("\(what): the screen \(side) at its height (\(rect)) is \(Int((black * 100).rounded())) % black, not the full-screen error's black")
            }
        }
    }
}
