import XCTest

/// Settings with the pointer and keyboard, what the Mac alone has: the profile editor's sheet shows it whole, and its
/// Kids profile section on a new profile takes the kids rules; a menu
/// picker and a switch on the centre line of their rows' title and subtitle; an addon's details stay up on Return and
/// close on Escape; Trackers with every tracker connected, Settings' longest section: its last switch, AniList's Anime,
/// comes into the window as the wheel turns over the page, and a click switches it; the languages' sheet keeps its
/// height while a search narrows the list, and a language row's drag gives a text field nothing; every settings sheet a
/// dev session reaches ends right under its form: its buttons no more than 64 points under the form's last row
/// (`sheetProblems`).
extension SettingsTests {
    /// The editor's name and its buttons (Save, Cancel, Delete profile) lie in its sheet at the window's default size,
    /// however tall the form above the buttons.
    func testProfileEditorSheetShowsTheEditorWhole() throws {
        try launchDev()
        ready(require("nav.settings", timeout: 30, "the sidebar lists Settings")).click()
        let edit = require("settings.profile.edit", "Settings offers Edit profile")
        shot("settings")
        ready(edit).click()
        require("editor.name", "Edit profile opens the editor")
        let sheet = app.sheets.firstMatch
        if !sheet.exists(within: 5) { fail("the profile editor isn't in a sheet") }
        let cancel = require("editor.cancel", "the editor has Cancel")
        for id in ["editor.name", "editor.save", "editor.cancel", "editor.delete"] where !sheet.frame.contains(require(id, timeout: 5, "the profile editor has \(id)").frame) {
            fail("\(id) lies outside the editor's sheet: \(element(id).frame) in \(sheet.frame)")
        }
        shot("profile editor")
        ready(cancel).click()
        requireGone("editor.name", "Cancel closes the editor")
    }

    /// A new profile's Kids profile section in the editor's sheet (`--dev-open profile`, the dev
    /// profile locked by a PIN, so the switch can be turned on): a click turns it on, and the section shows the highest
    /// rating's choices with Kids chosen, Allow unrated titles off and the lines saying no title is allowed or blocked
    /// yet; a click chooses Little, another allows unrated titles (`requireKidsRules`). Nothing is saved.
    func testKidsProfileSectionTakesTheKidsRules() throws {
        try launchDev(open: ["profile"], arguments: ["--dev-profile", "Parent::2468"])
        require("editor.name", timeout: 30, "--dev-open profile opens a new profile's editor")
        let sheet = app.sheets.firstMatch
        if !sheet.exists(within: 5) { fail("the profile editor isn't in a sheet") }
        // The wheel turns over the sheet's scroll view: the sheet itself has no hit point to scroll at.
        let page = sheet.scrollViews.firstMatch
        let kids = scrollTo(element("editor.kids"), over: page, "the editor has the Kids profile switch")
        if !waitUntil(10, { kids.isEnabled }) { fail("with the dev profile locked by a PIN, Kids profile can't be turned on") }
        flipSwitch(kids)
        if !waitUntil(5, { switchValue(kids) == "1" }) { fail("a click doesn't turn Kids profile on") }
        requireKidsRules(over: page, picks: true, "the Mac's sheet")
    }

    /// An addon's details have one button, Close, which loses nothing: in the bar's cancellation slot, not the
    /// confirmation slot's default button. Return leaves the sheet up; Escape closes it with nothing in it focused.
    func testAddonDetailsCloseByEscapeNotByReturn() throws {
        try launchDev()
        openSection("addons")
        ready(require("addons.row.0", timeout: 30, "Addons lists Cinemeta")).click()
        require("addons.details.close", "a click on Cinemeta opens its details")
        shot("cinemeta's details")
        app.typeKey(.return, modifierFlags: [])
        if !holds(for: 2, { element("addons.details.close").exists }) { fail("Return closes the details: Close is their default button") }
        app.typeKey(.escape, modifierFlags: [])
        requireGone("addons.details.close", timeout: 5, "Escape closes the details")
        require("addons.row.0", "the details close back to Addons")
    }

    func testPickerAndSwitchSitOnTheirRowsCentreLine() throws {
        try launchDev()
        openSection("device")
        requireCentredOnItsRow("settings.device.cacheSize", "This device lists the read-ahead cache")
        requireCentredOnItsRow("settings.device.hardwareDecoding", "This device lists hardware decoding")
        shot("device rows")
        ready("settings.device.cacheSize").click()
        menuEntry("Automatic", "a click on the read-ahead cache opens its values")
        app.typeKey(.escape, modifierFlags: [])
    }

    func testTrackersLastSwitchScrollsIntoViewAndSwitches() throws {
        try launchDev(trackersConnected: true)
        openSection("trackers")
        let anime = scrollTo("settings.trackers.anilist.anime", "Trackers ends with AniList's Anime switch")
        let before = switchValue(anime)
        ready(anime).click()
        if !waitUntil(5, { switchValue(anime) != before }) { fail("a click doesn't switch AniList's Anime") }
        shot("trackers at its end")
    }

    /// The languages' sheet keeps its height while a search narrows the list under it: it opens as tall as the long
    /// list needs, and a query that leaves a few languages doesn't pull its bottom up.
    func testLanguagesSheetKeepsItsHeightWhileASearchNarrowsTheList() throws {
        try launchDev()
        openSection("playback")
        ready(require("settings.playback.audioLanguages", "Playback lists Audio languages")).click()
        let search = require("settings.languages.search", "Audio languages opens its editor")
        let sheet = app.sheets.containing(.any, identifier: "settings.languages.search").firstMatch
        if !sheet.exists(within: 5) { return fail("the languages' editor isn't in a sheet") }
        let available = app.descendants(matching: .any).matching(NSPredicate(format: "identifier MATCHES %@", #"settings\.languages\.available\.[0-9]+"#))
        // The sheet settles at its form's height.
        let before = settledFrame(of: sheet)
        let listed = available.count
        shot("languages before the search")
        ready(search).click()
        search.typeText("ger")
        // The search narrows the list, then the sheet settles at the height it keeps or takes.
        _ = waitUntil(5) {
            let count = available.count
            return count > 0 && count < listed
        }
        let narrowed = available.count
        if narrowed == 0 || narrowed >= listed { fail("searching \"ger\" lists \(narrowed) languages, not fewer than the \(listed) before") }
        let after = settledFrame(of: sheet)
        shot("languages after the search")
        if abs(after.height - before.height) > 2 {
            fail("the languages' sheet went from \(Int(before.height)) to \(Int(after.height)) points tall as \"ger\" narrowed the list from \(listed) to \(narrowed) languages")
        }
    }

    /// Watch together's relay, Addons' install page (for a link, with the fetched manifest, installed) and an addon's
    /// details, Playback's Player and its custom player, audio languages, Qualities and a stream pattern, Downloads'
    /// languages, and Connect on Trakt (`sheetProblems`). A Mac sheet takes no height from a form: at a fixed height
    /// the install preview ended 250 points above Cancel and Install.
    func testSettingsSheetsEndUnderTheirForms() throws {
        // Some twelve sheets, each opened and closed by its button: longer than the run's default 5 minutes.
        executionTimeAllowance = 600
        let url = try addonURL
        try launchDev(trackers: true)
        var problems: [String] = []
        require("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        ready(require("board.shortcut.watchTogether", timeout: 30, "Home's shortcuts offer Watch together")).click()
        require("watch.join.field", timeout: 60, "Home's Watch together opens the screen with its join field")
        problems += openedSheetProblems("the relay", from: scrollTo("watch.relay.setting", "the screen lists the relay"), buttons: "watch.relay.cancel")

        openSection("addons")
        ready(require("addons.install", "Settings' Addons row opens Addons")).click()
        let link = require("addons.install.link", "Install asks for the addon's link")
        problems += sheetProblems("the install page for a link", buttons: "addons.install.submit")
        ready(link).click()
        link.typeText(url)
        ready(require("addons.install.submit", "Install has Continue")).click()
        require("addons.install.confirm", timeout: 30, "the fetched manifest offers Install")
        problems += sheetProblems("the install preview", buttons: "addons.install.confirm")
        ready(element("addons.install.confirm")).click()
        require("addons.install.done", timeout: 30, "the addon installs")
        problems += sheetProblems("the install page, installed", buttons: "addons.install.done")
        ready(element("addons.install.done")).click()
        requireGone("addons.install.done", "Done closes the install page")
        problems += openedSheetProblems("Cinemeta's details", from: require("addons.row.0", "Addons lists Cinemeta"), buttons: "addons.details.close")

        openSection("playback")
        if element("settings.playback.player").exists(within: 3) {
            ready(element("settings.playback.player")).click()
            require("settings.player.close", timeout: 10, "Player opens its page")
            problems += sheetProblems("Player", buttons: "settings.player.close")
            if element("settings.player.addCustom").exists {
                problems += openedSheetProblems("Add custom player", from: element("settings.player.addCustom"), buttons: "settings.player.custom.cancel")
            }
            ready(element("settings.player.close")).click()
            requireGone("settings.player.close", "Close closes Player")
        }
        problems += openedSheetProblems("Audio languages", from: require("settings.playback.audioLanguages", "Playback lists Audio languages"), buttons: "settings.languages.close")
        openStreamsSettings()
        let suggest = element("settings.playback.streams.suggestions")
        if !suggest.exists {
            problems.append("the Streams group offers no Suggest a stream")
        } else {
            if element("settings.playback.streams.autoPlay").frame.minY >= suggest.frame.minY { problems.append("Suggest a stream isn't under auto-play") }
            if switchIsOn(suggest) { problems.append("Suggest a stream starts on") }
        }
        shot("streams group")
        problems += openedSheetProblems("Qualities", from: element("settings.playback.streams.qualities"), buttons: "settings.streams.qualities.close")
        problems += openedSheetProblems("the excluding pattern", from: element("settings.playback.streams.exclude"), buttons: "settings.streams.pattern.cancel")

        openSection("downloads")
        problems += openedSheetProblems("Downloads' languages", from: require("settings.downloads.subtitleLanguages", "Downloads lists its languages"), buttons: "settings.languages.close")

        openSection("trackers")
        // The fake Trakt approves the code after about 12 s, which closes the sheet by itself.
        problems += openedSheetProblems("Connect on Trakt", from: require("settings.trackers.trakt.connect", "Trackers offers Connect on Trakt"), buttons: "settings.trackers.connect.cancel")
        if !problems.isEmpty { fail(problems.joined(separator: "; ")) }
    }

    func testALanguageRowsDragGivesATextFieldNothing() throws {
        try launchDev()
        openSection("playback")
        ready(require("settings.playback.audioLanguages", "Playback lists Audio languages")).click()
        let search = require("settings.languages.search", "Audio languages opens its editor")
        let before = chosenLanguageCount
        for name in ["German", "French"] {
            ready(search).click()
            search.typeKey("a", modifierFlags: .command)
            search.typeText(name)
            ready(require("settings.languages.available.0", timeout: 10, "searching \(name) lists it")).click()
        }
        requireChosenLanguages(["German", "French"], from: before, "the added languages come last, in order")
        ready(search).click()
        search.typeKey("a", modifierFlags: .command)
        search.typeKey(.delete, modifierFlags: [])

        // A row's drag carries the row, not its language's code as text: dropped on the search field it types nothing,
        // through the second after the drop.
        let handle = require("settings.languages.chosen.\(before + 1).handle", "French shows its drag handle")
        ready(handle).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).click(
            forDuration: 0.3,
            thenDragTo: search.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)),
            withVelocity: .slow,
            thenHoldForDuration: 0.6
        )
        let typedNothing = { (search.value as? String).map { $0.isEmpty || $0 == search.placeholderValue } ?? true }
        if !holds(for: 1, typedNothing) {
            fail("French's row dropped on the search field typed \"\(search.value as? String ?? "")\" into it")
        }
        requireChosenLanguages(["German", "French"], from: before, "French dropped on the search field stays where it was")
        shot("row dropped on the search field")

        // The next drag after that one moves its row as ever.
        dragRow("settings.languages.chosen.\(before + 1)", onto: "settings.languages.chosen.\(before)", below: false)
        requireChosenLanguages(["French", "German"], from: before, "French dragged next onto German's upper half goes before it")
    }

    /// The control `id` sits on the centre line of its row's title and subtitle (the texts at its leading side, on its
    /// row of the page's column), as the row's value and chevron would; fails with `what` when it doesn't.
    private func requireCentredOnItsRow(_ id: String, _ what: String) {
        let control = require(id, what).frame
        let texts = app.staticTexts.allElementsBoundByIndex.map(\.frame).filter {
            $0.maxX <= control.minX && $0.minX > control.minX - 720 && $0.maxY > control.midY - 24 && $0.minY < control.midY + 24
        }
        guard let first = texts.first else { return fail("\(what): no title beside \(id) (\(control))") }
        let block = texts.dropFirst().reduce(first) { $0.union($1) }
        if abs(block.midY - control.midY) > 2 {
            fail("\(what): \(id) (y \(Int(control.midY))) sits off its row's centre line, its title and subtitle's (y \(Int(block.midY)), \(block))")
        }
    }

    /// Clicks `row`, takes the problems of the sheet it opens (`sheetProblems`, `button` among its buttons) and closes
    /// it with that button, unless it closed by itself.
    private func openedSheetProblems(_ what: String, from row: XCUIElement, buttons button: String) -> [String] {
        ready(row).click()
        require(button, timeout: 10, "a click on \(row.identifier) opens \(what)")
        let problems = sheetProblems(what, buttons: button)
        if element(button).exists { ready(element(button)).click() }
        requireGone(button, "\(button) closes \(what)")
        return problems
    }
}
