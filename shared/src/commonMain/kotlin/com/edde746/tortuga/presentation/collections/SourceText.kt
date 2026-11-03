package com.edde746.tortuga.presentation.collections

import com.edde746.tortuga.collections.CollectionSource
import com.edde746.tortuga.collections.SourceState
import com.edde746.tortuga.presentation.board.RowFeed
import com.edde746.tortuga.presentation.board.catalog
import com.edde746.tortuga.presentation.board.title
import com.edde746.tortuga.presentation.board.trackerRowTitle
import com.edde746.tortuga.presentation.board.typeLabelInText
import com.edde746.tortuga.presentation.settings.serviceName
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalog

// How a folder's sources are named, on the folder's page and in the editor.

/**
 * The titles of a folder's [sources], each with its state now: its catalog's or list's name ("Popular", "Watchlist"),
 * with its genre when it has one ("Popular · Drama"); the type too when another source of the folder has the same name
 * ("Popular · Movies").
 */
internal fun sourceTitles(sources: List<Pair<CollectionSource, SourceState>>): List<String> {
    val names = sources.map { (source, state) -> sourceName(source, state) }
    val counts = names.groupingBy { it }.eachCount()
    return sources.mapIndexed { i, (source, _) ->
        val type = when (source) {
            is CollectionSource.AddonCatalog -> source.type
            is CollectionSource.TrackerList -> source.type
        }
        if (counts.getValue(names[i]) > 1 && type != null) T.browse.rowTitle(isolate(names[i]), typeLabelInText(type)) else names[i]
    }
}

/** Where [source] comes from: its addon's name ("Cinemeta"; the source's own while not installed), its tracker's. */
internal fun sourceOrigin(source: CollectionSource, state: SourceState): String = when (source) {
    is CollectionSource.AddonCatalog -> ((state as? SourceState.Ready)?.feed as? RowFeed.Addon)?.planned?.addon?.manifest?.name
        ?: source.addonName ?: source.addonId
    is CollectionSource.TrackerList -> TrackerService.fromId(source.service)?.let(::serviceName) ?: source.service
}

private fun sourceName(source: CollectionSource, state: SourceState): String = when (source) {
    is CollectionSource.AddonCatalog -> {
        val feed = (state as? SourceState.Ready)?.feed as? RowFeed.Addon
        val name = feed?.planned?.catalog()?.title ?: source.catalogName ?: source.catalogId
        source.genre?.let { T.browse.rowTitle(isolate(name), it) } ?: name
    }
    is CollectionSource.TrackerList -> TrackerService.fromId(source.service)
        ?.let { trackerRowTitle(TrackerCatalog(it, source.listId, source.kind, type = null, name = source.name)) }
        ?: source.name ?: source.listId
}
