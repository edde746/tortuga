package com.edde746.tortuga.watch.e2e

import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.EngineError
import com.edde746.tortuga.playback.EngineErrorKind
import com.edde746.tortuga.playback.EngineState
import com.edde746.tortuga.playback.LoadRequest
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.playback.PlayerEngineListener
import com.edde746.tortuga.playback.SubtitleStyle
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.VideoLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A player that plays in real time, as mpv does towards the app: a file opens [openDelay] after its load (its duration
 * and whether it can seek from [fileOf] by URL; an unknown URL fails as a 404), keeps `pause` across files, moves its
 * position at its speed while not paused, counts a playback restart when a file opens and after each seek it can make,
 * and reaches the end of the file. It reports its state every [TICK] while a file is open. Called on its app's main
 * thread ([scope]); the test thread reads [positionAt] and the recorded history, and [fail]s a file.
 */
internal class SimulatedEngine(
    private val fileOf: (url: String) -> MediaFile?,
    private val scope: CoroutineScope,
    private val openDelay: Duration,
) : PlayerEngine {
    /** Where the position was at [anchorNanos] and how it moves from there. */
    private data class Motion(val anchorSeconds: Double, val anchorNanos: Long, val running: Boolean, val speed: Double, val durationSeconds: Double)

    @Volatile private var motion = Motion(0.0, System.nanoTime(), running = false, speed = 1.0, durationSeconds = 0.0)

    @Volatile var state = EngineState()
        private set

    /** Every URL loaded, in order. */
    val loads = CopyOnWriteArrayList<String>()

    /** When the position started moving each time ([System.nanoTime]), with the file playing. */
    val starts = CopyOnWriteArrayList<Pair<String, Long>>()

    /** When each file opened ([System.nanoTime]). */
    val opens = CopyOnWriteArrayList<Pair<String, Long>>()

    /** Every seek asked for, in seconds, those a file that can't seek refuses included. */
    val seeks = CopyOnWriteArrayList<Double>()

    /** The file loaded last. */
    @Volatile var url: String? = null
        private set

    @Volatile var released = false
        private set

    private var listener: PlayerEngineListener? = null
    private var opening: Job? = null
    private var ticker: Job? = null

    /** The position at [nanos] ([System.nanoTime]), as the player would show it then. */
    fun positionAt(nanos: Long = System.nanoTime()): Double {
        val m = motion
        if (!m.running) return m.anchorSeconds
        val moved = m.anchorSeconds + (nanos - m.anchorNanos) / 1e9 * m.speed
        return if (m.durationSeconds > 0) minOf(moved, m.durationSeconds) else moved
    }

    override fun load(request: LoadRequest): Long {
        loads += request.url
        url = request.url
        opening?.cancel()
        move(request.startSeconds, running = false, durationSeconds = 0.0)
        val id = loads.size.toLong()
        report {
            it.copy(
                loadId = id, loaded = false, eof = false, error = null, buffering = true, positionSeconds = 0.0, durationSeconds = 0.0,
                seekable = false, playbackRestarts = 0,
            )
        }
        opening = scope.launch {
            delay(openDelay)
            val file = fileOf(request.url)
            if (file == null) {
                report { it.copy(buffering = false, error = EngineError("HTTP error 404", 404)) }
                return@launch
            }
            val duration = file.durationSeconds
            opens += request.url to System.nanoTime()
            move(request.startSeconds, running = !state.paused, durationSeconds = duration)
            report {
                it.copy(
                    loaded = true, durationSeconds = duration, positionSeconds = request.startSeconds, buffering = false,
                    seekable = file.seekable, playbackRestarts = 1,
                )
            }
            ensureTicker()
        }
        return id
    }

    /** The open file stops playing for good, as a stream that dies or can't be decoded does. */
    fun fail() {
        opening?.cancel()
        move(positionAt(), running = false)
        report { it.copy(loaded = false, buffering = false, error = EngineError("decoding failed", kind = EngineErrorKind.DECODE)) }
    }

    override fun setPaused(paused: Boolean) {
        val s = state
        move(positionAt(), running = !paused && s.loaded && !s.eof)
        report { it.copy(paused = paused, positionSeconds = positionAt()) }
    }

    override fun seek(seconds: Double, exact: Boolean) {
        seeks += seconds
        val s = state
        if (!s.loaded || !s.seekable) return
        val target = seconds.coerceIn(0.0, s.durationSeconds)
        move(target, running = !s.paused)
        report { it.copy(positionSeconds = target, eof = false, playbackRestarts = it.playbackRestarts + 1) }
    }

    override fun setSpeed(speed: Double) {
        val m = motion
        motion = m.copy(anchorSeconds = positionAt(), anchorNanos = System.nanoTime(), speed = speed)
        report { it.copy(speed = speed, positionSeconds = positionAt()) }
    }

    override fun setSubtitleDelay(seconds: Double) = report { it.copy(subtitleDelaySeconds = seconds) }
    override fun setAudioDelay(seconds: Double) = report { it.copy(audioDelaySeconds = seconds) }
    override fun setSubtitleLift(fraction: Double) = Unit
    override fun setSubtitleStyle(style: SubtitleStyle) = Unit
    override fun setVolume(volume: Double) = report { it.copy(volume = volume) }
    override fun setMuted(muted: Boolean) = report { it.copy(muted = muted) }
    override fun setVideoLayout(layout: VideoLayout) = Unit
    override fun setAudioNormalization(enabled: Boolean) = Unit
    override fun setAudioChannels(channels: AudioChannels) = Unit
    override fun setShaders(paths: List<String>) = Unit
    override fun setAudioDevice(name: String) = Unit
    override fun setStatsWanted(wanted: Boolean) = Unit
    override fun selectTrack(kind: TrackKind, id: Long?) = Unit
    override fun selectSecondSubtitle(id: Long?) = Unit
    override fun addSubtitle(url: String, title: String?, lang: String?) = Unit

    override fun setListener(listener: PlayerEngineListener?) {
        this.listener = listener
    }

    override fun release() {
        released = true
        opening?.cancel()
        ticker?.cancel()
        move(positionAt(), running = false)
    }

    private fun move(seconds: Double, running: Boolean, durationSeconds: Double = motion.durationSeconds) {
        val was = motion
        motion = Motion(seconds, System.nanoTime(), running, was.speed, durationSeconds)
        if (running && !was.running) starts += (url.orEmpty() to motion.anchorNanos)
    }

    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                delay(TICK)
                val s = state
                if (!s.loaded) continue
                val position = positionAt()
                if (motion.running && s.durationSeconds > 0 && position >= s.durationSeconds) {
                    move(s.durationSeconds, running = false)
                    report { it.copy(positionSeconds = s.durationSeconds, eof = true) }
                } else if (position != s.positionSeconds) {
                    report { it.copy(positionSeconds = position) }
                }
            }
        }
    }

    private fun report(change: (EngineState) -> EngineState) {
        state = change(state)
        listener?.onState(state)
    }

    companion object {
        val TICK: Duration = 100.milliseconds
    }
}
