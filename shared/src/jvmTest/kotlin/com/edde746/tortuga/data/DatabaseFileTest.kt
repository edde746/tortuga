package com.edde746.tortuga.data

import com.edde746.tortuga.data.settings.ProfileSetting
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseFileTest {
    @Test
    fun reopeningKeepsTheDataAndTheClockMonotonic() = runTest {
        val file = Files.createTempDirectory("tortuga-db").resolve("nested").resolve(DATABASE_FILE_NAME)
        val clock = TestClock()

        val first = testData(clock, DatabaseFactory(file))
        val profile = first.profiles.create("Alex", null)
        first.settings.set(profile.id, ProfileSetting.BingeWatching, false)
        val lastHlc = first.sync.dirtyRecords(null, 10).maxOf { it.hlc }
        first.close()

        // The wall clock moved back an hour while the app was closed.
        clock.now -= 3_600_000
        val second = testData(clock, DatabaseFactory(file))
        assertEquals(listOf(profile), second.profiles.profiles())
        assertEquals(false, second.settings.settings(profile.id).bingeWatching)
        second.settings.set(profile.id, ProfileSetting.BingeWatching, true)
        val next = second.sync.dirtyRecords(null, 10).maxOf { it.hlc }
        assertTrue(next > lastHlc, "$next after $lastHlc")
        assertEquals(lastHlc.node, next.node)
        second.close()
    }
}
