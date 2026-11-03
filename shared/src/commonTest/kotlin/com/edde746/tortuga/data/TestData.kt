package com.edde746.tortuga.data

import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.sync.HybridClock
import kotlinx.coroutines.Dispatchers

/** A private in-memory database on the platform running the test. */
internal expect fun inMemoryDatabase(): DatabaseFactory

/** A wall clock the test moves by hand, in epoch milliseconds. */
internal class TestClock(var now: Long = 1_790_000_000_000L)

internal fun testData(
    clock: TestClock = TestClock(),
    factory: DatabaseFactory = inMemoryDatabase(),
    maxClockDriftMillis: Long = HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
): LocalData = LocalData(factory, { clock.now }, Dispatchers.Default, maxClockDriftMillis)

internal fun movie(id: String = "tt0111161", name: String = "The Shawshank Redemption") =
    MetaSnapshot(id = id, type = "movie", name = name, poster = "https://images.example/$id.jpg", posterShape = "poster")

internal fun series(id: String = "tt0903747", name: String = "Breaking Bad") =
    MetaSnapshot(id = id, type = "series", name = name, poster = null, posterShape = "poster")
