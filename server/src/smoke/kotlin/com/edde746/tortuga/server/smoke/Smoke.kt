package com.edde746.tortuga.server.smoke

import com.edde746.tortuga.sync.api.AccountDeletionResponse
import com.edde746.tortuga.sync.api.AccountInfo
import com.edde746.tortuga.sync.api.ApiError
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DeviceList
import com.edde746.tortuga.sync.api.DevicePlatform
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.LinkClaimResponse
import com.edde746.tortuga.sync.api.LinkCodeRequest
import com.edde746.tortuga.sync.api.LinkPollRequest
import com.edde746.tortuga.sync.api.LinkPollResponse
import com.edde746.tortuga.sync.api.LinkStartRequest
import com.edde746.tortuga.sync.api.LinkStartResponse
import com.edde746.tortuga.sync.api.LinkStatus
import com.edde746.tortuga.sync.api.PullResponse
import com.edde746.tortuga.sync.api.PushRequest
import com.edde746.tortuga.sync.api.PushResponse
import com.edde746.tortuga.sync.api.RecordChange
import com.edde746.tortuga.sync.api.RecordWrite
import com.edde746.tortuga.sync.api.RefreshRequest
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.SessionTokens
import com.edde746.tortuga.sync.api.SignInRequest
import com.edde746.tortuga.sync.api.SignInResponse
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * End-to-end client for a running server (scripts/smoke.sh): a phone signs in through the dev OIDC provider (Dex) and
 * pushes records, a TV signs in through a device link and reads them back, and later phases delete the account or
 * check that a restored server still syncs. Records travel as the apps write them: natural ids, readable JSON.
 *
 *   smoke setup  --server URL --dex URL --state FILE --markers FILE
 *   smoke delete --state FILE
 *   smoke verify-restored --state FILE
 */
fun main(args: Array<String>) {
    val options = args.drop(1).chunked(2).associate { (key, value) -> key.removePrefix("--") to value }
    val state = File(options["state"] ?: fail("--state is required"))
    runBlocking {
        Smoke().use { smoke ->
            when (args.firstOrNull()) {
                "setup" -> smoke.setup(
                    server = options["server"] ?: fail("--server is required"),
                    dex = options["dex"] ?: fail("--dex is required"),
                    state = state,
                    markers = File(options["markers"] ?: fail("--markers is required")),
                )
                "delete" -> smoke.delete(state)
                "verify-restored" -> smoke.verifyRestored(state)
                else -> fail("usage: smoke setup|delete|verify-restored --state FILE …")
            }
        }
    }
}

/** What later phases need from `setup`. Holds a throwaway dev account's tokens; the script deletes it. */
@Serializable
private data class SmokeState(
    val server: String,
    val accountId: String,
    val phone: SessionTokens,
    val tv: SessionTokens,
    val records: Map<String, JsonObject>,
)

private class Smoke : AutoCloseable {
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) { json(SyncProtocol.json) }
    }
    private val json = SyncProtocol.json

    /** Record id → payload: what a profile's synced data looks like. */
    private val records = mapOf(
        "addons/profile-1" to record(
            """{"key":{},"hlc":"1790000000000:0:phone","format":1,"body":{"addons":["https://streams.addon.example/key=KEY9f8e7d6c5b4a39281/manifest.json","https://v3-cinemeta.strem.io/manifest.json"]}}""",
        ),
        "library/profile-1/tt0111161" to record(
            """{"key":{"id":"tt0111161"},"hlc":"1790000000001:0:phone","format":1,"body":{"type":"movie","name":"The Shawshank Redemption"}}""",
        ),
        "stream/profile-1/tt0111161/tt0111161" to record(
            """{"key":{"meta":"tt0111161"},"hlc":"1790000000002:0:phone","format":1,"body":{"timeWatched":4211000,"lastWatched":1790000000002}}""",
        ),
        "profile/profile-1" to record(
            """{"key":{},"hlc":"1790000000003:0:phone","format":1,"body":{"name":"Wednesday Evening"}}""",
        ),
    )

    /** Strings that must never appear in a database dump: the server seals ids and payloads at rest. */
    private val markers = listOf(
        "streams.addon.example", "KEY9f8e7d6c5b4a39281", "tt0111161", "Shawshank", "Wednesday Evening", "timeWatched", "cinemeta",
        "addons/profile-1", "profile/profile-1",
    )

    suspend fun setup(server: String, dex: String, state: File, markers: File) {
        val info: ServerInfo = call(HttpMethod.Get, server + SyncPaths.INFO)
        check(info.protocolVersion == SyncProtocol.VERSION) { "server speaks protocol ${info.protocolVersion}" }
        val oidc = info.providers.single { it.id == IdentityProvider.OIDC }
        step("server speaks protocol ${info.protocolVersion}; sign-in providers: ${info.providers.map { it.id }}")

        val phoneSignIn: SignInResponse = call(
            HttpMethod.Post, server + SyncPaths.AUTH_SIGN_IN,
            body = SignInRequest(
                provider = IdentityProvider.OIDC,
                idToken = dexIdToken(dex, oidc.clientIds.first()),
                device = DeviceDescriptor("Smoke phone", DevicePlatform.ANDROID),
            ),
        )
        check(phoneSignIn.accountCreated) { "expected a new account on an empty server" }
        val accountId = phoneSignIn.account.accountId
        var phone = phoneSignIn.session
        step("phone signed in through ${oidc.displayName} (${oidc.issuer}); new account $accountId")

        val pushed: PushResponse = call(
            HttpMethod.Post, server + SyncPaths.SYNC_PUSH, phone.accessToken,
            PushRequest(records.map { (id, payload) -> RecordWrite(id, 0, payload = payload) }),
        )
        check(pushed.accepted.size == records.size && pushed.stale.isEmpty()) { "push: $pushed" }
        step("phone pushed ${pushed.accepted.size} records (versions ${pushed.accepted.map { it.version }})")

        // A TV with nobody signed in signs in through a device link.
        val tvSecret = base64Url(ByteArray(32).also(SecureRandom()::nextBytes))
        val started: LinkStartResponse = call(
            HttpMethod.Post, server + SyncPaths.LINK_START,
            body = LinkStartRequest(secretHash(tvSecret), DeviceDescriptor("Smoke TV", DevicePlatform.ANDROID_TV)),
        )
        val shown = started.code.chunked(4).joinToString("-")
        step("TV shows link code $shown")
        val pending: LinkPollResponse = call(HttpMethod.Post, server + SyncPaths.LINK_POLL, body = LinkPollRequest(started.linkId, tvSecret))
        check(pending.status == LinkStatus.PENDING)

        val claim: LinkClaimResponse = call(HttpMethod.Post, server + SyncPaths.LINK_CLAIM, phone.accessToken, LinkCodeRequest(shown.lowercase()))
        check(claim.device.name == "Smoke TV") { "claimed the wrong link: ${claim.device}" }
        expectStatus(HttpStatusCode.NoContent, send(HttpMethod.Post, server + SyncPaths.LINK_APPROVE, phone.accessToken, LinkCodeRequest(shown)))
        step("phone claimed the code (${claim.device.name}, ${claim.device.platform}) and approved")

        // Only the secret whose hash started the link collects the session; the code alone doesn't.
        expectStatus(
            HttpStatusCode.NotFound,
            send(HttpMethod.Post, server + SyncPaths.LINK_POLL, body = LinkPollRequest(started.linkId, started.code)),
        )
        var approved: LinkPollResponse = pending
        repeat(10) {
            if (approved.status != LinkStatus.PENDING) return@repeat
            approved = call(HttpMethod.Post, server + SyncPaths.LINK_POLL, body = LinkPollRequest(started.linkId, tvSecret))
            if (approved.status == LinkStatus.PENDING) delay(500)
        }
        check(approved.status == LinkStatus.APPROVED) { "link not approved: ${approved.status}" }
        check(approved.accountId == accountId) { "TV joined another account: ${approved.accountId}" }
        val tv = approved.session ?: error("an approved link must hand the TV a session")
        step("a poll with the code alone was refused; the TV's own poll collected its session on account $accountId")

        val tvPull = pullAll(server, tv.accessToken)
        check(current(tvPull) == records) { "TV read different data" }
        step("TV pulled ${tvPull.size} records; they match what the phone pushed")

        // Both devices change the same record: the later push is stale, gets the current one back, merges, retries.
        val progressId = "stream/profile-1/tt0111161/tt0111161"
        val base = tvPull.single { it.recordId == progressId }.version
        val tvWrite = progress(4300000, 1790000000100, "1790000000100:0:tv")
        val tvPush: PushResponse = call(HttpMethod.Post, server + SyncPaths.SYNC_PUSH, tv.accessToken, PushRequest(listOf(RecordWrite(progressId, base, payload = tvWrite))))
        check(tvPush.accepted.size == 1)
        val phoneWrite = progress(4250000, 1790000000200, "1790000000200:0:phone")
        val stale: PushResponse = call(HttpMethod.Post, server + SyncPaths.SYNC_PUSH, phone.accessToken, PushRequest(listOf(RecordWrite(progressId, base, payload = phoneWrite))))
        val currentProgress = stale.stale.single()
        check(currentProgress.payload == tvWrite) { "stale push didn't return the TV's write" }
        // Progress merge: furthest position, latest lastWatched.
        val merged = progress(4300000, 1790000000200, "1790000000201:0:phone")
        val retried: PushResponse = call(
            HttpMethod.Post, server + SyncPaths.SYNC_PUSH, phone.accessToken,
            PushRequest(listOf(RecordWrite(progressId, currentProgress.version, payload = merged))),
        )
        check(retried.accepted.single().version > currentProgress.version)
        step("conflict: phone's stale push got the TV's version ${currentProgress.version} back, merged, accepted as ${retried.accepted.single().version}")

        val devices: DeviceList = call(HttpMethod.Get, server + SyncPaths.DEVICES, phone.accessToken)
        step("devices: ${devices.devices.map { "${it.name} (${it.platform})" }}")

        phone = call(HttpMethod.Post, server + SyncPaths.AUTH_REFRESH, body = RefreshRequest(phone.refreshToken))
        val expected = records + (progressId to merged)
        state.writeText(json.encodeToString(SmokeState.serializer(), SmokeState(server, accountId, phone, tv, expected)))
        markers.writeText(this.markers.joinToString("\n", postfix = "\n"))
        step("setup done; state for the later phases in ${state.path}")
    }

    suspend fun delete(stateFile: File) {
        val state = json.decodeFromString(SmokeState.serializer(), stateFile.readText())
        val deletion: AccountDeletionResponse = call(HttpMethod.Delete, state.server + SyncPaths.ACCOUNT, state.phone.accessToken)
        step("account ${state.accountId} deleted (Apple revocation: ${deletion.appleRevocation})")
        val tvAfter = send(HttpMethod.Get, state.server + SyncPaths.SYNC_PULL, state.tv.accessToken)
        expectStatus(HttpStatusCode.Unauthorized, tvAfter)
        step("the TV's session is gone with it (${tvAfter.body<ApiError>().code})")
    }

    suspend fun verifyRestored(stateFile: File) {
        val state = json.decodeFromString(SmokeState.serializer(), stateFile.readText())
        val phone: SessionTokens = call(HttpMethod.Post, state.server + SyncPaths.AUTH_REFRESH, body = RefreshRequest(state.phone.refreshToken))
        val account: AccountInfo = call(HttpMethod.Get, state.server + SyncPaths.ACCOUNT, phone.accessToken)
        check(account.accountId == state.accountId)
        step("restored server knows account ${account.accountId}; the phone's session refreshed")

        val changes = pullAll(state.server, phone.accessToken)
        check(current(changes) == state.records) { "restored data differs" }
        step("pulled ${changes.size} records from the restored server (opened with the same data key); all match")

        val id = "library/profile-1/tt0068646"
        val pushed: PushResponse = call(
            HttpMethod.Post, state.server + SyncPaths.SYNC_PUSH, phone.accessToken,
            PushRequest(listOf(RecordWrite(id, 0, payload = record("""{"key":{"id":"tt0068646"},"hlc":"1790000001000:0:phone","format":1,"body":{"type":"movie"}}""")))),
        )
        val tvChanges = pullAll(state.server, state.tv.accessToken)
        check(tvChanges.any { it.recordId == id && it.version == pushed.accepted.single().version })
        step("the restored account syncs again: a new record (version ${pushed.accepted.single().version}) reached the TV")
    }

    private suspend fun dexIdToken(dex: String, clientId: String): String {
        val response = http.submitForm(
            "$dex/token",
            parameters {
                append("grant_type", "password")
                append("client_id", clientId)
                append("username", "alice@example.com")
                append("password", "password")
                append("scope", "openid email profile")
            },
        )
        check(response.status.isSuccess()) { "Dex token request failed: ${response.status} ${response.bodyAsText()}" }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id_token").jsonPrimitive.content
    }

    private suspend fun pullAll(server: String, token: String): List<RecordChange> {
        val changes = ArrayList<RecordChange>()
        var cursor = 0L
        do {
            val page: PullResponse = call(HttpMethod.Get, "$server${SyncPaths.SYNC_PULL}?${SyncPaths.PULL_SINCE}=$cursor&${SyncPaths.PULL_LIMIT}=2", token)
            changes += page.changes
            cursor = page.cursor
        } while (page.hasMore)
        // Latest write per record wins.
        return changes.groupBy { it.recordId }.map { (_, versions) -> versions.maxBy { it.version } }
    }

    private fun current(changes: List<RecordChange>): Map<String, JsonObject> =
        changes.filterNot { it.deleted }.associate { it.recordId to it.payload }

    private fun record(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun progress(timeWatched: Long, lastWatched: Long, hlc: String) = record(
        """{"key":{"meta":"tt0111161"},"hlc":"$hlc","format":1,"body":{"timeWatched":$timeWatched,"lastWatched":$lastWatched}}""",
    )

    /** [LinkStartRequest.secretHash]: unpadded base64url of SHA-256 of the secret's UTF-8 bytes. */
    private fun secretHash(secret: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(secret.encodeToByteArray()))

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private suspend inline fun <reified T> call(method: HttpMethod, url: String, token: String? = null, body: Any? = null): T {
        val response = send(method, url, token, body)
        check(response.status.isSuccess()) { "${method.value} $url → ${response.status}: ${response.bodyAsText()}" }
        return response.body()
    }

    private suspend fun send(method: HttpMethod, url: String, token: String? = null, body: Any? = null): HttpResponse =
        http.request(url) {
            this.method = method
            token?.let { bearerAuth(it) }
            if (body != null) jsonBody(body)
        }

    private fun HttpRequestBuilder.jsonBody(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun expectStatus(status: HttpStatusCode, response: HttpResponse) =
        check(response.status == status) { "expected $status, got ${response.status}" }

    private fun step(message: String) = println("smoke: $message")

    override fun close() = http.close()
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}
