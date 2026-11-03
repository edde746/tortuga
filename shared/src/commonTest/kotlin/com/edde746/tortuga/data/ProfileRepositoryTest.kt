package com.edde746.tortuga.data

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.sync.PushAck
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.movieMark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileRepositoryTest {
    private val clock = TestClock()
    private val data = testData(clock)

    @AfterTest
    fun close() = data.close()

    @Test
    fun profilesListInCreationOrder() = runTest {
        val first = data.profiles.create("  Alex ", avatar = "fox")
        clock.now += 1
        val second = data.profiles.create("Kids", avatar = null)
        assertEquals("Alex", first.name)
        assertEquals(listOf(first, second), data.profiles.observeProfiles().first())
        assertFailsWith<IllegalArgumentException> { data.profiles.create("   ", null) }
    }

    @Test
    fun renameAndAvatarChangeTheProfile() = runTest {
        val profile = data.profiles.create("Alex", null)
        data.profiles.rename(profile.id, "Sam")
        data.profiles.setAvatar(profile.id, "owl")
        assertEquals(profile.copy(name = "Sam", avatar = "owl"), data.profiles.profile(profile.id))
    }

    @Test
    fun aPinIsVerifiedAndNeverStoredOrSyncedAsItsDigits() = runTest {
        val profile = data.profiles.create("Alex", null)
        assertFalse(data.profiles.verifyPin(profile.id, "1234"))

        data.profiles.setPin(profile.id, "1234")
        assertTrue(data.profiles.profile(profile.id)!!.hasPin)
        assertTrue(data.profiles.verifyPin(profile.id, "1234"))
        assertFalse(data.profiles.verifyPin(profile.id, "4321"))
        assertFalse(data.profiles.verifyPin(profile.id, "12345"))
        // What syncs is the PIN's hash, not the PIN.
        val payload = Json.parseToJsonElement(data.sync.dirtyRecords(null, 10).single().payload)
        assertTrue(payload.primitives().none { it.content == "1234" }, "$payload")

        data.profiles.setPin(profile.id, null)
        assertFalse(data.profiles.profile(profile.id)!!.hasPin)
        assertFalse(data.profiles.verifyPin(profile.id, "1234"))
    }

    @Test
    fun theSamePinOnTwoProfilesStoresDifferentHashesAndEachUnlocksOnlyItsOwn() = runTest {
        val a = data.profiles.create("A", null)
        val b = data.profiles.create("B", null)
        data.profiles.setPin(a.id, "1111")
        data.profiles.setPin(b.id, "1111")
        val hashes = data.sync.dirtyRecords(null, 10).map { record ->
            Json.parseToJsonElement(record.payload).jsonObject["body"]!!.jsonObject["pinHash"]!!.jsonPrimitive.content
        }
        assertEquals(2, hashes.toSet().size, "salted: $hashes")
        assertTrue(data.profiles.verifyPin(a.id, "1111"))
        assertTrue(data.profiles.verifyPin(b.id, "1111"))

        data.profiles.setPin(b.id, "2222")
        assertTrue(data.profiles.verifyPin(b.id, "2222"))
        assertFalse(data.profiles.verifyPin(b.id, "1111"))
        assertTrue(data.profiles.verifyPin(a.id, "1111"))
    }

    @Test
    fun deleteTombstonesEverythingTheProfileSyncsAndDropsItsDeviceData() = runTest {
        val kept = data.profiles.create("Kept", null)
        val gone = data.profiles.create("Gone", null)
        for (profile in listOf(kept, gone)) {
            data.addons.install(profile.id, "https://v3-cinemeta.strem.io/manifest.json", """{"id":"com.linvo.cinemeta"}""")
            data.library.addToLibrary(profile.id, movie())
            data.streamHistory.record(profile.id, "tt0111161", "tt0111161", """{"url":"https://cdn.example/a.mkv"}""", "https://addon.example/manifest.json")
            data.settings.set(profile.id, ProfileSetting.BingeWatching, false)
            val binding = data.trackerAccounts.connect(profile.id, TrackerService.TRAKT, "alex", listOf("public"), 1).connectedAt
            data.trackerWriteQueue.enqueue(profile.id, TrackerService.TRAKT, binding, "movie:tt0111161", movieMark("tt0111161"), null, clock.now)
        }
        data.deviceSettings.set(DeviceSetting.ActiveProfileId, gone.id)
        val pushed = data.sync.dirtyRecords(null, 100)
        data.sync.markPushed(pushed.map { PushAck(it.recordId, it.hlc, 1) })

        data.profiles.delete(gone.id)

        assertEquals(listOf(kept), data.profiles.profiles())
        assertEquals(emptyList(), data.addons.addons(gone.id))
        assertNull(data.library.item(gone.id, "tt0111161"))
        assertNull(data.streamHistory.lastStream(gone.id, "tt0111161", "tt0111161"))
        assertEquals(true, data.settings.settings(gone.id).bingeWatching)
        assertEquals(emptyList(), data.trackerAccounts.accounts(gone.id))
        assertEquals(emptyList(), data.trackerWriteQueue.pending(gone.id))
        assertNull(data.deviceSettings.settings().activeProfileId)

        // Five tombstones to push (profile, addon list, library item, stream, setting), all based on version 1.
        val tombstones = data.sync.dirtyRecords(null, 100)
        assertEquals(5, tombstones.size)
        assertTrue(tombstones.all { it.deleted && it.baseVersion == 1L })
        // The other profile is untouched.
        assertEquals(1, data.addons.addons(kept.id).size)
        assertEquals(1, data.trackerWriteQueue.pending(kept.id).size)
    }
}

private fun JsonElement.primitives(): List<JsonPrimitive> = when (this) {
    is JsonPrimitive -> listOf(this)
    is JsonObject -> values.flatMap { it.primitives() }
    is JsonArray -> flatMap { it.primitives() }
}
