package com.edde746.tortuga.server

import com.edde746.tortuga.server.crypto.DataKey
import com.edde746.tortuga.server.crypto.RecordCipher
import com.edde746.tortuga.server.crypto.RecordCipher.Field
import com.edde746.tortuga.server.crypto.checkDataKey
import com.edde746.tortuga.server.support.ServerTestContext
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.server.support.testDataKey
import com.edde746.tortuga.server.support.withGitHub
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.PushRequest
import com.edde746.tortuga.sync.api.PushResponse
import com.edde746.tortuga.sync.api.RecordWrite
import com.edde746.tortuga.sync.api.SignInRequest
import com.edde746.tortuga.sync.api.SignInResponse
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.trackers.connections.TrackerConnectRequest
import com.edde746.tortuga.trackers.connections.TrackerConnection
import com.edde746.tortuga.trackers.connections.TrackerConnectionPaths
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonObject

/**
 * server/README.md, The data key: the server doesn't start with another key than the one the database's records are
 * sealed with ([checkDataKey], which the server runs before it serves).
 */
class DataKeyCheckTest {
    private val db = TestPostgres.database

    private val serversKey = RecordCipher(testDataKey)

    private fun anotherKey() = RecordCipher(DataKey(ByteArray(DataKey.SIZE).also(SecureRandom()::nextBytes)))

    /** The table as migration V8 leaves it: no check stored yet. */
    private suspend fun asMigrated() = db.transaction { update("INSERT INTO data_key DEFAULT VALUES") }

    /** Alice's account, with one record the server pushed under its key; her account id. */
    private suspend fun ServerTestContext.aliceWithARecord(): UUID {
        val alice = signIn("alice")
        post(SyncPaths.SYNC_PUSH, PushRequest(listOf(RecordWrite("kept", 0, payload = JsonObject(emptyMap())))), alice.session.accessToken)
            .ok<PushResponse>()
        return UUID.fromString(alice.account.accountId)
    }

    @Test
    fun aDatabaseTakesTheKeyItFirstStartsWithAndRefusesAnyOther() = serverTest {
        asMigrated()
        checkDataKey(db, serversKey)
        val refused = assertFailsWith<IllegalArgumentException> { checkDataKey(db, anotherKey()) }
        assertContains(refused.message.orEmpty(), DataKey.ENV)
        checkDataKey(db, serversKey)
    }

    @Test
    fun aDatabaseFromBeforeTheCheckTakesOnlyTheKeyItsNewestRecordOpensWith() = serverTest {
        // A record sealed with a key lost before the check existed, then one sealed with the server's key.
        val lost = anotherKey()
        val accountId = aliceWithARecord()
        db.transaction {
            val index = lost.index(accountId, "lost")
            update(
                """
                INSERT INTO records (account_id, id_index, sealed_record_id, version, deleted, sealed_payload, payload_size,
                                     updated_at)
                VALUES (?, ?, ?, 0, false, ?, 2, ?)
                """,
                accountId, index, lost.seal(Field.RECORD_ID, accountId, index, "lost".encodeToByteArray()),
                lost.seal(Field.PAYLOAD, accountId, index, "{}".encodeToByteArray()), clock.instant().minus(Duration.ofDays(1)),
            )
        }
        asMigrated()

        assertFailsWith<IllegalArgumentException> { checkDataKey(db, lost) }
        assertFailsWith<IllegalArgumentException> { checkDataKey(db, anotherKey()) }
        checkDataKey(db, serversKey)
        // Its check is stored now: the newest record no longer decides.
        db.transaction { update("DELETE FROM records") }
        assertFailsWith<IllegalArgumentException> { checkDataKey(db, anotherKey()) }
        checkDataKey(db, serversKey)
    }

    @Test
    fun aDatabaseFromBeforeTheCheckWithoutRecordsTakesOnlyTheKeyItsOtherSealedValuesOpenWith() = serverTest(configure = { c, _ -> withGitHub(c) }) {
        // An account that has only signed in with GitHub: its kept token is the database's one sealed value.
        post(SyncPaths.AUTH_SIGN_IN, SignInRequest(IdentityProvider.GITHUB, github.token("octocat"), device = DeviceDescriptor("Pixel", "android")))
            .ok<SignInResponse>()
        asMigrated()
        assertFailsWith<IllegalArgumentException> { checkDataKey(db, anotherKey()) }
        checkDataKey(db, serversKey)

        // An account that has only connected a tracker.
        TestPostgres.reset()
        val alice = signIn("alice").session.accessToken
        post(TrackerConnectionPaths.CONNECTIONS, TrackerConnectRequest("profile-1", "anilist", "", "anilist-access-0"), alice)
            .ok<TrackerConnection>()
        asMigrated()
        assertFailsWith<IllegalArgumentException> { checkDataKey(db, anotherKey()) }
        checkDataKey(db, serversKey)
    }

    @Test
    fun aKeyLostForGoodIsReplacedOnceItsCheckIsDeleted() = serverTest {
        aliceWithARecord()
        asMigrated()
        checkDataKey(db, serversKey)

        db.transaction { update("DELETE FROM data_key") }
        val replacement = anotherKey()
        checkDataKey(db, replacement)
        assertFailsWith<IllegalArgumentException> { checkDataKey(db, serversKey) }
        checkDataKey(db, replacement)
    }
}
