package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputKey
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.playback.Chapter
import com.edde746.tortuga.playback.session.NextEpisode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration

/** The controls' previous and next episode buttons ([PlayerUiState.episodes]) and the keys that change the episode. */
class EpisodeButtonsTest {
    private val next = NextEpisode(title = "Chapter Three", subtitle = "S1E3")

    private fun PlayerHarness.middleEpisode() = sessionInfo { it.copy(episode = true, nextEpisode = next, hasPreviousEpisode = true) }

    @Test
    fun aMovieHasNeither() = playerTest {
        playing()
        assertNull(state.episodes)
        dispatch(PlayerCommand.PreviousEpisode)
        dispatch(PlayerCommand.NextEpisode)
        assertEquals(0, session.previousEpisodes)
        assertEquals(0, session.nextEpisodes)
    }

    @Test
    fun aSeriesAlwaysHasBothDisabledAtItsEnds() = playerTest {
        playing()
        sessionInfo { it.copy(episode = true) }
        assertEquals(EpisodeButtons(previous = false, next = false), state.episodes, "a series of one episode")

        sessionInfo { it.copy(nextEpisode = next) }
        assertEquals(EpisodeButtons(previous = false, next = true), state.episodes, "the first episode")
        dispatch(PlayerCommand.PreviousEpisode)
        assertEquals(0, session.previousEpisodes)

        sessionInfo { it.copy(nextEpisode = null, hasPreviousEpisode = true) }
        assertEquals(EpisodeButtons(previous = true, next = false), state.episodes, "the last episode")
        dispatch(PlayerCommand.NextEpisode)
        assertEquals(0, session.nextEpisodes)

        middleEpisode()
        assertEquals(EpisodeButtons(previous = true, next = true), state.episodes)
    }

    @Test
    fun previousPlaysTheEpisodeBefore() = playerTest {
        middleEpisode()
        playing()
        dispatch(PlayerCommand.PreviousEpisode)
        assertEquals(1, session.previousEpisodes)
        assertEquals(0, session.nextEpisodes)
    }

    @Test
    fun previousDuringUpNextDropsIt() = playerTest {
        middleEpisode()
        playing(position = 595.0, duration = 600.0)
        assertEquals("Chapter Three", state.prompt?.text)
        assertEquals(EpisodeButtons(previous = true, next = true), state.episodes)
        dispatch(PlayerCommand.PreviousEpisode)
        assertEquals(1, session.previousEpisodes)
        assertNull(state.prompt)
        engineState { it.copy(eof = true) }
        assertEquals(0, session.nextEpisodes, "up next no longer plays the next episode")
    }

    @Test
    fun theHostChangesEpisodes() = playerTest {
        middleEpisode()
        playing()
        store.attachWatch(FakeWatch(canControl = true, canChangeEpisode = true))
        advance(Duration.ZERO)
        assertEquals(EpisodeButtons(previous = true, next = true), state.episodes)
        dispatch(PlayerCommand.PreviousEpisode)
        dispatch(PlayerCommand.NextEpisode)
        assertEquals(1, session.previousEpisodes)
        assertEquals(1, session.nextEpisodes)
    }

    @Test
    fun aGuestSeesBothDisabledAndIsToldWhy() = playerTest {
        middleEpisode()
        playing()
        store.attachWatch(FakeWatch(canControl = true, canChangeEpisode = false))
        advance(Duration.ZERO)
        assertEquals(EpisodeButtons(previous = false, next = false), state.episodes)
        dispatch(PlayerCommand.PreviousEpisode)
        dispatch(PlayerCommand.NextEpisode)
        assertEquals(0, session.previousEpisodes)
        assertEquals(0, session.nextEpisodes)
        assertEquals("Only the host controls playback", state.osd)
    }

    @Test
    fun mediaPreviousPlaysThePreviousEpisodeElseThePreviousChapter() = playerTest {
        engineState {
            it.copy(loaded = true, positionSeconds = 30.0, durationSeconds = 60.0, chapters = listOf(Chapter("One", 0.0), Chapter("Two", 20.0)))
        }
        press(InputKey.MEDIA_PREVIOUS)
        assertEquals(20.0, engine.seeks.last().first, "back to the start of the chapter")
        assertEquals(0, session.previousEpisodes)

        middleEpisode()
        press(InputKey.MEDIA_PREVIOUS)
        assertEquals(1, session.previousEpisodes)
        press(InputKey.MEDIA_NEXT)
        assertEquals(1, session.nextEpisodes)
    }

    @Test
    fun theRemotesPreviousKeyOnTv() = playerTest(InputProfile.TV) {
        middleEpisode()
        playing()
        press(InputKey.MEDIA_PREVIOUS)
        assertEquals(1, session.previousEpisodes)
    }

    @Test
    fun pAndNOnAKeyboard() = playerTest {
        middleEpisode()
        playing()
        press(InputKey.P)
        press(InputKey.N)
        assertEquals(1, session.previousEpisodes)
        assertEquals(1, session.nextEpisodes)
    }
}
