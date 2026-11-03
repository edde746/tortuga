package com.edde746.tortuga.app

import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.library.librarySnapshot
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.downloads.LocalSubtitle
import com.edde746.tortuga.downloads.LocalVideo
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.net.TortugaHttpClient
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.players.PlayerAppBanner
import com.edde746.tortuga.playback.players.PlayerAppEvent
import com.edde746.tortuga.playback.players.PlayerAppFailure
import com.edde746.tortuga.playback.players.PlayerAppLaunch
import com.edde746.tortuga.playback.players.PlayerAppPrompt
import com.edde746.tortuga.playback.players.PlayerAppSubtitle
import com.edde746.tortuga.playback.players.PlayerAppTitle
import com.edde746.tortuga.playback.resolve.redirect
import com.edde746.tortuga.playback.resolve.serveFile
import com.edde746.tortuga.playback.session.MOVIE
import com.edde746.tortuga.playback.session.PlayPurpose
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.SERIES
import com.edde746.tortuga.playback.session.STREAMS_URL
import com.edde746.tortuga.playback.session.SUBTITLE_ADDON
import com.edde746.tortuga.playback.session.choice
import com.edde746.tortuga.playback.session.json
import com.edde746.tortuga.playback.session.localPlayRequest
import com.edde746.tortuga.playback.session.urlStream
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Plays handed to player apps ([com.edde746.tortuga.playback.players.PlayerAppHandoff]) through the app's
 * [PlaybackController.play], against a fake launcher: which player plays, what it's handed, what is recorded, and the
 * banner, prompt and Detail line around it.
 */
class PlayerAppHandoffTest {
    @Test
    fun theSettingPicksThePlayerAndPlayWithPicksOneForOnePlay() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        w.data.deviceSettings.set(DeviceSetting.Player, VLC.id)
        val app = w.open()

        app.playback.play(w.movie())
        w.settle()
        assertEquals(listOf(VLC), w.launcher.launches.map { it.first })
        assertTrue(w.platform.playbacks.isEmpty(), "Tortuga's player didn't open")
        assertNull(app.state.value.playerApp, "the banner went once VLC started")

        // "Play with…" for one play: Tortuga, then Infuse; the setting stays.
        app.playback.play(w.movie(), PlayerApp.TORTUGA_ID)
        w.settle()
        assertEquals(1, w.platform.playbacks.size)
        assertEquals(listOf(Route.Board, Route.Player), app.navigator.backStack.value)
        app.navigator.back()
        app.playback.play(w.movie(), INFUSE.id)
        w.settle()
        assertEquals(listOf(VLC, INFUSE), w.launcher.launches.map { it.first })
        app.playback.play(w.movie())
        w.settle()
        assertEquals(listOf(VLC, INFUSE, VLC), w.launcher.launches.map { it.first })
        app.close()
    }

    @Test
    fun aPlayBeforeTheSettingIsReadGoesWhereItSaysOnceItIs() = runTest {
        val w = PlayerAppsWorld(this)
        val profile = w.data.addProfile(w.profileId)
        w.serveHostedLinks()
        w.data.deviceSettings.set(DeviceSetting.Player, VLC.id)
        val app = AppSession(w.graph, w.profileId, w.platform, start = null, profileAccount = null, startProfile = profile)
        app.playback.play(w.movie())
        assertTrue(w.launcher.launches.isEmpty() && w.platform.playbacks.isEmpty())
        w.settle()
        assertEquals(listOf(VLC), w.launcher.launches.map { it.first })
        assertTrue(w.platform.playbacks.isEmpty())
        app.close()

        // With the setting known, Tortuga plays at once, as without player apps.
        val tortuga = PlayerAppsWorld(this)
        val other = tortuga.open()
        other.playback.play(tortuga.movie())
        assertEquals(1, tortuga.platform.playbacks.size)
        other.close()
    }

    @Test
    fun aRoomAndAProbePlayInTortuga() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        w.data.deviceSettings.set(DeviceSetting.Player, VLC.id)
        val app = w.open()
        app.playback.play(w.movie().copy(purpose = PlayPurpose.PROBE))
        w.settle()
        assertEquals(1, w.platform.playbacks.size, "a watch-together probe is measured in Tortuga's player")

        app.playback.room = object : PlaybackRoom {
            override val inRoom = true

            override fun beforePlay(request: PlayRequest) = request

            override fun onStarted(active: ActivePlayback) = Unit

            override fun onStopped(active: ActivePlayback, replaced: Boolean) = Unit
        }
        app.playback.play(w.movie(), VLC.id)
        w.settle()
        assertEquals(2, w.platform.playbacks.size, "a room follows Tortuga's player")
        app.close()
    }

    @Test
    fun theAppGetsTheResolvedLinkTheHeadersTheSubtitlesTheTitleAndTheResumePoint() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        w.routes["subs.example.com"] = {
            json(
                """{"subtitles":[
                    {"id":"1","url":"https://subs.example.com/file/1.srt","lang":"eng"},
                    {"id":"2","url":"https://subs.example.com/file/2.srt","lang":"fre"}
                ]}""",
            )
        }
        w.data.addons.install(
            w.profileId,
            SUBTITLE_ADDON.transportUrl,
            """{"id":"org.example.subs","version":"1.0.0","name":"Subs","types":["movie","series"],"resources":["subtitles"]}""",
        )
        w.data.settings.set(w.profileId, ProfileSetting.SubtitleLanguages, listOf("fr", "en"))
        w.data.library.upsertFromMeta(w.profileId, MOVIE.librarySnapshot())
        w.data.library.recordProgress(w.profileId, MOVIE.id, MOVIE.id, 600_000, 3_000_000)
        val app = w.open()
        val request = w.movie().copy(stream = choice(urlStream("https://addon.example/movie", headers = mapOf("Referer" to "https://addon.example/"))))

        app.playback.play(request, VLC.id)
        assertEquals(PlayerAppBanner(T.playerApps.banner.opening(isolate("VLC")), busy = true, playInTortugaText = null, dismissText = T.playerApps.banner.cancel), app.state.value.playerApp)
        w.settle()
        val media = w.launcher.media
        assertEquals("https://cdn.example/movie.mkv", media.url, "the final link, never the addon's")
        assertEquals(false, media.local)
        assertEquals(mapOf("Referer" to "https://addon.example/", "User-Agent" to TortugaHttpClient.userAgent("test")), media.headers)
        assertEquals("The Shawshank Redemption", media.title)
        assertEquals(600.0, media.startSeconds, "the resume point")
        assertEquals(setOf("https://subs.example.com/file/1.srt", "https://subs.example.com/file/2.srt"), media.subtitles.map { it.url }.toSet())
        assertEquals("https://subs.example.com/file/2.srt", media.subtitles.single { it.selected }.url, "French first, as the profile prefers")
        assertEquals("movie.mkv", media.filename)
        assertNull(app.state.value.playerApp)

        // A player that can't be told where to start starts at the beginning; an episode's title has its code, beside
        // the name isolated.
        app.playback.play(w.episode(), INFUSE.id)
        w.settle()
        assertEquals(0.0, w.launcher.media.startSeconds)
        assertEquals("\u2068Chernobyl\u2069 S1E1", w.launcher.media.title)
        assertEquals(emptyList(), w.launcher.media.subtitles, "Infuse takes no subtitles: none is looked up")
        app.close()
    }

    @Test
    fun aLivePlayerRecordsProgressTrackersAndTheThresholdAsTortugasPlayerDoes() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        val app = w.open()
        val handoff = assertNotNull(app.playerAppHandoff)
        app.playback.play(w.movie(), VLC.id)
        w.settle()
        assertEquals(0.0, w.launcher.media.startSeconds)
        assertEquals(PlayerAppTitle.Playing(MOVIE.id, MOVIE.id, "VLC", 0, 0, paused = false), handoff.title(MOVIE.id).first())

        w.launcher.report(PlayerAppEvent.Started)
        var position = 0L
        fun report(paused: Boolean) = w.launcher.report(PlayerAppEvent.Position(position, 100_000, paused))
        fun playTo(to: Long) {
            while (position < to) {
                position += 1000
                report(paused = false)
                w.advance(1.seconds)
            }
        }
        report(paused = false)
        w.settle()
        playTo(20_000)
        report(paused = true)
        w.settle()
        assertEquals(20_000, w.item(MOVIE.id)?.state?.timeOffset, "saved on pause")
        assertEquals(PlayerAppTitle.Playing(MOVIE.id, MOVIE.id, "VLC", 20_000, 100_000, paused = true), handoff.title(MOVIE.id).first())
        assertEquals(0, w.item(MOVIE.id)?.state?.timesWatched)

        playTo(85_000)
        report(paused = true)
        w.settle()
        assertEquals(1, w.item(MOVIE.id)?.state?.timesWatched, "70 % reached")

        // A jump in the player is a seek: the resume point follows without counting it as watched.
        position = 30_000
        report(paused = true)
        w.settle()
        assertEquals(30_000, w.item(MOVIE.id)?.state?.timeOffset)
        playTo(95_000)
        w.launcher.report(PlayerAppEvent.Ended(95_000, 100_000, completed = false))
        w.settle()

        assertEquals(
            listOf(
                "started 0/100000", "playing 0/100000", "paused 20000/100000", "playing 21000/100000", "paused 85000/100000",
                "playing 31000/100000", "stopped 95000/100000",
            ),
            w.observer.events.filterNot { it.startsWith("position") },
        )
        assertTrue("position 10000/100000" in w.observer.events, "positions each second, as from Tortuga's player")
        assertTrue("position 30000/100000" in w.observer.events, "the seek")
        assertEquals(listOf(true, false, true, false, true, false), w.sync.playing)
        assertTrue(w.sync.flushes >= 3, "on pause, crossing the threshold and leaving")
        val item = assertNotNull(w.item(MOVIE.id))
        assertEquals(0, item.state.timeOffset, "left in the credits: the movie starts over next time")
        assertEquals(1, item.state.timesWatched)
        assertNull(handoff.title(MOVIE.id).first(), "nothing to ask: VLC said where it stopped")
        assertNotNull(w.data.streamHistory.lastStream(w.profileId, MOVIE.id, MOVIE.id))
        app.close()
    }

    @Test
    fun detailFollowsALivePlayerAndSaysWhenItIsPaused() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        w.installMetaAddon()
        val app = w.open()
        val detail = app.detailStore(Route.Detail("movie", MOVIE.id))
        app.playback.play(w.movie(), VLC.id)
        w.settle()
        fun line() = detail.state.value.playerApp?.text
        assertEquals("Playing in \u2068VLC\u2069 · 0:00", line(), "opening: nothing reported yet")

        w.launcher.report(PlayerAppEvent.Started)
        w.launcher.report(PlayerAppEvent.Position(8_000, 0, paused = false))
        w.settle()
        assertEquals(T.playerApps.detail.playing(isolate("VLC"), "0:08"), line())
        w.launcher.report(PlayerAppEvent.Position(8_000, 0, paused = true))
        w.settle()
        assertEquals(T.playerApps.detail.paused(isolate("VLC"), "0:08"), line())
        assertEquals("Paused in \u2068VLC\u2069 · 0:08", line())

        w.launcher.report(PlayerAppEvent.Position(8_000, 30_000, paused = true))
        w.settle()
        assertEquals("Paused in \u2068VLC\u2069 · 0:08 of 0:30", line())
        assertNull(detail.state.value.playerApp?.markWatchedText, "nothing to answer while it plays")
        w.launcher.report(PlayerAppEvent.Position(9_000, 30_000, paused = false))
        w.settle()
        assertEquals("Playing in \u2068VLC\u2069 · 0:09 of 0:30", line())

        w.launcher.report(PlayerAppEvent.Ended(12_000, 30_000, completed = false))
        w.settle()
        assertNull(detail.state.value.playerApp, "the line goes with the player")
        detail.release()
        app.close()
    }

    @Test
    fun aResumedLivePlayerStartsForTheTrackersWhereItIsOnceItKnowsTheDuration() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        w.data.library.upsertFromMeta(w.profileId, MOVIE.librarySnapshot())
        w.data.library.recordProgress(w.profileId, MOVIE.id, MOVIE.id, 40_000, 100_000)
        val app = w.open()
        app.playback.play(w.movie(), VLC.id)
        w.settle()
        assertEquals(40.0, w.launcher.media.startSeconds)

        // mpv says where it is before it read the duration: the trackers' start would have no progress.
        w.launcher.report(PlayerAppEvent.Started)
        w.launcher.report(PlayerAppEvent.Position(40_000, 0, paused = false))
        w.settle()
        assertEquals(emptyList(), w.observer.events, "nothing until the duration is known")
        w.launcher.report(PlayerAppEvent.Position(40_100, 100_000, paused = false))
        w.launcher.report(PlayerAppEvent.Ended(44_000, 100_000, completed = false))
        w.settle()

        assertEquals(listOf("started 40100/100000", "stopped 44000/100000"), w.observer.events)
        assertEquals(44_000, w.item(MOVIE.id)?.state?.timeOffset)
        app.close()
    }

    @Test
    fun eachHandOffTellsObserversOfItsOwn() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        val app = w.open()
        // Desktop: VLC plays the movie and reports.
        app.playback.play(w.movie(), VLC.id)
        w.settle()
        w.launcher.report(PlayerAppEvent.Started)
        w.launcher.report(PlayerAppEvent.Position(0, 100_000, paused = false))
        w.advance(10.seconds)
        w.launcher.report(PlayerAppEvent.Position(10_000, 100_000, paused = false))
        w.settle()

        // Another VLC plays the episode meanwhile: each tells its own observer, and the movie's recording goes on.
        app.playback.play(w.episode(), VLC.id)
        w.settle()
        w.advance(1.seconds)
        w.launcher.report(PlayerAppEvent.Position(11_000, 100_000, paused = false), launch = 0)
        w.launcher.report(PlayerAppEvent.Started)
        w.launcher.report(PlayerAppEvent.Position(0, 1_440_000, paused = false))
        w.launcher.report(PlayerAppEvent.Ended(12_000, 100_000, completed = false), launch = 0)
        w.settle()
        assertEquals(12_000, w.item(MOVIE.id)?.state?.timeOffset, "the movie's recording goes on")
        w.advance(5.seconds)
        w.launcher.report(PlayerAppEvent.Position(5_000, 1_440_000, paused = false))
        w.settle()

        val (movie, episode) = w.handoffObservers
        assertEquals(
            listOf("started 0/100000", "playing 0/100000", "position 10000/100000", "position 11000/100000", "stopped 12000/100000"),
            movie.events,
        )
        assertEquals(listOf("started 0/1440000", "playing 0/1440000", "position 5000/1440000"), episode.events)
        app.close()
    }

    @Test
    fun anOnReturnResultIsRecordedOnceAndOneWithoutAPositionAsks() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        val app = w.open()
        app.playback.play(w.movie(), MX_PLAYER.id)
        w.settle()
        val prompt = PlayerAppPrompt(w.profileId, MOVIE.id, MOVIE.id, "MX Player", "The Shawshank Redemption")
        assertEquals(listOf(prompt), w.data.deviceSettings.value(DeviceSetting.PlayerAppPrompts), "kept in case the app is closed meanwhile")

        w.launcher.report(PlayerAppEvent.Ended(60_000, 3_000_000, completed = false))
        w.settle()
        assertEquals(listOf("started 0/3000000", "position 60000/3000000", "stopped 60000/3000000"), w.observer.events)
        var state = assertNotNull(w.item(MOVIE.id)).state
        assertEquals(60_000, state.timeOffset)
        assertEquals(60_000, state.timeWatched)
        assertEquals(emptyList(), w.data.deviceSettings.value(DeviceSetting.PlayerAppPrompts))

        w.launcher.report(PlayerAppEvent.Ended(120_000, 3_000_000, completed = false))
        w.settle()
        state = assertNotNull(w.item(MOVIE.id)).state
        assertEquals(60_000, state.timeOffset, "recorded once")
        assertEquals(3, w.observer.events.size)

        app.playback.play(w.movie(), MX_PLAYER.id)
        w.settle()
        assertEquals(60.0, w.launcher.media.startSeconds)
        w.launcher.report(PlayerAppEvent.Ended(null, null, completed = false))
        w.settle()
        assertEquals(listOf(prompt), w.data.deviceSettings.value(DeviceSetting.PlayerAppPrompts))
        assertEquals(60_000, assertNotNull(w.item(MOVIE.id)).state.timeOffset, "no progress invented")
        app.close()
    }

    @Test
    fun aPlayToTheEndWithoutADurationTakesTheLibrarysOrAsks() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        val app = w.open()
        val prompt = PlayerAppPrompt(w.profileId, MOVIE.id, MOVIE.id, "MX Player", "The Shawshank Redemption")

        // Nothing says how long the video is: the prompt asks, nothing is recorded.
        app.playback.play(w.movie(), MX_PLAYER.id)
        w.settle()
        w.launcher.report(PlayerAppEvent.Ended(null, null, completed = true))
        w.settle()
        assertEquals(listOf(prompt), w.data.deviceSettings.value(DeviceSetting.PlayerAppPrompts))
        assertEquals(0, assertNotNull(w.item(MOVIE.id)).state.timeOffset)
        assertTrue(w.observer.events.isEmpty())

        // Left mid-way, then played to the end (mpv-android and MX Player leave the duration out then).
        app.playback.play(w.movie(), MX_PLAYER.id)
        w.settle()
        w.launcher.report(PlayerAppEvent.Ended(60_000, 3_000_000, completed = false))
        w.settle()
        w.observer.events.clear()
        app.playback.play(w.movie(), MX_PLAYER.id)
        w.settle()
        assertEquals(60.0, w.launcher.media.startSeconds)
        w.launcher.report(PlayerAppEvent.Ended(null, null, completed = true))
        w.settle()
        assertEquals(listOf("started 60000/3000000", "position 3000000/3000000", "stopped 3000000/3000000"), w.observer.events)
        val state = assertNotNull(w.item(MOVIE.id)).state
        assertEquals(1, state.flaggedWatched)
        assertEquals(1, state.timesWatched)
        assertEquals(0, state.timeOffset, "past the credits: back to the start")
        assertEquals(emptyList(), w.data.deviceSettings.value(DeviceSetting.PlayerAppPrompts))
        app.close()
    }

    @Test
    fun aPlayerThatReportsNothingRecordsTheStreamAndAsksOnDetailUntilAnswered() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        w.installMetaAddon()
        w.data.library.upsertFromMeta(w.profileId, SERIES.librarySnapshot())
        w.data.library.recordProgress(w.profileId, SERIES.id, "tt1:1:1", 600_000, 3_000_000)
        val app = w.open()
        app.playback.play(w.episode(), INFUSE.id)
        w.settle()

        assertEquals(600_000, assertNotNull(w.item(SERIES.id)).state.timeOffset, "no progress invented")
        assertTrue(w.observer.events.isEmpty())
        assertEquals(0, w.sync.flushes)
        assertTrue(w.sync.playing.isEmpty())
        assertNotNull(w.data.streamHistory.lastStream(w.profileId, SERIES.id, "tt1:1:1"))
        val prompt = PlayerAppPrompt(w.profileId, SERIES.id, "tt1:1:1", "Infuse", "S1E1 · \u20681:23:45\u2069")
        assertEquals(listOf(prompt), w.data.deviceSettings.value(DeviceSetting.PlayerAppPrompts))
        app.close()

        // The prompt outlives the app.
        val again = w.open()
        val detail = again.detailStore(Route.Detail("series", SERIES.id))
        w.settle()
        val line = assertNotNull(detail.state.value.playerApp)
        assertEquals(T.playerApps.detail.finished(isolate("Infuse"), isolate(prompt.label)), line.text)
        assertEquals("Opened in \u2068Infuse\u2069. Finished watching \u2068S1E1 · \u20681:23:45\u2069\u2069?", line.text)
        assertEquals(T.playerApps.detail.markWatched, line.markWatchedText)
        assertEquals(T.playerApps.detail.notYet, line.notYetText)

        detail.answerPlayerApp(finished = true)
        w.settle()
        assertNull(detail.state.value.playerApp)
        assertEquals(emptyList(), w.data.deviceSettings.value(DeviceSetting.PlayerAppPrompts))
        assertTrue(WatchedBitField.of(assertNotNull(w.item(SERIES.id)).state.watchedVideos, SERIES).isWatched("tt1:1:1"))
        detail.release()
        again.close()
    }

    @Test
    fun aPlayerAppPlayingAnErrorVideoRecordsNothing() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        // S1E1 half watched, 12:00 of 24:00, last played from another stream.
        w.data.library.upsertFromMeta(w.profileId, SERIES.librarySnapshot())
        w.data.library.recordProgress(w.profileId, SERIES.id, "tt1:1:1", 720_000, 1_440_000)
        val remembered = """{"url":"https://addon.example/s01e01-remembered"}"""
        w.data.streamHistory.record(w.profileId, SERIES.id, "tt1:1:1", remembered, STREAMS_URL)
        val before = assertNotNull(w.item(SERIES.id)).state
        suspend fun lastStream() = w.data.streamHistory.lastStream(w.profileId, SERIES.id, "tt1:1:1")?.stream?.source
        suspend fun assertNothingRecorded() {
            assertEquals(before, w.item(SERIES.id)?.state, "the library's progress, duration, watched marks and pointer")
            assertEquals(StreamSource.Url("https://addon.example/s01e01-remembered"), lastStream(), "the last stream stays")
            assertEquals(emptyList(), w.observer.events, "the trackers hear nothing")
        }
        val app = w.open()

        // VLC reports as it plays: a 120 s file where the episode ran 24 minutes, played to its end.
        app.playback.play(w.episode(), VLC.id)
        w.settle()
        assertEquals(720.0, w.launcher.media.startSeconds)
        w.launcher.report(PlayerAppEvent.Started)
        for (second in 0..120) {
            w.launcher.report(PlayerAppEvent.Position(second * 1000L, 120_000, paused = false))
            w.advance(1.seconds)
        }
        w.launcher.report(PlayerAppEvent.Ended(120_000, 120_000, completed = true))
        w.settle()
        assertNothingRecorded()

        // MX Player says the same once it closed: nothing recorded, nothing to ask.
        app.playback.play(w.episode(), MX_PLAYER.id)
        w.settle()
        w.launcher.report(PlayerAppEvent.Ended(120_000, 120_000, completed = true))
        w.settle()
        assertNothingRecorded()
        assertEquals(emptyList(), w.data.deviceSettings.value(DeviceSetting.PlayerAppPrompts))

        // The episode itself, in VLC: recorded as usual.
        app.playback.play(w.episode(), VLC.id)
        w.settle()
        w.launcher.report(PlayerAppEvent.Started)
        w.launcher.report(PlayerAppEvent.Position(720_000, 1_440_000, paused = false))
        w.advance(1.seconds)
        w.launcher.report(PlayerAppEvent.Ended(721_000, 1_440_000, completed = false))
        w.settle()
        assertEquals("started 720000/1440000", w.observer.events.first())
        assertEquals("stopped 721000/1440000", w.observer.events.last())
        assertEquals(721_000, w.item(SERIES.id)?.state?.timeOffset)
        assertEquals(StreamSource.Url("https://addon.example/s01e01"), lastStream())
        app.close()
    }

    @Test
    fun aLiveTitleInAPlayerAppRecordsItsStreamAndTrackerEventsOnly() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        val channel = MetaItem(MetaItemPreview(id = "tv1", type = "tv", name = "News 24"))
        // Progress an earlier play left on the channel.
        w.data.library.upsertFromMeta(w.profileId, channel.librarySnapshot())
        w.data.library.recordProgress(w.profileId, channel.id, channel.id, 600_000, 3_000_000)
        val before = assertNotNull(w.item(channel.id)).state
        val live = PlayRequest(channel, channel.id, choice(urlStream("https://addon.example/live")))
        suspend fun prompts() = w.data.deviceSettings.value(DeviceSetting.PlayerAppPrompts)
        val app = w.open()

        // VLC or mpv as it plays: the duration is what it downloaded so far, the position a few seconds behind.
        app.playback.play(live, VLC.id)
        w.settle()
        w.launcher.report(PlayerAppEvent.Started)
        for (second in 0..10) {
            w.launcher.report(PlayerAppEvent.Position(1_795_000 + second * 1000L, 1_800_000 + second * 1000L, paused = false))
            w.advance(1.seconds)
        }
        w.launcher.report(PlayerAppEvent.Position(1_806_000, 1_811_000, paused = true))
        w.launcher.report(PlayerAppEvent.Ended(1_806_000, 1_811_000, completed = false))
        w.settle()
        assertEquals(before, w.item(channel.id)?.state, "no progress, seek, watched mark or move on")
        assertEquals(emptyList(), prompts())
        assertNotNull(w.data.streamHistory.lastStream(w.profileId, channel.id, channel.id), "the stream is remembered")
        assertEquals("started 1795000/1800000", w.observer.events.first(), "the trackers hear it, as from Tortuga's player")
        assertEquals("stopped 1806000/1811000", w.observer.events.last())

        // MX Player says where it stopped once it closed, Infuse says nothing: neither asks "Finished watching?".
        app.playback.play(live, MX_PLAYER.id)
        w.settle()
        assertEquals(emptyList(), prompts())
        w.launcher.report(PlayerAppEvent.Ended(1_200_000, 1_210_000, completed = false))
        w.settle()
        app.playback.play(live, INFUSE.id)
        w.settle()
        assertEquals(emptyList(), prompts())
        assertEquals(before, w.item(channel.id)?.state)
        app.close()
    }

    @Test
    fun anErrorVideoTheResolveTellsIsShownAndNothingIsHandedOff() = runTest {
        val w = PlayerAppsWorld(this)
        w.routes["addon.example"] = { redirect("https://cdn.example/videos/failed_access_v2.mp4") }
        w.routes["cdn.example"] = { request -> serveFile(request, ByteArray(300_000)) }
        val app = w.open()
        app.playback.play(w.episode(), VLC.id)
        w.settle()
        assertTrue(w.launcher.launches.isEmpty())
        assertEquals(
            PlayerAppBanner(
                "The addon sent an error video instead of the stream. Its link is one of an addon's error videos. Your progress is kept.",
                busy = false,
                playInTortugaText = T.playerApps.banner.playInTortuga,
                dismissText = T.playerApps.banner.dismiss,
            ),
            app.state.value.playerApp,
        )
        assertNull(w.data.streamHistory.lastStream(w.profileId, SERIES.id, "tt1:1:1"))
        assertTrue(w.observer.events.isEmpty())
        app.close()
    }

    @Test
    fun aLaterPlaybackOfTheTitleReplacesItsPromptAndNotYetDismissesIt() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        w.installMetaAddon()
        val app = w.open()
        app.playback.play(w.movie(), INFUSE.id)
        w.settle()
        app.playback.play(w.episode(), INFUSE.id)
        w.settle()
        assertEquals(2, w.data.deviceSettings.value(DeviceSetting.PlayerAppPrompts).size, "one per title")

        // Tortuga's player plays the movie: its prompt goes.
        app.playback.play(w.movie(), PlayerApp.TORTUGA_ID)
        w.settle()
        assertEquals(listOf(SERIES.id), w.data.deviceSettings.value(DeviceSetting.PlayerAppPrompts).map { it.metaId })
        app.navigator.back()

        val detail = app.detailStore(Route.Detail("series", SERIES.id))
        w.settle()
        assertNotNull(detail.state.value.playerApp)
        detail.answerPlayerApp(finished = false)
        w.settle()
        assertNull(detail.state.value.playerApp)
        assertEquals(emptyList(), w.data.deviceSettings.value(DeviceSetting.PlayerAppPrompts))
        assertTrue(WatchedBitField.of(w.item(SERIES.id)?.state?.watchedVideos, SERIES).let { !it.isWatched("tt1:1:1") })
        detail.release()
        app.close()
    }

    @Test
    fun aFailedLaunchSaysSoAndPlayInTortugaPlaysItThere() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        val app = w.open()
        w.launcher.answer = { PlayerAppLaunch.Failed(PlayerAppFailure.NOT_INSTALLED) }
        app.playback.play(w.movie(), VLC.id)
        w.settle()
        val notInstalled = PlayerAppBanner("\u2068VLC\u2069 isn't installed on this device.", busy = false, playInTortugaText = "Play in Tortuga", dismissText = "Dismiss")
        assertEquals(notInstalled, app.state.value.playerApp)
        assertTrue(w.platform.playbacks.isEmpty(), "no silent fallback")

        app.playInTortuga()
        w.settle()
        assertEquals(1, w.platform.playbacks.size)
        assertEquals(Route.Player, app.navigator.current)
        assertNull(app.state.value.playerApp)
        app.navigator.back()

        w.launcher.answer = { PlayerAppLaunch.Failed(PlayerAppFailure.OTHER) }
        app.playback.play(w.movie(), VLC.id)
        w.settle()
        assertEquals("\u2068VLC\u2069 didn't start.", app.state.value.playerApp?.text)
        app.dismissPlayerApp()
        assertNull(app.state.value.playerApp)
        assertNull(w.data.streamHistory.lastStream(w.profileId, MOVIE.id, MOVIE.id), "nothing recorded for a play that didn't start")

        // A live player that started, then died before it opened the file (a crash, a failing exit), didn't start.
        w.launcher.answer = { PlayerAppLaunch.Launched(it.capabilities.progress) }
        app.playback.play(w.movie(), VLC.id)
        w.settle()
        assertNull(app.state.value.playerApp, "the banner went once VLC started")
        w.launcher.report(PlayerAppEvent.Ended(null, null, completed = false, failed = true))
        w.settle()
        assertEquals(
            PlayerAppBanner("\u2068VLC\u2069 didn't start.", busy = false, playInTortugaText = "Play in Tortuga", dismissText = "Dismiss"),
            app.state.value.playerApp,
        )
        assertNull(w.data.streamHistory.lastStream(w.profileId, MOVIE.id, MOVIE.id), "nothing recorded for a play that didn't start")
        app.playInTortuga()
        w.settle()
        assertEquals(2, w.platform.playbacks.size, "Play in Tortuga plays it there")
        app.close()
    }

    @Test
    fun anAndroidPlayerThatSaysItDidNotStartRecordsNothingAndSaysSo() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        val app = w.open()
        app.playback.play(w.movie(), MX_PLAYER.id)
        w.settle()
        // MX Player's or mpv-android's cancel, Vimu's failure: a result that says it failed, and nothing of where.
        w.launcher.report(PlayerAppEvent.Ended(null, null, completed = false, failed = true))
        w.settle()
        assertEquals(
            PlayerAppBanner("\u2068MX Player\u2069 didn't start.", busy = false, playInTortugaText = "Play in Tortuga", dismissText = "Dismiss"),
            app.state.value.playerApp,
        )
        assertEquals(emptyList(), w.data.deviceSettings.value(DeviceSetting.PlayerAppPrompts), "nothing to ask")
        assertNull(w.data.streamHistory.lastStream(w.profileId, MOVIE.id, MOVIE.id), "the stream that didn't play isn't the video's last")
        assertTrue(w.observer.events.isEmpty())
        app.dismissPlayerApp()

        // Failed once it played: where it stopped is recorded.
        app.playback.play(w.movie(), MX_PLAYER.id)
        w.settle()
        w.launcher.report(PlayerAppEvent.Ended(60_000, 3_000_000, completed = false, failed = true))
        w.settle()
        assertNull(app.state.value.playerApp)
        assertEquals(60_000, w.item(MOVIE.id)?.state?.timeOffset)
        assertNotNull(w.data.streamHistory.lastStream(w.profileId, MOVIE.id, MOVIE.id))
        app.close()
    }

    @Test
    fun aChosenPlayerNotFoundACancelAndAResolveFailureEachShowTheirBanner() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        w.data.deviceSettings.set(DeviceSetting.Player, "potplayer")
        w.data.deviceSettings.set(DeviceSetting.PlayerName, "PotPlayer")
        val app = w.open()
        app.playback.play(w.movie())
        w.settle()
        assertEquals(
            PlayerAppBanner(T.playerApps.banner.notInstalled(isolate("PotPlayer")), busy = false, playInTortugaText = T.playerApps.banner.playInTortuga, dismissText = T.playerApps.banner.dismiss),
            app.state.value.playerApp,
        )
        assertTrue(w.launcher.launches.isEmpty())

        // Cancel while the stream resolves: nothing starts.
        app.playback.play(w.movie(), VLC.id)
        assertEquals(true, app.state.value.playerApp?.busy)
        app.dismissPlayerApp()
        w.settle()
        assertNull(app.state.value.playerApp)
        assertTrue(w.launcher.launches.isEmpty())

        // A link that doesn't resolve is worded as the player words it, with nothing to fall back on.
        w.routes.remove("addon.example")
        app.playback.play(w.movie(), VLC.id)
        w.settle()
        assertEquals(PlayerAppBanner(T.player.error.notFound, busy = false, playInTortugaText = null, dismissText = T.playerApps.banner.dismiss), app.state.value.playerApp)
        assertTrue(w.launcher.launches.isEmpty())
        app.close()
    }

    @Test
    fun aDownloadHandsOffItsFileAndSavedSubtitlesUnlessTheAppsCantReadThem() = runTest {
        val w = PlayerAppsWorld(this)
        w.data.settings.set(w.profileId, ProfileSetting.SubtitleLanguages, listOf("fr"))
        val app = w.open()
        val local = LocalVideo(
            downloadId = 7,
            path = "/data/Movies/Movie.mkv",
            size = 2_000_000L,
            videoHash = "0123456789abcdef",
            filename = "Movie.mkv",
            subtitles = listOf(LocalSubtitle("/data/Movies/Movie.en.srt", "English", "eng"), LocalSubtitle("/data/Movies/Movie.fr.srt", "Français", "fre")),
            stream = choice(urlStream("https://addon.example/movie")),
        )
        app.playback.play(localPlayRequest(MOVIE, MOVIE.id, local), VLC.id)
        w.settle()
        val media = w.launcher.media
        assertEquals(local.path, media.url)
        assertEquals(true, media.local)
        assertEquals(emptyMap(), media.headers)
        assertEquals("Movie.mkv", media.filename)
        assertEquals(
            listOf(
                PlayerAppSubtitle("/data/Movies/Movie.en.srt", local = true, title = "English", language = "eng"),
                PlayerAppSubtitle("/data/Movies/Movie.fr.srt", local = true, title = "Français", language = "fre", selected = true),
            ),
            media.subtitles,
        )
        assertTrue(w.http.requests.isEmpty(), "nothing resolves and no addon is asked")
        app.close()

        val apple = PlayerAppsWorld(this, FakePlayerLauncher(handsOffFiles = false))
        val phone = apple.open()
        phone.playback.play(localPlayRequest(MOVIE, MOVIE.id, local), VLC.id)
        apple.settle()
        assertTrue(apple.launcher.launches.isEmpty())
        assertEquals(1, apple.platform.playbacks.size, "other apps can't read the file: Tortuga plays it")
        phone.close()
    }

    @Test
    fun theStreamsSheetClosesOnceThePlayerAppStarted() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        val app = w.open()
        val detail = Route.Detail("movie", MOVIE.id)
        app.navigator.navigate(detail)
        app.navigator.openStreams(MOVIE, MOVIE.id)
        w.launcher.answer = { PlayerAppLaunch.Failed(PlayerAppFailure.OTHER) }
        app.playback.play(w.movie(), VLC.id)
        w.settle()
        assertIs<Route.Streams>(app.navigator.current, "a failed hand-off leaves the sheet for another pick")

        w.launcher.answer = { PlayerAppLaunch.Launched(it.capabilities.progress) }
        app.playback.play(w.movie(), VLC.id)
        w.settle()
        assertEquals(listOf(Route.Board, detail), app.navigator.backStack.value)
        assertEquals(detail, app.navigator.current)
        app.close()
    }
}
