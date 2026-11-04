import XCTest

/// Settings and Addons by touch (iOS) and with the pointer and keyboard (the Mac): the sections stay where they first
/// show as the profile loads above them (sampled from a thread of its own); a kids profile's card without Edit
/// profile; a device switch that outlasts a relaunch, a
/// playback stepper that stops at its maximum (on the Mac beside its value on the row's centre line), Appearance
/// offering Blur unwatched episodes and the poster URL template (a kids profile the switch alone), the subtitle
/// size stepped up beside its preview, the test addon installed from its link (preview, then install; on iOS a tap on
/// the install page off the field puts the keyboard away), its ⋯ menu (Move up and Remove, no Move down for the last
/// addon), moved up by it, dragged by its handle (on iOS a touch held on it, down and back up), the order kept when
/// Addons opens again, its link shown and hidden, and removed once confirmed from its ⋯ menu (iOS) or its right-click
/// menu (the Mac); a `stremio://…/manifest.json` link opened from outside the app (the system's, to the running app)
/// opens Settings on Addons, on that addon's install page, its fetched manifest named; Cancel leaves Addons open (on iOS
/// with Settings the selected tab) and the back button returns to Settings' list; the same link opened over Cinemeta's
/// details shows its install page over them, and Cancel leaves the details; one that can't be fetched, opened while the
/// install page holds a typed link, brings the page back to its link with the failure under it and the field empty (the
/// store's link, which the followed one replaced); an addon switched off by its row's switch leaving Home with its
/// catalog's shelf, which comes back once it is switched on again; an addon that asks for the user's watch activity
/// installing only once its install page's agreement is switched on (Install disabled until then), its details then
/// showing Send watch activity on; Home's rows in Settings → Home: each with
/// its drag handle (on the Mac its ⋯ too), one hidden by its switch, another dragged by its handle to the top and back,
/// then moved to the top by its ⋯ menu's Move up (the first row's menu without it; on iOS its Move down and a drag back
/// to the top, on the Mac a right click opening the same menu), which Home follows, still after a relaunch, until Reset
/// to default; preferred languages moved and removed from their ⋯ menus (on the Mac removed from the right-click menu)
/// and dragged, kept when the editor opens again. Export and import through the system's own save and open sheets
/// (iOS, On My iPhone) or the app's own save and open panels (the Mac): Addons alone exported with the warning that the
/// file holds addon links, imported in another session (another Developer profile, without the test addon) into its
/// Developer, which then has the test addon; a file that isn't an export refused; on the Mac the import's review ends
/// right under its form (`sheetProblems`) and the page is shot with the sidebar hidden and in a narrowed window, on the
/// iPad in landscape.
/// What one platform alone has: `SettingsTests+iOS` (the tab bar, the profile editor's back button, the list staying
/// put while a section opens and closes, Playback's long French titles whole beside or above their controls, the
/// Streams group's pattern editor) and `SettingsTests+macOS` (the profile
/// editor's sheet, rows' centre lines, Trackers' last switch, the languages' sheet, the settings sheets ending under
/// their forms, a language row's drag onto a text field).
final class SettingsTests: TortugaUITestCase {
    func testSectionsStayPutAsTheProfileLoads() throws {
        try launchDev()
        let settings = navItem("nav.settings", "the sidebar lists Settings")
        requireSectionsStayPut("Settings opened by a tap on its tab (a click in the Mac's sidebar)") { activate(settings) }
    }

    /// On a kids profile (`--dev-kids`) the profile card has no Edit profile: the profile isn't edited
    /// from itself, and the store opens no editor there. A dev session has no account, so the card has no Switch or
    /// manage profiles either: once the card shows the profile's name it has no button at all, and keeps none.
    func testKidsProfileCardHasNoEdit() throws {
        try launchDev(arguments: ["--dev-kids", "2468"])
        activate(navItem("nav.settings", "the sidebar lists Settings"))
        require("settings.section.playback", timeout: 30, "Settings lists its sections")
        let name = app.staticTexts.matching(NSPredicate(format: "label == 'Developer' OR value == 'Developer'")).firstMatch
        if !name.exists(within: 30) { fail("Settings' profile card doesn't name the kids profile, Developer") }
        if !holds(for: 3, { !element("settings.profile.edit").exists }) {
            fail("the kids profile's card offers Edit profile, which opens nothing there")
        }
        shot("kids profile card")
    }

    func testDeviceSettingOutlastsARelaunch() throws {
        let data = freshDataName()
        launchDev(keepingData: data)
        openSection("device")
        let toggle = require("settings.device.hardwareDecoding", "This device lists hardware decoding")
        let before = switchValue(toggle)
        flipSwitch(toggle)
        if !waitUntil(5, { switchValue(toggle) != before }) { fail("a tap or click doesn't switch hardware decoding") }
        shot("hardware decoding switched")
        app.terminate()
        launchDev(keepingData: data)
        openSection("device")
        let again = require("settings.device.hardwareDecoding", "after a relaunch, This device lists hardware decoding")
        if !waitUntil(5, { switchValue(again) != before }) { fail("after a relaunch hardware decoding is back as it was") }
        shot("after relaunch")
    }

    /// While loudness normalization is on (the player's choice, kept for the next video; here set at the launch), This
    /// device's Passthrough switch is disabled and says passthrough is off meanwhile, in place of its hint; once
    /// normalization is off (the dev-setting link) the switch is enabled again and the hint is back.
    func testPassthroughIsDisabledWhileLoudnessIsNormalized() throws {
        try launchDev(settings: ["audio.normalization=true"])
        openSection("device")
        requirePassthroughSwitch(normalized: true, "with loudness normalization on")
        shot("passthrough while normalized")
        changeDevSetting("audio.normalization=false")
        requirePassthroughSwitch(normalized: false, "once loudness normalization is off")
        shot("passthrough")
    }

    func testPlaybackStepperStaysWithinItsBounds() throws {
        try launchDev()
        openSection("playback")
        let id = "settings.playback.watchedThreshold"
        #if os(macOS)
        let stepper = require(id, "Playback lists the watched threshold").frame
        let value = require("\(id).value", "the watched threshold shows its value").frame
        if abs(stepper.midY - value.midY) > 2 || stepper.minX - value.maxX > 16 {
            fail("the watched threshold's value (\(value)) and stepper (\(stepper)) aren't side by side on one line")
        }
        #endif
        requireStepperBounds(id, maximum: "100", below: "95") { activate($0) }
    }

    func testSubtitleSizeStepsAndThePreviewShows() throws {
        try launchDev()
        openSection("subtitles")
        require("settings.subtitles.preview", "Subtitles shows its preview")
        shot("subtitles")
        let size = "settings.subtitles.size"
        require(size, "Subtitles lists the size")
        for _ in 0..<3 { activate(stepperButton(size, up: true)) }
        if !waitUntil(5, { stepperText(size).contains("130%") }) { fail("three steps up from 100% show \"\(stepperText(size))\", not 130%") }
        shot("size 130%")
    }

    /// Appearance lists the interface language, Blur unwatched episodes, Hide watched titles, Hide titles under posters
    /// and the poster URL template, in that order.
    func testAppearanceListsItsRowsInOrder() throws {
        try launchDev()
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

    /// Appearance's rows `ids` (`settings.appearance.<id>`), each one under the one before.
    private func requireRowsInOrder(_ ids: [String], _ whose: String) {
        let rows = ids.map { (id: $0, frame: require("settings.appearance.\($0)", timeout: 30, "\(whose)Appearance lists \($0)").frame) }
        for (above, below) in zip(rows, rows.dropFirst()) where below.frame.minY <= above.frame.minY {
            fail("\(whose)Appearance's \(below.id) isn't under \(above.id)")
        }
    }

    func testAddonInstallsMovesShowsItsLinkAndIsRemoved() throws {
        let url = try addonURL
        try launchDev()
        openSection("addons")
        require("addons.install", "Settings' Addons row opens Addons")
        let before = addonRowCount
        activate(element("addons.install"))
        #if os(macOS)
        let link = require("addons.install.link", "Install asks for the addon's link")
        shot("install link")
        ready(link).click()
        #else
        let link = requireTypingFocus("addons.install.link", "Install asks for the addon's link")
        #endif
        link.typeText(url)
        shot("link typed")
        #if os(iOS)
        if !app.keyboards.firstMatch.exists { fail("the software keyboard isn't up for the addon's link") }
        ready(link).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).withOffset(CGVector(dx: 0, dy: 120)).tap()
        if !waitUntil(5, { !app.keyboards.firstMatch.exists }) {
            fail("a tap on the install page off its field leaves the keyboard up")
        }
        #endif
        activate(require("addons.install.submit", "Install has Continue"))
        activate(require("addons.install.confirm", timeout: 30, "the fetched manifest offers Install"))
        shot("preview confirmed")
        activate(require("addons.install.done", timeout: 30, "the addon installs"))
        requireGone("addons.install.done", "Done closes the install page")
        if !waitUntil(10, { addonRowCount == before + 1 }) { fail("\(addonRowCount) addons listed after the install, not \(before + 1)") }
        let last = before
        let name = require("addons.row.\(last)", timeout: 5, "Addons lists the new addon last").label
        for index in 0...last {
            require("addons.row.\(index).handle", "addon \(index) shows its drag handle")
            #if os(macOS)
            require("addons.row.\(index).more", "addon \(index) shows ⋯")
            #endif
        }
        shot("installed")

        openMenu("addons.row.\(last)", "the new addon")
        require("addons.row.\(last).remove", timeout: 5, "⋯ on the new addon offers Remove")
        if element("addons.row.\(last).down").exists { fail("the last addon's menu offers Move down") }
        let up = require("addons.row.\(last).up", timeout: 5, "⋯ on the new addon offers Move up")
        shot("menu", of: menuShotSource)
        pickMenuEntry(up)
        requireAddonRow(last - 1, labelled: name, "the menu's Move up moves the addon up one place")
        #if os(macOS)
        shot("moved up")
        #endif

        dragRow("addons.row.\(last - 1)", onto: "addons.row.\(last)", below: true, shot: "dragging")
        requireAddonRow(last, labelled: name, "dragged by its handle onto the last addon's lower half, the addon is last again")
        #if os(macOS)
        shot("dragged to the bottom")
        goBack()
        if !element("settings.section.addons").exists(within: 3) {
            // On the Mac (two runs) the first click on Back after the drop left Addons up.
            XCTContext.runActivity(named: "Back again: the first click after the drop was lost") { _ in goBack() }
        }
        #else
        shot("dragged down")
        goBack()
        #endif
        activate(require("settings.section.addons", "the back button returns to Settings"))
        requireAddonRow(last, labelled: name, "Addons opened again lists the dragged addon where it was dropped")
        #if os(macOS)
        let row = "addons.row.\(last)"
        #else
        dragRow("addons.row.\(last)", onto: "addons.row.\(last - 1)", below: false)
        requireAddonRow(last - 1, labelled: name, "dragged by its handle onto the addon above's upper half, the addon goes before it")
        let row = "addons.row.\(last - 1)"
        #endif

        activate(element(row))
        #if os(macOS)
        ready(require("addons.details.reveal", "the details offer Show link")).click()
        #else
        require("addons.details.close", "a tap on the addon opens its details")
        ready(swipeUntilShown("addons.details.reveal", "the details offer Show link")).tap()
        #endif
        let shown = require("addons.details.link", timeout: 5, "Show link shows the link")
        #if os(macOS)
        let shownURL = shown.label == url || (shown.value as? String) == url
        #else
        let shownURL = shown.label == url
        #endif
        if !shownURL { fail("the details show \"\(shown.label)\" as the link, not the addon's") }
        shot("link shown")
        activate(require("addons.details.hide", "a shown link has Hide"))
        requireGone("addons.details.link", timeout: 5, "Hide hides the link")
        activate(require("addons.details.close", "the details have Close"))
        requireGone("addons.details.close", "Close closes the details")

        #if os(macOS)
        ready(row).rightClick()
        let remove = require("\(row).remove", timeout: 5, "a right click on the addon offers Remove")
        shot("right-click menu", of: XCUIScreen.main)
        pickMenuEntry(remove)
        #else
        openMenu(row, "the addon")
        pickMenuEntry(require("\(row).remove", timeout: 5, "⋯ on the addon offers Remove"))
        #endif
        activate(require("addons.remove.confirm", timeout: 5, "Remove asks first"))
        requireNoAddonRow(labelled: name, "confirming removes the addon")
        if addonRowCount != before { fail("\(addonRowCount) addons listed after the removal, not \(before)") }
        shot("removed")
    }

    /// A component in About opens its page in the app, titled with its name: its licence, the link to its project's
    /// page and its licence texts, which the app carries. Back returns to About's list. mpv-build's page holds the texts
    /// of every component of MPVKit, each under its folder (FFmpeg's first).
    func testAboutNoticeOpensItsLicenceTexts() throws {
        try launchDev()
        openSection("about")
        activate(scrollTo("settings.about.notice.9", "About lists Plezy"))
        require("settings.notice.link", timeout: 10, "Plezy's page shows its project page's link")
        require("settings.notice.text", timeout: 10, "Plezy's page shows its licence text")
        if !app.staticTexts["Plezy"].exists { fail("Plezy's page isn't titled Plezy") }
        shot("notice page")
        goBack()
        require("settings.about.notice.9", timeout: 10, "Back returns to About's list")
        if !holds(for: 2, { !element("settings.notice.link").exists }) { fail("Back leaves Plezy's page showing") }
        activate(scrollTo("settings.about.notice.8", "About lists mpv-build"))
        requireText("settings.notice.text", contains: "ffmpeg/", timeout: 10, "mpv-build's page starts with FFmpeg's licence text")
        if !app.staticTexts["mpv-build"].exists { fail("mpv-build's page isn't titled mpv-build") }
        shot("mpv-build's page")
    }

    func testStremioInstallLinkOpensTheAddonsInstallPage() throws {
        try launchDev()
        _ = navItem("nav.settings", "the sidebar lists Settings")
        openFromOutside(Self.installLink)
        requireInstallPreview(of: Self.installLinkAddon, "a stremio:// install link opened from outside the app while it runs")
        #if os(macOS)
        shot("install link while running")
        #else
        shot("install link")
        #endif
        activate(require("addons.install.cancel", "the install page has Cancel"))
        requireGone("addons.install.cancel", "Cancel closes the install page")
        require("addons.install", "the link opened Addons, which stays once its install page closes")
        #if os(iOS)
        if !phoneTab("nav.settings").isSelected { fail("with Addons open from the link, Settings isn't the selected tab") }
        shot("addons after the link")
        #endif
        goBack()
        requireGone("addons.install", "the back button closes Addons")
        require("settings.section.addons", "the back button returns to Settings' list")
    }

    /// An install link opened while an addon's details show: its install page comes up over the details, and Cancel
    /// leaves the details as they were.
    func testInstallLinkOverAnAddonsDetailsShowsItsInstallPage() throws {
        try launchDev()
        openSection("addons")
        activate(require("addons.row.0", timeout: 30, "Addons lists Cinemeta"))
        require("addons.details.close", "a tap or click on Cinemeta opens its details")
        shot("cinemeta's details")
        openFromOutside(Self.installLink)
        requireInstallPreview(of: Self.installLinkAddon, "a stremio:// install link opened over an addon's details")
        shot("install page over the details")
        if !element("addons.details.close").exists { fail("the install page took the place of Cinemeta's details: they don't show under it") }
        activate(require("addons.install.cancel", "the install page has Cancel"))
        requireGone("addons.install.cancel", "Cancel closes the install page")
        require("addons.details.close", "the details stay once the install page over them closes")
        shot("details after the install page")
    }

    /// An install link from outside the app that can't be fetched, opened while the install page holds a typed link:
    /// the page comes back to its link with the failure under it and the field empty, as the store's link is. A field
    /// keeping the typed link showed one that Continue couldn't send, and wiping it by sending its text to the store
    /// would wipe the failure too.
    func testFailedInstallLinkFromOutsideEmptiesTheFieldAndKeepsItsError() throws {
        // A link typed and not sent, then one that nothing answers (a `stremio://` link is fetched over HTTPS, and
        // nothing listens on port 9), refused at once: "Couldn't reach the addon."
        let typed = "https://typed.invalid/manifest.json"
        let unreachable = URL(string: "stremio://127.0.0.1:9/manifest.json")!
        let unreachableMessage = "Couldn't reach the addon."
        try launchDev()
        openSection("addons")
        activate(require("addons.install", "Settings' Addons row opens Addons"))
        #if os(macOS)
        let link = require("addons.install.link", "Install asks for the addon's link")
        ready(link).click()
        #else
        let link = requireTypingFocus("addons.install.link", "Install asks for the addon's link")
        #endif
        link.typeText(typed)
        let submit = require("addons.install.submit", "Install has Continue")
        if !waitUntil(5, { submit.isEnabled }) { fail("Continue stays disabled with a link typed") }
        shot("link typed")

        openFromOutside(unreachable)
        let failure = app.staticTexts.matching(NSPredicate(format: "label == %@ OR value == %@", unreachableMessage, unreachableMessage)).firstMatch
        if !failure.exists(within: 30) { return fail("the install page doesn't say \"\(unreachableMessage)\" for an install link it can't fetch") }
        let field = require("addons.install.link", timeout: 5, "the failed link brings the install page back to its link")
        if !waitUntil(5, { !fullText(of: field).contains(typed) }) {
            fail("back at the link after the followed one failed, the field still reads \"\(fullText(of: field))\", a link the page no longer has")
        }
        if !failure.exists { fail("the failure's message went as the field emptied") }
        shot("followed link failed")
    }

    /// An addon switched off by its row's switch in Addons leaves Home with its catalog's shelf, and switched on again
    /// brings it back: the watch-activity addon alone, in a session without Cinemeta, so Home's one shelf is its
    /// catalog's.
    func testAddonSwitchedOffLeavesHomeAndSwitchedOnComesBack() throws {
        try launchWithActivityAddonAlone()
        requireActivityShelf(shown: true, "Home with the addon on")
        shot("home with the addon on")
        openSection("addons")
        let toggle = require("addons.row.0.enabled", timeout: 30, "Addons lists the addon with its switch")
        if !switchIsOn(toggle) { fail("the addon's switch starts off") }
        flipSwitch(toggle)
        if !waitUntil(5, { !switchIsOn(toggle) }) { fail("a tap or click on the addon's switch doesn't turn it off") }
        shot("addon off")
        activate(navItem("nav.board", "the sidebar lists Home"))
        requireActivityShelf(shown: false, "Home with the addon off")
        shot("home with the addon off")
        openSection("addons")
        let again = require("addons.row.0.enabled", timeout: 10, "Addons still lists the addon")
        if switchIsOn(again) { fail("the addon's switch reads on after a trip to Home") }
        flipSwitch(again)
        if !waitUntil(5, { switchIsOn(again) }) { fail("a tap or click on the addon's switch doesn't turn it on") }
        activate(navItem("nav.board", "the sidebar lists Home"))
        requireActivityShelf(shown: true, "Home with the addon on again")
        shot("home with the addon on again")
    }

    /// An addon whose manifest asks for the user's watch activity installs only once they agree: its install page's
    /// agreement starts off with Install disabled, and switched on enables Install; installed, the addon's row has its
    /// switch on and its details Send watch activity on.
    func testWatchActivityAddonInstallsOnlyOnceAgreed() throws {
        let url = try activityAddonURL
        launch(["--dev", "--dev-open", "addons"] + Self.harnessSettings.flatMap { ["--dev-setting", $0] })
        require("addons.row.0", timeout: 30, "Addons lists the dev profile's addons")
        let before = addonRowCount
        activate(require("addons.install", "Addons offers Install"))
        #if os(macOS)
        let link = require("addons.install.link", "Install asks for the addon's link")
        ready(link).click()
        #else
        let link = requireTypingFocus("addons.install.link", "Install asks for the addon's link")
        #endif
        link.typeText(url)
        activate(require("addons.install.submit", "Install has Continue"))
        require("addons.install.confirm", timeout: 30, "the fetched manifest offers Install")
        // The button itself: the iPad's toolbar wraps it in an element of the same identifier that is always enabled.
        let confirm = app.buttons.matching(identifier: "addons.install.confirm").firstMatch
        let consent = showOnScreen("addons.install.watchActivity", "the install page asks to agree to send the addon watch activity")
        if switchIsOn(consent) { fail("the agreement starts on") }
        if confirm.isEnabled { fail("Install is enabled before the agreement is switched on") }
        shot("install waiting for the agreement")
        flipSwitch(consent)
        if !waitUntil(5, { confirm.isEnabled }) { fail("switching the agreement on doesn't enable Install") }
        shot("install agreed")
        activate(confirm)
        activate(require("addons.install.done", timeout: 30, "the addon installs"))
        requireGone("addons.install.done", "Done closes the install page")
        if !waitUntil(10, { addonRowCount == before + 1 }) { fail("\(addonRowCount) addons listed after the install, not \(before + 1)") }
        let row = "addons.row.\(before)"
        if !switchIsOn(require("\(row).enabled", "the new addon's row has its switch")) { fail("the new addon's switch is off") }
        shot("installed")
        activate(element(row))
        require("addons.details.close", "a tap or click on the addon opens its details")
        if !switchIsOn(showOnScreen("addons.details.enabled", "the details show the addon's switch")) { fail("the details' switch is off") }
        let allowed = showOnScreen("addons.details.watchActivity", "the details show Send watch activity")
        if !switchIsOn(allowed) { fail("Send watch activity is off after installing with the agreement") }
        shot("details with watch activity allowed")
    }

    func testHomeRowsHiddenAndMovedShowOnHomeOutlastARelaunchAndReset() throws {
        // Two launches, each waiting for the Board's shelves from live Cinemeta: longer than the run's default 5 minutes.
        executionTimeAllowance = 600
        let url = try addonURL
        let data = freshDataName()
        launchDev(keepingData: data, addon: url)
        #if os(macOS)
        require("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        #endif
        openSection("home")
        let rows = requireHomeRows("Settings → Home lists Home's rows")
        let titles = (0..<rows).map(homeRowTitle)
        let far = farHomeRow(of: rows, "Settings → Home")
        let moved = titles[far]
        for index in 0..<rows {
            require("settings.home.row.\(index).handle", "row \(index) shows its drag handle")
            #if os(macOS)
            require("settings.home.row.\(index).more", "row \(index) shows ⋯")
            #endif
        }
        shot("home rows")

        requireHomeRow(0, shown: true, "\(titles[0]) starts shown")
        flipSwitch(element("settings.home.row.0"))
        requireHomeRow(0, shown: false, "a tap or click on the row's switch hides \(titles[0])")
        #if os(macOS)
        let held = "home row held over the first row"
        #else
        shot("home row hidden")
        let held = "home row dragging"
        #endif

        dragRow("settings.home.row.\(far)", onto: "settings.home.row.0", below: false, shot: held)
        requireHomeRow(0, titled: moved, "row \(far) dragged by its handle onto the first row's upper half puts \(moved) first")
        requireHomeRow(1, titled: titles[0], "\(titles[0]) follows the dragged row")
        shot("home row dragged to the top")
        dragRow("settings.home.row.0", onto: "settings.home.row.\(far)", below: true)
        requireHomeRow(far, titled: moved, "dragged onto row \(far)'s lower half, \(moved) is back in its place")

        for index in stride(from: far, to: 0, by: -1) {
            openMenu("settings.home.row.\(index)", moved)
            pickMenuEntry(require("settings.home.row.\(index).up", timeout: 5, "⋯ on \(moved) offers Move up"))
            requireHomeRow(index - 1, titled: moved, "Move up moves \(moved) up one place")
        }
        openMenu("settings.home.row.0", "the first row")
        require("settings.home.row.0.down", timeout: 5, "⋯ on the first row offers Move down")
        if element("settings.home.row.0.up").exists { fail("the first row's menu offers Move up") }
        shot("first row's menu", of: menuShotSource)
        #if os(macOS)
        app.typeKey(.escape, modifierFlags: [])
        requireGone("settings.home.row.0.down", "Escape closes the menu")
        ready("settings.home.row.1").rightClick()
        require("settings.home.row.1.up", timeout: 5, "a right click on a row opens the same menu")
        shot("right-click menu", of: XCUIScreen.main)
        app.typeKey(.escape, modifierFlags: [])
        requireGone("settings.home.row.1.up", "Escape closes the right-click menu")
        #else
        pickMenuEntry(element("settings.home.row.0.down"))
        requireHomeRow(1, titled: moved, "Move down moves \(moved) down one place")
        dragRow("settings.home.row.1", onto: "settings.home.row.0", below: false)
        requireHomeRow(0, titled: moved, "dragged by its handle onto the first row's upper half, \(moved) is first again")
        #endif
        requireHomeRow(1, titled: titles[0], "\(titles[0]) follows the moved row")
        requireHomeRow(1, shown: false, "\(titles[0]) stays hidden after the moves")
        shot("home rows hidden and moved")

        activate(navItem("nav.board", "the sidebar lists Home"))
        requireBoardShelves([moved, titles[1]], without: titles[0], "Home shows \(moved) first and not \(titles[0])")
        shot("home after the change")

        app.terminate()
        launchDev(keepingData: data, addon: url)
        requireBoardShelves([moved, titles[1]], without: titles[0], "after a relaunch Home shows \(moved) first and not \(titles[0])")
        shot("home after relaunch")
        openSection("home")
        requireHomeRow(0, titled: moved, "after a relaunch, Settings → Home lists \(moved) first")
        requireHomeRow(1, titled: titles[0], "after a relaunch, \(titles[0]) follows it")
        requireHomeRow(1, shown: false, "after a relaunch \(titles[0]) stays hidden")
        shot("home rows after relaunch")

        // Reset sits under the rows: its state is read before the list goes back up to its first rows.
        let reset = showOnScreen("settings.home.reset", "Settings → Home offers Reset to default")
        activate(reset)
        if !waitUntil(10, { !reset.isEnabled }) { fail("Reset to default stays enabled with Home's default rows") }
        scrollTo(element("settings.home.row.0"), up: true, "after Reset to default, the list shows its first row again")
        requireHomeRow(0, titled: titles[0], "Reset to default puts \(titles[0]) back first")
        requireHomeRow(0, shown: true, "Reset to default shows \(titles[0]) again")
        requireHomeRow(far, titled: moved, "Reset to default puts \(moved) back in its place")
        shot("home rows reset")
    }

    func testLanguagesDraggedMovedAndRemovedFromTheirRows() throws {
        try launchDev()
        openSection("playback")
        activate(require("settings.playback.audioLanguages", "Playback lists Audio languages"))
        let search = require("settings.languages.search", "Audio languages opens its editor")
        let before = chosenLanguageCount
        #if os(iOS)
        var typed = ""
        #endif
        for name in ["German", "French", "Spanish"] {
            activate(search)
            #if os(macOS)
            search.typeKey("a", modifierFlags: .command)
            search.typeText(name)
            #else
            search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: typed.count) + name)
            typed = name
            #endif
            // Scrolled above the keyboard first: on the iPhone Duo's cover the first result lies under it.
            let result = require("settings.languages.available.0", timeout: 10, "searching \(name) lists it")
            activate(scrollTo(result, "searching \(name) lists it above the keyboard"))
        }
        #if os(iOS)
        // Return puts the keyboard away (where the tap on the language left it up), uncovering the rows.
        if app.keyboards.count > 0 { search.typeText("\n") }
        if !waitUntil(5, { app.keyboards.count == 0 }) { fail("Return leaves the keyboard up") }
        #endif
        if !waitUntil(5, { chosenLanguageCount == before + 3 }) { fail("\(chosenLanguageCount) languages chosen, not \(before + 3)") }
        let last = before + 2
        for index in 0...last {
            require("settings.languages.chosen.\(index).handle", "language \(index) shows its drag handle")
            #if os(macOS)
            require("settings.languages.chosen.\(index).more", "language \(index) shows ⋯")
            #endif
        }
        requireChosenLanguages(["German", "French", "Spanish"], from: before, "the added languages come last, in order")
        shot("languages chosen")

        openMenu("settings.languages.chosen.\(last)", "Spanish")
        require("settings.languages.chosen.\(last).remove", timeout: 5, "⋯ on Spanish offers Remove")
        if element("settings.languages.chosen.\(last).down").exists { fail("the last language's menu offers Move down") }
        let up = require("settings.languages.chosen.\(last).up", timeout: 5, "⋯ on Spanish offers Move up")
        shot("language's menu", of: menuShotSource)
        pickMenuEntry(up)
        requireChosenLanguages(["German", "Spanish", "French"], from: before, "Move up moves Spanish up one place")
        #if os(macOS)
        ready("settings.languages.chosen.\(before + 1)").rightClick()
        pickMenuEntry(require("settings.languages.chosen.\(before + 1).remove", timeout: 5, "a right click on Spanish offers Remove"))
        #else
        openMenu("settings.languages.chosen.\(before + 1)", "Spanish")
        pickMenuEntry(require("settings.languages.chosen.\(before + 1).remove", timeout: 5, "⋯ on Spanish offers Remove"))
        #endif
        if !waitUntil(5, { chosenLanguageCount == before + 2 }) { fail("\(chosenLanguageCount) languages chosen after Remove, not \(before + 2)") }
        requireChosenLanguages(["German", "French"], from: before, "Remove takes Spanish out")
        dragRow("settings.languages.chosen.\(before + 1)", onto: "settings.languages.chosen.\(before)", below: false, shot: "language dragging")
        requireChosenLanguages(["French", "German"], from: before, "French dragged by its handle onto German's upper half goes before it")
        shot("language moved, removed and dragged")

        closeSheet("settings.languages.close", showing: "settings.languages.search", "Close (an iPhone: a pull down) closes the editor")
        activate(require("settings.playback.audioLanguages", "Playback lists Audio languages"))
        require("settings.languages.search", "Audio languages opens its editor again")
        requireChosenLanguages(["French", "German"], from: before, "the editor opens again with the stored order")
        shot("languages reopened")
    }

    func testExportASubsetAndImportItIntoAnotherProfile() throws {
        // Two launches and three file sheets (the Mac's file panels): longer than the run's default 5 minutes.
        executionTimeAllowance = 600
        let url = try addonURL
        #if os(macOS)
        // The panels' folder: the run's files folder, outside the sandboxed runner's container, with
        // the file that isn't an export, not-tortuga.json, which the run writes there since the runner can't.
        let folder = try AccountScenario.variable("TORTUGA_FILES")
        #endif
        launchDev(keepingData: freshDataName(), addon: url)
        activate(navItem("nav.settings", "the sidebar lists Settings"))
        let exported = installedAddons("with the test addon, Settings counts the addons")
        openDataSection()
        shot("export and import")
        for category in ["library", "progress", "settings", "streams"] {
            #if os(macOS)
            let toggle = scrollTo("settings.data.export.\(category)", "Export lists \(category)")
            #else
            let toggle = require("settings.data.export.\(category)", "Export lists \(category)")
            #endif
            flipSwitch(toggle)
            if !waitUntil(5, { switchValue(toggle) == "0" }) { fail("a tap or click doesn't untick \(category)") }
        }
        require("settings.data.export.private", "with Addons ticked, Export warns that the file holds addon links")
        shot("export choices")

        activate(showOnScreen("settings.data.export", "the section has Export"))
        #if os(macOS)
        let save = filePanelButton("Export opens the save panel")
        goTo(folder)
        shot("save panel")
        ready(save).click()
        #else
        // The sheet's Save button by its label: iOS 26.5 gives it no identifier (27.0: `DOCPicker.actionButton`).
        let save = app.buttons.matching(NSPredicate(format: "label == 'Save'")).firstMatch
        if !save.exists(within: 20) { fail("Export opens the system's save sheet: no Save button") }
        showLocalFiles("the save sheet")
        shot("save sheet")
        ready(save).tap()
        #endif
        requireDataNotice("Saved tortuga-", "the section says the file is saved")
        #if os(macOS)
        let saved = allText(of: element("settings.data.notice"))
        guard let name = saved.firstMatch(of: #/tortuga-[0-9-]+\.json/#).map({ String($0.output) }) else {
            return fail("the notice \"\(saved)\" names no tortuga-….json")
        }
        #else
        let storage = try localFileStorage()
        let saved = try FileManager.default.contentsOfDirectory(atPath: storage.path(percentEncoded: false))
        if !saved.contains(where: { $0.hasPrefix("tortuga-") && $0.hasSuffix(".json") }) {
            fail("On My iPhone holds \(saved), no tortuga-….json")
        }
        try Data(#"{"name": "not a Tortuga export"}"#.utf8).write(to: storage.appending(path: "not-tortuga.json"))
        #endif

        // Another session: its own Developer profile (another id, so the file's profile isn't one of its own), without
        // the test addon.
        app.terminate()
        launchDev(keepingData: freshDataName())
        activate(navItem("nav.settings", "the sidebar lists Settings"))
        let before = installedAddons("without the test addon, Settings counts the addons")
        if before == exported { fail("a session without the test addon counts as many addons (\(before)) as the exporting one") }
        openDataSection()
        activate(showOnScreen("settings.data.import", "the section has Import"))
        #if os(macOS)
        let open = filePanelButton("Import opens the open panel")
        goTo("\(folder)/\(name)")
        ready(open).click()
        #else
        showLocalFiles("the open sheet")
        ready(fileInSheet("tortuga-", "the open sheet lists the export")).tap()
        #endif
        require("settings.data.review.confirm", timeout: 20, "the opened export shows its review")
        require("settings.data.review.addons", "the review lists Addons")
        for category in ["library", "progress", "settings", "streams"] where element("settings.data.review.\(category)").exists {
            fail("the review lists \(category); the export holds Addons alone")
        }
        let targetId = "settings.data.review.profile.0.target"
        let target = require(targetId, "the file's profile, not one of this session's, offers where it goes")
        requireText(targetId, contains: "A new profile", "the file's profile goes to a new profile until another is picked")
        #if os(macOS)
        ready(target).click()
        #else
        ready(target.staticTexts.allElementsBoundByIndex.last ?? target).tap()
        #endif
        let developer = menuEntry("Developer", "the target's menu offers this session's profile")
        menuEntry("A new profile", "the target's menu offers a new profile")
        shot("import review")
        pickMenuEntry(developer)
        requireText(targetId, contains: "Developer", "the target shows Developer once picked")
        #if os(macOS)
        for problem in sheetProblems("the import review", buttons: "settings.data.review.confirm") { fail(problem) }
        #endif
        activate(require("settings.data.review.confirm", "the review has Import"))
        requireGone("settings.data.review.confirm", timeout: 20, "Import closes the review")
        requireDataNotice("Import finished:", "the section says the import finished")
        shot("import finished")

        goBack()
        requireText("settings.section.addons", contains: exported, timeout: 20, "after the import Developer has the exported addons")
        shot("addons after the import")

        openDataSection()
        // The import's notice, below Export, goes by its dismiss button, a bare symbol that takes taps and clicks in the
        // least hit target; the next one shows in its place above Import.
        let dismiss = showOnScreen("notice.dismiss", "the import's notice has a dismiss button")
        requireHitTarget(dismiss, "the import notice's dismiss button")
        activate(dismiss)
        requireGone("settings.data.notice", "a tap or click on its dismiss button takes the notice away")
        activate(showOnScreen("settings.data.import", "the section has Import"))
        #if os(macOS)
        let refuse = filePanelButton("Import opens the open panel")
        goTo("\(folder)/not-tortuga.json")
        ready(refuse).click()
        if !waitUntil(10, { !refuse.exists }) { fail("picking a file leaves the open panel up") }
        #else
        let sheet = showLocalFiles("the open sheet")
        ready(fileInSheet("not-tortuga", "the open sheet lists the file that isn't an export")).tap()
        if !waitUntil(10, { !sheet.exists }) { fail("picking a file leaves the open sheet up") }
        #endif
        requireDataNotice("This file isn't a Tortuga export.", "a file that isn't an export is refused")
        if element("settings.data.review.confirm").exists { fail("a refused file shows a review") }
        shot("refused file")
        #if os(macOS)
        // The page in the window's other layouts: the sidebar hidden (the View menu's Hide Sidebar), then shown again in
        // a window narrowed to its least width. Each shot once the picture stops moving: the menu's click returns once
        // the app idles, the sidebar on its way out; the narrowing once the window is narrower.
        let view = app.menuBars.menuBarItems["View"]
        ready(view).click()
        pickMenuEntry(view.menuItems["toggleSidebar:"])
        screenSettles()
        shot("sidebar hidden")
        ready(view).click()
        pickMenuEntry(view.menuItems["toggleSidebar:"])
        let window = app.windows.firstMatch
        let wide = window.frame.width
        let corner = window.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 1)).withOffset(CGVector(dx: -1, dy: -1))
        corner.click(forDuration: 0.3, thenDragTo: corner.withOffset(CGVector(dx: -window.frame.width, dy: 0)))
        _ = waitUntil(5) { window.frame.width < wide }
        screenSettles()
        shot("narrow window")
        #else
        if isPad {
            // The page in landscape, beside the sidebar. Last, with no query after the turn, and the app quit before
            // the turn back (`quitAndTurn`): turned while it runs, the iPad can leave XCUITest waiting on it for good.
            // The screen's own shot, turned a quarter (the app's holds its portrait frame cropped to the landscape
            // canvas), once the screen's own pictures show the turn: changed, then still.
            addTeardownBlock { TortugaUITestCase.quitAndTurn(to: .portrait) }
            let upright = XCUIScreen.main.screenshot().pngRepresentation
            XCUIDevice.shared.orientation = .landscapeLeft
            _ = waitUntil(5) { XCUIScreen.main.screenshot().pngRepresentation != upright }
            screenSettles(XCUIScreen.main)
            shot("refused file in landscape", of: XCUIScreen.main)
        }
        #endif
    }

    #if os(iOS)
    /// A file over the 32 MiB Import reads (a video picked by mistake) is refused as one that couldn't be opened, before
    /// it is read: not read whole and handed on, which said it isn't an export. The file goes into On My iPhone once the
    /// open sheet has shown it (a fresh simulator has no such folder before), then the sheet opens again to list it.
    func testImportRefusesAFileOver32MiB() throws {
        try launchDev()
        activate(navItem("nav.settings", "the sidebar lists Settings"))
        openDataSection()
        activate(showOnScreen("settings.data.import", "the section has Import"))
        showLocalFiles("the open sheet")
        let file = try localFileStorage().appending(path: "too-big.json")
        FileManager.default.createFile(atPath: file.path(percentEncoded: false), contents: nil)
        addTeardownBlock { try? FileManager.default.removeItem(at: file) }
        let handle = try FileHandle(forWritingTo: file)
        try handle.truncate(atOffset: 33 << 20)
        try handle.close()
        ready(app.buttons.matching(NSPredicate(format: "label == 'Cancel'")).firstMatch).tap()
        activate(showOnScreen("settings.data.import", "Cancel returns to the section's Import"))
        let sheet = showLocalFiles("the open sheet, again")
        ready(fileInSheet("too-big", "the open sheet lists the 33 MiB file")).tap()
        if !waitUntil(10, { !sheet.exists }) { fail("picking a file leaves the open sheet up") }
        requireDataNotice("The file couldn't be opened.", "a file over 32 MiB is refused as unreadable")
        shot("file over 32 MiB refused")
    }
    #endif

    /// Opens the menu of the row `row` by its ⋯ (`<row>.more`); `what` names the row in the failure when it has none.
    private func openMenu(_ row: String, _ what: String) {
        activate(require("\(row).more", "\(what): \(row) shows ⋯"))
    }

    /// What a menu's shot takes: on the Mac the screen, where a menu is a window of its own; on iOS the app.
    private var menuShotSource: (any XCUIScreenshotProviding)? {
        #if os(macOS)
        XCUIScreen.main
        #else
        nil
        #endif
    }

    /// From Settings' first page, Export and import, below the other sections.
    private func openDataSection() {
        activate(showOnScreen("settings.section.data", "Settings lists Export and import"))
        require("settings.data.export.addons", "Export and import opens")
    }

    /// Export and import's notice reading `text` within 20 s; fails with `what`. On iOS it is scrolled into view first:
    /// the list makes it only as it scrolls in, and on the iPhone Duo's cover it lies below the screen.
    private func requireDataNotice(_ text: String, _ what: String) {
        #if os(iOS)
        showOnScreen("settings.data.notice", what)
        #endif
        requireText("settings.data.notice", contains: text, timeout: 20, what)
    }

    #if os(macOS)
    /// Drags the handle of the ordered row `row` over row `onto`'s upper half (its lower half: `below`), holds it there
    /// while the line marks the place, and drops it. The handle is scrolled inside the window first, and `onto`'s handle
    /// must lie there then too (`liesInside`): a drop aimed past the window's edge misses the list, so the two rows must
    /// share the window (`farHomeRow`). With `shot`, the screen while it is held there (sampled from a thread of its
    /// own) is attached under that name.
    func dragRow(_ row: String, onto: String, below: Bool, shot name: String? = nil) {
        let handle = scrollTo(require("\(row).handle", "\(row) shows its drag handle"), "\(row)'s drag handle lies inside the window")
        let target = require("\(onto).handle", "\(onto) shows its drag handle")
        if !liesInside(target) { return fail("\(onto) at \(target.frame) isn't inside the window with \(row)'s handle at \(handle.frame): a drag between them leaves the screen") }
        let sampler = name == nil ? nil : ScreenSampler()
        sampler?.start()
        ready(handle).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).click(
            forDuration: 0.3,
            thenDragTo: target.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: below ? 0.85 : 0.15)),
            withVelocity: .slow,
            thenHoldForDuration: 1.2
        )
        let dropped = Date()
        guard let name, let sampler else { return }
        // Held over the target half a second before the drop: the line marks the place.
        guard let frame = sampler.stop(before: dropped.addingTimeInterval(-0.5)) else { return fail("no frame sampled while \(row) was dragged") }
        let attachment = XCTAttachment(screenshot: frame)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// The default button (`OKButton`: Export, Open) of the save or open panel that SwiftUI's `fileExporter` or
    /// `fileImporter` shows: NSSavePanel or NSOpenPanel in the app's own process (it isn't sandboxed), a sheet on the
    /// window. Fails with `what` when it doesn't show.
    private func filePanelButton(_ what: String) -> XCUIElement {
        let button = app.sheets.buttons.matching(identifier: "OKButton").firstMatch
        if !button.exists(within: 10) { fail("\(what): no panel with a default button") }
        return button
    }

    /// Takes the open file panel to `path` by Go to Folder (Command-Shift-G, the path typed, Return): a folder, or a
    /// file, which the panel then selects. The path is typed once Go to Folder's field has the keyboard focus (the
    /// focus moves off what had it in the panel), and the panel goes on once the focus has left that field again.
    private func goTo(_ path: String) {
        let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasKeyboardFocus == true")).firstMatch
        let before = focused.exists ? focused.frame : nil
        app.typeKey("g", modifierFlags: [.command, .shift])
        var field: CGRect?
        if !waitUntil(5, { field = focused.exists ? focused.frame : nil; return field != nil && field != before }) {
            fail("Command-Shift-G doesn't bring up Go to Folder's field")
        }
        app.typeText(path)
        app.typeKey(.return, modifierFlags: [])
        if !waitUntil(5, { !focused.exists || focused.frame != field }) { fail("Return leaves Go to Folder's field up") }
    }

    /// What is amiss with the sheet holding the button `button` (the innermost, over another sheet), which it shoots as
    /// `what`: it should end right under its form, no more than 64 points between the lowest text or control of the
    /// form (its last row, or the last in view of a form taller than the sheet may be) and the top of its buttons, and
    /// a sheet shorter than the 560-point form it may hold should show its form whole, nothing laid out past its bottom.
    func sheetProblems(_ what: String, buttons button: String) -> [String] {
        let frame = require(button, timeout: 5, "\(what) has \(button)").frame
        // The sheet settles at its form's height: its buttons hold still.
        _ = settledFrame(of: element(button))
        shot(what)
        let sheets = app.sheets.allElementsBoundByIndex.filter { $0.frame.contains(frame) }
        guard let sheet = sheets.min(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }),
              let tree = try? sheet.snapshot() else { return ["\(what) isn't in a sheet"] }
        let top = element(button).frame.minY
        let bounds = sheet.frame
        let kinds: Set<XCUIElement.ElementType> = [.staticText, .textField, .searchField, .button, .checkBox, .switch, .popUpButton, .image]
        var lowest: CGFloat?
        var past = 0
        func walk(_ node: any XCUIElementSnapshot) {
            if kinds.contains(node.elementType), !node.frame.isEmpty {
                if node.frame.maxY < top { lowest = max(lowest ?? 0, node.frame.maxY) }
                if node.frame.minY >= bounds.maxY { past += 1 }
            }
            node.children.forEach(walk)
        }
        walk(tree)
        guard let lowest else { return ["\(what): its sheet shows nothing above its buttons"] }
        let gap = top - lowest
        XCTContext.runActivity(named: "\(what): \(Int(gap)) points from the form's last row to its buttons in a sheet \(Int(bounds.height)) points tall") { _ in }
        var problems: [String] = []
        if gap > 64 { problems.append("\(what): \(Int(gap)) points from the form's last row to its buttons (sheet \(bounds))") }
        if past > 0, bounds.height < 540 { problems.append("\(what): \(past) of its form's elements lie past the bottom of a sheet \(Int(bounds.height)) points tall") }
        return problems
    }
    #else
    /// Drags the row `row` by its handle (`<row>.handle`, at its leading edge) over row `onto`'s upper part (its lower
    /// part: `below`): held until the row lifts, moved there, held while the rows make room, and let go. The page is
    /// swiped up first until the handle lies above iPhone's tab bar, which floats over the list's end (a press there
    /// lands on a tab, and isHittable doesn't see the bar), and `onto` must lie inside the window then too (`liesInside`):
    /// a drop aimed off the screen misses the list, so the two rows must share the window (11 rows in Settings →
    /// Home, the last one's handle swiped in left the first off the screen; `farHomeRow`). With `shot`, the
    /// screen mid-drag (sampled from a thread of its own) is attached under that name.
    private func dragRow(_ row: String, onto: String, below: Bool, shot name: String? = nil) {
        let grip = swipeUntilShown("\(row).handle", "\(row) shows its drag handle above the tab bar")
        let handle = grip.frame
        let place = require(onto, "the list shows \(onto)")
        if !liesInside(place) { return fail("\(onto) at \(place.frame) isn't inside the window with \(row)'s handle at \(handle): a drag between them leaves the screen") }
        let target = place.frame
        let start = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: handle.midX, dy: handle.midY))
        let end = start.withOffset(CGVector(dx: 0, dy: target.midY + target.height * (below ? 0.3 : -0.3) - handle.midY))
        let sampler = name == nil ? nil : ScreenSampler()
        sampler?.start()
        // Held 2 s over the place when shot: the press returns once the app idles, within a second of the drop, so the
        // frame a second before it shows the row still held.
        start.press(forDuration: 1, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: sampler == nil ? 0.6 : 2)
        guard let name, let sampler else { return }
        guard let frame = sampler.stop(before: Date().addingTimeInterval(-1)) else { return fail("no frame sampled while \(row) was dragged") }
        let attachment = XCTAttachment(screenshot: frame)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }


    /// The system's file sheet at On My iPhone (On My iPad), the local file provider's root, where the test's files
    /// are: the sheet opens where it was last or at Recents, and Browse lists the locations. Fails with `what` when it
    /// doesn't get there. The sheet's root, which goes as the sheet closes.
    @discardableResult
    private func showLocalFiles(_ what: String) -> XCUIElement {
        let local = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "DOC.browsingRoot Source: com.apple.FileProvider.LocalStorage"))
            .firstMatch
        let name = isPad ? "On My iPad" : "On My iPhone"
        for _ in 0..<3 where !local.exists(within: 3) {
            let browse = app.buttons.matching(NSPredicate(format: "label == 'Browse'")).firstMatch
            if let location = app.staticTexts.matching(NSPredicate(format: "label == %@", name)).allElementsBoundByIndex.first(where: \.isHittable) {
                ready(location).tap()
            } else if browse.exists {
                ready(browse).tap()
            }
        }
        if !local.exists { fail("\(what) doesn't show \(name)") }
        return local
    }

    /// The file whose name starts with `prefix` in the system's open sheet; fails with `what` when it isn't listed.
    private func fileInSheet(_ prefix: String, _ what: String) -> XCUIElement {
        let file = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
        if !file.exists(within: 10) { fail("\(what): no file \(prefix)…") }
        return file
    }

    /// On My iPhone's folder on the simulator's disk: the local file provider's group container
    /// (`group.com.apple.FileProvider.LocalStorage`) under the simulator's data (`SIMULATOR_SHARED_RESOURCES_DIRECTORY`).
    private func localFileStorage() throws -> URL {
        guard let data = ProcessInfo.processInfo.environment["SIMULATOR_SHARED_RESOURCES_DIRECTORY"] else {
            throw HarnessError("SIMULATOR_SHARED_RESOURCES_DIRECTORY is not set: the test runs on a simulator")
        }
        let groups = URL(filePath: data).appending(path: "Containers/Shared/AppGroup")
        for group in try FileManager.default.contentsOfDirectory(at: groups, includingPropertiesForKeys: nil) {
            let metadata = NSDictionary(contentsOf: group.appending(path: ".com.apple.mobile_container_manager.metadata.plist"))
            if metadata?["MCMMetadataIdentifier"] as? String == "group.com.apple.FileProvider.LocalStorage" {
                return group.appending(path: "File Provider Storage")
            }
        }
        throw HarnessError("no group.com.apple.FileProvider.LocalStorage container in \(groups.path(percentEncoded: false))")
    }
    #endif
}
