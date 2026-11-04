package com.edde746.tortuga.sync.account.signin

import com.edde746.tortuga.crypto.cryptoProvider
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.readText
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.isLocalHost
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.accept
import io.ktor.client.request.forms.prepareForm
import io.ktor.client.request.prepareGet
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.http.parseUrl
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Opens a URL in the system browser (desktop) or a browser tab; the platform implements it. */
fun interface BrowserLauncher {
    fun open(url: String)
}

/**
 * Where a browser-based flow comes back to the app: a provider's `redirect_uri` (OpenID Connect, Google on desktop) or
 * a web hand-off's return ([com.edde746.tortuga.sync.api.HandoffStartRequest.redirectUri]), and a wait for the browser
 * to arrive there. Android and desktop listen on a loopback port (RFC 8252 §7.3; `LoopbackRedirectReceiver`); Apple's
 * web authentication session catches [com.edde746.tortuga.sync.api.SyncPaths.HANDOFF_APP_RETURN]. Single use; [close]
 * releases it and ends a wait still running with an exception.
 */
interface RedirectReceiver : AutoCloseable {
    val redirectUri: String

    /**
     * Suspends until the browser requests [redirectUri] with `state` = [state], errors included; returns its query
     * parameters. A request with another state, or one whose query doesn't decode, is answered 404 and ignored.
     * Callers wait through [OAuth.awaitRedirect], which ends the wait by [close]: a receiver implemented in Swift
     * doesn't see Kotlin's cancellation.
     */
    @Throws(AccountException::class, CancellationException::class)
    suspend fun awaitRedirect(state: String): Map<String, String>
}

/** The endpoints an OpenID Connect issuer publishes at `/.well-known/openid-configuration`. */
class OidcEndpoints(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    /** RFC 8628, if the issuer supports the device flow. */
    val deviceAuthorizationEndpoint: String?,
)

/** Google's OAuth endpoints for the device flow on TVs (its discovery document doesn't list the device endpoint). */
object GoogleOAuth {
    const val ISSUER: String = "https://accounts.google.com"
    const val DEVICE_AUTHORIZATION_ENDPOINT: String = "https://oauth2.googleapis.com/device/code"
    const val TOKEN_ENDPOINT: String = "https://oauth2.googleapis.com/token"
}

/**
 * GitHub's OAuth endpoints, under the server's GitHub [com.edde746.tortuga.sync.api.ProviderInfo.issuer]
 * (github.com, or a GitHub Enterprise Server), which publishes no discovery document.
 */
internal object GitHubOAuth {
    /** github.com, the issuer when the server names none (it no longer offers GitHub). */
    const val ISSUER: String = "https://github.com"

    /** All Tortuga asks for: the account shows each sign-in's verified email. */
    const val SCOPE: String = "user:email"

    const val AUTHORIZE_PATH: String = "/login/oauth/authorize"
    const val DEVICE_CODE_PATH: String = "/login/device/code"
    const val TOKEN_PATH: String = "/login/oauth/access_token"
}

internal object OAuth {
    private val json = Json { ignoreUnknownKeys = true }
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** Unpadded base64url of [size] random bytes: PKCE and web hand-off verifiers, `state`, device-link secrets. */
    fun randomToken(size: Int = 32): String = base64Url.encode(CryptographyRandom.nextBytes(size))

    /**
     * Unpadded base64url of SHA-256([text]'s UTF-8 bytes): the PKCE S256 challenge, a web hand-off's or a device
     * link's challenge.
     */
    fun sha256Base64Url(text: String): String =
        base64Url.encode(cryptoProvider.get(SHA256).hasher().hashBlocking(text.encodeToByteArray()))

    fun url(base: String, parameters: List<Pair<String, String>>): String =
        base + (if ('?' in base) "&" else "?") + parameters.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }

    /**
     * [receiver]'s redirect for [state] ([RedirectReceiver.awaitRedirect]), or [AccountError.SignInExpired] when none
     * came within [timeoutMillis]. A deadline coroutine beside the wait closes [receiver] once it ends: at the timeout,
     * at the caller's cancellation, or after the redirect came. Closing is what ends a wait on the timeout or a
     * cancellation, since a receiver implemented in Swift doesn't see Kotlin's cancellation.
     */
    suspend fun awaitRedirect(receiver: RedirectReceiver, state: String, timeoutMillis: Long): Map<String, String> = coroutineScope {
        val deadline = launch { delay(timeoutMillis) }
        deadline.invokeOnCompletion { receiver.close() }
        try {
            receiver.awaitRedirect(state)
        } catch (e: Exception) {
            // The caller's cancellation and the timeout close the receiver: report them, not how the wait ended.
            ensureActive()
            if (deadline.isCompleted && !deadline.isCancelled) throw AccountException(AccountError.SignInExpired, e)
            throw e
        } finally {
            deadline.cancel()
        }
    }

    /**
     * The endpoints in [issuer]'s discovery document, which must name [issuer] itself (as OpenID Connect Discovery asks)
     * and endpoints over https (plain http only on this device or its network, as [ServerUrl.parse] allows it).
     * Anything else, no answer within [REQUEST_TIMEOUT_MILLIS] or one longer than [MAX_ANSWER_BYTES] is
     * [AccountError.ProviderUnavailable].
     */
    suspend fun discover(http: HttpClient, issuer: String): OidcEndpoints {
        val document = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
                http.prepareGet(issuer.trimEnd('/') + "/.well-known/openid-configuration") {
                    expectSuccess = false
                    accept(ContentType.Application.Json)
                }.execute { response ->
                    if (!response.status.isSuccess()) throw AccountException(AccountError.ProviderUnavailable)
                    response.readText(MAX_ANSWER_BYTES)?.let { json.parseToJsonElement(it).jsonObject }
                }
            } ?: throw AccountException(AccountError.ProviderUnavailable)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG) { "Discovery failed: ${e::class.simpleName}" }
            throw AccountException(AccountError.ProviderUnavailable, e)
        }
        if (document.string("issuer") != issuer) {
            Log.i(TAG) { "Discovery names another issuer" }
            throw AccountException(AccountError.ProviderUnavailable)
        }
        fun endpoint(name: String): String? = document.string(name)?.also {
            if (!isSecureEndpoint(it)) {
                Log.i(TAG) { "Discovery's $name isn't https" }
                throw AccountException(AccountError.ProviderUnavailable)
            }
        }
        return OidcEndpoints(
            authorizationEndpoint = endpoint("authorization_endpoint") ?: throw AccountException(AccountError.ProviderUnavailable),
            tokenEndpoint = endpoint("token_endpoint") ?: throw AccountException(AccountError.ProviderUnavailable),
            deviceAuthorizationEndpoint = endpoint("device_authorization_endpoint"),
        )
    }

    /** An https URL, or plain http to a host on this device or its network ([isLocalHost]). */
    private fun isSecureEndpoint(url: String): Boolean {
        val parsed = parseUrl(url) ?: return false
        return parsed.host.isNotBlank() && when (parsed.protocol.name) {
            "https" -> true
            "http" -> isLocalHost(parsed.host)
            else -> false
        }
    }

    /**
     * Posts a form to a token endpoint; returns the status and the JSON object answered (empty if none). No answer
     * within [REQUEST_TIMEOUT_MILLIS] is [AccountError.ProviderUnavailable], as is one longer than [MAX_ANSWER_BYTES].
     */
    suspend fun postForm(http: HttpClient, url: String, form: Parameters): Pair<Int, JsonObject> {
        val (status, text) = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
                http.prepareForm(url, form) {
                    expectSuccess = false
                    accept(ContentType.Application.Json)
                }.execute { response -> response.status.value to response.readText(MAX_ANSWER_BYTES) }
            } ?: throw AccountException(AccountError.ProviderUnavailable)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG) { "Token endpoint unreachable: ${e::class.simpleName}" }
            throw AccountException(AccountError.ProviderUnavailable, e)
        }
        if (text == null) throw AccountException(AccountError.ProviderUnavailable)
        val body = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        }
        return status to (body ?: JsonObject(emptyMap()))
    }

    fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.content?.toLongOrNull()

    private const val TAG = "OAuth"

    /** The longest a discovery or token request and its answer take. */
    private const val REQUEST_TIMEOUT_MILLIS: Long = 30_000

    /** Discovery documents and token answers are a few KB. */
    private const val MAX_ANSWER_BYTES: Long = 1024 * 1024
}
