package com.edde746.tortuga.server

import com.edde746.tortuga.server.service.SyncRecords
import com.edde746.tortuga.server.support.ServerTestContext
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.sync.api.AccountDeletionResponse
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.PullResponse
import com.edde746.tortuga.sync.api.PushRequest
import com.edde746.tortuga.sync.api.PushResponse
import com.edde746.tortuga.sync.api.RecordWrite
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class SyncTest {
    private fun payload(text: String) = JsonObject(mapOf("v" to JsonPrimitive(text)))

    /** A payload whose compact JSON (`{"v":"…"}`) is [bytes] bytes. */
    private fun payloadOfSize(bytes: Int) = payload("x".repeat(bytes - 8))

    private fun write(recordId: String, baseVersion: Long, text: String, deleted: Boolean = false) =
        RecordWrite(recordId, baseVersion, deleted, payload(text))

    private suspend fun ServerTestContext.push(token: String, vararg writes: RecordWrite) =
        post(SyncPaths.SYNC_PUSH, PushRequest(writes.toList()), token)

    private suspend fun ServerTestContext.pull(token: String, since: Long = 0, limit: Int? = null): PullResponse =
        get("${SyncPaths.SYNC_PULL}?${SyncPaths.PULL_SINCE}=$since" + (limit?.let { "&${SyncPaths.PULL_LIMIT}=$it" } ?: ""), token).ok()

    private suspend fun ServerTestContext.rawPush(token: String, body: String) = client.post(SyncPaths.SYNC_PUSH) {
        bearerAuth(token)
        setBody(TextContent(body, ContentType.Application.Json))
    }

    @Test
    fun pushedRecordsPullInVersionOrderWithPaging() = serverTest {
        val token = signIn("alice").session.accessToken
        val accepted = push(token, write("a", 0, "A1"), write("b", 0, "B1")).ok<PushResponse>().accepted
        assertEquals(listOf("a" to 1L, "b" to 2L), accepted.map { it.recordId to it.version })
        push(token, write("a", 1, "A2"), write("c", 0, "C1")).ok<PushResponse>()

        val first = pull(token, limit = 2)
        // "a" was rewritten at version 3, so the oldest change is "b".
        assertEquals(listOf("b" to 2L, "a" to 3L), first.changes.map { it.recordId to it.version })
        assertEquals(payload("A2"), first.changes[1].payload)
        assertTrue(first.hasMore)
        assertEquals(3, first.cursor)
        assertEquals(4, first.latestVersion)

        val rest = pull(token, since = first.cursor, limit = 2)
        assertEquals(listOf("c" to 4L), rest.changes.map { it.recordId to it.version })
        assertFalse(rest.hasMore)

        val none = pull(token, since = rest.cursor)
        assertEquals(emptyList(), none.changes)
        assertEquals(rest.cursor, none.cursor)
    }

    @Test
    fun recordsComeBackAsWritten() = serverTest {
        val token = signIn("alice").session.accessToken
        val recordId = "library/profile%2F1/tt0111161 ünïcode"
        val record = SyncProtocol.json.parseToJsonElement(
            """
            {"key":{"type":"movie","id":"tt0111161"},"hlc":"1790000000000:0:phone","format":1,
             "body":{"name":"Amélie 🎬","rating":8.5,"big":9007199254740993,"tags":["a",null,true],"nested":{"empty":{}}}}
            """,
        ).jsonObject
        push(token, RecordWrite(recordId, 0, payload = record)).ok<PushResponse>()

        val change = pull(token).changes.single()
        assertEquals(recordId, change.recordId)
        assertEquals(record, change.payload)
    }

    @Test
    fun staleWriteReturnsTheCurrentRecordAndMergeRetrySucceeds() = serverTest {
        val phone = signIn("alice", deviceName = "Phone").session.accessToken
        val tv = signIn("alice", deviceName = "TV").session.accessToken
        push(phone, write("progress", 0, "phone-1")).ok<PushResponse>()

        // The TV hasn't pulled and believes the record is new.
        val conflict = push(tv, write("progress", 0, "tv-1"), write("other", 0, "x")).ok<PushResponse>()
        assertEquals(listOf("other"), conflict.accepted.map { it.recordId })
        val current = conflict.stale.single()
        assertEquals("progress" to 1L, current.recordId to current.version)
        assertEquals(payload("phone-1"), current.payload)

        val merged = push(tv, write("progress", current.version, "merged")).ok<PushResponse>()
        assertEquals(3L, merged.accepted.single().version)
        assertEquals(payload("merged"), pull(phone, since = 2).changes.single().payload)
    }

    @Test
    fun concurrentWritesToOneRecordAcceptExactlyOne() = serverTest {
        val token = signIn("alice").session.accessToken
        val results = coroutineScope {
            (1..8).map { i -> async { push(token, write("addons", 0, "device-$i")).ok<PushResponse>() } }.awaitAll()
        }
        assertEquals(1, results.sumOf { it.accepted.size })
        assertEquals(7, results.sumOf { it.stale.size })
        assertEquals(1L, pull(token).latestVersion)
    }

    @Test
    fun tombstonesSyncLikeRecords() = serverTest {
        val token = signIn("alice").session.accessToken
        push(token, write("item", 0, "live")).ok<PushResponse>()
        push(token, write("item", 1, "deleted-at-hlc", deleted = true)).ok<PushResponse>()
        val change = pull(token).changes.single()
        assertTrue(change.deleted)
        assertEquals(2L, change.version)
        assertEquals(payload("deleted-at-hlc"), change.payload)
    }

    @Test
    fun accountsAreIsolated() = serverTest {
        val alice = signIn("alice").session.accessToken
        val bob = signIn("bob").session.accessToken
        push(alice, write("same-id", 0, "alice")).ok<PushResponse>()
        assertEquals(emptyList(), pull(bob).changes)
        // Bob's own record under the same id is independent of Alice's.
        assertEquals(1L, push(bob, write("same-id", 0, "bob")).ok<PushResponse>().accepted.single().version)
        assertEquals(payload("alice"), pull(alice).changes.single().payload)
    }

    @Test
    fun rejectsMalformedPushes() = serverTest {
        val token = signIn("alice").session.accessToken
        for (badId in listOf("", "line\nbreak", "nul\u0000", "x".repeat(SyncProtocol.MAX_RECORD_ID_LENGTH + 1))) {
            push(token, write(badId, 0, "x")).assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        }
        push(token, write("a", 0, "x"), write("a", 0, "y")).assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        push(token, RecordWrite("big", 0, payload = payloadOfSize(SyncProtocol.MAX_RECORD_BYTES + 1)))
            .assertError(HttpStatusCode.PayloadTooLarge, ApiErrorCode.PAYLOAD_TOO_LARGE)
        push(token, *Array(SyncProtocol.MAX_WRITES_PER_PUSH + 1) { write("r$it", 0, "x") })
            .assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        // Nothing of the rejected batches was written.
        assertEquals(0L, pull(token).latestVersion)

        // The limits themselves are allowed: the longest id, the largest payload.
        val longestId = "x".repeat(SyncProtocol.MAX_RECORD_ID_LENGTH)
        val pushed = push(token, RecordWrite(longestId, 0, payload = payloadOfSize(SyncProtocol.MAX_RECORD_BYTES)))
        assertEquals(longestId, pushed.ok<PushResponse>().accepted.single().recordId)
    }

    @Test
    fun aBaseVersionOfARecordTheServerDoesntHaveIsAConflictNotAMalformedPush() = serverTest {
        val token = signIn("alice").session.accessToken
        // The server lost the write this one is based on (restored from a backup): the code the devices re-sync on.
        push(token, write("new", 0, "x"), write("missing", 5, "x"))
            .assertError(HttpStatusCode.Conflict, ApiErrorCode.UNKNOWN_BASE_VERSION)
        assertEquals(0L, pull(token).latestVersion, "nothing of the push was written")
    }

    @Test
    fun rejectsPushBodiesThatArentPushRequests() = serverTest {
        val token = signIn("alice").session.accessToken
        val write = """{"recordId":"a","baseVersion":0,"payload":{"v":1}}"""
        val bodies = listOf(
            "", "[]", """{"writes":null}""", """{"other":[]}""", """{"writes":[$write]} {}""", """{"writes":[$write""",
            """{"writes":[{"recordId":"a","baseVersion":0,"payload":[1]}]}""",
            """{"writes":[{"recordId":"a","baseVersion":0}]}""",
            """{"writes":[{"recordId":1,"baseVersion":0,"payload":{}}]}""",
            """{"writes":[{"recordId":"a","baseVersion":0,"payload":{"v":nope}}]}""",
        )
        for (body in bodies) rawPush(token, body).assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        // A string longer than any record: refused as the parser reaches its limit, before the string is whole.
        val long = "x".repeat(2 * SyncProtocol.MAX_RECORD_BYTES)
        rawPush(token, """{"writes":[{"recordId":"a","baseVersion":0,"payload":{"v":"$long"}}]}""")
            .assertError(HttpStatusCode.PayloadTooLarge, ApiErrorCode.PAYLOAD_TOO_LARGE)
        assertEquals(0L, pull(token).latestVersion)
    }

    @Test
    fun aPushBodyReadsAsKotlinxSerializationReadsIt() = serverTest {
        val token = signIn("alice").session.accessToken
        // Unknown keys anywhere, any key order, whitespace and escapes: the payload is stored as its compact JSON.
        val body = """
            { "client": {"nested": [1, {"x": null}]},
              "writes": [ { "payload": { "text": "\u00e9t\u00e9 \/ \"q\" \n", "n": 1.50e3, "list": [ true, null, {} ] },
                            "extra": [], "recordId": "raw", "baseVersion": 0 } ] }
        """
        assertEquals(listOf("raw"), rawPush(token, body).ok<PushResponse>().accepted.map { it.recordId })
        val pulled = get("${SyncPaths.SYNC_PULL}?${SyncPaths.PULL_SINCE}=0", token).bodyAsText()
        assertTrue(""""payload":{"text":"été / \"q\" \n","n":1.50e3,"list":[true,null,{}]}""" in pulled, pulled)
    }

    @Test
    fun largeRecordsPullInPagesOfAboutAMebibyte() = serverTest {
        val token = signIn("alice").session.accessToken
        val ids = (0..5).map { "big$it" }
        push(token, *ids.map { RecordWrite(it, 0, payload = payloadOfSize(SyncProtocol.MAX_RECORD_BYTES)) }.toTypedArray())
            .ok<PushResponse>()

        // A page stops at the record that reaches 1 MiB: four of these, though the limit allows 500.
        val first = pull(token)
        assertEquals(ids.take(4), first.changes.map { it.recordId })
        assertTrue(first.hasMore)
        val rest = pull(token, since = first.cursor)
        assertEquals(ids.drop(4), rest.changes.map { it.recordId })
        assertFalse(rest.hasMore)
    }

    @Test
    fun staleRecordsPastAMebibyteComeBackWhenTheirWritesArePushedAgain() = serverTest {
        val phone = signIn("alice", deviceName = "Phone").session.accessToken
        val tv = signIn("alice", deviceName = "TV").session.accessToken
        val ids = (0..5).map { "big$it" }
        push(phone, *ids.map { RecordWrite(it, 0, payload = payloadOfSize(SyncProtocol.MAX_RECORD_BYTES)) }.toTypedArray())
            .ok<PushResponse>()

        // The TV believes all six are new: four come back stale, the last two in neither list, its own record accepted.
        val conflict = push(tv, *(ids + "tv").map { write(it, 0, "tv") }.toTypedArray()).ok<PushResponse>()
        assertEquals(listOf("tv"), conflict.accepted.map { it.recordId })
        assertEquals(ids.take(4), conflict.stale.map { it.recordId })
        val again = push(tv, *ids.drop(4).map { write(it, 0, "tv") }.toTypedArray()).ok<PushResponse>()
        assertEquals(ids.drop(4), again.stale.map { it.recordId })
    }

    @Test
    fun quotaCountsEachRecordsSealedIdAndRowBesideItsPayload() {
        // What a record with a one-character id takes: its payload, the id sealed (RecordCipher adds a format byte, a
        // 12-byte nonce and a 16-byte tag) and its row.
        fun charged(payloadBytes: Int): Long = payloadBytes + 1L + 29 + SyncRecords.ROW_BYTES
        serverTest(configure = { config, _ -> config.copy(accountQuotaBytes = charged(20) + charged(60)) }) {
            val token = signIn("alice").session.accessToken
            push(token, RecordWrite("a", 0, payload = payloadOfSize(60))).ok<PushResponse>()
            push(token, RecordWrite("b", 0, payload = payloadOfSize(60)))
                .assertError(HttpStatusCode.PayloadTooLarge, ApiErrorCode.QUOTA_EXCEEDED)
            // Replacing a record counts only the difference.
            push(token, RecordWrite("a", 1, payload = payloadOfSize(20))).ok<PushResponse>()
            // Exactly at the quota is fine; one byte over isn't.
            push(token, RecordWrite("b", 0, payload = payloadOfSize(61)))
                .assertError(HttpStatusCode.PayloadTooLarge, ApiErrorCode.QUOTA_EXCEEDED)
            push(token, RecordWrite("b", 0, payload = payloadOfSize(60))).ok<PushResponse>()
            // A record with the smallest payload still takes its id and its row.
            push(token, RecordWrite("c", 0, payload = JsonObject(emptyMap())))
                .assertError(HttpStatusCode.PayloadTooLarge, ApiErrorCode.QUOTA_EXCEEDED)
        }
    }

    @Test
    fun aTombstoneCarriesAtMostEightKibibytes() = serverTest {
        val token = signIn("alice").session.accessToken
        push(token, RecordWrite("a", 0, deleted = true, payload = payloadOfSize(SyncProtocol.MAX_TOMBSTONE_BYTES + 1)))
            .assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        push(token, RecordWrite("a", 0, deleted = true, payload = payloadOfSize(SyncProtocol.MAX_TOMBSTONE_BYTES))).ok<PushResponse>()
        // A record past the cap is still deleted with a small tombstone.
        push(token, RecordWrite("b", 0, payload = payloadOfSize(SyncProtocol.MAX_TOMBSTONE_BYTES + 1))).ok<PushResponse>()
        push(token, write("b", 2, "", deleted = true)).ok<PushResponse>()
    }

    @Test
    fun migrationV5RecountsEveryAccountsTotalAsPushesCountIt() = serverTest {
        val alice = signIn("alice").session.accessToken
        push(alice, write("a", 0, "first"), write("library/p/" + "x".repeat(900), 0, "long id")).ok<PushResponse>()
        push(alice, write("a", 1, "replaced with more"), write("b", 0, "", deleted = true)).ok<PushResponse>()
        push(signIn("bob").session.accessToken, write("c", 0, "y")).ok<PushResponse>()
        signIn("carol")
        val counted = storedBytes()

        // Before V5 an account's total was its payloads' bytes alone.
        TestPostgres.database.transaction {
            update("UPDATE accounts a SET stored_bytes = coalesce((SELECT sum(payload_size) FROM records r WHERE r.account_id = a.id), 0)")
        }
        assertNotEquals(counted, storedBytes())
        val migration = assertNotNull(javaClass.getResource("/db/migration/V5__quota_counts_record_ids.sql")).readText()
        TestPostgres.database.transaction { update(migration) }
        assertEquals(counted, storedBytes())
    }

    private suspend fun storedBytes(): Map<String, Long> = TestPostgres.database.transaction {
        query("SELECT id, stored_bytes FROM accounts") { it.getString("id") to it.getLong("stored_bytes") }.toMap()
    }

    @Test
    fun pushesAreRefusedWhileTheDatabasesDiskIsShort() = serverTest(
        configure = { config, _ ->
            config.copy(databaseDisk = Path.of(System.getProperty("java.io.tmpdir")), minFreeDatabaseDiskBytes = Long.MAX_VALUE)
        },
    ) {
        val token = signIn("alice").session.accessToken
        val refused = push(token, write("a", 0, "x"))
        refused.assertError(HttpStatusCode.ServiceUnavailable, ApiErrorCode.SERVER_BUSY)
        assertEquals("600", refused.headers[HttpHeaders.RetryAfter])
        assertTrue("disk" in refused.bodyAsText())
        assertTrue(pull(token).changes.isEmpty())
    }

    /**
     * A stream pick as the app pushes it (`pick` records: the pick, its context and outcome, and the 36 streams shown
     * around it, each as its badges, size and languages): about 4.9 KB, between a typical pick from the recorded stream
     * lists (3.5 KB) and the largest (6 KB).
     */
    private fun streamPick(recordKey: JsonObject, hlc: String, outcome: String?) = buildJsonObject {
        put("key", recordKey)
        put("hlc", hlc)
        put(
            "body",
            buildJsonObject {
                put("kind", "pick")
                put("metaId", "tt0388629")
                put("videoId", "tt0388629:1:1")
                put("runtimeSeconds", 1440)
                put("pickedAt", 1_790_000_000_000)
                put("deviceClass", "tv")
                put("metered", false)
                put("hdrDisplay", true)
                put("dolbyVisionDisplay", false)
                put("hardwareCodecs", JsonArray(listOf(JsonPrimitive("HEVC"), JsonPrimitive("AVC"))))
                put("source", "list")
                put(
                    "candidates",
                    buildJsonArray {
                        repeat(36) { i ->
                            add(
                                buildJsonObject {
                                    put("r", listOf("UHD_2160", "FHD_1080", "HD_720")[i % 3])
                                    put("v", listOf("HEVC", "AVC")[i % 2])
                                    put("s", listOf("WEB_DL", "BLURAY", "WEBRIP", "HDTV")[i % 4])
                                    put("a", JsonArray(listOf(JsonPrimitive(listOf("ATMOS", "TRUEHD", "FLAC")[i % 3]))))
                                    put("c", listOf("STEREO", "SURROUND_5_1")[i % 2])
                                    put("b", 700_000_000L + i * 123_456_789L)
                                    put("k", i % 5 == 0)
                                    put("la", JsonArray(listOf(JsonPrimitive("jpn"), JsonPrimitive("eng"))))
                                    if (i % 3 == 0) put("ls", JsonArray(listOf(JsonPrimitive("eng"), JsonPrimitive("spa"), JsonPrimitive("por"))))
                                    if (i % 4 == 0) put("ma", true)
                                },
                            )
                        }
                    },
                )
                put("chosen", 25)
                put("firstRow", 112)
                outcome?.let {
                    put("outcome", it)
                    put("outcomeAt", 1_790_000_300_000)
                }
            },
        )
        put("format", 1)
    }

    @Test
    fun aStreamPickIsStoredAsWrittenUpdatedTombstonedAndGoesWithItsAccount() = serverTest {
        val token = signIn("alice").session.accessToken
        val recordId = "pick/0f6c2d7e-6a51-4c1b-9d55-2b8e3f1a7c40/5a3e9b12-8c47-4f0e-a1d6-73c2b9e4f058"
        val key = buildJsonObject {
            put("kind", "pick")
            put("profileId", "0f6c2d7e-6a51-4c1b-9d55-2b8e3f1a7c40")
            put("pickId", "5a3e9b12-8c47-4f0e-a1d6-73c2b9e4f058")
        }
        val picked = streamPick(key, "01a0c0a2f800-0000-0123456789abcdef", outcome = null)
        val compact = SyncProtocol.json.encodeToString(JsonObject.serializer(), picked)
        assertTrue(compact.encodeToByteArray().size in 3_500..6_000, "${compact.length} bytes")
        push(token, RecordWrite(recordId, 0, payload = picked)).ok<PushResponse>()
        val raw = get("${SyncPaths.SYNC_PULL}?${SyncPaths.PULL_SINCE}=0", token).bodyAsText()
        assertTrue(compact in raw, "the payload comes back byte for byte")
        assertEquals(picked, pull(token).changes.single().payload)

        // Its outcome, from another device: a new write of the same record.
        val outcome = streamPick(key, "01a0c0a2f801-0000-fedcba9876543210", outcome = "good")
        assertEquals(2L, push(token, RecordWrite(recordId, 1, payload = outcome)).ok<PushResponse>().accepted.single().version)
        push(token, RecordWrite(recordId, 1, payload = picked)).ok<PushResponse>().let { stale ->
            assertEquals(outcome, stale.stale.single().payload, "a write on the old version gets the current one")
        }
        assertEquals(outcome, pull(token, since = 1).changes.single().payload)

        // Pruned: a tombstone without the pick.
        val tombstone = buildJsonObject {
            put("key", key)
            put("hlc", "01a0c0a2f802-0000-0123456789abcdef")
        }
        push(token, RecordWrite(recordId, 2, deleted = true, payload = tombstone)).ok<PushResponse>()
        val change = pull(token, since = 2).changes.single()
        assertTrue(change.deleted)
        assertEquals(tombstone, change.payload)

        delete(SyncPaths.ACCOUNT, token).ok<AccountDeletionResponse>()
        assertEquals(0, TestPostgres.count("records"))
    }
}
