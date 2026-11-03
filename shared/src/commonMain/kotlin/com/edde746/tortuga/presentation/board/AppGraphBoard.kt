package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.addonRegistry
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.presentation.calendar.newEpisodeCounts
import com.edde746.tortuga.presentation.library.continueWatching
import com.edde746.tortuga.presentation.menu.mediaActions
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The Board of [profileId], with the lists of its connected trackers, arranged as the profile set Home's rows; offline
 * it says so, pointing to Downloads where this device has them; while the profile has downloads on their way here, it
 * sums them up. Its Watch together shortcut, not while it runs as a kids profile ([kidsProfile]), says how many are in
 * this device's room ([watchingTogether], 0 outside one). On a [remote]'s UI its rows end in a See all card
 * ([SeeAll.CARD]). A guide catalog adds the TV guide's shortcut, not on a kids profile either; the Calendar shortcut
 * follows, and its Continue Watching cards count their series' new episodes. The catalog and tracker rows follow the
 * profile's "Hide watched titles" ([hiddenWatchedTitles]) and "Hide titles under posters" ([posterCaptions]). The
 * profile's collections come first, their Trakt lists readable without a Trakt account where the build has Trakt.
 */
fun AppGraph.boardStore(
    profileId: String,
    host: BoardHost,
    remote: Boolean,
    watchingTogether: Flow<Int>,
    kidsProfile: Flow<Boolean>,
): BoardStore = BoardStore(
    profileId = profileId,
    installedAddons = installedAddonsFlow(profileId),
    aggregator = aggregator,
    library = data.library,
    continueWatchingItems = continueWatching(profileId, BoardStore.CONTINUE_WATCHING_LIMIT),
    trackerSources = trackerCatalogSources(profileId),
    homeLayout = data.settings.observe(profileId).map { it.homeRows }.distinctUntilChanged(),
    host = host,
    parentScope = scope,
    network = network,
    offersDownloads = downloads != null,
    profileDownloads = downloads?.downloads(profileId) ?: flowOf(emptyList()),
    watchingTogether = watchingTogether,
    kidsProfile = kidsProfile,
    parseDispatcher = parseDispatcher,
    actions = mediaActions(profileId),
    remote = remote,
    kids = kidsGate(profileId),
    newEpisodes = newEpisodeCounts(profileId),
    hiddenTitles = hiddenWatchedTitles(profileId),
    posterCaptions = posterCaptions(profileId),
    collections = data.collections.observe(profileId),
    traktLists = trackers?.traktLists,
)

/**
 * Whether [profileId]'s poster cards show their titles under the artwork: not while its "Hide titles under posters"
 * is on ([com.edde746.tortuga.data.settings.ProfileSetting.HidePosterCaptions]). Continue Watching's always do.
 */
internal fun AppGraph.posterCaptions(profileId: String): Flow<Boolean> =
    data.settings.observe(profileId).map { !it.hidePosterCaptions }.distinctUntilChanged()

/** The read side of the profile's connected trackers (none without trackers on the graph). */
internal fun AppGraph.trackerCatalogSources(profileId: String): Flow<List<TrackerCatalogSource>> =
    trackers?.accounts?.catalogSources(profileId) ?: flowOf(emptyList())

/**
 * The profile's installed addons: as loaded (read now if they haven't been), then again whenever the stored list
 * changes (an install on this device, or sync; see [AppGraph.addonRegistry]).
 */
internal fun AppGraph.installedAddonsFlow(profileId: String): Flow<List<Descriptor>> {
    val registry = addonRegistry(profileId)
    return flow {
        emit(registry.current())
        emitAll(registry.addons)
    }.distinctUntilChanged()
}
