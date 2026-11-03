import XCTest

/// The screenshot tour on Apple TV: every screen of the tour once, with the remote, each shot with the focus where a
/// viewer would have it. The tests
/// run in name order and build on each other: the account part on the product signed in at the dev stack, then a dev
/// session (the test addon, the fake Trakt) whose database they share, so the library item, the progress and the
/// watched episode the first ones leave show on the Board and in the Library.
final class ScreenshotTour: TourTestCase {
    // MARK: Account

    func test1Account() throws {
        executionTimeAllowance = 1500
        let server = try tourServer
        launchProduct()

        require("signIn.server.edit", timeout: 60, "the sign-in screen shows")
        walkFocus(to: "signIn.server.edit", "the remote reaches Use your own server")
        press(.select)
        require("signIn.server.field", "Use your own server opens the server sheet")
        walkFocus(to: "signIn.server.field", "the server sheet's field takes focus")
        press(.select)
        app.typeText(server)
        putKeyboardAway("the server typed")
        walkFocus(to: "signIn.server.submit", "the remote reaches Check server")
        tourShot("signin-server", "Use your own server, with the dev stack's address typed; Check server focused")
        press(.select)
        requireGone("signIn.server.field", timeout: 30, "the server check passes and the sheet closes")
        require("signIn.option.oidc", timeout: 60, "the server's sign-in options list its OpenID Connect provider")
        walkFocus(to: "signIn.option.oidc", "the remote reaches the server's OpenID Connect provider")
        tourShot("signin", "the sign-in options of the self-hosted dev stack (its Dex as the OpenID Connect provider)")

        // A signed-out device can be signed in from one that is: Link from another device shows its code.
        walkFocus(to: "signIn.link", "the remote reaches Link from another device")
        press(.select)
        _ = requireCode("link.code", timeout: 30, "Link from another device shows a code")
        tourShot("signin-link", "Link from another device on a signed-out device: the code and its QR code, waiting for a signed-in device's approval")
        press(.menu)
        require("signIn.option.oidc", timeout: 30, "Menu returns to the server's sign-in options")

        try signUp(server)
        createProfile("Alex", avatar: 2, pin: nil, shoot: true)
        createProfile("Kids", avatar: 6, pin: [1, 2, 3, 4], shoot: false)
        if !waitUntil(30, { profileTile("Alex").exists && profileTile("Kids").exists }) { fail("the picker doesn't list both profiles") }
        let kids = profileTile("Kids")
        walkFocus(to: kids.identifier, "the remote reaches Kids")
        tourShot("profiles", "Who's watching: two profiles, Kids locked with a PIN and focused")
        press(.select)
        require("pin.digit.1", "the locked profile asks for its PIN")
        enterPin([1, 2])
        tourShot("pin", "Kids' PIN pad with two digits entered")
        enterPin([3, 4])
        requireGone("pin.digit.1", timeout: 30, "the profile's PIN opens it")

        requireFocus("board.row.0.item.0", timeout: 90, "the profile opens on the Board")
        // Signed in, the profile's card has both of its buttons (a dev session's has Edit alone), and the remote reaches
        // each: Up from the first section onto the card, then along it. The shot shows Edit profile focused.
        focusTabBar()
        moveFocus(to: "nav.settings", with: .right, "Right reaches the Settings tab")
        require("settings.section.playback", timeout: 10, "Settings shows")
        press(.down)
        moveFocus(to: "settings.section.playback", with: .down, limit: 6, "Down reaches Playback")
        press(.up)
        if !waitUntil(5, { focusedId?.hasPrefix("settings.profile.") == true }) {
            fail("Up from Playback leaves focus on \(focusedId ?? "nothing"), not on the profile's card")
        }
        walkFocus(to: "settings.profile.manage", "the remote reaches Switch or manage profiles")
        walkFocus(to: "settings.profile.edit", "the remote reaches Edit profile")
        tourShot("settings-profile", "Settings' first page signed in: Kids' card with Edit profile and Switch or manage profiles, Edit focused")
        // Switch or manage profiles: the picker over the running profile, Kids marked as it; Menu goes back to it.
        walkFocus(to: "settings.profile.manage", "the remote reaches Switch or manage profiles")
        press(.select)
        if !waitUntil(30, { profileTile("Kids").exists && profileTile("Alex").exists }) { fail("Switch or manage profiles doesn't open the picker") }
        shot("profiles over the running profile")
        press(.menu)
        require("settings.profile.edit", timeout: 30, "Menu closes the picker, back to Settings")
        // Down along Settings to Account: the tab bar the picker gives back, the second in the process, carries no nav.*
        // identifiers on tvOS (GitHubTests), which openSection walks by.
        moveFocus(to: "settings.section.account", with: .down, limit: 12, "Down reaches Account in Settings")
        press(.select)
        require("settings.account.signOut", timeout: 30, "the account section offers Sign out")
        tourShot("settings-account", "signed in on the dev stack as apple@example.com, this device listed")

        // Signed out and in again, as another device would: the account's profiles come back from the server.
        moveFocus(to: "settings.account.signOut", with: .down, limit: 20, "Down reaches Sign out")
        press(.select)
        confirmDialog("settings.account.dialog.confirm", "Sign out asks first")
        requireServerOptions(server, "signing out returns to the sign-in screen")
        try signIn()
        let alex = profileTile("Alex")
        if !waitUntil(180, { alex.exists && profileTile("Kids").exists }) { fail("signed in again, the account's two profiles don't show") }
        walkFocus(to: alex.identifier, "the remote reaches Alex")
        press(.select)
        requireFocus("board.row.0.item.0", timeout: 90, "Alex's profile opens on the Board")
        // Deleted, so the next run signs up afresh. A relaunch first, still signed in: deleting walks the tab bar by its
        // nav.* identifiers, which this one, the third in the process, lacks.
        app.terminate()
        launchProduct()
        if !waitUntil(60, { alex.exists || element("board.row.0.item.0").exists }) { fail("relaunched signed in, the app shows neither the picker nor Home") }
        if alex.exists {
            walkFocus(to: alex.identifier, "relaunched, the remote reaches Alex")
            press(.select)
        }
        deleteAccount()
    }

    // MARK: Detail

    func test2Detail() throws {
        try launchTour(open: ["board", "detail:movie:tt0111161"])
        requireFocus("detail.play", timeout: 60, "Play has focus once the movie's details show")
        tourShot("detail-movie", "The Shawshank Redemption from Cinemeta, Play focused, before it is added to the library")
        moveFocus(to: "detail.library", with: .right, "Right from Play reaches the library button")
        let add = element("detail.library").label
        press(.select)
        if !waitUntil(10, { element("detail.library").exists && element("detail.library").label != add }) { fail("Add to library doesn't turn into its undo") }

        app.terminate()
        try launchTour(open: ["board", "detail:series:tt7366338"])
        requireFocus("detail.play", timeout: 60, "Play has focus once the series' details show")
        press(.down)
        requireFocus("detail.season.0", "Down from Play lands on the selected season's chip")
        requireShelfContent("detail.episode", "the season lists its episodes")
        press(.down)
        requireFocus("detail.episode.0", "Down from the seasons lands on the first episode")
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        require("detail.menu.item.markWatched", timeout: 5, "a long press of Select on an episode opens its menu")
        tourShot("detail-menu", "the first episode's menu (a long press of Select on the remote)")
        press(.select)
        if !waitUntil(10, { element("detail.episode.0").exists && isWatched(element("detail.episode.0")) }) { fail("the menu's first entry doesn't mark the episode watched") }
        requireFocus("detail.episode.0", "focus returns to the episode after its menu")
        tourShot("detail-series", "Chernobyl: the season chips and the first season's episodes, the first one watched and focused")
    }

    // MARK: Streams and the player

    func test3Player() throws {
        try launchTour(open: ["board", "detail:movie:tt0111161"])
        requireFocus("detail.play", timeout: 60, "Play has focus once the movie's details show")
        press(.select)
        requireFocus("streams.row.0", timeout: 60, "Select on Play opens the Streams sheet, the remux stream focused")
        tourShot("streams", "the Streams sheet over The Shawshank Redemption's details, the first stream focused")
        press(.select)
        require("player.video", "Select on the remux stream opens the player")
        requirePlayback(1, timeout: 90)
        putAwaySkipButton()

        // Select pauses with the controls up, focus on the timeline (the skip button over the remux's Opening chapter
        // put away first, `putAwaySkipButton`); the subtitle button opens the panel on its tab.
        press(.select)
        requireFocus("player.scrub", "the timeline has focus once the controls are up")
        openPlayerMenu("player.subtitles")
        tourShot("player-subtitles", "the panel's subtitle tab")
        pickMenuItem { $0 == "French" }

        // Playing again until a bottom line of the French track is on screen (lines at 1–4, 5–8, 11–14, 21–24 and
        // 25–28 s; the one at 15–18 s is a sign at the top, `{\an8}`), then paused there with the controls up: Select
        // on the timeline, Down from the subtitle button where the pick left focus, plays and pauses.
        requireFocus("player.subtitles", "the subtitle button has focus again once the panel is gone")
        press(.down)
        requireFocus("player.scrub", "Down from the subtitle button reaches the timeline")
        press(.select)
        let lines: [ClosedRange<Double>] = [5.4...7.2, 11.4...13.2, 21.4...23.2, 25.4...27.2]
        if !waitUntil(40, { playerPosition().map { position in lines.contains { $0.contains(position) } } ?? false }) {
            fail("the player never reached a subtitle line (at \(playerPosition() ?? 0) s)")
        }
        press(.select)
        if !waitUntil(5, { element("player.scrub").exists && element("player.scrub").label == "Play" }) {
            if element("player.scrub").exists, element("player.scrub").label != "Play" { press(.select) }
        }
        if !waitUntil(5, { element("player.scrub").exists && element("player.scrub").label == "Play" }) {
            fail("Select doesn't pause")
        }
        tourShot("player", "the remux paused with the controls up and a French subtitle line above them")

        openPlayerMenu("player.audio")
        tourShot("player-audio", "the panel's audio tab")
        press(.menu)
        requireGone("player.menu", timeout: 5, "Menu closes the audio tab")
        openPlayerMenu("player.speed")
        tourShot("player-speed", "the panel's playback speed tab")
        press(.menu)
        requireGone("player.menu", timeout: 5, "Menu closes the speed tab")
        openPlayerMenu("player.subtitles")
        moveFocus(to: "player.menu.delay", with: .down, limit: 12, "Down reaches the subtitle delay entry")
        press(.select)
        requireFocus("player.delay.decrease", "the subtitle delay opens with focus on −")
        moveFocus(to: "player.delay.increase", with: .right, "Right reaches +")
        press(.select)
        let reset = element("player.delay.reset")
        if !waitUntil(5, { reset.exists && reset.isEnabled }) { fail("+ doesn't change the delay") }
        tourShot("player-delay", "the subtitle delay, moved once")
        press(.menu)
        requireGone("player.menu", timeout: 5, "Menu closes the subtitle delay")
        openPlayerMenu("player.more")
        requireFocus("player.more.info", "the options open with focus on Playback info")
        press(.select)
        require("player.info.panel", timeout: 5, "Select on Playback info opens the playback info")
        tourShot("player-info", "the panel's Info tab over the paused remux")
        press(.menu)
        requireGone("player.info.panel", timeout: 5, "Menu closes the playback info")
        leavePlayer()
        requireFocus("detail.play", "Menu returns to the movie's details, Play focused")
        if element("streams.row.0").exists { fail("back from the player, the Streams sheet is open again") }
    }

    func test4PlayerEnds() throws {
        try launchTour(open: ["board", "detail:series:tt7366338", "streams:series:tt7366338:tt7366338:1:1"])
        requireFocus("streams.row.0", timeout: 60, "the first stream has focus once the episode's Streams sheet lists them")
        // The H.264 file without a binge group: the next episode has no stream to go on with.
        let unbound = streamRow(containing: "720p")
        focusStreamRow(unbound, "the Streams sheet lists the H.264 file without a binge group")
        press(.select)
        require("player.video", timeout: 20, "Select on the H.264 stream opens the player")
        requirePlayback(1, timeout: 90)
        require("player.upNext", timeout: 40, "the end of the 20 s episode offers the next one")
        requireFocus("player.upNext", timeout: 5, "up next has focus as it comes up")
        // Shot as its fill grows, once the button has come up (its frame still).
        _ = settledFrame(of: element("player.upNext"))
        tourShot("player-upnext", "Chernobyl S1E1's end: up next, S1E2 and its name, focused and filling as it counts down", still: false)
        press(.select)

        // S1E2's Streams sheet comes up over the player, which plays the stream picked there.
        requireFocus("streams.row.0", timeout: 60, "the next episode's Streams sheet comes up over the player, its first stream focused")
        if element("detail.play").exists, element("detail.play").isHittable { fail("the next episode's streams came up over the details, not the player") }
        press(.down)
        requireFocus("streams.row.1", "Down moves focus to the H.264 stream")
        shot("the next episode's streams over the player")
        press(.select)
        requireGone("streams.row.1", "picking a stream closes the sheet over the player")
        require("player.video", "the stream picked plays in the player")
        requirePlayback(1, timeout: 60)
        leavePlayer()

        // Back on the series; its first episode's streams again, down to the missing file.
        walkFocus(to: "detail.episode.0", "back from the player, the remote reaches the first episode")
        press(.select)
        requireFocus("streams.row.0", timeout: 60, "Select on the episode opens its Streams sheet")
        let missing = streamRow(containing: "issing")
        focusStreamRow(missing, "the Streams sheet lists the missing file")
        press(.select)
        let error = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier IN {'player.error.retry', 'player.error.next', 'player.error.close'}")).firstMatch
        if !error.exists(within: 60) { fail("the missing file doesn't bring up the player's error") }
        tourShot("player-error", "the player's error for the missing file (a 404 from the media server)")
        leavePlayer()
    }

    /// Moves focus down the Streams panel to `row` (the panel makes its rows as they scroll in); fails with `what`.
    private func focusStreamRow(_ row: XCUIElement, _ what: String) {
        for _ in 0..<8 where !(row.exists && row.hasFocus) {
            if row.exists {
                moveFocus(to: row.identifier, with: .down, limit: 6, what)
                return
            }
            press(.down)
        }
        if !(row.exists && row.hasFocus) { fail("\(what): no such row reached") }
    }

    /// The Streams row whose text contains `text` (`720p`, `issing`).
    private func streamRow(containing text: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'streams.row.' AND label CONTAINS %@", text)).firstMatch
    }

    // MARK: Trackers and the Board

    func test5TrackersAndBoard() throws {
        try launchTour()
        requireBoardFocus()
        openSection("trackers")
        moveFocus(to: "settings.trackers.trakt.connect", with: .down, limit: 4, "Down reaches Connect on Trakt's card")
        press(.select)
        require("settings.trackers.connect.code", "Connect shows a device code")
        require("settings.trackers.connect.qr", timeout: 5, "the code comes with its QR code")
        tourShot("trackers-connect", "Connect on Trakt: its device code and QR code (the fake Trakt approves it)")
        requireGone("settings.trackers.connect.code", timeout: 60, "the fake approves the code and the flow closes")
        requireTracker("trakt", connectedAs: Self.fakeTraktUser, "Trakt is connected")
        tourShot("settings-trackers", "Trakt connected as the fake Trakt's user, Disconnect focused")

        press(.menu)
        focusTabBar()
        moveFocus(to: "nav.board", with: .left, "Left reaches the Board tab")
        requireShelfContent("board.cw", timeout: 60, "the Board shows Continue Watching")
        requireShelf(from: "Trakt", listing: Self.fakeTraktWatchlistTitle, "the Board shows Trakt's watchlist")
        press(.down)
        if !waitUntil(5, { focusedId?.hasPrefix("board.") == true }) { fail("Down from the tab bar doesn't enter the Board") }
        tourShot("board", "the top of Home, the first Continue Watching card focused")
        // Every shelf settled, Trakt's read where it stays (one left out above it moves it up an index) and the one
        // past it showing its cards.
        requireShelvesSettled("board.row", "the Board's shelves load")
        let trakt = shelfSources("Trakt").firstMatch
        let row = String(trakt.identifier.dropLast(".source".count))
        for _ in 0..<8 where focusedId?.hasPrefix("\(row).") != true { press(.down) }
        if focusedId?.hasPrefix("\(row).") != true { fail("Down doesn't reach Trakt's shelf: focus is on \(focusedId ?? "nothing")") }
        press(.down)
        tourShot("board-rows", "Home moved down past Trakt's shelves to the addons' catalogs")
    }

    // MARK: Discover and Search

    func test6DiscoverAndSearch() throws {
        try launchTour()
        requireBoardFocus()
        focusTabBar()
        moveFocus(to: "nav.discover", with: .right, "Right reaches the Discover tab")
        require("discover.item.0", timeout: 30, "Discover shows its grid")
        for _ in 0..<4 where focusedId?.hasPrefix("discover.item.") != true { press(.down) }
        tourShot("discover", "Discover on its first catalog, the first poster focused")
        press(.up)
        for _ in 0..<4 where !holdsFocus("discover.filter.0") { press(.right) }
        if !holdsFocus("discover.filter.0") { fail("Up and Right don't reach the Genre chip: focus is on \(focusedId ?? "nothing")") }
        press(.select)
        require("discover.filter.0.option.0", timeout: 5, "the Genre chip opens its choices")
        tourShot("discover-filter", "the Genre choices open")
        press(.down, count: 3)
        press(.select)

        focusTabBar()
        moveFocus(to: "nav.search", with: .right, "Right reaches the Search tab")
        let field = app.searchFields.firstMatch
        if !field.exists(within: 10) { fail("Search shows no search field") }
        press(.down)
        field.typeText("matrix")
        requireCard("search.group.0.item.0", timeout: 30, "typing matrix lists results")
        for _ in 0..<6 where focusedId?.hasPrefix("search.group.") != true { press(.down) }
        tourShot("search-results", "results for \"matrix\", the first result focused")
        press(.select)
        _ = waitUntil(20) { ["detail.loading", "detail.title", "detail.error"].contains { element($0).exists } }
        press(.menu)
        // The query deleted on the keyboard (Search keeps it), the opened result's query is a recent search.
        moveFocus(to: "nav.search", with: .up, limit: 10, "Up moves focus to the tab bar")
        press(.down)
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: "matrix".count))
        require("search.recent.0", "the emptied field lists the recent searches")
        for _ in 0..<8 where focusedId != "search.recent.0" {
            press(focusedId?.hasSuffix(".remove") == true ? .left : .down)
        }
        tourShot("search-recent", "the empty query: recent searches, the first focused")
    }

    // MARK: Library

    func test7Library() throws {
        try launchTour()
        requireBoardFocus()
        focusTabBar()
        moveFocus(to: "nav.library", with: .right, "Right reaches the Library tab")
        require("library.item.0", timeout: 30, "the Library lists the movie added from Detail")
        // Down from the tab bar lands on the row above the grid; along it to its first control, the view menu, so the
        // shot shows the row and the posters at rest under it.
        press(.down)
        for _ in 0..<4 where !holdsFocus("library.view") { press(.left) }
        if !holdsFocus("library.view") { fail("Down and Left don't reach the view menu: focus is on \(focusedId ?? "nothing")") }
        tourShot("library", "the library: the view, type and sort menus above the movie added from its Detail screen, the view menu focused")
        let libraryView = menuText("library.view")
        press(.select)
        requireFocus("library.view.option.0", timeout: 5, "Select on the view menu opens its panel, Library focused")
        moveFocus(to: "library.view.option.1", with: .down, "Down moves focus to Continue Watching")
        press(.select)
        requireGone("library.view.panel", "picking Continue Watching closes the panel")
        if !waitUntil(10, { menuText("library.view") != libraryView && element("library.item.0").exists }) {
            fail("Continue Watching lists nothing")
        }
        // Down from the menu lands on the card under it, which can be the second one: the walk goes on to the first.
        press(.down)
        walkFocus(to: "library.item.0", "the remote reaches Continue Watching's first item")
        if menuText("library.view") == libraryView { fail("the walk to the first item left Continue Watching") }
        tourShot("continue-watching", "Continue Watching: the movie and the episode played, the first focused")
        // The series' details: the episode played last shows its progress on its thumbnail (a shot of the run, not of
        // the gallery).
        let series = element("library.item.0").label.contains("Chernobyl") ? "library.item.0" : "library.item.1"
        walkFocus(to: series, "the remote reaches the series in Continue Watching")
        press(.select)
        // Continue Watching resumes the episode: the player on the stream it last played from, or, with no stream to
        // play, its streams over the series' details; Menu closes either.
        if waitUntil(30, { element("streams.row.0").exists || element("player.video").exists }) {
            if element("player.video").exists {
                leavePlayer()
            } else {
                press(.menu)
                requireGone("streams.row.0", "Menu closes the Streams panel over the series")
            }
        }
        require("detail.episode.1", timeout: 30, "the series' details list its second episode")
        screenSettles()
        shot("episode progress")
    }

    // MARK: Addons

    func test8Addons() throws {
        try launchTour()
        requireBoardFocus()
        focusTabBar()
        moveFocus(to: "nav.settings", with: .right, "Right reaches the Settings tab")
        press(.down)
        moveFocus(to: "settings.section.addons", with: .down, limit: 10, "Down reaches Addons in Settings")
        press(.select)
        require("addons.row.1", timeout: 30, "Addons lists the installed addons")
        moveFocus(to: "addons.row.0", with: .down, limit: 6, "Down reaches the first addon")
        tourShot("addons", "the installed addons: Cinemeta and the test addon, the first focused; its menu (a long press of Select) moves it")
        // The row's menu, not buttons beside it: Cinemeta, built in and first, can only move down.
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        require("addons.row.0.down", timeout: 5, "a long press of Select on the first addon opens its menu with Move down")
        if element("addons.row.0.remove").exists { fail("the built-in addon's menu offers Remove") }
        press(.menu)
        requireGone("addons.row.0.down", "Menu closes the addon's menu")
        requireFocus("addons.row.0", "focus returns to the addon after its menu")
        press(.select)
        requireFocus("addons.details.close", "the details open with Close focused")
        tourShot("addon-details", "Cinemeta's details")
        press(.menu)
        requireGone("addons.details.close", "Menu closes the details")
        requireFocus("addons.row.0", "focus returns to the addon whose details closed")

        // Install leads the row above the list, beside the type filter.
        walkFocus(to: "addons.install", "the remote reaches Install above the list")
        press(.select)
        requireFocus("addons.install.link", "the install page's link field has focus")
        press(.select)
        app.typeText(Self.previewAddon + "\n")
        requireFocus("addons.install.confirm", timeout: 30, "the fetched manifest offers Install, focused")
        tourShot("addon-install", "the install preview of WatchHub, Install focused")
        press(.menu)
        requireGone("addons.install.confirm", "Menu closes the install preview")
    }

    // MARK: Settings

    func test9Settings() throws {
        try launchTour()
        requireBoardFocus()
        focusTabBar()
        moveFocus(to: "nav.settings", with: .right, "Right reaches the Settings tab")
        press(.down)
        moveFocus(to: "settings.section.playback", with: .down, limit: 10, "Down reaches Playback")
        tourShot("settings", "Settings: the profile and the sections, Playback focused")
        for (section, screen, note) in [
            ("playback", "settings-playback", "Playback: per profile, synced"),
            ("device", "settings-device", "This device: decoding, HDR, frame rate, cache"),
            ("about", "settings-about", "About: version, source and licences"),
        ] {
            moveFocus(to: "settings.section.\(section)", with: .down, limit: 10, "Down reaches \(section)")
            press(.select)
            _ = waitUntil(5) { !element("settings.section.\(section)").exists || !element("settings.section.\(section)").isHittable }
            press(.down)
            tourShot(screen, note)
            press(.menu)
            require("settings.section.\(section)", "Menu returns to the list of sections")
        }
    }

    // MARK: Helpers

    /// A manifest the install preview shows (a live addon none of the tour's profiles has).
    static let previewAddon = "https://watchhub.strem.io/manifest.json"

    /// The device flow on the server's OpenID Connect provider: its code goes to the run's hook, which signs it in.
    private func signIn() throws {
        walkFocus(to: "signIn.option.oidc", "the remote reaches the server's OpenID Connect provider")
        press(.select)
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
        walkFocus(to: "profiles.tile.0", "the remote reaches the profile an earlier run left")
        press(.select)
        if element("pin.digit.1").exists(within: 5) { enterPin([1, 2, 3, 4]) }
        deleteAccount()
        requireServerOptions(server, "deleting the account an earlier run left returns to the sign-in screen")
        try signIn()
        require("editor.name", timeout: 180, "signed up afresh, the new account opens its first profile's editor")
    }

    /// Deletes the account of the profile just opened, from its account settings, which returns to the sign-in screen.
    private func deleteAccount() {
        requireFocus("board.row.0.item.0", timeout: 90, "the profile opens on the Board")
        openSection("account")
        require("settings.account.delete", timeout: 30, "the account section offers Delete account")
        moveFocus(to: "settings.account.delete", with: .down, limit: 25, "Down reaches Delete account")
        press(.select)
        confirmDialog("settings.account.dialog.confirm", "Delete account asks first")
        require("signIn.server.edit", timeout: 60, "deleting the account returns to the sign-in screen")
    }

    /// The sign-in screen with the dev stack's sign-in options: `server` entered again where the app went back to
    /// another server. `what` is the step that should have led there.
    private func requireServerOptions(_ server: String, _ what: String) {
        if !waitUntil(30, { element("signIn.option.oidc").exists || element("signIn.server.edit").exists }) { fail(what) }
        if !element("signIn.option.oidc").exists(within: 10) { enterOwnServer(server) }
    }

    /// The profile editor for a new profile (open already for a new account's first one, else Add profile in the
    /// picker): `name`, the avatar at `avatar`, and a PIN when given; the editor's screenshot when `shoot`.
    private func createProfile(_ name: String, avatar: Int, pin: [Int]?, shoot: Bool) {
        if !element("editor.name").exists {
            require("profiles.add", timeout: 60, "the picker offers Add profile")
            walkFocus(to: "profiles.add", "the remote reaches Add profile")
            press(.select)
        }
        require("editor.name", "the editor asks for a name")
        walkFocus(to: "editor.name", "the remote reaches the name")
        press(.select)
        app.typeText(name)
        putKeyboardAway("the profile's name typed")
        walkFocus(to: "editor.avatar.\(avatar)", "the remote reaches an avatar")
        press(.select)
        if shoot { tourShot("profile-editor", "the profile editor creating the first profile, an avatar picked") }
        if let pin {
            walkFocus(to: "editor.pin.set", "the remote reaches Set a PIN")
            press(.select)
            require("pin.digit.1", "Set a PIN opens the pad")
            enterPin(pin)
            enterPin(pin)
            if !waitUntil(10, { element("editor.save").exists }) { fail("the PIN, entered twice, doesn't return to the editor") }
        }
        let save = require("editor.save", "the editor has Create")
        if !waitUntil(5, { save.isEnabled }) { fail("Create stays disabled for \(name)") }
        walkFocus(to: "editor.save", limit: 16, "the remote reaches Create")
        press(.select)
        requireGone("editor.name", timeout: 30, "Create closes the editor")
    }

    /// Confirms the question up, the system alert (`focusDialogButton`).
    private func confirmDialog(_ id: String, _ what: String) {
        require(id, timeout: 10, what)
        focusDialogButton(id, "the remote reaches the confirmation")
        press(.select)
    }

    /// Opens the panel on the tab of the transport bar's `button` (focus goes up from the timeline to the buttons).
    private func openPlayerMenu(_ button: String, panel: String = "player.menu") {
        if require("player.scrub", timeout: 5, "the controls are up").hasFocus { press(.up) }
        let from = focusedId.map { order.firstIndex(of: $0) ?? 0 } ?? 0
        let to = order.firstIndex(of: button) ?? 0
        moveFocus(to: button, with: to >= from ? .right : .left, "the remote reaches \(button)")
        press(.select)
        require(panel, timeout: 5, "Select on \(button) opens the panel")
        _ = waitUntil(5) { focusedId?.hasPrefix("player.menu.") == true || focusedId?.hasPrefix("player.panel.") == true }
    }

    /// The transport bar's buttons from left to right.
    private let order = ["player.previous", "player.next", "player.speed", "player.more", "player.subtitles", "player.audio"]

    /// Moves focus to the open menu's first entry whose label `matches` (else its last unselected one) and selects it.
    private func pickMenuItem(_ matches: (String) -> Bool) {
        var items: [XCUIElement] = []
        while element("player.menu.item.\(items.count)").exists { items.append(element("player.menu.item.\(items.count)")) }
        guard let target = items.firstIndex(where: { matches($0.label) }) ?? items.lastIndex(where: { !$0.isSelected }) else {
            fail("the menu has no entry to pick: \(items.map(\.label))")
            return
        }
        let focusedIndex = focusedId.flatMap { Int($0.dropFirst("player.menu.item.".count)) } ?? 0
        moveFocus(to: "player.menu.item.\(target)", with: target > focusedIndex ? .down : .up, limit: 12, "the remote reaches the entry")
        press(.select)
        requireGone("player.menu", timeout: 5, "picking an entry closes the menu")
    }

    /// Menu until the player has closed (a first Menu may close a menu or the controls).
    private func leavePlayer() {
        for _ in 0..<4 where element("player.video").exists {
            press(.menu)
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Menu closes the player")
    }

    /// Waits for the Board to take focus once its shelves load: on its first card, Continue Watching's when it has one.
    private func requireBoardFocus() {
        require("board.row.0.item.0", timeout: 90, "the Board shows its shelves")
        if !waitUntil(10, { focusedId?.hasPrefix("board.") == true }) {
            fail("the Board doesn't take focus once its shelves load: focus is on \(focusedId ?? "nothing")")
        }
    }

}
