package com.edde746.tortuga.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile

/** OkHttp, each client on an [OkHttpClient] of its own, whose [IdleConnectionEviction] [closeIdleConnections] reaches. */
internal actual val platformHttpEngine: HttpClientEngineFactory<HttpClientEngineConfig> = object : HttpClientEngineFactory<OkHttpConfig> {
    override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine = OkHttp.create {
        val pool = ConnectionPool()
        preconfigured = OkHttpClient.Builder().connectionPool(pool).addInterceptor(IdleConnectionEviction(pool)).build()
        block()
    }
}

// OkHttp reuses a pooled connection without probing it (a GET), and doesn't resend a request whose read timed out.
internal actual fun HttpClient.closeIdleConnections() {
    (engine.config as? OkHttpConfig)?.preconfigured?.interceptors?.filterIsInstance<IdleConnectionEviction>()?.single()?.evictBeforeNextRequest()
}

/**
 * Closes [pool]'s idle connections on the next request's thread, before that request takes a connection. The caller of
 * [closeIdleConnections] is the main thread, where closing a connection can't happen: a TLS connection writes its
 * close_notify as it closes, which Android refuses on its main thread (`NetworkOnMainThreadException`) and which blocks
 * a desktop's UI thread while the write waits. OkHttp runs every call, and so this, on its dispatcher's threads.
 */
private class IdleConnectionEviction(private val pool: ConnectionPool) : Interceptor {
    private val requested = AtomicInteger()

    @Volatile private var done = 0

    fun evictBeforeNextRequest() {
        requested.incrementAndGet()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (requested.get() != done) {
            synchronized(this) {
                val request = requested.get()
                if (request != done) {
                    pool.evictAll()
                    done = request
                }
            }
        }
        return chain.proceed(chain.request())
    }
}
