package com.edde746.tortuga.trackers.anilist

import com.edde746.tortuga.trackers.AnimeIds
import com.edde746.tortuga.trackers.ApiFailureCategory
import com.edde746.tortuga.trackers.Rating
import com.edde746.tortuga.trackers.RatingKind
import com.edde746.tortuga.trackers.RatingTarget
import com.edde746.tortuga.trackers.RelayResult
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
import com.edde746.tortuga.trackers.client.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * AniList against its documented answers (docs.anilist.co):
 * - errors in a 200 body: https://docs.anilist.co/guide/graphql/errors
 * - rate limits (429, `Retry-After`): https://docs.anilist.co/guide/rate-limiting
 * - authenticated requests and token lifetime: https://docs.anilist.co/guide/auth/authenticated-requests
 * - `SaveMediaListEntry`, `MediaListCollection`, `Page`, score formats: https://docs.anilist.co/guide/graphql/mutations,
 *   https://docs.anilist.co/guide/graphql/queries/media-list, https://docs.anilist.co/reference/enum/scoreformat
 */
class AnilistTest {
    private val now = 1_790_000_000L

    private fun provider(service: ScriptedService, clock: Long = now) = AnilistProvider(service.http(TrackerService.ANILIST)) { clock }

    private val episode = TrackerContext(
        metaId = "anilist:21",
        videoId = "anilist:21:5",
        kind = TrackerMediaKind.EPISODE,
        contentType = TrackerContentType.ANIME,
        anime = AnimeIds(anilist = 21),
        animeEpisode = 5,
    )

    private val Recorded.query: String get() = field("query").orEmpty()

    private val Recorded.variables: JsonObject get() = checkNotNull(json()["variables"]).jsonObject

    @Test
    fun aRelaySessionLastsAYearAndLooksUpTheViewer() = runTest {
        val service = ScriptedService(this) { json("""{"data":{"Viewer":{"name":"Josh"}}}""") }
        val session = provider(service).signIn.session(RelayResult("access-1", refreshToken = null, expiresInSeconds = null))
        assertEquals(TrackerSession("access-1", null, now + 365L * 24 * 60 * 60, "Josh", now), session)
        assertEquals("Bearer access-1", service.requests.single().headers["Authorization"])
        assertNull(provider(service).refresher)
    }

    @Test
    fun aRefusedTokenSignsTheAccountOutAndErrorsMapToTheirKinds() = runTest {
        val credentials = FakeCredentials(TrackerService.ANILIST)
        val refused = ScriptedService(this) { json("""{"data":null,"errors":[{"message":"Invalid token","status":401}]}""", 401) }
        val auth = assertFailsWith<TrackerException.Auth> { provider(refused).tracker(credentials).markWatched(episode) }
        assertTrue(auth.permanent)
        assertTrue(credentials.invalidated)
        assertEquals(0, credentials.refreshes)

        val limited = ScriptedService(this) {
            Answer(429, """{"data":null,"errors":[{"message":"Too Many Requests.","status":429}]}""", mapOf("Retry-After" to "30", "X-RateLimit-Remaining" to "0"))
        }
        val error = assertFailsWith<TrackerException.RateLimited> {
            (provider(limited).tracker(FakeCredentials(TrackerService.ANILIST)) as Rating).rating(RatingTarget(RatingKind.SHOW, anime = AnimeIds(anilist = 21)))
        }
        assertEquals(30, error.retryAfterSeconds)

        // A 200 may still carry errors.
        val invalid = ScriptedService(this) {
            json("""{"data":null,"errors":[{"message":"validation","status":400,"locations":[{"line":2,"column":3}],"validation":{"score":["The score may not be greater than 100."]}}]}""")
        }
        val api = assertFailsWith<TrackerException.Api> {
            (provider(invalid).tracker(FakeCredentials(TrackerService.ANILIST)) as Rating).rate(RatingTarget(RatingKind.SHOW, anime = AnimeIds(anilist = 21)), 8)
        }
        assertEquals(ApiFailureCategory.GRAPHQL_ERRORS, api.category)
        assertEquals(false, api.transient)
    }

    @Test
    fun progressKeepsARepeatingEntryAndCountsACompletedRewatch() = runTest {
        fun service(entry: String) = ScriptedService(this) { request ->
            if ("SaveMediaListEntry" in request.query) json("""{"data":{"SaveMediaListEntry":{"id":1}}}""") else json("""{"data":{"Media":{"episodes":12,"mediaListEntry":$entry}}}""")
        }

        val repeating = service("""{"status":"REPEATING","repeat":1,"progress":4}""")
        provider(repeating).tracker(FakeCredentials(TrackerService.ANILIST)).markWatched(episode)
        assertEquals(parse("""{"mediaId":21,"progress":5,"status":"REPEATING"}"""), repeating.requests.last().variables)

        val finishing = service("""{"status":"REPEATING","repeat":1,"progress":11}""")
        provider(finishing).tracker(FakeCredentials(TrackerService.ANILIST)).markWatched(episode.copy(animeEpisode = 12))
        assertEquals(parse("""{"mediaId":21,"progress":12,"status":"COMPLETED","repeat":2}"""), finishing.requests.last().variables)
        assertTrue("\$repeat: Int" in finishing.requests.last().query)

        val fresh = service("null")
        provider(fresh).tracker(FakeCredentials(TrackerService.ANILIST)).markWatched(episode)
        assertEquals(parse("""{"mediaId":21,"progress":5,"status":"CURRENT"}"""), fresh.requests.last().variables)
        assertTrue("repeat" !in fresh.requests.last().query)
    }

    @Test
    fun anEarlierEpisodeNeverTakesProgressBackwards() = runTest {
        fun service(entry: String) = ScriptedService(this) { request ->
            if ("SaveMediaListEntry" in request.query) json("""{"data":{"SaveMediaListEntry":{"id":1}}}""") else json("""{"data":{"Media":{"episodes":12,"mediaListEntry":$entry}}}""")
        }
        // In progress or repeating at 8: episode 5 replayed or marked leaves the entry as it is.
        for (status in listOf("CURRENT", "REPEATING")) {
            val ahead = service("""{"status":"$status","repeat":1,"progress":8}""")
            provider(ahead).tracker(FakeCredentials(TrackerService.ANILIST)).markWatched(episode)
            assertTrue(ahead.requests.none { "SaveMediaListEntry" in it.query }, status)
        }
        // A completed entry is the one that goes back: episode 5 on it starts a rewatch.
        val completed = service("""{"status":"COMPLETED","repeat":1,"progress":12}""")
        provider(completed).tracker(FakeCredentials(TrackerService.ANILIST)).markWatched(episode)
        assertEquals(parse("""{"mediaId":21,"progress":5,"status":"REPEATING"}"""), completed.requests.last().variables)
    }

    @Test
    fun aWriteWhoseEntryCanNotBeReadFailsWithTheReadAndAnIdAniListDoesNotKnowIsNoEntry() = runTest {
        // Written blind, the unwatch would be lost and the rewatch of a completed entry written as CURRENT: the read's
        // failure goes to the caller, which queues the write.
        val failing = ScriptedService(this) { request ->
            if ("SaveMediaListEntry" in request.query) json("""{"data":{"SaveMediaListEntry":{"id":1}}}""") else Answer(503)
        }
        val tracker = provider(failing).tracker(FakeCredentials(TrackerService.ANILIST))
        assertEquals(503, assertFailsWith<TrackerException.Api> { tracker.markWatched(episode) }.status)
        assertEquals(503, assertFailsWith<TrackerException.Api> { tracker.markUnwatched(episode) }.status)
        assertTrue(failing.requests.none { "SaveMediaListEntry" in it.query })

        // An id AniList doesn't know answers 404 "Not Found.": there is no entry to take back.
        val unknown = ScriptedService(this) { json("""{"data":{"Media":null},"errors":[{"message":"Not Found.","status":404}]}""", 404) }
        provider(unknown).tracker(FakeCredentials(TrackerService.ANILIST)).markUnwatched(episode)
        assertEquals(1, unknown.requests.size)
    }

    @Test
    fun scoresAreSentAndReadAsPoint100() = runTest {
        var scoreRaw = 85
        val service = ScriptedService(this) { request ->
            if ("SaveMediaListEntry" in request.query) {
                json("""{"data":{"SaveMediaListEntry":{"id":1}}}""")
            } else {
                json("""{"data":{"Media":{"mediaListEntry":{"scoreRaw":$scoreRaw}}}}""")
            }
        }
        val tracker = provider(service).tracker(FakeCredentials(TrackerService.ANILIST)) as Rating
        val target = RatingTarget(RatingKind.SHOW, anime = AnimeIds(anilist = 21))
        assertEquals(9, tracker.rating(target))
        scoreRaw = 4
        assertEquals(1, tracker.rating(target))
        scoreRaw = 0
        assertNull(tracker.rating(target))

        tracker.rate(target, 7)
        assertEquals(parse("""{"mediaId":21,"scoreRaw":70}"""), service.requests.last().variables)
        tracker.clearRating(target)
        assertEquals(parse("""{"mediaId":21,"scoreRaw":0}"""), service.requests.last().variables)
        assertFailsWith<TrackerException.Unsupported> { tracker.rate(target.copy(kind = RatingKind.EPISODE), 7) }
    }

    @Test
    fun thePlanningRowSkipsCustomListsAndDuplicates() = runTest {
        val service = ScriptedService(this) { request ->
            if ("Viewer" in request.query) {
                json("""{"data":{"Viewer":{"id":5}}}""")
            } else {
                assertEquals(5, request.variables["userId"].toString().toInt())
                json(
                    """{"data":{"MediaListCollection":{"hasNextChunk":true,"lists":[
                      {"isCustomList":false,"entries":[
                        {"media":{"id":154587,"format":"TV","seasonYear":2023,"title":{"userPreferred":"Sousou no Frieren","english":"Frieren"},"coverImage":{"extraLarge":"https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx154587.jpg"}}},
                        {"media":{"id":142770,"format":"MOVIE","startDate":{"year":2022},"title":{"romaji":"Suzume no Tojimari"}}}
                      ]},
                      {"isCustomList":true,"entries":[{"media":{"id":1,"format":"TV","title":{"romaji":"Custom"}}}]},
                      {"isCustomList":false,"entries":[{"media":{"id":154587,"format":"TV","title":{"romaji":"Again"}}}]}
                    ]}}}""",
                )
            }
        }
        val catalog = provider(service).catalog(FakeCredentials(TrackerService.ANILIST))
        val page = catalog.page(catalog.catalogs().first())
        assertEquals(listOf("anilist:154587" to "series", "anilist:142770" to "movie"), page.items.map { it.id to it.type })
        assertEquals("Sousou no Frieren", page.items[0].name)
        assertEquals("2022", page.items[1].releaseInfo)
        assertEquals("2", page.next)
    }

    @Test
    fun theAiringRowAsksForThisSeason() {
        fun at(date: String) = AnilistCatalog.currentSeason(Instant.parse(date).epochSeconds)
        assertEquals("WINTER" to 2027, at("2026-12-15T00:00:00Z"))
        assertEquals("WINTER" to 2026, at("2026-02-01T00:00:00Z"))
        assertEquals("SPRING" to 2026, at("2026-03-01T00:00:00Z"))
        assertEquals("SUMMER" to 2026, at("2026-08-31T23:59:59Z"))
        assertEquals("FALL" to 2026, at("2026-09-29T12:00:00Z"))
    }

    @Test
    fun planningIsSetWithoutProgressAndOnlyAPlanningEntryIsDeleted() = runTest {
        var status = "PLANNING"
        val service = ScriptedService(this) { request ->
            when {
                "DeleteMediaListEntry" in request.query -> json("""{"data":{"DeleteMediaListEntry":{"deleted":true}}}""")
                "SaveMediaListEntry" in request.query -> json("""{"data":{"SaveMediaListEntry":{"id":9}}}""")
                else -> json("""{"data":{"Media":{"mediaListEntry":{"id":9,"status":"$status"}}}}""")
            }
        }
        val watchlist = provider(service).catalog(FakeCredentials(TrackerService.ANILIST)) as TrackerWatchlist
        val target = WatchlistTarget(isMovie = false, anime = AnimeIds(anilist = 21))
        watchlist.addToWatchlist(target)
        assertTrue("status: PLANNING" in service.requests.single().query && "progress" !in service.requests.single().query)

        watchlist.removeFromWatchlist(target)
        assertEquals(parse("""{"id":9}"""), service.requests.last().variables)

        status = "CURRENT"
        watchlist.removeFromWatchlist(target)
        assertTrue("DeleteMediaListEntry" !in service.requests.last().query)
    }
}
