package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.presentation.calendar.EpisodeFeedState
import com.edde746.tortuga.presentation.calendar.episodeFeed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Instant

/**
 * The ids of the [items] the profile has finished, by [now]: a movie watched ([LibraryItem.isWatched]), and a series
 * (any episodic type) whose videos out by [now] are all marked watched, in the first of [metas] with its id and
 * videos. Specials (season 0) don't count, and a video without a release date counts as out, as marking a series
 * watched takes it. A series with a new episode out is not finished, nor is one [metas] don't answer for.
 *
 * [metas] are what the addons send for the profile's last videos (Cinemeta: each series' latest twenty, newest
 * seasons): a series counts as finished when those that are out are watched.
 */
internal fun finishedTitles(items: List<LibraryItem>, metas: List<MetaItem>, now: Instant): Set<String> {
    val byId = metas.filter { it.videos.isNotEmpty() }.groupBy { it.id }
    return items.filterTo(mutableListOf()) { item ->
        if (item.type == "movie") return@filterTo item.isWatched
        val meta = byId[item.id]?.first() ?: return@filterTo false
        if (meta.preview.isLive) return@filterTo false
        val out = meta.videos.filter { it.seriesInfo?.season != 0 && it.isOutBy(now) }
        val marks = WatchedBitField.of(item.state.watchedVideos, meta)
        out.isNotEmpty() && out.all { marks.isWatched(it.id) }
    }.mapTo(HashSet()) { it.id }
}

/** The first release after [now] among [metas]' videos, when [finishedTitles] may change with time alone; null with none. */
private fun nextRelease(metas: List<MetaItem>, now: Instant): Instant? =
    metas.asSequence().flatMap { it.videos }.mapNotNull { it.released }.filter { it > now }.minOrNull()

private fun Video.isOutBy(now: Instant): Boolean = released?.let { it <= now } ?: true

/**
 * The titles Home's catalog and tracker rows and Discover leave out for [profileId]: while its "Hide watched titles"
 * switch is on ([com.edde746.tortuga.data.settings.ProfileSetting.HideWatched]), the titles it has finished
 * ([finishedTitles]); none while it is off. Its watched movies count at once; its watched series once the addons
 * answer for their last videos (an [com.edde746.tortuga.presentation.calendar.EpisodeFeed] of the profile's watched
 * series, in the library or not), and again as one of their episodes comes out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun AppGraph.hiddenWatchedTitles(profileId: String): Flow<Set<String>> =
    data.settings.observe(profileId).map { it.hideWatched }.distinctUntilChanged().flatMapLatest { hide ->
        if (!hide) return@flatMapLatest flowOf(emptySet())
        val watched = data.library.observeWatched(profileId)
        val feed = episodeFeed(profileId, Extras.LAST_VIDEOS_IDS, watched, key = FINISHED_FEED) { it.type != "movie" }
        combine(feed.state.onStart { emit(EpisodeFeedState(emptyList(), asked = false, loading = true)) }, watched) { answers, items -> answers.metas to items }
            .transformLatest { (metas, items) ->
                while (true) {
                    val now = clock.now()
                    emit(finishedTitles(items, metas, now))
                    val next = nextRelease(metas, now) ?: break
                    delay(next - now)
                }
            }
            .distinctUntilChanged()
    }

/** The key of the feed of the profile's watched series' last videos ([AppGraph.episodeFeed]). */
private const val FINISHED_FEED = "finished"
