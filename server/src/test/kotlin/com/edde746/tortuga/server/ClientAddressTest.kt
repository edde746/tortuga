package com.edde746.tortuga.server

import com.edde746.tortuga.server.http.addressKey
import com.edde746.tortuga.server.support.LinkSecret
import com.edde746.tortuga.server.support.ServerTestContext
import com.edde746.tortuga.server.support.assertError
import com.edde746.tortuga.server.support.serverTest
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.LinkStartRequest
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientAddressTest {
    @Test
    fun addressKeys() {
        assertEquals("2001:db8:1:2:0:0:0:0", addressKey("2001:db8:1:2:3:4:5:6"))
        assertEquals(addressKey("2001:db8:1:2::1"), addressKey("[2001:db8:1:2:ffff::9]"))
        assertEquals("fe80:0:0:0:0:0:0:0", addressKey("fe80::1%eth0"))
        assertEquals("192.0.2.1", addressKey("::ffff:192.0.2.1"))
        assertEquals("192.0.2.1", addressKey("192.0.2.1"))
        assertEquals("localhost", addressKey("localhost"))
        assertEquals("not:an:address", addressKey("not:an:address"))
    }

    @Test
    fun rateLimitsCountAnIpv6ClientByItsPrefix() = behindAProxy {
        startLinkVia("2001:db8:7:1::1").assertStarted()
        startLinkVia("2001:db8:7:1:ffff::2").assertStarted()
        startLinkVia("2001:db8:7:1::3").assertError(HttpStatusCode.TooManyRequests, ApiErrorCode.RATE_LIMITED)
        startLinkVia("2001:db8:7:2::1").assertStarted()
    }

    @Test
    fun aClientCantChooseItsAddressThroughXForwardedFor() = behindAProxy {
        // The client sends an X-Forwarded-For of its own; the proxy appends the address it saw.
        startLinkVia("203.0.113.1, 198.51.100.9").assertStarted()
        startLinkVia("203.0.113.2, 198.51.100.9").assertStarted()
        startLinkVia("203.0.113.3, 198.51.100.9").assertError(HttpStatusCode.TooManyRequests, ApiErrorCode.RATE_LIMITED)
        startLinkVia("203.0.113.3, 198.51.100.10").assertStarted()
    }

    /** [block] against a server that trusts its proxy's `X-Forwarded-For`, allowing 2 link starts per client a minute. */
    private fun behindAProxy(block: suspend ServerTestContext.() -> Unit) = serverTest(
        configure = { config, _ -> config.copy(trustForwardedHeaders = true, rateLimits = config.rateLimits.copy(linkStart = 2)) },
        block = block,
    )

    /** A link start whose request reached the server with [forwardedFor] as its `X-Forwarded-For`. */
    private suspend fun ServerTestContext.startLinkVia(forwardedFor: String): HttpResponse = client.post(SyncPaths.LINK_START) {
        header(HttpHeaders.XForwardedFor, forwardedFor)
        contentType(ContentType.Application.Json)
        setBody(LinkStartRequest(LinkSecret().hash, DeviceDescriptor("Living room TV", "android_tv")))
    }

    private fun HttpResponse.assertStarted() = assertEquals(HttpStatusCode.OK, status)
}
