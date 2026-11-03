package com.edde746.tortuga.addons

import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.transportUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * An [HttpClient] over Ktor's [MockEngine] that runs on the test scheduler, so handler delays and timeouts use
 * virtual time.
 */
internal fun TestScope.mockClient(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): HttpClient = HttpClient(MockEngine) {
    engine {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler(handler)
    }
}

internal fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

/** A stream-only addon at `https://<name>.example.com/manifest.json`. */
internal fun streamAddon(name: String, types: String = """["movie","series"]"""): Descriptor = Descriptor(
    Manifest.decode("""{"id":"org.example.$name","version":"1.0.0","name":"$name","types":$types,"resources":["stream"]}"""),
    "https://$name.example.com/manifest.json".transportUrl(),
)

internal fun streamsBody(vararg urls: String, extra: String = ""): String =
    """{"streams":[${urls.joinToString(",") { """{"url":"$it"}""" }}]$extra}"""
