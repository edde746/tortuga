package com.edde746.tortuga.trackers

/**
 * One tracker's line in a title's Watchlist action ([TrackerCoordinator.watchlist]).
 *
 * @param listed whether the title is on the tracker's list, as far as the app knows: the list's pages the app read and
 *   the user's writes (while one is on its way or queued, what it writes); `null` while that isn't known.
 */
data class WatchlistLine(val service: TrackerService, val listed: Boolean?, val state: WatchlistState)

/** Where the user's last change to a title's place on one tracker's list stands. */
enum class WatchlistState {
    /** No change on its way: [WatchlistLine.listed] is what the app knows of the list. */
    IDLE,

    /** A write is on its way. */
    SAVING,

    /** The write failed, or the service asked to wait: it is queued and retried later. */
    QUEUED,

    /** The write failed and couldn't be queued, or its retries were given up on: [WatchlistLine.listed] went back. */
    FAILED,
}
