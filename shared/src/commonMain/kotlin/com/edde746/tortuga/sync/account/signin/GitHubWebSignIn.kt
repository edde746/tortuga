package com.edde746.tortuga.sync.account.signin

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ProviderCredential
import com.edde746.tortuga.sync.account.SignInAttempt
import com.edde746.tortuga.sync.account.SyncApi
import com.edde746.tortuga.sync.api.HandoffCollectRequest
import com.edde746.tortuga.sync.api.HandoffStartRequest
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.SyncPaths
import kotlin.coroutines.cancellation.CancellationException

/**
 * GitHub's web flow on phones, tablets and computers (TVs and devices without a browser use GitHub's device flow,
 * [DeviceCodeSignIn.signInWithGitHub]), through the server's return page ([HandoffStartRequest] documents the
 * protocol), as [AppleWebSignIn] does for Apple:
 *
 * 1. A random verifier; [SyncPaths.GITHUB_START] issues the `state` for its challenge and the receiver's
 *    [RedirectReceiver.redirectUri].
 * 2. The browser opens `<issuer>/login/oauth/authorize` with the client ID, that `state`, `redirect_uri` = the server's
 *    [SyncPaths.GITHUB_RETURN] and `scope=user:email`.
 * 3. GitHub sends the browser to the return page, which keeps the code and sends the browser back to the receiver with
 *    the `state` and a one-time `proof`; the app collects once at [SyncPaths.GITHUB_HANDOFF] with the verifier and the
 *    proof, and the server hands over the access token it exchanged the code for.
 *
 * GitHub issues no ID token: the credential carries the access token, without a nonce.
 */
class GitHubWebSignIn internal constructor(
    private val api: SyncApi,
    private val browser: BrowserLauncher,
    private val openReceiver: () -> RedirectReceiver,
) {
    /**
     * Runs the flow on [attempt]'s server.
     *
     * @param clientId the GitHub OAuth App's client ID, one of the server's GitHub client IDs.
     * @throws AccountException [AccountError.ProviderNotEnabled] (no GitHub on this server),
     *   [AccountError.SignInCancelled] (declined at GitHub), [AccountError.SignInRejected] (another error from GitHub,
     *   a return without a proof, or GitHub refused the code), [AccountError.ProviderUnavailable] (the server couldn't
     *   reach GitHub), [AccountError.SignInExpired] (no return within [timeoutMillis], or nothing to collect: the
     *   server keeps a result ten minutes).
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun signIn(attempt: SignInAttempt, clientId: String, timeoutMillis: Long = 10 * 60_000): ProviderCredential {
        val github = attempt.provider(IdentityProvider.GITHUB) ?: throw AccountException(AccountError.ProviderNotEnabled)
        val verifier = OAuth.randomToken()
        openReceiver().use { receiver ->
            val state = api.githubStart(attempt.serverUrl.value, HandoffStartRequest(OAuth.sha256Base64Url(verifier), receiver.redirectUri)).state
            browser.open(
                OAuth.url(
                    github.issuer.trimEnd('/') + GitHubOAuth.AUTHORIZE_PATH,
                    listOf(
                        "client_id" to clientId,
                        "redirect_uri" to attempt.serverUrl.value + SyncPaths.GITHUB_RETURN,
                        "scope" to GitHubOAuth.SCOPE,
                        "state" to state,
                        "allow_signup" to "true",
                    ),
                ),
            )
            val redirect = OAuth.awaitRedirect(receiver, state, timeoutMillis)
            redirect[SyncPaths.RETURN_ERROR]?.let { error ->
                Log.i(TAG) { "GitHub returned $error" }
                throw AccountException(if (error == "access_denied") AccountError.SignInCancelled else AccountError.SignInRejected)
            }
            val proof = redirect[SyncPaths.RETURN_PROOF] ?: throw AccountException(AccountError.SignInRejected)
            val handoff = api.githubHandoff(attempt.serverUrl.value, HandoffCollectRequest(state, verifier, proof))
            return ProviderCredential(IdentityProvider.GITHUB, handoff.accessToken, nonce = null)
        }
    }

    private companion object {
        const val TAG = "GitHubWebSignIn"
    }
}
