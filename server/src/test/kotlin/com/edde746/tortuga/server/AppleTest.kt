package com.edde746.tortuga.server

import com.edde746.tortuga.server.auth.Secrets
import com.edde746.tortuga.server.http.MAX_TOKEN_ANSWER_BYTES
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.server.support.withAppleKey
import com.edde746.tortuga.sync.api.AccountDeletionResponse
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.AppleHandoff
import com.edde746.tortuga.sync.api.HandoffCollectRequest
import com.edde746.tortuga.sync.api.HandoffStartRequest
import com.edde746.tortuga.sync.api.HandoffStartResponse
import com.edde746.tortuga.sync.api.AppleRevocation
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.SignInRequest
import com.edde746.tortuga.sync.api.SignInResponse
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.client.request.forms.submitForm
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.parameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppleTest {
    private val device = DeviceDescriptor("iPhone", "ios")

    @Test
    fun hostedInstanceKeepsAppleRefreshTokenAndRevokesOnDeletion() = serverTest(configure = { c, e -> withAppleKey(c, e) }) {
        assertTrue(get(SyncPaths.INFO).ok<ServerInfo>().appleTokenRevocation)

        val first = nonce()
        post(SyncPaths.AUTH_SIGN_IN, SignInRequest(IdentityProvider.APPLE, apple.token("001234.apple", nonce = first), first, device = device))
            .assertError(HttpStatusCode.BadRequest, ApiErrorCode.APPLE_CODE_REQUIRED)
        // A code Apple issued for someone else doesn't go with this token.
        val second = nonce()
        post(
            SyncPaths.AUTH_SIGN_IN,
            SignInRequest(
                IdentityProvider.APPLE, apple.token("001234.apple", nonce = second), second,
                authorizationCode = appleEndpoints.issueCode("other"), device = device,
            ),
        ).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.APPLE_CODE_REJECTED)

        val signedIn = signIn("001234.apple", issuer = apple, authorizationCode = appleEndpoints.issueCode("001234.apple"))
        val exchange = appleEndpoints.exchanges.last()
        assertEquals("com.edde746.tortuga", exchange["client_id"])
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals(null, exchange["redirect_uri"])

        val deletion = delete(SyncPaths.ACCOUNT, signedIn.session.accessToken).ok<AccountDeletionResponse>()
        assertEquals(AppleRevocation.REVOKED, deletion.appleRevocation)
        val revocation = appleEndpoints.revocations.single()
        assertEquals("apple-refresh-001234.apple", revocation["token"])
        assertEquals("refresh_token", revocation["token_type_hint"])
        assertEquals("com.edde746.tortuga", revocation["client_id"])
        assertEquals(0, TestPostgres.count("accounts"))
        assertEquals(0, TestPostgres.count("identities"))
    }

    @Test
    fun webFlowCodesRepeatTheRedirectUri() = serverTest(configure = { c, e -> withAppleKey(c, e) }) {
        val nonce = nonce()
        val request = SignInRequest(
            IdentityProvider.APPLE,
            apple.token("web.user", audience = listOf("com.edde746.tortuga.web"), nonce = nonce),
            nonce,
            authorizationCode = appleEndpoints.issueCode("web.user"),
            device = DeviceDescriptor("Desktop", "linux"),
        )
        post(SyncPaths.AUTH_SIGN_IN, request).ok<SignInResponse>()
        val exchange = appleEndpoints.exchanges.single()
        assertEquals("com.edde746.tortuga.web", exchange["client_id"])
        assertEquals("https://tortuga.test/v2/auth/apple/return", exchange["redirect_uri"])
    }

    @Test
    fun failedRevocationStillDeletesTheAccount() = serverTest(configure = { c, e -> withAppleKey(c, e) }) {
        val signedIn = signIn("apple.user", issuer = apple, authorizationCode = appleEndpoints.issueCode("apple.user"))
        appleEndpoints.revokeStatus = HttpStatusCode.ServiceUnavailable
        val deletion = delete(SyncPaths.ACCOUNT, signedIn.session.accessToken).ok<AccountDeletionResponse>()
        assertEquals(AppleRevocation.FAILED, deletion.appleRevocation)
        assertEquals(0, TestPostgres.count("accounts"))
    }

    @Test
    fun aTokenAnswerPastTheCapFailsAsAFailingTokenEndpoint() = serverTest(configure = { c, e -> withAppleKey(c, e) }) {
        appleEndpoints.tokenAnswerBytes = MAX_TOKEN_ANSWER_BYTES.toInt() + 1
        val nonce = nonce()
        val request = SignInRequest(
            IdentityProvider.APPLE,
            apple.token("apple.user", nonce = nonce),
            nonce,
            authorizationCode = appleEndpoints.issueCode("apple.user"),
            device = device,
        )
        post(SyncPaths.AUTH_SIGN_IN, request).assertError(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE)
        assertEquals(0, TestPostgres.count("accounts"))

        appleEndpoints.tokenAnswerBytes = MAX_TOKEN_ANSWER_BYTES.toInt()
        signIn("apple.user", issuer = apple, authorizationCode = appleEndpoints.issueCode("apple.user"))
        assertEquals(1, TestPostgres.count("accounts"))
    }

    @Test
    fun aMalformedTokenAnswerFailsAsAFailingTokenEndpoint() = serverTest(configure = { c, e -> withAppleKey(c, e) }) {
        val malformed = listOf<(String) -> String>(
            { "not json" },
            { "[]" },
            { it.replace("\"refresh_token\":", "\"refresh_token\":{},\"was\":") },
            { it.replace("\"id_token\":\"", "\"id_token\":\"x") },
            { """{"refresh_token":"apple-refresh"}""" },
        )
        for (answer in malformed) {
            appleEndpoints.tokenAnswer = answer
            val nonce = nonce()
            val request = SignInRequest(
                IdentityProvider.APPLE,
                apple.token("apple.user", nonce = nonce),
                nonce,
                authorizationCode = appleEndpoints.issueCode("apple.user"),
                device = device,
            )
            post(SyncPaths.AUTH_SIGN_IN, request).assertError(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE)
        }
        assertEquals(0, TestPostgres.count("accounts"))
    }

    @Test
    fun ownServerCantRevokeAndSaysSo() = serverTest {
        val appleUser = signIn("apple.user", issuer = apple)
        assertEquals(
            AppleRevocation.NOT_CONFIGURED,
            delete(SyncPaths.ACCOUNT, appleUser.session.accessToken).ok<AccountDeletionResponse>().appleRevocation,
        )
        val googleUser = signIn("google.user", issuer = google)
        assertEquals(
            AppleRevocation.NONE,
            delete(SyncPaths.ACCOUNT, googleUser.session.accessToken).ok<AccountDeletionResponse>().appleRevocation,
        )
        assertTrue(appleEndpoints.exchanges.isEmpty() && appleEndpoints.revocations.isEmpty())
    }

    @Test
    fun anAppleTokenWithoutANonceIsRefused() = serverTest {
        post(SyncPaths.AUTH_SIGN_IN, SignInRequest(IdentityProvider.APPLE, apple.token("apple.user"), device = device))
            .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
        assertEquals(0, TestPostgres.count("accounts"))
    }

    @Test
    fun webReturnHandsTheResultOnlyToTheDeviceThatStartedItOnce() =
        serverTest(configure = { c, e -> withAppleKey(c, e, webReturn = true) }) {
            val verifier = Secrets.generate("")
            val start = post(SyncPaths.APPLE_START, HandoffStartRequest(Secrets.sha256Base64Url(verifier), LOOPBACK))
                .ok<HandoffStartResponse>()
            val nonce = nonce()
            val idToken = apple.token("web.user", audience = listOf("com.edde746.tortuga.web"), nonce = nonce)
            val code = appleEndpoints.issueCode("web.user")

            val page = browser.submitForm(
                SyncPaths.APPLE_RETURN,
                parameters {
                    append("state", start.state)
                    append("code", code)
                    append("id_token", idToken)
                    append("user", """{"name":{"firstName":"Web"}}""")
                },
            )
            assertEquals(HttpStatusCode.SeeOther, page.status)
            assertEquals("no-store", page.headers["Cache-Control"])
            val back = Url(page.headers[HttpHeaders.Location]!!)
            assertEquals(LOOPBACK, back.toString().substringBefore('?'))
            assertEquals(start.state, back.parameters[SyncPaths.RETURN_STATE])
            val proof = back.parameters[SyncPaths.RETURN_PROOF]!!
            // The state returns once: a second post for it, Apple's again or anyone's, changes nothing.
            val again = browser.submitForm(SyncPaths.APPLE_RETURN, parameters { append("state", start.state); append("id_token", "x") })
            assertEquals(HttpStatusCode.BadRequest, again.status)

            // Whoever lacks the verifier that started the flow, or the proof the browser came back with, gets nothing.
            post(SyncPaths.APPLE_HANDOFF, HandoffCollectRequest(start.state, Secrets.generate(""), proof))
                .assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
            post(SyncPaths.APPLE_HANDOFF, HandoffCollectRequest(start.state, verifier, Secrets.generate("ttp_")))
                .assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
            val collect = HandoffCollectRequest(start.state, verifier, proof)
            val handoff = post(SyncPaths.APPLE_HANDOFF, collect).ok<AppleHandoff>()
            assertEquals(idToken, handoff.idToken)
            assertEquals(code, handoff.authorizationCode)
            post(SyncPaths.APPLE_HANDOFF, collect).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)

            val signedIn = post(
                SyncPaths.AUTH_SIGN_IN,
                SignInRequest(IdentityProvider.APPLE, handoff.idToken, nonce, handoff.authorizationCode, device),
            ).ok<SignInResponse>()
            assertTrue(signedIn.accountCreated)
        }

    @Test
    fun aReturnPageWritesOnlyForAStateItIssued() = serverTest(configure = { c, e -> withAppleKey(c, e, webReturn = true) }) {
        val forged = browser.submitForm(
            SyncPaths.APPLE_RETURN,
            parameters { append("state", Secrets.generate("")); append("id_token", apple.token("web.user")) },
        )
        assertEquals(HttpStatusCode.BadRequest, forged.status)
        assertEquals(0, TestPostgres.count("apple_handoffs"))

        // Declined at Apple: the browser goes back with the error, and the flow is gone.
        val start = post(SyncPaths.APPLE_START, HandoffStartRequest(Secrets.sha256Base64Url(Secrets.generate("")), SyncPaths.HANDOFF_APP_RETURN))
            .ok<HandoffStartResponse>()
        val cancelled = browser.submitForm(
            SyncPaths.APPLE_RETURN,
            parameters { append("state", start.state); append("error", "user_cancelled_authorize") },
        )
        assertEquals(HttpStatusCode.SeeOther, cancelled.status)
        val back = Url(cancelled.headers[HttpHeaders.Location]!!)
        assertEquals("user_cancelled_authorize", back.parameters[SyncPaths.RETURN_ERROR])
        assertEquals(null, back.parameters[SyncPaths.RETURN_PROOF])
        assertEquals(0, TestPostgres.count("apple_handoffs"))
    }

    @Test
    fun aWebFlowReturnsOnlyToTheDeviceItStartedOn() = serverTest(configure = { c, e -> withAppleKey(c, e, webReturn = true) }) {
        val challenge = Secrets.sha256Base64Url(Secrets.generate(""))
        for (elsewhere in listOf("https://attacker.test/cb", "http://127.0.0.1.attacker.test:80/cb", "tortuga://signin/x", "$LOOPBACK?next=x")) {
            post(SyncPaths.APPLE_START, HandoffStartRequest(challenge, elsewhere)).assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        }
        post(SyncPaths.APPLE_START, HandoffStartRequest("short", LOOPBACK)).assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        assertEquals(0, TestPostgres.count("apple_handoffs"))
    }

    @Test
    fun returnPageIsOffUnlessConfigured() = serverTest {
        client.submitForm(SyncPaths.APPLE_RETURN, parameters { append("state", "x") })
            .assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
    }

    private companion object {
        /** The desktop app's loopback receiver. */
        const val LOOPBACK = "http://127.0.0.1:53682/callback"
    }
}
