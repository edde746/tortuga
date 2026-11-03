package com.edde746.tortuga.playback.resolve

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.readBody
import com.edde746.tortuga.playback.ErrorVideoSign
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.decodeURLPart
import io.ktor.http.parseUrl
import io.ktor.http.takeFrom
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readBuffer
import kotlinx.io.IOException
import kotlinx.io.readString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a picked stream's URL into what the player needs. It never touches a URL before [resolve] or
 * [resolveHead].
 *
 * The first request is one ranged GET for the first 64 KiB that follows redirects, which gives the final URL, the total
 * size and the hash's head chunk; the hash then takes one ranged GET on the final URL for the last 64 KiB. [resolve]
 * makes both; [resolveHead] makes the first and leaves the second to its caller, so a player can open the stream at
 * once. Every request carries the stream's `proxyHeaders.request` (its credentials only to the stream's origin, below).
 * The hash is best-effort: when the server's answers don't allow it (no size, a range other than the one asked for, a
 * failed tail request), the stream still resolves, without a hash. The first answer also tells an error video sent in
 * place of the file ([ResolvedStream.errorVideo]).
 *
 * Redirects: this class follows them itself, at most [MAX_REDIRECTS], through a copy of the client that follows none,
 * so one rule holds on every hop of a request for a stream (a download's too, in
 * [KtorTransport][com.edde746.tortuga.downloads.KtorTransport]): a `Location` that leaves http(s), or goes from https to
 * http, is refused ([redirectTarget]; the resolve fails as [ResolveFailure.UnsupportedUrl]), and `Authorization`,
 * `Cookie` and `Proxy-Authorization` go only to the stream URL's scheme, host and port: once a hop leaves it they are
 * dropped, from that hop on and from [ResolvedStream.headers] ([redirectHeaders]). Relative `Location`s resolve against
 * the URL that answered.
 *
 * Timeouts are this class's own, not the client's: an addon's service can take tens of seconds on the first byte while
 * it fetches a file, so each request lifts the client's request and socket timeouts (its connect timeout stays).
 *
 * @param client an app client (`net`), whose engine and plugins the copy that follows no redirects uses; requests set
 *   `expectSuccess = false`, `Cache-Control: no-store` (so a response cache never stores video bytes) and
 *   `Accept-Encoding: identity` unless the stream's headers set one.
 * @param timeout limit for the first request, redirects and slow first byte included.
 * @param hashTimeout limit for the tail request; when it runs out the stream resolves without a hash.
 */
class StreamResolver(
    client: HttpClient,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val hashTimeout: Duration = DEFAULT_HASH_TIMEOUT,
) {
    /** [client] leaving redirects to [get], which follows them under the rule above. */
    private val unredirected: HttpClient by lazy { client.config { followRedirects = false } }

    /** [resolveHead] and the hash it leaves pending: the stream with its hash, for callers that need it first. */
    suspend fun resolve(request: StreamRequest): ResolveResult {
        val result = resolveHead(request)
        if (result !is ResolveResult.Resolved) return result
        val pendingHash = result.pendingHash ?: return result
        return ResolveResult.Resolved(result.stream.copy(videoHash = pendingHash()))
    }

    /**
     * The first request alone: the stream with its final URL, size and file name, and its hash when the addon gave
     * one or the first answer holds the whole file. When the hash needs the tail request,
     * [ResolveResult.Resolved.pendingHash] makes it, for the caller to run while the stream already opens.
     */
    suspend fun resolveHead(request: StreamRequest): ResolveResult {
        val url = streamUrl(request.url) ?: return ResolveResult.Failed(ResolveFailure.UnsupportedUrl)
        val headers = StreamHeaders.sanitize(request.requestHeaders)

        val head = when (val result = attempt(timeout) { fetchHead(url, headers) }) {
            is Attempt.Done -> result.value
            is Attempt.Failed -> return ResolveResult.Failed(result.failure)
        }
        // 416 included: after fetchHead's retry it means an empty file or a server that serves no ranges at all.
        if (head.status != PARTIAL && head.status != OK) return ResolveResult.Failed(ResolveFailure.Http(head.status))

        val size = head.totalSize()
        val hintedHash = request.videoHash
        val stream = ResolvedStream(
            url = head.url.toString(),
            headers = head.headers,
            size = size ?: request.videoSize,
            videoHash = hintedHash,
            filename = request.filename ?: head.filename(),
            errorVideo = ErrorVideoSign.ofResolve(head.url, size, request.videoSize, head.body),
        )
        if (hintedHash != null || size == null || size == 0L) return ResolveResult.Resolved(stream)
        val length = OpenSubtitlesHash.chunkLength(size)
        val start = head.bytesFrom(0)?.takeIf { it.size >= length } ?: return ResolveResult.Resolved(stream)
        val headChunk = start.copyOf(length)
        if (start.size.toLong() >= size) {
            val tail = start.copyOfRange((size - length).toInt(), size.toInt())
            return ResolveResult.Resolved(stream.copy(videoHash = OpenSubtitlesHash.compute(size, headChunk, tail)))
        }
        val finalUrl = head.url
        return ResolveResult.Resolved(stream, pendingHash = { tailHash(finalUrl, stream.headers, size, headChunk) })
    }

    /**
     * The live HLS media playlist at [url] ([LivePlaylist]), read with [headers] (the stream's, as mpv sends them, under
     * the resolve's rule on every hop): a master playlist's first variant's, whose window the others share. Null when the
     * answer is no media playlist, not a 200, or slower than [PLAYLIST_TIMEOUT].
     */
    suspend fun livePlaylist(url: String, headers: Map<String, String>): LivePlaylist? {
        val start = streamUrl(url) ?: return null
        val text = attempt(PLAYLIST_TIMEOUT) {
            val master = playlistText(start, headers)
            val variant = LivePlaylist.firstVariant(master.text)
                ?.let { redirectTarget(master.url, it) ?: throw Abort(ResolveFailure.UnsupportedUrl) }
            if (variant == null) master.text else playlistText(variant, redirectHeaders(master.headers, start, variant)).text
        }
        return (text as? Attempt.Done)?.value?.let(LivePlaylist::parse)
    }

    /** The playlist at [url] ([get]); a body past [PLAYLIST_BYTES] is no playlist this player reads. */
    private suspend fun playlistText(url: Url, headers: Map<String, String>): Playlist =
        get(url, headers, configure = { header(HttpHeaders.CacheControl, "no-cache") }) { response, sent ->
            if (response.status.value != OK) throw Abort(ResolveFailure.Http(response.status.value))
            val body = response.readBody { it.readBuffer(PLAYLIST_BYTES + 1) }
            if (body.size > PLAYLIST_BYTES) throw Abort(ResolveFailure.UnsupportedUrl)
            Playlist(response.call.request.url, sent, body.readString())
        }

    /**
     * The first request. A 416 that reports a complete length under 64 KiB comes from a server that refuses a range
     * running past the end of a small file; it is asked again for exactly the file.
     */
    private suspend fun fetchHead(url: Url, headers: Map<String, String>): Answer {
        val head = fetch(url, headers, 0, CHUNK - 1L)
        val total = head.range?.total
        if (head.status != RANGE_NOT_SATISFIABLE || total == null || total !in 1 until CHUNK) return head
        return fetch(head.url, head.headers, 0, total - 1)
    }

    /** The hash of a [size]-byte file from [headChunk] and its tail from [url]; null when the answer allows none. */
    private suspend fun tailHash(url: Url, headers: Map<String, String>, size: Long, headChunk: ByteArray): String? {
        val length = headChunk.size
        val tailFrom = size - length
        val tail = when (val result = attempt(hashTimeout) { fetch(url, headers, tailFrom, size - 1) }) {
            is Attempt.Done -> result.value
            is Attempt.Failed -> return null
        }
        if (tail.totalSize() != size) return null
        val tailBytes = tail.bytesFrom(tailFrom)?.takeIf { it.size >= length } ?: return null
        return OpenSubtitlesHash.compute(size, headChunk, tailBytes.copyOf(length))
    }

    /**
     * GET with `Range: bytes=[first]-[last]` ([get]). Reads the part of a 206 that starts at [first]. A 200 comes from a
     * server that ignores `Range`: from it a request for the start reads the first chunk, or the whole body when it is no
     * larger than two chunks (so a small file needs no second request); a request for a later range reads nothing.
     */
    private suspend fun fetch(start: Url, headers: Map<String, String>, first: Long, last: Long): Answer =
        get(
            start,
            headers,
            configure = {
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
                header(HttpHeaders.Range, "bytes=$first-$last")
                header(HttpHeaders.CacheControl, "no-store")
                if (headers.keys.none { it.equals(HttpHeaders.AcceptEncoding, ignoreCase = true) }) {
                    header(HttpHeaders.AcceptEncoding, "identity")
                }
            },
        ) { response, sent -> answer(response, sent, first, last) }

    /**
     * GET [start] with the stream's [headers], following redirects itself (at most [MAX_REDIRECTS]): each hop sends the
     * headers [redirectHeaders] leaves it, then what [configure] adds; a `Location` [redirectTarget] refuses fails it as
     * [ResolveFailure.UnsupportedUrl]. [read] takes the first answer that isn't a redirect, with the stream headers its
     * request sent, and reads its body through [readBody], which cancels what it leaves unread.
     */
    private suspend fun <T> get(
        start: Url,
        headers: Map<String, String>,
        configure: HttpRequestBuilder.() -> Unit,
        read: suspend (response: HttpResponse, sent: Map<String, String>) -> T,
    ): T {
        var url = start
        var sent = headers
        repeat(MAX_REDIRECTS + 1) {
            val hop: Hop<T> = unredirected.prepareGet(url) {
                expectSuccess = false
                for ((name, value) in sent) header(name, value)
                configure()
            }.execute { response ->
                val location = response.headers[HttpHeaders.Location]
                if (response.status.value in REDIRECTS && location != null) {
                    Hop.Redirect(redirectTarget(response.call.request.url, location) ?: throw Abort(ResolveFailure.UnsupportedUrl))
                } else {
                    Hop.Answered(read(response, sent))
                }
            }
            when (hop) {
                is Hop.Answered -> return hop.value
                is Hop.Redirect -> {
                    sent = redirectHeaders(sent, start, hop.target)
                    url = hop.target
                }
            }
        }
        throw Abort(ResolveFailure.TooManyRedirects)
    }

    private suspend fun answer(response: HttpResponse, sent: Map<String, String>, first: Long, last: Long): Answer {
        val status = response.status.value
        val range = ContentRange.parse(response.headers[HttpHeaders.ContentRange])
        val encoding = response.headers[HttpHeaders.ContentEncoding]
        val length = response.contentLength()?.takeIf { encoding == null || encoding.equals("identity", true) }
        val rangeFirst = range?.first
        val rangeLast = range?.last
        val limit = when {
            status == PARTIAL && rangeFirst == first && rangeLast != null -> (minOf(rangeLast, last) - first + 1).toInt()
            status == OK && first == 0L -> if (length != null && length <= 2L * CHUNK) length.toInt() else CHUNK
            else -> 0
        }
        val body = if (limit > 0) response.readBody { it.readUpTo(limit) } else ByteArray(0)
        return Answer(
            url = response.call.request.url,
            headers = sent,
            status = status,
            range = range,
            contentLength = length,
            disposition = response.headers[HttpHeaders.ContentDisposition],
            body = body,
        )
    }

    /**
     * Runs [block] within [limit], mapping its failures: a transport failure is [ResolveFailure.Timeout] or
     * [ResolveFailure.Network]; anything else is this code's own, logged with its trace, [ResolveFailure.Unexpected].
     * Cancellation of the caller propagates.
     */
    private suspend fun <T : Any> attempt(limit: Duration, block: suspend () -> T): Attempt<T> = try {
        withTimeoutOrNull(limit) { block() }?.let { Attempt.Done(it) } ?: Attempt.Failed(ResolveFailure.Timeout)
    } catch (e: Abort) {
        Attempt.Failed(e.failure)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Attempt.Failed(e.toFailure())
    } catch (e: Exception) {
        Log.e(TAG, e) { "resolving failed in its own code (${e::class.simpleName})" }
        Attempt.Failed(ResolveFailure.Unexpected)
    }

    /** Ktor's timeouts are [IOException]s too. */
    private fun IOException.toFailure(): ResolveFailure = when (this) {
        is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException -> ResolveFailure.Timeout
        else -> ResolveFailure.Network(this::class.simpleName ?: "IOException")
    }

    /**
     * One server answer, with the stream headers its request sent ([get]): those for [url] and what is asked of it next.
     * [body] starts at [range]'s first byte on a 206 and at byte 0 on a 200.
     */
    private class Answer(
        val url: Url,
        val headers: Map<String, String>,
        val status: Int,
        val range: ContentRange?,
        val contentLength: Long?,
        val disposition: String?,
        val body: ByteArray,
    ) {
        /** Complete length: `Content-Range`'s on a 206, `Content-Length` on a 200. */
        fun totalSize(): Long? = when (status) {
            PARTIAL -> range?.total
            OK -> contentLength
            else -> null
        }

        /** The body when it starts at byte [offset] of the file, else null (a server that sent another range). */
        fun bytesFrom(offset: Long): ByteArray? = when (status) {
            PARTIAL -> body.takeIf { range?.first == offset }
            OK -> body.takeIf { offset == 0L }
            else -> null
        }

        fun filename(): String? = filenameFromDisposition(disposition) ?: filenameFromPath(url)
    }

    private sealed interface Hop<out T> {
        class Answered<T>(val value: T) : Hop<T>
        class Redirect(val target: Url) : Hop<Nothing>
    }

    /** A playlist's text, the URL that answered it and the stream headers its request sent ([get]). */
    private class Playlist(val url: Url, val headers: Map<String, String>, val text: String)

    private sealed interface Attempt<out T> {
        class Done<T>(val value: T) : Attempt<T>
        class Failed(val failure: ResolveFailure) : Attempt<Nothing>
    }

    /** Ends a resolve with [failure] from inside the request code. */
    private class Abort(val failure: ResolveFailure) : Exception()

    companion object {
        /** An addon's service can take tens of seconds on a first request while it fetches the file. */
        val DEFAULT_TIMEOUT: Duration = 60.seconds
        val DEFAULT_HASH_TIMEOUT: Duration = 15.seconds
        const val MAX_REDIRECTS = 10
        private const val TAG = "resolve"

        /** A live playlist's read ([livePlaylist]), and how much of it is read: hours of a live window's segments. */
        val PLAYLIST_TIMEOUT: Duration = 10.seconds
        private const val PLAYLIST_BYTES = 4L * 1024 * 1024

        private const val CHUNK = OpenSubtitlesHash.CHUNK_SIZE
        private const val OK = 200
        private const val PARTIAL = 206
        private const val RANGE_NOT_SATISFIABLE = 416
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts", "wmv", "flv", "mpg", "mpeg", "ogv", "3gp",
        )

        private fun streamUrl(text: String): Url? = parseUrl(text.trim())?.takeIf { it.isHttp() }

        private fun Url.isHttp() = protocolOrNull?.name in setOf("http", "https") && host.isNotEmpty()

        /**
         * Where a `Location` (or a playlist's URI) [location] in the answer from [from] leads, a relative one resolved
         * against [from]; null, the hop refused, when it isn't an http(s) URL with a host or it goes from https to http.
         */
        internal fun redirectTarget(from: Url, location: String): Url? {
            val target = runCatching {
                URLBuilder(from).apply {
                    parameters.clear()
                    fragment = ""
                    takeFrom(location)
                }.build()
            }.getOrNull() ?: return null
            val downgrade = from.protocol.name == "https" && target.protocol.name == "http"
            return target.takeIf { it.isHttp() && !downgrade }
        }

        /**
         * The stream headers for a hop to [target] of a request that started at [origin], from [headers], those the hop
         * before sent: without `Authorization`, `Cookie` and `Proxy-Authorization` when [target] isn't on [origin]'s
         * scheme, host and port. Once dropped they stay dropped: a hop back to [origin] goes where another host sent it.
         */
        internal fun redirectHeaders(headers: Map<String, String>, origin: Url, target: Url): Map<String, String> =
            if (target.sameOrigin(origin)) headers else headers.filterKeys { name -> CREDENTIALS.none { it.equals(name, ignoreCase = true) } }

        private val CREDENTIALS = listOf(HttpHeaders.Authorization, HttpHeaders.Cookie, HttpHeaders.ProxyAuthorization)

        private fun Url.sameOrigin(other: Url) =
            protocol.name == other.protocol.name && host.equals(other.host, ignoreCase = true) && port == other.port

        private suspend fun ByteReadChannel.readUpTo(limit: Int): ByteArray {
            val buffer = ByteArray(limit)
            var filled = 0
            while (filled < limit) {
                val read = readAvailable(buffer, filled, limit - filled)
                if (read < 0) break
                filled += read
            }
            return if (filled == limit) buffer else buffer.copyOf(filled)
        }

        private val extendedFilename = Regex("""(?:^|;)\s*filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
        private val plainFilename = Regex("""(?:^|;)\s*filename\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^;]*))""", RegexOption.IGNORE_CASE)
        private val quotedPair = Regex("""\\(.)""")

        /** `filename*` (RFC 6266 / 8187, percent-encoded) wins over `filename`. Directory parts are dropped. */
        internal fun filenameFromDisposition(value: String?): String? {
            if (value == null) return null
            val extended = extendedFilename.find(value)?.groupValues?.get(1)?.trim()
                ?.substringAfter('\'', "")?.substringAfter('\'', "")?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { it.decodeURLPart() }.getOrNull() }
            val plain = plainFilename.find(value)
                ?.let { match -> match.groupValues[1].replace(quotedPair, "$1").ifEmpty { match.groupValues[2] } }
            return (extended ?: plain)?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.takeIf { it.isNotEmpty() }
        }

        internal fun filenameFromPath(url: Url): String? = url.segments.lastOrNull()
            ?.takeIf { it.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS && !it.startsWith('.') }
    }
}
