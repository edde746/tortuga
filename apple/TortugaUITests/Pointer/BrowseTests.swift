import XCTest

/// The browse screens by touch on iPhone and iPad and with the pointer and keyboard on the Mac, on live Cinemeta (the
/// dev profile's default addon):
/// - the Board lists its shelves; scrolling sideways (a swipe on iOS) moves the first shelf along and a tap or click on
///   a card of the next shelf opens its Detail screen, whose back button returns to the Board; scrolling down (a swipe
///   up on iOS) moves the page to the shelves below, on iPad in landscape (its portrait holds the shelves whole);
/// - a shelf's "See all", one line of text, takes taps and clicks in the least hit target (`requireHitTarget`) and opens
///   Discover on its catalog (on iOS its tab selected), where a genre picked from the Genre
///   menu changes the selection and the grid; on iOS the row of filters fits the screen before and after (on iPhone a
///   capsule is cut short where the row is too narrow);
/// - Search lists results for "matrix" as it is typed (on the Mac its field is the screen's header row, on the
///   toolbar's band, and keeps every letter); the keyboard's Search key (Return on the Mac) keeps the query, a result
///   opens Detail and back, and the cleared field lists "matrix" as a recent search, which a tap or click searches
///   again; the field's clear button, a recent search's remove and Clear take taps and clicks in the least hit target;
/// - a Continue Watching card's value carries its progress; its menu (a long press on iOS, a right click on the Mac)
///   offers Remove from Continue Watching, which takes the card off Home, and a Discover poster's Mark as watched,
///   which marks the poster watched ("Watched" in its value, not "selected");
/// - Home's first card marked watched from its menu: Hide watched titles (Settings → Appearance) takes it off Home and
///   brings it back when switched off; Hide titles under posters leaves the artwork alone, the rows shorter, while the
///   card reads to VoiceOver as before;
/// - the test addon's series left at 12 s of its first episode, in Continue Watching: the card's menu offers Play from
///   the beginning, which opens the series' details and, auto-play off and no stream played before, the episode's
///   streams, whose pick plays from 0; back on Home, the card's Choose a stream opens the episode's streams;
/// - Library's two views keep a scroll position each (the test addon's 60 Tortuga Pages titles in the library and in
///   Continue Watching): the library scrolled down to its 31st title, Continue Watching opens at its top, and the
///   library, picked again, shows its 31st title where it was left.
/// Each platform's own: `BrowseTests+iOS` (the software keyboard, a long press's lift, glass buttons under an item
/// menu, the Tortuga Pages shelves), `BrowseTests+macOS` (Search's insertion point).
final class BrowseTests: TortugaUITestCase {
    func testBoardScrollsAlongAndDownAndOpensACard() throws {
        try launchDev(addon: true)
        let first = require("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        requireCard("board.row.1.item.0", timeout: 30, "the Board shows a second shelf")
        shot("board")
        let x = first.frame.minX
        #if os(iOS)
        ready(first).swipeLeft()
        #else
        first.scroll(byDeltaX: -600, deltaY: 0)
        #endif
        if !waitUntil(5, { !first.exists || first.frame.minX < x - 50 }) { fail("scrolling sideways doesn't move the first shelf along") }
        shot("first shelf moved along")
        let card = element("board.row.1.item.0")
        let title = card.label
        activate(card)
        requireDetail("\(Self.tapping) on a card opens its Detail screen")
        shot("detail of \(title)")
        goBack()
        #if os(iOS)
        // An iPad in portrait holds the dev profile's four shelves whole: in landscape, where it launches again
        // (`quitAndTurn`), the page has room to move.
        if isPad {
            Self.quitAndTurn(to: .landscapeLeft)
            addTeardownBlock { TortugaUITestCase.quitAndTurn(to: .portrait) }
            try launchDev(addon: true)
        }
        let back = requireCard("board.row.1.item.0", timeout: 90, isPad ? "the Board shows its second shelf in landscape" : "back returns to the Board")
        let y = back.frame.minY
        appWindow.swipeUp()
        #else
        let back = requireCard("board.row.1.item.0", "back returns to the Board")
        let y = back.frame.minY
        back.scroll(byDeltaX: 0, deltaY: -400)
        #endif
        if !waitUntil(5, { !back.exists || back.frame.minY < y - 50 }) { fail("scrolling down doesn't move the page") }
        shot("page moved down")
    }

    func testSeeAllOpensDiscoverAndAGenreFromTheMenuChangesIt() throws {
        try launchDev()
        let seeAll = require("board.row.0.seeAll", timeout: 90, "the first shelf shows See all")
        requireHitTarget(seeAll, "the first shelf's See all")
        shot("board with See all")
        activate(seeAll)
        require("discover.item.0", timeout: 30, "See all opens Discover on the shelf's catalog")
        #if os(iOS)
        let discover = phoneTab("nav.discover")
        if !waitUntil(5, { discover.isSelected }) { fail("See all doesn't select the Discover tab") }
        #endif
        let genre = require("discover.filter.0", "Discover shows the Genre menu")
        let before = menuText("discover.filter.0")
        #if os(iOS)
        requireFiltersOnScreen(Self.discoverFilters, "Discover's filters")
        #else
        let shown = element("discover.item.0").label
        #endif
        shot("discover")
        activate(genre)
        let option = require("discover.filter.0.option.3", "the Genre menu lists its genres")
        shot("genre menu")
        pickMenuEntry(option)
        #if os(iOS)
        let changed = waitUntil(30) { menuText("discover.filter.0") != before && element("discover.item.0").exists }
        if !changed { fail("picking a genre leaves the Genre menu on \(menuText("discover.filter.0"))") }
        requireFiltersOnScreen(Self.discoverFilters, "Discover's filters with a genre picked")
        #else
        if !waitUntil(30, { element("discover.item.0").exists && element("discover.item.0").label != shown }) {
            fail("picking a genre leaves the grid on \(shown)")
        }
        if !before.isEmpty, menuText("discover.filter.0") == before { fail("picking a genre leaves the Genre menu on \(before)") }
        #endif
        shot("discover genre \(menuText("discover.filter.0"))")
    }

    func testSearchListsResultsAndKeepsARecentSearch() throws {
        try launchDev()
        showDestination("nav.search")
        #if os(iOS)
        let field = require("search.field", "Search shows its field")
        #else
        let field = requireHeaderSearchField("Search shows its field")
        #endif
        activate(field)
        field.typeText("matrix")
        requireCard("search.group.0.item.0", timeout: 30, "typing matrix lists results")
        requireHitTarget(require("search.clear", "the field with text has its clear button"), "the field's clear button")
        #if os(macOS)
        if (field.value as? String) != "matrix" { fail("typed matrix, the field shows \(String(describing: field.value))") }
        #endif
        shot("results")
        // The keyboard's Search key (Return on the Mac) keeps the query as a recent search.
        #if os(iOS)
        field.typeText("\n")
        #else
        field.typeText("\r")
        #endif
        activate(element("search.group.0.item.0"))
        requireDetail("\(Self.tapping) on a result opens its Detail screen")
        goBack()
        #if os(iOS)
        activate(require("search.clear", "back returns to the results"))
        #else
        requireCard("search.group.0.item.0", "back returns to the results")
        clearSearchField(field)
        #endif
        let recent = require("search.recent.0", "the cleared field lists the recent searches")
        if !recent.label.contains("matrix") { fail("the recent search is \(recent.label), not matrix") }
        requireHitTarget(require("search.recent.0.remove", "the recent search has its remove button"), "the recent search's remove button")
        requireHitTarget(require("search.recent.clear", "the recent searches have Clear"), "the recent searches' Clear")
        shot("recent searches")
        activate(recent)
        requireCard("search.group.0.item.0", timeout: 30, "\(Self.tapping) on the recent search lists its results again")
        if (require("search.field", timeout: 5, "the recent search's results keep the field").value as? String) != "matrix" {
            fail("the field shows \(String(describing: field.value)), not matrix")
        }
        shot("recent search results")
    }

    func testAMediaItemsMenuRemovesFromContinueWatchingAndMarksWatched() throws {
        launch(["--dev", "--dev-progress", "movie:tt0111161:tt0111161:600:6000"])
        let card = require("board.cw.0", timeout: 90, "Home shows the movie in progress in Continue Watching")
        // 600 s of 6000: VoiceOver reads its progress after its title.
        if !waitUntil(10, { progressState(card) == "10%" }) {
            fail("the movie's Continue Watching card, \(card.label), reads \(itemState(card)) as its value, without its progress 10%")
        }
        openItemMenu(card)
        let remove = require("board.cw.0.menu.removeFromContinueWatching", timeout: 5, "a \(Self.menuGesture) on the card opens its menu")
        shot("continue watching menu")
        pickMenuEntry(remove)
        requireGone("board.cw.0", "Remove from Continue Watching takes the card off Home")
        showDestination("nav.discover")
        let poster = require("discover.item.0", timeout: 60, "Discover shows its grid")
        if isWatched(poster) { fail("Discover's first poster, \(poster.label), is watched before it is marked") }
        openItemMenu(poster)
        let watched = require("discover.item.0.menu.markWatched", timeout: 5, "a \(Self.menuGesture) on the poster opens its menu")
        shot("discover poster menu")
        pickMenuEntry(watched)
        let marked = element("discover.item.0")
        if !waitUntil(10, { marked.exists && isWatched(marked) }) { fail("Mark as watched doesn't mark \(poster.label) watched: its value reads \(itemState(marked))") }
        if marked.isSelected { fail("the watched poster \(poster.label) reads as selected; watched is its value") }
        shot("discover poster watched")
    }

    func testHomeHidesWatchedTitlesAndTheTitlesUnderPosters() throws {
        try launchDev()
        let first = requireCard("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        let title = first.label
        openItemMenu(first)
        pickMenuEntry(require("board.row.0.item.0.menu.markWatched", timeout: 5, "a \(Self.menuGesture) on the card opens its menu"))
        if !waitUntil(10, { first.exists && isWatched(first) }) { fail("Mark as watched doesn't mark \(title) watched: its value reads \(itemState(first))") }
        screenSettles()
        shot("home-default")

        changeDevSetting("appearance.hideWatched=true")
        let card = { self.app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'board.row.0.item.' AND label == %@", title)).firstMatch }
        if !waitUntil(10, { !card().exists }) { fail("with Hide watched titles on, the watched \(title) stays on Home's first shelf") }
        requireCard("board.row.0.item.0", "with Hide watched titles on, the first shelf keeps its unwatched titles")
        screenSettles()
        shot("home-hide-watched")
        changeDevSetting("appearance.hideWatched=false")
        if !waitUntil(10, { card().exists }) { fail("with Hide watched titles off again, \(title) doesn't come back to Home") }

        let shelf = requireCard("board.row.1.item.0", timeout: 30, "the Board shows a second shelf")
        let top = shelf.frame.minY
        let value = first.value as? String
        changeDevSetting("appearance.hidePosterCaptions=true")
        if !waitUntil(10, { shelf.exists && shelf.frame.minY < top - 10 }) {
            fail("with Hide titles under posters on, the second shelf stays at y \(Int(shelf.frame.minY)): the first's captions keep their room")
        }
        if first.label != title || first.value as? String != value {
            fail("without its caption the card reads \"\(first.label)\", \(first.value ?? "no value"), not \"\(title)\", \(value ?? "no value")")
        }
        screenSettles()
        shot("home-hide-captions")
    }

    func testAContinueWatchingCardPlaysFromTheStartAndChoosesAStream() throws {
        try launchDev(addon: true, progress: [Self.seriesEpisodeInProgress])
        let card = require("board.cw.0", timeout: 90, "Home shows the series' episode in progress in Continue Watching")
        openItemMenu(card)
        let fromStart = require("board.cw.0.menu.playFromStart", timeout: 5, "a \(Self.menuGesture) on the card in progress offers Play from the beginning")
        require("board.cw.0.menu.chooseStream", timeout: 5, "the card's menu offers Choose a stream")
        shot("continue watching menu in progress")
        pickMenuEntry(fromStart)
        activate(require("streams.row.0", timeout: 60, "Play from the beginning (auto-play off, no stream played before) opens the episode's streams"))
        let video = require("player.video", timeout: 20, "\(Self.tapping) on the stream opens the player")
        requirePlayingFromTheStart("Play from the beginning on the card")
        shot("played from the beginning")
        #if os(iOS)
        closePlayer(video, "the controls' back button closes the player")
        #else
        pointerOn(ready(video), at: CGVector(dx: 0.3, dy: 0.4))
        pointerOn(ready(video), at: CGVector(dx: 0.5, dy: 0.5))
        activate(require("player.back", timeout: 5, "the pointer over the video shows the controls"))
        requireGone("player.video", "the back button closes the player")
        #endif
        require("detail.play", "closing the player returns to the series' details")
        goBack()

        let back = require("board.cw.0", timeout: 30, "back on Home, the episode is still in Continue Watching")
        openItemMenu(back)
        pickMenuEntry(require("board.cw.0.menu.chooseStream", timeout: 5, "the card's \(Self.menuGesture) offers Choose a stream"))
        require("streams.row.0", timeout: 60, "Choose a stream on the card opens the episode's streams")
        if element("player.video").exists { fail("Choose a stream on the card opened the player, not the episode's streams") }
        shot("choose a stream from the card")
    }

    func testLibraryAndContinueWatchingKeepTheirOwnScrollPositions() throws {
        try launchDev(addon: true, open: ["library"], progress: Self.pagesInProgress, arguments: Self.pagesInLibrary)
        require("library.item.0", timeout: 90, "the Library lists the test addon's pages")
        #if os(macOS)
        require("library.view", timeout: 5, "the Library shows its view menu")
        #endif
        let libraryView = menuText("library.view")
        scrollTo("library.item.30", "the library scrolls down to its 31st title")
        if liesInside(element("library.item.0")) { fail("the library's 31st title shows with its first: nothing scrolled") }
        shot("library scrolled down")

        activate(element("library.view"))
        pickMenuEntry(require("library.view.option.1", timeout: 5, "the view menu lists Continue Watching"))
        if !waitUntil(5, { !menuText("library.view").isEmpty && menuText("library.view") != libraryView }) {
            fail("picking Continue Watching in the view menu leaves it on \(menuText("library.view"))")
        }
        if !waitUntil(10, { liesInside(element("library.item.0")) }) {
            fail("Continue Watching doesn't open at its top: its first title is at \(element("library.item.0").frame), the window at \(windowFrame)")
        }
        shot("continue watching at its top")

        activate(element("library.view"))
        pickMenuEntry(require("library.view.option.0", timeout: 5, "the view menu lists Library"))
        if !waitUntil(5, { menuText("library.view") == libraryView }) { fail("picking Library in the view menu doesn't show it") }
        if !waitUntil(10, { liesInside(element("library.item.30")) }) {
            fail("the library, picked again, isn't where it was left: its 31st title is at \(element("library.item.30").frame), the window at \(windowFrame)")
        }
        shot("library where it was left")
    }

    #if os(iOS)
    /// Discover's filter capsules: the type, the catalog and the catalog's filters.
    static let discoverFilters = ["discover.type", "discover.catalog"] + (0..<6).map { "discover.filter.\($0)" }
    #endif

    /// A top-level destination (`nav.search`): its tab on iOS (`phoneTab`), its row in the Mac's sidebar.
    private func showDestination(_ id: String) {
        #if os(iOS)
        ready(phoneTab(id)).tap()
        #else
        ready(require(id, timeout: 30, "the sidebar lists \(id)")).click()
        #endif
    }

    /// Waits for the Detail screen: its loading view, title or error.
    func requireDetail(_ what: String) {
        let shown = waitUntil(20) {
            ["detail.loading", "detail.title", "detail.error"].contains { element($0).exists }
        }
        if !shown { fail("\(what): no Detail screen") }
    }
}
