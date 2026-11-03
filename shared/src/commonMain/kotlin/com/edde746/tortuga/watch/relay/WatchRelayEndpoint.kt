package com.edde746.tortuga.watch.relay

import com.edde746.tortuga.log.Log
import io.ktor.http.Url

/**
 * The relays rooms go through: Open Watch Party's public relays ([HOSTED], every app's default), or one self-hosted
 * relay at [baseUrl]: `http` or `https`, a host, an optional port and an optional path prefix (a reverse proxy's),
 * nothing else. [baseUrl] is canonical (lower-case scheme and host, no default port, no trailing slash), so it
 * identifies the relay in invites and recent rooms; it is empty for the public relays.
 */
class WatchRelayEndpoint private constructor(val baseUrl: String) {
    override fun equals(other: Any?): Boolean = other is WatchRelayEndpoint && other.baseUrl == baseUrl

    override fun hashCode(): Int = baseUrl.hashCode()

    override fun toString(): String = baseUrl.ifEmpty { "public" }

    companion object {
        /** Open Watch Party's public relays, every app's default. */
        val HOSTED: WatchRelayEndpoint = WatchRelayEndpoint("")

        /** [value] as a relay base URL, or null when it isn't one (another scheme, user info, a query, a fragment). */
        fun parse(value: String): WatchRelayEndpoint? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null
            val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
            if (scheme != "http" && scheme != "https") return null
            val url = try {
                Url(trimmed)
            } catch (_: Exception) {
                return null
            }
            if (url.host.isEmpty() || url.user != null || url.password != null) return null
            if (url.encodedQuery.isNotEmpty() || url.encodedFragment.isNotEmpty() || '?' in trimmed || '#' in trimmed) return null
            if (url.specifiedPort != 0 && url.specifiedPort !in 1..65535) return null
            val path = url.encodedPath.trimEnd('/')
            val defaultPort = url.protocol.defaultPort
            val port = url.specifiedPort.takeIf { it != 0 && it != defaultPort }
            val base = buildString {
                append(url.protocol.name).append("://").append(url.host.lowercase())
                if (port != null) append(':').append(port)
                append(path)
            }
            return WatchRelayEndpoint(base)
        }

        /**
         * The relays a stored [override] names (a device's setting, a recent room's relay): the public relays for none
         * (null or empty, the public relays' [baseUrl]), else its relay; one that no longer parses falls back to the
         * public relays, logged.
         */
        fun resolve(override: String?): WatchRelayEndpoint {
            if (override.isNullOrEmpty()) return HOSTED
            return parse(override) ?: HOSTED.also { Log.w(TAG) { "Stored relay $override isn't a relay URL: using the public relays" } }
        }

        private const val TAG = "watch-relay"
    }
}
