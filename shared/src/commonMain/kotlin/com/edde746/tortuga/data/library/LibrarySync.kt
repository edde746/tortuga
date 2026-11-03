package com.edde746.tortuga.data.library

import com.edde746.tortuga.data.db.Library_item
import com.edde746.tortuga.data.sync.LibraryItemBody
import com.edde746.tortuga.data.sync.LibraryStateBody

/**
 * Merges two versions of one library item: the one with the later HLC wins, except that progress
 * keeps the furthest `timeWatched` (of the same video; a newer pointer to another video restarts it), the latest
 * `lastWatched` and the larger `overallTimeWatched`, so time played on a device that lost the race still counts.
 */
internal fun mergeLibraryItem(winner: LibraryItemBody, loser: LibraryItemBody): LibraryItemBody {
    val w = winner.state
    val l = loser.state
    val state = w.copy(
        timeWatched = if (w.videoId == l.videoId) maxOf(w.timeWatched, l.timeWatched) else w.timeWatched,
        lastWatched = listOfNotNull(w.lastWatched, l.lastWatched).maxOrNull(),
        overallTimeWatched = maxOf(w.overallTimeWatched, l.overallTimeWatched),
    )
    return winner.copy(state = state)
}

internal fun Library_item.toBody() = LibraryItemBody(
    type = type,
    name = name,
    poster = poster,
    posterShape = poster_shape,
    removed = removed,
    temp = temp,
    ctime = ctime,
    mtime = mtime,
    state = LibraryStateBody(
        lastWatched = last_watched,
        timeWatched = time_watched,
        timeOffset = time_offset,
        overallTimeWatched = overall_time_watched,
        timesWatched = times_watched,
        flaggedWatched = flagged_watched,
        duration = duration,
        videoId = video_id,
        watched = watched,
        noNotif = no_notif,
    ),
)

internal fun LibraryItemBody.toRow(
    profileId: String,
    itemId: String,
    recordId: String,
    hlc: String,
    serverVersion: Long?,
    dirty: Boolean,
) = Library_item(
    profile_id = profileId,
    item_id = itemId,
    type = type,
    name = name,
    poster = poster,
    poster_shape = posterShape,
    removed = removed,
    temp = temp,
    ctime = ctime,
    mtime = mtime,
    last_watched = state.lastWatched,
    time_watched = state.timeWatched,
    time_offset = state.timeOffset,
    overall_time_watched = state.overallTimeWatched,
    times_watched = state.timesWatched,
    flagged_watched = state.flaggedWatched,
    duration = state.duration,
    video_id = state.videoId,
    watched = state.watched,
    no_notif = state.noNotif,
    record_id = recordId,
    hlc = hlc,
    server_version = serverVersion,
    dirty = dirty,
    deleted = false,
)
