import XCTest

/// The playback info with the remote, on the test addon's matrix movie (`PlaybackInfoStream`): each stream in turn, from
/// a launch of its own on the movie's Streams screen (a dev start with an empty library, so each starts at its
/// beginning rather than where the stream before was left), plays, is paused by Select (the DTS file is 10 s long) and
/// has its info opened on the panel's Info tab from the options button's tab (`openPlayerInfo`): its sections, in three
/// columns, all within the screen at once, name the stream, its dynamic range and, for the remux and the DTS file, the
/// channels. Once the engine's stats are in (it reads them once a second while the info shows), the live values
/// (bitrates, frames, the cache) read and none of the rows is without its value (`requireLiveInfoValues`), the screen is
/// shot (`info-remux`, `info-dv8`, `info-dts`). On the DTS file the audio panel then opens too and its track names the
/// codec by its profile, DTS-HD MA rather than its core's DTS (`audio-dts`). Menu backs out of the panel and the player
/// to the Streams screen.
final class PlaybackInfoTests: TortugaUITestCase {
    func testInfoOfEachMatrixStream() throws {
        // Three streams, each launched, played, paused and read.
        executionTimeAllowance = 600
        for (index, stream) in PlaybackInfoStream.all.enumerated() {
            let what = "the \(stream.shot) stream"
            try launchDev(addon: true, open: [PlaybackInfoStream.streamsScreen])
            requireFocus("streams.row.0", timeout: 60, "the first stream has focus once the Streams screen lists them")
            moveFocus(to: "streams.row.\(index)", with: .down, "the remote reaches \(what)")
            press(.select)
            require("player.video", timeout: 20, "Select on \(what) opens the player")
            requirePlayback(2, timeout: 90)
            openPlayerInfo()
            requireFocus("player.panel.info", "the info of \(what) opens with focus on its tab")
            requireInfo(of: stream, "the info of \(what)")
            requireInfoSectionsOnScreen(stream, "the info of \(what)")
            requireLiveInfoValues("the info of \(what)")
            shot("info-\(stream.shot)")
            if stream.shot == "dts" { requireAudioNamesProfile() }
            leavePlayer()
            require("streams.row.0", "Menu returns to the Streams screen")
            if !waitUntil(5, { focusedId?.hasPrefix("streams.row.") == true }) {
                fail("back on the Streams screen, focus is on \(focusedId ?? "nothing"), not a stream")
            }
        }
    }

    /// From the info panel: Menu closes it, the audio button opens its panel, and a track there reads "· DTS-HD MA ·".
    private func requireAudioNamesProfile() {
        press(.menu)
        requireGone("player.info.panel", "Menu closes the playback info")
        if !element("player.scrub").exists { press(.select) }
        if require("player.scrub", timeout: 5, "the controls are up").hasFocus { press(.up) }
        moveFocus(to: "player.audio", with: .right, "the remote reaches the audio button")
        press(.select)
        require("player.menu", timeout: 5, "Select on the audio button opens the panel")
        let profile = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", " · DTS-HD MA · ")).firstMatch
        if !profile.waitForExistence(timeout: 5) { fail("the audio panel's DTS-HD MA track doesn't name its codec DTS-HD MA") }
        shot("audio-dts")
    }

    /// Menu until the player has closed: the first ones close the panel and put the controls away; each backs out of
    /// one thing.
    private func leavePlayer() {
        for _ in 0..<4 where element("player.video").exists {
            press(.menu)
            _ = waitUntil(3) { !element("player.video").exists }
        }
        requireGone("player.video", "Menu closes the player")
    }
}
