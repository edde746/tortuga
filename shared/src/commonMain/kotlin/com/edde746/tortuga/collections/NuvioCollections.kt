package com.edde746.tortuga.collections

import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.catalog.CatalogItems
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A collection file Tortuga refuses; [message] says why in the user's words, naming the collection or folder. */
class CollectionFileException(message: String) : Exception(message)

/** What [NuvioCollections.decode] read: the [collections] Tortuga can show, and what it left out ([dropped]). */
data class CollectionFile(val collections: List<Collection>, val dropped: ImportDrops)

/**
 * What a decoded file left out.
 *
 * @param unsupported the sources Tortuga can't read, counted by kind ("TMDB discover", "Letterboxd"), in file order.
 * @param folders the folders left with no source, in the collections that stay.
 * @param collections the titles of the collections left with no folder.
 */
data class ImportDrops(
    val unsupported: Map<String, Int> = emptyMap(),
    val folders: List<DroppedFolder> = emptyList(),
    val collections: List<String> = emptyList(),
)

/** A folder left out of the collection [collectionTitle]. */
data class DroppedFolder(val title: String, val collectionTitle: String)

/**
 * Nuvio's collection files: a JSON array of collections, as NuvioTV and NuvioMobile write them and AIOMetadata exports
 * them.
 *
 * Reading follows Nuvio's: unknown keys are ignored, an empty URL is none, view modes and tile shapes are read in any
 * case (`FOLLOW_HOME`, which packs write, as `FOLLOW_LAYOUT`; an unknown view mode as tabs; a missing tile shape as
 * square; `WIDE` as landscape), a folder's `sources` win over its legacy `catalogSources` (addon catalogs only) unless
 * empty, and an addon source's genre `None` is no genre. Nuvio shapes each folder; a Tortuga collection has one shape
 * ([Collection.shape]): the shape most of its folders in the file have, on a tie the earliest folder's of those tied.
 * A file Nuvio's validator refuses is refused here too
 * ([CollectionFileException]): not a JSON array of objects, a collection or folder without an id or title, a folder
 * without a list of sources, an unknown tile shape, an incomplete source; and so is a repeated collection id, or a
 * repeated folder id in a collection. TMDB, Letterboxd and other providers' sources are left out and counted by kind;
 * a folder left with no source, and a collection left with no folder, are left out and listed ([ImportDrops]).
 *
 * Writing gives Nuvio what it reads: the collection's shape as every folder's `tileShape`, addon sources in both
 * `sources` and `catalogSources`, a Trakt public list as a
 * `trakt` source (one per type, movies and shows, for a list of both), other tracker lists as Tortuga's own `tracker`
 * sources (which Nuvio leaves out), and Nuvio's defaults for what Tortuga doesn't keep (pinning, focus glow, focus
 * GIFs, emoji, hero art).
 */
object NuvioCollections {
    private val exportJson = Json { prettyPrint = true }

    /** @throws CollectionFileException when the file is refused. */
    fun decode(text: String): CollectionFile {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw CollectionFileException(T.collections.refused.notJson)
        }
        val array = root as? JsonArray ?: refuse(T.collections.refused.notList)
        if (array.isEmpty()) refuse(T.collections.refused.empty)
        val reader = Reader()
        val collections = array.mapIndexedNotNull { index, element -> reader.collection(index + 1, element) }
        return CollectionFile(collections, ImportDrops(reader.unsupported, reader.droppedFolders, reader.droppedCollections))
    }

    fun encode(collections: List<Collection>): String =
        exportJson.encodeToString(JsonArray.serializer(), JsonArray(collections.map(::collectionJson)))

    private class Reader {
        val unsupported = LinkedHashMap<String, Int>()
        val droppedFolders = mutableListOf<DroppedFolder>()
        val droppedCollections = mutableListOf<String>()
        private val ids = HashSet<String>()

        /** The collection at [position] (from 1); null when none of its folders stays (listed). */
        fun collection(position: Int, element: JsonElement): Collection? {
            val text = T.collections.refused
            val json = element as? JsonObject ?: refuse(text.collectionInvalid(position = position.toString()))
            val id = json.string("id")?.takeIf { it.isNotBlank() } ?: refuse(text.collectionNoId(position = position.toString()))
            val title = json.string("title") ?: refuse(text.collectionNoTitle(id = id))
            if (!ids.add(id)) refuse(text.duplicateCollection(id = id))
            val folders = json["folders"] as? JsonArray ?: refuse(text.noFolders(collection = title))
            val folderIds = HashSet<String>()
            val read = folders.mapIndexed { index, folder ->
                folder(index + 1, folder, title).also { (each, _) ->
                    if (!folderIds.add(each.id)) refuse(text.duplicateFolder(collection = title, id = each.id))
                }
            }
            val (kept, dropped) = read.map { it.first }.partition { it.sources.isNotEmpty() }
            if (kept.isEmpty()) {
                droppedCollections += title
                return null
            }
            droppedFolders += dropped.map { DroppedFolder(it.title, title) }
            val shapes = read.map { it.second }
            val counts = shapes.groupingBy { it }.eachCount()
            val most = counts.values.max()
            return Collection(
                id = id,
                title = title,
                viewMode = viewMode(json.string("viewMode")),
                showAllTab = json.boolean("showAllTab") ?: true,
                shape = shapes.first { counts.getValue(it) == most },
                backdropUrl = json.url("backdropImageUrl"),
                folders = kept,
            )
        }

        /** The folder at [position] (from 1) and its tile shape in the file. */
        private fun folder(position: Int, element: JsonElement, collection: String): Pair<CollectionFolder, PosterShape> {
            val text = T.collections.refused
            val json = element as? JsonObject ?: refuse(text.folderInvalid(position = position.toString(), collection = collection))
            val id = json.string("id")?.takeIf { it.isNotBlank() }
                ?: refuse(text.folderNoId(position = position.toString(), collection = collection))
            val title = json.string("title") ?: refuse(text.folderNoTitle(id = id, collection = collection))
            val current = json["sources"] as? JsonArray
            val legacy = json["catalogSources"] as? JsonArray
            val (sources, addonOnly) = when {
                current != null && (current.isNotEmpty() || legacy == null) -> current to false
                legacy != null -> legacy to true
                else -> refuse(text.noSources(folder = title, collection = collection))
            }
            val shape = when (val raw = json["tileShape"]) {
                null, JsonNull -> PosterShape.SQUARE
                else -> shape(raw) ?: refuse(text.unknownShape(folder = title, collection = collection, shape = raw.toString().trim('"')))
            }
            val folder = CollectionFolder(
                id = id,
                title = title,
                coverUrl = json.url("coverImageUrl"),
                hideTitle = json.boolean("hideTitle") ?: false,
                sources = sources.mapIndexedNotNull { index, source ->
                    source(source as? JsonObject, addonOnly) {
                        refuse(text.sourceInvalid(position = (index + 1).toString(), folder = title, collection = collection))
                    }
                },
            )
            return folder to shape
        }

        /**
         * The source [json] as Tortuga reads it; null for one it can't read (counted); [incomplete] for an incomplete
         * one. A legacy source ([addonOnly]) is an addon's whatever its provider says.
         */
        private fun source(json: JsonObject?, addonOnly: Boolean, incomplete: () -> Nothing): CollectionSource? {
            if (json == null) incomplete()
            val provider = if (addonOnly) ADDON else json.string("provider")?.lowercase()?.takeIf { it.isNotBlank() } ?: ADDON
            val kinds = T.collections.kind
            return when (provider) {
                ADDON -> {
                    val addonId = json.string("addonId")?.takeIf { it.isNotBlank() } ?: incomplete()
                    val type = json.string("type")?.takeIf { it.isNotBlank() } ?: incomplete()
                    val catalogId = json.string("catalogId")?.takeIf { it.isNotBlank() } ?: incomplete()
                    CollectionSource.AddonCatalog(
                        addonId = addonId,
                        type = type,
                        catalogId = catalogId,
                        genre = json.string("genre")?.trim()?.takeIf { it.isNotEmpty() && !it.equals(NO_GENRE, ignoreCase = true) },
                        addonName = json.string("addonName")?.takeIf { it.isNotBlank() },
                        catalogName = json.string("catalogName")?.takeIf { it.isNotBlank() },
                        addonUrl = json.url("addonBaseUrl")?.trimEnd('/'),
                    )
                }
                TRAKT -> {
                    val id = (json["traktListId"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 } ?: incomplete()
                    val tv = json.string("mediaType")?.trim()?.lowercase() in TV_TYPES
                    CollectionSource.TrackerList(
                        service = TrackerService.TRAKT.id,
                        listId = id.toString(),
                        kind = TrackerCatalog.Kind.PUBLIC_LIST,
                        type = if (tv) CatalogItems.SERIES else CatalogItems.MOVIE,
                        name = json.string("title")?.takeIf { it.isNotBlank() },
                    )
                }
                TRACKER -> {
                    val service = json.string("service")?.takeIf { it.isNotBlank() } ?: incomplete()
                    val listId = json.string("listId")?.takeIf { it.isNotBlank() } ?: incomplete()
                    val kind = json.string("kind")?.let { raw -> TrackerCatalog.Kind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
                        ?: incomplete()
                    val known = TrackerService.fromId(service) ?: return skip(kinds.unknown(name = service))
                    // Trakt's and MDBList's own lists are the number the service gave them (`list.<n>`); a file names anything.
                    if (kind == TrackerCatalog.Kind.LIST && known in NUMBERED_LISTS && listId.removePrefix(LIST_PREFIX).toLongOrNull()?.takeIf { it > 0 } == null) incomplete()
                    CollectionSource.TrackerList(
                        service = service,
                        listId = listId,
                        kind = kind,
                        type = json.string("type")?.takeIf { it.isNotBlank() },
                        name = json.string("name")?.takeIf { it.isNotBlank() },
                    )
                }
                TMDB -> {
                    val type = json.string("tmdbSourceType")?.takeIf { it.isNotBlank() } ?: incomplete()
                    skip(
                        when (type.uppercase()) {
                            "LIST" -> kinds.tmdbList
                            "COLLECTION" -> kinds.tmdbCollection
                            "COMPANY" -> kinds.tmdbCompany
                            "NETWORK" -> kinds.tmdbNetwork
                            "DISCOVER" -> kinds.tmdbDiscover
                            "PERSON" -> kinds.tmdbPerson
                            "DIRECTOR" -> kinds.tmdbDirector
                            else -> kinds.unknown(name = "TMDB $type")
                        },
                    )
                }
                LETTERBOXD -> skip(kinds.letterboxd)
                else -> skip(kinds.unknown(name = json.string("provider").orEmpty()))
            }
        }

        /** Counts a source left out as [kind] ([ImportDrops.unsupported]). */
        private fun skip(kind: String): CollectionSource? {
            unsupported[kind] = (unsupported[kind] ?: 0) + 1
            return null
        }
    }

    private fun viewMode(raw: String?): CollectionViewMode = when (raw?.trim()?.lowercase()) {
        "rows" -> CollectionViewMode.ROWS
        "follow_layout", "follow_home" -> CollectionViewMode.FOLLOW_APP
        else -> CollectionViewMode.TABS
    }

    private fun shape(raw: JsonElement): PosterShape? = when ((raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()) {
        "poster" -> PosterShape.POSTER
        "landscape", "wide" -> PosterShape.LANDSCAPE
        "square" -> PosterShape.SQUARE
        else -> null
    }

    private fun collectionJson(collection: Collection): JsonObject = buildJsonObject {
        put("id", collection.id)
        put("title", collection.title)
        put("backdropImageUrl", collection.backdropUrl)
        put("pinToTop", false)
        put("focusGlowEnabled", true)
        put(
            "viewMode",
            when (collection.viewMode) {
                CollectionViewMode.TABS -> "TABBED_GRID"
                CollectionViewMode.ROWS -> "ROWS"
                CollectionViewMode.FOLLOW_APP -> "FOLLOW_LAYOUT"
            },
        )
        put("showAllTab", collection.showAllTab)
        put("folders", JsonArray(collection.folders.map { folderJson(it, collection.shape) }))
    }

    private fun folderJson(folder: CollectionFolder, shape: PosterShape): JsonObject = buildJsonObject {
        put("id", folder.id)
        put("title", folder.title)
        put("coverImageUrl", folder.coverUrl)
        put("focusGifUrl", null as String?)
        put("focusGifEnabled", false)
        put("coverEmoji", null as String?)
        put("tileShape", shape.name)
        put("hideTitle", folder.hideTitle)
        put("sources", buildJsonArray { folder.sources.forEach { source -> sourceJson(source).forEach(::add) } })
        put("catalogSources", JsonArray(folder.sources.filterIsInstance<CollectionSource.AddonCatalog>().map { addonJson(it, provider = false) }))
        put("heroBackdropUrl", null as String?)
        put("heroVideoUrl", null as String?)
        put("titleLogoUrl", null as String?)
    }

    private fun sourceJson(source: CollectionSource): List<JsonObject> = when (source) {
        is CollectionSource.AddonCatalog -> listOf(addonJson(source, provider = true))
        is CollectionSource.TrackerList ->
            if (source.service == TrackerService.TRAKT.id && source.kind == TrackerCatalog.Kind.PUBLIC_LIST && source.listId.toLongOrNull() != null) {
                val types = source.type?.let(::listOf) ?: listOf(CatalogItems.MOVIE, CatalogItems.SERIES)
                types.map { type -> traktJson(source, type) }
            } else {
                listOf(
                    buildJsonObject {
                        put("provider", TRACKER)
                        put("service", source.service)
                        put("listId", source.listId)
                        put("kind", source.kind.name)
                        put("type", source.type)
                        put("name", source.name)
                    },
                )
            }
    }

    private fun addonJson(source: CollectionSource.AddonCatalog, provider: Boolean): JsonObject = buildJsonObject {
        if (provider) put("provider", ADDON)
        put("addonId", source.addonId)
        put("addonBaseUrl", source.addonUrl)
        put("addonName", source.addonName)
        put("type", source.type)
        put("catalogId", source.catalogId)
        put("catalogName", source.catalogName)
        put("genre", source.genre)
    }

    private fun traktJson(source: CollectionSource.TrackerList, type: String): JsonObject = buildJsonObject {
        put("provider", TRAKT)
        put("title", source.name.orEmpty())
        put("traktListId", source.listId.toLong())
        put("mediaType", if (type == CatalogItems.SERIES) "TV" else "MOVIE")
        put("sortBy", "rank")
        put("sortHow", "asc")
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    /** A URL field: blank (Nuvio's `""`) is none. */
    private fun JsonObject.url(key: String): String? = string(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun refuse(message: String): Nothing = throw CollectionFileException(message)

    private const val ADDON = "addon"
    private const val TRAKT = "trakt"
    private const val TMDB = "tmdb"
    private const val TRACKER = "tracker"
    private const val LETTERBOXD = "letterboxd"
    private const val NO_GENRE = "none"
    private val TV_TYPES = setOf("tv", "series")

    /** The services whose own lists' ids are `list.<n>` (their catalogs' ids). */
    private val NUMBERED_LISTS = setOf(TrackerService.TRAKT, TrackerService.MDBLIST)
    private const val LIST_PREFIX = "list."
}
