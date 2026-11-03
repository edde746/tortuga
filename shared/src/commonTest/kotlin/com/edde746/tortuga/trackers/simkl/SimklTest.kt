package com.edde746.tortuga.trackers.simkl

import com.edde746.tortuga.trackers.AnimeIds
import com.edde746.tortuga.trackers.DeviceCodeGrant
import com.edde746.tortuga.trackers.DeviceCodePoller
import com.edde746.tortuga.trackers.DevicePoll
import com.edde746.tortuga.trackers.ExternalIds
import com.edde746.tortuga.trackers.Rating
import com.edde746.tortuga.trackers.RatingKind
import com.edde746.tortuga.trackers.RatingTarget
import com.edde746.tortuga.trackers.RealtimeScrobble
import com.edde746.tortuga.trackers.ScrobbleState
import com.edde746.tortuga.trackers.ServiceChannel
import com.edde746.tortuga.trackers.TrackerContentType
import com.edde746.tortuga.trackers.TrackerContext
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerMediaKind
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.TrackerSession
import com.edde746.tortuga.trackers.catalog.TrackerWatchlist
import com.edde746.tortuga.trackers.catalog.WatchlistTarget
import com.edde746.tortuga.trackers.client.Answer
import com.edde746.tortuga.trackers.client.FakeCredentials
import com.edde746.tortuga.trackers.client.ScriptedService
import com.edde746.tortuga.trackers.client.json
import com.edde746.tortuga.trackers.client.parse
import com.edde746.tortuga.trackers.ratings.RatingSource
import com.edde746.tortuga.trackers.ratings.RatingTitle
import com.edde746.tortuga.trackers.ratings.TitleRating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject

/**
 * Simkl against its documented answers (api.simkl.org):
 * - AUTH V2 device flow: https://api.simkl.org/api-reference/oauth2-device
 * - tokens and refresh: https://api.simkl.org/api-reference/oauth2-tokens, scopes: …/oauth2-scopes
 * - headers, and which calls carry the user's token: https://api.simkl.org/conventions/headers, …/api-reference/auth-v2
 * - scrobble: https://api.simkl.org/guides/scrobble
 * - history: https://api.simkl.org/api-reference/simkl/add-to-history; anime numbering: https://api.simkl.org/guides/anime
 * - ratings: https://api.simkl.org/api-reference/simkl/get-user-ratings, …/add-ratings
 * - plan to watch: https://api.simkl.org/api-reference/simkl/get-all-items
 * - ids: https://api.simkl.org/conventions/standard-media-objects
 * - title scores: https://api.simkl.org/api-reference/simkl/redirect, …/get-anime
 */
class SimklTest {
    private val now = 1_790_000_000L

    private fun provider(service: ScriptedService) = SimklProvider(service.http(TrackerService.SIMKL), "client-id") { now }

    private val grant = DeviceCodeGrant("device-code-1", "BDWP-HQPK", "https://simkl.com/pin", null, 900, 5)

    private val episode = TrackerContext(
        metaId = "tt0944947",
        videoId = "tt0944947:1:2",
        kind = TrackerMediaKind.EPISODE,
        contentType = TrackerContentType.SERIES,
        ids = ExternalIds(imdb = "tt0944947", tmdb = 1399),
        season = 1,
        episode = 2,
    )

    /** Attack on Titan S3E13 (TVDB), which Simkl files as episode 1 of its own "Season 3 part 2" entry (guides/anime). */
    private val animeEpisode = TrackerContext(
        metaId = "tmdb:1429",
        videoId = "tmdb:1429:3:13",
        kind = TrackerMediaKind.EPISODE,
        contentType = TrackerContentType.ANIME,
        ids = ExternalIds(tmdb = 1429, tvdb = 267440),
        anime = AnimeIds(simkl = 931899),
        season = 3,
        episode = 13,
        animeEpisode = 1,
    )

    private val movie = TrackerContext(
        metaId = "tt1375666",
        videoId = "tt1375666",
        kind = TrackerMediaKind.MOVIE,
        contentType = TrackerContentType.MOVIE,
        ids = ExternalIds(imdb = "tt1375666"),
    )

    @Test
    fun theV2DeviceFlowAsksToWriteAndPollsUntilApproved() = runTest {
        val polls = ArrayDeque(listOf("authorization_pending", "slow_down", "ok"))
        val service = ScriptedService(this) { request ->
            when (request.path) {
                "/oauth2/device" -> json(
                    """{"device_code":"device-code-1","user_code":"BDWP-HQPK","verification_uri":"https://simkl.com/pin","verification_uri_complete":"https://simkl.com/pin?user_code=BDWP-HQPK","expires_in":900,"interval":5}""",
                )
                "/oauth2/token" -> when (val answer = polls.removeFirst()) {
                    "ok" -> json(
                        """{"access_token":"simkl_at_access1","token_type":"Bearer","expires_in":604800,"refresh_token":"simkl_rt_refresh1","scope":"media:read media:write"}""",
                    )
                    else -> json("""{"error":"$answer"}""", 400)
                }
                "/users/settings" -> json("""{"user":{"name":"Andrew","joined_at":"2014-01-01T00:00:00Z"},"account":{"id":1,"type":"free"}}""")
                else -> error("unexpected $request")
            }
        }
        val signIn = provider(service).signIn
        val requested = signIn.requestCode()
        assertEquals("https://simkl.com/pin?user_code=BDWP-HQPK", requested.verificationUrlComplete)
        assertEquals(mapOf("client_id" to "client-id", "scope" to "media:read media:write"), service.to("/oauth2/device").single().form())

        val result = DeviceCodePoller.poll(requested, testScheduler.timeSource) { signIn.probe(requested) }
        val session = signIn.session(assertIs<DevicePoll.Success>(result).token)

        assertEquals(TrackerSession("simkl_at_access1", "simkl_rt_refresh1", now + 604800, "Andrew", now), session)
        val poll = service.to("/oauth2/token").first().form()
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", poll["grant_type"])
        assertEquals("device-code-1", poll["device_code"])
        val settings = service.to("/users/settings").single()
        assertEquals("Bearer simkl_at_access1", settings.headers["Authorization"])
        assertEquals("client-id", settings.headers["simkl-api-key"])
        assertEquals("tortuga", settings.url.parameters["app-name"])
    }

    /** Simkl records no denial: a declined code answers `authorization_pending` until `expired_token`. */
    @Test
    fun anExpiredCodeEndsThePollingAndAV1ClientOrReadOnlyGrantFails() = runTest {
        assertEquals(DevicePoll.Expired, provider(ScriptedService(this) { json("""{"error":"expired_token"}""", 400) }).signIn.probe(grant))
        assertEquals(DevicePoll.Pending, provider(ScriptedService(this) { json("""{"error":"authorization_pending"}""", 400) }).signIn.probe(grant))
        val v1 = ScriptedService(this) {
            json("""{"error":"invalid_client","error_description":"This client_id is not enabled for OAuth 2.0"}""", 401)
        }
        assertFailsWith<TrackerException.SignIn> { provider(v1).signIn.probe(grant) }
        val readOnly = parse("""{"access_token":"simkl_at_a","expires_in":604800,"refresh_token":"simkl_rt_r","scope":"media:read"}""").jsonObject
        assertFailsWith<TrackerException.SignIn> { provider(ScriptedService(this) { error("no request") }).signIn.session(readOnly) }
    }

    @Test
    fun refreshingKeepsTheNonRotatingRefreshToken() = runTest {
        val service = ScriptedService(this) { request ->
            when (request.form()["refresh_token"]) {
                "simkl_rt_refresh1" -> json("""{"access_token":"simkl_at_access2","token_type":"Bearer","expires_in":604800,"scope":"media:read media:write"}""")
                else -> json("""{"error":"invalid_grant"}""", 400)
            }
        }
        val old = TrackerSession("simkl_at_access1", "simkl_rt_refresh1", now, "Andrew", now - 604800)
        assertEquals(TrackerSession("simkl_at_access2", "simkl_rt_refresh1", now + 604800, "Andrew", now), provider(service).refresher.refresh(old))
        assertEquals("refresh_token", service.requests.first().form()["grant_type"])
        assertTrue(assertFailsWith<TrackerException.Auth> { provider(service).refresher.refresh(old.copy(refreshToken = "simkl_rt_gone")) }.permanent)
    }

    @Test
    fun scrobblesCarryTwoDecimalsASeekSendsNothingAndAStopConflictIsDone() = runTest {
        val service = ScriptedService(this) { request ->
            if (request.path == "/scrobble/stop") json("""{"watched_at":"2026-09-29T18:42:10Z","expires_at":"2026-09-29T19:42:10Z"}""", 409) else json("""{"id":1,"action":"start"}""", 201)
        }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.SIMKL)) as RealtimeScrobble
        tracker.scrobble(episode, ScrobbleState.START, 42.4567)
        tracker.scrobble(episode, ScrobbleState.SEEK, 60.0)
        tracker.scrobble(episode, ScrobbleState.STOP, 95.0)

        assertEquals(listOf("/scrobble/start", "/scrobble/stop"), service.requests.map { it.path })
        assertEquals(
            parse("""{"progress":42.46,"show":{"ids":{"tmdb":1399,"imdb":"tt0944947"}},"episode":{"season":1,"number":2}}"""),
            parse(service.requests[0].body),
        )
        val start = service.requests[0]
        assertEquals("client-id", start.url.parameters["client_id"])
        assertEquals("tortuga", start.url.parameters["app-name"])
        assertEquals("1", start.url.parameters["app-version"])
        assertEquals("client-id", start.headers["simkl-api-key"])
        assertEquals("Bearer access-1", start.headers["Authorization"])
        // A conflict on start is not documented as success.
        val conflicted = ScriptedService(this) { json("{}", 409) }
        assertIs<TrackerException.Api>(
            assertFailsWith<TrackerException> {
                (provider(conflicted).tracker(FakeCredentials(TrackerService.SIMKL)) as RealtimeScrobble).scrobble(movie, ScrobbleState.START, 1.0)
            },
        )
    }

    @Test
    fun historyWritesTheEpisodeUnderItsShow() = runTest {
        val service = ScriptedService(this) { json("""{"added":{"movies":0,"shows":1,"episodes":1}}""", 201) }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.SIMKL)) as RealtimeScrobble
        tracker.markWatched(episode)
        tracker.reconcileWatchedAfterStop(movie, 80.0)
        tracker.markUnwatched(movie)

        assertEquals(listOf("/sync/history", "/sync/history/remove"), service.requests.map { it.path })
        assertEquals(
            parse("""{"shows":[{"ids":{"tmdb":1399,"imdb":"tt0944947"},"use_tvdb_anime_seasons":true,"seasons":[{"number":1,"episodes":[{"number":2}]}]}]}"""),
            parse(service.requests[0].body),
        )
        assertEquals(parse("""{"movies":[{"ids":{"imdb":"tt1375666"}}]}"""), parse(service.requests[1].body))
    }

    /**
     * An anime episode goes by its show's external ids in their own (TVDB) numbering, which Simkl maps onto its anime
     * entries; the mapping's Simkl id would pick one entry the numbers don't count in. An anime movie keeps it.
     */
    @Test
    fun anAnimeEpisodeGoesByItsShowsIdsAndAnAnimeMovieByItsSimklId() = runTest {
        val service = ScriptedService(this) { json("""{"id":1,"action":"start"}""", 201) }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.SIMKL)) as RealtimeScrobble
        tracker.scrobble(animeEpisode, ScrobbleState.START, 0.0)
        tracker.markWatched(animeEpisode)
        val animeMovie = movie.copy(metaId = "tt0245429", videoId = "tt0245429", ids = ExternalIds(imdb = "tt0245429"), anime = AnimeIds(simkl = 37207))
        tracker.markWatched(animeMovie)

        assertEquals(
            parse("""{"progress":0.0,"show":{"ids":{"tvdb":267440,"tmdb":1429}},"episode":{"season":3,"number":13}}"""),
            parse(service.to("/scrobble/start").single().body),
        )
        val (episodeMark, movieMark) = service.to("/sync/history")
        assertEquals(
            parse("""{"shows":[{"ids":{"tvdb":267440,"tmdb":1429},"use_tvdb_anime_seasons":true,"seasons":[{"number":3,"episodes":[{"number":13}]}]}]}"""),
            parse(episodeMark.body),
        )
        assertEquals(parse("""{"movies":[{"ids":{"simkl":37207,"imdb":"tt0245429"}}]}"""), parse(movieMark.body))
    }

    @Test
    fun aRejectedTokenRefreshesAndEachLimitSignalSaysWhenToComeBack() = runTest {
        val service = ScriptedService(this) { request ->
            if (request.headers["Authorization"] == "Bearer access-1") Answer(401) else json("{}", 201)
        }
        val credentials = FakeCredentials(TrackerService.SIMKL)
        provider(service).tracker(credentials).markWatched(movie)
        assertEquals(1, credentials.refreshes)
        assertEquals(2, service.requests.size)

        // api.simkl.org/resources/rate-limits, "Limit signals": the body tells them apart, and each wants its own wait.
        suspend fun refusal(answer: Answer): TrackerException =
            assertFailsWith<TrackerException> { provider(ScriptedService(this) { answer }).tracker(FakeCredentials(TrackerService.SIMKL)).markWatched(movie) }
        suspend fun waitFor(answer: Answer): Long? = assertIs<TrackerException.RateLimited>(refusal(answer)).retryAfterSeconds
        // The daily quota waits its Retry-After; the per-second rate about a second, whatever Retry-After (the daily reset's) says.
        assertEquals(45_000, waitFor(Answer(429, """{"error":"user_limit_exceeded"}""", mapOf("Retry-After" to "45000"))))
        assertEquals(2, waitFor(Answer(429, """{"error":"rate_limit"}""", mapOf("Retry-After" to "45000"))))
        // A throttling block from POST overages, and the per-user write lock.
        assertEquals(300, waitFor(Answer(412, """{"error":"client_id_failed"}""")))
        assertEquals(5, waitFor(Answer(400, """{"error":"RATE_LIMIT"}""")))
        // Any other 400 is an answer about the write.
        assertIs<TrackerException.Api>(refusal(Answer(400, """{"error":"bad_request"}""")))
    }

    /** A stop between the profile's threshold and Simkl's 80 % is two writes, which the service's channel spaces. */
    @Test
    fun writesGoASecondApartAsSimklsLimitAsks() = runTest {
        val sentAt = mutableListOf<Long>()
        val service = ScriptedService(this) {
            sentAt += testScheduler.currentTime
            json("{}", 201)
        }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.SIMKL)) as RealtimeScrobble
        val channel = ServiceChannel(backgroundScope, TrackerService.SIMKL)
        channel.submit(ScrobbleState.STOP, tracker.writeSpacing) {
            tracker.scrobble(movie, ScrobbleState.STOP, 75.0)
            true
        }
        channel.submit(null, tracker.writeSpacing) {
            tracker.reconcileWatchedAfterStop(movie, 75.0)
            true
        }.await()
        assertEquals(listOf("/scrobble/stop", "/sync/history"), service.requests.map { it.path })
        assertTrue(sentAt[1] - sentAt[0] >= 1_000, "${sentAt[1] - sentAt[0]} ms apart")
    }

    @Test
    fun ratingsMatchStringIdsAndOnlyWholeTitlesAreRated() = runTest {
        val service = ScriptedService(this) { request ->
            when (request.path) {
                "/sync/ratings/shows/1,2,3,4,5,6,7,8,9,10" -> json(
                    """{"shows":[{"user_rated_at":"2026-01-01T00:00:00Z","user_rating":8,"status":"watching","show":{"title":"Game of Thrones","year":2011,"ids":{"simkl":17465,"imdb":"tt0944947","tvdb":"121361"}}}]}""",
                )
                "/sync/ratings/anime/1,2,3,4,5,6,7,8,9,10" -> json("{}")
                else -> json("""{"added":{"shows":1}}""", 201)
            }
        }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.SIMKL)) as Rating
        // tvdb comes back as a string; the local id is a number.
        assertEquals(8, tracker.rating(RatingTarget(RatingKind.SHOW, ExternalIds(tvdb = 121361))))
        tracker.rate(RatingTarget(RatingKind.SHOW, ExternalIds(imdb = "tt0944947")), 9)
        assertEquals(parse("""{"shows":[{"ids":{"imdb":"tt0944947"},"rating":9}]}"""), parse(service.to("/sync/ratings").single().body))
        assertFailsWith<TrackerException.Unsupported> { tracker.rate(RatingTarget(RatingKind.EPISODE, ExternalIds(imdb = "tt0944947"), season = 1, episode = 1), 9) }
    }

    @Test
    fun planToWatchSeriesIncludeAnimeByTheirAnimeIds() = runTest {
        val service = ScriptedService(this) { request ->
            when (request.path) {
                "/sync/all-items/shows/plantowatch" -> json(
                    """{"shows":[{"added_to_watchlist_at":"2026-01-01T00:00:00Z","status":"plantowatch","show":{"title":"Severance","year":2022,"poster":"16/1616a","ids":{"simkl":1,"imdb":"tt11280740","tmdb":"95396"}}}]}""",
                )
                "/sync/all-items/anime/plantowatch" -> json(
                    """{"anime":[{"status":"plantowatch","anime_type":"tv","show":{"title":"Frieren","year":2023,"ids":{"simkl":2,"mal":"52991","anidb":"17617"}}},{"status":"plantowatch","anime_type":"movie","show":{"title":"Suzume","year":2022,"ids":{"simkl":3,"tmdb":"916224"}}}]}""",
                )
                else -> error("unexpected $request")
            }
        }
        val catalog = provider(service).catalog(FakeCredentials(TrackerService.SIMKL))
        val series = catalog.catalogs().single { it.id == "plantowatch.series" }
        val page = catalog.page(series)
        assertEquals(listOf("tt11280740" to "series", "mal:52991" to "series", "tmdb:916224" to "movie"), page.items.map { it.id to it.type })
        assertEquals("https://simkl.in/posters/16/1616a_m.webp", page.items[0].poster)
        assertEquals(null, page.next)
    }

    @Test
    fun planToWatchIsAddedByListAndRemovedOnlyWhileStillPlanned() = runTest {
        val service = ScriptedService(this) { request ->
            when (request.path) {
                "/sync/add-to-list", "/sync/history/remove" -> json("""{"added":{"shows":1}}""", 201)
                "/sync/all-items/shows/plantowatch" -> json("""{"shows":[{"status":"plantowatch","show":{"title":"Severance","ids":{"simkl":1,"imdb":"tt11280740"}}}]}""")
                "/sync/all-items/anime/plantowatch" -> json("{}")
                else -> error("unexpected $request")
            }
        }
        val watchlist = provider(service).catalog(FakeCredentials(TrackerService.SIMKL)) as TrackerWatchlist
        watchlist.addToWatchlist(WatchlistTarget(isMovie = false, ids = ExternalIds(imdb = "tt11280740")))
        assertEquals(parse("""{"shows":[{"to":"plantowatch","ids":{"imdb":"tt11280740"}}]}"""), parse(service.to("/sync/add-to-list").single().body))

        // Removing drops the library entry, history included: only while it is still planned.
        watchlist.removeFromWatchlist(WatchlistTarget(isMovie = false, ids = ExternalIds(imdb = "tt0944947")))
        assertTrue(service.to("/sync/history/remove").isEmpty())
        watchlist.removeFromWatchlist(WatchlistTarget(isMovie = false, ids = ExternalIds(imdb = "tt11280740")))
        assertEquals(parse("""{"shows":[{"ids":{"imdb":"tt11280740"}}]}"""), parse(service.to("/sync/history/remove").single().body))
    }

    /**
     * Title scores: `/redirect` carries the account's token (a V2 client gets 401 `user_token_required` without one;
     * a rejected token refreshes once) and isn't followed; the detail record goes without the token, so Simkl's edge
     * cache answers it.
     */
    @Test
    fun titleScoresResolveWithTheTokenAndReadTheCachedRecordWithout() = runTest {
        val service = ScriptedService(this) { request ->
            when {
                request.path == "/redirect" && request.headers["Authorization"] == "Bearer access-1" ->
                    json("""{"error":"user_token_failed","code":401}""", 401)
                request.path == "/redirect" -> Answer(301, headers = mapOf("Location" to "https://simkl.com/movies/53536/the-shawshank-redemption"))
                request.path == "/movies/53536" -> json("""{"title":"The Shawshank Redemption","ids":{"simkl":53536},"ratings":{"simkl":{"rating":8.9,"votes":13542},"imdb":{"rating":9.3,"votes":3207270}}}""")
                else -> error("unexpected $request")
            }
        }
        val credentials = FakeCredentials(TrackerService.SIMKL)
        val scores = provider(service).ratings(credentials).ratings(RatingTitle(movie = true, ids = ExternalIds(imdb = "tt0111161", tmdb = 278)))

        val s = TrackerService.SIMKL
        assertEquals(listOf(TitleRating(RatingSource.SIMKL, 8.9, 13542, s), TitleRating(RatingSource.IMDB, 9.3, 3207270, s)), scores)
        assertEquals(listOf("/redirect", "/redirect", "/movies/53536"), service.requests.map { it.path })
        assertEquals(1, credentials.refreshes)
        val redirect = service.requests[1]
        assertEquals("Bearer access-2", redirect.headers["Authorization"])
        assertEquals(listOf("simkl", "tt0111161", "278", "movie"), listOf("to", "imdb", "tmdb", "type").map { redirect.url.parameters[it] })
        val record = service.requests[2]
        assertEquals(null, record.headers["Authorization"])
        assertEquals("client-id", record.url.parameters["client_id"])
        assertEquals("tortuga", record.url.parameters["app-name"])
    }
}
