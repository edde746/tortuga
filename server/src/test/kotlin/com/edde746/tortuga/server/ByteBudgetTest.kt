package com.edde746.tortuga.server

import com.edde746.tortuga.server.http.ByteBudget
import com.edde746.tortuga.server.http.PUSH_MOST_BYTES
import com.edde746.tortuga.server.http.installByteBudget
import com.edde746.tortuga.server.http.installErrorHandling
import com.edde746.tortuga.server.http.reserveBytes
import com.edde746.tortuga.server.support.ServerTestContext
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.ok
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.sync.api.ApiError
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.PullResponse
import com.edde746.tortuga.sync.api.PushRequest
import com.edde746.tortuga.sync.api.PushResponse
import com.edde746.tortuga.sync.api.RecordWrite
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Calls hold what they reserve of the server's byte budget until they end; past it they are refused. */
class ByteBudgetTest {
    @Test
    fun aCallThatFindsNoRoomIsAnsweredBusyAndWhatACallReservedReturnsWhenItEnds() = budgetTest { budget, ended ->
        // Another call holds all but this call's own share.
        val other = budget.total - ByteBudget.CALL_BYTES
        assertTrue(budget.tryReserve(other))
        assertEquals("${budget.total}", client.get("/hold?bytes=0").text())
        assertEquals(other, ended.receive())

        // A body, or more bytes once in, find no room: 503 with Retry-After, and nothing stays held.
        for (busy in listOf(client.post("/body") { setBody(bodyOf("{}")) }, client.get("/hold?bytes=1"))) {
            busy.assertBusy()
            assertEquals(other, ended.receive())
        }

        // The other call ended: a body's cost and what the call reserved are held while it runs, and return after.
        budget.release(other)
        val body = "x".repeat(1000)
        val held = ByteBudget.CALL_BYTES + ByteBudget.BODY_COST * body.length
        assertEquals("$held", client.post("/body") { setBody(bodyOf(body)) }.text())
        assertEquals(0, ended.receive())
        assertEquals("${ByteBudget.CALL_BYTES + 5000}", client.get("/hold?bytes=5000").text())
        assertEquals(0, ended.receive())
    }

    @Test
    fun aRequestTheWholeBudgetCouldNeverHoldIsTooLarge() = budgetTest { budget, ended ->
        // Its body: three times its length is more than the budget, however idle.
        val body = "x".repeat((budget.total / ByteBudget.BODY_COST).toInt())
        client.post("/body") { setBody(bodyOf(body)) }.assertError(HttpStatusCode.PayloadTooLarge, ApiErrorCode.PAYLOAD_TOO_LARGE)
        assertEquals(0, ended.receive())
        // What it would build.
        client.get("/hold?bytes=${budget.total}").assertError(HttpStatusCode.PayloadTooLarge, ApiErrorCode.PAYLOAD_TOO_LARGE)
        assertEquals(0, ended.receive())
        // A body longer than its path takes, refused before it is read.
        client.post("/body") { setBody(bodyOf("x".repeat(LIMIT.toInt() + 1))) }
            .assertError(HttpStatusCode.PayloadTooLarge, ApiErrorCode.PAYLOAD_TOO_LARGE)
        assertEquals(0, ended.receive())
        // One just under it gets in.
        val fits = "x".repeat(((budget.total - ByteBudget.CALL_BYTES) / ByteBudget.BODY_COST).toInt())
        assertEquals(HttpStatusCode.OK, client.post("/body") { setBody(bodyOf(fits)) }.status)
        assertEquals(0, ended.receive())
    }

    @Test
    fun aPullAndAPushReserveTheRecordsTheyAnswerWith() = serverTest(configure = { config, _ -> config.copy(requestBytes = 8L shl 20) }) {
        val token = signIn("alice").session.accessToken
        val big = JsonObject(mapOf("v" to JsonPrimitive("x".repeat(200 * 1024))))
        push(token, (0..3).map { RecordWrite("big$it", 0, payload = big) }).ok<PushResponse>()

        // Another call holds all but 256 KiB: a pull's page (its four records, held as rows and as JSON) has no room.
        val budget = services.bytes
        val other = budget.total - 256 * 1024
        assertTrue(budget.tryReserve(other))
        get("${SyncPaths.SYNC_PULL}?${SyncPaths.PULL_SINCE}=0", token).assertBusy()
        // Nor a push's stale record: refused before it commits, so the write beside it isn't written either.
        val small = JsonObject(mapOf("v" to JsonPrimitive("x")))
        push(token, listOf(RecordWrite("big0", 0, payload = small), RecordWrite("new", 0, payload = small))).assertBusy()

        budget.release(other)
        val pulled = get("${SyncPaths.SYNC_PULL}?${SyncPaths.PULL_SINCE}=0", token).ok<PullResponse>()
        assertEquals(listOf("big0", "big1", "big2", "big3"), pulled.changes.map { it.recordId })
        assertEquals(big, pulled.changes.first().payload)
    }

    @Test
    fun aHeapWhoseBudgetCantHoldTheLargestPushStopsTheServerNamingTheHeapItNeeds() {
        // The hosted heap (174 MiB) holds it with room to spare; the server takes a quarter.
        assertEquals(174L shl 18, ByteBudget.ofHeap(174L shl 20))
        assertTrue(PUSH_MOST_BYTES <= ByteBudget.ofHeap(4 * PUSH_MOST_BYTES))

        // A byte less, and every 8 MiB push would be answered 413: the server doesn't start, and says what it needs.
        val needed = (4 * PUSH_MOST_BYTES + (1 shl 20) - 1) shr 20
        val refused = assertFailsWith<IllegalArgumentException> { ByteBudget.ofHeap(4 * PUSH_MOST_BYTES - 4) }
        assertTrue("needs a heap of at least $needed MiB" in refused.message!!, refused.message)
        assertFailsWith<IllegalArgumentException> { ByteBudget.ofHeap(64L shl 20) }
    }

    private suspend fun ServerTestContext.push(token: String, writes: List<RecordWrite>) =
        post(SyncPaths.SYNC_PUSH, PushRequest(writes), token)

    private companion object {
        const val TOTAL = 256L * 1024
        const val LIMIT = 128L * 1024

        fun bodyOf(text: String) = TextContent(text, ContentType.Text.Plain)

        /**
         * A server of a [TOTAL]-byte budget whose calls take bodies up to [LIMIT]: `POST /body` reads its body and
         * `GET /hold?bytes=<n>` reserves n bytes more, and both answer what the budget holds while they run. [block]'s
         * channel gets what the budget holds once each call has ended.
         */
        fun budgetTest(block: suspend BudgetTest.(ByteBudget, Channel<Long>) -> Unit) = testApplication {
            val budget = ByteBudget(TOTAL)
            val ended = Channel<Long>(Channel.UNLIMITED)
            application {
                // Inside the error pages, outside the budget: sees the call once it has returned what it reserved.
                intercept(ApplicationCallPipeline.Monitoring) {
                    try {
                        proceed()
                    } finally {
                        ended.trySend(budget.held)
                    }
                }
                install(ServerContentNegotiation) { json(SyncProtocol.json) }
                installErrorHandling()
                installByteBudget(budget) { LIMIT }
                routing {
                    post("/body") {
                        call.receiveText()
                        call.respondText("${budget.held}")
                    }
                    get("/hold") {
                        call.reserveBytes(call.request.queryParameters["bytes"]!!.toLong())
                        call.respondText("${budget.held}")
                    }
                }
            }
            BudgetTest(createClient { install(ContentNegotiation) { json() } }).block(budget, ended)
        }
    }

    private class BudgetTest(val client: HttpClient)
}

private suspend fun HttpResponse.assertBusy() {
    assertEquals(HttpStatusCode.ServiceUnavailable, status, bodyAsText())
    assertEquals(ApiErrorCode.SERVER_BUSY, body<ApiError>().code)
    assertEquals("5", headers[HttpHeaders.RetryAfter])
}

private suspend fun HttpResponse.text(): String {
    assertEquals(HttpStatusCode.OK, status, bodyAsText())
    return bodyAsText()
}
