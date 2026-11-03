package com.edde746.tortuga.input

import com.edde746.tortuga.input.PlayerCommand.AdjustAudioDelay
import com.edde746.tortuga.input.PlayerCommand.AdjustSubtitleDelay
import com.edde746.tortuga.input.PlayerCommand.AdjustVolume
import com.edde746.tortuga.input.PlayerCommand.DpadScrub
import com.edde746.tortuga.input.PlayerCommand.SeekBy
import com.edde746.tortuga.input.PlayerCommand.SeekToFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeymapTest {
    private val desktop = Keymap.forProfile(InputProfile.DESKTOP)
    private val tv = Keymap.forProfile(InputProfile.TV)
    private val touch = Keymap.forProfile(InputProfile.TOUCH)

    private val playing = InputContext()
    private val shift = KeyModifiers(shift = true)
    private val ctrl = KeyModifiers(ctrl = true)
    private val cmd = KeyModifiers(meta = true)

    @Test
    fun desktopKeys() {
        val expected = listOf(
            Triple(InputKey.SPACE, KeyModifiers.NONE, PlayerCommand.TogglePause),
            Triple(InputKey.K, KeyModifiers.NONE, PlayerCommand.TogglePause),
            Triple(InputKey.LEFT, KeyModifiers.NONE, SeekBy(-10.0)),
            Triple(InputKey.RIGHT, KeyModifiers.NONE, SeekBy(10.0)),
            Triple(InputKey.LEFT, shift, SeekBy(-60.0)),
            Triple(InputKey.RIGHT, shift, SeekBy(60.0)),
            Triple(InputKey.UP, KeyModifiers.NONE, AdjustVolume(5.0)),
            Triple(InputKey.DOWN, KeyModifiers.NONE, AdjustVolume(-5.0)),
            Triple(InputKey.M, KeyModifiers.NONE, PlayerCommand.ToggleMute),
            Triple(InputKey.F, KeyModifiers.NONE, PlayerCommand.ToggleFullscreen),
            Triple(InputKey.F11, KeyModifiers.NONE, PlayerCommand.ToggleFullscreen),
            Triple(InputKey.BRACKET_LEFT, KeyModifiers.NONE, PlayerCommand.SpeedDown),
            Triple(InputKey.BRACKET_RIGHT, KeyModifiers.NONE, PlayerCommand.SpeedUp),
            Triple(InputKey.BACKSPACE, KeyModifiers.NONE, PlayerCommand.ResetSpeed),
            Triple(InputKey.Z, KeyModifiers.NONE, AdjustSubtitleDelay(-0.1)),
            Triple(InputKey.X, KeyModifiers.NONE, AdjustSubtitleDelay(0.1)),
            Triple(InputKey.PLUS, ctrl, AdjustAudioDelay(0.1)),
            Triple(InputKey.MINUS, cmd, AdjustAudioDelay(-0.1)),
            Triple(InputKey.S, KeyModifiers.NONE, PlayerCommand.CycleSubtitles),
            Triple(InputKey.A, KeyModifiers.NONE, PlayerCommand.CycleAudio),
            Triple(InputKey.I, KeyModifiers.NONE, PlayerCommand.ToggleInfo),
            Triple(InputKey.PAGE_UP, KeyModifiers.NONE, PlayerCommand.NextChapter),
            Triple(InputKey.PAGE_DOWN, KeyModifiers.NONE, PlayerCommand.PreviousChapter),
            Triple(InputKey.HOME, KeyModifiers.NONE, SeekToFraction(0.0)),
            Triple(InputKey.DIGIT_0, KeyModifiers.NONE, SeekToFraction(0.0)),
            Triple(InputKey.DIGIT_7, KeyModifiers.NONE, SeekToFraction(0.7)),
            Triple(InputKey.ESCAPE, KeyModifiers.NONE, PlayerCommand.Back),
        )
        for ((key, modifiers, command) in expected) assertEquals(command, desktop.key(key, modifiers, playing), "$key $modifiers")
        // Alt shortcuts belong to the system; unmapped keys and Ctrl letters stay with the platform.
        assertNull(desktop.key(InputKey.SPACE, KeyModifiers(alt = true), playing))
        assertNull(desktop.key(InputKey.Q, KeyModifiers.NONE, playing))
        assertNull(desktop.key(InputKey.S, ctrl, playing))
        assertNull(desktop.key(InputKey.SELECT, KeyModifiers.NONE, playing))
    }

    @Test
    fun desktopEscapeLeavesFullscreenUnlessSomethingSmallerIsOpen() {
        val fullscreen = InputContext(fullscreen = true)
        assertEquals(PlayerCommand.ExitFullscreen, desktop.key(InputKey.ESCAPE, KeyModifiers.NONE, fullscreen))
        assertEquals(PlayerCommand.Back, desktop.key(InputKey.ESCAPE, KeyModifiers.NONE, fullscreen.copy(menuOpen = true)))
        assertEquals(PlayerCommand.Back, desktop.key(InputKey.ESCAPE, KeyModifiers.NONE, fullscreen.copy(scrubbing = true)))
    }

    @Test
    fun desktopNextAndPreviousEpisodeOnlyWhenThereIsOne() {
        assertNull(desktop.key(InputKey.N, KeyModifiers.NONE, playing))
        assertEquals(PlayerCommand.NextEpisode, desktop.key(InputKey.N, KeyModifiers.NONE, InputContext(nextEpisodeAvailable = true)))
        assertNull(desktop.key(InputKey.P, KeyModifiers.NONE, playing))
        assertEquals(PlayerCommand.PreviousEpisode, desktop.key(InputKey.P, KeyModifiers.NONE, InputContext(previousEpisodeAvailable = true)))
    }

    @Test
    fun desktopPointer() {
        assertEquals(PlayerCommand.TogglePause, desktop.click(0.2f, playing))
        assertEquals(PlayerCommand.CloseMenu, desktop.click(0.2f, InputContext(menuOpen = true)))
        assertNull(desktop.click(0.2f, InputContext(errorShown = true)))
        assertEquals(PlayerCommand.ToggleFullscreen, desktop.doubleClick(0.9f, playing))
        assertEquals(AdjustVolume(5.0), desktop.wheel(1.0, playing))
        assertEquals(AdjustVolume(-2.5), desktop.wheel(-0.5, playing))
        assertNull(desktop.wheel(0.0, playing))
    }

    @Test
    fun tvDpadDrivesPlaybackOnlyWithTheControlsDown() {
        val hidden = InputContext(controlsVisible = false)
        val visible = InputContext(controlsVisible = true)
        assertEquals(DpadScrub(forward = false), tv.key(InputKey.LEFT, KeyModifiers.NONE, hidden))
        assertEquals(DpadScrub(forward = true), tv.key(InputKey.RIGHT, KeyModifiers.NONE, hidden))
        assertEquals(PlayerCommand.ShowControls, tv.key(InputKey.UP, KeyModifiers.NONE, hidden))
        assertEquals(PlayerCommand.ShowControls, tv.key(InputKey.DOWN, KeyModifiers.NONE, hidden))
        assertEquals(PlayerCommand.TogglePause, tv.key(InputKey.SELECT, KeyModifiers.NONE, hidden))

        // With the controls up the D-pad moves focus, except on a focused seek bar.
        for (key in listOf(InputKey.LEFT, InputKey.RIGHT, InputKey.UP, InputKey.DOWN, InputKey.SELECT)) {
            assertNull(tv.key(key, KeyModifiers.NONE, visible), "$key")
        }
        assertEquals(DpadScrub(forward = true), tv.key(InputKey.RIGHT, KeyModifiers.NONE, visible.copy(timelineFocused = true)))
        assertNull(tv.key(InputKey.UP, KeyModifiers.NONE, visible.copy(timelineFocused = true)))
    }

    @Test
    fun tvWhileScrubbing() {
        val scrubbing = InputContext(controlsVisible = true, scrubbing = true)
        assertEquals(DpadScrub(forward = false), tv.key(InputKey.LEFT, KeyModifiers.NONE, scrubbing))
        assertEquals(PlayerCommand.CommitScrub, tv.key(InputKey.SELECT, KeyModifiers.NONE, scrubbing))
        assertEquals(PlayerCommand.Back, tv.key(InputKey.BACK, KeyModifiers.NONE, scrubbing))
        assertEquals(PlayerCommand.CancelScrub, tv.key(InputKey.ESCAPE, KeyModifiers.NONE, scrubbing))
    }

    @Test
    fun tvLeavesTheDpadToTheStillWatchingCardAndTheErrorScreen() {
        val prompt = PlayerCommand.NextEpisode
        for (context in listOf(InputContext(stillWatchingShown = true, prompt = prompt), InputContext(errorShown = true, controlsVisible = true))) {
            for (key in listOf(InputKey.LEFT, InputKey.RIGHT, InputKey.UP, InputKey.DOWN, InputKey.SELECT)) {
                assertNull(tv.key(key, KeyModifiers.NONE, context), "$key $context")
            }
            assertEquals(PlayerCommand.Back, tv.key(InputKey.BACK, KeyModifiers.NONE, context))
        }
    }

    @Test
    fun tvWithThePromptButtonSelectPressesItAndDownBringsTheControls() {
        for (command in listOf(PlayerCommand.SkipSegment, PlayerCommand.NextEpisode)) {
            val shown = InputContext(prompt = command)
            assertEquals(command, tv.key(InputKey.SELECT, KeyModifiers.NONE, shown))
            assertEquals(PlayerCommand.ShowControls, tv.key(InputKey.DOWN, KeyModifiers.NONE, shown))
            assertNull(tv.key(InputKey.UP, KeyModifiers.NONE, shown))
            assertEquals(DpadScrub(forward = true), tv.key(InputKey.RIGHT, KeyModifiers.NONE, shown))
            assertEquals(PlayerCommand.Back, tv.key(InputKey.BACK, KeyModifiers.NONE, shown))
            // With the controls up, focus moves among them and the button.
            for (key in listOf(InputKey.LEFT, InputKey.RIGHT, InputKey.UP, InputKey.DOWN, InputKey.SELECT)) {
                assertNull(tv.key(key, KeyModifiers.NONE, shown.copy(controlsVisible = true)), "$key")
            }
        }
    }

    @Test
    fun enterPressesThePromptButtonOnKeyboardsWhileItShows() {
        for (keymap in listOf(desktop, touch)) {
            assertEquals(PlayerCommand.SkipSegment, keymap.key(InputKey.SELECT, KeyModifiers.NONE, InputContext(prompt = PlayerCommand.SkipSegment)))
            assertEquals(PlayerCommand.NextEpisode, keymap.key(InputKey.SELECT, KeyModifiers.NONE, InputContext(prompt = PlayerCommand.NextEpisode)))
            assertNull(keymap.key(InputKey.SELECT, KeyModifiers.NONE, playing))
        }
    }

    @Test
    fun tvRemoteKeys() {
        assertEquals(PlayerCommand.ToggleInfo, tv.key(InputKey.INFO, KeyModifiers.NONE, playing))
        assertEquals(PlayerCommand.OpenMenu(PlayerMenu.SUBTITLES), tv.key(InputKey.MENU, KeyModifiers.NONE, playing))
        assertNull(tv.click(0.5f, playing))
        assertNull(tv.doubleClick(0.5f, playing))
        assertNull(tv.wheel(1.0, playing))
    }

    @Test
    fun touchTapsAndDoubleTaps() {
        assertEquals(PlayerCommand.ToggleControls, touch.click(0.5f, playing))
        assertEquals(PlayerCommand.CloseMenu, touch.click(0.5f, InputContext(menuOpen = true)))
        assertEquals(SeekBy(-10.0), touch.doubleClick(0.2f, playing))
        assertEquals(PlayerCommand.TogglePause, touch.doubleClick(0.5f, playing))
        assertEquals(SeekBy(10.0), touch.doubleClick(0.8f, playing))
        // With a menu open a tap closes it at once: nothing waits for a second tap.
        assertNull(touch.doubleClick(0.2f, InputContext(menuOpen = true)))
        assertNull(touch.wheel(1.0, playing))
    }

    @Test
    fun touchKeyboardsGetDesktopKeysButNoFullscreen() {
        assertEquals(PlayerCommand.TogglePause, touch.key(InputKey.SPACE, KeyModifiers.NONE, playing))
        assertEquals(SeekBy(10.0), touch.key(InputKey.RIGHT, KeyModifiers.NONE, playing))
        assertNull(touch.key(InputKey.F, KeyModifiers.NONE, playing))
        assertEquals(PlayerCommand.Back, touch.key(InputKey.ESCAPE, KeyModifiers.NONE, InputContext(fullscreen = true)))
    }

    @Test
    fun mediaKeysMeanTheSameEverywhere() {
        for (keymap in listOf(desktop, tv, touch)) {
            assertEquals(PlayerCommand.TogglePause, keymap.key(InputKey.MEDIA_PLAY_PAUSE, KeyModifiers.NONE, playing))
            assertEquals(PlayerCommand.Play, keymap.key(InputKey.MEDIA_PLAY, KeyModifiers.NONE, playing))
            assertEquals(PlayerCommand.Pause, keymap.key(InputKey.MEDIA_PAUSE, KeyModifiers.NONE, playing))
            assertEquals(PlayerCommand.Close, keymap.key(InputKey.MEDIA_STOP, KeyModifiers.NONE, playing))
            assertEquals(SeekBy(10.0), keymap.key(InputKey.MEDIA_FAST_FORWARD, KeyModifiers.NONE, playing))
            assertEquals(SeekBy(-10.0), keymap.key(InputKey.MEDIA_REWIND, KeyModifiers.NONE, playing))
            assertEquals(PlayerCommand.NextChapter, keymap.key(InputKey.MEDIA_NEXT, KeyModifiers.NONE, playing))
            assertEquals(
                PlayerCommand.NextEpisode,
                keymap.key(InputKey.MEDIA_NEXT, KeyModifiers.NONE, InputContext(nextEpisodeAvailable = true)),
            )
            assertEquals(PlayerCommand.PreviousChapter, keymap.key(InputKey.MEDIA_PREVIOUS, KeyModifiers.NONE, playing))
            assertEquals(
                PlayerCommand.PreviousEpisode,
                keymap.key(InputKey.MEDIA_PREVIOUS, KeyModifiers.NONE, InputContext(previousEpisodeAvailable = true)),
            )
            assertEquals(PlayerCommand.CycleSubtitles, keymap.key(InputKey.CAPTIONS, KeyModifiers.NONE, playing))
            assertEquals(PlayerCommand.CycleAudio, keymap.key(InputKey.AUDIO_TRACK, KeyModifiers.NONE, playing))
            assertEquals(PlayerCommand.Back, keymap.key(InputKey.BACK, KeyModifiers.NONE, playing))
        }
    }
}
