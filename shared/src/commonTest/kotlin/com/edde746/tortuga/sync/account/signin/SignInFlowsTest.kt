@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.sync.account.signin

import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.SignInAttempt
import com.edde746.tortuga.sync.account.SyncApi
import com.edde746.tortuga.sync.api.HandoffCollectRequest
import com.edde746.tortuga.sync.api.HandoffStartRequest
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.ProviderInfo
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The browser, device-code, Apple web and GitHub web sign-in flows against scripted provider endpoints, and which of
 * them each device runs with each server's providers ([ProviderFlows]).
 */
class SignInFlowsTest {
    private val issuer = "https://id.example"

    private fun attempt(appleWebReturn: Boolean = false) = SignInAttempt(
        ServerUrl.parse("https://sync.test"),
        ServerInfo(
            1,
            listOf(
                ProviderInfo(IdentityProvider.OIDC, issuer, listOf("tortuga-app"), "Example ID"),
                ProviderInfo(IdentityProvider.APPLE, "https://appleid.apple.com", listOf("com.edde746.tortuga")),
            ),
            appleTokenRevocation = false,
            appleWebReturn = appleWebReturn,
            accountQuotaBytes = 1L shl 26,
        ),
        nonce = "nonce-1",
    )

    /** A server offering GitHub at [githubIssuer] (an Enterprise Server's address: nothing assumes github.com). */
    private val githubIssuer = "https://github.example"

    private val githubAttempt = SignInAttempt(
        ServerUrl.parse("https://sync.test"),
        ServerInfo(
            1,
            listOf(ProviderInfo(IdentityProvider.GITHUB, githubIssuer, listOf("gh-client"))),
            appleTokenRevocation = false,
            appleWebReturn = false,
            accountQuotaBytes = 1L shl 26,
        ),
        nonce = "nonce-1",
    )

    private fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.form(): Map<String, String> {
        val text = (body as? TextContent)?.text ?: (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        return parseQueryString(text).entries().associate { it.key to it.value.single() }
    }

    private val discovery =
        """{"issuer":"$issuer","authorization_endpoint":"$issuer/auth","token_endpoint":"$issuer/token","device_authorization_endpoint":"$issuer/device"}"""

    /**
     * The browser and the app's receiver: opening the authorize URL lands the browser on [forged] (a page that sends it
     * to the receiver with a state of its own) if any, then on what [answer] makes of the URL's parameters (null: it
     * doesn't come back). As a receiver does, the wait takes only the redirect with the awaited state; without one only
     * [close] ends it, since it ignores cancellation as a receiver implemented in Swift does.
     */
    private class ScriptedBrowser(
        private val forged: Map<String, String>? = null,
        private val answer: (Map<String, String>) -> Map<String, String>?,
    ) : BrowserLauncher, RedirectReceiver {
        var opened: Url? = null
        var closed = false
        private var redirects: List<Map<String, String>> = emptyList()
        private val closing = CompletableDeferred<Unit>()
        override val redirectUri = "http://127.0.0.1:53682/callback"
        override fun open(url: String) {
            val parsed = Url(url)
            opened = parsed
            redirects = listOfNotNull(forged, answer(parsed.parameters.entries().associate { it.key to it.value.single() }))
        }
        override suspend fun awaitRedirect(state: String): Map<String, String> {
            redirects.firstOrNull { it[SyncPaths.RETURN_STATE] == state }?.let { return it }
            withContext(NonCancellable) { closing.await() }
            throw AccountException(AccountError.SignInCancelled)
        }
        override fun close() {
            closed = true
            closing.complete(Unit)
        }
    }

    /** What the sync server saw of a web flow: its start and its collect. */
    private class Handoff {
        var start: HandoffStartRequest? = null
        var collect: HandoffCollectRequest? = null
    }

    /**
     * The sync server's side of a web flow ([HandoffStartRequest]), on the test's scheduler: [startPath] issues the
     * state `state-1`, [collectPath] answers [collectBody] with [collectStatus]; [handoff] records both.
     */
    private fun TestScope.handoffServer(
        handoff: Handoff,
        startPath: String,
        collectPath: String,
        collectBody: String,
        collectStatus: HttpStatusCode = HttpStatusCode.OK,
    ) = HttpClient(
        MockEngine.create {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                val body = (request.body as TextContent).text
                when (request.url.encodedPath) {
                    startPath -> {
                        handoff.start = Json.decodeFromString(HandoffStartRequest.serializer(), body)
                        json("""{"state":"state-1","expiresInMillis":600000}""")
                    }
                    collectPath -> {
                        handoff.collect = Json.decodeFromString(HandoffCollectRequest.serializer(), body)
                        json(collectBody, collectStatus)
                    }
                    else -> error("unexpected ${request.url}")
                }
            }
        },
    )

    @Test
    fun theAuthorizationCodeFlowSendsPkceAndReturnsTheIdToken() = runTest {
        var tokenForm: Map<String, String>? = null
        val http = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/.well-known/openid-configuration" -> json(discovery)
                    "/token" -> {
                        tokenForm = request.form()
                        json("""{"access_token":"at","token_type":"Bearer","id_token":"eyJ.id.token"}""")
                    }
                    else -> error("unexpected ${request.url}")
                }
            },
        )
        val browser = ScriptedBrowser { mapOf("code" to "the-code", "state" to it.getValue("state")) }
        val credential = OidcSignIn(http, browser) { browser }.signIn(attempt(), clientId = "tortuga-app")

        assertEquals(IdentityProvider.OIDC, credential.provider)
        assertEquals("eyJ.id.token", credential.idToken)
        assertEquals("nonce-1", credential.nonce)
        val authorize = assertNotNull(browser.opened)
        assertEquals("$issuer/auth", authorize.toString().substringBefore('?'))
        val query = authorize.parameters
        assertEquals("code", query["response_type"])
        assertEquals("tortuga-app", query["client_id"])
        assertEquals("nonce-1", query["nonce"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(browser.redirectUri, query["redirect_uri"])
        val form = assertNotNull(tokenForm)
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        // The verifier sent to the token endpoint is the one the challenge was made from.
        assertEquals(query["code_challenge"], OAuth.sha256Base64Url(form.getValue("code_verifier")))
        assertEquals("tortuga-app", form["client_id"])
        assertNull(form["client_secret"])
    }

    @Test
    fun anotherStatesRedirectIsIgnoredAndADeclinedConsentEndsTheFlow() = runTest {
        var exchanged: String? = null
        val http = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/.well-known/openid-configuration" -> json(discovery)
                    "/token" -> {
                        exchanged = request.form()["code"]
                        json("""{"access_token":"at","id_token":"eyJ.id.token"}""")
                    }
                    else -> error("unexpected ${request.url}")
                }
            },
        )
        val forged = ScriptedBrowser(forged = mapOf("code" to "forged-code", "state" to "someone-else")) {
            mapOf("code" to "the-code", "state" to it.getValue("state"))
        }
        assertEquals("eyJ.id.token", OidcSignIn(http, forged) { forged }.signIn(attempt(), "tortuga-app").idToken)
        assertEquals("the-code", exchanged)

        val declined = ScriptedBrowser { mapOf("error" to "access_denied", "state" to it.getValue("state")) }
        assertEquals(
            AccountError.SignInCancelled,
            assertFailsWith<AccountException> { OidcSignIn(http, declined) { declined }.signIn(attempt(), "tortuga-app") }.error,
        )
        assertEquals(
            AccountError.ProviderNotEnabled,
            assertFailsWith<AccountException> {
                OidcSignIn(http, declined) { declined }.signIn(attempt(), "google-desktop", IdentityProvider.GOOGLE)
            }.error,
        )
    }

    @Test
    fun theDeviceFlowPollsPatientlyAndSlowsDownWhenAsked() = runTest {
        val polls = ArrayList<Long>()
        val answers = ArrayDeque(listOf("authorization_pending", "slow_down", "authorization_pending", "ok"))
        val http = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/device/code" -> json(
                        """{"device_code":"dc","user_code":"GQVQ-JKEC","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}""",
                    )
                    "/token" -> {
                        polls += testScheduler.currentTime
                        assertEquals("urn:ietf:params:oauth:grant-type:device_code", request.form()["grant_type"])
                        assertEquals("tv-secret", request.form()["client_secret"])
                        when (val answer = answers.removeFirst()) {
                            "ok" -> json("""{"access_token":"at","id_token":"eyJ.device.token"}""")
                            else -> json("""{"error":"$answer"}""", HttpStatusCode.BadRequest)
                        }
                    }
                    else -> error("unexpected ${request.url}")
                }
            },
        )
        var shown: DeviceUserCode? = null
        val credential = DeviceCodeSignIn(http) { testScheduler.currentTime }.signIn(
            clientId = "tv-client",
            clientSecret = "tv-secret",
            onUserCode = { shown = it },
            deviceAuthorizationEndpoint = "https://oauth2.example/device/code",
            tokenEndpoint = "https://oauth2.example/token",
        )
        assertEquals("GQVQ-JKEC", shown?.userCode)
        assertEquals("https://www.google.com/device", shown?.verificationUri)
        assertEquals("eyJ.device.token", credential.idToken)
        assertNull(credential.nonce)
        assertEquals(IdentityProvider.GOOGLE, credential.provider)
        // Every 5 s, then 10 s after slow_down.
        assertEquals(listOf(5_000L, 10_000, 20_000, 30_000), polls)
    }

    @Test
    fun aDeclinedDeviceCodeIsCancelled() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/device/code") {
                    json("""{"device_code":"dc","user_code":"X","verification_uri":"https://id.example/device","expires_in":600,"interval":1}""")
                } else {
                    json("""{"error":"access_denied"}""", HttpStatusCode.BadRequest)
                }
            },
        )
        val error = assertFailsWith<AccountException> {
            DeviceCodeSignIn(http).signIn("c", null, {}, IdentityProvider.OIDC, "$issuer/device/code", "$issuer/token")
        }
        assertEquals(AccountError.SignInCancelled, error.error)
    }

    /** The collect carried the issued state, the proof, and the verifier the start's challenge was made from. */
    private fun assertCollectedWithTheVerifier(handoff: Handoff, browser: ScriptedBrowser) {
        val start = assertNotNull(handoff.start)
        assertEquals(browser.redirectUri, start.redirectUri)
        val collect = assertNotNull(handoff.collect)
        assertEquals("state-1", collect.state)
        assertEquals("pr00f", collect.proof)
        assertEquals(start.challenge, OAuth.sha256Base64Url(collect.verifier))
        assertTrue(collect.verifier.length >= 43, "32 random bytes or more")
    }

    @Test
    fun theAppleWebFlowCollectsWhatTheReturnPageSentBackWithTheVerifierBehindTheChallenge() = runTest {
        val handoff = Handoff()
        val http = handoffServer(handoff, SyncPaths.APPLE_START, SyncPaths.APPLE_HANDOFF, """{"idToken":"eyJ.apple.token","authorizationCode":"c0de"}""")
        val browser = ScriptedBrowser { mapOf(SyncPaths.RETURN_STATE to it.getValue("state"), SyncPaths.RETURN_PROOF to "pr00f") }
        val credential = AppleWebSignIn(SyncApi(http), browser) { browser }.signIn(attempt(appleWebReturn = true), servicesId = "own.services")

        assertEquals(IdentityProvider.APPLE, credential.provider)
        assertEquals("eyJ.apple.token", credential.idToken)
        assertEquals("c0de", credential.authorizationCode)
        assertEquals("nonce-1", credential.nonce)
        val url = assertNotNull(browser.opened)
        assertEquals("https://appleid.apple.com/auth/authorize", url.toString().substringBefore('?'))
        assertEquals("own.services", url.parameters["client_id"])
        assertEquals("https://sync.test${SyncPaths.APPLE_RETURN}", url.parameters["redirect_uri"])
        assertEquals("code id_token", url.parameters["response_type"])
        assertEquals("form_post", url.parameters["response_mode"])
        assertEquals("name email", url.parameters["scope"])
        assertEquals("nonce-1", url.parameters["nonce"])
        assertEquals("state-1", url.parameters["state"])
        assertCollectedWithTheVerifier(handoff, browser)
    }

    @Test
    fun theAppleWebFlowNeedsTheServersReturnPage() = runTest {
        val flow = AppleWebSignIn(
            SyncApi(HttpClient(MockEngine { error("no request expected") })),
            { error("no browser expected") },
            { error("no receiver expected") },
        )
        val error = assertFailsWith<AccountException> { flow.signIn(attempt(appleWebReturn = false), "own.services") }
        assertEquals(AccountError.ProviderNotEnabled, error.error)
    }

    @Test
    fun theGitHubWebFlowIgnoresAnotherStatesRedirectAndReturnsTheAccessToken() = runTest {
        val handoff = Handoff()
        val http = handoffServer(handoff, SyncPaths.GITHUB_START, SyncPaths.GITHUB_HANDOFF, """{"accessToken":"gho_web"}""")
        // A page that sends the browser to the receiver with a state of its own comes first.
        val browser = ScriptedBrowser(forged = mapOf(SyncPaths.RETURN_STATE to "someone-else", SyncPaths.RETURN_PROOF to "forged")) {
            mapOf(SyncPaths.RETURN_STATE to it.getValue("state"), SyncPaths.RETURN_PROOF to "pr00f")
        }
        val credential = GitHubWebSignIn(SyncApi(http), browser) { browser }.signIn(githubAttempt, clientId = "gh-client")

        assertEquals(IdentityProvider.GITHUB, credential.provider)
        assertEquals("gho_web", credential.idToken)
        assertNull(credential.nonce)
        assertNull(credential.authorizationCode)
        val url = assertNotNull(browser.opened)
        assertEquals("$githubIssuer/login/oauth/authorize", url.toString().substringBefore('?'))
        assertEquals("gh-client", url.parameters["client_id"])
        assertEquals("https://sync.test${SyncPaths.GITHUB_RETURN}", url.parameters["redirect_uri"])
        assertEquals("user:email", url.parameters["scope"])
        assertEquals("state-1", url.parameters["state"])
        assertCollectedWithTheVerifier(handoff, browser)
    }

    /**
     * Runs Apple's web flow ([apple]) or GitHub's to its failure, the browser coming back with what [back] makes of the
     * issued state (null: it doesn't), a collect answered [collectBody] with [collectStatus]. Returns the error and
     * what the server saw.
     */
    private suspend fun TestScope.failedWebFlow(
        apple: Boolean,
        back: (state: String) -> Map<String, String>?,
        collectStatus: HttpStatusCode = HttpStatusCode.OK,
        collectBody: String = "{}",
        timeoutMillis: Long = 600_000,
    ): Pair<AccountError, Handoff> {
        val handoff = Handoff()
        val (start, collect) = if (apple) SyncPaths.APPLE_START to SyncPaths.APPLE_HANDOFF else SyncPaths.GITHUB_START to SyncPaths.GITHUB_HANDOFF
        val api = SyncApi(handoffServer(handoff, start, collect, collectBody, collectStatus))
        val browser = ScriptedBrowser { back(it.getValue("state")) }
        val error = assertFailsWith<AccountException> {
            if (apple) {
                AppleWebSignIn(api, browser) { browser }.signIn(attempt(appleWebReturn = true), "own.services", timeoutMillis)
            } else {
                GitHubWebSignIn(api, browser) { browser }.signIn(githubAttempt, "gh-client", timeoutMillis)
            }
        }
        return error.error to handoff
    }

    @Test
    fun aDeclinedAuthorizationIsCancelledAndAnotherErrorRejectedWithoutACollect() = runTest {
        val outcomes = listOf(
            Triple(true, "user_cancelled_authorize", AccountError.SignInCancelled),
            Triple(true, "invalid_request", AccountError.SignInRejected),
            Triple(false, "access_denied", AccountError.SignInCancelled),
            Triple(false, "redirect_uri_mismatch", AccountError.SignInRejected),
        )
        for ((apple, error, expected) in outcomes) {
            val (outcome, handoff) = failedWebFlow(apple, { state -> mapOf(SyncPaths.RETURN_STATE to state, SyncPaths.RETURN_ERROR to error) })
            assertEquals(expected, outcome, error)
            assertNull(handoff.collect, error)
        }
    }

    @Test
    fun aCollectTheServerHasNothingForHasExpiredAndGitHubsFailuresSayWhy() = runTest {
        val proof = { state: String -> mapOf(SyncPaths.RETURN_STATE to state, SyncPaths.RETURN_PROOF to "pr00f") }
        val nothing = """{"code":"not_found","message":"Nothing to collect"}"""
        assertEquals(AccountError.SignInExpired, failedWebFlow(apple = true, proof, HttpStatusCode.NotFound, nothing).first)
        assertEquals(AccountError.SignInExpired, failedWebFlow(apple = false, proof, HttpStatusCode.NotFound, nothing).first)
        assertEquals(
            AccountError.SignInRejected,
            failedWebFlow(apple = false, proof, HttpStatusCode.Unauthorized, """{"code":"invalid_id_token","message":"GitHub refused the code"}""").first,
        )
        assertEquals(
            AccountError.ProviderUnavailable,
            failedWebFlow(apple = false, proof, HttpStatusCode.BadGateway, """{"code":"upstream_unavailable","message":"GitHub unreachable"}""").first,
        )
    }

    @Test
    fun aBrowserThatDoesntComeBackExpiresTheFlowAtItsTimeout() = runTest {
        // The receiver ignores cancellation: only its closing at the timeout ends the wait.
        val (error, handoff) = failedWebFlow(apple = false, back = { null }, timeoutMillis = 60_000)
        assertEquals(AccountError.SignInExpired, error)
        assertEquals(60_000L, testScheduler.currentTime)
        assertNull(handoff.collect)
    }

    @Test
    fun cancellingAWebFlowClosesTheReceiverItWaitsOn() = runTest {
        val handoff = Handoff()
        val http = handoffServer(handoff, SyncPaths.GITHUB_START, SyncPaths.GITHUB_HANDOFF, """{"accessToken":"unused"}""")
        val browser = ScriptedBrowser { null }
        val flow = async { GitHubWebSignIn(SyncApi(http), browser) { browser }.signIn(githubAttempt, "gh-client") }
        runCurrent()
        assertNotNull(browser.opened)
        flow.cancel()
        runCurrent()
        // The receiver ignores cancellation: the flow ended because closing the receiver ended the wait.
        assertTrue(flow.isCompleted)
        assertTrue(flow.isCancelled)
        assertTrue(browser.closed)
        assertNull(handoff.collect)
    }

    /** A scripted GitHub device flow: the device code, then [answers] (HTTP 200 each, as GitHub does) one per poll. */
    private fun githubDevice(answers: List<String>, polls: MutableList<Long>, currentTime: () -> Long) = HttpClient(
        MockEngine { request ->
            assertTrue(request.headers[HttpHeaders.Accept].orEmpty().contains("application/json"), "GitHub answers JSON only when asked")
            val form = request.form()
            assertEquals("gh-client", form["client_id"])
            assertNull(form["client_secret"])
            when (request.url.toString()) {
                "$githubIssuer/login/device/code" -> {
                    assertEquals("user:email", form["scope"])
                    json("""{"device_code":"dc","user_code":"WDJB-MJHT","verification_uri":"$githubIssuer/login/device","expires_in":900,"interval":5}""")
                }
                "$githubIssuer/login/oauth/access_token" -> {
                    polls += currentTime()
                    assertEquals("urn:ietf:params:oauth:grant-type:device_code", form["grant_type"])
                    assertEquals("dc", form["device_code"])
                    json(answers[polls.size - 1])
                }
                else -> error("unexpected ${request.url}")
            }
        },
    )

    @Test
    fun theGitHubDeviceFlowUsesTheIssuersEndpointsAndReturnsTheAccessToken() = runTest {
        val polls = ArrayList<Long>()
        val http = githubDevice(
            listOf(
                """{"error":"authorization_pending"}""",
                """{"error":"slow_down","interval":10}""",
                """{"error":"authorization_pending"}""",
                """{"access_token":"gho_device","token_type":"bearer","scope":"user:email"}""",
            ),
            polls,
        ) { testScheduler.currentTime }
        var shown: DeviceUserCode? = null
        val credential = DeviceCodeSignIn(http) { testScheduler.currentTime }.signInWithGitHub(githubIssuer, "gh-client") { shown = it }

        assertEquals("WDJB-MJHT", shown?.userCode)
        assertEquals("$githubIssuer/login/device", shown?.verificationUri)
        assertEquals(IdentityProvider.GITHUB, credential.provider)
        assertEquals("gho_device", credential.idToken)
        assertNull(credential.nonce)
        // Every 5 s, then 10 s after slow_down.
        assertEquals(listOf(5_000L, 10_000, 20_000, 30_000), polls)
    }

    @Test
    fun aDeclinedOrExpiredGitHubDeviceCodeEndsTheFlow() = runTest {
        for ((answer, expected) in listOf("access_denied" to AccountError.SignInCancelled, "expired_token" to AccountError.SignInExpired)) {
            val http = githubDevice(listOf("""{"error":"$answer"}"""), ArrayList()) { testScheduler.currentTime }
            val error = assertFailsWith<AccountException> {
                DeviceCodeSignIn(http) { testScheduler.currentTime }.signInWithGitHub(githubIssuer, "gh-client") {}
            }
            assertEquals(expected, error.error, answer)
        }
    }

    // Which flow runs: ProviderFlows

    private fun flows(platform: TestSignInPlatform) =
        ProviderFlows(noProviderCalls, platform, { 0L }, appleWeb = { error("no Apple web flow") }, githubWeb = { error("no GitHub web flow") })

    /** The options of [info] on [serverUrl], the hosted server by default. */
    private fun options(
        platform: TestSignInPlatform,
        info: ServerInfo = ALL_PROVIDERS,
        serverUrl: String = SyncServers.HOSTED,
    ): List<Pair<String, SignInMethod>> = flows(platform).options(ServerUrl.parse(serverUrl), info, exclude = emptySet()).map { it.provider.id to it.method }

    @Test
    fun eachPlatformGetsTheFlowsPlanFourFiveNamesOnTheHostedServer() {
        // Windows and Linux: all in the browser, Apple and GitHub through the server's return page.
        assertEquals(
            listOf(
                IdentityProvider.APPLE to SignInMethod.APPLE_WEB,
                IdentityProvider.GOOGLE to SignInMethod.BROWSER,
                IdentityProvider.GITHUB to SignInMethod.GITHUB_WEB,
                IdentityProvider.OIDC to SignInMethod.BROWSER,
            ),
            options(TestSignInPlatform.desktop()),
        )
        // tvOS: Apple natively, Google, GitHub and the self-hosted provider by code.
        assertEquals(
            listOf(
                IdentityProvider.APPLE to SignInMethod.NATIVE,
                IdentityProvider.GOOGLE to SignInMethod.DEVICE_CODE,
                IdentityProvider.GITHUB to SignInMethod.DEVICE_CODE,
                IdentityProvider.OIDC to SignInMethod.DEVICE_CODE,
            ),
            options(TestSignInPlatform.appleTv()),
        )
        // Android phone: Google natively, Apple's and GitHub's web flows in a browser tab.
        val phone = TestSignInPlatform(nativeGoogle = true, canOpenBrowser = true, appleServicesId = "com.edde746.tortuga.web")
        assertEquals(
            listOf(
                IdentityProvider.APPLE to SignInMethod.APPLE_WEB,
                IdentityProvider.GOOGLE to SignInMethod.NATIVE,
                IdentityProvider.GITHUB to SignInMethod.GITHUB_WEB,
                IdentityProvider.OIDC to SignInMethod.DEVICE_CODE,
            ),
            options(phone),
        )
        // An Android TV without a browser can't do Apple's web flow; GitHub signs in by code.
        val androidTv = TestSignInPlatform(isTv = true, googleTvClient = OAuthClientConfig("google-tv", "s"))
        assertEquals(
            listOf(IdentityProvider.GOOGLE to SignInMethod.DEVICE_CODE, IdentityProvider.GITHUB to SignInMethod.DEVICE_CODE, IdentityProvider.OIDC to SignInMethod.DEVICE_CODE),
            options(androidTv),
        )
        // A device that isn't a TV but has no browser: GitHub by code all the same.
        assertEquals(
            listOf(IdentityProvider.GITHUB to SignInMethod.DEVICE_CODE, IdentityProvider.OIDC to SignInMethod.DEVICE_CODE),
            options(TestSignInPlatform()),
        )
        // Without the return page, Apple only works natively.
        val noReturn = ALL_PROVIDERS.copy(appleWebReturn = false)
        val flows = flows(TestSignInPlatform.desktop())
        assertEquals(
            listOf(IdentityProvider.GOOGLE, IdentityProvider.GITHUB, IdentityProvider.OIDC),
            flows.options(ServerUrl.HOSTED, noReturn, emptySet()).map { it.provider.id },
        )
    }

    @Test
    fun gitHubIsOfferedOnlyWhereTheServerListsItWithAClientId() {
        val unlisted = ALL_PROVIDERS.copy(providers = ALL_PROVIDERS.providers.filter { it.id != IdentityProvider.GITHUB })
        val noClientId = ALL_PROVIDERS.copy(
            providers = ALL_PROVIDERS.providers.map { if (it.id == IdentityProvider.GITHUB) it.copy(clientIds = emptyList()) else it },
        )
        for (platform in listOf(TestSignInPlatform.desktop(), TestSignInPlatform.appleTv())) {
            assertTrue(IdentityProvider.GITHUB in options(platform).map { it.first })
            assertFalse(IdentityProvider.GITHUB in options(platform, unlisted).map { it.first })
            assertFalse(IdentityProvider.GITHUB in options(platform, noClientId).map { it.first })
        }
    }

    /** A server other than the hosted one: a self-hoster's, or a UI test's fake of the hosted one. */
    private val ownServer = "https://sync.example.com"

    /** [ALL_PROVIDERS] with each provider listing the client IDs [clientIds] gives for it. */
    private fun listing(clientIds: (provider: String) -> List<String>) =
        ALL_PROVIDERS.copy(providers = ALL_PROVIDERS.providers.map { it.copy(clientIds = clientIds(it.id)) })

    /** Every provider listing Tortuga's own clients ([SignInConfig.clientIds]): GitHub's OAuth App, the Services ID, Google's. */
    private val tortugas = listing { BuildSignInConfig.config.clientIds.toList() }

    /** Every provider listing Tortuga's clients and then one of the server's own, `own-<provider>`. */
    private val own = listing { BuildSignInConfig.config.clientIds.toList() + "own-$it" }

    /** iPhone, iPad and the Mac: Apple and Google natively, the web flows in the web authentication session. */
    private val iPhone = TestSignInPlatform(nativeApple = true, nativeGoogle = true, canOpenBrowser = true)

    @Test
    fun tortugasOwnClientsSignInToTheHostedServerAlone() {
        val phone = TestSignInPlatform(nativeGoogle = true, canOpenBrowser = true, appleServicesId = "com.edde746.tortuga.web")
        for (platform in listOf(TestSignInPlatform.desktop(), TestSignInPlatform.appleTv(), phone, iPhone)) {
            assertEquals(emptyList(), options(platform, tortugas, ownServer))
        }
        // With clients of its own: no native sheet, no Google, Apple by its web flow wherever there is a browser.
        assertEquals(
            listOf(
                IdentityProvider.APPLE to SignInMethod.APPLE_WEB,
                IdentityProvider.GITHUB to SignInMethod.GITHUB_WEB,
                IdentityProvider.OIDC to SignInMethod.BROWSER,
            ),
            options(TestSignInPlatform.desktop(), own, ownServer),
        )
        assertEquals(
            listOf(
                IdentityProvider.APPLE to SignInMethod.APPLE_WEB,
                IdentityProvider.GITHUB to SignInMethod.GITHUB_WEB,
                IdentityProvider.OIDC to SignInMethod.DEVICE_CODE,
            ),
            options(iPhone, own, ownServer),
        )
        assertEquals(
            listOf(IdentityProvider.GITHUB to SignInMethod.DEVICE_CODE, IdentityProvider.OIDC to SignInMethod.DEVICE_CODE),
            options(TestSignInPlatform.appleTv(), own, ownServer),
        )
        // The hosted server, however its address is written, keeps the native sheets and Tortuga's clients.
        assertEquals(
            listOf(
                IdentityProvider.APPLE to SignInMethod.NATIVE,
                IdentityProvider.GOOGLE to SignInMethod.NATIVE,
                IdentityProvider.GITHUB to SignInMethod.GITHUB_WEB,
                IdentityProvider.OIDC to SignInMethod.DEVICE_CODE,
            ),
            options(iPhone, tortugas, "https://api.usetortuga.app:443/"),
        )
    }

    @Test
    fun theHostedServersButtonsDontRunAgainstAnotherServerListingTortugasClients() = runTest {
        val attempt = SignInAttempt(ServerUrl.parse(ownServer), tortugas, nonce = "nonce-1")
        for (platform in listOf(TestSignInPlatform.desktop(), TestSignInPlatform.appleTv(), iPhone)) {
            val flows = flows(platform)
            val hostedOptions = flows.options(ServerUrl.HOSTED, tortugas, emptySet())
            assertTrue(hostedOptions.isNotEmpty())
            for (option in hostedOptions) {
                val error = assertFailsWith<AccountException> { flows.obtain(attempt, option.provider.id, option.method) {} }
                assertEquals(AccountError.ProviderNotEnabled, error.error, "${option.provider.id} ${option.method}")
            }
            assertTrue(platform.opened.isEmpty())
        }
    }

    @Test
    fun anotherServersFlowsSignInAsItsOwnClientsAndTheHostedServersAppleWebFlowAsTortugas() = runTest {
        val handoff = handoffHttp { request ->
            when (request.url.encodedPath) {
                SyncPaths.APPLE_HANDOFF -> json("""{"idToken":"eyJ.apple"}""")
                SyncPaths.GITHUB_HANDOFF -> json("""{"accessToken":"gho_own"}""")
                else -> error("unexpected ${request.url}")
            }
        }
        val issuer = mockHttp { request ->
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> json(
                    """{"issuer":"https://id.example","authorization_endpoint":"https://id.example/auth","token_endpoint":"https://id.example/token"}""",
                )
                "/token" -> json("""{"access_token":"at","id_token":"eyJ.oidc"}""")
                else -> error("unexpected ${request.url}")
            }
        }
        val platform = TestSignInPlatform.desktop()
        platform.redirect = { query -> mapOf(SyncPaths.RETURN_STATE to query.getValue("state"), SyncPaths.RETURN_PROOF to "pr00f", "code" to "the-code") }
        val flows = ProviderFlows(
            issuer,
            platform,
            { testScheduler.currentTime },
            appleWeb = { browser -> AppleWebSignIn(SyncApi(handoff), browser, platform::openRedirectReceiver) },
            githubWeb = { browser -> GitHubWebSignIn(SyncApi(handoff), browser, platform::openRedirectReceiver) },
        )
        val elsewhere = SignInAttempt(ServerUrl.parse(ownServer), own, nonce = "nonce-1")
        for (option in flows.options(ServerUrl.parse(ownServer), own, emptySet())) {
            flows.obtain(elsewhere, option.provider.id, option.method) {}
            assertEquals("own-${option.provider.id}", Url(platform.opened.last()).parameters["client_id"], option.provider.id)
        }
        assertEquals(3, platform.opened.size)

        val hosted = SignInAttempt(ServerUrl.HOSTED, own, nonce = "nonce-1")
        val apple = flows.options(ServerUrl.HOSTED, own, emptySet()).single { it.provider.id == IdentityProvider.APPLE }
        assertEquals("eyJ.apple", flows.obtain(hosted, apple.provider.id, apple.method) {}.idToken)
        assertEquals(platform.appleServicesId, Url(platform.opened.last()).parameters["client_id"])
    }
}
