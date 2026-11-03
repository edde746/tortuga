package com.edde746.tortuga.addons

import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ResourceRequest

/** The state of one addon request (stremio-core `Loadable<T, ResourceError>`). */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    /**
     * [dropped]: malformed list elements left out of [content], at any depth, for the logs; [sent]: the elements of
     * the response's list as the addon sent them, malformed ones included, what a catalog's `skip` counts (see
     * [com.edde746.tortuga.model.AddonResponse]).
     */
    data class Ready<out T>(val content: T, val dropped: Int = 0, val sent: Int = 0) : Loadable<T>

    data class Error(val error: AddonError) : Loadable<Nothing>
}

/** One addon's part of an aggregated request. */
data class AddonLoadable<out T>(
    val addon: Descriptor,
    val request: ResourceRequest,
    val state: Loadable<T>,
)
