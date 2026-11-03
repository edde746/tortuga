@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.sync.engine

import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.SignInResult
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The sync engine's worker when its read of the local changes fails (a database error, not the server's). */
class SyncEngineReadFailureTest {
    @Test
    fun aCycleWhoseReadOfTheLocalChangesFailsIsRetriedAndPushes() = runTest {
        val directory = Files.createTempDirectory("tortuga-sync")
        val file = directory.resolve("tortuga.db")
        val server = FakeSyncServer()
        try {
            TestDevice("Phone", server, this, backgroundScope, database = DatabaseFactory(file)).use { phone ->
                phone.account.restore()
                assertEquals(SignInResult.NEW_ACCOUNT, phone.account.signIn(server.serverUrl) { TestDevice.credential() })
                phone.data.profiles.create("Alex", null)
                runCurrent()

                // Another connection takes a table the read of the local changes needs away: that read fails.
                DriverManager.getConnection("jdbc:sqlite:$file").use { other ->
                    other.createStatement().use { it.execute("ALTER TABLE stream_history RENAME TO stream_history_away") }
                    phone.engine.start()
                    phone.engine.flushNow()
                    runCurrent()
                    assertIs<SyncStatus.Kind.Error>(phone.engine.status.value.kind)
                    other.createStatement().use { it.execute("ALTER TABLE stream_history_away RENAME TO stream_history") }
                }
                server.pushes.clear()

                advanceTimeBy(SyncEngineConfig().maxRetryMillis)
                runCurrent()

                assertTrue(server.pushes.isNotEmpty(), "the retry pushed")
                assertTrue(phone.data.sync.dirtyRecords(null, 1).isEmpty())
                assertEquals(SyncStatus.Kind.Idle, phone.engine.status.value.kind)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
