package com.edde746.tortuga.server

import com.edde746.tortuga.server.support.LinkSecret
import com.edde746.tortuga.server.support.ServerTestContext
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.configFromEnvironment
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.sync.api.AccountDeletionResponse
import com.edde746.tortuga.sync.api.AccountInfo
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.LinkCodeRequest
import com.edde746.tortuga.sync.api.LinkPollResponse
import com.edde746.tortuga.sync.api.LinkStatus
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The review sign-in (server/README.md, Review sign-in): with `TORTUGA_REVIEW_PASSWORD_SHA256` set, the review page
 * approves a device's link for the one demo account to whoever has the review password; without it there is no page.
 */
class ReviewSignInTest {
    private val tv = DeviceDescriptor("Apple TV", "tvos")
    private val phone = DeviceDescriptor("iPhone", "ios")

    @Test
    fun aCodeEnteredOnTheReviewPageSignsTheDeviceInToTheDemoAccount() = reviewTest {
        val page = browserGet()
        assertEquals(HttpStatusCode.OK, page.status)
        assertContains(page.bodyAsText(), """name="password"""")
        assertContains(page.bodyAsText(), """name="code"""")

        val secret = LinkSecret()
        val started = startLink(secret, tv)
        assertEquals(LinkStatus.PENDING, pollLink(started.linkId, secret.secret).ok<LinkPollResponse>().status)
        // Typed as a person types it.
        val approved = review(PASSWORD, started.code.lowercase().chunked(4).joinToString("-"))
        assertEquals(HttpStatusCode.OK, approved.status)
        val answer = approved.bodyAsText()
        assertContains(answer, "Device signed in")

        val signedIn = pollLink(started.linkId, secret.secret).ok<LinkPollResponse>()
        assertEquals(LinkStatus.APPROVED, signedIn.status)
        val session = assertNotNull(signedIn.session)
        val account = get(SyncPaths.ACCOUNT, session.accessToken).ok<AccountInfo>()
        assertEquals(signedIn.accountId, account.accountId)
        assertEquals(listOf("review"), account.identities.map { it.provider })
        assertNull(account.identities.single().email)
        // The page shows nothing of the account.
        assertFalse(account.accountId in answer)
        // No app offers it as a sign-in: only the page reaches it.
        assertTrue(get(SyncPaths.INFO).ok<ServerInfo>().providers.none { it.id == "review" })

        // Another device joins the same account through the page.
        assertEquals(account.accountId, linkThroughThePage(phone).accountId)

        // The signed-in TV approves a third device with its own session, the way every signed-in device does.
        val thirdSecret = LinkSecret()
        val third = startLink(thirdSecret, DeviceDescriptor("Mac", "macos"))
        assertEquals(HttpStatusCode.NoContent, post(SyncPaths.LINK_APPROVE, LinkCodeRequest(third.code), session.accessToken).status)
        assertEquals(account.accountId, pollLink(third.linkId, thirdSecret.secret).ok<LinkPollResponse>().accountId)

        assertEquals(1, TestPostgres.count("accounts"))
        assertEquals(3, TestPostgres.count("sessions"))
    }

    @Test
    fun noSiteCanFrameThePage() = reviewTest {
        // A page's form framed under another site's could take a click meant for that site (clickjacking).
        for (page in listOf(browserGet(), review(PASSWORD, "ABCD-EFGH"))) {
            assertContains(assertNotNull(page.headers["Content-Security-Policy"]), "frame-ancestors 'none'")
            assertEquals("DENY", page.headers["X-Frame-Options"])
        }
    }

    @Test
    fun aWrongPasswordIsRefusedAndApprovesNothing() = reviewTest {
        val secret = LinkSecret()
        val started = startLink(secret, tv)
        // Nor the configured hash, nor the password in another case or with more after it.
        for (password in listOf("", PASSWORD_SHA256, PASSWORD.lowercase(), PASSWORD + "x")) {
            val refused = review(password, started.code)
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertContains(refused.bodyAsText(), "Wrong review password")
        }
        assertEquals(LinkStatus.PENDING, pollLink(started.linkId, secret.secret).ok<LinkPollResponse>().status)
        assertEquals(0, TestPostgres.count("accounts"))
    }

    @Test
    fun reviewTriesAreRateLimitedPerAddress() = reviewTest {
        val secret = LinkSecret()
        val started = startLink(secret, tv)
        repeat(5) { assertEquals(HttpStatusCode.Forbidden, review("guess-$it", started.code).status) }

        // The sixth try in the minute is refused before the password is read, the right one too.
        val limited = review(PASSWORD, started.code)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertNotNull(limited.headers[HttpHeaders.RetryAfter])
        assertContains(limited.bodyAsText(), "Too many tries")
        assertEquals(LinkStatus.PENDING, pollLink(started.linkId, secret.secret).ok<LinkPollResponse>().status)
        assertEquals(0, TestPostgres.count("accounts"))
        // The page itself still loads.
        assertEquals(HttpStatusCode.OK, browserGet().status)
    }

    @Test
    fun anUnknownOrExpiredCodeIsRefused() = reviewTest {
        val secret = LinkSecret()
        val started = startLink(secret, tv)
        val unused = if (started.code == "ABCDEFGH") "HGFEDCBA" else "ABCDEFGH"
        for (code in listOf(unused, "", "IOIO-0101")) {
            val refused = review(PASSWORD, code)
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertContains(refused.bodyAsText(), "No device shows this code")
        }

        clock.advance(Duration.ofMinutes(11))
        val expired = review(PASSWORD, started.code)
        assertEquals(HttpStatusCode.Gone, expired.status)
        assertContains(expired.bodyAsText(), "This code has expired")
        pollLink(started.linkId, secret.secret).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        // A refused code makes no demo account.
        assertEquals(0, TestPostgres.count("accounts"))
    }

    @Test
    fun withoutTheReviewPasswordConfiguredThereIsNoReviewPage() = serverTest {
        val secret = LinkSecret()
        val started = startLink(secret, tv)
        assertEquals(HttpStatusCode.NotFound, browserGet().status)
        assertEquals(HttpStatusCode.NotFound, review(PASSWORD, started.code).status)
        assertEquals(LinkStatus.PENDING, pollLink(started.linkId, secret.secret).ok<LinkPollResponse>().status)
        assertEquals(0, TestPostgres.count("accounts"))
    }

    @Test
    fun theDemoAccountIsMadeAgainAfterItsDeletion() = reviewTest {
        val first = linkThroughThePage(tv)
        delete(SyncPaths.ACCOUNT, assertNotNull(first.session).accessToken).ok<AccountDeletionResponse>()
        assertEquals(0, TestPostgres.count("accounts"))
        assertEquals(0, TestPostgres.count("identities"))

        val second = linkThroughThePage(tv)
        assertNotEquals(first.accountId, second.accountId)
        val account = get(SyncPaths.ACCOUNT, assertNotNull(second.session).accessToken).ok<AccountInfo>()
        assertEquals(listOf("review"), account.identities.map { it.provider })
        assertEquals(1, TestPostgres.count("accounts"))
    }

    /** [device] starts a link, its code is approved on the review page, and its poll collects the session. */
    private suspend fun ServerTestContext.linkThroughThePage(device: DeviceDescriptor): LinkPollResponse {
        val secret = LinkSecret()
        val started = startLink(secret, device)
        assertEquals(HttpStatusCode.OK, review(PASSWORD, started.code).status)
        return pollLink(started.linkId, secret.secret).ok<LinkPollResponse>().also { assertEquals(LinkStatus.APPROVED, it.status) }
    }

    private suspend fun ServerTestContext.browserGet(): HttpResponse =
        client.get(REVIEW) { header(HttpHeaders.Accept, "text/html") }

    /** The review page's form, posted as a browser posts it. */
    private suspend fun ServerTestContext.review(password: String, code: String): HttpResponse =
        client.submitForm(REVIEW, parameters {
            append("password", password)
            append("code", code)
        }) { header(HttpHeaders.Accept, "text/html") }

    /** [serverTest] with the review password's hash set as the hosted instance sets it, in its environment. */
    private fun reviewTest(block: suspend ServerTestContext.() -> Unit) = serverTest(
        configure = { config, _ ->
            // Written in capitals: the hash reads either way.
            configFromEnvironment("TORTUGA_REVIEW_PASSWORD_SHA256" to PASSWORD_SHA256.uppercase()).copy(
                dataKey = config.dataKey,
                apple = config.apple,
                google = config.google,
                oidc = config.oidc,
                janitorInterval = config.janitorInterval,
            )
        },
        block = block,
    )

    private companion object {
        const val REVIEW = "/review"
        const val PASSWORD = "Kq7mR2vX9pLw4nTb8sHd3cYf"
        val PASSWORD_SHA256: String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PASSWORD.toByteArray()))
    }
}
