package com.edde746.tortuga.addons

import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.model.TransportUrl
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.net.AddonResponseCache
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class AddonTransportTest {
    private val request = ResourceRequest("https://addon.example.com/cfg=1/manifest.json".transportUrl(), ResourcePath("stream", "movie", "tt1"))

    /** Serves `https://cdn.example.com/v<n>.mkv` on the n-th request, with [hints] as the cache fields, [delayMillis] late. */
    private class Server(var hints: String, var failing: Boolean = false, val delayMillis: Long = 0) {
        var requests = 0
    }

    private fun TestScope.transport(server: Server, cache: AddonResponseCache, revalidate: Boolean = true) = AddonTransport(
        mockClient { request ->
            assertEquals("https://addon.example.com/cfg=1/stream/movie/tt1.json", request.url.toString())
            server.requests++
            delay(server.delayMillis)
            if (server.failing) json("{}", HttpStatusCode.BadGateway) else json(streamsBody("https://cdn.example.com/v${server.requests}.mkv", extra = server.hints))
        },
        cache,
        if (revalidate) backgroundScope else null,
        parseDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun AddonResponse.url() = ((content as ResourceContent.Streams).items.single().source as StreamSource.Url).url

    @Test
    fun freshResponsesAreServedFromTheCache() = runTest {
        val clock = TestTimeSource()
        val server = Server(""","cacheMaxAge":60""")
        val transport = transport(server, AddonResponseCache(timeSource = clock))
        assertEquals("https://cdn.example.com/v1.mkv", transport.resource(request).url())
        clock += 59.seconds
        assertEquals("https://cdn.example.com/v1.mkv", transport.resource(request).url())
        assertEquals(1, server.requests)
        clock += 1.seconds
        assertEquals("https://cdn.example.com/v2.mkv", transport.resource(request).url())
        assertEquals(2, server.requests)
    }

    @Test
    fun staleWhileRevalidateServesTheOldResponseAndRefreshesInTheBackground() = runTest {
        val clock = TestTimeSource()
        val server = Server(""","cacheMaxAge":10,"staleRevalidate":60""")
        val transport = transport(server, AddonResponseCache(timeSource = clock))
        transport.resource(request)
        clock += 30.seconds
        assertEquals("https://cdn.example.com/v1.mkv", transport.resource(request).url())
        runCurrent()
        assertEquals(2, server.requests)
        assertEquals("https://cdn.example.com/v2.mkv", transport.resource(request).url())
        assertEquals(2, server.requests)
        // Past the revalidation window the entry is not served stale any more.
        clock += 80.seconds
        assertEquals("https://cdn.example.com/v3.mkv", transport.resource(request).url())
    }

    @Test
    fun staleIfErrorCoversAFailingAddon() = runTest {
        val clock = TestTimeSource()
        val server = Server(""","cacheMaxAge":10,"staleError":100""")
        val transport = transport(server, AddonResponseCache(timeSource = clock), revalidate = false)
        transport.resource(request)
        server.failing = true
        clock += 50.seconds
        assertEquals("https://cdn.example.com/v1.mkv", transport.resource(request).url())
        clock += 70.seconds
        assertEquals(AddonError.Http(502), assertFailsWith<AddonException> { transport.resource(request) }.error)
    }

    @Test
    fun responsesWithoutHintsAreNotCached() = runTest {
        val server = Server("")
        val transport = transport(server, AddonResponseCache())
        transport.resource(request)
        transport.resource(request)
        assertEquals(2, server.requests)
    }

    /**
     * Asked again while its answer is on its way (Detail asked, then the Streams list opened before a slow addon
     * answered), the addon is asked once and both get its answer. A caller that gives up doesn't stop the request
     * (Detail stops its ask as the list opens): one asking after it still gets that answer.
     */
    @Test
    fun anAnswerOnItsWayIsSharedNotAskedAgain() = runTest {
        val server = Server("", delayMillis = 6_000)
        val transport = transport(server, AddonResponseCache())
        val detail = async { transport.resource(request) }
        advanceTimeBy(2_000)
        val list = async { transport.resource(request) }
        assertEquals("https://cdn.example.com/v1.mkv", list.await().url())
        assertEquals("https://cdn.example.com/v1.mkv", detail.await().url())
        assertEquals(1, server.requests)

        val leaving = async { transport.resource(request) }
        advanceTimeBy(1_000)
        leaving.cancel()
        advanceTimeBy(1_000)
        val staying = async { transport.resource(request) }
        assertEquals("https://cdn.example.com/v2.mkv", staying.await().url())
        assertEquals(2, server.requests)
    }

    /**
     * An answer that never ends (an addon sending a byte now and then) ends its shared request at the deadline: the
     * caller gets a timeout, and the next ask, a refresh included, asks the addon again instead of joining it until the
     * app restarts.
     */
    @Test
    fun aRequestThatNeverEndsTimesOutAndTheNextAskAsksAgain() = runTest {
        val server = Server("", delayMillis = 10.minutes.inWholeMilliseconds)
        val transport = transport(server, AddonResponseCache())
        val first = async { runCatching { transport.resource(request) } }
        advanceTimeBy(60.seconds.inWholeMilliseconds + 1)
        runCurrent()
        assertTrue(first.isCompleted, "the request ended at its deadline")
        assertEquals(AddonError.Timeout, (first.await().exceptionOrNull() as AddonException).error)

        val again = async { runCatching { transport.resource(request, fresh = true) } }
        advanceTimeBy(1_000)
        assertEquals(2, server.requests)
        again.cancel()
    }

    /** An answer longer than 32 MiB fails as an invalid response once that much is read; it is never read whole. */
    @Test
    fun anAnswerPastTheCapFails() = runTest {
        val json = """{"streams":[]}""".encodeToByteArray()
        val body = ByteArray(32 * 1024 * 1024 + 1) { ' '.code.toByte() } + json
        val transport = AddonTransport(
            mockClient { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
            parseDispatcher = StandardTestDispatcher(testScheduler),
        )
        assertIs<AddonError.InvalidResponse>(assertFailsWith<AddonException> { transport.resource(request) }.error)
    }

    /** An event's answer counts by its status alone: a body that never ends isn't waited for, nor read. */
    @Test
    fun anEventIsSentWithoutReadingItsAnswer() = runTest {
        val endless = ByteChannel()
        val transport = AddonTransport(
            mockClient { respond(endless, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
            parseDispatcher = StandardTestDispatcher(testScheduler),
        )
        transport.send(request)
        assertEquals(0, testScheduler.currentTime, "answered at once, no read until the request's timeout")
    }

    @Test
    fun cacheIsBoundedLeastRecentlyUsedFirst() = runTest {
        val clock = TestTimeSource()
        val cache = AddonResponseCache(maxBodyChars = 250, timeSource = clock)
        val response = AddonResponse.decode(streamsBody("https://cdn.example.com/a.mkv", extra = ""","cacheMaxAge":60"""))
        cache.store("a", response, 100)
        cache.store("b", response, 100)
        assertIs<AddonResponseCache.Lookup.Fresh>(cache.lookup("a"))
        cache.store("c", response, 100)
        assertIs<AddonResponseCache.Lookup.Miss>(cache.lookup("b"))
        assertIs<AddonResponseCache.Lookup.Fresh>(cache.lookup("a"))
        assertIs<AddonResponseCache.Lookup.Fresh>(cache.lookup("c"))
        cache.store("huge", response, 251)
        assertIs<AddonResponseCache.Lookup.Miss>(cache.lookup("huge"))
    }

    @Test
    fun theLastFewMetasWithoutHintsAreKeptForAWhile() = runTest {
        val clock = TestTimeSource()
        val cache = AddonResponseCache(timeSource = clock)
        fun meta(extra: String = "") = AddonResponse.decode("""{"meta":{"id":"tt1","type":"movie","name":"One"}$extra}""")
        cache.store("m0", meta(), 100)
        clock += 29.minutes
        assertIs<AddonResponseCache.Lookup.Fresh>(cache.lookup("m0"))
        clock += 1.minutes
        assertIs<AddonResponseCache.Lookup.Miss>(cache.lookup("m0"))
        // Streams without hints aren't kept, nor a meta whose hints say not to.
        cache.store("streams", AddonResponse.decode(streamsBody("https://cdn.example.com/a.mkv", extra = "")), 100)
        assertIs<AddonResponseCache.Lookup.Miss>(cache.lookup("streams"))
        cache.store("zero", meta(""","cacheMaxAge":0"""), 100)
        assertIs<AddonResponseCache.Lookup.Miss>(cache.lookup("zero"))
        // The eight most recent of them.
        for (i in 1..9) cache.store("m$i", meta(), 100)
        assertIs<AddonResponseCache.Lookup.Miss>(cache.lookup("m1"))
        for (i in 2..9) assertIs<AddonResponseCache.Lookup.Fresh>(cache.lookup("m$i"))
    }

    @Test
    fun failuresAreTyped() = runTest {
        val transport = AddonTransport(
            mockClient { request ->
                when (request.url.encodedPath) {
                    "/stream/movie/missing.json" -> json("{}", HttpStatusCode.NotFound)
                    "/stream/movie/garbage.json" -> json("not json")
                    "/manifest.json" -> json("""{"id":"x","version":"1","name":"x","types":[],"resources":[]}""")
                    else -> throw IOException("connection reset by https://addon.example.com/secret-key/x")
                }
            },
            parseDispatcher = StandardTestDispatcher(testScheduler),
        )
        suspend fun error(id: String) = assertFailsWith<AddonException> {
            transport.resource(ResourceRequest("https://addon.example.com/manifest.json".transportUrl(), ResourcePath("stream", "movie", id)))
        }.error

        assertEquals(AddonError.Http(404), error("missing"))
        assertIs<AddonError.InvalidResponse>(error("garbage"))
        assertEquals(AddonError.Network("connection reset by https://addon.example.com/…"), error("reset"))
        assertNull(TransportUrl.checked("https://addon.example.com/stremio/v1"))
        assertNull(TransportUrl.checked("ftp://addon.example.com/manifest.json"))
        assertIs<AddonError.InvalidManifest>(assertFailsWith<AddonException> { transport.manifest("https://addon.example.com/manifest.json".transportUrl()) }.error)
    }
}
