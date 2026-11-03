package com.edde746.tortuga.playback.resolve

import com.edde746.tortuga.strings.T

/** The outcome of [StreamResolver.resolve] and [StreamResolver.resolveHead]. Cancellation isn't one: it throws. */
sealed interface ResolveResult {
    /**
     * [stream] can play. [pendingHash], from [StreamResolver.resolveHead] only, makes the tail request for the hash
     * [stream] lacks and returns it, or null when the server's answer allows none; null when there is nothing to ask.
     */
    data class Resolved(val stream: ResolvedStream, val pendingHash: (suspend () -> String?)? = null) : ResolveResult
    data class Failed(val failure: ResolveFailure) : ResolveResult
}

/**
 * Why a stream couldn't be resolved. None of these carry an exception message: HTTP stacks put the URL, and so the
 * API key, in theirs.
 */
sealed interface ResolveFailure {
    /** The final answer (after redirects) was an HTTP error. */
    data class Http(val status: Int) : ResolveFailure

    /** No answer within the resolve timeout (connect and first byte included). */
    data object Timeout : ResolveFailure

    /**
     * The connection failed (DNS, refused, reset, TLS).
     * @param kind the exception's class name, for diagnostics (`UnknownHostException`, `SSLHandshakeException`, …).
     */
    data class Network(val kind: String) : ResolveFailure

    /** Not an absolute `http`/`https` URL, or a redirect pointed at one that isn't. */
    data object UnsupportedUrl : ResolveFailure

    /** A redirect loop: more than [StreamResolver.MAX_REDIRECTS] redirects (or the client's own limit) in a row. */
    data object TooManyRedirects : ResolveFailure

    /** The resolver's own code failed, not the server or the connection: a bug, logged with its stack trace. */
    data object Unexpected : ResolveFailure
}

/** The failure as one line for the player's error view. */
val ResolveFailure.displayText: String
    get() = when (this) {
        is ResolveFailure.Http -> T.streams.resolveFailed.http(status.toString())
        ResolveFailure.Timeout -> T.streams.resolveFailed.timeout
        is ResolveFailure.Network -> T.streams.resolveFailed.network
        ResolveFailure.UnsupportedUrl -> T.streams.resolveFailed.unsupportedUrl
        ResolveFailure.TooManyRedirects -> T.streams.resolveFailed.tooManyRedirects
        ResolveFailure.Unexpected -> T.streams.resolveFailed.unexpected
    }
