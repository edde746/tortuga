package com.edde746.tortuga.presentation.collections

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.addonRegistry
import com.edde746.tortuga.presentation.board.hiddenWatchedTitles
import com.edde746.tortuga.presentation.board.installedAddonsFlow
import com.edde746.tortuga.presentation.board.posterCaptions
import com.edde746.tortuga.presentation.board.trackerCatalogSources
import com.edde746.tortuga.presentation.menu.mediaActions
import kotlinx.coroutines.flow.first

/**
 * The folder [folderId] of [profileId]'s collection [collectionId], filtered as Home's rows are (hidden watched titles,
 * the kids gate); its notes' buttons open Settings unless [kidsProfile] (whose Settings have no Addons or Trackers).
 */
fun AppGraph.folderStore(profileId: String, collectionId: String, folderId: String, host: FolderHost, kidsProfile: Boolean): FolderStore =
    FolderStore(
        profileId = profileId,
        collectionId = collectionId,
        folderId = folderId,
        collections = data.collections.observe(profileId),
        installedAddons = installedAddonsFlow(profileId),
        trackerSources = trackerCatalogSources(profileId),
        traktLists = trackers?.traktLists,
        aggregator = aggregator,
        library = data.library,
        actions = mediaActions(profileId),
        host = host,
        parentScope = scope,
        noteActions = !kidsProfile,
        kids = kidsGate(profileId),
        hiddenTitles = hiddenWatchedTitles(profileId),
        posterCaptions = posterCaptions(profileId),
    )

/** Importing collections into [profileId]; [offersFiles]: the platform has file dialogs (Open file). */
internal fun AppGraph.collectionImportStore(profileId: String, offersFiles: Boolean): CollectionImportStore = CollectionImportStore(
    profileId = profileId,
    http = http,
    collections = data.collections,
    addons = { addonRegistry(profileId).current() },
    trackers = { trackerCatalogSources(profileId).first() },
    traktLists = trackers?.traktLists,
    offersFiles = offersFiles,
    work = parseDispatcher,
    parentScope = scope,
)

/** The editor of [profileId]'s collection [collectionId] (null: a new one), which calls [close] when done. */
internal fun AppGraph.collectionEditorStore(profileId: String, collectionId: String?, close: () -> Unit): CollectionEditorStore =
    CollectionEditorStore(
        profileId = profileId,
        collectionId = collectionId,
        collections = data.collections,
        installedAddons = installedAddonsFlow(profileId),
        trackerSources = trackerCatalogSources(profileId),
        traktLists = trackers?.traktLists,
        close = close,
        parentScope = scope,
    )
