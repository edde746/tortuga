import XCTest

/// An anime entry's Detail before its title is found: One Piece on MyAnimeList's airing row (the fake trackers'
/// `--airing`), pressed on a fresh install, first shows the page of its card (the name, no actions) while the anime
/// lists are looked up. Focus stays on that page meanwhile, on the name, so the tab bar, which the page hides, doesn't
/// come back over it; once the title comes its Play takes focus, as on a page whose title was there at once.
final class EarlyDetailTests: TortugaUITestCase {
    func testAnAnimeEntryFromHomeKeepsFocusOnItsPageUntilItsTitleComes() throws {
        try launchDev(trackersConnected: true)
        requireFocus("board.row.0.item.0", timeout: 90, "the Board's first card has focus once its shelves load")
        // Home builds its shelves as they come on screen, the trackers' after the addons': Down until One Piece's is there.
        let card = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'board.row.' AND label == 'One Piece'")).firstMatch
        let walk = Date()
        while !card.exists, Date().timeIntervalSince(walk) < 120 { press(.down) }
        if !card.exists { fail("Home's airing row doesn't show One Piece") }
        moveFocus(to: card.identifier, with: .down, limit: 3, "Down reaches One Piece's card")
        shot("home")
        XCUIRemote.shared.press(.select)
        let title = element("detail.title"), play = element("detail.play")
        // Looks at the page while it shows the card's name and no Play, each read without waiting (the page lasts under
        // a second on the simulator): one taken between the name showing and Play not yet there. Focus is on nothing for
        // a moment as the page comes and as it hands focus on to Play, so the name has to hold it in one look at least.
        var early: [(focus: String?, tabBar: Bool, name: String)] = []
        let start = Date()
        while !play.exists, Date().timeIntervalSince(start) < 120 {
            guard title.exists, let shown = try? title.snapshot() else { continue }
            let here = focused
            let look = (focus: here.exists ? (try? here.snapshot())?.identifier : nil, tabBar: focusInTabBar, name: shown.label)
            if play.exists { break }
            if early.isEmpty { shot("early") }
            if look.focus == "detail.title", !early.contains(where: { $0.focus == "detail.title" }) { shot("early, the name focused") }
            early.append(look)
        }
        guard let first = early.first else { return fail("One Piece's title came before its card's page showed: nothing to check") }
        if first.name != "One Piece" { fail("the early page names \(first.name), not the card's One Piece") }
        if let away = early.first(where: \.tabBar) { fail("while the title loads focus went to the tab bar (on \(away.focus ?? "nothing"))") }
        if !early.contains(where: { $0.focus == "detail.title" }) {
            fail("while the title loads focus is never on the page's name: \(early.map { $0.focus ?? "nothing" })")
        }
        requireFocus("detail.play", timeout: 60, "Play takes focus once One Piece's title has come")
        if focusInTabBar { fail("focus is in the tab bar once the title has come") }
        shot("resolved")
    }
}
