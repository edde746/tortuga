package com.edde746.tortuga.playback.suggest

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertTrue

class StreamScorerDeviceBudgetTest {
    /**
     * [StreamScorerBudget] on this device, reported in the instrumentation's output (`streamScorer=…`): a list opening
     * scores within 50 ms; learning again after a pick, in the background, within a second.
     */
    @Test
    fun theScorersCostOnThisDevice() {
        val cost = StreamScorerBudget.measure()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("streamScorer", cost.toString()) })
        assertTrue(cost.scoreFirstMillis < 50, "$cost")
        assertTrue(cost.relearnMillis < 1000, "$cost")
    }
}
