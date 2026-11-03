package com.edde746.tortuga.presentation.addons

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.addonRegistry
import com.edde746.tortuga.app.refreshAddonManifests

/** The Addons store of [profileId], with Browse where the build has it ([com.edde746.tortuga.app.BuildFlavor.addonCatalogs]). */
fun AppGraph.addonsStore(profileId: String, host: AddonsHost): AddonsStore = AddonsStore(
    registry = addonRegistry(profileId),
    transport = transport,
    storedOrder = { data.addons.addons(profileId).map { it.transportUrl } },
    host = host,
    parentScope = scope,
    refreshManifests = { refreshAddonManifests(profileId, maxAge = null) },
    catalogs = aggregator.takeIf { flavor.addonCatalogs },
)
