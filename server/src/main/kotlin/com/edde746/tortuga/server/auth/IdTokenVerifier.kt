package com.edde746.tortuga.server.auth

import com.edde746.tortuga.server.http.ApiException
import com.edde746.tortuga.server.http.bodyAsTextAtMost
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.security.Key
import java.text.ParseException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * A sign-in provider as the verifier sees it.
 *
 * @property issuer the canonical issuer: identities are stored under it, and discovery is fetched from it.
 * @property acceptedIssuers `iss` values that mean this provider (Google also uses `accounts.google.com`).
 * @property jwksUri where the signing keys are fetched instead of the discovery document's `jwks_uri`: for a server
 *   that can't reach the provider under its public [issuer] (a container next to the provider).
 */
class ProviderSpec(
    val id: String,
    val issuer: String,
    val acceptedIssuers: Set<String>,
    val clientIds: List<String>,
    val displayName: String? = null,
    val jwksUri: String? = null,
)

/** The claims the server keeps from a verified ID token. */
class VerifiedIdToken(
    val provider: ProviderSpec,
    val subject: String,
    /** Only if the provider marked it verified. */
    val email: String?,
    val nonce: String?,
    /** The matched client ID (`aud`). */
    val audience: String,
    /**
     * The last instant [IdTokenVerifier] accepts this token: its `exp` plus the clock skew. Its used-token marker is
     * kept until then.
     */
    val acceptedUntil: Instant,
    val tokenHash: ByteArray,
)

/**
 * Verifies OpenID Connect ID tokens against each provider's published keys: signature (RS/PS/ES algorithms only),
 * issuer, audience (and `azp` when there are several audiences), `exp`, `iat`, `nbf` and `sub`.
 *
 * Keys come from the provider's discovery document (`/.well-known/openid-configuration` → `jwks_uri`), or from
 * [ProviderSpec.jwksUri] when it is set, and are cached for an hour; a token signed by an unknown key id refetches
 * them, at most every 30 seconds per provider.
 * Single use and nonces are checked by the caller against the database ([VerifiedIdToken.tokenHash]).
 */
class IdTokenVerifier(
    private val http: HttpClient,
    providers: List<ProviderSpec>,
    private val clock: Clock,
) {
    private val providers = providers.associateBy { it.id }
    private val keySets = ConcurrentHashMap<String, CachedKeys>()
    private val jwksUris = ConcurrentHashMap<String, String>()
    private val fetchLock = Mutex()
    private val verifierFactory = DefaultJWSVerifierFactory()

    fun provider(id: String): ProviderSpec? = providers[id]

    suspend fun verify(providerId: String, idToken: String): VerifiedIdToken {
        val provider = providers[providerId]
            ?: throw ApiException(HttpStatusCode.BadRequest, ApiErrorCode.PROVIDER_NOT_ENABLED, "Provider not enabled")
        val jwt = try {
            SignedJWT.parse(idToken)
        } catch (e: ParseException) {
            throw invalid("not a signed JWT")
        }
        if (jwt.header.algorithm !in ALLOWED_ALGORITHMS) throw invalid("algorithm not allowed")
        verifySignature(provider, jwt)
        return checkClaims(provider, jwt, idToken)
    }

    private suspend fun verifySignature(provider: ProviderSpec, jwt: SignedJWT) {
        val selector = JWKSelector(JWKMatcher.forJWSHeader(jwt.header))
        var candidates = selector.select(keys(provider, refresh = false))
        if (candidates.isEmpty()) candidates = selector.select(keys(provider, refresh = true))
        if (candidates.isEmpty()) throw invalid("no matching signing key")
        val verified = candidates.any { jwk ->
            val key = publicKey(jwk) ?: return@any false
            try {
                jwt.verify(verifierFactory.createJWSVerifier(jwt.header, key))
            } catch (e: Exception) {
                false
            }
        }
        if (!verified) throw invalid("bad signature")
    }

    private fun checkClaims(provider: ProviderSpec, jwt: SignedJWT, idToken: String): VerifiedIdToken {
        val claims = try {
            jwt.jwtClaimsSet
        } catch (e: ParseException) {
            throw invalid("unreadable claims")
        }
        val now = clock.instant()
        val skew = CLOCK_SKEW.toJavaDuration()
        try {
            if (claims.issuer !in provider.acceptedIssuers) throw invalid("issuer")
            val audience = claims.audience.orEmpty()
            val matched = audience.firstOrNull { it in provider.clientIds } ?: throw invalid("audience")
            if (audience.size > 1) {
                val azp = claims.getStringClaim("azp")
                if (azp == null || azp !in provider.clientIds) throw invalid("authorized party")
            }
            val expiresAt = claims.expirationTime?.toInstant() ?: throw invalid("no exp")
            val acceptedUntil = expiresAt.plus(skew)
            if (!acceptedUntil.isAfter(now)) throw invalid("expired")
            if (expiresAt.isAfter(now.plus(MAX_TOKEN_LIFETIME.toJavaDuration()).plus(skew))) throw invalid("exp too far")
            val issuedAt = claims.issueTime?.toInstant() ?: throw invalid("no iat")
            if (issuedAt.isAfter(now.plus(skew))) throw invalid("issued in the future")
            claims.notBeforeTime?.toInstant()?.let { if (it.isAfter(now.plus(skew))) throw invalid("not yet valid") }
            val subject = claims.subject?.takeIf { it.isNotBlank() && it.length <= 255 } ?: throw invalid("subject")
            val emailVerified = when (val value = claims.getClaim("email_verified")) {
                is Boolean -> value
                is String -> value.equals("true", ignoreCase = true)
                else -> false
            }
            return VerifiedIdToken(
                provider = provider,
                subject = subject,
                email = claims.getStringClaim("email")?.takeIf { emailVerified && it.length <= 320 },
                nonce = claims.getStringClaim("nonce"),
                audience = matched,
                acceptedUntil = acceptedUntil,
                tokenHash = Secrets.sha256(idToken),
            )
        } catch (e: ParseException) {
            throw invalid("claim type")
        }
    }

    private suspend fun keys(provider: ProviderSpec, refresh: Boolean): JWKSet {
        val cached = keySets[provider.id]
        if (cached != null && !refresh && cached.age() < KEYS_TTL) return cached.keys
        return fetchLock.withLock {
            val current = keySets[provider.id]
            // Another request refreshed while this one waited, or a refresh just happened: use what is there.
            if (current != null && current !== cached) return@withLock current.keys
            if (current != null && refresh && current.age() < MIN_REFRESH_INTERVAL) return@withLock current.keys
            try {
                val fetched = fetchKeys(provider)
                keySets[provider.id] = CachedKeys(fetched, clock.instant())
                fetched
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Fetching signing keys of provider {} failed: {}", provider.id, e.toString())
                current?.keys ?: throw ApiException(
                    HttpStatusCode.BadGateway,
                    ApiErrorCode.UPSTREAM_UNAVAILABLE,
                    "Provider keys unavailable",
                )
            }
        }
    }

    private suspend fun fetchKeys(provider: ProviderSpec): JWKSet {
        val uri = provider.jwksUri ?: jwksUris[provider.id] ?: discoverJwksUri(provider).also { jwksUris[provider.id] = it }
        val body = http.prepareGet(uri).execute { response ->
            check(response.status.isSuccess()) { "JWKS returned ${response.status.value}" }
            response.bodyAsTextAtMost("the JWKS at $uri", MAX_KEY_SET_BYTES)
        }
        return JWKSet.parse(body)
    }

    private suspend fun discoverJwksUri(provider: ProviderSpec): String {
        val url = provider.issuer.trimEnd('/') + "/.well-known/openid-configuration"
        val body = http.prepareGet(url).execute { response ->
            check(response.status.isSuccess()) { "Discovery returned ${response.status.value}" }
            response.bodyAsTextAtMost("the discovery document at $url", MAX_DISCOVERY_BYTES)
        }
        val document = Json.parseToJsonElement(body).jsonObject
        check(document.string("issuer") == provider.issuer) { "Discovery issuer differs from the configured issuer" }
        return document.string("jwks_uri") ?: error("Discovery has no jwks_uri")
    }

    private fun CachedKeys.age(): Duration = (clock.millis() - fetchedAt.toEpochMilli()).coerceAtLeast(0).milliseconds

    private class CachedKeys(val keys: JWKSet, val fetchedAt: Instant)

    companion object {
        private val log = LoggerFactory.getLogger(IdTokenVerifier::class.java)

        private val ALLOWED_ALGORITHMS = setOf(
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512,
        )
        private val KEYS_TTL = 1.hours
        private val MIN_REFRESH_INTERVAL = 30.seconds

        /** Tolerated clock difference when checking `exp`, `iat` and `nbf`. */
        private val CLOCK_SKEW = 60.seconds

        /** ID tokens are short-lived (Apple 10 minutes, Google an hour); longer ones are refused. */
        private val MAX_TOKEN_LIFETIME = 24.hours

        /**
         * A discovery document is a few KiB (Google's 1.3, Keycloak's about 7, with every supported algorithm listed);
         * anything longer isn't one.
         */
        internal const val MAX_DISCOVERY_BYTES = 64L * 1024

        /**
         * A key set is a few keys, each with its certificate chain at most (Microsoft Entra's, the largest known, about
         * 20 KiB); anything longer isn't one.
         */
        internal const val MAX_KEY_SET_BYTES = 256L * 1024

        private fun invalid(reason: String) =
            ApiException(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN, "ID token rejected: $reason")

        private fun publicKey(jwk: JWK): Key? = when (jwk) {
            is RSAKey -> jwk.toRSAPublicKey()
            is ECKey -> jwk.toECPublicKey()
            else -> null
        }

        private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.content
    }
}
