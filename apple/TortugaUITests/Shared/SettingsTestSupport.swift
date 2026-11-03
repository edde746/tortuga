import XCTest

/// What the Settings, Addons and Trackers tests share with each other: opening a Settings section, a `--dev` session
/// whose database outlasts a relaunch, reading the installed-addon rows, an addon's install link and the configurable
/// addon the Addons tests open, and the rows of Settings → Home against the Board's shelves.
extension TortugaUITestCase {
    #if os(tvOS)
    /// Up until a tab has focus: the tab bar sits above every screen, Settings' longest list included.
    func focusTabBar() {
        for _ in 0..<12 where focusedId?.hasPrefix("nav.") != true {
            press(.up)
        }
        if focusedId?.hasPrefix("nav.") != true { fail("Up doesn't reach the tab bar; focus is on \(focusedId ?? "nothing")") }
    }

    /// The Settings tab, then Down into its list and along it to `section`, and Select. Settings is the tab bar's last
    /// tab: Right reaches it, Left in a right-to-left language, whose tab bar runs from the right.
    func openSection(_ section: String) {
        require("nav.board", "the tab bar shows")
        focusTabBar()
        moveFocus(to: "nav.settings", with: Self.rightToLeft ? .left : .right, "the remote reaches the Settings tab")
        // The tab bar names the screen, which shows no title of its own: its first section tells it.
        require("settings.section.playback", timeout: 10, "Settings shows")
        press(.down)
        moveFocus(to: "settings.section.\(section)", with: .down, limit: 10, "Down reaches \(section) in Settings")
        press(.select)
    }
    #elseif os(iOS)
    /// The Settings tab, back to its list where Settings comes back on the page it was left on (every tab comes back as
    /// it was left), then the row of `section` (`settings.section.<section>`), scrolled clear of the tab bar first: the
    /// rows past Trackers lie under it or below the screen, and on a screen as short as the iPhone Duo's cover in
    /// landscape the list makes them only as they scroll in (`scrollTo`).
    func openSection(_ section: String) {
        ready(phoneTab("nav.settings")).tap()
        let row = element("settings.section.\(section)")
        let back = backButton
        for _ in 0..<3 where !row.exists(within: 3) && back.exists { ready(back).tap() }
        require("settings.section.playback", "Settings lists its sections")
        ready(scrollTo(row, "Settings shows \(section)'s row")).tap()
    }
    #elseif os(macOS)
    /// Settings in the sidebar, back to its list where Settings comes back on the page it was left on (every
    /// destination comes back as it was left), then the row of `section` (`settings.section.<section>`), whose page is
    /// pushed over the list.
    func openSection(_ section: String) {
        ready(require("nav.settings", timeout: 30, "the sidebar lists Settings")).click()
        let row = element("settings.section.\(section)")
        let back = app.toolbars.buttons.matching(NSPredicate(format: "label == 'Back' OR identifier == 'Back'")).firstMatch
        for _ in 0..<3 where !row.exists(within: 3) && back.exists { ready(back).click() }
        ready(require("settings.section.\(section)", "Settings lists \(section)")).click()
    }
    #endif

    #if !os(tvOS)
    /// The top-level destination `id` (`nav.board`): iPhone's or iPad's tab (`phoneTab`), or the Mac's sidebar row,
    /// which fails with `what` when the sidebar doesn't list it within 30 s.
    func navItem(_ id: String, _ what: String) -> XCUIElement {
        #if os(iOS)
        return phoneTab(id)
        #else
        return require(id, timeout: 30, what)
        #endif
    }
    #endif

    /// Opens Settings by `open` while `ScreenSampler` takes the screen, and fails with `what` where a frame shows
    /// Playback's row anywhere but where it stays once the profile has loaded above it: a tap, click or press aimed at
    /// a row as Settings opens lands on that row. The first frame that shows the row is attached.
    func requireSectionsStayPut(_ what: String, open: () -> Void) {
        let title = "Playback"
        let sampler = ScreenSampler()
        sampler.start()
        open()
        let row = require("settings.section.playback", timeout: 10, "\(what): Settings lists \(title)")
        require("settings.profile.edit", timeout: 10, "\(what): Settings shows the profile")
        // The sampler goes on half a second past the profile's coming, the row kept to its place meanwhile (as the
        // frames are read: its middle within half its height).
        let place = row.frame
        if !holds(for: 0.5, { abs(row.frame.midY - place.midY) <= place.height / 2 }) {
            fail("\(what): \(title)'s row moves from y \(Int(place.midY)) to y \(Int(row.frame.midY)) once the profile shows")
        }
        let frames = sampler.stop()
        // The row's column down the whole screen: Vision reads the row's title wherever a frame shows it.
        let column = CGRect(x: place.minX, y: 0, width: place.width, height: 10_000)
        func attach(_ frame: XCUIScreenshot, _ name: String) {
            let attachment = XCTAttachment(screenshot: frame)
            attachment.name = name
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        var shown = false
        for (index, frame) in frames.enumerated() {
            let read = frame.texts(in: column).filter { $0.text.hasSuffix(title) }
            if let away = read.first(where: { abs($0.frame.midY - place.midY) > place.height / 2 }) {
                attach(frame, "settings rows away from their place")
                fail("\(what): frame \(index + 1) of \(frames.count) shows \(title) at y \(Int(away.frame.midY)), its row's place is y \(Int(place.midY))")
            }
            if !read.isEmpty, !shown {
                shown = true
                attach(frame, "settings as it opens")
            }
        }
        if !shown { fail("\(what): none of the \(frames.count) frames sampled shows \(title)") }
    }

    /// Launches a `--dev` session that keeps its database under `name` (`--dev-data`), with the addon at `addon`
    /// installed (`--dev-addon`) where given, opened on `open` (`--dev-open` screens), with the watch progress
    /// `progress` (`--dev-progress` specs), the run's own device settings (`harnessSettings`) and `arguments` last
    /// (`--dev-crash-reports <dsn>`): launched again with the same name, it finds this device's settings and the dev
    /// profile's as they were left.
    func launchDev(
        keepingData name: String, addon: String? = nil, open: [String] = [], progress: [String] = [], arguments extra: [String] = []
    ) {
        launch(
            ["--dev", "--dev-data", name] + (addon.map { ["--dev-addon", $0] } ?? []) + open.flatMap { ["--dev-open", $0] }
                + progress.flatMap { ["--dev-progress", $0] } + Self.harnessSettings.flatMap { ["--dev-setting", $0] } + extra
        )
    }

    /// A name for `launchDev(keepingData:)` no other run of the test has used.
    func freshDataName() -> String {
        "settings-" + UUID().uuidString.prefix(8)
    }

    /// What the Addons row on Settings' first page counts ("3 addons installed"), once the addons are read; fails with
    /// `what` when it counts none.
    func installedAddons(_ what: String) -> String {
        let row = require("settings.section.addons", timeout: 30, what)
        var count = ""
        let counted = waitUntil(20) {
            count = allText(of: row).firstMatch(of: #/\d+ addons? installed/#).map { String($0.output) } ?? ""
            return !count.isEmpty
        }
        if !counted { fail("\(what): the Addons row says \"\(allText(of: row))\"") }
        return count
    }

    /// A settings switch's state as the accessibility tree reports it (`0` or `1`).
    func switchValue(_ element: XCUIElement) -> String {
        element.value.map { "\($0)" } ?? ""
    }

    /// Whether the switch `element` is on: it reads 1 (on tvOS a toggle button reads 1 while selected).
    func switchIsOn(_ element: XCUIElement) -> Bool {
        ["1", "true"].contains(switchValue(element).lowercased())
    }

    /// This device's Passthrough switch while loudness normalization is on (`normalized`): disabled, saying that
    /// passthrough is off meanwhile in place of its hint about the system's Dolby decoder; else enabled, saying the hint
    /// without the note. Waits up to 10 s; fails with `what`. tvOS reads the switch, whose label takes the row's texts;
    /// iOS and the Mac the row's texts (the Mac reports a text's words as its value).
    func requirePassthroughSwitch(normalized: Bool, _ what: String) {
        let row = require("settings.device.audioPassthrough", "\(what): This device lists passthrough")
        let note = "Off while loudness normalization is on", hint = "system's Dolby decoder"
        func shows(_ text: String) -> Bool {
            #if os(tvOS)
            row.label.contains(text)
            #else
            app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", text, text)).firstMatch.exists
            #endif
        }
        let (shown, gone) = normalized ? (note, hint) : (hint, note)
        if !waitUntil(10, { shows(shown) && !shows(gone) && row.isEnabled != normalized }) {
            fail(
                "\(what): the passthrough switch isn't \(normalized ? "disabled" : "enabled") saying \"\(shown)\" alone (enabled: "
                    + "\(row.isEnabled), the note shows: \(shows(note)), the hint: \(shows(hint)))"
            )
        }
    }

    #if os(tvOS)
    /// Picks `option` (its id and its label) for the focused choice row `id` (`SettingsChoicePicker`): Select opens its
    /// values as a list (`<id>.option.<option id>`), where the remote reaches the value, and Select picks it; the row
    /// then shows it.
    func pickChoice(_ id: String, option: (id: String, label: String)) {
        press(.select)
        let entry = "\(id).option.\(option.id)"
        require(entry, "\(id) opens its values")
        walkFocus(to: entry, "the remote reaches \(option.label)")
        press(.select)
        requireGone(entry, "picking \(option.label) closes the list")
        requireText(id, contains: option.label, "\(id) shows the choice picked")
    }
    #elseif os(iOS)
    /// Picks `option` (its id and its label) in the menu of the choice row `id` (`SettingsChoicePicker`), scrolled into
    /// view first (`showOnScreen`), which opens from the value the row shows, beside its title or under it; the row then
    /// shows it.
    func pickChoice(_ id: String, option: (id: String, label: String)) {
        ready(showOnScreen(id, "the page has \(id)")).tap()
        let entry = menuEntry(option.label, "\(id)'s menu")
        pickMenuEntry(entry)
        if !waitUntil(5, { !entry.exists }) { fail("picking \(option.label) doesn't close \(id)'s menu") }
        requireText(id, contains: option.label, "\(id) shows the choice picked")
    }
    #elseif os(macOS)
    /// Picks `option` (its id and its label) in the pop-up menu of the choice row `id` (`SettingsChoicePicker`),
    /// scrolled into view first (`showOnScreen`); the row then shows it.
    func pickChoice(_ id: String, option: (id: String, label: String)) {
        ready(showOnScreen(id, "the page has \(id)")).click()
        let entry = menuEntry(option.label, "\(id)'s menu")
        pickMenuEntry(entry)
        if !waitUntil(5, { !entry.exists }) { fail("picking \(option.label) doesn't close \(id)'s menu") }
        requireText(id, contains: option.label, "\(id) shows the choice picked")
    }
    #endif

    /// What the stepper `id` shows: its value's text (`<id>.value`), else the stepper's own label and value. On tvOS
    /// `<id>.value` is the stepper, named by its row's title, and the number is its value.
    func stepperText(_ id: String) -> String {
        let value = element("\(id).value")
        #if os(tvOS)
        return value.exists ? value.value as? String ?? "" : ""
        #else
        if value.exists { return text(of: value) }
        let stepper = element(id)
        return "\(stepper.label) \(stepper.value.map { "\($0)" } ?? "")"
        #endif
    }

    #if !os(tvOS)
    /// The step up (`up`) or down button of the stepper `id` (`settings.playback.watchedThreshold`).
    func stepperButton(_ id: String, up: Bool) -> XCUIElement {
        let stepper = element(id)
        #if os(macOS)
        let arrows = up ? stepper.incrementArrows : stepper.decrementArrows
        return arrows.count > 0 ? arrows.firstMatch : (up ? app.incrementArrows : app.decrementArrows).firstMatch
        #else
        let named = stepper.buttons[up ? "Increment" : "Decrement"]
        return named.exists ? named : stepper.buttons.element(boundBy: up ? 1 : 0)
        #endif
    }

    /// Steps the stepper `id`, scrolled into view first (`showOnScreen`), up until it shows `maximum`, checks a step past
    /// it changes nothing and a step down shows `below`; `press` presses a step button (a tap, a click).
    func requireStepperBounds(_ id: String, maximum: String, below: String, press: (XCUIElement) -> Void) {
        showOnScreen(id, "the page lists \(id)")
        for _ in 0..<8 where !stepperText(id).contains(maximum) { press(ready(stepperButton(id, up: true))) }
        if !waitUntil(5, { stepperText(id).contains(maximum) }) { fail("stepping up stops at \"\(stepperText(id))\", not \(maximum)") }
        shot("at the maximum")
        press(ready(stepperButton(id, up: true)))
        if !waitUntil(2, { stepperText(id).contains(maximum) }) { fail("a step past the maximum shows \"\(stepperText(id))\"") }
        press(ready(stepperButton(id, up: false)))
        if !waitUntil(5, { stepperText(id).contains(below) }) { fail("a step down shows \"\(stepperText(id))\", not \(below)") }
        shot("stepped down to \(below)")
    }
    #endif

    #if !os(tvOS)
    /// Flips the settings switch `toggle`: on the Mac a click; on iOS its switch control where the tree has one (a tap
    /// on a form row's label doesn't reach the switch), else the row's trailing end, by a 0.1 s press, as a finger
    /// gives: a freshly booted iPad simulator, busy for its first minutes, drops most of XCUITest's zero-length taps on
    /// a switch, never the press.
    func flipSwitch(_ toggle: XCUIElement) {
        #if os(macOS)
        ready(toggle).click()
        #else
        let control = toggle.switches.firstMatch
        if control.exists, control.frame != toggle.frame {
            ready(control).press(forDuration: 0.1)
        } else {
            ready(toggle).coordinate(withNormalizedOffset: CGVector(dx: 0.94, dy: 0.5)).press(forDuration: 0.1)
        }
        #endif
    }

    /// `id` scrolled into view, where a form or list makes its rows only as they scroll in: by swipes on iOS
    /// (`swipeUntilShown`), on the Mac by `scrollTo`; fails with `what` when it doesn't show.
    @discardableResult
    func showOnScreen(_ id: String, _ what: String, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        #if os(macOS)
        return scrollTo(id, what, file: file, line: line)
        #else
        return swipeUntilShown(id, what, file: file, line: line)
        #endif
    }

    /// The profile editor's Kids profile section once its switch is on (a new profile's), scrolled over `container`
    /// (the editor's scroll view): the highest rating's three choices, Kids chosen; Allow unrated titles off; the lines
    /// saying no title is allowed or blocked yet. With `picks`, a tap (a click) on Little chooses it alone and one on the
    /// unrated switch turns it on. Fails with `what`, the section's state.
    func requireKidsRules(over container: XCUIElement, picks: Bool, _ what: String) {
        let caps = (0..<3).map { element("editor.kids.cap.\($0)") }
        for (index, cap) in caps.enumerated() {
            scrollTo(cap, over: container, "\(what): the section lists the highest rating's choice \(index)")
        }
        if caps.map(\.isSelected) != [false, true, false] {
            fail("\(what): the highest rating's choices read selected \(caps.map(\.isSelected)), not Kids alone")
        }
        let unrated = scrollTo(element("editor.kids.unrated"), over: container, "\(what): the section has Allow unrated titles")
        if switchValue(unrated) != "0" { fail("\(what): Allow unrated titles reads \(switchValue(unrated)), not off") }
        shot("highest rating and unrated titles, \(what)")
        if picks {
            activate(scrollTo(caps[0], over: container, "\(what): Little shows again"))
            if !waitUntil(5, { caps.map(\.isSelected) == [true, false, false] }) {
                fail("\(what): \(Self.tapping) on Little leaves the choices selected \(caps.map(\.isSelected))")
            }
            flipSwitch(scrollTo(unrated, over: container, "\(what): Allow unrated titles shows again"))
            if !waitUntil(5, { switchValue(unrated) == "1" }) { fail("\(what): \(Self.tapping) doesn't allow unrated titles") }
            shot("Little and unrated titles picked, \(what)")
        }
        scrollTo(element("editor.kids.allowed.none"), over: container, "\(what): the section says no title is allowed yet")
        scrollTo(element("editor.kids.blockedTitle.none"), over: container, "\(what): the section says no title is blocked yet")
        shot("no allowed or blocked titles, \(what)")
    }
    #endif

    /// The installed-addon rows the Addons section lists (`addons.row.<i>`).
    var addonRowCount: Int {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier MATCHES %@", #"addons\.row\.[0-9]+"#)).count
    }

    /// An addon's install link as addon sites hand it out: WatchHub, one of Stremio's own, which a dev profile hasn't
    /// installed. A `stremio://` link is fetched over HTTPS, so the test addon (plain HTTP) can't be one.
    static let installLink = URL(string: "stremio://watchhub.strem.io/manifest.json")!
    static let installLinkAddon = "WatchHub"

    /// The second test addon, beside the test addon on its server: configurable, with a background
    /// (tools/media/test_addon.py's /configurable/manifest.json).
    var configurableAddonURL: String {
        get throws {
            try addonURL.replacingOccurrences(of: "/manifest.json", with: "/configurable/manifest.json")
        }
    }

    /// The watch-activity addon beside the test addon on its server (`/activity/manifest.json`): its manifest declares
    /// `player` and `library`, and one movie catalog, `activityCatalog`.
    var activityAddonURL: String {
        get throws {
            try addonURL.replacingOccurrences(of: "/manifest.json", with: "/activity/manifest.json")
        }
    }

    static let activityAddon = "Tortuga Watch Activity"
    static let activityCatalog = "Tortuga Activity"

    /// Launches a `--dev` session whose profile has the watch-activity addon alone (no Cinemeta), opened on `open`
    /// where given: Home's one shelf is its catalog's.
    func launchWithActivityAddonAlone(open: [String] = []) throws {
        launch(
            ["--dev", "--dev-addons-only", "--dev-addon", try activityAddonURL] + open.flatMap { ["--dev-open", $0] }
                + Self.harnessSettings.flatMap { ["--dev-setting", $0] }
        )
    }

    /// Waits for Home to show the watch-activity addon's shelf (`shown`: its first card, under a heading that holds the
    /// catalog's name: "Tortuga Activity · Movies", the name inside its direction marks) or none of it; fails with
    /// `what`.
    func requireActivityShelf(shown: Bool, _ what: String) {
        let card = element("board.row.0.item.0")
        let titled = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", Self.activityCatalog, Self.activityCatalog)
        )
        if shown {
            if !card.exists(within: 60) { fail("\(what): Home shows no card") }
            if !waitUntil(10, { titled.count > 0 }) { fail("\(what): Home shows no shelf titled \(Self.activityCatalog)") }
        } else if !waitUntil(15, { !card.exists && titled.count == 0 }) {
            fail("\(what): Home still shows \(Self.activityCatalog)")
        }
    }

    /// Waits for Addons' install page with `name`'s fetched manifest and Install, as a followed install link opens it;
    /// fails with `what`. The name is the page's heading, read as VoiceOver reads a heading, by its label, in the app's
    /// tree as a snapshot holds it: the Mac reports a heading's words as its label alone (a plain text's as its value),
    /// and there a `staticTexts` query matching label or value found no element while the tree held the heading
    /// labelled "WatchHub".
    func requireInstallPreview(of name: String, _ what: String) {
        require("addons.install.confirm", timeout: 30, "\(what): the install page offers Install")
        func reads(_ node: any XCUIElementSnapshot) -> Bool { node.label == name || node.children.contains(where: reads) }
        if !waitUntil(5, { (try? app.snapshot()).map(reads) == true }) { fail("\(what): the install page has no heading \(name)") }
    }

    /// Waits for the row at `index` to be the addon labelled `label`; fails with `what` when it isn't.
    func requireAddonRow(_ index: Int, labelled label: String, _ what: String) {
        let row = require("addons.row.\(index)", what)
        if !waitUntil(10, { row.label == label }) { fail("\(what): row \(index) is \"\(row.label)\", not \"\(label)\"") }
    }

    /// Waits until no addon row is labelled `label`; fails with `what` when one stays. One query for rows and label:
    /// counting the rows, then reading each, raced the removed row's disappearance.
    func requireNoAddonRow(labelled label: String, _ what: String) {
        let rows = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier MATCHES %@ AND label == %@", #"addons\.row\.[0-9]+"#, label))
        if !waitUntil(10, { rows.count == 0 }) { fail("\(what): \"\(label)\" is still listed") }
    }

    /// The chosen languages' rows in the open language editor (`settings.languages.chosen.<i>`).
    var chosenLanguageCount: Int {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier MATCHES %@", #"settings\.languages\.chosen\.[0-9]+"#)).count
    }

    /// The chosen languages from row `first` on are `names`, in order (each row reads its rank and its name), and no row
    /// follows them; fails with `what` when they aren't within a few seconds.
    func requireChosenLanguages(_ names: [String], from first: Int, _ what: String) {
        let rows = { (0..<names.count).map { self.allText(of: self.element("settings.languages.chosen.\(first + $0)")) } }
        let matches = {
            self.chosenLanguageCount == first + names.count && zip(rows(), names).allSatisfy { $0.contains($1) }
        }
        if !waitUntil(5, matches) { fail("\(what): the rows from \(first) read \(rows()), not \(names)") }
    }

    /// Waits for Settings → Home to list its rows (`settings.home.row.<i>`, the dev profile's Cinemeta catalogs); how
    /// many it lists. Fails with `what` when it lists fewer than three (one to hide, one to move past it, one after).
    func requireHomeRows(_ what: String) -> Int {
        require("settings.home.row.0", timeout: 30, what)
        let rows = app.descendants(matching: .any).matching(NSPredicate(format: "identifier MATCHES %@", #"settings\.home\.row\.[0-9]+"#))
        if rows.count < 3 { fail("\(what): \(rows.count) rows listed, not at least 3") }
        return rows.count
    }

    #if !os(tvOS)
    /// The farthest of Settings → Home's `rows` rows that a drag from the first one reaches on the screen: with the list
    /// showing its first row, the last row inside the window with its handle (`liesInside`) whose next row is inside
    /// too, unless it is the list's last. A drop aimed past the window lands nowhere (11
    /// rows on iPhone: the last row's handle swiped into the window left the first row off it), and a row held at the
    /// window's edge with rows past it can scroll the list on under it. On iOS the row also ends 80 points clear of the
    /// window's bottom: a row held nearer scrolls the list on whatever follows the rows (the iPhone Duo's
    /// cover: the last row, held at y 651 of 678, scrolled the list to Reset to default). Fails with `what` when fewer
    /// than three rows qualify (one to hide, one to move past it, one after).
    func farHomeRow(of rows: Int, _ what: String) -> Int {
        scrollTo(element("settings.home.row.0.handle"), up: true, "\(what): the list shows its first row")
        let inside = { (index: Int) in
            self.liesInside(self.element("settings.home.row.\(index)")) && self.liesInside(self.element("settings.home.row.\(index).handle"))
        }
        #if os(iOS)
        let bottom = windowFrame.maxY - 80
        let clear = { (index: Int) in self.element("settings.home.row.\(index)").frame.maxY <= bottom }
        #else
        let clear = { (_: Int) in true }
        #endif
        let far = (1..<rows).last { inside($0) && clear($0) && ($0 == rows - 1 || inside($0 + 1)) } ?? 0
        if far < 2 { fail("\(what): rows 0-\(far) of \(rows) share the window, not three") }
        return far
    }
    #endif

    /// The title of the row at `index` in Settings → Home: its switch's label, the row's title. A switch without a label
    /// of its own: the first of the texts on its line, at its leading side, top first. Empty while there is no such row.
    func homeRowTitle(_ index: Int) -> String {
        let row = element("settings.home.row.\(index)")
        guard row.exists else { return "" }
        let label = text(of: row)
        #if os(macOS)
        if label.isEmpty {
            let frame = row.frame
            return app.staticTexts.allElementsBoundByIndex
                .filter { $0.exists && abs($0.frame.midY - frame.midY) < 20 && $0.frame.midX < frame.maxX }
                .min { $0.frame.minY < $1.frame.minY }
                .map { text(of: $0) } ?? ""
        }
        #endif
        return label
    }

    /// Waits for the row at `index` in Settings → Home to be the one titled `title`; fails with `what` when it isn't.
    func requireHomeRow(_ index: Int, titled title: String, _ what: String) {
        require("settings.home.row.\(index)", what)
        if title.isEmpty { fail("\(what): the row's title can't be read") }
        if !waitUntil(10, { homeRowTitle(index) == title }) { fail("\(what): row \(index) is \"\(homeRowTitle(index))\", not \"\(title)\"") }
    }

    /// Whether the row at `index` in Settings → Home is shown on Home: its switch (on tvOS a toggle button) reads 1.
    func homeRowShown(_ index: Int) -> Bool {
        switchIsOn(element("settings.home.row.\(index)"))
    }

    /// Waits for the row at `index` in Settings → Home to be shown on Home (`shown`) or hidden; fails with `what` when
    /// it isn't.
    func requireHomeRow(_ index: Int, shown: Bool, _ what: String) {
        if !waitUntil(5, { element("settings.home.row.\(index)").exists && homeRowShown(index) == shown }) { fail("\(what): row \(index) is \(shown ? "hidden" : "shown")") }
    }

    /// Waits until the Board's first shelves are headed `titles` in order (each heading on the line of its shelf's
    /// `headingEnd`) and no shelf is headed `hidden`; fails with `what`. The shelves load from live Cinemeta: up to 90 s.
    func requireBoardShelves(_ titles: [String], without hidden: String, _ what: String) {
        let shown = waitUntil(90) {
            titles.indices.allSatisfy { shelf($0, isHeaded: titles[$0]) } && !shelfHeadings(hidden).contains { $0.exists }
        }
        guard !shown else { return }
        let found = titles.indices.map { index in
            let end = headingEnd(index)
            guard end.exists else { return "(no shelf \(index))" }
            return app.staticTexts.allElementsBoundByIndex
                .filter { $0.exists && abs($0.frame.midY - end.frame.midY) < max($0.frame.height, end.frame.height) && $0.frame.maxX < end.frame.minX }
                .map { text(of: $0) }
                .joined(separator: " | ")
        }
        let stillThere = shelfHeadings(hidden).contains { $0.exists } ? "; \"\(hidden)\" still shows" : ""
        fail("\(what): the first shelves are headed \(found), not \(titles)\(stillThere)")
    }

    /// The shelf at `index` is headed `title`: a text of that title on the line of its `headingEnd`, before it.
    private func shelf(_ index: Int, isHeaded title: String) -> Bool {
        let end = headingEnd(index)
        guard end.exists else { return false }
        let line = end.frame
        return shelfHeadings(title).contains { heading in
            let frame = heading.frame
            return abs(frame.midY - line.midY) < max(frame.height, line.height) && frame.maxX <= line.minX
        }
    }

    /// What ends the heading of the shelf at `index`: its See all, on tvOS (whose shelves end in a See all card) its
    /// source.
    private func headingEnd(_ index: Int) -> XCUIElement {
        #if os(tvOS)
        element("board.row.\(index).source")
        #else
        element("board.row.\(index).seeAll")
        #endif
    }

    /// The texts that read `title` (the Mac reports a text's words as its value).
    private func shelfHeadings(_ title: String) -> [XCUIElement] {
        app.staticTexts.matching(NSPredicate(format: "label == %@ OR value == %@", title, title)).allElementsBoundByIndex
    }
}
