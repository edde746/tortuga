import XCTest

/// The playback info by touch on iOS and with the pointer on the Mac, on the test addon's matrix movie
/// (`PlaybackInfoStream`): each stream in turn, from a launch of its own on the movie's Streams screen (a dev start with
/// an empty library, so each starts at its beginning rather than where the stream before was left), plays, is paused
/// with the controls' play/pause (the DTS file is 10 s long), and has its info opened from the options menu. mpv
/// measures the video and audio bitrates over the packets its decoders take, so a pause before its first measurement
/// leaves them "—" for as long as the video stays paused: on iOS it plays 3 s, by which both are measured whatever a
/// busy simulator's decoding drops, on the Mac 2 s. Once the engine's stats are in (it reads them once a second while
/// the info shows) the live values (bitrates, frames, the cache) read and none of the rows is without its value
/// (`requireLiveInfoValues`), the info is shot as it opened (`info-remux`, `info-dv8`, `info-dts`).
/// - iOS: a sheet of sections under their headings that names the stream, its dynamic range and, for the remux and the
///   DTS file, the channels; once shot, it is scrolled down to the end of its Video section (`info-remux-video`, …)
///   and to the end of its Network section past Audio, the list's last row whole (`info-remux-more`, …). Close, then
///   the back button, return to the Streams screen.
/// - The Mac: the glass panel's sections, all within the window, name the stream, its dynamic range and, for the remux
///   and the DTS file, the channels; the window is shot. The back button returns to the Streams screen.
final class PlaybackInfoTests: TortugaUITestCase {
    func testInfoOfEachMatrixStream() throws {
        #if os(iOS)
        // Three streams, each launched, played, paused, read and shot three times: an iPad simulator decoding the 4K
        // files in software takes about six minutes.
        executionTimeAllowance = 900
        #else
        // Three streams, each launched, played, paused and read.
        executionTimeAllowance = 600
        #endif
        for (index, stream) in PlaybackInfoStream.all.enumerated() {
            let what = "the \(stream.shot) stream"
            try launchDev(addon: true, open: [PlaybackInfoStream.streamsScreen])
            activate(require("streams.row.\(index)", timeout: 60, "the Streams screen lists \(what)"))
            let video = require("player.video", timeout: 20, "\(Self.tapping) on \(what) opens the player")
            #if os(macOS)
            requirePlayback(2, timeout: 90)
            showControls(video)
            ready("player.playPause").click()
            if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Play" }) {
                fail("play/pause did not pause \(what): it reads \(element("player.playPause").label)")
            }
            showControls(video)
            #else
            requirePlayback(3, timeout: 90)
            pauseAtOnce(video, "play/pause pauses \(what)")
            #endif
            togglePlaybackInfo()
            require("player.info.panel", timeout: 5, "Playback info in the options menu opens the info of \(what)")
            #if os(macOS)
            requireInfo(of: stream, "the info of \(what)")
            requireInfoSectionsOnScreen(stream, "the info of \(what)")
            #else
            require("player.info.stream", timeout: 5, "the info of \(what) starts with its Stream section")
            #endif
            requireLiveInfoValues("the info of \(what)")
            shot("info-\(stream.shot)")
            #if os(macOS)
            showControls(video)
            ready(require("player.back", timeout: 5, "the controls offer back")).click()
            requireGone("player.video", "the back button closes the player")
            #else
            // Down the list, in its order: the stream, its video, the end of the Video section, its audio.
            requireInfoRow("Name", contains: stream.name, "the info of \(what)")
            requireInfoRows(stream.videoRows, "the info of \(what)")
            // The Video section's last row (the Audio section's Output comes after it).
            revealInPlayerSheet(infoRow("Output"), panel: "player.info.panel", "the info of \(what): its Video section's output")
            requireInfoValues("the info of \(what) down to its Video section's output")
            shot("info-\(stream.shot)-video")
            requireInfoRows(stream.audioRows, "the info of \(what)")
            // The list's end: Audio, then Subtitles where the file has them, then Network, whose last row is the
            // download speed.
            revealInPlayerSheet(infoRow("Download speed"), panel: "player.info.panel", "the info of \(what): the end of its Network section")
            require("player.info.network", timeout: 5, "the info of \(what) ends with its Network section")
            requireInfoValues("the info of \(what) down to its end")
            shot("info-\(stream.shot)-more")
            closeSheet("player.info.close", showing: "player.info.panel", "Close (an iPhone: a pull down) closes the info of \(what)")
            closePlayer(video, "the controls' back button closes the player")
            #endif
            require("streams.row.0", "the back button returns to the Streams screen")
        }
    }

    #if os(macOS)
    /// Brings the controls up, or keeps them up for a while longer, by moving the pointer over the video (a hover
    /// where the pointer already is moves nothing, so the auto-hide timer would run on).
    private func showControls(_ video: XCUIElement) {
        pointerOn(video, at: CGVector(dx: 0.3, dy: 0.4))
        pointerOn(video, at: CGVector(dx: 0.5, dy: 0.5))
        require("player.playPause", timeout: 5, "the pointer over the video shows the controls")
    }
    #else
    /// Pauses with the controls' play/pause: a tap on it while they are up pauses without waiting for them to hide
    /// first (`tapPlayerControl`), which would take the 10 s DTS file to its end. Where they are down (they hid
    /// meanwhile), a tap on the video brings them up for the next try. On a simulator busy decoding the remux's 4K HEVC
    /// in software a tap on the button can come to nothing: it is tried again, each given 3 s to show Play. Fails with
    /// `what` when four taps on it don't pause.
    private func pauseAtOnce(_ video: XCUIElement, _ what: String) {
        // Read by a snapshot: the controls can go while this waits.
        let paused = { (try? self.element("player.playPause").snapshot().label) == "Play" }
        var taps = 0
        for _ in 0..<8 where taps < 4 {
            if let frame = try? element("player.playPause").snapshot().frame {
                taps += 1
                app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: frame.midX, dy: frame.midY)).tap()
                if waitUntil(3, paused) { return }
            } else {
                ready(video).tap()
            }
        }
        fail(what)
    }
    #endif
}
