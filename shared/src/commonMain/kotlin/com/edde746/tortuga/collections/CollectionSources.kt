package com.edde746.tortuga.collections

import com.edde746.tortuga.addons.PlannedRequest
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.Resources
import com.edde746.tortuga.presentation.board.RowFeed
import com.edde746.tortuga.presentation.settings.serviceName
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.trakt.TraktLists

/** Whether a folder's source can show its titles now, and what it lacks when it can't ([note]). */
sealed interface SourceState {
    /** It shows [feed]'s titles: an addon's catalog, or a tracker's list (a connected account's, or [TraktLists]). */
    data class Ready(val feed: RowFeed) : SourceState

    /** The addon isn't installed, or is turned off: [name] is its name in the source, else its manifest id. */
    data class NeedsAddon(val name: String) : SourceState

    /** The addon [addonName] is there without the catalog [catalogName] (its name in the source, else its id). */
    data class NeedsCatalog(val addonName: String, val catalogName: String) : SourceState

    /** The tracker [service] isn't connected on this profile. */
    data class NeedsTracker(val service: TrackerService) : SourceState

    /** It can't show here at all; [text] says why. */
    data class Unavailable(val text: String) : SourceState
}

/** The line that tells the user what a source lacks; null for a ready one. */
val SourceState.note: String?
    get() = when (this) {
        is SourceState.Ready -> null
        is SourceState.NeedsAddon -> T.collections.needsAddon(name = name)
        is SourceState.NeedsCatalog -> T.collections.needsCatalog(addon = addonName, catalog = catalogName)
        is SourceState.NeedsTracker -> T.settings.trackers.connectNamed(name = serviceName(service))
        is SourceState.Unavailable -> text
    }

object CollectionSources {
    /**
     * [source] against the profile's enabled [addons] (installed order), its connected trackers' [trackers] and
     * Trakt's public lists where this build has Trakt ([traktLists]).
     *
     * An addon source takes the addon whose transport URL is its `addonUrl` when it has one, else the first whose
     * manifest id is its `addonId`, and that addon's catalog of its type and id; its genre goes as the `genre` extra.
     * A catalog that needs an extra other than a genre, or doesn't take the genre, can't show. A tracker source takes
     * its service's connected account; a Trakt public list takes [traktLists], with or without a Trakt account.
     */
    fun resolve(
        source: CollectionSource,
        addons: List<Descriptor>,
        trackers: List<TrackerCatalogSource>,
        traktLists: TraktLists?,
    ): SourceState = when (source) {
        is CollectionSource.AddonCatalog -> addon(source, addons)
        is CollectionSource.TrackerList -> tracker(source, trackers, traktLists)
    }

    private fun addon(source: CollectionSource.AddonCatalog, addons: List<Descriptor>): SourceState {
        val addon = source.addonUrl?.let { url -> addons.firstOrNull { it.transportUrl.removeSuffix(MANIFEST).trimEnd('/') == url } }
            ?: addons.firstOrNull { it.manifest.id == source.addonId }
            ?: return SourceState.NeedsAddon(source.addonName ?: source.addonId)
        val catalog = addon.manifest.catalogs.firstOrNull { it.type == source.type && it.id == source.catalogId }
            ?: return SourceState.NeedsCatalog(addon.manifest.name, source.catalogName ?: source.catalogId)
        val extra = source.genre?.let { listOf(ExtraValue(Extras.GENRE, it)) }.orEmpty()
        if (!catalog.isExtraSupported(extra)) return SourceState.Unavailable(T.collections.needsFilter)
        val path = ResourcePath(Resources.CATALOG, catalog.type, catalog.id, extra)
        return SourceState.Ready(RowFeed.Addon(PlannedRequest(addon, ResourceRequest(addon.transport, path))))
    }

    private fun tracker(source: CollectionSource.TrackerList, trackers: List<TrackerCatalogSource>, traktLists: TraktLists?): SourceState {
        val service = TrackerService.fromId(source.service) ?: return SourceState.Unavailable(T.collections.kind.unknown(name = source.service))
        val catalog = TrackerCatalog(service, source.listId, source.kind, source.type, source.name)
        if (service == TrackerService.TRAKT && source.kind == TrackerCatalog.Kind.PUBLIC_LIST) {
            return traktLists?.let { SourceState.Ready(RowFeed.Tracker(it, catalog)) }
                ?: SourceState.Unavailable(T.collections.traktUnavailable)
        }
        val account = trackers.firstOrNull { it.service == service } ?: return SourceState.NeedsTracker(service)
        return SourceState.Ready(RowFeed.Tracker(account, catalog))
    }

    private const val MANIFEST = "/manifest.json"
}
