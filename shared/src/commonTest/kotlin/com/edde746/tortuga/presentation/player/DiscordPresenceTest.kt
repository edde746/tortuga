package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.input.PlayerCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DiscordPresenceTest {
    private class RecordingHost : DiscordPresenceHost {
        val published = mutableListOf<DiscordActivity?>()

        override fun publish(activity: DiscordActivity?) {
            published += activity
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class Fixture(private val scope: TestScope, enabled: Boolean) {
        val player = PlayerHarness(scope, PlayerConfig.defaults(InputProfile.DESKTOP, doubleClickIntervalMillis = 300))
        val host = RecordingHost()
        val enabled = MutableStateFlow(enabled)
        private val clock = object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(nowMillis)
        }
        val presence = DiscordPresence(player.store, player.session, this.enabled, host, scope.backgroundScope, clock, scope.testScheduler.timeSource)

        init {
            scope.runCurrent()
        }

        /** The wall clock, in Unix milliseconds, moving with the test's virtual time. */
        val nowMillis: Long get() = START_MILLIS + scope.currentTime

        val nowSeconds: Long get() = nowMillis / 1000

        val last: DiscordActivity? get() = host.published.last()

        fun setEnabled(on: Boolean) {
            enabled.value = on
            scope.runCurrent()
        }

        fun episode() = player.sessionInfo {
            it.copy(
                title = "Severance",
                subtitle = "S1E2 · Half Loop",
                artworkUrl = "https://images.example/s1e2-still.jpg",
                posterUrl = "https://images.example/severance-poster.jpg",
                episode = true,
            )
        }
    }

    private fun presenceTest(enabled: Boolean = true, body: Fixture.() -> Unit) = runTest { Fixture(this, enabled).body() }

    @Test
    fun aPlayingEpisodeShowsItsSeriesEpisodePosterAndTimes() = presenceTest {
        episode()
        player.playing(position = 100.0, duration = 600.0)
        val activity = assertNotNull(last)
        assertEquals("Severance", activity.details)
        assertEquals("S1E2 · Half Loop", activity.state)
        assertEquals("https://images.example/severance-poster.jpg", activity.imageUrl, "the series poster, not the episode's still")
        assertEquals("Severance", activity.imageText)
        assertEquals(nowSeconds - 100, activity.startSeconds, "elapsed: the 100 s played")
        assertEquals(nowSeconds + 500, activity.endSeconds, "left: the 500 s to go")
    }

    @Test
    fun aMovieWithoutAPosterShowsTheArtworkWhenItIsHttps() = presenceTest {
        player.sessionInfo { it.copy(title = "Arrival", subtitle = null, artworkUrl = "https://images.example/arrival-backdrop.jpg") }
        player.playing()
        assertNull(last?.state)
        assertEquals("https://images.example/arrival-backdrop.jpg", last?.imageUrl)

        // Discord fetches the image itself, and only over https.
        player.sessionInfo { it.copy(artworkUrl = "http://192.168.1.5/arrival.jpg") }
        assertNull(last?.imageUrl)
    }

    @Test
    fun theSpeedScalesTheTimes() = presenceTest {
        player.playing(position = 100.0, duration = 600.0)
        player.dispatch(PlayerCommand.SetSpeed(2.0))
        assertEquals(nowSeconds + 250, last?.endSeconds, "500 s left at double speed")
        assertEquals(nowSeconds - 50, last?.startSeconds, "the video's 300 s at double speed end then")
    }

    @Test
    fun anUnknownLengthCountsTheElapsedTimeAndALiveStreamNone() = presenceTest {
        player.playing(position = 42.0, duration = 0.0)
        assertEquals(nowSeconds - 42, last?.startSeconds)
        assertNull(last?.endSeconds)

        player.sessionInfo { it.copy(live = true) }
        assertNotNull(last)
        assertNull(last?.startSeconds)
        assertNull(last?.endSeconds)
    }

    @Test
    fun pausingTakesTheActivityDownAndPlayingPutsItBack() = presenceTest {
        episode()
        player.playing(position = 100.0, duration = 600.0)
        assertNotNull(last)

        player.engineState { it.copy(paused = true) }
        assertNull(last, "Rich Presence has no paused state: the card goes")

        player.advance(30.seconds)
        player.engineState { it.copy(paused = false) }
        assertEquals("Severance", last?.details)
        assertEquals(nowSeconds + 500, last?.endSeconds, "the time left counts from the resume")
    }

    @Test
    fun theEndAndTheReleaseTakeTheActivityDown() = presenceTest {
        player.playing()
        assertNotNull(last)
        player.engineState { it.copy(eof = true) }
        assertNull(last)

        player.engineState { it.copy(eof = false) }
        assertNotNull(last)
        presence.release()
        assertNull(last)
        val count = host.published.size
        player.position(200.0)
        assertEquals(count, host.published.size, "nothing after the release")
    }

    @Test
    fun theSettingOffShowsNothing() = presenceTest(enabled = false) {
        player.playing()
        presence.release()
        assertTrue(host.published.isEmpty(), "off: the host never hears of the playback")
    }

    @Test
    fun theSettingOnAndOffDuringPlaybackShowsAndTakesDownTheActivity() = presenceTest(enabled = false) {
        player.playing()
        setEnabled(true)
        assertNotNull(last)
        setEnabled(false)
        assertNull(last)
    }

    @Test
    fun playingAtItsPacePublishesNothingAndASeekPublishes() = presenceTest {
        player.playing(position = 100.0, duration = 600.0)
        val published = host.published.size
        // Thirty seconds of reports a quarter-second apart, as mpv sends them: the end stays where it was.
        repeat(120) { tick ->
            player.advance(0.25.seconds)
            player.position(100.0 + (tick + 1) * 0.25)
        }
        assertEquals(published, host.published.size)

        player.position(400.0)
        assertEquals(published + 1, host.published.size)
        assertEquals(nowSeconds + 200, last?.endSeconds)
    }

    @Test
    fun updatesBeyondDiscordsLimitWaitAndOnlyTheNewestGoesOut() = presenceTest {
        player.playing(position = 0.0, duration = 6000.0)
        repeat(9) { player.position(600.0 * (it + 1)) }
        assertEquals(DiscordPresence.UPDATES, host.published.size, "five updates go out at once")
        val end = nowSeconds + 600

        // The video plays on while the rest wait.
        repeat(20) {
            player.advance(1.seconds)
            player.position(5400.0 + it + 1)
        }
        assertEquals(DiscordPresence.UPDATES + 1, host.published.size, "then the newest of those waiting")
        assertTrue(abs(assertNotNull(last?.endSeconds) - end) <= DiscordActivity.TIMESTAMP_SLACK_SECONDS, "the last seek: 5400 s of 6000")
    }

    @Test
    fun theSettingOffTakesTheActivityDownAtOnceBeyondDiscordsLimit() = presenceTest {
        player.playing(position = 0.0, duration = 6000.0)
        repeat(6) { player.position(600.0 * (it + 1)) }
        assertEquals(DiscordPresence.UPDATES, host.published.size, "the sixth update waits for its turn")
        setEnabled(false)
        assertNull(last, "off: down without waiting for a turn")
        val count = host.published.size
        player.advance(30.seconds)
        assertEquals(count, host.published.size, "and the update that waited never goes out")
    }

    @Test
    fun longTextIsCutToDiscordsLimit() = presenceTest {
        player.sessionInfo { it.copy(title = "A".repeat(200)) }
        player.playing()
        val details = assertNotNull(last).details
        assertEquals(DiscordActivity.MAX_TEXT, details.length)
        assertTrue(details.endsWith("…"))
    }

    @Test
    fun aOneCharacterTitleIsPaddedToDiscordsShortest() = presenceTest {
        player.sessionInfo { it.copy(title = "M") }
        player.playing()
        val activity = assertNotNull(last)
        assertEquals(DiscordActivity.MIN_TEXT, activity.details.length)
        assertEquals("M", activity.details.trim('\u200B'))
    }

    @Test
    fun setActivityCarriesWhatDiscordShows() {
        val activity = DiscordActivity("Severance", "S1E2 · Half Loop", "https://images.example/p.jpg", "Severance", 1_790_000_000, 1_790_003_000)
        val command = Json.parseToJsonElement(DiscordRpc.setActivity(activity, pid = 4242, nonce = "7")).jsonObject
        assertEquals("SET_ACTIVITY", command.text("cmd"))
        assertEquals("7", command.text("nonce"))
        val args = command.obj("args")
        assertEquals(4242L, args["pid"]?.jsonPrimitive?.long)
        val sent = args.obj("activity")
        assertEquals(3L, sent["type"]?.jsonPrimitive?.long, "Watching")
        assertEquals("Severance", sent.text("details"))
        assertEquals("S1E2 · Half Loop", sent.text("state"))
        assertEquals(1_790_000_000L, sent.obj("timestamps")["start"]?.jsonPrimitive?.long)
        assertEquals(1_790_003_000L, sent.obj("timestamps")["end"]?.jsonPrimitive?.long)
        assertEquals("https://images.example/p.jpg", sent.obj("assets").text("large_image"))

        val clear = Json.parseToJsonElement(DiscordRpc.setActivity(null, pid = 4242, nonce = "8")).jsonObject
        assertEquals("SET_ACTIVITY", clear.text("cmd"))
        assertFalse("activity" in clear.obj("args"), "no activity takes it down")
    }

    @Test
    fun discordsRepliesAreToldApart() {
        assertTrue(DiscordRpc.isReady("""{"cmd":"DISPATCH","evt":"READY","data":{"v":1,"user":{"id":"1"}}}"""))
        assertFalse(DiscordRpc.isReady("""{"cmd":"SET_ACTIVITY","data":null,"evt":null,"nonce":"1"}"""))
        assertEquals("4000 Invalid Client ID", DiscordRpc.errorOf("""{"cmd":"DISPATCH","evt":"ERROR","data":{"code":4000,"message":"Invalid Client ID"}}"""))
        assertEquals("4000 Invalid Client ID", DiscordRpc.errorOf("""{"code":4000,"message":"Invalid Client ID"}"""), "a close frame's reason")
        assertNull(DiscordRpc.errorOf("""{"cmd":"SET_ACTIVITY","data":{"details":"x"},"nonce":"1"}"""))
        assertNull(DiscordRpc.errorOf("not json"))
    }

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private companion object {
        const val START_MILLIS = 1_790_000_000_000L
    }
}
