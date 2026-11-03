import XCTest

/// A stream's proxyHeaders in Tortuga's own player, on the test addon's movies whose one stream comes through the
/// addon, which logs each request's headers in the run's
/// addon.log and keeps them for its `/proxied.json`:
/// - tortuga-hls, an HLS playlist at a URL without an extension, served as application/octet-stream, whose type only
///   the stream's proxyHeaders.response gives: it plays as HLS (the playback info's container) for its 20 s;
/// - tortuga-hls-fail, the same, whose segment answers 502 to its first request: mpv's HLS open fails and it opens the
///   playlist's entries as files of their own, which play the segment's 20 s;
/// - tortuga-headers, remux.mkv: it plays as MKV for its 30 s.
/// Every request of the player's for the movie (an open-ended Range or none; the app's resolver asks for closed
/// ranges), the fallback's included, carries the streams' proxyHeaders.request, X-Tortuga-Test and a User-Agent:
/// `/proxied.json` shows them.
final class ProxyHeadersTests: TortugaUITestCase {
    func testHLSKnownByItsResponseContentTypePlays() throws {
        try play("tortuga-hls", container: "HLS", seconds: 20)
    }

    func testHLSWhoseSegmentFailsOncePlaysWithTheHeaders() throws {
        try play("tortuga-hls-fail", container: nil, seconds: 20, failures: 1)
    }

    func testStreamWithRequestHeadersPlays() throws {
        try play("tortuga-headers", container: "MKV", seconds: 30)
    }

    /// `movie`'s stream from its Streams screen: past 4 s without an error, `seconds` long (the duration in the player's
    /// position label, `0:05 / 0:20`), `container` (when given) in the playback info; the close button closes the
    /// player. Then the addon's record of the requests since the launch: the player's carry both headers, and the addon
    /// failed `failures` of them on purpose.
    private func play(_ movie: String, container: String?, seconds: Double, failures: Int = 0) throws {
        let earlier = try proxiedRequests().count
        try launchDev(addon: true, open: ["streams:movie:\(movie):\(movie)"])
        ready(require("streams.row.0", timeout: 60, "the Streams screen lists \(movie)'s stream")).tap()
        let video = require("player.video", "a tap on \(movie)'s stream opens the player")
        requirePlayback(4, timeout: 90)
        if element("player.error").exists { fail("\(movie) shows a playback error: \(fullText(of: element("player.error")))") }
        let label = playhead()
        guard let duration = label.split(separator: "/").last.flatMap({ Self.seconds(String($0)) }), abs(duration - seconds) <= 1 else {
            return fail("\(movie)'s position reads '\(label)'; expected \(seconds) s long")
        }
        shot("\(movie) playing")

        if let container {
            showPlayerControls(video, "a tap on the video shows the controls")
            togglePlaybackInfo()
            require("player.info.panel", timeout: 5, "Playback info in the options menu opens the playback info")
            let row = revealInPlayerSheet(infoRow("Container"), panel: "player.info.panel", "the container row")
            if !waitUntil(10, { row.exists && fullText(of: row).contains(container) }) {
                fail("\(movie)'s container reads '\(row.exists ? fullText(of: row) : "nothing")', not \(container)")
            }
            shot("\(movie) info")
            closeSheet("player.info.close", showing: "player.info.panel", "Close (an iPhone: a pull down) closes the playback info")
        }

        showPlayerControls(video, "a tap on the video shows the controls")
        ready("player.back").tap()
        requireGone("player.video", "the close button closes the player")
        require("streams.row.0", "the close button returns to the Streams screen")

        let requests = try proxiedRequests().dropFirst(earlier)
        let player = requests.filter { $0.range?.wholeMatch(of: /bytes=\d+-\d+/) == nil }
        let wrong = player.filter { $0.testHeader != "proxy-headers" || $0.userAgent != "TortugaProxyHeaders/1.0" }
        if player.isEmpty || !wrong.isEmpty {
            let shown = wrong.prefix(5).map { "\($0.file) \($0.range ?? "-"): \($0.testHeader), \($0.userAgent ?? "no User-Agent")" }
            fail("\(movie): \(wrong.count) of the player's \(player.count) requests lacked the stream's headers: \(shown)")
        }
        let failed = requests.filter { $0.status == 502 }.map(\.file)
        if failed.count != failures { fail("\(movie): the addon failed \(failed) on purpose, not \(failures) request(s)") }
    }

    /// The test addon's record of the requests through its /headers/ and /hls/ routes, since it started.
    private func proxiedRequests() throws -> [ProxiedRequest] {
        guard let manifest = URL(string: try addonURL) else { throw HarnessError("TORTUGA_ADDON is not a URL") }
        let data = try Data(contentsOf: manifest.deletingLastPathComponent().appendingPathComponent("proxied.json"))
        return try JSONDecoder().decode(Proxied.self, from: data).requests
    }
}

/// test_addon.py's `/proxied.json`.
private struct Proxied: Decodable {
    let requests: [ProxiedRequest]
}

/// One request through the test addon: the file it asked for, what it sent and, for one the addon failed on purpose,
/// the status it answered.
private struct ProxiedRequest: Decodable {
    let file: String
    let testHeader: String
    let userAgent: String?
    let range: String?
    let status: Int?

    enum CodingKeys: String, CodingKey {
        case file, status
        case testHeader = "X-Tortuga-Test"
        case userAgent = "User-Agent"
        case range = "Range"
    }
}
