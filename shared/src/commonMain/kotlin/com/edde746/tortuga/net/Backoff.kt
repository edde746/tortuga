package com.edde746.tortuga.net

import kotlin.random.Random

/**
 * Waits before a retry, randomized so that the clients that failed together (a server restart, one rate limit window)
 * don't all come back at the same moment: full jitter, as in AWS's "Exponential Backoff and Jitter" (2015).
 */
internal object Backoff {
    /**
     * The wait before retry [attempt] (1 for the first after a failure): uniformly random from 0 to
     * min([maxMillis], [minMillis] · 2^(attempt − 1)).
     */
    fun fullJitter(attempt: Int, minMillis: Long, maxMillis: Long, random: Random = Random): Long {
        val exponent = (attempt - 1).coerceIn(0, MAX_EXPONENT)
        return random.nextLong((minMillis shl exponent).coerceAtMost(maxMillis) + 1)
    }

    /**
     * Like [fullJitter], but from half the bound to the bound: the wait never drops below half of it, so a retry that
     * fails at once still slows down as the bound grows.
     */
    fun equalJitter(attempt: Int, minMillis: Long, maxMillis: Long, random: Random = Random): Long {
        val bound = (minMillis shl (attempt - 1).coerceIn(0, MAX_EXPONENT)).coerceAtMost(maxMillis)
        return bound / 2 + random.nextLong(bound - bound / 2 + 1)
    }

    /** The wait a server asked for ([retryAfterMillis], its `Retry-After`), spread over up to twice as long. */
    fun afterRetryAfter(retryAfterMillis: Long, random: Random = Random): Long =
        retryAfterMillis + random.nextLong(retryAfterMillis + 1)

    /**
     * The wait before the next poll after [errors] failures in a row: [intervalMillis] while the polls succeed; after a
     * failure, [intervalMillis] plus [fullJitter] of the interval, growing per failure, at most [maxMillis] in all.
     */
    fun poll(errors: Int, intervalMillis: Long, maxMillis: Long, random: Random = Random): Long =
        if (errors <= 0) intervalMillis else intervalMillis + fullJitter(errors, intervalMillis, maxMillis - intervalMillis, random)

    /** 2^20 times the first wait is past any cap the callers use, and far from overflowing. */
    private const val MAX_EXPONENT = 20
}
