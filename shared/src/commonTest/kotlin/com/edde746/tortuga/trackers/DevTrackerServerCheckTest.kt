package com.edde746.tortuga.trackers

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [checkDevTrackerServer] against a fake tracker server's `GET /_config`. */
class DevTrackerServerCheckTest {
    private val requested = mutableListOf<String>()

    private fun server(config: String, status: HttpStatusCode = HttpStatusCode.OK) = HttpClient(
        MockEngine { request ->
            requested += request.url.toString()
            respond(config, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    private suspend fun check(http: HttpClient) =
        checkDevTrackerServer(http, "http://127.0.0.1:8772/", "/tortuga", "tortuga-tester", "fake-access-dev")

    @Test
    fun aServerThatAgreesPasses() = runTest {
        check(server(config()))
        assertEquals(listOf("http://127.0.0.1:8772/_config"), requested)
    }

    @Test
    fun eachDisagreementIsNamed() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            check(server(config(account = "/account", user = "someone", tokenPrefix = "token-", prefixes = """["","/simkl"]""")))
        }
        val message = e.message.orEmpty()
        for (part in listOf("'/account'", "'someone'", "'token-'", "[, /simkl]")) assertTrue(part in message, "$part in: $message")
    }

    @Test
    fun aServerWithoutTheConfigFails() = runTest {
        val e = assertFailsWith<IllegalStateException> { check(server("", HttpStatusCode.NotFound)) }
        assertTrue("404" in e.message.orEmpty(), e.message)
    }

    private fun config(
        account: String = "/tortuga",
        user: String = "tortuga-tester",
        tokenPrefix: String = "fake-access-",
        prefixes: String = """["","/anilist","/github","/mal","/mdblist","/simkl"]""",
    ) = """{"account":"$account","prefixes":$prefixes,"user":"$user","tokenPrefix":"$tokenPrefix"}"""
}
