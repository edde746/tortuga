package com.edde746.tortuga.watch.relay

import com.edde746.tortuga.net.UriComponent
import party.openwatch.PartyException
import party.openwatch.formatCode

/**
 * An invite to a room: `tortuga://watch?code=XXXXXX`, with `&relay=<relay base URL>` when the room is on a self-hosted
 * relay ([WatchRelayEndpoint.HOSTED] stands for Open Watch Party's public relays, which route every code). A link
 * without a relay joins on the public relays whatever the guest's own setting says; a bare code joins on the guest's
 * relay. [code] is the relay's form: 6 symbols, no separator.
 */
data class WatchInvite(val code: String, val relay: WatchRelayEndpoint) {
    fun toUrl(): String =
        "$SCHEME://$HOST?code=$code" + if (relay == WatchRelayEndpoint.HOSTED) "" else "&relay=${UriComponent.encode(relay.baseUrl)}"

    companion object {
        const val SCHEME = "tortuga"
        const val HOST = "watch"

        /** A `tortuga://watch` link, or null for anything else (or one without a valid code). */
        fun parseLink(text: String): WatchInvite? {
            val trimmed = text.trim()
            val prefix = "$SCHEME://$HOST"
            if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
            val rest = trimmed.substring(prefix.length).removePrefix("/")
            if (!rest.startsWith("?")) return null
            val params = rest.substring(1).split('&').mapNotNull { pair ->
                val name = pair.substringBefore('=', missingDelimiterValue = "")
                if (name.isEmpty()) null else name to UriComponent.decode(pair.substringAfter('='))
            }.toMap()
            val code = params["code"]?.let(::normalizeCode) ?: return null
            val relay = params["relay"]?.let { WatchRelayEndpoint.parse(it) ?: return null } ?: WatchRelayEndpoint.HOSTED
            return WatchInvite(code, relay)
        }

        /**
         * What a join field holds: an invite link (its relay), or a code (joined on [relay]); null for neither. Spaces
         * and hyphens in a code are ignored and letters upper-cased.
         */
        fun parseInput(text: String, relay: WatchRelayEndpoint): WatchInvite? =
            parseLink(text) ?: normalizeCode(text)?.let { WatchInvite(it, relay) }

        /** [text] as a room code (6 symbols of the relays' alphabet, without separators), or null. */
        fun normalizeCode(text: String): String? = try {
            formatCode(text).replace("-", "")
        } catch (e: PartyException) {
            null
        }
    }
}
