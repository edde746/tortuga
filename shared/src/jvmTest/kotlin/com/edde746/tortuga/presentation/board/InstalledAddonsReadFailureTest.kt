package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.addons.DefaultAddons
import com.edde746.tortuga.app.addonRegistry
import com.edde746.tortuga.app.testGraph
import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.presentation.settings.HomeSettingsBody
import com.edde746.tortuga.presentation.settings.homeSettingsStore
import com.edde746.tortuga.strings.T
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/** The installed addons when the first read of them fails without finding the database busy (a table gone). */
class InstalledAddonsReadFailureTest {
    @Test
    fun aRegistryFailureIsReturnedAndTheScreenEndsItsLoadingSayingSo() = runTest {
        val directory = Files.createTempDirectory("tortuga-addons")
        val file = directory.resolve("tortuga.db")
        val clock = TestClock()
        val data = LocalData(DatabaseFactory(file), { clock.now }, StandardTestDispatcher(testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        val graph = testGraph(data)
        try {
            val profile = data.profiles.create("Ann", null)
            val stored = DefaultAddons.descriptors.map { AddonDescriptor(it.transportUrl, DefaultAddons.manifestJson.getValue(it.transportUrl)) }
            data.addons.replaceAll(profile.id, stored)

            // Another connection takes the addon table away while the list is read.
            val store = DriverManager.getConnection("jdbc:sqlite:$file").use { other ->
                other.createStatement().use { it.execute("ALTER TABLE addon RENAME TO addon_away") }
                val read = withTimeoutOrNull(1.minutes) { runCatching { graph.addonRegistry(profile.id).current() } }
                assertTrue(assertNotNull(read, "the read ends instead of reading again").isFailure)

                val store = graph.homeSettingsStore(profile.id, offersFiles = false)
                runCurrent()
                other.createStatement().use { it.execute("ALTER TABLE addon_away RENAME TO addon") }
                store
            }
            advanceTimeBy(1.minutes)
            runCurrent()

            // Once: with the table back, the page still says so instead of reading the list again.
            val state = store.state.value
            assertEquals(HomeSettingsBody.Empty(T.addons.unreadable), state.body)
            store.release()
        } finally {
            graph.close()
            directory.toFile().deleteRecursively()
        }
    }
}
