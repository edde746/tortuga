package com.edde746.tortuga.playback.players

import com.edde746.tortuga.lang.LanguageNames
import com.edde746.tortuga.strings.isolate

/**
 * The intent interface an Android player app speaks ([AndroidPlayers]): what its `ACTION_VIEW` intent takes and how its
 * activity result reads, and so what it can be handed and report back.
 */
enum class AndroidPlayerApi(val capabilities: PlayerAppCapabilities) {
    /** MX Player's (free and pro): the start, the title, the request headers and the subtitles; a result on return. */
    MX(PlayerAppCapabilities(startPosition = true, subtitles = true, progress = PlayerAppProgress.ON_RETURN)),

    /** Just Player's: MX Player's without headers; MX Player's result. */
    JUST_PLAYER(PlayerAppCapabilities(startPosition = true, subtitles = true, progress = PlayerAppProgress.ON_RETURN)),

    /** mpv-android's: the start, the title and the subtitles (not on a play that starts past 0); a result on return. */
    MPV(PlayerAppCapabilities(startPosition = true, subtitles = true, progress = PlayerAppProgress.ON_RETURN)),

    /** VLC's: the start and the title (its subtitle extra takes only a file path of its own); a result on return. */
    VLC(PlayerAppCapabilities(startPosition = true, subtitles = false, progress = PlayerAppProgress.ON_RETURN)),

    /** Vimu's: the start, the title and one subtitle; a result code on return. */
    VIMU(PlayerAppCapabilities(startPosition = true, subtitles = true, progress = PlayerAppProgress.ON_RETURN)),

    /** Kodi's: the URL only, and nothing back. */
    KODI(PlayerAppCapabilities.URL_ONLY),

    /** Nova's: the URL and the title, and nothing back. */
    NOVA(PlayerAppCapabilities.URL_ONLY),

    /**
     * Any other app's, and a custom player's unknown package: MX Player's extras, which many players read, and whichever
     * of MX Player's or VLC's result keys it returns (it may return none). Nothing documents that it reads a start, so
     * it is handed none past 0 (MX Player's `position` 0, which starts an app that reads it from the beginning).
     */
    GENERIC(PlayerAppCapabilities(startPosition = false, subtitles = true, progress = PlayerAppProgress.ON_RETURN)),
}

/**
 * A player app by its package name.
 *
 * @param activity the activity class the intent names instead of only the package, for an app that returns a result
 *   only to an explicit intent (MX Player, for http(s) URLs).
 */
data class AndroidPlayer(val packageName: String, val api: AndroidPlayerApi, val activity: String? = null)

/**
 * One extra of a player app's intent, typed as the app reads it: `Bundle.getInt` finds nothing where a Long was put,
 * and a `String[]` where a `Parcelable[]` of `Uri`s is read. The values carry the stream's URL and headers: never log
 * them.
 */
sealed interface AndroidExtra {
    val key: String

    data class Text(override val key: String, val value: String) : AndroidExtra

    /** A `String[]`; a null element leaves that entry to the app (MX Player's `subs.name`). */
    data class Texts(override val key: String, val values: List<String?>) : AndroidExtra

    data class IntValue(override val key: String, val value: Int) : AndroidExtra

    data class LongValue(override val key: String, val value: Long) : AndroidExtra

    data class Flag(override val key: String, val value: Boolean) : AndroidExtra

    /** A `Parcelable[]` of `Uri`s, from their strings. */
    data class Uris(override val key: String, val uris: List<String>) : AndroidExtra
}

/**
 * A subtitle as the intent hands it: [uri] is the addon's URL, or the `content://` URI a download's saved file is
 * shared under.
 */
data class AndroidSubtitle(val uri: String, val subtitle: PlayerAppSubtitle) {
    override fun toString(): String = "AndroidSubtitle(uri=<redacted>, subtitle=$subtitle)"
}

/**
 * Android's player apps (`AndroidPlayerApps` in app-android starts them): the known packages and the intent interface
 * each one speaks, the extras its `ACTION_VIEW` intent gets ([extras]) and how its activity result reads ([ended]).
 * Every key, type and result code is the app's own, as its documentation or source gives it:
 *
 * - **MX Player** (`com.mxtech.videoplayer.ad`, `.pro`), its intent definition (sites.google.com/site/mxvpen/api, the
 *   former mx.j2inter.com/api): `position` (int, ms), `title`, `filename`, `secure_uri` (the URL stays out of its
 *   properties dialog), `return_result`, `headers` (`String[]`, each name then its value), `subs` (`Uri[]`),
 *   `subs.name`, `subs.enable`. Its result, `com.mxtech.intent.result.VIEW`: `position` and `duration` (int, ms), both
 *   left out when it played to the end, and `end_by` (`user` or `playback_completion`); `RESULT_OK`, `RESULT_CANCELED`
 *   when the user cancelled before any playback started, `RESULT_FIRST_USER` when its playback ended with an error.
 *   For an http(s) URL it returns a result only to an intent naming its activity class.
 * - **Just Player** (`com.brouken.player`), its `PlayerActivity`: `position` (int), `title`, `return_result`, `subs`,
 *   `subs.name`, `subs.enable`; MX Player's result, action and keys.
 * - **mpv-android** (`is.xyz.mpv`), mpv-android.github.io/mpv-android/intent.html and its `MPVActivity`: `position`
 *   (int, ms), `title`, `subs`, `subs.enable`. A play that starts past 0 gets no subtitles: its activity runs each
 *   `sub-add` before it sets the start (the commands it queues when the file starts), and a subtitle being fetched
 *   holds the start back until the video already plays from 0, which then returns an earlier position than the one
 *   stored. Its result, `is.xyz.mpv.MPVActivity.result`: `RESULT_OK` with `position` and `duration` (int, ms) when
 *   the user left, without them when playback finished normally; `RESULT_CANCELED` when playback didn't start.
 * - **VLC** (`org.videolan.vlc`), wiki.videolan.org/Android_Player_Intents and its `VideoPlayerActivity`: `position`
 *   (long, ms) with `from_start` false, or `from_start` true to start from the beginning rather than its own resume
 *   point, and `title`. Its subtitle extra, `subtitles_location`, is a file path, which neither an addon's URL nor a
 *   shared download is. Its result, `org.videolan.vlc.player.result`: `extra_position` and `extra_duration` (long, ms),
 *   both 0 once its playback stopped (at the end of the video too), which says nothing; `RESULT_OK`, or an error code
 *   from `RESULT_FIRST_USER` + 1 to + 3 (its service connection failed, a playback error, the video track lost).
 * - **Vimu** (`net.gtvbox.videoplayer`, `net.gtvbox.vimuhd`), vimu.tv/player-api: `forcename` (the title),
 *   `startfrom` (int, ms), `forceresume` false (Tortuga has the resume point), `forcedsrt` (one subtitle's URL). Its
 *   result, `net.gtvbox.videoplayer.result` (older versions set no action), is a code: 1 played to the end, 0 stopped
 *   with `position` and `duration` (int, ms), 4 failed.
 * - **Kodi** (`org.xbmc.kodi`): the URL only; it returns nothing.
 * - **Nova** (`org.courville.nova`), its `PlayerActivity`: `title`; it returns nothing.
 * - Any other package: MX Player's extras ([AndroidPlayerApi.GENERIC]), started from the beginning.
 */
object AndroidPlayers {
    /** `Activity.RESULT_OK`. */
    const val RESULT_OK: Int = -1

    /** `Activity.RESULT_CANCELED`. */
    const val RESULT_CANCELED: Int = 0

    /** The packages whose intent interface is known. */
    val known: List<AndroidPlayer> = listOf(
        AndroidPlayer("org.videolan.vlc", AndroidPlayerApi.VLC),
        AndroidPlayer("is.xyz.mpv", AndroidPlayerApi.MPV),
        AndroidPlayer("com.mxtech.videoplayer.ad", AndroidPlayerApi.MX, activity = "com.mxtech.videoplayer.ad.ActivityScreen"),
        AndroidPlayer("com.mxtech.videoplayer.pro", AndroidPlayerApi.MX, activity = "com.mxtech.videoplayer.ActivityScreen"),
        AndroidPlayer("com.brouken.player", AndroidPlayerApi.JUST_PLAYER),
        AndroidPlayer("net.gtvbox.videoplayer", AndroidPlayerApi.VIMU),
        AndroidPlayer("net.gtvbox.vimuhd", AndroidPlayerApi.VIMU),
        AndroidPlayer("org.xbmc.kodi", AndroidPlayerApi.KODI),
        AndroidPlayer("org.courville.nova", AndroidPlayerApi.NOVA),
    )

    /**
     * The system chooser's ("Ask every time"): the app the user picks gets [AndroidPlayerApi.GENERIC]'s extras with the
     * resume point (it may be one that reads it), and nothing comes back (the chooser is started without a result).
     */
    val chooserCapabilities: PlayerAppCapabilities =
        AndroidPlayerApi.GENERIC.capabilities.copy(startPosition = true, progress = PlayerAppProgress.NONE)

    /** The player [packageName] is: a known one, else an app that gets [AndroidPlayerApi.GENERIC]'s extras. */
    fun of(packageName: String): AndroidPlayer =
        known.firstOrNull { it.packageName == packageName } ?: AndroidPlayer(packageName, AndroidPlayerApi.GENERIC)

    /**
     * The extras of [api]'s intent for [media], whose video is the intent's data. [subtitles] are [media]'s subtitles
     * that can be handed over, in order; their names ([PlayerAppSubtitle.language], then [PlayerAppSubtitle.title]) are
     * in [locale]'s language, as the built-in player's track menu names them.
     */
    fun extras(api: AndroidPlayerApi, media: PlayerAppMedia, subtitles: List<AndroidSubtitle>, locale: String): List<AndroidExtra> = buildList {
        val start = startMillis(media.startSeconds)
        val title = media.title.takeIf { it.isNotBlank() }
        val selected = subtitles.firstOrNull { it.subtitle.selected }
        when (api) {
            AndroidPlayerApi.MX, AndroidPlayerApi.GENERIC -> {
                // Present, the position wins over the app's own resume prompt: 0 starts from the beginning.
                add(AndroidExtra.IntValue(POSITION, start.toInt()))
                add(AndroidExtra.Flag(RETURN_RESULT, true))
                add(AndroidExtra.Flag(SECURE_URI, true))
                title?.let { add(AndroidExtra.Text(TITLE, it)) }
                media.filename?.let { add(AndroidExtra.Text(FILENAME, it)) }
                if (media.headers.isNotEmpty()) add(AndroidExtra.Texts(HEADERS, media.headers.flatMap { (name, value) -> listOf(name, value) }))
                addSubtitles(subtitles, selected, named = true, locale)
            }
            AndroidPlayerApi.JUST_PLAYER -> {
                add(AndroidExtra.IntValue(POSITION, start.toInt()))
                add(AndroidExtra.Flag(RETURN_RESULT, true))
                title?.let { add(AndroidExtra.Text(TITLE, it)) }
                addSubtitles(subtitles, selected, named = true, locale)
            }
            AndroidPlayerApi.MPV -> {
                add(AndroidExtra.IntValue(POSITION, start.toInt()))
                title?.let { add(AndroidExtra.Text(TITLE, it)) }
                // Its sub-adds run before the start is set and would hold it back: a resumed play goes without them.
                if (start == 0L) addSubtitles(subtitles, selected, named = false, locale)
            }
            AndroidPlayerApi.VLC -> {
                if (start > 0) {
                    add(AndroidExtra.LongValue(POSITION, start))
                    add(AndroidExtra.Flag(FROM_START, false))
                } else {
                    add(AndroidExtra.Flag(FROM_START, true))
                }
                title?.let { add(AndroidExtra.Text(TITLE, it)) }
            }
            AndroidPlayerApi.VIMU -> {
                if (start > 0) add(AndroidExtra.IntValue(VIMU_START, start.toInt()))
                add(AndroidExtra.Flag(VIMU_RESUME, false))
                title?.let { add(AndroidExtra.Text(VIMU_TITLE, it)) }
                selected?.let { add(AndroidExtra.Text(VIMU_SUBTITLE, it.uri)) }
            }
            AndroidPlayerApi.KODI -> Unit
            AndroidPlayerApi.NOVA -> title?.let { add(AndroidExtra.Text(TITLE, it)) }
        }
    }

    /**
     * What the activity result of a player whose intent interface is [api] says: where it stopped and whether it finished
     * the video. The result's [action] names the interface it answers in (Just Player answers in MX Player's); without a
     * known one, [api]'s is read. A result that says nothing (an app that sets none, a launch the player refused) is
     * `Ended(null, null, false)`. A player's own answer that it failed is [PlayerAppEvent.Ended.failed], without a
     * position when it never played: mpv-android's and MX Player's cancel (`RESULT_CANCELED` with their result's action;
     * Android's default code alone says nothing), MX Player's and VLC's errors, Vimu's failure.
     *
     * @param extras the result intent's extras; numbers as the app put them (Int or Long), or as text.
     */
    fun ended(api: AndroidPlayerApi, action: String?, resultCode: Int, extras: Map<String, Any?>): PlayerAppEvent.Ended {
        val answer = when (action) {
            MX_RESULT -> AndroidPlayerApi.MX
            MPV_RESULT -> AndroidPlayerApi.MPV
            VLC_RESULT -> AndroidPlayerApi.VLC
            VIMU_RESULT -> AndroidPlayerApi.VIMU
            else -> api
        }
        fun millis(key: String): Long? = when (val value = extras[key]) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }?.takeIf { it >= 0 }
        return when (answer) {
            AndroidPlayerApi.MPV -> {
                val position = millis(POSITION)
                if (resultCode == RESULT_CANCELED && action == MPV_RESULT) {
                    PlayerAppEvent.Ended(null, null, completed = false, failed = true)
                } else {
                    stoppedAt(position, millis(DURATION), completed = resultCode == RESULT_OK && position == null)
                }
            }
            AndroidPlayerApi.VLC -> {
                val position = millis(VLC_POSITION)
                val duration = millis(VLC_DURATION)
                val failed = resultCode in VLC_ERRORS
                // VLC zeroes both once its playback stopped (it played to the end, or never opened the video).
                if (position == 0L && duration == 0L) {
                    PlayerAppEvent.Ended(null, null, completed = false, failed = failed)
                } else {
                    stoppedAt(position, duration, completed = false, failed = failed)
                }
            }
            AndroidPlayerApi.VIMU -> when (resultCode) {
                VIMU_COMPLETED -> stoppedAt(millis(POSITION), millis(DURATION), completed = true)
                VIMU_FAILED -> PlayerAppEvent.Ended(null, null, completed = false, failed = true)
                else -> stoppedAt(millis(POSITION), millis(DURATION), completed = false)
            }
            else -> if (resultCode == RESULT_CANCELED && action == MX_RESULT) {
                PlayerAppEvent.Ended(null, null, completed = false, failed = true)
            } else {
                stoppedAt(
                    millis(POSITION) ?: millis(VLC_POSITION),
                    millis(DURATION) ?: millis(VLC_DURATION),
                    completed = extras[END_BY] == END_BY_COMPLETION,
                    failed = answer == AndroidPlayerApi.MX && resultCode == MX_ERROR,
                )
            }
        }
    }

    /**
     * The name of [subtitle] in a player's track menu: its language's name, then its title where that adds to it
     * (isolated there).
     */
    internal fun subtitleName(subtitle: PlayerAppSubtitle, locale: String): String? {
        val language = LanguageNames.displayName(subtitle.language, locale)
        val title = subtitle.title?.trim()?.takeIf { it.isNotEmpty() && !it.equals(language, ignoreCase = true) }
        return if (language != null && title != null) "$language · ${isolate(title)}" else language ?: title
    }

    private fun MutableList<AndroidExtra>.addSubtitles(subtitles: List<AndroidSubtitle>, selected: AndroidSubtitle?, named: Boolean, locale: String) {
        if (subtitles.isEmpty()) return
        add(AndroidExtra.Uris(SUBS, subtitles.map { it.uri }))
        if (named) add(AndroidExtra.Texts(SUBS_NAME, subtitles.map { subtitleName(it.subtitle, locale) }))
        selected?.let { add(AndroidExtra.Uris(SUBS_ENABLE, listOf(it.uri))) }
    }

    /**
     * A duration of 0 is unknown; a position past the duration is the end. The player [completed] the video when it says
     * so, or when where it stopped says so ([finishedAt]); [failed] when it says its playback failed.
     */
    private fun stoppedAt(position: Long?, duration: Long?, completed: Boolean, failed: Boolean = false): PlayerAppEvent.Ended {
        val known = duration?.takeIf { it > 0 }
        val stopped = if (position != null && known != null) minOf(position, known) else position
        return PlayerAppEvent.Ended(stopped, known, completed || finishedAt(stopped, known), failed)
    }

    /** The start in whole milliseconds, as an Int extra can hold it. */
    private fun startMillis(seconds: Double): Long =
        if (seconds.isFinite() && seconds > 0) (seconds * 1000).toLong().coerceAtMost(Int.MAX_VALUE.toLong()) else 0

    private const val POSITION = "position"
    private const val DURATION = "duration"
    private const val TITLE = "title"
    private const val FILENAME = "filename"
    private const val RETURN_RESULT = "return_result"
    private const val SECURE_URI = "secure_uri"
    private const val HEADERS = "headers"
    private const val SUBS = "subs"
    private const val SUBS_NAME = "subs.name"
    private const val SUBS_ENABLE = "subs.enable"
    private const val END_BY = "end_by"
    private const val END_BY_COMPLETION = "playback_completion"
    private const val MX_RESULT = "com.mxtech.intent.result.VIEW"
    private const val MX_ERROR = 1
    private const val MPV_RESULT = "is.xyz.mpv.MPVActivity.result"
    private const val FROM_START = "from_start"
    private const val VLC_POSITION = "extra_position"
    private const val VLC_DURATION = "extra_duration"
    private const val VLC_RESULT = "org.videolan.vlc.player.result"
    private val VLC_ERRORS = 2..4
    private const val VIMU_TITLE = "forcename"
    private const val VIMU_START = "startfrom"
    private const val VIMU_RESUME = "forceresume"
    private const val VIMU_SUBTITLE = "forcedsrt"
    private const val VIMU_RESULT = "net.gtvbox.videoplayer.result"
    private const val VIMU_COMPLETED = 1
    private const val VIMU_FAILED = 4
}
