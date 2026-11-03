package com.edde746.tortuga.sync.account.signin

import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ProviderCredential
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.SignInAttempt
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.ProviderInfo
import com.edde746.tortuga.sync.api.ServerInfo
import io.ktor.client.HttpClient

/** How a sign-in button signs in on this device. */
enum class SignInMethod {
    /** The platform's own sign-in UI ([SignInPlatform.nativeSignIn]). */
    NATIVE,

    /** The system browser with PKCE, returning to the app ([SignInPlatform.openRedirectReceiver]). */
    BROWSER,

    /**
     * A code to enter on another device (the OAuth device flow): Google, GitHub or the self-hoster's provider on a TV,
     * GitHub on any device without a browser.
     */
    DEVICE_CODE,

    /** Sign in with Apple's web flow through the server's return page. */
    APPLE_WEB,

    /** GitHub's web flow through the server's return page. */
    GITHUB_WEB,
}

/** What a provider flow is waiting for, for the progress screen. */
internal sealed interface ProviderProgress {
    /** The platform's sheet is up. */
    data class Native(val provider: String) : ProviderProgress

    /** The browser is open; the flow waits for it to come back. */
    data object Browser : ProviderProgress

    /** The device flow's code is ready to show. */
    data class DeviceCode(val code: DeviceUserCode) : ProviderProgress
}

/** A provider of a server this device can sign in with, and how ([ProviderFlows.options]). */
internal data class SignInOption(val provider: ProviderInfo, val method: SignInMethod)

/**
 * Runs the right provider flow for this device: which providers of a server it can offer ([options]),
 * and the flow that gets a provider's ID token (GitHub's access token) for a [SignInAttempt] ([obtain]). Used by
 * sign-in and by linking a second identity.
 *
 * Tortuga's own OAuth clients ([SignInConfig.clientIds]) sign in to the hosted server ([SyncServers.HOSTED]) alone. On
 * any other server: no native sheet (it signs in as Tortuga's apps), no Google (each of its flows here needs one of
 * Tortuga's clients, or a secret no server advertises), Apple only through its web flow, and Apple's web flow, GitHub
 * and OpenID Connect only with the first client the server lists that isn't Tortuga's. [obtain] applies the same rules,
 * so an option from another server's list doesn't run.
 */
internal class ProviderFlows(
    private val http: HttpClient,
    private val platform: SignInPlatform,
    private val clock: () -> Long,
    private val appleWeb: (BrowserLauncher) -> AppleWebSignIn,
    private val githubWeb: (BrowserLauncher) -> GitHubWebSignIn,
) {
    private val browser = BrowserLauncher { url -> platform.openBrowser(url) }

    /**
     * The providers of [info] ([serverUrl]'s) this device can sign in with, each with how, Apple first, without the
     * providers in [exclude].
     */
    fun options(serverUrl: ServerUrl, info: ServerInfo, exclude: Set<String>): List<SignInOption> {
        val hosted = serverUrl == ServerUrl.HOSTED
        return info.providers
            .filter { it.id !in exclude }
            .mapNotNull { provider ->
                method(provider.id, serverClientId(provider, hosted) != null, info.appleWebReturn, hosted)?.let { SignInOption(provider, it) }
            }
            .sortedBy { ORDER.indexOf(it.provider.id).let { index -> if (index < 0) ORDER.size else index } }
    }

    /**
     * How many buttons the hosted server's providers make here: every provider this build has a flow for ([ORDER]) but
     * the self-hoster's OpenID Connect (today Apple with its web return page, Google and GitHub), so a provider added to
     * the flows counts before the hosted server lists it.
     */
    val hostedOptionCount: Int
        get() = ORDER.filter { it != IdentityProvider.OIDC }.count { method(it, serverClient = true, appleWebReturn = true, hosted = true) != null }

    /**
     * Gets [providerId]'s ID token (GitHub's access token) for [attempt] by [method], reporting what it waits for
     * through [onProgress]. Cancelling the calling coroutine abandons the flow. A web flow ends with
     * [SignInPlatform.closeBrowser] however it ends.
     *
     * @throws AccountException [AccountError.ProviderNotEnabled] if the server no longer lists the provider, this
     *   device may not use it there by [method] (the option came from another server's list), or its issuer has no
     *   device flow; and whatever the flow throws.
     */
    suspend fun obtain(attempt: SignInAttempt, providerId: String, method: SignInMethod, onProgress: (ProviderProgress) -> Unit): ProviderCredential {
        val hosted = attempt.serverUrl == ServerUrl.HOSTED
        val provider = attempt.provider(providerId) ?: throw AccountException(AccountError.ProviderNotEnabled)
        val serverClientId = serverClientId(provider, hosted)
        if (method(provider.id, serverClientId != null, attempt.serverInfo.appleWebReturn, hosted) != method) {
            throw AccountException(AccountError.ProviderNotEnabled)
        }
        return when (method) {
            SignInMethod.NATIVE -> {
                onProgress(ProviderProgress.Native(provider.id))
                platform.nativeSignIn(provider.id, attempt.nonce)
            }
            SignInMethod.BROWSER -> {
                val client = if (provider.id == IdentityProvider.GOOGLE) {
                    platform.googleBrowserClient
                } else {
                    serverClientId?.let { OAuthClientConfig(it, clientSecret = null) }
                }
                if (client == null) throw AccountException(AccountError.ProviderNotEnabled)
                onProgress(ProviderProgress.Browser)
                OidcSignIn(http, browser, platform::openRedirectReceiver).signIn(
                    attempt = attempt,
                    clientId = client.clientId,
                    provider = provider.id,
                    clientSecret = client.clientSecret,
                )
            }
            SignInMethod.DEVICE_CODE -> deviceCode(provider, serverClientId, onProgress)
            SignInMethod.APPLE_WEB -> {
                val servicesId = (if (hosted) platform.appleServicesId else serverClientId)
                    ?: throw AccountException(AccountError.ProviderNotEnabled)
                web(onProgress) { appleWeb(browser).signIn(attempt, servicesId) }
            }
            SignInMethod.GITHUB_WEB -> {
                val clientId = serverClientId ?: throw AccountException(AccountError.ProviderNotEnabled)
                web(onProgress) { githubWeb(browser).signIn(attempt, clientId) }
            }
        }
    }

    /** Runs a web flow ([signIn]), closing the browser at its end. */
    private suspend fun web(onProgress: (ProviderProgress) -> Unit, signIn: suspend () -> ProviderCredential): ProviderCredential {
        try {
            onProgress(ProviderProgress.Browser)
            return signIn()
        } finally {
            platform.closeBrowser()
        }
    }

    private suspend fun deviceCode(provider: ProviderInfo, serverClientId: String?, onProgress: (ProviderProgress) -> Unit): ProviderCredential {
        val flow = DeviceCodeSignIn(http, clock)
        val report: (DeviceUserCode) -> Unit = { onProgress(ProviderProgress.DeviceCode(it)) }
        return when (provider.id) {
            IdentityProvider.GOOGLE -> {
                val client = platform.googleTvClient ?: throw AccountException(AccountError.ProviderNotEnabled)
                flow.signIn(clientId = client.clientId, clientSecret = client.clientSecret, onUserCode = report)
            }
            IdentityProvider.GITHUB -> {
                val clientId = serverClientId ?: throw AccountException(AccountError.ProviderNotEnabled)
                flow.signInWithGitHub(provider.issuer, clientId, report)
            }
            else -> {
                val clientId = serverClientId ?: throw AccountException(AccountError.ProviderNotEnabled)
                val endpoints = OAuth.discover(http, provider.issuer)
                val deviceEndpoint = endpoints.deviceAuthorizationEndpoint ?: throw AccountException(AccountError.ProviderNotEnabled)
                flow.signIn(
                    clientId = clientId,
                    clientSecret = null,
                    onUserCode = report,
                    provider = provider.id,
                    deviceAuthorizationEndpoint = deviceEndpoint,
                    tokenEndpoint = endpoints.tokenEndpoint,
                    scope = "openid email profile",
                )
            }
        }
    }

    /**
     * The client of [provider]'s that the server lists for this device: the first; on a server other than the hosted
     * one the first that isn't one of Tortuga's ([SignInConfig.clientIds]), or null. Apple's web flow, GitHub and
     * OpenID Connect sign in as it (Apple's web flow with the hosted server as Tortuga's Services ID instead).
     */
    private fun serverClientId(provider: ProviderInfo, hosted: Boolean): String? =
        if (hosted) provider.clientIds.firstOrNull() else provider.clientIds.firstOrNull { it !in BuildSignInConfig.config.clientIds }


    /**
     * How [provider] signs in on this device, or null. [serverClient]: the server lists a client this device may use
     * ([serverClientId]); [hosted]: the server is the hosted one, the only one Tortuga's clients sign in to.
     */
    private fun method(provider: String, serverClient: Boolean, appleWebReturn: Boolean, hosted: Boolean): SignInMethod? {
        val browserFlow = platform.canOpenBrowser && platform.canReceiveRedirect
        return when (provider) {
            IdentityProvider.APPLE -> when {
                hosted && platform.nativeApple -> SignInMethod.NATIVE
                !appleWebReturn || !platform.canOpenBrowser -> null
                // Tortuga's Services ID with the hosted server, the server's own with any other.
                hosted -> if (platform.appleServicesId != null) SignInMethod.APPLE_WEB else null
                serverClient -> SignInMethod.APPLE_WEB
                else -> null
            }
            IdentityProvider.GOOGLE -> when {
                !hosted -> null
                platform.nativeGoogle -> SignInMethod.NATIVE
                platform.isTv && platform.googleTvClient != null -> SignInMethod.DEVICE_CODE
                browserFlow && platform.googleBrowserClient != null -> SignInMethod.BROWSER
                else -> null
            }
            IdentityProvider.GITHUB -> when {
                !serverClient -> null
                platform.isTv || !platform.canOpenBrowser -> SignInMethod.DEVICE_CODE
                else -> SignInMethod.GITHUB_WEB
            }
            IdentityProvider.OIDC -> when {
                !serverClient -> null
                browserFlow && !platform.isTv -> SignInMethod.BROWSER
                // Whether the issuer has a device flow is known once the button is used.
                else -> SignInMethod.DEVICE_CODE
            }
            // A provider a newer server added: this build has no flow for it.
            else -> null
        }
    }

    private companion object {
        /** Every provider this build has a flow for, in the order of their buttons. */
        val ORDER = listOf(IdentityProvider.APPLE, IdentityProvider.GOOGLE, IdentityProvider.GITHUB, IdentityProvider.OIDC)
    }
}
