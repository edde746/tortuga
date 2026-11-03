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
 * Sign in with Apple's web flow, through the server's return page ([HandoffStartRequest] documents the
 * protocol): on Android, Windows and Linux with Tortuga's Services ID against the hosted server, and on any device with
 * a browser against another server that lists a Services ID of its own (iPhone, iPad and the Mac included).
 *
 * 1. A random verifier; [SyncPaths.APPLE_START] issues the `state` for its challenge and the receiver's
 *    [RedirectReceiver.redirectUri].
 * 2. The browser opens Apple's authorize URL with that `state`, `redirect_uri` = the server's [SyncPaths.APPLE_RETURN],
 *    `response_type=code id_token`, `response_mode=form_post`, `scope=name email` and the attempt's nonce.
 * 3. Apple posts the result to the return page, which sends the browser back to the receiver with the `state` and a
 *    one-time `proof`; the app collects the result once at [SyncPaths.APPLE_HANDOFF] with the verifier and the proof.
 *
 * Needs a server with [com.edde746.tortuga.sync.api.ServerInfo.appleWebReturn].
 */
class AppleWebSignIn internal constructor(
    private val api: SyncApi,
    private val browser: BrowserLauncher,
    private val openReceiver: () -> RedirectReceiver,
) {
    /**
     * Runs the flow on [attempt]'s server.
     *
     * @param servicesId the Sign in with Apple Services ID, the web flow's `client_id`.
     * @throws AccountException [AccountError.ProviderNotEnabled] (no Apple, or no web return on this server),
     *   [AccountError.SignInCancelled] (declined at Apple), [AccountError.SignInRejected] (another error from Apple, or
     *   a return without a proof), [AccountError.SignInExpired] (no return within [timeoutMillis], or nothing to
     *   collect: the server keeps a result ten minutes).
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun signIn(attempt: SignInAttempt, servicesId: String, timeoutMillis: Long = 10 * 60_000): ProviderCredential {
        val apple = attempt.provider(IdentityProvider.APPLE)
        if (apple == null || !attempt.serverInfo.appleWebReturn) throw AccountException(AccountError.ProviderNotEnabled)
        val verifier = OAuth.randomToken()
        openReceiver().use { receiver ->
            val state = api.appleStart(attempt.serverUrl.value, HandoffStartRequest(OAuth.sha256Base64Url(verifier), receiver.redirectUri)).state
            browser.open(
                OAuth.url(
                    apple.issuer.trimEnd('/') + "/auth/authorize",
                    listOf(
                        "client_id" to servicesId,
                        "redirect_uri" to attempt.serverUrl.value + SyncPaths.APPLE_RETURN,
                        "response_type" to "code id_token",
                        "response_mode" to "form_post",
                        "scope" to "name email",
                        "state" to state,
                        "nonce" to attempt.nonce,
                    ),
                ),
            )
            val redirect = OAuth.awaitRedirect(receiver, state, timeoutMillis)
            redirect[SyncPaths.RETURN_ERROR]?.let { error ->
                Log.i(TAG) { "Apple returned $error" }
                throw AccountException(if (error == "user_cancelled_authorize") AccountError.SignInCancelled else AccountError.SignInRejected)
            }
            val proof = redirect[SyncPaths.RETURN_PROOF] ?: throw AccountException(AccountError.SignInRejected)
            val handoff = api.appleHandoff(attempt.serverUrl.value, HandoffCollectRequest(state, verifier, proof))
            return ProviderCredential(IdentityProvider.APPLE, handoff.idToken, attempt.nonce, handoff.authorizationCode)
        }
    }

    private companion object {
        const val TAG = "AppleWebSignIn"
    }
}
