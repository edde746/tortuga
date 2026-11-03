package com.edde746.tortuga.playback.resolve

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.playback.ErrorVideoSign
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.writeFully
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class StreamResolverTest {
    // 1 MiB + 13 bytes: both chunks are full and the size isn't word-aligned.
    private val movie = lcgBytes(1_048_589)
    private val movieHash = "e8effb2276320a89"
    private val movieTail = "bytes=983053-1048588"

    private suspend fun resolve(request: StreamRequest, http: FakeHttp): ResolveResult =
        StreamResolver(http.client).resolve(request)

    private fun ResolveResult.stream(): ResolvedStream = assertIs<ResolveResult.Resolved>(this, "$this").stream

    private fun ResolveResult.failure(): ResolveFailure = assertIs<ResolveResult.Failed>(this, "$this").failure

    @Test
    fun followsAbsoluteAndRelativeRedirectsThenHashesTheFinalUrl() {
        // The resolver follows every hop itself, whatever the client's own setting.
        for (clientFollows in listOf(true, false)) runTest {
            val http = FakeHttp(this, followRedirects = clientFollows) { request ->
                when (request.url.encodedPath) {
                    "/resolve/host/KEY/abc/null/0" -> redirect("https://host.example/unrestrict/1?key=KEY")
                    "/unrestrict/1" -> redirect("/d/ABC/stream", HttpStatusCode.TemporaryRedirect)
                    "/d/ABC/stream" -> redirect("The.Movie.2019.2160p.mkv", HttpStatusCode.SeeOther)
                    "/d/ABC/The.Movie.2019.2160p.mkv" -> serveFile(request, movie)
                    else -> error("unexpected ${request.url}")
                }
            }
            val stream = resolve(StreamRequest("https://addon.example/resolve/host/KEY/abc/null/0"), http).stream()

            assertEquals("https://host.example/d/ABC/The.Movie.2019.2160p.mkv", stream.url, "client follows: $clientFollows")
            assertEquals(movie.size.toLong(), stream.size)
            assertEquals(movieHash, stream.videoHash)
            assertEquals("The.Movie.2019.2160p.mkv", stream.filename)
            // Every hop of the first request asks for the head; one more request, on the final URL, for the tail.
            assertEquals(List(4) { "bytes=0-65535" } + movieTail, http.requests.map { it.headers[HttpHeaders.Range] })
            assertEquals(stream.url, http.requests.last().url.toString())
        }
    }

    @Test
    fun aRedirectFromHttpsToHttpIsRefused() {
        for (clientFollows in listOf(true, false)) runTest {
            val http = FakeHttp(this, followRedirects = clientFollows) { request ->
                if (request.url.host == "addon.example") redirect("http://cdn.example/file.mkv") else serveFile(request, movie)
            }
            assertEquals(ResolveFailure.UnsupportedUrl, resolve(StreamRequest("https://addon.example/play"), http).failure(), "client follows: $clientFollows")
            assertEquals(listOf("addon.example"), http.requests.map { it.url.host })

            // A stream on plain http may move to another plain http host.
            val stream = resolve(StreamRequest("http://addon.example/play"), http).stream()
            assertEquals("http://cdn.example/file.mkv", stream.url)
        }
    }

    @Test
    fun credentialHeadersGoOnlyToTheStreamUrlsOrigin() {
        val credentials = mapOf("Authorization" to "Bearer KEY", "cookie" to "session=KEY", "Proxy-Authorization" to "Basic KEY")
        // Another host, and the same host on another port: both are another origin.
        for (elsewhere in listOf("https://cdn.example/f.mkv", "https://addon.example:8443/f.mkv")) runTest {
            val http = FakeHttp(this) { request ->
                when (request.url.encodedPath) {
                    "/play" -> redirect("/play/2")
                    "/play/2" -> redirect(elsewhere)
                    else -> serveFile(request, movie)
                }
            }
            val stream = resolve(StreamRequest("https://addon.example/play", requestHeaders = credentials + ("X-Token" to "abc")), http).stream()

            assertEquals(elsewhere, stream.url)
            assertEquals(mapOf("X-Token" to "abc"), stream.headers, "what the player and a download send to $elsewhere")
            val (origin, other) = http.requests.partition { it.url.encodedPath.startsWith("/play") }
            assertEquals(2, origin.size)
            for (request in origin) {
                for ((name, value) in credentials) assertEquals(value, request.headers[name], "$name on ${request.url}")
            }
            assertEquals(2, other.size, "the head and the tail at $elsewhere")
            for (request in other) {
                for (name in credentials.keys) assertNull(request.headers[name], "$name on ${request.url}")
                assertEquals("abc", request.headers["X-Token"], "on ${request.url}")
            }
        }
    }

    @Test
    fun resolveHeadAnswersFromTheFirstRequestAndLeavesTheTailToItsCaller() = runTest {
        val http = FakeHttp(this) { request ->
            if (request.url.host == "addon.example") redirect("https://cdn.example/f.mkv") else serveFile(request, movie)
        }
        val head = assertIs<ResolveResult.Resolved>(StreamResolver(http.client).resolveHead(StreamRequest("https://addon.example/play")))
        assertEquals("https://cdn.example/f.mkv", head.stream.url)
        assertEquals(movie.size.toLong(), head.stream.size)
        assertNull(head.stream.videoHash)
        assertEquals(List(2) { "bytes=0-65535" }, http.requests.map { it.headers[HttpHeaders.Range] })

        assertEquals(movieHash, head.pendingHash?.invoke())
        assertEquals(movieTail, http.requests.last().headers[HttpHeaders.Range])
        assertEquals(head.stream.url, http.requests.last().url.toString())
    }

    @Test
    fun theFirstAnswerTellsAnErrorVideoByItsLinkOrItsSize() = runTest {
        val clip = lcgBytes(300_000)
        val http = FakeHttp(this) { request ->
            when (request.url.host) {
                "addon.example" -> when (val name = request.url.segments.last()) {
                    "failed" -> redirect("https://clips.example/videos/failed_access_v2.mp4")
                    else -> redirect("https://cdn.example/$name.mkv")
                }
                "cdn.example" -> serveFile(request, if (request.url.encodedPath == "/movie.mkv") movie else clip)
                else -> serveFile(request, clip)
            }
        }
        val resolver = StreamResolver(http.client)
        suspend fun errorVideo(name: String, videoSize: Long? = null) =
            assertIs<ResolveResult.Resolved>(resolver.resolveHead(StreamRequest("https://addon.example/$name", videoSize = videoSize))).stream.errorVideo

        assertEquals(ErrorVideoSign.Url, errorVideo("failed"), "an addon's link for a file its service refused")
        assertEquals(ErrorVideoSign.Size(300_000, 1_200_000_000), errorVideo("clip", videoSize = 1_200_000_000), "300 KB where the addon said 1.2 GB")
        assertNull(errorVideo("clip"), "no size from the addon: the length tells it once it opens")
        assertNull(errorVideo("movie", videoSize = movie.size.toLong()))
        assertNull(errorVideo("movie"))
    }

    @Test
    fun sendsTheStreamHeadersOnEveryRequestAndHandsThemToThePlayer() = runTest {
        val http = FakeHttp(this) { request ->
            if (request.url.host == "addon.example") redirect("https://cdn.example/f.mkv") else serveFile(request, movie)
        }
        val stream = resolve(
            StreamRequest(
                "https://addon.example/play",
                requestHeaders = linkedMapOf(
                    "user-agent" to "Custom/1.0",
                    " Referer " to " https://site.example/ ",
                    "X-Token" to "abc",
                    "Range" to "bytes=5-",
                    "Host" to "elsewhere.example",
                    "X-Injected" to "a\r\nX-Evil: 1",
                    "Bad Name" to "x",
                ),
            ),
            http,
        ).stream()

        val expected = mapOf("User-Agent" to "Custom/1.0", "Referer" to "https://site.example/", "X-Token" to "abc")
        assertEquals(expected, stream.headers)
        assertEquals("Custom/1.0", stream.userAgent)
        assertEquals(3, http.requests.size)
        for (request in http.requests) {
            for ((name, value) in expected) assertEquals(value, request.headers[name], "$name on ${request.url}")
            assertEquals(listOf(request.headers[HttpHeaders.Range]!!), request.headers.getAll(HttpHeaders.Range))
            assertEquals("no-store", request.headers[HttpHeaders.CacheControl])
            assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
            assertNull(request.headers["X-Injected"])
            assertNull(request.headers["X-Evil"])
        }
    }

    @Test
    fun aHeaderValueOutsidePrintableAsciiIsDropped() = runTest {
        // OkHttp (Android, Windows, Linux) throws on it, which would fail the whole resolve.
        val http = FakeHttp(this) { request -> serveFile(request, movie) }
        val stream = resolve(
            StreamRequest("https://cdn.example/f.mkv", requestHeaders = linkedMapOf("Referer" to "https://sitio.example/año/", "X-Token" to "abc")),
            http,
        ).stream()

        assertEquals(mapOf("X-Token" to "abc"), stream.headers)
        for (request in http.requests) assertNull(request.headers["Referer"], "on ${request.url}")
    }

    @Test
    fun readsOneChunkFromAServerThatIgnoresRangeAndCancelsTheBody() = runTest {
        val total = 64L * 1024 * 1024
        val written = mutableListOf<Long>()
        val cancelled = mutableListOf<Boolean>()
        val writers = mutableListOf<Job>()
        // Real threads throughout: on Kotlin/Native, finishing a call whose body was cancelled waits on work outside
        // the test scheduler, so with virtual time the resolve timeout runs out at once.
        val http = FakeHttp(this, engineDispatcher = Dispatchers.Default) {
            val body = ByteChannel()
            val index = written.size
            written += 0L
            cancelled += false
            writers += backgroundScope.launch(Dispatchers.Default) {
                val block = ByteArray(8192) { 1 }
                try {
                    while (written[index] < total) {
                        body.writeFully(block)
                        body.flush()
                        written[index] += block.size
                    }
                    body.flushAndClose()
                } catch (e: ClosedByteChannelException) {
                    cancelled[index] = true
                }
            }
            respond(body, HttpStatusCode.OK, headers(HttpHeaders.ContentLength to "$total"))
        }
        val stream = withContext(Dispatchers.Default) {
            resolve(StreamRequest("https://cdn.example/f.mkv"), http).also { writers.joinAll() }
        }.stream()

        assertEquals(total, stream.size)
        // The tail can't be had from a server that ignores Range without reading the whole file.
        assertNull(stream.videoHash)
        assertEquals(listOf(true, true), cancelled)
        for (bytes in written) assertTrue(bytes < 4L * 1024 * 1024, "read $bytes of $total bytes")
    }

    @Test
    fun hashesASmallFileFromOneRequestWhenTheServerIgnoresRange() = runTest {
        val file = lcgBytes(100_000)
        val http = FakeHttp(this) { respond(file, HttpStatusCode.OK, headers(HttpHeaders.ContentLength to "${file.size}")) }
        val stream = resolve(StreamRequest("https://cdn.example/f.mkv"), http).stream()
        assertEquals("d00119502c54bf58", stream.videoHash)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun hashesFilesSmallerThanTwoChunksLikeTheReferenceImplementation() {
        // size to (hash, requests): under one chunk the head is the whole file; between one and two the chunks overlap.
        val cases = mapOf(7 to ("0000000000000007" to 1), 13 to ("211b0196c54b04e5" to 1), 1000 to ("9f7dcf3f829e7e88" to 1), 100_000 to ("d00119502c54bf58" to 2))
        for ((size, expected) in cases) runTest {
            val file = lcgBytes(size)
            val http = FakeHttp(this) { request -> serveFile(request, file) }
            val stream = resolve(StreamRequest("https://cdn.example/f.mkv"), http).stream()
            assertEquals(size.toLong(), stream.size)
            assertEquals(expected.first, stream.videoHash, "size $size")
            assertEquals(expected.second, http.requests.size, "size $size")
        }
        runTest {
            val file = lcgBytes(100_000)
            val http = FakeHttp(this) { request -> serveFile(request, file) }
            resolve(StreamRequest("https://cdn.example/f.mkv"), http)
            assertEquals("bytes=34464-99999", http.requests.last().headers[HttpHeaders.Range])
        }
    }

    @Test
    fun resolvesWithoutAHashWhenTheServerSendsOtherRanges() {
        val size = movie.size.toLong()
        val servers = mapOf<String, suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData>(
            "head starts elsewhere" to { request -> if (request.requestedRange()!!.first == 0L) respondRange(movie, 100, 65635) else serveFile(request, movie) },
            "head is short" to { request -> if (request.requestedRange()!!.first == 0L) respondRange(movie, 0, 32767) else serveFile(request, movie) },
            "tail starts elsewhere" to { request -> if (request.requestedRange()!!.first == 0L) serveFile(request, movie) else respondRange(movie, 0, 65535) },
            "tail reports another size" to { request ->
                val range = request.requestedRange()!!
                if (range.first == 0L) serveFile(request, movie) else respondRange(movie, range.first, range.last, total = size + 1)
            },
            "tail is short" to { request ->
                val range = request.requestedRange()!!
                if (range.first == 0L) serveFile(request, movie) else respondRange(movie, range.first, range.first + 100)
            },
            "tail refused" to { request ->
                if (request.requestedRange()!!.first == 0L) serveFile(request, movie) else respond(ByteArray(0), HttpStatusCode.Forbidden)
            },
        )
        for ((name, server) in servers) runTest {
            val stream = resolve(StreamRequest("https://cdn.example/f.mkv"), FakeHttp(this, handler = server)).stream()
            assertEquals(size, stream.size, name)
            assertNull(stream.videoHash, name)
        }
        runTest {
            val http = FakeHttp(this) { respond(movie.copyOf(65536), HttpStatusCode.PartialContent) }
            val stream = resolve(StreamRequest("https://cdn.example/f.mkv"), http).stream()
            assertNull(stream.size, "206 without Content-Range")
            assertNull(stream.videoHash)
        }
    }

    @Test
    fun asksASmallFileAgainForExactlyItsBytesAfterA416() = runTest {
        val file = lcgBytes(1000)
        val http = FakeHttp(this) { request ->
            // Some servers refuse a range that runs past the end instead of shortening it.
            if (request.requestedRange()!!.last >= file.size) {
                respond(ByteArray(0), HttpStatusCode.RequestedRangeNotSatisfiable, headers(HttpHeaders.ContentRange to "bytes */1000"))
            } else {
                serveFile(request, file)
            }
        }
        val stream = resolve(StreamRequest("https://cdn.example/f.mkv"), http).stream()
        assertEquals("9f7dcf3f829e7e88", stream.videoHash)
        assertEquals(listOf("bytes=0-65535", "bytes=0-999"), http.requests.map { it.headers[HttpHeaders.Range] })
    }

    @Test
    fun anEmptyFileIsA416Failure() = runTest {
        val http = FakeHttp(this) { request -> serveFile(request, ByteArray(0)) }
        assertEquals(ResolveFailure.Http(416), resolve(StreamRequest("https://cdn.example/f.mkv"), http).failure())
        assertEquals(1, http.requests.size)
    }

    @Test
    fun httpErrorsAreTypedFailures() = runTest {
        val cases = mapOf(
            404 to FakeHttp(this) { respond(ByteArray(0), HttpStatusCode.NotFound) },
            403 to FakeHttp(this) { request ->
                if (request.url.host == "addon.example") redirect("https://cdn.example/x") else respond(ByteArray(0), HttpStatusCode.Forbidden)
            },
            302 to FakeHttp(this) { respond(ByteArray(0), HttpStatusCode.Found) }, // no Location
            503 to FakeHttp(this) { respond(ByteArray(0), HttpStatusCode.ServiceUnavailable) },
        )
        for ((status, http) in cases) {
            assertEquals(ResolveFailure.Http(status), resolve(StreamRequest("https://addon.example/play"), http).failure())
        }
    }

    @Test
    fun aRedirectLoopFailsAfterTheLimit() {
        for (clientFollows in listOf(true, false)) runTest {
            val http = FakeHttp(this, followRedirects = clientFollows) { request ->
                redirect(if (request.url.encodedPath == "/a") "/b" else "/a")
            }
            assertEquals(ResolveFailure.TooManyRedirects, resolve(StreamRequest("https://addon.example/a"), http).failure())
            assertEquals(StreamResolver.MAX_REDIRECTS + 1, http.requests.size, "client follows: $clientFollows")
        }
    }

    @Test
    fun rejectsUrlsThatArentHttpWithoutARequest() = runTest {
        val http = FakeHttp(this) { redirect("ftp://files.example/f.mkv") }
        for (url in listOf("ftp://files.example/f.mkv", "not a url", "", "file:///etc/passwd")) {
            assertEquals(ResolveFailure.UnsupportedUrl, resolve(StreamRequest(url), http).failure(), url)
        }
        assertTrue(http.requests.isEmpty())
        assertEquals(ResolveFailure.UnsupportedUrl, resolve(StreamRequest("https://addon.example/play"), http).failure())
    }

    @Test
    fun addonHintsSkipHashingAndNameTheFile() = runTest {
        val http = FakeHttp(this) { request -> serveFile(request, movie) }
        val stream = resolve(
            StreamRequest(
                "https://cdn.example/d/stream",
                videoHash = "abcdef0123456789",
                videoSize = 5,
                filename = "The.Movie.2019.mkv",
            ),
            http,
        ).stream()
        assertEquals("abcdef0123456789", stream.videoHash)
        assertEquals(movie.size.toLong(), stream.size, "the server's size wins over the hint")
        assertEquals("The.Movie.2019.mkv", stream.filename)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun namesTheFileFromContentDispositionThenThePath() = runTest {
        val disposition = """attachment; filename="fallback.mkv"; filename*=UTF-8''The%20Movie%20%E2%80%93%202019.mkv"""
        val http = FakeHttp(this) { request ->
            when (request.url.encodedPath) {
                "/dl/1" -> serveFile(request, movie, HttpHeaders.ContentDisposition to disposition)
                "/dl/2" -> serveFile(request, movie, HttpHeaders.ContentDisposition to """inline; filename="C:\\dir\\Quoted \"x\".mkv"""")
                else -> serveFile(request, movie)
            }
        }
        suspend fun name(path: String) = resolve(StreamRequest("https://cdn.example$path"), http).stream().filename
        assertEquals("The Movie – 2019.mkv", name("/dl/1"))
        assertEquals("Quoted \"x\".mkv", name("/dl/2"))
        assertEquals("Show S01E02.mp4", name("/files/Show%20S01E02.mp4"))
        assertNull(name("/play/stream"))
        assertNull(name("/index.php"))
    }

    @Test
    fun theFirstByteHasTheResolveTimeout() = runTest {
        val never = FakeHttp(this) { awaitCancellation() }
        val resolver = StreamResolver(never.client, timeout = 45.seconds)
        assertEquals(ResolveFailure.Timeout, resolver.resolve(StreamRequest("https://addon.example/play")).failure())
        assertEquals(45_000, currentTime)

        // An addon's service preparing the file for 40 s is slow, not failed.
        val slow = FakeHttp(this) { request ->
            if (request.url.host == "addon.example") {
                delay(40.seconds)
                redirect("https://cdn.example/f.mkv")
            } else {
                serveFile(request, movie)
            }
        }
        assertEquals(movieHash, StreamResolver(slow.client, timeout = 45.seconds).resolve(StreamRequest("https://addon.example/play")).stream().videoHash)
    }

    @Test
    fun aStalledTailRequestOnlyCostsTheHash() = runTest {
        val http = FakeHttp(this) { request ->
            if (request.requestedRange()!!.first == 0L) serveFile(request, movie) else awaitCancellation()
        }
        val start = currentTime
        val stream = StreamResolver(http.client, hashTimeout = 10.seconds).resolve(StreamRequest("https://cdn.example/f.mkv")).stream()
        assertEquals(movie.size.toLong(), stream.size)
        assertNull(stream.videoHash)
        assertEquals(10_000, currentTime - start)
    }

    @Test
    fun networkErrorsAreTypedWithoutTheirMessage() = runTest {
        val http = FakeHttp(this) { throw IOException("connection reset: https://addon.example/SECRETKEY/play") }
        val failure = resolve(StreamRequest("https://addon.example/SECRETKEY/play"), http).failure()
        assertEquals(ResolveFailure.Network("IOException"), failure)
        assertFalse("SECRETKEY" in failure.toString())
    }

    @Test
    fun aFailureOfItsOwnIsNoNetworkErrorAndIsLoggedWithItsTrace() = runTest {
        val lines = mutableListOf<String>()
        val sink = Log.sink
        Log.sink = Log.Sink { level, tag, message, error -> lines += "$level $tag $message\n${error.orEmpty()}" }
        val failure = try {
            val http = FakeHttp(this) { throw IllegalStateException("index 70000 out of bounds for length 65536") }
            resolve(StreamRequest("https://addon.example/play"), http).failure()
        } finally {
            Log.sink = sink
        }
        assertEquals(ResolveFailure.Unexpected, failure, "a failure of its own, not the network's")
        val logged = lines.single()
        assertTrue(logged.startsWith("ERROR resolve "), logged)
        assertTrue("IllegalStateException: index 70000 out of bounds for length 65536" in logged, "with its trace: $logged")
    }

    @Test
    fun printingRequestsAndStreamsHidesUrlsAndHeaderValues() {
        val request = StreamRequest("https://addon.example/SECRETKEY/play", mapOf("Authorization" to "Bearer SECRETTOKEN"))
        val stream = ResolvedStream("https://cdn.example/SECRETKEY/f.mkv", request.requestHeaders, 1, null, null)
        for (text in listOf(request.toString(), stream.toString())) {
            assertFalse("SECRET" in text, text)
            assertTrue("Authorization" in text, text)
        }
    }
}
