@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.profiles

import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.presentation.account.await
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A save whose writes fail part-way, through a database that refuses one of them. */
class ProfileEditorRetryTest {
    private class Host : ProfileEditorHost {
        var closed = 0
        override fun closeEditor() {
            closed++
        }
        override fun profileDeleted(profileId: String) = Unit
    }

    @Test
    fun aNewProfileWhosePinFailedToSaveIsNotCreatedTwice() = runTest {
        val file = Files.createTempFile("tortuga-editor", ".db")
        LocalData(DatabaseFactory(file), { 1_790_000_000_000L }, StandardTestDispatcher(testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS).use { data ->
            fun sql(statement: String) = DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().execute(statement) }
            // The profile's row goes in; storing its PIN fails, as a full disk would make it.
            sql("CREATE TRIGGER refuse_pin BEFORE INSERT ON profile WHEN NEW.pin_hash IS NOT NULL BEGIN SELECT RAISE(ABORT, 'disk full'); END")
            val host = Host()
            val store = ProfileEditorStore(
                null,
                data.profiles,
                data.settings,
                { name, avatar, kids, homeCatalogs -> data.profiles.create(name, avatar, kids, homeCatalogs) },
                { emptyList() },
                {},
                host,
                backgroundScope,
            )
            store.setName("Wednesday")
            store.startPinSetup()
            repeat(2) { "1357".forEach { store.pinDigit(it.digitToInt()) } }
            store.save()
            runCurrent()
            assertEquals(T.profiles.editor.saveFailed, store.state.await { !it.saving }.error)
            assertEquals(0, host.closed)

            sql("DROP TRIGGER refuse_pin")
            store.save()
            runCurrent()
            assertEquals(1, host.closed)
            val profile = data.profiles.profiles().single()
            assertEquals("Wednesday", profile.name)
            assertTrue(data.profiles.verifyPin(profile.id, "1357"))
        }
        Files.deleteIfExists(file)
    }
}
