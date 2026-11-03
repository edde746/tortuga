package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputEvent
import com.edde746.tortuga.input.InputKey
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.KeyModifiers
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.playback.AudioChannels
import com.edde746.tortuga.playback.AudioDevice
import com.edde746.tortuga.playback.VideoFit
import com.edde746.tortuga.playback.VideoLayout
import com.edde746.tortuga.playback.session.NextEpisode
import kotlinx.coroutines.flow.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class OptionsTest {
    private fun PlayerHarness.option(id: PlayerOptionId): PlayerOption? = state.options.firstOrNull { it.id == id }

    private fun PlayerHarness.choose(id: PlayerOptionId, label: String) {
        val option = option(id) ?: error("no $id option")
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.OPTIONS))
        dispatch(PlayerCommand.OpenMenu(option.page ?: error("$id has no page")))
        dispatch(option.choices.first { it.label == label }.command)
    }

    @Test
    fun theOptionsListTheFitZoomSleepTimerAndAudioInOrder() = playerTest {
        playing()
        assertEquals(
            listOf(
                PlayerOptionId.VIDEO_FIT to "Letterbox",
                PlayerOptionId.ZOOM to "100%",
                PlayerOptionId.SLEEP_TIMER to "Off",
                PlayerOptionId.AUDIO_NORMALIZATION to "Off",
                PlayerOptionId.AUDIO_CHANNELS to "Original",
            ),
            state.options.map { it.id to it.value },
        )
        assertEquals(listOf("Letterbox", "Fill screen", "Stretch"), option(PlayerOptionId.VIDEO_FIT)?.choices?.map { it.label })
        assertEquals(
            listOf("Reset", "50%", "75%", "90%", "100%", "110%", "120%", "130%", "140%", "150%", "175%", "200%"),
            option(PlayerOptionId.ZOOM)?.choices?.map { it.label },
        )
        assertEquals(
            listOf("Off", "End of current video", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes", "120 minutes"),
            option(PlayerOptionId.SLEEP_TIMER)?.choices?.map { it.label },
        )
        assertEquals(true, option(PlayerOptionId.SLEEP_TIMER)?.choices?.first()?.selected)
        assertEquals(PlayerCommand.SetAudioNormalization(true), option(PlayerOptionId.AUDIO_NORMALIZATION)?.toggle)
        assertTrue(state.options.none { it.adjusted })
    }

    @Test
    fun aPickedFitGoesToTheEngineAndIsKeptForTheNextVideo() = playerTest {
        playing()
        choose(PlayerOptionId.VIDEO_FIT, "Stretch")
        assertEquals(listOf(VideoLayout(VideoFit.FILL)), engine.layouts)
        assertEquals(VideoFit.FILL, preferences.choices.value.videoFit, "this device keeps the fit for the next video")
        assertNull(state.menu, "a pick closes the page")
        assertNull(state.osd, "the menu showed the pick")
        assertEquals("Stretch", option(PlayerOptionId.VIDEO_FIT)?.value)
        assertEquals(true, option(PlayerOptionId.VIDEO_FIT)?.adjusted)
    }

    @Test
    fun theStoredFitGoesToTheEngineAsThePlayerOpensAndFollowsAChangedSetting() = runStoreWith(VideoFit.COVER) {
        assertEquals(listOf(VideoLayout(VideoFit.COVER)), engine.layouts)
        assertEquals("Fill screen", option(PlayerOptionId.VIDEO_FIT)?.value)
        choices { it.copy(videoFit = VideoFit.CONTAIN) }
        assertEquals(VideoLayout.DEFAULT, engine.layouts.last())
    }

    @Test
    fun zoomKeysStepByAPercentWithinTheRangeAndShowTheZoom() = playerTest {
        playing()
        val alt = KeyModifiers(alt = true)
        press(InputKey.PLUS, alt)
        assertEquals(VideoLayout(zoom = 1.01), engine.layouts.last())
        assertEquals("Zoom 101%", state.osd)
        press(InputKey.MINUS, alt)
        press(InputKey.MINUS, alt)
        assertEquals(0.99, engine.layouts.last().zoom)
        press(InputKey.BACKSPACE, alt)
        assertEquals(VideoLayout.DEFAULT, engine.layouts.last())
        assertEquals("Zoom 100%", state.osd)

        dispatch(PlayerCommand.SetZoom(1.987))
        assertEquals(1.99, engine.layouts.last().zoom)
        dispatch(PlayerCommand.ZoomIn)
        dispatch(PlayerCommand.ZoomIn)
        assertEquals(2.0, engine.layouts.last().zoom, "the zoom stops at 200 %")
        dispatch(PlayerCommand.SetZoom(0.1))
        assertEquals(0.5, engine.layouts.last().zoom, "and at 50 %")
        // Backspace without Alt still resets the speed.
        press(InputKey.BACKSPACE)
        assertEquals(1.0, engine.speeds.last())
    }

    @Test
    fun aZoomPickedInItsPageClosesItWithoutAMessage() = playerTest {
        playing()
        choose(PlayerOptionId.ZOOM, "120%")
        assertEquals(VideoLayout(zoom = 1.2), engine.layouts.last())
        assertNull(state.menu)
        assertNull(state.osd)
        assertEquals("120%", option(PlayerOptionId.ZOOM)?.value)
        assertEquals(listOf("120%"), option(PlayerOptionId.ZOOM)?.choices?.filter { it.selected }?.map { it.label })
        choose(PlayerOptionId.ZOOM, "Reset")
        assertEquals(VideoLayout.DEFAULT, engine.layouts.last())
    }

    @Test
    fun aPinchZoomsOnceItClearlyMovesSnapsTo100AndShowsTheZoomAtTheEnd() = playerTest(InputProfile.TOUCH) {
        playing()
        // Fingers resting on the video, or barely moving: nothing.
        input(InputEvent.Pinch(1.03))
        input(InputEvent.Pinch(1.05))
        input(InputEvent.PinchEnd)
        assertEquals(emptyList(), engine.layouts)
        assertNull(state.osd)

        // A pinch out zooms from its third clear move, by the pinch's own scale.
        input(InputEvent.Pinch(1.2))
        input(InputEvent.Pinch(1.3))
        assertEquals(emptyList(), engine.layouts)
        input(InputEvent.Pinch(1.4))
        assertEquals(VideoLayout(zoom = 1.4), engine.layouts.last())
        input(InputEvent.Pinch(1.5))
        assertEquals(VideoLayout(zoom = 1.5), engine.layouts.last())
        assertNull(state.osd, "no message while the fingers move")
        input(InputEvent.PinchEnd)
        assertEquals("Zoom 150%", state.osd)

        // Pinching back in from 150 % lands on 100 % exactly when it ends close to it.
        input(InputEvent.Pinch(0.8))
        input(InputEvent.Pinch(0.7))
        input(InputEvent.Pinch(0.68))
        assertEquals(VideoLayout.DEFAULT, engine.layouts.last(), "102 % snaps to 100 %")
        input(InputEvent.PinchEnd)
        assertEquals("Zoom 100%", state.osd)
    }

    @Test
    fun pictureInPictureShowsTheWholePictureAndTheLayoutComesBackAfter() = playerTest {
        playing()
        dispatch(PlayerCommand.SetVideoFit(VideoFit.COVER))
        dispatch(PlayerCommand.SetZoom(1.3))
        store.onPictureInPictureChanged(true)
        assertEquals(VideoLayout.DEFAULT, engine.layouts.last())
        assertEquals("Fill screen", option(PlayerOptionId.VIDEO_FIT)?.value, "the options keep the viewer's choice")
        store.onPictureInPictureChanged(false)
        assertEquals(VideoLayout(VideoFit.COVER, 1.3), engine.layouts.last())
    }

    @Test
    fun backFromAnOptionsPageReturnsToTheOptions() = playerTest(InputProfile.TV) {
        playing()
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.OPTIONS))
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.SLEEP_TIMER))
        assertEquals(PlayerOptionId.SLEEP_TIMER, state.openOption?.id)
        press(InputKey.ESCAPE)
        assertEquals(PlayerMenu.OPTIONS, state.menu)
        assertNull(state.openOption)
        press(InputKey.ESCAPE)
        assertNull(state.menu)
    }

    @Test
    fun theSleepTimerAsksAfterItsMinutesThenPausesUnlessTheViewerKeepsWatching() = playerTest {
        playing()
        choose(PlayerOptionId.SLEEP_TIMER, "5 minutes")
        assertEquals("5:00 left", option(PlayerOptionId.SLEEP_TIMER)?.value)
        advance(61.seconds)
        assertEquals("3:59 left", option(PlayerOptionId.SLEEP_TIMER)?.value)
        // The timer counts real time: a pause doesn't stop it.
        dispatch(PlayerCommand.Pause)
        dispatch(PlayerCommand.Play)
        advance(239.seconds)
        assertEquals(StillWatchingState("Pausing in 30 seconds", secondsLeft = 30, progress = 0f), state.stillWatching)
        advance(10.seconds)
        assertEquals("Pausing in 20 seconds", state.stillWatching?.countdown)

        // Kept watching: the same five minutes again.
        dispatch(PlayerCommand.KeepWatching)
        assertNull(state.stillWatching)
        assertEquals("5:00 left", option(PlayerOptionId.SLEEP_TIMER)?.value)
        advance(5.minutes)
        assertEquals(30, state.stillWatching?.secondsLeft)

        // Unanswered: the playback pauses when the countdown runs out, and the timer is off.
        advance(29.seconds)
        assertEquals(false, state.paused)
        advance(1.seconds)
        assertEquals(true, state.paused)
        assertNull(state.stillWatching)
        assertEquals("Off", option(PlayerOptionId.SLEEP_TIMER)?.value)
        advance(10.minutes)
        assertNull(state.stillWatching)
    }

    @Test
    fun theSleepTimerAsksAndPausesOnTimeHoweverLateEachSecondWakes() = TestTimeSource().let { clock ->
        playerTest(timeSource = clock) {
            playing()
            dispatch(PlayerCommand.SetSleepTimer(5))
            // Each second wakes after 1.5 s, as on a slow TV: the timer follows the time measured.
            fun lateSecond() {
                clock += 1500.milliseconds
                advance(1.seconds)
            }
            repeat(199) { lateSecond() }
            assertNull(state.stillWatching, "4:58.5")
            lateSecond()
            assertEquals(30, state.stillWatching?.secondsLeft, "asked at 5:00, after 200 late seconds rather than 300")
            repeat(19) { lateSecond() }
            assertEquals(false, state.paused, "28.5 s of the question's 30")
            lateSecond()
            assertEquals(true, state.paused, "paused 30 s after asking, after 20 late seconds")
        }
    }

    @Test
    fun withFastTimersTheSleepTimersMinutesAreSixSecondsEachAndTheQuestionStillWaitsThirty() = playerTest(
        config = PlayerConfig.defaults(InputProfile.DESKTOP, doubleClickIntervalMillis = 500).copy(sleepTimerMinuteSeconds = 6),
    ) {
        playing()
        choose(PlayerOptionId.SLEEP_TIMER, "5 minutes")
        // The menu tells the real time left.
        assertEquals("0:30 left", option(PlayerOptionId.SLEEP_TIMER)?.value)
        advance(29.seconds)
        assertEquals("0:01 left", option(PlayerOptionId.SLEEP_TIMER)?.value)
        assertNull(state.stillWatching)
        advance(1.seconds)
        assertEquals(StillWatchingState("Pausing in 30 seconds", secondsLeft = 30, progress = 0f), state.stillWatching)

        // Kept watching: the same 30 s again.
        dispatch(PlayerCommand.KeepWatching)
        assertNull(state.stillWatching)
        assertEquals("0:30 left", option(PlayerOptionId.SLEEP_TIMER)?.value)
        advance(29.seconds)
        assertNull(state.stillWatching)
        advance(1.seconds)
        assertEquals(30, state.stillWatching?.secondsLeft)

        // Unanswered: the playback pauses after the question's full 30 s.
        advance(29.seconds)
        assertEquals(false, state.paused)
        advance(1.seconds)
        assertEquals(true, state.paused)
        assertEquals("Off", option(PlayerOptionId.SLEEP_TIMER)?.value)
    }

    @Test
    fun pausingOrBackAnswersTheSleepTimersQuestion() = playerTest(InputProfile.TV) {
        playing()
        dispatch(PlayerCommand.SetSleepTimer(10))
        advance(10.minutes)
        assertEquals(30, state.stillWatching?.secondsLeft)
        // Back is "still watching".
        press(InputKey.ESCAPE)
        assertNull(state.stillWatching)
        assertEquals("10:00 left", option(PlayerOptionId.SLEEP_TIMER)?.value)
        advance(10.minutes)
        dispatch(PlayerCommand.Pause)
        assertNull(state.stillWatching)
        assertEquals("Off", option(PlayerOptionId.SLEEP_TIMER)?.value)
    }

    @Test
    fun aSleepTimerForTheEndOfTheVideoStopsThereInsteadOfPlayingTheNextEpisode() = playerTest {
        sessionInfo { it.copy(nextEpisode = NextEpisode(title = "Chapter Two", subtitle = "S1E2")) }
        playing(position = 500.0, duration = 600.0)
        choose(PlayerOptionId.SLEEP_TIMER, "End of current video")
        assertEquals("End of current video", option(PlayerOptionId.SLEEP_TIMER)?.value)
        position(595.0)
        assertNull(state.prompt, "no countdown to the next episode")
        engineState { it.copy(positionSeconds = 600.0, eof = true) }
        assertEquals(0, session.nextEpisodes)
        assertEquals(true, engine.paused.last())
        assertEquals("Off", option(PlayerOptionId.SLEEP_TIMER)?.value)
    }

    @Test
    fun autoPlayNextIsASwitchWhileThereIsANextEpisode() = playerTest {
        playing(position = 500.0, duration = 600.0)
        assertNull(option(PlayerOptionId.AUTO_PLAY_NEXT))
        sessionInfo { it.copy(nextEpisode = NextEpisode(title = "Chapter Two", subtitle = "S1E2")) }
        assertEquals(true, option(PlayerOptionId.AUTO_PLAY_NEXT)?.on)
        dispatch(option(PlayerOptionId.AUTO_PLAY_NEXT)!!.toggle!!)
        assertEquals(listOf(false), session.autoPlayNext)
        assertEquals(false, option(PlayerOptionId.AUTO_PLAY_NEXT)?.on)
        position(595.0)
        assertNull(state.prompt, "without auto-play or credits nothing comes up before the end")
    }

    @Test
    fun theAudioOptionsGoToTheEngineAndAreKept() = playerTest {
        playing()
        dispatch(option(PlayerOptionId.AUDIO_NORMALIZATION)!!.toggle!!)
        assertEquals(listOf(true), engine.normalizations)
        assertEquals(true, preferences.choices.value.audioNormalization)
        assertEquals("On", option(PlayerOptionId.AUDIO_NORMALIZATION)?.value)

        choose(PlayerOptionId.AUDIO_CHANNELS, "Stereo")
        assertEquals(listOf(AudioChannels.STEREO), engine.channels)
        assertEquals(AudioChannels.STEREO, preferences.choices.value.audioChannels)
        assertEquals("Mix down to two channels for stereo speakers or headphones", option(PlayerOptionId.AUDIO_CHANNELS)?.choices?.last()?.detail)
    }

    @Test
    fun theAudioOutputIsOfferedWhereThePlatformListsSeveral() = playerTest {
        playing()
        engineState { it.copy(audioDevices = listOf(AudioDevice("auto", "Autoselect device"))) }
        assertNull(option(PlayerOptionId.AUDIO_OUTPUT))
        engineState {
            it.copy(audioDevices = listOf(AudioDevice("auto", "Autoselect device"), AudioDevice("coreaudio/BuiltInSpeakerDevice", "MacBook Air Speakers")))
        }
        assertEquals("System default", option(PlayerOptionId.AUDIO_OUTPUT)?.value)
        choose(PlayerOptionId.AUDIO_OUTPUT, "MacBook Air Speakers")
        assertEquals(listOf("coreaudio/BuiltInSpeakerDevice"), engine.audioDevices)
        assertEquals("MacBook Air Speakers", option(PlayerOptionId.AUDIO_OUTPUT)?.value)
        assertNull(state.menu)
    }

    @Test
    fun anOutputListedByTwoDriversIsToldApartByItsDriver() = playerTest {
        playing()
        engineState {
            it.copy(
                audioDevices = listOf(
                    AudioDevice("auto", "Autoselect device"),
                    AudioDevice("coreaudio/BuiltInSpeakerDevice", "MacBook Air Speakers"),
                    AudioDevice("avfoundation/BuiltInSpeakerDevice", "MacBook Air Speakers"),
                    AudioDevice("coreaudio/AirPods", "AirPods Pro"),
                ),
            )
        }
        val choices = assertNotNull(option(PlayerOptionId.AUDIO_OUTPUT)).choices
        assertEquals(
            listOf(
                "System default" to null,
                "MacBook Air Speakers" to "Core Audio",
                "MacBook Air Speakers" to "AVFoundation",
                "AirPods Pro" to null,
            ),
            choices.map { it.label to it.detail },
        )
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.OPTIONS))
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.AUDIO_OUTPUT))
        dispatch(choices[2].command)
        assertEquals(listOf("avfoundation/BuiltInSpeakerDevice"), engine.audioDevices)
        assertEquals("\u2068MacBook Air Speakers\u2069 · AVFoundation", option(PlayerOptionId.AUDIO_OUTPUT)?.value, "the device's name isolated beside its driver")
    }

    /** A store whose device had [fit] stored before it started. */
    private fun runStoreWith(fit: VideoFit, body: PlayerHarness.() -> Unit) = playerTest(preferences = PlayerChoices(videoFit = fit), body = body)
}
