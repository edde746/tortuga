package com.edde746.tortuga.server

import com.edde746.tortuga.server.auth.Secrets
import com.edde746.tortuga.server.crypto.RecordCipher
import com.edde746.tortuga.server.crypto.RecordCipher.Field
import com.edde746.tortuga.server.crypto.checkDataKey
import com.edde746.tortuga.server.service.sealPlaintextAppleTokens
import com.edde746.tortuga.server.support.LinkSecret
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.server.support.testDataKey
import com.edde746.tortuga.server.support.withAppleKey
import com.edde746.tortuga.server.support.withGitHub
import com.edde746.tortuga.sync.api.AccountDeletionResponse
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.AppleHandoff
import com.edde746.tortuga.sync.api.AppleRevocation
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.GitHubHandoff
import com.edde746.tortuga.sync.api.HandoffCollectRequest
import com.edde746.tortuga.sync.api.HandoffStartRequest
import com.edde746.tortuga.sync.api.HandoffStartResponse
import com.edde746.tortuga.sync.api.LinkCodeRequest
import com.edde746.tortuga.sync.api.PullResponse
import com.edde746.tortuga.sync.api.PushRequest
import com.edde746.tortuga.sync.api.PushResponse
import com.edde746.tortuga.sync.api.RecordWrite
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.parameters
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject

/**
 * The server reads synced records but stores them sealed under its data key, so neither the
 * rows nor a dump of the database holds an account's addon URLs, ids or titles; and deleting the account leaves
 * nothing behind.
 */
class AtRestEncryptionTest {
    private val addonUrl = "https://streams.addon.example/key=KEY9f8e7d6c5b4a39281/manifest.json"
    private val stremioId = "tt0111161"
    private val title = "The Shawshank Redemption"
    private val profileName = "Wednesday Evening"

    /** Records as the apps write them: natural ids, the record JSON as the payload. */
    private val writes = listOf(
        record("addons/profile-1", """{"key":{},"hlc":"1790000000000:0:phone","format":1,"body":{"addons":[{"transportUrl":"$addonUrl","name":"Example Streams"}]}}"""),
        record("library/profile-1/$stremioId", """{"key":{"id":"$stremioId"},"hlc":"1790000000001:0:phone","format":1,"body":{"type":"movie","name":"$title"}}"""),
        record("profile/profile-1", """{"key":{},"hlc":"1790000000002:0:phone","format":1,"body":{"name":"$profileName"}}"""),
    )

    /** What must appear nowhere in the database: the payloads' telling values and the record ids. */
    private val needles = listOf(
        addonUrl, "streams.addon.example", "KEY9f8e7d6c5b4a39281", stremioId, title, "Shawshank", profileName, "transportUrl",
        "addons/profile-1", "library/profile-1", "profile/profile-1",
    )

    private fun record(recordId: String, json: String) =
        RecordWrite(recordId, 0, payload = SyncProtocol.json.parseToJsonElement(json).jsonObject)

    @Test
    fun recordsAreSealedAtRestAndPullIntact() = serverTest {
        val phone = signIn("alice").session.accessToken
        assertEquals(3, post(SyncPaths.SYNC_PUSH, PushRequest(writes), phone).ok<PushResponse>().accepted.size)

        // The rows themselves: index, sealed id and sealed payload hold none of it, as text or bytes.
        val rows = TestPostgres.database.transaction {
            query("SELECT id_index, sealed_record_id, sealed_payload FROM records") { row ->
                listOf(row.getBytes(1), row.getBytes(2), row.getBytes(3))
            }
        }
        assertEquals(3, rows.size)
        for (column in rows.flatten()) {
            val raw = String(column, Charsets.ISO_8859_1)
            for (needle in needles) assertFalse(raw.contains(needle.latin1()), "\"$needle\" in a records column")
        }

        // A dump of every table, as pg_dump writes rows.
        val dump = TestPostgres.dump()
        assertEquals(3, dump.getValue("records").lines().count { it.isNotEmpty() }, "the dump has the records")
        assertTrue(dump.getValue("records").contains("\\\\x"), "the dump renders bytea as hex")
        val all = dump.values.joinToString("\n")
        for (needle in needles) {
            assertFalse(all.contains(needle), "\"$needle\" in the database dump")
            assertFalse(all.contains(needle.encodeToByteArray().toHex()), "\"$needle\" as bytea in the database dump")
        }

        // Another device of the account reads every record back as written.
        val tv = signIn("alice", deviceName = "TV").session.accessToken
        val pulled = get(SyncPaths.SYNC_PULL, tv).ok<PullResponse>().changes
        assertEquals(writes.associate { it.recordId to it.payload }, pulled.associate { it.recordId to it.payload })

        // The index is per account: Bob's record under one of Alice's ids gets an index of its own.
        val bob = signIn("bob").session.accessToken
        post(SyncPaths.SYNC_PUSH, PushRequest(listOf(writes[1])), bob).ok<PushResponse>()
        val indexes = TestPostgres.database.transaction { queryOne("SELECT count(DISTINCT id_index) FROM records") { it.getInt(1) } }
        assertEquals(4, indexes)
    }

    @Test
    fun aSealedPayloadOpensOnlyInItsOwnRow() = serverTest {
        val phone = signIn("alice").session.accessToken
        post(SyncPaths.SYNC_PUSH, PushRequest(writes.take(2)), phone).ok<PushResponse>()
        // Someone with write access to the database swaps two records' payloads.
        TestPostgres.database.transaction {
            update(
                """
                UPDATE records r SET sealed_payload = o.sealed_payload FROM records o
                WHERE r.account_id = o.account_id AND r.id_index <> o.id_index
                """,
            )
        }
        // Neither opens in the other's row: the pull leaves both out, and its cursor still moves past them.
        val pulled = get(SyncPaths.SYNC_PULL, phone).ok<PullResponse>()
        assertEquals(emptyList(), pulled.changes)
        assertEquals(2L, pulled.cursor)
    }

    @Test
    fun deletingTheAccountLeavesNoRows() = serverTest {
        val phone = signIn("alice", email = "alice@example.com")
        val token = phone.session.accessToken
        post(SyncPaths.SYNC_PUSH, PushRequest(writes), token).ok<PushResponse>()
        signIn("alice", deviceName = "TV", platform = "android_tv")
        // A link the account approved whose device hasn't collected its session yet.
        val secret = LinkSecret()
        val link = startLink(secret, DeviceDescriptor("Laptop", "linux"))
        assertEquals(HttpStatusCode.NoContent, post(SyncPaths.LINK_APPROVE, LinkCodeRequest(link.code), token).status)

        delete(SyncPaths.ACCOUNT, token).ok<AccountDeletionResponse>()
        val remaining = TestPostgres.dump().filterValues { it.isNotBlank() }
        // Only the replay guard keeps hashes of this account's ID tokens until they expire; they name no account.
        assertEquals(setOf("used_id_tokens"), remaining.keys)
        assertFalse(remaining.values.any { phone.account.accountId in it })
        // The approval went with the account: the laptop finds nothing to collect.
        pollLink(link.linkId, secret.secret).assertError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND)
    }

    @Test
    fun appleTokensAndWebSignInResultsAreSealedAtRest() = serverTest(configure = { c, e -> withGitHub(withAppleKey(c, e, webReturn = true)) }) {
        val signedIn = signIn("001234.apple", issuer = apple, authorizationCode = appleEndpoints.issueCode("001234.apple"))
        // Apple's and GitHub's results waiting on their return pages for the app.
        val appleVerifier = Secrets.generate("")
        val appleFlow = post(SyncPaths.APPLE_START, HandoffStartRequest(Secrets.sha256Base64Url(appleVerifier), LOOPBACK))
            .ok<HandoffStartResponse>()
        val idToken = apple.token("web.user", audience = listOf("com.edde746.tortuga.web"), nonce = nonce())
        val code = appleEndpoints.issueCode("web.user")
        val appleProof = browser.submitForm(
            SyncPaths.APPLE_RETURN,
            parameters {
                append("state", appleFlow.state)
                append("code", code)
                append("id_token", idToken)
                append("user", """{"name":{"firstName":"Wilhelmina"}}""")
            },
        ).proof()
        val gitHubVerifier = Secrets.generate("")
        val gitHubFlow = post(SyncPaths.GITHUB_START, HandoffStartRequest(Secrets.sha256Base64Url(gitHubVerifier), LOOPBACK))
            .ok<HandoffStartResponse>()
        val gitHubCode = github.authorize("octocat")
        val gitHubProof = browser.get("${SyncPaths.GITHUB_RETURN}?code=$gitHubCode&state=${gitHubFlow.state}").proof()

        val dump = TestPostgres.dump().values.joinToString("\n")
        for (secret in listOf("apple-refresh-001234.apple", idToken, code, "Wilhelmina", gitHubCode)) {
            assertFalse(secret in dump, "\"$secret\" in the database dump")
            assertFalse(secret.encodeToByteArray().toHex() in dump, "\"$secret\" as bytea in the database dump")
        }

        // Each opens for the app as it came, and the refresh token revokes the sign-in.
        val appleHandoff = post(SyncPaths.APPLE_HANDOFF, HandoffCollectRequest(appleFlow.state, appleVerifier, appleProof)).ok<AppleHandoff>()
        assertEquals(AppleHandoff(idToken, code, """{"name":{"firstName":"Wilhelmina"}}"""), appleHandoff)
        val gitHubToken = post(SyncPaths.GITHUB_HANDOFF, HandoffCollectRequest(gitHubFlow.state, gitHubVerifier, gitHubProof))
            .ok<GitHubHandoff>().accessToken
        assertEquals("octocat", github.loginOf(gitHubToken))
        val deletion = delete(SyncPaths.ACCOUNT, signedIn.session.accessToken).ok<AccountDeletionResponse>()
        assertEquals(AppleRevocation.REVOKED, deletion.appleRevocation)
        assertEquals("apple-refresh-001234.apple", appleEndpoints.revocations.single()["token"])
    }

    @Test
    fun appleSecretsAServerFromBeforeStoredInPlainTextAreSealedAtItsFirstStart() = runBlocking {
        // A database as the server left it before migration 9: an Apple identity's refresh token and the results of
        // an Apple and a GitHub web sign-in waiting for the app in plain text, and a flow of each still at the provider.
        val upgrade = TestPostgres.databaseAt("8")
        val accountId = UUID.randomUUID()
        val identityId = UUID.randomUUID()
        val now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
        val later = now.plusMinutes(10)
        upgrade.connection().use { connection ->
            fun insert(sql: String, vararg values: Any?) = connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.executeUpdate()
            }
            insert("INSERT INTO accounts (id, created_at) VALUES (?, ?)", accountId, now)
            insert(
                """
                INSERT INTO identities (id, account_id, provider, issuer, subject, apple_refresh_token, apple_client_id,
                                        created_at, last_used_at)
                VALUES (?, ?, 'apple', 'https://appleid.apple.com', '001234.apple', 'apple-refresh-from-before',
                        'com.edde746.tortuga', ?, ?)
                """,
                identityId, accountId, now, now,
            )
            insert(
                """
                INSERT INTO apple_handoffs (state, challenge, redirect_uri, proof_hash, id_token, authorization_code, user_json, expires_at)
                VALUES ('returned', 'c', 'r', 'p', 'id-token-from-before', 'apple-code-from-before', '{"name":"Before"}', ?)
                """,
                later,
            )
            insert("INSERT INTO apple_handoffs (state, challenge, redirect_uri, expires_at) VALUES ('waiting', 'c', 'r', ?)", later)
            insert(
                "INSERT INTO github_handoffs (state, challenge, redirect_uri, proof_hash, code, expires_at) VALUES ('returned', 'c', 'r', 'p', 'github-code-from-before', ?)",
                later,
            )
            insert("INSERT INTO github_handoffs (state, challenge, redirect_uri, expires_at) VALUES ('waiting', 'c', 'r', ?)", later)
        }

        // The server's start: migrations, the data key's check, then the sealing.
        val db = upgrade.connect()
        val cipher = RecordCipher(testDataKey)
        checkDataKey(db, cipher)
        sealPlaintextAppleTokens(db, cipher)

        val dump = TestPostgres.dump(db).values.joinToString("\n")
        for (secret in listOf("apple-refresh-from-before", "id-token-from-before", "apple-code-from-before", "Before", "github-code-from-before")) {
            assertFalse(secret in dump, "\"$secret\" in the database dump")
            assertFalse(secret.encodeToByteArray().toHex() in dump, "\"$secret\" as bytea in the database dump")
        }
        db.transaction {
            val (plain, sealed) = queryOne("SELECT apple_refresh_token, sealed_apple_refresh_token FROM identities") {
                it.getString(1) to it.getBytes(2)
            }!!
            assertNull(plain)
            val opened = cipher.open(Field.APPLE_REFRESH_TOKEN, accountId, identityId.toString().encodeToByteArray(), sealed)
            assertEquals("apple-refresh-from-before", opened?.decodeToString())
            // The results in plain text are gone (those sign-ins start again); the flows at the provider go on.
            assertEquals(listOf("waiting"), query("SELECT state FROM apple_handoffs") { it.getString(1) })
            assertEquals(listOf("waiting"), query("SELECT state FROM github_handoffs") { it.getString(1) })
        }
        // The next start finds nothing left to seal.
        sealPlaintextAppleTokens(db, cipher)
        val again = db.transaction { queryOne("SELECT sealed_apple_refresh_token FROM identities") { it.getBytes(1) }!! }
        assertEquals("apple-refresh-from-before", cipher.open(Field.APPLE_REFRESH_TOKEN, accountId, identityId.toString().encodeToByteArray(), again)?.decodeToString())
    }

    /** The proof a return page's redirect takes back to the app. */
    private fun HttpResponse.proof(): String = Url(headers[HttpHeaders.Location]!!).parameters[SyncPaths.RETURN_PROOF]!!

    private fun String.latin1(): String = String(encodeToByteArray(), Charsets.ISO_8859_1)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

private const val LOOPBACK = "http://127.0.0.1:53682/callback"
