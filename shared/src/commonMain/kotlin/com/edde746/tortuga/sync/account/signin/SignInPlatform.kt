package com.edde746.tortuga.sync.account.signin

import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ProviderCredential
import kotlin.coroutines.cancellation.CancellationException

/** The sync servers the app knows by name. */
object SyncServers {
    /** The hosted instance: the API host under the project's domain, usetortuga.app. */
    const val HOSTED: String = "https://api.usetortuga.app"
}

/** An OAuth client registered for one kind of Tortuga app: its ID, and the secret Google gives installed apps. */
class OAuthClientConfig(val clientId: String, val clientSecret: String?) {
    override fun toString(): String = "OAuthClientConfig(clientId=$clientId)"
}

/**
 * Tortuga's OAuth registrations as this build ships them ([BuildSignInConfig]: the `tortuga.signin.*` Gradle
 * properties or `TORTUGA_*` environment variables, the IDs defaulting to Tortuga's own); a blank value leaves its flow
 * out. Each app's [SignInPlatform] picks the ones its devices use.
 *
 * @param appleBundleId the apps' bundle ID, the audience of the native Sign in with Apple sheet's tokens.
 * @param appleServicesId the Sign in with Apple Services ID, Apple's web flow's `client_id`.
 * @param githubClientId Tortuga's GitHub OAuth App, which the hosted server lists.
 * @param googleServerClientId Google's "Web application" client: the audience Android's native sign-in (Credential
 *   Manager's `serverClientId`) asks Google's ID tokens for.
 * @param googleDesktopClientId Google's "Desktop app" client, for the browser flow with a loopback redirect.
 * @param googleIosClientId Google's "iOS" client, which Google's sheet signs in as on iPhone, iPad and the Mac (the
 *   Info.plist's `GIDClientID`).
 * @param googleTvClientId Google's "TVs and Limited Input devices" client, for the device flow.
 */
class SignInConfig(
    appleBundleId: String,
    appleServicesId: String,
    githubClientId: String,
    googleServerClientId: String,
    googleDesktopClientId: String,
    googleDesktopClientSecret: String,
    googleIosClientId: String,
    googleTvClientId: String,
    googleTvClientSecret: String,
) {
    val appleServicesId: String? = appleServicesId.ifBlank { null }
    val googleServerClientId: String? = googleServerClientId.ifBlank { null }
    val googleDesktopClient: OAuthClientConfig? = installedClient(googleDesktopClientId, googleDesktopClientSecret)
    val googleTvClient: OAuthClientConfig? = installedClient(googleTvClientId, googleTvClientSecret)

    /**
     * Every client ID of Tortuga's registrations, this device's or not: the apps use them with [SyncServers.HOSTED]
     * alone, and with any other server only that server's own ([com.edde746.tortuga.sync.api.NonceResponse]).
     */
    val clientIds: Set<String> = listOf(
        appleBundleId,
        appleServicesId,
        githubClientId,
        googleServerClientId,
        googleDesktopClientId,
        googleIosClientId,
        googleTvClientId,
    ).filterTo(HashSet()) { it.isNotBlank() }

    /** Google's token endpoint wants an installed app's secret too, so a client without one isn't offered. */
    private fun installedClient(id: String, secret: String): OAuthClientConfig? =
        if (id.isBlank() || secret.isBlank()) null else OAuthClientConfig(id, secret)
}

/**
 * What this device can do to sign in, implemented by each app. The sign-in screens offer a provider
 * only through a flow the device supports, and use Tortuga's own clients ([SignInConfig.clientIds]) with the hosted
 * server ([SyncServers.HOSTED]) alone:
 *
 * - Apple: on the hosted server natively ([nativeApple]: iOS, iPadOS, tvOS), else Apple's web flow through the
 *   server's return page with Tortuga's Services ID ([canOpenBrowser], [appleServicesId]: macOS, Android, desktop). On
 *   any other server only that web flow, with the server's own Services ID, where the device has a browser.
 * - Google, on the hosted server only: natively ([nativeGoogle]: Android phones, Apple platforms), else its device flow
 *   on a TV ([isTv], [googleTvClient]), else the browser with PKCE and a loopback redirect ([canOpenBrowser],
 *   [canReceiveRedirect], [googleBrowserClient]: desktop).
 * - GitHub: its web flow through the server's return page where the device has a browser ([canOpenBrowser], not
 *   [isTv]), else GitHub's device flow.
 * - The self-hoster's OpenID Connect provider: the browser with PKCE where the device receives redirects, else the
 *   issuer's device flow.
 *
 * Called on the main thread.
 */
interface SignInPlatform {
    /** A TV: sign-in happens with a code on another device rather than in a browser here. */
    val isTv: Boolean

    /** [nativeSignIn] supports Sign in with Apple. */
    val nativeApple: Boolean

    /** [nativeSignIn] supports Google sign-in. */
    val nativeGoogle: Boolean

    /** [openBrowser] works: the device has a browser the user can finish a sign-in in. */
    val canOpenBrowser: Boolean

    /**
     * A provider's own browser sign-in (OpenID Connect, Google) can return to the app: [openRedirectReceiver]'s
     * loopback port on Android and desktop. The web flows through the server's return page need [canOpenBrowser] only.
     */
    val canReceiveRedirect: Boolean

    /** Tortuga's Google client for the browser flow (the desktop client), or null where it isn't used. */
    val googleBrowserClient: OAuthClientConfig?

    /** Tortuga's Google client for the device flow ("TVs and Limited Input devices"), or null where it isn't used. */
    val googleTvClient: OAuthClientConfig?

    /** Tortuga's Sign in with Apple Services ID, the hosted server's web flow `client_id`; null where it isn't used. */
    val appleServicesId: String?

    /**
     * Runs the platform's own sign-in UI for [provider] ([com.edde746.tortuga.sync.api.IdentityProvider]) with [nonce]
     * passed unchanged, as [com.edde746.tortuga.sync.account.NativeSignIn] describes. A closed sheet throws
     * [AccountException] with [AccountError.SignInCancelled].
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun nativeSignIn(provider: String, nonce: String): ProviderCredential

    /** Opens [url] in the system browser (Apple: in an `ASWebAuthenticationSession`). */
    fun openBrowser(url: String)

    /**
     * A web flow that went through the server's return page (Apple's, GitHub's) ended: signed in, failed or
     * cancelled. Apple cancels the `ASWebAuthenticationSession` [openBrowser] started; Android and desktop have
     * nothing to close.
     */
    fun closeBrowser()

    /**
     * A new receiver for one browser sign-in's return: `LoopbackRedirectReceiver.open()` on Android and desktop; on
     * iPhone, iPad and the Mac the web authentication session's return to
     * [com.edde746.tortuga.sync.api.SyncPaths.HANDOFF_APP_RETURN] (web flows through the server's return page only).
     */
    fun openRedirectReceiver(): RedirectReceiver
}
