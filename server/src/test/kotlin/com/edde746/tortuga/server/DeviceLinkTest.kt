package com.edde746.tortuga.server

import com.edde746.tortuga.server.support.LinkSecret
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.sync.api.AccountDeletionResponse
import com.edde746.tortuga.sync.api.AccountInfo
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DeviceList
import com.edde746.tortuga.sync.api.LinkClaimResponse
import com.edde746.tortuga.sync.api.LinkCodeRequest
import com.edde746.tortuga.sync.api.LinkPollResponse
import com.edde746.tortuga.sync.api.LinkStartRequest
import com.edde746.tortuga.sync.api.LinkStatus
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.normalizeLinkCode
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class DeviceLinkTest {
    private val tv = DeviceDescriptor("Living room TV", "android_tv")

    @Test
    fun signedOutTvSignsInToTheApproversAccount() = serverTest {
        val phone = signIn("alice")
        val secret = LinkSecret()
        val started = startLink(secret, tv)
        assertEquals(started.code, normalizeLinkCode(started.code), "eight characters of the code alphabet")
        assertEquals(LinkStatus.PENDING, pollLink(started.linkId, secret.secret).ok<LinkPollResponse>().status)

        // The phone takes the code as typed and sees which device asks.
        val typed = started.code.lowercase().chunked(4).joinToString("-")
        assertEquals(tv, post(SyncPaths.LINK_CLAIM, LinkCodeRequest(typed), phone.session.accessToken).ok<LinkClaimResponse>().device)
        assertEquals(HttpStatusCode.NoContent, post(SyncPaths.LINK_APPROVE, LinkCodeRequest(typed), phone.session.accessToken).status)

        val approved = pollLink(started.linkId, secret.secret).ok<LinkPollResponse>()
        assertEquals(LinkStatus.APPROVED, approved.status)
        assertEquals(phone.account.accountId, approved.accountId)
        // The TV got its own session on the phone's account.
        val session = assertNotNull(approved.session)
        assertNotEquals(phone.session.deviceId, session.deviceId)
        assertEquals(phone.account.accountId, get(SyncPaths.ACCOUNT, session.accessToken).ok<AccountInfo>().accountId)
        val devices = get(SyncPaths.DEVICES, session.accessToken).ok<DeviceList>().devices
        assertEquals(setOf("Test phone", "Living room TV"), devices.map { it.name }.toSet())
        assertEquals(session.deviceId, devices.single { it.current }.deviceId)

        // Collected once: the link is gone.
        pollLink(started.linkId, secret.secret).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        post(SyncPaths.LINK_CLAIM, LinkCodeRequest(started.code), phone.session.accessToken)
            .assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        assertEquals(0, TestPostgres.count("device_links"))
    }

    @Test
    fun aPollRacingItsApproversAccountDeletionWaitsForIt() = serverTest {
        val phone = signIn("alice").session.accessToken
        val secret = LinkSecret()
        val started = startLink(secret, tv)
        assertEquals(HttpStatusCode.NoContent, post(SyncPaths.LINK_APPROVE, LinkCodeRequest(started.code), phone).status)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        coroutineScope {
            // Holds the deletion between its lock on the account and the delete that cascades to the approved link:
            // reading the account's tracker connections, in between, waits for this lock.
            val holder = async(Dispatchers.IO) {
                TestPostgres.database.transaction {
                    update("LOCK TABLE tracker_connections IN ACCESS EXCLUSIVE MODE")
                    locked.countDown()
                    release.await()
                }
            }
            try {
                withContext(Dispatchers.IO) { locked.await() }
                val deletion = async { delete(SyncPaths.ACCOUNT, phone) }
                awaitLockWaiters(1)
                // The new device collects its approval meanwhile.
                val poll = async { pollLink(started.linkId, secret.secret) }
                awaitLockWaiters(2)
                release.countDown()
                deletion.await().ok<AccountDeletionResponse>()
                // The link went with the account; no session was opened on it.
                poll.await().assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
            } finally {
                release.countDown()
                holder.await()
            }
        }
        assertEquals(0, TestPostgres.count("sessions"))
    }

    @Test
    fun onlyTheSecretThatStartedTheLinkCollectsTheSession() = serverTest {
        val phone = signIn("alice").session.accessToken
        val secret = LinkSecret()
        val started = startLink(secret, tv)
        // Neither the code a user types or scans, nor the hash the server keeps, nor another device's secret.
        val wrong = listOf(started.code, secret.hash, LinkSecret().secret, "", secret.secret + "x")
        for (guess in wrong) pollLink(started.linkId, guess).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)

        post(SyncPaths.LINK_APPROVE, LinkCodeRequest(started.code), phone)
        for (guess in wrong) pollLink(started.linkId, guess).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        // The wrong polls minted nothing and left the approval waiting for its device.
        assertEquals(1, TestPostgres.count("sessions"))
        assertEquals(1, TestPostgres.count("device_links"))

        assertNotNull(pollLink(started.linkId, secret.secret).ok<LinkPollResponse>().session)
        assertEquals(2, TestPostgres.count("sessions"))
        pollLink(started.linkId, secret.secret).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        assertEquals(2, TestPostgres.count("sessions"))

        // Unknown link ids are not found either.
        pollLink(UUID.randomUUID().toString(), secret.secret).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        pollLink("not-a-link", secret.secret).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
    }

    @Test
    fun unknownCodesAreNotFound() = serverTest {
        val phone = signIn("alice").session.accessToken
        val started = startLink(LinkSecret(), tv)
        val unused = if (started.code == "ABCDEFGH") "HGFEDCBA" else "ABCDEFGH"
        for (code in listOf(unused, "", "ABCD", "IOIO-0101", started.code + "A")) {
            for (path in listOf(SyncPaths.LINK_CLAIM, SyncPaths.LINK_APPROVE, SyncPaths.LINK_REJECT)) {
                post(path, LinkCodeRequest(code), phone).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
            }
        }
        // The open link was left alone.
        assertEquals(tv, post(SyncPaths.LINK_CLAIM, LinkCodeRequest(started.code), phone).ok<LinkClaimResponse>().device)
    }

    @Test
    fun answeredLinksCantBeAnsweredAgain() = serverTest {
        val phone = signIn("alice").session.accessToken
        val stranger = signIn("mallory").session.accessToken
        val approved = startLink(LinkSecret(), tv)
        assertEquals(HttpStatusCode.NoContent, post(SyncPaths.LINK_APPROVE, LinkCodeRequest(approved.code), phone).status)
        val rejected = startLink(LinkSecret(), tv)
        assertEquals(HttpStatusCode.NoContent, post(SyncPaths.LINK_REJECT, LinkCodeRequest(rejected.code), phone).status)

        for (code in listOf(approved.code, rejected.code)) {
            for (path in listOf(SyncPaths.LINK_CLAIM, SyncPaths.LINK_APPROVE, SyncPaths.LINK_REJECT)) {
                for (token in listOf(phone, stranger)) {
                    post(path, LinkCodeRequest(code), token).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
                }
            }
        }
    }

    @Test
    fun rejectedAndExpiredLinks() = serverTest {
        val phone = signIn("alice").session.accessToken
        val secret = LinkSecret()
        val rejected = startLink(secret, tv)
        assertEquals(HttpStatusCode.NoContent, post(SyncPaths.LINK_REJECT, LinkCodeRequest(rejected.code), phone).status)
        val answer = pollLink(rejected.linkId, secret.secret).ok<LinkPollResponse>()
        assertEquals(LinkStatus.REJECTED, answer.status)
        assertNull(answer.session)
        assertNull(answer.accountId)
        pollLink(rejected.linkId, secret.secret).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        assertEquals(1, TestPostgres.count("sessions"))

        val expiringSecret = LinkSecret()
        val expiring = startLink(expiringSecret, tv)
        clock.advance(Duration.ofMinutes(11))
        post(SyncPaths.LINK_CLAIM, LinkCodeRequest(expiring.code), phone).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        post(SyncPaths.LINK_APPROVE, LinkCodeRequest(expiring.code), phone).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        pollLink(expiring.linkId, expiringSecret.secret).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        services.janitor.sweep()
        assertEquals(0, TestPostgres.count("device_links"))
    }

    @Test
    fun aLinksLifetimeIsSentRelativeAndRunsOutOnTheServersClock() = serverTest {
        val phone = signIn("alice").session.accessToken
        val secret = LinkSecret()
        val started = startLink(secret, tv)
        assertEquals(Duration.ofMinutes(10).toMillis(), started.expiresInMillis)

        // A claim four minutes in gets what is left, so the approving device's clock needn't match the server's.
        clock.advance(Duration.ofMinutes(4))
        val claim = post(SyncPaths.LINK_CLAIM, LinkCodeRequest(started.code), phone).ok<LinkClaimResponse>()
        assertEquals(Duration.ofMinutes(6).toMillis(), claim.expiresInMillis)

        clock.advance(Duration.ofMillis(claim.expiresInMillis - 1))
        assertEquals(1L, post(SyncPaths.LINK_CLAIM, LinkCodeRequest(started.code), phone).ok<LinkClaimResponse>().expiresInMillis)
        assertEquals(LinkStatus.PENDING, pollLink(started.linkId, secret.secret).ok<LinkPollResponse>().status)

        // Whatever the devices' clocks say, the server's decides.
        clock.advance(Duration.ofMillis(1))
        post(SyncPaths.LINK_CLAIM, LinkCodeRequest(started.code), phone).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        post(SyncPaths.LINK_APPROVE, LinkCodeRequest(started.code), phone).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        pollLink(started.linkId, secret.secret).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
    }

    @Test
    fun rejectsMalformedStarts() = serverTest {
        val hash = LinkSecret().hash
        val badHashes = listOf(
            "",
            hash.dropLast(1),
            "$hash=",
            hash + "A",
            // Hex, standard base64 and stray characters aren't unpadded base64url.
            "ab".repeat(32),
            "+" + hash.drop(1),
            "!" + hash.drop(1),
        )
        for (secretHash in badHashes) {
            post(SyncPaths.LINK_START, LinkStartRequest(secretHash, tv)).assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        }
        for (device in listOf(DeviceDescriptor(" ", "android_tv"), DeviceDescriptor("TV", ""), DeviceDescriptor("x".repeat(65), "tvos"))) {
            post(SyncPaths.LINK_START, LinkStartRequest(hash, device)).assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        }
        // A body that doesn't decode as the request: malformed JSON, a missing field, another type.
        for (body in listOf("{\"secretHash\":", "{}", "[]")) {
            client.post(SyncPaths.LINK_START) { setBody(TextContent(body, ContentType.Application.Json)) }
                .assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        }
        assertEquals(0, TestPostgres.count("device_links"))
    }

    @Test
    fun startingLinksIsRateLimited() = serverTest(configure = { config, _ -> config.copy(rateLimits = RateLimits(linkStart = 3)) }) {
        repeat(3) { startLink(LinkSecret(), tv) }
        val limited = post(SyncPaths.LINK_START, LinkStartRequest(LinkSecret().hash, tv))
        limited.assertError(HttpStatusCode.TooManyRequests, ApiErrorCode.RATE_LIMITED)
        assertNotNull(limited.headers["Retry-After"])
    }

    @Test
    fun linkAnswersCountPerSessionHoweverItsHeaderIsWritten() =
        serverTest(configure = { config, _ -> config.copy(rateLimits = RateLimits(linkAnswer = 2)) }) {
            val phone = signIn("alice").session.accessToken
            suspend fun claim(authorization: String) = client.post(SyncPaths.LINK_CLAIM) {
                header(HttpHeaders.Authorization, authorization)
                contentType(ContentType.Application.Json)
                setBody(LinkCodeRequest("nope"))
            }
            claim("Bearer $phone").assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
            claim("bearer $phone").assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
            claim("BEARER   $phone").assertError(HttpStatusCode.TooManyRequests, ApiErrorCode.RATE_LIMITED)
        }

    /** Waits until [count] database sessions wait for a lock. */
    private suspend fun awaitLockWaiters(count: Int) {
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (true) {
            val waiting = TestPostgres.database.transaction {
                queryOne("SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted") { it.getInt(1) }!!
            }
            if (waiting >= count) return
            check(deadline.hasNotPassedNow()) { "$count sessions didn't wait for a lock within 10 s" }
            delay(10)
        }
    }
}
