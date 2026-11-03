package com.edde746.tortuga.net

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Retry waits stay within their bounds and spread across them. */
class BackoffTest {
    private val random = Random(42)

    private fun samples(wait: () -> Long) = List(500) { wait() }

    @Test
    fun fullJitterStaysUnderABoundThatDoublesUpToTheCap() {
        for (attempt in 1..30) {
            val bound = minOf(300_000L, 5_000L shl minOf(attempt - 1, 20))
            val waits = samples { Backoff.fullJitter(attempt, 5_000, 300_000, random) }
            assertTrue(waits.all { it in 0..bound }, "attempt $attempt: ${waits.min()}..${waits.max()} outside 0..$bound")
            assertTrue(waits.max() > bound * 9 / 10 && waits.min() < bound / 10, "attempt $attempt spreads over its range")
        }
    }

    @Test
    fun equalJitterStaysBetweenHalfABoundThatDoublesUpToTheCapAndTheBound() {
        for (attempt in 1..30) {
            val bound = minOf(300_000L, 10_000L shl minOf(attempt - 1, 20))
            val waits = samples { Backoff.equalJitter(attempt, 10_000, 300_000, random) }
            assertTrue(waits.all { it in bound / 2..bound }, "attempt $attempt: ${waits.min()}..${waits.max()} outside ${bound / 2}..$bound")
            assertTrue(waits.max() > bound * 19 / 20 && waits.min() < bound * 11 / 20, "attempt $attempt spreads over its range")
        }
    }

    @Test
    fun aServersRetryAfterIsTheLeastWaitAndTwiceItTheMost() {
        val waits = samples { Backoff.afterRetryAfter(90_000, random) }
        assertTrue(waits.all { it in 90_000..180_000 }, "${waits.min()}..${waits.max()}")
        assertTrue(waits.distinct().size > 100, "spread, not one instant")
    }

    @Test
    fun pollsKeepTheirIntervalUntilAFailureThenWaitLongerUpToTheCap() {
        assertEquals(2_000, Backoff.poll(0, 2_000, 30_000, random))
        for (errors in 1..12) {
            val most = minOf(30_000L, 2_000L + (2_000L shl (errors - 1)))
            val waits = samples { Backoff.poll(errors, 2_000, 30_000, random) }
            assertTrue(waits.all { it in 2_000..most }, "after $errors failures: ${waits.min()}..${waits.max()} outside 2000..$most")
        }
    }
}
