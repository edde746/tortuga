import XCTest
import UIKit

/// The browse screens by touch, iPhone's and iPad's own (the shared ones: `BrowseTests`):
/// - the keyboard goes away on a tap on Search's page and on a scroll of the results, and a tap on a result with the
///   keyboard up opens it at once;
/// - a tap on the Search tab puts the cursor in the field, the keyboard up: from another tab, and again while Search
///   shows; a launch on Search and Back from a result's Detail leave the keyboard down, the field's text kept;
/// - a long press lifts what it was made on, in its own proportions, under its menu: a card's artwork alone, without
///   the caption under it, whose letters the preview's rounded corners cut (Continue Watching's card, a Home row's
///   poster, a Discover poster, a Search result, a Library item), on Detail an episode's thumbnail alone, 16:9 (the
///   whole row, shrunk beside the menu on a short window, came out unreadable), and a season's chip whole; the text
///   left out stays where it is (a card's caption, an episode's title and date), read in every frame sampled from the
///   press until a second and a half after the menu closes, but where the menu covers it (`requireTextStays`), and a
///   long press on a card's caption opens its menu too;
/// - a tap on a card's caption, under its artwork, opens its title as a tap on the artwork does (a Home row's poster,
///   a Discover poster);
/// - Detail's round + (a glass button) takes no taps while an item menu is open, so the glass can't take the taps of
///   the menu's entries, and takes taps again once the menu closes: the episode's ⋯ menu closed by a tap beside it
///   and by a pick, its long-press menu by a tap beside it;
/// - the test addon's Tortuga Pages shelf (`--pages`: 20 titles a page), on the Board and in Search's results for
///   "tortugapages", loads its next page when swiped along to its end;
/// - at the largest text size (accessibility-extra-extra-extra-large) Discover's filter capsules go on as many lines
///   as they take, each whole and on the screen: the system's audit (`textAuditIssues`, which takes the app from the
///   smallest text size to the largest) finds none of their text cut short.
extension BrowseTests {
    func testATapOrAScrollOfTheSearchPagePutsTheKeyboardAway() throws {
        try launchDev()
        ready(phoneTab("nav.search")).tap()
        let field = require("search.field", "Search shows its field")
        let window = app.windows.firstMatch
        requireKeyboard(after: field)
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap()
        requireNoKeyboard("a tap on the blank page under the field")
        shot("blank page tapped")
        requireKeyboard(after: field)
        field.typeText("matrix")
        ready(requireCard("search.group.0.item.0", timeout: 30, "typing matrix lists results")).tap()
        requireDetail("a tap on a result with the keyboard up opens its Detail screen")
        goBack()
        requireKeyboard(after: require("search.field", "back returns to Search"))
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
            .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)))
        requireNoKeyboard("a scroll of the results")
        shot("results scrolled")
    }

    func testTheSearchTabPutsTheCursorInTheField() throws {
        try launchDev(open: ["search"])
        let field = require("search.field", timeout: 30, "a launch on Search shows its field")
        requireNoKeyboard("a launch on Search")
        ready(phoneTab("nav.board")).tap()
        require("board.row.0.item.0", timeout: 90, "the Board shows its shelves")
        ready(phoneTab("nav.search")).tap()
        requireTypingIn(field, "a tap on the Search tab from Home")
        shot("search tab tapped")
        app.typeText("matrix")
        ready(requireCard("search.group.0.item.0", timeout: 30, "matrix typed after the tap lists results")).tap()
        requireDetail("a tap on a result opens its Detail screen")
        goBack()
        requireNoKeyboard("back from a result's Detail")
        if (require("search.field", "back returns to Search").value as? String) != "matrix" {
            fail("back from Detail, the field shows \(String(describing: field.value)), not matrix")
        }
        ready(phoneTab("nav.search")).tap()
        requireTypingIn(field, "a tap on the Search tab while Search shows")
        shot("search tab tapped again")
    }

    func testALongPressLiftsAnItemsArtworkAloneItsTextStayingAndASeasonChipWhole() throws {
        try launchDev(addon: true, progress: [Self.seriesEpisodeInProgress])
        requireCard("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        requireLift(of: "board.cw.0", text: .below, "a Continue Watching card")
        // The caption takes the press too: 20 pt under the artwork.
        ready("board.cw.0").coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 1)).withOffset(CGVector(dx: 0, dy: 20))
            .press(forDuration: 1.2)
        let captionMenu = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "board.cw.0.menu.")).firstMatch
        if !captionMenu.exists(within: 5) { fail("a long press on a Continue Watching card's caption opens no menu") }
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap()
        if !waitUntil(5, { !captionMenu.exists }) { fail("a tap beside the caption's menu doesn't close it") }
        requireLift(of: "board.row.0.item.0", text: .below, "a Home row's poster")
        ready(phoneTab("nav.discover")).tap()
        require("discover.item.0", timeout: 60, "Discover shows its grid")
        requireLift(of: "discover.item.0", text: .below, "a Discover poster")
        ready(phoneTab("nav.search")).tap()
        let field = require("search.field", "Search shows its field")
        ready(field).tap()
        field.typeText("matrix\n")
        requireCard("search.group.0.item.0", timeout: 30, "typing matrix lists results")
        requireNoKeyboard("the keyboard's Search key")
        requireLift(of: "search.group.0.item.0", text: .below, "a Search result")
        ready(phoneTab("nav.library")).tap()
        ready("library.view").tap()
        pickMenuEntry(require("library.view.option.1", timeout: 5, "the view menu lists Continue Watching"))
        require("library.item.0", timeout: 10, "the Library's Continue Watching lists the episode in progress")
        requireLift(of: "library.item.0", text: .below, "a Library item")

        try launchDev(addon: true, open: ["detail:series:tortuga-series"])
        require("detail.play", timeout: 60, "the series' details show Play")
        scrollTo("detail.episode.0", "the series lists its first episode")
        requireLift(of: "detail.episode.0", menu: "detail.menu.item.", part: ("thumbnail", 16.0 / 9.0), text: .inside, "an episode's row")
        scrollTo(element("detail.season.0"), up: true, "the series shows its first season's chip")
        requireLift(of: "detail.season.0", menu: "detail.menu.item.", "a season's chip")
    }

    func testATapOnACardsCaptionOpensItsTitle() throws {
        try launchDev(addon: true)
        for (id, screen) in [("board.row.0.item.0", "nav.board"), ("discover.item.0", "nav.discover")] {
            // On the caption's title and on its second line (the year): 14 and 34 pt under the artwork, the card's
            // button, its caption 6 pt under it.
            for below in [14.0, 34.0] {
                ready(phoneTab(screen)).tap()
                let card = ready(require(id, timeout: 90, "\(screen) shows \(id)"))
                print("caption tap, \(id) at \(card.frame), \(Int(below)) pt under it")
                card.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 1)).withOffset(CGVector(dx: 0, dy: below)).tap()
                requireDetail("a tap \(Int(below)) pt under \(id)'s artwork, on its caption, opens its Detail screen")
                shot("detail from \(id)'s caption, \(Int(below)) pt under its artwork")
                goBack()
            }
        }
    }

    func testAGlassButtonTakesTapsAgainOnceAnItemMenuCloses() throws {
        try launchDev(addon: true, open: ["detail:series:tortuga-series"])
        require("detail.play", timeout: 60, "the series' details show Play")

        ready(scrollTo("detail.episode.0.menu", "the series lists its first episode with its ⋯ button")).tap()
        let entry = require("detail.menu.item.markWatched", timeout: 5, "the episode's ⋯ button opens its menu")
        shot("episode ⋯ menu")
        requireLibraryButtonOff("the episode's ⋯ menu")
        closeMenu(showing: entry, "the episode's ⋯ menu")
        requireLibraryButtonTurns("after the episode's ⋯ menu closed without a pick")

        ready(scrollTo("detail.episode.0.menu", "the series lists its first episode with its ⋯ button")).tap()
        let watched = require("detail.menu.item.markWatched", timeout: 5, "the episode's ⋯ button opens its menu again")
        requireLibraryButtonOff("the episode's ⋯ menu, again")
        pickMenuEntry(watched)
        if !waitUntil(10, { element("detail.episode.0").exists && isWatched(element("detail.episode.0")) }) {
            fail("the ⋯ menu's Mark as watched doesn't mark the episode watched")
        }
        requireLibraryButtonTurns("after a pick in the episode's ⋯ menu")

        ready(scrollTo("detail.episode.0", "the series lists its first episode")).press(forDuration: 1.2)
        let unwatched = require("detail.menu.item.markUnwatched", timeout: 5, "a long press on the watched episode opens its menu")
        shot("episode long-press menu")
        requireLibraryButtonOff("the episode's long-press menu")
        closeMenu(showing: unwatched, "the episode's long-press menu")
        requireLibraryButtonTurns("after the episode's long-press menu closed without a pick")
    }

    /// Closes the open menu that shows `entry` with a tap beside it: at the entry's height, at the window's other side
    /// (a ⋯ menu hangs from its button at the row's end). Fails with `what` while the entry stays.
    private func closeMenu(showing entry: XCUIElement, _ what: String) {
        let frame = entry.frame
        let screen = app.frame
        let side: CGFloat = frame.midX > screen.midX ? 0.03 : 0.97
        app.coordinate(withNormalizedOffset: CGVector(dx: side, dy: (frame.midY - screen.minY) / screen.height)).tap()
        if !waitUntil(5, { !entry.exists }) { fail("a tap beside \(what) doesn't close it") }
    }

    /// With `menu` open, Detail's round + (`detail.library`) takes no taps: disabled. Fails otherwise.
    private func requireLibraryButtonOff(_ menu: String) {
        let library = element("detail.library")
        if !waitUntil(3, { library.exists && !library.isEnabled }) {
            fail("with \(menu) open, the library button \(library.exists ? "takes taps (enabled)" : "isn't there")")
        }
    }

    /// Detail's round + (`detail.library`), scrolled back into the window: fails with `what` unless it is enabled and a
    /// tap turns it into the other of add and remove.
    private func requireLibraryButtonTurns(_ what: String) {
        let library = scrollTo(element("detail.library"), up: true, "the series' details show the library button")
        if !waitUntil(5, { library.isEnabled }) { fail("\(what), the library button stays disabled") }
        let before = library.label
        ready(library).tap()
        if !waitUntil(10, { element("detail.library").exists && element("detail.library").label != before }) {
            fail("\(what), a tap on the library button leaves it reading \(before)")
        }
    }

    /// Where the text a long press leaves out of its lift lies: under the pressed element (a card's caption under its
    /// artwork button), or inside it (an episode row's title and date beside its thumbnail).
    enum LiftText {
        case below, inside
    }

    /// Long-presses `id` (a card's artwork button, a row, a chip), waits for its menu (an entry identified `<menu>…`, by
    /// default `<id>.menu.…`) and keeps a shot; fails with `what` unless the lifted preview (UIKit's "Preview", the item
    /// scaled up) has the proportions of `id` itself (on a card, its artwork's, the caption under it left out), or of
    /// `part` of it where given (its name, its width over its height: an episode row's 16:9 thumbnail). Then closes the
    /// menu with a tap beside it. With `text`, the screen is sampled from just before the press until a second and a
    /// half after the menu closed, and the text left out of the lift must stay (`requireTextStays`).
    private func requireLift(
        of id: String, menu: String? = nil, part: (name: String, ratio: CGFloat)? = nil, text: LiftText? = nil, _ what: String
    ) {
        let item = ready(id)
        let pressed = item.frame
        let sampler = text.map { _ -> ScreenSampler in
            screenSettles()
            let sampler = ScreenSampler(of: appScreen)
            sampler.start(limit: 30)
            _ = waitUntil(5) { sampler.count >= 2 }
            return sampler
        }
        let pressing = Date()
        item.press(forDuration: 1.2)
        let entries = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", menu ?? "\(id).menu."))
        let entry = entries.firstMatch
        if !entry.exists(within: 5) { fail("a long press on \(what) opens no menu") }
        let preview = app.otherElements.matching(NSPredicate(format: "label == 'Preview'")).firstMatch
        if !preview.exists(within: 5) { fail("\(what)'s menu shows no lifted preview") }
        // The lift's animation settles before the shot.
        _ = settledFrame(of: preview)
        let settled = Date()
        shot("\(what) menu")
        let lifted = preview.frame
        let ratio = part?.ratio ?? pressed.width / pressed.height
        if abs((lifted.width / lifted.height) / ratio - 1) > 0.02 {
            let size = "\(Int(pressed.width))×\(Int(pressed.height)) pt"
            let wanted = part.map { "\(id)'s \($0.name) alone (\(String(format: "%.2f", $0.ratio)):1, the \(size) whole lifted)" }
                ?? "\(id)'s \(size) scaled up (a card lifting its caption with its artwork)"
            fail("\(what): its menu lifts a \(Int(lifted.width))×\(Int(lifted.height)) pt preview, not \(wanted)")
        }
        let menuEntries = entries.allElementsBoundByIndex.map(\.frame)
        let closing = Date()
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap()
        if !waitUntil(5, { !entry.exists }) { fail("a tap beside \(what)'s menu doesn't close it") }
        guard let sampler, let text else { return }
        _ = holds(for: 1.5) { true }
        let place = text == .below ? CGRect(x: pressed.minX, y: pressed.maxY, width: pressed.width, height: 46) : pressed
        requireTextStays(
            in: place, frames: sampler.stopTimed(), pressing: pressing, open: settled..<closing, closing: closing,
            menuEntries: menuEntries, what
        )
    }

    /// Fails with `what` unless the lowest line of text read in `place` (a card's subtitle, an episode's date) before
    /// the press reads there in every frame of `frames` (sampled from before `pressing` until after the menu closed)
    /// where the menu doesn't cover it: while the menu is `open` and settled, unless its entries (`menuEntries`) lie
    /// over `place`; and wherever the menu is away (one of its entries' places looks as it did before the press: the
    /// lift, and from the moment the menu has gone). A system context menu hides the view it is on from the lift until
    /// about a second after the menu has gone: text inside that view vanished for all that time.
    private func requireTextStays(
        in place: CGRect, frames: [(taken: Date, shot: XCUIScreenshot)], pressing: Date, open: Range<Date>, closing: Date,
        menuEntries: [CGRect], _ what: String
    ) {
        guard let before = frames.last(where: { $0.taken < pressing }), let beforePixels = ScreenPixels(before.shot) else {
            return fail("\(what): no frame sampled before the press")
        }
        guard let subtitle = before.shot.texts(in: place).max(by: { $0.frame.minY < $1.frame.minY })?.text else {
            return fail("\(what): no text read in \(place) before the press")
        }
        // The entries clear of the text, with the margin the menu's panel has around them; the one farthest from the text
        // tells where the menu shows or not, whatever it does over the text.
        let clear = menuEntries.filter { !$0.insetBy(dx: -16, dy: -16).intersects(place) }
        guard let watched = clear.max(by: { abs($0.midY - place.midY) < abs($1.midY - place.midY) }) else {
            return fail("\(what): every entry of its menu lies over its text at \(place): nowhere to tell the menu has gone")
        }
        let covered = clear.count < menuEntries.count
        var judged = 0, judgedAfter = 0
        var missing: [(time: String, shot: XCUIScreenshot, read: [String])] = []
        for frame in frames where frame.taken >= pressing {
            let away = ScreenPixels(frame.shot).map { $0.changedShare(in: watched, from: beforePixels) < 0.02 } ?? false
            guard away || (open.contains(frame.taken) && !covered) else { continue }
            judged += 1
            if frame.taken >= closing { judgedAfter += 1 }
            let read = frame.shot.textLines(in: place)
            // Recognition splits a line in two at times (iPad: "S1E1", "1 min left"): each line, and each with the next.
            let candidates = read + zip(read, read.dropFirst()).map { "\($0) \($1)" }
            if !candidates.contains(where: { Self.reads($0, subtitle) }) {
                let time = frame.taken < closing
                    ? String(format: "%.1f s into the press", frame.taken.timeIntervalSince(pressing))
                    : String(format: "%.1f s after the tap closing the menu", frame.taken.timeIntervalSince(closing))
                missing.append((time, frame.shot, read))
            }
        }
        if judgedAfter < 3 {
            return fail("\(what): its menu's entry at \(watched) never looked as before the press after the menu closed (\(judged) frames judged)")
        }
        guard let first = missing.first else { return }
        let attachment = XCTAttachment(screenshot: first.shot)
        attachment.name = "\(what): text gone"
        attachment.lifetime = .keepAlways
        add(attachment)
        fail("\(what): \"\(subtitle)\" isn't there in \(missing.count) of the \(judged) frames where the menu leaves it in view: "
            + missing.map { "\($0.time) (read: \($0.read))" }.joined(separator: ", "))
    }

    /// `line` (read from a screenshot) reads `wanted` (read from another): their letters and digits the same but for at
    /// most a fifth of them (at least two), as the dimming behind a menu changes what text recognition makes of a line.
    private static func reads(_ line: String, _ wanted: String) -> Bool {
        let a = Array(line.lowercased().filter { $0.isLetter || $0.isNumber })
        let b = Array(wanted.lowercased().filter { $0.isLetter || $0.isNumber })
        // The edit distance, one row at a time.
        var row = Array(0...b.count)
        for (i, x) in a.enumerated() {
            var diagonal = row[0]
            row[0] = i + 1
            for (j, y) in b.enumerated() {
                let above = row[j + 1]
                row[j + 1] = min(above + 1, row[j] + 1, diagonal + (x == y ? 0 : 1))
                diagonal = above
            }
        }
        return row[b.count] <= max(2, b.count / 5)
    }

    func testAHomeRowAndASearchRowLoadTheirNextPagesAtTheirEnds() throws {
        try launchDev(addon: true)
        require("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        swipeToSecondPage(of: pagesShelf("board.row", "the Board"), "the Board's Tortuga Pages shelf")
        ready(phoneTab("nav.search")).tap()
        let field = require("search.field", "Search shows its field")
        ready(field).tap()
        field.typeText("tortugapages\n")
        swipeToSecondPage(of: pagesShelf("search.group", "Search's results"), "Search's Tortuga Pages shelf")
    }

    func testDiscoversFiltersReadWholeAtTheLargestTextSize() throws {
        try launchDev(textSize: Self.largestTextSize)
        ready(phoneTab("nav.discover")).tap()
        require("discover.item.0", timeout: 60, "Discover shows its catalog")
        requireFiltersOnScreen(Self.discoverFilters, "Discover's filters at the largest text size")
        screenSettles()
        shot("discover, largest text")
        let filters = Self.discoverFilters.map { element($0) }.filter(\.exists)
        let issues = textAuditIssues("Discover's filters at the largest text size", within: filters)
        if !issues.isEmpty { fail("the audit finds \(issues.count) issues: \(issues.joined(separator: "; "))") }
    }

    /// The shelf (`board.row.4`) whose first card is the test addon's "Tortuga Page 1", among the shelves `prefix` names,
    /// the card swiped inside the window, above iPhone's tab bar, once no shelf is loading (`requireShelvesSettled`: one
    /// above it that comes back empty leaves the page, moving it up an index and up the page).
    private func pagesShelf(_ prefix: String, _ screen: String) -> String {
        let first = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label == 'Tortuga Page 1' AND identifier BEGINSWITH %@", prefix + "."))
            .firstMatch
        scrollTo(first, "\(screen) shows the Tortuga Pages shelf")
        requireShelvesSettled(prefix, "the Tortuga Pages shelf in \(screen)")
        if !first.exists(within: 5) { fail("\(screen) loses the Tortuga Pages shelf as its shelves settle") }
        scrollTo(first, "\(screen)'s Tortuga Pages shelf, its shelves settled")
        return first.identifier.replacingOccurrences(of: #"\.item\.0$"#, with: "", options: .regularExpression)
    }

    /// Drags `shelf` along, a screen at a time, at the height of its first card (inside the window: `pagesShelf`), until
    /// its 25th card (on its second page, which its end asked for) shows.
    private func swipeToSecondPage(of shelf: String, _ what: String) {
        let start = require("\(shelf).item.0", "\(what) shows its first card")
        if element("\(shelf).item.24").exists { fail("\(what) shows its second page before it is swiped along") }
        shot("\(what) first page")
        let y = start.frame.midY
        let from = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: app.frame.width * 0.85, dy: y))
        let to = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: app.frame.width * 0.1, dy: y))
        let second = element("\(shelf).item.24")
        for _ in 0..<20 where !second.exists(within: 1) { from.press(forDuration: 0.05, thenDragTo: to) }
        if !second.exists { fail("\(what) swiped to its end shows no second page") }
        shot("\(what) second page")
    }

    /// Taps `field` and waits for the software keyboard.
    private func requireKeyboard(after field: XCUIElement) {
        ready(field).tap()
        if !app.keyboards.firstMatch.exists(within: 10) {
            fail("the software keyboard doesn't come up for \(field.identifier) (a hardware keyboard attached?)")
        }
    }

    /// Waits for the software keyboard with `field`'s cursor: fails with `what` when either doesn't come.
    private func requireTypingIn(_ field: XCUIElement, _ what: String) {
        if !app.keyboards.firstMatch.exists(within: 5) {
            fail("\(what) leaves the software keyboard down (a hardware keyboard attached?)")
        }
        // XCUITest's own attribute, which its API doesn't name.
        if !waitUntil(5, { field.value(forKey: "hasKeyboardFocus") as? Bool == true }) {
            fail("\(what) raises the keyboard without the cursor in Search's field")
        }
    }

    /// Fails with `what` while the software keyboard stays up.
    private func requireNoKeyboard(_ what: String) {
        if !waitUntil(5, { !app.keyboards.firstMatch.exists }) { fail("\(what) leaves the keyboard up") }
    }
}
