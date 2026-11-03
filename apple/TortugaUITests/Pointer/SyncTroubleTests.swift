import XCTest

/// Sync trouble on iPhone, iPad and the Mac, on the dev stack (server/compose.dev.yaml) behind the test's
/// own relay (`ServerRelay`). Each test has the run's hook make the Dex user's account afresh with one profile, Ana;
/// the app signs in on the relay's address by Dex's device flow (the hook completes it) and
/// syncs: Settings → Account reads "Up to date". Signed out at the end, the hook deletes the account.
///
/// The sync status: relaunched with the relay closed (the server out of reach), the app's first sync fails and it says
/// so: on iPhone the line across the top of the screen the app reopens on (Settings, where the sign-in left it:
/// `nav.sync`), on iPad and the Mac the mark on the running profile's avatar at the sidebar's foot (`nav.sync`, the
/// status its label; on iPad Home's navigation bar's profile carries it too), and Settings → Account's status. Settings
/// shows its profile card and no running profile's button beside it (iPhone: none; iPad: none in its navigation bar).
/// The relay open again, Account's Sync now gets through: the status reads "Up to date", and iPhone's line and the
/// avatar's mark are gone. Then the other session writes from a clock two days ahead: Sync now refuses its record and
/// the status says a clock is wrong (Settings → Account; the sidebar's mark on iPad and the Mac).
///
/// Signing out with a change the server doesn't have: the relay closed, a step up of Ana's watched threshold (a synced
/// setting) stays on this device, so Sign out, confirmed, pushes in vain and asks whether to delete it ("Delete changes
/// that haven't synced?", answered "Sign out and delete" or Cancel); Cancel leaves the app signed in, Account's status
/// offline. The relay open again, Sync now gets the change through, and Sign out signs out at its first answer.
///
/// iOS (`SyncTroubleTests+iOS`): signed in, the profile picker at the largest text size, where the system's audit finds
/// no text clipped or cut short and none that keeps its size; and Home's first card opening a Detail that slides in and
/// goes back the way it came.
final class SyncTroubleTests: TortugaUITestCase {
    /// The relay the app's session is on, until the test signs out (`signOut`).
    private var signedInThrough: ServerRelay?

    /// The sign-ins `signInThroughRelay` has made in this run. The run's watcher takes each event's name once, so
    /// each sign-in after the run's first hands over its device code and its asks of the other session under names of
    /// its own (`device-code-2`, `peer-reset-2`, …), as the tour's device codes go: the count goes on from test to
    /// test.
    private static var signIns = 0
    /// This test's sign-in among them (`signIns`): its events' number.
    var signIn = 1

    func testSyncTroubleShowsUntilSyncNowGetsThrough() throws {
        continueAfterFailure = false
        executionTimeAllowance = 900
        let (relay, exchange) = try signInThroughRelay()

        // A running app's open connections would end with the relay too, but its sync would wait for its next turn.
        app.terminate()
        relay.close()
        if relay.answers() { fail("the server still answers at \(relay.url) once the relay is closed") }
        launchProduct()
        openProfile("relaunched with the server out of reach")
        #if os(iOS)
        if isPad {
            requireSidebarMark(SyncStatusText.offline, shot: "sync mark, server out of reach", "relaunched with the server out of reach")
            requireProfileCardAlone("relaunched with the server out of reach, the sidebar closed")
            let settings = phoneTab("nav.settings"), home = phoneTab("nav.board")
            ready(home).tap()
            if !waitUntil(5, { home.isSelected }) { fail("a tap on Home in the floating tab bar doesn't select it") }
            requireMark(SyncStatusText.offline, atFoot: false, timeout: 10, "relaunched with the server out of reach, the sidebar closed, on Home")
            shot("sync mark in Home's navigation bar, server out of reach")
            ready(settings).tap()
            if !waitUntil(5, { settings.isSelected }) { fail("a tap on Settings in the floating tab bar doesn't select it") }
        } else {
            requireSyncLineOverSettings()
            requireProfileCardAlone("relaunched with the server out of reach")
            shot("sync line over Settings")
        }
        #else
        requireSidebarMark(SyncStatusText.offline, shot: "sync mark, server out of reach", "relaunched with the server out of reach")
        #endif
        openSection("account")
        requireAccountStatus(SyncStatusText.offline, timeout: 30, "the server out of reach")
        shot("account, server out of reach", of: shotSource)

        try relay.open()
        activateShown("settings.account.syncNow", "the account offers Sync now")
        requireAccountStatus(SyncStatusText.idle, timeout: 60, "the server back, Sync now")
        #if os(iOS)
        if isPad {
            requireNoSidebarMark(shot: "no sync mark, synced again", "the server back, Sync now")
        } else {
            requireGone("nav.sync", timeout: 10, "the server back, Sync now takes the sync line away")
        }
        #else
        requireNoSidebarMark(shot: "no sync mark, synced again", "the server back, Sync now")
        #endif
        shot("account, synced again", of: shotSource)

        peer("ahead", exchange, "the other session writes from a clock two days ahead", number: signIn)
        activateShown("settings.account.syncNow", "the account offers Sync now")
        requireAccountStatus(SyncStatusText.clockWrong, timeout: 60, "a record from a clock two days ahead")
        shot("account, a clock is wrong", of: shotSource)
        #if os(iOS)
        if isPad {
            requireSidebarMark(SyncStatusText.clockWrong, shot: "sync mark, a clock is wrong", "a record from a clock two days ahead")
        }
        #else
        requireSidebarMark(SyncStatusText.clockWrong, shot: "sync mark, a clock is wrong", "a record from a clock two days ahead")
        #endif

        signOut("the test done")
        peer("delete", exchange, "the other session deletes the account", number: signIn)
    }

    func testSignOutAsksBeforeDeletingChangesThatHaventSynced() throws {
        continueAfterFailure = false
        executionTimeAllowance = 900
        let (relay, exchange) = try signInThroughRelay()

        // The server out of reach: a step up of Ana's watched threshold, a synced setting, stays on this device.
        relay.close()
        if relay.answers() { fail("the server still answers at \(relay.url) once the relay is closed") }
        openSection("playback")
        let threshold = "settings.playback.watchedThreshold"
        require(threshold, "Playback lists the watched threshold")
        let before = stepperText(threshold)
        activate(stepperButton(threshold, up: true))
        if !waitUntil(5, { stepperText(threshold) != before }) { fail("a step up leaves the watched threshold at \"\(before)\"") }

        openSection("account")
        activateShown("settings.account.signOut", "the account offers Sign out")
        require("settings.account.dialog.confirm", timeout: 10, "Sign out asks first")
        shot("sign out asks first", of: shotSource)
        activate(element("settings.account.dialog.confirm"))
        let what = "Sign out confirmed with a change the server doesn't have"
        let title = Self.unsyncedTitle
        let question = app.staticTexts.matching(NSPredicate(format: "label == %@ OR value == %@", title, title)).firstMatch
        if !question.exists(within: 60) { fail("\(what): no \"\(title)\" after 60 s") }
        requireText("settings.account.dialog.confirm", contains: "Sign out and delete", "\(what): the question's answer")
        shot("delete changes that haven't synced", of: shotSource)

        activate(element("settings.account.dialog.cancel"))
        requireGone("settings.account.dialog.cancel", "Cancel closes the question")
        if waitUntil(5, { element("signIn.option.oidc").exists || element("signIn.server.edit").exists }) {
            fail("Cancel signs the device out")
        }
        requireAccountStatus(SyncStatusText.offline, timeout: 30, "Cancel leaves the device signed in, its change unsynced")
        shot("cancel keeps the device signed in", of: shotSource)

        try relay.open()
        activateShown("settings.account.syncNow", "the account offers Sync now")
        requireAccountStatus(SyncStatusText.idle, timeout: 60, "the server back, Sync now")
        signOut("the change synced")
        peer("delete", exchange, "the other session deletes the account", number: signIn)
    }

    /// The question a sign-out asks when changes made here still haven't synced (shared/i18n/en.i18n.json's
    /// `settings.account.unsynced.title`; the app runs in English).
    private static let unsyncedTitle = "Delete changes that haven't synced?"

    /// The relay to the dev stack's server, open, and the run's exchange directory, once the other session has made the
    /// account afresh and the app has signed in to it on the relay's address by Dex's device flow, opened Ana and read
    /// "Up to date" in Settings → Account. `atPicker` runs while the profile picker shows, before Ana is opened. The
    /// relay closes as the test ends.
    func signInThroughRelay(atPicker: (() -> Void)? = nil) throws -> (relay: ServerRelay, exchange: URL) {
        let server = try AccountScenario.variable("TORTUGA_SERVER")
        let exchange = URL(fileURLWithPath: try AccountScenario.variable("TORTUGA_EXCHANGE"), isDirectory: true)
        let relay = try ServerRelay(to: server)
        try relay.open()
        addTeardownBlock { relay.close() }
        if !relay.answers() { fail("the dev stack's server doesn't answer through the relay at \(relay.url)") }
        #if os(macOS)
        // The Mac keeps the app's session from run to run, and one on the relay's address, which goes with this test,
        // would keep the next run's app offline, with no sign-in screen for the account tests: a test that stopped while
        // signed in signs out here, before the relay closes (teardown blocks run last first).
        addTeardownBlock { [self] in
            guard let relay = signedInThrough else { return }
            do { try relay.open() } catch { return fail("the relay doesn't open again to sign out: \(error)") }
            app.terminate()
            launchProduct()
            openProfile("relaunched to sign out")
            openSection("account")
            signOut("signing out after the test stopped")
        }
        #endif
        Self.signIns += 1
        signIn = Self.signIns
        peer("reset", exchange, "the other session makes the account afresh", number: signIn)

        // On the Mac a session an earlier run left belongs to an account the reset deleted: its first call signs it out.
        launchProduct()
        showStackOptions(relay.url, "the sign-in screen shows")
        activate(element("signIn.option.oidc"))
        let code = requireCode("deviceCode.code", timeout: 60, "Dex's device flow shows its code")
        publish(signIn == 1 ? "device-code" : "device-code-\(signIn)", code: code, to: exchange)
        signedInThrough = relay
        if let atPicker {
            require("profiles.tile.0", timeout: 180, "signed in with Dex, the profile picker shows")
            atPicker()
        }
        openProfile("signed in with Dex")
        openSection("account")
        requireAccountStatus(SyncStatusText.idle, timeout: 60, "signed in, the account syncs")
        return (relay, exchange)
    }

    #if os(iOS)
    /// iPhone's sync line once the first sync fails: across the top of the screen, under the status bar, reading the
    /// server out of reach, over Settings, where the app reopens: the sign-in left it there.
    private func requireSyncLineOverSettings() {
        let what = "relaunched with the server out of reach"
        let line = require("nav.sync", timeout: 90, "\(what), the sync line shows")
        if !waitUntil(10, { line.label.contains(SyncStatusText.offline) }) {
            fail("\(what): the sync line reads \"\(line.label)\", not \"\(SyncStatusText.offline)\"")
        }
        if !phoneTab("nav.settings").isSelected { fail("\(what): the app isn't back on Settings, where it was left") }
        let window = app.windows.firstMatch.frame
        if line.frame.minY > window.height / 6 || line.frame.width < window.width - 1 {
            fail("\(what): the sync line lies at \(line.frame), not across the top of the screen (\(window))")
        }
    }
    #endif

    #if os(iOS)
    /// Settings' profile card (its Switch or manage profiles), and no running profile's button beside it: none on
    /// iPhone, none in iPad's navigation bar over Settings (the sidebar's foot keeps its own); fails with `what`.
    private func requireProfileCardAlone(_ what: String) {
        require("settings.profile.manage", timeout: 30, "\(what): Settings shows its profile card")
        let window = app.windows.firstMatch.frame
        let beside = profileFrames.filter { !isPad || $0.midY < window.minY + window.height / 6 }
        if !beside.isEmpty { fail("\(what): Settings shows the running profile's button at \(beside) beside its profile card") }
    }
    #endif

    /// The sidebar's sync mark once it reads `status` (`requireMark`), kept as the shot `name`. On iPad the sidebar is
    /// the one the floating tab bar opens into, over the screen in portrait: opened for the reading and closed again by
    /// its toggle, as a tap on the screen would only close it.
    private func requireSidebarMark(_ status: String, shot name: String, _ what: String) {
        #if os(iOS)
        openSidebar(what)
        #endif
        requireMark(status, atFoot: true, timeout: 90, what)
        shot(name, of: shotSource)
        #if os(iOS)
        closeSidebar(what)
        #endif
    }

    /// No sync mark once the status is up to date, the running profile at the sidebar's foot unmarked, kept as the shot
    /// `name`; on iPad the sidebar opened for it and closed again (`requireSidebarMark`).
    private func requireNoSidebarMark(shot name: String, _ what: String) {
        #if os(iOS)
        openSidebar(what)
        #endif
        let window = app.windows.firstMatch.frame
        if !waitUntil(10, { profileFrames.contains { $0.midY > window.midY } }) {
            fail("\(what): no profile at the sidebar's foot (\(profileFrames))")
        }
        if !waitUntil(10, { syncMarks.isEmpty }) {
            fail("\(what): the sync mark stays, reading \(syncMarks.map(reading))")
        }
        shot(name, of: shotSource)
        #if os(iOS)
        closeSidebar(what)
        #endif
    }

    /// The sync mark (`nav.sync`) reading `status` on the running profile's avatar at the window's foot (the
    /// sidebar's) or, `atFoot` false, at its top (iPad's navigation bar), at the avatar's lower trailing corner; fails
    /// with `what` and what the marks read when none does within `timeout`.
    private func requireMark(_ status: String, atFoot: Bool, timeout: TimeInterval, _ what: String) {
        let window = app.windows.firstMatch.frame
        let placed = { (frame: CGRect) in atFoot ? frame.midY > window.midY : frame.midY < window.minY + window.height / 6 }
        var mark: XCUIElement?
        let found = waitUntil(timeout) {
            mark = syncMarks.first { reading($0).contains(status) && placed($0.frame) }
            return mark != nil
        }
        guard found, let mark else {
            let place = atFoot ? "at the sidebar's foot" : "in the navigation bar"
            return fail("\(what): no sync mark \(place) reads \"\(status)\" after \(Int(timeout)) s; the marks read \(syncMarks.map(reading))")
        }
        // The avatar (32 pt) leads its button, centred on the button's height; the mark's centre lies on its lower
        // trailing quarter, the mark reaching a few points past its edge.
        let frame = mark.frame
        let onCorner = { (profile: CGRect) in
            (profile.minX + 16...profile.minX + 36).contains(frame.midX) && (profile.midY...profile.maxY + 4).contains(frame.midY)
        }
        if !profileFrames.contains(where: onCorner) {
            fail("\(what): the sync mark at \(frame) isn't on the lower trailing corner of a profile's avatar (\(profileFrames))")
        }
    }

    /// The sync marks on screen (`nav.sync`).
    private var syncMarks: [XCUIElement] {
        app.descendants(matching: .any).matching(identifier: "nav.sync").allElementsBoundByIndex
    }

    /// The running profile's buttons on screen (`nav.profile`): the sidebar's, iPad's navigation bar's.
    private var profileFrames: [CGRect] {
        app.descendants(matching: .any).matching(identifier: "nav.profile").allElementsBoundByIndex.map(\.frame)
    }

    /// What a sync mark reads: its label, else its value.
    private func reading(_ mark: XCUIElement) -> String {
        mark.label.isEmpty ? "\(mark.value ?? "")" : mark.label
    }

    #if os(iOS)
    /// Closes iPad's sidebar by its toggle; fails with `what` while its profile stays.
    private func closeSidebar(_ what: String) {
        toggleSidebar("\(what), the sidebar closes")
        let window = app.windows.firstMatch.frame
        if !waitUntil(10, { !profileFrames.contains { $0.midY > window.midY } }) {
            fail("\(what): the sidebar's profile stays once its toggle closes it")
        }
    }
    #endif

    #if os(iOS)
    /// Taps iPad's sidebar toggle where it shows: the floating tab bar's (`ToggleSideBar`) or the open sidebar's
    /// (`ToggleSidebar`).
    private func toggleSidebar(_ what: String) {
        let toggles = app.buttons.matching(NSPredicate(format: "identifier ==[c] 'ToggleSidebar'"))
        var shown: XCUIElement?
        if !waitUntil(10, { shown = toggles.allElementsBoundByIndex.first { $0.isHittable }; return shown != nil }) {
            return fail("\(what): no sidebar toggle on screen")
        }
        shown?.tap()
    }
    #endif

    /// Settings → Account's Sign out, confirmed, back to the sign-in screen (Account open).
    func signOut(_ what: String) {
        activateShown("settings.account.signOut", "\(what): the account offers Sign out")
        activate(require("settings.account.dialog.confirm", timeout: 10, "\(what): Sign out asks first"))
        if !waitUntil(60, { element("signIn.option.oidc").exists || element("signIn.server.edit").exists }) {
            fail("\(what): signing out doesn't return to the sign-in screen")
        }
        signedInThrough = nil
    }
}
