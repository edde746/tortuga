package com.edde746.tortuga.trackers.mal

import com.edde746.tortuga.trackers.AnimeIds
import com.edde746.tortuga.trackers.Rating
import com.edde746.tortuga.trackers.RatingKind
import com.edde746.tortuga.trackers.RatingTarget
import com.edde746.tortuga.trackers.RelayResult
import com.edde746.tortuga.trackers.SeriesProgress
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
import com.edde746.tortuga.trackers.client.Recorded
import com.edde746.tortuga.trackers.client.ScriptedService
import com.edde746.tortuga.trackers.client.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

/**
 * MyAnimeList against its documented answers:
 * - tokens: https://myanimelist.net/apiconfig/references/authorization ("Refresh an access token")
 * - API v2: https://myanimelist.net/apiconfig/references/api/v2 (`GET /users/@me`, `GET /anime/{id}`,
 *   `PUT /anime/{id}/my_list_status`, `GET /users/{user}/animelist`, `GET /anime/ranking`)
 */
class MalTest {
    private val now = 1_790_000_000L

    private fun provider(service: ScriptedService) = MalProvider(service.http(TrackerService.MYANIMELIST), "client-id") { now }

    private fun episode(progress: Int) = TrackerContext(
        metaId = "kitsu:11",
        videoId = "kitsu:11:$progress",
        kind = TrackerMediaKind.EPISODE,
        contentType = TrackerContentType.ANIME,
        anime = AnimeIds(mal = 21, kitsu = 11),
        animeEpisode = progress,
    )

    /** A list entry as `GET /anime/21?fields=num_episodes,my_list_status` answers it; `null` = not on the list. */
    private fun TestScope.entry(listStatus: String?, onPut: (Recorded) -> Unit = {}) = ScriptedService(this) { request ->
        when (request.method) {
            "GET" -> json("""{"id":21,"title":"One Punch Man","num_episodes":12${listStatus?.let { ""","my_list_status":$it""" }.orEmpty()}}""")
            "PUT" -> {
                onPut(request)
                json("""{"status":"watching","score":0,"num_episodes_watched":5,"is_rewatching":false,"updated_at":"2026-09-29T00:00:00+00:00"}""")
            }
            else -> error("unexpected $request")
        }
    }

    private suspend fun ScriptedService.watch(context: TrackerContext): Map<String, String>? {
        (provider(this).tracker(FakeCredentials(TrackerService.MYANIMELIST))).markWatched(context)
        return requests.lastOrNull { it.method == "PUT" }?.form()
    }

    private suspend fun ScriptedService.unwatch(context: TrackerContext): Map<String, String>? {
        provider(this).tracker(FakeCredentials(TrackerService.MYANIMELIST)).markUnwatched(context)
        return requests.lastOrNull { it.method == "PUT" }?.form()
    }

    @Test
    fun aRelaySessionKeepsTheRefreshTokenAndLooksUpTheName() = runTest {
        val service = ScriptedService(this) { json("""{"id":1,"name":"Xinil","joined_at":"2004-11-05T00:00:00+00:00"}""") }
        val session = provider(service).signIn.session(RelayResult("access-1", "refresh-1", 2_678_400))
        assertEquals(TrackerSession("access-1", "refresh-1", now + 2_678_400, "Xinil", now), session)
        assertEquals("/v2/users/@me", service.requests.single().path)

        assertFailsWith<TrackerException.SignIn> { provider(service).signIn.session(RelayResult("access-1", null, 2_678_400)) }
    }

    @Test
    fun refreshingGoesStraightToMyAnimeList() = runTest {
        val service = ScriptedService(this) { request ->
            if (request.form()["refresh_token"] == "refresh-1") {
                json("""{"token_type":"Bearer","expires_in":2678400,"access_token":"access-2","refresh_token":"refresh-2"}""")
            } else {
                json("""{"error":"invalid_grant","message":"The refresh token is invalid."}""", 400)
            }
        }
        val old = TrackerSession("access-1", "refresh-1", now, "Xinil", now - 100)
        assertEquals(TrackerSession("access-2", "refresh-2", now + 2_678_400, "Xinil", now), provider(service).refresher.refresh(old))
        val request = service.requests.single()
        assertEquals("https://myanimelist.net/v1/oauth2/token", request.url.toString())
        assertEquals(mapOf("client_id" to "client-id", "grant_type" to "refresh_token", "refresh_token" to "refresh-1"), request.form())
        assertTrue(assertFailsWith<TrackerException.Auth> { provider(service).refresher.refresh(old.copy(refreshToken = "refresh-x")) }.permanent)
    }

    @Test
    fun progressIsWrittenAsTheAbsoluteEpisodeCount() = runTest {
        assertEquals(mapOf("num_watched_episodes" to "5", "status" to "watching"), entry(null).watch(episode(5)))
        // The last episode completes the entry; a claim past the total is capped.
        assertEquals(mapOf("num_watched_episodes" to "12", "status" to "completed"), entry(null).watch(episode(13)))
        val tracker = provider(entry(null)).tracker(FakeCredentials(TrackerService.MYANIMELIST)) as SeriesProgress
        assertEquals("21", tracker.seriesEntryId(episode(5)))
        assertEquals(7, tracker.seriesProgress(episode(5).copy(animeProgress = 7)))
    }

    @Test
    fun rewatchesAreKeptAndACompletedRewatchIsCounted() = runTest {
        // New progress on a completed entry starts a rewatch; its status stays completed.
        assertEquals(
            mapOf("num_watched_episodes" to "3", "is_rewatching" to "true"),
            entry("""{"status":"completed","num_episodes_watched":12,"is_rewatching":false,"num_times_rewatched":1}""").watch(episode(3)),
        )
        // Finishing a rewatch lands on completed with the count raised, as an absolute value.
        assertEquals(
            mapOf("num_watched_episodes" to "12", "status" to "completed", "is_rewatching" to "false", "num_times_rewatched" to "2"),
            entry("""{"status":"completed","num_episodes_watched":11,"is_rewatching":true,"num_times_rewatched":1}""").watch(episode(12)),
        )
    }

    @Test
    fun anEarlierEpisodeNeverTakesAnEntryInProgressBackwards() = runTest {
        // Watching, at 8: episode 3 replayed or marked leaves it there; episode 8 again is written.
        assertNull(entry("""{"status":"watching","num_episodes_watched":8,"is_rewatching":false}""").watch(episode(3)))
        assertEquals(
            mapOf("num_watched_episodes" to "8", "status" to "watching"),
            entry("""{"status":"watching","num_episodes_watched":8,"is_rewatching":false}""").watch(episode(8)),
        )
    }

    @Test
    fun anEarlierEpisodeNeverTakesARewatchBackwards() = runTest {
        // A rewatch at 8, which MyAnimeList keeps as completed with is_rewatching: forward only, like any entry in progress.
        val rewatch = """{"status":"completed","num_episodes_watched":8,"is_rewatching":true,"num_times_rewatched":1}"""
        assertNull(entry(rewatch).watch(episode(3)))
        assertEquals(mapOf("num_watched_episodes" to "9", "is_rewatching" to "true"), entry(rewatch).watch(episode(9)))
    }

    @Test
    fun unwatchingTakesAnEntryBackToBeforeTheFirstEpisodeUnmarked() = runTest {
        val atFour = """{"status":"watching","num_episodes_watched":4,"is_rewatching":false}"""
        // From its first episode: back to 0. From episode 3: the two before it stay.
        assertEquals(mapOf("num_watched_episodes" to "0", "status" to "watching"), entry(atFour).unwatch(episode(1)))
        assertEquals(mapOf("num_watched_episodes" to "2", "status" to "watching"), entry(atFour).unwatch(episode(3)))
        // Episodes the entry hasn't reached: nothing to undo.
        assertNull(entry(atFour).unwatch(episode(5)))
        // A rewatch stays one.
        assertEquals(
            mapOf("num_watched_episodes" to "2", "is_rewatching" to "true"),
            entry("""{"status":"completed","num_episodes_watched":8,"is_rewatching":true,"num_times_rewatched":1}""").unwatch(episode(3)),
        )
        // Without the episode to start from, nothing is written, or even read.
        val unplaced = entry(atFour)
        assertNull(unplaced.unwatch(episode(4).copy(animeEpisode = null)))
        assertEquals(emptyList(), unplaced.requests.map { it.method })
    }

    @Test
    fun unwatchingNeverTouchesACompletedEntryOrOneMyAnimeListDoesNotKnow() = runTest {
        val completed = entry("""{"status":"completed","num_episodes_watched":12,"is_rewatching":false}""")
        assertNull(completed.unwatch(episode(1)))
        assertEquals(listOf("GET"), completed.requests.map { it.method })

        val unknown = ScriptedService(this) { Answer(404) }
        assertNull(unknown.unwatch(episode(1)))
        assertEquals(listOf("GET"), unknown.requests.map { it.method })
    }

    @Test
    fun aWriteWhoseEntryCanNotBeReadFailsWithTheRead() = runTest {
        // Written blind, the unwatch would be lost and the rewatch of a completed entry written as watching: the read's
        // failure goes to the caller, which queues the write.
        val limited = ScriptedService(this) { Answer(429, headers = mapOf("Retry-After" to "30")) }
        assertEquals(30, assertFailsWith<TrackerException.RateLimited> { limited.unwatch(episode(1)) }.retryAfterSeconds)
        val failing = ScriptedService(this) { request -> if (request.method == "GET") Answer(503) else json("{}") }
        assertEquals(503, assertFailsWith<TrackerException.Api> { failing.watch(episode(3)) }.status)
        val unreachable = ScriptedService(this) { throw IOException("connection reset") }
        assertFailsWith<TrackerException.Network> { unreachable.unwatch(episode(1)) }
        for (service in listOf(limited, failing, unreachable)) assertEquals(listOf("GET"), service.requests.map { it.method })
    }

    @Test
    fun eachWriteReadsTheEntryAfresh() = runTest {
        var listStatus = """{"status":"plan_to_watch","num_episodes_watched":0,"is_rewatching":false}"""
        val service = ScriptedService(this) { request ->
            if (request.method == "GET") json("""{"id":21,"num_episodes":12,"my_list_status":$listStatus}""") else json("{}")
        }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.MYANIMELIST))
        // Not started yet: the unwatch writes nothing. Then watched to 5 elsewhere: episode 3 leaves it there.
        tracker.markUnwatched(episode(1))
        listStatus = """{"status":"watching","num_episodes_watched":5,"is_rewatching":false}"""
        tracker.markWatched(episode(3))
        assertEquals(listOf("GET", "GET"), service.requests.map { it.method })
    }

    @Test
    fun scoresAreMyAnimeListsOwnOneToTen() = runTest {
        val service = entry("""{"status":"watching","score":8,"num_episodes_watched":4,"is_rewatching":false}""")
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.MYANIMELIST)) as Rating
        val target = RatingTarget(RatingKind.SHOW, anime = AnimeIds(mal = 21))
        assertEquals(8, tracker.rating(target))
        tracker.rate(target, 11)
        assertEquals(mapOf("score" to "10"), service.requests.last().form())
        tracker.clearRating(target)
        assertEquals(mapOf("score" to "0"), service.requests.last().form())
        assertFailsWith<TrackerException.Unsupported> { tracker.rate(target.copy(anime = AnimeIds(anilist = 30276)), 5) }
        // Not on the list: no score.
        assertNull((provider(entry(null)).tracker(FakeCredentials(TrackerService.MYANIMELIST)) as Rating).rating(target))
    }

    @Test
    fun aRejectedTokenRefreshesAndALimitIsRateLimited() = runTest {
        val service = ScriptedService(this) { request ->
            when {
                request.headers["Authorization"] == "Bearer access-1" -> Answer(401)
                request.method == "GET" -> json("""{"id":21,"num_episodes":12}""")
                else -> json("{}")
            }
        }
        val credentials = FakeCredentials(TrackerService.MYANIMELIST)
        provider(service).tracker(credentials).markWatched(episode(1))
        assertEquals(1, credentials.refreshes)
        assertEquals(listOf("GET", "GET", "PUT"), service.requests.map { it.method })

        val limited = ScriptedService(this) { request -> if (request.method == "GET") json("{}") else Answer(429, headers = mapOf("Retry-After" to "5")) }
        val error = assertFailsWith<TrackerException.RateLimited> { provider(limited).tracker(FakeCredentials(TrackerService.MYANIMELIST)).markWatched(episode(1)) }
        assertEquals(5, error.retryAfterSeconds)
    }

    @Test
    fun thePlanToWatchRowPagesByOffset() = runTest {
        val service = ScriptedService(this) { request ->
            assertEquals("/v2/users/@me/animelist", request.path)
            assertEquals("plan_to_watch", request.url.parameters["status"])
            json(
                """{"data":[
                    {"node":{"id":52991,"title":"Sousou no Frieren","main_picture":{"medium":"https://cdn.myanimelist.net/images/anime/1015/138006.jpg","large":"https://cdn.myanimelist.net/images/anime/1015/138006l.jpg"},"media_type":"tv","start_season":{"year":2023,"season":"fall"}},"list_status":{"status":"plan_to_watch"}},
                    {"node":{"id":50594,"title":"Suzume no Tojimari","media_type":"movie"},"list_status":{"status":"plan_to_watch"}}
                  ],"paging":{"next":"https://api.myanimelist.net/v2/users/@me/animelist?offset=100"}}""",
            )
        }
        val catalog = provider(service).catalog(FakeCredentials(TrackerService.MYANIMELIST))
        val page = catalog.page(catalog.catalogs().first())
        assertEquals(listOf("mal:52991" to "series", "mal:50594" to "movie"), page.items.map { it.id to it.type })
        assertEquals("https://cdn.myanimelist.net/images/anime/1015/138006l.jpg", page.items[0].poster)
        assertEquals("2023", page.items[0].releaseInfo)
        assertEquals("100", page.next)
    }

    @Test
    fun planToWatchIsSetAndOnlyAStillPlannedEntryIsDeleted() = runTest {
        var status = "plan_to_watch"
        val service = ScriptedService(this) { request ->
            when (request.method) {
                "GET" -> json("""{"id":21,"my_list_status":{"status":"$status","num_episodes_watched":0}}""")
                else -> json("{}")
            }
        }
        val watchlist = provider(service).catalog(FakeCredentials(TrackerService.MYANIMELIST)) as TrackerWatchlist
        val target = WatchlistTarget(isMovie = false, anime = AnimeIds(mal = 21))
        watchlist.addToWatchlist(target)
        assertEquals(mapOf("status" to "plan_to_watch"), service.requests.single().form())

        watchlist.removeFromWatchlist(target)
        assertEquals("DELETE", service.requests.last().method)
        assertEquals("/v2/anime/21/my_list_status", service.requests.last().path)

        // Watching since: the entry is off the plan-to-watch list already and keeps its progress.
        status = "watching"
        watchlist.removeFromWatchlist(target)
        assertEquals("GET", service.requests.last().method)
    }
}
