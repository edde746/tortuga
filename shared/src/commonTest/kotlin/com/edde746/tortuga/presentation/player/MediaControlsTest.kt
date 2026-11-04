package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.playback.session.NextEpisode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MediaControlsTest {
    private class RecordingHost : MediaControlsHost {
        val shown = mutableListOf<NowPlaying>()
        var hidden = 0
        var userPauses = 0

        override fun show(nowPlaying: NowPlaying, commands: MediaCommandHandler) {
            shown += nowPlaying
        }

        override fun hide() {
            hidden++
        }

        override fun userPaused() {
            userPauses++
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class Fixture(private val scope: TestScope) {
        val player = PlayerHarness(scope, PlayerConfig.defaults(InputProfile.TOUCH, doubleClickIntervalMillis = 300))
        val host = RecordingHost()
        val controls = MediaControls(player.store, player.session, host, scope.backgroundScope, scope.testScheduler.timeSource)

        init {
            scope.runCurrent()
        }

        val last: NowPlaying get() = host.shown.last()

        /** An OS command, and whatever it sets off. */
        fun perform(command: MediaCommand) {
            controls.perform(command)
            scope.runCurrent()
        }

        /** A command from the player's own controls, and whatever it sets off. */
        fun dispatch(command: PlayerCommand) {
            player.store.dispatch(command)
            scope.runCurrent()
        }
    }

    private fun controlsTest(body: Fixture.() -> Unit) = runTest { Fixture(this).body() }

    @Test
    fun theControlsFollowThePlayersStatus() = controlsTest {
        assertEquals(NowPlayingStatus.BUFFERING, last.status, "a playback shows up while it opens")
        assertTrue(last.playing)
        assertFalse(last.canSeek)

        player.playing(position = 100.0, duration = 600.0)
        assertEquals(NowPlayingStatus.PLAYING, last.status)
        assertEquals(100.0, last.positionSeconds)
        assertEquals(600.0, last.durationSeconds)
        assertTrue(last.canSeek)

        player.engineState { it.copy(buffering = true) }
        assertEquals(NowPlayingStatus.BUFFERING, last.status)
        assertTrue(last.playing)

        player.engineState { it.copy(buffering = false, paused = true) }
        assertEquals(NowPlayingStatus.PAUSED, last.status)
        assertFalse(last.playing)

        player.engineState { it.copy(paused = false, eof = true) }
        assertEquals(NowPlayingStatus.STOPPED, last.status)
    }

    @Test
    fun metadataComesFromTheSession() = controlsTest {
        player.sessionInfo {
            it.copy(
                title = "Chernobyl",
                subtitle = "S1E2 · Please Remain Calm",
                artworkUrl = "https://images.example/e2.jpg",
                episode = true,
                nextEpisode = NextEpisode("Open Wide, O Earth", "S1E3"),
                hasPreviousEpisode = true,
            )
        }
        assertEquals("Chernobyl", last.title)
        assertEquals("S1E2 · Please Remain Calm", last.subtitle)
        assertEquals("https://images.example/e2.jpg", last.artworkUrl)
        assertTrue(last.canSkipNext)
        assertTrue(last.canSkipPrevious)

        // The OS loads the artwork itself: only web images qualify.
        player.sessionInfo { it.copy(artworkUrl = "file:///sdcard/poster.jpg", nextEpisode = null, hasPreviousEpisode = false) }
        assertNull(last.artworkUrl)
        assertFalse(last.canSkipNext)
        assertFalse(last.canSkipPrevious)
    }

    @Test
    fun aGuestsControlsOfferNoOtherEpisode() = controlsTest {
        player.sessionInfo { it.copy(episode = true, nextEpisode = NextEpisode("Next", null), hasPreviousEpisode = true) }
        player.store.attachWatch(FakeWatch(canControl = true, canChangeEpisode = false))
        player.advance(0.seconds)
        assertFalse(last.canSkipNext)
        assertFalse(last.canSkipPrevious)
    }

    @Test
    fun aLivePlaybackShowsAsLiveWithoutASeekBar() = controlsTest {
        player.playing(position = 100.0, duration = 600.0)
        assertFalse(last.live)
        assertTrue(last.canSeek)

        player.sessionInfo { it.copy(live = true) }
        assertTrue(last.live)
        assertFalse(last.canSeek)
    }

    @Test
    fun playbackAtNormalPacePublishesNothing() = controlsTest {
        player.playing(position = 100.0, duration = 600.0)
        val published = host.shown.size
        // Thirty seconds of reports a quarter-second apart, as mpv sends them, on the same clock the OS extrapolates by.
        repeat(120) { tick ->
            player.advance(0.25.seconds)
            player.position(100.0 + (tick + 1) * 0.25)
        }
        assertEquals(published, host.shown.size)
        assertEquals(100.0, last.positionSeconds, "the OS carries the position on from the last value")
    }

    @Test
    fun seeksStallsAndSpeedChangesPublish() = controlsTest {
        player.playing(position = 100.0, duration = 600.0)
        player.advance(5.seconds)
        player.position(105.0)
        val published = host.shown.size

        perform(MediaCommand.SeekBy(30.0))
        assertEquals(published + 1, host.shown.size, "a seek jumps away from the extrapolation")
        assertEquals(135.0, last.positionSeconds)

        // A stall: ten seconds pass with the position stuck and the status still PLAYING.
        player.advance(10.seconds)
        player.position(135.5)
        assertEquals(135.5, last.positionSeconds)

        player.dispatch(PlayerCommand.SetSpeed(1.5))
        assertEquals(1.5, last.rate)
    }

    @Test
    fun aSeekWhilePausedPublishes() = controlsTest {
        player.playing(position = 100.0, duration = 600.0)
        perform(MediaCommand.Pause)
        player.advance(20.seconds)
        val published = host.shown.size
        player.position(100.4)
        assertEquals(published, host.shown.size, "paused, the OS holds the position; a small report changes nothing")
        perform(MediaCommand.SeekTo(300.0))
        assertEquals(300.0, last.positionSeconds)
        assertEquals(NowPlayingStatus.PAUSED, last.status)
    }

    @Test
    fun commandsActLikeThePlayersOwnButtons() = controlsTest {
        player.playing(position = 100.0, duration = 600.0)
        player.sessionInfo { it.copy(episode = true, nextEpisode = NextEpisode("Next", null, autoplay = false), hasPreviousEpisode = true) }

        perform(MediaCommand.Pause)
        perform(MediaCommand.TogglePause)
        perform(MediaCommand.Play)
        assertEquals(listOf(true, false, false), player.engine.paused)

        perform(MediaCommand.SeekTo(700.0))
        perform(MediaCommand.SeekBy(-1000.0))
        assertEquals(listOf(600.0 to true, 0.0 to true), player.engine.seeks, "seeks clamp to the file")
        assertEquals(listOf(600.0, 0.0), player.session.seeks, "and the session records them as seeks")

        perform(MediaCommand.Next)
        assertEquals(1, player.session.nextEpisodes)

        perform(MediaCommand.Previous)
        assertEquals(1, player.session.previousEpisodes)

        perform(MediaCommand.Stop)
        assertEquals(1, player.host.closed)
    }

    @Test
    fun theHostHearsTheViewersPausesButNotTheSystems() = controlsTest {
        player.playing(position = 100.0, duration = 600.0)

        dispatch(PlayerCommand.Pause)
        assertEquals(1, host.userPauses, "the player's own Pause")

        dispatch(PlayerCommand.TogglePause)
        assertEquals(1, host.userPauses, "a toggle that plays is no pause")

        perform(MediaCommand.SystemPause)
        assertEquals(1, host.userPauses, "the system's pause isn't the viewer's")

        player.engineState { it.copy(paused = false) }
        perform(MediaCommand.Pause)
        assertEquals(2, host.userPauses, "the OS controls' Pause")
    }

    @Test
    fun releaseHidesTheControlsAndIgnoresLateCommands() = controlsTest {
        player.playing()
        controls.release()
        assertEquals(1, host.hidden)
        assertNull(controls.nowPlaying)
        val published = host.shown.size

        perform(MediaCommand.Pause)
        player.engineState { it.copy(paused = true) }
        assertEquals(emptyList(), player.engine.paused)
        assertEquals(published, host.shown.size)
        controls.release()
        assertEquals(1, host.hidden)
    }
}
