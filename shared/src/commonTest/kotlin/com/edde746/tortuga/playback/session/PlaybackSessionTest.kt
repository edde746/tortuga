package com.edde746.tortuga.playback.session

import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.SkipMode
import com.edde746.tortuga.data.settings.StreamQuality
import com.edde746.tortuga.data.settings.SubtitleMode
import com.edde746.tortuga.input.VideoClickAction
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamProxyHeaders
import com.edde746.tortuga.playback.Chapter
import com.edde746.tortuga.playback.EngineError
import com.edde746.tortuga.playback.EngineErrorKind
import com.edde746.tortuga.playback.MediaAccessibilityPreferences
import com.edde746.tortuga.playback.PlaybackFailure
import com.edde746.tortuga.playback.PlaybackOptions
import com.edde746.tortuga.playback.SubtitleStyle
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.displayText
import com.edde746.tortuga.playback.resolve.OpenSubtitlesHash
import com.edde746.tortuga.playback.resolve.ResolveFailure
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.resolve.redirect
import com.edde746.tortuga.playback.resolve.serveFile
import com.edde746.tortuga.presentation.board.toContinueWatchingCard
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PlaybackSessionTest {
    private val file = lcgBytes(300_000)

    /** `addon.example/<name>` is a resolve link that redirects to `cdn.example/<name>.mkv`. */
    private fun SessionHarness.redirectLinks(delaySeconds: Int = 0) {
        serveCdn(file)
        routes["addon.example"] = { request ->
            if (delaySeconds > 0) delay(delaySeconds.seconds)
            redirect("https://cdn.example/${request.url.segments.last()}.mkv")
        }
    }

    private fun movie(vararg alternatives: Stream) =
        PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/movie")), alternatives.map { choice(it) })

    private fun firstEpisode(stream: Stream = urlStream("https://addon.example/e1")) = PlayRequest(SERIES, "tt1:1:1", choice(stream))

    private fun secondEpisode(stream: Stream = urlStream("https://addon.example/e2")) = PlayRequest(SERIES, "tt1:1:2", choice(stream))

    private fun snapshot(meta: MetaItem) = MetaSnapshot(meta.id, meta.type, meta.name, null, "poster")

    @Test
    fun resolvesFirstThenLoadsTheFinalUrlWithTheAddonHeadersAndAUserAgent() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks(delaySeconds = 20)
        h.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/movie", headers = mapOf("X-Token" to "abc")))))
        assertTrue(h.session.info.value.resolving)
        assertEquals("The Shawshank Redemption", h.session.info.value.title)
        assertNull(h.session.info.value.subtitle)

        h.advance(19.seconds)
        assertTrue(h.engine.loads.isEmpty(), "still resolving: an addon's host may take this long")
        h.advance(2.seconds)
        val load = h.engine.loads.single()
        assertEquals("https://cdn.example/movie.mkv", load.url)
        assertEquals(mapOf("X-Token" to "abc", "User-Agent" to "Tortuga/test"), load.headers)
        assertEquals(0.0, load.startSeconds)
        assertFalse(h.session.info.value.resolving)
        assertEquals("abc", h.http.requests.first().headers["X-Token"], "the resolve sends the addon's headers too")
    }

    @Test
    fun theArtworkIsTheEpisodesThumbnailElseTheBackdropElseThePoster() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        val preview = MetaItemPreview(id = "tt3", type = "series", name = "Show", poster = "https://img.example/poster.jpg", background = "https://img.example/backdrop.jpg")
        val meta = MetaItem(preview, videos = listOf(episode(1, 1).copy(thumbnail = "https://img.example/e1.jpg"), episode(1, 2)))
        val stream = choice(urlStream("https://addon.example/e1"))
        h.start(PlayRequest(meta, "tt1:1:1", stream))
        assertEquals("https://img.example/e1.jpg", h.session.info.value.artworkUrl)
        h.start(PlayRequest(meta, "tt1:1:2", stream))
        assertEquals("https://img.example/backdrop.jpg", h.session.info.value.artworkUrl)
        h.start(PlayRequest(meta.copy(preview = preview.copy(background = "")), "tt1:1:2", stream))
        assertEquals("https://img.example/poster.jpg", h.session.info.value.artworkUrl)
        h.start(PlayRequest(MOVIE, MOVIE.id, stream))
        assertNull(h.session.info.value.artworkUrl)
    }

    @Test
    fun anAddonUserAgentIsKept() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.start(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/movie", headers = mapOf("user-agent" to "VLC/3.0")))))
        assertEquals(mapOf("User-Agent" to "VLC/3.0"), h.engine.loads.single().headers)
    }

    @Test
    fun anHlsTypeInTheResponseHeadersOpensTheFileAsHls() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        fun answered(name: String, type: String) = urlStream("https://addon.example/$name").let {
            it.copy(behaviorHints = it.behaviorHints.copy(proxyHeaders = StreamProxyHeaders(response = mapOf(" content-type" to type))))
        }
        h.start(PlayRequest(MOVIE, MOVIE.id, choice(answered("live", "application/vnd.apple.mpegURL; charset=utf-8")), listOf(choice(answered("mp4", "video/mp4")))))
        assertEquals("hls", h.engine.loads.single().demuxerFormat)

        h.session.playNextStream()
        h.settle()
        assertNull(h.engine.loads.last().demuxerFormat, "another type leaves it to the probe")
    }

    @Test
    fun aFailedResolveIsShownAndRetryResolvesAgain() = runTest {
        val h = SessionHarness(this)
        h.serveCdn(file)
        var answers = 0
        h.routes["addon.example"] = {
            answers++
            if (answers == 1) respond("", HttpStatusCode.NotFound) else redirect("https://cdn.example/movie.mkv")
        }
        h.start(movie())
        assertEquals(PlaybackFailure.Resolve(ResolveFailure.Http(404)), h.session.info.value.failure)
        assertFalse(h.session.info.value.resolving)
        assertTrue(h.engine.loads.isEmpty())

        h.session.retry()
        h.settle()
        assertNull(h.session.info.value.failure)
        assertEquals("https://cdn.example/movie.mkv", h.engine.loads.single().url)
    }

    @Test
    fun aFileLoadsWithTheDeviceSettingsOfItsLoadAndAChangeWaitsForTheNextFile() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.start(movie())
        val mib = 1024L * 1024
        assertEquals(PlaybackOptions("videotoolbox", 150 * mib, 50 * mib), h.engine.loads.single().options)
        h.engine.open(duration = 3000.0)
        h.settle()

        h.data.deviceSettings.set(DeviceSetting.HardwareDecoding, false)
        h.data.deviceSettings.set(DeviceSetting.CacheSizeMegabytes, 512)
        h.settle()
        assertEquals(1, h.engine.loads.size, "the file playing keeps what it loaded with")

        h.session.retry()
        h.settle()
        assertEquals(PlaybackOptions("no", 512 * mib, 512 * mib / 3), h.engine.loads.last().options)
    }

    @Test
    fun theSubtitleStyleIsSentAtOnceAndAgainWhenASettingChangesMidFile() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.settle()
        assertEquals(listOf(SubtitleStyle.DEFAULT), h.engine.subtitleStyles, "the device's style before anything loads")
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()

        h.data.deviceSettings.set(DeviceSetting.SubtitleSize, 150)
        h.data.deviceSettings.set(DeviceSetting.HardwareDecoding, false)
        h.settle()
        assertEquals(1, h.engine.loads.size, "the file playing goes on")
        assertEquals(57.0, h.engine.subtitleStyles.last().fontSize, "the change shows on the file playing")
        assertEquals(2, h.engine.subtitleStyles.size, "a setting outside the style sends nothing")

        h.session.close()
        h.data.deviceSettings.set(DeviceSetting.SubtitleBold, true)
        h.settle()
        assertEquals(2, h.engine.subtitleStyles.size, "nothing reaches a released engine")
    }

    @Test
    fun resumesWhereTheLibraryLeftOff() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.data.library.upsertFromMeta(h.profileId, snapshot(MOVIE))
        h.data.library.recordProgress(h.profileId, MOVIE.id, MOVIE.id, 600_000, 3_000_000)
        h.start(movie())
        assertEquals(600.0, h.engine.loads.single().startSeconds)
    }

    @Test
    fun pastTheCreditsItStartsOverAndTheResumePointFollows() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.data.library.upsertFromMeta(h.profileId, snapshot(MOVIE))
        h.data.library.recordProgress(h.profileId, MOVIE.id, MOVIE.id, 2_900_000, 3_000_000)
        h.start(movie())
        assertEquals(0.0, h.engine.loads.single().startSeconds)
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(0, h.item(MOVIE.id)?.state?.timeOffset)
    }

    @Test
    fun progressIsSavedWhilePlayingAndOnPauseSeekAndClose() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(MOVIE.id, h.item(MOVIE.id)?.state?.videoId, "the stream's first frame puts the item in the library")
        assertEquals(listOf(true), h.sync.playing)

        h.playTo(20.0)
        assertEquals(15_000, h.item(MOVIE.id)?.state?.timeOffset, "saved every 15 s while playing")

        h.engine.setPaused(true)
        h.settle()
        assertEquals(20_000, h.item(MOVIE.id)?.state?.timeOffset)
        assertEquals(false, h.sync.playing.last())
        val flushes = h.sync.flushes
        assertTrue(flushes >= 1, "a pause pushes the progress")
        h.engine.setPaused(false)
        h.settle()

        // The store reports the seek, then sends it to the engine.
        h.session.onSeek(1000.0)
        h.engine.seek(1000.0, exact = false)
        h.settle()
        val afterSeek = h.item(MOVIE.id)!!.state
        assertEquals(1_000_000, afterSeek.timeOffset)
        assertEquals(20_000, afterSeek.timeWatched, "a jump is not time watched")

        h.playTo(1010.0)
        h.session.close()
        h.settle()
        val closed = h.item(MOVIE.id)!!.state
        assertEquals(1_010_000, closed.timeOffset)
        assertEquals(30_000, closed.timeWatched)
        assertTrue(h.engine.released)
        assertTrue(h.sync.flushes > flushes)
    }

    @Test
    fun aRunOfSeeksPushesOnceTwoSecondsAfterTheLast() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        val flushes = h.sync.flushes

        // A held seek key: a seek every half second.
        for (target in listOf(100.0, 110.0, 120.0, 130.0)) {
            h.session.onSeek(target)
            h.engine.seek(target, exact = false)
            h.advance(500.milliseconds)
        }
        assertEquals(flushes, h.sync.flushes, "nothing while seeking")
        h.advance(1.seconds)
        assertEquals(flushes, h.sync.flushes)
        h.advance(1.seconds)
        assertEquals(flushes + 1, h.sync.flushes, "one push 2 s after the last seek")
        assertEquals(130_000, h.item(MOVIE.id)!!.state.timeOffset)
    }

    @Test
    fun theStreamIsRememberedOnceItOpens() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        val stream = urlStream("https://addon.example/movie", name = "1080p")
        h.start(PlayRequest(MOVIE, MOVIE.id, choice(stream)))
        assertNull(h.data.streamHistory.lastStream(h.profileId, MOVIE.id, MOVIE.id), "not before it plays")
        h.engine.open(duration = 3000.0)
        h.settle()
        val entry = h.data.streamHistory.lastStream(h.profileId, MOVIE.id, MOVIE.id)!!
        assertEquals(STREAMS_URL, entry.transportUrl)
        assertEquals(stream, entry.stream)
    }

    @Test
    fun preparingForAnotherTitlePausesTheVideoItLeaves() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.open(PlayRequest(MOVIE, MOVIE.id, choice(urlStream("https://addon.example/movie"))), duration = 3000.0)
        h.playTo(30.0)
        assertFalse(h.engine.state.paused)
        h.session.prepare("Chernobyl", "S1 E1", artworkUrl = null, resolving = true)
        h.settle()
        assertTrue(h.engine.state.paused, "the left video is no longer heard under the search for the room's file")
        assertTrue(h.session.info.value.resolving)
        assertEquals("Chernobyl", h.session.info.value.title)
    }

    /** Starts [request] and opens its file, [duration] seconds long, where it starts. */
    private fun SessionHarness.open(request: PlayRequest, duration: Double = 100.0) {
        start(request)
        engine.open(duration)
        settle()
    }

    /** A seek in the player: the store reports it, then sends it to the engine. */
    private fun SessionHarness.seekTo(seconds: Double) {
        session.onSeek(seconds)
        engine.seek(seconds, exact = false)
        settle()
    }

    private suspend fun SessionHarness.watched(videoId: String) =
        WatchedBitField.of(item(SERIES.id)?.state?.watchedVideos, SERIES).isWatched(videoId)

    private suspend fun SessionHarness.continueWatching() =
        data.library.observeContinueWatching(profileId, 10).first().map { it.toContinueWatchingCard() }

    @Test
    fun anEpisodeLeftJustShortOfTheThresholdStaysUnwatchedAndResumesThere() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.open(firstEpisode())
        h.playTo(69.9)
        h.session.close()
        h.settle()
        val item = h.item(SERIES.id)!!
        assertFalse(h.watched("tt1:1:1"), "69.9 of 100 s is short of the default 70 %")
        assertEquals(0, item.state.timesWatched)
        assertEquals("tt1:1:1", h.continueWatching().single().videoId)
        assertEquals(69.9, PlaybackSession.resumePoint(item, "tt1:1:1"))
    }

    @Test
    fun anEpisodeAtTheThresholdIsWatchedAndContinueWatchingShowsTheNextOneFromItsStart() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.open(firstEpisode())
        h.playTo(70.0)
        // Played on, then back: nothing recorded after it counted moves the pointer back.
        h.playTo(80.0)
        h.seekTo(10.0)
        h.session.close()
        h.settle()
        assertTrue(h.watched("tt1:1:1"))
        val item = h.item(SERIES.id)!!
        assertEquals(1, item.state.timesWatched)
        val card = h.continueWatching().single()
        assertEquals("tt1:1:2", card.videoId)
        assertEquals(0f, card.progress)
        assertEquals(0.0, PlaybackSession.resumePoint(item, "tt1:1:2"))
    }

    @Test
    fun aSeekPastTheThresholdThenLeavingAtOnceCounts() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.open(firstEpisode())
        h.playTo(5.0)
        // Back right after the seek, before the engine got there.
        h.session.onSeek(80.0)
        h.session.close()
        h.settle()
        assertTrue(h.watched("tt1:1:1"))
        assertEquals("tt1:1:2", h.continueWatching().single().videoId)
    }

    @Test
    fun anEpisodeResumedAtFortyPercentAndLeftAtSeventyFiveCounts() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.data.library.upsertFromMeta(h.profileId, snapshot(SERIES))
        h.data.library.recordProgress(h.profileId, SERIES.id, "tt1:1:1", 40_000, 100_000)
        h.start(firstEpisode())
        assertEquals(40.0, h.engine.loads.single().startSeconds)
        h.engine.open(duration = 100.0)
        h.settle()
        h.playTo(75.0)
        h.session.close()
        h.settle()
        assertTrue(h.watched("tt1:1:1"), "75 % reached, though only 35 % of it played")
        assertEquals("tt1:1:2", h.continueWatching().single().videoId)
    }

    @Test
    fun theLastEpisodeCountedAsWatchedLeavesContinueWatching() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        // S1E2 is the last regular episode: the next is a special, which the next episode never moves into.
        h.open(secondEpisode())
        h.playTo(75.0)
        h.session.close()
        h.settle()
        assertTrue(h.watched("tt1:1:2"))
        assertEquals("tt1:1:2", h.item(SERIES.id)!!.state.videoId)
        assertEquals(emptyList(), h.continueWatching())
    }

    @Test
    fun killedPastTheThresholdTheLibraryAlreadyShowsTheNextEpisode() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.open(firstEpisode())
        // 2 s past 70 %, 12 s after the last periodic save: the app is killed here, so no pause, leave or close.
        h.playTo(72.0)
        assertTrue(h.watched("tt1:1:1"))
        assertEquals("tt1:1:2", h.continueWatching().single().videoId)
    }

    @Test
    fun aProfileThatSetItsOwnThresholdKeepsIt() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.data.settings.set(h.profileId, ProfileSetting.WatchedThresholdPercent, 80)
        h.open(firstEpisode())
        h.playTo(75.0)
        h.engine.setPaused(true)
        h.settle()
        assertFalse(h.watched("tt1:1:1"), "75 % is short of the profile's 80 %")
        assertEquals("tt1:1:1", h.item(SERIES.id)!!.state.videoId)
        h.engine.setPaused(false)
        h.playTo(80.0)
        assertTrue(h.watched("tt1:1:1"))
        assertEquals("tt1:1:2", h.item(SERIES.id)!!.state.videoId)
    }

    @Test
    fun anEpisodeLeftPastTheCreditsUnderAHigherThresholdMovesOnUnwatched() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.data.settings.set(h.profileId, ProfileSetting.WatchedThresholdPercent, 95)
        h.open(firstEpisode())
        h.playTo(92.0)
        h.session.close()
        h.settle()
        assertFalse(h.watched("tt1:1:1"), "92 % is short of the profile's 95 %")
        assertEquals("tt1:1:2", h.continueWatching().single().videoId, "past 90 %, as before")
    }

    @Test
    fun aMoviePastTheThresholdIsWatchedAndStillResumesWhereItWasLeft() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.open(movie(), duration = 7200.0)
        // 83 % of two hours: past 70 %, short of the credits (90 %).
        h.seekTo(6000.0)
        h.session.close()
        h.settle()
        val item = h.item(MOVIE.id)!!
        assertEquals(1, item.state.timesWatched)
        assertEquals(listOf(MOVIE.id), h.continueWatching().map { it.id })
        assertEquals(6000.0, PlaybackSession.resumePoint(item, MOVIE.id))
    }

    @Test
    fun aMovieLeftInTheCreditsStartsOverNextTime() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.open(movie(), duration = 3000.0)
        h.seekTo(2900.0)
        h.session.close()
        h.settle()
        val item = h.item(MOVIE.id)!!
        assertEquals(1, item.state.timesWatched)
        assertEquals(0, item.state.timeOffset)
        assertEquals(emptyList(), h.continueWatching())
    }

    @Test
    fun theCreditsStartingBeforeTheThresholdCountTheEpisodeThere() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.open(firstEpisode(), duration = 3000.0)
        h.engine.report { it.copy(chapters = listOf(Chapter("Episode", 0.0), Chapter("End Credits", 1800.0))) }
        h.settle()
        h.seekTo(1700.0)
        assertFalse(h.watched("tt1:1:1"))
        // 63 %: before the 70 % threshold, past the credits' start.
        h.seekTo(1900.0)
        assertTrue(h.watched("tt1:1:1"))
        assertEquals("tt1:1:2", h.item(SERIES.id)!!.state.videoId)
    }

    @Test
    fun leavingBeforeTheCreditsChapterKeepsThePosition() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.start(movie())
        h.engine.open(duration = 3000.0)
        // Credits followed by a scene don't run to the end: they don't finish the movie.
        h.engine.report { it.copy(chapters = listOf(Chapter("Movie", 0.0), Chapter("End Credits", 2400.0), Chapter("Post-credits scene", 2600.0))) }
        h.settle()
        h.session.onSeek(2500.0)
        h.engine.seek(2500.0, exact = false)
        h.settle()
        h.session.close()
        h.settle()
        assertEquals(2_500_000, h.item(MOVIE.id)!!.state.timeOffset)
    }

    @Test
    fun theProfilesPlayerSettingsReachThePlayerWhileItPlays() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.data.settings.set(h.profileId, ProfileSetting.UpNextCountdownSeconds, 25)
        h.start(firstEpisode())
        assertEquals(NextEpisode("Please Remain Calm", "S1E2", autoplay = true, countdownSeconds = 25), h.session.info.value.nextEpisode)
        assertEquals(SkipModes(), h.session.info.value.skipping)
        assertEquals(VideoClickAction.PAUSE, h.session.info.value.videoClick)
        h.engine.open(duration = 3000.0)
        h.settle()

        h.data.settings.set(h.profileId, ProfileSetting.SkipIntros, SkipMode.AUTO)
        h.data.settings.set(h.profileId, ProfileSetting.SkipCredits, SkipMode.OFF)
        h.data.settings.set(h.profileId, ProfileSetting.UpNextCountdownSeconds, 5)
        h.data.settings.set(h.profileId, ProfileSetting.BingeWatching, false)
        h.data.settings.set(h.profileId, ProfileSetting.VideoClick, VideoClickAction.CONTROLS)
        h.settle()
        assertEquals(SkipModes(intros = SkipMode.AUTO, credits = SkipMode.OFF), h.session.info.value.skipping)
        assertEquals(NextEpisode("Please Remain Calm", "S1E2", autoplay = false, countdownSeconds = 5), h.session.info.value.nextEpisode)
        assertEquals(VideoClickAction.CONTROLS, h.session.info.value.videoClick)
    }

    @Test
    fun theNextEpisodePlaysFromTheSameBingeGroup() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.routes["streams.example.com"] = { request ->
            assertEquals("tt1:1:2.json", request.url.segments.last())
            json(
                """{"streams":[
                    {"url":"https://addon.example/e2-720","behaviorHints":{"bingeGroup":"hq-720"}},
                    {"url":"https://addon.example/e2-1080","behaviorHints":{"bingeGroup":"hq-1080"}}
                ]}""",
            )
        }
        h.start(firstEpisode(urlStream("https://addon.example/e1", bingeGroup = "hq-1080")))
        assertEquals("S1E1 · \u20681:23:45\u2069", h.session.info.value.subtitle)
        assertEquals(NextEpisode("Please Remain Calm", "S1E2", autoplay = true), h.session.info.value.nextEpisode)
        h.engine.open(duration = 3000.0)
        h.settle()

        h.session.playNextEpisode()
        h.settle()
        assertEquals("https://cdn.example/e2-1080.mkv", h.engine.loads.last().url)
        assertEquals(0.0, h.engine.loads.last().startSeconds)
        assertEquals("S1E2 · \u2068Please Remain Calm\u2069", h.session.info.value.subtitle)
        assertNull(h.session.info.value.nextEpisode, "the special after the season isn't next")
        assertEquals(1, h.session.info.value.alternativeStreams, "the other stream of the episode")
        assertEquals("tt1:1:2", h.item(SERIES.id)!!.state.videoId)
    }

    @Test
    fun theBingePicksFallbacksPassTheFiltersOnceEach() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.data.settings.set(h.profileId, ProfileSetting.HiddenStreamQualities, listOf(StreamQuality.UHD_4K))
        val binge = urlStream("https://addon.example/e2-1080", bingeGroup = "hq-1080")
        val hd = urlStream("https://addon.example/e2-720")
        val uhd = urlStream("https://addon.example/e2-2160", name = "Streams\n2160p")
        // The addon sent the title with episode 2's streams, and its `/stream` answer lists them again.
        val series = SERIES.copy(addon = STREAM_ADDON, videos = SERIES.videos.map { if (it.id == "tt1:1:2") it.copy(streams = listOf(binge, hd)) else it })
        h.routes["streams.example.com"] = {
            json("""{"streams":[${listOf(uhd, hd, binge).joinToString(",") { AddonJson.encodeToString(Stream.serializer(), it) }}]}""")
        }
        h.start(PlayRequest(series, "tt1:1:1", choice(urlStream("https://addon.example/e1", bingeGroup = "hq-1080"))))
        h.engine.open(duration = 3000.0)
        h.settle()

        h.session.playNextEpisode()
        h.settle()
        assertEquals("https://cdn.example/e2-1080.mkv", h.engine.loads.last().url)
        assertEquals(1, h.session.info.value.alternativeStreams, "the 720p stream once; the profile hides 2160p")
        h.session.playNextStream()
        h.settle()
        assertEquals("https://cdn.example/e2-720.mkv", h.engine.loads.last().url)
        assertEquals(0, h.session.info.value.alternativeStreams)
    }

    @Test
    fun theNextEpisodeFromTheEndOfAFileOpensPlaying() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.routes["streams.example.com"] = { json("""{"streams":[{"url":"https://addon.example/e2","behaviorHints":{"bingeGroup":"hq-1080"}}]}""") }
        h.start(firstEpisode(urlStream("https://addon.example/e1", bingeGroup = "hq-1080")))
        h.engine.open(duration = 3000.0)
        h.engine.report { it.copy(positionSeconds = 3000.0, eof = true, paused = true) }
        h.settle()

        h.session.playNextEpisode()
        h.settle()
        assertEquals("https://cdn.example/e2.mkv", h.engine.loads.last().url)
        assertEquals(false, h.engine.state.paused, "mpv pauses at the end of a file until the next one replaces it")
    }

    @Test
    fun withoutABingeMatchTheNextEpisodePlaysItsRememberedFileWithTheLinkItsAddonGivesNow() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        // The addon makes a new link for every answer (a `/playback/…` link): the one the stream history saved, on
        // another device or from an earlier answer, may be good only there and then.
        h.routes["streams.example.com"] = {
            json("""{"streams":[{"url":"https://addon.example/e2-other"},{"url":"https://addon.example/e2-now","behaviorHints":{"filename":"Show.S01E02.mkv"}}]}""")
        }
        val remembered = urlStream("https://addon.example/e2-saved").let { it.copy(behaviorHints = it.behaviorHints.copy(filename = "Show.S01E02.mkv")) }
        h.data.streamHistory.record(h.profileId, SERIES.id, "tt1:1:2", AddonJson.encodeToString(Stream.serializer(), remembered), STREAMS_URL)
        h.start(firstEpisode())
        h.engine.open(duration = 3000.0)
        h.settle()

        h.session.playNextEpisode()
        h.settle()
        assertEquals("https://cdn.example/e2-now.mkv", h.engine.loads.last().url)
        assertEquals(1, h.session.info.value.alternativeStreams)
        assertTrue(h.http.requests.none { it.url.toString() == "https://addon.example/e2-saved" }, "the saved link is never opened")
    }

    @Test
    fun withoutABingeMatchAndItsRememberedFileNoLongerOfferedTheUserIsAsked() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.routes["streams.example.com"] = { json("""{"streams":[{"url":"https://addon.example/e2-other","behaviorHints":{"filename":"Show.S01E02.720p.mkv"}}]}""") }
        val remembered = urlStream("https://addon.example/e2-saved").let { it.copy(behaviorHints = it.behaviorHints.copy(filename = "Show.S01E02.mkv")) }
        h.data.streamHistory.record(h.profileId, SERIES.id, "tt1:1:2", AddonJson.encodeToString(Stream.serializer(), remembered), STREAMS_URL)
        h.start(firstEpisode())
        h.engine.open(duration = 3000.0)
        h.settle()

        h.session.playNextEpisode()
        h.settle()
        assertEquals(listOf<SessionEvent>(SessionEvent.ChooseStream(SERIES, "tt1:1:2")), h.events)
        assertEquals(1, h.engine.loads.size)
    }

    @Test
    fun withNothingToContinueWithTheUserIsAsked() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.routes["streams.example.com"] = { json("""{"streams":[{"url":"https://addon.example/e2","behaviorHints":{"bingeGroup":"other"}}]}""") }
        h.start(firstEpisode(urlStream("https://addon.example/e1", bingeGroup = "hq-1080")))
        h.engine.open(duration = 3000.0)
        h.settle()

        h.session.playNextEpisode()
        h.settle()
        assertEquals(listOf<SessionEvent>(SessionEvent.ChooseStream(SERIES, "tt1:1:2")), h.events)
        assertEquals(1, h.engine.loads.size)
        assertEquals(true, h.engine.pauses.last())
        assertFalse(h.session.info.value.resolving)
    }

    @Test
    fun bingeWatchingOffLeavesTheNextEpisodeToTheUser() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.data.settings.set(h.profileId, ProfileSetting.BingeWatching, false)
        h.start(firstEpisode())
        assertEquals(NextEpisode("Please Remain Calm", "S1E2", autoplay = false), h.session.info.value.nextEpisode)
    }

    @Test
    fun autoPlayNextTurnedOffInThePlayerAppliesAtOnceAndIsTheProfilesBingeWatching() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.start(firstEpisode())
        h.session.setAutoPlayNext(false)
        assertEquals(NextEpisode("Please Remain Calm", "S1E2", autoplay = false), h.session.info.value.nextEpisode)
        h.settle()
        assertFalse(h.data.settings.settings(h.profileId).bingeWatching)
    }

    @Test
    fun withAutoPlayTheNextEpisodeWithoutABingeMatchPlaysTheFirstStreamThatPasses() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.data.settings.set(h.profileId, ProfileSetting.StreamAutoPlay, AutoPlayMode.FIRST_MATCH)
        h.data.settings.set(h.profileId, ProfileSetting.HiddenStreamQualities, listOf(StreamQuality.UHD_4K))
        h.routes["streams.example.com"] = {
            json(
                """{"streams":[
                    {"name":"Streams\n2160p","url":"https://addon.example/e2-2160","behaviorHints":{"bingeGroup":"other"}},
                    {"name":"Streams\n1080p","url":"https://addon.example/e2-1080"}
                ]}""",
            )
        }
        // First match goes by the list, not by the stream history.
        val remembered = urlStream("https://addon.example/e2-remembered")
        h.data.streamHistory.record(h.profileId, SERIES.id, "tt1:1:2", AddonJson.encodeToString(Stream.serializer(), remembered), STREAMS_URL)
        h.start(firstEpisode(urlStream("https://addon.example/e1", bingeGroup = "hq-1080")))
        assertEquals(PlayingStream("Streams", name = null, autoPlay = null), h.session.info.value.stream)
        h.engine.open(duration = 3000.0)
        h.settle()

        h.session.playNextEpisode()
        h.settle()
        assertEquals("https://cdn.example/e2-1080.mkv", h.engine.loads.last().url)
        assertEquals(PlayingStream("Streams", "Streams 1080p", AutoPlayPick.FIRST_MATCH), h.session.info.value.stream)
        assertEquals(emptyList(), h.events)
    }

    @Test
    fun withReuseLastLinkTheNextEpisodePlaysItsRememberedStreamWhileOffered() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.data.settings.set(h.profileId, ProfileSetting.StreamAutoPlay, AutoPlayMode.REUSE_LAST_LINK)
        h.routes["streams.example.com"] = {
            json("""{"streams":[{"url":"https://addon.example/e2-first"},{"url":"https://addon.example/e2-remembered"}]}""")
        }
        val remembered = urlStream("https://addon.example/e2-remembered")
        h.data.streamHistory.record(h.profileId, SERIES.id, "tt1:1:2", AddonJson.encodeToString(Stream.serializer(), remembered), STREAMS_URL)
        h.start(firstEpisode())
        h.engine.open(duration = 3000.0)
        h.settle()

        h.session.playNextEpisode()
        h.settle()
        assertEquals("https://cdn.example/e2-remembered.mkv", h.engine.loads.last().url)
        assertEquals(AutoPlayPick.LAST_LINK, h.session.info.value.stream?.autoPlay)
        assertEquals(1, h.session.info.value.alternativeStreams)
    }

    @Test
    fun withAutoPlayAndNothingThatPassesTheUserIsAskedWithANotice() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.data.settings.set(h.profileId, ProfileSetting.StreamAutoPlay, AutoPlayMode.FIRST_MATCH)
        h.data.settings.set(h.profileId, ProfileSetting.StreamIncludePattern, "remux")
        h.routes["streams.example.com"] = { json("""{"streams":[{"name":"1080p","url":"https://addon.example/e2"}]}""") }
        h.start(firstEpisode())
        h.engine.open(duration = 3000.0)
        h.settle()

        h.session.playNextEpisode()
        h.settle()
        assertEquals(
            listOf<SessionEvent>(SessionEvent.ChooseStream(SERIES, "tt1:1:2", "Auto-play found no stream that passes your filters.")),
            h.events,
        )
        assertEquals(1, h.engine.loads.size)
    }

    // The previous episode

    @Test
    fun theSessionTellsTheControlsWhichEpisodesThereAre() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.start(firstEpisode())
        h.engine.open(duration = 3000.0)
        h.settle()
        assertTrue(h.session.info.value.episode)
        assertFalse(h.session.info.value.hasPreviousEpisode, "the first episode")
        assertNotNull(h.session.info.value.nextEpisode)
        h.session.playPreviousEpisode()
        h.settle()
        assertEquals(1, h.engine.loads.size, "nothing before the first episode")
        assertEquals(emptyList(), h.events)

        h.start(secondEpisode())
        assertTrue(h.session.info.value.episode)
        assertTrue(h.session.info.value.hasPreviousEpisode)
        assertNull(h.session.info.value.nextEpisode, "the special after the season isn't next")

        h.start(PlayRequest(SERIES, "tt1:0:1", choice(urlStream("https://addon.example/special"))))
        assertTrue(h.session.info.value.episode)
        assertFalse(h.session.info.value.hasPreviousEpisode, "the specials don't lead back into the season")

        h.start(movie())
        assertFalse(h.session.info.value.episode)
        assertFalse(h.session.info.value.hasPreviousEpisode)
        assertNull(h.session.info.value.nextEpisode)
    }

    @Test
    fun thePreviousEpisodePlaysFromTheSameBingeGroup() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.routes["streams.example.com"] = { request ->
            assertEquals("tt1:1:1.json", request.url.segments.last())
            json(
                """{"streams":[
                    {"url":"https://addon.example/e1-720","behaviorHints":{"bingeGroup":"hq-720"}},
                    {"url":"https://addon.example/e1-1080","behaviorHints":{"bingeGroup":"hq-1080"}}
                ]}""",
            )
        }
        h.start(secondEpisode(urlStream("https://addon.example/e2", bingeGroup = "hq-1080")))
        h.engine.open(duration = 3000.0)
        h.settle()

        h.session.playPreviousEpisode()
        h.settle()
        assertEquals("https://cdn.example/e1-1080.mkv", h.engine.loads.last().url)
        assertEquals(0.0, h.engine.loads.last().startSeconds)
        assertEquals("S1E1 · \u20681:23:45\u2069", h.session.info.value.subtitle)
        assertFalse(h.session.info.value.hasPreviousEpisode)
        assertEquals("Please Remain Calm", h.session.info.value.nextEpisode?.title)
        assertEquals(1, h.session.info.value.alternativeStreams, "the other stream of the episode")
        assertEquals("tt1:1:2", h.item(SERIES.id)!!.state.videoId, "the pointer moves once the previous episode plays")
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals("tt1:1:1", h.item(SERIES.id)!!.state.videoId)
    }

    @Test
    fun withAutoPlayThePreviousEpisodeWithoutABingeMatchPlaysTheFirstStreamThatPasses() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.data.settings.set(h.profileId, ProfileSetting.StreamAutoPlay, AutoPlayMode.FIRST_MATCH)
        h.routes["streams.example.com"] = { json("""{"streams":[{"name":"Streams\n1080p","url":"https://addon.example/e1-1080"}]}""") }
        h.start(secondEpisode())
        h.engine.open(duration = 3000.0)
        h.settle()

        h.session.playPreviousEpisode()
        h.settle()
        assertEquals("https://cdn.example/e1-1080.mkv", h.engine.loads.last().url)
        assertEquals(PlayingStream("Streams", "Streams 1080p", AutoPlayPick.FIRST_MATCH), h.session.info.value.stream)
        assertEquals(emptyList(), h.events)
    }

    @Test
    fun withNoStreamForThePreviousEpisodeTheUserIsAsked() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.routes["streams.example.com"] = { json("""{"streams":[{"url":"https://addon.example/e1","behaviorHints":{"bingeGroup":"other"}}]}""") }
        h.start(secondEpisode(urlStream("https://addon.example/e2", bingeGroup = "hq-1080")))
        h.engine.open(duration = 3000.0)
        h.settle()

        h.session.playPreviousEpisode()
        h.settle()
        assertEquals(listOf<SessionEvent>(SessionEvent.ChooseStream(SERIES, "tt1:1:1")), h.events)
        assertEquals(1, h.engine.loads.size)
        assertEquals(true, h.engine.pauses.last())
        assertFalse(h.session.info.value.resolving)
        assertEquals("tt1:1:2", h.item(SERIES.id)!!.state.videoId, "the pointer stays on the episode left")
    }

    @Test
    fun anAutoPlayedStreamThatDoesntStartAsksForAnotherOnce() = runTest {
        val h = SessionHarness(this)
        h.serveCdn(file)
        h.routes["addon.example"] = { respond("", HttpStatusCode.NotFound) }
        h.start(movie())
        assertEquals(PlaybackFailure.Resolve(ResolveFailure.Http(404)), h.session.info.value.failure)
        assertEquals(emptyList(), h.events, "a stream the user picked shows its failure only")

        h.start(movie().copy(autoPlay = AutoPlayPick.FIRST_MATCH))
        assertEquals(PlaybackFailure.Resolve(ResolveFailure.Http(404)), h.session.info.value.failure)
        val asked = SessionEvent.ChooseStream(MOVIE, MOVIE.id, "The stream auto-play picked didn't start. Choose another one.")
        assertEquals(listOf<SessionEvent>(asked), h.events)
        h.session.retry()
        h.settle()
        assertEquals(listOf<SessionEvent>(asked), h.events, "asked once")
    }

    @Test
    fun anExpiredLinkIsResolvedAgainOnceThenTheNextStreamIsOffered() = runTest {
        val h = SessionHarness(this)
        h.serveCdn(file)
        var resolves = 0
        h.routes["addon.example"] = { request ->
            resolves++
            redirect("https://cdn.example/${request.url.segments.last()}-$resolves.mkv")
        }
        h.start(movie(urlStream("https://addon.example/alternative")))
        assertEquals(1, h.session.info.value.alternativeStreams)
        h.engine.open(duration = 3000.0)
        h.settle()
        h.playTo(120.0)

        h.engine.fail("HTTP error 403 Forbidden", httpStatus = 403)
        h.settle()
        assertNull(h.session.info.value.failure, "an expiry the session recovers from isn't shown")
        assertEquals(2, h.engine.loads.size)
        assertEquals("https://cdn.example/movie-2.mkv", h.engine.loads[1].url)
        assertEquals(120.0, h.engine.loads[1].startSeconds)

        h.engine.open(duration = 3000.0)
        h.settle()
        h.playTo(130.0)
        h.engine.fail("HTTP error 403 Forbidden", httpStatus = 403)
        h.settle()
        assertEquals(PlaybackFailure.Engine(EngineError("HTTP error 403 Forbidden", 403)), h.session.info.value.failure)
        assertEquals(2, h.engine.loads.size, "one re-resolve only")
        assertEquals(130_000, h.item(MOVIE.id)!!.state.timeOffset)

        h.session.playNextStream()
        h.settle()
        assertEquals("https://cdn.example/alternative-3.mkv", h.engine.loads[2].url)
        assertEquals(130.0, h.engine.loads[2].startSeconds)
        assertEquals(0, h.session.info.value.alternativeStreams)
        assertNull(h.session.info.value.failure)
    }

    /**
     * A seek past what was read, on a link that died meanwhile: its request answers 403, and FFmpeg makes the failed
     * read the file's end, which mpv reports at the file's length. An end after an HTTP error is the stream failing
     * wherever it comes: a fresh link plays on from the seek's target. That one dying too, however soon after its
     * start, shows the error.
     */
    @Test
    fun anEndAfterAnHttpErrorIsTheStreamFailingEvenAtTheLength() = runTest {
        val h = SessionHarness(this)
        h.serveCdn(file)
        var resolves = 0
        h.routes["addon.example"] = { request ->
            resolves++
            redirect("https://cdn.example/${request.url.segments.last()}-$resolves.mkv")
        }
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        h.playTo(120.0)
        h.seekTo(1800.0)
        h.engine.report { it.copy(positionSeconds = 3000.0, eof = true, paused = true, httpErrorStatus = 403) }
        h.settle()

        assertFalse(h.session.engineState.value.eof)
        assertNull(h.session.info.value.failure)
        assertEquals(listOf("https://cdn.example/movie-2.mkv", 1800.0), h.engine.loads.last().let { listOf(it.url, it.startSeconds) })
        assertEquals(1_800_000, h.item(MOVIE.id)!!.state.timeOffset, "the movie stays where it was sent, not at its end")

        h.engine.open(duration = 3000.0)
        h.settle()
        h.playTo(1802.0)
        h.engine.report { it.copy(positionSeconds = 1802.0, eof = true, paused = true, httpErrorStatus = 403) }
        h.settle()
        assertFalse(h.session.engineState.value.eof)
        assertEquals(PlaybackFailure.Engine(EngineError("the file ended at 1802.0 s of 3000.0 s after HTTP error 403", 403, EngineErrorKind.CUT_SHORT)), h.session.info.value.failure)
    }

    @Test
    fun tenMinutesOfPlaybackAfterAReResolveEarnAnotherOne() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.start(movie())
        h.engine.open(duration = 7200.0)
        h.settle()
        h.playTo(100.0)
        h.engine.fail("HTTP error 410 Gone", httpStatus = 410)
        h.settle()
        h.engine.open(duration = 7200.0)
        h.settle()
        h.playTo(100.0 + 599.0, step = 5.0)
        h.engine.fail("HTTP error 403 Forbidden", httpStatus = 403)
        h.settle()
        assertIs<PlaybackFailure.Engine>(h.session.info.value.failure, "under ten minutes: still the same expiry")

        val g = SessionHarness(this)
        g.redirectLinks()
        g.start(movie())
        g.engine.open(duration = 7200.0)
        g.settle()
        g.engine.fail("HTTP error 410 Gone", httpStatus = 410)
        g.settle()
        g.engine.fail("HTTP error 410 Gone", httpStatus = 410)
        g.settle()
        assertIs<PlaybackFailure.Engine>(g.session.info.value.failure, "an expiry before the file opened is not retried")
    }

    @Test
    fun theReResolveBudgetComesBackAfterTenMinutesOfPlayback() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.start(movie())
        h.engine.open(duration = 7200.0)
        h.settle()
        h.playTo(100.0)
        h.engine.fail("HTTP error 410 Gone", httpStatus = 410)
        h.settle()
        h.engine.open(duration = 7200.0)
        h.settle()
        h.playTo(100.0 + 601.0, step = 5.0)
        h.engine.fail("HTTP error 403 Forbidden", httpStatus = 403)
        h.settle()
        assertNull(h.session.info.value.failure)
        assertEquals(3, h.engine.loads.size)
        assertEquals(701.0, h.engine.loads[2].startSeconds)
    }

    /**
     * An engine can report again what it had for the last load after the session loaded the next file (its thread
     * hasn't taken the load yet), with an error object of its own: the report carries the last load's id, so it is no
     * failure of the next file.
     */
    @Test
    fun anEarlierLoadsReportIsNoFailureOfTheNextFile() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        h.playTo(120.0)
        h.engine.fail("HTTP error 403 Forbidden", httpStatus = 403)
        h.settle()
        assertEquals(2, h.engine.loads.size, "resolved again")

        h.engine.report { it.copy(loadId = 1, error = EngineError("HTTP error 403 Forbidden", 403)) }
        h.settle()
        assertNull(h.session.info.value.failure, "the re-resolved file's own reports haven't failed")
        assertEquals(2, h.engine.loads.size)
    }

    @Test
    fun failuresNeverShowAUrl() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.start(movie())
        h.engine.fail(
            "Failed to open https://cdn.example/dl/0123456789abcdef0123456789abcdef/Example.Film.mkv?token=s3cr3tT0k3n&k=fakeApiKey42: loading failed",
            kind = EngineErrorKind.OPEN,
        )
        h.settle()
        val failure = assertIs<PlaybackFailure.Engine>(h.session.info.value.failure)
        // The state keeps mpv's text for logs and reports, cut of its secrets; the player says what kind of failure it was.
        assertEquals("Failed to open: loading failed", failure.error.message)
        assertEquals(EngineErrorKind.OPEN, failure.error.kind)
        assertEquals("The stream couldn't be opened.", failure.displayText)
    }

    @Test
    fun tracksFollowTheProfileLanguagesUntilTheUserPicks() = runTest {
        val h = SessionHarness(this, addons = listOf(STREAM_ADDON, SUBTITLE_ADDON))
        h.redirectLinks()
        h.data.settings.set(h.profileId, ProfileSetting.AudioLanguages, listOf("jpn"))
        h.data.settings.set(h.profileId, ProfileSetting.SubtitleLanguages, listOf("eng"))
        h.routes["subs.example.com"] = {
            json(
                """{"subtitles":[
                    {"id":"1","url":"https://subs.example.com/file/1.srt","lang":"eng"},
                    {"id":"2","url":"https://subs.example.com/file/2.srt","lang":"pob"}
                ]}""",
            )
        }
        h.engine.fileTracks = listOf(
            track(1, TrackKind.AUDIO, "eng", selected = true, default = true),
            track(2, TrackKind.AUDIO, "jpn"),
            track(1, TrackKind.SUBTITLE, "spa"),
        )
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()

        assertEquals(listOf<Triple<String, String?, String?>>(Triple("https://subs.example.com/file/1.srt", "Subs", "en")), h.engine.addedSubtitles)
        // Japanese audio, and English subtitles once the addon's arrive.
        assertEquals(listOf<Pair<TrackKind, Long?>>(TrackKind.AUDIO to 2L, TrackKind.SUBTITLE to 2L), h.engine.selections)
        val lookup = h.http.requests.single { it.url.host == "subs.example.com" }
        assertTrue(lookup.url.encodedPath.startsWith("/subtitles/movie/tt2/filename=movie.mkv&videoSize=300000&videoHash="), lookup.url.encodedPath)

        // The user turns subtitles off; later tracks don't turn them back on.
        h.session.onTrackSelected(TrackKind.SUBTITLE, null)
        h.engine.selectTrack(TrackKind.SUBTITLE, null)
        h.engine.addSubtitle("https://subs.example.com/late.srt", "Late", "en")
        h.settle()
        assertEquals<Pair<TrackKind, Long?>>(TrackKind.SUBTITLE to null, h.engine.selections.last())
    }

    /** `cdn.example` serves [file], answering every request but the head's (the first 64 KiB) with [tail]. */
    private fun SessionHarness.slowTail(tail: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        routes["cdn.example"] = { request ->
            if (request.headers[HttpHeaders.Range] == "bytes=0-65535") serveFile(request, file) else tail(request)
        }
    }

    @Test
    fun theFileLoadsBeforeItsHashAndItsSubtitlesWaitForTheHash() = runTest {
        val h = SessionHarness(this, addons = listOf(STREAM_ADDON, SUBTITLE_ADDON))
        h.redirectLinks()
        h.slowTail { request ->
            delay(10.seconds)
            serveFile(request, file)
        }
        h.english()
        h.routes["subs.example.com"] = { json("""{"subtitles":[{"id":"1","url":"https://subs.example.com/file/1.srt","lang":"eng"}]}""") }
        h.start(movie())
        assertEquals("https://cdn.example/movie.mkv", h.engine.loads.single().url, "the tail request doesn't hold the load")
        h.engine.open(duration = 3000.0)
        h.settle()
        assertTrue(h.http.requests.none { it.url.host == "subs.example.com" }, "the subtitles are looked up by the hash")
        assertNull(h.session.openedFile.value, "the file is reported open with its hash")

        h.advance(10.seconds)
        val hash = OpenSubtitlesHash.compute(file.size.toLong(), file.copyOf(65536), file.copyOfRange(file.size - 65536, file.size))
        val lookup = h.http.requests.single { it.url.host == "subs.example.com" }
        assertTrue("videoHash=$hash" in lookup.url.encodedPath, lookup.url.encodedPath)
        assertEquals(listOf<Triple<String, String?, String?>>(Triple("https://subs.example.com/file/1.srt", "Subs", "en")), h.engine.addedSubtitles)
        assertEquals(hash, h.session.openedFile.value?.resolved?.videoHash)
        assertEquals(1, h.engine.loads.size)
    }

    @Test
    fun aFailedTailRequestLeavesTheFileWithoutAHash() = runTest {
        val h = SessionHarness(this, addons = listOf(STREAM_ADDON, SUBTITLE_ADDON))
        h.redirectLinks()
        h.slowTail { respond("", HttpStatusCode.InternalServerError) }
        h.english()
        h.routes["subs.example.com"] = { json("""{"subtitles":[]}""") }
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals("https://cdn.example/movie.mkv", h.engine.loads.single().url)
        assertNull(h.session.info.value.failure)
        val lookup = h.http.requests.single { it.url.host == "subs.example.com" }
        assertFalse("videoHash" in lookup.url.encodedPath, lookup.url.encodedPath)
        val opened = assertNotNull(h.session.openedFile.value)
        assertNull(opened.resolved.videoHash)
        assertEquals(300_000L, opened.resolved.size)
    }

    @Test
    fun theSubtitleAddonsGetTheAddonsSizeAndAHashFromTheFile() = runTest {
        val h = SessionHarness(this, addons = listOf(STREAM_ADDON, SUBTITLE_ADDON))
        h.redirectLinks()
        h.english()
        h.routes["subs.example.com"] = { json("""{"subtitles":[]}""") }
        val stream = urlStream("https://addon.example/movie").let { it.copy(behaviorHints = it.behaviorHints.copy(videoSize = 299_999L)) }
        h.start(PlayRequest(MOVIE, MOVIE.id, choice(stream)))
        h.engine.open(duration = 3000.0)
        h.settle()

        // As stremio-core asks: the addon's size before the server's; a size alone doesn't spare the hash.
        val hash = OpenSubtitlesHash.compute(file.size.toLong(), file.copyOf(65536), file.copyOfRange(file.size - 65536, file.size))
        val lookup = h.http.requests.single { it.url.host == "subs.example.com" }
        assertEquals("/subtitles/movie/tt2/filename=movie.mkv&videoSize=299999&videoHash=$hash.json", lookup.url.encodedPath)
    }

    @Test
    fun closingThePlayerCancelsTheTailRequest() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        var cancelled = false
        h.slowTail {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.advance(5.seconds)
        assertEquals(1, h.engine.loads.size)
        assertFalse(cancelled)

        h.session.close()
        h.settle()
        assertTrue(cancelled)
    }

    @Test
    fun closingThePlayerCancelsTheResolveInFlight() = runTest {
        val h = SessionHarness(this)
        h.serveCdn(file)
        var cancelled = false
        h.routes["addon.example"] = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        h.start(movie())
        h.advance(5.seconds)
        assertTrue(h.session.info.value.resolving)

        h.session.close()
        h.settle()
        assertTrue(cancelled)
    }

    @Test
    fun anotherStreamCancelsTheResolveInFlight() = runTest {
        val h = SessionHarness(this)
        h.serveCdn(file)
        var cancelled = false
        h.routes["addon.example"] = { request ->
            if (request.url.encodedPath == "/movie") {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            redirect("https://cdn.example/${request.url.segments.last()}.mkv")
        }
        h.start(movie(urlStream("https://addon.example/other")))
        h.advance(5.seconds)
        assertTrue(h.session.info.value.resolving)

        h.session.playNextStream()
        h.settle()
        assertTrue(cancelled)
        assertEquals(listOf("https://cdn.example/other.mkv"), h.engine.loads.map { it.url })
    }

    /** The user picks track [id] of [kind] in the player (null: off), as the player store passes it on. */
    private fun SessionHarness.pick(kind: TrackKind, id: Long?) {
        session.onTrackSelected(kind, id)
        engine.selectTrack(kind, id)
        settle()
    }

    private fun ScriptedEngine.selected(kind: TrackKind): Long? = state.tracks.firstOrNull { it.kind == kind && it.selected }?.id

    private suspend fun SessionHarness.english() {
        data.settings.set(profileId, ProfileSetting.AudioLanguages, listOf("eng"))
        data.settings.set(profileId, ProfileSetting.SubtitleLanguages, listOf("eng"))
    }

    @Test
    fun theUsersTrackPicksGoOnToTheNextAndPreviousEpisodes() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.english()
        h.routes["streams.example.com"] = { request ->
            val episode = request.url.segments.last().removeSuffix(".json").substringAfterLast(':')
            json("""{"streams":[{"url":"https://addon.example/e$episode","behaviorHints":{"bingeGroup":"hq"}}]}""")
        }
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.start(firstEpisode(urlStream("https://addon.example/e1", bingeGroup = "hq")))
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(1L, h.engine.selected(TrackKind.SUBTITLE), "English audio the user understands: its forced subtitles")

        h.pick(TrackKind.AUDIO, 2)
        h.pick(TrackKind.SUBTITLE, 5)
        h.session.playNextEpisode()
        h.settle()
        assertEquals("https://cdn.example/e2.mkv", h.engine.loads.last().url)
        h.engine.fileTracks = EPISODE_2_TRACKS
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(3L, h.engine.selected(TrackKind.AUDIO), "German, the third track here")
        assertEquals(2L, h.engine.selected(TrackKind.SUBTITLE), "French, the second track here")

        h.pick(TrackKind.SUBTITLE, null)
        h.session.playPreviousEpisode()
        h.settle()
        assertEquals("https://cdn.example/e1.mkv", h.engine.loads.last().url)
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(2L, h.engine.selected(TrackKind.AUDIO))
        assertNull(h.engine.selected(TrackKind.SUBTITLE), "turned off, they stay off: not even the forced ones")
    }

    @Test
    fun theSubtitlesFollowTheAudioTheUserPicks() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.english()
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(1L, h.engine.selected(TrackKind.SUBTITLE), "English audio the user understands: its forced subtitles")

        h.pick(TrackKind.AUDIO, 2)
        assertEquals(2L, h.engine.selected(TrackKind.SUBTITLE), "German audio the user doesn't read: full English subtitles")
    }

    @Test
    fun theSystemsCaptionAndDescriptionSettingsApplyToTheFilePlayingAndAPickStillWins() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.english()
        h.engine.fileTracks = EPISODE_1_TRACKS + track(4, TrackKind.AUDIO, "en", "English - Audio Description", codec = "aac", channels = 2)
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(1L, h.engine.selected(TrackKind.AUDIO))
        assertEquals(1L, h.engine.selected(TrackKind.SUBTITLE), "captions off, English audio the user understands: its forced subtitles")

        h.mediaAccessibility.value = MediaAccessibilityPreferences(captions = true, audioDescriptions = true)
        h.settle()
        assertEquals(4L, h.engine.selected(TrackKind.AUDIO), "the audio description")
        assertEquals(3L, h.engine.selected(TrackKind.SUBTITLE), "captions on: English (SDH)")

        h.pick(TrackKind.SUBTITLE, 2)
        h.mediaAccessibility.value = MediaAccessibilityPreferences()
        h.settle()
        assertEquals(1L, h.engine.selected(TrackKind.AUDIO), "descriptions off: the plain English track")
        assertEquals(2L, h.engine.selected(TrackKind.SUBTITLE), "the user's pick stays")
    }

    @Test
    fun showSubtitlesAlwaysPicksTheSdhTrackWithTheSystemsCaptionsOff() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.english()
        h.data.settings.set(h.profileId, ProfileSetting.ShowSubtitles, SubtitleMode.ALWAYS)
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(3L, h.engine.selected(TrackKind.SUBTITLE), "English audio the user understands: English (SDH), not its forced")
    }

    /** The user picks subtitle track [id] as the second subtitle in the player (null: off), as the player store passes it on. */
    private fun SessionHarness.pickSecond(id: Long?) {
        session.onSecondSubtitleSelected(id)
        engine.selectSecondSubtitle(id)
        settle()
    }

    private fun ScriptedEngine.second(): Long? = state.tracks.firstOrNull { it.kind == TrackKind.SUBTITLE && it.secondary }?.id

    @Test
    fun theSecondSubtitleLanguagePicksItsTrackBesideTheFirst() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.english()
        h.data.settings.set(h.profileId, ProfileSetting.SecondSubtitleLanguage, "fre")
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(1L, h.engine.selected(TrackKind.SUBTITLE), "the first follows the languages as ever")
        assertEquals(5L, h.engine.second(), "Français")
    }

    @Test
    fun theSecondSubtitleIsNeverTheFirstsTrack() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.english()
        h.data.settings.set(h.profileId, ProfileSetting.ShowSubtitles, SubtitleMode.ALWAYS)
        h.data.settings.set(h.profileId, ProfileSetting.SecondSubtitleLanguage, "eng")
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(3L, h.engine.selected(TrackKind.SUBTITLE), "English (SDH)")
        assertEquals(2L, h.engine.second(), "the other full English track, not the first's")
    }

    @Test
    fun aSecondSubtitleLanguageTheFileLacksShowsNone() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.english()
        h.data.settings.set(h.profileId, ProfileSetting.SecondSubtitleLanguage, "jpn")
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(1L, h.engine.selected(TrackKind.SUBTITLE))
        assertNull(h.engine.second())
        assertEquals(emptyList(), h.engine.secondSelections, "nothing to turn on, nothing sent")
    }

    @Test
    fun theFirstTakingTheSecondsTrackMovesTheSecondToAnother() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.english()
        h.data.settings.set(h.profileId, ProfileSetting.SecondSubtitleLanguage, "eng")
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(1L, h.engine.selected(TrackKind.SUBTITLE), "English audio the user understands: its forced subtitles")
        assertEquals(2L, h.engine.second(), "full English")

        h.pick(TrackKind.AUDIO, 2)
        assertEquals(2L, h.engine.selected(TrackKind.SUBTITLE), "German audio: full English, the second's track until now")
        assertEquals(3L, h.engine.second(), "the second moves to the other full English track")
    }

    @Test
    fun theUsersSecondSubtitlePickGoesOnToTheNextAndPreviousEpisodes() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.english()
        h.data.settings.set(h.profileId, ProfileSetting.SecondSubtitleLanguage, "fre")
        h.routes["streams.example.com"] = { request ->
            val episode = request.url.segments.last().removeSuffix(".json").substringAfterLast(':')
            json("""{"streams":[{"url":"https://addon.example/e$episode","behaviorHints":{"bingeGroup":"hq"}}]}""")
        }
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.start(firstEpisode(urlStream("https://addon.example/e1", bingeGroup = "hq")))
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(5L, h.engine.second(), "Français, the profile's second language")

        h.pickSecond(4)
        h.session.playNextEpisode()
        h.settle()
        assertEquals("https://cdn.example/e2.mkv", h.engine.loads.last().url)
        h.engine.fileTracks = EPISODE_2_TRACKS
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(5L, h.engine.second(), "Español, the fifth track here")
        assertEquals(4L, h.engine.selected(TrackKind.SUBTITLE), "the first follows the languages: its forced English")

        h.pickSecond(null)
        h.session.playPreviousEpisode()
        h.settle()
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.engine.open(duration = 3000.0)
        h.settle()
        assertNull(h.engine.second(), "turned off, it stays off despite the profile's second language")
    }

    @Test
    fun aFileWithNothingForAPickFollowsTheLanguagesAndThePickGoesOn() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.english()
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.start(movie(urlStream("https://addon.example/other"), urlStream("https://addon.example/third")))
        h.engine.open(duration = 3000.0)
        h.settle()
        h.pick(TrackKind.SUBTITLE, 4)

        h.session.playNextStream()
        h.settle()
        h.engine.fileTracks = EPISODE_1_TRACKS.filter { it.language != "es" }
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(1L, h.engine.selected(TrackKind.SUBTITLE), "no Spanish: the languages' choice")

        h.session.playNextStream()
        h.settle()
        h.engine.fileTracks = EPISODE_2_TRACKS
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(5L, h.engine.selected(TrackKind.SUBTITLE), "Spanish again where there is some")
    }

    @Test
    fun aReloadAfterAnExpiryGetsTheVeryTrackBack() = runTest {
        val h = SessionHarness(this)
        h.redirectLinks()
        h.english()
        h.engine.fileTracks = listOf(
            track(1, TrackKind.AUDIO, "en", selected = true),
            track(1, TrackKind.SUBTITLE, "en", "English"),
            track(2, TrackKind.SUBTITLE, "en", "English"),
        )
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        h.playTo(60.0)
        h.pick(TrackKind.SUBTITLE, 2)

        h.engine.fail("HTTP error 403 Forbidden", httpStatus = 403)
        h.settle()
        assertEquals(2, h.engine.loads.size)
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(2L, h.engine.selected(TrackKind.SUBTITLE), "the second of two alike")
    }

    @Test
    fun anAddonsSubtitlesPickedTakeOverTheNextEpisodeWhenTheyArriveUnlessTheUserPicksFirst() = runTest {
        val h = SessionHarness(this, addons = listOf(STREAM_ADDON, SUBTITLE_ADDON))
        h.redirectLinks()
        h.english()
        h.routes["streams.example.com"] = { request ->
            val episode = request.url.segments.last().removeSuffix(".json").substringAfterLast(':')
            json("""{"streams":[{"url":"https://addon.example/e$episode","behaviorHints":{"bingeGroup":"hq"}}]}""")
        }
        // OpenSubtitles answers after the file opened.
        h.routes["subs.example.com"] = { request ->
            delay(5.seconds)
            val video = request.url.segments[2]
            json("""{"subtitles":[{"id":"1","url":"https://subs.example.com/$video.srt","lang":"eng"}]}""")
        }
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.start(firstEpisode(urlStream("https://addon.example/e1", bingeGroup = "hq")))
        h.engine.open(duration = 3000.0)
        h.advance(5.seconds)
        val added = h.engine.state.tracks.single { it.external }
        h.pick(TrackKind.SUBTITLE, added.id)

        h.session.playNextEpisode()
        h.settle()
        h.engine.fileTracks = EPISODE_2_TRACKS
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(3L, h.engine.selected(TrackKind.SUBTITLE), "the file's own English until the addon's arrive")
        h.advance(5.seconds)
        assertEquals("https://subs.example.com/tt1:1:2.srt", h.engine.addedSubtitles.last().first)
        assertEquals(6L, h.engine.selected(TrackKind.SUBTITLE), "the addon's English")

        h.session.playPreviousEpisode()
        h.settle()
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(2L, h.engine.selected(TrackKind.SUBTITLE))
        h.pick(TrackKind.SUBTITLE, 5)
        h.advance(5.seconds)
        assertEquals(5L, h.engine.selected(TrackKind.SUBTITLE), "the user's French, not the late English")
    }

    @Test
    fun aSubtitleWaitsUntilAskedForThenPlaysAndItsLanguageComesFirstInTheNextEpisode() = runTest {
        val h = SessionHarness(this, addons = listOf(STREAM_ADDON, SUBTITLE_ADDON))
        h.redirectLinks()
        h.english()
        h.routes["streams.example.com"] = { request ->
            val episode = request.url.segments.last().removeSuffix(".json").substringAfterLast(':')
            json("""{"streams":[{"url":"https://addon.example/e$episode","behaviorHints":{"bingeGroup":"hq"}}]}""")
        }
        h.routes["subs.example.com"] = { request ->
            val video = request.url.segments[2]
            json(
                """{"subtitles":[
                    {"id":"1","url":"https://subs.example.com/$video.en.srt","lang":"eng"},
                    {"id":"2","url":"https://subs.example.com/$video.ja.srt","lang":"jpn"}
                ]}""",
            )
        }
        h.engine.fileTracks = EPISODE_1_TRACKS
        h.start(firstEpisode(urlStream("https://addon.example/e1", bingeGroup = "hq")))
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(listOf("https://subs.example.com/tt1:1:1.en.srt"), h.engine.addedSubtitles.map { it.first })
        val japanese = h.session.info.value.moreSubtitles.single()
        assertEquals("https://subs.example.com/tt1:1:1.ja.srt" to "ja", japanese.url to japanese.language)

        h.session.addSubtitle(japanese.url)
        h.settle()
        assertEquals(japanese.url, h.engine.addedSubtitles.last().first)
        assertEquals(7L, h.engine.selected(TrackKind.SUBTITLE), "the Japanese, once listed")
        assertEquals(emptyList(), h.session.info.value.moreSubtitles)

        h.session.playNextEpisode()
        h.settle()
        h.engine.fileTracks = EPISODE_2_TRACKS
        h.engine.open(duration = 3000.0)
        h.settle()
        assertEquals(
            listOf("https://subs.example.com/tt1:1:2.ja.srt", "https://subs.example.com/tt1:1:2.en.srt"),
            h.engine.addedSubtitles.drop(2).map { it.first },
            "the picked language is added before the profile's",
        )
        assertEquals(6L, h.engine.selected(TrackKind.SUBTITLE), "the next episode's Japanese")
    }

    @Test
    fun anAddedSubtitleThatNeverLoadsLeavesTheOtherChoicesGoing() = runTest {
        val h = SessionHarness(this, addons = listOf(STREAM_ADDON, SUBTITLE_ADDON))
        h.redirectLinks()
        h.english()
        h.data.settings.set(h.profileId, ProfileSetting.SecondSubtitleLanguage, "fre")
        h.routes["subs.example.com"] = {
            json(
                """{"subtitles":[
                    {"id":"1","url":"https://subs.example.com/file/en.srt","lang":"eng"},
                    {"id":"2","url":"https://subs.example.com/file/ja.srt","lang":"jpn"}
                ]}""",
            )
        }
        h.engine.fileTracks = EPISODE_1_TRACKS.filter { it.language != "fr" }
        h.start(movie())
        h.engine.open(duration = 3000.0)
        h.settle()
        assertNull(h.engine.second(), "no French yet")

        val japanese = h.session.info.value.moreSubtitles.single()
        h.engine.deadSubtitles = setOf(japanese.url)
        h.session.addSubtitle(japanese.url)
        h.settle()
        h.engine.addSubtitle("https://subs.example.com/late.fr.srt", "Late", "fr")
        h.settle()
        assertEquals(6L, h.engine.second(), "the late French, though the Japanese never came")
    }

    @Test
    fun aTrailerPlaysFromTheStartAsItIsAndRecordsNothing() = runTest {
        val h = SessionHarness(this, addons = listOf(STREAM_ADDON, SUBTITLE_ADDON))
        h.redirectLinks()
        h.routes["subs.example.com"] = { json("""{"subtitles":[]}""") }
        // The episode half watched, 12:00 of 24:00, last played from its own stream.
        h.data.library.upsertFromMeta(h.profileId, snapshot(SERIES))
        h.data.library.recordProgress(h.profileId, SERIES.id, "tt1:1:1", 720_000, 1_440_000)
        val episodeStream = AddonJson.encodeToString(Stream.serializer(), urlStream("https://addon.example/e1"))
        h.data.streamHistory.record(h.profileId, SERIES.id, "tt1:1:1", episodeStream, STREAMS_URL)
        val before = assertNotNull(h.item(SERIES.id)).state
        val observer = RecordingObserver().also { h.session.observe(it) }

        h.start(PlayRequest(SERIES, "tt1:1:1", choice(urlStream("https://addon.example/trailer")), purpose = PlayPurpose.TRAILER))
        assertEquals(0.0, h.engine.loads.single().startSeconds, "from the start, not the episode's resume point")
        h.engine.open(duration = 120.0)
        h.settle()
        // Two minutes against the episode's 24: a trailer, not an error video sent for the episode.
        assertNull(h.session.info.value.failure)
        assertNull(h.session.info.value.errorVideo)
        assertFalse(h.engine.state.paused, "it plays")
        assertNull(h.session.openedFile.value, "never the file a watch-together room follows")
        assertNull(h.session.info.value.nextEpisode)
        h.playTo(120.0)
        h.engine.report { it.copy(eof = true) }
        h.advance(60.seconds)
        h.session.close()
        h.settle()

        assertEquals(before, h.item(SERIES.id)?.state, "the library's progress, duration, watched marks and pointer")
        assertEquals(urlStream("https://addon.example/e1"), h.data.streamHistory.lastStream(h.profileId, SERIES.id, "tt1:1:1")?.stream)
        assertEquals(emptyList(), observer.events, "the trackers hear nothing")
        assertTrue(h.http.requests.none { it.url.host == "subs.example.com" }, "no subtitle addon asked")
    }
}
