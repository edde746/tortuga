package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.playback.session.PlayerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * What Discord shows on the user's profile while a video plays (Rich Presence): a "Watching" activity with the title,
 * the episode line, the poster and the time elapsed and left. Rich Presence has no paused state, and an activity
 * without timestamps counts up from when Discord got it, so a paused or stopped video shows none ([of] is null).
 *
 * @param details the first line, which Discord's member list shows too: the movie or series name.
 * @param state the second line: the episode (`S1E2 · Pilot`), or null for a movie.
 * @param imageUrl the large image: the title's poster (an episode's series poster), else the media controls'
 *   artwork, as an https URL Discord fetches itself; null when there is none.
 * @param imageText the image's tooltip: the title.
 * @param startSeconds when the video would have started at the current speed, in Unix seconds: Discord counts the
 *   elapsed time from it; null for a live stream.
 * @param endSeconds when the video ends at the current speed, in Unix seconds: Discord shows the time left and a bar;
 *   null while the length is unknown and for a live stream.
 */
data class DiscordActivity(
    val details: String,
    val state: String?,
    val imageUrl: String?,
    val imageText: String,
    val startSeconds: Long?,
    val endSeconds: Long?,
) {
    /**
     * [other] shows the same: every field equal but the timestamps, which may differ by [TIMESTAMP_SLACK_SECONDS] (a
     * position report's lag, whole seconds rounded), so a video playing at its pace publishes nothing.
     */
    fun sameAs(other: DiscordActivity): Boolean =
        copy(startSeconds = other.startSeconds, endSeconds = other.endSeconds) == other &&
            near(startSeconds, other.startSeconds) && near(endSeconds, other.endSeconds)

    companion object {
        /** Discord's longest text field; a longer one fails the whole update. */
        const val MAX_TEXT = 128

        /** Discord's shortest text field; a shorter one (a film named "M") fails the whole update. */
        const val MIN_TEXT = 2

        /** Discord's longest image URL; a longer one fails the whole update, so the image is left out. */
        const val MAX_IMAGE_URL = 256

        const val TIMESTAMP_SLACK_SECONDS = 1L

        /**
         * The activity for [nowPlaying] at [nowMillis] (Unix milliseconds), or null when Discord should show none: the
         * video is paused or stopped, or has no title yet.
         */
        fun of(nowPlaying: NowPlaying, nowMillis: Long): DiscordActivity? {
            if (!nowPlaying.playing) return null
            val title = nowPlaying.title.trim().takeIf { it.isNotEmpty() }?.limited() ?: return null
            val rate = nowPlaying.rate.takeIf { it > 0.0 } ?: 1.0
            fun scaled(seconds: Double) = (seconds.coerceAtLeast(0.0) * 1000 / rate).toLong()
            var start: Long? = null
            var end: Long? = null
            when {
                nowPlaying.live -> Unit
                nowPlaying.durationSeconds > 0.0 -> {
                    val endMillis = nowMillis + scaled(nowPlaying.durationSeconds - nowPlaying.positionSeconds)
                    start = (endMillis - scaled(nowPlaying.durationSeconds)) / 1000
                    end = endMillis / 1000
                }
                else -> start = (nowMillis - scaled(nowPlaying.positionSeconds)) / 1000
            }
            val image = (nowPlaying.posterUrl ?: nowPlaying.artworkUrl)
                ?.takeIf { it.startsWith("https://") && it.length <= MAX_IMAGE_URL }
            return DiscordActivity(
                details = title,
                state = nowPlaying.subtitle?.trim()?.takeIf { it.isNotEmpty() }?.limited(),
                imageUrl = image,
                imageText = title,
                startSeconds = start,
                endSeconds = end,
            )
        }

        private fun near(a: Long?, b: Long?) = if (a == null || b == null) a == b else abs(a - b) <= TIMESTAMP_SLACK_SECONDS

        /**
         * Within Discord's limits: at most [MAX_TEXT] UTF-16 units, cut on a character's boundary, an ellipsis marking
         * the cut; a single character gets a zero-width space after it, which shows nothing, to reach [MIN_TEXT].
         */
        private fun String.limited(): String {
            if (length < MIN_TEXT) return this + "\u200B"
            if (length <= MAX_TEXT) return this
            val cut = take(MAX_TEXT - 1)
            return (if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut) + "…"
        }
    }
}

/**
 * This build's Discord application ([BuildDiscordConfig]: the `tortuga.discord.applicationId` Gradle property or the
 * `TORTUGA_DISCORD_APPLICATION_ID` environment variable, else Tortuga's). Discord names the activity after it
 * ("Watching Tortuga"), and each connection's handshake carries its id ([DiscordRpc.handshake]).
 */
class DiscordConfig(applicationId: String) {
    /**
     * The application's id; null when blank: the platform then has no [DiscordPresenceHost], so Settings offers no
     * Discord switch and nothing connects to Discord.
     */
    val applicationId: String? = applicationId.takeIf { it.isNotBlank() }
}

/**
 * Discord's local RPC, as the desktop and Mac apps speak it to the Discord app on the same computer: frames of a
 * little-endian 32-bit opcode, a little-endian 32-bit length and that many bytes of UTF-8 JSON, over a Unix socket
 * (`discord-ipc-<n>` in the runtime or temporary directory) or a Windows named pipe (`\\.\pipe\discord-ipc-<n>`). This
 * object has the opcodes and the JSON; the apps have the sockets.
 */
object DiscordRpc {
    /** The first frame, with [handshake]'s JSON; Discord answers with READY ([isReady]) or closes. */
    const val OP_HANDSHAKE = 0

    /** A command or its answer. */
    const val OP_FRAME = 1

    /** Either side ends the connection; Discord's carries the reason as JSON. */
    const val OP_CLOSE = 2

    /** Discord checks the connection: answered by [OP_PONG] with the same payload. */
    const val OP_PING = 3
    const val OP_PONG = 4

    /** Bytes before a frame's JSON: the opcode and the length. */
    const val HEADER_BYTES = 8

    /** The longest frame payload either side sends; a longer length means the stream is corrupt. */
    const val MAX_PAYLOAD_BYTES = 64 * 1024

    /** Discord's activity type for a video: the profile says "Watching". */
    private const val TYPE_WATCHING = 3

    /** The member list's status line names [DiscordActivity.details] ("Watching Severance") rather than the app. */
    private const val STATUS_DISPLAY_DETAILS = 2

    private val json = Json { ignoreUnknownKeys = true }

    /** The handshake's JSON: protocol version 1 and the Discord application's id ([DiscordConfig.applicationId]). */
    fun handshake(applicationId: String): String = buildJsonObject {
        put("v", 1)
        put("client_id", applicationId)
    }.toString()

    /**
     * The command that shows [activity] for the process [pid] (the app's: Discord takes the activity down when it
     * exits), or takes it down when null. [nonce] tells the answer apart; any text unique on the connection.
     */
    fun setActivity(activity: DiscordActivity?, pid: Long, nonce: String): String = buildJsonObject {
        put("cmd", "SET_ACTIVITY")
        putJsonObject("args") {
            put("pid", pid)
            if (activity != null) {
                putJsonObject("activity") {
                    put("type", TYPE_WATCHING)
                    put("status_display_type", STATUS_DISPLAY_DETAILS)
                    put("details", activity.details)
                    activity.state?.let { put("state", it) }
                    if (activity.startSeconds != null || activity.endSeconds != null) {
                        putJsonObject("timestamps") {
                            activity.startSeconds?.let { put("start", it) }
                            activity.endSeconds?.let { put("end", it) }
                        }
                    }
                    activity.imageUrl?.let { url ->
                        putJsonObject("assets") {
                            put("large_image", url)
                            put("large_text", activity.imageText)
                        }
                    }
                }
            }
        }
        put("nonce", nonce)
    }.toString()

    /** Discord's frame [payload] is its READY event, the answer to a handshake it accepted. */
    fun isReady(payload: String): Boolean {
        val reply = parse(payload) ?: return false
        return reply["cmd"]?.jsonPrimitive?.content == "DISPATCH" && reply["evt"]?.jsonPrimitive?.content == "READY"
    }

    /**
     * Discord's frame [payload] as a log line when it reports an error (an ERROR event, or a close frame's reason):
     * its code and message; null for anything else.
     */
    fun errorOf(payload: String): String? {
        val reply = parse(payload) ?: return null
        val data = reply["data"] as? JsonObject
        val code = (data?.get("code") ?: reply["code"])?.jsonPrimitive?.intOrNull ?: return null
        val message = (data?.get("message") ?: reply["message"])?.jsonPrimitive?.content.orEmpty()
        return "$code $message".trim()
    }

    private fun parse(payload: String): JsonObject? =
        runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
}

/**
 * Discord's local RPC on this computer ([DiscordRpc]) as one Discord application, whose id each handshake carries,
 * implemented by the desktop and Mac apps. Called on the main thread, which it never blocks: the connection lives on a
 * thread of its own.
 */
interface DiscordPresenceHost {
    /**
     * Shows [activity] on the user's Discord profile in place of what the app showed; null takes it down. While there
     * is an activity to show, the host keeps a connection to Discord, connecting again whenever Discord isn't running
     * yet or restarts; with none, it takes the activity down and disconnects.
     */
    fun publish(activity: DiscordActivity?)
}

/**
 * One playback's Rich Presence: while the device's Discord setting is on ([enabled]), the playback's [DiscordActivity]
 * goes to the [host] whenever it shows something new; off, paused, stopped or [release]d, the activity is taken down.
 *
 * Discord takes at most [UPDATES] activity updates per [WINDOW]: one beyond that waits for its turn, and then the
 * newest activity goes out (none if it is what Discord shows already). Taking the activity down never waits: the host
 * disconnects, and an activity still waiting never goes out.
 *
 * Main thread; [release] ends it.
 */
class DiscordPresence internal constructor(
    store: PlayerStore,
    session: PlayerSession,
    enabled: Flow<Boolean>,
    private val host: DiscordPresenceHost,
    parentScope: CoroutineScope,
    clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    /** What the host shows: the last activity published, or null. */
    private var published: DiscordActivity? = null

    /** When the updates of the last [WINDOW] went out, oldest first. */
    private val sent = ArrayDeque<TimeMark>()

    init {
        val wanted = combine(store.state, store.timeline, session.info, session.engineState, enabled) { ui, timeline, info, engine, on ->
            if (on) DiscordActivity.of(NowPlaying.of(ui, timeline, info, engine), clock.now().toEpochMilliseconds()) else null
        }.stateIn(scope, SharingStarted.Eagerly, null)
        scope.launch {
            wanted.collectLatest { next ->
                if (shows(next)) return@collectLatest
                if (next != null) {
                    awaitTurn()
                    sent.addLast(timeSource.markNow())
                }
                published = next
                host.publish(next)
            }
        }
    }

    /** The playback ended: the activity is taken down at once. */
    fun release() {
        scope.cancel()
        if (published != null) host.publish(null)
        published = null
    }

    /** [activity] is what the host shows already. */
    private fun shows(activity: DiscordActivity?): Boolean {
        val shown = published
        return if (activity == null || shown == null) activity == shown else activity.sameAs(shown)
    }

    private suspend fun awaitTurn() {
        while (sent.size >= UPDATES) {
            val wait = WINDOW - sent.first().elapsedNow()
            if (wait > Duration.ZERO) delay(wait) else sent.removeFirst()
        }
    }

    internal companion object {
        const val UPDATES = 5
        val WINDOW = 20.seconds
    }
}
