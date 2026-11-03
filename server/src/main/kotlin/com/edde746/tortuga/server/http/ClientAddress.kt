package com.edde746.tortuga.server.http

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * What every per-client limit counts this call's client as: the HTTP rate limits and the tracker sign-in relay's. It
 * is the address the call came from, behind a trusted proxy the last one in `X-Forwarded-For`
 * ([com.edde746.tortuga.server.ServerConfig.trustForwardedHeaders]), as [addressKey] counts it.
 */
val ApplicationCall.clientAddress: String get() = addressKey(request.origin.remoteAddress)

/**
 * What a client address ([remoteAddress]) is counted as: an IPv6 address by its /64, one subscriber's prefix (an
 * IPv4-mapped one as the IPv4 address), anything else as it is. Only literals are parsed; nothing is looked up.
 */
fun addressKey(remoteAddress: String): String {
    val literal = remoteAddress.removePrefix("[").substringBefore(']').substringBefore('%')
    val isLiteral = ':' in literal && literal.all { it == ':' || it == '.' || it.digitToIntOrNull(16) != null }
    if (!isLiteral) return remoteAddress
    val address = try {
        InetAddress.getByName(literal)
    } catch (e: UnknownHostException) {
        return remoteAddress
    }
    if (address !is Inet6Address) return address.hostAddress
    return InetAddress.getByAddress(address.address.copyOf().apply { fill(0, 8, 16) }).hostAddress
}
