package com.edde746.tortuga.server

import com.edde746.tortuga.server.crypto.RecordCipher
import com.edde746.tortuga.server.crypto.RecordCipher.Field
import com.edde746.tortuga.server.http.MAX_TOKEN_ANSWER_BYTES
import com.edde746.tortuga.server.support.ServerTestContext
import com.edde746.tortuga.server.support.TestPostgres
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.captureLogs
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.server.support.testDataKey
import com.edde746.tortuga.sync.api.AccountDeletionResponse
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.PullResponse
import com.edde746.tortuga.sync.api.PushRequest
import com.edde746.tortuga.sync.api.PushResponse
import com.edde746.tortuga.sync.api.RecordWrite
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.trackers.connections.TrackerAccessToken
import com.edde746.tortuga.trackers.connections.TrackerConnectRequest
import com.edde746.tortuga.trackers.connections.TrackerConnection
import com.edde746.tortuga.trackers.connections.TrackerConnectionList
import com.edde746.tortuga.trackers.connections.TrackerConnectionPaths
import com.edde746.tortuga.trackers.connections.TrackerConnectionsApi
import com.edde746.tortuga.trackers.connections.TrackerTokenRequest
import io.ktor.client.request.delete
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ExternalServicesBuilder
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tracker connections held by the account (protocol: `TrackerConnectionsApi` in shared). The server refreshes and
 * revokes through the apps' own providers, which call the services' hosts; fakes stand in for them, answering as the
 * documentation describes:
 * - Trakt: docs.trakt.tv/reference/postoauthtoken (a JSON body with `refresh_token`, `client_id` and `grant_type`; the
 *   answer is a new access and refresh token with `expires_in` and `created_at`; a refresh token works once) and
 *   docs.trakt.tv/reference/postoauthrevoke (`token`, `client_id`).
 * - Simkl: api.simkl.org/api-reference/oauth2-tokens (a form), here refusing every refresh as for a revoked grant.
 * AniList's tokens don't refresh and it has no revocation endpoint, so it needs no fake.
 */
class TrackerConnectionsTest {
    @Test
    fun connectionsListWithoutTokensAndAReconnectMovesTheirTime() = trackerTest { trackers ->
        val alice = signIn("alice").session.accessToken
        val first = connect(alice, traktSignIn())
        assertEquals(TrackerConnection(PROFILE, "trakt", "alice-trakt", clock.now.toEpochMilli()), first)

        // A reconnect gets a time of its own, also within the same millisecond or when the clock went back.
        val second = connect(alice, traktSignIn(access = "trakt-access-second", refresh = "trakt-refresh-second"))
        assertEquals(first.connectedAtMillis + 1, second.connectedAtMillis)
        clock.now = clock.now.minusSeconds(60)
        val third = connect(alice, traktSignIn(access = "trakt-access-third", refresh = "trakt-refresh-third"))
        assertEquals(first.connectedAtMillis + 2, third.connectedAtMillis)
        clock.advance(Duration.ofMinutes(5))
        val aniList = connect(alice, aniListSignIn())

        // Every device lists every profile's connections, oldest first, with no token or client id.
        val listed = get(TrackerConnectionPaths.CONNECTIONS, alice)
        assertEquals(listOf(third, aniList), listed.ok<TrackerConnectionList>().connections)
        val json = listed.bodyAsText()
        for (secret in listOf("trakt-access", "trakt-refresh", "trakt-client", "anilist-access")) {
            assertFalse(secret in json, "\"$secret\" in $json")
        }

        // The replaced sign-ins' times name nothing any more; their tokens weren't revoked.
        askToken(alice, first).assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)
        askToken(alice, second).assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)
        assertEquals("trakt-access-third", askToken(alice, third).ok<TrackerAccessToken>().accessToken)
        assertTrue(trackers.revocations.isEmpty())
    }

    @Test
    fun aTokenThatLastsIsHandedOutWithoutRefresh() = trackerTest { trackers ->
        val alice = signIn("alice").session.accessToken
        val trakt = connect(alice, traktSignIn(expiresIn = 3600))
        val response = askToken(alice, trakt)
        assertEquals("no-store, private", response.headers[HttpHeaders.CacheControl])
        assertEquals(TrackerAccessToken("trakt-access-0", 3600).fields(), response.ok<TrackerAccessToken>().fields())

        // Its time left counts on the server's clock, down to one second outside the refresh window.
        clock.advance(Duration.ofSeconds(3600 - TrackerConnectionsApi.REFRESH_WINDOW_SECONDS - 1))
        val later = askToken(alice, trakt).ok<TrackerAccessToken>()
        assertEquals(TrackerAccessToken("trakt-access-0", TrackerConnectionsApi.REFRESH_WINDOW_SECONDS + 1).fields(), later.fields())

        // A token without an expiry has no time left to tell.
        val aniList = connect(alice, aniListSignIn(expiresIn = null))
        assertEquals(TrackerAccessToken("anilist-access-0", null).fields(), askToken(alice, aniList).ok<TrackerAccessToken>().fields())
        assertTrue(trackers.refreshes.isEmpty())
    }

    @Test
    fun aTokenNearItsExpiryIsRefreshedAndTheRotatedRefreshTokenUsedNext() = trackerTest { trackers ->
        val alice = signIn("alice").session.accessToken
        val trakt = connect(alice, traktSignIn(expiresIn = 3600))
        clock.advance(Duration.ofSeconds(3600 - TrackerConnectionsApi.REFRESH_WINDOW_SECONDS))
        val first = askToken(alice, trakt).ok<TrackerAccessToken>()
        assertEquals(TrackerAccessToken("trakt-access-1", TRAKT_EXPIRES_IN).fields(), first.fields())
        assertEquals(
            mapOf("refresh_token" to "trakt-refresh-0", "client_id" to "trakt-client", "grant_type" to "refresh_token"),
            trackers.refreshes.single().fields,
        )

        // The next refresh, a week later (on a device whose session is current), spends the refresh token the first
        // one brought.
        clock.advance(Duration.ofSeconds(TRAKT_EXPIRES_IN - TrackerConnectionsApi.REFRESH_WINDOW_SECONDS))
        val tv = signIn("alice", deviceName = "TV").session.accessToken
        assertEquals("trakt-access-2", askToken(tv, trakt).ok<TrackerAccessToken>().accessToken)
        assertEquals(listOf("trakt-refresh-0", "trakt-refresh-1"), trackers.refreshes.map { it.fields["refresh_token"] })
        // Still the same sign-in, with its username.
        assertEquals(listOf(trakt), list(tv))
    }

    @Test
    fun concurrentRequestsShareOneRefresh() = trackerTest { trackers ->
        val phone = signIn("alice").session.accessToken
        val tv = signIn("alice", deviceName = "TV").session.accessToken
        val trakt = connect(phone, traktSignIn(expiresIn = 60))
        // Trakt holds its answer while more requests than the pool has connections find the refresh due.
        val answer = trackers.holdRefreshes()
        val asking = List(TestPostgres.POOL_SIZE + 2) { i -> if (i % 2 == 0) phone else tv }
        val tokens = coroutineScope {
            val tokens = asking.map { device -> async { askToken(device, trakt).ok<TrackerAccessToken>().accessToken } }
            trackers.awaitRefreshes(1)
            delay(500.milliseconds)
            answer.complete(Unit)
            tokens.awaitAll()
        }
        assertEquals(List(asking.size) { "trakt-access-1" }, tokens)
        assertEquals(1, trackers.refreshes.size)
    }

    @Test
    fun aSlowTrackerHoldsUpNothingElse() = trackerTest { trackers ->
        val alice = signIn("alice").session.accessToken
        // More connections due for a refresh than the pool has database connections.
        val connections = List(TestPostgres.POOL_SIZE + 2) { i ->
            connect(alice, traktSignIn(profileId = "profile-$i", access = "trakt-access-a$i", refresh = "trakt-refresh-a$i", expiresIn = 60))
        }
        val answer = trackers.holdRefreshes()
        val tokens = coroutineScope {
            val tokens = connections.map { async { askToken(alice, it).ok<TrackerAccessToken>().accessToken } }
            withTimeout(10.seconds) {
                // Every refresh is at Trakt at once, and while Trakt keeps them all waiting the server answers the rest.
                trackers.awaitRefreshes(connections.size)
                get("${SyncPaths.SYNC_PULL}?${SyncPaths.PULL_SINCE}=0", alice).ok<PullResponse>()
                assertEquals(connections.size, list(alice).size)
                assertEquals("ok", get("/healthz").bodyAsText())
            }
            answer.complete(Unit)
            tokens.awaitAll()
        }
        assertEquals(connections.size, tokens.toSet().size)
        assertEquals(connections.size, trackers.refreshes.size)
    }

    @Test
    fun aRefreshThatOutlivesItsConnectionRevokesWhatItGot() = trackerTest { trackers ->
        val alice = signIn("alice").session.accessToken
        val trakt = connect(alice, traktSignIn(expiresIn = 60))
        val answer = trackers.holdRefreshes()
        coroutineScope {
            val asking = async { askToken(alice, trakt) }
            trackers.awaitRefreshes(1)
            // The account goes while Trakt hasn't answered: its deletion revokes the token stored then.
            delete(SyncPaths.ACCOUNT, alice).ok<AccountDeletionResponse>()
            answer.complete(Unit)
            asking.await().assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)
        }
        // The refresh found the row gone and revoked the token Trakt gave it.
        assertEquals(listOf("trakt-access-0", "trakt-access-1"), trackers.revocations.map { it.fields["token"] })
        assertEquals(0, TestPostgres.count("tracker_connections"))
    }

    @Test
    fun theConnectionsCounterMovesWhenTheListingChanges() = trackerTest {
        val alice = signIn("alice").session.accessToken
        suspend fun counters(): Pair<Long?, Long?> =
            get("${SyncPaths.SYNC_PULL}?${SyncPaths.PULL_SINCE}=0", alice).ok<PullResponse>().trackersVersion to
                get(TrackerConnectionPaths.CONNECTIONS, alice).ok<TrackerConnectionList>().version
        assertEquals(0L to 0L, counters())

        val trakt = connect(alice, traktSignIn())
        assertEquals(1L to 1L, counters())
        // A refresh changes nothing listed.
        askToken(alice, trakt, rejected = "trakt-access-0").ok<TrackerAccessToken>()
        assertEquals(1L to 1L, counters())

        connect(alice, traktSignIn(access = "trakt-access-again", refresh = "trakt-refresh-again"))
        assertEquals(2L to 2L, counters())
        val simkl = connect(alice, simklSignIn())
        assertEquals(3L to 3L, counters())
        // Simkl refuses the refresh, so the connection goes.
        askToken(alice, simkl, rejected = "simkl-access-0").assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)
        assertEquals(4L to 4L, counters())
        connect(alice, simklSignIn())
        assertEquals(HttpStatusCode.NoContent, delete(TrackerConnectionPaths.connection(PROFILE, "simkl"), alice).status)
        assertEquals(6L to 6L, counters())
        // Disconnecting what isn't connected changes nothing.
        assertEquals(HttpStatusCode.NoContent, delete(TrackerConnectionPaths.connection(PROFILE, "simkl"), alice).status)
        assertEquals(6L to 6L, counters())

        // The profile's tombstone deletes its connection.
        push(alice, RecordWrite(profileRecord(PROFILE), 0, payload = payload)).ok<PushResponse>()
        assertEquals(6L to 6L, counters())
        push(alice, RecordWrite(profileRecord(PROFILE), 1, deleted = true, payload = payload)).ok<PushResponse>()
        assertEquals(7L to 7L, counters())
        assertEquals(emptyList(), list(alice))
    }

    @Test
    fun aRejectedTokenIsRefreshedOnceForEveryDeviceAskingWithIt() = trackerTest { trackers ->
        val phone = signIn("alice").session.accessToken
        val tv = signIn("alice", deviceName = "TV").session.accessToken
        val trakt = connect(phone, traktSignIn())
        // Trakt answered the phone 401 to a token that hasn't expired yet.
        assertEquals("trakt-access-1", askToken(phone, trakt, rejected = "trakt-access-0").ok<TrackerAccessToken>().accessToken)
        // The TV got its 401 for the same token: the current one, without another refresh.
        assertEquals("trakt-access-1", askToken(tv, trakt, rejected = "trakt-access-0").ok<TrackerAccessToken>().accessToken)
        assertEquals(1, trackers.refreshes.size)
    }

    @Test
    fun aRefusedRefreshDeletesTheConnection() = trackerTest { trackers ->
        val alice = signIn("alice").session.accessToken
        val trakt = connect(alice, traktSignIn())
        val simkl = connect(alice, simklSignIn())
        trackers.refreshStatus = HttpStatusCode.Unauthorized
        askToken(alice, trakt, rejected = "trakt-access-0").assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)
        askToken(alice, simkl, rejected = "simkl-access-0").assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)
        assertEquals(listOf("trakt", "simkl"), trackers.refreshes.map { it.service })

        // Gone for every device; there is nothing left to revoke.
        assertEquals(emptyList(), list(alice))
        askToken(alice, trakt).assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)
        assertTrue(trackers.revocations.isEmpty())
    }

    @Test
    fun aFailingTokenEndpointKeepsTheConnection() = trackerTest { trackers ->
        val alice = signIn("alice").session.accessToken
        val trakt = connect(alice, traktSignIn(expiresIn = 60))
        trackers.refreshStatus = HttpStatusCode.ServiceUnavailable
        askToken(alice, trakt).assertError(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE)
        assertEquals(listOf(trakt), list(alice))

        // Once Trakt is back, the kept refresh token works.
        trackers.refreshStatus = HttpStatusCode.OK
        assertEquals("trakt-access-1", askToken(alice, trakt).ok<TrackerAccessToken>().accessToken)
        assertEquals(listOf("trakt-refresh-0", "trakt-refresh-0"), trackers.refreshes.map { it.fields["refresh_token"] })
    }

    @Test
    fun aTokenAnswerPastTheCapFailsAsAFailingTokenEndpoint() = trackerTest { trackers ->
        val alice = signIn("alice").session.accessToken
        val trakt = connect(alice, traktSignIn(expiresIn = 60))
        trackers.refreshAnswerBytes = MAX_TOKEN_ANSWER_BYTES.toInt()
        assertEquals("trakt-access-1", askToken(alice, trakt).ok<TrackerAccessToken>().accessToken)

        // One byte more isn't read: the connection stays, as when the token endpoint fails.
        trackers.refreshAnswerBytes = MAX_TOKEN_ANSWER_BYTES.toInt() + 1
        askToken(alice, trakt, rejected = "trakt-access-1").assertError(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE)
        assertEquals(listOf(trakt), list(alice))
        assertEquals(2, trackers.refreshes.size)
    }

    @Test
    fun aRejectedAniListTokenEndsTheConnection() = trackerTest { trackers ->
        val alice = signIn("alice").session.accessToken
        val aniList = connect(alice, aniListSignIn())
        // A device rejecting an older token gets the stored one.
        assertEquals("anilist-access-0", askToken(alice, aniList, rejected = "anilist-access-older").ok<TrackerAccessToken>().accessToken)
        // The stored one rejected: AniList's tokens don't refresh, so the sign-in is over.
        askToken(alice, aniList, rejected = "anilist-access-0").assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)
        assertEquals(emptyList(), list(alice))
        assertTrue(trackers.refreshes.isEmpty())
    }

    @Test
    fun disconnectRevokesWithTheStoredTokenAndClientId() = trackerTest { trackers ->
        val alice = signIn("alice").session.accessToken
        val trakt = connect(alice, traktSignIn(clientId = "trakt-client-b"))
        // By now the stored token is a refreshed one, refreshed as the same client.
        askToken(alice, trakt, rejected = "trakt-access-0").ok<TrackerAccessToken>()
        assertEquals("trakt-client-b", trackers.refreshes.single().fields["client_id"])

        val path = TrackerConnectionPaths.connection(PROFILE, "trakt")
        assertEquals(HttpStatusCode.NoContent, delete(path, alice).status)
        assertEquals(mapOf("token" to "trakt-access-1", "client_id" to "trakt-client-b"), trackers.revocations.single().fields)
        assertEquals(emptyList(), list(alice))
        askToken(alice, trakt).assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)

        // Again, a service never connected, or one this server doesn't know: 204, nothing to revoke.
        assertEquals(HttpStatusCode.NoContent, delete(path, alice).status)
        assertEquals(HttpStatusCode.NoContent, delete(TrackerConnectionPaths.connection(PROFILE, "simkl"), alice).status)
        assertEquals(HttpStatusCode.NoContent, delete(TrackerConnectionPaths.connection(PROFILE, "letterboxd"), alice).status)
        assertEquals(1, trackers.revocations.size)
    }

    @Test
    fun deletingTheAccountRevokesItsConnectionsAndLeavesNoRows() = trackerTest { trackers ->
        val alice = signIn("alice").session.accessToken
        connect(alice, traktSignIn(profileId = "profile-1", access = "trakt-access-a", refresh = "trakt-refresh-a"))
        connect(alice, traktSignIn(profileId = "profile-2", access = "trakt-access-b", refresh = "trakt-refresh-b"))
        connect(alice, aniListSignIn())
        val bob = signIn("bob").session.accessToken
        val bobs = connect(bob, traktSignIn(access = "trakt-access-bob", refresh = "trakt-refresh-bob"))

        delete(SyncPaths.ACCOUNT, alice).ok<AccountDeletionResponse>()
        assertEquals(setOf("trakt-access-a", "trakt-access-b"), trackers.revocations.map { it.fields["token"] }.toSet())
        assertEquals(2, trackers.revocations.size)
        // Only Bob's connection is left.
        assertEquals(1, TestPostgres.count("tracker_connections"))
        assertEquals(listOf(bobs), list(bob))
    }

    @Test
    fun anotherAccountsOrProfilesConnectionIsOutOfReach() = trackerTest { trackers ->
        val alice = signIn("alice").session.accessToken
        val bob = signIn("bob").session.accessToken
        val trakt = connect(alice, traktSignIn())

        assertEquals(emptyList(), list(bob))
        askToken(bob, trakt).assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)
        askToken(bob, trakt, rejected = "trakt-access-0").assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)
        assertEquals(HttpStatusCode.NoContent, delete(TrackerConnectionPaths.connection(PROFILE, "trakt"), bob).status)
        assertEquals(listOf(trakt), list(alice))

        // Another profile of the account, asking with this sign-in's time, has no connection.
        askToken(alice, trakt.copy(profileId = "profile-2")).assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)

        // Bob's own connection under the same profile id is his alone.
        val bobs = connect(bob, traktSignIn(access = "trakt-access-bob", refresh = "trakt-refresh-bob"))
        assertEquals("trakt-access-0", askToken(alice, trakt).ok<TrackerAccessToken>().accessToken)
        assertEquals("trakt-access-bob", askToken(bob, bobs).ok<TrackerAccessToken>().accessToken)
        assertTrue(trackers.refreshes.isEmpty())
        assertTrue(trackers.revocations.isEmpty())
    }

    @Test
    fun aDeletedProfileTakesItsConnectionsWithIt() = trackerTest { trackers ->
        val alice = signIn("alice").session.accessToken
        // A profile id with the characters record ids percent-encode.
        connect(alice, traktSignIn(profileId = "kids/50%", access = "trakt-access-kids", refresh = "trakt-refresh-kids"))
        val other = connect(alice, traktSignIn(access = "trakt-access-other", refresh = "trakt-refresh-other"))
        val kids = profileRecord("kids/50%")
        push(alice, RecordWrite(kids, 0, payload = payload)).ok<PushResponse>()

        // The other profile's record, a tombstone of another kind of record, and a stale tombstone change nothing.
        push(
            alice,
            RecordWrite(profileRecord(PROFILE), 0, payload = payload),
            RecordWrite(SyncProtocol.recordId(listOf("library", "kids/50%", "tt0111161")), 0, deleted = true, payload = payload),
        ).ok<PushResponse>()
        assertEquals(1, push(alice, RecordWrite(kids, 0, deleted = true, payload = payload)).ok<PushResponse>().stale.size)
        assertEquals(2, list(alice).size)
        assertTrue(trackers.revocations.isEmpty())

        // The profile's tombstone deletes its connections and revokes them.
        push(alice, RecordWrite(kids, 1, deleted = true, payload = payload)).ok<PushResponse>()
        assertEquals(listOf(other), list(alice))
        assertEquals(listOf("trakt-access-kids"), trackers.revocations.map { it.fields["token"] })
    }

    @Test
    fun tokensUsernamesAndClientIdsAreSealedAtRest() = trackerTest {
        val alice = signIn("alice").session.accessToken
        val trakt = connect(
            alice,
            traktSignIn(access = "trakt-access-9f8e7d", refresh = "trakt-refresh-6c5b4a", clientId = "trakt-client-3e2d1c", username = "wednesday-addams"),
        )
        // The rotated tokens a refresh brings are sealed as well.
        askToken(alice, trakt, rejected = "trakt-access-9f8e7d").ok<TrackerAccessToken>()
        connect(alice, aniListSignIn())

        val dump = TestPostgres.dump()
        val rows = dump.getValue("tracker_connections")
        assertEquals(2, rows.lines().count { it.isNotEmpty() })
        // Profile ids and service names are stored as they are.
        assertTrue(PROFILE in rows && "trakt" in rows && "anilist" in rows, rows)
        val all = dump.values.joinToString("\n")
        for (needle in listOf("trakt-access", "trakt-refresh", "trakt-client-3e2d1c", "wednesday-addams", "anilist-access", "alice-anilist")) {
            assertFalse(needle in all, "\"$needle\" in the database dump")
            assertFalse(needle.encodeToByteArray().toHex() in all, "\"$needle\" as bytea in the database dump")
        }
    }

    @Test
    fun aSealedSessionOpensOnlyInItsOwnRow() = trackerTest {
        val alice = signIn("alice").session.accessToken
        connect(alice, traktSignIn(profileId = "profile-1"))
        connect(alice, traktSignIn(profileId = "profile-2"))
        // Someone with write access to the database swaps the two sessions.
        TestPostgres.database.transaction {
            update(
                """
                UPDATE tracker_connections t SET sealed_session = o.sealed_session FROM tracker_connections o
                WHERE t.account_id = o.account_id AND t.profile_id <> o.profile_id
                """,
            )
        }
        // Neither opens in the other's row: the list leaves both out.
        assertEquals(emptyList(), list(alice))
    }

    @Test
    fun rejectsInvalidSignInsAndRequestsWithoutASession() = trackerTest {
        val alice = signIn("alice").session.accessToken
        val invalid = listOf(
            traktSignIn(service = "letterboxd"),
            traktSignIn(profileId = ""),
            traktSignIn(profileId = "p".repeat(TrackerConnectionsApi.MAX_PROFILE_ID_LENGTH + 1)),
            traktSignIn(access = " "),
            traktSignIn(access = "a".repeat(TrackerConnectionsApi.MAX_TOKEN_LENGTH + 1)),
            traktSignIn(refresh = "r".repeat(TrackerConnectionsApi.MAX_TOKEN_LENGTH + 1)),
            // Trakt's tokens refresh, so a sign-in without a refresh token would die at its first expiry.
            traktSignIn(refresh = null),
            traktSignIn(clientId = "c".repeat(TrackerConnectionsApi.MAX_CLIENT_ID_LENGTH + 1)),
            traktSignIn(username = "u".repeat(TrackerConnectionsApi.MAX_USERNAME_LENGTH + 1)),
        )
        for (request in invalid) {
            post(TrackerConnectionPaths.CONNECTIONS, request, alice).assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)
        }
        // Every field at its limit is fine, and so is AniList without a refresh token.
        connect(
            alice,
            traktSignIn(
                profileId = "p".repeat(TrackerConnectionsApi.MAX_PROFILE_ID_LENGTH),
                access = "a".repeat(TrackerConnectionsApi.MAX_TOKEN_LENGTH),
                refresh = "r".repeat(TrackerConnectionsApi.MAX_TOKEN_LENGTH),
                clientId = "c".repeat(TrackerConnectionsApi.MAX_CLIENT_ID_LENGTH),
                username = "u".repeat(TrackerConnectionsApi.MAX_USERNAME_LENGTH),
            ),
        )
        connect(alice, aniListSignIn())
        assertEquals(2, list(alice).size)
        post(TrackerConnectionPaths.TOKEN, TrackerTokenRequest(PROFILE, "letterboxd", 0), alice)
            .assertError(HttpStatusCode.BadRequest, ApiErrorCode.INVALID_REQUEST)

        // Every route needs a session.
        get(TrackerConnectionPaths.CONNECTIONS).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)
        post(TrackerConnectionPaths.CONNECTIONS, traktSignIn()).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)
        post(TrackerConnectionPaths.TOKEN, TrackerTokenRequest(PROFILE, "anilist", 0)).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)
        client.delete(TrackerConnectionPaths.connection(PROFILE, "anilist")).assertError(HttpStatusCode.Unauthorized, ApiErrorCode.UNAUTHORIZED)
        assertEquals(2, list(alice).size)
    }

    @Test
    fun anAccountHoldsAtMostItsLimitOfConnections() = trackerTest {
        val alice = signIn("alice").session.accessToken
        repeat(TrackerConnectionsApi.MAX_CONNECTIONS) { connect(alice, traktSignIn(profileId = "profile-$it")) }
        assertEquals(TrackerConnectionsApi.MAX_CONNECTIONS, list(alice).size)

        // A new connection past the limit is refused; replacing one still fits.
        post(TrackerConnectionPaths.CONNECTIONS, aniListSignIn(), alice).assertError(HttpStatusCode.PayloadTooLarge, ApiErrorCode.QUOTA_EXCEEDED)
        connect(alice, traktSignIn(profileId = "profile-0", access = "trakt-access-again", refresh = "trakt-refresh-again"))
        assertEquals(TrackerConnectionsApi.MAX_CONNECTIONS, list(alice).size)

        // A disconnect makes room for one.
        assertEquals(HttpStatusCode.NoContent, delete(TrackerConnectionPaths.connection("profile-0", "trakt"), alice).status)
        connect(alice, aniListSignIn())
        assertEquals(TrackerConnectionsApi.MAX_CONNECTIONS, list(alice).size)
    }

    @Test
    fun noTokenUsernameOrClientIdReachesTheLogs() {
        val events = captureLogs {
            trackerTest { trackers ->
                val alice = signIn("alice").session.accessToken
                val trakt = connect(alice, traktSignIn(expiresIn = 60))
                askToken(alice, trakt).ok<TrackerAccessToken>()
                trackers.refreshStatus = HttpStatusCode.ServiceUnavailable
                askToken(alice, trakt, rejected = "trakt-access-1").assertError(HttpStatusCode.BadGateway, ApiErrorCode.UPSTREAM_UNAVAILABLE)
                trackers.refreshStatus = HttpStatusCode.Unauthorized
                askToken(alice, trakt, rejected = "trakt-access-1").assertError(HttpStatusCode.Gone, TrackerConnectionsApi.DISCONNECTED)
                connect(alice, traktSignIn())
                delete(TrackerConnectionPaths.connection(PROFILE, "trakt"), alice)
            }
        }
        for (logged in listOf("Tracker token refreshed", "Tracker refresh failed", "Tracker refresh refused", "Tracker disconnected")) {
            assertTrue(events.any { logged in it }, "\"$logged\" is logged: $events")
        }
        val leaks = events.filter { line -> listOf("trakt-access", "trakt-refresh", "trakt-client", "alice-trakt").any { it in line } }
        assertTrue(leaks.isEmpty(), "sensitive values in logs: $leaks")
    }

    @Test
    fun aStoredSessionTheServerCantDecodeIsItsOwnErrorLoggedWithoutItsTokens() {
        val events = captureLogs {
            trackerTest {
                val alice = signIn("alice")
                val accountId = UUID.fromString(alice.account.accountId)
                // Sealed with the server's key, but not a session the server wrote.
                val session = """{"clientId":"trakt-client","session":"trakt-access-unreadable"}""".encodeToByteArray()
                val sealed = RecordCipher(testDataKey).seal(Field.TRACKER_SESSION, accountId, "$PROFILE/trakt".encodeToByteArray(), session)
                TestPostgres.database.transaction {
                    update(
                        "INSERT INTO tracker_connections (account_id, profile_id, service, sealed_session, connected_at) VALUES (?, ?, ?, ?, ?)",
                        accountId, PROFILE, "trakt", sealed, clock.instant(),
                    )
                }
                get(TrackerConnectionPaths.CONNECTIONS, alice.session.accessToken)
                    .assertError(HttpStatusCode.InternalServerError, ApiErrorCode.INTERNAL)
            }
        }
        assertTrue(events.any { it.startsWith("Unhandled error on GET ${TrackerConnectionPaths.CONNECTIONS}") }, "logged: $events")
        val leaks = events.filter { line -> listOf("trakt-access-unreadable", "trakt-client").any { it in line } }
        assertTrue(leaks.isEmpty(), "sensitive values in logs: $leaks")
    }

    private val payload = JsonObject(mapOf("name" to JsonPrimitive("Kids")))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** [TrackerAccessToken] has no equality of its own (it keeps its token out of `toString`). */
    private fun TrackerAccessToken.fields() = accessToken to expiresInSeconds
}

private const val PROFILE = "profile-1"

/** How long the fake Trakt's access tokens last: a week. */
private const val TRAKT_EXPIRES_IN = 604_800L

private fun traktSignIn(
    profileId: String = PROFILE,
    access: String = "trakt-access-0",
    refresh: String? = "trakt-refresh-0",
    expiresIn: Long? = 86_400,
    clientId: String = "trakt-client",
    username: String? = "alice-trakt",
    service: String = "trakt",
) = TrackerConnectRequest(profileId, service, clientId, access, refresh, expiresIn, username)

/** As the apps sign in to AniList: no client id, no refresh token, a token for a year. */
private fun aniListSignIn(expiresIn: Long? = 31_536_000) =
    TrackerConnectRequest(PROFILE, "anilist", "", "anilist-access-0", null, expiresIn, "alice-anilist")

private fun simklSignIn() = TrackerConnectRequest(PROFILE, "simkl", "simkl-client", "simkl-access-0", "simkl-refresh-0", null, "alice-simkl")

/** A profile's record id, as the apps write it. */
private fun profileRecord(profileId: String) = SyncProtocol.recordId(listOf(SyncProtocol.PROFILE_RECORD, profileId))

/** [serverTest] with the fake tracker services mounted. */
private fun trackerTest(block: suspend ServerTestContext.(FakeTrackers) -> Unit) {
    val trackers = FakeTrackers()
    serverTest(external = { services, clock -> trackers.mount(services, clock) }) { block(trackers) }
}

private suspend fun ServerTestContext.connect(token: String, request: TrackerConnectRequest): TrackerConnection =
    post(TrackerConnectionPaths.CONNECTIONS, request, token).ok()

private suspend fun ServerTestContext.list(token: String): List<TrackerConnection> =
    get(TrackerConnectionPaths.CONNECTIONS, token).ok<TrackerConnectionList>().connections

private suspend fun ServerTestContext.askToken(token: String, connection: TrackerConnection, rejected: String? = null): HttpResponse =
    post(TrackerConnectionPaths.TOKEN, TrackerTokenRequest(connection.profileId, connection.service, connection.connectedAtMillis, rejected), token)

private suspend fun ServerTestContext.push(token: String, vararg writes: RecordWrite): HttpResponse =
    post(SyncPaths.SYNC_PUSH, PushRequest(writes.toList()), token)

/** A request a fake token or revoke endpoint received: the service and the fields of its body. */
private class Received(val service: String, val fields: Map<String, String>)

/** Trakt's token and revoke endpoints, and Simkl's token endpoint refusing every refresh with 400. */
private class FakeTrackers {
    val refreshes = CopyOnWriteArrayList<Received>()
    val revocations = CopyOnWriteArrayList<Received>()

    /** What Trakt's token endpoint answers; 200 is a new pair of tokens. */
    @Volatile var refreshStatus: HttpStatusCode = HttpStatusCode.OK

    /** Trakt's new tokens come padded with spaces to this many bytes; null: unpadded. */
    @Volatile var refreshAnswerBytes: Int? = null

    /** Trakt's token endpoint answers once this completes; null: at once. */
    @Volatile private var held: CompletableDeferred<Unit>? = null

    private val arrived = MutableStateFlow(0)
    private val issued = AtomicInteger()
    private val spent: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Holds every refresh until the returned signal completes. */
    fun holdRefreshes(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { held = it }

    /** Waits until Trakt's token endpoint has received [count] refreshes. */
    suspend fun awaitRefreshes(count: Int) {
        arrived.first { it >= count }
    }

    fun mount(services: ExternalServicesBuilder, clock: Clock) {
        services.hosts("https://api.trakt.tv") {
            routing {
                post("/oauth/token") {
                    val fields = jsonFields(call.receiveText())
                    refreshes += Received("trakt", fields)
                    arrived.update { it + 1 }
                    held?.await()
                    val (status, body) = traktRefresh(fields, clock)
                    call.respondText(body, ContentType.Application.Json, status)
                }
                post("/oauth/revoke") {
                    revocations += Received("trakt", jsonFields(call.receiveText()))
                    call.respondText("", status = HttpStatusCode.OK)
                }
            }
        }
        services.hosts("https://api.simkl.com") {
            routing {
                post("/oauth2/token") {
                    refreshes += Received("simkl", call.receiveParameters().entries().associate { it.key to it.value.single() })
                    call.respondText("""{"error":"invalid_grant"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                }
            }
        }
    }

    /** A new access and refresh token, numbered in order; a refresh token works once, as Trakt's do. */
    private fun traktRefresh(fields: Map<String, String>, clock: Clock): Pair<HttpStatusCode, String> {
        val refused = """{"error":"invalid_grant"}"""
        if (refreshStatus != HttpStatusCode.OK) return refreshStatus to refused
        val presented = fields["refresh_token"]
        if (presented == null || !spent.add(presented)) return HttpStatusCode.Unauthorized to refused
        val n = issued.incrementAndGet()
        val tokens = """{"access_token":"trakt-access-$n","token_type":"bearer","expires_in":$TRAKT_EXPIRES_IN,""" +
            """"refresh_token":"trakt-refresh-$n","scope":"public","created_at":${clock.instant().epochSecond}}"""
        return HttpStatusCode.OK to tokens.padEnd(refreshAnswerBytes ?: 0)
    }

    private fun jsonFields(text: String): Map<String, String> =
        Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
}
