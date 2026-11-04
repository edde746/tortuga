package com.edde746.tortuga.addons

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.log.Redactor
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.DropCounter
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.TransportUrl
import com.edde746.tortuga.net.AddonResponseCache
import com.edde746.tortuga.net.readBody
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readString
import kotlinx.serialization.SerializationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A manifest and the exact body it was decoded from (what the registry persists). */
data class FetchedManifest(val manifest: Manifest, val json: String)

/**
 * The addon HTTP transport (stremio-core `AddonHTTPTransport`) over the app's [client]. Resource responses go through
 * [cache] when given. With a [scope], a stale-while-revalidate hit is refreshed there, and each resource request runs
 * there to its answer, which every caller asking for it meanwhile gets ([flight]); without one, stale entries are
 * refetched in the foreground and each caller makes its own request. Bodies are read and decoded on [parseDispatcher],
 * off the caller's (main) thread; the result comes back in the caller's context, and a cached response never leaves
 * it. Failures are [AddonException]s. A list answer whose every element is malformed is [AddonError.InvalidResponse],
 * never kept: what the addon sent can't be read, which an empty answer would hide.
 *
 * Every request, a shared one included, ends within [REQUEST_TIMEOUT] (as [AddonError.Timeout]), the body read
 * included, and reads at most [MAX_BODY_BYTES] of its body (a longer one is [AddonError.InvalidResponse]): an addon
 * that sends a byte now and then can't hold its URL's shared request, and every later ask joining it, until the app
 * restarts, nor a huge answer fill the memory.
 */
class AddonTransport(
    private val client: HttpClient,
    private val cache: AddonResponseCache? = null,
    /** Where stale entries are refreshed and requests run to their answer ([flight]): the app's lifetime. */
    private val scope: CoroutineScope? = null,
    /** Where answers are read, decoded and prepared ([AddonAggregator] and [AddonRegistry] work there too). */
    internal val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** The resource requests on their way, by URL ([flight]). */
    private val inFlight = mutableMapOf<String, CompletableDeferred<AddonResponse>>()
    private val inFlightLock = Mutex()

    /** Fetches and decodes the manifest at [transportUrl]. */
    suspend fun manifest(transportUrl: TransportUrl): FetchedManifest =
        withContext(parseDispatcher) {
            val body = get(transportUrl.value)
            val drops = DropCounter()
            val manifest = try {
                Manifest.decode(body, drops)
            } catch (e: SerializationException) {
                throw AddonException(AddonError.InvalidManifest(Redactor.redact(e.message.orEmpty())), e)
            } catch (e: IllegalArgumentException) {
                throw AddonException(AddonError.InvalidManifest(Redactor.redact(e.message.orEmpty())), e)
            }
            if (drops.count > 0) {
                Log.w(TAG) { "${manifest.name}: ${drops.count} malformed resource(s) or catalog(s) left out of the manifest, the first: ${drops.first}" }
            }
            FetchedManifest(manifest, body)
        }

    /**
     * Requests [request] from its addon, from [cache] when it holds a usable response. [fresh] (a refresh the user asked
     * for) skips the cache's fresh and stale answers and asks the addon; its answer is stored as any other, and a failed
     * request still falls back to the cache's `staleError` answer. Either way a request already on its way is joined.
     */
    suspend fun resource(request: ResourceRequest, fresh: Boolean = false): AddonResponse {
        if (!fresh) cached(request)?.let { return it }
        val url = TransportUrl.resourceUrl(request.base, request.path)
        val cache = cache ?: return answer(url)
        return try {
            answer(url)
        } catch (e: AddonException) {
            cache.staleIfError(url) ?: throw e
        }
    }

    /**
     * [request]'s response if [cache] holds a usable one, decoded already: a fresh one, or a stale one while it is
     * refreshed in [scope]. Null otherwise. Runs in the caller's context.
     */
    internal suspend fun cached(request: ResourceRequest): AddonResponse? {
        val cache = cache ?: return null
        val url = TransportUrl.resourceUrl(request.base, request.path)
        return when (val hit = cache.lookup(url)) {
            is AddonResponseCache.Lookup.Fresh -> hit.response
            is AddonResponseCache.Lookup.Stale -> scope?.let {
                revalidate(url, it)
                hit.response
            }
            AddonResponseCache.Lookup.Miss -> null
        }
    }

    /**
     * Sends [request] as an event (`player`, `library`): a GET of its URL each time, past the cache and any request on
     * its way, whose answer is not read beyond its status (its body is left unread).
     */
    suspend fun send(request: ResourceRequest) {
        val url = TransportUrl.resourceUrl(request.base, request.path)
        withContext(parseDispatcher) { exchange(url) {} }
    }

    /** Refreshes [url]'s stale entry in [scope]; a failure leaves the stale one, logged. */
    private fun revalidate(url: String, scope: CoroutineScope) {
        scope.launch {
            try {
                flight(url, scope).await()
            } catch (e: AddonException) {
                Log.d(TAG) { "revalidating $url failed: ${e.error}" }
            }
        }
    }

    /** [url]'s answer: the request's on its way in [scope] ([flight]), or without one the caller's own. */
    private suspend fun answer(url: String): AddonResponse {
        val scope = scope ?: return fetch(url)
        return flight(url, scope).await()
    }

    /**
     * The request for [url] on its way, or a new one launched in [scope], on [parseDispatcher] where [fetch] works (no
     * hop through the scope's main thread on the way). It runs to its answer even when the caller that started it gives
     * up: Detail stops its own ask as the Streams list opens, and the list then gets that answer rather than asking a
     * slow addon again, so the addon answers once (its links stay good, one request of an addon's rate limit
     * spent). Every caller asking meanwhile gets that answer or failure.
     */
    private suspend fun flight(url: String, scope: CoroutineScope): Deferred<AddonResponse> = inFlightLock.withLock {
        inFlight.getOrPut(url) {
            CompletableDeferred<AddonResponse>().also { answer ->
                scope.launch(parseDispatcher) {
                    // Kept for the callers waiting, whose await throws it.
                    val result = try {
                        Result.success(fetch(url))
                    } catch (e: Throwable) {
                        Result.failure(e)
                    }
                    withContext(NonCancellable) { inFlightLock.withLock { inFlight.remove(url) } }
                    result.fold({ answer.complete(it) }, { answer.completeExceptionally(it) })
                }
            }
        }
    }

    private suspend fun fetch(url: String): AddonResponse = withContext(parseDispatcher) {
        val body = get(url)
        val response = try {
            AddonResponse.decode(body)
        } catch (e: SerializationException) {
            throw AddonException(AddonError.InvalidResponse(Redactor.redact(e.message.orEmpty())), e)
        } catch (e: IllegalArgumentException) {
            throw AddonException(AddonError.InvalidResponse(Redactor.redact(e.message.orEmpty())), e)
        }
        if (response.everyElementDropped) {
            throw AddonException(AddonError.InvalidResponse(Redactor.redact("all ${response.sent} elements malformed, the first: ${response.firstDrop}")))
        }
        cache?.store(url, response, body.length.toLong())
        response
    }

    private suspend fun get(url: String): String = exchange(url) { response ->
        val body = response.readBody { it.readBuffer(MAX_BODY_BYTES + 1) }
        if (body.size > MAX_BODY_BYTES) throw AddonException(AddonError.InvalidResponse("longer than $MAX_BODY_BYTES bytes"))
        body.readString()
    }

    /** A GET of [url] whose successful response [read] takes; any failure is an [AddonException]. */
    private suspend fun <T : Any> exchange(url: String, read: suspend (HttpResponse) -> T): T {
        try {
            return withTimeoutOrNull(REQUEST_TIMEOUT) {
                client.prepareGet(url).execute { response ->
                    if (!response.status.isSuccess()) throw AddonException(AddonError.Http(response.status.value))
                    read(response)
                }
            } ?: throw AddonException(AddonError.Timeout)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AddonException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw AddonException(AddonError.Timeout, e)
        } catch (e: ConnectTimeoutException) {
            throw AddonException(AddonError.Timeout, e)
        } catch (e: SocketTimeoutException) {
            throw AddonException(AddonError.Timeout, e)
        } catch (e: Exception) {
            throw AddonException(AddonError.Network(Redactor.redact(e.message ?: e::class.simpleName.orEmpty())), e)
        }
    }

    companion object {
        /**
         * How long one request may take, its body included: twice the wait of [AddonAggregator]'s callers, so an answer
         * still reaches a caller that joined the request late.
         */
        val REQUEST_TIMEOUT: Duration = 60.seconds

        /** 32 MiB: far above any catalog, meta or stream list an addon sends. */
        const val MAX_BODY_BYTES: Long = 32L * 1024 * 1024

        private const val TAG = "addons"
    }
}
