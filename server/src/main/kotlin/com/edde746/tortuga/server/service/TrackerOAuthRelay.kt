package com.edde746.tortuga.server.service

import com.edde746.tortuga.server.OAuthClientConfig
import com.edde746.tortuga.server.TrackerOAuthConfig
import com.edde746.tortuga.server.auth.Secrets
import com.edde746.tortuga.server.http.ApiException
import com.edde746.tortuga.server.http.MAX_TOKEN_ANSWER_BYTES
import com.edde746.tortuga.server.http.badRequest
import com.edde746.tortuga.server.http.bodyAsTextAtMost
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.trackers.relay.OAuthRelayApi
import com.edde746.tortuga.trackers.relay.OAuthRelayBrowser
import com.edde746.tortuga.trackers.relay.OAuthRelayError
import com.edde746.tortuga.trackers.relay.OAuthRelayPaths
import com.edde746.tortuga.trackers.relay.OAuthRelayResult
import com.edde746.tortuga.trackers.relay.OAuthRelayStartRequest
import com.edde746.tortuga.trackers.relay.OAuthRelayStartResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.forms.prepareForm
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * The tracker OAuth relay (protocol: `OAuthRelayApi` in shared): sign-in for MyAnimeList (authorization code with
 * PKCE) and AniList (authorization code with the client secret) on devices that can't take a redirect themselves.
 *
 * Sessions live in memory for [TrackerOAuthConfig.sessionTtl]: a restart only cancels sign-ins in progress. Each has
 * independent random keys: the QR code's browser `state`, the app's own browser `state` (when the app can take a
 * browser's return), and the poll secret, of which only the SHA-256 is kept. The callback is claimed before the code
 * exchange, so a replayed or concurrent duplicate never reaches the service.
 *
 * The tokens go only to the device that started the sign-in (a link someone else started must not hand the
 * tokens of the person who opens it to whoever holds the poll secret). The callback holds them and releases them
 * either to the proof it sends the app's own browser back to the app's return with (only a browser on that device
 * reaches it, as with the web sign-ins' hand-offs, [AppleHandoffs]), or, for the QR code's browser on another device,
 * to the poll secret once the person confirms on the callback page, which names the starting device. Results are
 * handed out once and the session dropped with them. Tokens and client secrets are never logged.
 *
 * A client address holds at most [TrackerOAuthConfig.maxSessionsPerAddress] sign-ins in progress, and a session at most
 * [MAX_WAITING_POLLS] polls held waiting, so one client can neither fill [TrackerOAuthConfig.maxSessions] (a memory
 * backstop) nor hold the server's connections with polls.
 */
class TrackerOAuthRelay(private val config: TrackerOAuthConfig, private val http: HttpClient, private val clock: Clock) {
    private val clients: Map<String, OAuthClientConfig> = buildMap {
        config.myAnimeList?.let { put(OAuthRelayApi.MYANIMELIST, it) }
        config.aniList?.let { put(OAuthRelayApi.ANILIST, it) }
    }

    /** The services this server can sign in to. */
    val services: Set<String> get() = clients.keys

    /** Sign-ins in progress. */
    internal val inProgress: Int get() = synchronized(this) { byPollDigest.size }

    /** Sessions by each of their browser states. */
    private val byState = HashMap<String, Session>()
    private val byPollDigest = HashMap<String, Session>()

    /** Sessions in progress by client address; an address with none has no entry. */
    private val byAddress = HashMap<String, Int>()

    /** What a callback or confirmation page tells the browser. */
    enum class CallbackPage { SIGNED_IN, CANCELLED, FAILED, INCOMPLETE, UNAVAILABLE, INVALID }

    /** How the callback answers the browser. */
    sealed interface Callback {
        /** A page saying how it went. */
        class Page(val page: CallbackPage) : Callback

        /**
         * The page asking the person on another device to confirm that [device] (null: an app that didn't say) gets
         * the tokens: its form posts [state] and [confirm] back to the callback.
         */
        class Confirm(val service: String, val state: String, val confirm: String, val device: String?) : Callback

        /** Back to the app on the browser's own device: [location] carries the state and a proof, or an error. */
        class AppReturn(val location: String) : Callback
    }

    /** A result poll's answer. */
    sealed interface Poll {
        class Ready(val result: OAuthRelayResult) : Poll
        data object Pending : Poll
        data object Gone : Poll
    }

    /**
     * Starts a sign-in ([OAuthRelayPaths.START]) for the client at [address] (the call's
     * [com.edde746.tortuga.server.http.clientAddress]).
     */
    fun start(request: OAuthRelayStartRequest, address: String): OAuthRelayStartResponse {
        val service = request.service
        if (service != OAuthRelayApi.MYANIMELIST && service != OAuthRelayApi.ANILIST) throw badRequest("Unknown service")
        val returnUri = request.redirectUri?.also {
            if (!AppleHandoffs.isAppReturn(it)) throw badRequest("redirectUri must be a loopback address or ${SyncPaths.HANDOFF_APP_RETURN}")
        }
        val device = request.device?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > OAuthRelayStartRequest.MAX_DEVICE_LENGTH || it.any(Char::isISOControl)) {
                throw badRequest("device must be at most ${OAuthRelayStartRequest.MAX_DEVICE_LENGTH} printable characters")
            }
        }
        val client = clients[service]
        val publicUrl = config.publicUrl
        if (client == null || publicUrl == null) {
            throw ApiException(HttpStatusCode.ServiceUnavailable, OAuthRelayApi.NOT_CONFIGURED, "No $service client on this server")
        }
        val pollSecret = Secrets.generate(Secrets.TRACKER_OAUTH_POLL_PREFIX)
        val session = Session(
            state = Secrets.generate(prefix = ""),
            pollDigest = Secrets.sha256Base64Url(pollSecret),
            service = service,
            address = address,
            device = device,
            browser = returnUri?.let { Browser(Secrets.generate(prefix = ""), it) },
            codeVerifier = if (service == OAuthRelayApi.MYANIMELIST) pkceVerifier() else null,
            createdAt = clock.instant(),
        )
        synchronized(this) {
            purgeExpired()
            if ((byAddress[address] ?: 0) >= config.maxSessionsPerAddress) {
                throw ApiException(HttpStatusCode.TooManyRequests, ApiErrorCode.RATE_LIMITED, "Too many sign-ins in progress from this address")
            }
            if (byPollDigest.size >= config.maxSessions) {
                throw ApiException(HttpStatusCode.ServiceUnavailable, ApiErrorCode.UPSTREAM_UNAVAILABLE, "Too many sign-ins in progress")
            }
            // 256-bit values don't collide; the check only keeps a broken random source from mixing sessions.
            check(session.states.none { it in byState } && session.pollDigest !in byPollDigest)
            session.states.forEach { byState[it] = session }
            byPollDigest[session.pollDigest] = session
            byAddress.merge(address, 1, Int::plus)
        }
        return OAuthRelayStartResponse(
            session = pollSecret,
            url = entryUrl(publicUrl, service, session.state),
            expiresInMillis = config.sessionTtl.inWholeMilliseconds,
            browser = session.browser?.let { OAuthRelayBrowser(entryUrl(publicUrl, service, it.state), it.state) },
        )
    }

    /** The service's authorization URL for the browser that opened a start URL, or null when [state] is no session's. */
    fun authorizationUrl(service: String, state: String?): String? {
        val client = clients[service] ?: return null
        if (state == null) return null
        val session = synchronized(this) { live(byState[state]) } ?: return null
        if (session.service != service || session.callbackClaimed) return null
        return URLBuilder(client.authorizeUrl).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", client.clientId)
            parameters.append("redirect_uri", redirectUri(service))
            parameters.append("state", state)
            session.codeVerifier?.let {
                // MyAnimeList accepts only `plain`, where the challenge is the verifier itself.
                parameters.append("code_challenge", it)
                parameters.append("code_challenge_method", "plain")
            }
        }.buildString()
    }

    /**
     * The browser's return from [service] ([OAuthRelayPaths.callback]) with its query [code], [state] and [error]:
     * exchanges the code and holds the tokens for the device that started the sign-in, or publishes the error, and
     * says how to answer the browser.
     */
    suspend fun callback(service: String, state: String?, code: String?, error: String?): Callback {
        val invalid = Callback.Page(CallbackPage.INVALID)
        val client = clients[service] ?: return invalid
        val session = synchronized(this) {
            live(byState[state ?: return invalid])?.takeIf { it.service == service && !it.callbackClaimed }
                ?.also { it.callbackClaimed = true }
        } ?: return invalid
        // The app's own browser, or the QR code's on another device.
        val browser = session.browser?.takeIf { it.state == state }

        if (error != null) {
            val denied = error == "access_denied"
            val result = OAuthRelayResult(error = if (denied) OAuthRelayError.ACCESS_DENIED else OAuthRelayError.AUTHORIZATION_FAILED)
            if (!complete(session, result)) return invalid
            return browser?.appReturn(SyncPaths.RETURN_ERROR to AppleHandoffs.providerError(error))
                ?: Callback.Page(if (denied) CallbackPage.CANCELLED else CallbackPage.FAILED)
        }
        if (code.isNullOrEmpty()) {
            if (!complete(session, OAuthRelayResult(error = OAuthRelayError.MISSING_CODE))) return invalid
            return browser?.appReturn(SyncPaths.RETURN_ERROR to "invalid_request") ?: Callback.Page(CallbackPage.INCOMPLETE)
        }
        val tokens = exchange(service, client, session, code)
        if (tokens == null) {
            if (!complete(session, OAuthRelayResult(error = OAuthRelayError.EXCHANGE_FAILED))) return invalid
            return browser?.appReturn(SyncPaths.RETURN_ERROR to "server_error") ?: Callback.Page(CallbackPage.UNAVAILABLE)
        }
        // The proof for the app's own browser, else the confirmation for the page on another device.
        val key = Secrets.generate(if (browser != null) AppleHandoffs.PROOF_PREFIX else "")
        if (!hold(session, Held(tokens, Secrets.sha256Base64Url(key), viaBrowser = browser != null))) return invalid
        return browser?.appReturn(SyncPaths.RETURN_PROOF to key) ?: Callback.Confirm(service, session.state, key, session.device)
    }

    /**
     * The person on another device confirmed ([OAuthRelayPaths.callback]'s form `POST`) with the [confirm] its callback
     * page carried for [state]: the tokens go to the poll secret. [CallbackPage.INVALID] for anything else.
     */
    fun confirm(service: String, state: String?, confirm: String?): CallbackPage {
        if (state == null || confirm == null) return CallbackPage.INVALID
        synchronized(this) {
            val session = live(byState[state]) ?: return CallbackPage.INVALID
            val held = session.held
            if (session.service != service || state != session.state || held == null || held.viaBrowser ||
                held.digest != Secrets.sha256Base64Url(confirm)
            ) {
                return CallbackPage.INVALID
            }
            session.held = null
            session.result = held.tokens
            session.done.complete(Unit)
        }
        return CallbackPage.SIGNED_IN
    }

    /**
     * The result for [pollSecret] ([OAuthRelayPaths.RESULT]). With the [proof] the app's own browser brought back, the
     * held tokens at once, else [Poll.Gone]. Without one, waits up to [TrackerOAuthConfig.resultWait] for a confirmed
     * result or an error; a poll past the session's [MAX_WAITING_POLLS] waiting is answered pending at once. A
     * delivered result ends the session.
     */
    suspend fun poll(pollSecret: String, proof: String? = null): Poll {
        val digest = Secrets.sha256Base64Url(pollSecret)
        if (proof != null) {
            return synchronized(this) {
                val session = live(byPollDigest[digest]) ?: return Poll.Gone
                val held = session.held?.takeIf { it.viaBrowser && it.digest == Secrets.sha256Base64Url(proof) } ?: return Poll.Gone
                remove(session)
                Poll.Ready(held.tokens)
            }
        }
        val session = synchronized(this) {
            live(byPollDigest[digest])?.also {
                if (it.waitingPolls >= MAX_WAITING_POLLS) return Poll.Pending
                it.waitingPolls++
            }
        } ?: return Poll.Gone
        try {
            withTimeoutOrNull(config.resultWait) { session.done.await() } ?: return Poll.Pending
        } finally {
            synchronized(this) { session.waitingPolls-- }
        }
        return synchronized(this) {
            val result = session.result
            if (byPollDigest[digest] !== session || result == null) {
                Poll.Gone
            } else {
                session.result = null
                remove(session)
                Poll.Ready(result)
            }
        }
    }

    private suspend fun exchange(service: String, client: OAuthClientConfig, session: Session, code: String): OAuthRelayResult? {
        val verifier = synchronized(this) { session.codeVerifier }
        return try {
            val body = http.prepareForm(
                url = client.tokenUrl,
                formParameters = parameters {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("client_id", client.clientId)
                    append("redirect_uri", redirectUri(service))
                    client.clientSecret?.let { append("client_secret", it) }
                    verifier?.let { append("code_verifier", it) }
                },
            ) { accept(ContentType.Application.Json) }.execute { response ->
                if (response.status == HttpStatusCode.OK) {
                    response.bodyAsTextAtMost("the $service token endpoint", MAX_TOKEN_ANSWER_BYTES)
                } else {
                    log.warn("Tracker sign-in: {} refused the code exchange (HTTP {})", service, response.status.value)
                    null
                }
            } ?: return null
            val tokens = json.decodeFromString(TokenResponse.serializer(), body)
            val accessToken = tokens.accessToken?.takeIf { it.isNotEmpty() }
            if (accessToken == null) {
                log.warn("Tracker sign-in: {} answered the code exchange without an access token", service)
                return null
            }
            OAuthRelayResult(accessToken = accessToken, refreshToken = tokens.refreshToken, expiresInSeconds = tokens.expiresIn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The type only: messages of client and parser exceptions can quote the response.
            log.warn("Tracker sign-in: {} code exchange failed ({})", service, e::class.java.simpleName)
            null
        }
    }

    /** Publishes [result] (an error) once, while the session is still registered; the verifier isn't needed after this. */
    private fun complete(session: Session, result: OAuthRelayResult): Boolean = synchronized(this) {
        if (byPollDigest[session.pollDigest] !== session || session.done.isCompleted) return@synchronized false
        session.result = result
        session.codeVerifier = null
        session.done.complete(Unit)
    }

    /** Keeps the tokens for their [held] key, while the session is still registered. */
    private fun hold(session: Session, held: Held): Boolean = synchronized(this) {
        if (byPollDigest[session.pollDigest] !== session || session.done.isCompleted) return@synchronized false
        session.held = held
        session.codeVerifier = null
        true
    }

    private fun redirectUri(service: String): String = config.publicUrl + OAuthRelayPaths.callback(service)

    private fun entryUrl(publicUrl: String, service: String, state: String): String =
        URLBuilder(publicUrl + OAuthRelayPaths.authorize(service)).apply { parameters.append("state", state) }.buildString()

    private fun Browser.appReturn(vararg query: Pair<String, String>): Callback =
        Callback.AppReturn(AppleHandoffs.appReturn(returnUri, SyncPaths.RETURN_STATE to state, *query))

    /** [session], unless it has expired (it is then dropped). Callers hold the lock. */
    private fun live(session: Session?): Session? {
        if (session == null) return null
        if (!expired(session, clock.instant())) return session
        remove(session)
        return null
    }

    /**
     * Drops every expired sign-in with what it holds: tokens no device collected, its waiting polls (they answer gone).
     * The janitor runs it every round, so an abandoned sign-in outlives its [TrackerOAuthConfig.sessionTtl] by one
     * round at most, however quiet the relay.
     */
    fun purgeExpired() = synchronized(this) {
        val now = clock.instant()
        byPollDigest.values.filter { expired(it, now) }.forEach(::remove)
    }

    private fun expired(session: Session, now: Instant) = session.createdAt.plusMillis(config.sessionTtl.inWholeMilliseconds) <= now

    private fun remove(session: Session) {
        if (byPollDigest[session.pollDigest] !== session) return
        byPollDigest.remove(session.pollDigest)
        session.states.forEach { if (byState[it] === session) byState.remove(it) }
        byAddress.computeIfPresent(session.address) { _, count -> (count - 1).takeIf { it > 0 } }
        session.held = null
        session.result = null
        session.codeVerifier = null
        session.done.complete(Unit)
    }

    /** The app's own browser: its [state], and the app's return on its device ([OAuthRelayStartRequest.redirectUri]). */
    private class Browser(val state: String, val returnUri: String)

    /** Tokens held for the device that started the sign-in: [digest] is the proof's or the confirmation's SHA-256. */
    private class Held(val tokens: OAuthRelayResult, val digest: String, val viaBrowser: Boolean)

    private class Session(
        /** The QR code's state: its callback asks for a confirmation. */
        val state: String,
        val pollDigest: String,
        val service: String,
        /** The client address that started it. */
        val address: String,
        /** The starting device's name, which the confirmation page shows. */
        val device: String?,
        val browser: Browser?,
        /** MyAnimeList's PKCE verifier; cleared once the tokens are held or an error published. */
        var codeVerifier: String?,
        val createdAt: Instant,
    ) {
        val states: List<String> = listOfNotNull(state, browser?.state)
        var callbackClaimed = false

        /** Released to the poll secret: an error, or the confirmed tokens. */
        var result: OAuthRelayResult? = null

        /** Tokens awaiting the proof or the confirmation. */
        var held: Held? = null
        val done = CompletableDeferred<Unit>()

        /** Polls held waiting for [done]; guarded by the relay. */
        var waitingPolls = 0
    }

    @Serializable
    private class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
    )

    private companion object {
        val log = LoggerFactory.getLogger("com.edde746.tortuga.server.trackers")
        val json = Json { ignoreUnknownKeys = true }
        val random = SecureRandom()

        /**
         * Polls one session may have held waiting at once: the app's one, and its retry while the server hasn't yet seen
         * the first's connection end.
         */
        const val MAX_WAITING_POLLS = 2

        /** RFC 7636 §4.1: 43 to 128 characters of the unreserved set. */
        const val PKCE_VERIFIER_LENGTH = 64
        const val PKCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        fun pkceVerifier(): String = buildString(PKCE_VERIFIER_LENGTH) {
            repeat(PKCE_VERIFIER_LENGTH) { append(PKCE_ALPHABET[random.nextInt(PKCE_ALPHABET.length)]) }
        }
    }
}
