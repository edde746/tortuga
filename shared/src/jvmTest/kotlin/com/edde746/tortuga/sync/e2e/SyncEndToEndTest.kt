package com.edde746.tortuga.sync.e2e

import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.streams.DeviceClass
import com.edde746.tortuga.data.streams.PickContext
import com.edde746.tortuga.data.streams.PickOutcome
import com.edde746.tortuga.data.streams.PickSource
import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamPick
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.TortugaHttpClient
import com.edde746.tortuga.playback.streams.HdrBadge
import com.edde746.tortuga.playback.streams.ResolutionBadge
import com.edde746.tortuga.playback.streams.VideoCodecBadge
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.AccountState
import com.edde746.tortuga.sync.account.DeviceLinkOutcome
import com.edde746.tortuga.sync.account.ProviderCredential
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.SignInResult
import com.edde746.tortuga.sync.account.SignedOutReason
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.engine.SyncEngine
import com.edde746.tortuga.sync.engine.SyncEngineConfig
import com.edde746.tortuga.sync.engine.SyncStatus
import com.edde746.tortuga.sync.engine.heavyAddonList
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shared sync client end to end against the real server, with Dex as the OIDC
 * provider: `server/scripts/sync-e2e.sh` runs this through `:shared:syncEndToEndTest` with a stack's coordinates
 * (its own compose project, or a shared dev stack). `jvmTest` skips it.
 *
 * Device A signs up and fills a profile (with a PIN and a heavy addon list, over the record size limit unpacked);
 * device C signs in to the same account and sees it all; device B
 * (a signed-out TV) signs in through a device link A approves and gets the data; progress crosses between them; while C
 * is in front, a push from A reaches it within 2 s through its notice stream, and the stream closes in the background;
 * a stale push merges; a database dump holds none of the account's addon URLs, ids or titles (the server encrypts them
 * at rest); account deletion leaves no rows.
 */
class SyncEndToEndTest {
    private val server = ServerUrl.parse(setting("server"))
    private val dex = setting("dex")
    private val postgres = setting("postgresContainer")
    private val dockerCli = System.getProperty("tortuga.e2e.docker")?.takeIf { it.isNotBlank() } ?: "docker"
    private val http = TortugaHttpClient.create("e2e")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logLines = ArrayList<String>()

    /** Runs once before the phone's next push is sent: another device's push racing it. */
    @Volatile private var beforePhonePush: (suspend () -> Unit)? = null
    private val phoneHttp = TortugaHttpClient.create("e2e").apply {
        plugin(HttpSend).intercept { request ->
            if (request.url.buildString().substringBefore('?').endsWith(SyncPaths.SYNC_PUSH)) beforePhonePush?.let { hook ->
                beforePhonePush = null
                hook()
            }
            execute(request)
        }
    }

    /** When the tablet sent each pull (epoch millis): its notice stream's pulls, told apart from any other. */
    private val tabletPulls = CopyOnWriteArrayList<Long>()
    private val tabletHttp = TortugaHttpClient.create("e2e").apply {
        plugin(HttpSend).intercept { request ->
            if (request.url.buildString().substringBefore('?').endsWith(SyncPaths.SYNC_PULL)) tabletPulls += System.currentTimeMillis()
            execute(request)
        }
    }

    private inner class Device(
        name: String,
        platform: String,
        http: HttpClient = this@SyncEndToEndTest.http,
        config: SyncEngineConfig = SyncEngineConfig(),
    ) : AutoCloseable {
        val secrets = MemorySecretStore()
        val data = LocalData(DatabaseFactory.inMemory())
        val account = SyncAccount(secrets, data, http, DeviceDescriptor(name, platform))
        val engine = SyncEngine(account, data, scope, config)

        suspend fun sync() {
            val status = engine.syncNow()
            assertEquals(SyncStatus.Kind.Idle, status.kind, "sync status: ${status.text}")
        }

        override fun close() = data.close()
    }

    private fun setting(name: String): String = System.getProperty("tortuga.e2e.$name")?.takeIf { it.isNotBlank() }
        ?: error("-Ptortuga.e2e.$name is not set; run server/scripts/sync-e2e.sh")

    private fun step(message: String) = println("e2e: $message")

    /** Waits up to [timeoutMillis] for [condition], checking every 20 ms. */
    private suspend fun waitUntil(timeoutMillis: Long, what: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "not within $timeoutMillis ms: $what" }
            delay(20)
        }
    }

    private fun engineLogged(line: String) = synchronized(logLines) { line in logLines }

    /** A fresh Dex ID token for the static user (password grant; the server accepts each token once). */
    private suspend fun dexCredential(): ProviderCredential {
        val response = http.submitForm(
            "$dex/token",
            parameters {
                append("grant_type", "password")
                append("client_id", "tortuga-dev")
                append("username", "alice@example.com")
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

    /**
     * The rows of [accountId] in every table that names an account (an `account_id` or `approved_account_id` column),
     * and its `accounts` row. Scoped to the one account, since a shared dev stack serves other runs' accounts at
     * the same time.
     */
    private fun accountRows(accountId: String): Map<String, Int> {
        val columns = sql(
            "SELECT table_name || ' ' || column_name FROM information_schema.columns WHERE table_schema = 'public' " +
                "AND column_name IN ('account_id', 'approved_account_id') ORDER BY 1",
        ).lines().filter { it.isNotBlank() }.map { it.substringBefore(' ') to it.substringAfter(' ') }
        return columns.associate { (table, column) -> table to sql("SELECT count(*) FROM $table WHERE $column = '$accountId'").toInt() } +
            ("accounts" to sql("SELECT count(*) FROM accounts WHERE id = '$accountId'").toInt())
    }

    @Test
    fun signUpSecondDeviceLinkConflictAndDeletion() = runBlocking {
        Log.minLevel = Log.Level.DEBUG
        Log.sink = Log.Sink { level, tag, message, _ ->
            if (tag == "SyncEngine") synchronized(logLines) { logLines += message }
            if (level >= Log.Level.WARN) println("log ${level.name[0]}/$tag: $message")
        }
        val phone = Device("E2E phone", DevicePlatform.ANDROID, phoneHttp)
        val tv = Device("E2E TV", DevicePlatform.ANDROID_TV)
        // The tablet's periodic pull is an hour away, so only its notice stream can make it pull during the run.
        val tablet = Device("E2E tablet", DevicePlatform.IPADOS, tabletHttp, SyncEngineConfig(pullIntervalMillis = 3_600_000))
        try {
            run(phone, tv, tablet)
        } finally {
            listOf(phone, tv, tablet).forEach { it.close() }
            scope.cancel()
            http.close()
            phoneHttp.close()
            tabletHttp.close()
            Log.sink = Log.ConsoleSink
            Log.minLevel = Log.Level.INFO
        }
    }

    private suspend fun run(phone: Device, tv: Device, tablet: Device) {
        // 1. Sign-up on the phone through Dex, and a profile (with a PIN) with addons, a library item and progress.
        val info = phone.account.serverInfo(server)
        step("server protocol ${info.protocolVersion}, providers ${info.providers.map { "${it.id} (${it.displayName ?: it.issuer})" }}")
        phone.account.restore()
        if (phone.account.signIn(server) { dexCredential() } == SignInResult.EXISTING_ACCOUNT) {
            step("A found the e2e user's account from an earlier run that didn't finish; deleting it first")
            phone.account.deleteAccount()
            assertEquals(SignInResult.NEW_ACCOUNT, phone.account.signIn(server) { dexCredential() })
        }
        val accountId = assertIs<AccountState.SignedIn>(phone.account.state.value).accountId
        step("A signed up: account $accountId")

        val profile = phone.data.profiles.create("E2E Wednesday", "avatar-7")
        phone.data.profiles.setPin(profile.id, "2468")
        // A heavy profile's addons, plus one whose link carries an account key.
        val addons = heavyAddonList() +
            AddonDescriptor("https://keyed-addon.example/key=E2EKEYe2e5f4a3b2c1d0/manifest.json", "{\"id\":\"keyed.example.streams\"}")
        phone.data.addons.replaceAll(profile.id, addons)
        val movie = MetaSnapshot("tt0111161", "movie", "The Shawshank Redemption", "https://images.metahub.space/poster/medium/tt0111161/img", "poster")
        phone.data.library.addToLibrary(profile.id, movie)
        phone.data.library.recordProgress(profile.id, movie.id, movie.id, 0, 8_520_000)
        phone.data.library.recordProgress(profile.id, movie.id, movie.id, 600_000, 8_520_000)
        phone.sync()
        val records = sql("SELECT count(*) FROM records WHERE account_id = '$accountId'").toInt()
        assertEquals(3, records)
        step("A created profile \"E2E Wednesday\" (PIN set), ${addons.size} addons, 1 library item at 10:00 and synced: $records records on the server")

        // 2. A second device signs in to the same account and gets its data.
        tablet.account.restore()
        assertEquals(SignInResult.EXISTING_ACCOUNT, tablet.account.signIn(server) { dexCredential() })
        assertEquals(accountId, assertIs<AccountState.SignedIn>(tablet.account.state.value).accountId)
        tablet.sync()
        assertEquals(listOf("E2E Wednesday"), tablet.data.profiles.profiles().map { it.name })
        assertTrue(tablet.data.profiles.verifyPin(profile.id, "2468"))
        assertFalse(tablet.data.profiles.verifyPin(profile.id, "1357"))
        assertEquals(addons, tablet.data.addons.addons(profile.id).map { AddonDescriptor(it.transportUrl, it.manifestJson, it.enabled, it.watchActivity) })
        assertEquals(600_000, tablet.data.library.item(profile.id, movie.id)?.state?.timeOffset)
        step("C signed in to the same account and synced: same profile (PIN verifies), the ${addons.size} addons in order, the item at 10:00")

        // 3. The TV signs in through a device link: it shows a code, the phone approves, the TV gets its own session.
        tv.account.restore()
        val pending = tv.account.startDeviceLink(server)
        step("B (signed out) shows link code ${pending.code}; QR ${pending.qrPayload.substringBefore("&code")}&code=…")
        val linked = scope.async { pending.awaitResult() }
        val incoming = phone.account.claimDeviceLink(pending.code.lowercase())
        assertEquals("E2E TV", incoming.deviceName)
        incoming.approve()
        assertEquals(DeviceLinkOutcome.LINKED, withTimeout(30_000) { linked.await() })
        val tvState = assertIs<AccountState.SignedIn>(tv.account.state.value)
        assertEquals(accountId, tvState.accountId)
        val devices = phone.account.devices()
        assertEquals(3, devices.size, "three sessions: $devices")
        assertTrue(devices.any { it.deviceId == tvState.deviceId && it.name == "E2E TV" })
        step("A claimed ${incoming.code} (\"${incoming.deviceName}\", ${incoming.platform}) and approved; B signed in with its own session (${devices.size} devices)")

        tv.sync()
        assertEquals(listOf("E2E Wednesday"), tv.data.profiles.profiles().map { it.name })
        assertTrue(tv.data.profiles.verifyPin(profile.id, "2468"))
        assertEquals(addons, tv.data.addons.addons(profile.id).map { AddonDescriptor(it.transportUrl, it.manifestJson, it.enabled, it.watchActivity) })
        val tvItem = assertNotNull(tv.data.library.item(profile.id, movie.id))
        assertEquals(600_000, tvItem.state.timeOffset)
        step("B synced: same profile (PIN verifies), the ${addons.size} addons in order, the library item at ${tvItem.state.timeOffset / 1000} s")

        // 4. Progress played on A appears on B and C.
        phone.data.library.recordProgress(profile.id, movie.id, movie.id, 1_500_000, 8_520_000)
        phone.engine.flushNow()
        phone.sync()
        tv.sync()
        tablet.sync()
        assertEquals(1_500_000, tv.data.library.item(profile.id, movie.id)?.state?.timeOffset)
        assertEquals(1_500_000, tablet.data.library.item(profile.id, movie.id)?.state?.timeOffset)
        step("A played to 25:00; B and C show 25:00 after their next sync")

        // 4a. A stream pick on B, and what came of it, reach A: every device's picks teach every device's suggestions.
        val tvContext = PickContext(DeviceClass.TV, metered = false, hdrDisplay = true, dolbyVisionDisplay = true, hardwareCodecs = setOf(VideoCodecBadge.HEVC))
        val shown = listOf(
            PickedStream(ResolutionBadge.UHD_2160, listOf(HdrBadge.DOLBY_VISION), VideoCodecBadge.HEVC, sizeBytes = 20L shl 30),
            PickedStream(ResolutionBadge.FHD_1080, videoCodec = VideoCodecBadge.AVC, sizeBytes = 4L shl 30),
        )
        tv.data.streamPicks.record(profile.id, StreamPick("e2e-pick", movie.id, movie.id, 8_520, 0, tvContext, PickSource.LIST, shown, 0, null))
        tv.data.streamPicks.setOutcome("e2e-pick", PickOutcome.GOOD)
        tv.sync()
        phone.sync()
        val pick = assertNotNull(phone.data.streamPicks.picks(profile.id).singleOrNull(), "B's pick on A")
        assertEquals(DeviceClass.TV, pick.context.deviceClass)
        assertEquals(shown, pick.candidates)
        assertEquals(PickOutcome.GOOD, pick.outcome)
        step("B's stream pick (4K Dolby Vision over 1080p, played well) reached A with its candidates, its device's kind and its outcome")

        // 4b. While C is in front, its notice stream brings A's push without C asking: no periodic pull is due.
        synchronized(logLines) { logLines.clear() }
        tablet.engine.start()
        tablet.engine.onForeground()
        waitUntil(10_000, "C's notice stream opens") { engineLogged("The notice stream is open") }
        // The foreground's pull and the open's pull settle first: C is idle and hasn't sent a pull for a second.
        waitUntil(10_000, "C's pulls settle") {
            tabletPulls.last().let { System.currentTimeMillis() - it >= 1_000 } && tablet.engine.status.value.kind == SyncStatus.Kind.Idle
        }
        val pullsBefore = tabletPulls.size
        phone.data.library.recordProgress(profile.id, movie.id, movie.id, 1_800_000, 8_520_000)
        phone.sync()
        val pushedAt = System.currentTimeMillis()
        waitUntil(2_000, "A's push reaches C") { tablet.data.library.item(profile.id, movie.id)?.state?.timeOffset == 1_800_000L }
        val tookMillis = System.currentTimeMillis() - pushedAt
        assertEquals(1, tabletPulls.size - pullsBefore, "C pulled once for A's push")
        step("A played to 30:00 and pushed; C, in front, showed 30:00 ${tookMillis} ms later through its notice stream (one pull, no periodic pull due)")
        tablet.engine.onBackground()
        waitUntil(5_000, "C's notice stream closes in the background") { engineLogged("The notice stream is closed") }
        tablet.engine.stop()
        step("C went to the background: its notice stream closed")

        // 5. A stale push: the phone plays further, the TV writes later and pushes first.
        phone.data.library.recordProgress(profile.id, movie.id, movie.id, 4_000_000, 8_520_000)
        // Last writer wins by HLC, which orders two devices' writes by their clocks' millisecond, then counter, then node
        // id: writes in the same millisecond have no real-time order. The TV's write is later once its clock has
        // passed the millisecond of the phone's.
        val phoneWrite = phone.data.sync.dirtyRecords(null, 10).single { it.recordId.startsWith("library/") }.hlc
        while (System.currentTimeMillis() <= phoneWrite.wallMillis) delay(1)
        tv.data.library.recordProgress(profile.id, movie.id, movie.id, 2_100_000, 8_520_000)
        val phoneWatched = phone.data.library.item(profile.id, movie.id)!!.state.timeWatched
        val tvWatched = tv.data.library.item(profile.id, movie.id)!!.state.timeWatched
        synchronized(logLines) { logLines.clear() }
        // B pushes just before A's push reaches the server, so A's push is based on a superseded version.
        beforePhonePush = { tv.sync() }
        phone.sync()
        val staleLines = synchronized(logLines) { logLines.filter { "stale" in it } }
        assertEquals(1, staleLines.size, "A's push came back stale once: $logLines")
        tv.sync()
        tablet.sync()
        phone.sync()
        val merged = listOf(phone, tv, tablet).map { it.data.library.item(profile.id, movie.id)!!.state }
        assertTrue(merged.all { it == merged.first() }, "all three devices hold the same merged state: $merged")
        assertEquals(maxOf(phoneWatched, tvWatched), merged.first().timeWatched)
        assertEquals(2_100_000, merged.first().timeOffset)
        step("conflict: A (time watched ${phoneWatched / 1000} s, at 66:40) and B (${tvWatched / 1000} s, at 35:00, written later) pushed at once; A got \"${staleLines.single()}\", merged and re-pushed")
        step("merged on A, B and C: time watched ${merged.first().timeWatched / 1000} s (the furthest), position ${merged.first().timeOffset / 1000} s (the later write)")

        // 6. The database holds none of the account's addon URLs, ids or titles: the server encrypts them at rest.
        val dump = docker("exec", postgres, "pg_dump", "-U", "tortuga", "tortuga")
        val markers = listOf(
            "E2E Wednesday", "keyed-addon.example", "E2EKEYe2e5f4a3b2c1d0", "cinemeta", "tt0111161", "Shawshank", "metahub", profile.id,
            "library/", "addons/", "pick/", "UHD_2160",
        )
        val found = markers.filter { it in dump || it.encodeToByteArray().toHexString() in dump }
        assertTrue(found.isEmpty(), "plaintext in the database dump: $found")
        step("pg_dump (${dump.length} bytes) holds none of ${markers.size} plaintext markers, as text or bytea hex")

        // 7. Account deletion leaves no rows behind.
        val before = accountRows(accountId)
        step("the account's rows before deletion: $before")
        assertTrue(before.getValue("records") > 0 && before.getValue("sessions") == 3, "rows before deletion: $before")
        val deletion = phone.account.deleteAccount()
        assertEquals(AccountState.SignedOut(SignedOutReason.ACCOUNT_DELETED), phone.account.state.value)
        val after = accountRows(accountId)
        step("the account's rows after deletion: $after (Apple revocation: ${deletion.appleRevocation})")
        assertEquals(emptyMap(), after.filterValues { it != 0 })
        val ended = runCatching { tv.account.accountInfo() }.exceptionOrNull()
        assertEquals(AccountError.SessionEnded, (ended as? AccountException)?.error)
        assertEquals(AccountState.SignedOut(SignedOutReason.SESSION_ENDED), tv.account.state.value)
        step("account deleted: none of its rows left; B's next request is unauthorized and it is signed out")
    }
}
