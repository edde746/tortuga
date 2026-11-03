package com.edde746.tortuga.playback.players

import com.edde746.tortuga.playback.session.pastCredits
import kotlinx.serialization.Serializable

/**
 * Player apps: other apps on the device that play a stream instead of Tortuga's own player. Settings →
 * Playback → Player picks one for every play on this device (`DeviceSetting.Player`), and the "Play with…" menu of a
 * stream or a download picks one for a single play. `PlayerAppHandoff` resolves the stream as the built-in player
 * would, hands the final URL to the platform's [PlayerAppLauncher] and records what the player reports back.
 *
 * Not to be confused with `StreamPlayability.External`: an addon's `externalUrl`, opened in the browser.
 */

/** How a player app reports its playback back. */
enum class PlayerAppProgress {
    /** While it plays: position, duration and pause state (mpv's IPC socket, VLC's HTTP interface). */
    LIVE,

    /** Once, when the user comes back: where it stopped, if the player says (Android players' activity results). */
    ON_RETURN,

    /** Nothing: an app opened through a URL scheme, a program without an interface, the system chooser. */
    NONE,
}

/**
 * What a player app's launcher can hand it and get back.
 *
 * @param startPosition it starts at a given position (the resume point).
 * @param subtitles it takes the addons' subtitles (or a download's saved ones) with the video.
 * @param progress what it reports back.
 */
data class PlayerAppCapabilities(
    val startPosition: Boolean,
    val subtitles: Boolean,
    val progress: PlayerAppProgress,
) {
    companion object {
        /** A player that takes only the URL and reports nothing. */
        val URL_ONLY: PlayerAppCapabilities = PlayerAppCapabilities(startPosition = false, subtitles = false, progress = PlayerAppProgress.NONE)
    }
}

enum class PlayerAppKind {
    /** Found on this device: on Android any app that plays video, elsewhere one of the players the launcher knows. */
    DETECTED,

    /** One the user added in Settings → Player ([CustomPlayer]). */
    CUSTOM,

    /** The system asks which app plays it each time: Android's chooser ("Ask every time"). */
    CHOOSER,
}

/**
 * A player app on this device.
 *
 * @param id stable on this device: what `DeviceSetting.Player` stores and "Play with…" passes. A detected player's is
 *   the launcher's own (Android: the package name; desktop and Apple: `mpv`, `vlc`, `iina`, `infuse`, …), a custom
 *   player's is [CUSTOM_PREFIX] + [CustomPlayer.id], the chooser's [CHOOSER_ID]. [TORTUGA_ID] is the built-in player,
 *   never a [PlayerApp].
 * @param name as the app calls itself ("VLC", "mpv"); Android's is the app's label.
 * @param custom the user's definition, for a [PlayerAppKind.CUSTOM] player.
 */
data class PlayerApp(
    val id: String,
    val name: String,
    val kind: PlayerAppKind,
    val capabilities: PlayerAppCapabilities,
    val custom: CustomPlayer? = null,
) {
    companion object {
        /** The built-in player's id in `DeviceSetting.Player` and "Play with…". */
        const val TORTUGA_ID: String = "tortuga"

        /** The system chooser's id ([PlayerAppKind.CHOOSER]). */
        const val CHOOSER_ID: String = "chooser"

        /** What a custom player's id starts with. */
        const val CUSTOM_PREFIX: String = "custom:"
    }
}

/**
 * What custom players are on this platform: what [CustomPlayer.command] and [CustomPlayer.arguments] hold.
 */
enum class CustomPlayerKind {
    /**
     * Windows, Linux, macOS: [CustomPlayer.command] is a program (an absolute path, or a name on `PATH`) and
     * [CustomPlayer.arguments] its argument template with [PlayerAppTemplates.URL], [PlayerAppTemplates.TITLE],
     * [PlayerAppTemplates.START] and [PlayerAppTemplates.SUBTITLE] ([PlayerAppTemplates.arguments]).
     */
    COMMAND,

    /**
     * iPhone, iPad, Apple TV: [CustomPlayer.command] is a URL template with [PlayerAppTemplates.URL] (percent-encoded)
     * or [PlayerAppTemplates.URL_RAW] ([PlayerAppTemplates.url]); [CustomPlayer.arguments] is empty.
     */
    URL_TEMPLATE,

    /**
     * Android: [CustomPlayer.command] is an app's package name, launched as the known players are, with MX Player's
     * extras; [CustomPlayer.arguments] is empty.
     */
    PACKAGE,
}

/**
 * A player the user added (`DeviceSetting.CustomPlayers`), as its platform's [CustomPlayerKind] reads it.
 *
 * @param id unique among the custom players; its [PlayerApp.id] is [PlayerApp.CUSTOM_PREFIX] + [id].
 */
@Serializable
data class CustomPlayer(val id: String, val name: String, val command: String, val arguments: String = "")

/**
 * What a player app is handed. The URL and the header values carry API keys: [toString] leaves them out, and so must
 * every log built from it.
 *
 * @param url the stream's final URL after redirects (never the addon's resolve link), or for a download ([local]) the
 *   file's absolute path.
 * @param headers the stream's request headers with a `User-Agent`; empty for a download.
 * @param title the title the player shows: "Chernobyl S1E2", "The Shawshank Redemption".
 * @param startSeconds where it starts: the resume point, 0 from the beginning.
 * @param subtitles the addons' subtitles for the stream, in the order the built-in player adds them, or a download's
 *   saved ones.
 * @param filename the file's name when known (the addon's, the server's, the download's).
 */
data class PlayerAppMedia(
    val url: String,
    val local: Boolean,
    val headers: Map<String, String>,
    val title: String,
    val startSeconds: Double,
    val subtitles: List<PlayerAppSubtitle>,
    val filename: String?,
) {
    override fun toString(): String =
        "PlayerAppMedia(url=<redacted>, local=$local, headers=${headers.keys}, title=$title, startSeconds=$startSeconds, subtitles=${subtitles.size}, filename=$filename)"
}

/**
 * A subtitle handed with the video: an addon's (an `http(s)` URL) or a download's saved file ([local]: a path).
 * [selected]: the one to show at start, as the built-in player would choose it among these (the profile's subtitle
 * languages); none when subtitles stay off.
 */
data class PlayerAppSubtitle(val url: String, val local: Boolean, val title: String?, val language: String?, val selected: Boolean = false) {
    override fun toString(): String = "PlayerAppSubtitle(url=<redacted>, local=$local, title=$title, language=$language, selected=$selected)"
}

/** What a launched player reports ([PlayerAppEvents]). */
sealed interface PlayerAppEvent {
    /** It opened the file. */
    data object Started : PlayerAppEvent

    /** Where it is, while it plays ([PlayerAppProgress.LIVE]); [durationMillis] is 0 while unknown. */
    data class Position(val positionMillis: Long, val durationMillis: Long, val paused: Boolean) : PlayerAppEvent

    /**
     * It closed, or the user came back from it: where it stopped when it says (null when it doesn't), and whether it
     * finished the video ([completed]): it said it played to the end, or where it stopped says so ([finishedAt]).
     * [failed]: it failed, by its process (an error, a failing exit status or a crash: a live player's launcher knows;
     * a broken install dies as it loads) or by its own answer (an Android player's result, [AndroidPlayers.ended]).
     * Failed before it opened the file (a live player that never started, a result without a position), it didn't
     * start. The last event.
     */
    data class Ended(val positionMillis: Long?, val durationMillis: Long?, val completed: Boolean, val failed: Boolean = false) : PlayerAppEvent
}

/**
 * How far short of the duration a player app may stop and have played the video to its end: mpv's last position is its
 * last frame's, a frame or two and the audio's tail short of the duration; VLC's is its last answer's, up to a poll and
 * a second before it quit.
 */
internal const val END_TOLERANCE_MILLIS = 3_000L

/** [positionMillis] is within [END_TOLERANCE_MILLIS] of the end of [durationMillis] (0: unknown). */
internal fun reachedEnd(positionMillis: Long, durationMillis: Long): Boolean =
    durationMillis > 0 && durationMillis - positionMillis <= END_TOLERANCE_MILLIS

/**
 * A player app that stopped at [positionMillis] of [durationMillis] (null or 0: unknown) finished the video, whatever it
 * said of how it stopped: at its end ([reachedEnd]), or past its credits as Tortuga's player counts them ([pastCredits]).
 */
internal fun finishedAt(positionMillis: Long?, durationMillis: Long?): Boolean {
    if (positionMillis == null || durationMillis == null) return false
    return reachedEnd(positionMillis, durationMillis) || pastCredits(positionMillis / 1000.0, durationMillis / 1000.0)
}

/** Where a launched player's reports go. Called from any thread; nothing after [PlayerAppEvent.Ended]. */
fun interface PlayerAppEvents {
    fun onEvent(event: PlayerAppEvent)
}

/** How a launch went ([PlayerAppLauncher.launch]). */
sealed interface PlayerAppLaunch {
    /**
     * The player started. [progress] is what this launch will report through its [PlayerAppEvents]: [PlayerAppProgress.LIVE]
     * positions then [PlayerAppEvent.Ended], one [PlayerAppEvent.Ended] ([PlayerAppProgress.ON_RETURN]), or nothing.
     */
    data class Launched(val progress: PlayerAppProgress) : PlayerAppLaunch

    data class Failed(val failure: PlayerAppFailure) : PlayerAppLaunch
}

enum class PlayerAppFailure {
    /** The player isn't on this device (any more). */
    NOT_INSTALLED,

    /** It is, but didn't start. */
    OTHER,
}

/**
 * The platform's side of player apps (`AppPlatform.playerApps`): which players this device has, and starting one.
 *
 * - Android: every app that handles `ACTION_VIEW` for video types (with its label), the chooser, packages as custom
 *   players; results come back as activity results.
 * - Windows and Linux: mpv, VLC and the other known players found on `PATH` or where they install; mpv and VLC report
 *   live through mpv's IPC and VLC's HTTP interface; programs as custom players.
 * - macOS: IINA, VLC, mpv, Infuse by bundle id; mpv, VLC and IINA live; programs as custom players.
 * - iPhone, iPad, Apple TV: apps with a documented URL scheme; URL templates as custom players; nothing comes back.
 */
interface PlayerAppLauncher {
    /** The custom players this platform takes; null where none can be added. */
    val customPlayers: CustomPlayerKind?

    /** Downloads can be handed to a player app (other apps can read the files): false on iPhone, iPad and Apple TV. */
    val handsOffFiles: Boolean

    /**
     * Finds this device's player apps (not Tortuga, not the custom players) and hands them to [done], once, from any
     * thread: detection can take a while (Windows reads the registry). Called again when the Player page opens.
     */
    fun detect(done: PlayerAppsFound)

    /**
     * Starts [app] on [media]: the launcher passes what [app]'s capabilities say it takes and reports to [events] what
     * it hears back. Called on the main thread; returns once the player started, or once it's known that it didn't (an
     * iPhone app's `UIApplication.open` answers later). Never logs [media]'s URL, headers or the command line.
     */
    suspend fun launch(app: PlayerApp, media: PlayerAppMedia, events: PlayerAppEvents): PlayerAppLaunch
}

/** [PlayerAppLauncher.detect]'s answer. */
fun interface PlayerAppsFound {
    fun onFound(players: List<PlayerApp>)
}
