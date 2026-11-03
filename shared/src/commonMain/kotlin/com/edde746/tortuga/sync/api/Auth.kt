package com.edde746.tortuga.sync.api

import kotlinx.serialization.Serializable

/**
 * [SyncPaths.AUTH_NONCE]: a single-use nonce for the next sign-in. The client passes [nonce] unchanged to the
 * provider's sign-in request (Sign in with Apple's `nonce`, Google's `nonce`, the OIDC `nonce` parameter) so the ID
 * token carries it, then sends it back in [SignInRequest.nonce]. Apple's tokens must carry one; others without a
 * `nonce` claim (a device flow's, as Google's on TVs) skip this.
 *
 * The nonce isn't bound to the server that issued it: the apps run a flow with one of Tortuga's own OAuth clients only
 * against the hosted server, and against any other server only with that server's own clients, whose tokens the hosted
 * server doesn't accept.
 *
 * @property expiresInMillis how long the nonce stays valid from this response (ten minutes).
 */
@Serializable
data class NonceResponse(val nonce: String, val expiresInMillis: Long)

/**
 * The device a session belongs to, shown in the device list.
 *
 * @property name user-visible, at most [SyncProtocol.MAX_DEVICE_NAME_LENGTH] characters ("Living room TV").
 * @property platform one of [DevicePlatform], at most [SyncProtocol.MAX_PLATFORM_LENGTH] characters.
 */
@Serializable
data class DeviceDescriptor(val name: String, val platform: String)

/** Values of [DeviceDescriptor.platform]. The server accepts any short string; these are the ones the apps send. */
object DevicePlatform {
    const val IOS: String = "ios"
    const val IPADOS: String = "ipados"
    const val MACOS: String = "macos"
    const val TVOS: String = "tvos"
    const val ANDROID: String = "android"
    const val ANDROID_TV: String = "android_tv"
    const val WINDOWS: String = "windows"
    const val LINUX: String = "linux"
}

/**
 * [SyncPaths.AUTH_SIGN_IN]: signs in with a provider's ID token, creating the account on first use. The account is
 * keyed by the token's issuer and subject, never by email.
 *
 * The server verifies the token's signature against the provider's published keys (JWKS), the issuer, the audience
 * (one of [ProviderInfo.clientIds]) and expiry, and accepts each token once.
 *
 * GitHub ([IdentityProvider.GITHUB]) issues no ID token: [idToken] carries a GitHub OAuth access token instead, from
 * [GitHubHandoff] or GitHub's device flow. The server resets it at GitHub (`PATCH /applications/{client_id}/token`
 * with its client secret), which refuses a token of any other OAuth app and ends the one the app sent; the identity
 * is GitHub's numeric user id under the issuer [ProviderInfo.issuer], with the primary verified email when the token
 * may read it (scope `user:email`). The server keeps only the reset token, sealed, to delete the grant when the
 * account is deleted or GitHub is unlinked ([AccountDeletionResponse.githubRevocation]).
 *
 * @property provider one of [IdentityProvider].
 * @property nonce the [NonceResponse.nonce] the token was requested with; required when the token has a `nonce`
 *   claim, and consumed by this call. Null for GitHub.
 * @property authorizationCode Sign in with Apple's authorization code, required when
 *   [ServerInfo.appleTokenRevocation] is true: the server exchanges it for Apple's refresh token, which it needs to
 *   revoke the sign-in when the account is deleted (Apple TN3194). Ignored for other providers.
 * @property device this device, for its new session.
 */
@Serializable
data class SignInRequest(
    val provider: String,
    val idToken: String,
    val nonce: String? = null,
    val authorizationCode: String? = null,
    val device: DeviceDescriptor,
)

/**
 * Response to [SignInRequest].
 *
 * @property accountCreated true if this sign-in created the account (it has no data yet: the app goes on to create
 *   its first profile). Otherwise the device gets the account's data from its next pull.
 */
@Serializable
data class SignInResponse(val accountCreated: Boolean, val account: AccountInfo, val session: SessionTokens)

/**
 * One device's session. Keep both tokens in the platform `SecretStore`.
 *
 * @property deviceId this session's id in [DeviceList].
 * @property accessToken sent as `Authorization: Bearer`, valid for [accessExpiresInMillis] from this response (about
 *   an hour).
 * @property refreshToken exchanged at [SyncPaths.AUTH_REFRESH] for a new pair. Every refresh rotates it; the previous
 *   one works once more, within ten minutes, so a refresh whose response was lost can be retried. Any other use of a
 *   spent refresh token (a second retry, a retry after ten minutes, or one after the new token was used) ends the
 *   session: [ApiErrorCode.INVALID_REFRESH_TOKEN]. A session left unused for [refreshExpiresInMillis] ends.
 */
@Serializable
data class SessionTokens(
    val deviceId: String,
    val accessToken: String,
    val accessExpiresInMillis: Long,
    val refreshToken: String,
    val refreshExpiresInMillis: Long,
)

/** [SyncPaths.AUTH_REFRESH]: exchanges [refreshToken] for a new [SessionTokens]. */
@Serializable
data class RefreshRequest(val refreshToken: String)

/**
 * [SyncPaths.APPLE_START] or [SyncPaths.GITHUB_START]: begins a web flow through the server's return page (Sign in with
 * Apple on Android, Windows, Linux and on Apple devices against a server other than the hosted one; GitHub on phones,
 * tablets and computers). The server keeps the browser's result for the device that started the flow alone:
 *
 * 1. The app draws a random verifier (at least 32 bytes, base64url), opens its redirect receiver ([redirectUri]) and
 *    sends [challenge]; the server answers a fresh [HandoffStartResponse.state], valid ten minutes.
 * 2. The app opens the provider's authorize URL with that `state` and `redirect_uri` = the server's
 *    [SyncPaths.APPLE_RETURN] (`response_type=code id_token`, `response_mode=form_post`, `scope=name email` and the
 *    `nonce` from [SyncPaths.AUTH_NONCE], which Apple's token must carry) or [SyncPaths.GITHUB_RETURN]
 *    (`client_id` = the GitHub [ProviderInfo.clientIds]' one, `scope=user:email`).
 * 3. The provider sends the browser to the return page. For a `state` the server issued and that hasn't returned yet,
 *    it keeps the result with a fresh one-time proof and redirects the browser (303) to [redirectUri] with `state`
 *    and `proof`, or with `state` and `error` when the user declined (nothing is kept then). Any other `state` gets a
 *    static error page and writes nothing.
 * 4. The app's receiver takes that redirect, and the app collects the result once with [HandoffCollectRequest].
 *
 * A link someone else started sends the browser back to the device the browser runs on, not to theirs: the proof
 * never reaches whoever holds the verifier, so the result can't be collected by them. The verifier keeps a redirect
 * that another program on the device intercepted worthless.
 *
 * @property challenge unpadded base64url of SHA-256 of the verifier's UTF-8 bytes.
 * @property redirectUri `http://127.0.0.1:<port><path>` (the loopback receiver of Android and desktop) or
 *   [SyncPaths.HANDOFF_APP_RETURN] (Apple's web authentication session); no query or fragment.
 */
@Serializable
data class HandoffStartRequest(val challenge: String, val redirectUri: String)

/** Response to [HandoffStartRequest]: the `state` for the authorize URL, valid for [expiresInMillis]. */
@Serializable
data class HandoffStartResponse(val state: String, val expiresInMillis: Long)

/**
 * [SyncPaths.APPLE_HANDOFF] or [SyncPaths.GITHUB_HANDOFF]: collects a web flow's result ([HandoffStartRequest]) once.
 * The server hands it over only for the [verifier] whose challenge started [state] and the [proof] the return page
 * sent the browser back with, and deletes it; anything else is a 404.
 *
 * For GitHub the server exchanges the kept code at GitHub with its client secret first. A 401
 * ([ApiErrorCode.INVALID_ID_TOKEN]) means GitHub refused the code (expired or used), a 502
 * ([ApiErrorCode.UPSTREAM_UNAVAILABLE]) that GitHub couldn't be reached: either way the code is spent and the sign-in
 * starts again.
 */
@Serializable
data class HandoffCollectRequest(val state: String, val verifier: String, val proof: String)

/**
 * The fields Apple posted to the return page, passed on unverified; the app sends them in a [SignInRequest]
 * (provider [IdentityProvider.APPLE]).
 *
 * @property user the raw `user` JSON Apple sends on the first authorization only (name and email).
 */
@Serializable
data class AppleHandoff(val idToken: String, val authorizationCode: String? = null, val user: String? = null)

/**
 * The GitHub web flow's result.
 *
 * @property accessToken the GitHub OAuth access token, which the app sends as [SignInRequest.idToken] (or
 *   [LinkIdentityRequest.idToken]) with provider [IdentityProvider.GITHUB].
 */
@Serializable
data class GitHubHandoff(val accessToken: String)
