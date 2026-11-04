package com.edde746.tortuga.trackers.mdblist

import com.edde746.tortuga.trackers.DeviceCodeGrant
import com.edde746.tortuga.trackers.DeviceCodePoller
import com.edde746.tortuga.trackers.DevicePoll
import com.edde746.tortuga.trackers.EpisodeHistory
import com.edde746.tortuga.trackers.ExternalIds
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * MDBList against its documented answers:
 * - device code, refresh and revoke: https://api.mdblist.com/docs/authentication/
 * - scrobble, `/sync/watched`, `/sync/ratings`, `/user`, `/lists/user`, list items: the OpenAPI schema behind
 *   https://api.mdblist.com/docs/ (`/schema/`), including its 429 + `Retry-After` rate-limit rule
 */
class MdblistTest {
    private val now = 1_790_000_000L

    private fun provider(service: ScriptedService) = MdblistProvider(service.http(TrackerService.MDBLIST), "client-id") { now }

    private val grant = DeviceCodeGrant("device-code-1", "ABCD-EFGH", "https://mdblist.com/oauth/device/", null, 300, 5)

    private val movie = TrackerContext(
        metaId = "tt0111161",
        videoId = "tt0111161",
        kind = TrackerMediaKind.MOVIE,
        contentType = TrackerContentType.MOVIE,
        ids = ExternalIds(imdb = "tt0111161", tmdb = 278, tvdb = 1),
    )

    private val episode = TrackerContext(
        metaId = "tmdb:1396",
        videoId = "tmdb:1396:1:2",
        kind = TrackerMediaKind.EPISODE,
        contentType = TrackerContentType.SERIES,
        ids = ExternalIds(tmdb = 1396),
        season = 1,
        episode = 2,
    )

    @Test
    fun theDeviceFlowPrefillsTheCodeAndPollsUntilApproved() = runTest {
        val polls = ArrayDeque(listOf("authorization_pending", "slow_down", "ok"))
        val service = ScriptedService(this) { request ->
            when (request.path) {
                "/oauth/device-authorization/" -> json(
                    """{"device_code":"device-code-1","user_code":"ABCD-EFGH","verification_uri":"https://mdblist.com/oauth/device/","expires_in":300,"interval":5}""",
                )
                "/oauth/token/" -> when (val answer = polls.removeFirst()) {
                    "ok" -> json("""{"access_token":"access-1","token_type":"Bearer","expires_in":2592000,"refresh_token":"refresh-1","scope":"write"}""")
                    else -> json("""{"error":"$answer"}""", 400)
                }
                "/user" -> json("""{"user_id":1,"username":"linaspurinis","name":"Linas","plan":"free"}""")
                else -> error("unexpected $request")
            }
        }
        val signIn = provider(service).signIn
        val requested = signIn.requestCode()
        assertEquals("https://mdblist.com/oauth/device/?user_code=ABCD-EFGH", requested.verificationUrlComplete)
        val device = service.to("/oauth/device-authorization/").single()
        assertEquals(mapOf("client_id" to "client-id", "scope" to "write"), device.form())
        // MDBList answers 400 to `application/x-www-form-urlencoded; charset=UTF-8`.
        assertEquals("application/x-www-form-urlencoded", device.contentType)

        val result = DeviceCodePoller.poll(requested, testScheduler.timeSource) { signIn.probe(requested) }
        val session = signIn.session(assertIs<DevicePoll.Success>(result).token)

        assertEquals(TrackerSession("access-1", "refresh-1", now + 2592000, "linaspurinis", now), session)
        assertEquals(
            mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to "device-code-1", "client_id" to "client-id"),
            service.to("/oauth/token/").first().form(),
        )
        assertEquals("Bearer access-1", service.to("/user").single().headers["Authorization"])
    }

    @Test
    fun anExpiredGoneOrDeniedGrantEndsThePolling() = runTest {
        for ((error, expected) in listOf("expired_token" to DevicePoll.Expired, "device_not_found" to DevicePoll.Expired, "access_denied" to DevicePoll.Denied)) {
            assertEquals(expected, provider(ScriptedService(this) { json("""{"error":"$error"}""", 400) }).signIn.probe(grant), error)
        }
        // An unknown answer keeps polling; the deadline still ends the flow.
        assertEquals(DevicePoll.Pending, provider(ScriptedService(this) { Answer(500) }).signIn.probe(grant))
    }

    @Test
    fun refreshingAsAPublicClient() = runTest {
        val service = ScriptedService(this) { request ->
            if (request.form()["refresh_token"] == "refresh-1") {
                json("""{"access_token":"access-2","token_type":"Bearer","expires_in":2592000,"refresh_token":"refresh-2","scope":"write"}""")
            } else {
                json("""{"error":"invalid_grant"}""", 400)
            }
        }
        val old = TrackerSession("access-1", "refresh-1", now, "linaspurinis", now - 2592000)
        assertEquals(TrackerSession("access-2", "refresh-2", now + 2592000, "linaspurinis", now), provider(service).refresher.refresh(old))
        assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "refresh-1", "client_id" to "client-id"), service.requests.single().form())
        assertTrue(assertFailsWith<TrackerException.Auth> { provider(service).refresher.refresh(old.copy(refreshToken = "refresh-x")) }.permanent)
    }

    @Test
    fun revokingSendsTheRefreshToken() = runTest {
        val service = ScriptedService(this) { Answer(200) }
        provider(service).revoke(TrackerSession("access-1", "refresh-1", now, "linaspurinis", now))
        val revoke = service.to("/oauth/revoke_token/").single()
        // A revoked access token goes on working until it expires; a revoked refresh token ends the grant.
        assertEquals(mapOf("token" to "refresh-1", "client_id" to "client-id"), revoke.form())
        assertEquals("application/x-www-form-urlencoded", revoke.contentType)
    }

    @Test
    fun scrobblesNestTheEpisodeInTheShowAndClampTheProgress() = runTest {
        val service = ScriptedService(this) { json("""{"id":1,"action":"start","progress":10}""", 201) }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.MDBLIST)) as RealtimeScrobble
        tracker.scrobble(episode, ScrobbleState.START, 10.123)
        tracker.scrobble(movie, ScrobbleState.SEEK, 50.0)
        tracker.scrobble(movie, ScrobbleState.STOP, 100.4)

        assertEquals(listOf("/scrobble/start", "/scrobble/start", "/scrobble/stop"), service.requests.map { it.path })
        assertEquals(parse("""{"show":{"ids":{"tmdb":1396},"season":{"number":1,"episode":{"number":2}}},"progress":10.12}"""), parse(service.requests[0].body))
        // No TVDB id in MDBList's id block.
        assertEquals(parse("""{"movie":{"ids":{"imdb":"tt0111161","tmdb":278}},"progress":100.0}"""), parse(service.requests[2].body))
    }

    @Test
    fun watchedMarksCarryTheDateAndATvdbOnlyItemIsSkipped() = runTest {
        val service = ScriptedService(this) { json("""{"updated":{"movies":1},"not_found":{},"errors":[]}""") }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.MDBLIST)) as RealtimeScrobble
        tracker.markWatched(episode, Instant.parse("2025-10-21T12:00:00Z"))
        tracker.reconcileWatchedAfterStop(movie, 80.0)
        tracker.markUnwatched(movie)
        val tvdbOnly = episode.copy(ids = ExternalIds(tvdb = 81189))
        tracker.markWatched(tvdbOnly)

        assertEquals(listOf("/sync/watched", "/sync/watched/remove"), service.requests.map { it.path })
        assertEquals(
            parse("""{"shows":[{"ids":{"tmdb":1396},"seasons":[{"number":1,"episodes":[{"number":2,"watched_at":"2025-10-21T12:00:00Z"}]}]}]}"""),
            parse(service.requests[0].body),
        )
        assertNull((tracker as EpisodeHistory).historyRowIdentity(tvdbOnly))
        assertEquals("tmdb=1396", tracker.historyRowIdentity(episode))
    }

    @Test
    fun aRejectedTokenRefreshesAndLimitsAreRateLimited() = runTest {
        val service = ScriptedService(this) { request ->
            if (request.headers["Authorization"] == "Bearer access-1") json("""{"detail":"Invalid token"}""", 401) else json("{}")
        }
        val credentials = FakeCredentials(TrackerService.MDBLIST)
        provider(service).tracker(credentials).markWatched(movie)
        assertEquals(1, credentials.refreshes)

        val limited = ScriptedService(this) { json("""{"error":"API rate limit exceeded!"}""", 429, mapOf("Retry-After" to "120")) }
        val error = assertFailsWith<TrackerException.RateLimited> { provider(limited).tracker(FakeCredentials(TrackerService.MDBLIST)).markWatched(movie) }
        assertEquals(120, error.retryAfterSeconds)
    }

    @Test
    fun ratingsAreFoundInTheirKindsList() = runTest {
        val service = ScriptedService(this) { request ->
            when (request.method) {
                "GET" -> json(
                    """{"movies":[{"rating":8,"rated_at":"2025-10-21T14:00:00Z","movie":{"title":"The Shawshank Redemption","ids":{"imdb":"tt0111161","tmdb":278}}}],"shows":[],"seasons":[],"episodes":[],"pagination":{"total":1,"limit":100,"offset":0}}""",
                )
                else -> json("""{"updated":{"movies":1},"not_found":{},"errors":[]}""")
            }
        }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.MDBLIST)) as Rating
        assertEquals(8, tracker.rating(RatingTarget(RatingKind.MOVIE, ExternalIds(tmdb = 278))))
        tracker.clearRating(RatingTarget(RatingKind.MOVIE, ExternalIds(imdb = "tt0111161")))
        assertEquals(parse("""{"movies":[{"ids":{"imdb":"tt0111161"}}]}"""), parse(service.to("/sync/ratings/remove").single().body))
        assertFailsWith<TrackerException.Unsupported> { tracker.rate(RatingTarget(RatingKind.SHOW, ExternalIds(tvdb = 81189)), 7) }
    }

    @Test
    fun theUsersListsAreRowsPagedByOffset() = runTest {
        val service = ScriptedService(this) { request ->
            when (request.path) {
                "/lists/user" -> json("""[{"id":1234,"name":"Sci-fi","slug":"sci-fi","items":150,"likes":0,"ranked":0}]""")
                "/lists/1234/items" -> {
                    assertEquals("100", request.url.parameters["offset"])
                    json(
                        """{"movies":[{"id":917496,"rank":1,"adult":0,"title":"Beetlejuice Beetlejuice","imdb_id":"tt2049403","tvdb_id":null,"ids":{"mdblist":"m917496","imdb":"tt2049403","tmdb":917496,"tvdb":null},"language":"en","mediatype":"movie","release_year":2024}],
                           "shows":[{"id":258902,"rank":2,"adult":0,"title":"English Teacher","imdb_id":"tt20782190","tvdb_id":421968,"mediatype":"show","release_year":2024}]}""",
                        headers = mapOf("X-Has-More" to "false"),
                    )
                }
                else -> error("unexpected $request")
            }
        }
        val catalog = provider(service).catalog(FakeCredentials(TrackerService.MDBLIST))
        val rows = catalog.catalogs()
        assertEquals(listOf(TrackerCatalog.Kind.WATCHLIST, TrackerCatalog.Kind.LIST), rows.map { it.kind })
        assertEquals("Sci-fi", rows[1].name)
        val page = catalog.page(rows[1], "100")
        assertEquals(listOf("tt2049403" to "movie", "tt20782190" to "series"), page.items.map { it.id to it.type })
        assertEquals("2024", page.items[0].releaseInfo)
        assertNull(page.next)
        // A list row from a collection file names any id: only a list's number goes into the path.
        for (id in listOf("list.1234/../../user", "list.1234?x=1", "list.sci-fi")) {
            assertFailsWith<TrackerException.Unsupported>(id) { catalog.page(rows[1].copy(id = id), null) }
        }
        assertEquals(2, service.requests.size, "nothing asked for them")
    }

    @Test
    fun theWatchlistTakesTheDocumentedIdShape() = runTest {
        val service = ScriptedService(this) { json("""{"added":1,"existing":0,"not_found":0}""") }
        val watchlist = provider(service).catalog(FakeCredentials(TrackerService.MDBLIST)) as TrackerWatchlist
        watchlist.addToWatchlist(WatchlistTarget(isMovie = true, ids = ExternalIds(imdb = "tt0111161", tmdb = 278, tvdb = 1)))
        watchlist.removeFromWatchlist(WatchlistTarget(isMovie = false, ids = ExternalIds(tmdb = 1396)))
        assertEquals(listOf("/watchlist/items/add", "/watchlist/items/remove"), service.requests.map { it.path })
        assertEquals(parse("""{"movies":[{"ids":{"imdb":"tt0111161","tmdb":278}}]}"""), parse(service.requests[0].body))
        assertEquals(parse("""{"shows":[{"ids":{"tmdb":1396}}]}"""), parse(service.requests[1].body))
    }
}
