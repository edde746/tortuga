package com.edde746.tortuga.sync.account

import com.edde746.tortuga.sync.account.signin.SyncServers
import io.ktor.http.parseUrl

/**
 * A sync server's base URL, checked once where it comes in ([parse]: typed, scanned, a launch's setting): [value] is
 * canonical (`https://` assumed, no default port, no trailing slash), so two of them are equal when they name the same
 * server.
 */
class ServerUrl private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is ServerUrl && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        /** The hosted instance ([SyncServers.HOSTED]). */
        val HOSTED: ServerUrl = parse(SyncServers.HOSTED)

        /**
         * [input] as a server base URL: `https://` is assumed when no scheme is given; the scheme, host, port and path
         * are kept, a trailing slash dropped. Paths are allowed, for servers behind a reverse proxy under a prefix.
         * Plain `http` is only for a host on this device or its network ([isLocalHost]), the same rule on every
         * platform: anywhere else the sign-in, the session's tokens and the synced data would cross the internet
         * unencrypted.
         *
         * @throws AccountException with [AccountError.InvalidServerUrl] for anything but an http(s) URL with a host and
         *   no user info, query or fragment, and [AccountError.InsecureServerUrl] for plain `http` to any other host.
         */
        @Throws(AccountException::class)
        fun parse(input: String): ServerUrl {
            val trimmed = input.trim()
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = parseUrl(withScheme)
            if (url == null || url.protocol.name !in setOf("http", "https") || url.host.isBlank() ||
                url.user != null || url.encodedQuery.isNotEmpty() || url.encodedFragment.isNotEmpty() ||
                withScheme.substringAfter("://").substringBefore('/').contains('@')
            ) {
                throw AccountException(AccountError.InvalidServerUrl)
            }
            if (url.protocol.name == "http" && !isLocalHost(url.host)) throw AccountException(AccountError.InsecureServerUrl)
            val port = if (url.specifiedPort == 0 || url.specifiedPort == url.protocol.defaultPort) "" else ":${url.specifiedPort}"
            return ServerUrl("${url.protocol.name}://${url.host}$port${url.encodedPath.trimEnd('/')}")
        }
    }
}

/**
 * Whether [host] (an IPv6 address in brackets, as Ktor gives it) is this device or on its network, judged as written,
 * never by what the name resolves to: a loopback, private (RFC 1918; IPv6 unique local), link-local or shared address
 * (RFC 6598's 100.64.0.0/10, where Tailscale puts its devices), `localhost`, a `.local` (mDNS) name or a name without
 * a dot. The names are the ones Apple's App Transport Security lets the app reach over plain http
 * (`NSAllowsLocalNetworking`).
 */
internal fun isLocalHost(host: String): Boolean {
    val name = host.lowercase().removeSuffix(".")
    if (name.startsWith('[')) {
        val address = name.removeSurrounding("[", "]")
        val first = address.substringBefore(':').takeIf { it.length in 1..4 }?.toIntOrNull(16) ?: return address == "::1"
        return first and 0xFE00 == 0xFC00 || first and 0xFFC0 == 0xFE80
    }
    val octets = name.split('.')
    if (octets.size == 4 && octets.all { it.length in 1..3 && it.all(Char::isDigit) }) {
        val (a, b) = octets.map { it.toInt() }
        return a == 127 || a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168 || a == 169 && b == 254 ||
            a == 100 && b in 64..127
    }
    return '.' !in name || name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local")
}
