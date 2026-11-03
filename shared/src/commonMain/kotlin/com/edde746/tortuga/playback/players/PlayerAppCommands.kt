package com.edde746.tortuga.playback.players

import com.edde746.tortuga.playback.resolve.StreamHeaders
import kotlin.math.floor

/**
 * Custom players' templates ([CustomPlayer]): the placeholders and how they expand. Nothing runs through a shell: an
 * argument template is split into arguments first ([splitArguments]), then each placeholder is replaced inside its
 * argument, so a title with spaces or quotes stays one argument (on Windows the desktop launcher quotes each argument for
 * the program's command line, and starts no batch file). Placeholders expand in one pass: a title or URL that holds a
 * placeholder's text stays as it is.
 */
object PlayerAppTemplates {
    /** The stream's final URL, or a download's path; in a URL template, percent-encoded. */
    const val URL: String = "{url}"

    /** In a URL template: the URL as it is. */
    const val URL_RAW: String = "{url_raw}"

    /** The title ([PlayerAppMedia.title]). */
    const val TITLE: String = "{title}"

    /** The start position in whole seconds ("0" from the beginning). */
    const val START: String = "{start}"

    /**
     * The first subtitle's URL or path. Without a subtitle, an argument holding it is left out: `--sub-file={subtitle}`
     * would hand the player an empty file name.
     */
    const val SUBTITLE: String = "{subtitle}"

    /** The arguments of a [CustomPlayerKind.COMMAND] player for [media], from its argument [template]. */
    fun arguments(template: String, media: PlayerAppMedia): List<String> {
        val subtitle = media.subtitles.firstOrNull()?.url
        val values = mapOf(
            URL to media.url,
            TITLE to media.title,
            START to wholeSeconds(media.startSeconds),
            SUBTITLE to subtitle.orEmpty(),
        )
        return splitArguments(template).filterNot { subtitle == null && SUBTITLE in it }.map { expand(it, values) }
    }

    /** The URL a [CustomPlayerKind.URL_TEMPLATE] player opens for [media]: [URL] percent-encoded, [URL_RAW] as it is. */
    fun url(template: String, media: PlayerAppMedia): String =
        expand(template, mapOf(URL_RAW to media.url, URL to percentEncode(media.url)))

    /**
     * [template] split into arguments at unquoted whitespace. Double or single quotes group (and are dropped). Inside
     * double quotes backslashes are themselves unless a double quote follows them, as in Windows' own rule: then each pair
     * is one backslash, and an odd one makes the quote part of the argument (`"\\nas\share"` keeps both backslashes).
     */
    fun splitArguments(template: String): List<String> {
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var inArgument = false
        var quote: Char? = null
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                quote == '"' && c == '\\' -> {
                    var end = i
                    while (end < template.length && template[end] == '\\') end++
                    val count = end - i
                    val beforeQuote = end < template.length && template[end] == '"'
                    repeat(if (beforeQuote) count / 2 else count) { current.append('\\') }
                    if (beforeQuote && count % 2 == 1) current.append('"')
                    // The last character taken: an escaped quote, else the last backslash (a closing quote is read next).
                    i = if (beforeQuote && count % 2 == 1) end else end - 1
                }
                quote != null && c == quote -> quote = null
                quote != null -> current.append(c)
                c == '"' || c == '\'' -> {
                    quote = c
                    inArgument = true
                }
                c.isWhitespace() -> {
                    if (inArgument) arguments += current.toString()
                    current.clear()
                    inArgument = false
                }
                else -> {
                    current.append(c)
                    inArgument = true
                }
            }
            i++
        }
        if (inArgument) arguments += current.toString()
        return arguments
    }

    /** [text] percent-encoded as a URL query value: everything but `A-Z a-z 0-9 - _ . ~`, as UTF-8 bytes. */
    fun percentEncode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                append(ch)
            } else {
                append('%')
                append(HEX[c shr 4])
                append(HEX[c and 0x0F])
            }
        }
    }

    internal fun wholeSeconds(seconds: Double): String = if (seconds.isFinite() && seconds > 0) floor(seconds).toLong().toString() else "0"

    /** [text] with each placeholder of [values] replaced by its value, in one pass. */
    private fun expand(text: String, values: Map<String, String>): String = buildString {
        var i = 0
        while (i < text.length) {
            val placeholder = if (text[i] == '{') values.keys.firstOrNull { text.startsWith(it, i) } else null
            if (placeholder == null) {
                append(text[i])
                i++
            } else {
                append(values.getValue(placeholder))
                i += placeholder.length
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

/**
 * The command lines of the players Tortuga knows (desktop and macOS), without the program itself, from each player's
 * own documentation of its command line. Each passes what its player takes of the start position, the title, the
 * subtitles and the request headers: mpv, IINA, Celluloid (mpv's options) and PotPlayer every header, VLC only
 * `User-Agent` and `Referer`, MPC-HC and MPC-BE none.
 */
object PlayerAppCommands {
    /**
     * mpv's options for [media], ending with `--` and the URL: its window shows at once (also while a slow stream
     * opens), and mpv reports through its JSON IPC at [ipcPath] (a Unix socket's path, or a Windows named pipe's
     * `\\.\pipe\…` name) while it plays ([MpvIpcSession]).
     */
    fun mpv(media: PlayerAppMedia, ipcPath: String?): List<String> =
        listOf("--force-window=immediate") + mpvOptions(media, ipcPath, lists = false).map { "--$it" } + listOf("--", media.url)

    /**
     * IINA's `iina-cli` arguments for [media]: iina-cli leaves standard input alone (`--no-stdin`, else it may open
     * `-`), stays until IINA quits (`--keep-running`), and this IINA quits when its window closes
     * (`-quitWhenNoOpenedWindow YES`, an argument-domain default IINA never saves), so the process ends with the
     * playback; then the URL, and mpv's options as IINA passes them (`--mpv-<option>`), with mpv's IPC at [ipcPath].
     * IINA sets each as a property, which takes no `-append`: lists go whole.
     */
    fun iina(media: PlayerAppMedia, ipcPath: String?): List<String> =
        listOf("--no-stdin", "--keep-running", "-quitWhenNoOpenedWindow", "YES", media.url) + mpvOptions(media, ipcPath, lists = true).map { "--mpv-$it" }

    /**
     * Celluloid's arguments for [media] (`celluloid(1)`): a window of its own when Celluloid already runs
     * (`--new-window`), mpv's options as Celluloid passes them (`--mpv-<option>`), then the URL. Celluloid quotes each
     * value in `'…'` and reads backslash escapes back before mpv sees it (anything but `\\ \' \" \? \a \b \f \n \r \t \v`
     * aborts it), so backslashes and single quotes are escaped; it sets each as an option, which takes no `-append`.
     */
    fun celluloid(media: PlayerAppMedia): List<String> =
        listOf("--new-window") + mpvOptions(media, null, lists = true).map { "--mpv-" + celluloidEscaped(it) } + media.url

    /**
     * VLC's arguments for [media] (`vlc -H`): its HTTP interface listens on 127.0.0.1:[httpPort] with [httpPassword]
     * while it plays ([VlcHttpSession]); VLC quits at the end of the file, so it ends like mpv. The `User-Agent` and
     * `Referer` follow the URL as its own options: VLC 3 ignores `--http-user-agent` as a global option.
     * [oneInstanceOption]: this VLC has `--one-instance` (Windows, and Linux with D-Bus), turned off so another VLC
     * window doesn't take the file. The Mac's VLC has no such option and refuses to start when given it; each run of its
     * executable is a process of its own.
     */
    fun vlc(media: PlayerAppMedia, httpPort: Int?, httpPassword: String?, oneInstanceOption: Boolean = true): List<String> = buildList {
        if (httpPort != null && httpPassword != null) {
            add("--extraintf=http")
            add("--http-host=127.0.0.1")
            add("--http-port=$httpPort")
            add("--http-password=$httpPassword")
        }
        // Another VLC window must not take the file: this process is the one whose interface is read.
        if (oneInstanceOption) add("--no-one-instance")
        add("--play-and-exit")
        if (media.startSeconds > 0) add("--start-time=${PlayerAppTemplates.wholeSeconds(media.startSeconds)}")
        if (media.title.isNotBlank()) add("--meta-title=${media.title}")
        // VLC shows the first slave: the subtitle the built-in player would show goes first.
        val subtitles = media.subtitles.sortedByDescending { it.selected }.map { it.url }.filter(::vlcTakesSubtitle)
        if (subtitles.isNotEmpty()) add("--input-slave=${subtitles.joinToString("#")}")
        add(media.url)
        header(media, StreamHeaders.USER_AGENT)?.let { add(":http-user-agent=$it") }
        header(media, REFERER)?.let { add(":http-referrer=$it") }
    }

    /**
     * MPC-HC's and MPC-BE's switches for [media] (Help → Command Line Switches, the same in both): a new window that
     * plays at once, `/start` in milliseconds, and the subtitles saved with a download (`/sub` loads a file). They take
     * no title and no request headers.
     */
    fun mpc(media: PlayerAppMedia): List<String> = buildList {
        add(media.url)
        add("/new")
        add("/play")
        if (media.startSeconds.isFinite() && media.startSeconds > 0) {
            add("/start")
            add((floor(media.startSeconds).toLong() * 1000).toString())
        }
        for (subtitle in media.subtitles.filter { it.local }) {
            add("/sub")
            add(subtitle.url)
        }
    }

    /**
     * PotPlayer's switches for [media] (its `CmdLine.txt`): a new window (`/new`), `/seek` in seconds, `/title`, one
     * subtitle (`/sub`, a path or a URL: the one to show, else the first), `/user_agent`, `/referer`, and the other
     * headers in one `/headers`, a `Name: value` line each.
     */
    fun potPlayer(media: PlayerAppMedia): List<String> = buildList {
        add(media.url)
        add("/new")
        if (media.startSeconds > 0) add("/seek=${PlayerAppTemplates.wholeSeconds(media.startSeconds)}")
        if (media.title.isNotBlank()) add("/title=${media.title}")
        (media.subtitles.firstOrNull { it.selected } ?: media.subtitles.firstOrNull())?.let { add("/sub=${it.url}") }
        header(media, StreamHeaders.USER_AGENT)?.let { add("/user_agent=$it") }
        header(media, REFERER)?.let { add("/referer=$it") }
        val others = media.headers.filterKeys { !it.equals(StreamHeaders.USER_AGENT, ignoreCase = true) && !it.equals(REFERER, ignoreCase = true) }
        if (others.isNotEmpty()) add("/headers=" + others.entries.joinToString("\r\n") { (name, value) -> "$name: $value" })
    }

    /**
     * mpv's options without their `--`, so IINA and Celluloid can prefix them their own way. On mpv's command line
     * ([lists] false) each header and subtitle is one `-append`; through the client API ([lists] true: IINA,
     * Celluloid) the header list and the subtitle list go whole, their separators (`,`, and `:` off Windows) escaped in
     * the items.
     */
    private fun mpvOptions(media: PlayerAppMedia, ipcPath: String?, lists: Boolean): List<String> = buildList {
        if (ipcPath != null) add("input-ipc-server=$ipcPath")
        if (media.startSeconds > 0) add("start=${PlayerAppTemplates.wholeSeconds(media.startSeconds)}")
        if (media.title.isNotBlank()) add("force-media-title=${media.title}")
        header(media, StreamHeaders.USER_AGENT)?.let { add("user-agent=$it") }
        val fields = media.headers.filterKeys { !it.equals(StreamHeaders.USER_AGENT, ignoreCase = true) }.map { (name, value) -> "$name: $value" }
        val subtitles = media.subtitles.map { it.url }
        if (lists) {
            if (fields.isNotEmpty()) add("http-header-fields=" + fields.joinToString(",") { it.replace(",", "\\,") })
            if (subtitles.isNotEmpty()) add("sub-files=" + subtitles.joinToString(":") { it.replace(":", "\\:") })
        } else {
            for (field in fields) add("http-header-fields-append=$field")
            for (subtitle in subtitles) add("sub-files-append=$subtitle")
        }
    }

    /** An mpv option `name=value` with its value escaped for Celluloid's parser. */
    private fun celluloidEscaped(option: String): String {
        val name = option.substringBefore('=')
        val value = option.substringAfter('=')
        return name + "=" + value.replace("\\", "\\\\").replace("'", "\\'")
    }

    private fun header(media: PlayerAppMedia, name: String): String? = media.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * VLC 3 takes a subtitle as an input slave by its file extension, as it spells them (an address without one loads
     * as audio and fails), and `#` separates the slaves.
     */
    private fun vlcTakesSubtitle(url: String): Boolean {
        if ('#' in url) return false
        val name = url.substringBefore('?').substringAfterLast('/').substringAfterLast('\\')
        return name.substringAfterLast('.', "") in VLC_SUBTITLE_EXTENSIONS
    }

    private const val REFERER = "Referer"

    /** VLC 3's `SLAVE_SPU_EXTENSIONS` (`vlc_input_item.h`). */
    private val VLC_SUBTITLE_EXTENSIONS = setOf(
        "aqt", "ass", "cdg", "dks", "idx", "jss", "mpl2", "mpsub", "pjs", "psb", "rt", "sami", "sbv", "scc", "smi", "srt", "ssa", "stl",
        "sub", "ttml", "tt", "usf", "vtt", "webvtt",
    )
}
