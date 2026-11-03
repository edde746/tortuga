package com.edde746.tortuga.sync.account.signin

import com.edde746.tortuga.strings.T
import java.io.IOException
import java.net.ConnectException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The desktop redirect listener, with a real browser request over the loopback interface. */
class LoopbackRedirectReceiverTest {
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    private fun get(url: String, timeout: Duration = Duration.ofSeconds(30)): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI(url)).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofString())

    /** A connection to [redirectUri]'s port that has sent [bytes]. */
    private fun connect(redirectUri: String, bytes: String = ""): Socket {
        val uri = URI(redirectUri)
        return Socket(uri.host, uri.port).apply {
            getOutputStream().apply {
                write(bytes.toByteArray())
                flush()
            }
        }
    }

    /** Whether the listener has closed [socket] (its end of the stream, or a reset) within the socket's read timeout. */
    private fun closedByListener(socket: Socket): Boolean = try {
        socket.getInputStream().read() < 0
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: IOException) {
        true
    }

    /** The status of a GET of [target] sent to [redirectUri]'s port as written (`java.net.URI` refuses a bad escape). */
    private fun rawStatus(redirectUri: String, target: String): Int {
        val uri = URI(redirectUri)
        Socket(uri.host, uri.port).use { socket ->
            socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nConnection: close\r\n\r\n".toByteArray())
            return socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
        }
    }

    @Test
    fun theRedirectsQueryIsReturnedAndTheBrowserToldToGoBack() = runBlocking {
        LoopbackRedirectReceiver.open().use { receiver ->
            assertTrue(receiver.redirectUri.matches(Regex("http://127\\.0\\.0\\.1:\\d+/callback")))
            val redirect = async { receiver.awaitRedirect("s t") }
            val (favicon, page) = withContext(Dispatchers.IO) {
                get(receiver.redirectUri.replace("/callback", "/favicon.ico")) to get("${receiver.redirectUri}?code=a%2Fb&state=s%20t")
            }
            assertEquals(404, favicon.statusCode())
            assertEquals(200, page.statusCode())
            assertTrue(T.account.browser.signedIn in page.body())
            assertEquals(mapOf("code" to "a/b", "state" to "s t"), withTimeout(5_000) { redirect.await() })
        }
    }

    @Test
    fun aForgedOrGarbledRequestIsNotFoundAndTheRealHandOffStillCompletes() = runBlocking {
        LoopbackRedirectReceiver.open().use { receiver ->
            val redirect = async { receiver.awaitRedirect("the-state") }
            val statuses = withContext(Dispatchers.IO) {
                listOf(
                    get("${receiver.redirectUri}?state=someone-else&proof=forged").statusCode(),
                    get("${receiver.redirectUri}?proof=forged").statusCode(),
                    rawStatus(receiver.redirectUri, "/callback?state=the-state&proof=%zz"),
                )
            }
            assertEquals(listOf(404, 404, 404), statuses)
            val page = withContext(Dispatchers.IO) { get("${receiver.redirectUri}?state=the-state&proof=pr00f") }
            assertEquals(200, page.statusCode())
            assertTrue(T.account.browser.signedIn in page.body(), "a hand-off carries a proof, no code")
            assertEquals(mapOf("state" to "the-state", "proof" to "pr00f"), withTimeout(5_000) { redirect.await() })
        }
    }

    @Test
    fun aProviderErrorIsReturnedAndShownAsAFailure() = runBlocking {
        LoopbackRedirectReceiver.open().use { receiver ->
            val redirect = async { receiver.awaitRedirect("x") }
            val page = withContext(Dispatchers.IO) { get("${receiver.redirectUri}?error=access_denied&state=x") }
            assertTrue(T.account.browser.failed in page.body())
            assertEquals("access_denied", withTimeout(5_000) { redirect.await() }["error"])
        }
    }

    @Test
    fun cancellingTheWaitReleasesThePort() = runBlocking {
        val receiver = LoopbackRedirectReceiver.open()
        val uri = receiver.redirectUri
        val waiting = async { receiver.awaitRedirect("s") }
        withContext(Dispatchers.IO) { Thread.sleep(100) }
        waiting.cancel()
        withContext(Dispatchers.IO) {
            Thread.sleep(100)
            assertFailsWith<ConnectException> { get(uri) }
        }
        Unit
    }

    @Test
    fun connectionsThatStallDontHoldUpTheRedirect() = runBlocking {
        LoopbackRedirectReceiver.open().use { receiver ->
            val redirect = async { receiver.awaitRedirect("s") }
            val page = withContext(Dispatchers.IO) {
                // A browser's idle preconnect, and a local process that sent half a request line and stopped.
                connect(receiver.redirectUri).use {
                    connect(receiver.redirectUri, "GET /callback?sta").use {
                        get("${receiver.redirectUri}?state=s", timeout = Duration.ofSeconds(3))
                    }
                }
            }
            assertEquals(200, page.statusCode())
            assertEquals(mapOf("state" to "s"), withTimeout(5_000) { redirect.await() })
        }
    }

    @Test
    fun aRequestThatDripsInIsLetGoTenSecondsAfterItConnected() = runBlocking {
        LoopbackRedirectReceiver.open().use { receiver ->
            val redirect = async { receiver.awaitRedirect("s") }
            val (closed, heldMillis) = withContext(Dispatchers.IO) {
                connect(receiver.redirectUri).use { socket ->
                    val started = System.nanoTime()
                    socket.soTimeout = 500
                    var closed = false
                    // A byte every half second, each in time for any timeout per read.
                    while (!closed && System.nanoTime() - started < 15_000_000_000) {
                        closed = try {
                            socket.getOutputStream().apply {
                                write('a'.code)
                                flush()
                            }
                            closedByListener(socket)
                        } catch (_: IOException) {
                            true
                        }
                    }
                    closed to (System.nanoTime() - started) / 1_000_000
                }
            }
            assertTrue(closed, "the listener still reads a request that has been dripping in for ${heldMillis / 1000} s")
            assertTrue(heldMillis in 9_000..12_000, "let go after $heldMillis ms")
            redirect.cancel()
        }
    }

    @Test
    fun aRequestHeadOverSixteenKilobytesIsLetGoAtOnce() = runBlocking {
        LoopbackRedirectReceiver.open().use { receiver ->
            val redirect = async { receiver.awaitRedirect("s") }
            val closed = withContext(Dispatchers.IO) {
                // 20 KB of header lines and no blank line to end them.
                val head = "GET /callback?state=s HTTP/1.1\r\n" + "X-Filler: ${"a".repeat(990)}\r\n".repeat(20)
                connect(receiver.redirectUri, head).use { socket ->
                    socket.soTimeout = 3_000
                    closedByListener(socket)
                }
            }
            assertTrue(closed, "the listener still reads a request head of 20 KB")
            redirect.cancel()
        }
    }
}
