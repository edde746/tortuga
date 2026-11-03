import XCTest

/// Settings by touch, what iOS alone has: the five tabs with Settings' Addons section pushed over its list, the profile
/// editor's sheet with Cancel in its bar, the list staying where it is while Addons opens over it, Playback's long
/// French titles whole beside or above their controls at the default text size and at XXL, the Streams group's
/// pattern editor (auto-play off at the start; an invalid pattern flagged while typed and on its row, Cancel keeping
/// it, Clear removing it), the profile editor's Kids profile section on a new profile (blocked, ready, on with the
/// highest rating, unrated titles, the starter catalogs and no titles yet; Little, unrated and no catalog picked) at the
/// default text size and the largest, and a launch that can't start saying so in the interface language.
extension SettingsTests {
    func testTabBarHasFiveTabsAndAddonsOpensFromSettings() throws {
        try launchDev()
        if !waitUntil(20, { !tabIds().isEmpty }) { fail("the tab bar shows no tabs") }
        let ids = tabIds()
        // Five tabs, no More; the buttons that carry identifiers (iOS 27 at times leaves some without) carry their
        // destination's, in order.
        let inOrder = zip(ids, Self.phoneTabs).allSatisfy { id, expected in id.isEmpty || id == expected }
        if ids.count != 5 || !inOrder { fail("the tab bar has \(ids), not \(Self.phoneTabs)") }
        let settings = phoneTab("nav.settings")
        ready(settings).tap()
        if !waitUntil(5, { settings.isSelected }) { fail("a tap on Settings doesn't select it") }
        requireTabScreen("nav.settings", "Settings shows")
        shot("settings")
        ready(require("settings.section.addons", "Settings lists Addons")).tap()
        require("addons.install", "Addons opens from Settings")
        if !settings.isSelected { fail("with Addons open, Settings is no longer the selected tab") }
        shot("addons over settings")
        let back = backButton
        if !back.exists { fail("Addons has no back button") }
        ready(back).tap()
        requireGone("addons.install", "the back button closes Addons")
        require("settings.section.addons", "the back button returns to Settings")
        shot("back on settings")
    }

    /// The profile editor over the app is a sheet: its way out is Cancel in the bar's cancellation slot, the only one
    /// (no back button); a new name typed, then Cancel (a tap; on iPad the hardware keyboard's Escape, which presses it
    /// with the name field focused), the editor opens again on the old name. The simulator never hands XCUITest's
    /// Escape key to the app, so the test types Command-period, which iPadOS delivers as Escape.
    func testProfileEditorSheetCancelsInItsBar() throws {
        try launchDev()
        ready(phoneTab("nav.settings")).tap()
        let edit = require("settings.profile.edit", timeout: 30, "Settings offers Edit profile")
        ready(edit).tap()
        let name = requireTypingFocus("editor.name", "Edit profile opens the editor on the name")
        let old = name.value as? String ?? ""
        if old.isEmpty { fail("the editor's name field is empty") }
        let cancel = app.navigationBars.buttons["editor.cancel"]
        if !cancel.exists(within: 5) { fail("the profile editor's sheet has no Cancel in its bar") }
        if element("editor.back").exists { fail("the profile editor's sheet has a back button") }
        let cancels = app.buttons.matching(identifier: "editor.cancel").count
        if cancels != 1 { fail("the profile editor shows Cancel \(cancels) times") }
        name.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old.count) + "Renamed")
        if !waitUntil(5, { name.value as? String == "Renamed" }) { fail("the name field reads \(name.value ?? "nothing"), not Renamed") }
        shot("profile editor, renamed")
        if isPad {
            app.typeKey(".", modifierFlags: .command)
        } else {
            ready(cancel).tap()
        }
        requireGone("editor.name", isPad ? "Escape (Command-period) closes the editor" : "Cancel closes the editor")
        ready(edit).tap()
        let again = require("editor.name", "Edit profile opens the editor again")
        if !waitUntil(5, { again.value as? String == old }) {
            fail("after Cancel the editor's name reads \(again.value ?? "nothing"), not \(old)")
        }
    }

    /// A new profile's Kids profile section (`--dev-open profile`), at the
    /// default text size and the largest accessibility one. With no other profile locked by a PIN (the dev profile has
    /// none) the switch is off, can't be turned on, and the section says why (blocked). With the dev profile locked
    /// (`--dev-profile Parent::2468`) the switch can be turned on (ready); on, the section lists the highest rating's
    /// three choices with Kids chosen and Allow unrated titles off (at the default size a tap chooses Little and allows
    /// unrated titles: `requireKidsRules`), the Home rows with the starter two picked, Family movies and Family series,
    /// the third not, says no title is allowed or blocked yet, and says what the profile takes (two catalogs picked). At
    /// the default size, both unpicked, Save stays on: Home rows are optional (no catalog picked). Nothing is saved. Every
    /// scroll goes over the editor's page (`editorPage`).
    func testKidsProfileSectionIsBlockedThenReadyThenPicksCatalogs() throws {
        let sizes: [(String?, String)] = [(nil, "default text"), ("UICTContentSizeCategoryAccessibilityXXXL", "largest text")]
        for (textSize, size) in sizes {
            try launchDev(open: ["profile"], textSize: textSize)
            let blocked = openKidsSection(size)
            if blocked.isEnabled { fail("\(size): with no other profile locked by a PIN, Kids profile can be turned on") }
            scrollTo(element("editor.kids.blocked"), over: editorPage, "\(size): the section says why the switch is off")
            shot("blocked, \(size)")

            try launchDev(open: ["profile"], textSize: textSize, arguments: ["--dev-profile", "Parent::2468"])
            let ready = openKidsSection(size)
            if !waitUntil(10, { ready.isEnabled }) { fail("\(size): with the dev profile locked by a PIN, Kids profile stays off") }
            if element("editor.kids.blocked").exists { fail("\(size): the section still says why it can't be turned on") }
            shot("ready, \(size)")

            flipSwitch(ready)
            if !waitUntil(5, { switchValue(ready) == "1" }) { fail("\(size): \(Self.tapping) doesn't turn Kids profile on") }
            requireKidsRules(over: editorPage, picks: textSize == nil, size)
            let movies = scrollTo(element("editor.kids.catalog.0"), over: editorPage, "\(size): on, the section lists the catalogs")
            let series = element("editor.kids.catalog.1")
            let picked = [movies, series, element("editor.kids.catalog.2")].map(switchValue)
            if picked != ["1", "1", "0"] { fail("\(size): the first three catalogs' switches read \(picked), not the starter two picked") }
            scrollTo(series, over: editorPage, "\(size): Family series shows")
            shot("two catalogs picked, \(size)")
            scrollTo(element("editor.kids.leaving"), over: editorPage, "\(size): the section says leaving the profile asks for a parent's PIN")
            if textSize == nil {
                shot("what a kids profile takes, \(size)")
                scrollTo(movies, over: editorPage, up: true, "\(size): Family movies shows again")
                flipSwitch(movies)
                flipSwitch(series)
                if !element("editor.save").isEnabled { fail("\(size): with no catalog picked, Save is off") }
                shot("no catalog picked, \(size)")
            }
        }
    }

    /// The new profile's editor named Mia, its keyboard put away by a tap on the page's title (above the field on every
    /// screen: the Avatar heading lies under the keyboard on the iPhone Duo's cover), scrolled down to the Kids profile
    /// switch, which it returns.
    private func openKidsSection(_ size: String) -> XCUIElement {
        let name = require("editor.name", timeout: 30, "\(size): --dev-open profile opens a new profile's editor")
        ready(name).tap()
        name.typeText("Mia")
        ready(app.staticTexts["New profile"].firstMatch).tap()
        if !waitUntil(5, { !app.keyboards.firstMatch.exists }) { fail("\(size): a tap off the name field leaves the keyboard up") }
        return scrollTo(element("editor.kids"), over: editorPage, "\(size): the editor has the Kids profile switch")
    }

    /// The profile editor's scrolling page. On iPad the editor is a sheet in the middle of the window, shorter than it,
    /// and a scroll measured against the window misses it: a row cut off by the sheet's top still lies inside the window,
    /// so the scrolls went on down (the Home rows' first switch, 15 scrolls), and a long drag starts above or below the
    /// sheet, on the screen under it (1 Nov, iPad Pro 13-inch at the largest text size: the sheet gone, Home scrolled).
    private var editorPage: XCUIElement {
        app.scrollViews.containing(.any, identifier: "editor.name").firstMatch
    }

    func testTheListStaysPutWhileASectionOpensAndCloses() throws {
        try launchDev()
        ready(phoneTab("nav.settings")).tap()
        let row = require("settings.section.playback", timeout: 30, "Settings lists Playback")
        require("settings.profile.edit", timeout: 30, "Settings shows the profile")
        shot("settings")
        requireStaysPut(row, label: "Playback", "settings") {
            ready(require("settings.section.addons", "Settings lists Addons")).tap()
            require("addons.install", "Addons opens from Settings")
        }
        shot("settings after addons")
    }

    /// A launch that can't start says so in the interface language, not in its English reason: started in German with
    /// a dev argument that needs another (`--dev-trackers-connected` without `--dev-trackers`), the app shows
    /// "Tortuga konnte nicht gestartet werden." and no text of the arguments.
    func testALaunchThatCantStartSaysSoInTheInterfaceLanguage() throws {
        launch(["--dev", "--dev-trackers-connected"], languages: ["de"])
        let reason = app.staticTexts["Tortuga konnte nicht gestartet werden."]
        let english = app.staticTexts.containing(NSPredicate(format: "label CONTAINS '--dev'")).firstMatch
        if !reason.waitForExistence(timeout: 30) {
            fail("the launch that can't start shows no German reason; it reads '\(english.exists ? english.label : "nothing")'")
        }
        if english.exists { fail("the launch that can't start shows its English reason: '\(english.label)'") }
        shot("launch failed, German")
    }

    func testStreamPatternEditorFlagsAnInvalidPatternAndClears() throws {
        try launchDev()
        openStreamsSettings()
        requireText("settings.playback.streams.autoPlay", contains: "Off", "auto-play starts off")
        let suggest = showOnScreen("settings.playback.streams.suggestions", "the Streams group has Suggest a stream after auto-play")
        if element("settings.playback.streams.autoPlay").frame.minY >= suggest.frame.minY { fail("Suggest a stream isn't under auto-play") }
        if (suggest.value as? String) == "1" { fail("Suggest a stream starts on") }
        shot("streams group")
        let include = "settings.playback.streams.include"
        ready(showOnScreen(include, "the Streams group has Only streams matching")).tap()
        let field = require("settings.streams.pattern.field", "Only streams matching opens its editor")
        ready(field).tap()
        field.typeText("1080p(")
        require("settings.streams.pattern.error", timeout: 5, "an unclosed group says why the filters would ignore it")
        requireText("settings.streams.pattern.error", contains: StreamFilterTexts.invalidPattern, "the editor's line")
        shot("invalid pattern typed")
        ready(require("settings.streams.pattern.save", "the editor has Save")).tap()
        requireGone("settings.streams.pattern.field", "Save closes the editor")
        requireText(include, contains: "1080p(", "the row shows the stored pattern")
        requireText(include, contains: StreamFilterTexts.invalidPattern, "the row says the stored pattern is ignored")
        shot("invalid pattern stored")

        ready(include).tap()
        requireText("settings.streams.pattern.field", contains: "1080p(", "the editor starts from the stored pattern")
        ready(field).tap()
        field.typeText(XCUIKeyboardKey.delete.rawValue)
        requireGone("settings.streams.pattern.error", timeout: 5, "a valid pattern takes the editor's line away")
        ready(require("settings.streams.pattern.cancel", "the editor has Cancel")).tap()
        requireGone("settings.streams.pattern.field", "Cancel closes the editor")
        requireText(include, contains: StreamFilterTexts.invalidPattern, "Cancel keeps the stored pattern")

        ready(include).tap()
        ready(require("settings.streams.pattern.clear", "the editor has Clear")).tap()
        requireGone("settings.streams.pattern.field", "Clear closes the editor")
        requireText(include, contains: "Any stream", "Clear removes the pattern")
        if allText(of: element(include)).contains(StreamFilterTexts.invalidPattern) { fail("cleared, the row still says the pattern is ignored") }
        shot("pattern cleared")
    }

    /// Playback's rows in French, the longest titles beside a stepper, a switch, a menu and a link's value, at the
    /// default text size and at XXL: each title shows whole (its frame as tall as its text needs at its width in the body
    /// font: every line on the screen, none cut off with an ellipsis), beside its control or above it, and the control
    /// (a stepper's step-up button) stays hittable. On a 402 pt iPhone "Compte à rebours de l’épisode suivant" beside "10
    /// secondes" and its stepper, and "Uniquement les sources correspondant à" beside "Toutes les sources", need three
    /// lines. The rows go top to bottom, each scrolled to by its control: XCUITest aims at an element's middle, and where
    /// a stepper holds its title and value as its label, the middle of the stepper, of its title or of its value hits
    /// another element and reads as not hittable.
    func testLongTitlesShowWholeBesideOrAboveTheirControls() throws {
        let steppers = ["watchedThreshold", "upNextCountdown", "seekShort", "seekLong"]
        let rows = ["showSubtitles", "watchedThreshold", "bingeWatching", "upNextCountdown", "seekShort", "seekLong", "skipIntros", "streams.include"]
        for (textSize, name) in [(nil, "default text"), ("UICTContentSizeCategoryXXL", "XXL text")] as [(String?, String)] {
            try launchDev(textSize: textSize, languages: ["fr"])
            openSection("playback")
            for row in rows {
                let id = "settings.playback.\(row)"
                let control = steppers.contains(row) ? stepperButton(id, up: true) : element(id)
                _ = control.exists(within: 5)
                scrollTo(control, over: frontmostList, "\(name): Playback lists \(id)")
                requireTitleWhole(of: id, textSize: textSize, "\(name): \(id)")
                if !control.isHittable { fail("\(name): \(id)'s control isn't hittable at \(control.frame)") }
            }
            shot("french playback, \(name)")
        }
    }

    /// Fails with `what` unless the title of the form row holding `id` shows whole: the row's first text (the top one,
    /// leading; not the stepper's value, `<id>.value`) as tall as its text needs at its width, in the body font at the
    /// content size category `textSize` (nil: the system's).
    private func requireTitleWhole(of id: String, textSize: String?, _ what: String) {
        guard let row = try? app.cells.containing(.any, identifier: id).firstMatch.snapshot() else {
            return fail("\(what): no form row holds \(id)")
        }
        var texts: [any XCUIElementSnapshot] = []
        func collect(_ node: any XCUIElementSnapshot) {
            if node.elementType == .staticText, node.identifier != "\(id).value" { texts.append(node) }
            node.children.forEach(collect)
        }
        collect(row)
        guard let title = texts.min(by: { ($0.frame.minY, $0.frame.minX) < ($1.frame.minY, $1.frame.minX) }) else {
            return fail("\(what): its row shows no text")
        }
        let category = textSize.map(UIContentSizeCategory.init(rawValue:)) ?? UITraitCollection.current.preferredContentSizeCategory
        let font = UIFont.preferredFont(forTextStyle: .body, compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
        // A point of slack: the text's frame is its widest line, which the measure must not wrap again.
        let needed = (title.label as NSString).boundingRect(
            with: CGSize(width: title.frame.width + 2, height: .greatestFiniteMagnitude), options: .usesLineFragmentOrigin,
            attributes: [.font: font], context: nil
        ).height
        let lines = { (height: CGFloat) in Int((height / font.lineHeight).rounded()) }
        if lines(title.frame.height) < lines(needed) {
            fail("\(what): its title \"\(title.label)\" shows \(lines(title.frame.height)) of the \(lines(needed)) lines it takes at \(Int(title.frame.width)) points (\(title.frame))")
        }
    }
}
