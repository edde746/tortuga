package com.edde746.tortuga.server

import com.edde746.tortuga.server.support.ServerTestContext
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.server.support.configFromEnvironment
import com.edde746.tortuga.sync.api.AccountInfo
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.DeviceList
import com.edde746.tortuga.sync.api.RefreshRequest
import com.edde746.tortuga.sync.api.SessionTokens
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionTest {
    @Test
    fun accessTokenExpiresAndRefreshRotates() = serverTest {
        val first = signIn("alice").session
        assertEquals(Duration.ofHours(1).toMillis(), first.accessExpiresInMillis)
        assertEquals(Duration.ofDays(180).toMillis(), first.refreshExpiresInMillis)
        get(SyncPaths.ACCOUNT, first.accessToken).ok<AccountInfo>()

        // The lifetimes are sent relative; the server's own clock ends them.
        clock.advance(Duration.ofMillis(first.accessExpiresInMillis - 1))
        get(SyncPaths.ACCOUNT, first.accessToken).ok<AccountInfo>()
        clock.advance(Duration.ofMillis(1))
        get(SyncPaths.ACCOUNT, first.accessToken).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.TOKEN_EXPIRED)

        val second = post(SyncPaths.AUTH_REFRESH, RefreshRequest(first.refreshToken)).ok<SessionTokens>()
        assertEquals(first.deviceId, second.deviceId)
        assertNotEquals(first.refreshToken, second.refreshToken)
        get(SyncPaths.ACCOUNT, second.accessToken).ok<AccountInfo>()

        // The response was "lost": the old refresh token works once more, within ten minutes.
        clock.advance(Duration.ofMinutes(10).minusMillis(1))
        val retried = post(SyncPaths.AUTH_REFRESH, RefreshRequest(first.refreshToken)).ok<SessionTokens>()
        // A refresh replaces the access token.
        get(SyncPaths.ACCOUNT, second.accessToken).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)

        // The newest refresh token rotates on.
        val third = post(SyncPaths.AUTH_REFRESH, RefreshRequest(retried.refreshToken)).ok<SessionTokens>()
        get(SyncPaths.ACCOUNT, third.accessToken).ok<AccountInfo>()
    }

    @Test
    fun theRetryOfALostRefreshWorksOnceAndASecondEndsTheSession() = serverTest {
        val first = signIn("alice").session
        post(SyncPaths.AUTH_REFRESH, RefreshRequest(first.refreshToken)).ok<SessionTokens>()
        val retried = post(SyncPaths.AUTH_REFRESH, RefreshRequest(first.refreshToken)).ok<SessionTokens>()

        post(SyncPaths.AUTH_REFRESH, RefreshRequest(first.refreshToken))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_REFRESH_TOKEN)
        assertSessionEnded(retried)
    }

    @Test
    fun theOldRefreshTokenAfterTenMinutesEndsTheSession() = serverTest {
        val first = signIn("alice").session
        val second = post(SyncPaths.AUTH_REFRESH, RefreshRequest(first.refreshToken)).ok<SessionTokens>()
        clock.advance(Duration.ofMinutes(10))

        post(SyncPaths.AUTH_REFRESH, RefreshRequest(first.refreshToken))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_REFRESH_TOKEN)
        assertSessionEnded(second)
    }

    @Test
    fun theOldRefreshTokenAfterTheNewOneWasUsedEndsTheSession() = serverTest {
        val first = signIn("alice").session
        val second = post(SyncPaths.AUTH_REFRESH, RefreshRequest(first.refreshToken)).ok<SessionTokens>()
        val third = post(SyncPaths.AUTH_REFRESH, RefreshRequest(second.refreshToken)).ok<SessionTokens>()

        post(SyncPaths.AUTH_REFRESH, RefreshRequest(first.refreshToken))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_REFRESH_TOKEN)
        assertSessionEnded(third)
    }

    @Test
    fun aStolenRefreshTokenEndsTheSessionOnceTheDeviceRefreshesAgain() = serverTest {
        val stolen = signIn("alice").session
        val device = post(SyncPaths.AUTH_REFRESH, RefreshRequest(stolen.refreshToken)).ok<SessionTokens>()
        // The thief's use counts as the device's retry: it replaces the device's tokens, and the thief rotates on.
        val thief = post(SyncPaths.AUTH_REFRESH, RefreshRequest(stolen.refreshToken)).ok<SessionTokens>()
        val thiefLater = post(SyncPaths.AUTH_REFRESH, RefreshRequest(thief.refreshToken)).ok<SessionTokens>()
        get(SyncPaths.ACCOUNT, device.accessToken).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)

        // The device's refresh token was replaced: it fails, and the session ends for the thief as well.
        post(SyncPaths.AUTH_REFRESH, RefreshRequest(device.refreshToken))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_REFRESH_TOKEN)
        assertSessionEnded(thiefLater)
    }

    /** Neither of [tokens] works any more. */
    private suspend fun ServerTestContext.assertSessionEnded(tokens: SessionTokens) {
        get(SyncPaths.ACCOUNT, tokens.accessToken).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)
        post(SyncPaths.AUTH_REFRESH, RefreshRequest(tokens.refreshToken))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_REFRESH_TOKEN)
        assertEquals(0, TestPostgres.count("sessions"))
    }

    @Test
    fun idleSessionEnds() = serverTest {
        val tokens = signIn("alice").session
        clock.advance(Duration.ofDays(181))
        post(SyncPaths.AUTH_REFRESH, RefreshRequest(tokens.refreshToken))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_REFRESH_TOKEN)
    }

    @Test
    fun revokingADeviceEndsItsSessionOnly() = serverTest {
        val phone = signIn("alice", deviceName = "Phone").session
        val tv = signIn("alice", deviceName = "TV").session
        val stranger = signIn("mallory").session

        // Another account's device is invisible.
        delete("${SyncPaths.DEVICES}/${tv.deviceId}", stranger.accessToken)
            .assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)

        assertEquals(HttpStatusCode.NoContent, delete("${SyncPaths.DEVICES}/${tv.deviceId}", phone.accessToken).status)
        get(SyncPaths.ACCOUNT, tv.accessToken).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)
        post(SyncPaths.AUTH_REFRESH, RefreshRequest(tv.refreshToken))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_REFRESH_TOKEN)
        assertEquals(listOf("Phone"), get(SyncPaths.DEVICES, phone.accessToken).ok<DeviceList>().devices.map { it.name })

        assertEquals(HttpStatusCode.NoContent, postEmpty(SyncPaths.AUTH_SIGN_OUT, phone.accessToken).status)
        get(SyncPaths.DEVICES, phone.accessToken).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)
    }

    @Test
    fun aSignInPastTheSessionLimitEndsTheLeastRecentlySeenOne() = serverTest {
        val stranger = signIn("mallory").session
        val first = signIn("alice", deviceName = "First").session
        clock.advance(Duration.ofMinutes(1))
        val second = signIn("alice", deviceName = "Second").session
        clock.advance(Duration.ofMinutes(1))
        repeat(SyncProtocol.MAX_SESSIONS - 2) { signIn("alice", deviceName = "Device $it") }
        // At the limit every session stays.
        val atLimit = get(SyncPaths.DEVICES, first.accessToken).ok<DeviceList>().devices.map { it.name }
        assertEquals(SyncProtocol.MAX_SESSIONS, atLimit.size)
        assertTrue("Second" in atLimit)

        // The first device is seen again, so the second is the least recently seen when one more signs in.
        clock.advance(Duration.ofMinutes(10))
        get(SyncPaths.ACCOUNT, first.accessToken).ok<AccountInfo>()
        signIn("alice", deviceName = "Newest")
        get(SyncPaths.ACCOUNT, second.accessToken).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)
        post(SyncPaths.AUTH_REFRESH, RefreshRequest(second.refreshToken))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_REFRESH_TOKEN)
        val devices = get(SyncPaths.DEVICES, first.accessToken).ok<DeviceList>().devices.map { it.name }
        assertEquals(SyncProtocol.MAX_SESSIONS, devices.size)
        assertEquals(listOf("First", "Newest"), devices.filter { it == "First" || it == "Newest" || it == "Second" }.sorted())
        // Another account's sessions are its own.
        get(SyncPaths.ACCOUNT, stranger.accessToken).ok<AccountInfo>()
    }

    @Test
    fun requestsWithoutAValidTokenAreRejected() = serverTest {
        get(SyncPaths.ACCOUNT).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)
        get(SyncPaths.ACCOUNT, "tta_unknown").assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)
    }

    @Test
    fun tokenRequestsFromOneClientAreRateLimited() =
        serverTest(configure = { config, _ -> config.copy(rateLimits = config.rateLimits.copy(api = 3)) }) {
            repeat(3) { get(SyncPaths.ACCOUNT, "tta_unknown").assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED) }
            val limited = get(SyncPaths.SYNC_PULL, "tta_unknown")
            limited.assertError(HttpStatusCode.TooManyRequests, ApiErrorCode.RATE_LIMITED)
            assertNotNull(limited.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun aLifetimeBelowOneRefusesToStart() {
        for (name in listOf("TORTUGA_ACCESS_TOKEN_TTL_SECONDS", "TORTUGA_SESSION_IDLE_DAYS", "TORTUGA_DATABASE_POOL_SIZE")) {
            for (value in listOf("0", "-1")) {
                val error = assertFailsWith<IllegalArgumentException> { configFromEnvironment(name to value) }
                assertEquals("$name must be at least 1", error.message)
            }
        }
    }
}
