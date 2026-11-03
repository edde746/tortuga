package com.edde746.tortuga.fixtures

import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.ManifestCatalog
import com.edde746.tortuga.model.ManifestResource
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every recorded fixture decodes to what stremio-core decodes from it: same items in the same order, and the same
 * fields that matter (source, ids, names, release info, links and trailer counts, episode order).
 * [StremioCoreFixtureOracle] holds stremio-core's summary lines; this test renders ours in the same format.
 */
class FixtureOracleTest {
    @Test
    fun liveCorpusDecodesLikeStremioCore() {
        assertEquals(
            FixtureCorpus.files.keys.filter { it.startsWith("live/") && it != "live/index.json" }.toSet(),
            StremioCoreFixtureOracle.summaries.keys,
        )
        for ((file, expected) in StremioCoreFixtureOracle.summaries) {
            val body = FixtureCorpus.text(file)
            val actual = if (file.endsWith("manifest.json")) manifest(Manifest.decode(body)) else response(AddonResponse.decode(body))
            assertEquals(expected, actual, file)
        }
    }

    private fun response(response: AddonResponse): List<String> {
        assertEquals(0, response.dropped)
        return when (val c = response.content) {
            is ResourceContent.Metas -> c.items.map(::preview)
            is ResourceContent.Meta -> listOf(preview(c.item.preview)) + c.item.videos.map { v ->
                "video|${v.id}|${v.title}|${v.seriesInfo?.let { "${it.season}x${it.episode}" }.orEmpty()}|${v.released ?: ""}"
            }
            is ResourceContent.Streams -> c.items.map(::stream)
            is ResourceContent.Subtitles -> c.items.map { "${it.id}|${it.lang}|${it.url}|${it.label.orEmpty()}" }
            else -> error("unexpected $c")
        }
    }

    private fun preview(m: MetaItemPreview) = listOf(
        m.id, m.type, m.name, m.releaseInfo.orEmpty(), m.released?.toString().orEmpty(), m.posterShape.name.lowercase(),
        m.poster.orEmpty(), "links=${m.links.size}", "trailers=${m.trailerStreams.size}",
        "default=${m.behaviorHints.defaultVideoId.orEmpty()}",
    ).joinToString("|")

    private fun stream(s: Stream): String {
        val source = when (val src = s.source) {
            is StreamSource.Url -> "url:${src.url}"
            is StreamSource.YouTube -> "yt:${src.ytId}"
            is StreamSource.PeerToPeer -> "peerToPeer:${src.infoHash}:${src.fileIdx ?: ""}"
            is StreamSource.External -> "external:${src.externalUrl.orEmpty()}"
            is StreamSource.PlayerFrame -> "frame:${src.playerFrameUrl}"
            is StreamSource.Nntp -> "nntp"
            is StreamSource.Archive -> "archive"
        }
        val hints = s.behaviorHints
        return listOf(source, s.name.orEmpty(), s.description.orEmpty(), hints.bingeGroup.orEmpty(), hints.filename.orEmpty(), "subs=${s.subtitles.size}")
            .joinToString("|")
    }

    private fun manifest(m: Manifest): List<String> {
        val head = "${m.id}|${m.version}|${m.name}|types=${m.types.joinToString(",")}|prefixes=${m.idPrefixes?.joinToString(",") ?: "null"}"
        val resources = m.resources.map { r ->
            when (r) {
                is ManifestResource.Short -> "resource|${r.name}"
                is ManifestResource.Full ->
                    "resource|${r.name}|${r.types?.joinToString(",") ?: "null"}|${r.idPrefixes?.joinToString(",") ?: "null"}"
            }
        }
        fun catalog(kind: String, c: ManifestCatalog) = "$kind|${c.type}|${c.id}|${c.name.orEmpty()}|" + c.extra.joinToString(",") { e ->
            e.name + (if (e.isRequired) "!" else "") + (if (e.options.isEmpty()) "" else "[${e.options.size}]")
        }
        val hints = m.behaviorHints
        return listOf(head) + resources + m.catalogs.map { catalog("catalog", it) } + m.addonCatalogs.map { catalog("addonCatalog", it) } +
            "hints|adult=${hints.adult}|p2p=${hints.p2p}|configurable=${hints.configurable}|configurationRequired=${hints.configurationRequired}"
    }
}
