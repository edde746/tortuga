package com.edde746.tortuga.addons

import com.edde746.tortuga.model.ExtraProp
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.ManifestCatalog
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.withExtra

/**
 * Paging through one catalog with the `skip` extra, as stremio-core's `CatalogWithFilters` does: `skip` is the
 * number of items loaded so far, so pages of any size (Cinemeta sends about 50) are neither skipped nor repeated.
 */
object CatalogPaging {
    /**
     * The request for the page after [loadedPageSizes] (item counts of the pages loaded so far with [request], first
     * page first), or null when there is none: the catalog doesn't declare `skip`, no page is loaded yet, or a page
     * was empty or failed (pass null for a page that isn't ready).
     */
    fun nextPage(catalog: ManifestCatalog, request: ResourceRequest, loadedPageSizes: List<Int?>): ResourceRequest? {
        if (catalog.extraProp(Extras.SKIP) == null || loadedPageSizes.isEmpty()) return null
        if (loadedPageSizes.any { it == null || it == 0 }) return null
        val skip = loadedPageSizes.sumOf { it ?: 0 }
        return request.copy(path = request.path.copy(extra = request.path.extra.withExtra(ExtraProp.SKIP, skip.toString())))
    }
}
