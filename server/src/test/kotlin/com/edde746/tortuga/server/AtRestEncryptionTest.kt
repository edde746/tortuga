package com.edde746.tortuga.server

import com.edde746.tortuga.server.support.LinkSecret
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.sync.api.AccountDeletionResponse
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.LinkCodeRequest
import com.edde746.tortuga.sync.api.PullResponse
import com.edde746.tortuga.sync.api.PushRequest
import com.edde746.tortuga.sync.api.PushResponse
import com.edde746.tortuga.sync.api.RecordWrite
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    private fun String.latin1(): String = String(encodeToByteArray(), Charsets.ISO_8859_1)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
