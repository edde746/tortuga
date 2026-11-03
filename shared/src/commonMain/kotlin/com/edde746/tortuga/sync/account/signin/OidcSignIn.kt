package com.edde746.tortuga.sync.account.signin

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ProviderCredential
import com.edde746.tortuga.sync.account.SignInAttempt
import com.edde746.tortuga.sync.account.signin.OAuth.string
import com.edde746.tortuga.sync.api.IdentityProvider
import io.ktor.client.HttpClient
import io.ktor.http.parameters
import kotlin.coroutines.cancellation.CancellationException

/**
 * Browser sign-in with the OAuth 2.0 authorization-code flow and PKCE (RFC 7636, S256) against an OpenID Connect
 * issuer: the self-hoster's OIDC provider, and Google on desktop. The platform opens the browser
 * ([BrowserLauncher]) and receives the redirect ([RedirectReceiver]: a loopback port on desktop and Android).
 *
 * The request carries the attempt's server nonce, a random `state` (the receiver takes only the redirect that carries
 * it), and the PKCE challenge; the code is exchanged at the issuer's token endpoint for the ID token, which
 * [com.edde746.tortuga.sync.account.SyncAccount.completeSignIn] sends to the Tortuga server.
 */
class OidcSignIn(
    private val http: HttpClient,
    private val browser: BrowserLauncher,
    private val openReceiver: () -> RedirectReceiver,
) {
    /**
     * Runs the flow for [provider] ([IdentityProvider.OIDC] or [IdentityProvider.GOOGLE]) as the server of [attempt]
     * lists it.
     *
     * @param clientId the OAuth client, as [com.edde746.tortuga.sync.account.signin.ProviderFlows] picks it: one of
     *   the server's client IDs for [IdentityProvider.OIDC], Tortuga's desktop client for Google.
     * @param clientSecret for clients that have one despite being installed apps (Google's desktop clients); PKCE
     *   protects the exchange either way.
     * @throws AccountException [AccountError.ProviderNotEnabled], [AccountError.ProviderUnavailable],
     *   [AccountError.SignInCancelled] (the user declined), [AccountError.SignInExpired] (no redirect within
     *   [timeoutMillis]), [AccountError.SignInRejected] (another error from the provider, a refused code, no ID token).
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun signIn(
        attempt: SignInAttempt,
        clientId: String,
        provider: String = IdentityProvider.OIDC,
        clientSecret: String? = null,
        scope: String = "openid email profile",
        timeoutMillis: Long = 10 * 60_000,
    ): ProviderCredential {
        val info = attempt.provider(provider) ?: throw AccountException(AccountError.ProviderNotEnabled)
        val endpoints = OAuth.discover(http, info.issuer)
        val verifier = OAuth.randomToken()
        val state = OAuth.randomToken(16)
        openReceiver().use { receiver ->
            val url = OAuth.url(
                endpoints.authorizationEndpoint,
                listOf(
                    "response_type" to "code",
                    "client_id" to clientId,
                    "redirect_uri" to receiver.redirectUri,
                    "scope" to scope,
                    "state" to state,
                    "nonce" to attempt.nonce,
                    "code_challenge" to OAuth.sha256Base64Url(verifier),
                    "code_challenge_method" to "S256",
                ),
            )
            browser.open(url)
            val redirect = OAuth.awaitRedirect(receiver, state, timeoutMillis)
            redirect["error"]?.let { error ->
                Log.i(TAG) { "The provider returned $error" }
                throw AccountException(if (error == "access_denied") AccountError.SignInCancelled else AccountError.SignInRejected)
            }
            val code = redirect["code"] ?: throw AccountException(AccountError.SignInRejected)
            val (status, body) = OAuth.postForm(
                http,
                endpoints.tokenEndpoint,
                parameters {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", receiver.redirectUri)
                    append("client_id", clientId)
                    append("code_verifier", verifier)
                    clientSecret?.let { append("client_secret", it) }
                },
            )
            val idToken = body.string("id_token")
            if (status !in 200..299 || idToken == null) {
                Log.i(TAG) { "Code exchange failed: HTTP $status ${body.string("error").orEmpty()}" }
                throw AccountException(if (status >= 500) AccountError.ProviderUnavailable else AccountError.SignInRejected)
            }
            return ProviderCredential(provider, idToken, attempt.nonce)
        }
    }

    private companion object {
        const val TAG = "OidcSignIn"
    }
}
