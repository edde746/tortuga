package com.edde746.tortuga.server.http

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readString

/**
 * What the server reads of a token endpoint's answer (Apple's code exchange, a tracker's code exchange, refresh or
 * revoke, GitHub's code exchange, token reset and email list): an access and a refresh token, an expiry, a scope and at
 * most an ID token or a user's record come to a few KiB. Anything longer isn't one.
 */
const val MAX_TOKEN_ANSWER_BYTES = 64L * 1024

/**
 * [this] answer's body as UTF-8 text, at most [maxBytes] of it: a longer body fails with an [IllegalStateException]
 * naming [endpoint] and the cap, its rest cancelled. Call it inside `HttpStatement.execute` (`prepareGet`,
 * `prepareForm`), where the body streams from the engine: `get` and `submitForm` return only once the whole body is in
 * memory.
 *
 * `bodyAsChannel()` is a copy of the body that a coroutine of the client fills, at most 1 MiB ahead of the reader,
 * and `execute`'s cleanup cancels the engine's body but not that copy. Left uncancelled with its buffer full, the copy
 * keeps the coroutine suspended, holding its buffer, as long as the client lives; so the copy is cancelled here.
 */
suspend fun HttpResponse.bodyAsTextAtMost(endpoint: String, maxBytes: Long): String {
    val channel = bodyAsChannel()
    val body = try {
        channel.readBuffer(maxBytes + 1)
    } finally {
        channel.cancel()
    }
    check(body.size <= maxBytes) { "$endpoint answered more than $maxBytes bytes" }
    return body.readString()
}
