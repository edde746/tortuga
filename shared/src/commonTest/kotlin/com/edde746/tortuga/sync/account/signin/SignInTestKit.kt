package com.edde746.tortuga.sync.account.signin

import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ProviderCredential
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.ProviderInfo
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext

/**
 * Apple, Google, GitHub and a self-hosted provider, with the Apple web return page: what the hosted instance offers
 * (GitHub once its OAuth App is configured there).
 */
internal val ALL_PROVIDERS = ServerInfo(
    SyncProtocol.VERSION,
    listOf(
        ProviderInfo(IdentityProvider.GOOGLE, "https://accounts.google.com", listOf("google-android", "google-desktop", "google-tv")),
        ProviderInfo(IdentityProvider.OIDC, "https://id.example", listOf("tortuga-app"), "Authentik"),
        ProviderInfo(IdentityProvider.GITHUB, GITHUB_ISSUER, listOf("tortuga-github")),
        ProviderInfo(IdentityProvider.APPLE, "https://appleid.apple.com", listOf("com.edde746.tortuga", "com.edde746.tortuga.web")),
    ),
    appleTokenRevocation = true,
    appleWebReturn = true,
    accountQuotaBytes = 1L shl 26,
)

/** The GitHub [ALL_PROVIDERS] lists: a GitHub Enterprise Server's address, so nothing assumes github.com. */
internal const val GITHUB_ISSUER = "https://github.example"

/** A [SignInPlatform] whose capabilities a test sets; the fake server takes the native "ID token" as the subject. */
internal class TestSignInPlatform(
    override val isTv: Boolean = false,
    override val nativeApple: Boolean = false,
    override val nativeGoogle: Boolean = false,
    override val canOpenBrowser: Boolean = false,
    override val canReceiveRedirect: Boolean = false,
    override val googleBrowserClient: OAuthClientConfig? = null,
    override val googleTvClient: OAuthClientConfig? = null,
    override val appleServicesId: String? = null,
) : SignInPlatform {
    var native: suspend (provider: String, nonce: String) -> ProviderCredential = { provider, nonce -> ProviderCredential(provider, "alice", nonce) }
    val opened = ArrayList<String>()

    /** How many times a web flow closed the browser ([closeBrowser]). */
    var browserClosed = 0

    /**
     * What the browser comes back to the receiver with, from the authorization URL's parameters; null: it doesn't come
     * back.
     */
    var redirect: (Map<String, String>) -> Map<String, String>? = { null }
    private var answer: Map<String, String>? = null

    override suspend fun nativeSignIn(provider: String, nonce: String): ProviderCredential = native(provider, nonce)

    override fun openBrowser(url: String) {
        opened += url
        answer = redirect(Url(url).parameters.entries().associate { it.key to it.value.single() })
    }

    override fun closeBrowser() {
        browserClosed++
    }

    /**
     * As a receiver does, only the redirect with the awaited state ends the wait; without one only
     * [RedirectReceiver.close] does, since the wait ignores cancellation as a receiver implemented in Swift does.
     */
    override fun openRedirectReceiver(): RedirectReceiver = object : RedirectReceiver {
        private val closed = CompletableDeferred<Unit>()
        override val redirectUri = "http://127.0.0.1:53682/callback"
        override suspend fun awaitRedirect(state: String): Map<String, String> {
            answer?.takeIf { it[SyncPaths.RETURN_STATE] == state }?.let { return it }
            withContext(NonCancellable) { closed.await() }
            throw cancelled()
        }
        override fun close() {
            closed.complete(Unit)
        }
    }

    companion object {
        /** Windows and Linux: everything in the browser. */
        fun desktop() = TestSignInPlatform(
            canOpenBrowser = true,
            canReceiveRedirect = true,
            googleBrowserClient = OAuthClientConfig("google-desktop", "desktop-secret"),
            appleServicesId = "com.edde746.tortuga.web",
        )

        /** Apple TV: Sign in with Apple natively, Google by its device flow. */
        fun appleTv() = TestSignInPlatform(isTv = true, nativeApple = true, googleTvClient = OAuthClientConfig("google-tv", "tv-secret"))
    }
}

internal fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))

/** Provider endpoints nobody should call. */
internal val noProviderCalls = HttpClient(MockEngine { request -> error("unexpected ${request.url}") })

/** Scripted provider endpoints answering on the test's scheduler, so their calls run in virtual time. */
internal fun TestScope.mockHttp(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
    HttpClient(
        MockEngine.create {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
        },
    )

/**
 * The sync server's web hand-off ([com.edde746.tortuga.sync.api.HandoffStartRequest]) on the test's scheduler: a start
 * issues the state `state-1`, a collect gets [collect]'s answer.
 */
internal fun TestScope.handoffHttp(collect: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
    mockHttp { request ->
        when (request.url.encodedPath) {
            SyncPaths.APPLE_START, SyncPaths.GITHUB_START -> json("""{"state":"state-1","expiresInMillis":600000}""")
            else -> collect(request)
        }
    }

/** A browser that comes back from the authorize page with a web hand-off's proof. */
internal val handoffProof: (Map<String, String>) -> Map<String, String> = { query ->
    mapOf(SyncPaths.RETURN_STATE to query.getValue("state"), SyncPaths.RETURN_PROOF to "pr00f")
}

/** What a sign-in the user closed throws. */
internal fun cancelled() = AccountException(AccountError.SignInCancelled)
