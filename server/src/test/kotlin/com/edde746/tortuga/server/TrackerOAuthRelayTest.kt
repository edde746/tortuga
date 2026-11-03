package com.edde746.tortuga.server

import com.edde746.tortuga.server.routes.trackerOAuthRoutes
import com.edde746.tortuga.server.service.TrackerOAuthRelay
import com.edde746.tortuga.server.support.TestClock
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.captureLogs
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.testDataKey
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.trackers.relay.OAuthRelayApi
import com.edde746.tortuga.trackers.relay.OAuthRelayError
import com.edde746.tortuga.trackers.relay.OAuthRelayPaths
import com.edde746.tortuga.trackers.relay.OAuthRelayResult
import com.edde746.tortuga.trackers.relay.OAuthRelayResultRequest
import com.edde746.tortuga.trackers.relay.OAuthRelayStartRequest
import com.edde746.tortuga.trackers.relay.OAuthRelayStartResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The tracker OAuth relay against fake MyAnimeList and AniList token endpoints, which answer as their documentation
 * describes:
 * - MyAnimeList: https://myanimelist.net/apiconfig/references/authorization (authorization code with PKCE; the token
 *   endpoint takes `client_id`, `client_secret` where the app type has one, `grant_type=authorization_code`, `code`,
 *   `redirect_uri` and `code_verifier`, and answers `token_type`, `expires_in`, `access_token`, `refresh_token`).
 * - AniList: https://docs.anilist.co/guide/auth/authorization-code (`grant_type`, `client_id`, `client_secret`,
 *   `redirect_uri`, `code`; same answer, a year-long token).
 *
 * No database: the relay keeps its sessions in memory, so these run on the server's plugins and the relay's routes
 * alone.
 */
class TrackerOAuthRelayTest {
    @Test
    fun myAnimeListSignsInWithPlainPkce() = relayTest {
        val start = start(OAuthRelayApi.MYANIMELIST)
        assertEquals(10 * 60 * 1000L, start.expiresInMillis)
        assertTrue(start.url.startsWith("https://tortuga.test/v1/trackers/oauth/mal?state="), start.url)

        val authorize = authorize(start.url)
        assertEquals("mal.test", authorize.host)
        assertEquals("/v1/oauth2/authorize", authorize.encodedPath)
        val query = authorize.parameters
        assertEquals("code", query["response_type"])
        assertEquals("mal-client", query["client_id"])
        assertEquals("https://tortuga.test/v1/trackers/oauth/mal/callback", query["redirect_uri"])
        assertEquals("plain", query["code_challenge_method"])
        val challenge = assertNotNull(query["code_challenge"])
        assertTrue(Regex("^[A-Za-z0-9._~-]{43,128}$").matches(challenge), challenge)
        val state = assertNotNull(query["state"])
        assertFalse(state in start.session, "the URL on screen must not carry the poll secret")

        val page = callback("mal", state = state, code = "mal-code-1")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("Connect MyAnimeList to Tortuga on “$DEVICE”?" in page.bodyAsText())

        val exchange = provider.exchanges.single()
        assertEquals("mal", exchange.service)
        assertEquals("authorization_code", exchange.form["grant_type"])
        assertEquals("mal-code-1", exchange.form["code"])
        assertEquals("mal-client", exchange.form["client_id"])
        assertEquals("https://tortuga.test/v1/trackers/oauth/mal/callback", exchange.form["redirect_uri"])
        assertEquals(challenge, exchange.form["code_verifier"], "plain PKCE: the verifier is the challenge")
        assertNull(exchange.form["client_secret"], "no secret configured for MyAnimeList")

        assertTrue("Signed in" in confirm("mal", page).bodyAsText())
        val result = poll(start.session).ok<OAuthRelayResult>()
        assertEquals("mal-access-fixture", result.accessToken)
        assertEquals("mal-refresh-fixture", result.refreshToken)
        assertEquals(2_678_400L, result.expiresInSeconds)
        assertNull(result.error)

        // Delivered once; the session is gone with it.
        poll(start.session).assertError(HttpStatusCode.Gone, ApiErrorCode.NOT_FOUND)
    }

    @Test
    fun aniListSignsInWithTheClientSecret() = relayTest {
        val start = start(OAuthRelayApi.ANILIST)
        val authorize = authorize(start.url)
        assertEquals("anilist.test", authorize.host)
        assertEquals("anilist-client", authorize.parameters["client_id"])
        assertEquals("https://tortuga.test/v1/trackers/oauth/anilist/callback", authorize.parameters["redirect_uri"])
        assertNull(authorize.parameters["code_challenge"])

        confirm("anilist", callback("anilist", state = authorize.parameters["state"], code = "anilist-code-1"))
        val form = provider.exchanges.single().form
        assertEquals("anilist-secret-fixture", form["client_secret"])
        assertEquals("anilist-code-1", form["code"])
        assertNull(form["code_verifier"])

        val result = poll(start.session).ok<OAuthRelayResult>()
        assertEquals("anilist-access-fixture", result.accessToken)
        assertEquals(31_536_000L, result.expiresInSeconds)
    }

    @Test
    fun aPollWaitsForTheBrowserAndTimesOutWithNoContent() = relayTest(resultWait = 5.seconds) {
        val start = start(OAuthRelayApi.MYANIMELIST)
        val state = authorize(start.url).parameters["state"]
        val result = coroutineScope {
            val waiting = async { poll(start.session) }
            confirm("mal", callback("mal", state = state, code = "mal-code-1"))
            waiting.await()
        }
        assertEquals("mal-access-fixture", result.ok<OAuthRelayResult>().accessToken)
    }

    @Test
    fun aPollWithNothingYetAnswersNoContent() = relayTest {
        val start = start(OAuthRelayApi.ANILIST)
        assertEquals(HttpStatusCode.NoContent, poll(start.session).status)
        assertEquals(HttpStatusCode.NoContent, poll(start.session).status)
        poll("tto_not-a-session").assertError(HttpStatusCode.Gone, ApiErrorCode.NOT_FOUND)
    }

    @Test
    fun servicesWithoutARegistrationAreNotConfigured() = relayTest(mal = false) {
        postJson(OAuthRelayPaths.START, OAuthRelayStartRequest(OAuthRelayApi.MYANIMELIST))
            .assertError(HttpStatusCode.ServiceUnavailable, OAuthRelayApi.NOT_CONFIGURED)
        postJson(OAuthRelayPaths.START, OAuthRelayStartRequest("kitsu"))
            .assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        assertEquals(HttpStatusCode.Gone, client.get(OAuthRelayPaths.authorize("mal") + "?state=anything").status)
        // AniList still works on the same server.
        start(OAuthRelayApi.ANILIST)
    }

    @Test
    fun aServerWithoutRegistrationsReportsNotConfigured() = relayTest(mal = false, aniList = false) {
        for (service in listOf(OAuthRelayApi.MYANIMELIST, OAuthRelayApi.ANILIST)) {
            postJson(OAuthRelayPaths.START, OAuthRelayStartRequest(service))
                .assertError(HttpStatusCode.ServiceUnavailable, OAuthRelayApi.NOT_CONFIGURED)
        }
    }

    @Test
    fun theCallbackIsSingleUseAndBoundToItsService() = relayTest {
        val mal = start(OAuthRelayApi.MYANIMELIST)
        val state = authorize(mal.url).parameters["state"]

        // Unknown state, or another service's: nothing reaches a provider.
        assertEquals(HttpStatusCode.Gone, callback("mal", state = "forged", code = "c").status)
        assertEquals(HttpStatusCode.Gone, callback("anilist", state = state, code = "c").status)
        assertEquals(HttpStatusCode.Gone, callback("mal", state = null, code = "c").status)
        assertTrue(provider.exchanges.isEmpty())

        val page = callback("mal", state = state, code = "mal-code-1")
        assertEquals(HttpStatusCode.OK, page.status)
        // A replay (or a second tab) never reaches the provider again, and the start URL is spent too.
        assertEquals(HttpStatusCode.Gone, callback("mal", state = state, code = "mal-code-2").status)
        assertEquals(HttpStatusCode.Gone, client.get(mal.url.removePrefix("https://tortuga.test")).status)
        assertEquals(1, provider.exchanges.size)
        assertEquals(HttpStatusCode.NoContent, poll(mal.session).status)
        confirm("mal", page)
        assertEquals("mal-access-fixture", poll(mal.session).ok<OAuthRelayResult>().accessToken)
    }

    @Test
    fun declinedAndFailedSignInsReportTheirError() = relayTest {
        suspend fun outcome(service: String, code: String?, error: String?): Pair<HttpStatusCode, String?> {
            val start = start(service)
            val state = authorize(start.url).parameters["state"]
            val page = callback(service, state = state, code = code, error = error)
            return page.status to poll(start.session).ok<OAuthRelayResult>().also { assertNull(it.accessToken) }.error
        }

        assertEquals(HttpStatusCode.OK to OAuthRelayError.ACCESS_DENIED, outcome("mal", code = null, error = "access_denied"))
        assertEquals(HttpStatusCode.OK to OAuthRelayError.AUTHORIZATION_FAILED, outcome("anilist", code = null, error = "server_error"))
        assertEquals(HttpStatusCode.BadRequest to OAuthRelayError.MISSING_CODE, outcome("mal", code = null, error = null))
        // The provider refuses the code (MyAnimeList: 400 invalid_grant).
        assertEquals(HttpStatusCode.BadGateway to OAuthRelayError.EXCHANGE_FAILED, outcome("mal", code = "expired-code", error = null))
        // The provider answers 200 without a token.
        assertEquals(HttpStatusCode.BadGateway to OAuthRelayError.EXCHANGE_FAILED, outcome("anilist", code = "empty-answer", error = null))
    }

    @Test
    fun aSignInFinishedOnAnotherDeviceWaitsForItsConfirmation() = relayTest {
        // Device A starts and shows the link (a TV's QR code, or a link A sends someone); B's browser opens it
        // and authorizes. A's poll secret gets nothing until B confirms on a page that names A.
        val start = start(OAuthRelayApi.ANILIST, device = "Living room TV")
        val state = authorize(start.url).parameters["state"]
        val page = callback("anilist", state = state, code = "anilist-code-1")
        assertEquals(HttpStatusCode.OK, page.status)
        val text = page.bodyAsText()
        assertTrue("Tortuga on “Living room TV” asked to connect your AniList account." in text, text)
        assertFalse("anilist-access-fixture" in text)
        assertEquals(HttpStatusCode.NoContent, poll(start.session).status)

        // Only the confirmation the page carries releases the tokens.
        assertEquals(HttpStatusCode.Gone, postConfirm("anilist", state, "forged").status)
        assertEquals(HttpStatusCode.Gone, postConfirm("anilist", state, null).status)
        assertEquals(HttpStatusCode.NoContent, poll(start.session).status)
        assertEquals(HttpStatusCode.OK, confirm("anilist", page).status)
        assertEquals("anilist-access-fixture", poll(start.session).ok<OAuthRelayResult>().accessToken)
        assertEquals(HttpStatusCode.Gone, confirm("anilist", page).status)
    }

    @Test
    fun anUnconfirmedSignInEndsWithoutReleasingItsTokens() = relayTest {
        val start = start(OAuthRelayApi.MYANIMELIST, device = null)
        val page = callback("mal", state = authorize(start.url).parameters["state"], code = "mal-code-1")
        // An app that doesn't say which device it is: the page still says the tokens go elsewhere.
        assertTrue("Tortuga on another device asked to connect your MyAnimeList account." in page.bodyAsText())
        clock.advance(Duration.ofMinutes(10))
        poll(start.session).assertError(HttpStatusCode.Gone, ApiErrorCode.NOT_FOUND)
        assertEquals(HttpStatusCode.Gone, confirm("mal", page).status)
    }

    @Test
    fun theAppsOwnBrowserGoesBackToTheAppWithAProof() = relayTest {
        val start = start(OAuthRelayApi.MYANIMELIST, redirectUri = LOOPBACK)
        val browser = assertNotNull(start.browser)
        assertEquals(browser.state, authorize(browser.url).parameters["state"])
        val back = callback("mal", state = browser.state, code = "mal-code-1")
        assertEquals(HttpStatusCode.SeeOther, back.status)
        val location = Url(assertNotNull(back.headers[HttpHeaders.Location]))
        assertEquals(LOOPBACK, location.toString().substringBefore('?'))
        assertEquals(browser.state, location.parameters[SyncPaths.RETURN_STATE])
        val proof = assertNotNull(location.parameters[SyncPaths.RETURN_PROOF])
        assertFalse("mal-access-fixture" in back.bodyAsText())

        // The link may have been opened on someone else's device, whose own receiver got the proof. Without
        // it the poll secret gets nothing, and the other device's confirmation doesn't take it either.
        assertEquals(HttpStatusCode.NoContent, poll(start.session).status)
        poll(start.session, proof = "ttp_forged").assertError(HttpStatusCode.Gone, ApiErrorCode.NOT_FOUND)
        assertEquals(HttpStatusCode.Gone, postConfirm("mal", browser.state, proof).status)
        assertEquals(HttpStatusCode.Gone, postConfirm("mal", Url(start.url).parameters["state"], proof).status)
        // The callback is spent for both browsers.
        assertEquals(HttpStatusCode.Gone, client.get(start.url.removePrefix(PUBLIC_URL)).status)

        assertEquals("mal-access-fixture", poll(start.session, proof).ok<OAuthRelayResult>().accessToken)
        poll(start.session, proof).assertError(HttpStatusCode.Gone, ApiErrorCode.NOT_FOUND)
    }

    @Test
    fun theAppsOwnBrowserTakesADeclineBackToTheApp() = relayTest {
        val start = start(OAuthRelayApi.ANILIST, redirectUri = SyncPaths.HANDOFF_APP_RETURN)
        val browser = assertNotNull(start.browser)
        val back = callback("anilist", state = authorize(browser.url).parameters["state"], code = null, error = "access_denied")
        assertEquals(HttpStatusCode.SeeOther, back.status)
        val location = Url(assertNotNull(back.headers[HttpHeaders.Location]))
        assertEquals("access_denied", location.parameters[SyncPaths.RETURN_ERROR])
        assertEquals(browser.state, location.parameters[SyncPaths.RETURN_STATE])
        assertEquals(OAuthRelayError.ACCESS_DENIED, poll(start.session).ok<OAuthRelayResult>().error)
    }

    @Test
    fun aStartsReturnStaysOnTheDevice() = relayTest {
        for (elsewhere in listOf("https://attacker.test/cb", "http://127.0.0.1.attacker.test:80/cb", "tortuga://signin/x", "$LOOPBACK?next=x")) {
            postJson(OAuthRelayPaths.START, OAuthRelayStartRequest(OAuthRelayApi.MYANIMELIST, DEVICE, elsewhere))
                .assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        }
        postJson(OAuthRelayPaths.START, OAuthRelayStartRequest(OAuthRelayApi.MYANIMELIST, "x".repeat(65)))
            .assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        assertNull(start(OAuthRelayApi.MYANIMELIST).browser)
    }

    @Test
    fun sessionsExpire() = relayTest {
        val start = start(OAuthRelayApi.MYANIMELIST)
        clock.advance(Duration.ofMinutes(10))
        val expired = client.get(start.url.removePrefix("https://tortuga.test"))
        assertEquals(HttpStatusCode.Gone, expired.status)
        // The browser gets the relay's page, not an API error.
        assertEquals(ContentType.Text.Html, expired.contentType()?.withoutParameters())
        assertTrue("Link expired" in expired.bodyAsText())
        poll(start.session).assertError(HttpStatusCode.Gone, ApiErrorCode.NOT_FOUND)
    }

    @Test
    fun theSessionCapTurnsNewSignInsAway() = relayTest(maxSessions = 2) {
        start(OAuthRelayApi.MYANIMELIST)
        start(OAuthRelayApi.ANILIST)
        assertEquals(HttpStatusCode.ServiceUnavailable, postJson(OAuthRelayPaths.START, OAuthRelayStartRequest(OAuthRelayApi.MYANIMELIST)).status)
        // Expired sessions make room again.
        clock.advance(Duration.ofMinutes(11))
        start(OAuthRelayApi.MYANIMELIST)
    }

    @Test
    fun startsAndPollsAreRateLimitedPerAddress() = relayTest(rateLimits = RateLimits(trackerOAuthStart = 2, trackerOAuthResult = 2)) {
        val start = start(OAuthRelayApi.MYANIMELIST)
        start(OAuthRelayApi.MYANIMELIST)
        val limited = postJson(OAuthRelayPaths.START, OAuthRelayStartRequest(OAuthRelayApi.MYANIMELIST))
        assertNotNull(limited.headers[HttpHeaders.RetryAfter])
        limited.assertError(HttpStatusCode.TooManyRequests, ApiErrorCode.RATE_LIMITED)

        poll(start.session)
        poll(start.session)
        poll(start.session).assertError(HttpStatusCode.TooManyRequests, ApiErrorCode.RATE_LIMITED)
    }

    @Test
    fun oneClientHoldsAtMostItsSignInsInProgress() = relayTest(maxSessionsPerAddress = 2) {
        val first = start(OAuthRelayApi.MYANIMELIST, from = "198.51.100.7")
        start(OAuthRelayApi.ANILIST, from = "198.51.100.7")
        startFrom("198.51.100.7").assertError(HttpStatusCode.TooManyRequests, ApiErrorCode.RATE_LIMITED)
        // An IPv6 client counts by its /64.
        start(OAuthRelayApi.MYANIMELIST, from = "2001:db8:5:6::1")
        start(OAuthRelayApi.MYANIMELIST, from = "2001:db8:5:6::2")
        startFrom("2001:db8:5:6::3").assertError(HttpStatusCode.TooManyRequests, ApiErrorCode.RATE_LIMITED)
        start(OAuthRelayApi.MYANIMELIST, from = "198.51.100.8")

        // A delivered result gives its place back, and so does expiry.
        confirm("mal", callback("mal", state = authorize(first.url).parameters["state"], code = "mal-code-1"))
        poll(first.session).ok<OAuthRelayResult>()
        start(OAuthRelayApi.MYANIMELIST, from = "198.51.100.7")
        clock.advance(Duration.ofMinutes(11))
        start(OAuthRelayApi.MYANIMELIST, from = "2001:db8:5:6::3")
        start(OAuthRelayApi.MYANIMELIST, from = "2001:db8:5:6::3")
    }

    @Test
    fun aSessionHoldsAtMostTwoPollsWaiting() = relayTest(resultWait = 1.hours) {
        val start = start(OAuthRelayApi.MYANIMELIST)
        coroutineScope {
            // Undispatched, each runs until it waits for the result.
            val waiting = List(2) { launch(start = CoroutineStart.UNDISPATCHED) { relay.poll(start.session) } }
            assertEquals(TrackerOAuthRelay.Poll.Pending, withTimeout(5.seconds) { relay.poll(start.session) })
            assertTrue(waiting.all { it.isActive })
            waiting.forEach { it.cancel() }
        }
    }

    @Test
    fun noTokenCodeOrSecretReachesTheLogs() {
        val events = captureLogs {
            relayTest(malSecret = "mal-secret-fixture") {
                val ok = start(OAuthRelayApi.MYANIMELIST)
                val okState = authorize(ok.url).parameters["state"]
                val page = callback("mal", state = okState, code = "mal-code-1")
                confirm("mal", page)
                poll(ok.session).ok<OAuthRelayResult>()

                val own = start(OAuthRelayApi.MYANIMELIST, redirectUri = LOOPBACK)
                val back = callback("mal", state = authorize(own.browser!!.url).parameters["state"], code = "mal-code-1")
                val proof = Url(back.headers[HttpHeaders.Location]!!).parameters[SyncPaths.RETURN_PROOF]!!
                poll(own.session, proof).ok<OAuthRelayResult>()

                val refused = start(OAuthRelayApi.ANILIST)
                callback("anilist", state = authorize(refused.url).parameters["state"], code = "expired-code")
                poll(refused.session).ok<OAuthRelayResult>()
                sensitive += listOfNotNull(ok.session, okState, own.session, proof, refused.session, field(page.bodyAsText(), "confirm"))
            }
        }
        assertTrue(events.any { "refused the code exchange" in it }, "the failure is logged: $events")
        val leaks = events.filter { line -> sensitive.any { it in line } }
        assertTrue(leaks.isEmpty(), "sensitive values in logs: $leaks")
    }

    private val sensitive = mutableListOf(
        "mal-access-fixture", "mal-refresh-fixture", "anilist-access-fixture", "anilist-refresh-fixture",
        "mal-code-1", "expired-code", "anilist-secret-fixture", "mal-secret-fixture", "invalid_grant-detail",
    )
}

/** A token request a fake provider received. */
private class Exchange(val service: String, val form: Parameters)

/** The fake MyAnimeList and AniList token endpoints. */
private class FakeProviders {
    val exchanges = CopyOnWriteArrayList<Exchange>()

    fun answer(service: String, form: Parameters): Pair<HttpStatusCode, String> {
        exchanges += Exchange(service, form)
        return when (form["code"]) {
            // MyAnimeList's (and OAuth 2.0's) refusal of a spent or unknown code.
            "expired-code" -> HttpStatusCode.BadRequest to """{"error":"invalid_grant","message":"invalid_grant-detail"}"""
            "empty-answer" -> HttpStatusCode.OK to """{"token_type":"Bearer"}"""
            else -> HttpStatusCode.OK to
                """{"token_type":"Bearer","expires_in":${if (service == "mal") 2678400 else 31536000},""" +
                """"access_token":"$service-access-fixture","refresh_token":"$service-refresh-fixture"}"""
        }
    }
}

private class RelayTestContext(
    val client: HttpClient,
    val clock: TestClock,
    val provider: FakeProviders,
    val relay: TrackerOAuthRelay,
) {
    suspend inline fun <reified B> postJson(path: String, body: B, from: String? = null): HttpResponse = client.post(path) {
        // As the trusted proxy in front would add it.
        from?.let { header(HttpHeaders.XForwardedFor, it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    suspend fun start(service: String, from: String? = null, device: String? = DEVICE, redirectUri: String? = null): OAuthRelayStartResponse =
        postJson(OAuthRelayPaths.START, OAuthRelayStartRequest(service, device, redirectUri), from).ok()

    /** A start from client address [from] (the test client's own when null). */
    suspend fun startFrom(from: String?, service: String = OAuthRelayApi.MYANIMELIST): HttpResponse =
        postJson(OAuthRelayPaths.START, OAuthRelayStartRequest(service, DEVICE), from)

    suspend fun poll(session: String, proof: String? = null): HttpResponse =
        postJson(OAuthRelayPaths.RESULT, OAuthRelayResultRequest(session, proof))

    /** Presses the button of a callback's confirmation [page]. */
    suspend fun confirm(service: String, page: HttpResponse): HttpResponse {
        val html = page.bodyAsText()
        return postConfirm(service, field(html, "state"), field(html, "confirm"))
    }

    /** The confirmation form's post, as a browser sends it. */
    suspend fun postConfirm(service: String, state: String?, confirm: String?): HttpResponse =
        client.submitForm(
            OAuthRelayPaths.callback(service),
            parameters {
                state?.let { append("state", it) }
                confirm?.let { append("confirm", it) }
            },
        )

    /** Opens [url] as the browser would and returns where the relay redirects it. */
    suspend fun authorize(url: String): Url {
        val response = client.get(url.removePrefix(PUBLIC_URL))
        assertEquals(HttpStatusCode.Found, response.status, response.bodyAsText())
        assertEquals("no-store, private", response.headers[HttpHeaders.CacheControl])
        return Url(assertNotNull(response.headers[HttpHeaders.Location]))
    }

    /** The provider sending the browser back. */
    suspend fun callback(service: String, state: String?, code: String?, error: String? = null): HttpResponse =
        client.get(OAuthRelayPaths.callback(service)) {
            url {
                state?.let { parameters.append("state", it) }
                code?.let { parameters.append("code", it) }
                error?.let { parameters.append("error", it) }
            }
        }
}

private const val PUBLIC_URL = "https://tortuga.test"
private const val DEVICE = "Test phone"
private const val LOOPBACK = "http://127.0.0.1:53682/callback"

/** The value of the hidden form field [name] on a confirmation page. */
private fun field(html: String, name: String): String =
    assertNotNull(Regex("""name="$name" value="([^"]*)"""").find(html), html).groupValues[1]

private fun relayTest(
    mal: Boolean = true,
    aniList: Boolean = true,
    malSecret: String? = null,
    resultWait: kotlin.time.Duration = 300.milliseconds,
    maxSessions: Int = 5000,
    maxSessionsPerAddress: Int = 5,
    rateLimits: RateLimits = RateLimits(trackerOAuthStart = 1000, trackerOAuthResult = 1000, signInReturn = 1000),
    block: suspend RelayTestContext.() -> Unit,
) = testApplication {
    val providers = FakeProviders()
    externalServices {
        for ((service, host) in listOf("mal" to "https://mal.test", "anilist" to "https://anilist.test")) {
            hosts(host) {
                routing {
                    post(if (service == "mal") "/v1/oauth2/token" else "/api/v2/oauth/token") {
                        val (status, body) = providers.answer(service, call.receiveParameters())
                        call.respondText(body, ContentType.Application.Json, status)
                    }
                }
            }
        }
    }
    val config = ServerConfig(
        database = DatabaseConfig("unused", "unused", "", 1),
        dataKey = testDataKey,
        apple = null,
        google = null,
        oidc = null,
        trustForwardedHeaders = true,
        rateLimits = rateLimits,
        trackerOAuth = TrackerOAuthConfig(
            publicUrl = PUBLIC_URL,
            myAnimeList = if (!mal) null else OAuthClientConfig(
                "mal-client", malSecret, "https://mal.test/v1/oauth2/authorize", "https://mal.test/v1/oauth2/token",
            ),
            aniList = if (!aniList) null else OAuthClientConfig(
                "anilist-client", "anilist-secret-fixture", "https://anilist.test/api/v2/oauth/authorize", "https://anilist.test/api/v2/oauth/token",
            ),
            resultWait = resultWait,
            maxSessions = maxSessions,
            maxSessionsPerAddress = maxSessionsPerAddress,
        ),
    )
    val clock = TestClock()
    val relay = TrackerOAuthRelay(config.trackerOAuth, createClient { expectSuccess = false }, clock)
    application {
        installServerPlugins(config)
        routing { trackerOAuthRoutes(relay) }
    }
    val client = createClient {
        expectSuccess = false
        followRedirects = false
        install(ContentNegotiation) { json(SyncProtocol.json) }
    }
    RelayTestContext(client, clock, providers, relay).block()
}
