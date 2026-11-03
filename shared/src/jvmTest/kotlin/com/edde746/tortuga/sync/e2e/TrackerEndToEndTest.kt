package com.edde746.tortuga.sync.e2e

import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.TortugaHttpClient
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.sync.account.AccountState
import com.edde746.tortuga.sync.account.ProviderCredential
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.SignInResult
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.engine.SyncEngine
import com.edde746.tortuga.sync.engine.SyncStatus
import com.edde746.tortuga.trackers.ExternalIds
import com.edde746.tortuga.trackers.RealtimeScrobble
import com.edde746.tortuga.trackers.ScrobbleState
import com.edde746.tortuga.trackers.SignInPrompt
import com.edde746.tortuga.trackers.TrackerAccounts
import com.edde746.tortuga.trackers.TrackerConfig
import com.edde746.tortuga.trackers.TrackerContentType
import com.edde746.tortuga.trackers.TrackerContext
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerMediaKind
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.TrackerService.TRAKT
import com.edde746.tortuga.trackers.TrackerTokenStore
import com.edde746.tortuga.trackers.clientId
import com.edde746.tortuga.trackers.devTrackerRedirect
import com.edde746.tortuga.trackers.trackerProviders
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Tracker connections end to end: the shared tracker client against the real account server,
 * with `tools/media/test_trackers.py` as Trakt. `server/scripts/sync-e2e.sh` runs this with the sync test; the
 * server must have been started with `TORTUGA_DEV_TRACKERS_URL` naming the same fake, so its refreshes and revokes
 * reach it too. `jvmTest` skips it.
 *
 * Device A connects Trakt to a profile by device code; device B, signed in to the same account, lists the connection
 * and scrobbles with the access token the server gives it, without signing in to Trakt; the server refreshes once for
 * both devices; B disconnects, the server revokes, and A drops the connection. The database holds none of the tokens
 * or the username in the clear, and deleting the account leaves no connection behind.
 */
class TrackerEndToEndTest {
    private val server = ServerUrl.parse(setting("server"))
    private val dex = setting("dex")
    private val postgres = setting("postgresContainer")
    private val trackersUrl = setting("trackers")
    private val dockerCli = System.getProperty("tortuga.e2e.docker")?.takeIf { it.isNotBlank() } ?: "docker"
    private val http = TortugaHttpClient.create("e2e")
    private val serviceHttp = http.config { install(devTrackerRedirect(trackersUrl)) }
    private val config = TrackerConfig(traktClientId = "tortuga-dev")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private inner class Device(name: String, platform: String) : AutoCloseable {
        val secrets = MemorySecretStore()
        val data = LocalData(DatabaseFactory.inMemory())
        val account = SyncAccount(secrets, data, http, DeviceDescriptor(name, platform))
        val engine = SyncEngine(account, data, scope)
        val trackers = TrackerAccounts(
            repository = data.trackerAccounts,
            tokens = TrackerTokenStore(secrets),
            server = account.sessions,
            api = account.api,
            providers = trackerProviders(serviceHttp, config),
            clientIds = TrackerService.entries.associateWith { config.clientId(it) },
            relay = null,
            clock = Clock.System,
            timeSource = TimeSource.Monotonic,
            onConnected = {},
            relist = {},
        )

        suspend fun sync() {
            val status = engine.syncNow()
            assertEquals(SyncStatus.Kind.Idle, status.kind, "sync status: ${status.text}")
        }

        /** Every secret this device keeps, as text. */
        fun secretText(): String = secrets.entries.values.joinToString("\n") { it.decodeToString() }

        override fun close() = data.close()
    }

    private fun setting(name: String): String = System.getProperty("tortuga.e2e.$name")?.takeIf { it.isNotBlank() }
        ?: error("-Ptortuga.e2e.$name is not set; run server/scripts/sync-e2e.sh")

    private fun step(message: String) = println("e2e: $message")

    /** A fresh Dex ID token for the desktop user (password grant), apart from the sync test's alice. */
    private suspend fun dexCredential(): ProviderCredential {
        val response = http.submitForm(
            "$dex/token",
            parameters {
                append("grant_type", "password")
                append("client_id", "tortuga-dev")
                append("username", "desktop@example.com")
                append("password", "password")
                append("scope", "openid email profile")
            },
        )
        check(response.status.isSuccess()) { "Dex token request failed: ${response.status}" }
        val token = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id_token").jsonPrimitive.content
        return ProviderCredential(IdentityProvider.OIDC, token, nonce = null)
    }

    private fun docker(vararg args: String): String {
        val process = ProcessBuilder(listOf(dockerCli) + args).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "docker ${args.first()} failed: $output" }
        return output
    }

    private fun sql(query: String): String = docker("exec", postgres, "psql", "-U", "tortuga", "-d", "tortuga", "-tA", "-c", query).trim()

    private fun connectionRows(accountId: String): Int = sql("SELECT count(*) FROM tracker_connections WHERE account_id = '$accountId'").toInt()

    /** What the fake received (its `/_log`). */
    private suspend fun fakeLog(): JsonObject = Json.parseToJsonElement(http.get("$trackersUrl/_log").bodyAsText()).jsonObject

    private fun JsonObject.count(key: String): Int = getValue(key).jsonPrimitive.int

    /** The fake's requests to [path], as `<status> bearer=<whether a fake access token came>`. */
    private fun JsonObject.requests(path: String): List<String> = getValue("requests").jsonArray.map { it.jsonObject }
        .filter { it.getValue("path").jsonPrimitive.content == path }
        .map { "${it.getValue("status").jsonPrimitive.int} bearer=${it.getValue("bearer").jsonPrimitive.content}" }

    /** Starts the fake on [trackersUrl]'s port, approving a device code at its second poll; stopped by the caller. */
    private suspend fun startFake(): Process {
        val script = File(System.getProperty("tortuga.e2e.fakeTrackers"))
        val process = ProcessBuilder("python3", script.path, "--port", Url(trackersUrl).port.toString(), "--approve-after", "2")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        repeat(50) {
            if (runCatching { fakeLog() }.isSuccess) return process
            check(process.isAlive) { "the fake tracker server exited (${process.exitValue()})" }
            delay(200)
        }
        process.destroy()
        error("the fake tracker server didn't answer at $trackersUrl")
    }

    @Test
    fun aConnectionServesEveryDeviceOfTheAccount() = runBlocking {
        val fake = startFake()
        val a = Device("E2E desktop", DevicePlatform.WINDOWS)
        val b = Device("E2E phone", DevicePlatform.ANDROID)
        try {
            run(a, b)
        } finally {
            listOf(a, b).forEach { it.close() }
            fake.destroy()
            scope.cancel()
            http.close()
            Log.sink = Log.ConsoleSink
        }
    }

    private suspend fun run(a: Device, b: Device) {
        Log.sink = Log.Sink { level, tag, message, _ -> if (level >= Log.Level.WARN) println("log ${level.name[0]}/$tag: $message") }

        // 1. A signs up and connects Trakt to a profile by device code; the server stores the connection, sealed.
        a.account.restore()
        if (a.account.signIn(server) { dexCredential() } == SignInResult.EXISTING_ACCOUNT) {
            step("A found the e2e user's account from an earlier run that didn't finish; deleting it first")
            a.account.deleteAccount()
            assertEquals(SignInResult.NEW_ACCOUNT, a.account.signIn(server) { dexCredential() })
        }
        val accountId = assertIs<AccountState.SignedIn>(a.account.state.value).accountId
        val profile = a.data.profiles.create("E2E Trackers", "avatar-3")
        a.sync()
        val connected = assertNotNull(
            a.trackers.signIn(profile.id, TRAKT) { prompt ->
                step("A shows Trakt's code ${(prompt as SignInPrompt.DeviceCode).grant.userCode}")
            },
        )
        assertEquals("tortuga-tester", connected.username)
        assertEquals(1, connectionRows(accountId))
        assertTrue("fake-refresh-" !in a.secretText(), "A keeps no refresh token")
        val dump = docker("exec", postgres, "pg_dump", "-U", "tortuga", "tortuga")
        val markers = listOf("fake-access-1", "fake-refresh-1", "tortuga-tester")
        val found = markers.filter { it in dump || it.encodeToByteArray().toHexString() in dump }
        assertTrue(found.isEmpty(), "plaintext in the database dump: $found")
        step("A (account $accountId) connected Trakt as ${connected.username}; the server's row is sealed; A keeps no refresh token")

        // 2. B signs in to the account, lists the connection and scrobbles with the server's token, never signing in to Trakt.
        b.account.restore()
        assertEquals(SignInResult.EXISTING_ACCOUNT, b.account.signIn(server) { dexCredential() })
        b.sync()
        b.trackers.syncConnections()
        val listed = b.trackers.accounts(profile.id).single()
        assertEquals(TRAKT to "tortuga-tester", listed.service to listed.username)
        val pollsBefore = fakeLog().count("polls")
        val tracker = b.trackers.bound(profile.id).first().single().tracker
        val movie = TrackerContext("tt0111161", "tt0111161", TrackerMediaKind.MOVIE, TrackerContentType.MOVIE, ExternalIds(imdb = "tt0111161"))
        (tracker as RealtimeScrobble).scrobble(movie, ScrobbleState.START, 12.5)
        val afterScrobble = fakeLog()
        assertEquals(listOf("201 bearer=true"), afterScrobble.requests("/scrobble/start"))
        assertEquals(pollsBefore, afterScrobble.count("polls"), "B didn't sign in to Trakt")
        assertEquals(1, afterScrobble.count("tokensIssued"))
        assertEquals("fake-access-1", b.trackers.credentials(profile.id, TRAKT)?.accessToken())
        step("B listed Trakt (${listed.username}) and scrobbled start with the server's token, without signing in")

        // 3. One refresh, by the server, for both devices.
        val bCredentials = assertNotNull(b.trackers.credentials(profile.id, TRAKT))
        assertEquals("fake-access-2", bCredentials.refreshAfterUnauthorized("fake-access-1"))
        val aCredentials = assertNotNull(a.trackers.credentials(profile.id, TRAKT))
        assertEquals("fake-access-2", aCredentials.refreshAfterUnauthorized("fake-access-1"))
        val afterRefresh = fakeLog()
        assertEquals(listOf("200 bearer=false"), afterRefresh.requests("/oauth/token"), "the server refreshed once, at the fake")
        assertEquals(2, afterRefresh.count("tokensIssued"))
        assertTrue("fake-refresh-" !in a.secretText() + b.secretText(), "no device keeps a refresh token")
        step("B's 401 had the server refresh once; A's 401 with the old token got the same new token without another")

        // 4. B disconnects: the server revokes and forgets; A's next token request finds it gone and drops it.
        b.trackers.disconnect(profile.id, TRAKT)
        assertEquals(emptyList(), b.trackers.accounts(profile.id))
        assertEquals(1, fakeLog().count("revoked"))
        assertEquals(0, connectionRows(accountId))
        val gone = assertFailsWith<TrackerException.Auth> { aCredentials.refreshAfterUnauthorized("fake-access-2") }
        assertTrue(gone.permanent)
        assertEquals(emptyList(), a.trackers.accounts(profile.id))
        a.trackers.syncConnections()
        assertEquals(emptyList(), a.trackers.accounts(profile.id))
        step("B disconnected: the server revoked at the fake and deleted the row; A's next token request got 410 and A dropped it")

        // 5. Deleting the account takes its connections with it.
        a.trackers.signIn(profile.id, TRAKT) {}
        assertEquals(1, connectionRows(accountId))
        a.account.deleteAccount()
        assertEquals(0, connectionRows(accountId))
        assertEquals(2, fakeLog().count("revoked"))
        step("A connected again and deleted the account: the server revoked the connection and left no row")
    }
}
