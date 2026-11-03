package com.edde746.tortuga.server

import com.edde746.tortuga.server.crypto.RecordCipher
import com.edde746.tortuga.server.crypto.checkDataKey
import com.edde746.tortuga.server.db.Database
import com.edde746.tortuga.server.http.BodyGate
import com.edde746.tortuga.server.http.ByteBudget
import com.edde746.tortuga.server.http.Connections
import com.zaxxer.hikari.pool.HikariPool
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.netty.channel.ChannelOption
import java.time.Clock
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * The server, as it runs and as the engine tests run it, on Netty: Ktor 3.6.0's CIO keeps a thread spinning on each
 * socket whose client stops reading (server/README.md). [connections] caps the connections the server holds and the
 * accept queue, and holds each connection's kernel buffers to [Connections.RECEIVE_BUFFER_BYTES] received (on the
 * listening socket, which accepted ones inherit) and [Connections.SEND_BUFFER_BYTES] sent: memory that counts against
 * the container's limit and that the byte budget doesn't count. Each HTTP/1 connection's request bodies go through a
 * [BodyGate] on [bytes], the budget the module admits calls against ([installByteBudget]): no body is read before its
 * call is admitted, an admitted one comes in through a raised receive buffer while [connections] has room for it, and
 * none may arrive slower than [bodyGrace] plus its length at [bodyBytesPerSecond].
 */
fun tortugaEmbeddedServer(
    port: Int,
    host: String,
    bytes: ByteBudget,
    connections: Connections = Connections.ofMemory(),
    bodyGrace: Duration = BodyGate.GRACE,
    bodyBytesPerSecond: Long = BodyGate.BYTES_PER_SECOND,
    module: Application.() -> Unit,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    // Ktor's Netty engine tries kqueue and epoll before NIO. The distribution has their classes but none of their
    // native libraries, so the try can only fail, and on Linux it fails through System.loadLibrary: native access,
    // which JDK 24+ restricts and warns about on stderr. Netty reads this when the engine first tries.
    System.setProperty("io.netty.transport.noNative", "true")
    return embeddedServer(
        Netty,
        configure = {
            connector {
                this.port = port
                this.host = host
            }
            configureBootstrap = {
                handler(connections)
                option(ChannelOption.SO_BACKLOG, connections.backlog)
                option(ChannelOption.SO_RCVBUF, Connections.RECEIVE_BUFFER_BYTES)
                childOption(ChannelOption.SO_SNDBUF, Connections.SEND_BUFFER_BYTES)
            }
            channelPipelineConfig = {
                // Between the codec and Ktor's handler, on HTTP/1 connections (the only kind: no TLS, no h2c).
                if (get(KTOR_HTTP1_HANDLER) != null) {
                    addBefore(KTOR_HTTP1_HANDLER, "tortugaBodyGate", BodyGate(bytes, connections, bodyGrace, bodyBytesPerSecond))
                }
            }
        },
        module = module,
    )
}

/** The name Ktor's Netty engine gives its HTTP/1 handler in each connection's pipeline. */
private const val KTOR_HTTP1_HANDLER = "http1"

/** `tortuga-server`: serves the API on `TORTUGA_HOST:TORTUGA_PORT` after migrating the database. */
fun main() {
    val log = LoggerFactory.getLogger("com.edde746.tortuga.server")
    // A missing or invalid setting (the data key above all), a database that doesn't let it in within a minute
    // (Database.connect: a wrong address or password, or Postgres down), or a data key that isn't the database's stops
    // the server with one line that says what to fix.
    val config = try {
        ServerConfig.fromEnvironment()
    } catch (e: IllegalArgumentException) {
        log.error("Not starting: {}", e.message)
        exitProcess(1)
    }
    val db = try {
        Database.connect(config.database.url, config.database.user, config.database.password, config.database.poolSize)
    } catch (e: HikariPool.PoolInitializationException) {
        log.error("Not starting: no connection to the database at TORTUGA_DATABASE_URL: {}", e.cause?.message ?: e.message)
        exitProcess(1)
    }
    try {
        runBlocking { checkDataKey(db, RecordCipher(config.dataKey)) }
    } catch (e: IllegalArgumentException) {
        log.error("Not starting: {}", e.message)
        exitProcess(1)
    }
    val http = HttpClient(OkHttp) {
        install(UserAgent) { agent = "Tortuga-Server" }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
        }
        expectSuccess = false
    }
    val services = Services(config, db, http, Clock.systemUTC())
    val connections = Connections.ofMemory()
    log.atInfo()
        .addKeyValue("providers", services.info.providers.joinToString(",") { it.id })
        .addKeyValue("appleTokenRevocation", services.apple != null)
        .addKeyValue("appleWebReturn", config.apple?.webReturn == true)
        // Its client ID and addresses; GitHubConfig leaves the secret out.
        .addKeyValue("github", config.github?.toString() ?: "off")
        .addKeyValue("trackerOAuth", services.trackerOAuth.services.joinToString(","))
        .addKeyValue("maxConnections", connections.max)
        .addKeyValue("connectionBacklog", connections.backlog)
        .log("Starting on {}:{}", config.host, config.port)
    config.devTrackersUrl?.let {
        log.warn("TORTUGA_DEV_TRACKERS_URL is set: tracker refreshes and revokes go to the fake tracker server at {}, not the services", it)
    }
    tortugaEmbeddedServer(config.port, config.host, services.bytes, connections) { tortugaServer(services) }.start(wait = true)
}
