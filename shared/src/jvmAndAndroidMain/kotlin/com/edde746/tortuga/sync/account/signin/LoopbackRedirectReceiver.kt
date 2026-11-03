package com.edde746.tortuga.sync.account.signin

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.strings.T
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The OAuth redirect target of desktop and Android sign-in (RFC 8252 §7.3): an HTTP listener on the loopback
 * interface that takes the browser's redirect to `http://127.0.0.1:<port><path>` with the awaited `state` and answers
 * with a page telling the user to go back to the app. Any other request (another path, another or no `state`, a query
 * that doesn't decode) gets a 404, while it keeps listening. Each connection is answered on its own, so one that
 * stalls (a browser's idle preconnect, a local process sending nothing or a byte at a time) holds up no other; one
 * whose request head hasn't come within [HEAD_TIMEOUT_MILLIS] of its accept, or runs past [HEAD_LIMIT_BYTES], is
 * closed unanswered. Plain `java.net` sockets, so a jlinked runtime needs only `java.base`.
 *
 * @see open
 */
class LoopbackRedirectReceiver private constructor(private val server: ServerSocket, private val path: String) : RedirectReceiver {
    override val redirectUri: String = "http://127.0.0.1:${server.localPort}$path"

    override suspend fun awaitRedirect(state: String): Map<String, String> = coroutineScope {
        val redirect = CompletableDeferred<Map<String, String>>()
        val connections = ConcurrentHashMap.newKeySet<Socket>()
        launch(Dispatchers.IO) {
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    redirect.completeExceptionally(IllegalStateException("The redirect listener was closed", e))
                    break
                }
                connections += socket
                // Accepted as the wait ended: the sweep below may have missed it.
                if (server.isClosed) socket.close()
                launch {
                    try {
                        socket.use { handle(it, state) }?.let(redirect::complete)
                    } catch (e: IOException) {
                        // A connection that stalls past its deadline, sends too much or drops isn't the redirect.
                        Log.i(TAG) { "A request to the redirect listener failed: ${e::class.simpleName}: ${e.message}" }
                    } finally {
                        connections -= socket
                    }
                }
            }
        }
        try {
            redirect.await()
        } finally {
            // Closing the sockets is what unblocks accept() and the reads still waiting, the wait's end or not.
            server.close()
            connections.forEach { it.close() }
        }
    }

    override fun close() = server.close()

    /** Answers one request; returns its query parameters if it was the redirect with `state` = [state]. */
    private fun handle(socket: Socket, state: String): Map<String, String>? {
        val requestLine = requestHead(socket).substringBefore('\n').removeSuffix("\r")
        if (requestLine.isEmpty()) return null
        val parts = requestLine.split(' ')
        val target = parts.getOrNull(1)
        val parameters = if (parts.firstOrNull() == "GET" && target != null && target.substringBefore('?') == path) query(target) else null
        if (parameters == null || parameters["state"] != state) {
            respond(socket, 404, "Not Found", "")
            return null
        }
        // A provider's redirect carries `code`, a web hand-off's `proof`; either way no `error` means it went through.
        val text = if ("error" in parameters) T.account.browser.failed else T.account.browser.signedIn
        try {
            respond(socket, 200, "OK", page(text))
        } catch (e: IOException) {
            // The browser left before the page came: the redirect arrived all the same.
            Log.i(TAG) { "The redirect's page wasn't delivered: ${e::class.simpleName}" }
        }
        return parameters
    }

    /**
     * [socket]'s request head, up to the blank line that ends it, or what came before the client hung up.
     *
     * @throws SocketTimeoutException when it hasn't ended [HEAD_TIMEOUT_MILLIS] after this call.
     * @throws IOException when it runs past [HEAD_LIMIT_BYTES].
     */
    private fun requestHead(socket: Socket): String {
        val deadline = System.nanoTime() + HEAD_TIMEOUT_MILLIS * 1_000_000
        val input = socket.getInputStream()
        val head = ByteArray(HEAD_LIMIT_BYTES)
        var size = 0
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) throw SocketTimeoutException("The request head didn't come within $HEAD_TIMEOUT_MILLIS ms")
            if (size == head.size) throw IOException("The request head runs past $HEAD_LIMIT_BYTES bytes")
            socket.soTimeout = left.toInt()
            val read = input.read(head, size, head.size - size)
            if (read < 0) break
            size += read
            if (endsHead(head, from = size - read, until = size)) break
        }
        return String(head, 0, size, StandardCharsets.US_ASCII)
    }

    /** Whether a line feed in [from]..<[until] of [bytes] ends an empty line (`\n\n`, `\r\n\r\n`): a request head's end. */
    private fun endsHead(bytes: ByteArray, from: Int, until: Int): Boolean {
        for (i in maxOf(from, 1) until until) {
            if (bytes[i] != LF) continue
            if (bytes[i - 1] == LF || i >= 2 && bytes[i - 1] == CR && bytes[i - 2] == LF) return true
        }
        return false
    }

    /** [target]'s query parameters, or null when one doesn't decode (a malformed `%` escape). */
    private fun query(target: String): Map<String, String>? = try {
        target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { pair ->
            decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun respond(socket: Socket, status: Int, reason: String, html: String) {
        val body = html.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun page(text: String): String {
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>${T.app.name}</title>" +
            "<style>body{font-family:system-ui,sans-serif;background:#05090D;color:#FFF1D6;display:grid;" +
            "place-items:center;min-height:100vh;margin:0;text-align:center}main{max-width:28rem;padding:2rem}</style>" +
            "</head><body><main><p>$escaped</p></main></body></html>"
    }

    // The charset-name overload: URLDecoder.decode(String, Charset) needs Android API 33.
    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

    companion object {
        /**
         * The port self-hosters register for the apps' redirect (`http://127.0.0.1:53682/callback`, server/README.md):
         * a provider that registers one exact redirect URI needs it.
         */
        const val PREFERRED_PORT = 53682

        /**
         * Listens on 127.0.0.1. [port] 0 takes a free port, which Google's desktop clients accept; a provider that
         * registers one exact redirect URI needs that port ([PREFERRED_PORT]).
         */
        fun open(port: Int = 0, path: String = "/callback"): LoopbackRedirectReceiver {
            require(path.startsWith('/')) { "The redirect path starts with /" }
            return LoopbackRedirectReceiver(ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")), path)
        }

        /**
         * Listens on [PREFERRED_PORT], or on any free port while another program holds it (Google and RFC 8252
         * providers accept any loopback port; one that registered the exact URI will then refuse the redirect).
         */
        fun openPreferred(): LoopbackRedirectReceiver = try {
            open(PREFERRED_PORT)
        } catch (e: BindException) {
            Log.w(TAG) { "Port $PREFERRED_PORT is taken (${e.message}), listening on a free port" }
            open()
        }

        /** How long a connection has, from its accept, to send its request head; a browser sends it at once. */
        private const val HEAD_TIMEOUT_MILLIS = 10_000L

        /** The most a request head may hold (Node.js's default limit); a browser's redirect has a few hundred bytes. */
        private const val HEAD_LIMIT_BYTES = 16 * 1024

        private const val LF: Byte = 10
        private const val CR: Byte = 13
        private const val TAG = "LoopbackRedirect"
    }
}
