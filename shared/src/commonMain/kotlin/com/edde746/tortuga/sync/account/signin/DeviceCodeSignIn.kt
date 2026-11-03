package com.edde746.tortuga.sync.account.signin

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ProviderCredential
import com.edde746.tortuga.sync.account.signin.OAuth.long
import com.edde746.tortuga.sync.account.signin.OAuth.string
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.trackers.client.TokenAnswers
import io.ktor.client.HttpClient
import io.ktor.http.parameters
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.delay

/**
 * What the TV shows while the user signs in on another device: "go to [verificationUri] and enter [userCode]", or a
 * QR code of [verificationUriComplete] where the provider offers one.
 */
class DeviceUserCode(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val expiresAtMillis: Long,
)

/**
 * The OAuth 2.0 device authorization grant (RFC 8628): Google, GitHub and the self-hoster's provider on TVs, where
 * the user signs in on a phone or computer with a short code, so the TV needs no typing. The token it
 * yields has no `nonce` claim, so it goes to the server without one.
 *
 * Google's variant: the device endpoint answers `verification_url` (RFC 8628 says `verification_uri`), and the
 * "TVs and Limited Input devices" client sends its client secret, which Google doesn't treat as confidential.
 *
 * GitHub's ([signInWithGitHub]): the endpoints sit under the issuer, which publishes no discovery document; GitHub
 * answers JSON only to requests that ask for it (`Accept: application/json`, as [OAuth.postForm] does); its token
 * endpoint reports pending, slow-down, refusal and expiry with HTTP 200 and an `error`; and the credential is the
 * `access_token` (GitHub issues no ID token).
 */
class DeviceCodeSignIn internal constructor(private val http: HttpClient, private val clock: () -> Long) {
    constructor(http: HttpClient) : this(http, { Clock.System.now().toEpochMilliseconds() })

    /**
     * Requests a user code, reports it through [onUserCode], and polls the token endpoint until the user approved,
     * declined or the code expired.
     *
     * @throws AccountException [AccountError.SignInCancelled] (declined), [AccountError.SignInExpired],
     *   [AccountError.ProviderUnavailable], [AccountError.SignInRejected] (any other refusal).
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun signIn(
        clientId: String,
        clientSecret: String?,
        onUserCode: (DeviceUserCode) -> Unit,
        provider: String = IdentityProvider.GOOGLE,
        deviceAuthorizationEndpoint: String = GoogleOAuth.DEVICE_AUTHORIZATION_ENDPOINT,
        tokenEndpoint: String = GoogleOAuth.TOKEN_ENDPOINT,
        scope: String = "openid email",
    ): ProviderCredential = deviceFlow(clientId, clientSecret, onUserCode, provider, deviceAuthorizationEndpoint, tokenEndpoint, scope, ID_TOKEN)

    /**
     * GitHub's device flow at [issuer] (the server's GitHub [com.edde746.tortuga.sync.api.ProviderInfo.issuer]) for the
     * server's GitHub OAuth App [clientId], which needs no secret here; the credential carries GitHub's access token.
     *
     * @throws AccountException as [signIn].
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun signInWithGitHub(issuer: String, clientId: String, onUserCode: (DeviceUserCode) -> Unit): ProviderCredential = deviceFlow(
        clientId = clientId,
        clientSecret = null,
        onUserCode = onUserCode,
        provider = IdentityProvider.GITHUB,
        deviceAuthorizationEndpoint = issuer.trimEnd('/') + GitHubOAuth.DEVICE_CODE_PATH,
        tokenEndpoint = issuer.trimEnd('/') + GitHubOAuth.TOKEN_PATH,
        scope = GitHubOAuth.SCOPE,
        credentialField = ACCESS_TOKEN,
    )

    /** The flow; the token endpoint's [credentialField] is the credential's token. */
    private suspend fun deviceFlow(
        clientId: String,
        clientSecret: String?,
        onUserCode: (DeviceUserCode) -> Unit,
        provider: String,
        deviceAuthorizationEndpoint: String,
        tokenEndpoint: String,
        scope: String,
        credentialField: String,
    ): ProviderCredential {
        val (status, grant) = OAuth.postForm(
            http,
            deviceAuthorizationEndpoint,
            parameters {
                append("client_id", clientId)
                append("scope", scope)
            },
        )
        val deviceCode = grant.string("device_code")
        val userCode = grant.string("user_code")
        val verificationUri = grant.string("verification_uri") ?: grant.string("verification_url")
        if (status !in 200..299 || deviceCode == null || userCode == null || verificationUri == null) {
            Log.i(TAG) { "Device authorization failed: HTTP $status ${grant.string("error").orEmpty()}" }
            throw AccountException(if (status >= 500) AccountError.ProviderUnavailable else AccountError.SignInRejected)
        }
        val expiresAt = clock() + (grant.long("expires_in") ?: DEFAULT_EXPIRY_SECONDS) * 1000
        var interval = (grant.long("interval") ?: DEFAULT_INTERVAL_SECONDS).coerceAtLeast(1) * 1000
        onUserCode(
            DeviceUserCode(
                userCode,
                verificationUri,
                grant.string("verification_uri_complete") ?: grant.string("verification_url_complete"),
                expiresAt,
            ),
        )
        while (true) {
            delay(interval)
            if (clock() >= expiresAt) throw AccountException(AccountError.SignInExpired)
            val (tokenStatus, token) = try {
                OAuth.postForm(
                    http,
                    tokenEndpoint,
                    parameters {
                        append("grant_type", TokenAnswers.DEVICE_CODE_GRANT)
                        append("device_code", deviceCode)
                        append("client_id", clientId)
                        clientSecret?.let { append("client_secret", it) }
                    },
                )
            } catch (e: AccountException) {
                // Unreachable for a moment: keep polling until the code expires.
                if (e.error == AccountError.ProviderUnavailable) continue
                throw e
            }
            token.string(credentialField)?.takeIf { tokenStatus in 200..299 }?.let { return ProviderCredential(provider, it, nonce = null) }
            when (token.string("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += SLOW_DOWN_MILLIS
                "access_denied" -> throw AccountException(AccountError.SignInCancelled)
                "expired_token" -> throw AccountException(AccountError.SignInExpired)
                else -> {
                    Log.i(TAG) { "Device token polling failed: HTTP $tokenStatus ${token.string("error").orEmpty()}" }
                    if (tokenStatus >= 500) continue
                    throw AccountException(AccountError.SignInRejected)
                }
            }
        }
    }

    private companion object {
        const val TAG = "DeviceCodeSignIn"
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val DEFAULT_EXPIRY_SECONDS = 1800L
        const val ID_TOKEN = "id_token"
        const val ACCESS_TOKEN = "access_token"

        /** RFC 8628 §3.5: `slow_down` adds five seconds to the interval. */
        const val SLOW_DOWN_MILLIS = 5_000L
    }
}
