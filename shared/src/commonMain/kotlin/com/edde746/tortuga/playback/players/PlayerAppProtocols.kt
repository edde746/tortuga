package com.edde746.tortuga.playback.players

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Reads one mpv's JSON IPC (`--input-ipc-server`, [PlayerAppCommands.mpv]) into [PlayerAppEvent]s. The platform owns
 * the socket or pipe: once connected it writes [commands] (one JSON object per line), passes every line it reads to
 * [onLine] and, when the connection closes (mpv quit), reports [ended]. mpv says it reached the end (`end-file` with
 * reason `eof`, or `eof-reached`) only when nothing failed on the way: a file played to its end after its audio output
 * failed to open ends with reason `error`, so where it stopped counts too ([finishedAt]). Taken back from the end (its
 * `keep-open`), it isn't there any more: `eof-reached` turning false, or a later position short of the end, says so.
 * Not thread-safe: one reader at a time.
 */
class MpvIpcSession {
    /** The lines to write once connected: observe the position, the duration, pause and the end of the file. */
    val commands: List<String> = listOf(
        """{"command":["observe_property",1,"time-pos"]}""",
        """{"command":["observe_property",2,"duration"]}""",
        """{"command":["observe_property",3,"pause"]}""",
        """{"command":["observe_property",4,"eof-reached"]}""",
    )

    private val progress = ProgressTracker()

    /** One line mpv wrote: what it means, in order (none for most lines). */
    fun onLine(line: String): List<PlayerAppEvent> {
        val message = parseObject(line) ?: return emptyList()
        val events = mutableListOf<PlayerAppEvent>()
        when (message.string("event")) {
            "file-loaded" -> progress.start(events)
            "end-file" -> if (message.string("reason") == "eof") progress.eof = true
            "property-change" -> {
                val data = message["data"] as? JsonPrimitive
                when (message.string("name")) {
                    "time-pos" -> data?.doubleOrNull?.let { progress.position(it, events) }
                    "duration" -> data?.doubleOrNull?.let { progress.duration(it, events) }
                    "pause" -> data?.booleanOrNull?.let { progress.paused(it, events) }
                    "eof-reached" -> progress.eof = data?.booleanOrNull == true
                }
            }
        }
        return events
    }

    /** The connection closed: mpv quit, its process with an error when [failed]. The last event. */
    fun ended(failed: Boolean = false): PlayerAppEvent.Ended = progress.ended(failed)
}

/**
 * Reads one VLC's HTTP interface ([PlayerAppCommands.vlc]) into [PlayerAppEvent]s. The platform polls
 * `http://127.0.0.1:<port>` + [STATUS_PATH] with [authorization] about once a second while VLC runs, passes each
 * answer's body to [onStatus] and, once VLC quit, reports [ended]. VLC says nothing of how it quit (it quits at the end
 * of the file, `--play-and-exit`): its last answer's position tells whether it finished ([finishedAt]). Not
 * thread-safe: one poller at a time.
 */
class VlcHttpSession {
    private val progress = ProgressTracker()

    /** One `status.json` answer: what it means, in order. */
    fun onStatus(json: String): List<PlayerAppEvent> {
        val status = parseObject(json) ?: return emptyList()
        val events = mutableListOf<PlayerAppEvent>()
        val state = status.string("state")
        val length = status.number("length")?.takeIf { it > 0 }
        if (state == "playing" || state == "paused") {
            progress.start(events)
            length?.let { progress.duration(it, events) }
            // `time` is whole seconds; `position` (0 to 1) of the length is finer.
            val fraction = status.number("position")?.takeIf { it in 0.0..1.0 }
            val position = if (length != null && fraction != null) fraction * length else status.number("time")
            position?.let { progress.position(it, events) }
            progress.paused(state == "paused", events)
        }
        return events
    }

    /**
     * VLC quit (the HTTP interface stopped answering and the process ended), its process with an error when [failed].
     * The last event.
     */
    fun ended(failed: Boolean = false): PlayerAppEvent.Ended = progress.ended(failed)

    companion object {
        const val STATUS_PATH: String = "/requests/status.json"

        /** The `Authorization` header for [password]: VLC's HTTP interface takes an empty user name. */
        fun authorization(password: String): String = "Basic " + Base64.encode(":$password".encodeToByteArray())
    }
}

/**
 * What a live player said so far, turned into events: a position once it moved a second, pause and duration changes;
 * at the end, where it stopped and whether it finished ([finishedAt], or [eof]).
 */
private class ProgressTracker {
    /**
     * The player is at the end of the file: it said so, and no later position is short of the end (a player that keeps
     * its window open there, mpv's or IINA's `keep-open`, can be taken back from it).
     */
    var eof = false
    private var started = false
    private var positionSeconds: Double? = null
    private var durationSeconds = 0.0
    private var paused = false
    private var reportedMillis: Long? = null

    fun start(events: MutableList<PlayerAppEvent>) {
        if (started) return
        started = true
        events += PlayerAppEvent.Started
    }

    fun position(seconds: Double, events: MutableList<PlayerAppEvent>) {
        if (!seconds.isFinite() || seconds < 0) return
        start(events)
        positionSeconds = seconds
        val millis = millis(seconds)
        if (eof && durationSeconds > 0 && !reachedEnd(millis, millis(durationSeconds))) eof = false
        val reported = reportedMillis
        if (reported == null || abs(millis - reported) >= STEP_MILLIS) report(events)
    }

    fun duration(seconds: Double, events: MutableList<PlayerAppEvent>) {
        if (!seconds.isFinite() || seconds <= 0 || seconds == durationSeconds) return
        durationSeconds = seconds
        if (positionSeconds != null) report(events)
    }

    fun paused(paused: Boolean, events: MutableList<PlayerAppEvent>) {
        if (paused == this.paused) return
        this.paused = paused
        if (positionSeconds != null) report(events)
    }

    fun ended(failed: Boolean): PlayerAppEvent.Ended {
        val duration = durationSeconds.takeIf { it > 0 }?.let(::millis)
        val last = positionSeconds?.let(::millis)
        // At the end, the position (which some players drop there, and mpv's stops a frame short of) is the duration.
        val atEnd = duration != null && (eof || (last != null && reachedEnd(last, duration)))
        return PlayerAppEvent.Ended(if (atEnd) duration else last, duration, eof || finishedAt(last, duration), failed)
    }

    private fun report(events: MutableList<PlayerAppEvent>) {
        val millis = millis(positionSeconds ?: return)
        reportedMillis = millis
        events += PlayerAppEvent.Position(millis, millis(durationSeconds), paused)
    }

    private fun millis(seconds: Double): Long = if (seconds.isFinite() && seconds > 0) (seconds * 1000).roundToLong() else 0

    companion object {
        /** Positions are reported once they moved this much (pause and duration changes at once). */
        const val STEP_MILLIS = 1000L
    }
}

private val protocolJson = Json { ignoreUnknownKeys = true }

private fun parseObject(text: String): JsonObject? = try {
    protocolJson.parseToJsonElement(text) as? JsonObject
} catch (e: IllegalArgumentException) {
    null
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
