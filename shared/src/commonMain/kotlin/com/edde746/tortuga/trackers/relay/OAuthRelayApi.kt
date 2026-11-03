package com.edde746.tortuga.trackers.relay

import kotlinx.serialization.Serializable

/**
 * The OAuth relay: sign-in for MyAnimeList and AniList, whose token exchange needs a registered
 * redirect URI (and, for AniList, a client secret) that an app on a TV can't hold. The relay is part of the Tortuga
 * server (`server/`); these are the types both sides use, and their KDoc is the
 * specification.
 *
 * **Flow.**
 * 1. The app sends [OAuthRelayStartRequest] to [OAuthRelayPaths.START] with its device's name and, where it can take a
 *    browser's return ([OAuthRelayStartRequest.redirectUri]), where that return goes. It gets a poll secret
 *    ([OAuthRelayStartResponse.session]), a URL to show as a QR code ([OAuthRelayStartResponse.url]) and, for that
 *    return, a URL to open in its own browser ([OAuthRelayStartResponse.browser]).
 * 2. The browser opens a URL: the relay redirects it to the service's authorization page with the relay's client id,
 *    its own callback as `redirect_uri`, the URL's random `state` and (MyAnimeList) a PKCE challenge. The service sends
 *    the browser back to the callback, where the relay exchanges the code for tokens and holds them.
 * 3. The tokens go only to the device that started the sign-in:
 *    - Its own browser ([OAuthRelayStartResponse.browser]'s state): the callback sends the browser on to
 *      [OAuthRelayStartRequest.redirectUri] with that state and a one-time `proof` (as the web sign-ins of
 *      `sync/api`'s `HandoffStartRequest` do), and the app collects with [OAuthRelayResultRequest.proof]. Nothing to
 *      confirm: only a browser on the app's device reaches its loopback receiver or its app return.
 *    - Another device (the QR code's state): the callback page names the device that started the sign-in
 *      ([OAuthRelayStartRequest.device]) and asks the person to confirm with one button (a `POST` to the callback);
 *      the confirmation releases the tokens to the poll secret.
 * 4. Meanwhile the app long-polls [OAuthRelayPaths.RESULT] with [OAuthRelayResultRequest] (no proof) until an
 *    [OAuthRelayResult] arrives: the confirmed tokens, or an error from either browser.
 *
 * **Guarantees.** A session lives [SESSION_TTL_MILLIS] (the start response says how long exactly). The browser states,
 * the poll secret, the proof and the confirmation are independent random values: a URL on screen can't collect the
 * tokens, and a link someone else started ends either at the browser's own device or at a page that names the device
 * the tokens would go to. The callback is single use, the result is delivered once, and the relay keeps no token after
 * delivering it (or ever on disk) and never logs one. Refreshing tokens needs no relay: MyAnimeList refreshes with the
 * public client id, and AniList tokens last a year and aren't refreshed.
 *
 * **Errors.** Non-2xx responses from [OAuthRelayPaths.START] and [OAuthRelayPaths.RESULT] carry the server's
 * `ApiError` (`sync/api`). The relay adds [NOT_CONFIGURED].
 */
object OAuthRelayApi {
    /** How long a session waits for its callback and for the app to collect the result. */
    const val SESSION_TTL_MILLIS: Long = 10 * 60 * 1000

    /** How long [OAuthRelayPaths.RESULT] holds a request before answering 204. Clients time out well after this. */
    const val RESULT_WAIT_MILLIS: Long = 50_000

    /**
     * 503 `ApiError.code` from [OAuthRelayPaths.START]: this server has no client registration for the service (a
     * self-hosted server, or one whose operator hasn't configured it).
     */
    const val NOT_CONFIGURED: String = "not_configured"

    /** Service ids of [OAuthRelayStartRequest.service]. */
    const val MYANIMELIST: String = "mal"
    const val ANILIST: String = "anilist"
}

/** Every relay endpoint, relative to the server's base URL. */
object OAuthRelayPaths {
    /**
     * `POST`, public: [OAuthRelayStartRequest] → [OAuthRelayStartResponse]. 400 for an unknown service, 503
     * [OAuthRelayApi.NOT_CONFIGURED] for one this server can't sign in to, 429 (with `Retry-After`) when one address
     * starts too many.
     */
    const val START: String = "/v1/trackers/oauth/start"

    /**
     * `POST`, public: [OAuthRelayResultRequest] → 200 [OAuthRelayResult] once the tokens are released to it (or an
     * error came back); 204 when nothing arrived within [OAuthRelayApi.RESULT_WAIT_MILLIS] (poll again); 410 when the
     * session is unknown, expired or already collected, or the proof isn't the session's (start again); 429 (with
     * `Retry-After`) when polled too often. A request with a proof answers at once.
     */
    const val RESULT: String = "/v1/trackers/oauth/result"

    /** `GET ?state=…`, the browser's entry: a redirect to the service. The start response's URLs point here. */
    fun authorize(service: String): String = "/v1/trackers/oauth/$service"

    /**
     * `GET ?code=…&state=…` (or `?error=…&state=…`), the browser's return from the service: the `redirect_uri`. A form
     * `POST` here (`state`, `confirm`) is the confirmation its page asks for on another device.
     */
    fun callback(service: String): String = "/v1/trackers/oauth/$service/callback"
}

/**
 * Starts a sign-in. [service] is [OAuthRelayApi.MYANIMELIST] or [OAuthRelayApi.ANILIST].
 *
 * @property device this device's name, which the confirmation page on another device shows (at most
 *   [MAX_DEVICE_LENGTH] characters).
 * @property redirectUri where this device takes its own browser's return, as a web sign-in's
 *   `HandoffStartRequest.redirectUri` (`sync/api`): `http://127.0.0.1:<port><path>` (the loopback receiver of Android
 *   and desktop) or `SyncPaths.HANDOFF_APP_RETURN` (Apple's web authentication session); null on a device without a
 *   browser, which gets no [OAuthRelayStartResponse.browser].
 */
@Serializable
data class OAuthRelayStartRequest(val service: String, val device: String? = null, val redirectUri: String? = null) {
    companion object {
        const val MAX_DEVICE_LENGTH: Int = 64
    }
}

/**
 * A started sign-in.
 *
 * @property session the poll secret for [OAuthRelayResultRequest]. It collects the tokens, so it stays on the device
 *   and out of logs.
 * @property url the page to show as a QR code for another device (a phone): its callback page asks to confirm.
 * @property expiresInMillis the session's remaining lifetime ([OAuthRelayApi.SESSION_TTL_MILLIS]).
 * @property browser this device's own browser, when the start had a [OAuthRelayStartRequest.redirectUri].
 */
@Serializable
class OAuthRelayStartResponse(val session: String, val url: String, val expiresInMillis: Long, val browser: OAuthRelayBrowser? = null) {
    override fun toString(): String = "OAuthRelayStartResponse(url=$url, expiresInMillis=$expiresInMillis)"
}

/**
 * The sign-in in this device's own browser: open [url]; the callback sends the browser on to the start's
 * [OAuthRelayStartRequest.redirectUri] with `state` = [state] and a `proof` (or the service's `error`).
 */
@Serializable
class OAuthRelayBrowser(val url: String, val state: String) {
    override fun toString(): String = "OAuthRelayBrowser"
}

/**
 * Collects a sign-in's result; [session] is [OAuthRelayStartResponse.session], and [proof] the one the browser brought
 * back to [OAuthRelayStartRequest.redirectUri] (null: wait for a confirmation on another device, or an error).
 */
@Serializable
class OAuthRelayResultRequest(val session: String, val proof: String? = null) {
    override fun toString(): String = "OAuthRelayResultRequest"
}

/**
 * A finished sign-in: the service's tokens, or [error] (one of [OAuthRelayError]; tokens are then absent).
 * [expiresInSeconds] is the service's `expires_in` as it sent it.
 */
@Serializable
class OAuthRelayResult(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresInSeconds: Long? = null,
    val error: String? = null,
) {
    override fun toString(): String = "OAuthRelayResult(error=$error, expiresInSeconds=$expiresInSeconds)"
}

/** Values of [OAuthRelayResult.error]. */
object OAuthRelayError {
    /** The user declined on the service's page. Not a failure to report; the app returns to where it was. */
    const val ACCESS_DENIED: String = "access_denied"

    /** The service answered the authorization with another error. */
    const val AUTHORIZATION_FAILED: String = "authorization_failed"

    /** The service came back without a code. */
    const val MISSING_CODE: String = "missing_code"

    /** Exchanging the code failed (the service refused it or couldn't be reached). */
    const val EXCHANGE_FAILED: String = "exchange_failed"
}
