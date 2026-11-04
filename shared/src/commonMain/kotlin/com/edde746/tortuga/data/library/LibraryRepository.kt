package com.edde746.tortuga.data.library

import com.edde746.tortuga.data.Db
import com.edde746.tortuga.data.Tx
import com.edde746.tortuga.data.db.Library_item
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.session.isEpisode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * A profile's library and watch progress, with stremio-core's semantics
 * (`models/ctx/update_library.rs`, `models/player.rs`) except where noted. Every change bumps `_mtime`, stamps a new
 * HLC and marks the item for sync; a call that changes nothing writes nothing. A write that knows the item's meta ends
 * with the one rule for where the item rests ([LibraryItemState.settled]). An item's poster is the addon's, never a file
 * on this device ([keptPoster]): the item syncs to the profile's other devices and outlives the downloads.
 */
class LibraryRepository internal constructor(private val db: Db) {
    fun observeItem(profileId: String, itemId: String): Flow<LibraryItem?> =
        db.observeOneOrNull(db.database.libraryItemQueries.selectLive(profileId, itemId)).map { it?.toModel() }

    /** The user's library (added and not removed), most recently changed first. */
    fun observeLibrary(profileId: String): Flow<List<LibraryItem>> =
        db.observeList(db.database.libraryItemQueries.selectLibrary(profileId)).map { rows -> rows.map { it.toModel() } }

    /**
     * The items with a resume position, most recently changed first: Continue Watching, without the series a new
     * episode brings back ([com.edde746.tortuga.presentation.library.continueWatching] adds them).
     */
    fun observeContinueWatching(profileId: String, limit: Int): Flow<List<LibraryItem>> =
        db.observeList(db.database.libraryItemQueries.selectContinueWatching(profileId, limit.toLong()))
            .map { rows -> rows.map { it.toModel() } }

    /** The profile's titles in the library or watched at least once, by id: what a catalog card shows of its title. */
    fun observeMarks(profileId: String): Flow<Map<String, TitleMark>> =
        db.observeList(db.database.libraryItemQueries.selectMarks(profileId))
            .map { rows -> rows.associate { it.item_id to TitleMark(inLibrary = !it.removed, watched = it.times_watched > 0) } }

    /**
     * The profile's titles watched at least once or with a video marked watched, in the library or not, most recently
     * changed first.
     */
    fun observeWatched(profileId: String): Flow<List<LibraryItem>> =
        db.observeList(db.database.libraryItemQueries.selectWatched(profileId)).map { rows -> rows.map { it.toModel() } }

    suspend fun item(profileId: String, itemId: String): LibraryItem? =
        db.read { libraryItemQueries.selectLive(profileId, itemId).executeAsOneOrNull()?.toModel() }

    /**
     * Adds the item to the library, creating it or refreshing its meta fields. Returns whether it entered the library:
     * false when it was in it already (an item only in Continue Watching wasn't). [at] is the change's time (epoch
     * milliseconds, what the library sorts by); null is now.
     */
    suspend fun addToLibrary(profileId: String, meta: MetaSnapshot, at: Long? = null): Boolean = db.write(at) {
        val existing = liveRow(profileId, meta.id)
        save((existing?.let { withMeta(it, meta) } ?: newRow(profileId, meta)).copy(removed = false, temp = false))
        existing == null || existing.removed
    }

    /**
     * Refreshes the item's meta fields, or creates it as a temporary item (not in the library, shown in Continue
     * Watching once it has progress). Playback calls this before [recordProgress]. [at] as for [addToLibrary].
     */
    suspend fun upsertFromMeta(profileId: String, meta: MetaSnapshot, at: Long? = null): LibraryItem = db.write(at) {
        save(liveRow(profileId, meta.id)?.let { withMeta(it, meta) } ?: newRow(profileId, meta))
    }.toModel()

    /**
     * Removes the item from the library, keeping its watch state (Stremio's `RemoveFromLibrary`). Returns whether it
     * left the library (it was in it).
     */
    suspend fun removeFromLibrary(profileId: String, itemId: String): Boolean = db.write {
        val row = liveRow(profileId, itemId) ?: return@write false
        save(row.copy(removed = true, temp = false))
        !row.removed
    }

    /** Deletes the item and its watch state on every device (a tombstone). */
    suspend fun delete(profileId: String, itemId: String) {
        db.write {
            val row = liveRow(profileId, itemId) ?: return@write
            q.libraryItemQueries.upsert(row.copy(hlc = tick().toString(), dirty = true, deleted = true))
        }
    }

    /**
     * Records playback of [videoId] at [timeMs] of [durationMs] (stremio-core `ActionPlayer::TimeChanged`). Call it
     * while playing and on pause, stop and exit; report seeks with [recordSeek] first, or the jump counts as
     * watched time.
     *
     * - A new video moves the pointer, and its time watched and watched flag start over.
     * - Otherwise the time played since the last update is added to time watched and the overall, and the position
     *   only moves forward.
     * - Unlike Stremio, the resume position moves to a new video right away, and the overall counts each played
     *   millisecond once (Stremio adds a video's time watched to it again when the pointer moves).
     * - The first update [watched] (the playback counts the video as watched, `PlaybackRules.countsAsWatched` with the
     *   profile's watched threshold) flags the video, `timesWatched` goes up once and, given [meta], the video's watched
     *   mark is set ([WatchedBitField]; not over a stored field that can't be read, which stays as it is);
     *   [ProgressUpdate.crossedWatchedThreshold] reports it. An episode then moves the pointer on in the same write
     *   ([LibraryItemState.settled]).
     * - Unlike Stremio, a rewatch (an episode of [meta] already watched that the pointer isn't on) leaves the pointer
     *   where it is: Continue Watching keeps the episode it had, and the rewatch's position is the player's alone.
     *
     * Returns `null` when the item doesn't exist ([upsertFromMeta] creates it). [at] as for [addToLibrary].
     */
    suspend fun recordProgress(
        profileId: String,
        itemId: String,
        videoId: String,
        timeMs: Long,
        durationMs: Long,
        meta: MetaItem? = null,
        watched: Boolean = false,
        at: Long? = null,
    ): ProgressUpdate? =
        db.write(at) {
            val row = liveRow(profileId, itemId) ?: return@write null
            if (meta != null && row.video_id != videoId && meta.isEpisode(videoId) && WatchedBitField.of(row.watched, meta).isWatched(videoId)) {
                return@write ProgressUpdate(save(row.copy(last_watched = now()), meta).toModel(), crossedWatchedThreshold = false)
            }
            var next = row.copy(last_watched = now())
            next = if (row.video_id != videoId) {
                next.copy(
                    video_id = videoId,
                    time_watched = 0,
                    flagged_watched = 0,
                    time_offset = timeMs.coerceAtLeast(0),
                    duration = durationMs.coerceAtLeast(0),
                )
            } else {
                val played = (timeMs - row.time_offset).coerceAtLeast(0)
                next.copy(
                    time_watched = row.time_watched + played,
                    overall_time_watched = row.overall_time_watched + played,
                ).let { if (timeMs > row.time_offset) it.copy(time_offset = timeMs, duration = durationMs.coerceAtLeast(0)) else it }
            }
            val crossed = next.flagged_watched == 0L && watched
            if (crossed) {
                next = next.copy(flagged_watched = 1, times_watched = next.times_watched + 1)
                if (meta != null && meta.videos.isNotEmpty()) {
                    marksToChange(next, meta)?.let { marks -> next = next.copy(watched = marks.apply { set(videoId, true) }.serialize()) }
                }
            }
            if (next.temp && next.times_watched == 0L) next = next.copy(removed = true)
            if (next.removed) next = next.copy(temp = true)
            ProgressUpdate(save(next, meta).toModel(), crossed)
        }

    /**
     * A seek in [videoId] to [timeMs] (stremio-core `ActionPlayer::Seek`): moves the position without counting watched
     * time, when the pointer is on [videoId] (a rewatch leaves it where it was, [recordProgress]).
     */
    suspend fun recordSeek(profileId: String, itemId: String, videoId: String, timeMs: Long, durationMs: Long) {
        mutate(profileId, itemId) {
            if (it.video_id != videoId) {
                it
            } else {
                it.copy(last_watched = now(), time_offset = timeMs.coerceAtLeast(0), duration = durationMs.coerceAtLeast(0))
            }
        }
    }

    /**
     * Marks the item watched or unwatched (Stremio's `MetaItemMarkAsWatched`): watched counts one more watch, clears
     * the resume position and creates the item if needed; unwatched resets the count and the current video's watched
     * flag, so playing it again counts as a watch. Per-video marks are left alone ([markAllWatched] sets them too).
     * Returns whether it marked: false for unwatched without an item.
     */
    suspend fun markWatched(profileId: String, meta: MetaSnapshot, watched: Boolean): Boolean = db.write {
        val row = liveRow(profileId, meta.id) ?: if (watched) newRow(profileId, meta) else return@write false
        save(row.markedWatched(watched, now()))
        true
    }

    /**
     * Marks one video of [meta] watched or unwatched ([WatchedBitField]; stremio-core `MarkVideoAsWatched`), creating
     * the item (not in the library) to mark one watched. Marking watched moves `lastWatched` up to the video's
     * release date if that is later; an item that rests on a video now watched moves on ([LibraryItemState.settled]).
     * Unmarking the video the item rests on clears its watched flag too, so playing it again counts as a watch.
     * Returns whether the video's mark changed: never over a stored field that can't be read ([marksToChange]).
     */
    suspend fun markVideoWatched(profileId: String, meta: MetaItem, videoId: String, watched: Boolean): Boolean = db.write {
        val video = meta.videos.firstOrNull { it.id == videoId } ?: return@write false
        val row = rowForMarks(profileId, meta, watched) ?: return@write false
        val marks = marksToChange(row, meta) ?: return@write false
        val changed = marks.isWatched(videoId) != watched
        marks.set(videoId, watched)
        var next = row.copy(watched = marks.serialize())
        if (watched) next = next.copy(last_watched = laterOf(next.last_watched, video.released))
        if (!watched && row.video_id == videoId) next = next.copy(flagged_watched = 0)
        save(next, meta)
        changed
    }

    /**
     * Marks the videos of [season] (0 for specials) watched or unwatched (stremio-core `MarkSeasonAsWatched`). Unlike
     * Stremio, marking watched leaves videos released after now unmarked, so an episode doesn't arrive already
     * watched. An item that rests on a video now watched moves on past the watched videos ([LibraryItemState.settled]);
     * one resting on a video now unwatched loses its watched flag ([markVideoWatched]).
     * Returns the videos whose mark changed, in the season's order: none over a stored field that can't be read
     * ([marksToChange]).
     */
    suspend fun markSeasonWatched(profileId: String, meta: MetaItem, season: Int, watched: Boolean): List<String> = db.write {
        val videos = meta.videosInSeason(season).filter { !watched || isReleased(it) }
        if (videos.isEmpty()) return@write emptyList()
        val row = rowForMarks(profileId, meta, watched) ?: return@write emptyList()
        val marks = marksToChange(row, meta) ?: return@write emptyList()
        val changed = videos.filter { marks.isWatched(it.id) != watched }.map { it.id }
        videos.forEach { marks.set(it.id, watched) }
        var next = row.copy(watched = marks.serialize())
        if (watched) next = next.copy(last_watched = laterOf(next.last_watched, videos.last().released))
        if (!watched && videos.any { it.id == row.video_id }) next = next.copy(flagged_watched = 0)
        save(next, meta)
        changed
    }

    /**
     * Marks the whole item watched or unwatched: [markWatched], plus every video's mark for an item with videos
     * (only videos released by now when marking watched, as [markSeasonWatched]); a stored field that can't be read
     * keeps its marks ([marksToChange]).
     */
    suspend fun markAllWatched(profileId: String, meta: MetaItem, watched: Boolean): Boolean = db.write {
        val row = rowForMarks(profileId, meta, watched) ?: return@write false
        var next = row.markedWatched(watched, now())
        if (meta.videos.isNotEmpty()) {
            marksToChange(row, meta)?.let { marks ->
                meta.videos.filter { !watched || isReleased(it) }.forEach { marks.set(it.id, watched) }
                next = next.copy(watched = marks.serialize())
            }
        }
        save(next, meta)
        true
    }

    /**
     * [row]'s marks of [meta]'s videos to change; null when its stored field can't be read, which stays as it is
     * (logged): marks set over none would replace every mark it holds, here and, through sync, everywhere.
     */
    private fun marksToChange(row: Library_item, meta: MetaItem): WatchedBitField? {
        val marks = WatchedBitField.readable(row.watched, meta)
        if (marks == null) Log.e("library") { "${row.item_id}: its watched field can't be read, and a mark doesn't replace it" }
        return marks
    }

    /** Stores Stremio's watched-videos bitfield ([LibraryItemState.watchedVideos]) as given. */
    suspend fun setWatchedVideos(profileId: String, itemId: String, watchedVideos: String?) {
        mutate(profileId, itemId) { it.copy(watched = watchedVideos) }
    }

    /**
     * Takes the item out of Continue Watching as Stremio's dismiss does (`RewindLibraryItem` and
     * `DismissNotificationItem`): clears the resume position and moves `lastWatched` to now, so the episodes out by now
     * are no longer new and a series comes back only for one out later
     * ([com.edde746.tortuga.presentation.calendar.newEpisodesUpNext]).
     */
    suspend fun rewind(profileId: String, itemId: String) {
        mutate(profileId, itemId) { it.copy(time_offset = 0, last_watched = now()) }
    }

    /**
     * Moves the pointer to [videoId] of [meta] (stremio-core `advance_to_video`) as the next one is asked
     * for or the current one is left past its credits: it starts unwatched at position 1 ms, which keeps the item in
     * Continue Watching; a video already watched moves on past it ([LibraryItemState.settled]).
     */
    suspend fun advanceToVideo(profileId: String, meta: MetaItem, videoId: String) {
        db.write {
            val row = liveRow(profileId, meta.id) ?: return@write
            save(row.advancedTo(videoId), meta)
        }
    }

    /** Holds the item of [meta] to the one rule ([LibraryItemState.settled]); writes only a change. */
    suspend fun settle(profileId: String, meta: MetaItem) {
        db.write {
            val row = liveRow(profileId, meta.id) ?: return@write
            save(row, meta)
        }
    }

    /**
     * Holds Continue Watching to the one rule ([LibraryItemState.settled]) for what writers without the meta stored (a
     * sync from another device's older build, a file import, a state an older build left here): each of its episode
     * items with watched marks is [settle]d with its meta from [metaOf], asked once per item it answers for; an item it
     * has no meta for (no addon answered: offline, a timeout) is asked again with the next change. Runs until cancelled;
     * a change whose settling throws is logged, and the next change settles what is left.
     */
    suspend fun settleContinueWatching(profileId: String, metaOf: suspend (type: String, id: String) -> MetaItem?) {
        val metas = HashMap<String, MetaItem>()
        db.observeList(db.database.libraryItemQueries.selectContinueWatching(profileId, Long.MAX_VALUE)).collect { rows ->
            try {
                for (row in rows) {
                    if (row.video_id == null || row.video_id == row.item_id || row.watched == null) continue
                    val meta = metas[row.item_id] ?: metaOf(row.type, row.item_id)?.also { metas[row.item_id] = it } ?: continue
                    val state = row.toModel().state
                    if (state.settled(meta, Instant.fromEpochMilliseconds(db.wallClock())) != state) settle(profileId, meta)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("library", e) { "settling Continue Watching failed (${e::class.simpleName})" }
            }
        }
    }

    private suspend fun mutate(profileId: String, itemId: String, change: Tx.(Library_item) -> Library_item) {
        db.write {
            val row = liveRow(profileId, itemId) ?: return@write
            save(change(row))
        }
    }

    private fun Tx.liveRow(profileId: String, itemId: String) =
        q.libraryItemQueries.selectLive(profileId, itemId).executeAsOneOrNull()

    /** The item to change marks on, with [meta]'s fields; created (not in the library) only to mark something watched. */
    private fun Tx.rowForMarks(profileId: String, meta: MetaItem, watched: Boolean): Library_item? {
        val snapshot = meta.librarySnapshot()
        return liveRow(profileId, meta.id)?.let { withMeta(it, snapshot) } ?: if (watched) newRow(profileId, snapshot) else null
    }

    /** [row] with [meta]'s fields, its poster as [keptPoster] keeps it. */
    private fun Tx.withMeta(row: Library_item, meta: MetaSnapshot) =
        row.copy(type = meta.type, name = meta.name, poster = keptPoster(meta, row.poster), poster_shape = meta.posterShape)

    /**
     * The poster the item keeps of [meta]: its own, unless it is a file on this device (the artwork a download saved,
     * which the download manager's saved meta and downloads give as `file:` URLs): then the item's [current] one, else
     * the addon's poster the title's downloads were saved with, else none.
     */
    private fun Tx.keptPoster(meta: MetaSnapshot, current: String?): String? {
        fun String?.isFile() = this?.startsWith("file:", ignoreCase = true) == true
        if (!meta.poster.isFile()) return meta.poster
        return current?.takeUnless { it.isFile() }
            ?: q.libraryItemQueries.selectDownloadPoster(meta.id).executeAsOneOrNull()
    }

    /** Out by now; a video without a date counts as out. */
    private fun Tx.isReleased(video: Video): Boolean = video.released?.let { it.toEpochMilliseconds() <= now() } ?: true

    /**
     * A new temporary item (Stremio's `LibraryItem::from(MetaItemPreview)`). A tombstone of the same item is
     * replaced, keeping its record id and server version so the revival pushes on top of the deletion.
     */
    private fun Tx.newRow(profileId: String, meta: MetaSnapshot): Library_item {
        val now = now()
        val tombstone = q.libraryItemQueries.select(profileId, meta.id).executeAsOneOrNull()
        return Library_item(
            profile_id = profileId,
            item_id = meta.id,
            type = meta.type,
            name = meta.name,
            poster = keptPoster(meta, current = null),
            poster_shape = meta.posterShape,
            removed = true,
            temp = true,
            ctime = now,
            mtime = now,
            last_watched = now,
            time_watched = 0,
            time_offset = 0,
            overall_time_watched = 0,
            times_watched = 0,
            flagged_watched = 0,
            duration = 0,
            video_id = null,
            watched = null,
            no_notif = false,
            record_id = tombstone?.record_id ?: RecordKey.LibraryItem(profileId, meta.id).recordId(),
            hlc = "",
            server_version = tombstone?.server_version,
            dirty = true,
            deleted = false,
        )
    }

    /**
     * Writes [row] as a local change, held to the one rule ([LibraryItemState.settled]) when the write knows the item's
     * [meta], unless its content equals the stored row (Stremio's `eq_no_mtime`), and returns the stored row.
     */
    private fun Tx.save(row: Library_item, meta: MetaItem? = null): Library_item {
        val settled = if (meta == null) row else row.settled(meta, now())
        val stored = q.libraryItemQueries.select(settled.profile_id, settled.item_id).executeAsOneOrNull()
        if (stored != null && !stored.deleted && stored.sameContent(settled)) return stored
        val written = settled.copy(mtime = now(), hlc = tick().toString(), dirty = true, deleted = false)
        q.libraryItemQueries.upsert(written)
        return written
    }
}

/** stremio-core `LibraryItem::mark_as_watched`, at [now]; unwatched also clears the current video's watched flag. */
private fun Library_item.markedWatched(watched: Boolean, now: Long) =
    if (watched) copy(times_watched = times_watched + 1, last_watched = now, time_offset = 0) else copy(times_watched = 0, flagged_watched = 0)

/** stremio-core `advance_to_video`, without adding the time watched to the overall again (see recordProgress). */
private fun Library_item.advancedTo(videoId: String) =
    copy(video_id = videoId, time_watched = 0, flagged_watched = 0, time_offset = 1)

/** [LibraryItemState.settled] on a stored row, at [now] (epoch milliseconds). */
private fun Library_item.settled(meta: MetaItem, now: Long): Library_item {
    val state = toModel().state.settled(meta, Instant.fromEpochMilliseconds(now))
    return copy(video_id = state.videoId, time_watched = state.timeWatched, time_offset = state.timeOffset, flagged_watched = state.flaggedWatched)
}

/** `lastWatched` after marking a video released at [released] watched (stremio-core `mark_video_as_watched`). */
private fun laterOf(lastWatched: Long?, released: Instant?): Long? {
    val releasedMs = released?.toEpochMilliseconds()
    return when {
        lastWatched == null -> releasedMs
        releasedMs != null && lastWatched < releasedMs -> releasedMs
        else -> lastWatched
    }
}

/** Equal apart from `_mtime` and the sync columns. */
private fun Library_item.sameContent(other: Library_item) =
    copy(mtime = 0, hlc = "", server_version = null, dirty = false) ==
        other.copy(mtime = 0, hlc = "", server_version = null, dirty = false)

internal fun Library_item.toModel() = LibraryItem(
    id = item_id,
    type = type,
    name = name,
    poster = poster,
    posterShape = poster_shape,
    removed = removed,
    temp = temp,
    ctime = ctime,
    mtime = mtime,
    state = LibraryItemState(
        lastWatched = last_watched,
        timeWatched = time_watched,
        timeOffset = time_offset,
        overallTimeWatched = overall_time_watched,
        timesWatched = times_watched,
        flaggedWatched = flagged_watched,
        duration = duration,
        videoId = video_id,
        watchedVideos = watched,
        noNotif = no_notif,
    ),
)
