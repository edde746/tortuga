package com.edde746.tortuga.addons

import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.transportUrl
import kotlin.test.Test
import kotlin.test.assertEquals

/** The calendar's and the new-episode badges' requests against stremio-core's own plan ([StremioCoreIdsGolden]). */
class StremioCoreIdsGoldenTest {
    private val manifests = StremioCoreIdsGolden.manifests + (DefaultAddons.CINEMETA_URL to DefaultAddons.manifestJson.getValue(DefaultAddons.CINEMETA_URL))

    @Test
    fun idsRequestsMatchStremioCore() {
        for (case in StremioCoreIdsGolden.cases) {
            val addons = case.addons.map { Descriptor(Manifest.decode(manifests.getValue(it)), it.transportUrl()) }
            val planned = AggrRequest.CatalogsFiltered(case.extraName, case.idTypes, case.limit).plan(addons).map { (addon, request) ->
                IdsPlanned(addon.transportUrl, request.path.resource, request.path.type, request.path.id, request.path.extra.map { it.name to it.value })
            }
            assertEquals(case.expected, planned, case.name)
        }
    }
}
