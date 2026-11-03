package com.edde746.tortuga.mpv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.mpv.core.MpvCore
import com.edde746.tortuga.mpv.video.VideoHost
import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.EngineError
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.HardwareDecoder
import com.edde746.tortuga.playback.LoadRequest
import com.edde746.tortuga.playback.PlaybackOptions
import com.edde746.tortuga.playback.PlayerEngineListener
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The desktop engine on this machine's libmpv, without a window (`vo=null`) and, but for the passthrough playback on
 * this machine's own output, without audio output (`ao=null`).
 */
class MpvPlayerEngineTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val engine = MpvPlayerEngine(MpvEngineOptions(extraOptions = mapOf("vo" to "null", "ao" to "null")), WindowlessHost)

    @AfterTest
    fun stop() {
        engine.release()
        engine.awaitReleased(10.seconds)
        server.stop(0)
    }

    /**
     * A file the server refuses fails with its status from the first error the engine publishes, even when the
     * engine's thread is busy as the refusal comes in (here a listener callback on the stats read the test asks for).
     * mpv buffers the log line naming the status ("HTTP error 404 Not Found") apart from its events and hands out the
     * end-file it explains first. The playback session decides on the first error whether a link expired.
     */
    @Test
    fun aRefusedFileFailsWithItsStatusWhileTheEngineIsBusy() {
        val asked = CountDownLatch(1)
        val answer = CountDownLatch(1)
        server.createContext("/") { exchange ->
            asked.countDown()
            answer.await(10, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        val firstError = AtomicReference<EngineError?>()
        val failed = CountDownLatch(1)
        engine.setListener(object : PlayerEngineListener {
            override fun onState(state: EngineState) {
                if (state.stats != null && answer.count > 0) {
                    // The server answers while this callback holds the engine's thread.
                    answer.countDown()
                    Thread.sleep(1_000)
                }
                state.error?.let { if (firstError.compareAndSet(null, it)) failed.countDown() }
            }
        })
        engine.startWithoutWindow()
        val url = "http://127.0.0.1:${server.address.port}/missing.mkv"
        engine.load(LoadRequest(url = url, options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.LINUX)))
        assertTrue(asked.await(10, TimeUnit.SECONDS), "mpv never asked for the file")
        engine.setStatsWanted(true)
        assertTrue(failed.await(10, TimeUnit.SECONDS), "no error; last state ${engine.currentState}")
        assertEquals(404, firstError.get()?.httpStatus, "the first error: ${firstError.get()}")
    }

    /**
     * A load starts the file's own values over (its tracks, position and length) and keeps what mpv's decoder and
     * outputs reported, here the audio, until mpv reports the next file's: mpv sends a property only when it differs
     * from what it sent last, so a next episode's identical value wouldn't come again.
     */
    @Test
    fun aLoadStartsTheFilesOwnValuesOverAndKeepsWhatTheDecoderReported() {
        val file = silentAc3()
        try {
            val reports = CopyOnWriteArrayList<EngineState>()
            val playing = CountDownLatch(1)
            val failed = CountDownLatch(1)
            engine.setListener(object : PlayerEngineListener {
                override fun onState(state: EngineState) {
                    reports += state
                    if (state.loaded && state.audio != null && state.tracks.isNotEmpty()) playing.countDown()
                    if (state.error != null) failed.countDown()
                }
            })
            engine.startWithoutWindow()
            engine.load(LoadRequest(url = file.toString(), options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.LINUX)))
            assertTrue(playing.await(20, TimeUnit.SECONDS), "never played: ${engine.currentState}")
            val before = reports.size
            engine.load(LoadRequest(url = "$file.missing", options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.LINUX)))
            assertTrue(failed.await(20, TimeUnit.SECONDS), "no error; last state ${engine.currentState}")
            val first = reports.drop(before).first { !it.loaded }
            assertEquals(emptyList(), first.tracks, "the load's first report: $first")
            assertNotNull(first.audio, "the load's first report: $first")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /**
     * The display matching's hold while the display switches mode reports buffering only for a file that plays: never
     * over a failure, which the player shows (and a watch-together room must not wait for).
     */
    @Test
    fun aDisplayHoldOverAFailedFileIsNoBuffering() {
        val failed = CountDownLatch(1)
        engine.setListener(object : PlayerEngineListener {
            override fun onState(state: EngineState) {
                if (state.error != null) failed.countDown()
            }
        })
        engine.startWithoutWindow()
        val missing = Files.createTempDirectory("missing").resolve("missing.mkv")
        engine.load(LoadRequest(url = missing.toString(), options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.LINUX)))
        assertTrue(failed.await(20, TimeUnit.SECONDS), "no error; last state ${engine.currentState}")
        engine.holdForDisplay(true)
        // The mpv thread runs calls in order: once this read returns, the hold has run.
        engine.properties(emptyList())
        val s = engine.currentState
        assertNotNull(s.error, "the failure stays: $s")
        assertFalse(s.buffering, "held over a failure: $s")
    }

    /**
     * A file value mpv refuses (here a negative demuxer cache, as a broken setting would give) fails that load with
     * mpv's reason, and nothing opens: the player shows the error instead of playing the file without the value.
     */
    @Test
    fun aFileValueMpvRefusesFailsTheLoad() {
        val file = silentAc3()
        try {
            val reports = CopyOnWriteArrayList<EngineState>()
            val failed = CountDownLatch(1)
            engine.setListener(object : PlayerEngineListener {
                override fun onState(state: EngineState) {
                    reports += state
                    if (state.error != null) failed.countDown()
                }
            })
            engine.startWithoutWindow()
            val options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.LINUX).copy(demuxerMaxBytes = -1)
            engine.load(LoadRequest(url = file.toString(), options = options))
            assertTrue(failed.await(20, TimeUnit.SECONDS), "no error; last state ${engine.currentState}")
            // The mpv thread runs calls in order: once this read returns, anything the load set off has run.
            engine.properties(emptyList())
            val s = engine.currentState
            assertNotNull(s.error, "the state after the load: $s")
            assertFalse(reports.any { it.loaded }, "the file opened: ${reports.firstOrNull { it.loaded }}")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /**
     * The Passthrough setting in mpv's options: on, `audio-spdif` names every
     * format mpv wraps for a receiver and `audio-exclusive` is yes, from the core's start when set before it. Both go
     * back to mpv's defaults while the audio must be decoded (normalized, mixed down to stereo, played at another speed
     * than 1) and return after; a 5.1 mix only shapes decoded audio and leaves them. Off, mpv's defaults.
     */
    @Test
    fun passthroughSetsMpvsOptionsAndStepsAsideWhileTheAudioMustBeDecoded() {
        val on = mapOf("audio-spdif" to "ac3,eac3,dts,dts-hd,truehd", "audio-exclusive" to "yes")
        val off = mapOf("audio-spdif" to "", "audio-exclusive" to "no")
        fun options() = engine.properties(listOf("audio-spdif", "audio-exclusive"))
        engine.setAudioPassthrough(true)
        engine.startWithoutWindow()
        assertEquals(on, options(), "set before the core")
        engine.setAudioNormalization(true)
        assertEquals(off, options(), "normalized")
        engine.setAudioNormalization(false)
        assertEquals(on, options(), "normalized no more")
        engine.setAudioChannels(AudioChannels.STEREO)
        assertEquals(off, options(), "mixed down to stereo")
        engine.setAudioChannels(AudioChannels.SURROUND_51)
        assertEquals(on, options(), "mixed down to 5.1")
        engine.setSpeed(1.5)
        assertEquals(off, options(), "at 1.5×")
        engine.setSpeed(1.0)
        assertEquals(on, options(), "at 1×")
        engine.setAudioPassthrough(false)
        assertEquals(off, options(), "the setting off")
        engine.setAudioNormalization(true)
        engine.setAudioPassthrough(true)
        assertEquals(off, options(), "turned on while normalized")
        engine.setAudioNormalization(false)
        assertEquals(on, options(), "then normalized no more")
    }

    /**
     * With the setting on, a Dolby Digital track plays on this machine's own audio output, whatever that takes: mpv
     * opens the output with the bitstream where it takes one, and decodes where it refuses it (no receiver), so the
     * playback goes on either way. The track is silent, the decoded audio muted; the output and its format are printed.
     */
    @Test
    fun aDolbyTrackPlaysOnThisMachinesOutputWithPassthroughOn() {
        val file = silentAc3()
        val output = MpvPlayerEngine(MpvEngineOptions(extraOptions = mapOf("vo" to "null", "mute" to "yes")), WindowlessHost)
        try {
            val error = AtomicReference<EngineError?>()
            val played = CountDownLatch(1)
            output.setListener(object : PlayerEngineListener {
                override fun onState(state: EngineState) {
                    state.error?.let(error::set)
                    if (state.error != null || state.positionSeconds >= 2.5) played.countDown()
                }
            })
            output.setAudioPassthrough(true)
            output.startWithoutWindow()
            output.load(LoadRequest(url = file.toString(), options = PlaybackOptions.of(DeviceSettings.DEFAULT, HardwareDecoder.LINUX)))
            val reached = played.await(20, TimeUnit.SECONDS)
            val audio = output.properties(listOf("current-ao", "audio-out-params/format", "audio-spdif", "audio-exclusive"))
            println("passthrough on this machine's output: $audio, position ${output.currentState.positionSeconds} s")
            assertTrue(reached && error.get() == null, "no 2.5 s played within 20 s: error ${error.get()}, $audio, state ${output.currentState}")
            assertNotNull(audio["audio-out-params/format"], "the audio output never opened: $audio")
        } finally {
            output.release()
            output.awaitReleased(10.seconds)
            Files.deleteIfExists(file)
        }
    }

    /** A silent AC-3 file, 150 frames of 1536 samples at 48 kHz: 4.8 s. */
    private fun silentAc3(): Path {
        val frame = SILENT_AC3_FRAME.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val file = Files.createTempFile("silent", ".ac3")
        Files.write(file, ByteArray(frame.size * 150) { frame[it % frame.size] })
        return file
    }

    /** `vo=null` needs no window: nothing to attach, bind or let go of. */
    private object WindowlessHost : VideoHost {
        override val hardwareDecoder = HardwareDecoder.LINUX

        override fun attach(window: ComposeWindow, onReady: () -> Unit): Boolean {
            onReady()
            return true
        }

        override fun options(): Map<String, String> = emptyMap()

        override fun bind(core: MpvCore, onVideoReady: (error: String?) -> Unit) = onVideoReady(null)

        override fun unbind(core: MpvCore) = Unit

        override fun awaitUnbound(): Boolean = true

        override fun close() = Unit

        @Composable
        override fun Content(modifier: Modifier) = Unit
    }

    private companion object {
        /** One AC-3 frame of stereo silence at 48 kHz and 32 kb/s (FFmpeg's encoder): every frame of a silent track. */
        const val SILENT_AC3_FRAME =
            "0b773f11004043e106f00108080821010104571f3e7cf9f3e7cf9f3e7cfbfe757cf9f3e7cf9f3e7cf8ff9d5f3e7cf9f3e7cf9f3e32fede" +
                "4c99200000f1e35ad680035ac3000000078f1ad6b4001ad6180000003c78d6b5a000d6b0c0000001e3c6b5ad0006b5860000000f1e35" +
                "ad680035ac3000000078f1ad6b4001ad60e48e"
    }
}
