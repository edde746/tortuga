package com.edde746.tortuga.downloads

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.readBody
import com.edde746.tortuga.playback.resolve.ContentRange
import com.edde746.tortuga.playback.resolve.StreamResolver
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * [DownloadTransport] over the app's Ktor client ([AppGraph.http][com.edde746.tortuga.app.AppGraph.http]), in this
 * process: Android (inside its foreground worker) and desktop. Each transfer is one GET with `Range: bytes=<offset>-`
 * and `If-Range` when resuming, `Cache-Control: no-store` and `Accept-Encoding: identity`, the request timeout lifted
 * as the stream resolver lifts it (the socket's idle timeout stays, at [STALL_TIMEOUT], so a dead connection ends), the
 * body appended to the `.part` file; a 206 must start at the offset with the expected total, a 200 to a resumed request
 * means the file changed ([TransferResult.Changed]; the `.part` is emptied and the manager restarts it).
 *
 * Redirects are followed here, at most 10, under the resolve's rule for every hop of a stream request
 * ([StreamResolver.redirectTarget], [StreamResolver.redirectHeaders]): `Authorization`, `Cookie` and
 * `Proxy-Authorization` go only to the link's own scheme, host and port, and a `Location` from https to http, or out of
 * http(s), ends the transfer as [TransferResult.Http] with the redirect's status.
 *
 * A failed write is [TransferResult.WriteFailed], full volume or not: the OS words its error in the user's language, so
 * the manager tells a full volume by the location's free space ([DownloadWorker]).
 *
 * [start] runs [transfer] in this transport's own scope, one run of an id at a time: a start while the id's stopped run
 * still unwinds (a write held up by a slow volume, past [Transfers.STOP_WAIT]) waits for that run to end, and the run it
 * replaced reports no result, so its late [TransferResult.Stopped] never ends the new one. A host with a coroutine of
 * its own (Android's worker) calls [transfer] there instead.
 */
class KtorTransport internal constructor(
    http: HttpClient,
    private val io: CoroutineContext,
    private val fileSystem: FileSystem,
    private val timeSource: TimeSource,
) : DownloadTransport {
    constructor(http: HttpClient) : this(http, Dispatchers.IO, SystemFileSystem, TimeSource.Monotonic)

    /** [http] leaving redirects to [fetch], which follows them under the resolve's rule. */
    private val unredirected: HttpClient by lazy { http.config { followRedirects = false } }
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    override fun start(request: TransferRequest, listener: TransferListener) {
        val previous = jobs.value[request.id]
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                previous?.join()
                transfer(request, listener)
            } catch (_: CancellationException) {
                TransferResult.Stopped
            }
            val run = coroutineContext.job
            var current = false
            jobs.update { running ->
                current = running[request.id] === run
                if (current) running - request.id else running
            }
            if (current) {
                listener.onResult(request.id, result)
            } else {
                Log.d("downloads") { "transfer ${request.id}: a newer run took its place; its ${result::class.simpleName} goes unreported" }
            }
        }
        jobs.update { it + (request.id to job) }
        job.start()
    }

    override fun stop(id: String) {
        jobs.value[id]?.cancel()
    }

    /**
     * Moves [request]'s bytes into its `.part` file in the calling coroutine, reporting [TransferListener.onResponse]
     * once the answer is accepted and [TransferListener.onProgress] at most every [PROGRESS_INTERVAL] (and at the end);
     * returns the result instead of calling [TransferListener.onResult]. Cancelling the coroutine keeps what arrived
     * and rethrows.
     */
    suspend fun transfer(request: TransferRequest, listener: TransferListener): TransferResult = withContext(io) {
        val part = Path(request.partPath)
        try {
            prepare(part, request.offset) ?: return@withContext TransferResult.Changed
            fetch(request, part, listener)
        } catch (e: CancellationException) {
            throw e
        } catch (_: WriteError) {
            TransferResult.WriteFailed
        } catch (e: Exception) {
            when (e) {
                is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException -> TransferResult.Network("Timeout")
                else -> TransferResult.Network(e::class.simpleName ?: "Exception")
            }
        }
    }

    /** The part file ready for [offset]: emptied for 0; null when it doesn't hold exactly [offset] bytes to continue. */
    private fun prepare(part: Path, offset: Long): Unit? = writing {
        part.parent?.let { fileSystem.createDirectories(it) }
        val existing = fileSystem.metadataOrNull(part)?.size ?: 0L
        when {
            offset == 0L -> if (existing > 0) fileSystem.sink(part).close()
            existing != offset -> return@writing null
        }
        Unit
    }

    private suspend fun fetch(request: TransferRequest, part: Path, listener: TransferListener): TransferResult {
        val start = Url(request.url)
        var url = start
        var sent = request.headers
        repeat(MAX_REDIRECTS + 1) {
            val outcome = unredirected.prepareGet(url) {
                expectSuccess = false
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = STALL_TIMEOUT.inWholeMilliseconds
                }
                for ((name, value) in sent) {
                    if (name.equals(HttpHeaders.Range, true) || name.equals(HttpHeaders.IfRange, true)) continue
                    header(name, value)
                }
                if (request.offset > 0) {
                    header(HttpHeaders.Range, "bytes=${request.offset}-")
                    request.validator?.let { header(HttpHeaders.IfRange, it) }
                }
                header(HttpHeaders.CacheControl, "no-store")
                if (request.headers.keys.none { it.equals(HttpHeaders.AcceptEncoding, ignoreCase = true) }) {
                    header(HttpHeaders.AcceptEncoding, "identity")
                }
            }.execute { response ->
                val location = response.headers[HttpHeaders.Location]
                if (response.status.value in REDIRECTS && location != null) {
                    StreamResolver.redirectTarget(response.call.request.url, location)?.let { Hop.Redirect(it) } ?: Hop.Done(TransferResult.Http(response.status.value))
                } else {
                    Hop.Done(receive(response, request, part, listener))
                }
            }
            when (outcome) {
                is Hop.Done -> return outcome.result
                is Hop.Redirect -> {
                    sent = StreamResolver.redirectHeaders(sent, start, outcome.target)
                    url = outcome.target
                }
            }
        }
        return TransferResult.Network("TooManyRedirects")
    }

    private suspend fun receive(response: HttpResponse, request: TransferRequest, part: Path, listener: TransferListener): TransferResult {
        val status = response.status.value
        val validator = response.headers[HttpHeaders.ETag] ?: response.headers[HttpHeaders.LastModified]
        val encoding = response.headers[HttpHeaders.ContentEncoding]
        val length = response.contentLength()?.takeIf { encoding == null || encoding.equals("identity", true) }
        val total: Long? = when (status) {
            PARTIAL -> {
                val range = ContentRange.parse(response.headers[HttpHeaders.ContentRange])
                if (range?.first != request.offset || !sameTotal(request.expectedSize, range.total)) return changed(part)
                range.total ?: request.expectedSize
            }
            OK -> {
                if (request.offset > 0) return changed(part)
                if (!sameTotal(request.expectedSize, length)) return changed(part)
                length ?: request.expectedSize
            }
            else -> return TransferResult.Http(status)
        }
        listener.onResponse(request.id, validator)
        val bytes = response.readBody { append(it, request, part, total, listener) }
        if (total != null && bytes < total) return TransferResult.Network("Truncated")
        return TransferResult.Completed(bytes, validator)
    }

    /** Appends [channel] after the [TransferRequest.offset] bytes there; returns the part's size after it. */
    private suspend fun append(channel: ByteReadChannel, request: TransferRequest, part: Path, total: Long?, listener: TransferListener): Long {
        var bytes = request.offset
        var reported = timeSource.markNow()
        listener.onProgress(request.id, bytes, total)
        val sink = writing { fileSystem.sink(part, append = true).buffered() }
        try {
            while (!channel.exhausted()) {
                val chunk = channel.readBuffer(CHUNK_BYTES)
                bytes += writing { sink.transferFrom(chunk) }
                if (reported.elapsedNow() >= PROGRESS_INTERVAL) {
                    reported = timeSource.markNow()
                    listener.onProgress(request.id, bytes, total)
                }
            }
        } finally {
            writing { sink.close() }
        }
        listener.onProgress(request.id, bytes, total)
        return bytes
    }

    /** A resumed answer that doesn't continue the file: the part is emptied, the manager starts over. */
    private fun changed(part: Path): TransferResult {
        writing { if (fileSystem.exists(part)) fileSystem.sink(part).close() }
        return TransferResult.Changed
    }

    private sealed interface Hop {
        class Done(val result: TransferResult) : Hop
        class Redirect(val target: Url) : Hop
    }

    companion object {
        /** The socket may go this long without a byte before the transfer counts as a network failure. */
        val STALL_TIMEOUT: Duration = 2.minutes
        val PROGRESS_INTERVAL: Duration = 500.milliseconds
        private const val MAX_REDIRECTS = 10
        private const val CHUNK_BYTES = 256L * 1024
        private const val OK = 200
        private const val PARTIAL = 206
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        private fun sameTotal(expected: Long?, total: Long?) = expected == null || total == null || expected == total
    }
}

/**
 * For transports that receive a response body as a file of its own (Apple's background `URLSession` download tasks):
 * the shared rules for what a response means for the `.part` file.
 */
object TransferFiles {
    /**
     * Puts the body at [incomingPath], answered to [request] with [status] and these headers, into
     * [TransferRequest.partPath] and deletes [incomingPath]: appended after a 206 whose `Content-Range` starts at the
     * request's offset (with the expected total), in place of it after a 200 to a request from offset 0 whose
     * [contentLength] is the expected size (or either is unknown). A 200 to a resumed request or of another length, or
     * a 206 that doesn't continue the file, is [TransferResult.Changed] (the part emptied after a 200, as
     * [KtorTransport] does); another status is [TransferResult.Http]. Only the request's part path, offset and expected
     * size are read (not its URL or headers).
     */
    fun adopt(
        request: TransferRequest,
        incomingPath: String,
        status: Int,
        contentRange: String?,
        contentLength: Long?,
        etag: String?,
        lastModified: String?,
    ): TransferResult = adopt(SystemFileSystem, request, incomingPath, status, contentRange, contentLength, etag, lastModified)

    internal fun adopt(
        fileSystem: FileSystem,
        request: TransferRequest,
        incomingPath: String,
        status: Int,
        contentRange: String?,
        contentLength: Long?,
        etag: String?,
        lastModified: String?,
    ): TransferResult {
        val incoming = Path(incomingPath)
        val part = Path(request.partPath)
        val validator = etag ?: lastModified
        return try {
            val result = when (status) {
                200 -> if (request.offset > 0 || request.expectedSize.let { it != null && contentLength != null && it != contentLength }) {
                    if (fileSystem.exists(part)) fileSystem.sink(part).close()
                    TransferResult.Changed
                } else {
                    part.parent?.let { fileSystem.createDirectories(it) }
                    fileSystem.atomicMove(incoming, part)
                    TransferResult.Completed(fileSystem.metadataOrNull(part)?.size ?: 0L, validator)
                }
                206 -> {
                    val range = ContentRange.parse(contentRange)
                    val existing = fileSystem.metadataOrNull(part)?.size ?: 0L
                    val expected = request.expectedSize
                    if (range?.first != request.offset || existing != request.offset || (expected != null && range.total != null && range.total != expected)) {
                        TransferResult.Changed
                    } else {
                        fileSystem.sink(part, append = true).buffered().use { sink ->
                            fileSystem.source(incoming).use { source -> sink.transferFrom(source) }
                        }
                        TransferResult.Completed(fileSystem.metadataOrNull(part)?.size ?: 0L, validator)
                    }
                }
                else -> TransferResult.Http(status)
            }
            result
        } catch (e: IOException) {
            logWriteFailure(e)
            TransferResult.WriteFailed
        } catch (e: Exception) {
            // Swift calls this: nothing may escape it.
            Log.w("downloads") { "adopting a body failed: ${e::class.simpleName}" }
            TransferResult.WriteFailed
        } finally {
            runCatching { fileSystem.delete(incoming, mustExist = false) }
        }
    }
}

/** A write to the part file failed: the transfer ends [TransferResult.WriteFailed]. */
private class WriteError : Exception()

/** Runs a file operation, turning its I/O failure into a [WriteError]. */
private inline fun <T> writing(block: () -> T): T = try {
    block()
} catch (e: IOException) {
    logWriteFailure(e)
    throw WriteError()
}

/**
 * Logs a failed write by its class alone. Its message is neither logged nor read: it can hold a path, and its words are
 * the OS's, in the user's language (the manager tells a full volume by the location's free space).
 */
private fun logWriteFailure(e: IOException) {
    Log.w("downloads") { "write failed: ${e::class.simpleName}" }
}
