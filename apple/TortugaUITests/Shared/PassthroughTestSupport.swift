import XCTest

/// What the passthrough tests read of mpv's audio output: the engine probe's `ao`, `spdif` and `format` fields
/// (`EngineReading`: mpv's `current-ao`, `audio-spdif` and `audio-out-params/format`), off `player.engine`.
extension TortugaUITestCase {
    /// The formats passthrough names in `audio-spdif` here (`MpvPlayerEngine.passthroughCodecs`): AC-3 and E-AC-3 for
    /// the system's Dolby decoder, and from iOS and tvOS 26 TrueHD and DTS-HD for mpv-build's `spatial` output.
    static var passthroughCodecs: String {
        #if !os(macOS)
        if #available(iOS 26, tvOS 26, *) { return "ac3,eac3,truehd,dts-hd" }
        #endif
        return "ac3,eac3"
    }

    /// The outputs mpv may settle on here (`MpvPlayerEngine.audioOutput`).
    static var passthroughAudioOutputs: Set<String> {
        #if os(macOS)
        ["coreaudio", "avfoundation"]
        #else
        ["spatial", "avfoundation", "audiounit"]
        #endif
    }

    /// The engine probe's fields as it reads now; empty while it reads nothing.
    func audioProbe() -> [String: String] {
        let read = (try? element("player.engine").snapshot())?.label ?? ""
        var fields: [String: String] = [:]
        for field in read.split(separator: " ") {
            let pair = field.split(separator: "=", maxSplits: 1)
            if pair.count == 2 { fields[String(pair[0])] = String(pair[1]) }
        }
        return fields
    }

    /// mpv's audio output with passthrough `on` or off: `audio-spdif` names this platform's formats
    /// (`passthroughCodecs`) or none, and mpv has opened one of this platform's outputs (`passthroughAudioOutputs`)
    /// with a format, which while off is never a bitstream (`spdif-…`). Waits up to 20 s; fails with `what`. The
    /// reading goes into the run's record either way: which output took the track, and whether it went out undecoded.
    func requirePassthrough(_ on: Bool, _ what: String) {
        let spdif = on ? Self.passthroughCodecs : "none"
        var fields: [String: String] = [:]
        let met = waitUntil(20) {
            fields = audioProbe()
            guard fields["spdif"] == spdif, let output = fields["ao"], Self.passthroughAudioOutputs.contains(output),
                  let format = fields["format"], format != "none" else {
                return false
            }
            return on || !format.hasPrefix("spdif-")
        }
        let reading = fields.isEmpty ? "nothing" : fields.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: " ")
        XCTContext.runActivity(named: "\(what): \(reading)") { _ in }
        if !met {
            let outputs = Self.passthroughAudioOutputs.sorted().joined(separator: ", ")
            fail("\(what): mpv's audio output reads \(reading), not spdif=\(spdif) with a format on one of \(outputs)")
        }
    }
}
