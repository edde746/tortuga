package com.edde746.tortuga.data

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.data.sync.PushAck
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsRepositoryTest {
    private val data = testData()
    private val p = "p1"

    @AfterTest
    fun close() = data.close()

    @Test
    fun defaults() = runTest {
        val settings = data.settings.settings(p)
        assertEquals(ProfileSettings.DEFAULT, settings)
        assertEquals(70, settings.watchedThresholdPercent)
        assertEquals(emptyList(), settings.audioLanguages)
        assertEquals(true, settings.bingeWatching)
        assertEquals(10, settings.upNextCountdownSeconds)
        assertEquals(DeviceSettings.DEFAULT, data.deviceSettings.settings())
        assertEquals(true, DeviceSettings.DEFAULT.hardwareDecoding)
    }

    @Test
    fun valuesAreCleanedByTheirRules() = runTest {
        data.settings.set(p, ProfileSetting.WatchedThresholdPercent, 250)
        data.settings.set(p, ProfileSetting.UpNextCountdownSeconds, -3)
        data.settings.set(p, ProfileSetting.AudioLanguages, listOf(" JPN", "eng", "", "jpn"))
        data.settings.set(p, ProfileSetting.InterfaceLanguage, "  ")
        val settings = data.settings.observe(p).first()
        assertEquals(100, settings.watchedThresholdPercent)
        assertEquals(0, settings.upNextCountdownSeconds)
        assertEquals(listOf("jpn", "eng"), settings.audioLanguages)
        assertEquals(null, settings.interfaceLanguage)
    }

    @Test
    fun settingsArePerProfileAndSyncPerKey() = runTest {
        data.settings.set(p, ProfileSetting.BingeWatching, false)
        data.settings.set(p, ProfileSetting.SubtitleLanguages, listOf("eng"))
        data.settings.set("p2", ProfileSetting.SubtitleLanguages, listOf("spa"))
        assertEquals(listOf("eng"), data.settings.settings(p).subtitleLanguages)
        assertEquals(listOf("spa"), data.settings.settings("p2").subtitleLanguages)
        assertEquals(true, data.settings.settings("p2").bingeWatching)
        assertEquals(3, data.sync.dirtyRecords(null, 10).size)
    }

    @Test
    fun resetSyncsATombstoneAndFallsBackToTheDefault() = runTest {
        data.settings.set(p, ProfileSetting.SeekShortSeconds, 5)
        val set = data.sync.dirtyRecords(null, 10).single()
        data.sync.markPushed(listOf(PushAck(set.recordId, set.hlc, 3)))

        data.settings.reset(p, ProfileSetting.SeekShortSeconds)
        assertEquals(10, data.settings.settings(p).seekShortSeconds)
        val reset = data.sync.dirtyRecords(null, 10).single()
        assertTrue(reset.deleted)
        assertEquals(set.recordId, reset.recordId)
        assertEquals(3L, reset.baseVersion)
    }

    @Test
    fun deviceSettingsNeverSync() = runTest {
        data.deviceSettings.set(DeviceSetting.AudioPassthrough, true)
        data.deviceSettings.set(DeviceSetting.CacheSizeMegabytes, 100_000)
        data.deviceSettings.set(DeviceSetting.ActiveProfileId, p)
        val settings = data.deviceSettings.observe().first()
        assertEquals(true, settings.audioPassthrough)
        assertEquals(4096, settings.cacheSizeMegabytes)
        assertEquals(p, settings.activeProfileId)
        assertEquals(emptyList(), data.sync.dirtyRecords(null, 10))

        data.deviceSettings.reset(DeviceSetting.CacheSizeMegabytes)
        assertEquals(null, data.deviceSettings.settings().cacheSizeMegabytes)
    }
}
