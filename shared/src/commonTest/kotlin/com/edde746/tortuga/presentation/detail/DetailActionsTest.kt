package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.presentation.detail.DetailAction.CHOOSE_STREAM
import com.edde746.tortuga.presentation.detail.DetailAction.DOWNLOAD
import com.edde746.tortuga.presentation.detail.DetailAction.KIDS_BLOCK
import com.edde746.tortuga.presentation.detail.DetailAction.LIBRARY
import com.edde746.tortuga.presentation.detail.DetailAction.RATE
import com.edde746.tortuga.presentation.detail.DetailAction.TRAILER
import com.edde746.tortuga.presentation.detail.DetailAction.WATCHED
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailActionsTest {
    private val download = DetailDownload(DetailDownloadState.AVAILABLE, "Download", null, opensMenu = false, menuTitle = "Download", menu = emptyList())

    /** A title's state with auto-play's Choose a stream, a download and trailers where asked, and a kids profile's Block. */
    private fun state(chooseStream: Boolean = true, download: Boolean = true, trailers: Boolean = true, kidsBlock: Boolean = false, rating: Boolean = false) = DetailUiState(
        body = DetailBody.Loading,
        sources = emptyList(),
        primaryAction = DetailPrimaryAction("Play", "tt0111161", resume = false, episodeTitle = null, progress = null),
        inLibrary = false,
        libraryActionText = "Add to library",
        watched = false,
        watchedActionText = "Mark as watched",
        trailers = if (trailers) listOf(DetailTrailer("a", "Trailer")) else emptyList(),
        trailerActionText = "Trailer",
        seasons = emptyList(),
        episodes = emptyList(),
        chooseStreamText = if (chooseStream) "Choose a stream" else null,
        download = if (download) this.download else null,
        kidsBlockAction = if (kidsBlock) "Block on this profile" else null,
        userRating = if (rating) DetailUserRating(text = null, label = "Rate this title", score = null, trackers = emptyList()) else null,
    )

    @Test
    fun aRowWithRoomForEveryActionShowsThemAllInTheRowsOrder() {
        val all = listOf(CHOOSE_STREAM, LIBRARY, WATCHED, DOWNLOAD, TRAILER)
        assertEquals(DetailActionFit(all, emptyList()), state().fitActions(5))
        assertEquals(DetailActionFit(all, emptyList()), state().fitActions(9))
    }

    /** ⋯ takes a slot of its own: one slot short keeps the three most important, the trailer and watched go behind it. */
    @Test
    fun aNarrowerRowKeepsTheMostImportantAndPutsTheRestBehindMore() {
        assertEquals(DetailActionFit(listOf(CHOOSE_STREAM, LIBRARY, DOWNLOAD), listOf(WATCHED, TRAILER)), state().fitActions(4))
        assertEquals(DetailActionFit(listOf(CHOOSE_STREAM, LIBRARY), listOf(WATCHED, DOWNLOAD, TRAILER)), state().fitActions(3))
        assertEquals(DetailActionFit(listOf(CHOOSE_STREAM), listOf(LIBRARY, WATCHED, DOWNLOAD, TRAILER)), state().fitActions(2))
    }

    @Test
    fun withRoomForNoneEveryActionIsBehindMore() {
        val all = listOf(CHOOSE_STREAM, LIBRARY, WATCHED, DOWNLOAD, TRAILER)
        assertEquals(DetailActionFit(emptyList(), all), state().fitActions(1))
        assertEquals(DetailActionFit(emptyList(), all), state().fitActions(0))
    }

    /** Auto-play off, no downloads (Apple TV) and no trailer: the library and watched alone, and only they count. */
    @Test
    fun actionsThatDontApplyArentCounted() {
        val bare = state(chooseStream = false, download = false, trailers = false)
        assertEquals(listOf(LIBRARY, WATCHED), bare.actions)
        assertEquals(DetailActionFit(listOf(LIBRARY, WATCHED), emptyList()), bare.fitActions(2))
        val noAutoPlay = state(chooseStream = false)
        assertEquals(DetailActionFit(listOf(LIBRARY, DOWNLOAD), listOf(WATCHED, TRAILER)), noAutoPlay.fitActions(3))
    }

    /** A TV's row has every action, the trailer second, whatever applies; nothing goes behind ⋯. */
    @Test
    fun theTvRowHasEveryActionInItsOwnOrder() {
        assertEquals(listOf(CHOOSE_STREAM, TRAILER, LIBRARY, WATCHED, DOWNLOAD), state().tvActions)
        assertEquals(listOf(TRAILER, LIBRARY, WATCHED, DOWNLOAD), state(chooseStream = false).tvActions)
        assertEquals(listOf(LIBRARY, WATCHED), state(chooseStream = false, download = false, trailers = false).tvActions)
    }

    /**
     * A kids profile's Block on this profile is always behind ⋯, last, which then takes a slot however much room the
     * others have: where they fill every slot, ⋯ takes the last and the least important joins Block behind it.
     */
    @Test
    fun blockOnThisProfileIsAlwaysLastBehindMore() {
        val kids = state(kidsBlock = true)
        val buttons = listOf(CHOOSE_STREAM, LIBRARY, WATCHED, DOWNLOAD, TRAILER)
        assertEquals(buttons + KIDS_BLOCK, kids.actions)
        assertEquals(DetailActionFit(buttons, listOf(KIDS_BLOCK)), kids.fitActions(9))
        assertEquals(DetailActionFit(buttons, listOf(KIDS_BLOCK)), kids.fitActions(6))
        assertEquals(DetailActionFit(listOf(CHOOSE_STREAM, LIBRARY, WATCHED, DOWNLOAD), listOf(TRAILER, KIDS_BLOCK)), kids.fitActions(5))
        assertEquals(DetailActionFit(listOf(CHOOSE_STREAM, LIBRARY), listOf(WATCHED, DOWNLOAD, TRAILER, KIDS_BLOCK)), kids.fitActions(3))
        assertEquals(DetailActionFit(emptyList(), buttons + KIDS_BLOCK), kids.fitActions(0))
    }

    /** A TV's row ends with Block on this profile. */
    @Test
    fun theTvRowEndsWithBlockOnThisProfile() {
        assertEquals(listOf(CHOOSE_STREAM, TRAILER, LIBRARY, WATCHED, DOWNLOAD, KIDS_BLOCK), state(kidsBlock = true).tvActions)
    }

    /**
     * The user's rating, where a tracker rates the title, is always behind ⋯, before a kids profile's Block: ⋯ shows
     * even where every button has room. A TV's row has it as a button, before Block.
     */
    @Test
    fun theRatingIsAlwaysBehindMore() {
        val buttons = listOf(CHOOSE_STREAM, LIBRARY, WATCHED, DOWNLOAD, TRAILER)
        assertEquals(DetailActionFit(buttons, listOf(RATE)), state(rating = true).fitActions(9))
        assertEquals(DetailActionFit(buttons, listOf(RATE, KIDS_BLOCK)), state(rating = true, kidsBlock = true).fitActions(6))
        assertEquals(DetailActionFit(listOf(CHOOSE_STREAM, LIBRARY), listOf(WATCHED, DOWNLOAD, TRAILER, RATE)), state(rating = true).fitActions(3))
        assertEquals(listOf(CHOOSE_STREAM, TRAILER, LIBRARY, WATCHED, DOWNLOAD, RATE, KIDS_BLOCK), state(rating = true, kidsBlock = true).tvActions)
    }
}
