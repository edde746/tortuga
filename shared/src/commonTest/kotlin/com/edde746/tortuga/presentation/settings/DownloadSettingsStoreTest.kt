package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.downloads.DownloadLocation
import com.edde746.tortuga.downloads.DownloadManager
import com.edde746.tortuga.downloads.DownloadPlatform
import com.edde746.tortuga.downloads.DownloadsHarness
import com.edde746.tortuga.downloads.FILM
import com.edde746.tortuga.downloads.SHOW
import com.edde746.tortuga.downloads.cdnChoice
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.downloads.installDownloads
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Settings → Downloads over a real DownloadManager: this device's values, per platform, and Delete all. */
class DownloadSettingsStoreTest {
    private val text = T.settings.downloads

    /** Downloads on [h]'s graph over [platform] in place of the harness's own. */
    private fun TestScope.installOn(h: DownloadsHarness, platform: DownloadPlatform): DownloadManager =
        h.graph.installDownloads(platform, StandardTestDispatcher(testScheduler), SystemFileSystem, TimeSource.Monotonic).also { h.settle() }

    private fun DownloadsHarness.store(): DownloadSettingsStore =
        assertNotNull(graph.downloadSettingsStore(profileId)).also { settle() }

    @Test
    fun onAPhoneWifiOnlyIsOnUntilTurnedOffAndDownloadsAtOnceStayWithinOneToThree() = downloadsTest { h ->
        h.install()
        val store = h.store()
        var state = store.state.value
        assertFalse(state.loading)
        assertEquals(text.title, state.title)
        assertEquals(text.note, state.note)
        assertNull(state.location, "one location: nothing to choose")
        assertNull(state.folder)
        assertEquals(SettingToggleRow("wifiOnly", text.wifiOnly, text.wifiOnlyHint, checked = true), state.wifiOnly, "a phone's default")
        assertEquals(SettingToggleRow("autoRemoveWatched", text.autoRemoveWatched, text.autoRemoveWatchedHint, checked = false), state.autoRemoveWatched)

        store.toggle("wifiOnly", false)
        h.settle()
        assertEquals(false, h.data.deviceSettings.settings().downloadWifiOnly)
        assertEquals(false, store.state.value.wifiOnly?.checked)

        state = store.state.value
        assertEquals(1, state.atOnce.value)
        assertFalse(state.atOnce.canDecrease)
        store.stepAtOnce(up = false)
        h.settle()
        assertEquals(1, h.data.deviceSettings.settings().downloadsAtOnce)
        repeat(3) { store.stepAtOnce(up = true) }
        h.settle()
        state = store.state.value
        assertEquals(3, h.data.deviceSettings.settings().downloadsAtOnce)
        assertEquals(3, state.atOnce.value)
        assertFalse(state.atOnce.canIncrease)

        store.toggle("autoRemoveWatched", true)
        h.settle()
        assertTrue(h.data.deviceSettings.settings().downloadAutoRemoveWatched)
        assertTrue(store.state.value.autoRemoveWatched.checked)
    }

    @Test
    fun onAndroidTvWifiOnlyIsOffByDefault() = downloadsTest { h ->
        installOn(h, object : DownloadPlatform by h.platform {
            override val wifiOnlyDefault: Boolean? = false
        })
        assertEquals(false, h.store().state.value.wifiOnly?.checked)
    }

    @Test
    fun aDesktopChoosesAFolderAndHasNoWifiOnly() = downloadsTest { h ->
        installOn(h, object : DownloadPlatform by h.platform {
            override val choosesFolder: Boolean = true
            override val wifiOnlyDefault: Boolean? = null
        })
        val store = h.store()
        var state = store.state.value
        assertNull(state.wifiOnly)
        assertNull(state.location)
        assertEquals(DownloadFolderRow(text.location.title, text.location.hint, h.root.toString(), text.location.change), state.folder)

        val picked = h.file("Picked").toString()
        store.setFolder(" ")
        store.setFolder(picked)
        h.settle()
        state = store.state.value
        assertEquals(DownloadLocation.folder(picked).id, h.data.deviceSettings.settings().downloadLocation)
        assertEquals(picked, state.folder?.path)
    }

    @Test
    fun anAndroidDeviceWithAnSdCardChoosesWhereDownloadsGo() = downloadsTest { h ->
        val internal = h.platform.locationList.single()
        val card = DownloadLocation("volume:1234-ABCD", h.file("card").toString(), text.location.external, removable = true)
        h.platform.locationList = listOf(internal, card)
        val manager = h.install()
        val store = h.store()
        var location = assertNotNull(store.state.value.location)
        assertEquals(text.location.title, location.title)
        assertEquals(internal.label, location.valueLabel)
        assertEquals(listOf(SettingChoiceOption(internal.id, internal.label, true), SettingChoiceOption(card.id, card.label, false)), location.options)

        store.choose(location.id, "volume:gone")
        store.choose(location.id, card.id)
        h.settle()
        location = assertNotNull(store.state.value.location)
        assertEquals(card.label, location.valueLabel)
        assertEquals(listOf(false, true), location.options.map { it.selected })
        assertEquals(card.id, manager.locations.value.selected, "new downloads go there")
    }

    @Test
    fun theSubtitleLanguagesSavedAreTheProfilesUntilSomeAreChosenHere() = downloadsTest { h ->
        h.data.settings.set(h.profileId, ProfileSetting.SubtitleLanguages, listOf("eng", "jpn"))
        h.install()
        val store = h.store()
        val separator = T.settings.listSeparator
        var state = store.state.value
        assertEquals(text.subtitleLanguagesProfile("English${separator}Japanese"), state.subtitleLanguages.valueLabel)
        assertNull(state.useProfileLanguagesText)

        store.openLanguages()
        assertEquals(listOf("eng", "jpn"), store.state.value.languagePicker?.chosen?.map { it.code })
        store.addLanguage("pob")
        h.settle()
        assertEquals(listOf("eng", "jpn", "pob"), h.data.deviceSettings.settings().downloadSubtitleLanguages)
        state = store.state.value
        assertEquals("English${separator}Japanese${separator}Portuguese (Brazil)", state.subtitleLanguages.valueLabel)
        assertEquals(text.useProfileLanguages, state.useProfileLanguagesText)

        // This device's list no longer follows the profile's.
        h.data.settings.set(h.profileId, ProfileSetting.SubtitleLanguages, listOf("spa"))
        h.settle()
        store.moveLanguage("pob", up = true)
        h.settle()
        store.removeLanguage("eng")
        h.settle()
        assertEquals(listOf("pob", "jpn"), store.state.value.languagePicker?.chosen?.map { it.code })
        assertEquals(listOf("pob", "jpn"), h.data.deviceSettings.settings().downloadSubtitleLanguages)

        store.useProfileLanguages()
        h.settle()
        state = store.state.value
        assertNull(h.data.deviceSettings.settings().downloadSubtitleLanguages)
        assertEquals(text.subtitleLanguagesProfile("Spanish"), state.subtitleLanguages.valueLabel)
        assertEquals(listOf("spa"), state.languagePicker?.chosen?.map { it.code })
        store.closeLanguages()
        assertNull(store.state.value.languagePicker)
    }

    @Test
    fun deleteAllAsksFirstThenDeletesEveryProfilesDownloadsOnThisDevice() = downloadsTest { h ->
        h.serveCdn(lcgBytes(50_000, seed = 17))
        val other = h.createProfile("Sam")
        val manager = h.install()
        val store = h.store()
        assertEquals(DeleteAllDownloadsRow(text.deleteAll, enabled = false), store.state.value.deleteAll, "nothing to delete yet")
        store.requestDeleteAll()
        assertNull(store.state.value.deleteAllDialog, "nothing to delete, nothing asked")

        manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice())
        manager.enqueue(other, SHOW, "tt0903747:1:1", cdnChoice("1-1.mkv"))
        h.settle()
        assertEquals(2, manager.all.value.size)
        assertTrue(store.state.value.deleteAll.enabled)
        val files = listOf(
            assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id)).path,
            assertNotNull(manager.localVideo(other, SHOW.id, "tt0903747:1:1")).path,
        )

        store.confirmDeleteAll()
        h.settle()
        assertEquals(2, manager.all.value.size, "a confirmation nobody was asked deletes nothing")

        store.requestDeleteAll()
        assertEquals(
            DeleteAllDownloadsDialog(text.deleteAllTitle, text.deleteAllText, text.deleteAllConfirm, T.settings.cancel),
            store.state.value.deleteAllDialog,
        )
        store.dismissDialog()
        h.settle()
        assertNull(store.state.value.deleteAllDialog)
        assertEquals(2, manager.all.value.size, "dismissed: nothing deleted")

        store.requestDeleteAll()
        store.confirmDeleteAll()
        h.settle()
        assertNull(store.state.value.deleteAllDialog)
        assertEquals(emptyList(), manager.all.value)
        assertFalse(store.state.value.deleteAll.enabled)
        assertEquals(listOf(false, false), files.map { SystemFileSystem.exists(Path(it)) }, "every profile's files")
    }
}
