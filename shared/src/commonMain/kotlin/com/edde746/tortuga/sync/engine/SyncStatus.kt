package com.edde746.tortuga.sync.engine

import com.edde746.tortuga.strings.T

/**
 * What the sync engine is doing, for a settings row or a status badge: [kind] to branch on, [text] preformatted in
 * the user's language.
 *
 * @property lastSyncedAtMillis when a sync last completed on this device (this process), or null.
 */
data class SyncStatus(val kind: Kind, val text: String, val lastSyncedAtMillis: Long?) {
    /** The engine's state; only [Error] has a reason ([Failure]). */
    sealed interface Kind {
        /** Everything is pushed and pulled as of [lastSyncedAtMillis]. */
        data object Idle : Kind

        data object Syncing : Kind

        /**
         * The last attempt failed for another reason than the connection: [failure]. [Failure.SERVER],
         * [Failure.RATE_LIMITED] and [Failure.UNKNOWN] are retried with backoff. The others aren't retried on a timer:
         * [Failure.QUOTA_EXCEEDED], [Failure.TOO_LARGE] and [Failure.ADDONS_TOO_LARGE] wait for the user's "Sync
         * now", a return to the foreground that pulls, or a restart; [Failure.CLOCK_WRONG] waits for the clocks to
         * agree (this device's clock reaching the refused records' time) or a restart.
         */
        data class Error(val failure: Failure) : Kind

        /** The server couldn't be reached; local changes wait and are pushed once it can. */
        data object Offline : Kind

        data object SignedOut : Kind

        /** A state the user should see without looking: nothing syncs until something changes. */
        val needsAttention: Boolean get() = when (this) {
            is Error, Offline -> true
            Idle, Syncing, SignedOut -> false
        }
    }

    enum class Failure {
        SERVER,
        RATE_LIMITED,
        QUOTA_EXCEEDED,
        TOO_LARGE,

        /** A profile's addon list is over the server's record size limit: it stays on this device until addons go. */
        ADDONS_TOO_LARGE,

        /**
         * Records from another device were refused because their time is more than a day ahead of this device's
         * clock: this clock or that device's is wrong. They apply once the clocks agree.
         */
        CLOCK_WRONG,
        UNKNOWN,
    }

    internal companion object {
        fun of(kind: Kind, lastSyncedAtMillis: Long?): SyncStatus = SyncStatus(
            kind,
            when (kind) {
                Kind.Idle -> T.account.status.idle
                Kind.Syncing -> T.account.status.syncing
                Kind.Offline -> T.account.status.offline
                Kind.SignedOut -> T.account.status.signedOut
                is Kind.Error -> when (kind.failure) {
                    Failure.SERVER -> T.account.status.failed.server
                    Failure.RATE_LIMITED -> T.account.status.failed.rateLimited
                    Failure.QUOTA_EXCEEDED -> T.account.status.failed.quotaExceeded
                    Failure.TOO_LARGE -> T.account.status.failed.tooLarge
                    Failure.ADDONS_TOO_LARGE -> T.account.status.failed.addonsTooLarge
                    Failure.CLOCK_WRONG -> T.account.status.failed.clockWrong
                    Failure.UNKNOWN -> T.account.status.failed.unknown
                }
            },
            lastSyncedAtMillis,
        )
    }
}
