package com.edde746.tortuga.trackers.trakt

import com.edde746.tortuga.trackers.ApiFailureCategory
import com.edde746.tortuga.trackers.DeviceCodeGrant
import com.edde746.tortuga.trackers.DeviceCodePoller
import com.edde746.tortuga.trackers.DevicePoll
import com.edde746.tortuga.trackers.EpisodeHistory
import com.edde746.tortuga.trackers.ExternalIds
import com.edde746.tortuga.trackers.HistoryItem
import com.edde746.tortuga.trackers.Rating
import com.edde746.tortuga.trackers.RatingKind
import com.edde746.tortuga.trackers.RatingTarget
import com.edde746.tortuga.trackers.RealtimeScrobble
import com.edde746.tortuga.trackers.ScrobbleState
import com.edde746.tortuga.trackers.TrackerContentType
import com.edde746.tortuga.trackers.TrackerContext
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerMediaKind
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.TrackerSession
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerWatchlist
import com.edde746.tortuga.trackers.catalog.WatchlistTarget
import com.edde746.tortuga.trackers.client.Answer
import com.edde746.tortuga.trackers.client.FakeCredentials
import com.edde746.tortuga.trackers.client.ScriptedService
import com.edde746.tortuga.trackers.client.json
import com.edde746.tortuga.trackers.client.parse
import com.edde746.tortuga.trackers.ratings.RatingTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.jsonObject

/**
 * Trakt against its documented answers (docs.trakt.tv):
 * - device codes: https://docs.trakt.tv/reference/postoauthdevicecode, https://docs.trakt.tv/reference/postoauthdevicetoken
 * - tokens: https://docs.trakt.tv/reference/postoauthtoken, https://docs.trakt.tv/docs/authentication-oauth
 * - scrobble: https://docs.trakt.tv/reference/postscrobblestart, …/postscrobblestop
 * - history and ratings: https://docs.trakt.tv/reference/postsynchistory, …/getsyncratings, …/postsyncratings
 * - catalogs: https://docs.trakt.tv/reference/getsyncwatchlist, …/getmoviestrending, …/getrecommendationsmovies
 */
class TraktTest {
    private val now = 1_790_000_000L

    private fun provider(service: ScriptedService) = TraktProvider(service.http(TrackerService.TRAKT), "client-id") { now }

    private val movie = TrackerContext(
        metaId = "tt0111161",
        videoId = "tt0111161",
        kind = TrackerMediaKind.MOVIE,
        contentType = TrackerContentType.MOVIE,
        ids = ExternalIds(imdb = "tt0111161", tmdb = 278),
    )

    private val episode = TrackerContext(
        metaId = "tt0903747",
        videoId = "tt0903747:1:2",
        kind = TrackerMediaKind.EPISODE,
        contentType = TrackerContentType.SERIES,
        ids = ExternalIds(imdb = "tt0903747", tvdb = 81189),
        season = 1,
        episode = 2,
    )

    @Test
    fun theDeviceCodeFlowPollsUntilApprovedAndLooksUpTheUsername() = runTest {
        val polls = ArrayDeque(listOf(400, 429, 200))
        val service = ScriptedService(this) { request ->
            when (request.path) {
                "/oauth/device/code" -> json(
                    """{"device_code":"device-code-1","user_code":"5055CC52","verification_url":"https://trakt.tv/activate","expires_in":600,"interval":5}""",
                )
                "/oauth/device/token" -> when (val status = polls.removeFirst()) {
                    200 -> json(
                        """{"access_token":"access-1","token_type":"bearer","expires_in":604800,"refresh_token":"refresh-1","scope":"public","created_at":1789990000}""",
                    )
                    else -> Answer(status)
                }
                "/users/settings" -> json("""{"user":{"username":"sean","name":"Sean Rudford"},"account":{"timezone":"America/Los_Angeles"}}""")
                else -> error("unexpected $request")
            }
        }
        val signIn = provider(service).signIn
        val grant = signIn.requestCode()
        assertEquals("5055CC52", grant.userCode)
        assertEquals("https://trakt.tv/activate/5055CC52", grant.verificationUrlComplete)
        assertEquals(5, grant.intervalSeconds)

        val result = DeviceCodePoller.poll(grant, testScheduler.timeSource) { signIn.probe(grant) }
        val session = signIn.session(assertIs<DevicePoll.Success>(result).token)

        assertEquals(TrackerSession("access-1", "refresh-1", 1789990000L + 604800, "sean", 1789990000L), session)
        val poll = service.to("/oauth/device/token").first()
        assertEquals("device-code-1", poll.field("code"))
        // Trakt's apps have no secret since 2026-10-01: the client ID alone signs in.
        assertNull(poll.field("client_secret"))
        // Every call carries the API version and key; the username lookup carries the new token.
        assertTrue(service.requests.all { it.headers["trakt-api-version"] == "2" && it.headers["trakt-api-key"] == "client-id" })
        assertEquals("Bearer access-1", service.to("/users/settings").single().headers["Authorization"])
        assertEquals(3, service.to("/oauth/device/token").size)
    }

    @Test
    fun anExpiredOrRefusedCodeEndsThePolling() = runTest {
        val grant = DeviceCodeGrant("device-code-1", "5055CC52", "https://trakt.tv/activate", null, 600, 5)
        for ((status, expected) in listOf(404 to DevicePoll.Expired, 410 to DevicePoll.Expired, 409 to DevicePoll.Denied, 418 to DevicePoll.Denied)) {
            val service = ScriptedService(this) { Answer(status) }
            assertEquals(expected, provider(service).signIn.probe(grant), "HTTP $status")
        }
        // Unreachable for a moment: keep polling.
        val offline = ScriptedService(this) { throw IOException("offline") }
        assertEquals(DevicePoll.Pending, provider(offline).signIn.probe(grant))
    }

    @Test
    fun aSignInWithoutARefreshTokenFails() = runTest {
        val service = ScriptedService(this) { error("no request expected") }
        assertFailsWith<TrackerException.SignIn> {
            provider(service).signIn.session(parse("""{"access_token":"access-1","expires_in":604800}""").jsonObject)
        }
    }

    @Test
    fun scrobblesSendTheItemAndProgressAndASeekIsPauseThenStart() = runTest {
        val service = ScriptedService(this) { request ->
            // A duplicate within Trakt's window is a 409 that counts as done.
            if (request.path == "/scrobble/stop") json("""{"watched_at":"2026-09-29T18:42:10.000Z","expires_at":"2026-09-30T18:42:10.000Z"}""", 409) else json("{}", 201)
        }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.TRAKT)) as RealtimeScrobble
        tracker.scrobble(episode, ScrobbleState.START, 1.5)
        tracker.scrobble(episode, ScrobbleState.SEEK, 42.0)
        tracker.scrobble(movie, ScrobbleState.STOP, 91.25)

        assertEquals(listOf("/scrobble/start", "/scrobble/pause", "/scrobble/start", "/scrobble/stop"), service.requests.map { it.path })
        assertEquals(
            parse("""{"show":{"ids":{"imdb":"tt0903747","tvdb":81189}},"episode":{"season":1,"number":2},"progress":1.5}"""),
            parse(service.requests[0].body),
        )
        assertEquals(parse("""{"movie":{"ids":{"imdb":"tt0111161","tmdb":278}},"progress":91.25}"""), parse(service.requests[3].body))
        assertEquals("Bearer access-1", service.requests[0].headers["Authorization"])
    }

    @Test
    fun aSeeksPauseAndStartGoASecondApartAsTraktsWriteLimitAsks() = runTest {
        val sentAt = mutableListOf<Long>()
        val service = ScriptedService(this) {
            sentAt += testScheduler.currentTime
            json("{}", 201)
        }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.TRAKT)) as RealtimeScrobble
        tracker.scrobble(episode, ScrobbleState.SEEK, 42.0)
        assertEquals(listOf("/scrobble/pause", "/scrobble/start"), service.requests.map { it.path })
        assertTrue(sentAt[1] - sentAt[0] >= 1_000, "${sentAt[1] - sentAt[0]} ms apart")
    }

    @Test
    fun historyWritesCarryTheWatchDateAndAStopBelowTraktsRuleIsRecorded() = runTest {
        val service = ScriptedService(this) { json("""{"added":{"movies":1,"episodes":0}}""", 201) }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.TRAKT)) as RealtimeScrobble
        tracker.markWatched(episode, Instant.parse("2026-09-28T20:00:00Z"))
        tracker.markUnwatched(movie)
        // 85% already recorded a play at Trakt; 75% did not, and the profile's lower threshold still counts it.
        tracker.reconcileWatchedAfterStop(movie, 85.0)
        tracker.reconcileWatchedAfterStop(movie, 75.0)

        assertEquals(listOf("/sync/history", "/sync/history/remove", "/sync/history"), service.requests.map { it.path })
        assertEquals(
            parse(
                """{"shows":[{"ids":{"imdb":"tt0903747","tvdb":81189},"seasons":[{"number":1,"episodes":[{"watched_at":"2026-09-28T20:00:00Z","number":2}]}]}]}""",
            ),
            parse(service.requests[0].body),
        )
        assertEquals(parse("""{"movies":[{"ids":{"imdb":"tt0111161","tmdb":278}}]}"""), parse(service.requests[1].body))
    }

    @Test
    fun aBatchIsOneRequestWithEachShowsEpisodesUnderIt() = runTest {
        val service = ScriptedService(this) { json("""{"added":{"movies":1,"episodes":3}}""", 201) }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.TRAKT)) as EpisodeHistory
        val at = Instant.parse("2026-09-28T20:00:00Z")
        val items = listOf(
            HistoryItem(episode, at),
            HistoryItem(movie, at),
            HistoryItem(episode.copy(videoId = "tt0903747:2:1", season = 2, episode = 1), at),
            HistoryItem(episode.copy(videoId = "tt0903747:1:3", episode = 3), at),
            // Named twice: sent once.
            HistoryItem(episode, at),
            // Nothing to name it by: left out.
            HistoryItem(episode.copy(videoId = "tt0903747:x", season = null), at),
        )
        tracker.writeHistory(items, watched = true)
        tracker.writeHistory(items, watched = false)

        assertEquals(listOf("/sync/history", "/sync/history/remove"), service.requests.map { it.path })
        val date = "\"watched_at\":\"2026-09-28T20:00:00Z\""
        assertEquals(
            parse(
                """{"movies":[{"ids":{"imdb":"tt0111161","tmdb":278},$date}],"shows":[{"ids":{"imdb":"tt0903747","tvdb":81189},"seasons":[""" +
                    """{"number":1,"episodes":[{"number":2,$date},{"number":3,$date}]},{"number":2,"episodes":[{"number":1,$date}]}]}]}""",
            ),
            parse(service.requests[0].body),
        )
        assertEquals(
            parse(
                """{"movies":[{"ids":{"imdb":"tt0111161","tmdb":278}}],"shows":[{"ids":{"imdb":"tt0903747","tvdb":81189},"seasons":[""" +
                    """{"number":1,"episodes":[{"number":2},{"number":3}]},{"number":2,"episodes":[{"number":1}]}]}]}""",
            ),
            parse(service.requests[1].body),
        )
    }

    @Test
    fun anEpisodeWithoutExternalNumbersIsNotWritten() = runTest {
        val service = ScriptedService(this) { error("no request expected") }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.TRAKT)) as RealtimeScrobble
        tracker.markWatched(episode.copy(season = null, episode = null))
        tracker.scrobble(movie.copy(ids = ExternalIds()), ScrobbleState.START, 0.0)
        assertTrue(service.requests.isEmpty())
    }

    @Test
    fun aRejectedTokenIsRefreshedOnceAndTheRequestRetried() = runTest {
        val service = ScriptedService(this) { request ->
            if (request.headers["Authorization"] == "Bearer access-1") Answer(401) else json("{}", 201)
        }
        val credentials = FakeCredentials(TrackerService.TRAKT)
        provider(service).tracker(credentials).markWatched(movie)
        assertEquals(1, credentials.refreshes)
        assertEquals(listOf("Bearer access-1", "Bearer access-2"), service.requests.map { it.headers["Authorization"] })
    }

    @Test
    fun failuresMapToTheirKinds() = runTest {
        suspend fun failure(answer: () -> Answer, credentials: FakeCredentials = FakeCredentials(TrackerService.TRAKT)): Throwable =
            assertFailsWith<TrackerException> { provider(ScriptedService(this) { answer() }).tracker(credentials).markWatched(movie) }

        val limited = assertIs<TrackerException.RateLimited>(failure({ Answer(429, headers = mapOf("Retry-After" to "30")) }))
        assertEquals(30, limited.retryAfterSeconds)
        assertTrue(limited.transient)
        // A timestamp (or milliseconds) sent as Retry-After isn't a wait of years: the service's own hold applies.
        val stamped = assertIs<TrackerException.RateLimited>(failure({ Answer(429, headers = mapOf("Retry-After" to "1767225600")) }))
        assertNull(stamped.retryAfterSeconds)

        val outage = assertIs<TrackerException.Api>(failure({ Answer(503) }))
        assertEquals(503, outage.status)
        assertTrue(outage.transient)

        val refused = assertIs<TrackerException.Api>(failure({ Answer(422) }))
        assertEquals(false, refused.transient)

        val dead = FakeCredentials(TrackerService.TRAKT).apply {
            refreshFailure = TrackerException.Auth(TrackerService.TRAKT, "refresh failed", 400, permanent = true)
        }
        val expired = assertIs<TrackerException.Auth>(failure({ Answer(401) }, dead))
        assertTrue(expired.permanent)

        val offline = assertFailsWith<TrackerException.Network> {
            provider(ScriptedService(this) { throw IOException("offline") }).tracker(FakeCredentials(TrackerService.TRAKT)).markWatched(movie)
        }
        assertTrue(offline.transient)
    }

    @Test
    fun anAnswerThatCantBeReadFailsRatherThanReadingAsNothing() = runTest {
        val watchlist = TrackerCatalog(TrackerService.TRAKT, "watchlist", TrackerCatalog.Kind.WATCHLIST, type = null)
        val rated = RatingTarget(RatingKind.MOVIE, ExternalIds(imdb = "tt0111161"))
        // A captive portal's page, an empty body, and an object where Trakt documents a list.
        for (body in listOf("<html>Sign in to the Wi-Fi</html>", "", """{"status":"ok"}""")) {
            val provider = provider(ScriptedService(this) { json(body) })
            val credentials = FakeCredentials(TrackerService.TRAKT)
            val reads: List<suspend () -> Any?> = listOf(
                { provider.catalog(credentials).catalogs() },
                { provider.catalog(credentials).page(watchlist) },
                { (provider.tracker(credentials) as Rating).rating(rated) },
            )
            for (read in reads) {
                assertEquals(ApiFailureCategory.UNREADABLE, assertFailsWith<TrackerException.Api>(body) { read() }.category)
            }
        }
        val portal = provider(ScriptedService(this) { json("<html>Sign in to the Wi-Fi</html>") })
        val ratings = portal.ratings(FakeCredentials(TrackerService.TRAKT))
        val failure = assertFailsWith<TrackerException.Api> { ratings.ratings(RatingTitle(true, ExternalIds(imdb = "tt0111161"))) }
        assertEquals(ApiFailureCategory.UNREADABLE, failure.category)
    }

    @Test
    fun refreshingRotatesBothTokensAndARefusalIsPermanent() = runTest {
        val service = ScriptedService(this) { request ->
            when (request.field("refresh_token")) {
                "refresh-1" -> json(
                    """{"access_token":"access-2","token_type":"bearer","expires_in":604800,"refresh_token":"refresh-2","scope":"public","created_at":1790000000}""",
                )
                "refresh-dead" -> json("""{"error":"invalid_grant","error_description":"session not found"}""", 400)
                else -> Answer(502)
            }
        }
        val refresher = provider(service).refresher
        val old = TrackerSession("access-1", "refresh-1", now - 10, "sean", 1_789_000_000)
        assertEquals(TrackerSession("access-2", "refresh-2", 1_790_000_000L + 604800, "sean", 1_790_000_000L), refresher.refresh(old))
        assertEquals("refresh_token", service.requests.single().field("grant_type"))

        assertTrue(assertFailsWith<TrackerException.Auth> { refresher.refresh(old.copy(refreshToken = "refresh-dead")) }.permanent)
        assertEquals(false, assertFailsWith<TrackerException.Auth> { refresher.refresh(old.copy(refreshToken = "refresh-x")) }.permanent)
    }

    @Test
    fun ratingsReadTheMatchingEntryAndWriteTheScore() = runTest {
        val service = ScriptedService(this) { request ->
            when (request.path) {
                "/sync/ratings/episodes" -> json(
                    """[
                      {"rated_at":"2026-01-01T00:00:00.000Z","rating":7,"type":"episode","episode":{"season":1,"number":1,"ids":{"trakt":1}},"show":{"title":"Breaking Bad","ids":{"trakt":1388,"imdb":"tt0903747","tvdb":81189}}},
                      {"rated_at":"2026-01-02T00:00:00.000Z","rating":9,"type":"episode","episode":{"season":1,"number":2,"ids":{"trakt":2}},"show":{"title":"Breaking Bad","ids":{"trakt":1388,"imdb":"tt0903747","tvdb":81189}}}
                    ]""",
                )
                else -> json("""{"added":{"episodes":1}}""", 201)
            }
        }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.TRAKT)) as Rating
        val target = RatingTarget(RatingKind.EPISODE, ExternalIds(imdb = "tt0903747"), season = 1, episode = 2)
        assertEquals(9, tracker.rating(target))
        assertNull(tracker.rating(target.copy(episode = 3)))

        tracker.rate(target, 12)
        assertEquals(
            parse("""{"shows":[{"ids":{"imdb":"tt0903747"},"seasons":[{"number":1,"episodes":[{"number":2,"rating":10}]}]}]}"""),
            parse(service.to("/sync/ratings").single().body),
        )
        tracker.clearRating(RatingTarget(RatingKind.MOVIE, ExternalIds(tmdb = 278)))
        assertEquals(parse("""{"movies":[{"ids":{"tmdb":278}}]}"""), parse(service.to("/sync/ratings/remove").single().body))
    }

    @Test
    fun theWatchlistRowOpensItemsByTheirStremioIds() = runTest {
        val service = ScriptedService(this) { request ->
            if (request.path == "/users/me/lists") return@ScriptedService json("[]")
            assertEquals("/sync/watchlist", request.path)
            assertEquals("2", request.url.parameters["page"])
            json(
                """[
                  {"rank":1,"listed_at":"2026-01-01T00:00:00.000Z","type":"movie","movie":{"title":"TRON: Legacy","year":2010,"ids":{"trakt":1,"slug":"tron-legacy-2010","imdb":"tt1104001","tmdb":20526},"images":{"poster":["walter-r2.trakt.tv/images/movies/000/000/001/posters/medium/a.jpg.webp"]}}},
                  {"rank":2,"listed_at":"2026-01-01T00:00:00.000Z","type":"show","show":{"title":"Shōgun","year":2024,"ids":{"trakt":2,"tmdb":126308}}},
                  {"rank":3,"listed_at":"2026-01-01T00:00:00.000Z","type":"season","season":{"number":1,"ids":{"trakt":3}},"show":{"title":"Ignored","ids":{"trakt":4}}},
                  {"rank":4,"listed_at":"2026-01-01T00:00:00.000Z","type":"movie","movie":{"title":"No ids","year":2020,"ids":{"trakt":5}}}
                ]""",
                headers = mapOf("X-Pagination-Page" to "2", "X-Pagination-Page-Count" to "3"),
            )
        }
        val catalog = provider(service).catalog(FakeCredentials(TrackerService.TRAKT))
        val watchlist = catalog.catalogs().first { it.kind == TrackerCatalog.Kind.WATCHLIST }
        val page = catalog.page(watchlist, "2")

        assertEquals(listOf("tt1104001" to "movie", "tmdb:126308" to "series"), page.items.map { it.id to it.type })
        assertEquals("https://walter-r2.trakt.tv/images/movies/000/000/001/posters/medium/a.jpg.webp", page.items[0].poster)
        assertEquals("2010", page.items[0].releaseInfo)
        assertEquals("3", page.next)
    }

    @Test
    fun theUsersListsAreRowsAndTheWatchlistIsWritable() = runTest {
        val service = ScriptedService(this) { request ->
            when (request.path) {
                "/users/me/lists" -> json("""[{"name":"Star Wars in machete order","item_count":5,"ids":{"trakt":55,"slug":"star-wars-in-machete-order"}}]""")
                "/users/me/lists/55/items/movie,show" -> json(
                    """[{"rank":1,"id":101,"listed_at":"2026-01-01T00:00:00.000Z","type":"movie","movie":{"title":"Star Wars: Episode IV - A New Hope","year":1977,"ids":{"trakt":1,"imdb":"tt0076759","tmdb":11}}}]""",
                )
                "/sync/watchlist", "/sync/watchlist/remove" -> json("""{"added":{"movies":1},"existing":{"movies":0},"not_found":{"movies":[]}}""", 201)
                else -> error("unexpected $request")
            }
        }
        val catalog = provider(service).catalog(FakeCredentials(TrackerService.TRAKT))
        val list = catalog.catalogs().single { it.kind == TrackerCatalog.Kind.LIST }
        assertEquals("Star Wars in machete order", list.name)
        assertEquals(listOf("tt0076759"), catalog.page(list).items.map { it.id })
        // A list row from a collection file names any id: only a list's number goes into the path.
        for (id in listOf("list.55/../../../sync/history", "list.55?x=1", "list.slug")) {
            assertFailsWith<TrackerException.Unsupported>(id) { catalog.page(list.copy(id = id)) }
        }
        assertEquals(1, service.to("/users/me/lists/55/items/movie,show").size, "nothing asked for them")

        val watchlist = catalog as TrackerWatchlist
        watchlist.addToWatchlist(WatchlistTarget(isMovie = false, ids = ExternalIds(imdb = "tt0903747", tvdb = 81189)))
        assertEquals(parse("""{"shows":[{"ids":{"imdb":"tt0903747","tvdb":81189}}]}"""), parse(service.to("/sync/watchlist").single().body))
        watchlist.removeFromWatchlist(WatchlistTarget(isMovie = true, ids = ExternalIds(tmdb = 11)))
        assertEquals(parse("""{"movies":[{"ids":{"tmdb":11}}]}"""), parse(service.to("/sync/watchlist/remove").single().body))
        assertFailsWith<TrackerException.Unsupported> { watchlist.addToWatchlist(WatchlistTarget(isMovie = true)) }
    }
}
