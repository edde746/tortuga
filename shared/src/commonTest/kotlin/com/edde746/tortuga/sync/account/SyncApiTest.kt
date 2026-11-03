@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.sync.account

import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.HandoffCollectRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The protocol client's handling of answers the in-memory server doesn't produce. */
class SyncApiTest {
    private fun api(status: HttpStatusCode, body: String, headers: Map<String, String> = emptyMap()): SyncApi {
        val engine = MockEngine {
            respond(body, status, headersOf(*(headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))
        }
        return SyncApi(HttpClient(engine))
    }

    private suspend fun errorOf(status: HttpStatusCode, body: String, headers: Map<String, String> = emptyMap()) =
        assertFailsWith<AccountException> { api(status, body, headers).devices("https://sync.test", "token") }.error

    private fun apiError(code: String) = "{\"code\":\"$code\",\"message\":\"diagnostic\"}"

    @Test
    fun errorCodesMapToAccountErrors() = runTest {
        assertEquals(AccountError.SessionEnded, errorOf(HttpStatusCode.Unauthorized, apiError(ApiErrorCode.UNAUTHORIZED)))
        assertEquals(AccountError.SessionEnded, errorOf(HttpStatusCode.Unauthorized, apiError(ApiErrorCode.INVALID_REFRESH_TOKEN)))
        assertEquals(AccountError.SignInRejected, errorOf(HttpStatusCode.Unauthorized, apiError(ApiErrorCode.INVALID_ID_TOKEN)))
        assertEquals(AccountError.SignInRejected, errorOf(HttpStatusCode.BadRequest, apiError(ApiErrorCode.APPLE_CODE_REQUIRED)))
        assertEquals(AccountError.QuotaExceeded, errorOf(HttpStatusCode.PayloadTooLarge, apiError(ApiErrorCode.QUOTA_EXCEEDED)))
        assertEquals(AccountError.IdentityInUse, errorOf(HttpStatusCode.Conflict, apiError(ApiErrorCode.IDENTITY_IN_USE)))
        assertEquals(AccountError.ProviderUnavailable, errorOf(HttpStatusCode.BadGateway, apiError(ApiErrorCode.UPSTREAM_UNAVAILABLE)))
        assertEquals(AccountError.InvalidRequest("diagnostic"), errorOf(HttpStatusCode.BadRequest, apiError(ApiErrorCode.INVALID_REQUEST)))
        assertEquals(AccountError.UnknownBaseVersion, errorOf(HttpStatusCode.Conflict, apiError(ApiErrorCode.UNKNOWN_BASE_VERSION)))
        // Unknown codes and bodies that aren't ApiErrors fall back to the status.
        assertEquals(AccountError.Server(503), errorOf(HttpStatusCode.ServiceUnavailable, "<html>proxy error</html>"))
        assertEquals(AccountError.NotFound, errorOf(HttpStatusCode.NotFound, apiError("gone_in_v2")))
        assertEquals(AccountError.Unexpected(418, "teapot"), errorOf(HttpStatusCode(418, "I'm a teapot"), apiError("teapot")))
        // A login proxy's 401 in front of the server isn't the server ending the session.
        assertEquals(AccountError.Unexpected(401, ""), errorOf(HttpStatusCode.Unauthorized, "<html>Sign in to the proxy</html>"))
    }

    @Test
    fun anExpiredAccessTokenIsLeftToTheSessionLayer() = runTest {
        assertFailsWith<TokenExpiredException> {
            api(HttpStatusCode.Unauthorized, apiError(ApiErrorCode.TOKEN_EXPIRED)).devices("https://sync.test", "token")
        }
    }

    @Test
    fun aSuccessThatIsNotTheExpectedJsonIsUnexpected() = runTest {
        assertEquals(AccountError.Unexpected(200, "malformed_response"), errorOf(HttpStatusCode.OK, "{\"devices\":\"no\"}"))
    }

    @Test
    fun rateLimitsWithoutRetryAfterWaitARandomPartOfTheDefault() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) respond(apiError(ApiErrorCode.RATE_LIMITED), HttpStatusCode.TooManyRequests)
            else respond("{\"devices\":[]}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val started = testScheduler.currentTime
        assertEquals(emptyList(), SyncApi(HttpClient(engine)).devices("https://sync.test", "token").devices)
        assertTrue(testScheduler.currentTime - started in 0..SyncApi.DEFAULT_RETRY_AFTER_MILLIS)
        assertEquals(2, calls)
    }

    @Test
    fun aBusyServersRetryAfterIsWaitedOutWhenShortAndReportedWhenLong() = runTest {
        var retryAfter = "5"
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls % 2 == 1) {
                respond(apiError("server_busy"), HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.RetryAfter, retryAfter))
            } else {
                respond("{\"devices\":[]}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val api = SyncApi(HttpClient(engine))
        val started = testScheduler.currentTime
        assertEquals(emptyList(), api.devices("https://sync.test", "token").devices)
        val waited = testScheduler.currentTime - started
        assertTrue(waited in 5_000..10_000, "waited $waited ms: at least Retry-After, at most twice it")

        retryAfter = "60"
        calls = 0
        val error = assertFailsWith<AccountException> { api.devices("https://sync.test", "token") }.error
        assertEquals(AccountError.RateLimited(60_000), error, "the caller waits it out")
        assertEquals(1, calls)
    }

    @Test
    fun retryAfterIsReadInSeconds() {
        assertEquals(7_000, SyncApi.parseRetryAfter("7"))
        assertNull(SyncApi.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(SyncApi.parseRetryAfter("-3"))
    }

    @Test
    fun aHandOffTheServerHasNothingForHasExpired() = runTest {
        val nothing = api(HttpStatusCode.NotFound, apiError(ApiErrorCode.NOT_FOUND))
        val request = HandoffCollectRequest("state", "verifier", "proof")
        assertEquals(AccountError.SignInExpired, assertFailsWith<AccountException> { nothing.appleHandoff("https://sync.test", request) }.error)
        assertEquals(AccountError.SignInExpired, assertFailsWith<AccountException> { nothing.githubHandoff("https://sync.test", request) }.error)
    }

    @Test
    fun serverUrlsAreNormalized() {
        assertEquals("https://sync.example.com", ServerUrl.parse(" sync.example.com/ ").value)
        assertEquals("http://192.168.1.5:8080", ServerUrl.parse("http://192.168.1.5:8080").value)
        assertEquals("https://example.com/tortuga", ServerUrl.parse("https://example.com:443/tortuga/").value)
        for (bad in listOf("", "ftp://sync.example.com", "https://user:pass@sync.example.com", "https://sync.example.com/?x=1", "https://")) {
            assertEquals(AccountError.InvalidServerUrl, assertFailsWith<AccountException>(bad) { ServerUrl.parse(bad).value }.error)
        }
    }

    @Test
    fun plainHttpIsOnlyForAServerOnThisDeviceOrItsNetwork() {
        val local = listOf(
            "http://127.0.0.1:18180", "http://localhost:8080", "http://[::1]:8080", "http://10.0.2.2:8080", "http://172.31.0.4",
            "http://192.168.1.5:8080", "http://169.254.10.1", "http://100.101.102.103", "http://[fd12:3456::1]", "http://[fe80::1]",
            "http://NAS.local:8080", "http://nas",
        )
        assertEquals(local, local.map { ServerUrl.parse(it).value })
        val internet = listOf(
            "http://sync.example.com", "http://sync.example.com/tortuga", "http://8.8.8.8:8080", "http://172.32.0.1",
            "http://100.128.0.1", "http://[2001:db8::1]", "http://[fd::1]", "http://nas.lan", "http://192.168.1.5.nip.io",
        )
        for (url in internet) {
            assertEquals(AccountError.InsecureServerUrl, assertFailsWith<AccountException>(url) { ServerUrl.parse(url).value }.error)
        }
        assertEquals("https://sync.example.com", ServerUrl.parse("https://sync.example.com").value)
    }

    @Test
    fun linkQrCodesCarryTheServerAndCode() {
        val qr = DeviceLinkQr(ServerUrl.parse("https://example.com/tortuga%20sync"), "ABCDEFGH")
        assertEquals(qr, DeviceLinkQr.parse(qr.encode()))
        assertEquals(DeviceLinkQr(ServerUrl.HOSTED, "ABCDEFGH"), DeviceLinkQr.parse("tortuga://link?server=https%3A%2F%2Fapi.usetortuga.app&code=abcd-efgh"))
        assertNull(DeviceLinkQr.parse("https://api.usetortuga.app/link?code=ABCDEFGH"))
        assertNull(DeviceLinkQr.parse("tortuga://link?server=https%3A%2F%2Fapi.usetortuga.app&code=ABCDEFG0"))
        assertNull(DeviceLinkQr.parse("tortuga://link?server=ftp%3A%2F%2Fsync.example.com&code=ABCDEFGH"), "a server that isn't one")
    }
}
