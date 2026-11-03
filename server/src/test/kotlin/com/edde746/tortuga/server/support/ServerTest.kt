package com.edde746.tortuga.server.support

import com.edde746.tortuga.server.AppleConfig
import com.edde746.tortuga.server.AppleSigningKey
import com.edde746.tortuga.server.DatabaseConfig
import com.edde746.tortuga.server.GitHubConfig
import com.edde746.tortuga.server.ProviderConfig
import com.edde746.tortuga.server.RateLimits
import com.edde746.tortuga.server.ServerConfig
import com.edde746.tortuga.server.Services
import com.edde746.tortuga.server.crypto.DataKey
import com.edde746.tortuga.server.tortugaServer
import com.edde746.tortuga.sync.api.ApiError
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.LinkPollRequest
import com.edde746.tortuga.sync.api.LinkStartRequest
import com.edde746.tortuga.sync.api.LinkStartResponse
import com.edde746.tortuga.sync.api.NonceResponse
import com.edde746.tortuga.sync.api.SignInRequest
import com.edde746.tortuga.sync.api.SignInResponse
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ExternalServicesBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.ZERO

/** A clock tests move by hand; the server and the fake issuers share it. */
class TestClock(@Volatile var now: Instant = Instant.parse("2026-09-29T12:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    fun advance(duration: Duration) {
        now += duration
    }
}

/** The tests' data key: 32 random bytes in a temporary file, loaded as the server loads `TORTUGA_DATA_KEY_FILE`. */
val testDataKey: DataKey by lazy {
    val file = Files.createTempFile("tortuga-data", ".key")
    file.toFile().deleteOnExit()
    Files.write(file, ByteArray(DataKey.SIZE).also(SecureRandom()::nextBytes))
    DataKey.load(file)
}

/**
 * A signed-out device's device-link secret, drawn as the protocol says, and the [hash] that starts its link
 * ([LinkStartRequest.secretHash]: unpadded base64url of SHA-256 of the secret's UTF-8 bytes).
 */
class LinkSecret {
    val secret: String = base64Url(ByteArray(32).also(SecureRandom()::nextBytes))
    val hash: String = base64Url(MessageDigest.getInstance("SHA-256").digest(secret.encodeToByteArray()))

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** What a test gets: the fakes, the server's services, and a JSON client for the server. */
class ServerTestContext(
    val client: HttpClient,
    val clock: TestClock,
    val oidc: FakeIssuer,
    val apple: FakeIssuer,
    val google: FakeIssuer,
    val appleEndpoints: FakeAppleEndpoints,
    val services: Services,
    val github: FakeGitHub,
) {
    suspend fun signIn(
        subject: String,
        issuer: FakeIssuer = oidc,
        deviceName: String = "Test phone",
        platform: String = DevicePlatform.ANDROID,
        email: String? = null,
        authorizationCode: String? = null,
    ): SignInResponse {
        val provider = when (issuer) {
            apple -> IdentityProvider.APPLE
            google -> IdentityProvider.GOOGLE
            else -> IdentityProvider.OIDC
        }
        // Apple's tokens must carry a nonce the server issued; the other providers' may go without.
        val nonce = if (provider == IdentityProvider.APPLE) nonce() else null
        val request = SignInRequest(
            provider = provider,
            idToken = issuer.token(subject, nonce = nonce, email = email),
            nonce = nonce,
            authorizationCode = authorizationCode,
            device = DeviceDescriptor(deviceName, platform),
        )
        return post(SyncPaths.AUTH_SIGN_IN, request).ok()
    }

    /** A nonce for the next sign-in ([SyncPaths.AUTH_NONCE]). */
    suspend fun nonce(): String = postEmpty(SyncPaths.AUTH_NONCE).ok<NonceResponse>().nonce

    /** A browser: the return pages' redirects back to the app are read, not followed. */
    val browser: HttpClient by lazy { client.config { followRedirects = false } }

    suspend inline fun <reified B> post(path: String, body: B, token: String? = null): HttpResponse =
        client.post(path) {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    suspend fun postEmpty(path: String, token: String? = null): HttpResponse =
        client.post(path) { token?.let { bearerAuth(it) } }

    /** Starts a device link for a signed-out [device] with [secret]'s hash. */
    suspend fun startLink(secret: LinkSecret, device: DeviceDescriptor): LinkStartResponse =
        post(SyncPaths.LINK_START, LinkStartRequest(secret.hash, device)).ok()

    suspend fun pollLink(linkId: String, secret: String): HttpResponse = post(SyncPaths.LINK_POLL, LinkPollRequest(linkId, secret))

    suspend fun get(path: String, token: String? = null): HttpResponse = client.get(path) { token?.let { bearerAuth(it) } }

    suspend fun delete(path: String, token: String): HttpResponse = client.delete(path) { bearerAuth(token) }
}

/** The response body as [T], after asserting 200. */
suspend inline fun <reified T> HttpResponse.ok(): T {
    assertEquals(HttpStatusCode.OK, status, bodyAsText())
    return body()
}

/** Asserts an error response with [status] and [code]. */
suspend fun HttpResponse.assertError(status: HttpStatusCode, code: String) {
    assertEquals(status, this.status, bodyAsText())
    assertEquals(code, body<ApiError>().code)
}

/**
 * Runs [block] against the whole server (Ktor's test engine) on an emptied Testcontainers database, with fake Apple,
 * Google and OIDC issuers and a fake GitHub (which the server uses only when [configure] adds [withGitHub]).
 * [configure] adjusts the configuration (Apple key, web return page, rate limits); [external] mounts more fake services
 * on the test clock (the tracker services' token endpoints).
 */
fun serverTest(
    configure: (ServerConfig, FakeAppleEndpoints) -> ServerConfig = { config, _ -> config },
    external: (ExternalServicesBuilder, TestClock) -> Unit = { _, _ -> },
    block: suspend ServerTestContext.() -> Unit,
) {
    TestPostgres.reset()
    val clock = TestClock()
    val oidc = FakeIssuer("https://oidc.test", "tortuga-oidc", clock)
    val apple = FakeIssuer("https://apple.test", "com.edde746.tortuga", clock)
    val google = FakeIssuer("https://google.test", "tortuga-google", clock)
    val appleEndpoints = FakeAppleEndpoints(apple, clock)
    val github = FakeGitHub()
    val base = ServerConfig(
        database = DatabaseConfig("unused", "unused", "", 1),
        dataKey = testDataKey,
        apple = AppleConfig(
            issuer = apple.issuer,
            clientIds = listOf(apple.clientId),
            servicesId = "com.edde746.tortuga.web",
            signingKey = null,
            webReturn = false,
            webRedirectUri = "https://tortuga.test" + SyncPaths.APPLE_RETURN,
        ),
        google = ProviderConfig(google.issuer, listOf(google.clientId)),
        oidc = ProviderConfig(oidc.issuer, listOf(oidc.clientId), displayName = "Test IdP"),
        janitorInterval = ZERO,
        rateLimits = RateLimits(auth = 1000, linkStart = 1000, linkAnswer = 1000, linkPoll = 1000),
    )
    val config = configure(base, appleEndpoints)
    testApplication {
        externalServices {
            oidc.mount(this)
            google.mount(this)
            apple.mount(this) { appleEndpoints.routes(this) }
            github.mount(this)
            external(this, clock)
        }
        val outbound = createClient { expectSuccess = false }
        val services = Services(config, TestPostgres.database, outbound, clock)
        application { tortugaServer(services) }
        val client = createClient {
            expectSuccess = false
            install(ContentNegotiation) { json(SyncProtocol.json) }
        }
        ServerTestContext(client, clock, oidc, apple, google, appleEndpoints, services, github).block()
    }
}

/** [ServerConfig] with Tortuga's Apple key configured (the hosted instance). */
fun withAppleKey(config: ServerConfig, endpoints: FakeAppleEndpoints, webReturn: Boolean = false): ServerConfig =
    config.copy(
        apple = config.apple!!.copy(
            signingKey = AppleSigningKey(endpoints.teamId, endpoints.keyId, endpoints.privateKeyPem),
            webReturn = webReturn,
        ),
    )

/** [ServerConfig] with Sign in with GitHub through [FakeGitHub]. */
fun withGitHub(config: ServerConfig): ServerConfig = config.copy(
    github = GitHubConfig(
        clientId = FakeGitHub.CLIENT_ID,
        clientSecret = FakeGitHub.CLIENT_SECRET,
        url = FakeGitHub.URL,
        apiUrl = FakeGitHub.API_URL,
        tokenUrl = "${FakeGitHub.URL}/login/oauth/access_token",
    ),
)

/** [ServerConfig.fromEnvironment] with a database and a throwaway data key, plus [values]. */
fun configFromEnvironment(vararg values: Pair<String, String>): ServerConfig {
    val key = Files.createTempFile("tortuga-data", ".key")
    try {
        Files.write(key, ByteArray(DataKey.SIZE).also(SecureRandom()::nextBytes))
        val env = mapOf(
            "TORTUGA_DATABASE_URL" to "jdbc:postgresql://postgres:5432/tortuga",
            "TORTUGA_DATABASE_USER" to "tortuga",
            DataKey.ENV to key.toString(),
        )
        return ServerConfig.fromEnvironment(env + values)
    } finally {
        Files.delete(key)
    }
}
