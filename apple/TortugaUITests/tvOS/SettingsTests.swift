import XCTest

/// Settings and Addons with the remote: the sections stay where they first show as the profile loads above them
/// (sampled from a thread of its own); Edit profile opens the editor whole in a panel over Settings, which Menu
/// closes; in the editor Down from the avatar at the end of the last line reaches Set PIN, and Up from it the avatar
/// chosen; in Audio languages Down and Up between two chosen languages land on the next row, past a disabled Move down
/// or Move up; a device setting switched with Select outlasts a relaunch; a playback stepper steps up with Right and down
/// with Left (mirrored in a right-to-left language: TORTUGA_LANGUAGE=ar) and stops at its maximum; the Streams group's
/// pattern editor (auto-play off at the start) flags an invalid pattern typed with the system keyboard, under its field
/// and on its row, and Clear removes it; the test addon, in Settings' Addons section, installs from its link (preview,
/// then Install), moves up from its menu (a long press of Select), shows and hides its link in the details (Select), and
/// is removed from its menu after the confirmation. An addon switched off by its row's switch (the row's one button of
/// its own, Right from the row) leaves Home with its catalog's shelf, which comes back once it is switched on again. An
/// addon that asks for the user's watch activity: its install page opens with the agreement focused and Install
/// disabled, Select on the agreement enables Install; installed, the remote reaches its row's switch, on, and in its
/// details Send watch activity and the addon's switch, both on. A `stremio://…/manifest.json` link opened from outside
/// the app (the system's) shows that addon's install page over the screen, its fetched manifest named, Install focused:
/// from a Board card, Cancel leaves the Board as it was; from Home's button in the tab bar, Install lands on Settings'
/// Addons section, which lists the addon. A configurable addon's Configure, from its menu and from its details (its
/// background behind their header), shows its page as a QR code to open on a phone, naming the site and never writing
/// the link out, which
/// Close or Menu closes, focus back where it was. Home's rows: one hidden by Select on its switch, another moved to the
/// top by Select again and again on its Move up button, which keeps focus as the row moves; Home follows, still after a
/// relaunch, until Reset to default. Apple TV has no file dialogs: Settings lists no Export and import. Playback, and
/// Trackers with every tracker connected, show their first rows whole under their navigation bars as they open, and
/// scrolled to their last rows show their titles alone in their bars, no row drawn under them. Every page with a title
/// that a dev session reaches reads its title at 4.5:1 or more and keeps its rows to the readable column. On a kids
/// profile the profile card has no Edit profile; a new profile's Kids profile section, turned on with Select, takes the
/// highest rating and unrated titles by Select and says no title is allowed or blocked yet.
final class SettingsTests: TortugaUITestCase {
    func testProfileEditorShowsWholeInAPanelAndMenuClosesIt() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        focusTabBar()
        moveFocus(to: "nav.settings", with: .right, "Right reaches the Settings tab")
        require("settings.section.playback", timeout: 10, "Settings shows")
        // Down enters the list (the sections, or the card's buttons at its trailing end); the pen is above the sections.
        press(.down)
        walkFocus(to: "settings.profile.edit", "the remote reaches Edit profile")
        press(.select)
        // tvOS's own sheet squeezed the editor to a sliver in the middle of the screen, its rows off it.
        let panel = require("overlay.panel", "Edit profile opens the editor in a panel")
        requireFocus("editor.name", "the name field has focus in the panel")
        for id in ["editor.name", "editor.avatar.0", "editor.pin.set", "editor.save"] {
            let control = require(id, "the editor shows \(id)")
            if !panel.frame.contains(control.frame) { fail("\(id) lies outside the panel: \(control.frame) in \(panel.frame)") }
        }
        let field = element("editor.name").frame
        if field.width < panel.frame.width / 2 { fail("the name field is \(Int(field.width)) pt wide in a \(Int(panel.frame.width)) pt panel") }
        if !app.frame.contains(panel.frame) { fail("the panel runs off the screen: \(panel.frame) in \(app.frame)") }
        shot("profile editor")
        press(.menu)
        requireGone("editor.name", "Menu closes the editor")
        requireFocus("settings.profile.edit", "focus returns to Edit profile")
        shot("back on settings")
    }

    /// On a kids profile (`--dev-kids`) the profile card has no Edit profile: the profile isn't edited
    /// from itself, and the store opens no editor there. A dev session has no account, so the card has no Switch or
    /// manage profiles either: once the card shows the profile's name it has no button at all, and keeps none.
    func testKidsProfileCardHasNoEdit() throws {
        try launchDev(arguments: ["--dev-kids", "2468"])
        require("nav.board", timeout: 60, "the tab bar shows")
        focusTabBar()
        moveFocus(to: "nav.settings", with: Self.rightToLeft ? .left : .right, "the remote reaches the Settings tab")
        require("settings.section.playback", timeout: 10, "Settings shows")
        let name = app.staticTexts.matching(NSPredicate(format: "label == 'Developer'")).firstMatch
        if !name.exists(within: 30) { fail("Settings' profile card doesn't name the kids profile, Developer") }
        if !holds(for: 3, { !element("settings.profile.edit").exists }) {
            fail("the kids profile's card offers Edit profile, which opens nothing there")
        }
        shot("kids profile card")
    }

    /// A new profile's Kids profile section in the editor's panel (`--dev-open profile`, the dev
    /// profile locked by a PIN, so the switch can be turned on): Down from the name reaches the switch, which Select
    /// turns on; the section then shows the highest rating's three choices, Kids chosen, which Down walks and Select on
    /// Little chooses alone, Allow unrated titles off, which Select turns on, the Home rows and the lines saying no
    /// title is allowed or blocked yet. Nothing is saved.
    func testKidsProfileSectionTakesTheKidsRules() throws {
        try launchDev(open: ["profile"], arguments: ["--dev-profile", "Parent::2468"])
        requireFocus("editor.name", timeout: 30, "--dev-open profile opens a new profile's editor on its name")
        let kids = require("editor.kids", "the editor has the Kids profile switch")
        if !waitUntil(10, { kids.isEnabled }) { fail("with the dev profile locked by a PIN, Kids profile can't be turned on") }
        moveFocus(to: "editor.kids", with: .down, limit: 12, "Down reaches the Kids profile switch")
        press(.select)
        if !waitUntil(5, { switchIsOn(kids) }) { fail("Select doesn't turn Kids profile on") }
        let caps = (0..<3).map { require("editor.kids.cap.\($0)", "on, the section lists the highest rating's choice \($0)") }
        if caps.map(\.isSelected) != [false, true, false] {
            fail("the highest rating's choices read selected \(caps.map(\.isSelected)), not Kids alone")
        }
        moveFocus(to: "editor.kids.cap.0", with: .down, limit: 3, "Down reaches Little")
        shot("highest rating")
        press(.select)
        if !waitUntil(5, { caps.map(\.isSelected) == [true, false, false] }) {
            fail("Select on Little leaves the choices selected \(caps.map(\.isSelected))")
        }
        let unrated = require("editor.kids.unrated", "the section has Allow unrated titles")
        if switchIsOn(unrated) { fail("Allow unrated titles starts on") }
        moveFocus(to: "editor.kids.unrated", with: .down, limit: 4, "Down reaches Allow unrated titles")
        press(.select)
        if !waitUntil(5, { switchIsOn(unrated) }) { fail("Select doesn't allow unrated titles") }
        shot("Little and unrated titles picked")
        for id in ["editor.kids.allowed.none", "editor.kids.blockedTitle.none"] {
            require(id, "the section says no title is \(id.contains("allowed") ? "allowed" : "blocked") yet")
        }
        let catalogs = (0..<20).prefix { element("editor.kids.catalog.\($0)").exists }.count
        if catalogs == 0 { fail("on, the section lists no Home rows") }
        moveFocus(to: "editor.kids.catalog.\(max(catalogs - 1, 0))", with: .down, limit: catalogs + 2, "Down reaches the last Home row")
        shot("home rows and no titles yet")
    }

    func testDownFromAnyAvatarReachesSetPinAndUpTheChosenAvatar() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        focusTabBar()
        moveFocus(to: "nav.settings", with: .right, "Right reaches the Settings tab")
        require("settings.section.playback", timeout: 10, "Settings shows")
        press(.down)
        walkFocus(to: "settings.profile.edit", "the remote reaches Edit profile")
        press(.select)
        requireFocus("editor.name", "the name field has focus in the editor's panel")
        let avatars = (0..<40).prefix { element("editor.avatar.\($0)").exists }.map { element("editor.avatar.\($0)").frame }
        // The rightmost avatar with none under it: the furthest from Set PIN at the section's leading edge.
        let open = avatars.indices.filter { index in
            !avatars.contains { $0.minY > avatars[index].maxY && $0.minX < avatars[index].maxX && avatars[index].minX < $0.maxX }
        }
        guard let end = open.max(by: { avatars[$0].maxX < avatars[$1].maxX }) else { return fail("the editor shows no avatars") }
        let pin = require("editor.pin.set", "the editor offers Set PIN").frame
        // The name field sits right of the profile's avatar, which takes no focus: Down from it enters the avatars.
        press(.down)
        requireFocus(prefix: "editor.avatar.", "Down from the name field enters the avatars")
        walkFocus(to: "editor.avatar.\(end)", limit: 16, "the remote reaches the rightmost avatar with none under it")
        press(.select)
        shot("editor, rightmost avatar chosen")
        var problems: [String] = []
        press(.down)
        if !waitUntil(3, { holdsFocus("editor.pin.set") }) {
            problems.append("Down from editor.avatar.\(end) at \(avatars[end]) leaves focus on \(focusedId ?? "nothing"), not Set PIN at \(pin)")
            walkFocus(to: "editor.pin.set", limit: 6, "the remote reaches Set PIN")
        }
        press(.up)
        if !waitUntil(3, { holdsFocus("editor.avatar.\(end)") }) {
            problems.append("Up from Set PIN leaves focus on \(focusedId ?? "nothing"), not editor.avatar.\(end), the avatar chosen")
        }
        if !problems.isEmpty { fail(problems.joined(separator: "\n")) }
    }

    func testDownAndUpBetweenChosenLanguagesStayInTheList() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openSection("playback")
        moveFocus(to: "settings.playback.audioLanguages", with: .down, limit: 4, "Down reaches Audio languages")
        press(.select)
        require("settings.languages.search", "Audio languages opens its editor")
        for _ in 0..<2 {
            let before = chosenLanguageCount
            walkFocus(to: "settings.languages.available.0", limit: 12, "the remote reaches the first language to add")
            press(.select)
            if !waitUntil(5, { chosenLanguageCount == before + 1 }) { return fail("Select on a language to add leaves \(chosenLanguageCount) chosen") }
        }
        // The last row's Move down is disabled, as the first row's Move up.
        let last = chosenLanguageCount - 1
        shot("audio languages, two added")
        var problems: [String] = []
        walkFocus(to: "settings.languages.chosen.\(last - 1).down", limit: 12, "the remote reaches Move down on the row before the last")
        press(.down)
        if !waitUntil(3, { focusedId?.hasPrefix("settings.languages.chosen.\(last).") == true }) {
            problems.append("Down from settings.languages.chosen.\(last - 1).down leaves focus on \(focusedId ?? "nothing"), not on the last row")
        }
        walkFocus(to: "settings.languages.chosen.1.up", limit: 12, "the remote reaches Move up on the second row")
        press(.up)
        if !waitUntil(3, { focusedId?.hasPrefix("settings.languages.chosen.0.") == true }) {
            problems.append("Up from settings.languages.chosen.1.up leaves focus on \(focusedId ?? "nothing"), not on the first row")
        }
        if !problems.isEmpty { fail(problems.joined(separator: "\n")) }
    }

    func testSectionsStayPutAsTheProfileLoads() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        focusTabBar()
        requireSectionsStayPut("Settings shown from its tab") {
            moveFocus(to: "nav.settings", with: .right, "Right reaches the Settings tab")
        }
        press(.down)
        moveFocus(to: "settings.section.about", with: .down, limit: 14, "Down reaches About, Settings' last section")
        if element("settings.section.data").exists { fail("Settings lists Export and import; Apple TV has no file dialogs") }
        shot("settings end: no export and import")
    }

    /// A component in About opens its page with focus on the link to its project's page, its licence text under it;
    /// Down scrolls the text with focus staying on the link, and Menu returns to About's list, focus on the component.
    /// mpv-build's page, above it, holds the texts of every component of MPVKit, each under its folder (FFmpeg's first).
    func testAboutNoticeOpensItsLicenceTexts() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openSection("about")
        moveFocus(to: "settings.about.notice.9", with: .down, limit: 16, "Down reaches Plezy in About")
        press(.select)
        requireFocus("settings.notice.link", timeout: 10, "Plezy's page opens with focus on its project page's link")
        require("settings.notice.text", timeout: 10, "Plezy's page shows its licence text")
        let heading = element("settings.notice.text")
        let top = heading.frame.minY
        shot("notice page")
        press(.down)
        if !waitUntil(3, { !heading.exists || heading.frame.minY < top - 100 }) {
            fail("Down on the link leaves the licence text where it was")
        }
        if !holds(for: 2, { holdsFocus("settings.notice.link") }) {
            fail("Down on the link moves focus to \(focusedId ?? "nothing") rather than scrolling the text")
        }
        shot("notice page scrolled")
        press(.menu)
        requireFocus("settings.about.notice.9", timeout: 10, "Menu returns to About's list, focus on Plezy")
        moveFocus(to: "settings.about.notice.8", with: .up, limit: 1, "Up reaches mpv-build in About")
        press(.select)
        requireFocus("settings.notice.link", timeout: 10, "mpv-build's page opens with focus on its project page's link")
        requireText("settings.notice.text", contains: "ffmpeg/", timeout: 10, "mpv-build's page starts with FFmpeg's licence text")
        shot("mpv-build's page")
    }

    func testSectionTitlesShowAloneOverTheRowsScrolledUnderThem() throws {
        try launchDev(trackersConnected: true)
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        var problems: [String] = []
        openSection("playback")
        problems += firstRowProblems("Playback", row: "settings.playback.audioLanguages", "Playback as it opens")
        moveFocus(to: "settings.playback.streams.exclude", with: .down, limit: 27, "Down reaches Playback's last row")
        problems += titleProblems("Playback", "Playback at its last row")
        shot("playback at its end")
        press(.menu)
        // Every tracker connected: five services' rows, Settings' longest section.
        openSection("trackers")
        problems += firstRowProblems("Trackers", row: "settings.trackers.trakt.status", "Trackers as it opens")
        moveFocus(to: "settings.trackers.anilist.anime", with: .down, limit: 30, "Down reaches AniList's Anime, Trackers' last row")
        problems += titleProblems("Trackers", "Trackers at its last row")
        shot("trackers at its end")
        if !problems.isEmpty { fail(problems.joined(separator: "; ")) }
    }

    /// What is amiss with the first row, `id`, of the page titled `title` as it opens: it should start below the bar and
    /// the 24 points under it where the page fades in (`pageTitle`), and show, Vision reading text in it.
    private func firstRowProblems(_ title: String, row id: String, _ what: String) -> [String] {
        let bar = app.navigationBars[title]
        if !bar.exists(within: 5) { fail("\(what): no navigation bar titled \(title)") }
        let row = require(id, timeout: 10, what).frame
        var problems: [String] = []
        if row.minY < bar.frame.maxY + 24 {
            problems.append("\(what): \(id) starts at y \(Int(row.minY)), less than 24 points under the bar's lower edge \(Int(bar.frame.maxY))")
        }
        if XCUIScreen.main.screenshot().texts(in: row).isEmpty { problems.append("\(what): \(id) shows no text") }
        return problems
    }

    /// What is amiss with the title of the page titled `title`, scrolled so that rows lie under its navigation bar
    /// (fails with `what` when none do): the screen should show the title alone there, Vision reading no other text
    /// in the bar and the bar's band showing nothing but the page's black beside the title (a row's grey platter would
    /// show).
    private func titleProblems(_ title: String, _ what: String) -> [String] {
        let bar = app.navigationBars[title]
        if !bar.exists(within: 5) { fail("\(what): no navigation bar titled \(title)") }
        let band = bar.frame
        if !app.cells.allElementsBoundByIndex.contains(where: { $0.frame.intersects(band) }) {
            fail("\(what): no row has scrolled up under the title's bar \(band)")
        }
        let heading = bar.staticTexts[title].frame
        let screen = XCUIScreen.main.screenshot()
        var problems: [String] = []
        let read = screen.texts(in: band).map(\.text)
        if read != [title] { problems.append("\(what): the title's bar reads \(read), not \(title) alone") }
        guard let pixels = ScreenPixels(screen) else { return problems + ["\(what): the screenshot has no pixels"] }
        let beside = [
            CGRect(x: band.minX, y: band.minY, width: heading.minX - 16 - band.minX, height: band.height),
            CGRect(x: heading.maxX + 16, y: band.minY, width: band.maxX - heading.maxX - 16, height: band.height),
        ]
        for side in beside {
            if let lowest = pixels.lowestRow(in: side, matching: { max($0, $1, $2) > 12 }) {
                problems.append("\(what): beside the title its bar shows more than black, down to y \(Int(lowest))")
            }
        }
        return problems
    }

    /// Settings' sections, and the pages over them: Playback's audio languages, Qualities and a stream pattern;
    /// Appearance's interface language values; Addons' install page (for a link, with the fetched manifest, installed)
    /// and an addon's details; Connect on Trakt (`pageProblems`).
    func testEveryPageTitleReadsAndItsFormKeepsToTheColumn() throws {
        // Some twenty pages, each opened and closed with the remote: longer than the run's default 5 minutes.
        executionTimeAllowance = 900
        let url = try addonURL
        try launchDev(trackers: true)
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        focusTabBar()
        moveFocus(to: "nav.settings", with: .right, "Right reaches the Settings tab")
        require("settings.section.playback", timeout: 10, "Settings shows")
        var problems: [String] = []
        for section in ["playback", "appearance", "subtitles", "home", "device", "account", "trackers", "addons", "about"]
        where element("settings.section.\(section)").exists(within: 2) {
            openSection(section)
            _ = waitUntil(5) { !element("settings.section.\(section)").isHittable }
            problems += pageProblems("Settings → \(section)")
            press(.menu)
            require("settings.section.\(section)", "Menu returns to Settings' list from \(section)")
        }

        openSection("playback")
        moveFocus(to: "settings.playback.audioLanguages", with: .down, limit: 4, "Down reaches Audio languages")
        problems += coverProblems("Playback → audio languages", shows: "settings.languages.close")
        moveFocus(to: "settings.playback.streams.qualities", with: .down, limit: 17, "Down reaches Qualities")
        problems += coverProblems("Playback → Qualities", shows: "settings.streams.qualities.close")
        moveFocus(to: "settings.playback.streams.exclude", with: .down, limit: 8, "Down reaches the excluding pattern")
        problems += coverProblems("Playback → excluding pattern", shows: "settings.streams.pattern.cancel")
        press(.menu)
        require("settings.section.trackers", "Menu returns to Settings' list from Playback")

        openSection("appearance")
        moveFocus(to: "settings.appearance.interfaceLanguage", with: .up, limit: 4, "Up reaches the interface language")
        problems += coverProblems("Appearance → interface language", shows: "settings.appearance.interfaceLanguage.option.", prefix: true)
        press(.menu)
        require("settings.section.trackers", "Menu returns to Settings' list from Appearance")

        openSection("trackers")
        moveFocus(to: "settings.trackers.trakt.connect", with: .down, limit: 4, "Down reaches Connect on Trakt's card")
        // The fake Trakt approves the code after about 12 s, which closes the page by itself.
        problems += coverProblems("Trackers → Connect on Trakt", shows: "settings.trackers.connect.code")
        press(.menu)
        require("settings.section.addons", "Menu returns to Settings' list from Trackers")

        openSection("addons")
        require("addons.install", "Settings' Addons row opens Addons")
        moveFocus(to: "addons.install", with: .up, limit: 4, "Install takes focus at the top")
        press(.select)
        requireFocus("addons.install.link", "the install page's link field has focus")
        problems += pageProblems("Addons → install page for a link")
        press(.select)
        app.typeText(url + "\n")
        requireFocus("addons.install.confirm", timeout: 30, "the fetched manifest offers Install, focused")
        problems += pageProblems("Addons → install preview")
        press(.select)
        requireFocus("addons.install.done", timeout: 30, "the addon installs; Done has focus")
        problems += pageProblems("Addons → install page, installed")
        press(.select)
        requireGone("addons.install.done", "Done closes the install page")
        moveFocus(to: "addons.row.0", with: .down, limit: 6, "Down reaches the first addon")
        problems += coverProblems("Addons → Cinemeta's details", shows: "addons.details.close")
        if !problems.isEmpty { fail(problems.joined(separator: "; ")) }
    }

    /// Opens the page over the focused row with Select, waits for `id` on it (with `prefix`, an identifier starting so),
    /// takes its problems (`pageProblems`) and closes it with Menu, unless it closed by itself.
    private func coverProblems(_ what: String, shows id: String, prefix: Bool = false) -> [String] {
        press(.select)
        let shown = prefix
            ? app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", id)).firstMatch
            : element(id)
        if !shown.exists(within: 10) { fail("\(what): Select opens no page with \(id)") }
        let problems = pageProblems(what)
        if shown.exists { press(.menu) }
        if !waitUntil(10, { !shown.exists }) { fail("\(what): Menu doesn't close the page") }
        return problems
    }

    /// What is amiss with the page on the screen now, which it shoots as `what`: its title (the navigation bar highest
    /// on the screen: a page's own over the one it covers) should read at 4.5:1 or more against the black behind it
    /// (tvOS's default title colour, white at 25 %, read at 2.0:1), and its rows keep to the readable column
    /// (`readableColumn`: 1200 points, a focused row's platter a little wider).
    private func pageProblems(_ what: String) -> [String] {
        let titled = { self.app.navigationBars.allElementsBoundByIndex.filter { $0.exists && $0.staticTexts.count > 0 } }
        if !waitUntil(5, { !titled().isEmpty }) { return ["\(what): no navigation bar shows a title"] }
        // The push or the cover ends, and the title fades in with it: the shot once the screen has settled.
        screenSettles()
        guard let bar = titled().min(by: { $0.frame.minY < $1.frame.minY }) else { return ["\(what): no navigation bar shows a title"] }
        let named = bar.staticTexts[bar.identifier]
        let heading = named.exists ? named : bar.staticTexts.firstMatch
        shot(what)
        guard let pixels = ScreenPixels() else { return ["\(what): the screenshot has no pixels"] }
        let contrast = pixels.contrast(in: heading.frame)
        let widest = app.cells.allElementsBoundByIndex.filter { $0.exists && $0.isHittable }.map(\.frame.width).max() ?? 0
        let measured = String(format: "%@: the title \"%@\" reads at %.1f:1, the widest row is %d points", what, heading.label, contrast, Int(widest))
        XCTContext.runActivity(named: measured) { _ in }
        var problems: [String] = []
        if contrast < 4.5 { problems.append(String(format: "%@: the title \"%@\" reads at %.1f:1, under 4.5:1", what, heading.label, contrast)) }
        if widest > 1260 { problems.append("\(what): a row \(Int(widest)) points wide runs past the 1200-point column") }
        return problems
    }

    func testDeviceSettingOutlastsARelaunch() throws {
        let data = freshDataName()
        launchDev(keepingData: data)
        // The tab bar has focus until the Board's shelves load and their first card takes it: the way to Settings
        // starts from there, or Right moves along a shelf instead of the tabs.
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openSection("device")
        let toggle = element("settings.device.hardwareDecoding")
        moveFocus(to: "settings.device.hardwareDecoding", with: .down, "This device's first switch takes focus")
        let before = switchValue(toggle)
        press(.select)
        if !waitUntil(5, { toggle.exists && switchValue(toggle) != before }) { fail("Select doesn't switch hardware decoding") }
        shot("hardware decoding switched")
        app.terminate()
        launchDev(keepingData: data)
        requireFocus("board.row.0.item.0", timeout: 90, "after a relaunch the first card has focus once the Board's shelves load")
        openSection("device")
        let again = require("settings.device.hardwareDecoding", "after a relaunch, This device lists hardware decoding")
        if !waitUntil(5, { switchValue(again) != before }) { fail("after a relaunch hardware decoding is back as it was") }
        shot("after relaunch")
    }

    /// While loudness normalization is on (the player's choice, kept for the next video; here set at the launch), This
    /// device's Passthrough switch is disabled and says passthrough is off meanwhile, in place of its hint: the remote
    /// passes over it between the rows around it. Once normalization is off (the dev-setting link) the switch is enabled
    /// again, takes focus and says its hint.
    func testPassthroughIsDisabledWhileLoudnessIsNormalized() throws {
        try launchDev(settings: ["audio.normalization=true"])
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openSection("device")
        let passthrough = "settings.device.audioPassthrough", cache = "settings.device.cacheSize"
        moveFocus(to: cache, with: .down, "Down reaches the cache size, under the passthrough switch")
        requirePassthroughSwitch(normalized: true, "with loudness normalization on")
        press(.up)
        if holdsFocus(passthrough) { fail("with loudness normalization on, Up from the cache size stops on the passthrough switch") }
        shot("passthrough while normalized")
        press(.down)
        requireFocus(cache, "Down goes back to the cache size")
        changeDevSetting("audio.normalization=false")
        requirePassthroughSwitch(normalized: false, "once loudness normalization is off")
        press(.up)
        requireFocus(passthrough, "once loudness normalization is off, Up from the cache size reaches the passthrough switch")
        shot("passthrough")
    }

    func testPlaybackStepperStaysWithinItsBounds() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openSection("playback")
        let id = "settings.playback.watchedThreshold"
        // Down along the page to its last stepper, then Up to the threshold's: the form scrolls the rows in as focus
        // comes near them.
        moveFocus(to: "settings.playback.seekLong.value", with: .down, limit: 14, "Down reaches the last stepper")
        moveFocus(to: "\(id).value", with: .up, limit: 6, "Up reaches the watched threshold's value")
        // Up is the press towards the forward chevron: Right, and Left in a right-to-left language, whose row mirrors.
        let (up, down) = Self.rightToLeft ? (("Left", XCUIRemote.Button.left), ("Right", XCUIRemote.Button.right))
            : (("Right", XCUIRemote.Button.right), ("Left", XCUIRemote.Button.left))
        for _ in 0..<8 where !stepperText(id).contains("100") { press(up.1) }
        if !stepperText(id).contains("100") { fail("\(up.0) on the value stops at \"\(stepperText(id))\", not 100") }
        shot("at the maximum")
        press(up.1)
        if !waitUntil(2, { stepperText(id).contains("100") }) { fail("\(up.0) past the maximum shows \"\(stepperText(id))\"") }
        press(down.1)
        if !waitUntil(5, { stepperText(id).contains("95") }) { fail("\(down.0) from the maximum shows \"\(stepperText(id))\", not 95") }
        shot("stepped down to 95")
    }

    func testStreamPatternEditorFlagsAnInvalidPatternAndClears() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openStreamsSettings()
        requireText("settings.playback.streams.autoPlay", contains: "Off", "auto-play starts off")
        let suggest = "settings.playback.streams.suggestions"
        moveFocus(to: suggest, with: .down, limit: 1, "Down from auto-play reaches Suggest a stream")
        if switchIsOn(element(suggest)) { fail("Suggest a stream starts on") }
        shot("suggest a stream")
        moveFocus(to: "settings.playback.streams.exclude", with: .down, limit: 9, "Down reaches the Streams group's last row")
        shot("streams group")
        let include = "settings.playback.streams.include"
        focusStreamsRow(include)
        press(.select)
        requireFocus("settings.streams.pattern.field", "Only streams matching opens its editor with focus on the field")
        press(.select)
        typeOnKeyboard("1080p(")
        require("settings.streams.pattern.error", timeout: 5, "an unclosed group says why the filters would ignore it")
        requireText("settings.streams.pattern.error", contains: StreamFilterTexts.invalidPattern, "the editor's line")
        shot("invalid pattern typed")
        moveFocus(to: "settings.streams.pattern.save", with: .down, "Down reaches Save")
        press(.select)
        requireGone("settings.streams.pattern.field", "Save closes the editor")
        requireText(include, contains: "1080p(", "the row shows the stored pattern")
        requireText(include, contains: StreamFilterTexts.invalidPattern, "the row says the stored pattern is ignored")
        shot("invalid pattern stored")

        focusStreamsRow(include)
        press(.select)
        requireText("settings.streams.pattern.field", contains: "1080p(", "the editor starts from the stored pattern")
        moveFocus(to: "settings.streams.pattern.clear", with: .down, "Down reaches Clear")
        press(.select)
        requireGone("settings.streams.pattern.field", "Clear closes the editor")
        requireText(include, contains: "Any stream", "Clear removes the pattern")
        if allText(of: element(include)).contains(StreamFilterTexts.invalidPattern) { fail("cleared, the row still says the pattern is ignored") }
        shot("pattern cleared")
    }

    func testSubtitleSizeStepsAndThePreviewShows() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openSection("subtitles")
        require("settings.subtitles.preview", "Subtitles shows its preview")
        let id = "settings.subtitles.size"
        // Down along the page to the height's stepper, then Up to the size's: the form scrolls the rows in as focus
        // comes near them.
        moveFocus(to: "settings.subtitles.position.value", with: .down, limit: 10, "Down reaches the height's value")
        moveFocus(to: "\(id).value", with: .up, limit: 8, "Up reaches the size's value")
        shot("subtitles")
        press(.right, count: 3)
        if !waitUntil(5, { stepperText(id).contains("130%") }) { fail("Right three times from 100% shows \"\(stepperText(id))\", not 130%") }
        shot("size 130%")
    }

    /// Appearance's rows in order, each one Down from the one before: the interface language, Blur unwatched episodes,
    /// Hide watched titles, Hide titles under posters and the poster URL template.
    func testAppearanceListsItsRowsInOrder() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openSection("appearance")
        requireRowsInOrder(["interfaceLanguage", "blurUnwatched", "hideWatched", "hidePosterCaptions", "posterTemplate"], "")
        shot("appearance")
    }

    /// A kids profile (`--dev-kids`) has every Appearance row but the poster URL template, which it ignores.
    func testKidsAppearanceHasNoPosterTemplate() throws {
        try launchDev(arguments: ["--dev-kids", "2468"])
        openSection("appearance")
        requireRowsInOrder(["interfaceLanguage", "blurUnwatched", "hideWatched", "hidePosterCaptions"], "a kids profile's ")
        if !holds(for: 3, { !element("settings.appearance.posterTemplate").exists }) {
            fail("a kids profile's Appearance lists the poster URL template, which it ignores")
        }
        shot("appearance-kids")
    }

    /// Appearance's rows `ids` (`settings.appearance.<id>`): focus on the first, then each one Down from the one before.
    private func requireRowsInOrder(_ ids: [String], _ whose: String) {
        require("settings.appearance.\(ids[0])", timeout: 30, "\(whose)Appearance lists \(ids[0])")
        moveFocus(to: "settings.appearance.\(ids[0])", with: .up, limit: 4, "\(whose)Appearance: Up reaches \(ids[0])")
        for (above, below) in zip(ids, ids.dropFirst()) {
            moveFocus(to: "settings.appearance.\(below)", with: .down, limit: 1, "\(whose)Appearance: Down from \(above) reaches \(below)")
        }
    }

    func testAddonInstallsMovesShowsItsLinkAndIsRemoved() throws {
        let url = try addonURL
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openSection("addons")
        require("addons.install", "Settings' Addons row opens Addons")
        let before = addonRowCount
        // Pushed over Settings' list, the Addons section takes focus at its top row, where Install leads.
        moveFocus(to: "addons.install", with: .up, limit: 4, "Install takes focus at the top")
        press(.select)
        requireFocus("addons.install.link", "the install page's link field has focus")
        shot("install link")
        press(.select)
        app.typeText(url)
        shot("link typed")
        app.typeText("\n")
        requireFocus("addons.install.confirm", timeout: 30, "the fetched manifest offers Install, focused")
        shot("preview")
        press(.select)
        requireFocus("addons.install.done", timeout: 30, "the addon installs; Done has focus")
        press(.select)
        requireGone("addons.install.done", "Done closes the install page")
        if !waitUntil(10, { addonRowCount == before + 1 }) { fail("\(addonRowCount) addons listed after the install, not \(before + 1)") }
        let last = before
        let name = require("addons.row.\(last)", timeout: 5, "Addons lists the installed addon").label
        shot("installed")

        moveFocus(to: "addons.row.\(last)", with: .down, limit: 12, "Down reaches the new addon")
        if element("addons.row.\(last).up").exists { fail("the addon's row shows its own Move up button") }
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        focusMenuEntry("addons.row.\(last).up", "a long press of Select on the addon opens its menu with Move up")
        shot("addon menu")
        press(.select)
        requireAddonRow(last - 1, labelled: name, "Move up moves the addon up one place")
        shot("moved up")

        moveFocus(to: "addons.row.\(last - 1)", with: .up, limit: 3, "focus stays on the moved addon")
        press(.select)
        requireFocus("addons.details.close", "the details open with Close focused")
        moveFocus(to: "addons.details.reveal", with: .up, "Up reaches Show link")
        press(.select)
        let shown = require("addons.details.link", timeout: 5, "Show link shows the link")
        if shown.label != url { fail("the details show \"\(shown.label)\" as the link, not the addon's") }
        shot("link shown")
        if !waitUntil(3, { holdsFocus("addons.details.hide") }) {
            moveFocus(to: "addons.details.hide", with: .up, limit: 3, "Hide takes Show link's place")
        }
        press(.select)
        requireGone("addons.details.link", timeout: 5, "Hide hides the link")
        press(.menu)
        requireGone("addons.details.close", "Menu closes the details")

        requireFocus("addons.row.\(last - 1)", "focus returns to the addon whose details closed")
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        focusMenuEntry("addons.row.\(last - 1).remove", "a long press of Select on the addon opens its menu with Remove")
        shot("addon context menu")
        press(.select)
        require("addons.remove.confirm", timeout: 5, "Remove asks first")
        shot("remove asked")
        focusDialogButton("addons.remove.confirm", "the confirmation's Remove takes focus")
        press(.select)
        requireNoAddonRow(labelled: name, "confirming removes the addon")
        shot("removed")
    }

    /// An addon switched off by its row's switch (Right from the row, then Select) leaves Home with its catalog's shelf,
    /// and switched on again brings it back: the watch-activity addon alone, in a session without Cinemeta, so Home's
    /// one shelf is its catalog's.
    func testAddonSwitchedOffLeavesHomeAndSwitchedOnComesBack() throws {
        try launchWithActivityAddonAlone()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelf loads")
        requireActivityShelf(shown: true, "Home with the addon on")
        shot("home with the addon on")
        openSection("addons")
        let toggle = focusAddonSwitch(0, "the remote reaches the addon's switch")
        if !switchIsOn(toggle) { fail("the addon's switch starts off") }
        press(.select)
        if !waitUntil(5, { !switchIsOn(toggle) }) { fail("Select on the addon's switch doesn't turn it off") }
        shot("addon off")
        focusTabBar()
        moveFocus(to: "nav.board", with: .left, limit: 6, "Left along the tab bar reaches Home")
        requireActivityShelf(shown: false, "Home with the addon off")
        shot("home with the addon off")
        moveFocus(to: "nav.settings", with: .right, limit: 6, "Right along the tab bar reaches Settings")
        // Settings comes back on the page it was left on: Addons, else its list.
        if !element("addons.install").exists(within: 5) {
            press(.down)
            moveFocus(to: "settings.section.addons", with: .down, limit: 10, "Down reaches addons in Settings")
            press(.select)
        }
        let again = focusAddonSwitch(0, "the remote reaches the addon's switch again")
        if switchIsOn(again) { fail("the addon's switch reads on after a trip to Home") }
        press(.select)
        if !waitUntil(5, { switchIsOn(again) }) { fail("Select on the addon's switch doesn't turn it on") }
        focusTabBar()
        moveFocus(to: "nav.board", with: .left, limit: 6, "Left along the tab bar reaches Home")
        requireActivityShelf(shown: true, "Home with the addon on again")
        shot("home with the addon on again")
    }

    /// An addon whose manifest asks for the user's watch activity: its install page opens with the agreement focused
    /// and Install disabled; Select on the agreement switches it on and enables Install, which Down reaches; installed,
    /// the remote reaches the row's switch, on, and in the details, opened on Close, Down reaches the addon's switch
    /// and then Send watch activity, both on.
    func testWatchActivityAddonInstallsOnlyOnceAgreed() throws {
        let url = try activityAddonURL
        launch(["--dev", "--dev-open", "addons"] + Self.harnessSettings.flatMap { ["--dev-setting", $0] })
        require("addons.row.0", timeout: 30, "Addons lists the dev profile's addons")
        let before = addonRowCount
        moveFocus(to: "addons.install", with: .up, limit: 4, "Install takes focus at the top")
        press(.select)
        requireFocus("addons.install.link", "the install page's link field has focus")
        press(.select)
        app.typeText(url + "\n")
        let consent = "addons.install.watchActivity"
        requireFocus(consent, timeout: 30, "the fetched manifest's agreement has focus first")
        require("addons.install.confirm", "the install page offers Install")
        let confirm = app.buttons.matching(identifier: "addons.install.confirm").firstMatch
        if switchIsOn(element(consent)) { fail("the agreement starts on") }
        if confirm.isEnabled { fail("Install is enabled before the agreement is switched on") }
        shot("install waiting for the agreement")
        press(.select)
        if !waitUntil(5, { confirm.isEnabled }) { fail("Select on the agreement doesn't enable Install") }
        moveFocus(to: "addons.install.confirm", with: .down, limit: 2, "Down reaches Install")
        shot("install agreed")
        press(.select)
        requireFocus("addons.install.done", timeout: 30, "the addon installs; Done has focus")
        press(.select)
        requireGone("addons.install.done", "Done closes the install page")
        if !waitUntil(10, { addonRowCount == before + 1 }) { fail("\(addonRowCount) addons listed after the install, not \(before + 1)") }
        let toggle = focusAddonSwitch(before, "the remote reaches the new addon's switch")
        if !switchIsOn(toggle) { fail("the new addon's switch is off") }
        shot("installed, its switch focused")
        moveFocus(to: "addons.row.\(before)", with: .left, limit: 2, "Left returns to the addon's row")
        press(.select)
        requireFocus("addons.details.close", "the details open with Close focused")
        moveFocus(to: "addons.details.enabled", with: .down, limit: 2, "Down from Close reaches the addon's switch")
        if !switchIsOn(element("addons.details.enabled")) { fail("the details' switch is off") }
        moveFocus(to: "addons.details.watchActivity", with: .down, limit: 2, "Down reaches Send watch activity")
        if !switchIsOn(element("addons.details.watchActivity")) { fail("Send watch activity is off after installing with the agreement") }
        shot("details, send watch activity focused")
    }

    /// Down along Addons until the row at `index` or its switch has focus, then Right onto the switch
    /// (`addons.row.<index>.enabled`), which it returns; fails with `what` when the remote doesn't reach it.
    private func focusAddonSwitch(_ index: Int, _ what: String) -> XCUIElement {
        let row = "addons.row.\(index)"
        let toggle = "\(row).enabled"
        require(row, timeout: 30, what)
        for _ in 0..<(index + 4) where ![row, toggle].contains(focusedId ?? "") {
            press(.down)
        }
        if focusedId == row { press(.right) }
        requireFocus(toggle, what)
        return element(toggle)
    }

    func testStremioInstallLinkShowsItsInstallPageOverTheScreen() throws {
        try launchDev()
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openInstallLink("with a Board card focused")
        moveFocus(to: "addons.install.cancel", with: .down, limit: 2, "Down reaches Cancel")
        press(.select)
        requireGone("addons.install.cancel", "Cancel closes the install page")
        requireFocus("board.row.0.item.0", "Cancel leaves the Board as it was, its card focused")
        if element("addons.install").exists { fail("Cancel opened Addons") }

        focusTabBar()
        moveFocus(to: "nav.board", with: .left, limit: 6, "Left along the tab bar reaches Home")
        openInstallLink("with Home's button in the tab bar focused")
        press(.select)
        require("addons.install", timeout: 30, "Install lands on Addons")
        let row = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier MATCHES %@ AND label BEGINSWITH %@", #"addons\.row\.[0-9]+"#, Self.installLinkAddon)).firstMatch
        if !row.exists(within: 10) { fail("Addons doesn't list \(Self.installLinkAddon) after Install") }
        // Addons stays: focus moving on from Install doesn't take it away again.
        if !holds(for: 2, { element("addons.install").exists }) {
            fail("Addons went away again after Install (focus on \(focusedId ?? "nothing"))")
        }
        shot("addons after install")
    }

    /// Opens the install link from outside the app: its install page shows, Install focused. Fails with `what`.
    private func openInstallLink(_ what: String) {
        openFromOutside(Self.installLink)
        requireInstallPreview(of: Self.installLinkAddon, "a stremio:// install link opened \(what)")
        requireFocus("addons.install.confirm", "\(what): Install has focus on the install page")
        shot("install page \(what)")
    }

    func testConfigureShowsTheConfigurationPageAsAQRCode() throws {
        let url = try configurableAddonURL
        let host = URL(string: url)?.host ?? ""
        launch(["--dev", "--dev-addon", url, "--dev-open", "addons"] + Self.harnessSettings.flatMap { ["--dev-setting", $0] })
        let name = "Tortuga Configurable Test"
        let row = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier MATCHES %@ AND label BEGINSWITH %@", #"addons\.row\.[0-9]+"#, name)).firstMatch
        if !row.exists(within: 30) { fail("Addons doesn't list \(name)") }
        let id = row.identifier
        moveFocus(to: id, with: .down, limit: 12, "Down reaches \(name)")

        XCUIRemote.shared.press(.select, forDuration: 1.5)
        focusMenuEntry("\(id).configure", "a long press of Select on the addon opens its menu with Configure")
        press(.select)
        requireConfigureHandoff(host: host, "the menu's Configure")
        shot("configure from the menu")
        requireOwnFocus("openOnPhone.close", "Close has focus under the QR code")
        press(.select)
        requireGone("openOnPhone.qr", "Close closes the QR code")
        requireFocus(id, "focus returns to the addon")

        press(.select)
        requireFocus("addons.details.close", "the details open with Close focused")
        shot("details with the background")
        moveFocus(to: "addons.details.configure", with: .up, limit: 4, "Up reaches the details' Configure")
        press(.select)
        requireConfigureHandoff(host: host, "the details' Configure")
        shot("configure from the details")
        press(.menu)
        requireGone("openOnPhone.qr", "Menu closes the QR code")
        requireFocus("addons.details.configure", "focus returns to the details' Configure")
    }

    func testConfigureWithALinkTooLongForAQRCodeSaysSo() throws {
        // A configuration of 3,000 characters in the addon's link: its page's link is past what a QR code holds (about
        // 2.3 KB at medium correction).
        let url = try configurableAddonURL.replacingOccurrences(
            of: "/configurable/manifest.json",
            with: "/configurable/\(String(repeating: "c", count: 3000))/manifest.json"
        )
        let host = URL(string: url)?.host ?? ""
        launch(["--dev", "--dev-addon", url, "--dev-open", "addons"] + Self.harnessSettings.flatMap { ["--dev-setting", $0] })
        let name = "Tortuga Configurable Test"
        let row = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier MATCHES %@ AND label BEGINSWITH %@", #"addons\.row\.[0-9]+"#, name)).firstMatch
        if !row.exists(within: 30) { fail("Addons doesn't list \(name)") }
        let id = row.identifier
        moveFocus(to: id, with: .down, limit: 12, "Down reaches \(name)")

        XCUIRemote.shared.press(.select, forDuration: 1.5)
        focusMenuEntry("\(id).configure", "a long press of Select on the addon opens its menu with Configure")
        press(.select)
        requireConfigureHandoff(host: host, "Configure with a link too long for a QR code")
        requireText("openOnPhone.qr", contains: "can't be shown as a QR code", "the page says the link can't be a QR code, not a blank square")
        shot("configure with a link too long for a QR code")
        requireOwnFocus("openOnPhone.close", "Close has focus under the line")
        press(.select)
        requireGone("openOnPhone.qr", "Close closes the page")
    }

    /// The configurable addon's page shown as a QR code to open on a phone, over whatever was up: the site it leads to
    /// (the addon's `host`) named, the link itself never written out. Fails with `what`.
    private func requireConfigureHandoff(host: String, _ what: String) {
        require("openOnPhone.qr", timeout: 10, "\(what) shows the page as a QR code")
        requireText("openOnPhone.site", contains: host, "\(what): the QR code's page names the site")
        let written = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "/configure"))
        if written.count > 0 { fail("\(what): the page writes out the link (\(written.firstMatch.label))") }
    }

    /// Waits for `id` itself to have focus: the row under a panel spans the panel's buttons too, so `requireFocus`
    /// would take the row that still has focus behind the panel for them. Fails with `what`.
    private func requireOwnFocus(_ id: String, _ what: String) {
        require(id, timeout: 10, what)
        if !waitUntil(10, { element(id).hasFocus }) { fail("\(what): focus is on \(focusedId ?? "nothing"), not \(id)") }
    }

    func testHomeRowsHiddenAndMovedShowOnHomeOutlastARelaunchAndReset() throws {
        // Two launches, each waiting for the Board's shelves from live Cinemeta: longer than the run's default 5 minutes.
        executionTimeAllowance = 600
        let url = try addonURL
        let data = freshDataName()
        launchDev(keepingData: data, addon: url)
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        openSection("home")
        let rows = requireHomeRows("Settings → Home lists Home's rows")
        let titles = (0..<rows).map(homeRowTitle)
        let moved = titles[rows - 1]

        focusHomeRowControl("settings.home.row.0", row: 0, "Down reaches the first row's switch")
        shot("home rows")
        requireHomeRow(0, shown: true, "\(titles[0]) starts shown")
        press(.select)
        requireHomeRow(0, shown: false, "Select on the switch hides \(titles[0])")
        focusHomeRowControl("settings.home.row.\(rows - 1).up", row: rows - 1, "Down and Right reach the last row's Move up")
        // Select again and again on the same button: focus moves with the row, to its Move down once at the top.
        for index in stride(from: rows - 1, to: 0, by: -1) {
            press(.select)
            requireHomeRow(index - 1, titled: moved, "Select on Move up moves \(moved) up one place")
            let next = index > 1 ? "settings.home.row.\(index - 1).up" : "settings.home.row.0.down"
            requireFocus(next, "focus moves with \(moved) to row \(index - 1)")
            if index == rows - 1 { shot("reordering with focus on move up") }
        }
        requireHomeRow(1, titled: titles[0], "\(titles[0]) follows the moved row")
        requireHomeRow(1, shown: false, "\(titles[0]) stays hidden after the moves")
        shot("home rows hidden and moved")

        focusTabBar()
        moveFocus(to: "nav.board", with: .left, "Left reaches the Home tab")
        requireBoardShelves([moved, titles[1]], without: titles[0], "Home shows \(moved) first and not \(titles[0])")
        shot("home after the change")

        app.terminate()
        launchDev(keepingData: data, addon: url)
        requireFocus("board.row.0.item.0", timeout: 90, "after a relaunch the first card has focus once the Board's shelves load")
        requireBoardShelves([moved, titles[1]], without: titles[0], "after a relaunch Home shows \(moved) first and not \(titles[0])")
        shot("home after relaunch")
        openSection("home")
        requireHomeRow(0, titled: moved, "after a relaunch, Settings → Home lists \(moved) first")
        requireHomeRow(1, titled: titles[0], "after a relaunch, \(titles[0]) follows it")
        requireHomeRow(1, shown: false, "after a relaunch \(titles[0]) stays hidden")
        shot("home rows after relaunch")

        moveFocus(to: "settings.home.reset", with: .down, limit: rows + 6, "Down reaches Reset to default")
        press(.select)
        requireHomeRow(0, titled: titles[0], "Reset to default puts \(titles[0]) back first")
        requireHomeRow(0, shown: true, "Reset to default shows \(titles[0]) again")
        requireHomeRow(rows - 1, titled: moved, "Reset to default puts \(moved) back last")
        if element("settings.home.reset").isEnabled { fail("Reset to default stays enabled with Home's default rows") }
        shot("home rows reset")
    }

    /// Down along Settings → Home until a control of the row at `row` has focus, then along the row to `id` (its
    /// switch, `.up` or `.down`, in that order from the leading side).
    private func focusHomeRowControl(_ id: String, row: Int, _ what: String) {
        let controls = ["settings.home.row.\(row)", "settings.home.row.\(row).up", "settings.home.row.\(row).down"]
        for _ in 0..<(row + 4) where !controls.contains(focusedId ?? "") {
            press(.down)
        }
        guard let here = controls.firstIndex(of: focusedId ?? ""), let target = controls.firstIndex(of: id) else {
            fail("\(what): Down leaves focus on \(focusedId ?? "nothing"), not on row \(row)")
            return
        }
        moveFocus(to: id, with: target < here ? .left : .right, limit: 2, what)
    }

    /// Moves focus in the open menu to its entry `id`. The context menu of a long press focuses the collection cell
    /// around the entry, not the identified element itself (`holdsFocus` counts the focused element around `id`).
    private func focusMenuEntry(_ id: String, _ what: String) {
        require(id, timeout: 5, "\(what): the menu has no \(id)")
        for button in [XCUIRemote.Button.down, .down, .down, .up, .up, .up] where !holdsFocus(id) {
            press(button)
        }
        if !holdsFocus(id) { fail("\(what): focus is on \(focusedEntry), not \(element(id).label)") }
    }
}
