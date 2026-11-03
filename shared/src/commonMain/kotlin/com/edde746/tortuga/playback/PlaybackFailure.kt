package com.edde746.tortuga.playback

import com.edde746.tortuga.format.formatByteSize
import com.edde746.tortuga.format.formatTimecode
import com.edde746.tortuga.log.Redactor
import com.edde746.tortuga.playback.resolve.ResolveFailure
import com.edde746.tortuga.playback.resolve.displayText
import com.edde746.tortuga.strings.T

/**
 * Why the current stream can't play: what the playback session surfaces and the player store words. Neither variant
 * holds a URL: [ResolveFailure] carries no exception text, and the session builds [Engine] errors with
 * [withoutSecrets].
 */
sealed interface PlaybackFailure {
    /** The picked stream didn't resolve to a playable URL (at first, or on the one re-resolve after an expiry). */
    data class Resolve(val failure: ResolveFailure) : PlaybackFailure

    /** The engine couldn't open or keep playing the stream; [EngineError.httpStatus] when an HTTP answer said why. */
    data class Engine(val error: EngineError) : PlaybackFailure

    /**
     * The stream answered with an error video instead of the video ([ErrorVideoSign]): it isn't played as the video,
     * and nothing about it is recorded. [isEpisode] and [isMovie] say what it stood in for, as the error words it.
     */
    data class ErrorVideo(val sign: ErrorVideoSign, val isEpisode: Boolean, val isMovie: Boolean) : PlaybackFailure
}

/**
 * The failure as the player words it (its error card's message); a hand-off to a player app (`PlayerAppHandoff`) words
 * its failures alike.
 */
internal val PlaybackFailure.displayText: String
    get() = when (this) {
        is PlaybackFailure.Resolve -> when (val resolve = failure) {
            ResolveFailure.Timeout -> T.player.error.resolveTimeout
            is ResolveFailure.Http -> httpText(resolve.status)
            else -> resolve.displayText
        }
        // The engine's own text is English (mpv's): it went to the log (PlaybackSession), the player says what it means.
        is PlaybackFailure.Engine -> error.httpStatus?.let(::httpText) ?: when (error.kind) {
            EngineErrorKind.OPEN -> T.player.error.cantOpen
            EngineErrorKind.FORMAT -> T.player.error.unsupportedFormat
            EngineErrorKind.DECODE -> T.player.error.cantDecode
            EngineErrorKind.OTHER -> T.player.error.engineFailed
        }
        is PlaybackFailure.ErrorVideo -> {
            val why = when (val sign = sign) {
                ErrorVideoSign.Url -> T.player.errorVideo.byUrl
                is ErrorVideoSign.Size -> T.player.errorVideo.bySize(formatByteSize(sign.servedBytes), formatByteSize(sign.hintedBytes))
                is ErrorVideoSign.Duration -> T.player.errorVideo.byDuration(formatTimecode(sign.seconds), formatTimecode(sign.knownSeconds))
            }
            listOf(T.player.errorVideo.sent, why, T.player.errorVideo.kept).joinToString(" ")
        }
    }

private fun httpText(status: Int): String = when (status) {
    403, 410 -> T.player.error.expired
    404 -> T.player.error.notFound
    else -> T.player.error.http(status.toString())
}

/**
 * The error with its message fit to keep in the session's state, which logs and reports print (the player shows its
 * [EngineError.kind] instead): URLs (stream links carry API keys) and key-shaped tokens are cut out ([Redactor]).
 */
internal fun EngineError.withoutSecrets(): EngineError {
    // Prose punctuation right after a URL stays: "Failed to open <url>: 403" keeps its colon.
    val text = URL.replace(message) { match -> match.value.substring(match.value.trimEnd { it in TRAILING_PUNCTUATION }.length) }
        .let(Redactor::redact)
        .replace(SPACE_BEFORE_PUNCTUATION, "$1")
        .replace(SPACES, " ")
        .trim()
        .trimStart(':', ',', ';', '.')
        .trimEnd(':', ',', ';')
        .trim()
    return copy(message = text)
}

// An apostrophe inside a URL is part of it (`Night's.Journey.mkv`), a closing one is not ([Redactor]'s rule). The
// second branch is a peer-to-peer link.
private val URL = Regex("""\b[A-Za-z][A-Za-z0-9+.\-]*://[^\s"<>]*|\bmagnet:\?[^\s"'<>]*""", RegexOption.IGNORE_CASE)
private const val TRAILING_PUNCTUATION = ").,;:!?]}'"
private val SPACE_BEFORE_PUNCTUATION = Regex("""\s+([:,;.!?])""")
private val SPACES = Regex("""\s{2,}""")
