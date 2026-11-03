package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputKey
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.playback.EngineError
import com.edde746.tortuga.playback.EngineErrorKind
import com.edde746.tortuga.playback.ErrorVideoSign
import com.edde746.tortuga.playback.PlaybackFailure
import com.edde746.tortuga.playback.resolve.ResolveFailure
import com.edde746.tortuga.playback.session.NextEpisode
import com.edde746.tortuga.strings.T
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ErrorFlowTest {
    @Test
    fun retryFirstThenTheNextStream() = playerTest {
        sessionInfo { it.copy(alternativeStreams = 2) }
        playing()
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.AUDIO))
        sessionInfo { it.copy(failure = PlaybackFailure.Engine(EngineError("loading failed", httpStatus = 403))) }
        assertEquals(
            ErrorState(
                title = "Can't play this stream",
                message = "The stream link has expired.",
                alternatives = "2 more streams available",
                buttons = listOf(
                    ErrorButton(ErrorAction.RETRY, T.player.error.retry, PlayerCommand.Retry, primary = true),
                    ErrorButton(ErrorAction.NEXT, T.player.error.nextStream, PlayerCommand.NextStream, primary = false),
                    ErrorButton(ErrorAction.CLOSE, T.player.error.close, PlayerCommand.Close, primary = false),
                ),
            ),
            state.error,
        )
        assertEquals(PlaybackStatus.FAILED, state.status)
        assertNull(state.menu)

        dispatch(PlayerCommand.Retry)
        assertEquals(1, session.retries)
        sessionInfo { it.copy(failure = null, resolving = true) }
        assertNull(state.error)
        assertEquals(PlaybackStatus.RESOLVING, state.status)
        assertEquals("Preparing stream…", state.loadingText)

        // The retry failed too: the next stream becomes the suggestion.
        sessionInfo { it.copy(resolving = false, failure = PlaybackFailure.Engine(EngineError("loading failed", kind = EngineErrorKind.OPEN))) }
        assertEquals("The stream couldn't be opened.", state.error?.message)
        assertEquals(ErrorAction.NEXT, state.error?.primary?.action)

        dispatch(PlayerCommand.NextStream)
        assertEquals(1, session.nextStreams)
        sessionInfo { it.copy(failure = null, alternativeStreams = 1) }
        sessionInfo { it.copy(failure = PlaybackFailure.Resolve(ResolveFailure.Timeout)) }
        // A new stream starts over with retry.
        assertEquals("The stream took too long to start.", state.error?.message)
        assertEquals("1 more stream available", state.error?.alternatives)
        assertEquals(listOf(ErrorAction.RETRY to true, ErrorAction.NEXT to false, ErrorAction.CLOSE to false), state.errorButtons)
    }

    /** mpv's error text is English: the player words an engine error in the interface language; mpv's goes to the log. */
    @Test
    fun anEngineErrorIsWordedInTheInterfaceLanguage() = playerTest {
        playing()
        try {
            T.locale = "ru"
            sessionInfo { it.copy(failure = PlaybackFailure.Engine(EngineError("loading failed"))) }
            val message = assertNotNull(state.error).message
            assertFalse(message.contains(Regex("[A-Za-z]")), message)
        } finally {
            T.locale = "en"
        }
    }

    /**
     * Over the error the controls are the title and Back alone: the failure closes the menu and the info, the
     * playback's controls go, and nothing opens them again (the menus' buttons, a remote's Menu and Info) until Retry
     * or the next stream takes the error away and brings the controls back.
     */
    @Test
    fun theErrorLeavesTheTitleAndBackAloneUntilARetryOrTheNextStream() = playerTest(InputProfile.TV) {
        sessionInfo { it.copy(alternativeStreams = 1) }
        playing()
        dispatch(PlayerCommand.ToggleInfo)
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.SUBTITLES))
        assertTrue(state.showsPlaybackControls)

        sessionInfo { it.copy(failure = PlaybackFailure.Engine(EngineError("loading failed"))) }
        assertTrue(state.controlsVisible, "the title and Back stay")
        assertFalse(state.showsPlaybackControls)
        assertNull(state.menu)
        assertFalse(state.infoVisible)
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.OPTIONS))
        dispatch(PlayerCommand.OpenSubtitleLanguage("en"))
        dispatch(PlayerCommand.ToggleInfo)
        press(InputKey.MENU)
        press(InputKey.INFO)
        advance(30.seconds)
        assertNull(state.menu, "no menu opens over the error")
        assertFalse(state.infoVisible, "nor the info")
        assertTrue(state.controlsVisible)
        assertFalse(state.showsPlaybackControls)

        dispatch(PlayerCommand.Retry)
        sessionInfo { it.copy(failure = null, resolving = true) }
        assertTrue(state.controlsVisible)
        assertTrue(state.showsPlaybackControls, "Retry brings the controls back")
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.SUBTITLES))
        assertEquals(PlayerMenu.SUBTITLES, state.menu)

        sessionInfo { it.copy(resolving = false, failure = PlaybackFailure.Engine(EngineError("loading failed"))) }
        assertFalse(state.showsPlaybackControls)
        assertNull(state.menu)
        dispatch(PlayerCommand.NextStream)
        sessionInfo { it.copy(failure = null, alternativeStreams = 0) }
        assertTrue(state.showsPlaybackControls, "the next stream brings them back")
        playing()
        advance(5.seconds)
        assertFalse(state.controlsVisible, "and they hide again as it plays")
    }

    /**
     * An error in an episode's credits takes the controls the same way, with what goes beside them: up next's button
     * and the subtitles' lift above the bottom controls.
     */
    @Test
    fun anErrorWhilePlayingTakesThePlaybackControlsAndUpNext() = playerTest {
        sessionInfo { it.copy(episode = true, nextEpisode = NextEpisode(title = "Chapter Two", subtitle = "S1E2")) }
        playing(position = 590.0, duration = 600.0)
        store.onControlsHeightChanged(0.2)
        assertEquals(PromptKind.UP_NEXT, state.prompt?.kind)
        assertEquals(0.2, engine.subtitleLifts.last())

        sessionInfo { it.copy(failure = PlaybackFailure.Engine(EngineError("stream ended", httpStatus = 410))) }
        assertTrue(state.controlsVisible)
        assertFalse(state.showsPlaybackControls)
        assertNull(state.prompt, "no up next over the error")
        assertEquals(0.0, engine.subtitleLifts.last(), "nothing to lift the subtitles over")
    }

    /**
     * A live playback that fails ends the same way: its live label (the red dot, LIVE and the time played) goes with the
     * transport in the bottom bar, and the title and Back stay over the error, which no menu or info opens over.
     */
    @Test
    fun aLivePlaybackThatFailsLeavesTheTitleAndBackAlone() = playerTest(InputProfile.TV) {
        sessionInfo { it.copy(live = true) }
        playing(position = 30.0, duration = 36.0)
        store.onControlsHeightChanged(0.2)
        dispatch(PlayerCommand.ShowControls)
        dispatch(PlayerCommand.ToggleInfo)
        assertEquals(PlaybackMode.LIVE, state.mode)
        assertTrue(state.showsPlaybackControls, "the live label beside play/pause")
        assertEquals(0.2, engine.subtitleLifts.last())

        sessionInfo { it.copy(failure = PlaybackFailure.Engine(EngineError("stream ended", httpStatus = 404))) }
        assertEquals(PlaybackStatus.FAILED, state.status)
        assertTrue(state.controlsVisible, "the title and Back stay")
        assertFalse(state.showsPlaybackControls, "no live label, transport or menus")
        assertFalse(state.infoVisible)
        dispatch(PlayerCommand.OpenMenu(PlayerMenu.OPTIONS))
        press(InputKey.INFO)
        advance(30.seconds)
        assertNull(state.menu, "no menu opens over the error")
        assertFalse(state.infoVisible, "nor the info")
        assertTrue(state.controlsVisible)
        assertFalse(state.showsPlaybackControls)
        assertEquals(0.0, engine.subtitleLifts.last(), "nothing to lift the subtitles over")
    }

    @Test
    fun withoutAlternativesOnlyRetryIsOffered() = playerTest {
        playing()
        sessionInfo { it.copy(failure = PlaybackFailure.Resolve(ResolveFailure.Http(404))) }
        assertEquals("The stream is no longer available.", state.error?.message)
        assertEquals(listOf(ErrorAction.RETRY to true, ErrorAction.CLOSE to false), state.errorButtons, "a gone link without another stream: Retry")
        assertNull(state.error?.alternatives)
        dispatch(PlayerCommand.NextStream)
        assertEquals(0, session.nextStreams)
        dispatch(PlayerCommand.Retry)
        sessionInfo { it.copy(failure = PlaybackFailure.Resolve(ResolveFailure.Http(502))) }
        assertEquals("The server responded with HTTP 502.", state.error?.message)
        assertEquals(ErrorAction.RETRY, state.error?.primary?.action)
    }

    /**
     * The card's buttons each carry their label and command, and exactly one is primary: Retry where the action the
     * failure calls for has no button.
     */
    @Test
    fun theCardHasOnePrimaryButtonAndRetryStandsInForOneItLacks() {
        val actions = listOf(ErrorAction.RETRY, ErrorAction.NEXT, ErrorAction.CLOSE)
        assertEquals(
            listOf(
                ErrorButton(ErrorAction.RETRY, T.player.error.retry, PlayerCommand.Retry, primary = false),
                ErrorButton(ErrorAction.NEXT, T.player.error.nextStream, PlayerCommand.NextStream, primary = true),
                ErrorButton(ErrorAction.CLOSE, T.player.error.close, PlayerCommand.Close, primary = false),
            ),
            PlayerStates.errorButtons(actions, primary = ErrorAction.NEXT),
        )
        for (missing in listOf(ErrorAction.NEXT, ErrorAction.CHOOSE_STREAM)) {
            val buttons = PlayerStates.errorButtons(listOf(ErrorAction.RETRY, ErrorAction.CLOSE), primary = missing)
            assertEquals(listOf(ErrorAction.RETRY to true, ErrorAction.CLOSE to false), buttons.map { it.action to it.primary }, "$missing")
        }
    }

    /**
     * A link that is gone (HTTP 404 or 410, at the resolve or in the engine) or one the player can't open puts the next
     * stream first while other streams are left: Retry can't bring it back. A failure Retry can mend (a timeout, a
     * dropped connection, a server's 5xx, a 403 that a new resolve may renew, an engine error without a status) keeps
     * Retry first; so does a dead link with no other stream.
     */
    @Test
    fun aLinkThatIsGoneSuggestsTheNextStreamFirst() = playerTest {
        sessionInfo { it.copy(alternativeStreams = 3) }
        playing()
        val gone = listOf(
            PlaybackFailure.Resolve(ResolveFailure.Http(404)),
            PlaybackFailure.Resolve(ResolveFailure.Http(410)),
            PlaybackFailure.Engine(EngineError("loading failed", httpStatus = 404)),
            PlaybackFailure.Engine(EngineError("loading failed", httpStatus = 410)),
            PlaybackFailure.Resolve(ResolveFailure.UnsupportedUrl),
        )
        for (failure in gone) {
            sessionInfo { it.copy(failure = failure) }
            assertEquals(ErrorAction.NEXT, state.error?.primary?.action, "$failure")
            assertEquals(listOf(ErrorAction.RETRY, ErrorAction.NEXT, ErrorAction.CLOSE), state.error?.buttons?.map { it.action })
            sessionInfo { it.copy(failure = null) }
        }
        val mendable = listOf(
            PlaybackFailure.Resolve(ResolveFailure.Timeout),
            PlaybackFailure.Resolve(ResolveFailure.Network("SocketException")),
            PlaybackFailure.Resolve(ResolveFailure.Http(503)),
            PlaybackFailure.Resolve(ResolveFailure.Http(403)),
            PlaybackFailure.Engine(EngineError("loading failed", httpStatus = 502)),
            PlaybackFailure.Engine(EngineError("loading failed")),
        )
        for (failure in mendable) {
            sessionInfo { it.copy(failure = failure) }
            assertEquals(ErrorAction.RETRY, state.error?.primary?.action, "$failure")
            sessionInfo { it.copy(failure = null) }
        }
        sessionInfo { it.copy(alternativeStreams = 0, failure = PlaybackFailure.Resolve(ResolveFailure.Http(404))) }
        assertEquals(ErrorAction.RETRY, state.error?.primary?.action, "no other stream: Retry is all there is")
    }

    /**
     * Next stream comes first after a retry that failed too, and only until the stream plays: a retry that worked, or a
     * stream picked from the list, starts the next error with Retry again.
     */
    @Test
    fun aRetryThatPlayedStartsTheNextErrorWithRetry() = playerTest {
        val serverError = PlaybackFailure.Engine(EngineError("loading failed", httpStatus = 503))
        sessionInfo { it.copy(alternativeStreams = 2) }
        playing()
        sessionInfo { it.copy(failure = serverError) }
        engineState { it.copy(loaded = false) }
        dispatch(PlayerCommand.Retry)
        sessionInfo { it.copy(failure = null, resolving = true) }
        sessionInfo { it.copy(resolving = false) }
        playing()
        sessionInfo { it.copy(failure = serverError) }
        assertEquals(ErrorAction.RETRY, state.error?.primary?.action, "the retry played: a new error offers Retry first")

        // A retry of an error video, then a stream picked from the list.
        engineState { it.copy(loaded = false) }
        val sign = ErrorVideoSign.Url
        sessionInfo { it.copy(failure = PlaybackFailure.ErrorVideo(sign, isEpisode = false, isMovie = true), errorVideo = sign) }
        dispatch(PlayerCommand.Retry)
        dispatch(PlayerCommand.ChooseStream)
        sessionInfo { it.copy(errorVideo = null, failure = serverError) }
        assertEquals(ErrorAction.RETRY, state.error?.primary?.action, "a stream picked from the list starts with Retry")
    }

    @Test
    fun retryDoesNothingWithoutAnError() = playerTest {
        sessionInfo { it.copy(alternativeStreams = 3) }
        playing()
        dispatch(PlayerCommand.Retry)
        assertEquals(0, session.retries)
    }

    @Test
    fun anErrorVideoOffersAnotherStreamItsMessageAndRetry() = playerTest {
        sessionInfo { it.copy(episode = true, alternativeStreams = 2) }
        playing()
        dispatch(PlayerCommand.ChooseStream)
        dispatch(PlayerCommand.WatchErrorVideo)
        assertEquals(0, session.streamChoices, "nothing without an error video")
        assertEquals(0, session.errorVideoWatches)

        val sign = ErrorVideoSign.Duration(120.0, 1440.0)
        val failure = PlaybackFailure.ErrorVideo(sign, isEpisode = true, isMovie = false)
        sessionInfo { it.copy(failure = failure, errorVideo = sign) }
        assertEquals(
            ErrorState(
                title = "This isn't the episode",
                message = "The addon sent an error video instead of the stream. " +
                    "It runs 2:00, where this video ran 24:00 when you last played it. Your progress is kept.",
                alternatives = null,
                buttons = listOf(
                    ErrorButton(ErrorAction.CHOOSE_STREAM, T.player.errorVideo.chooseStream, PlayerCommand.ChooseStream, primary = true),
                    ErrorButton(ErrorAction.WATCH, T.player.errorVideo.watch, PlayerCommand.WatchErrorVideo, primary = false),
                    ErrorButton(ErrorAction.RETRY, T.player.error.retry, PlayerCommand.Retry, primary = false),
                    ErrorButton(ErrorAction.CLOSE, T.player.error.close, PlayerCommand.Close, primary = false),
                ),
                errorVideo = true,
            ),
            state.error,
        )
        assertEquals(PlaybackStatus.FAILED, state.status)

        dispatch(PlayerCommand.ChooseStream)
        assertEquals(1, session.streamChoices)
        dispatch(PlayerCommand.WatchErrorVideo)
        assertEquals(1, session.errorVideoWatches)
        // Its message plays: no error meanwhile, and nothing more to watch until its end shows the error again.
        sessionInfo { it.copy(failure = null) }
        assertNull(state.error)
        dispatch(PlayerCommand.WatchErrorVideo)
        assertEquals(1, session.errorVideoWatches)
        sessionInfo { it.copy(failure = failure) }
        dispatch(PlayerCommand.Retry)
        assertEquals(1, session.retries)
    }

    @Test
    fun loadingStatesAreTold() = playerTest {
        sessionInfo { it.copy(resolving = true) }
        assertEquals(PlaybackStatus.RESOLVING, state.status)
        assertEquals("Preparing stream…", state.loadingText)
        sessionInfo { it.copy(resolving = false) }
        assertEquals(PlaybackStatus.OPENING, state.status)
        assertEquals("Opening…", state.loadingText)
        playing()
        assertEquals(PlaybackStatus.PLAYING, state.status)
        assertNull(state.loadingText)
        engineState { it.copy(buffering = true) }
        assertEquals(PlaybackStatus.BUFFERING, state.status)
        assertEquals("Buffering…", state.loadingText)
        assertTrue(state.status.isLoading)
        dispatch(PlayerCommand.Pause)
        assertEquals(PlaybackStatus.PAUSED, state.status)
        assertNull(state.loadingText)
        assertEquals("Play", state.playPauseLabel)
    }

    /** The card's buttons by action, each with whether it is the primary one. */
    private val PlayerUiState.errorButtons: List<Pair<ErrorAction, Boolean>>?
        get() = error?.buttons?.map { it.action to it.primary }
}
