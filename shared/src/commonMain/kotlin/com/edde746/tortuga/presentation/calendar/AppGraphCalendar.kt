package com.edde746.tortuga.presentation.calendar

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.presentation.board.installedAddonsFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Instant

fun AppGraph.calendarStore(profileId: String, host: CalendarHost): CalendarStore = CalendarStore(
    episodeFeed(profileId, Extras.CALENDAR_VIDEOS_IDS) { it.onCalendar }, data.library.observeLibrary(profileId), host, clock, scope,
    kidsGate(profileId), data.settings.observe(profileId),
)

/**
 * The count of new episodes of each library series that has any ([newEpisodeCounts]), by id: the badge on its card;
 * none until the addons answer. Collecting it asks the addons for the series' last videos ([EpisodeFeed]). An episode
 * they list ahead of its release counts from its release on ([nextNewEpisode]), with nothing else changing.
 */
internal fun AppGraph.newEpisodeCounts(profileId: String): Flow<Map<String, Int>> =
    newEpisodes(profileId) { items, metas, now -> newEpisodeCounts(items, metas, now) }.onStart { emit(emptyMap()) }.distinctUntilChanged()

/**
 * The library series that new episodes bring back to Continue Watching, on their Up next ([newEpisodesUpNext]); none
 * until the addons answer. Collecting it asks what [newEpisodeCounts] asks, and a series comes back as its episode's
 * release passes, as the badge counts it.
 */
internal fun AppGraph.newEpisodesUpNext(profileId: String): Flow<List<LibraryItem>> =
    newEpisodes(profileId) { items, metas, now -> newEpisodesUpNext(items, metas, now) }.onStart { emit(emptyList()) }.distinctUntilChanged()

/**
 * [of] the profile's library, the addons' last videos of its series ([EpisodeFeed]) and the time: again as either
 * changes, and when an episode they list ahead of its release comes out ([nextNewEpisode]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> AppGraph.newEpisodes(profileId: String, of: (List<LibraryItem>, List<MetaItem>, Instant) -> T): Flow<T> {
    val library = data.library.observeLibrary(profileId)
    val feed = episodeFeed(profileId, Extras.LAST_VIDEOS_IDS) { it.followsNewEpisodes }
    return combine(feed.state, library) { answers, items -> answers.metas to items }
        .transformLatest { (metas, items) ->
            while (true) {
                val now = clock.now()
                emit(of(items, metas, now))
                val next = nextNewEpisode(items, metas, now) ?: break
                delay(next - now)
            }
        }
}

/**
 * The profile's [EpisodeFeed] of [extraName] for the items of [library] (its library unless given) that it [follows],
 * one per [key] and profile: a feed of other items or rules takes a key of its own.
 */
internal fun AppGraph.episodeFeed(
    profileId: String,
    extraName: String,
    library: Flow<List<LibraryItem>> = data.library.observeLibrary(profileId),
    key: String = extraName,
    follows: (LibraryItem) -> Boolean,
): EpisodeFeed =
    episodeFeeds.getOrPut(profileId to key) {
        EpisodeFeed(extraName, follows, library, installedAddonsFlow(profileId), foreground, aggregator, scope)
    }
