package com.edde746.tortuga.addons

import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.ManifestResource
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.Resources

/** One addon and what to ask it. */
data class PlannedRequest(val addon: Descriptor, val request: ResourceRequest)

/**
 * A request to every applicable addon (stremio-core `AggrRequest`). [plan] picks the addons exactly as stremio-core
 * does and keeps the installed order.
 */
sealed interface AggrRequest {
    /**
     * Every catalog, of every addon, that accepts [extra] (all values name declared properties and every required
     * property has a value) and, if [type] is set, has that type. With no extra this is the Board query, which
     * leaves out catalogs with required extras; with a `search` value it is Search.
     */
    data class AllCatalogs(val extra: List<ExtraValue> = emptyList(), val type: String? = null) : AggrRequest

    /** [path] from every addon whose manifest supports it ([com.edde746.tortuga.model.Manifest.isResourceSupported]). */
    data class AllOfResource(val path: ResourcePath) : AggrRequest

    /**
     * Every catalog that takes the [extraName] extra (and needs no other), asked for the [idTypes] it accepts: stremio-core
     * `AggrRequest::CatalogsFiltered` with `ExtraType::Ids`. [idTypes] (id, type) are newest first. A catalog gets the
     * ids whose type is its type and that its addon accepts: the manifest lists the type and the id matches the
     * manifest's prefixes, and the `catalog` resource accepts both (a full resource without types or prefixes accepts
     * any). It takes the newest ones up to the smaller of [limit] and the extra's `optionsLimit`, sorted by id (so the
     * addon can cache the answer) and joined with commas; a catalog that accepts none is not asked.
     */
    data class CatalogsFiltered(val extraName: String, val idTypes: List<Pair<String, String>>, val limit: Int? = null) : AggrRequest

    fun plan(addons: List<Descriptor>): List<PlannedRequest> = when (this) {
        is AllCatalogs -> addons.flatMap { addon ->
            addon.manifest.catalogs
                .filter { catalog -> catalog.isExtraSupported(extra) && (type == null || catalog.type == type) }
                .map { catalog ->
                    PlannedRequest(addon, ResourceRequest(addon.transport, ResourcePath(Resources.CATALOG, catalog.type, catalog.id, extra)))
                }
        }
        is AllOfResource -> addons
            .filter { it.manifest.isResourceSupported(path) }
            .map { PlannedRequest(it, ResourceRequest(it.transport, path)) }
        is CatalogsFiltered -> addons.flatMap { addon ->
            addon.manifest.catalogs
                .filter { it.areExtraNamesSupported(listOf(extraName)) }
                .mapNotNull { catalog ->
                    val accepted = idTypes.filter { (id, type) -> catalog.type == type && addon.manifest.acceptsCatalogId(id, type) }
                    val optionsLimit = catalog.extraProp(extraName)?.optionsLimit ?: Int.MAX_VALUE
                    val ids = accepted.take(minOf(optionsLimit, limit ?: Int.MAX_VALUE)).map { it.first }.sorted()
                    if (ids.isEmpty()) return@mapNotNull null
                    val extra = listOf(ExtraValue(extraName, ids.joinToString(",")))
                    PlannedRequest(addon, ResourceRequest(addon.transport, ResourcePath(Resources.CATALOG, catalog.type, catalog.id, extra)))
                }
        }
    }

    companion object {
        /** The Board: the first page of every catalog without required extras. */
        fun board(type: String? = null): AggrRequest = AllCatalogs(type = type)

        /** Search: every catalog that takes a `search` extra (and needs nothing else). */
        fun search(query: String, type: String? = null): AggrRequest = AllCatalogs(listOf(ExtraValue(Extras.SEARCH, query)), type)

        fun meta(type: String, id: String): AggrRequest = AllOfResource(ResourcePath(Resources.META, type, id))

        fun streams(type: String, id: String): AggrRequest = AllOfResource(ResourcePath(Resources.STREAM, type, id))

        /** Subtitles for a video; [extra] carries `videoHash`, `videoSize` and `filename` when known. */
        fun subtitles(type: String, id: String, extra: List<ExtraValue> = emptyList()): AggrRequest =
            AllOfResource(ResourcePath(Resources.SUBTITLES, type, id, extra))
    }
}

/** Whether a `catalog` request of [type] may carry [id] (stremio-core's checks in `CatalogsFiltered`). */
private fun Manifest.acceptsCatalogId(id: String, type: String): Boolean {
    fun matches(prefixes: List<String>?) = prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
    val resourceAccepts = resources.any { resource ->
        when (resource) {
            is ManifestResource.Short -> resource.name == Resources.CATALOG
            is ManifestResource.Full -> resource.name == Resources.CATALOG &&
                (resource.types == null || type in resource.types) && matches(resource.idPrefixes)
        }
    }
    return resourceAccepts && matches(idPrefixes) && type in types
}
