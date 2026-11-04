package com.edde746.tortuga.server.support

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ExternalServicesBuilder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * An OpenID Connect issuer with generated RSA keys, served to the server under test through Ktor's external services:
 * discovery at `/.well-known/openid-configuration` and the key set at `/keys`. [token] mints ID tokens.
 */
class FakeIssuer(val issuer: String, val clientId: String, private val clock: Clock) {
    private val keys = CopyOnWriteArrayList(listOf(newKey()))
    val keySetRequests = AtomicInteger()

    /** The discovery document comes padded with spaces to this many bytes; null: unpadded. */
    @Volatile var discoveryBytes: Int? = null

    /** The key set comes padded with spaces to this many bytes; null: unpadded. */
    @Volatile var keySetBytes: Int? = null

    /** The key set answers with this status: anything but 200 is a provider that can't serve its keys. */
    @Volatile var keySetStatus: HttpStatusCode = HttpStatusCode.OK

    /** The key set's path, as discovery names it; its other paths answer 404 (a provider that moved its key set). */
    @Volatile var keySetPath: String = "keys"

    /** Starts signing with a new key, published alongside the old one (a provider's key rotation). */
    fun rotateKey(): RSAKey = newKey().also { keys += it }

    fun token(
        subject: String,
        audience: List<String> = listOf(clientId),
        nonce: String? = null,
        email: String? = null,
        emailVerified: Boolean = true,
        issuer: String = this.issuer,
        issuedAt: Instant = clock.instant(),
        lifetime: Duration = Duration.ofMinutes(10),
        signingKey: RSAKey = keys.last(),
        authorizedParty: String? = null,
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .audience(audience)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt + lifetime))
            .jwtID(UUID.randomUUID().toString())
        nonce?.let { claims.claim("nonce", it) }
        email?.let { claims.claim("email", it).claim("email_verified", emailVerified) }
        authorizedParty?.let { claims.claim("azp", it) }
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(), claims.build())
        jwt.sign(RSASSASigner(signingKey))
        return jwt.serialize()
    }

    fun mount(services: ExternalServicesBuilder, extraRoutes: Route.() -> Unit = {}) {
        services.hosts(issuer.trimEnd('/')) {
            routing {
                get("/.well-known/openid-configuration") {
                    val document = """{"issuer":"$issuer","jwks_uri":"${issuer.trimEnd('/')}/$keySetPath","id_token_signing_alg_values_supported":["RS256"]}"""
                    call.respondText(document.padEnd(discoveryBytes ?: 0), ContentType.Application.Json)
                }
                get("/{keySet}") {
                    if (call.parameters["keySet"] != keySetPath) return@get call.respondText("", status = HttpStatusCode.NotFound)
                    keySetRequests.incrementAndGet()
                    if (keySetStatus != HttpStatusCode.OK) return@get call.respondText("", status = keySetStatus)
                    val keySet = JWKSet(keys.map { it.toPublicJWK() }).toString()
                    call.respondText(keySet.padEnd(keySetBytes ?: 0), ContentType.Application.Json)
                }
                extraRoutes()
            }
        }
    }

    companion object {
        fun newKey(): RSAKey = RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate()

        /** A key nobody publishes, for forged tokens. */
        val foreignKey: RSAKey by lazy { newKey() }
    }
}

/**
 * Apple's `/auth/token` and `/auth/revoke` for the fake Apple issuer, checking each client secret the way Apple does:
 * an ES256 JWT from the configured team and key, signed with the Sign in with Apple key generated here.
 */
class FakeAppleEndpoints(private val issuer: FakeIssuer, private val clock: Clock) {
    val teamId = "TEAM123456"
    val keyId = "KEY1234567"
    private val signingKey: ECKey = ECKeyGenerator(Curve.P_256).keyID(keyId).generate()

    /** The `.p8` PEM the server is configured with. */
    val privateKeyPem: String =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(signingKey.toECPrivateKey().encoded) +
            "\n-----END PRIVATE KEY-----\n"

    private val codes = HashMap<String, String>()
    val exchanges = CopyOnWriteArrayList<Map<String, String>>()
    val revocations = CopyOnWriteArrayList<Map<String, String>>()

    /** Apple answers revoke with this status. */
    @Volatile var revokeStatus: HttpStatusCode = HttpStatusCode.OK

    /** Apple's tokens come padded with spaces to this many bytes; null: unpadded. */
    @Volatile var tokenAnswerBytes: Int? = null

    /** What Apple's token endpoint answers a valid code with, given the tokens it would send; by default those tokens. */
    @Volatile var tokenAnswer: (String) -> String = { it }

    /** An authorization code Apple would hand the app for [subject]. */
    fun issueCode(subject: String): String = "code-${UUID.randomUUID()}".also { synchronized(codes) { codes[it] = subject } }

    fun routes(route: Route) {
        route.post("/auth/token") {
            val form = call.receiveParameters().entries().associate { it.key to it.value.single() }
            exchanges += form
            val subject = synchronized(codes) { codes.remove(form["code"]) }
            if (!validSecret(form["client_secret"], form["client_id"]) || subject == null) {
                call.respondText("""{"error":"invalid_grant"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }
            val idToken = issuer.token(subject, audience = listOf(form.getValue("client_id")))
            val tokens = """{"access_token":"a","token_type":"Bearer","expires_in":3600,"refresh_token":"apple-refresh-$subject","id_token":"$idToken"}"""
            call.respondText(tokenAnswer(tokens).padEnd(tokenAnswerBytes ?: 0), ContentType.Application.Json)
        }
        route.post("/auth/revoke") {
            val form = call.receiveParameters().entries().associate { it.key to it.value.single() }
            revocations += form
            val status = if (validSecret(form["client_secret"], form["client_id"])) revokeStatus else HttpStatusCode.BadRequest
            call.respondText("", status = status)
        }
    }

    private fun validSecret(secret: String?, clientId: String?): Boolean {
        val jwt = runCatching { SignedJWT.parse(secret) }.getOrNull() ?: return false
        val claims = jwt.jwtClaimsSet
        return jwt.header.algorithm == JWSAlgorithm.ES256 &&
            jwt.header.keyID == keyId &&
            jwt.verify(ECDSAVerifier(signingKey.toECPublicKey())) &&
            claims.issuer == teamId &&
            claims.subject == clientId &&
            claims.audience == listOf("https://appleid.apple.com") &&
            claims.expirationTime.toInstant().isAfter(clock.instant())
    }
}
