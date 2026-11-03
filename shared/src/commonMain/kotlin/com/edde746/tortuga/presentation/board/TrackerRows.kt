package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.presentation.settings.serviceName
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.TrackerException
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalog

// Text and links for the connected trackers' lists, as the Board and Discover show them.

/** A tracker list's own name, without its type: "Watchlist", "Trending", an MDBList list's name. */
internal fun trackerCatalogName(catalog: TrackerCatalog): String {
    val text = T.browse.tracker
    return when (catalog.kind) {
        TrackerCatalog.Kind.WATCHLIST -> text.watchlist
        TrackerCatalog.Kind.RECOMMENDED -> text.recommended
        TrackerCatalog.Kind.TRENDING -> text.trending
        TrackerCatalog.Kind.PLAN_TO_WATCH -> text.planToWatch
        TrackerCatalog.Kind.PLANNING -> text.planning
        TrackerCatalog.Kind.AIRING -> text.airing
        TrackerCatalog.Kind.LIST, TrackerCatalog.Kind.PUBLIC_LIST -> catalog.name?.trim()?.takeIf { it.isNotEmpty() } ?: text.list
    }
}

/** A tracker list as a Board row's title: "Watchlist", "Trending · Movies" (a list of one type says which). */
internal fun trackerRowTitle(catalog: TrackerCatalog): String =
    catalog.type?.let { rowTitle(trackerCatalogName(catalog), it) } ?: trackerCatalogName(catalog)

/** Why a tracker's lists or a page of one didn't load, worded for the row. */
internal fun trackerErrorText(service: TrackerService, error: Throwable): String {
    val text = T.browse.tracker
    val name = serviceName(service)
    return when {
        error is TrackerException.Network -> text.unreachable(name)
        error is TrackerException.Auth && error.permanent -> text.signedOut(name)
        else -> text.failed(name)
    }
}

/**
 * The [DeepLink.Discover] of a tracker list (a Board row's "See all"): its transport URL names the service
 * (`tortuga-tracker:trakt`), which is never an addon's, the type and catalog id the list's.
 */
internal fun trackerDiscoverLink(service: TrackerService, catalog: TrackerCatalog, type: String): DeepLink.Discover =
    DeepLink.Discover(TRACKER_LINK_PREFIX + service.id, type, catalog.id)

/** The service a [trackerDiscoverLink]'s transport URL names; null for an addon's. */
internal fun trackerOfLink(transportUrl: String): TrackerService? =
    if (transportUrl.startsWith(TRACKER_LINK_PREFIX)) TrackerService.fromId(transportUrl.removePrefix(TRACKER_LINK_PREFIX)) else null

private const val TRACKER_LINK_PREFIX = "tortuga-tracker:"
