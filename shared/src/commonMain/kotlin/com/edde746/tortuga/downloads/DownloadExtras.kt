package com.edde746.tortuga.downloads

import com.edde746.tortuga.data.downloads.DownloadRow
import com.edde746.tortuga.data.downloads.DownloadStream
import com.edde746.tortuga.data.downloads.SavedMeta
import com.edde746.tortuga.data.downloads.SavedSubtitle
import com.edde746.tortuga.lang.LanguageTags
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.readBody
import com.edde746.tortuga.playback.subtitles.SubtitleRequest
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.seconds

/**
 * What a completed download keeps besides its file: the artwork (the meta's poster,
 * background and logo, the episode's thumbnail) as files under the location's `artwork/`, and the addon subtitles for
 * the subtitle languages to save (`DownloadSubtitleLanguages`, else the first owner's subtitle and second subtitle
 * languages, `ProfileSettings.savedSubtitleLanguages`), looked up by the file's hash,
 * size and name as playback looks them up, in `<video base>_subs/`. The meta itself is saved when the download is
 * queued. Each part that fails stays marked missing and is tried again later; it never fails the download.
 */
internal class DownloadExtras(private val env: DownloadEnv) {
    /**
     * Saves what [rows] miss, each read again first, while the network is up. A title's saved meta (a long series' is
     * megabytes) is read once for all of its rows.
     */
    suspend fun save(rows: List<DownloadRow>) {
        val metas = mutableMapOf<String, SavedMeta?>()
        for (listed in rows) {
            if (!env.network.value.online) return
            val row = env.repo.download(listed.id) ?: continue
            if (!row.artworkSaved) env.repo.setArtworkSaved(row.id, saveArtwork(row, metas))
            if (!row.subtitlesSaved) env.repo.setSubtitlesSaved(row.id, saveSubtitles(row))
        }
    }

    /** [metas]: the saved metas read so far, by meta id (null when there is none), with the artwork saved since. */
    private suspend fun saveArtwork(row: DownloadRow, metas: MutableMap<String, SavedMeta?>): Boolean {
        if (row.metaId !in metas) metas[row.metaId] = env.repo.savedMeta(row.metaId)
        val saved = metas[row.metaId] ?: return true
        // A saved meta that can't be read names no artwork: it stays missing until the meta is saved again (the next
        // download of the title, a Keep synced run) and is fetched then.
        val meta = saved.meta ?: return false
        val directory = env.locationPath(saved.locationId) ?: return false
        val video = meta.video(row.videoId)
        val urls = listOfNotNull(meta.preview.poster, meta.preview.background, meta.preview.logo, video?.thumbnail)
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
        var complete = true
        val added = mutableMapOf<String, String>()
        for (url in urls) {
            val known = saved.artwork[url]
            if (known != null && env.io { exists(child(directory, known)) }) continue
            val path = DownloadNames.artworkPath(url)
            if (fetch(url, directory, path)) added[url] = path else complete = false
        }
        env.repo.addArtwork(row.metaId, added)
        metas[row.metaId] = saved.copy(artwork = saved.artwork + added)
        return complete
    }

    private suspend fun saveSubtitles(row: DownloadRow): Boolean {
        val owner = env.repo.owners(row.id).firstOrNull() ?: return true
        val languages = env.device.value?.downloadSubtitleLanguages ?: env.settings.settings(owner).savedSubtitleLanguages
        val wanted = languages.mapNotNull(LanguageTags::canonical).distinct()
        if (wanted.isEmpty()) return true
        val videoPath = row.relativePath ?: return false
        val directory = env.locationPath(row.locationId) ?: return false
        // A download whose stream can't be read looks its subtitles up by its file alone.
        val choice = (row.stream as? DownloadStream.Picked)?.choice
        val request = SubtitleRequest(
            type = row.type,
            videoId = row.videoId,
            videoHash = row.videoHash,
            // The addon's size first, as stremio-core sends it.
            videoSize = choice?.stream?.behaviorHints?.videoSize ?: row.totalBytes,
            filename = row.filename,
            streamSubtitles = choice?.stream?.subtitles.orEmpty(),
            streamAddonName = choice?.addonName.orEmpty(),
        )
        // What the player would add as the file opens; the rest stay with the addons.
        val found = env.subtitles.resolve(request, env.addons(owner), wanted).added
        val folder = DownloadNames.subtitlesDirectory(videoPath)
        val existing = env.repo.subtitles(row.id).map { it.relativePath }.toSet()
        var complete = true
        found.forEachIndexed { index, subtitle ->
            val path = "$folder/${index + 1}.${subtitle.language ?: "und"}.${subtitleExtension(subtitle.url)}"
            if (path in existing) return@forEachIndexed
            if (fetch(subtitle.url, directory, path)) {
                env.repo.addSubtitle(row.id, SavedSubtitle(path, subtitle.title ?: subtitle.language.orEmpty(), subtitle.language))
            } else {
                complete = false
            }
        }
        return complete
    }

    /**
     * GETs [url] into [path] under [directory] (through a `.part` file, moved into place only when it holds something);
     * false when that fails, so the part stays missing and is asked for again.
     */
    private suspend fun fetch(url: String, directory: String, path: String): Boolean {
        if (!env.io { env.createDirectories(directory, path.substringBeforeLast('/')) }) return false
        val target = child(directory, path)
        val part = Path("$target${DownloadNames.PART}")
        return try {
            env.http.prepareGet(url) {
                expectSuccess = false
                timeout { requestTimeoutMillis = FETCH_TIMEOUT.inWholeMilliseconds }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    Log.d(TAG) { "an extra answered HTTP ${response.status.value}" }
                    return@execute false
                }
                response.readBody { channel ->
                    withContext(env.io) {
                        var bytes = 0L
                        env.files.sink(part).use { sink ->
                            while (!channel.exhausted()) bytes += sink.transferFrom(channel.readBuffer(CHUNK_BYTES))
                        }
                        if (bytes > 0) return@withContext env.files.move(part, target)
                        Log.d(TAG) { "an extra answered an empty body" }
                        env.files.delete(part)
                        false
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG) { "an extra failed (${e::class.simpleName})" }
            env.io { delete(part) }
            false
        }
    }

    private companion object {
        const val TAG = "downloads"
        const val CHUNK_BYTES = 64L * 1024
        val FETCH_TIMEOUT = 60.seconds
        val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub", "ttml", "smi")

        /** The subtitle file's extension from its URL; `srt` (what subtitle addons serve) when it names none. */
        fun subtitleExtension(url: String): String =
            url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()
                .takeIf { it in SUBTITLE_EXTENSIONS } ?: "srt"
    }
}
