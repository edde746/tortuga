package com.edde746.tortuga.trackers.relay

import com.edde746.tortuga.trackers.RelayResult
import com.edde746.tortuga.trackers.RelayStart
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's relay client against a fake relay speaking [OAuthRelayApi]. */
class OAuthRelayClientTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun TestScope.client(answer: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = OAuthRelayClient(
        HttpClient(MockEngine { request -> requests += request; answer(request) }) { install(HttpTimeout) },
        "https://relay.test/",
        "Living room TV",
        testScheduler.timeSource,
    )

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg headers: Pair<String, String>) =
        respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json"), *headers.map { it.first to listOf(it.second) }.toTypedArray()))

    @Test
    fun startsThenPollsUntilTheBrowserIsBack() = runTest {
        val polls = ArrayDeque(listOf(HttpStatusCode.NoContent, HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable, HttpStatusCode.OK))
        val relay = client { request ->
            when (request.url.encodedPath) {
                OAuthRelayPaths.START -> json("""{"session":"tto_poll","url":"https://relay.test/v1/trackers/oauth/mal?state=s","expiresInMillis":600000}""")
                OAuthRelayPaths.RESULT -> when (polls.removeFirst()) {
                    HttpStatusCode.NoContent -> respond("", HttpStatusCode.NoContent)
                    HttpStatusCode.TooManyRequests -> json("""{"code":"rate_limited","message":"Too many requests"}""", HttpStatusCode.TooManyRequests, HttpHeaders.RetryAfter to "7")
                    HttpStatusCode.ServiceUnavailable -> json("""{"code":"server_busy","message":"Busy"}""", HttpStatusCode.ServiceUnavailable, HttpHeaders.RetryAfter to "5")
                    else -> json("""{"accessToken":"access-fixture","refreshToken":"refresh-fixture","expiresInSeconds":2678400}""")
                }
                else -> error("unexpected ${request.url}")
            }
        }

        val start = relay.start(TrackerService.MYANIMELIST, redirectUri = null)
        assertEquals(RelayStart("tto_poll", "https://relay.test/v1/trackers/oauth/mal?state=s"), start)
        assertEquals("https://relay.test/v1/trackers/oauth/start", requests.single().url.toString())
        assertEquals("""{"service":"mal","device":"Living room TV"}""", (requests.single().body as TextContent).text)

        assertEquals(RelayResult("access-fixture", "refresh-fixture", 2678400), relay.poll(TrackerService.MYANIMELIST, start.session))
        val resultPolls = requests.drop(1)
        assertEquals(4, resultPolls.size)
        assertTrue(resultPolls.all { (it.body as TextContent).text == """{"session":"tto_poll"}""" })
        assertEquals(ContentType.Application.Json, (resultPolls.first().body as TextContent).contentType)
        // The 204 answered at once waited out the least gap between polls; the 429's and the busy 503's Retry-After
        // were honoured.
        assertEquals(OAuthRelayClient.MIN_POLL_GAP.inWholeMilliseconds + 7_000 + 5_000, currentTime)
    }

    @Test
    fun aDeclineIsNullAndOtherEndingsAreSignInFailures() = runTest {
        var answer = """{"error":"access_denied"}"""
        var status = HttpStatusCode.OK
        val relay = client { json(answer, status) }
        assertNull(relay.poll(TrackerService.ANILIST, "tto_x"))

        for (error in listOf("exchange_failed", "missing_code", "authorization_failed", "something_new")) {
            answer = """{"error":"$error"}"""
            assertFailsWith<TrackerException.SignIn> { relay.poll(TrackerService.ANILIST, "tto_x") }
        }
        answer = """{"code":"not_found","message":"No such sign-in, or it ended"}"""
        status = HttpStatusCode.Gone
        assertFailsWith<TrackerException.SignIn> { relay.poll(TrackerService.ANILIST, "tto_x") }
        status = HttpStatusCode.OK
        answer = """{"expiresInSeconds":1}"""
        assertFailsWith<TrackerException.SignIn> { relay.poll(TrackerService.ANILIST, "tto_x") }
    }

    @Test
    fun theOwnBrowsersProofCollectsAtOnce() = runTest {
        var status = HttpStatusCode.OK
        val relay = client { request ->
            when (request.url.encodedPath) {
                OAuthRelayPaths.START -> json(
                    """{"session":"tto_poll","url":"https://relay.test/v1/trackers/oauth/mal?state=qr","expiresInMillis":600000,""" +
                        """"browser":{"url":"https://relay.test/v1/trackers/oauth/mal?state=own","state":"own"}}""",
                )
                else -> json("""{"accessToken":"access-fixture"}""", status)
            }
        }
        val start = relay.start(TrackerService.MYANIMELIST, redirectUri = "http://127.0.0.1:53682/callback")
        assertEquals(RelayStart("tto_poll", "https://relay.test/v1/trackers/oauth/mal?state=qr", "https://relay.test/v1/trackers/oauth/mal?state=own", "own"), start)
        assertEquals(
            """{"service":"mal","device":"Living room TV","redirectUri":"http://127.0.0.1:53682/callback"}""",
            (requests.single().body as TextContent).text,
        )

        assertEquals(RelayResult("access-fixture"), relay.collect(TrackerService.MYANIMELIST, start.session, "ttp_proof"))
        assertEquals("""{"session":"tto_poll","proof":"ttp_proof"}""", (requests.last().body as TextContent).text)
        status = HttpStatusCode.Gone
        assertFailsWith<TrackerException.SignIn> { relay.collect(TrackerService.MYANIMELIST, start.session, "ttp_other") }
    }

    @Test
    fun aServerWithoutTheRegistrationSaysSo() = runTest {
        val relay = client { json("""{"code":"not_configured","message":"No mal client on this server"}""", HttpStatusCode.ServiceUnavailable) }
        val failure = assertFailsWith<TrackerException.SignIn> { relay.start(TrackerService.MYANIMELIST, redirectUri = null) }
        assertTrue("registration" in failure.message.orEmpty(), failure.message)
        assertEquals(false, failure.transient)
        // Services that sign in directly never reach the relay.
        assertFailsWith<TrackerException.SignIn> { relay.start(TrackerService.TRAKT, redirectUri = null) }
        assertEquals(1, requests.size)
    }

    @Test
    fun transportFailuresAreRetriedLongerApartWhileTheSessionCanStillSucceed() = runTest {
        val sentAt = mutableListOf<Long>()
        val relay = client {
            sentAt += currentTime
            throw IOException("relay unreachable")
        }
        val failure = assertFailsWith<TrackerException.Network> { relay.poll(TrackerService.MYANIMELIST, "tto_x") }
        assertTrue(failure.transient)
        assertTrue(currentTime >= OAuthRelayApi.SESSION_TTL_MILLIS, "gave up at $currentTime ms")
        val gaps = sentAt.zipWithNext { a, b -> b - a }
        val least = OAuthRelayClient.RETRY_DELAY.inWholeMilliseconds
        val most = OAuthRelayClient.MAX_RETRY_DELAY.inWholeMilliseconds
        assertTrue(gaps.all { it in least..most }, "gaps ${gaps.min()}..${gaps.max()} outside $least..$most")
        assertTrue(gaps.size < OAuthRelayApi.SESSION_TTL_MILLIS / least / 2, "${gaps.size} retries: the waits grew")
    }

    @Test
    fun aRetryAfterPastTheSessionsLifeWaitsOnlyUntilItEnds() = runTest {
        val relay = client { json("""{"code":"rate_limited","message":"Too many requests"}""", HttpStatusCode.TooManyRequests, HttpHeaders.RetryAfter to "3600") }
        assertFailsWith<TrackerException.RateLimited> { relay.poll(TrackerService.MYANIMELIST, "tto_x") }
        assertEquals(OAuthRelayApi.SESSION_TTL_MILLIS, currentTime, "gave up as the session ended, not an hour on")
        assertEquals(2, requests.size)
    }
}
