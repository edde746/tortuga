import XCTest

/// Audio passthrough reaching mpv by touch (iOS) and with the pointer (the Mac), on the test addon's HDR10 remux
/// (remux.mkv: TrueHD 5.1 first, then AC-3 5.1), from a launch of its own with This device's Passthrough on, then one
/// with it off: on, mpv's `audio-spdif` names the formats this platform passes (AC-3 and E-AC-3, from iOS 26 TrueHD and
/// DTS-HD too) and mpv opens one of its outputs; off, `audio-spdif` is empty and the output plays decoded audio. The
/// engine probe (`requirePassthrough`) records which output took the TrueHD track and in what format: a simulator has
/// no receiver, so whether a bitstream plays undecoded is the system's to show on a device.
final class PassthroughTests: TortugaUITestCase {
    func testPassthroughSettingReachesMpv() throws {
        // Two launches, each playing the 4K remux, which a simulator decodes in software.
        executionTimeAllowance = 600
        for on in [true, false] {
            let what = "with passthrough \(on ? "on" : "off")"
            try launchDev(addon: true, open: [PlaybackInfoStream.streamsScreen], settings: ["audio.passthrough=\(on)"])
            activate(require("streams.row.0", timeout: 60, "the Streams screen lists the remux \(what)"))
            require("player.video", timeout: 20, "\(Self.tapping) on the remux opens the player \(what)")
            requirePlayback(2, timeout: 90)
            requirePassthrough(on, "the remux's TrueHD track \(what)")
            app.terminate()
        }
    }
}
