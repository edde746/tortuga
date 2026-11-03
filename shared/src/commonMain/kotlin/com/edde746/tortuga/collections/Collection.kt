package com.edde746.tortuga.collections

import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * A user-made group of folder tiles on Home, stored and synced as the profile setting `collection.<id>`
 * ([CollectionsRepository]); its row's key in Home's layout is [collectionHomeKey]. Read from and written as Nuvio's
 * collection files ([NuvioCollections]).
 *
 * @param shape the shape of every folder's tile in the row: one size, so tiles and their titles line up.
 * @param backdropUrl the image behind a folder's page.
 */
@Serializable
data class Collection(
    val id: String,
    val title: String,
    val viewMode: CollectionViewMode = CollectionViewMode.TABS,
    val showAllTab: Boolean = true,
    val shape: PosterShape = PosterShape.POSTER,
    val backdropUrl: String? = null,
    val folders: List<CollectionFolder>,
)

/** How a folder's sources show: a tab each (with an All tab when [Collection.showAllTab]), or a row each. */
@Serializable
enum class CollectionViewMode {
    TABS,
    ROWS,

    /** As Home shows catalogs: rows. */
    FOLLOW_APP,
}

/**
 * One tile of a [Collection], opening its [sources]' titles.
 *
 * @param coverUrl the tile's image, shown as a still (a GIF's first frame) cropped to the collection's
 *   [Collection.shape]; without one the tile shows its title.
 */
@Serializable
data class CollectionFolder(
    val id: String,
    val title: String,
    val coverUrl: String? = null,
    val hideTitle: Boolean = false,
    val sources: List<CollectionSource>,
)

/** Where a folder's titles come from. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("source")
sealed interface CollectionSource {
    /**
     * An addon's catalog: the installed addon whose transport URL is [addonUrl] (its manifest URL without
     * `/manifest.json`) when given, else whose manifest id is [addonId], and its catalog of [type] and [catalogId],
     * filtered by [genre] (its `genre` extra) when set. [addonName] and [catalogName] name them while they are missing.
     */
    @Serializable
    @SerialName("addon")
    data class AddonCatalog(
        val addonId: String,
        val type: String,
        val catalogId: String,
        val genre: String? = null,
        val addonName: String? = null,
        val catalogName: String? = null,
        val addonUrl: String? = null,
    ) : CollectionSource

    /**
     * A tracker's catalog ([TrackerCatalog]): kind, type and name as the tracker layer gives them. A
     * [TrackerCatalog.Kind.PUBLIC_LIST] is a list anyone can read by its URL.
     *
     * @param service the [com.edde746.tortuga.trackers.TrackerService.id].
     * @param listId the [TrackerCatalog.id].
     */
    @Serializable
    @SerialName("tracker")
    data class TrackerList(
        val service: String,
        val listId: String,
        val kind: TrackerCatalog.Kind,
        val type: String? = null,
        val name: String? = null,
    ) : CollectionSource
}
