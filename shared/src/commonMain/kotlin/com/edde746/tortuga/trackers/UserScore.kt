package com.edde746.tortuga.trackers

/**
 * One tracker's line in the user's own rating of a title ([TrackerCoordinator.userScores], [TrackerCoordinator.rate]).
 *
 * @param score the tracker's score of the title, 1–10, `null` for none; while a write is on its way or queued, the
 *   score it writes.
 */
data class UserScore(val service: TrackerService, val score: Int?, val state: UserScoreState)

enum class UserScoreState {
    /** Being read. */
    READING,

    /** Read: [UserScore.score] is the tracker's. */
    READ,

    /** The read failed: the tracker's score is unknown. */
    UNREADABLE,

    /** A write is on its way. */
    SAVING,

    /** The tracker took the write. */
    SAVED,

    /** The write failed, or the service asked to wait: it is queued and retried later. */
    QUEUED,

    /** The write failed and couldn't be queued. */
    FAILED,
}
