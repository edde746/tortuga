package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.playback.EngineError
import com.edde746.tortuga.playback.PlaybackFailure
import com.edde746.tortuga.playback.Track
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.session.NextEpisode
import com.edde746.tortuga.presentation.watch.PlayerWatchUi
import com.edde746.tortuga.presentation.watch.WatchPromptUi
import kotlinx.coroutines.flow.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * The rules every UI draws the player by: which button a menu belongs to, what may be controlled, what covers the
 * video, what holds a TV remote.
 */
class PlayerRulesTest {
    private val prompt = WatchPromptUi("Your file is 2 minutes shorter than Sam's", detail = null, actions = emptyList())

    private fun PlayerHarness.room(watch: FakeWatch, change: (PlayerWatchUi) -> PlayerWatchUi) {
        watch.state.update { it?.let(change) }
        advance(Duration.ZERO)
    }

    @Test
    fun aDelayBelongsToItsTrackMenuAndAnOptionsPageToTheOptions() {
        assertEquals(
            mapOf(
                PlayerMenu.AUDIO to PlayerMenu.AUDIO,
                PlayerMenu.SUBTITLES to PlayerMenu.SUBTITLES,
                PlayerMenu.SPEED to PlayerMenu.SPEED,
                PlayerMenu.SUBTITLE_DELAY to PlayerMenu.SUBTITLES,
                PlayerMenu.AUDIO_DELAY to PlayerMenu.AUDIO,
                PlayerMenu.MORE_SUBTITLES to PlayerMenu.SUBTITLES,
                PlayerMenu.SUBTITLE_LANGUAGE to PlayerMenu.SUBTITLES,
                PlayerMenu.OPTIONS to PlayerMenu.OPTIONS,
                PlayerMenu.VIDEO_FIT to PlayerMenu.OPTIONS,
                PlayerMenu.ZOOM to PlayerMenu.OPTIONS,
                PlayerMenu.SHADERS to PlayerMenu.OPTIONS,
                PlayerMenu.SLEEP_TIMER to PlayerMenu.OPTIONS,
                PlayerMenu.AUDIO_CHANNELS to PlayerMenu.OPTIONS,
                PlayerMenu.AUDIO_OUTPUT to PlayerMenu.OPTIONS,
                PlayerMenu.WATCH_TOGETHER to PlayerMenu.WATCH_TOGETHER,
            ),
            PlayerMenu.entries.associateWith { it.parent },
        )
    }

    @Test
    fun onlyAGuestInAHostOnlyRoomMayNotControlPlayback() = playerTest {
        playing()
        assertTrue(state.canControl, "no room")
        val watch = FakeWatch(canControl = true, canChangeEpisode = false)
        store.attachWatch(watch)
        advance(Duration.ZERO)
        assertTrue(state.canControl)
        assertTrue(state.canSeek)
        room(watch) { it.copy(canControl = false) }
        assertFalse(state.canControl)
        assertFalse(state.canSeek, "the timeline can, this device may not")
        assertTrue(state.seekable)
    }

    @Test
    fun theTrackMenusShowWhenThereIsATrackToChoose() = playerTest {
        playing()
        assertFalse(state.showsAudioMenu)
        assertFalse(state.showsSubtitleMenu, "Off alone")
        engineState { it.copy(tracks = listOf(Track(1, TrackKind.AUDIO, null, "eng", "aac", selected = true, external = false))) }
        assertTrue(state.showsAudioMenu)
        assertFalse(state.showsSubtitleMenu)
        engineState { it.copy(tracks = it.tracks + Track(1, TrackKind.SUBTITLE, null, "eng", "subrip", selected = false, external = false)) }
        assertTrue(state.showsSubtitleMenu)
    }

    @Test
    fun theSpinnerCoversTheVideoOnlyWhileItLoads() = playerTest {
        sessionInfo { it.copy(resolving = true) }
        assertEquals(PlayerCentre.Loading("Preparing stream…"), state.centre)
        sessionInfo { it.copy(resolving = false) }
        assertEquals(PlayerCentre.Loading("Opening…"), state.centre)
        playing()
        assertNull(state.centre)
        engineState { it.copy(buffering = true) }
        assertEquals(PlayerCentre.Loading("Buffering…"), state.centre)
    }

    @Test
    fun theErrorCoversThePromptThePromptTheSearchAndTheSearchTheSpinner() = playerTest {
        sessionInfo { it.copy(resolving = true) }
        val watch = FakeWatch(canControl = true, canChangeEpisode = false)
        store.attachWatch(watch)
        room(watch) { it.copy(matching = "Finding a file that matches Sam's (2 of 4)…") }
        assertEquals(PlayerCentre.Matching("Finding a file that matches Sam's (2 of 4)…"), state.centre)
        room(watch) { it.copy(prompt = prompt) }
        assertEquals(PlayerCentre.Prompt(prompt), state.centre)
        sessionInfo { it.copy(resolving = false, failure = PlaybackFailure.Engine(EngineError("loading failed", httpStatus = 403))) }
        assertEquals(PlayerCentre.Error(state.error!!), state.centre)
        sessionInfo { it.copy(failure = null) }
        assertEquals(PlayerCentre.Prompt(prompt), state.centre, "the prompt still waits once the error is gone")
        room(watch) { it.copy(prompt = null, matching = null) }
        assertEquals(PlayerCentre.Loading("Opening…"), state.centre)
    }

    /**
     * What holds a TV remote over the video, each with buttons of its own: the open menu, the playback info, the error,
     * the prompt button, the sleep timer's question and a guest's prompt. The controls alone, a guest's search and the
     * spinner leave it to the video and the timeline.
     */
    @Test
    fun theMenusCardsAndPromptsHoldTheRemoteAndTheControlsDoNot() = playerTest {
        playing()
        dispatch(PlayerCommand.ShowControls)
        val shown = state
        assertTrue(shown.controlsVisible)
        assertFalse(shown.remoteHeld, "the controls alone")
        val room = assertNotNull(FakeWatch(canControl = true, canChangeEpisode = false).state.value)
        val holders = mapOf(
            "a menu" to shown.copy(menu = PlayerMenu.AUDIO),
            "the playback info" to shown.copy(infoVisible = true),
            "the error" to shown.copy(error = PlayerStates.error(PlaybackFailure.Engine(EngineError("loading failed")), alternatives = 0, retried = false)),
            "the prompt button" to shown.copy(
                prompt = PlayerStates.upNext(NextEpisode(title = "Chapter Two", subtitle = "S1E2"), progress = null, secondsLeft = null, withControls = false),
            ),
            "still watching" to shown.copy(stillWatching = PlayerStates.stillWatching(30)),
            "a guest's prompt" to shown.copy(watch = room.copy(prompt = prompt)),
        )
        for ((what, held) in holders) assertTrue(held.remoteHeld, what)
        assertFalse(shown.copy(watch = room.copy(matching = "Finding a file that matches Sam's (2 of 4)…")).remoteHeld, "a guest's search")
        assertFalse(shown.copy(status = PlaybackStatus.BUFFERING).remoteHeld, "the spinner")
    }
}
