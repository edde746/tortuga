package com.edde746.tortuga.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HybridClockTest {
    private val local = "000000000000000a"
    private val remote = "000000000000000b"
    private var wall = 1_000_000L
    private fun clock(last: Hlc? = null, maxDrift: Long = HybridClock.DEFAULT_MAX_DRIFT_MILLIS) =
        HybridClock(local, last, { wall }, maxDrift)

    @Test
    fun sendFollowsTheWallClock() {
        val clock = clock()
        assertEquals(Hlc(1_000_000, 0, local), clock.send())
        wall = 1_000_005
        assertEquals(Hlc(1_000_005, 0, local), clock.send())
    }

    @Test
    fun sendCountsWithinOneMillisecond() {
        val clock = clock()
        assertEquals(listOf(0, 1, 2), List(3) { clock.send().counter })
    }

    @Test
    fun sendStaysMonotonicWhenTheWallClockGoesBack() {
        val clock = clock()
        val first = clock.send()
        wall -= 60_000
        val stamps = List(5) { clock.send() }
        assertTrue(stamps.all { it > first })
        assertEquals(stamps, stamps.sorted())
        assertEquals(first.wallMillis, stamps.last().wallMillis)
    }

    @Test
    fun counterOverflowBorrowsTheNextMillisecond() {
        val clock = clock(last = Hlc(1_000_000, Hlc.MAX_COUNTER, local))
        val next = clock.send()
        assertEquals(Hlc(1_000_001, 0, local), next)
        assertTrue(next > Hlc(1_000_000, Hlc.MAX_COUNTER, remote))
    }

    @Test
    fun restoredClockContinuesAfterItsLastTimestamp() {
        val saved = Hlc(2_000_000, 7, remote)
        val clock = clock(last = saved)
        val next = clock.send()
        assertTrue(next > saved)
        assertEquals(Hlc(2_000_000, 8, local), next)
    }

    @Test
    fun receiveMovesPastARemoteClockAhead() {
        val clock = clock()
        clock.send()
        val ahead = Hlc(1_000_500, 3, remote)
        assertEquals(Hlc(1_000_500, 4, local), clock.receive(ahead))
        // Later local writes order after what was received, although the wall clock hasn't reached it.
        assertTrue(clock.send() > ahead)
    }

    @Test
    fun receiveOfAnOlderTimestampStillAdvances() {
        val clock = clock()
        val mine = clock.send()
        val received = clock.receive(Hlc(500, 9, remote))
        assertEquals(Hlc(1_000_000, mine.counter + 1, local), received)
    }

    @Test
    fun receiveWithEqualWallTimesTakesTheHigherCounter() {
        val clock = clock(last = Hlc(1_000_000, 2, local))
        assertEquals(Hlc(1_000_000, 6, local), clock.receive(Hlc(1_000_000, 5, remote)))
        assertEquals(Hlc(1_000_000, 7, local), clock.receive(Hlc(1_000_000, 1, remote)))
    }

    @Test
    fun receiveBehindTheWallClockResetsTheCounter() {
        val clock = clock(last = Hlc(900_000, 40, local))
        assertEquals(Hlc(1_000_000, 0, local), clock.receive(Hlc(950_000, 12, remote)))
    }

    @Test
    fun mergedTimestampsFromTwoDevicesStayCausal() {
        var wallA = 1_000_000L
        var wallB = 999_000L // one second behind
        val a = HybridClock(local, null, { wallA }, HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        val b = HybridClock(remote, null, { wallB }, HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        val writeA = a.send()
        b.receive(writeA)
        val writeB = b.send()
        // B saw A's write, so B's next write orders after it even though B's wall clock is behind.
        assertTrue(writeB > writeA)
        wallA += 1
        wallB += 1
        a.receive(writeB)
        assertTrue(a.send() > writeB)
    }

    @Test
    fun driftGuardRefusesAFarFutureTimestampAndKeepsTheClock() {
        val clock = clock(maxDrift = 60_000)
        val before = clock.send()
        val farAhead = Hlc(wall + 60_001, 0, remote)
        val error = assertFailsWith<ClockDriftException> { clock.receive(farAhead) }
        assertEquals(farAhead, error.remote)
        assertEquals(before, clock.last)
        // Exactly at the limit is accepted.
        assertEquals(Hlc(wall + 60_000, 1, local), clock.receive(Hlc(wall + 60_000, 0, remote)))
    }

    @Test
    fun aClockThatRanPastTheDriftGuardStartsAgainFromTheWallClock() {
        // Stamped while the wall clock was set two days ahead, then put right.
        val ranAhead = Hlc(wall + 2 * HybridClock.DEFAULT_MAX_DRIFT_MILLIS, 5, local)
        val clock = clock(last = ranAhead)
        assertEquals(Hlc(wall, 0, local), clock.send())
        assertEquals(Hlc(wall, 1, local), clock.send())
        // Another device accepts what it stamps now.
        val other = HybridClock(remote, null, { wall }, HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        other.receive(clock.send())

        // Ahead by the guard or less, it stays monotonic.
        val within = Hlc(wall + HybridClock.DEFAULT_MAX_DRIFT_MILLIS, 0, local)
        assertEquals(Hlc(within.wallMillis, 1, local), clock(last = within).send())
        // A received timestamp folds into the restarted clock, not the one ahead.
        assertEquals(Hlc(wall + 10, 1, local), clock(last = ranAhead).receive(Hlc(wall + 10, 0, remote)))
    }
}
