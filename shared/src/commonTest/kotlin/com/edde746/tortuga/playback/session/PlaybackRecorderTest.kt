@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.playback.session

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.model.MetaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What both players record through a [PlaybackRecorder] (Tortuga's player and the player apps' hand-offs): writes in
 * call order past one that fails, observers past one that throws, and the watched step: an episode that comes to
 * count moves the library on and records nothing more, a movie goes on recording until it is left in its credits.
 */
class PlaybackRecorderTest {
    private class World(val scope: TestScope) {
        val profileId = "profile-1"
        val wallClock = TestClock()
        val data = LocalData(inMemoryDatabase(), { wallClock.now }, StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        val sync = RecordingSync()

        /** The recorder's owner's scope, as a player's: it ends once the last write is done. */
        val owner = CoroutineScope(scope.backgroundScope.coroutineContext + SupervisorJob(scope.backgroundScope.coroutineContext[Job]))
        val recorder = PlaybackRecorder(profileId, data.library, data.streamHistory, sync, progressIntervalMillis = 5_000, owner, "test")

        fun settle() = scope.runCurrent()

        suspend fun state(meta: MetaItem) = assertNotNull(data.library.item(profileId, meta.id)).state
    }

    @Test
    fun writesRunInCallOrderPastOneThatFailsAndTheOwnerEndsOnceTheLastIsDone() = runTest {
        val w = World(this)
        val done = mutableListOf<String>()
        w.recorder.write { done += "first" }
        w.recorder.write { error("the database went away") }
        w.recorder.write { done += "third" }
        w.recorder.close()
        w.recorder.write { done += "after close" }
        assertTrue(w.owner.isActive, "the writes queued at close still run")
        w.settle()
        assertEquals(listOf("first", "third"), done)
        assertFalse(w.owner.isActive)
    }

    @Test
    fun anObserverThatThrowsIsPassedOverAndTheNextOneHearsEverything() = runTest {
        val w = World(this)
        w.recorder.observe(object : PlaybackObserver {
            override fun onVideoStarted(meta: MetaItem, videoId: String, at: PlaybackPosition, playing: Boolean) = error("broken tracker")
            override fun onPlayingChanged(playing: Boolean, at: PlaybackPosition) = error("broken tracker")
            override fun onPosition(at: PlaybackPosition) = error("broken tracker")
            override fun onVideoStopped(at: PlaybackPosition) = error("broken tracker")
        })
        val heard = RecordingObserver().also(w.recorder::observe)
        w.recorder.notifyObservers { onVideoStarted(MOVIE, MOVIE.id, PlaybackPosition(0, 100_000), playing = false) }
        w.recorder.notifyObservers { onPlayingChanged(true, PlaybackPosition(0, 100_000)) }
        w.recorder.notifyObservers { onPosition(PlaybackPosition(1_000, 100_000)) }
        w.recorder.notifyObservers { onVideoStopped(PlaybackPosition(1_000, 100_000)) }
        assertEquals(listOf("started 0/100000", "playing 0/100000", "position 1000/100000", "stopped 1000/100000"), heard.events)
    }

    @Test
    fun anEpisodeThatComesToCountMovesToTheNextEpisodeAndRecordsNothingMore() = runTest {
        val w = World(this)
        val recording = w.recorder.video(SERIES, "tt1:1:1")
        recording.saveItem()
        recording.recordStream(choice(urlStream("https://addon.example/episode")))
        recording.saveProgress(600_000, 3_000_000, thresholdPercent = 70, creditsStartMillis = null, flush = false)
        assertFalse(recording.comesToCount(2_000_000, 3_000_000, thresholdPercent = 70, creditsStartMillis = null))
        assertTrue(recording.comesToCount(2_000_000, 3_000_000, thresholdPercent = 70, creditsStartMillis = 2_000_000), "its credits count it sooner")
        // 73 %: past the default 70 %, short of the credits.
        assertTrue(recording.comesToCount(2_200_000, 3_000_000, thresholdPercent = 70, creditsStartMillis = null))
        recording.saveProgress(2_200_000, 3_000_000, thresholdPercent = 70, creditsStartMillis = null, flush = false)
        assertTrue(recording.movedOn)
        assertFalse(recording.comesToCount(2_400_000, 3_000_000, thresholdPercent = 70, creditsStartMillis = null), "it counted already")

        // Nothing more: no progress (nor its push), and leaving it past its credits doesn't move the pointer again.
        recording.saveProgress(2_900_000, 3_000_000, thresholdPercent = 70, creditsStartMillis = null, flush = true)
        recording.leave(pastCredits = true, next = null)
        w.settle()

        val state = w.state(SERIES)
        assertEquals("tt1:1:2", state.videoId, "the next episode waits in Continue Watching")
        assertEquals(1, state.timeOffset)
        assertTrue(WatchedBitField.of(state.watchedVideos, SERIES).isWatched("tt1:1:1"))
        assertNotNull(w.data.streamHistory.lastStream(w.profileId, SERIES.id, "tt1:1:1"))
        assertEquals(2, w.sync.flushes, "crossing into watched, and leaving")
    }

    @Test
    fun aMovieThatComesToCountGoesOnRecordingAndResumesThereUntilLeftInItsCredits() = runTest {
        val w = World(this)
        val recording = w.recorder.video(MOVIE, MOVIE.id)
        recording.saveItem()
        recording.saveProgress(2_400_000, 3_000_000, thresholdPercent = 70, creditsStartMillis = null, flush = false)
        assertFalse(recording.movedOn)
        w.settle()
        var state = w.state(MOVIE)
        assertEquals(1, state.timesWatched)
        assertEquals(2_400_000, state.timeOffset, "80 %: short of the credits, so it resumes there")

        // A movie goes on recording: a seek back, then left in its credits.
        recording.recordSeek(900_000, 3_000_000)
        w.settle()
        assertEquals(900_000, w.state(MOVIE).timeOffset)
        recording.saveProgress(2_900_000, 3_000_000, thresholdPercent = 70, creditsStartMillis = null, flush = false)
        recording.leave(pastCredits = true, next = null)
        w.settle()
        state = w.state(MOVIE)
        assertEquals(1, state.timesWatched)
        assertEquals(0, state.timeOffset, "left in the credits: it starts over next time")
    }
}
