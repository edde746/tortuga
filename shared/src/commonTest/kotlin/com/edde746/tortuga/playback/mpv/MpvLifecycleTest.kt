package com.edde746.tortuga.playback.mpv

import com.edde746.tortuga.playback.AudioInfo
import com.edde746.tortuga.playback.Chapter
import com.edde746.tortuga.playback.DynamicRange
import com.edde746.tortuga.playback.EngineError
import com.edde746.tortuga.playback.EngineErrorKind
import com.edde746.tortuga.playback.PlaybackStats
import com.edde746.tortuga.playback.TrackKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One engine's mpv events and properties, in the orders mpv hands them out, as [MpvLifecycle] makes them state. */
class MpvLifecycleTest {
    private val mpv = MpvLifecycle()

    @Test
    fun aLoadIsANewFileWhoseStatesCarryItsId() {
        val first = mpv.nextLoadId()
        val second = mpv.nextLoadId()
        assertEquals(first + 1, second)
        mpv.load(first)
        assertEquals(first, mpv.state.loadId)
        assertTrue(mpv.state.buffering, "opening")
        assertFalse(mpv.state.loaded)
        mpv.startFile()
        mpv.text("track-list", TRACKS)
        mpv.double("time-pos", 12.0)
        assertEquals(first, mpv.state.loadId, "the load's file keeps its id")
    }

    @Test
    fun theFirstPlaybackRestartAfterTheFileStartedIsItsFirstFrame() {
        playing()
        assertTrue(mpv.state.loaded)
        assertFalse(mpv.state.buffering)
        assertEquals(1, mpv.state.playbackRestarts)
        assertFalse(mpv.playbackRestart(), "a seek's")
        assertEquals(2, mpv.state.playbackRestarts)
    }

    @Test
    fun aLoadDropsTheLastFilesChaptersTracksAndPosition() {
        playing()
        mpv.text("chapter-list", """[{"title":"Intro","time":0.0},{"title":"End Credits","time":1300.0}]""")
        mpv.double("duration", 1400.0)
        mpv.text("demuxer-cache-state", """{"cache-end":30.0,"eof":true,"seekable-ranges":[{"start":0.0,"end":29.0}]}""")
        mpv.stats(PlaybackStats(fileName = "e1.mkv"))
        assertEquals(2, mpv.state.chapters.size)

        mpv.load(2)
        with(mpv.state) {
            assertEquals(emptyList(), chapters, "no intro or credits of the last file on the next")
            assertEquals(emptyList(), tracks)
            assertEquals(0.0 to 0.0, positionSeconds to durationSeconds)
            assertNull(cacheEndSeconds)
            assertNull(stats)
            assertFalse(loaded)
            assertEquals(0, playbackRestarts)
        }
        mpv.startFile()
        mpv.double("duration", 1300.0)
        assertNull(mpv.state.cacheEndSeconds, "the last file's read doesn't come back with the next one's duration")
    }

    /**
     * What is read ahead ends where mpv's `cache-end` says, which counts as `time-pos` does (its `cache-duration` counts
     * from the demuxer's reader, ahead of the position), and at the file's end once the read reached it.
     */
    @Test
    fun whatIsReadAheadEndsAtTheCacheEndOrTheFilesEnd() {
        playing()
        mpv.double("time-pos", 3.0)
        mpv.text("demuxer-cache-state", """{"cache-end":12.5,"reader-pts":3.4,"cache-duration":9.1,"eof":false}""")
        assertEquals(12.5, mpv.state.cacheEndSeconds)
        mpv.text("demuxer-cache-state", """{"cache-end":19.96,"reader-pts":3.5,"cache-duration":16.46,"eof":true}""")
        assertEquals(19.96, mpv.state.cacheEndSeconds, "no duration yet")
        mpv.double("duration", 20.05)
        assertEquals(20.05, mpv.state.cacheEndSeconds, "read to the end: the file's end, not its last packet")
        mpv.text("demuxer-cache-state", """{"eof":true}""")
        assertEquals(20.05, mpv.state.cacheEndSeconds, "every packet taken at the end")
        mpv.text("demuxer-cache-state", """{"cache-end":4.0,"eof":false}""")
        assertEquals(4.0, mpv.state.cacheEndSeconds, "a seek elsewhere reads again from there")
    }

    @Test
    fun theReplacedFilesEventsSayNothingOfTheLoad() {
        playing()
        mpv.load(2)
        // mpv still plays the last file until it takes the loadfile: a seek's frame, then its end.
        assertFalse(mpv.playbackRestart())
        mpv.logLine("http: HTTP error 503 Service Unavailable", error = true)
        mpv.endFile(END_STOP, 0, "")
        mpv.endFile(END_ERROR, LOADING_FAILED, "loading failed")
        with(mpv.state) {
            assertFalse(loaded)
            assertEquals(0, playbackRestarts)
            assertNull(error, "the replaced file's end is no failure of this load")
            assertTrue(buffering, "still opening")
        }
        mpv.startFile()
        mpv.endFile(END_ERROR, LOADING_FAILED, "loading failed")
        assertEquals(EngineError("loading failed", null, EngineErrorKind.OPEN), mpv.state.error, "nothing the last file logged")
    }

    @Test
    fun aNewFileKeepsWhatTheOutputsReportAndThePlayersSettings() {
        playing()
        mpv.text("video-params", VIDEO_PARAMS)
        mpv.text("audio-params", AUDIO_PARAMS)
        mpv.text("hwdec-current", "mediacodec")
        mpv.flag("seekable", true)
        mpv.text("audio-out-params/format", "spdif-eac3")
        mpv.outputRange(DynamicRange.HDR10)
        mpv.double("speed", 1.5)
        mpv.double("volume", 80.0)
        mpv.flag("mute", true)
        val before = mpv.state

        mpv.load(2)
        mpv.startFile()
        with(mpv.state) {
            // mpv sends a property only when it differs from what it sent last: the next episode's identical video
            // params may never come again.
            assertEquals(before.video, video)
            assertEquals(AudioInfo("aac", 2, 48000), audio)
            assertEquals("mediacodec", hwdec)
            assertTrue(seekable)
            assertTrue(audioPassthrough)
            assertEquals(DynamicRange.HDR10, outputDynamicRange)
            assertEquals(Triple(1.5, 80.0, true), Triple(speed, volume, muted))
        }
        assertNotNull(before.video)
    }

    @Test
    fun theTrackListIsReadOnceForTheTracksTheVideoAndTheAudio() {
        mpv.load(1)
        mpv.startFile()
        mpv.text("video-params", VIDEO_PARAMS)
        mpv.text("audio-params", AUDIO_PARAMS)
        assertNull(mpv.state.video?.codec, "no track list yet")
        mpv.text("track-list", TRACKS)
        assertEquals(listOf(TrackKind.VIDEO, TrackKind.AUDIO), mpv.state.tracks.map { it.kind })
        assertEquals("hevc", mpv.state.video?.codec)
        assertEquals(DynamicRange.HDR10, mpv.state.video?.dynamicRange)
        assertEquals("aac", mpv.state.audio?.codec)
        assertEquals("pq", mpv.transfer)
        assertEquals(listOf(1L), mpv.videoTracks.map { it.id })
    }

    @Test
    fun aMalformedNodeChangesNothing() {
        playing()
        val tracks = mpv.state.tracks
        mpv.text("track-list", """[{"id":1""")
        assertEquals(tracks, mpv.state.tracks, "not read as no tracks")
        mpv.text("track-list", """{"id":1}""")
        assertEquals(tracks, mpv.state.tracks, "an object where mpv prints a list")
        mpv.unavailable("track-list")
        assertEquals(emptyList(), mpv.state.tracks, "unavailable: none")
    }

    @Test
    fun aPropertyTheLifecycleDoesntMapOrInAnotherFormatFails() {
        assertFailsWith<IllegalArgumentException> { mpv.text("current-vo", "gpu") }
        assertFailsWith<IllegalArgumentException> { mpv.double("track-list", 1.0) }
        assertFailsWith<IllegalArgumentException> { mpv.unavailable("dwidth") }
    }

    @Test
    fun anErrorEndNamesTheFailureByTheLogAndMpvsCode() {
        mpv.load(1)
        mpv.startFile()
        mpv.logLine("http: HTTP error 404 Not Found", error = false)
        mpv.logLine("Failed to open https://cdn.example/dl/0123456789abcdef0123456789abcdef/a.mkv?token=s3cr3t.", error = true)
        mpv.endFile(END_ERROR, LOADING_FAILED, "loading failed")
        val error = assertNotNull(mpv.state.error)
        assertEquals(404 to EngineErrorKind.OPEN, error.httpStatus to error.kind)
        assertFalse("s3cr3t" in error.message, error.message)
        assertTrue(error.message.startsWith("Failed to open"), error.message)
        assertFalse(mpv.state.buffering)
        assertFalse(mpv.state.loaded)
    }

    @Test
    fun aStatusLoggedAfterTheErrorEndGoesToThatError() {
        mpv.load(1)
        mpv.startFile()
        // mpv hands its client queued events before its log lines.
        mpv.endFile(END_ERROR, LOADING_FAILED, "loading failed")
        mpv.logLine("http: HTTP error 403 Forbidden", error = false)
        assertEquals(403, mpv.state.error?.httpStatus)
    }

    @Test
    fun aStatusAFileLoggedDoesntLabelTheFileItsPlaylistOpensNext() {
        mpv.load(1)
        mpv.startFile()
        mpv.logLine("http: HTTP error 502 Bad Gateway", error = false)
        // The HLS open failed: mpv falls back to the playlist's entries, which it opens as files of their own.
        mpv.endFile(END_REDIRECT, 0, "")
        assertTrue(mpv.state.buffering, "a redirect goes on to the entries")
        mpv.startFile()
        mpv.endFile(END_ERROR, UNKNOWN_FORMAT, "unrecognized file format")
        assertEquals(EngineError("unrecognized file format", null, EngineErrorKind.FORMAT), mpv.state.error)
    }

    /**
     * FFmpeg's HTTP error for the stream (here a reconnect's, after the connection closed early) stays in the state
     * through the file's end, which FFmpeg makes of the failed read; an external subtitle that failed to open logged
     * its own, which isn't the stream's. A new file starts without one.
     */
    @Test
    fun theStreamsHttpErrorStaysWithTheFileAndASubtitlesIsntIt() {
        mpv.load(1)
        mpv.startFile()
        mpv.playbackRestart()
        mpv.logLine("http: HTTP error 404 File not found", error = false)
        mpv.logLine("Failed to open https://subs.example/a.srt.", error = true)
        mpv.logLine("Can not open external file https://subs.example/a.srt.", error = true)
        assertNull(mpv.state.httpErrorStatus, "the subtitle's 404")
        mpv.logLine("http: Stream ends prematurely at 565588, should be 1155664", error = false)
        mpv.logLine("http: Will reconnect at 565588 in 0 second(s), error=Input/output error.", error = false)
        mpv.logLine("http: HTTP error 403 Link expired", error = false)
        mpv.logLine("http: HTTP error 404 File not found", error = false)
        mpv.logLine("Can not open external file https://subs.example/b.srt.", error = true)
        mpv.flag("eof-reached", true)
        assertEquals(403, mpv.state.httpErrorStatus, "the stream's, once the second subtitle's is taken back")
        assertTrue(mpv.state.eof)
        mpv.load(2)
        assertNull(mpv.state.httpErrorStatus)
    }

    @Test
    fun theEndKeepsTheFileLoaded() {
        playing()
        mpv.flag("eof-reached", true)
        assertTrue(mpv.state.eof)
        assertTrue(mpv.state.loaded)
        mpv.endFile(END_EOF, 0, "")
        assertTrue(mpv.state.loaded)
        assertFalse(mpv.state.buffering)
    }

    @Test
    fun aHoldBuffersWithTheViewersPauseButNeverOverAFailureOrTheEnd() {
        playing()
        mpv.flag("pause", false)
        mpv.hold(viewerPaused = false)
        mpv.flag("pause", true)
        assertTrue(mpv.state.buffering, "the display switches")
        assertFalse(mpv.state.paused, "mpv's pause is the hold's, not the viewer's")
        mpv.hold(viewerPaused = true)
        assertTrue(mpv.state.paused)
        mpv.endHold(paused = true)
        assertFalse(mpv.state.buffering)
        assertTrue(mpv.state.paused)

        mpv.hold(viewerPaused = false)
        mpv.flag("eof-reached", true)
        assertFalse(mpv.state.buffering, "at the end")
        mpv.flag("eof-reached", false)
        mpv.fail(EngineError("Video decoding stalled", kind = EngineErrorKind.DECODE))
        assertFalse(mpv.state.buffering, "with an error")
    }

    @Test
    fun anUnavailableOrEndlessValueIsNone() {
        playing()
        mpv.double("time-pos", 10.0)
        mpv.double("speed", 2.0)
        mpv.flag("mute", true)
        mpv.double("time-pos", Double.NaN)
        assertEquals(0.0, mpv.state.positionSeconds)
        mpv.double("duration", Double.POSITIVE_INFINITY)
        assertEquals(0.0, mpv.state.durationSeconds)
        // The player's settings: mpv always has one, so none keeps the last.
        mpv.unavailable("speed")
        mpv.unavailable("mute")
        assertEquals(2.0 to true, mpv.state.speed to mpv.state.muted)
        mpv.unavailable("seekable")
        assertFalse(mpv.state.seekable)
    }

    @Test
    fun aFloatOptionComesBackAsTheDecimalThatWasSet() {
        mpv.double("sub-delay", (-0.1f).toDouble())
        mpv.double("audio-delay", 0.25f.toDouble())
        mpv.double("volume", 133.3f.toDouble())
        assertEquals(Triple(-0.1, 0.25, 133.3), Triple(mpv.state.subtitleDelaySeconds, mpv.state.audioDelaySeconds, mpv.state.volume))
        mpv.double("speed", 1.1)
        assertEquals(1.1, mpv.state.speed)
    }

    @Test
    fun aStatsReadingWhileTheLoadWaitsForItsFileIsTheReplacedFiles() {
        playing()
        mpv.load(2)
        mpv.stats(PlaybackStats(fileName = "e1.mkv"))
        assertNull(mpv.state.stats)
        mpv.startFile()
        mpv.stats(PlaybackStats(fileName = "e2.mkv"))
        assertEquals("e2.mkv", mpv.state.stats?.fileName)
        mpv.stats(null)
        assertNull(mpv.state.stats)
    }

    @Test
    fun thePositionAloneGoesOutEveryQuarterSecondOfVideo() {
        playing()
        assertNotNull(mpv.publishable())
        assertNull(mpv.publishable(), "nothing new")
        mpv.double("time-pos", 0.2)
        assertNull(mpv.publishable())
        mpv.double("time-pos", 0.3)
        assertEquals(0.3, mpv.publishable()?.positionSeconds)
        mpv.double("time-pos", 0.4)
        mpv.text("chapter-list", """[{"title":"Intro","time":0.0}]""")
        assertEquals(0.4, mpv.publishable()?.positionSeconds, "anything else goes at once, with the position as it is")
        mpv.republish()
        assertNotNull(mpv.publishable(), "a new listener gets the state")
    }

    /** A file loaded and playing: its first frame is up. */
    private fun playing() {
        mpv.load(1)
        mpv.startFile()
        mpv.text("track-list", TRACKS)
        mpv.fileLoaded()
        assertTrue(mpv.playbackRestart())
    }

    private companion object {
        const val END_EOF = 0
        const val END_STOP = 2
        const val END_ERROR = 4
        const val END_REDIRECT = 5
        const val LOADING_FAILED = -13
        const val UNKNOWN_FORMAT = -17

        const val TRACKS =
            """[{"id":1,"type":"video","selected":true,"codec":"hevc"},{"id":1,"type":"audio","selected":true,"codec":"aac"}]"""
        const val VIDEO_PARAMS = """{"pixelformat":"yuv420p10","w":3840,"h":2160,"gamma":"pq"}"""
        const val AUDIO_PARAMS = """{"format":"floatp","samplerate":48000,"channel-count":2}"""
    }
}
