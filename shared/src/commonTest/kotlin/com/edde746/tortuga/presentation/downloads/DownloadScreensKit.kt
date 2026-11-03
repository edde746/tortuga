package com.edde746.tortuga.presentation.downloads

import com.edde746.tortuga.downloads.Download
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.downloads.DownloadsHarness
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.playback.session.json
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException
import kotlin.test.assertEquals

// Shared by the tests of the screens over a real DownloadManager (DownloadsHarness): Detail, Streams, Downloads,
// Settings → Downloads, the app offline and watch together's file search.

private const val META_MANIFEST =
    """{"id":"org.example.meta","version":"1.0.0","name":"Meta","types":["movie","series"],"resources":["meta"],"idPrefixes":["tt"]}"""

/** A meta addon at `meta.example.com` for `tt` ids ([serveMeta]). */
internal val META_ADDON = Descriptor(Manifest.decode(META_MANIFEST), "https://meta.example.com/manifest.json".transportUrl())

/** Installs [META_ADDON] for [profileId] (the harness's profile by default), after its other addons. */
internal suspend fun DownloadsHarness.installMetaAddon(profileId: String = this.profileId) =
    data.addons.install(profileId, META_ADDON.transportUrl, META_MANIFEST)

/**
 * [META_ADDON] answers with [metas] by id, 404 for another id; while [reachable] says no, every request fails as it
 * does without a connection.
 */
internal fun DownloadsHarness.serveMeta(vararg metas: MetaItem, reachable: () -> Boolean = { true }) {
    routes["meta.example.com"] = { request ->
        if (!reachable()) throw IOException("no connection")
        val id = request.url.segments.last().removeSuffix(".json")
        val meta = metas.firstOrNull { it.id == id }
        if (meta == null) respond("", HttpStatusCode.NotFound) else json("""{"meta":${AddonJson.encodeToString(MetaItem.serializer(), meta)}}""")
    }
}

/**
 * Downloads [videoId] of [meta] from [choice] for the harness's profile over the real transport (serve the file with
 * [DownloadsHarness.serveCdn] first) and returns the completed download.
 */
internal suspend fun DownloadsHarness.downloaded(meta: MetaItem, videoId: String, choice: StreamChoice): Download {
    val id = manager.enqueue(profileId, meta, videoId, choice).id
    settle()
    val download = checkNotNull(download(id))
    assertEquals(DownloadStatus.COMPLETED, download.status)
    return download
}
