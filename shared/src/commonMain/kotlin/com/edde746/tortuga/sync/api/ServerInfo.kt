package com.edde746.tortuga.sync.api

import kotlinx.serialization.Serializable

/**
 * [SyncPaths.INFO]: what a server supports. The app fetches it when the user enters a server URL (self-hosted
 * instances) and offers only the listed sign-in providers.
 *
 * @property protocolVersion the server's [SyncProtocol.VERSION]; a client refuses a server older than it needs.
 * @property providers the enabled sign-in providers.
 * @property appleTokenRevocation whether this server exchanges Sign in with Apple authorization codes and revokes the
 *   tokens on account deletion (the hosted instance). If false, account deletion tells the user to remove Tortuga in
 *   their Apple ID settings instead (Apple TN3194).
 * @property appleWebReturn whether this server serves the Sign in with Apple web-flow return page
 *   ([SyncPaths.APPLE_RETURN]) and [SyncPaths.APPLE_HANDOFF].
 * @property accountQuotaBytes most bytes one account's records may take on the server: each record's payload, its id
 *   sealed, and a fixed amount for its row.
 */
@Serializable
data class ServerInfo(
    val protocolVersion: Int,
    val providers: List<ProviderInfo>,
    val appleTokenRevocation: Boolean,
    val appleWebReturn: Boolean,
    val accountQuotaBytes: Long,
)

/**
 * A sign-in provider the server accepts: ID tokens from Apple, Google and the OIDC provider, GitHub's OAuth access
 * tokens from [IdentityProvider.GITHUB].
 *
 * @property id one of [IdentityProvider].
 * @property issuer the OpenID Connect issuer (`https://appleid.apple.com`, `https://accounts.google.com`, or the
 *   self-hoster's own). For [IdentityProvider.GITHUB], GitHub's web address (`https://github.com`, or a GitHub
 *   Enterprise Server's): the app opens `<issuer>/login/oauth/authorize` and runs the device flow at
 *   `<issuer>/login/device/code` and `<issuer>/login/oauth/access_token`.
 * @property clientIds OAuth client IDs whose ID tokens the server accepts (the token's `aud`). For [IdentityProvider.OIDC]
 *   the app runs the authorization-code flow with PKCE against [issuer] with the first of these as a public client. For
 *   [IdentityProvider.GITHUB], the one client ID of the server's GitHub OAuth App.
 * @property displayName the button label for [IdentityProvider.OIDC] ("Authentik", "Keycloak", …); null for Apple,
 *   Google and GitHub, whose labels the app owns.
 */
@Serializable
data class ProviderInfo(
    val id: String,
    val issuer: String,
    val clientIds: List<String>,
    val displayName: String? = null,
)

/** Values of [ProviderInfo.id], [SignInRequest.provider] and [IdentityInfo.provider]. */
object IdentityProvider {
    const val APPLE: String = "apple"
    const val GOOGLE: String = "google"

    /** The self-hoster's own OpenID Connect provider. */
    const val OIDC: String = "oidc"

    /**
     * GitHub, through the server's GitHub OAuth App: listed only where the server has its client ID and secret. GitHub
     * issues no ID token: the app signs in with a GitHub OAuth access token ([SignInRequest.idToken]), from the web flow
     * through the server's return page ([HandoffStartRequest]) or, on TVs, from GitHub's device flow.
     */
    const val GITHUB: String = "github"
}
