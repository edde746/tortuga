package com.edde746.tortuga.playback.suggest

import kotlin.test.Test
import kotlin.test.assertTrue

class StreamScorerBudgetTest {
    /**
     * A Streams list opening only scores its streams (the scorer is learnt beforehand, [StreamPicks.scorer]): within
     * 50 ms for the largest list, and the model a few hundred bytes.
     */
    @Test
    fun scoringTheLargestListFitsAListOpening() {
        val cost = StreamScorerBudget.measure()
        println("stream scorer: $cost")
        assertTrue(cost.scoreMillis < 50, "$cost")
        assertTrue(cost.modelBytes < 1024, "$cost")
    }
}
