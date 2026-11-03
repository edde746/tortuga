@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppearanceSettingsStoreTest {
    private class Host : AppearanceSettingsHost {
        val languages = mutableListOf<String?>()
        override fun interfaceLanguageChanged(tag: String?) {
            languages += tag
        }
    }

    private class Fixture(scope: TestScope, kids: Boolean) {
        val data = LocalData(
            inMemoryDatabase(), { 1_790_000_000_000L },
            StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        )
        val host = Host()
        val store = AppearanceSettingsStore("p", kids, data.settings, host, scope.backgroundScope)
        val state get() = store.state.value
    }

    private fun TestScope.fixture(kids: Boolean = false): Fixture = Fixture(this, kids).also { runCurrent() }

    /** The page's rows' ids, in order. */
    private val AppearanceSettingsUiState.rowIds: List<String>
        get() = listOfNotNull(
            interfaceLanguage.id, blurUnwatched.id, hideWatched.id, hidePosterCaptions.id, posterTemplate?.let { "posterTemplate" },
        )

    @Test
    fun theInterfaceLanguageLeadsAppearanceOnEveryProfileAndPlaybackNoLongerHasIt() = runTest {
        val adult = fixture(kids = false)
        assertEquals(listOf("interfaceLanguage", "blurUnwatched", "hideWatched", "hidePosterCaptions", "posterTemplate"), adult.state.rowIds)
        val kids = fixture(kids = true)
        assertEquals(listOf("interfaceLanguage", "blurUnwatched", "hideWatched", "hidePosterCaptions"), kids.state.rowIds)

        kids.store.choose("interfaceLanguage", "de")
        runCurrent()
        assertEquals(listOf<String?>("de"), kids.host.languages, "a kids profile picks its language, as it did in Playback")
        assertEquals("de", kids.data.settings.settings("p").interfaceLanguage)

        val playback = PlaybackSettingsStore("p", adult.data.settings, InputProfile.DESKTOP, playerChoice = false, backgroundScope)
        runCurrent()
        val choices = playback.state.value.sections.flatMap { it.rows }.filterIsInstance<PlaybackSettingRow.Choice>().map { it.row.id }
        assertFalse("interfaceLanguage" in choices, "$choices")
        adult.data.close()
        kids.data.close()
    }

    @Test
    fun theInterfaceLanguageIsOneOfTheTranslationsOrTheSystems() = runTest {
        val f = fixture()
        val row = f.state.interfaceLanguage
        assertEquals(AppearanceSettingsStore.SYSTEM_LANGUAGE, row.options.first().id)
        assertTrue(row.options.first().selected)
        assertTrue(row.options.any { it.id == "de" && it.label == "Deutsch" })

        f.store.choose("interfaceLanguage", "de")
        runCurrent()
        assertEquals(listOf<String?>("de"), f.host.languages)
        assertEquals("de", f.data.settings.settings("p").interfaceLanguage)
        assertEquals("Deutsch", f.state.interfaceLanguage.valueLabel)

        f.store.choose("interfaceLanguage", "xx")
        runCurrent()
        assertEquals(listOf<String?>("de"), f.host.languages, "a language without a translation isn't taken")

        f.store.choose("interfaceLanguage", AppearanceSettingsStore.SYSTEM_LANGUAGE)
        runCurrent()
        assertEquals(listOf("de", null), f.host.languages)
        assertNull(f.data.settings.settings("p").interfaceLanguage)

        // Stored by a build with more translations (synced): the row shows its tag, no option selected.
        f.data.settings.set("p", ProfileSetting.InterfaceLanguage, "xx")
        runCurrent()
        assertEquals("xx", f.state.interfaceLanguage.valueLabel)
        assertTrue(f.state.interfaceLanguage.options.none { it.selected })
        f.data.close()
    }

    /** The languages are named in their own words, in the order the interface language's alphabet gives them. */
    @Test
    fun theLanguagesSortAsTheInterfaceLanguageReads() = runTest {
        try {
            T.locale = "fr"
            val f = fixture()
            val names = f.state.interfaceLanguage.options.drop(1).map { it.label }
            assertTrue(names.indexOf("Čeština") in 0..<names.indexOf("Deutsch"), "Č among the C's: $names")
            f.data.close()
        } finally {
            T.locale = "en"
        }
    }

    @Test
    fun theSwitchesAreStoredOnTheProfileOnceReadAndFollowSyncedChanges() = runTest {
        val f = fixture(kids = true)
        for (id in listOf("hideWatched", "hidePosterCaptions")) {
            val row = if (id == "hideWatched") f.state.hideWatched else f.state.hidePosterCaptions
            assertTrue(row.enabled && !row.checked, "$id: off by default, taking changes once read")
            f.store.toggle(id, true)
            runCurrent()
        }
        val stored = f.data.settings.settings("p")
        assertTrue(stored.hideWatched && stored.hidePosterCaptions)
        assertTrue(f.state.hideWatched.checked && f.state.hidePosterCaptions.checked)

        f.data.settings.set("p", ProfileSetting.HideWatched, false)
        runCurrent()
        assertFalse(f.state.hideWatched.checked, "a change synced from another device shows up")
        f.data.close()
    }
}
