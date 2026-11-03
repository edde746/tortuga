package com.edde746.tortuga.trackers.relay

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.Backoff
import com.edde746.tortuga.sync.api.ApiError
import com.edde746.tortuga.trackers.OAuthRelay
import com.edde746.tortuga.trackers.RelayResult
import com.edde746.tortuga.trackers.RelayStart
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The app's side of the OAuth relay ([OAuthRelayApi]) at [baseUrl], over the app's
 * [http] client, for the device named [device] (the confirmation page on another device shows it).
 *
 * A poll is held by the relay for up to [OAuthRelayApi.RESULT_WAIT_MILLIS], longer than the app client's socket
 * timeout, so result requests carry their own [POLL_TIMEOUT]. A 204 is polled again at once after a held poll, and
 * [MIN_POLL_GAP] after the start of one answered sooner (a proxy that doesn't hold); 429s and 503s with `Retry-After`
 * (a server too busy) wait for `Retry-After` (a 429 without one, [RETRY_DELAY]) and transport failures [RETRY_DELAY] at
 * first, then longer with jitter up to [MAX_RETRY_DELAY], both only while the relay session can still succeed
 * ([OAuthRelayApi.SESSION_TTL_MILLIS] from the start of the poll).
 */
class OAuthRelayClient(
    private val http: HttpClient,
    baseUrl: String,
    private val device: String,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : OAuthRelay {
    private val base = baseUrl.trimEnd('/')

    override suspend fun start(service: TrackerService, redirectUri: String?): RelayStart {
        val request = OAuthRelayStartRequest(relayId(service), device.take(OAuthRelayStartRequest.MAX_DEVICE_LENGTH), redirectUri)
        val response = send(service, OAuthRelayPaths.START, json.encodeToString(OAuthRelayStartRequest.serializer(), request), AUTH_TIMEOUT)
        val text = response.bodyAsText()
        if (response.status == HttpStatusCode.OK) {
            val start = decode(service, text) { json.decodeFromString(OAuthRelayStartResponse.serializer(), it) }
            return RelayStart(start.session, start.url, start.browser?.url, start.browser?.state)
        }
        val code = runCatching { json.decodeFromString(ApiError.serializer(), text).code }.getOrNull()
        throw when {
            response.status == HttpStatusCode.ServiceUnavailable && code == OAuthRelayApi.NOT_CONFIGURED ->
                TrackerException.SignIn(service, "the relay has no registration for it")
            comeBackLater(response) -> TrackerException.RateLimited(service, retryAfterSeconds(response))
            else -> TrackerException.SignIn(service, "relay HTTP ${response.status.value}")
        }
    }

    override suspend fun collect(service: TrackerService, session: String, proof: String): RelayResult {
        val body = json.encodeToString(OAuthRelayResultRequest.serializer(), OAuthRelayResultRequest(session, proof))
        val response = send(service, OAuthRelayPaths.RESULT, body, AUTH_TIMEOUT)
        return when {
            response.status == HttpStatusCode.OK ->
                result(service, response.bodyAsText()) ?: throw TrackerException.SignIn(service, "the relay answered a decline to a proof")
            comeBackLater(response) -> throw TrackerException.RateLimited(service, retryAfterSeconds(response))
            else -> throw TrackerException.SignIn(service, "relay HTTP ${response.status.value} for the proof")
        }
    }

    override suspend fun poll(service: TrackerService, session: String): RelayResult? {
        val deadline = timeSource.markNow() + OAuthRelayApi.SESSION_TTL_MILLIS.milliseconds
        val body = json.encodeToString(OAuthRelayResultRequest.serializer(), OAuthRelayResultRequest(session))
        var failures = 0
        while (true) {
            val sent = timeSource.markNow()
            val response = try {
                send(service, OAuthRelayPaths.RESULT, body, POLL_TIMEOUT)
            } catch (e: TrackerException.Network) {
                if (deadline.hasPassedNow()) throw e
                failures++
                Log.d(TAG) { "Relay poll for ${service.id} failed (${e.cause?.let { it::class.simpleName }}); retrying" }
                delay(Backoff.poll(failures, RETRY_DELAY.inWholeMilliseconds, MAX_RETRY_DELAY.inWholeMilliseconds).milliseconds)
                continue
            }
            failures = 0
            when {
                response.status == HttpStatusCode.NoContent -> delay(MIN_POLL_GAP - sent.elapsedNow())
                response.status == HttpStatusCode.OK -> return result(service, response.bodyAsText())
                response.status == HttpStatusCode.Gone -> throw TrackerException.SignIn(service, "the relay session expired or was already used")
                comeBackLater(response) -> {
                    if (deadline.hasPassedNow()) throw TrackerException.RateLimited(service, retryAfterSeconds(response))
                    delay(retryAfterSeconds(response)?.takeIf { it > 0 }?.seconds ?: RETRY_DELAY)
                }
                else -> throw TrackerException.SignIn(service, "relay HTTP ${response.status.value}")
            }
        }
    }

    private fun result(service: TrackerService, text: String): RelayResult? {
        val result = decode(service, text) { json.decodeFromString(OAuthRelayResult.serializer(), it) }
        val error = result.error
        return when {
            error == OAuthRelayError.ACCESS_DENIED -> null
            error != null -> throw TrackerException.SignIn(
                service,
                when (error) {
                    OAuthRelayError.MISSING_CODE -> "missing authorization code"
                    OAuthRelayError.EXCHANGE_FAILED -> "token exchange failed"
                    else -> "upstream authorization failed"
                },
            )
            result.accessToken.isNullOrEmpty() -> throw TrackerException.SignIn(service, "the relay answered without a token")
            else -> RelayResult(result.accessToken, result.refreshToken, result.expiresInSeconds)
        }
    }

    private suspend fun send(service: TrackerService, path: String, body: String, timeout: Duration): HttpResponse = try {
        http.post(base + path) {
            setBody(TextContent(body, ContentType.Application.Json))
            timeout {
                requestTimeoutMillis = timeout.inWholeMilliseconds
                socketTimeoutMillis = timeout.inWholeMilliseconds
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw TrackerException.Network(service, e)
    }

    private inline fun <T> decode(service: TrackerService, text: String, parse: (String) -> T): T = try {
        parse(text)
    } catch (e: SerializationException) {
        throw TrackerException.SignIn(service, "unreadable relay answer")
    } catch (e: IllegalArgumentException) {
        throw TrackerException.SignIn(service, "unreadable relay answer")
    }

    private fun relayId(service: TrackerService): String = when (service) {
        TrackerService.MYANIMELIST -> OAuthRelayApi.MYANIMELIST
        TrackerService.ANILIST -> OAuthRelayApi.ANILIST
        else -> throw TrackerException.SignIn(service, "not a relay service")
    }

    private fun retryAfterSeconds(response: HttpResponse): Long? = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()

    /** A 429, or a 503 with `Retry-After`: the server asks to come back later. */
    private fun comeBackLater(response: HttpResponse): Boolean =
        response.status == HttpStatusCode.TooManyRequests ||
            (response.status == HttpStatusCode.ServiceUnavailable && retryAfterSeconds(response) != null)

    companion object {
        /** Start requests. */
        val AUTH_TIMEOUT: Duration = 15.seconds

        /** A result request: the relay's hold plus margin. */
        val POLL_TIMEOUT: Duration = 65.seconds

        /** Between polls after a transport failure or a 429 without `Retry-After`. */
        val RETRY_DELAY: Duration = 2.seconds

        /** The longest wait after transport failures in a row. */
        val MAX_RETRY_DELAY: Duration = 30.seconds

        /** The least time between the starts of two polls, whatever answers them. */
        val MIN_POLL_GAP: Duration = 2.seconds

        private const val TAG = "OAuthRelay"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
