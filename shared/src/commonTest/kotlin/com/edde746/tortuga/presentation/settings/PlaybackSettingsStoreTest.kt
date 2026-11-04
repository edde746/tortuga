@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.SkipMode
import com.edde746.tortuga.data.settings.StreamQuality
import com.edde746.tortuga.data.settings.SubtitleMode
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.VideoClickAction
import com.edde746.tortuga.presentation.player.localeOrder
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSettingsStoreTest {
    private class Fixture(scope: TestScope, profile: InputProfile, playerChoice: Boolean) {
        val data = LocalData(
            inMemoryDatabase(), { 1_790_000_000_000L },
            StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        )
        val store = PlaybackSettingsStore("p", data.settings, profile, playerChoice, scope.backgroundScope)
        val state get() = store.state.value
    }

    private fun TestScope.fixture(profile: InputProfile = InputProfile.DESKTOP, playerChoice: Boolean = true): Fixture =
        Fixture(this, profile, playerChoice).also { runCurrent() }

    private val PlaybackSettingsUiState.rows get() = sections.flatMap { it.rows }

    private inline fun <reified R : PlaybackSettingRow> PlaybackSettingsUiState.row(): R? = rows.filterIsInstance<R>().singleOrNull()

    private fun PlaybackSettingsUiState.stepper(field: PlaybackNumber) = rows.filterIsInstance<PlaybackSettingRow.Stepper>().single { it.field == field }.row

    /** The choice row [id], or null where the pane has none. */
    private fun PlaybackSettingsUiState.choice(id: String) = rows.filterIsInstance<PlaybackSettingRow.Choice>().singleOrNull { it.row.id == id }?.row

    private fun PlaybackSettingsUiState.toggle(id: String) = rows.filterIsInstance<PlaybackSettingRow.Toggle>().single { it.row.id == id }.row

    private fun PlaybackSettingsUiState.languages(kind: LanguageListKind) = rows.filterIsInstance<PlaybackSettingRow.Languages>().single { it.kind == kind }.row

    private val PlaybackSettingsUiState.showSubtitles get() = assertNotNull(choice("showSubtitles"))

    /**
     * This device's Player heads the pane in a group of its own, above the profile's synced groups, so no group mixes
     * the two; a device without player apps (or a kids profile) has no Player.
     */
    @Test
    fun theDevicesPlayerHeadsThePaneAboveTheProfilesGroups() = runTest {
        val f = fixture()
        val profiles = listOf(PlaybackSettingsGroup.LANGUAGES, PlaybackSettingsGroup.PLAYBACK, PlaybackSettingsGroup.SKIPPING, PlaybackSettingsGroup.STREAMS)
        assertEquals(listOf(PlaybackSettingsGroup.PLAYER) + profiles, f.state.sections.map { it.group })
        val player = f.state.sections.first()
        assertEquals(listOf<PlaybackSettingRow>(PlaybackSettingRow.Player), player.rows)
        assertNull(player.header)
        assertEquals(1, f.state.rows.count { it == PlaybackSettingRow.Player })
        f.data.close()

        val without = fixture(playerChoice = false)
        assertEquals(profiles, without.state.sections.map { it.group })
        assertNull(without.state.row<PlaybackSettingRow.Player>())
        without.data.close()
    }

    @Test
    fun steppersShowTheValueWithinTheirBounds() = runTest {
        val f = fixture()
        assertFalse(f.state.loading)
        val threshold = f.state.stepper(PlaybackNumber.WATCHED_THRESHOLD)
        assertEquals(70, threshold.value)
        assertEquals(T.settings.value.percent("70"), threshold.valueLabel)
        assertEquals(Triple(50, 100, 5), Triple(threshold.min, threshold.max, threshold.step))
        assertTrue(threshold.canDecrease && threshold.canIncrease)
        assertEquals(Triple(0, 60, 5), f.state.stepper(PlaybackNumber.UP_NEXT_COUNTDOWN).let { Triple(it.min, it.max, it.step) })
        assertEquals(T.settings.value.seconds(10), f.state.stepper(PlaybackNumber.SEEK_SHORT).valueLabel)
        f.data.close()
    }

    @Test
    fun skippingShowsTheButtonByDefaultAndEachKindIsSetOnItsOwn() = runTest {
        val f = fixture()
        val skipping = f.state.sections.single { it.group == PlaybackSettingsGroup.SKIPPING }
        assertEquals(T.settings.playback.skipping.header, skipping.header)
        assertEquals(listOf("skipIntros", "skipRecaps", "skipCredits"), skipping.rows.map { (it as PlaybackSettingRow.Choice).row.id })
        for (row in skipping.rows.map { (it as PlaybackSettingRow.Choice).row }) {
            assertEquals(T.settings.playback.skipping.button, row.valueLabel)
            assertEquals(listOf("OFF", "BUTTON", "AUTO"), row.options.map { it.id })
            assertEquals(listOf("Off", "Show button", "Skip automatically"), row.options.map { it.label })
        }
        f.store.choose("skipIntros", "AUTO")
        f.store.choose("skipCredits", "OFF")
        f.store.choose("skipRecaps", "nonsense")
        runCurrent()
        val stored = f.data.settings.settings("p")
        assertEquals(Triple(SkipMode.AUTO, SkipMode.BUTTON, SkipMode.OFF), Triple(stored.skipIntros, stored.skipRecaps, stored.skipCredits))
        assertEquals("Skip automatically", f.state.choice("skipIntros")?.valueLabel)
        assertEquals("Off", f.state.choice("skipCredits")?.valueLabel)
        assertEquals(true, f.state.choice("skipIntros")?.options?.single { it.id == "AUTO" }?.selected)
        f.data.close()
    }

    @Test
    fun aClickOnTheVideoPausesByDefaultAndTheRowShowsOnlyWhereAMouseClicksIt() = runTest {
        val f = fixture()
        val row = assertNotNull(f.state.choice("videoClick"))
        assertEquals(listOf("PAUSE", "CONTROLS"), row.options.map { it.id })
        assertEquals(listOf("Pause and play", "Show or hide the controls"), row.options.map { it.label })
        assertEquals("Pause and play", row.valueLabel)

        f.store.choose("videoClick", "CONTROLS")
        f.store.choose("videoClick", "nonsense")
        runCurrent()
        assertEquals(VideoClickAction.CONTROLS, f.data.settings.settings("p").videoClick)
        assertEquals("Show or hide the controls", f.state.choice("videoClick")?.valueLabel)
        f.data.close()

        // A tap always shows or hides the controls, and a remote has no clicks: no row.
        for (profile in listOf(InputProfile.TOUCH, InputProfile.TV)) {
            val other = fixture(profile)
            assertNull(other.state.choice("videoClick"), "$profile")
            other.data.close()
        }
    }

    @Test
    fun subtitlesShowAlwaysOnceChosenOnEveryKindOfDevice() = runTest {
        for (profile in InputProfile.entries) {
            val f = fixture(profile)
            assertEquals(listOf("Follow the audio", "Always"), f.state.showSubtitles.options.map { it.label }, "$profile")
            f.store.choose("showSubtitles", "ALWAYS")
            f.store.choose("showSubtitles", "nonsense")
            runCurrent()
            assertEquals(SubtitleMode.ALWAYS, f.data.settings.settings("p").showSubtitles, "$profile")
            assertEquals("Always", f.state.showSubtitles.valueLabel, "$profile")
            f.data.close()
        }
    }

    @Test
    fun quickStepsAddUpAndStopAtTheBounds() = runTest {
        val f = fixture()
        // Steps sent faster than the screen follows the stored value (a held D-pad key) each start from the last.
        repeat(6) { f.store.stepNumber(PlaybackNumber.WATCHED_THRESHOLD, up = true) }
        runCurrent()
        assertEquals(100, f.data.settings.settings("p").watchedThresholdPercent)
        assertFalse(f.state.stepper(PlaybackNumber.WATCHED_THRESHOLD).canIncrease)
        f.store.stepNumber(PlaybackNumber.WATCHED_THRESHOLD, up = true)
        runCurrent()
        assertEquals(100, f.data.settings.settings("p").watchedThresholdPercent)

        repeat(3) { f.store.stepNumber(PlaybackNumber.UP_NEXT_COUNTDOWN, up = false) }
        runCurrent()
        val countdown = f.state.stepper(PlaybackNumber.UP_NEXT_COUNTDOWN)
        assertEquals(0, countdown.value)
        assertFalse(countdown.canDecrease)
        assertEquals(T.settings.playback.upNextImmediately, countdown.valueLabel)
        assertEquals(0, f.data.settings.settings("p").upNextCountdownSeconds)

        f.store.stepNumber(PlaybackNumber.SEEK_LONG, up = true)
        f.store.stepNumber(PlaybackNumber.SEEK_LONG, up = true)
        f.store.stepNumber(PlaybackNumber.SEEK_LONG, up = false)
        runCurrent()
        assertEquals(40, f.data.settings.settings("p").seekLongSeconds)
        f.data.close()
    }

    @Test
    fun aValueOffTheStepsStepsOntoThem() = runTest {
        val f = fixture()
        // Stored by an earlier version's typed input, or synced: below the stepper's bounds and between steps.
        f.data.settings.set("p", ProfileSetting.WatchedThresholdPercent, 30)
        f.data.settings.set("p", ProfileSetting.SeekShortSeconds, 7)
        runCurrent()
        assertEquals(T.settings.value.percent("30"), f.state.stepper(PlaybackNumber.WATCHED_THRESHOLD).valueLabel)
        assertFalse(f.state.stepper(PlaybackNumber.WATCHED_THRESHOLD).canDecrease)
        f.store.stepNumber(PlaybackNumber.WATCHED_THRESHOLD, up = false)
        runCurrent()
        assertEquals(30, f.data.settings.settings("p").watchedThresholdPercent)
        f.store.stepNumber(PlaybackNumber.WATCHED_THRESHOLD, up = true)
        runCurrent()
        assertEquals(50, f.data.settings.settings("p").watchedThresholdPercent)

        f.store.stepNumber(PlaybackNumber.SEEK_SHORT, up = false)
        runCurrent()
        assertEquals(5, f.data.settings.settings("p").seekShortSeconds)
        f.data.settings.set("p", ProfileSetting.SeekShortSeconds, 7)
        runCurrent()
        f.store.stepNumber(PlaybackNumber.SEEK_SHORT, up = true)
        runCurrent()
        assertEquals(10, f.data.settings.settings("p").seekShortSeconds)

        // Above the bounds (an earlier version took up to 600 s): a step down lands on the top bound.
        f.data.settings.set("p", ProfileSetting.SeekLongSeconds, 600)
        runCurrent()
        assertFalse(f.state.stepper(PlaybackNumber.SEEK_LONG).canIncrease)
        f.store.stepNumber(PlaybackNumber.SEEK_LONG, up = false)
        runCurrent()
        assertEquals(300, f.data.settings.settings("p").seekLongSeconds)
        f.data.close()
    }

    @Test
    fun languagePreferencesAreOrderedListsPickedFromEveryLanguage() = runTest {
        val f = fixture()
        assertEquals(T.settings.playback.subtitleLanguagesNone, f.state.languages(LanguageListKind.SUBTITLES).valueLabel)
        f.store.openLanguages(LanguageListKind.SUBTITLES)
        val picker = assertNotNull(f.state.languagePicker)
        assertEquals(T.settings.playback.subtitleLanguagesNone, picker.noneChosenText)
        assertTrue(picker.available.any { it.code == "eng" && it.name == "English" })
        assertTrue(picker.available.any { it.code == "pob" && it.name == "Portuguese (Brazil)" })
        val names = picker.available.map { it.name }
        assertEquals(names.sortedWith(localeOrder("en")), names, "listed by name, in English's order")

        f.store.searchLanguages("portug")
        assertEquals(setOf("por", "pob"), f.state.languagePicker?.available?.map { it.code }?.toSet())
        f.store.searchLanguages("zzzz")
        assertEquals(T.settings.playback.noLanguageMatches, f.state.languagePicker?.emptyText)
        f.store.searchLanguages("")

        f.store.addLanguage("pob")
        f.store.addLanguage("eng")
        runCurrent()
        assertEquals(listOf("pob", "eng"), f.data.settings.settings("p").subtitleLanguages)
        assertEquals(listOf("1", "2"), f.state.languagePicker?.chosen?.map { it.rank })
        assertEquals("Portuguese (Brazil)${T.settings.listSeparator}English", f.state.languages(LanguageListKind.SUBTITLES).valueLabel)
        assertFalse(f.state.languagePicker!!.available.any { it.code == "eng" }, "chosen languages aren't offered again")

        f.store.moveLanguage("eng", up = true)
        runCurrent()
        assertEquals(listOf("eng", "pob"), f.data.settings.settings("p").subtitleLanguages)
        f.store.moveLanguageTo("eng", 5)
        runCurrent()
        assertEquals(listOf("pob", "eng"), f.data.settings.settings("p").subtitleLanguages)
        f.store.removeLanguage("pob")
        runCurrent()
        assertEquals(listOf("eng"), f.data.settings.settings("p").subtitleLanguages)
        // The audio list is its own.
        assertEquals(emptyList(), f.data.settings.settings("p").audioLanguages)
        f.data.close()
    }

    /** Language lists follow the interface language: a search without the accents finds. */
    @Test
    fun languagesSearchAsTheInterfaceLanguageReads() = runTest {
        try {
            T.locale = "fr"
            val f = fixture()
            f.store.openLanguages(LanguageListKind.SUBTITLES)
            f.store.searchLanguages("francais")
            val found = f.state.languagePicker?.available?.map { it.name }.orEmpty()
            assertTrue(found.any { it.equals("Français", ignoreCase = true) }, "found: $found")
            f.data.close()
        } finally {
            T.locale = "en"
        }
    }

    @Test
    fun aChangeSyncedFromAnotherDeviceShowsUp() = runTest {
        val f = fixture()
        f.data.settings.set("p", ProfileSetting.BingeWatching, false)
        f.data.settings.set("other", ProfileSetting.WatchedThresholdPercent, 50)
        runCurrent()
        assertFalse(f.state.toggle("bingeWatching").checked)
        assertEquals(70, f.state.stepper(PlaybackNumber.WATCHED_THRESHOLD).value, "another profile's value isn't this one's")
        f.data.close()
    }

    @Test
    fun theSwitchesAreSetByTheirRowsIdAndAnUnknownRowFails() = runTest {
        val f = fixture()
        assertTrue(f.state.toggle("bingeWatching").checked)
        f.store.toggle("bingeWatching", false)
        runCurrent()
        val stored = f.data.settings.settings("p")
        assertFalse(stored.bingeWatching)
        assertFalse(f.state.toggle("bingeWatching").checked)
        assertFailsWith<IllegalStateException> { f.store.toggle("showSubtitles", true) }
        assertFailsWith<IllegalStateException> { f.store.choose("bingeWatching", "ALWAYS") }
        f.data.close()
    }

    /**
     * The Streams group has the suggestions' switch right after auto-play, off until the profile turns it on, which
     * stores the profile's setting.
     */
    @Test
    fun theSuggestionsSwitchFollowsAutoPlayAndStoresTheSetting() = runTest {
        val f = fixture()
        val streams = f.state.sections.single { it.group == PlaybackSettingsGroup.STREAMS }.rows
        assertEquals("streams.autoPlay", (streams[0] as PlaybackSettingRow.Choice).row.id)
        val row = (streams[1] as PlaybackSettingRow.Toggle).row
        assertEquals(Triple("streams.suggestions", "Suggest a stream", false), Triple(row.id, row.title, row.checked))
        assertEquals(T.settings.playback.streams.suggestionsHint, row.subtitle)
        f.store.toggle("streams.suggestions", true)
        runCurrent()
        assertTrue(f.data.settings.settings("p").streamSuggestions)
        assertTrue(f.state.toggle("streams.suggestions").checked)
        f.store.toggle("streams.suggestions", false)
        runCurrent()
        assertFalse(f.data.settings.settings("p").streamSuggestions)
        f.data.close()
    }

    @Test
    fun theStreamsGroupSetsAutoPlayTheQualitiesAndThePatterns() = runTest {
        val f = fixture()
        val autoPlay = assertNotNull(f.state.choice("streams.autoPlay"))
        assertEquals(listOf("OFF", "FIRST_MATCH", "REUSE_LAST_LINK"), autoPlay.options.map { it.id })
        assertEquals(T.settings.playback.streams.autoPlayOff, autoPlay.valueLabel)
        val qualities = f.state.streamChoices(StreamChoicesKind.QUALITIES)
        assertEquals(listOf("4K", "1440p", "1080p", "720p", "SD", "Unknown"), qualities.options.map { it.label })
        assertTrue(qualities.options.all { it.selected }, "every resolution shows by default")
        assertEquals(T.settings.playback.streams.qualitiesAll, qualities.valueLabel)
        assertEquals("", f.state.streamPattern(StreamPatternKind.INCLUDE).value)
        assertNull(f.state.streamPattern(StreamPatternKind.INCLUDE).error)

        f.store.choose("streams.autoPlay", "REUSE_LAST_LINK")
        f.store.choose("streams.autoPlay", "teleport")
        f.store.setStreamQualityShown("4k", shown = false)
        f.store.setStreamQualityShown("unknown", shown = false)
        f.store.setStreamQualityShown("4k", shown = true)
        runCurrent()
        val stored = f.data.settings.settings("p")
        assertEquals(AutoPlayMode.REUSE_LAST_LINK, stored.streamAutoPlay)
        assertEquals(listOf(StreamQuality.UNKNOWN), stored.hiddenStreamQualities)
        assertEquals("All but Unknown", f.state.streamChoices(StreamChoicesKind.QUALITIES).valueLabel)
        assertFalse(f.state.streamChoices(StreamChoicesKind.QUALITIES).options.last().selected)
        f.store.setStreamQualityShown("4k", shown = false)
        f.store.setStreamQualityShown("1440p", shown = false)
        f.store.setStreamQualityShown("1080p", shown = false)
        runCurrent()
        assertEquals("720p, SD", f.state.streamChoices(StreamChoicesKind.QUALITIES).valueLabel, "with most hidden, the ones shown")

        // An invalid pattern is kept as typed, reported, and filters nothing (StreamFilter).
        f.store.setStreamPattern(StreamPatternKind.EXCLUDE, " (sample ")
        runCurrent()
        assertEquals("(sample", f.data.settings.settings("p").streamExcludePattern)
        assertEquals("(sample", f.state.streamPattern(StreamPatternKind.EXCLUDE).valueLabel)
        assertEquals(T.settings.playback.streams.invalidPattern, f.state.streamPattern(StreamPatternKind.EXCLUDE).error)
        assertEquals(T.settings.playback.streams.invalidPattern, f.store.patternError("x265|("))
        assertNull(f.store.patternError("hdtv|xvid"))
        assertNull(f.store.patternError(""))
        f.store.setStreamPattern(StreamPatternKind.EXCLUDE, "hdtv|xvid")
        runCurrent()
        assertNull(f.state.streamPattern(StreamPatternKind.EXCLUDE).error)
        f.data.close()
    }

    @Test
    fun aSizeLimitIsOneTheRowOffersOrTheOneStored() = runTest {
        val f = fixture()
        f.store.choose("streams.minSize", "1024")
        f.store.choose("streams.maxSize", "2147483647")
        runCurrent()
        assertEquals(1024, f.data.settings.settings("p").streamMinSizeMb)
        assertEquals(0, f.data.settings.settings("p").streamMaxSizeMb, "a size the row doesn't offer changes nothing")

        // A size another version stored is offered while stored: once another is chosen, it no longer is.
        f.data.settings.set("p", ProfileSetting.StreamMaxSizeMb, 3000)
        runCurrent()
        f.store.choose("streams.maxSize", "2048")
        runCurrent()
        f.store.choose("streams.maxSize", "3000")
        runCurrent()
        assertEquals(2048, f.data.settings.settings("p").streamMaxSizeMb)
        f.data.close()
    }
}
