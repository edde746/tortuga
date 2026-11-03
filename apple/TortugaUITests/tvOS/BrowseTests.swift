import XCTest

/// The browse screens with the remote, on live Cinemeta (the dev profile's default addon):
/// - the Board's first card has focus once its shelves load; Right moves focus along the shelf and Down to the next
///   one; Select opens the card's Detail screen and Menu brings focus back to that card;
/// - the first shelf (Cinemeta's Popular, more than 20 movies) shows 20 cards, then a See all card and no See all in
///   its heading; Right from the 20th card moves focus to the See all card, and Select on it opens Discover on the
///   shelf's catalog (its first poster the shelf's first card) with focus on its first poster; Genre opens its panel,
///   which lists the genres readably (labelled rows inside the panel, the selected one focused), and a genre picked
///   there closes it and changes the selection and the grid;
/// - Search types "matrix" on the search keyboard and lists results; Select on one opens Detail and Menu brings focus
///   back to it; a new visit to Search finds "matrix" and its results as they were left; the query deleted, it lists
///   "matrix" as a recent search, and Select on it searches it again;
/// - Menu goes back from screens still loading (the test addon's late movies): a Detail whose meta is on its way, and
///   Streams whose addon hasn't answered; on the Board, the Home tab's root, Menu moves focus to the tab bar, and from
///   there it leaves the app for the tvOS Home screen;
/// - holding Select on a Continue Watching card opens its menu (Play from the beginning, no Download: Apple TV has no
///   downloads), whose Remove from Continue Watching takes the card off Home, focus going to the next shelf's card;
///   held on a Discover poster, its Mark as watched marks the poster watched ("Watched" in its value, not "selected"),
///   a short press still opens the poster's Detail screen, and the menu's Choose a stream opens the movie's streams;
/// - Home's first card marked watched from its menu: Hide watched titles (Settings → Appearance) takes it off Home and
///   brings it back when switched off; with Hide titles under posters the focused card alone shows its title, the
///   others their artwork, in room the shelf keeps (the shelves don't move as focus does), and the card reads to
///   VoiceOver as before;
/// - focus the user moved to the tab bar while a screen's first item is still to come stays there once it comes
///   (test_addon.py's late catalog addon, its answers held until the test lets them come): Discover's first poster,
///   opened on the catalog's genre link, and Home's first card, the catalog's row moved to the top in Settings → Home;
/// - a failed shelf and Discover's failed next page (the test addon server's failing catalog addon alone): Down from the
///   shelf above's first card reaches the failed shelf's Retry at the row's trailing end, and Down from the first
///   poster of Discover's last row reaches the Retry centred under the grid, whichever column the poster is in.
final class BrowseTests: TortugaUITestCase {
    func testBoardMovesAlongAndDownOpensACardAndMenuReturnsFocusToIt() throws {
        try launchDev(addon: true)
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        shot("board")
        press(.right)
        requireFocus("board.row.0.item.1", "Right moves focus along the shelf")
        let opened = focusIntoShelf("board.row.1", with: .down, "Down moves focus to the next shelf")
        shot("next shelf")
        press(.select)
        requireDetail("Select opens the card's Detail screen")
        shot("detail")
        press(.menu)
        requireFocus(opened, "Menu returns to the Board with focus on the card that was opened")
        shot("back on the board")
    }

    func testSeeAllOpensDiscoverAndAGenreFromTheMenuChangesIt() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        let first = element("board.row.0.item.0").label
        // A remote's rows stop at 20 cards and end in a See all card; none in the heading, which only Up would reach.
        moveFocus(to: "board.row.0.item.19", with: .right, limit: 19, "Right moves along the first shelf to its 20th card")
        if element("board.row.0.item.20").exists { fail("the first shelf shows a 21st card, past a remote's 20") }
        if element("board.row.0.seeAll").exists { fail("the first shelf's heading shows See all on tvOS") }
        press(.right)
        requireFocus("board.row.0.seeAllCard", "Right from the 20th card moves focus to the shelf's See all card")
        shot("see all card")
        press(.select)
        requireFocus("discover.item.0", timeout: 30, "See all opens Discover on the shelf's catalog with focus on its first poster")
        let opened = element("discover.item.0").label
        if opened != first { fail("Discover's first poster is \(opened), not the shelf's first card, \(first): not the shelf's catalog") }
        require("discover.filter.0", "Discover shows the Genre menu")
        let before = menuText("discover.filter.0")
        shot("discover")
        // Up from the grid to the filters above it, then along them to Genre.
        press(.up)
        for _ in 0..<4 where !holdsFocus("discover.filter.0") { press(.right) }
        if !holdsFocus("discover.filter.0") { fail("Up and Right move along the filters to Genre: focus is on \(focusedId ?? "nothing")") }
        shot("genre focused")
        press(.select)
        // Every genre shows in the panel with its label (once, a white bar over unreadable, clipped rows).
        let panel = require("discover.filter.0.panel", timeout: 5, "Select on Genre opens its panel")
        requireFocus("discover.filter.0.option.0", "the selected genre (All) has focus in the panel")
        var genres: [String] = []
        for index in 0..<5 {
            let option = require("discover.filter.0.option.\(index)", timeout: 5, "the Genre panel lists genre \(index)")
            if option.label.isEmpty { fail("genre \(index) has no label") }
            if !panel.frame.contains(option.frame) {
                fail("genre \(index) (\(option.label)) lies outside the panel: \(option.frame) in \(panel.frame)")
            }
            genres.append(option.label)
        }
        if Set(genres).count != genres.count { fail("the panel's genres repeat: \(genres)") }
        shot("genre panel")
        press(.down, count: 3)
        requireFocus("discover.filter.0.option.3", "Down moves focus along the genres")
        press(.select)
        requireGone("discover.filter.0.panel", "picking a genre closes the panel")
        let changed = waitUntil(30) {
            let text = menuText("discover.filter.0")
            return text != before && text.contains(genres[3]) && element("discover.item.0").exists
        }
        if !changed { fail("picking \(genres[3]) leaves the Genre filter on \(menuText("discover.filter.0"))") }
        shot("discover genre \(menuText("discover.filter.0"))")
    }

    func testSearchListsResultsAndKeepsARecentSearch() throws {
        try launchDev()
        // The Board takes focus once at the start: after that the tab bar keeps it.
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        moveFocus(to: "nav.board", with: .up, "Up moves focus to the tab bar")
        moveFocus(to: "nav.search", with: .right, "Right moves along the tabs to Search")
        let field = app.searchFields.firstMatch
        if !field.exists(within: 10) { fail("Search shows no search field") }
        press(.down)
        field.typeText("matrix")
        requireCard("search.group.0.item.0", timeout: 30, "typing matrix lists results")
        shot("results")
        let opened = moveFocus(prefix: "search.group.", with: .down, "Down moves focus from the keyboard to the results")
        press(.select)
        requireDetail("Select on a result opens its Detail screen")
        press(.menu)
        requireFocus(opened, "Menu returns to the results with focus on the result that was opened")
        shot("back on the results")
        // Another visit finds Search as it was left: the query and its results.
        moveFocus(to: "nav.search", with: .up, limit: 10, "Up moves focus to the tab bar")
        press(.left)
        requireFocus("nav.discover", "Left moves focus to Discover")
        press(.right)
        requireFocus("nav.search", "Right moves focus back to Search")
        requireCard("search.group.0.item.0", "Search shown again lists the results it was left with")
        if !field.exists(within: 5) { fail("Search shown again shows no search field") }
        let kept = field.value as? String ?? ""
        if !kept.contains("matrix") { fail("Search shown again has \(kept.debugDescription) in its field, not matrix") }
        shot("search again")
        // The query deleted on the keyboard, the opened result's query is a recent search.
        press(.down)
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "matrix".count))
        let recent = require("search.recent.0", "the emptied field lists the recent searches")
        if !recent.label.contains("matrix") { fail("the recent search is \(recent.label), not matrix") }
        shot("recent searches")
        // Down through the keyboard to the list; the heading's Clear or a row's remove button may take focus on the way
        // (Left from a remove button is its row).
        for _ in 0..<8 where focusedId != "search.recent.0" {
            press(focusedId?.hasSuffix(".remove") == true ? .left : .down)
        }
        requireFocus("search.recent.0", "Down moves focus to the recent search")
        press(.select)
        requireCard("search.group.0.item.0", timeout: 30, "Select on the recent search lists its results again")
        // The system field's accessibility value can carry its dictation hint ("Hold (the microphone button) to
        // dictate in English") after the text.
        let text = field.value as? String ?? ""
        if !text.contains("matrix") { fail("the field shows \(text.debugDescription), not matrix") }
        shot("recent search results")
    }

    func testMenuGoesBackFromScreensStillLoadingAndLeavesTheAppFromTheTabBar() throws {
        // The Board, the details of a movie whose streams answer late, and on top a movie whose meta answers late
        // (tools/media/test_addon.py's tortuga-slow-streams and tortuga-slow-meta, 25 s each).
        try launchDev(addon: true, open: ["board", "detail:movie:tortuga-slow-streams", "detail:movie:tortuga-slow-meta"])
        require("detail.loading", timeout: 30, "a Detail whose meta is late shows its loading view")
        requireFocusOffTheTabBar("the loading Detail")
        shot("detail loading")
        press(.menu)
        requireFocus("detail.play", timeout: 15, "Menu on the loading Detail returns to the details under it with focus on Play")
        shot("back on the details")
        press(.select)
        let heading = require("streams.title", "Select on Play opens the movie's streams")
        if heading.label != "Tortuga Slow Streams Test" { fail("the Streams panel is headed \"\(heading.label)\", not the movie's title") }
        if element("streams.row.0").exists { fail("the streams listed a row before their addon answered") }
        requireFocusOffTheTabBar("the Streams still loading")
        shot("streams loading")
        press(.menu)
        requireFocus("detail.play", "Menu on the Streams still loading returns to the details with focus on Play")
        press(.menu)
        requireFocus("board.row.0.item.0", timeout: 90, "Menu on the details returns to the Board, where the first card takes focus")
        // The Board holds that first focus for a moment (`FocusRestore`'s 1.5 s, which ends showing nothing): waited
        // out while the card keeps it.
        _ = holds(for: 2) { holdsFocus("board.row.0.item.0") }
        shot("board")
        press(.menu)
        requireFocus("nav.board", "Menu on the Board, the Home tab's root, moves focus to the tab bar")
        shot("tab bar")
        XCUIRemote.shared.press(.menu)
        // Out of the app nothing in it has focus; the simulator goes on reporting the app as running in the foreground.
        if !waitUntil(10, { !focused.exists }) {
            fail("Menu on the tab bar doesn't leave the app for the Home screen: focus is on \(focusedId ?? "nothing")")
        }
        shot("home screen")
    }

    func testHoldingSelectOpensAMediaItemsMenuAndAPressStillOpensTheItem() throws {
        launch(["--dev", "--dev-progress", "movie:tt0111161:tt0111161:600:6000"] + Self.harnessSettings.flatMap { ["--dev-setting", $0] })
        requireFocus("board.cw.0", timeout: 90, "Continue Watching's card has focus once Home shows the movie in progress")
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        let remove = "board.cw.0.menu.removeFromContinueWatching"
        require(remove, timeout: 5, "holding Select on the card opens its menu")
        require("board.cw.0.menu.playFromStart", "the menu of the movie in progress offers Play from the beginning")
        if element("board.cw.0.menu.download").exists { fail("the card's menu offers Download; Apple TV has no downloads") }
        moveFocus(to: remove, with: .down, limit: 4, "Down moves along the menu to Remove from Continue Watching")
        shot("continue watching menu")
        press(.select)
        requireGone("board.cw.0", "Remove from Continue Watching takes the card off Home")
        // Focus goes to what took the card's place: Home's shortcuts, which Continue Watching had above them.
        if !waitUntil(5, { ["board.row.", "board.shortcut."].contains { focusedId?.hasPrefix($0) == true } }) {
            fail("with the card gone, focus is on \(focusInTabBar ? "the tab bar" : focusedId ?? "nothing"), not Home's shortcuts or a card")
        }
        // The shortcuts and the shelves below move up into Continue Watching's place.
        screenSettles()
        shot("continue watching removed")

        moveFocus(to: "nav.board", with: .up, "Up moves focus to the tab bar")
        press(.right)
        requireFocus("nav.discover", "Right moves focus to Discover")
        require("discover.item.0", timeout: 60, "Discover shows its grid")
        // Down into the screen first: an arrow along the tab bar would change the tab.
        press(.down)
        walkFocus(to: "discover.item.0", limit: 6, "Down reaches Discover's first poster")
        let title = element("discover.item.0").label
        if isWatched(element("discover.item.0")) { fail("Discover's first poster, \(title), is watched before it is marked") }
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        let watched = "discover.item.0.menu.markWatched"
        require(watched, timeout: 5, "holding Select on the poster opens its menu")
        moveFocus(to: watched, with: .up, limit: 4, "Mark as watched, the menu's first entry, has focus")
        shot("discover poster menu")
        press(.select)
        let marked = element("discover.item.0")
        if !waitUntil(10, { marked.exists && isWatched(marked) }) { fail("Mark as watched doesn't mark \(title) watched: its value reads \(itemState(marked))") }
        if marked.isSelected { fail("the watched poster \(title) reads as selected; watched is its value") }
        requireFocus("discover.item.0", "focus returns to the poster after its menu")
        shot("discover poster watched")
        press(.select)
        requireDetail("a short press on the poster still opens its Detail screen")
        shot("discover poster detail")
        press(.menu)
        requireFocus("discover.item.0", "Menu returns to Discover with focus on the poster")

        // A menu entry the poster's press doesn't offer: Choose a stream opens the movie's streams.
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        let choose = "discover.item.0.menu.chooseStream"
        require(choose, timeout: 5, "holding Select on the poster opens its menu again")
        moveFocus(to: choose, with: .down, limit: 4, "Down moves along the menu to Choose a stream")
        press(.select)
        let heading = require("streams.title", timeout: 30, "Choose a stream opens the movie's streams")
        if heading.label != title { fail("the Streams panel is headed \"\(heading.label)\", not \"\(title)\"") }
        shot("discover poster streams")
    }

    func testHomeHidesWatchedTitlesAndTheTitlesUnderPosters() throws {
        try launchDev()
        let id = "board.row.0.item.0"
        requireFocus(id, timeout: 90, "the first card has focus once the Board's shelves load")
        let first = element(id)
        let title = first.label
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        let watched = "\(id).menu.markWatched"
        require(watched, timeout: 5, "holding Select on the card opens its menu")
        moveFocus(to: watched, with: .up, limit: 4, "Mark as watched, the menu's first entry, has focus")
        press(.select)
        if !waitUntil(10, { first.exists && isWatched(first) }) { fail("Mark as watched doesn't mark \(title) watched: its value reads \(itemState(first))") }
        screenSettles()
        shot("home-default")

        changeDevSetting("appearance.hideWatched=true")
        let card = { self.app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'board.row.0.item.' AND label == %@", title)).firstMatch }
        if !waitUntil(10, { !card().exists }) { fail("with Hide watched titles on, the watched \(title) stays on Home's first shelf") }
        requireCard(id, "with Hide watched titles on, the first shelf keeps its unwatched titles")
        screenSettles()
        shot("home-hide-watched")
        changeDevSetting("appearance.hideWatched=false")
        if !waitUntil(10, { card().exists }) { fail("with Hide watched titles off again, \(title) doesn't come back to Home") }

        moveFocus(to: id, with: .left, limit: 6, "Left brings focus back to the first card")
        let shelf = requireCard("board.row.1.item.0", timeout: 30, "the Board shows a second shelf")
        let top = shelf.frame.minY
        let value = first.value as? String
        changeDevSetting("appearance.hidePosterCaptions=true")
        screenSettles()
        if first.label != title || first.value as? String != value {
            fail("without its caption the card reads \"\(first.label)\", \(first.value ?? "no value"), not \"\(title)\", \(value ?? "no value")")
        }
        shot("home-hide-captions")
        press(.right)
        requireFocus("board.row.0.item.1", "Right moves focus along the shelf")
        screenSettles()
        if abs(shelf.frame.minY - top) > 2 { fail("the second shelf moved from y \(Int(top)) to \(Int(shelf.frame.minY)) as focus moved without captions") }
        shot("home-hide-captions next card")
    }

    func testDiscoversLateFirstPosterLeavesFocusOnTheTabBar() throws {
        try lateCatalog("hold")
        let late = try lateAddonURL
        launch(["--dev", "--dev-addon", late] + Self.harnessSettings.flatMap { ["--dev-setting", $0] })
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        guard let manifest = late.addingPercentEncoding(withAllowedCharacters: .alphanumerics),
              let link = URL(string: "stremio:///discover/\(manifest)/movie/tortuga-late?genre=Late") else {
            return fail("no genre link for \(late)")
        }
        openFromOutside(link)
        require("discover.filter.0", timeout: 15, "the genre link opens Discover")
        if !waitUntil(10, { menuText("discover.filter.0").contains("Late") }) {
            fail("the genre link opens Discover on \(menuText("discover.filter.0")), not the Late genre")
        }
        // The user's moves while the catalog is held: down into Discover's filters and back up to the tab bar, each one
        // checked (a Down at once after the tab changed left focus on the tab bar: no move).
        for _ in 0..<3 where focusInTabBar { press(.down) }
        if focusInTabBar { fail("Down doesn't move focus from the tab bar into Discover's filters") }
        moveFocus(to: "nav.discover", with: .up, limit: 3, "Up moves focus back to the tab bar")
        if element("discover.item.0").exists { fail("Discover shows a poster before the late catalog answers") }
        shot("discover loading, tab bar focused")
        try lateCatalog("release")
        require("discover.item.0", timeout: 30, "the late catalog's poster shows once it answers")
        requireFocusStaysInTabBar("Discover's first poster, come after the user moved focus to the tab bar")
        shot("discover first poster, tab bar focused")
    }

    func testHomesLateFirstCardLeavesFocusOnTheTabBar() throws {
        // Settings → Home's rows reordered by the remote, then Home: longer than the run's default 5 minutes.
        executionTimeAllowance = 600
        try lateCatalog("hold")
        // Started on Settings: Home asks for its shelves once its tab shows.
        launch(["--dev", "--dev-addon", try lateAddonURL, "--dev-open", "settings"] + Self.harnessSettings.flatMap { ["--dev-setting", $0] })
        openSection("home")
        let rows = requireHomeRows("Settings → Home lists Home's rows")
        guard let late = (0..<rows).first(where: { homeRowTitle($0).withoutDirectionMarks.hasPrefix("Tortuga Late") }) else {
            return fail("Settings → Home doesn't list the late catalog's row: \((0..<rows).map(homeRowTitle))")
        }
        let title = homeRowTitle(late)
        // Down to the row, Right to its Move up, then Select again and again: focus moves with the row.
        let controls = ["settings.home.row.\(late)", "settings.home.row.\(late).up", "settings.home.row.\(late).down"]
        for _ in 0..<(late + 4) where !controls.contains(focusedId ?? "") { press(.down) }
        moveFocus(to: "settings.home.row.\(late).up", with: .right, limit: 2, "Down and Right reach \(title)'s Move up")
        for index in stride(from: late, to: 0, by: -1) {
            press(.select)
            requireHomeRow(index - 1, titled: title, "Select on Move up moves \(title) up one place")
        }
        focusTabBar()
        moveFocus(to: "nav.board", with: .left, limit: 6, "Left along the tab bar reaches Home")
        require("board.row.0.loading", timeout: 30, "Home's first shelf is the late catalog's, still loading")
        // The user's moves while the catalog is held: down onto Home's shortcuts and back up to the tab bar.
        moveFocus(prefix: "board.shortcut.", with: .down, limit: 2, "Down moves focus onto Home's shortcuts")
        moveFocus(to: "nav.board", with: .up, limit: 3, "Up moves focus back to the tab bar")
        if element("board.row.0.item.0").exists { fail("Home shows its first card before the late catalog answers") }
        shot("home loading, tab bar focused")
        try lateCatalog("release")
        require("board.row.0.item.0", timeout: 30, "Home's first card shows once the late catalog answers")
        requireFocusStaysInTabBar("Home's first card, come after the user moved focus to the tab bar")
        shot("home first card, tab bar focused")
    }

    func testDownFromAShelfReachesTheFailedShelfsRetry() throws {
        launch(["--dev", "--dev-addons-only", "--dev-addon", try failingAddonURL] + Self.harnessSettings.flatMap { ["--dev-setting", $0] })
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        let retry = require("board.row.1.retry", timeout: 30, "the failed catalog's shelf shows Retry under the first shelf")
        shot("failed shelf")
        press(.down)
        if !waitUntil(3, { holdsFocus("board.row.1.retry") }) {
            fail("Down from the first card leaves focus on \(focusedId ?? "nothing"), not on the failed shelf's Retry at \(retry.frame)")
        }
        press(.up)
        requireFocus(prefix: "board.row.0.item.", "Up from Retry comes back to the shelf above")
    }

    func testDownFromDiscoversLastRowReachesItsRetry() throws {
        let failing = try failingAddonURL
        launch(["--dev", "--dev-addons-only", "--dev-addon", failing] + Self.harnessSettings.flatMap { ["--dev-setting", $0] })
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        guard let manifest = failing.addingPercentEncoding(withAllowedCharacters: .alphanumerics),
              let link = URL(string: "stremio:///discover/\(manifest)/movie/tortuga-failing-more") else {
            return fail("no Discover link for \(failing)")
        }
        openFromOutside(link)
        requireFocus("discover.item.0", timeout: 30, "the catalog's link opens Discover with focus on its first poster")
        let retry = require("discover.more.retry", timeout: 30, "the next page's failure shows Retry under the posters")
        // Down the first column, at the leading edge, to its last poster (the grid makes its rows as they scroll in),
        // then once more: Retry, centred under the grid.
        for _ in 0..<8 {
            let here = focusedId ?? "nothing"
            press(.down)
            if waitUntil(3, { holdsFocus("discover.more.retry") }) {
                shot("discover retry focused")
                return
            }
            if focusedId == here {
                shot("discover last row, focus stays")
                return fail("Down from \(here) leaves focus on it, not on Retry at \(retry.frame)")
            }
        }
        fail("eight presses of Down from the first poster leave focus on \(focusedId ?? "nothing"), not on Retry")
    }

    /// test_addon.py's late catalog addon, beside the test addon on its server.
    private var lateAddonURL: String {
        get throws {
            try addonURL.replacingOccurrences(of: "/manifest.json", with: "/late/manifest.json")
        }
    }

    /// The test addon server's failing catalog addon, beside the test addon: a catalog whose next pages fail, then one
    /// that fails.
    private var failingAddonURL: String {
        get throws {
            try addonURL.replacingOccurrences(of: "/manifest.json", with: "/failing/manifest.json")
        }
    }

    /// Holds the late catalog's answers from now on (`hold`), or answers the held ones and the next at once (`release`).
    private func lateCatalog(_ action: String) throws {
        guard let url = URL(string: try addonURL.replacingOccurrences(of: "/manifest.json", with: "/late/\(action)")) else {
            throw HarnessError("TORTUGA_ADDON is not a URL")
        }
        _ = try Data(contentsOf: url)
    }

    /// Fails with `what` when focus leaves the tab bar within 3 s: a screen's focus restore takes it within a moment.
    private func requireFocusStaysInTabBar(_ what: String) {
        if waitUntil(3, { focusedId?.hasPrefix("nav.") != true }) {
            fail("\(what): focus left the tab bar for \(focusedId ?? "nothing")")
        }
    }

    /// Fails with `what` when focus is on the tab bar, from which Menu leaves the app, once the screen has shown: still
    /// there after 5 s (the system gives the tab bar focus at launch, which the screen takes as it shows), or back on
    /// it within the 2 s after (the screen that had it gone). (What holds focus on a screen still loading has no
    /// identifier, and a scroll view holding it doesn't report it.)
    private func requireFocusOffTheTabBar(_ what: String) {
        if !waitUntil(5, { !focusInTabBar }) || !holds(for: 2, { !focusInTabBar }) {
            fail("\(what) leaves focus on the tab bar (\(focusedId ?? "no identifier"))")
        }
    }

    /// Presses `button` until an element whose identifier starts with `prefix` has focus; its identifier.
    private func moveFocus(prefix: String, with button: XCUIRemote.Button, limit: Int = 6, _ what: String) -> String {
        for presses in 0...limit {
            if let id = focusedId, id.hasPrefix(prefix) { return id }
            if presses < limit { press(button) }
        }
        fail("\(what): \(limit) presses left focus on \(focusedId ?? "nothing"), not \(prefix)…")
        return ""
    }

    /// Waits for the Detail screen, its loading view, title or error, and for focus on it: Menu goes back from the
    /// screen that has focus, which while Detail slides in is still the one under it.
    private func requireDetail(_ what: String) {
        let shown = waitUntil(20) {
            ["detail.loading", "detail.title", "detail.error"].contains { element($0).exists }
        }
        if !shown { fail("\(what): no Detail screen") }
        if !waitUntil(5, { focusedId?.hasPrefix("detail.") == true }) {
            fail("\(what): focus is on \(focusInTabBar ? "the tab bar" : focusedId ?? "nothing"), not on the Detail screen")
        }
    }
}
