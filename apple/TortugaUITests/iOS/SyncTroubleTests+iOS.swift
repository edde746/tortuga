import XCTest

extension SyncTroubleTests {
    /// The profile picker, the first screen once signed in, at the default text size and at the largest
    /// (accessibility-extra-extra-extra-large), each shot: signed in, the app relaunched at the largest size still shows
    /// the picker (no profile chosen yet), where the system's audit (`textAuditIssues`) finds no text clipped or cut
    /// short (Ana's name, Add profile's, Manage profiles') and none that keeps its size but the avatar's initial, sized
    /// to its circle, and Add profile's name, on two lines there, gets a tile taller than Ana's, on one, with Manage
    /// profiles under it, so nothing covers its second line. Relaunched at the system's size, Ana opens; signed out at
    /// the end, the hook deletes the account.
    func testProfilePickerReadsWholeAtTheLargestTextSize() throws {
        continueAfterFailure = false
        executionTimeAllowance = 900
        var issues: [String] = []
        let (_, exchange) = try signInThroughRelay { [self] in
            require("profiles.add", "the picker offers Add profile")
            screenSettles()
            shot("profiles, default text")
            app.terminate()
            launch(["-UIPreferredContentSizeCategoryName", Self.largestTextSize])
            require("profiles.tile.0", timeout: 60, "relaunched at the largest text size, the profile picker shows")
            require("profiles.add", "the picker offers Add profile at the largest text size")
            screenSettles()
            shot("profiles, largest text")
            issues = textAuditIssues("the profile picker at the largest text size", keepingItsSize: ["profile.initial"])
            let ana = element("profiles.tile.0").frame, add = element("profiles.add").frame
            if add.height <= ana.height {
                issues.append("Add profile's tile is \(add.height) pt tall, Ana's \(ana.height): its name's second line has no room")
            }
            let manage = element("profiles.manage")
            if manage.exists, manage.frame.minY < add.maxY - 0.5 {
                issues.append("Manage profiles (\(manage.frame)) starts over Add profile's tile (\(add))")
            }
            app.terminate()
            launchProduct()
        }
        signOut("the picker audited")
        peer("delete", exchange, "the other session deletes the account", number: signIn)
        if !issues.isEmpty { fail("the audit finds \(issues.count) issues: \(issues.joined(separator: "; "))") }
    }

    /// The signed-in app (the product, not a `--dev` session), Cinemeta installed from its link in Settings → Addons (the
    /// account is made afresh, without addons): Home's first card opens a Detail that slides in and goes back the way it
    /// came, by the back button and by a swipe (`openedTwice`), as in the development session (`TabsTests`). Signed out
    /// at the end, the hook deletes the account.
    func testAHomeCardSlidesInAndBackSignedIn() throws {
        continueAfterFailure = false
        executionTimeAllowance = 900
        let (_, exchange) = try signInThroughRelay()
        openSection("addons")
        ready(require("addons.install", timeout: 30, "Settings' Addons row opens Addons")).tap()
        let link = require("addons.install.link", timeout: 10, "Install asks for the addon's link")
        ready(link).tap()
        link.typeText("https://v3-cinemeta.strem.io/manifest.json")
        ready(require("addons.install.submit", "Install has Continue")).tap()
        ready(require("addons.install.confirm", timeout: 30, "Cinemeta's manifest offers Install")).tap()
        ready(require("addons.install.done", timeout: 30, "Cinemeta installs")).tap()
        let home = phoneTab("nav.board")
        ready(home).tap()
        if !waitUntil(5, { home.isSelected }) { fail("a tap on Home doesn't select it") }
        let card = requireCard("board.row.0.item.0", timeout: 90, "the Board shows Cinemeta's first shelf")
        let problems = openedTwice("Home's first card") { ready(card).tap() }
        openSection("account")
        signOut("the card opened and gone back from")
        peer("delete", exchange, "the other session deletes the account", number: signIn)
        if !problems.isEmpty { fail(problems.joined(separator: "; ")) }
    }
}
