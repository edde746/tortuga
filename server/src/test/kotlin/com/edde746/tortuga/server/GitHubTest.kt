package com.edde746.tortuga.server

import com.edde746.tortuga.server.auth.Secrets
import com.edde746.tortuga.server.db.Database
import com.edde746.tortuga.server.support.FakeGitHub
import com.edde746.tortuga.server.support.ServerTestContext
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.captureLogs
import com.edde746.tortuga.server.support.configFromEnvironment
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.server.support.withGitHub
import com.edde746.tortuga.sync.api.AccountDeletionResponse
import com.edde746.tortuga.sync.api.AccountInfo
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.AppleRevocation
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.GitHubHandoff
import com.edde746.tortuga.sync.api.GitHubRevocation
import com.edde746.tortuga.sync.api.HandoffCollectRequest
import com.edde746.tortuga.sync.api.HandoffStartRequest
import com.edde746.tortuga.sync.api.HandoffStartResponse
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.LinkIdentityRequest
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.SignInRequest
import com.edde746.tortuga.sync.api.SignInResponse
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Sign in with GitHub against [FakeGitHub]: the web flow's start, return page and pickup, sign-in, linking, revocation. */
class GitHubTest {
    private val device = DeviceDescriptor("Pixel", "android")

    /** An app's web flow: its verifier, and the state the server issued for it. */
    private class WebFlow(val verifier: String, val state: String)

    /** The app starts a web flow that returns to its loopback receiver. */
    private suspend fun ServerTestContext.start(): WebFlow {
        val verifier = Secrets.generate("")
        val start = post(SyncPaths.GITHUB_START, HandoffStartRequest(Secrets.sha256Base64Url(verifier), LOOPBACK))
        return WebFlow(verifier, start.ok<HandoffStartResponse>().state)
    }

    /** GitHub's redirect of the browser to the return page. */
    private suspend fun ServerTestContext.returnPage(query: String): HttpResponse = browser.get("${SyncPaths.GITHUB_RETURN}?$query")

    private suspend fun ServerTestContext.handoff(flow: WebFlow, proof: String): HttpResponse =
        post(SyncPaths.GITHUB_HANDOFF, HandoffCollectRequest(flow.state, flow.verifier, proof))

    /** GitHub's redirect with [login]'s code, then the app's pickup: the token the app signs in with. */
    private suspend fun ServerTestContext.webFlow(login: String): String {
        val flow = start()
        val proof = returnPage("code=${github.authorize(login)}&state=${flow.state}").proof(flow)
        return handoff(flow, proof).ok<GitHubHandoff>().accessToken
    }

    private suspend fun ServerTestContext.signInWith(token: String): HttpResponse =
        post(SyncPaths.AUTH_SIGN_IN, SignInRequest(IdentityProvider.GITHUB, token, device = device))

    private suspend fun ServerTestContext.link(token: String, accessToken: String): HttpResponse =
        post(SyncPaths.ACCOUNT_IDENTITIES, LinkIdentityRequest(IdentityProvider.GITHUB, token), accessToken)

    @Test
    fun webFlowSignsInAsTheNumericUserAndKeepsOnlyTheResetTokenSealed() = serverTest(configure = { c, _ -> withGitHub(c) }) {
        val provider = get(SyncPaths.INFO).ok<ServerInfo>().providers.single { it.id == IdentityProvider.GITHUB }
        assertEquals(FakeGitHub.URL, provider.issuer)
        assertEquals(listOf(FakeGitHub.CLIENT_ID), provider.clientIds)

        val flow = start()
        val code = github.authorize("octocat")
        handoff(flow, Secrets.generate("ttp_")).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        val page = returnPage("code=$code&state=${flow.state}")
        assertEquals("no-store", page.headers["Cache-Control"])
        val appToken = handoff(flow, page.proof(flow)).ok<GitHubHandoff>().accessToken
        assertEquals("octocat", github.loginOf(appToken))

        val signedIn = signInWith(appToken).ok<SignInResponse>()
        assertTrue(signedIn.accountCreated)
        val identity = signedIn.account.identities.single()
        assertEquals(IdentityProvider.GITHUB, identity.provider)
        // The primary verified address from /user/emails, not the older one listed first.
        assertEquals(FakeGitHub.email("octocat"), identity.email)
        // The identity is GitHub's numeric user id under GitHub's address, never the login.
        val (issuer, subject) = TestPostgres.database.transaction {
            queryOne("SELECT issuer, subject FROM identities") { it.getString("issuer") to it.getString("subject") }!!
        }
        assertEquals(FakeGitHub.URL, issuer)
        assertEquals(github.userId("octocat").toString(), subject)

        // GitHub ended the app's token at the reset, so it signs in once; the server holds only the new one, sealed.
        assertFalse(github.isLive(appToken))
        signInWith(appToken).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
        val kept = github.liveTokens("octocat").single()
        val dump = TestPostgres.dump().values.joinToString("\n")
        for (secret in listOf(appToken, kept, code, FakeGitHub.CLIENT_SECRET)) {
            assertFalse(secret in dump, "the database holds a GitHub token, code or secret")
        }
        val sealed = TestPostgres.database.transaction {
            queryOne("SELECT count(*) FROM identities WHERE github_token IS NOT NULL") { it.getInt(1) }
        }
        assertEquals(1, sealed)

        // The same user signing in again, with another token, returns to the account.
        val again = signInWith(github.token("octocat")).ok<SignInResponse>()
        assertFalse(again.accountCreated)
        assertEquals(signedIn.account.accountId, again.account.accountId)
    }

    @Test
    fun anotherAppsTokenOrAMadeUpOneIsRefused() = serverTest(configure = { c, _ -> withGitHub(c) }) {
        val otherApps = github.token("octocat", FakeGitHub.OTHER_CLIENT_ID)
        for (token in listOf(otherApps, "gho_made-up")) {
            signInWith(token).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
        }
        // The other app's token is left as it was.
        assertTrue(github.isLive(otherApps))
        assertEquals(0, TestPostgres.count("accounts"))
    }

    @Test
    fun declinedAtGitHubSendsTheBrowserBackWithTheError() = serverTest(configure = { c, _ -> withGitHub(c) }) {
        val flow = start()
        val page = returnPage("error=access_denied&error_description=The+user+has+denied+your+application+access.&state=${flow.state}")
        assertEquals(HttpStatusCode.SeeOther, page.status)
        val back = Url(page.headers[HttpHeaders.Location]!!)
        assertEquals(flow.state, back.parameters[SyncPaths.RETURN_STATE])
        assertEquals("access_denied", back.parameters[SyncPaths.RETURN_ERROR])
        assertNull(back.parameters[SyncPaths.RETURN_PROOF])
        assertEquals(0, TestPostgres.count("github_handoffs"))
    }

    @Test
    fun theFirstAnswerIsCollectedOnceUntilItExpires() = serverTest(configure = { c, _ -> withGitHub(c) }) {
        val flow = start()
        val proof = returnPage("code=${github.authorize("octocat")}&state=${flow.state}").proof(flow)
        // Another redirect for the same state (a reload, a forged one) doesn't replace the first, and gets no proof.
        val again = returnPage("code=${github.authorize("mallory")}&state=${flow.state}")
        assertEquals(HttpStatusCode.BadRequest, again.status)
        assertContains(again.bodyAsText(), "Sign-in failed")
        val token = handoff(flow, proof).ok<GitHubHandoff>().accessToken
        assertEquals("octocat", github.loginOf(token))
        handoff(flow, proof).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)

        val late = start()
        val lateProof = returnPage("code=${github.authorize("octocat")}&state=${late.state}").proof(late)
        clock.advance(Duration.ofMinutes(10))
        handoff(late, lateProof).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        assertEquals(1, TestPostgres.count("github_handoffs"))
        services.janitor.sweep()
        assertEquals(0, TestPostgres.count("github_handoffs"))
    }

    @Test
    fun onlyTheStartingVerifierWithTheProofCollects() = serverTest(configure = { c, _ -> withGitHub(c) }) {
        val flow = start()
        val proof = returnPage("code=${github.authorize("octocat")}&state=${flow.state}").proof(flow)
        // Whoever learnt the state and the proof (the browser's address) but not the verifier gets nothing, and so
        // does a verifier without the proof.
        handoff(WebFlow(Secrets.generate(""), flow.state), proof).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        handoff(flow, Secrets.generate("ttp_")).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        post(SyncPaths.GITHUB_HANDOFF, HandoffCollectRequest("short", flow.verifier, proof))
            .assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        // A state the server never issued writes nothing, whatever comes with it.
        for (query in listOf("code=abc&state=short", "code=abc", "state=${flow.state}", "error=access_denied", "code=abc&state=${Secrets.generate("")}")) {
            val page = returnPage(query)
            assertEquals(HttpStatusCode.BadRequest, page.status, query)
            assertContains(page.bodyAsText(), "Sign-in failed")
        }
        assertEquals(1, TestPostgres.count("github_handoffs"))
        handoff(flow, proof).ok<GitHubHandoff>()
    }

    @Test
    fun aCodeGitHubRefusesIsSpent() = serverTest(configure = { c, _ -> withGitHub(c) }) {
        val flow = start()
        val proof = returnPage("code=made-up&state=${flow.state}").proof(flow)
        handoff(flow, proof).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
        handoff(flow, proof).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
    }

    @Test
    fun linksToAnExistingAccountUnlessAnotherOneHasIt() = serverTest(configure = { c, _ -> withGitHub(c) }) {
        val alice = signIn("alice")
        val linked = link(webFlow("octocat"), alice.session.accessToken).ok<AccountInfo>()
        assertEquals(listOf(IdentityProvider.OIDC, IdentityProvider.GITHUB), linked.identities.map { it.provider })
        assertEquals(FakeGitHub.email("octocat"), linked.identities.last().email)
        assertEquals(alice.account.accountId, signInWith(github.token("octocat")).ok<SignInResponse>().account.accountId)

        val bob = signIn("bob")
        link(github.token("octocat"), bob.session.accessToken).assertError(HttpStatusCode.Conflict, ApiErrorCode.IDENTITY_IN_USE)
        assertEquals(1, get(SyncPaths.ACCOUNT, bob.session.accessToken).ok<AccountInfo>().identities.size)
    }

    @Test
    fun aSignInWhoseTokenGitHubResetWaitsOutABusyPool() = serverTest(configure = { c, _ -> withGitHub(c) }) {
        val token = github.token("octocat")
        val taken = CountDownLatch(TestPostgres.POOL_SIZE)
        val release = CountDownLatch(1)
        val signedIn = coroutineScope {
            // Every connection held by a transaction that doesn't end until released.
            val holders = List(TestPostgres.POOL_SIZE) {
                async(Dispatchers.IO) {
                    TestPostgres.database.transaction {
                        taken.countDown()
                        release.await()
                    }
                }
            }
            try {
                withContext(Dispatchers.IO) { taken.await() }
                // GitHub resets the token at once; the new one then exists only in this request, which must not be shed
                // after the wait an ordinary request gets.
                val signIn = async { signInWith(token) }
                delay(Database.ADMISSION_WAIT + 1.seconds)
                release.countDown()
                signIn.await()
            } finally {
                release.countDown()
                holders.awaitAll()
            }
        }
        val session = signedIn.ok<SignInResponse>().session.accessToken
        assertFalse(github.isLive(token))
        // The account kept the token GitHub issued at the reset: deletion deletes the grant with it.
        assertEquals(GitHubRevocation.REVOKED, delete(SyncPaths.ACCOUNT, session).ok<AccountDeletionResponse>().githubRevocation)
        assertFalse(github.hasGrant("octocat"))
    }

    @Test
    fun deletionDeletesTheGrantWithTheNewestToken() = serverTest(configure = { c, _ -> withGitHub(c) }) {
        signInWith(webFlow("octocat")).ok<SignInResponse>()
        val second = signInWith(github.token("octocat")).ok<SignInResponse>()
        val newest = github.liveTokens("octocat").last()

        val deletion = delete(SyncPaths.ACCOUNT, second.session.accessToken).ok<AccountDeletionResponse>()
        assertEquals(GitHubRevocation.REVOKED, deletion.githubRevocation)
        assertEquals(AppleRevocation.NONE, deletion.appleRevocation)
        assertEquals(listOf(newest), github.grantDeletions)
        assertFalse(github.hasGrant("octocat"))
        assertTrue(github.liveTokens("octocat").isEmpty())
        assertEquals(0, TestPostgres.count("accounts"))
    }

    @Test
    fun aRefusedGrantDeletionStillDeletesTheAccount() = serverTest(configure = { c, _ -> withGitHub(c) }) {
        val signedIn = signInWith(webFlow("octocat")).ok<SignInResponse>()
        // The user removed Tortuga at GitHub meanwhile, which ended the kept token.
        github.removeGrant("octocat")
        val deletion = delete(SyncPaths.ACCOUNT, signedIn.session.accessToken).ok<AccountDeletionResponse>()
        assertEquals(GitHubRevocation.FAILED, deletion.githubRevocation)
        assertEquals(1, github.grantDeletions.size)
        assertEquals(0, TestPostgres.count("accounts"))
        assertEquals(0, TestPostgres.count("identities"))
    }

    @Test
    fun unlinkingGitHubDeletesTheGrant() = serverTest(configure = { c, _ -> withGitHub(c) }) {
        val alice = signIn("alice")
        val token = alice.session.accessToken
        val linked = link(webFlow("octocat"), token).ok<AccountInfo>()
        val kept = github.liveTokens("octocat").single()
        val githubId = linked.identities.single { it.provider == IdentityProvider.GITHUB }.identityId

        val after = delete("${SyncPaths.ACCOUNT_IDENTITIES}/$githubId", token).ok<AccountInfo>()
        assertEquals(listOf(IdentityProvider.OIDC), after.identities.map { it.provider })
        assertEquals(listOf(kept), github.grantDeletions)
        assertFalse(github.hasGrant("octocat"))
        assertEquals(GitHubRevocation.NONE, delete(SyncPaths.ACCOUNT, token).ok<AccountDeletionResponse>().githubRevocation)
    }

    @Test
    fun gitHubUnreachableIsUpstreamUnavailable() = serverTest(configure = { c, _ -> withGitHub(c) }) {
        val signedIn = signInWith(webFlow("octocat")).ok<SignInResponse>()
        val flow = start()
        val proof = returnPage("code=${github.authorize("octocat")}&state=${flow.state}").proof(flow)
        github.down = true
        signInWith(github.token("octocat")).assertError(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE)
        handoff(flow, proof).assertError(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE)
        val deletion = delete(SyncPaths.ACCOUNT, signedIn.session.accessToken).ok<AccountDeletionResponse>()
        assertEquals(GitHubRevocation.FAILED, deletion.githubRevocation)
        assertEquals(0, TestPostgres.count("accounts"))
    }

    @Test
    fun withoutItsAppGitHubIsOff() = serverTest {
        assertTrue(get(SyncPaths.INFO).ok<ServerInfo>().providers.none { it.id == IdentityProvider.GITHUB })
        signInWith(github.token("octocat")).assertError(HttpStatusCode.BadRequest, ApiErrorCode.PROVIDER_NOT_ENABLED)
        val verifier = Secrets.generate("")
        post(SyncPaths.GITHUB_START, HandoffStartRequest(Secrets.sha256Base64Url(verifier), LOOPBACK))
            .assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        returnPage("code=${github.authorize("octocat")}&state=${Secrets.generate("")}")
            .assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
        handoff(WebFlow(verifier, Secrets.generate("")), Secrets.generate("ttp_"))
            .assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
    }

    @Test
    fun noTokenCodeSecretProofOrEmailReachesTheLogs() {
        lateinit var fake: FakeGitHub
        lateinit var refused: WebFlow
        lateinit var refusedProof: String
        val lines = captureLogs {
            serverTest(configure = { c, _ -> withGitHub(c) }) {
                fake = github
                val signedIn = signInWith(webFlow("octocat")).ok<SignInResponse>()
                signInWith(github.token("octocat", FakeGitHub.OTHER_CLIENT_ID))
                    .assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
                refused = start()
                refusedProof = returnPage("code=made-up-code&state=${refused.state}").proof(refused)
                handoff(refused, refusedProof).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.INVALID_ID_TOKEN)
                github.removeGrant("octocat")
                assertEquals(
                    GitHubRevocation.FAILED,
                    delete(SyncPaths.ACCOUNT, signedIn.session.accessToken).ok<AccountDeletionResponse>().githubRevocation,
                )
            }
        }
        assertTrue(lines.any { "Account deleted" in it }, "the logs were captured")
        val basic = Base64.getEncoder().encodeToString("${FakeGitHub.CLIENT_ID}:${FakeGitHub.CLIENT_SECRET}".toByteArray())
        val secrets = fake.issued +
            listOf("made-up-code", FakeGitHub.CLIENT_SECRET, basic, FakeGitHub.email("octocat"), refused.verifier, refusedProof)
        for (line in lines) {
            for (secret in secrets) assertFalse(secret in line, "a log line holds a GitHub token, code, secret, proof or email: $line")
        }
    }

    @Test
    fun gitHubNeedsBothItsClientIdAndSecretAndNeverPrintsTheSecret() {
        val id = "TORTUGA_GITHUB_CLIENT_ID" to "Ov23liFixture"
        val secret = "TORTUGA_GITHUB_CLIENT_SECRET" to "github-secret-fixture"
        for (half in listOf(id, secret)) {
            val error = assertFailsWith<IllegalArgumentException> { configFromEnvironment(half) }
            assertContains(error.message.orEmpty(), "TORTUGA_GITHUB_CLIENT_SECRET")
        }
        val config = configFromEnvironment(id, secret)
        assertEquals("Ov23liFixture", config.github?.clientId)
        assertFalse(secret.second in config.toString())
    }

    private companion object {
        /** The app's loopback receiver (Android, desktop). */
        const val LOOPBACK = "http://127.0.0.1:53682/callback"

        /** The proof the return page sent the browser back to [flow]'s app with (a 303 to its receiver). */
        fun HttpResponse.proof(flow: WebFlow): String {
            assertEquals(HttpStatusCode.SeeOther, status)
            val back = Url(headers[HttpHeaders.Location]!!)
            assertEquals(LOOPBACK, back.toString().substringBefore('?'))
            assertEquals(flow.state, back.parameters[SyncPaths.RETURN_STATE])
            return back.parameters[SyncPaths.RETURN_PROOF]!!
        }
    }
}
