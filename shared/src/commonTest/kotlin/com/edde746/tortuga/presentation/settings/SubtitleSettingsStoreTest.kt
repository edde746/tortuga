@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.DeviceSettings
import com.edde746.tortuga.data.settings.SubtitleAppearance
import com.edde746.tortuga.data.settings.SubtitleColor
import com.edde746.tortuga.data.settings.SubtitleOutline
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleSettingsStoreTest {
    private class Fixture(scope: TestScope) {
        val data = LocalData(
            inMemoryDatabase(), { 1_790_000_000_000L },
            StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        )
        val store = SubtitleSettingsStore(data.deviceSettings, scope.backgroundScope)
        val state get() = store.state.value

        suspend fun stored() = data.deviceSettings.settings().subtitles
    }

    private fun TestScope.fixture() = Fixture(this).also { runCurrent() }

    @Test
    fun theSteppersStopAtTheirBoundsAndLabelTheirEnds() = runTest {
        val f = fixture()
        assertEquals("100%", f.state.size.valueLabel)
        assertEquals(T.settings.subtitles.backgroundOff, f.state.background.valueLabel)
        assertEquals(T.settings.subtitles.positionBottom, f.state.position.valueLabel)
        assertFalse(f.state.background.canDecrease)

        // Steps sent faster than the screen follows the stored value (a held D-pad key) each start from the last.
        repeat(12) { f.store.stepNumber(SubtitleNumber.SIZE, up = true) }
        runCurrent()
        assertEquals(200, f.stored().sizePercent)
        assertFalse(f.state.size.canIncrease)
        repeat(20) { f.store.stepNumber(SubtitleNumber.SIZE, up = false) }
        runCurrent()
        assertEquals(50, f.stored().sizePercent)

        f.store.stepNumber(SubtitleNumber.BACKGROUND, up = true)
        f.store.stepNumber(SubtitleNumber.POSITION, up = true)
        runCurrent()
        assertEquals(25, f.stored().backgroundPercent)
        assertEquals(5, f.stored().liftPercent)
        assertEquals(listOf("25%", "5%"), listOf(f.state.background.valueLabel, f.state.position.valueLabel))
        f.data.close()
    }

    @Test
    fun storedValuesOutsideTheRangesOrUnknownReadAsTheNearestOrTheDefault() = runTest {
        val f = fixture()
        f.data.deviceSettings.set(DeviceSetting.SubtitleSize, 400)
        f.data.deviceSettings.set(DeviceSetting.SubtitleLift, -3)
        runCurrent()
        assertEquals(200, f.stored().sizePercent)
        assertEquals(0, f.stored().liftPercent)

        // A value between steps (another version's) moves onto the steps.
        f.data.deviceSettings.set(DeviceSetting.SubtitleSize, 105)
        runCurrent()
        f.store.stepNumber(SubtitleNumber.SIZE, up = true)
        runCurrent()
        assertEquals(110, f.stored().sizePercent)
        f.data.close()

        // Read back, a stored value outside its range is clamped; a colour or outline a later version added, or a value
        // of another type, reads as the default.
        val stored = DeviceSettings.fromStored(
            mapOf(
                "subtitles.sizePercent" to "400", "subtitles.backgroundPercent" to "-5", "subtitles.liftPercent" to "90",
                "subtitles.textColor" to "\"MAGENTA\"", "subtitles.outline" to "2", "subtitles.outlineColor" to "\"GRAY\"",
                "subtitles.bold" to "\"yes\"",
            ),
        ).subtitles
        assertEquals(
            SubtitleAppearance.DEFAULT.copy(sizePercent = 200, backgroundPercent = 0, liftPercent = 50, outlineColor = SubtitleColor.GRAY),
            stored,
        )
    }

    @Test
    fun choicesTakeOnlyTheirOptionsAndTheOutlineColourGoesWithTheOutline() = runTest {
        val f = fixture()
        assertEquals(SubtitleColor.entries.map { it.name }, f.state.textColor.options.map { it.id })
        assertEquals(T.settings.subtitles.colors.white, f.state.textColor.valueLabel)
        assertEquals(T.settings.subtitles.outlines.thin, f.state.outline.valueLabel)
        assertNotNull(f.state.outlineColor)

        f.store.choose(f.state.textColor.id, "YELLOW")
        f.store.choose("textColor", "magenta")
        f.store.choose("outlineColor", "GRAY")
        runCurrent()
        assertEquals(SubtitleColor.YELLOW, f.stored().textColor)
        assertEquals(SubtitleColor.GRAY, f.stored().outlineColor)
        assertEquals(listOf("YELLOW"), f.state.textColor.options.filter { it.selected }.map { it.id })

        f.store.choose(f.state.outline.id, "NONE")
        runCurrent()
        assertEquals(SubtitleOutline.NONE, f.stored().outline)
        assertNull(f.state.outlineColor, "no outline: nothing to colour")

        f.store.toggle(f.state.bold.id, true)
        f.store.toggle(f.state.styledOverride.id, true)
        runCurrent()
        assertTrue(f.stored().bold && f.stored().styledOverride)
        assertTrue(f.state.bold.checked && f.state.styledOverride.checked)
        f.data.close()
    }

    @Test
    fun thePreviewDrawsThePlayersValuesAsSharesOfThePicture() = runTest {
        val f = fixture()
        val plain = f.state.preview
        assertEquals(38.0 / 720, plain.fontSize)
        assertEquals(1.65 / 720, plain.outlineWidth)
        assertEquals(0xFFFFFFFFL, plain.textColor)
        assertEquals(0xFF000000L, plain.outlineColor)
        assertNull(plain.boxColor)
        assertEquals(34.0 / 720, plain.bottom)

        f.store.choose("textColor", "YELLOW")
        repeat(5) { f.store.stepNumber(SubtitleNumber.SIZE, up = true) }
        repeat(2) { f.store.stepNumber(SubtitleNumber.BACKGROUND, up = true) }
        repeat(2) { f.store.stepNumber(SubtitleNumber.POSITION, up = true) }
        runCurrent()
        val styled = f.state.preview
        assertEquals(57.0 / 720, styled.fontSize)
        assertEquals(0xFFFFFF00L, styled.textColor)
        assertEquals(0x80000000L, styled.boxColor)
        assertEquals(6.0 / 720, styled.boxPadding)
        assertEquals(34.0 / 720 + 0.1, styled.bottom)
        f.data.close()
    }
}
