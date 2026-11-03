package com.edde746.tortuga.mpv

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.edde746.mpv.MediaCodecQuery
import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.playback.ANDROID_LOUDNESS_NORMALIZATION_FILTER
import com.edde746.tortuga.playback.EngineError
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.HardwareDecoder
import com.edde746.tortuga.playback.LoadRequest
import com.edde746.tortuga.playback.PlaybackOptions
import com.edde746.tortuga.playback.PlayerEngineListener
import com.edde746.tortuga.playback.TrackKind
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The engine against real libmpv on a device (an instrumented test): the PlayerEngine v2
 * controls must come back from mpv as state, clamped to their ranges, a file the server refuses must fail with its
 * HTTP status (on each of two engines open at once too), a load on an mpv that didn't start or with a file option mpv
 * refuses must fail with the reason, a Surface destroyed after the release must be let go of without waiting for
 * mpv's termination, loudness normalization must filter every channel of a 5.1 track with passthrough out of its way,
 * and a speed other than 1 must decode a track that passthrough sends undecoded. A load must start the next file
 * without the last one's tracks or length, a display hold must not report a failed file as buffering, and a load's
 * values must hold on the files mpv opens from its playlist. MPEG-2 must decode in hardware where the device has a
 * decoder for it, heavy AV1 must keep up in hardware and stay in hardware across Surface hand-offs, and a live stream
 * with late segments must play without stalling.
 * Plays the sample without a host view (on the placeholder surface, or on a test Surface) and without audio
 * output. The `mediaUrl` instrumentation argument overrides the sample URL; the matrix files and the live stream are
 * on the same server.
 */
class MpvPlayerEngineDeviceTest {
    private lateinit var engine: MpvPlayerEngine
    private lateinit var url: String
    private val latest = AtomicReference(EngineState())
    private val firstError = AtomicReference<EngineError?>()
    private val lock = Object()

    /** What the engine's events go to (its `events`), set by a test. */
    private val events = AtomicReference<((String) -> Unit)?>()

    /** Where the engine's reports also go, set by a test. */
    private val reports = AtomicReference<MutableList<EngineState>?>()

    @Before
    fun start() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        url = InstrumentationRegistry.getArguments().getString("mediaUrl") ?: "http://127.0.0.1:8765/sample.mkv"
        engine = MpvPlayerEngine(instrumentation.targetContext, mapOf("ao" to "null")) { events.get()?.invoke(it) }
        engine.setListener(object : PlayerEngineListener {
            override fun onState(state: EngineState) {
                state.error?.let { firstError.compareAndSet(null, it) }
                reports.get()?.add(state)
                latest.set(state)
                synchronized(lock) { lock.notifyAll() }
            }
        })
        engine.load(LoadRequest(url = url, options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.ANDROID)))
        await("the file to load") { it.loaded && it.positionSeconds > 0.5 }
    }

    @After
    fun stop() {
        events.set(null)
        engine.release()
    }

    @Test
    fun controlsComeBackAsStateWithinTheirRanges() {
        engine.setSpeed(1.5)
        engine.setSubtitleDelay(0.25)
        engine.setAudioDelay(-0.1)
        engine.setVolume(200.0)
        engine.setMuted(true)
        val s = await("the controls to apply") { it.speed == 1.5 && it.muted && it.volume == 130.0 }
        assertEquals(0.25, s.subtitleDelaySeconds, 1e-9)
        assertEquals(-0.1, s.audioDelaySeconds, 1e-9)

        engine.setSpeed(0.0)
        engine.setVolume(-5.0)
        engine.setMuted(false)
        await("the lower bounds") { it.speed == 0.01 && it.volume == 0.0 && !it.muted }
    }

    @Test
    fun mediaInfoDescribesTheSample() {
        val s = await("video and audio info") { it.video != null && it.audio != null && it.hwdec != null }
        val video = assertNotNull(s.video)
        assertEquals(1920 to 1080, video.width to video.height)
        assertEquals("hevc", video.codec)
        val audio = assertNotNull(s.audio)
        assertEquals("aac", audio.codec)
        assertEquals(2, audio.channels)
        assertEquals(48_000, audio.sampleRate)
        val subtitle = s.tracks.single { it.kind == TrackKind.SUBTITLE }
        assertEquals("ass", subtitle.codec)
        assertEquals(2, s.tracks.single { it.kind == TrackKind.AUDIO }.channels)
    }

    /**
     * A file the server answers 404 for fails with that status from the first error the engine publishes: mpv's log
     * line naming it ("HTTP error 404 Not Found") reaches the engine before the end-file it explains. The playback
     * session decides on the first error whether a link expired (ExpiryPolicy), and the player words it by the status.
     */
    @Test
    fun aFileTheServerRefusesFailsWithItsStatusAtOnce() {
        engine.load(LoadRequest(url = "$url.missing", options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.ANDROID)))
        val error = awaitError(firstError)
        assertEquals(404, error.httpStatus, "the first error: $error")
        assertTrue(error.message.isNotBlank(), "the first error: $error")
    }

    /**
     * A load starts the next file over from its first report: without the last file's tracks, position or length,
     * which mpv reports for the next file as it opens it.
     */
    @Test
    fun aLoadStartsTheNextFileWithoutTheLastOnesTracksOrLength() {
        await("the sample's tracks and length") { it.tracks.isNotEmpty() && it.durationSeconds > 0 }
        val after = CopyOnWriteArrayList<EngineState>()
        reports.set(after)
        engine.load(LoadRequest(url = "$url.missing", options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.ANDROID)))
        awaitError(firstError)
        val first = after.first { !it.loaded }
        assertEquals(emptyList(), first.tracks, "the load's first report: $first")
        assertEquals(0.0 to 0.0, first.positionSeconds to first.durationSeconds, "the load's first report: $first")
    }

    /**
     * The output's hold while the display switches mode reports buffering only for a file that plays: never over a
     * failure, which the player shows (and a watch-together room must not wait for).
     */
    @Test
    fun aDisplayHoldOverAFailedFileIsNoBuffering() {
        engine.load(LoadRequest(url = "$url.missing", options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.ANDROID)))
        awaitError(firstError)
        engine.holdForDisplay(true)
        // The executor is serial: once this read returns, the hold has run.
        engine.properties(emptyList(), timeoutMs = 10_000)
        val s = engine.currentState
        assertNotNull(s.error, "the failure stays: $s")
        assertFalse(s.buffering, "held over a failure: $s")
    }

    /**
     * A load's values hold on the files mpv opens from its playlist, as an HLS open that fails falls back to the
     * playlist's entries: here a playlist naming the sample, which starts where the load asked. mpv drops a file's
     * per-file options on those files, which would start at the beginning.
     */
    @Test
    fun aFileAPlaylistOpensStartsWhereTheLoadAsked() {
        val playlist = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "start.m3u")
        playlist.writeText("#EXTM3U\n$url\n")
        try {
            val after = CopyOnWriteArrayList<EngineState>()
            reports.set(after)
            engine.load(LoadRequest(url = playlist.absolutePath, options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.ANDROID), startSeconds = 20.0))
            // The load's own reports start at its first, which no longer has the sample loaded.
            fun playing() = after.dropWhile { it.loaded }.firstOrNull { it.loaded && it.positionSeconds > 0 }
            await("the playlist's file to play") { playing() != null }
            val first = assertNotNull(playing())
            assertTrue(first.positionSeconds >= 19.0, "the playlist's file's first position: $first")
        } finally {
            playlist.delete()
        }
    }

    /**
     * Two engines open at once (the next player starting while the last one's mpv still terminates, which can take
     * seconds) each fail with their own file's status. FFmpeg has one log callback per process: libmpv sends each line
     * to the mpv whose thread logged it (mpv-build's patch 0209). Before, every line went to the first mpv alive, and
     * once it was gone to none, so the engine started beside it never got a status.
     */
    @Test
    fun twoEnginesOpenAtOnceEachFailWithTheirOwnStatus() {
        val beside = MpvPlayerEngine(InstrumentationRegistry.getInstrumentation().targetContext, mapOf("ao" to "null"))
        val besideError = AtomicReference<EngineError?>()
        beside.setListener(object : PlayerEngineListener {
            override fun onState(state: EngineState) {
                state.error?.let { besideError.compareAndSet(null, it) }
                synchronized(lock) { lock.notifyAll() }
            }
        })
        try {
            val options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.ANDROID)
            beside.load(LoadRequest(url = "$url.missing", options = options))
            val second = awaitError(besideError)
            engine.load(LoadRequest(url = "$url.missing", options = options))
            val first = awaitError(firstError)
            assertEquals(404 to 404, first.httpStatus to second.httpStatus, "the errors: $first (first engine), $second (second engine)")
        } finally {
            beside.release()
        }
    }

    /**
     * An mpv that doesn't start (here an option it refuses; on a device, EGL or mpv's own start failing) fails every
     * load with that reason: the player shows the error instead of buffering for good.
     */
    @Test
    fun aLoadOnAnMpvThatDidNotStartFailsWithTheReason() {
        val broken = MpvPlayerEngine(InstrumentationRegistry.getInstrumentation().targetContext, mapOf("ao" to "null", "no-such-option" to "yes"))
        try {
            broken.load(LoadRequest(url = url, options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.ANDROID)))
            // The executor is serial: once this read returns, the load has run.
            broken.properties(emptyList(), timeoutMs = 10_000)
            val s = broken.currentState
            val error = assertNotNull(s.error, "the state after the load: $s")
            assertTrue(error.message.startsWith("mpv initialization failed"), "the error: $error")
            assertTrue(!s.buffering, "the state after the load: $s")
        } finally {
            broken.release()
        }
    }

    /**
     * A file option mpv refuses (here a negative demuxer cache, as a broken setting would give) fails that load with
     * mpv's reason at once: the player shows the error instead of buffering for good.
     */
    @Test
    fun aFileOptionMpvRefusesFailsTheLoad() {
        val options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.ANDROID).copy(demuxerMaxBytes = -1)
        engine.load(LoadRequest(url = url, options = options))
        val error = awaitError(firstError)
        assertTrue(error.message.isNotBlank(), "the first error: $error")
        val s = latest.get()
        assertTrue(!s.buffering && !s.loaded, "the state after the error: $s")
    }

    /**
     * Leaving the player releases the engine, then destroys its Surfaces on the main thread. The termination after the
     * release can take seconds (a decoder draining at the end of a paused file); held here, the Surface's destruction
     * still returns at once, and nothing reaches that Surface afterwards.
     */
    @Test
    fun aSurfaceDestroyedAfterTheReleaseIsLetGoWithoutWaitingForTheTermination() {
        val consumer = HandlerThread("test-surface").apply { start() }
        val reader = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 3)
        val frames = AtomicInteger()
        val firstFrames = CountDownLatch(5)
        reader.setOnImageAvailableListener({
            it.acquireLatestImage()?.close()
            frames.incrementAndGet()
            firstFrames.countDown()
        }, Handler(consumer.looper))
        val terminating = CountDownLatch(1)
        val terminate = CountDownLatch(1)
        try {
            engine.attachSurface(reader.surface)
            assertTrue(firstFrames.await(10, TimeUnit.SECONDS), "only ${frames.get()} frames reached the test Surface")
            events.set {
                if (it == "terminate") {
                    terminating.countDown()
                    terminate.await(10, TimeUnit.SECONDS)
                }
            }
            engine.release()
            val start = System.nanoTime()
            engine.detachSurface()
            val heldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue(heldMs < 1_000, "surfaceDestroyed held its thread ${heldMs}ms")
            assertTrue(terminating.await(5, TimeUnit.SECONDS), "the termination never started")
            Thread.sleep(100)
            val left = frames.get()
            Thread.sleep(1_000)
            assertEquals(left, frames.get(), "frames reached the Surface after it was let go")
        } finally {
            terminate.countDown()
            reader.close()
            consumer.quitSafely()
        }
    }

    /**
     * Normalization is [ANDROID_LOUDNESS_NORMALIZATION_FILTER] in mpv's `af`, and passthrough waits for it: a filter
     * can't process a bitstream. On a 5.1 TrueHD track, with the route taking TrueHD undecoded, the audio is decoded
     * and filtered on all 6 channels while normalization is on; turned off during the playback, `af` is empty and the
     * track goes out undecoded again; on again, it is decoded and filtered again. The audio output stays open
     * throughout.
     */
    @Test
    fun normalizationFiltersEveryChannelWithPassthroughOutOfItsWay() {
        engine.setAudioNormalization(true)
        engine.setAudioPassthrough(listOf("truehd"))
        val options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.ANDROID)
        engine.load(LoadRequest(url = URI(url).resolve(SURROUND_PATH).toString(), options = options))
        await("the 5.1 file to play") { it.loaded && it.positionSeconds > 0.5 && it.audio?.channels == 6 }
        assertFilteredOnEveryChannel("normalization on at the start")

        engine.setAudioNormalization(false)
        await("the track to go out undecoded") { it.audioPassthrough }
        val off = engine.properties(listOf("af", "audio-spdif"))
        assertEquals(mapOf("af" to "", "audio-spdif" to "truehd"), off, "normalization off")

        engine.setAudioNormalization(true)
        await("the track to be decoded again") { !it.audioPassthrough }
        assertFilteredOnEveryChannel("normalization on again")
    }

    /**
     * mpv can't change the tempo of a bitstream, so passthrough waits for the speed to be 1. On a 5.1 TrueHD track,
     * with the route taking TrueHD undecoded, the track goes out undecoded at 1×; at 1.5× `audio-spdif` is empty and
     * the audio output plays decoded PCM; back at 1× the track goes out undecoded again.
     */
    @Test
    fun aSpeedOtherThanOneDecodesWithPassthroughOutOfItsWay() {
        engine.setAudioPassthrough(listOf("truehd"))
        val options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.ANDROID)
        engine.load(LoadRequest(url = URI(url).resolve(SURROUND_PATH).toString(), options = options))
        await("the 5.1 file to go out undecoded") { it.loaded && it.positionSeconds > 0.5 && it.audioPassthrough }

        engine.setSpeed(1.5)
        await("the speed of 1.5") { it.speed == 1.5 }
        val faster = awaitDecodedOutput("at 1.5×", listOf("audio-spdif"))
        assertEquals("", faster["audio-spdif"], "at 1.5×: audio-spdif ($faster)")

        engine.setSpeed(1.0)
        await("the track to go out undecoded again at 1×") { it.speed == 1.0 && it.audioPassthrough }
        assertEquals(mapOf("audio-spdif" to "truehd"), engine.properties(listOf("audio-spdif")), "back at 1×")
    }

    /** MPEG-2 (broadcast HD, a DVD remux) decodes in hardware on a device with an MPEG-2 decoder. */
    @Test
    fun mpeg2DecodesInHardware() {
        assumeTrue("no hardware MPEG-2 decoder", MediaCodecQuery.findHardwareDecoder("video/mpeg2") != null)
        load(MPEG2_PATH)
        val s = await("MPEG-2 to play") { it.loaded && it.positionSeconds > 1.0 && it.hwdec != null }
        assertEquals("mediacodec", s.hwdec, "hwdec-current")
    }

    /**
     * About 30 Mb/s of AV1 film grain at 1080p24 keeps up on a hardware AV1 decoder: over 15 s of playback mpv drops
     * or shows late at most one frame in twenty. Tensor's decoder, given no operating rate, ran it at 5-8 fps.
     */
    @Test
    fun heavyAv1KeepsUpInHardware() {
        assumeTrue("no hardware AV1 decoder", MediaCodecQuery.hardwareAv1)
        load(AV1_GRAIN_PATH)
        await("the AV1 file to play in hardware", timeoutSeconds = 30) { it.loaded && it.positionSeconds > 2.0 && it.hwdec == "mediacodec" }
        val before = dropped()
        val start = latest.get().positionSeconds
        await("15 s of playback", timeoutSeconds = 40) { it.positionSeconds > start + 15.0 }
        val lost = dropped() - before
        Log.i(TAG, "heavy AV1: $lost frames dropped or late in 15 s (${engine.properties(DROP_COUNTERS)})")
        assertTrue(lost <= 15.0 * 24 / 20, "$lost of 360 frames dropped or late")
    }

    /**
     * Hardware AV1 survives the video Surface coming and going (Home and back, a lock): each hand-off rebuilds the video
     * output, and the file goes on decoding in hardware. On a Pixel's BigOcean decoder an unparked rebuild killed the
     * codec service and left software decoding (or a green line).
     */
    @Test
    fun hardwareAv1SurvivesSurfaceHandOffs() {
        assumeTrue("no hardware AV1 decoder", MediaCodecQuery.hardwareAv1)
        val consumer = HandlerThread("test-surface").apply { start() }
        val reader = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 3)
        val frames = AtomicInteger()
        reader.setOnImageAvailableListener({
            it.acquireLatestImage()?.close()
            frames.incrementAndGet()
        }, Handler(consumer.looper))
        try {
            load(AV1_GRAIN_PATH)
            await("the AV1 file to play in hardware", timeoutSeconds = 30) { it.loaded && it.positionSeconds > 1.0 && it.hwdec == "mediacodec" }
            repeat(HAND_OFFS) { round ->
                engine.attachSurface(reader.surface)
                val shown = frames.get()
                val at = latest.get().positionSeconds
                await("hand-off ${round + 1}: playing on the test Surface", timeoutSeconds = 15) { it.positionSeconds > at + 1.0 && frames.get() > shown }
                engine.detachSurface()
                Thread.sleep(500)
            }
            engine.attachSurface(reader.surface)
            val at = latest.get().positionSeconds
            val s = await("playback after $HAND_OFFS hand-offs", timeoutSeconds = 15) { it.positionSeconds > at + 2.0 }
            assertEquals("mediacodec", s.hwdec, "hwdec-current after $HAND_OFFS hand-offs")
        } finally {
            engine.detachSurface()
            reader.close()
            consumer.quitSafely()
        }
    }

    /**
     * A live stream whose origin answers every third 2-s segment 3 s late, opened at its newest segment: as a live file
     * ([LoadRequest.live]) it starts with seconds buffered and plays 40 s without a stall. The same stream opened as a
     * plain file is logged for comparison.
     */
    @Test
    fun aLiveStreamWithLateSegmentsPlaysWithoutStalling() {
        val live = URI(url).resolve(LATE_LIVE_PATH).toString()
        Log.i(TAG, "late live, opened as a plain file: ${stalls(live, asLive = false)} stalls in 40 s")
        val stalls = stalls(live, asLive = true)
        Log.i(TAG, "late live, opened as live: $stalls stalls in 40 s")
        assertEquals(0, stalls, "stalls in 40 s of the live stream")
    }

    /** How often [live] stalls (a buffering report while it plays) in 40 s after its first frame. */
    private fun stalls(live: String, asLive: Boolean): Int {
        val seen = CopyOnWriteArrayList<EngineState>()
        reports.set(seen)
        val options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.ANDROID)
        engine.load(LoadRequest(url = live, options = options, liveStartIndex = -1, live = asLive))
        val first = await("the live stream to play", timeoutSeconds = 30) { it.loaded && !it.buffering && it.positionSeconds > 0 }
        val start = seen.indexOfFirst { it === first }.coerceAtLeast(0)
        Thread.sleep(40_000)
        reports.set(null)
        return seen.drop(start).zipWithNext().count { (a, b) -> !a.buffering && b.buffering }
    }

    private fun load(path: String) {
        engine.load(LoadRequest(url = URI(url).resolve(path).toString(), options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.ANDROID)))
    }

    /** Frames mpv dropped or showed late so far ([DROP_COUNTERS], summed). */
    private fun dropped(): Double = engine.properties(DROP_COUNTERS).values.sumOf { it?.toDoubleOrNull() ?: 0.0 }

    /** `af` holds the chain, `audio-spdif` is empty and the audio output plays decoded PCM on 6 channels. */
    private fun assertFilteredOnEveryChannel(what: String) {
        val p = awaitDecodedOutput(what, listOf("af", "audio-spdif"))
        assertEquals(ANDROID_LOUDNESS_NORMALIZATION_FILTER, p["af"], "$what: af")
        assertEquals("", p["audio-spdif"], "$what: audio-spdif")
        assertEquals("6", p["audio-out-params/channel-count"], "$what: the audio output's channels ($p)")
    }

    /** Waits for the audio output to play decoded PCM; then [names] and the output's format and channels, as read. */
    private fun awaitDecodedOutput(what: String, names: List<String>): Map<String, String?> {
        val read = names + listOf("audio-out-params/format", "audio-out-params/channel-count")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var p = engine.properties(read)
        // The output reopens for the decoded audio; until then mpv reports none (or the bitstream's).
        while (p["audio-out-params/channel-count"] == null || p["audio-out-params/format"]?.startsWith("spdif") != false) {
            if (System.nanoTime() > deadline) fail("$what: timed out waiting for a decoded audio output; last properties $p")
            Thread.sleep(100)
            p = engine.properties(read)
        }
        return p
    }

    private fun awaitError(error: AtomicReference<EngineError?>): EngineError {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        synchronized(lock) {
            while (true) {
                error.get()?.let { return it }
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) fail("Timed out waiting for the error; last state ${latest.get()}")
                lock.wait(left)
            }
        }
    }

    private fun await(what: String, timeoutSeconds: Long = 20, predicate: (EngineState) -> Boolean): EngineState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        synchronized(lock) {
            while (true) {
                val s = latest.get()
                if (predicate(s)) return s
                s.error?.let { fail("Playback error while waiting for $what: $it") }
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) fail("Timed out waiting for $what; last state $s")
                lock.wait(left)
            }
        }
    }

    private companion object {
        const val TAG = "MpvPlayerEngineDeviceTest"

        /** Dolby TrueHD 5.1 (h264 video, 20 s), from the media matrix (tools/media/matrix.json, `truehd`). */
        const val SURROUND_PATH = "/matrix/truehd.mkv"

        /** MPEG-2 Main 1080p24 (20 s) and AV1 at about 30 Mb/s of film grain (1080p24, 30 s), from the media matrix. */
        const val MPEG2_PATH = "/matrix/mpeg2.mkv"
        const val AV1_GRAIN_PATH = "/matrix/av1-grain.mkv"

        /** The media server's live HLS (h264-sdr's 2-s segments, 12 s of them listed), every third segment 3 s late. */
        const val LATE_LIVE_PATH = "/matrix/h264-sdr.live.m3u8?late=3"

        /** mpv's counts of frames dropped (by the vo, by the decoder) and shown late. */
        val DROP_COUNTERS = listOf("frame-drop-count", "decoder-frame-drop-count", "vo-delayed-frame-count")

        /** How many times the Surface comes and goes in [hardwareAv1SurvivesSurfaceHandOffs]. */
        const val HAND_OFFS = 5
    }
}
