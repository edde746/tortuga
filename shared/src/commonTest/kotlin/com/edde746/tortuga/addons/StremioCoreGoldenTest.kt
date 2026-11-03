package com.edde746.tortuga.addons

import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.model.TransportUrl
import kotlin.test.Test
import kotlin.test.assertEquals

/** URL encoding, install links, deep links and legacy items against stremio-core's own output ([StremioCoreGolden]). */
class StremioCoreGoldenTest {
    @Test
    fun resourceUrlsMatchStremioCore() {
        for (case in StremioCoreGolden.resourceUrls) {
            val path = ResourcePath(case.resource, case.type, case.id, case.extra.map { (name, value) -> ExtraValue(name, value) })
            assertEquals(case.expected, TransportUrl.checked(case.transportUrl)?.let { TransportUrl.resourceUrl(it, path) }, "$case")
        }
    }

    @Test
    fun installLinksNormalizeLikeStremioCore() {
        for (case in StremioCoreGolden.installLinks) {
            assertEquals(case.expected, TransportUrl.normalize(case.input), "input: ${case.input}")
        }
    }

    @Test
    fun detailLinksRoundTrip() {
        for (case in StremioCoreGolden.detailLinks) {
            val link = DeepLink.Detail(case.type, case.id, case.videoId)
            assertEquals(case.expected, link.toUrl(), "$case")
            // An empty video id (a trailing `/`) reads as no video.
            assertEquals(link.copy(videoId = case.videoId?.takeIf { it.isNotEmpty() }), DeepLink.parse(case.expected), case.expected)
        }
    }

    @Test
    fun legacyCatalogItemsGetStremioCoreLinksAndTrailers() {
        val item = (AddonResponse.decode(StremioCoreGolden.LEGACY_CATALOG_JSON).content as ResourceContent.Metas).items.single()
        assertEquals(StremioCoreGolden.legacyLinks, item.links.map { LinkCase(it.name, it.category, it.url) })
        assertEquals(StremioCoreGolden.legacyTrailerYtIds, item.trailerStreams.map { (it.source as StreamSource.YouTube).ytId })
        assertEquals(StremioCoreGolden.LEGACY_RELEASE_INFO, item.releaseInfo)
        assertEquals(StremioCoreGolden.LEGACY_RELEASED, item.released.toString())
        assertEquals(StremioCoreGolden.LEGACY_POSTER_SHAPE, item.posterShape.name.lowercase())
        assertEquals(PosterShape.POSTER, item.posterShape)
        assertEquals(null, item.poster)
    }

    @Test
    fun genreLinksParseAsDiscoverLinks() {
        val genre = StremioCoreGolden.legacyLinks.last()
        assertEquals(
            DeepLink.Discover(DefaultAddons.CINEMETA_URL, "movie", "top", listOf(ExtraValue("genre", genre.name))),
            DeepLink.parse(genre.url),
        )
    }
}
