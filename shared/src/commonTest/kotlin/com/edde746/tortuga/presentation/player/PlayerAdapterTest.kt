package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.PlayerCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The player as the Open Watch Party SDK reads and commands it ([PlayerStore.watchPlayer]). */
class PlayerAdapterTest {
    @Test
    fun theReadsFollowTheEngine() = playerTest {
        val player = store.watchPlayer
        assertNull(player.durationMs, "nothing loaded")
        assertFalse(player.firstFrameShown)

        playing(position = 12.3456, duration = 600.0)
        assertEquals(12_346, player.positionMs)
        assertEquals(600_000, player.durationMs)
        assertFalse(player.paused)
        assertFalse(player.firstFrameShown, "loaded, no frame yet")
        engineState { it.copy(playbackRestarts = 1) }
        assertTrue(player.firstFrameShown)

        engineState { it.copy(buffering = true) }
        assertTrue(player.buffering)
        // At the end the player stands still, whatever mpv's pause says.
        engineState { it.copy(buffering = false, eof = true) }
        assertTrue(player.paused)

        assertTrue(player.canSetRate)
        engineState { it.copy(audioPassthrough = true) }
        assertFalse(player.canSetRate, "the receiver decodes the audio: a nudge would glitch it")
    }

    @Test
    fun playAndPauseReturnOnceTheEngineReportsThem() = playerTest {
        playing()
        engine.echo = false
        val player = store.watchPlayer

        val pause = start { player.pause() }
        assertEquals(listOf(true), engine.paused)
        assertTrue(player.paused, "the command reads at once")
        assertFalse(pause.isCompleted)
        engineState { it.copy(paused = true) }
        assertTrue(pause.isCompleted)

        val play = start { player.play() }
        assertEquals(listOf(true, false), engine.paused)
        assertFalse(play.isCompleted)
        engineState { it.copy(paused = false) }
        assertTrue(play.isCompleted)
    }

    @Test
    fun aSeekBeforeTheFileLoadsWaitsForItThenLandsExactly() = playerTest {
        val player = store.watchPlayer

        val seek = start { player.seek(42_000) }
        assertEquals(emptyList(), engine.seeks, "nothing to seek in yet")

        playing(position = 0.0, duration = 600.0)
        assertEquals(listOf(42.0 to true), engine.seeks)
        assertFalse(seek.isCompleted, "until mpv says the seek landed")
        engineState { it.copy(playbackRestarts = it.playbackRestarts + 1) }
        assertTrue(seek.isCompleted)
    }

    @Test
    fun theRoomRepeatingTheViewersSeekDoesntSeekTwice() = playerTest {
        playing()
        engine.echo = false
        dispatch(PlayerCommand.SeekTo(300.0))
        assertEquals(listOf(300.0), engine.seeks.map { it.first })

        val repeat = start { store.watchPlayer.seek(300_100) }
        assertEquals(1, engine.seeks.size)
        assertFalse(repeat.isCompleted)
        position(300.0)
        assertTrue(repeat.isCompleted)

        // Another position is another seek.
        start { store.watchPlayer.seek(100_000) }
        assertEquals(100.0, engine.seeks.last().first)
    }

    @Test
    fun theRoomHearsStateChangesNotPositionTicks() = playerTest {
        var changes = 0
        store.watchPlayer.onChanged = { changes++ }
        playing()
        assertEquals(1, changes)

        position(101.0)
        position(102.0)
        assertEquals(1, changes)

        engineState { it.copy(buffering = true) }
        engineState { it.copy(buffering = false, playbackRestarts = 1) }
        engineState { it.copy(paused = true) }
        assertEquals(4, changes)
    }

    @Test
    fun inARoomPlayAndPauseGoToTheRoomAndTheRateShowsAsTheRooms() = playerTest {
        playing()
        val watch = FakeWatch(canControl = true, canChangeEpisode = false)
        store.attachWatch(watch)
        watch.routesControls = true

        dispatch(PlayerCommand.TogglePause)
        assertEquals(listOf("pause"), watch.presses)
        assertEquals(emptyList(), engine.paused, "the room commands the player")

        watch.roomRate = 1.5
        start { store.watchPlayer.setRate(1.56) }
        assertEquals(listOf(1.56), engine.speeds)
        assertEquals(PlayerText.speed(1.5), state.speedLabel, "the nudge doesn't show")
    }

    /**
     * Play at the end of the file in a room starts the room over from the start, as Play does outside one: the relay
     * ends a room played from its end at once, so a bare play would only show "Finished" again.
     */
    @Test
    fun inARoomPlayAtTheEndStartsTheRoomOver() = playerTest {
        playing()
        val watch = FakeWatch(canControl = true, canChangeEpisode = false)
        store.attachWatch(watch)
        watch.routesControls = true
        engineState { it.copy(positionSeconds = 600.0, paused = true, eof = true) }

        dispatch(PlayerCommand.TogglePause)
        assertEquals(listOf(0.0), watch.seeks, "the room goes back to the start")
        assertEquals(listOf("play"), watch.presses)
        dispatch(PlayerCommand.Play)
        assertEquals(listOf(0.0, 0.0), watch.seeks)
        assertEquals(listOf("play", "play"), watch.presses)

        engineState { it.copy(positionSeconds = 300.0, eof = false) }
        dispatch(PlayerCommand.Play)
        assertEquals(listOf(0.0, 0.0), watch.seeks, "mid-file, Play plays on")
    }
}
