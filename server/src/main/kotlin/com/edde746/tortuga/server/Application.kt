package com.edde746.tortuga.server

import com.edde746.tortuga.server.auth.AppleClient
import com.edde746.tortuga.server.auth.GitHubClient
import com.edde746.tortuga.server.auth.IdTokenVerifier
import com.edde746.tortuga.server.auth.ProviderSpec
import com.edde746.tortuga.server.auth.Secrets
import com.edde746.tortuga.server.crypto.RecordCipher
import com.edde746.tortuga.server.db.Database
import com.edde746.tortuga.server.http.BearerKey
import com.edde746.tortuga.server.http.ByteBudget
import com.edde746.tortuga.server.http.bearerToken
import com.edde746.tortuga.server.http.clientAddress
import com.edde746.tortuga.server.http.installByteBudget
import com.edde746.tortuga.server.http.installErrorHandling
import com.edde746.tortuga.server.routes.accountRoutes
import com.edde746.tortuga.server.routes.appleWebRoutes
import com.edde746.tortuga.server.routes.authRoutes
import com.edde746.tortuga.server.routes.gitHubWebRoutes
import com.edde746.tortuga.server.routes.linkRoutes
import com.edde746.tortuga.server.routes.reviewRoutes
import com.edde746.tortuga.server.routes.syncEventRoutes
import com.edde746.tortuga.server.routes.syncRoutes
import com.edde746.tortuga.server.routes.trackerConnectionRoutes
import com.edde746.tortuga.server.routes.trackerOAuthRoutes
import com.edde746.tortuga.server.service.Accounts
import com.edde746.tortuga.server.service.AppleHandoffs
import com.edde746.tortuga.server.service.DeviceLinks
import com.edde746.tortuga.server.service.GitHubHandoffs
import com.edde746.tortuga.server.service.Janitor
import com.edde746.tortuga.server.service.ReviewSignIn
import com.edde746.tortuga.server.service.Sessions
import com.edde746.tortuga.server.service.SyncEvents
import com.edde746.tortuga.server.service.SyncRecords
import com.edde746.tortuga.server.service.TrackerConnections
import com.edde746.tortuga.server.service.TrackerOAuthRelay
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.ProviderInfo
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.trackers.devTrackerRedirect
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import java.nio.file.Files
import java.time.Clock
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The services behind the routes, built once per application from [ServerConfig]. */
class Services(val config: ServerConfig, val db: Database, http: HttpClient, val clock: Clock) {
    val providers: List<ProviderSpec> = buildList {
        config.apple?.let { add(ProviderSpec(IdentityProvider.APPLE, it.issuer, setOf(it.issuer), it.audiences)) }
        config.google?.let {
            // Google signs with either form of its issuer.
            val aliases = if (it.issuer == ServerConfig.DEFAULT_GOOGLE_ISSUER) setOf("accounts.google.com") else emptySet()
            add(ProviderSpec(IdentityProvider.GOOGLE, it.issuer, aliases + it.issuer, it.clientIds))
        }
        config.oidc?.let { add(ProviderSpec(IdentityProvider.OIDC, it.issuer, setOf(it.issuer), it.clientIds, it.displayName, it.jwksUri)) }
    }
    val apple: AppleClient? = config.apple?.let { apple ->
        apple.signingKey?.let { AppleClient(http, apple, it, clock) }
    }
    val github: GitHubClient? = config.github?.let { GitHubClient(http, it) }
    val verifier = IdTokenVerifier(http, providers, clock)
    val events = SyncEvents(config.maxEventStreams)
    val sessions = Sessions(db, events, clock, config.accessTokenTtl, config.sessionIdleTtl)
    private val cipher = RecordCipher(config.dataKey)
    // A development stack sends tracker refreshes and revokes to its fake tracker server instead of the services.
    val trackerConnections = TrackerConnections(
        db,
        sessions,
        events,
        cipher,
        config.devTrackersUrl?.let { url -> http.config { install(devTrackerRedirect(url)) } } ?: http,
        clock,
    )
    val accounts = Accounts(db, verifier, apple, github, cipher, sessions, trackerConnections, events, clock)
    val links = DeviceLinks(db, sessions, clock)
    /** The review sign-in, where its password's hash is configured; null (self-hosted servers): no review page. */
    val review: ReviewSignIn? = config.reviewPasswordSha256?.let { ReviewSignIn(it, links, accounts) }
    val records = SyncRecords(
        db, sessions, cipher, trackerConnections, events, clock, config.accountQuotaBytes,
        config.databaseDisk?.let { Files.getFileStore(it) }, config.minFreeDatabaseDiskBytes,
    )
    val handoffs = AppleHandoffs(db, cipher, clock)
    val githubHandoffs: GitHubHandoffs? = github?.let { GitHubHandoffs(db, it, cipher, clock) }
    val janitor = Janitor(db, events, clock, config.janitorInterval)
    val trackerOAuth = TrackerOAuthRelay(config.trackerOAuth, http, clock)
    val bytes = ByteBudget(config.requestBytes)

    val info = ServerInfo(
        protocolVersion = SyncProtocol.VERSION,
        // GitHub issues no ID tokens, so it isn't among the verifier's providers; its issuer is GitHub's web address.
        providers = providers.map { ProviderInfo(it.id, it.issuer, it.clientIds, it.displayName) } +
            listOfNotNull(config.github?.let { ProviderInfo(IdentityProvider.GITHUB, it.url, listOf(it.clientId)) }),
        appleTokenRevocation = apple != null,
        appleWebReturn = config.apple?.webReturn == true,
        accountQuotaBytes = config.accountQuotaBytes,
    )
}

/** Rate limiter names (limits in [RateLimits]). */
object Limits {
    val API = RateLimitName("api")
    val AUTH = RateLimitName("auth")
    val LINK_START = RateLimitName("link-start")
    val LINK_ANSWER = RateLimitName("link-answer")
    val LINK_POLL = RateLimitName("link-poll")
    val TRACKER_OAUTH_START = RateLimitName("tracker-oauth-start")
    val TRACKER_OAUTH_RESULT = RateLimitName("tracker-oauth-result")
    val SIGN_IN_RETURN = RateLimitName("sign-in-return")
    val REVIEW = RateLimitName("review")
}

/** Polled by the container's health check: 200 while the database answers. */
private const val HEALTH_PATH = "/healthz"

/**
 * `/healthz`'s check: the database answered within the last [TTL]. One call at a time asks it, so health checks cost
 * the database at most one query per [TTL] however many arrive.
 */
private class HealthCheck(private val db: Database) {
    private val asking = Mutex()
    @Volatile private var answered: TimeMark? = null

    suspend fun check() {
        if (fresh()) return
        asking.withLock {
            if (fresh()) return
            db.transaction { queryOne("SELECT 1") { it.getInt(1) } }
            answered = TimeSource.Monotonic.markNow()
        }
    }

    private fun fresh(): Boolean = answered?.let { it.elapsedNow() < TTL } ?: false

    companion object {
        val TTL = 5.seconds
    }
}

/** The whole server: plugins, routes and the cleanup and keepalive jobs. */
fun Application.tortugaServer(services: Services) {
    val config = services.config
    installServerPlugins(config, services.bytes)
    install(SSE)

    val health = HealthCheck(services.db)
    routing {
        get(SyncPaths.INFO) { call.respond(services.info) }
        get(HEALTH_PATH) {
            health.check()
            call.respondText("ok", ContentType.Text.Plain, HttpStatusCode.OK)
        }
        // Every request these take reads a token from the database, also a bad one: a generous limit per client keeps
        // a flood from taking the connections real requests need. Their own, tighter limits apply as well.
        rateLimit(Limits.API) {
            authRoutes(services)
            accountRoutes(services)
            linkRoutes(services)
            syncRoutes(services)
            syncEventRoutes(services)
            trackerConnectionRoutes(services)
        }
        if (config.apple?.webReturn == true) appleWebRoutes(services)
        services.githubHandoffs?.let { gitHubWebRoutes(it) }
        services.review?.let { reviewRoutes(it) }
        trackerOAuthRoutes(services.trackerOAuth)
    }

    if (config.janitorInterval.isPositive()) services.janitor.start(this)
    services.events.start(this)
}

/**
 * The plugins every route relies on: JSON, client addresses, body limits and the calls' [bytes], request ids and
 * logging, rate limits, errors.
 */
fun Application.installServerPlugins(config: ServerConfig, bytes: ByteBudget = ByteBudget(config.requestBytes)) {
    install(ContentNegotiation) { json(SyncProtocol.json) }
    // The last X-Forwarded-For entry is the address the proxy in front saw; the ones before it are what the client sent.
    if (config.trustForwardedHeaders) install(XForwardedHeaders) { useLastProxy() }
    install(RequestBodyLimit) { bodyLimit(::largestBody) }
    installByteBudget(bytes, ::largestBody)
    install(CallId) {
        generate { UUID.randomUUID().toString() }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        // No ANSI colours: with them Ktor installs Jansi's console, which loads a native library (and JDK 24+ warns on
        // stderr, outside the JSON log); the lines are requestLine's anyway. The build leaves Jansi out.
        disableDefaultColors()
        callIdMdc("requestId")
        // The container's health check calls every 30 s; its lines would say nothing.
        filter { it.request.path() != HEALTH_PATH }
        // Evaluated again when the line is written, after the call's work has authenticated it.
        mdc("accountId") { it.attributes.getOrNull(BearerKey)?.session?.accountId?.toString() }
        // Status, method, path (no query) and time: never tokens, emails or bodies.
        format { call -> requestLine(call) }
    }
    install(RateLimit) {
        val limits = config.rateLimits
        register(Limits.API) {
            rateLimiter(limit = limits.api, refillPeriod = 1.minutes)
            requestKey { it.clientAddress }
        }
        register(Limits.SIGN_IN_RETURN) {
            rateLimiter(limit = limits.signInReturn, refillPeriod = 1.minutes)
            requestKey { it.clientAddress }
        }
        register(Limits.AUTH) {
            rateLimiter(limit = limits.auth, refillPeriod = 1.minutes)
            requestKey { it.clientAddress }
        }
        register(Limits.LINK_START) {
            rateLimiter(limit = limits.linkStart, refillPeriod = 1.minutes)
            requestKey { it.clientAddress }
        }
        register(Limits.LINK_ANSWER) {
            rateLimiter(limit = limits.linkAnswer, refillPeriod = 1.minutes)
            // The token as the session reads it: however the header is written, one session has one count.
            requestKey { call -> call.bearerToken()?.let(Secrets::sha256Base64Url) ?: call.clientAddress }
        }
        register(Limits.LINK_POLL) {
            rateLimiter(limit = limits.linkPoll, refillPeriod = 1.minutes)
            requestKey { it.clientAddress }
        }
        register(Limits.TRACKER_OAUTH_START) {
            rateLimiter(limit = limits.trackerOAuthStart, refillPeriod = 1.minutes)
            requestKey { it.clientAddress }
        }
        register(Limits.TRACKER_OAUTH_RESULT) {
            rateLimiter(limit = limits.trackerOAuthResult, refillPeriod = 1.minutes)
            requestKey { it.clientAddress }
        }
        register(Limits.REVIEW) {
            rateLimiter(limit = limits.review, refillPeriod = 1.minutes)
            requestKey { it.clientAddress }
        }
    }
    installErrorHandling()
}

/** The largest body of every request but a push: a sign-in's ID token and code, a tracker's tokens, all under 24 KiB. */
private const val SMALL_BODY_BYTES = 64L * 1024

/** The largest body [call]'s path takes. */
private fun largestBody(call: ApplicationCall): Long = when (call.request.path()) {
    SyncPaths.SYNC_PUSH -> SyncProtocol.MAX_REQUEST_BYTES.toLong()
    else -> SMALL_BODY_BYTES
}

private fun requestLine(call: ApplicationCall): String {
    val status = call.response.status()?.value ?: 0
    return "$status ${call.request.httpMethod.value} ${call.request.path()} ${call.processingTimeMillis()}ms"
}
