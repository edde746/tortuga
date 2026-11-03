import XCTest
#if os(iOS)
import UIKit
#endif

/// The screenshot tour on iPhone, iPad and the Mac: every screen of the tour once, by touch or the pointer. The tests
/// run in name order and build on each
/// other: the account part on the product signed in at the dev stack, then a dev session (the test addon, the fake
/// Trakt) whose database they share, so the library item, the progress and the watched episode the first ones leave
/// show on the Board and in the Library. The iPad lies in landscape; the iPhone turns to landscape for the player.
final class ScreenshotTour: TourTestCase {
    // MARK: Account

    func test1Account() throws {
        executionTimeAllowance = 1500
        let server = try tourServer
        launchProduct()
        orient()
        #if os(macOS)
        signOutLeftAccount()
        #endif

        let edit = require("signIn.server.edit", timeout: 60, "the sign-in screen shows")
        activate(edit)
        let field = requireTypingFocus("signIn.server.field", "Use your own server opens the server sheet")
        typeReplacing(field, server)
        let submit = require("signIn.server.submit", "the server sheet has Check server")
        if !waitUntil(5, { submit.isEnabled }) { fail("Check server stays disabled with \(server) typed") }
        tourShot("signin-server", "Use your own server, with the dev stack's address typed")
        activate(submit)
        requireGone("signIn.server.field", timeout: 30, "the server check passes and the sheet closes")
        require("signIn.option.oidc", timeout: 60, "the server's sign-in options list its OpenID Connect provider")
        tourShot("signin", "the sign-in options of the self-hosted dev stack (its Dex as the OpenID Connect provider)")

        // A signed-out device can be signed in from one that is: Link from another device shows its code.
        activate(require("signIn.link", "the sign-in screen offers Link from another device"))
        _ = requireCode("link.code", timeout: 30, "Link from another device shows a code")
        tourShot("signin-link", "Link from another device on a signed-out device: the code and its QR code, waiting for a signed-in device's approval")
        activate(require("signIn.back", "the link page has a back button"))
        require("signIn.option.oidc", timeout: 30, "back from the link page, the server's sign-in options show")
        #if os(macOS)
        // Past sign-in the account keeps its session in the data protection keychain, which only a team-signed build
        // may use (an ad-hoc-signed one gets errSecMissingEntitlement): TORTUGA_MAC_SIGN=dev says this build is one.
        if ProcessInfo.processInfo.environment["TORTUGA_MAC_SIGN"] != "dev" {
            for screen in ["profiles", "pin", "profile-editor", "settings-profile", "settings-account"] {
                tourSkip(screen, "an ad-hoc-signed Mac build can't keep a session in the data protection keychain (errSecMissingEntitlement): these need a build signed for team G88U5B5783, run with TEST_RUNNER_TORTUGA_MAC_SIGN=dev")
            }
            return
        }
        #endif

        try signUp(server)
        createProfile("Alex", avatar: 2, pin: nil, shoot: true)
        createProfile("Kids", avatar: 6, pin: [1, 2, 3, 4], shoot: false)
        if !waitUntil(30, { profileTile("Alex").exists && profileTile("Kids").exists }) { fail("the picker doesn't list both profiles") }
        tourShot("profiles", "Who's watching: two profiles, Kids locked with a PIN")
        activate(profileTile("Kids"))
        require("pin.digit.1", "the locked profile asks for its PIN")
        pressPin([1, 2])
        tourShot("pin", "Kids' PIN pad with two digits entered")
        pressPin([3, 4])
        requireGone("pin.digit.1", timeout: 30, "the profile's PIN opens it")

        // Signed in, the profile's card has both of its buttons (a dev session's has Edit alone).
        destination("nav.settings")
        require("settings.profile.manage", timeout: 30, "signed in, the profile's card offers Switch or manage profiles")
        require("settings.profile.edit", "the profile's card offers Edit profile")
        tourShot("settings-profile", "Settings' first page signed in: Kids' card with Edit profile and Switch or manage profiles")
        // Switch or manage profiles: the picker over the running profile, Kids marked as it; Back goes back to it.
        activate(require("settings.profile.manage", "the profile's card offers Switch or manage profiles"))
        if !waitUntil(30, { profileTile("Kids").exists && profileTile("Alex").exists }) { fail("Switch or manage profiles doesn't open the picker") }
        shot("profiles over the running profile")
        activate(require("profiles.close", "the picker over the running profile has Back"))
        require("settings.profile.edit", timeout: 30, "Back closes the picker, back to Settings")
        settingsPage("account")
        require("settings.account.syncNow", timeout: 30, "the account section offers Sync now")
        tourShot("settings-account", "signed in on the dev stack as apple@example.com: status, server, sign-ins, devices")

        // Link a new device: the code field holds only the code, its hint inside it while empty, a code typed in lower
        // case shown in capitals.
        activateShown("settings.account.approveLink", "the account section offers Link a new device")
        let code = requireTypingFocus("settings.account.link.code", "Link a new device asks for the code")
        shot("link a device")
        code.typeText("4ynw-ndmk")
        if !waitUntil(5, { code.value as? String == "4YNW-NDMK" }) {
            fail("the link code typed as 4ynw-ndmk reads \"\(code.value as? String ?? "")\", not 4YNW-NDMK")
        }
        shot("link a device, code typed")
        // Continue with a code no device waits with: back at the code with the server's answer under it and the field
        // empty, as the store's code is (a refused code is cleared). A field keeping the typed code showed one that
        // Continue couldn't send, and wiping it by sending its text to the store would wipe the answer too.
        let linkSubmit = require("settings.account.link.submit", "the link sheet has Continue")
        if !waitUntil(5, { linkSubmit.isEnabled }) { fail("Continue stays disabled with a whole code typed") }
        activate(linkSubmit)
        let refusedText = "No device is waiting with this code"
        let refused = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@ OR value BEGINSWITH %@", refusedText, refusedText)).firstMatch
        if !refused.exists(within: 30) { fail("a code no device waits with isn't refused under the field") }
        let refusedCode = require("settings.account.link.code", timeout: 5, "the refused code brings the sheet back to the code")
        if !waitUntil(5, { !fullText(of: refusedCode).contains("4YNW") }) {
            fail("back at the code after its refusal, the field still reads \"\(fullText(of: refusedCode))\", a code the sheet no longer has")
        }
        if !refused.exists { fail("the refusal went as the field emptied") }
        shot("link a device, code refused")
        activate(require("settings.account.link.cancel", "the link sheet has Cancel"))
        requireGone("settings.account.link.code", "Cancel closes the link sheet")

        // Signed out and in again, as another device would: the account's profiles come back from the server.
        activateShown("settings.account.signOut", "the account section offers Sign out")
        activate(require("settings.account.dialog.confirm", timeout: 10, "Sign out asks first"))
        requireServerOptions(server, "signing out returns to the sign-in screen")
        try signIn()
        if !waitUntil(180, { profileTile("Alex").exists && profileTile("Kids").exists }) {
            fail("signed in again, the account's two profiles don't show")
        }
        // Deleted, so the next run signs up afresh.
        activate(profileTile("Alex"))
        deleteAccount()
    }

    // MARK: Detail

    func test2Detail() throws {
        try launchTour(open: ["board", "detail:movie:tt0111161"])
        orient()
        require("detail.play", timeout: 60, "the movie's details show Play")
        tourShot("detail-movie", "The Shawshank Redemption from Cinemeta, before it is added to the library")
        let library = require("detail.library", "the movie's details show the library button")
        let add = library.label
        activate(library)
        if !waitUntil(10, { element("detail.library").exists && element("detail.library").label != add }) { fail("Add to library doesn't turn into its undo") }

        app.terminate()
        try launchTour(open: ["board", "detail:series:tt7366338"])
        orient()
        require("detail.play", timeout: 60, "the series' details show Play")
        scrollTo("detail.season.0", "the series has its seasons' chips")
        let episode = scrollTo("detail.episode.0", "the season lists its episodes")
        openItemMenu(episode)
        let watched = require("detail.menu.item.markWatched", timeout: 5, "an episode's menu opens")
        tourShot("detail-menu", "the first episode's menu (\(menuGesture))")
        pickMenuEntry(watched)
        if !waitUntil(10, { element("detail.episode.0").exists && isWatched(element("detail.episode.0")) }) { fail("the menu's first entry doesn't mark the episode watched") }
        scrollTo("detail.season.0", "the season chips")
        tourShot("detail-series", "Chernobyl: the season chips and the first season's episodes, the first one watched")
    }

    // MARK: Streams and the player

    func test3Player() throws {
        try launchTour(open: ["board", "detail:movie:tt0111161"])
        orient()
        activate(require("detail.play", timeout: 60, "the movie's details show Play"))
        require("streams.row.0", timeout: 60, "Play opens the Streams sheet with the test addon's streams")
        tourShot("streams", "the Streams sheet over The Shawshank Redemption's details: the test addon's streams")
        orient(player: true)
        activate(element("streams.row.0"))
        let video = require("player.video", timeout: 20, "the remux stream opens the player")
        requirePlayback(1, timeout: 90)

        showControls(video)
        activate(element("player.subtitles"))
        let french = menuEntry("French", "the subtitle button opens its menu")
        tourShot("player-subtitles", "the remux's subtitle menu")
        pickMenuEntry(french)
        if !waitUntil(5, { !french.exists }) { fail("picking French doesn't close the menu") }

        // Paused on a bottom line of the French track, lifted above the controls.
        pauseOnSubtitleLine(video)
        tourShot("player", "the remux paused with the controls up and a French subtitle line above them")

        activate(element("player.audio"))
        let german = menuEntry("German", "the audio button opens its menu")
        tourShot("player-audio", "the remux's audio tracks")
        closeMenu(video, entry: german)
        activate(element("player.speed"))
        let fast = menuEntry("1.5×", "the speed button opens its menu")
        tourShot("player-speed", "the playback speeds")
        closeMenu(video, entry: fast)
        showControls(video)
        activate(element("player.subtitles"))
        #if os(iOS)
        // A landscape iPhone's native subtitle menu is shorter than its entries and XCUITest can't scroll it to its
        // last one, the delay (the player stays landscape when the phone turns): that screen is left out there.
        let delayShown = isRegular || app.buttons
            .matching(NSPredicate(format: "label == 'Subtitle delay' OR label BEGINSWITH 'Subtitle delay,'")).firstMatch
            .exists(within: 5)
        #else
        let delayShown = true
        #endif
        if delayShown {
            pickMenuEntry(menuEntry("Subtitle delay", "the subtitle menu ends with the delay entry"))
            require("player.delay", timeout: 5, "the delay entry opens the subtitle delay")
            #if os(macOS)
            ready(app.steppers.firstMatch.incrementArrows.firstMatch).click()
            #else
            ready("player.delay-Increment").tap()
            #endif
            let reset = element("player.delay.reset")
            if !waitUntil(5, { reset.exists && reset.isEnabled }) { fail("+ doesn't change the delay") }
            tourShot("player-delay", "the subtitle delay, moved once")
            activate(reset)
            closeMenu(video, entry: element("player.delay"))
        } else {
            closeMenu(video, entry: app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Off'")).firstMatch)
            tourSkip("player-delay", "a landscape iPhone's native subtitle menu is shorter than its entries, and XCUITest can't scroll it to its last one, the delay; the iPad's tour shows it")
        }
        showControls(video)
        togglePlaybackInfo()
        require("player.info.panel", timeout: 5, "Playback info in the options menu opens the playback info")
        tourShot("player-info", "the playback info over the paused remux")
        #if os(macOS)
        showControls(video)
        togglePlaybackInfo()
        requireGone("player.info.panel", timeout: 5, "the playback info closes")
        #else
        closeSheet("player.info.close", showing: "player.info.panel", "Close (an iPhone: a pull down) closes the playback info")
        #endif

        activate(require("player.back", timeout: 5, "the controls have the back button"))
        requireGone("player.video", "the back button closes the player")
        require("detail.play", "the back button returns to the movie's details")
        if element("streams.row.0").exists { fail("back from the player, the Streams sheet is open again") }
        orient()
    }

    func test4PlayerEnds() throws {
        try launchTour(open: ["board", "detail:series:tt7366338", "streams:series:tt7366338:tt7366338:1:1"])
        orient(player: true)
        // The H.264 file without a binge group: the next episode has no stream to go on with.
        let unbound = streamRow(containing: "720p")
        revealInSheet(unbound, "the episode's Streams sheet lists the H.264 file without a binge group")
        activate(unbound)
        require("player.video", timeout: 20, "the H.264 stream opens the player")
        requirePlayback(1, timeout: 90)
        #if os(macOS)
        // Out of the window, so up next counts down: the controls coming and going under a pointer over the video count
        // as its moves.
        pointerOutOfTheWindow()
        #endif
        let next = require("player.upNext", timeout: 40, "the end of the 20 s episode offers the next one")
        // Shot as its fill grows, once the button has come up (its frame still).
        _ = settledFrame(of: next)
        tourShot("player-upnext", "Chernobyl S1E1's end: up next, S1E2 and its name, filling as it counts down", still: false)
        #if os(macOS)
        // The pointer coming into the window brings the controls up and lifts the button above them, as a hand on the
        // mouse does before it reaches the button; a click straight from the menu bar would land on the video.
        showControls(element("player.video"))
        #endif
        activate(next)

        // S1E2's Streams sheet comes up over the player, which plays the stream picked there.
        let h264 = require("streams.row.1", timeout: 60, "the next episode's Streams sheet comes up over the player")
        if element("detail.play").exists, element("detail.play").isHittable { fail("the next episode's streams came up over the details, not the player") }
        shot("the next episode's streams over the player")
        activate(h264)
        requireGone("streams.row.1", "picking a stream closes the sheet over the player")
        require("player.video", "the stream picked plays in the player")
        requirePlayback(1, timeout: 60)
        leavePlayer()

        // Back on the series, in portrait on iPhone (its page doesn't scroll to a swipe in landscape); its first
        // episode's streams again, down to the missing file, whose error shows in landscape.
        orient()
        activate(scrollTo("detail.episode.0", "back from the player, the series' details list the first episode"))
        let missing = streamRow(containing: "issing")
        revealInSheet(missing, "the first episode's Streams sheet lists the missing file")
        activate(missing)
        orient(player: true)
        let error = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier IN {'player.error.retry', 'player.error.next', 'player.error.close'}")).firstMatch
        if !error.exists(within: 60) { fail("the missing file doesn't bring up the player's error") }
        tourShot("player-error", "the player's error for the missing file (a 404 from the media server)")
        leavePlayer()
        orient()
    }

    // MARK: Trackers and the Board

    func test5TrackersAndBoard() throws {
        try launchTour()
        orient()
        require("board.row.0.item.0", timeout: 90, "the Board shows its shelves")
        settingsPage("trackers")
        activate(require("settings.trackers.trakt.connect", timeout: 30, "Trackers offers Connect on Trakt's card"))
        require("settings.trackers.connect.code", "Connect shows a device code")
        require("settings.trackers.connect.qr", timeout: 5, "the code comes with its QR code")
        tourShot("trackers-connect", "Connect on Trakt: its device code and QR code (the fake Trakt approves it)")
        requireGone("settings.trackers.connect.code", timeout: 60, "the fake approves the code and the flow closes")
        requireTracker("trakt", connectedAs: Self.fakeTraktUser, "Trakt is connected")
        tourShot("settings-trackers", "Trakt connected as the fake Trakt's user")

        destination("nav.board")
        require("board.cw.0", timeout: 60, "the Board shows Continue Watching")
        let traktShelf = shelfSources("Trakt").firstMatch.exists(within: 60)
        tourShot("board", "the top of Home: Continue Watching (the movie and the episode played), then the shelves"
            + (traktShelf ? "" : "; Trakt's shelves didn't show"))
        let trakt = shelfSources("Trakt").firstMatch
        for _ in 0..<8 where trakt.exists && trakt.frame.minY > app.windows.firstMatch.frame.minY + app.windows.firstMatch.frame.height * 0.3 {
            scrollDown()
        }
        tourShot("board-rows", "Home scrolled: Trakt's shelves and the addons' catalogs")
    }

    // MARK: Discover and Search

    func test6DiscoverAndSearch() throws {
        try launchTour()
        orient()
        require("board.row.0.item.0", timeout: 90, "the Board shows its shelves")
        destination("nav.discover")
        require("discover.item.0", timeout: 30, "Discover shows its grid")
        tourShot("discover", "Discover on its first catalog")
        activate(require("discover.filter.0", "Discover shows the Genre menu"))
        require("discover.filter.0.option.3", timeout: 5, "the Genre menu lists its genres")
        tourShot("discover-filter", "the Genre menu open")
        pickMenuEntry(element("discover.filter.0.option.3"))

        destination("nav.search")
        let field = require("search.field", "Search shows its field")
        activate(field)
        field.typeText("matrix")
        requireCard("search.group.0.item.0", timeout: 30, "typing matrix lists results")
        // The keyboard's Search key (Return on the Mac) keeps the query as a recent search.
        field.typeText("\n")
        hideKeyboard()
        tourShot("search-results", "results for \"matrix\", grouped by catalog")
        activate(require("search.clear", "the field has Clear"))
        let recent = require("search.recent.0", "the cleared field lists the recent searches")
        if !recent.label.contains("matrix") { fail("the recent search is \(recent.label), not matrix") }
        hideKeyboard()
        tourShot("search-recent", "the empty query: recent searches")
    }

    // MARK: Library

    func test7Library() throws {
        try launchTour()
        orient()
        require("board.row.0.item.0", timeout: 90, "the Board shows its shelves")
        destination("nav.library")
        require("library.item.0", timeout: 30, "the Library lists the movie added from Detail")
        tourShot("library", "the library: the view, type and sort menus above the movie added from its Detail screen")
        let libraryView = menuText("library.view")
        activate(require("library.view", "the Library shows its view menu"))
        pickMenuEntry(require("library.view.option.1", timeout: 5, "the view menu lists Continue Watching"))
        if !waitUntil(10, { menuText("library.view") != libraryView && element("library.item.0").exists }) {
            fail("Continue Watching lists nothing")
        }
        tourShot("continue-watching", "Continue Watching: the movie and the episode played")
        // The series' details: the episode played last shows its progress on its thumbnail (a shot of the run, not of
        // the gallery).
        let series = element("library.item.0").label.contains("Chernobyl") ? "library.item.0" : "library.item.1"
        activate(require(series, "Continue Watching lists the series"))
        // Continue Watching resumes the episode: the player on the stream it last played from, or, with no stream to
        // play, its streams over the series' details; either is closed here.
        if waitUntil(30, { element("streams.row.0").exists || element("player.video").exists }) {
            if element("player.video").exists {
                leavePlayer()
            } else {
                #if os(iOS)
                if isRegular {
                    activate(require("streams.close", "the Streams sheet has Close"))
                } else {
                    // The sheet stands at half height: a tap on the dimmed details above it closes it.
                    app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15)).tap()
                }
                #else
                activate(require("streams.close", "the Streams sheet has Close"))
                #endif
                requireGone("streams.row.0", "closing the Streams sheet returns to the series")
            }
        }
        scrollTo("detail.episode.1", "the series' details list its second episode")
        screenSettles()
        shot("episode progress")
    }

    // MARK: Addons

    func test8Addons() throws {
        try launchTour()
        orient()
        require("board.row.0.item.0", timeout: 90, "the Board shows its shelves")
        settingsPage("addons")
        require("addons.row.1", timeout: 30, "Addons lists the installed addons")
        tourShot("addons", "the installed addons: Cinemeta and the test addon")
        activate(element("addons.install"))
        let link = requireTypingFocus("addons.install.link", "Install asks for the addon's link")
        link.typeText(Self.previewAddon)
        activate(require("addons.install.submit", "Install has Continue"))
        if !element("addons.install.confirm").exists(within: 30) { fail("the fetched manifest offers no Install") }
        tourShot("addon-install", "the install preview of WatchHub")
        activate(element("addons.install.cancel"))
        requireGone("addons.install.confirm", "Cancel closes the install preview")
        activate(element("addons.row.0"))
        require("addons.details.close", "a click on an addon opens its details")
        tourShot("addon-details", "Cinemeta's details")
        activate(element("addons.details.close"))
    }

    // MARK: Settings

    func test9Settings() throws {
        try launchTour()
        orient()
        require("board.row.0.item.0", timeout: 90, "the Board shows its shelves")
        destination("nav.settings")
        require("settings.section.playback", timeout: 30, "Settings lists its sections")
        tourShot("settings", "Settings: the profile and the sections")
        for (section, screen, note) in [
            ("playback", "settings-playback", "Playback: per profile, synced"),
            ("device", "settings-device", "This device: decoding, HDR, frame rate, cache"),
            ("about", "settings-about", "About: version, source and licences"),
        ] {
            settingsPage(section)
            tourShot(screen, note)
            leavePage()
        }
    }

    // MARK: Helpers

    /// A manifest the install preview shows (a live addon none of the tour's profiles has).
    static let previewAddon = "https://watchhub.strem.io/manifest.json"

    #if os(iOS)
    private var isRegular: Bool { UIDevice.current.userInterfaceIdiom == .pad }
    private var menuGesture: String { "long press" }
    #else
    private var isRegular: Bool { true }
    private var menuGesture: String { "right click" }
    #endif

    /// The iPad in landscape; the iPhone in portrait, or in landscape for the `player`; the iPhone Duo as the run poses
    /// it (the pose in `TORTUGA_POSE`: XCUIDevice's orientation turns nothing there).
    private func orient(player: Bool = false) {
        #if os(iOS)
        if isFoldable { return }
        let landscape = isRegular || player
        XCUIDevice.shared.orientation = landscape ? .landscapeLeft : .portrait
        _ = waitUntil(5) { (app.frame.width > app.frame.height) == landscape }
        #endif
    }

    /// A top-level destination (`nav.library`): the iPhone's tab, else the tab bar's or the sidebar's.
    private func destination(_ id: String) {
        #if os(iOS)
        if !isRegular {
            ready(phoneTab(id)).tap()
            return
        }
        #endif
        activate(require(id, timeout: 30, "the tabs list \(id)"))
    }

    /// Settings, then `section`'s page, pushed over the list: its row scrolled inside the window first (`scrollTo`; on
    /// iPhone the rows past Trackers lie under the tab bar or below the screen, and on a screen as short as the iPhone
    /// Duo's cover in landscape the list makes them only as they scroll in).
    private func settingsPage(_ section: String) {
        destination("nav.settings")
        require("settings.section.playback", timeout: 30, "Settings lists its sections")
        activate(scrollTo("settings.section.\(section)", "Settings shows \(section)'s row"))
    }

    /// Back from a pushed Settings page: the navigation bar's back button (iOS), the window toolbar's (Mac).
    private func leavePage() {
        #if os(iOS)
        ready(backButton).tap()
        #else
        goBack()
        #endif
    }

    /// Scrolls the page down by about a screen: on the Mac the wheel over the middle of the window's content, right of
    /// the sidebar (a scroll sent to the window element left the Board where it was).
    private func scrollDown() {
        #if os(macOS)
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.6)).scroll(byDeltaX: 0, deltaY: -300)
        #else
        appWindow.swipeUp()
        #endif
    }

    /// Puts the on-screen keyboard away (a scroll dismisses it) so the screen shows whole; the Mac has none.
    private func hideKeyboard() {
        #if os(iOS)
        guard app.keyboards.firstMatch.exists else { return }
        let scroll = app.scrollViews.firstMatch
        ready(scroll.exists ? scroll : app.windows.firstMatch).swipeDown()
        _ = waitUntil(3) { !app.keyboards.firstMatch.exists }
        #endif
    }

    /// Types `text` into `field` in place of what it holds: the Mac keeps the product's data from run to run, the
    /// server field's too.
    private func typeReplacing(_ field: XCUIElement, _ text: String) {
        #if os(macOS)
        field.typeKey("a", modifierFlags: .command)
        #endif
        field.typeText(text)
    }

    #if os(macOS)
    /// The Mac keeps the product's data from run to run (a simulator starts afresh): an account an earlier tour left
    /// signed in is signed out first, from a profile's account settings (Kids' PIN is the tour's). A tour that stopped
    /// in a new account's first profile editor left the account without profiles: one is made to get in.
    private func signOutLeftAccount() {
        let states = ["signIn.server.edit", "editor.name", "profiles.tile.0", "profiles.add", "pin.digit.1", "nav.settings"]
        guard waitUntil(60, { states.contains { element($0).exists } }), !element("signIn.server.edit").exists else { return }
        if element("editor.name").exists || (element("profiles.add").exists && !element("profiles.tile.0").exists(within: 5)) {
            createProfile("Alex", avatar: 2, pin: nil, shoot: false)
            require("profiles.tile.0", timeout: 30, "the new profile shows in the picker")
        }
        if element("profiles.tile.0").exists { openFirstProfile() }
        settingsPage("account")
        activateShown("settings.account.signOut", "the account an earlier tour left offers Sign out")
        activate(require("settings.account.dialog.confirm", timeout: 10, "Sign out asks first"))
        require("signIn.server.edit", timeout: 60, "signing out the account an earlier tour left returns to the sign-in screen")
    }
    #endif

    /// The device flow on the server's OpenID Connect provider: its code goes to the run's hook, which signs it in.
    private func signIn() throws {
        activate(element("signIn.option.oidc"))
        let code = requireCode("deviceCode.code", timeout: 60, "the provider's device flow shows its code")
        try publishDeviceCode(code)
    }

    /// Signs up as the tour's Dex user: a new account goes straight to its first profile's editor. An account an earlier
    /// run left with profiles (it stopped before deleting it) shows them instead: it is deleted and signed up afresh.
    private func signUp(_ server: String) throws {
        try signIn()
        if !waitUntil(180, { ["editor.name", "profiles.tile.0", "profiles.add"].contains { element($0).exists } }) {
            fail("once Dex's login completes, the account shows neither its first profile's editor nor the profile picker")
        }
        // The picker shows a moment before a new account's editor opens over it.
        _ = waitUntil(10) { element("editor.name").exists || element("profiles.tile.0").exists }
        guard element("profiles.tile.0").exists, !element("editor.name").exists else { return }
        openFirstProfile()
        deleteAccount()
        requireServerOptions(server, "deleting the account an earlier run left returns to the sign-in screen")
        try signIn()
        require("editor.name", timeout: 180, "signed up afresh, the new account opens its first profile's editor")
    }

    /// Opens the picker's first profile, with the tour's PIN where it asks for one.
    private func openFirstProfile() {
        activate(element("profiles.tile.0"))
        if element("pin.digit.1").exists(within: 5) { pressPin([1, 2, 3, 4]) }
    }

    /// Deletes the signed-in account from its account settings, which returns to the sign-in screen.
    private func deleteAccount() {
        settingsPage("account")
        activateShown("settings.account.delete", "the account section offers Delete account")
        activate(require("settings.account.dialog.confirm", timeout: 10, "Delete account asks first"))
        require("signIn.server.edit", timeout: 60, "deleting the account returns to the sign-in screen")
    }

    /// The sign-in screen with the dev stack's sign-in options: `server` typed again where the app went back to another
    /// server. `what` is the step that should have led there.
    private func requireServerOptions(_ server: String, _ what: String) {
        if !waitUntil(30, { element("signIn.option.oidc").exists || element("signIn.server.edit").exists }) { fail(what) }
        if element("signIn.option.oidc").exists(within: 10) { return }
        activate(element("signIn.server.edit"))
        let field = requireTypingFocus("signIn.server.field", "Use your own server opens the server sheet")
        typeReplacing(field, server)
        activate(require("signIn.server.submit", "the server sheet has Check server"))
        require("signIn.option.oidc", timeout: 60, "the server's sign-in options list its OpenID Connect provider")
    }

    /// The profile editor for a new profile (open already for a new account's first one, else Add profile in the
    /// picker): `name`, the avatar at `avatar`, and a PIN when given; the editor's screenshot when `shoot`.
    private func createProfile(_ name: String, avatar: Int, pin: [Int]?, shoot: Bool) {
        if !element("editor.name").exists {
            activate(require("profiles.add", timeout: 60, "the picker offers Add profile"))
        }
        let field = require("editor.name", "the editor asks for a name")
        activate(field)
        field.typeText(name)
        hideKeyboard()
        activate(require("editor.avatar.\(avatar)", "the editor offers avatars"))
        if shoot { tourShot("profile-editor", "the profile editor creating the first profile") }
        if let pin {
            activate(require("editor.pin.set", "the editor offers Set a PIN"))
            pressPin(pin)
            pressPin(pin)
            if !waitUntil(10, { element("editor.save").exists }) { fail("the PIN, entered twice, doesn't return to the editor") }
            hideKeyboard()
        }
        let save = require("editor.save", "the editor has Create")
        if !waitUntil(5, { save.isEnabled }) { fail("Create stays disabled for \(name)") }
        activate(save)
        requireGone("editor.name", timeout: 30, "Create closes the editor")
    }

    /// Brings the controls up: a tap on the video (iOS, `showPlayerControls`), the pointer over it (Mac).
    private func showControls(_ video: XCUIElement) {
        #if os(macOS)
        // A move of the pointer keeps them up a while longer, too.
        pointerOn(video, at: CGVector(dx: 0.3, dy: 0.4))
        pointerOn(video, at: CGVector(dx: 0.5, dy: 0.5))
        require("player.playPause", timeout: 5, "the controls come up")
        #else
        showPlayerControls(video, "the controls come up")
        #endif
    }

    /// Closes the open player menu or popover, whose `entry` shows while it is open: a tap beside it (iOS), Escape (Mac).
    private func closeMenu(_ video: XCUIElement, entry: XCUIElement) {
        #if os(macOS)
        app.typeKey(.escape, modifierFlags: [])
        #else
        video.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.5)).tap()
        #endif
        if !waitUntil(5, { !entry.exists }) { fail("the menu doesn't close") }
    }

    /// Pauses the remux with the controls up on a bottom line of its French track, which the player lifts above them:
    /// line 2 of 6 (5–8 s), reached by a tap (a click on the Mac) on the timeline at 6.5 s of its 30 s. (Waiting for a
    /// line while it plays lands late where position reads are slow, and the line at 15–18 s is a sign at the top of
    /// the picture, `{\an8}`, which stays there.)
    private func pauseOnSubtitleLine(_ video: XCUIElement) {
        showControls(video)
        if element("player.playPause").label != "Play" { activate(element("player.playPause")) }
        if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Play" }) { fail("play/pause doesn't pause the remux") }
        let point = ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 6.5 / 30, dy: 0.5))
        #if os(macOS)
        point.click()
        #else
        point.tap()
        #endif
        if !waitUntil(10, { playerPosition().map { (5.5...7.5).contains($0) } ?? false }) {
            fail("a tap on the timeline at 6.5 s left the remux at \(playerPosition() ?? 0) s")
        }
        showControls(video)
    }

    /// The Streams row whose text contains `text` (`720p`, `issing`).
    private func streamRow(containing text: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'streams.row.' AND label CONTAINS %@", text)).firstMatch
    }

    /// Scrolls the Streams sheet's list until `row` is inside it and the window (`scrollTo`; the list makes its rows as
    /// they scroll in). An iPhone's sheet opens at half height: unless `row` shows already, the sheet is first pulled up
    /// to full height by its top (`pullSheetUp`).
    private func revealInSheet(_ row: XCUIElement, _ what: String) {
        require("streams.row.0", "the Streams sheet lists streams")
        _ = row.exists(within: 3)
        #if os(macOS)
        let lists = app.scrollViews
        #else
        let lists = app.collectionViews
        #endif
        let list = lists.containing(NSPredicate(format: "identifier BEGINSWITH 'streams.row.'")).firstMatch
        #if os(iOS)
        if !liesInside(row) { pullSheetUp(list) }
        #endif
        scrollTo(row, over: list, steps: 8, what)
    }

    /// Out of the player to the screen under it: the error's Close, else the controls' back button, the controls
    /// brought up right before the tap or click (`showControls`): over a playing video they hide 5 s after they came
    /// up, which can fall between a look at the button and the tap.
    private func leavePlayer() {
        let video = element("player.video")
        for _ in 0..<4 where video.exists {
            let close = element("player.error.close")
            if close.exists {
                activate(close)
            } else {
                showControls(video)
                activate(element("player.back"))
            }
            _ = waitUntil(5) { !video.exists }
        }
        requireGone("player.video", timeout: 10, "the player closes")
    }

    /// Enters `pin` on the PIN pad: a tap or click on each digit's key.
    private func pressPin(_ pin: [Int]) {
        for digit in pin {
            activate(require("pin.digit.\(digit)", "the PIN pad has \(digit)"))
        }
    }
}
