package com.edde746.tortuga.presentation.search

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.presentation.board.installedAddonsFlow
import com.edde746.tortuga.presentation.board.posterCaptions
import com.edde746.tortuga.presentation.menu.mediaActions

/**
 * Search for [profileId]; [query] (a `stremio:///search` link's, [com.edde746.tortuga.model.DeepLink.Search]) is
 * searched at once, null opens it blank with the recent searches. Offline it says so, pointing to Downloads where this
 * device has them. Its results follow the profile's "Hide titles under posters" ([posterCaptions]).
 */
fun AppGraph.searchStore(profileId: String, query: String?, host: SearchHost): SearchStore = SearchStore(
    installedAddons = installedAddonsFlow(profileId),
    aggregator = aggregator,
    deviceSettings = data.deviceSettings,
    profileId = profileId,
    host = host,
    initialQuery = query,
    parentScope = scope,
    network = network,
    offersDownloads = downloads != null,
    marks = data.library.observeMarks(profileId),
    actions = mediaActions(profileId),
    kids = kidsGate(profileId),
    posterCaptions = posterCaptions(profileId),
)
