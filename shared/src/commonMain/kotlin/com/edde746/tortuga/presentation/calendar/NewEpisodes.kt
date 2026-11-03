package com.edde746.tortuga.presentation.calendar

import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.playback.session.isEpisode
import com.edde746.tortuga.playback.session.nextUnwatched
import kotlin.time.Instant

/** On the calendar: in the library (stremio-core's calendar asks for every item not removed and not temporary). */
internal val LibraryItem.onCalendar: Boolean
    get() = !removed && !temp

/**
 * Its new episodes are counted (stremio-core `LibraryItem::should_pull_notifications`): a series or other episodic
 * title in the library, not a live channel, whose notifications for new videos are on ([LibraryItemState.noNotif]
 * false). The checks on behaviour hints (live, a default video) read the addon's meta ([newEpisodeCount]): the library
 * keeps no hints.
 */
internal val LibraryItem.followsNewEpisodes: Boolean
    get() = !state.noNotif && type != "movie" && type != "other" && type != "tv" && !removed && !temp

/**
 * How many videos of [meta] are new to [item] (stremio-core `update_notification_items`): released after the item was
 * last watched and by [now], the first of each id ([MetaItem.releases]). None when the item was never watched, isn't
 * [followsNewEpisodes], or [meta] is a live channel's or names a default video (a title of one video).
 */
internal fun newEpisodeCount(item: LibraryItem, meta: MetaItem, now: Instant): Int {
    val releases = newReleases(item, meta) ?: return 0
    val first = releases.firstNew(item)
    return (releases.countUntil(now) - first).coerceAtLeast(0)
}

/**
 * The count of new episodes ([newEpisodeCount]) of each of [items] that has any, from the first of [metas] with its id
 * and videos (the order stremio-core takes the answers in).
 */
internal fun newEpisodeCounts(items: List<LibraryItem>, metas: List<MetaItem>, now: Instant): Map<String, Int> =
    withMetas(items, metas).mapNotNull { (item, meta) ->
        newEpisodeCount(item, meta, now).takeIf { it > 0 }?.let { item.id to it }
    }.toMap()

/**
 * When [newEpisodeCounts] next changes with time alone: the first release after [now] of an episode it will count
 * then (one the addons list ahead of its release); null when none is to come.
 */
internal fun nextNewEpisode(items: List<LibraryItem>, metas: List<MetaItem>, now: Instant): Instant? =
    withMetas(items, metas).mapNotNull { (item, meta) ->
        val releases = newReleases(item, meta) ?: return@mapNotNull null
        releases.getOrNull(maxOf(releases.firstNew(item), releases.countUntil(now)))
    }.minOrNull()

/**
 * The series of [items] that new episodes bring back to Continue Watching, as it lists them (stremio-core's
 * `ContinueWatchingPreview` lists a library item with new-video notifications beside those in progress): each one out
 * of Continue Watching with new episodes out by [now] ([newEpisodeCount]) and an episode left to watch, on that episode
 * at its start (Up next, as the library leaves a series that moves on), changed and last watched when its newest new
 * episode came out, as if watched then: stremio-core orders such an item by that release.
 *
 * The episode is the one the item rests on while that isn't watched, else the first after it not watched
 * ([nextUnwatched]), and for an item resting on none, the first not watched after the last one marked watched. A series
 * whose episodes are all watched has none. Removing the item from Continue Watching moves its last watched time to that
 * moment ([com.edde746.tortuga.data.library.LibraryRepository.rewind]), so it stays out until another episode comes out.
 */
internal fun newEpisodesUpNext(items: List<LibraryItem>, metas: List<MetaItem>, now: Instant): List<LibraryItem> =
    withMetas(items, metas).mapNotNull { (item, meta) -> upNext(item, meta, now) }

/** [item] as [newEpisodesUpNext] lists it, or null when it doesn't. */
private fun upNext(item: LibraryItem, meta: MetaItem, now: Instant): LibraryItem? {
    if (item.isInContinueWatching) return null
    val releases = newReleases(item, meta) ?: return null
    val out = releases.countUntil(now)
    if (out <= releases.firstNew(item)) return null
    val marks = WatchedBitField.of(item.state.watchedVideos, meta)
    val current = item.state.videoId?.takeIf { meta.isEpisode(it) }
    val next = if (current != null && item.state.flaggedWatched == 0L && !marks.isWatched(current)) {
        meta.video(current)
    } else {
        (current ?: meta.videos.lastOrNull { marks.isWatched(it.id) }?.id)?.let { meta.nextUnwatched(it, marks, now) }
    } ?: return null
    val newest = releases[out - 1].toEpochMilliseconds()
    return item.copy(
        mtime = newest,
        state = item.state.copy(lastWatched = newest, videoId = next.id, timeWatched = 0, flaggedWatched = 0, timeOffset = 1, duration = 0),
    )
}

/** [meta]'s releases ([MetaItem.releases]) when its videos can be new to [item] ([newEpisodeCount]), else null. */
private fun newReleases(item: LibraryItem, meta: MetaItem): List<Instant>? {
    if (item.state.lastWatched == null || !item.followsNewEpisodes) return null
    if (meta.preview.isLive || meta.preview.behaviorHints.defaultVideoId != null) return null
    return meta.releases
}

/** The index of the first of these releases (oldest first) after [item] was last watched. */
private fun List<Instant>.firstNew(item: LibraryItem): Int {
    val lastWatched = item.state.lastWatched ?: return size
    return countWhile { it.toEpochMilliseconds() <= lastWatched }
}

/** How many of these releases (oldest first) are out by [now]. */
private fun List<Instant>.countUntil(now: Instant): Int = countWhile { it <= now }

/** The length of the prefix of this list whose elements are [before] (a binary search: it holds for a prefix only). */
internal inline fun <T> List<T>.countWhile(before: (T) -> Boolean): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (before(this[middle])) low = middle + 1 else high = middle
    }
    return low
}

/** Each of [items] that [metas] answer for, with the first of them with its id and videos. */
private fun withMetas(items: List<LibraryItem>, metas: List<MetaItem>): List<Pair<LibraryItem, MetaItem>> {
    val byId = metas.filter { it.videos.isNotEmpty() }.groupBy { it.id }
    return items.mapNotNull { item -> byId[item.id]?.first()?.let { item to it } }
}
