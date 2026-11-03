package com.edde746.tortuga.server.auth

import com.edde746.tortuga.server.AppleConfig
import com.edde746.tortuga.server.AppleSigningKey
import com.edde746.tortuga.server.http.ApiException
import com.edde746.tortuga.server.http.MAX_TOKEN_ANSWER_BYTES
import com.edde746.tortuga.server.http.bodyAsTextAtMost
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.prepareForm
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.ParseException
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Sign in with Apple's REST API with Tortuga's key (Apple TN3194): exchanges an authorization code for a refresh
 * token at sign-in, and revokes that token when the account is deleted. Exists only when a key is configured.
 */
class AppleClient(
    private val http: HttpClient,
    private val config: AppleConfig,
    private val key: AppleSigningKey,
    private val clock: Clock,
) {
    private val signer = ECDSASigner(parsePrivateKey(key.privateKeyPem))
    private val secrets = ConcurrentHashMap<String, CachedSecret>()
    private val baseUrl = config.issuer.trimEnd('/')

    /**
     * Exchanges [code] for Apple's refresh token for [clientId] (the ID token's audience) and checks that it belongs to
     * [subject].
     */
    suspend fun exchangeCode(code: String, clientId: String, subject: String): String {
        val (status, body) = try {
            http.prepareForm(
                url = "$baseUrl/auth/token",
                formParameters = parameters {
                    append("client_id", clientId)
                    append("client_secret", clientSecret(clientId))
                    append("code", code)
                    append("grant_type", "authorization_code")
                    // Web-flow codes must repeat the redirect URI of the authorization request.
                    if (clientId == config.servicesId) config.webRedirectUri?.let { append("redirect_uri", it) }
                },
            ).execute { response -> response.status to response.bodyAsTextAtMost("Apple's token endpoint", MAX_TOKEN_ANSWER_BYTES) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Apple token request failed: {}", e.toString())
            throw ApiException(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE, "Apple unreachable")
        }
        if (status == HttpStatusCode.BadRequest) {
            throw ApiException(HttpStatusCode.Unauthorized, ApiErrorCode.APPLE_CODE_REJECTED, "Apple rejected the code")
        }
        if (!status.isSuccess()) {
            log.warn("Apple token endpoint returned {}", status.value)
            throw ApiException(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE, "Apple token endpoint failed")
        }
        // The response comes straight from Apple over TLS; its ID token names whose code this was.
        val (refreshToken, codeSubject) = tokenAnswer(body) ?: run {
            log.warn("Apple token endpoint answered {} without a refresh token and a readable ID token", status.value)
            throw ApiException(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE, "Apple token endpoint failed")
        }
        if (codeSubject != subject) {
            throw ApiException(HttpStatusCode.Unauthorized, ApiErrorCode.APPLE_CODE_REJECTED, "Code is for another user")
        }
        return refreshToken
    }

    /**
     * The refresh token of Apple's token answer and its ID token's subject; null when [body] isn't such an answer. The
     * parser's message quotes the input, which holds tokens: unlogged.
     */
    private fun tokenAnswer(body: String): Pair<String, String>? {
        val json = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        val refreshToken = json.string("refresh_token") ?: return null
        val idToken = json.string("id_token") ?: return null
        val codeSubject = try {
            SignedJWT.parse(idToken).jwtClaimsSet.subject
        } catch (_: ParseException) {
            null
        } ?: return null
        return refreshToken to codeSubject
    }

    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Revokes [refreshToken]; true if Apple confirmed. */
    suspend fun revoke(refreshToken: String, clientId: String): Boolean = try {
        // The status is all a revoke answers; its body is never read.
        val status = http.prepareForm(
            url = "$baseUrl/auth/revoke",
            formParameters = parameters {
                append("client_id", clientId)
                append("client_secret", clientSecret(clientId))
                append("token", refreshToken)
                append("token_type_hint", "refresh_token")
            },
        ).execute { it.status }
        if (!status.isSuccess()) log.warn("Apple revoke returned {}", status.value)
        status.isSuccess()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Apple revoke endpoint unreachable: {}", e.toString())
        false
    }

    /** The ES256 client secret JWT Apple requires (valid for an hour, reused until five minutes before expiry). */
    private fun clientSecret(clientId: String): String {
        val now = clock.instant()
        secrets[clientId]?.takeIf { it.expiresAt.isAfter(now.plusSeconds(300)) }?.let { return it.value }
        val expiresAt = now.plusSeconds(3600)
        val jwt = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.keyId).build(),
            JWTClaimsSet.Builder()
                .issuer(key.teamId)
                .subject(clientId)
                .audience(APPLE_AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .build(),
        )
        jwt.sign(signer)
        return jwt.serialize().also { secrets[clientId] = CachedSecret(it, expiresAt) }
    }

    private class CachedSecret(val value: String, val expiresAt: Instant)

    companion object {
        private val log = LoggerFactory.getLogger(AppleClient::class.java)

        /** The client secret's `aud` is always Apple's production issuer, whichever endpoint is called. */
        private const val APPLE_AUDIENCE = "https://appleid.apple.com"

        fun parsePrivateKey(pem: String): ECPrivateKey {
            val der = Base64.getMimeDecoder().decode(
                pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").trim(),
            )
            return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)) as ECPrivateKey
        }
    }
}
