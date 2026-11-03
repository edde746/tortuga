package com.edde746.tortuga.playback.suggest

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.settings.StreamSortKey
import com.edde746.tortuga.data.streams.DeviceClass
import com.edde746.tortuga.data.streams.PickContext
import com.edde746.tortuga.data.streams.PickOutcome
import com.edde746.tortuga.data.streams.PickSource
import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamPick
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.playback.streams.ResolutionBadge
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class StreamPicksTest {
    /**
     * The scorer is learnt once for the settings a list asks with. Once a pick or its outcome is written, the next list
     * gets the scorer learnt before while it is learnt again in the background, and the list after gets the new one.
     */
    @Test
    fun theScorerIsKeptUntilThePicksChange() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val data = LocalData(inMemoryDatabase(), { 1_790_000_000_000L }, dispatcher, HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        val tv = PickContext(DeviceClass.TV, metered = false, hdrDisplay = true, dolbyVisionDisplay = false, hardwareCodecs = emptySet())
        val shown = listOf(PickedStream(ResolutionBadge.UHD_2160), PickedStream(ResolutionBadge.FHD_1080))
        repeat(StreamScorer.MIN_PICKS + 1) { data.streamPicks.record("p", StreamPick("s$it", "tt$it", "tt$it", null, 0, tv, PickSource.LIST, shown, 1, PickOutcome.GOOD)) }
        val picks = StreamPicks("p", data.streamPicks, data.settings, { tv }, backgroundScope, learnDispatcher = dispatcher)
        val english = LanguagePrefs.of(listOf("eng"), emptyList())
        val bySize = listOf(StreamSortKey.SIZE_SMALLEST)

        val first = assertNotNull(picks.scorer(english, bySize))
        assertSame(first, picks.scorer(english, bySize), "kept")
        val byResolution = assertNotNull(picks.scorer(english, listOf(StreamSortKey.RESOLUTION)))
        assertNotSame(first, byResolution, "another sort: learnt for its prior")

        picks.outcome("s0", PickOutcome.BAD)
        runCurrent()
        assertSame(byResolution, picks.scorer(english, listOf(StreamSortKey.RESOLUTION)), "the list doesn't wait")
        runCurrent()
        val relearnt = assertNotNull(picks.scorer(english, listOf(StreamSortKey.RESOLUTION)))
        assertNotSame(byResolution, relearnt, "learnt again once the outcome was written")
        assertEquals(StreamScorer.MIN_PICKS, relearnt.picksUsed, "the pick that went wrong left out")
    }
}
