import XCTest

/// One stream of the test addon's matrix movie (`tortuga-matrix`, tools/media/test_addon.py) and what the playback
/// info says of it. Its Streams screen lists them in `all`'s order: the HDR10 remux (remux.mkv: HEVC Main 10, TrueHD
/// 5.1 first, subtitles), Dolby Vision 8.1 (dv-p81.mkv: AAC 2.0) and DTS-HD MA 7.1 (dts-hdma.mkv: H.264 SDR, 10 s long).
struct PlaybackInfoStream {
    /// The dev start on the movie's Streams screen.
    static let streamsScreen = "streams:movie:tortuga-matrix:tortuga-matrix"

    static let all = [
        PlaybackInfoStream(
            shot: "remux", name: "Tortuga Test HDR10 remux", videoRows: [("Dynamic range", "HDR10")], audioRows: [("Channels", "5.1")],
            subtitles: true
        ),
        PlaybackInfoStream(
            shot: "dv8", name: "Tortuga Test Dolby Vision 8", videoRows: [("Dynamic range", "Dolby Vision")], audioRows: [], subtitles: false
        ),
        PlaybackInfoStream(
            shot: "dts", name: "Tortuga Test DTS-HD MA", videoRows: [("Dynamic range", "SDR")], audioRows: [("Channels", "7.1")], subtitles: false
        ),
    ]

    /// The screenshots' name: `info-remux`.
    let shot: String
    /// What the Name row says.
    let name: String
    /// Rows of the Video and the Audio section by label, and a part of what each says.
    let videoRows: [(label: String, value: String)]
    let audioRows: [(label: String, value: String)]
    /// The file has subtitle tracks: the info has a Subtitles section.
    let subtitles: Bool

    /// The info's sections for it (`player.info.<section>`), in order.
    var sections: [String] {
        ["player.info.stream", "player.info.video", "player.info.audio"] + (subtitles ? ["player.info.subtitles"] : []) + ["player.info.network"]
    }
}

extension TortugaUITestCase {
    /// The playback info's rows `rows` say what they should (on iOS each scrolled to first, the list's rows in order).
    /// Fails with `what`.
    func requireInfoRows(_ rows: [(label: String, value: String)], _ what: String) {
        for row in rows {
            #if os(iOS)
            revealInPlayerSheet(infoRow(row.label), panel: "player.info.panel", "\(what): the \(row.label) row")
            #endif
            requireInfoRow(row.label, contains: row.value, what)
        }
    }

    /// Every row of the playback info (on iOS every row its list has made) has its value: none reads "—", as a live
    /// value does while mpv has none (right after a load or a seek, or at the file's end). Waits up to 10 s; fails with
    /// `what` and the first row still without one.
    func requireInfoValues(_ what: String) {
        let unknown = element("player.info.panel").descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'player.info.row' AND (label CONTAINS %@ OR value CONTAINS %@)", "—", "—"))
            .firstMatch
        if !waitUntil(10, { !unknown.exists }) {
            fail("\(what): the info's row \"\(unknown.exists ? fullText(of: unknown) : "?")\" has no value")
        }
    }

    /// The playback info's live values, read: the engine reads its stats once a second only while the info shows, so
    /// the rows they bring come in with its first reading after the info opened, the Stream section's Container first
    /// (near the list's top, so on iOS too where the list makes only the rows it shows); then every row has its value
    /// (`requireInfoValues`). Fails with `what`.
    func requireLiveInfoValues(_ what: String) {
        if !infoRow("Container").exists(within: 10) { fail("\(what): no row of the engine's stats (Container) after 10 s") }
        requireInfoValues(what)
    }

    #if !os(iOS)
    /// The playback info shows what it says of `stream`: its name, then its Video and Audio rows. Fails with `what`.
    func requireInfo(of stream: PlaybackInfoStream, _ what: String) {
        requireInfoRow("Name", contains: stream.name, what)
        requireInfoRows(stream.videoRows + stream.audioRows, what)
    }

    /// Each of `stream`'s sections shows whole within the window: the info needs no scrolling. Fails with `what`.
    func requireInfoSectionsOnScreen(_ stream: PlaybackInfoStream, _ what: String) {
        let window = app.windows.firstMatch.frame
        for id in stream.sections {
            let frame = require(id, timeout: 5, "\(what): the info's \(id) section").frame
            if !window.insetBy(dx: -0.5, dy: -0.5).contains(frame) {
                fail("\(what): \(id) (\(frame)) reaches past the window (\(window))")
            }
        }
    }
    #endif
}
