package com.edde746.tortuga.presentation.discover

import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ExtraProp
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.ManifestCatalog
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.Resources
import com.edde746.tortuga.model.TransportUrl
import com.edde746.tortuga.model.withExtra

/**
 * An installed addon's catalog and a [request] for it. Those Discover lists (stremio-core's selectable catalogs) have
 * an option to start with for every required extra, and their [request] asks for the first page with the first option
 * of each (`default_required_extra`).
 */
internal class DiscoverCatalog(val addon: Descriptor, val catalog: ManifestCatalog, val request: ResourceRequest)

/** An extra with options, as a filter of the selected catalog (stremio-core `SelectableExtra`). */
internal class DiscoverFilter(val prop: ExtraProp, val options: List<DiscoverFilterOption>)

/**
 * One option of a [DiscoverFilter] and the request that picks it (stremio-core `SelectableExtraOption`). A null
 * [value] is "All": the extra left out, offered only when it isn't required.
 */
internal class DiscoverFilterOption(val value: String?, val selected: Boolean, val request: ResourceRequest)

/** Discover's selection rules, ported from stremio-core's `CatalogWithFilters::selectable_update`. */
internal object DiscoverCatalogs {
    /** Every catalog of every addon that Discover lists, in addon order. */
    fun selectable(addons: List<Descriptor>): List<DiscoverCatalog> = addons.flatMap { addon ->
        addon.manifest.catalogs.mapNotNull { catalog ->
            catalog.defaultRequiredExtra()?.let { extra ->
                DiscoverCatalog(addon, catalog, ResourceRequest(addon.transport, ResourcePath(Resources.CATALOG, catalog.type, catalog.id, extra)))
            }
        }
    }

    /** The catalog [request] asks for, where the installed addon at its transport URL declares it. */
    fun declared(addons: List<Descriptor>, request: ResourceRequest): DiscoverCatalog? {
        val addon = addons.firstOrNull { it.transport == request.base } ?: return null
        val catalog = addon.manifest.catalogs.firstOrNull { it.type == request.path.type && it.id == request.path.id } ?: return null
        return DiscoverCatalog(addon, catalog, request)
    }

    /** The filters of [catalog] while [selected] (a request for it) is shown: its extras with options, but `skip`. */
    fun filters(catalog: ManifestCatalog, selected: ResourceRequest): List<DiscoverFilter> =
        catalog.extra.filter { it.name != Extras.SKIP && it.options.isNotEmpty() }.map { prop ->
            val values = selected.path.extra.filter { it.name == prop.name }.map { it.value }
            val all = if (prop.isRequired) null else DiscoverFilterOption(null, values.isEmpty(), selected.withExtra(prop, null))
            val options = prop.options.map { value -> DiscoverFilterOption(value, value in values, selected.withExtra(prop, value)) }
            DiscoverFilter(prop, listOfNotNull(all) + options)
        }

    /**
     * The request [link] opens: any catalog, as stremio-core's `selected_update` loads any request, never `skip`. On a
     * catalog an installed addon declares ([declared]): the link's extras that the catalog declares, plus the first
     * option of each required extra the link leaves out (where it has options). On any other (its addon isn't
     * installed): the link's extras. Null when the link's transport URL isn't one.
     */
    fun resolve(addons: List<Descriptor>, link: DeepLink.Discover): ResourceRequest? {
        val request = ResourceRequest(
            TransportUrl.checked(link.transportUrl) ?: return null,
            ResourcePath(Resources.CATALOG, link.type, link.catalogId, link.extra.filter { it.name != Extras.SKIP }),
        )
        val catalog = declared(addons, request)?.catalog ?: return request
        val extra = request.path.extra.filter { catalog.extraProp(it.name) != null }
        val defaults = catalog.extra
            .filter { prop -> prop.isRequired && prop.options.isNotEmpty() && extra.none { it.name == prop.name } }
            .map { ExtraValue(it.name, it.options.first()) }
        return request.copy(path = request.path.copy(extra = extra + defaults))
    }

    private fun ResourceRequest.withExtra(prop: ExtraProp, value: String?) =
        copy(path = path.copy(extra = path.extra.withExtra(prop, value)))
}
