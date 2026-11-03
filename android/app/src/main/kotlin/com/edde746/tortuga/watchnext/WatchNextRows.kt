package com.edde746.tortuga.watchnext

import android.media.tv.TvContract.WatchNextPrograms
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.homescreen.HomeScreenItem

/**
 * One row of Android TV's Watch Next table ([WatchNextPrograms]) as this app writes it, and reads it back to see what
 * changed. [key] (the internal provider id) is the profile's and the title's id ([watchNextKey]): Continue Watching has
 * one item per title.
 */
internal data class WatchNextProgram(
    val key: String,
    val type: Int,
    val watchNextType: Int,
    val title: String,
    val seasonNumber: String?,
    val episodeNumber: String?,
    val posterUri: String?,
    val posterAspectRatio: Int?,
    val durationMillis: Int,
    val lastPlaybackPositionMillis: Int,
    val lastEngagementMillis: Long,
    val intentUri: String,
)

/** A row's [WatchNextProgram.key]: `<profile id>/<title id>` (profile ids are UUIDs, without a slash). */
internal fun watchNextKey(profileId: String, id: String): String = "$profileId/$id"

/**
 * [this] item as its Watch Next row: an episode as [WatchNextPrograms.TYPE_TV_EPISODE] with its numbers, anything else
 * as [WatchNextPrograms.TYPE_MOVIE]; [WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE] when it resumes somewhere, else
 * [WatchNextPrograms.WATCH_NEXT_TYPE_NEXT] (the next episode, at its start); the poster in its own shape; the position
 * only with a duration. [intentUri] is what selecting the row starts: the item's link, in this app.
 */
internal fun HomeScreenItem.toWatchNextProgram(intentUri: String): WatchNextProgram {
    val episode = kind == HomeScreenItem.Kind.EPISODE
    val duration = durationMillis.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    return WatchNextProgram(
        key = watchNextKey(profileId, id),
        type = if (episode) WatchNextPrograms.TYPE_TV_EPISODE else WatchNextPrograms.TYPE_MOVIE,
        watchNextType = if (positionMillis > 0) WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE else WatchNextPrograms.WATCH_NEXT_TYPE_NEXT,
        title = title,
        seasonNumber = season?.takeIf { episode }?.toString(),
        episodeNumber = this.episode?.takeIf { episode }?.toString(),
        posterUri = posterUrl,
        posterAspectRatio = posterUrl?.let {
            when (posterShape) {
                PosterShape.POSTER -> WatchNextPrograms.ASPECT_RATIO_2_3
                PosterShape.SQUARE -> WatchNextPrograms.ASPECT_RATIO_1_1
                PosterShape.LANDSCAPE -> WatchNextPrograms.ASPECT_RATIO_16_9
            }
        },
        durationMillis = duration,
        lastPlaybackPositionMillis = if (duration > 0) positionMillis.coerceIn(0, Int.MAX_VALUE.toLong()).toInt() else 0,
        lastEngagementMillis = lastEngagedMillis,
        intentUri = intentUri,
    )
}

/**
 * A row of this app's in the Watch Next table as read back: its id, what it holds, and whether the home screen still
 * shows it ([WatchNextPrograms.COLUMN_BROWSABLE]: the launcher clears it when the user removes the row).
 */
internal data class PublishedRow(val id: Long, val program: WatchNextProgram, val browsable: Boolean)

/**
 * What a publish changes in the Watch Next table, and the user's removals in force after it ([removed]: each removed
 * item's key, with the last engagement it had when the user removed it).
 */
internal data class WatchNextPlan(
    val insert: List<WatchNextProgram>,
    val update: List<Pair<Long, WatchNextProgram>>,
    val delete: List<Long>,
    val removed: Map<String, Long>,
)

/**
 * The changes that make this app's Watch Next rows [rows] show [programs], [profileId]'s items (null for none: a
 * profile with a PIN, signed out), touching only what differs: a row whose values changed is updated in place (a saved
 * position updates its one row), a program without a row goes in, and a row whose item left (finished, removed from
 * Continue Watching, another profile's) goes out, as do a key's extra rows.
 *
 * The user's removals ([removed], updated in the result): a row the home screen no longer shows ([PublishedRow.browsable]
 * false) was removed by the user. It is deleted and its item recorded, and a recorded item stays off the home screen
 * until it is watched again, its last engagement moving past the one recorded. A record goes once its item leaves
 * [profileId]'s list; another profile's records stay for when it runs again.
 */
internal fun planWatchNext(
    profileId: String?,
    programs: List<WatchNextProgram>,
    rows: List<PublishedRow>,
    removed: Map<String, Long>,
): WatchNextPlan {
    val removals = removed.toMutableMap()
    for (row in rows) {
        if (!row.browsable) removals[row.program.key] = maxOf(removals[row.program.key] ?: Long.MIN_VALUE, row.program.lastEngagementMillis)
    }
    val keys = programs.mapTo(HashSet()) { it.key }
    if (profileId != null) {
        val prefix = watchNextKey(profileId, "")
        removals.keys.removeAll { it.startsWith(prefix) && it !in keys }
    }
    val shown = programs.filter { program ->
        val at = removals[program.key]
        if (at != null && program.lastEngagementMillis > at) removals.remove(program.key)
        program.key !in removals
    }
    val kept = HashMap<String, PublishedRow>()
    val delete = ArrayList<Long>()
    for (row in rows) {
        val key = row.program.key
        if (row.browsable && key in keys && key !in removals && key !in kept) kept[key] = row else delete += row.id
    }
    val insert = ArrayList<WatchNextProgram>()
    val update = ArrayList<Pair<Long, WatchNextProgram>>()
    for (program in shown) {
        val row = kept[program.key]
        when {
            row == null -> insert += program
            row.program != program -> update += row.id to program
        }
    }
    return WatchNextPlan(insert, update, delete, removals)
}
