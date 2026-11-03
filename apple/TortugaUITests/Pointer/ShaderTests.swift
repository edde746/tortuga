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
