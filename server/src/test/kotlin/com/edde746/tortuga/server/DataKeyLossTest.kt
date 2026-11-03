package com.edde746.tortuga.server

import com.edde746.tortuga.server.crypto.DataKey
import com.edde746.tortuga.server.crypto.RecordCipher
import com.edde746.tortuga.server.crypto.RecordCipher.Field
import com.edde746.tortuga.server.support.ServerTestContext
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.server.support.withGitHub
import com.edde746.tortuga.sync.api.AccountDeletionResponse
import com.edde746.tortuga.sync.api.AccountInfo
import com.edde746.tortuga.sync.api.GitHubRevocation
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.PullResponse
import com.edde746.tortuga.sync.api.PushRequest
import com.edde746.tortuga.sync.api.PushResponse
import com.edde746.tortuga.sync.api.RecordWrite
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.trackers.connections.TrackerConnectRequest
import com.edde746.tortuga.trackers.connections.TrackerConnection
import com.edde746.tortuga.trackers.connections.TrackerConnectionList
import com.edde746.tortuga.trackers.connections.TrackerConnectionPaths
import com.edde746.tortuga.trackers.connections.TrackerConnectionsApi
import com.edde746.tortuga.trackers.connections.TrackerTokenRequest
import io.ktor.http.HttpStatusCode
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * server/README.md, The data key: once a lost key is replaced, accounts survive and what was sealed before is gone. Rows
 * sealed with a key the server no longer holds never fail a request: pulls and listings leave them out, and deleting
 * them (a disconnect, a profile, an unlink, the account) revokes nothing.
 */
class DataKeyLossTest {
    /** The key the rows below were sealed with; the server under test holds another. */
    private val lostKey = RecordCipher(DataKey(ByteArray(DataKey.SIZE).also(SecureRandom()::nextBytes)))

    private val payload = JsonObject(mapOf("v" to JsonPrimitive("x")))

    @Test
    fun recordsSealedWithAnotherKeyAreLeftOutOfPulls() = serverTest {
        val alice = signIn("alice")
        val token = alice.session.accessToken
        val accountId = UUID.fromString(alice.account.accountId)
        TestPostgres.database.transaction {
            val index = lostKey.index(accountId, "lost")
            update(
                """
                INSERT INTO records (account_id, id_index, sealed_record_id, version, deleted, sealed_payload, payload_size,
                                     updated_at)
                VALUES (?, ?, ?, 1, false, ?, 2, ?)
                """,
                accountId, index, lostKey.seal(Field.RECORD_ID, accountId, index, "lost".encodeToByteArray()),
                lostKey.seal(Field.PAYLOAD, accountId, index, "{}".encodeToByteArray()), clock.instant(),
            )
            update("UPDATE accounts SET sync_version = 1 WHERE id = ?", accountId)
        }
        post(SyncPaths.SYNC_PUSH, PushRequest(listOf(RecordWrite("kept", 0, payload = payload))), token).ok<PushResponse>()

        val pulled = get("${SyncPaths.SYNC_PULL}?${SyncPaths.PULL_SINCE}=0", token).ok<PullResponse>()
        assertEquals(listOf("kept"), pulled.changes.map { it.recordId })
        assertEquals(2L, pulled.cursor)
    }

    @Test
    fun trackerConnectionsSealedWithAnotherKeyAreGoneAndDelete() = serverTest {
        val alice = signIn("alice")
        val token = alice.session.accessToken
        val accountId = UUID.fromString(alice.account.accountId)
        val kept = post(
            TrackerConnectionPaths.CONNECTIONS,
            TrackerConnectRequest("profile-3", "anilist", "", "anilist-access", null, 3600, "alice-anilist"),
            token,
        ).ok<TrackerConnection>()
        val lost = insertConnection(accountId, "profile-1", "trakt")
        insertConnection(accountId, "profile-2", "simkl")

        // Listed without them, and their tokens are gone.
        val listed = get(TrackerConnectionPaths.CONNECTIONS, token).ok<TrackerConnectionList>()
        assertEquals(listOf(kept), listed.connections)
        post(TrackerConnectionPaths.TOKEN, TrackerTokenRequest("profile-1", "trakt", lost.toEpochMilli(), null), token)
            .assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)

        // A disconnect deletes one, and the counter moves.
        assertEquals(HttpStatusCode.NoContent, delete(TrackerConnectionPaths.connection("profile-1", "trakt"), token).status)
        assertEquals(listed.version!! + 1, get(TrackerConnectionPaths.CONNECTIONS, token).ok<TrackerConnectionList>().version)
        // Its profile's tombstone deletes the other.
        val tombstone = RecordWrite(SyncProtocol.recordId(listOf(SyncProtocol.PROFILE_RECORD, "profile-2")), 0, deleted = true, payload = payload)
        post(SyncPaths.SYNC_PUSH, PushRequest(listOf(tombstone)), token).ok<PushResponse>()
        assertEquals(1, TestPostgres.count("tracker_connections"))
        assertEquals(listOf(kept), get(TrackerConnectionPaths.CONNECTIONS, token).ok<TrackerConnectionList>().connections)
    }

    @Test
    fun anAccountWhoseSealedTokensDontOpenUnlinksGitHubAndIsDeleted() = serverTest(configure = { config, _ -> withGitHub(config) }) {
        val alice = signIn("alice")
        val token = alice.session.accessToken
        val accountId = UUID.fromString(alice.account.accountId)
        val unlinked = insertGitHubIdentity(accountId, "1001")
        insertGitHubIdentity(accountId, "1002")
        insertConnection(accountId, "profile-1", "trakt")

        val remaining = delete("${SyncPaths.ACCOUNT_IDENTITIES}/$unlinked", token).ok<AccountInfo>()
        assertEquals(listOf(IdentityProvider.OIDC, IdentityProvider.GITHUB), remaining.identities.map { it.provider })

        // The grant can't be deleted without its token, so GitHub's revocation failed; everything else is gone.
        val deletion = delete(SyncPaths.ACCOUNT, token).ok<AccountDeletionResponse>()
        assertEquals(GitHubRevocation.FAILED, deletion.githubRevocation)
        for (table in listOf("accounts", "identities", "sessions", "tracker_connections")) {
            assertEquals(0, TestPostgres.count(table), table)
        }
    }

    /** A connection as a server holding [lostKey] stored it; its connection time. */
    private suspend fun ServerTestContext.insertConnection(accountId: UUID, profileId: String, service: String): Instant {
        clock.advance(Duration.ofSeconds(1))
        val connectedAt = clock.instant()
        val sealed = lostKey.seal(Field.TRACKER_SESSION, accountId, "$profileId/$service".encodeToByteArray(), "{}".encodeToByteArray())
        TestPostgres.database.transaction {
            update(
                "INSERT INTO tracker_connections (account_id, profile_id, service, sealed_session, connected_at) VALUES (?, ?, ?, ?, ?)",
                accountId, profileId, service, sealed, connectedAt,
            )
        }
        return connectedAt
    }

    /** A GitHub identity whose kept token a server holding [lostKey] sealed, linked after the others; its id. */
    private suspend fun ServerTestContext.insertGitHubIdentity(accountId: UUID, userId: String): UUID {
        clock.advance(Duration.ofSeconds(1))
        val id = UUID.randomUUID()
        val sealed = lostKey.seal(Field.GITHUB_TOKEN, accountId, id.toString().encodeToByteArray(), "gho_$userId".encodeToByteArray())
        TestPostgres.database.transaction {
            update(
                """
                INSERT INTO identities (id, account_id, provider, issuer, subject, github_token, created_at, last_used_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, accountId, IdentityProvider.GITHUB, "https://github.com", userId, sealed, clock.instant(), clock.instant(),
            )
        }
        return id
    }
}
