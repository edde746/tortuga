import XCTest

/// Audio passthrough reaching mpv with the remote, on the test addon's HDR10 remux (remux.mkv: TrueHD 5.1 first, then
/// AC-3 5.1), from a launch of its own with This device's Passthrough on (Apple TV's default), then one with it off:
/// on, mpv's `audio-spdif` names the formats Apple TV passes (AC-3 and E-AC-3, from tvOS 26 TrueHD and DTS-HD too) and
/// mpv opens one of its outputs; off, `audio-spdif` is empty and the output plays decoded audio. The engine probe
/// (`requirePassthrough`) records which output took the TrueHD track and in what format: a simulator has no HDMI, so
/// what a TV or receiver gets is the device's to show.
final class PassthroughTests: TortugaUITestCase {
    func testPassthroughSettingReachesMpv() throws {
        // Two launches, each playing the 4K remux, which a simulator decodes in software.
        executionTimeAllowance = 600
        for on in [true, false] {
            let what = "with passthrough \(on ? "on" : "off")"
            try launchDev(addon: true, open: [PlaybackInfoStream.streamsScreen], settings: ["audio.passthrough=\(on)"])
            requireFocus("streams.row.0", timeout: 60, "the remux has focus once the Streams screen lists it \(what)")
            press(.select)
            require("player.video", timeout: 20, "Select on the remux opens the player \(what)")
            requirePlayback(2, timeout: 90)
            requirePassthrough(on, "the remux's TrueHD track \(what)")
            app.terminate()
        }
    }
}
