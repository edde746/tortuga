package com.edde746.tortuga.presentation.discover

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.addonRegistry
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.presentation.board.hiddenWatchedTitles
import com.edde746.tortuga.presentation.board.installedAddonsFlow
import com.edde746.tortuga.presentation.board.posterCaptions
import com.edde746.tortuga.presentation.board.trackerCatalogSources
import com.edde746.tortuga.presentation.menu.mediaActions

/**
 * Discover for [profileId], with the lists of its connected trackers, opened on [link]'s catalog (a
 * `stremio:///discover/…` link, or a Board row's "See all"): any addon catalog, installed or not, or a connected
 * tracker's list. With no link, or a link to a tracker list that isn't there, it opens on the first catalog of the
 * first type; offline it says so, pointing to Downloads where this device has them. It follows the profile's "Hide
 * watched titles" ([hiddenWatchedTitles]) and "Hide titles under posters" ([posterCaptions]).
 */
fun AppGraph.discoverStore(profileId: String, link: DeepLink.Discover?, host: DiscoverHost): DiscoverStore = DiscoverStore(
    installedAddons = installedAddonsFlow(profileId),
    aggregator = aggregator,
    trackerSources = trackerCatalogSources(profileId),
    link = link,
    host = host,
    parentScope = scope,
    network = network,
    offersDownloads = downloads != null,
    marks = data.library.observeMarks(profileId),
    actions = mediaActions(profileId),
    disabledAddons = { addonRegistry(profileId).installed.value.filterNot { it.enabled }.mapTo(HashSet()) { it.descriptor.transportUrl } },
    kids = kidsGate(profileId),
    hiddenTitles = hiddenWatchedTitles(profileId),
    posterCaptions = posterCaptions(profileId),
)
