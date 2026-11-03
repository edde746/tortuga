package com.edde746.tortuga.data

import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.data.addon.InstalledAddon
import com.edde746.tortuga.data.sync.AddonListBody
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.data.sync.SyncRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonRepositoryTest {
    private val data = testData()
    private val profile = "p1"
    private val cinemeta = "https://v3-cinemeta.strem.io/manifest.json"
    private val subtitles = "https://opensubtitles-v3.strem.io/manifest.json"
    private val streams = "https://addon.example/manifest.json"

    @AfterTest
    fun close() = data.close()

    private suspend fun urls() = data.addons.addons(profile).map { it.transportUrl }

    @Test
    fun installAppendsAndUpgradeKeepsThePosition() = runTest {
        data.addons.install(profile, cinemeta, """{"version":"3.0.0"}""")
        data.addons.install(profile, subtitles, "{}")
        data.addons.install(profile, cinemeta, """{"version":"3.1.0"}""")
        assertEquals(
            listOf(InstalledAddon(cinemeta, """{"version":"3.1.0"}""", 0), InstalledAddon(subtitles, "{}", 1)),
            data.addons.observeAddons(profile).first(),
        )
    }

    @Test
    fun removeCompactsPositions() = runTest {
        listOf(cinemeta, subtitles, streams).forEach { data.addons.install(profile, it, "{}") }
        assertTrue(data.addons.remove(profile, subtitles))
        assertFalse(data.addons.remove(profile, subtitles))
        assertEquals(listOf(0, 1), data.addons.addons(profile).map { it.position })
        assertEquals(listOf(cinemeta, streams), urls())
    }

    @Test
    fun reorderNeedsEveryAddonOnce() = runTest {
        listOf(cinemeta, subtitles, streams).forEach { data.addons.install(profile, it, "{}") }
        data.addons.reorder(profile, listOf(streams, cinemeta, subtitles))
        assertEquals(listOf(streams, cinemeta, subtitles), urls())
        assertFailsWith<IllegalArgumentException> { data.addons.reorder(profile, listOf(streams, cinemeta)) }
        assertFailsWith<IllegalArgumentException> { data.addons.reorder(profile, listOf(streams, cinemeta, cinemeta)) }
        assertEquals(listOf(streams, cinemeta, subtitles), urls())
    }

    @Test
    fun replaceAllSetsTheWholeListAndRefusesDuplicates() = runTest {
        data.addons.install(profile, streams, "{}")
        data.addons.replaceAll(profile, listOf(AddonDescriptor(cinemeta, "{}"), AddonDescriptor(subtitles, "{}")))
        assertEquals(listOf(cinemeta, subtitles), urls())
        assertFailsWith<IllegalArgumentException> {
            data.addons.replaceAll(profile, listOf(AddonDescriptor(cinemeta, "{}"), AddonDescriptor(cinemeta, "[]")))
        }
    }

    @Test
    fun theWholeListIsOneSyncRecordPerProfile() = runTest {
        listOf(cinemeta, subtitles, streams).forEach { data.addons.install(profile, it, "{}") }
        data.addons.reorder(profile, listOf(subtitles, streams, cinemeta))
        data.addons.install("p2", cinemeta, "{}")
        val records = data.sync.dirtyRecords(null, 100)
        assertEquals(2, records.size)
        val payload = records.single { it.recordId == RecordKey.AddonList(profile).recordId() }.payload
        val body = SyncRecord.decode(Json.parseToJsonElement(payload).jsonObject).body as AddonListBody
        // In the list's order.
        assertEquals(listOf(subtitles, streams, cinemeta), body.addons.map { it.transportUrl })
    }

    @Test
    fun anUnchangedListWritesNothing() = runTest {
        data.addons.install(profile, cinemeta, "{}")
        val before = data.sync.dirtyRecords(null, 10).single().hlc
        data.addons.install(profile, cinemeta, "{}")
        data.addons.reorder(profile, listOf(cinemeta))
        assertEquals(before, data.sync.dirtyRecords(null, 10).single().hlc)
    }
}
