import XCTest

/// The options menu's Shaders over the H.264 file, paused: its submenu offers Import shader after the presets (this
/// device has the system's open dialog), and each preset picked there becomes the option's value and runs on the same
/// paused frame, shot each time, then Off again.
extension PlayerTests {
    func testShaderPresetsPickedFromTheOptionsMenu() throws {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        activate(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream"))
        let video = require("player.video", "a pick of the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        pause(video)

        showControls(video)
        activate(element("player.more"))
        let shaders = openMenuEntry("Shaders", "the options menu lists Shaders")
        activate(shaders)
        _ = openMenuEntry("Import shader", "Shaders offers Import shader")
        #if os(macOS)
        shot("options menu: Shaders")
        // Escape closes the submenu, then the options menu; one more would leave the player.
        app.typeKey(.escape, modifierFlags: [])
        if !waitUntil(2, { menuClosed(shaders) }) { app.typeKey(.escape, modifierFlags: []) }
        #else
        screenShot("options menu: Shaders")
        video.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.5)).tap()
        #endif
        if !waitUntil(5, { menuClosed(shaders) }) { fail("the options menu doesn't close") }

        for preset in ["NVScaler", "ArtCNN C4F16", "Anime4K Fast A", "Off"] {
            pickOption("Shaders", preset, video: video)
            shot("shaders: \(preset)")
        }
    }
}

#if os(macOS)
/// Shaders' own entries over the H.264 file playing, on the Mac, whose open panel reaches the run's files
/// (`TORTUGA_FILES`): tortuga-test.glsl, a shader that shows the picture as it is, and oversized.glsl, one byte over
/// the 32 MiB a shader import reads.
extension PlayerTests {
    /// oversized.glsl picked: the import fails ("Couldn't import the shader") rather than reading the file whole.
    func testShaderImportOverTheSizeLimitFails() throws {
        let folder = try AccountScenario.variable("TORTUGA_FILES")
        let video = try openPlayingVideo()
        pickShaderEntry("Import shader", video)
        let open = openPanelButton("Import shader opens the open panel")
        goTo("\(folder)/oversized.glsl")
        ready(open).click()
        requirePlayerMessage("Couldn't import the shader", "picking oversized.glsl")
    }

    /// Import shader and an imported shader's Delete while the video plays, the controls hiding on their timer (3 s)
    /// meanwhile: the open panel stays up past it and imports tortuga-test.glsl ("Shader imported"), which Shaders then
    /// offers to delete; the question before the deletion stays up past it too, and its Delete takes the shader out of
    /// Shaders.
    func testShaderPromptsOutlastTheControls() throws {
        let folder = try AccountScenario.variable("TORTUGA_FILES")
        let video = try openPlayingVideo()
        pickShaderEntry("Import shader", video)
        let open = openPanelButton("Import shader opens the open panel")
        if !holds(for: 6, { open.exists }) { fail("the open panel went as the controls hid") }
        goTo("\(folder)/tortuga-test.glsl")
        ready(open).click()
        requirePlayerMessage("Shader imported", "picking tortuga-test.glsl")

        pickShaderEntry("Delete tortuga-test", video)
        let delete = app.buttons.matching(NSPredicate(format: "label == 'Delete'")).firstMatch
        if !delete.exists(within: 10) { fail("Delete tortuga-test doesn't ask before deleting") }
        shot("question before the deletion")
        if !holds(for: 6, { delete.exists }) { fail("the question before the deletion went as the controls hid") }
        ready(delete).click()

        showControls(video)
        activate(element("player.more"))
        let shaders = openMenuEntry("Shaders", "the options menu lists Shaders")
        activate(shaders)
        _ = openMenuEntry("Import shader", "Shaders offers Import shader")
        if app.menuItems.matching(NSPredicate(format: "title == 'Delete tortuga-test'")).firstMatch.exists {
            fail("Shaders still offers to delete tortuga-test once its deletion was confirmed")
        }
        app.typeKey(.escape, modifierFlags: [])
        if !waitUntil(2, { menuClosed(shaders) }) { app.typeKey(.escape, modifierFlags: []) }
    }

    /// The player playing the H.264 file.
    private func openPlayingVideo() throws -> XCUIElement {
        try launchDev(addon: true, open: ["streams:movie:tt0111161:tt0111161"])
        activate(require("streams.row.1", timeout: 60, "the Streams screen lists the H.264 stream"))
        let video = require("player.video", "a pick of the H.264 stream opens the player")
        requirePlayback(2, timeout: 90)
        return video
    }

    /// Picks Shaders' entry `label` from the options menu.
    private func pickShaderEntry(_ label: String, _ video: XCUIElement) {
        showControls(video)
        activate(element("player.more"))
        activate(openMenuEntry("Shaders", "the options menu lists Shaders"))
        pickMenuEntry(openMenuEntry(label, "Shaders offers \(label)"))
    }

    /// Waits up to 5 s for the player's message (`player.osd`) to read `expected`; fails with `what`.
    private func requirePlayerMessage(_ expected: String, _ what: String) {
        let message = element("player.osd")
        if !waitUntil(5, { message.exists && text(of: message).contains(expected) }) {
            fail("\(what): the player's message reads '\(message.exists ? text(of: message) : "nothing")', not '\(expected)'")
        }
    }

    /// The default button (`OKButton`, Open) of the open panel that `fileImporter` shows, a sheet on the window. Fails
    /// with `what` when it doesn't show.
    private func openPanelButton(_ what: String) -> XCUIElement {
        let button = app.sheets.buttons.matching(identifier: "OKButton").firstMatch
        if !button.exists(within: 10) { fail("\(what): no panel with a default button") }
        return button
    }

    /// Takes the open panel to the file at `path` by Go to Folder (Command-Shift-G, the path typed, Return), once its
    /// field has the keyboard focus; the panel goes on once the focus has left that field again.
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
}
#endif
