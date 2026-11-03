package com.edde746.tortuga.collections

import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.presentation.settings.serviceName
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.trakt.TraktLists

/**
 * What importing a file would do, before it is done.
 *
 * @param collections what the import stores ([CollectionsRepository.import]): the file's collections but those too
 *   large to sync.
 * @param added the ids of [collections] the profile doesn't have; [replaced] those it has, which they replace.
 * @param tooLarge the collections left out because they are too large to sync.
 * @param lines the summary as the user reads it, one line each: what is added and replaced, what the sources need
 *   (addons by name, trackers to connect, with counts), what can't show, the unsupported sources by kind with counts
 *   and what to use instead, and the folders, collections and too-large collections left out.
 */
data class ImportSummary(
    val collections: List<Collection>,
    val added: List<String>,
    val replaced: List<String>,
    val tooLarge: List<Collection>,
    val lines: List<String>,
)

object CollectionImport {
    /**
     * [file] against the profile's [current] collections, its enabled [addons], its connected [trackers] and Trakt's
     * public lists where this build has them ([traktLists]); [fitsSync] tells a collection small enough to sync
     * ([CollectionsRepository.fitsSync]).
     */
    fun summary(
        file: CollectionFile,
        current: List<Collection>,
        addons: List<Descriptor>,
        trackers: List<TrackerCatalogSource>,
        traktLists: TraktLists?,
        fitsSync: (Collection) -> Boolean,
    ): ImportSummary {
        val text = T.collections.summary
        val (collections, tooLarge) = file.collections.partition(fitsSync)
        val have = current.mapTo(HashSet()) { it.id }
        val (replaced, added) = collections.map { it.id }.partition { it in have }

        val needsAddon = LinkedHashMap<String, Int>()
        val needsTracker = LinkedHashMap<TrackerService, Int>()
        val notes = LinkedHashSet<String>()
        for (source in collections.flatMap { it.folders }.flatMap { it.sources }) {
            when (val state = CollectionSources.resolve(source, addons, trackers, traktLists)) {
                is SourceState.Ready -> Unit
                is SourceState.NeedsAddon -> needsAddon[state.name] = (needsAddon[state.name] ?: 0) + 1
                is SourceState.NeedsTracker -> needsTracker[state.service] = (needsTracker[state.service] ?: 0) + 1
                is SourceState.NeedsCatalog, is SourceState.Unavailable -> notes += state.note!!
            }
        }

        val lines = buildList {
            if (added.isNotEmpty()) add(text.adds(added.size))
            if (replaced.isNotEmpty()) add(text.replaces(replaced.size))
            needsAddon.forEach { (name, count) -> add(text.needsAddon(count, name = name)) }
            needsTracker.forEach { (service, count) -> add(text.needsTracker(count, service = serviceName(service))) }
            addAll(notes)
            file.dropped.unsupported.forEach { (kind, count) -> add(text.unsupported(count, kind = kind)) }
            if (file.dropped.unsupported.isNotEmpty()) add(text.unsupportedHint)
            file.dropped.folders.forEach { add(text.folderDropped(folder = it.title, collection = it.collectionTitle)) }
            file.dropped.collections.forEach { add(text.collectionDropped(title = it)) }
            tooLarge.forEach { add(text.tooLarge(title = it.title)) }
        }
        return ImportSummary(collections, added, replaced, tooLarge, lines)
    }
}
