package com.edde746.tortuga.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.websocket.WebSockets

/**
 * The app's one [HttpClient]: OkHttp on Android/JVM, Darwin on Apple. Shared code never builds its own;
 * it takes this client injected.
 *
 * - `User-Agent: Tortuga/<version>`.
 * - Connect and socket (idle read) timeouts; no overall request timeout, so callers set their own per call, the body
 *   read included, and cap what they read: addon answers ([com.edde746.tortuga.addons.AddonTransport.REQUEST_TIMEOUT],
 *   [com.edde746.tortuga.addons.AddonTransport.MAX_BODY_BYTES]), tracker answers (`TrackerHttp`), files (their own
 *   request timeouts, streamed to disk). A body read inside `HttpStatement.execute` goes through [readBody].
 * - Redirects are followed (Cinemeta's catalogs redirect).
 * - No HTTP cache: addon JSON is cached by [AddonResponseCache] from the response's own cache hints.
 * - WebSockets, which Open Watch Party's SDK needs for its relays (`watch/`).
 */
object TortugaHttpClient {
    const val CONNECT_TIMEOUT_MILLIS: Long = 15_000
    const val SOCKET_TIMEOUT_MILLIS: Long = 30_000

    fun userAgent(appVersion: String): String = "Tortuga/$appVersion"

    fun create(appVersion: String, configure: HttpClientConfig<*>.() -> Unit = {}): HttpClient =
        create(appVersion, platformHttpEngine, configure)

    fun <T : HttpClientEngineConfig> create(
        appVersion: String,
        engine: HttpClientEngineFactory<T>,
        configure: HttpClientConfig<*>.() -> Unit = {},
    ): HttpClient = HttpClient(engine) {
        install(UserAgent) { agent = userAgent(appVersion) }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
        }
        followRedirects = true
        install(WebSockets)
        configure()
    }
}

/** OkHttp on Android and the JVM, Darwin (URLSession) on Apple platforms. */
internal expect val platformHttpEngine: HttpClientEngineFactory<HttpClientEngineConfig>

/**
 * Closes the client's idle kept-alive connections, so its next request opens a new one. A kept-alive connection can die
 * silently while the app is in the background or its network changes (a router's NAT entry dropped, a server gone): its
 * socket stays open, nothing answers on it, and the request sent on it waits out the socket timeout. Callable from the
 * main thread: it does no network I/O there (closing a TLS connection writes to it), the connections close on the next
 * request's thread before it takes one.
 */
internal expect fun HttpClient.closeIdleConnections()
