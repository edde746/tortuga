package com.edde746.tortuga.data.downloads

import com.edde746.tortuga.data.Db
import com.edde746.tortuga.data.Tx
import com.edde746.tortuga.data.db.Download_meta
import com.edde746.tortuga.data.db.Download_subtitle
import com.edde746.tortuga.data.db.Download_sync_rule
import com.edde746.tortuga.data.db.SelectDownloads
import com.edde746.tortuga.data.db.SelectRules
import com.edde746.tortuga.downloads.DownloadFailure
import com.edde746.tortuga.downloads.DownloadFailureKind
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.playback.resolve.OpenSubtitlesHash
import com.edde746.tortuga.playback.session.StreamChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import com.edde746.tortuga.data.db.Download as Row

/**
 * One download as stored ([DownloadRepository]). Its [stream]'s URL and addon URL can embed API keys: [toString]
 * leaves them out.
 */
internal data class DownloadRow(
    val id: Long,
    val type: String,
    val metaId: String,
    val videoId: String,
    val title: String,
    val year: Int?,
    val episodeTitle: String?,
    val season: Int?,
    val episode: Int?,
    val poster: String?,
    val thumbnail: String?,
    val status: DownloadStatus,
    val failure: DownloadFailure?,
    val stream: DownloadStream,
    val locationId: String,
    val relativePath: String?,
    val totalBytes: Long?,
    val downloadedBytes: Long,
    val validator: String?,
    val videoHash: String?,
    val filename: String?,
    val addedAt: Long,
    val completedAt: Long?,
    val attempts: Int,
    val keepSynced: Boolean,
    val transferring: Boolean,
    val artworkSaved: Boolean,
    val subtitlesSaved: Boolean,
    /** Only filled by [DownloadRepository.observeDownloads] and [DownloadRepository.downloads]. */
    val owners: Set<String> = emptySet(),
    /** The meta's saved artwork (addon URL → path under [artworkLocationId]); filled as [owners] is. */
    val artwork: Map<String, String> = emptyMap(),
    val artworkLocationId: String? = null,
) {
    override fun toString(): String =
        "DownloadRow(id=$id, metaId=$metaId, videoId=$videoId, status=$status, failure=$failure, path=$relativePath, " +
            "bytes=$downloadedBytes/$totalBytes, attempts=$attempts, owners=$owners)"
}

/**
 * The stream a download downloads from, decoded once as its row is read: a stored stream that no longer decodes is
 * [Unreadable] (logged then), never taken for none.
 */
internal sealed interface DownloadStream {
    /** No stream was picked for it: it is stored [DownloadFailureKind.NO_STREAM]. */
    data object None : DownloadStream

    data class Picked(val choice: StreamChoice) : DownloadStream

    /** Its stored stream no longer decodes: it can't be resolved again or compared with another. */
    data object Unreadable : DownloadStream
}

/** The meta saved with a meta's downloads, and its artwork saved as files under [locationId]. */
internal data class SavedMeta(
    val metaId: String,
    val type: String,
    /** Null when the stored meta no longer decodes (logged as it is read). */
    val meta: MetaItem?,
    val locationId: String,
    val artwork: Map<String, String>,
)

/**
 * A Keep synced rule of a profile, with its series' [title] and [poster] URL as last fetched (null for a rule older than
 * them whose series had no download, until its next run).
 */
internal data class SyncRule(
    val profileId: String,
    val metaId: String,
    val type: String,
    val episodes: Int?,
    val lastRun: Long?,
    val paused: Boolean,
    val title: String?,
    val poster: String?,
    /** The series' saved artwork (addon URL → path under [artworkLocationId]); only filled by [DownloadRepository.observeRules]. */
    val artwork: Map<String, String> = emptyMap(),
    val artworkLocationId: String? = null,
)

/** A saved subtitle file of a download. */
internal data class SavedSubtitle(val relativePath: String, val title: String, val language: String?)

/** An item's watch state as `library_item` keeps it ([DownloadRepository.observeWatchStates]). */
internal data class WatchState(val watched: String?, val timesWatched: Long, val flaggedWatched: Long)

/** What [DownloadRepository.enqueue] did. */
internal sealed interface Enqueued {
    val id: Long

    /** A new download. */
    data class Added(override val id: Long) : Enqueued

    /**
     * It existed; the profile owns it now (and a failed or cancelled one was queued again: [requeued]). [discarded] is
     * the path (under [locationId]) of the part file a requeue from another stream leaves behind, for the caller to
     * delete.
     */
    data class Owned(override val id: Long, val requeued: Boolean, val discarded: String? = null, val locationId: String? = null) : Enqueued
}

/** What a new download shows and downloads ([DownloadRepository.enqueue]). */
internal data class NewDownload(
    val type: String,
    val metaId: String,
    val videoId: String,
    val title: String,
    val year: Int?,
    val episodeTitle: String?,
    val season: Int?,
    val episode: Int?,
    val poster: String?,
    val thumbnail: String?,
    /** Null for an episode no stream was picked for: it is stored [DownloadFailureKind.NO_STREAM]. */
    val stream: StreamChoice?,
    val locationId: String,
    val keepSynced: Boolean,
) {
    override fun toString(): String = "NewDownload(metaId=$metaId, videoId=$videoId, stream=$stream)"
}

/**
 * The downloads' storage (data/db/Download.sq): downloads, their owners, the saved meta and subtitles,
 * and the Keep synced rules. Device-local. [com.edde746.tortuga.downloads.DownloadManager] is its only user.
 */
internal class DownloadRepository(private val db: Db) {
    /** Every download with its owners and its meta's artwork, most recently added first. */
    fun observeDownloads(): Flow<List<DownloadRow>> =
        db.observeList(db.database.downloadQueries.selectDownloads()).map { it.toModels() }

    suspend fun downloads(): List<DownloadRow> = db.read { downloadQueries.selectDownloads().executeAsList().toModels() }

    suspend fun download(id: Long): DownloadRow? = db.read { downloadQueries.select(id).executeAsOneOrNull()?.toModel() }

    suspend fun byVideo(metaId: String, videoId: String): DownloadRow? =
        db.read { downloadQueries.selectByVideo(metaId, videoId).executeAsOneOrNull()?.toModel() }

    /** The queue in its order: the oldest first. */
    suspend fun queued(): List<DownloadRow> = db.read { downloadQueries.selectQueued().executeAsList().map { it.toModel() } }

    suspend fun withStatus(statuses: Collection<DownloadStatus>): List<DownloadRow> =
        db.read { downloadQueries.selectByStatus(statuses.map { it.name }).executeAsList().map { it.toModel() } }

    suspend fun transferring(): List<DownloadRow> = db.read { downloadQueries.selectTransferring().executeAsList().map { it.toModel() } }

    suspend fun missingExtras(): List<DownloadRow> = db.read { downloadQueries.selectMissingExtras().executeAsList().map { it.toModel() } }

    /** The most recently added download of [metaId] that has a stream (the season picker's reference). */
    suspend fun lastWithStream(metaId: String): DownloadRow? =
        db.read {
            downloadQueries.selectLastWithStream(metaId).executeAsOneOrNull()?.let { downloadQueries.select(it).executeAsOneOrNull()?.toModel() }
        }

    suspend fun owners(id: Long): List<String> = db.read { downloadQueries.selectOwners(id).executeAsList() }

    /**
     * Queues [new] for [profileId]: a new download, or [profileId] added to the owners of the one for that video (a
     * failed or cancelled one queued again from [new]'s stream, when it has one; when that stream isn't the stored one's
     * release ([StreamChoice.sameRelease]), or the stored one can't be read, nothing of the file is kept:
     * [Enqueued.Owned.discarded]). One asked for by hand is no longer Keep synced's. [meta] is stored as the meta's
     * saved meta (its artwork kept), unless [keepSavedMeta] and one is saved already: a batch's later episodes leave
     * the one its first stored.
     */
    suspend fun enqueue(profileId: String, new: NewDownload, meta: MetaItem, keepSavedMeta: Boolean = false): Enqueued = db.write {
        if (!keepSavedMeta || q.downloadQueries.countMeta(new.metaId).executeAsOne() == 0L) storeMeta(new.metaId, meta, new.locationId)
        val existing = q.downloadQueries.selectByVideo(new.metaId, new.videoId).executeAsOneOrNull()?.toModel()
        if (existing == null) {
            q.downloadQueries.insert(
                type = new.type,
                metaId = new.metaId,
                videoId = new.videoId,
                title = new.title,
                year = new.year?.toLong(),
                episodeTitle = new.episodeTitle,
                season = new.season?.toLong(),
                episode = new.episode?.toLong(),
                poster = new.poster,
                thumbnail = new.thumbnail,
                status = if (new.stream == null) DownloadStatus.FAILED.name else DownloadStatus.QUEUED.name,
                failure = if (new.stream == null) DownloadFailureKind.NO_STREAM.name else null,
                httpStatus = null,
                streamJson = new.stream?.streamJson(),
                addonUrl = new.stream?.addonUrl,
                addonName = new.stream?.addonName,
                locationId = new.locationId,
                addedAt = now(),
                keepSynced = new.keepSynced,
            )
            val id = q.downloadQueries.lastInsertId().executeAsOne()
            q.downloadQueries.insertOwner(id, profileId, now())
            return@write Enqueued.Added(id)
        }
        q.downloadQueries.insertOwner(existing.id, profileId, now())
        if (!new.keepSynced && existing.keepSynced) q.downloadQueries.setKeepSynced(false, existing.id)
        val retryable = existing.status == DownloadStatus.FAILED || existing.status == DownloadStatus.CANCELLED
        val stream = new.stream
        if (!retryable || stream == null) return@write Enqueued.Owned(existing.id, requeued = false)
        val changed = (existing.stream as? DownloadStream.Picked)?.choice?.sameRelease(stream) != true
        q.downloadQueries.requeue(stream.streamJson(), stream.addonUrl, stream.addonName, existing.id)
        if (changed) q.downloadQueries.resetFile(existing.id)
        Enqueued.Owned(existing.id, requeued = true, existing.relativePath.takeIf { changed }, existing.locationId)
    }

    /** Adds [profileId] to [id]'s owners; one added [byHand] is no longer Keep synced's. */
    suspend fun addOwner(id: Long, profileId: String, byHand: Boolean) {
        db.write {
            q.downloadQueries.insertOwner(id, profileId, now())
            if (byHand) q.downloadQueries.setKeepSynced(false, id)
        }
    }

    /** Removes [profileId] from [id]'s owners; returns the owners left. */
    suspend fun removeOwner(id: Long, profileId: String): List<String> = db.write {
        q.downloadQueries.deleteOwner(id, profileId)
        q.downloadQueries.selectOwners(id).executeAsList()
    }

    suspend fun setStatus(id: Long, status: DownloadStatus, failure: DownloadFailure? = null) {
        db.write { q.downloadQueries.setStatus(status.name, failure?.kind?.name, failure?.httpStatus?.toLong(), id) }
    }

    /** Sets every download in [ids] to [status] (and [failure]). */
    suspend fun setStatuses(ids: Collection<Long>, status: DownloadStatus, failure: DownloadFailure? = null) {
        if (ids.isEmpty()) return
        db.write { ids.forEach { q.downloadQueries.setStatus(status.name, failure?.kind?.name, failure?.httpStatus?.toLong(), it) } }
    }

    /** Queues a failed or cancelled download again from its stored stream, attempts reset. */
    suspend fun requeue(id: Long): Boolean = db.write {
        val row = q.downloadQueries.select(id).executeAsOneOrNull() ?: return@write false
        val json = row.stream_json ?: return@write false
        q.downloadQueries.requeue(json, row.addon_url, row.addon_name, id)
        true
    }

    suspend fun resetFile(id: Long) {
        db.write { q.downloadQueries.resetFile(id) }
    }

    suspend fun setStream(id: Long, choice: StreamChoice) {
        db.write { q.downloadQueries.setStream(choice.streamJson(), choice.addonUrl, choice.addonName, id) }
    }

    suspend fun setPath(id: Long, relativePath: String) {
        db.write { q.downloadQueries.setPath(relativePath, id) }
    }

    suspend fun setResolved(id: Long, totalBytes: Long?, videoHash: String?, filename: String?) {
        db.write { q.downloadQueries.setResolved(totalBytes, videoHash, filename, id) }
    }

    suspend fun setProgress(id: Long, bytes: Long) {
        db.write { q.downloadQueries.setProgress(bytes, id) }
    }

    suspend fun setValidator(id: Long, validator: String?) {
        db.write { q.downloadQueries.setValidator(validator, id) }
    }

    suspend fun setTransferring(id: Long, transferring: Boolean) {
        db.write { q.downloadQueries.setTransferring(transferring, id) }
    }

    suspend fun setAttempts(id: Long, attempts: Int) {
        db.write { q.downloadQueries.setAttempts(attempts.toLong(), id) }
    }

    suspend fun complete(id: Long, bytes: Long, validator: String?) {
        db.write { q.downloadQueries.complete(bytes, validator, now(), id) }
    }

    suspend fun setArtworkSaved(id: Long, saved: Boolean) {
        db.write { q.downloadQueries.setArtworkSaved(saved, id) }
    }

    suspend fun setSubtitlesSaved(id: Long, saved: Boolean) {
        db.write { q.downloadQueries.setSubtitlesSaved(saved, id) }
    }

    /** At launch: downloads the last process left resolving or downloading (not with the platform) are queued again. */
    suspend fun requeueInterrupted() {
        db.write { q.downloadQueries.requeueInterrupted() }
    }

    /**
     * Deletes [id] with its owners and saved subtitles, and its meta's saved meta when no other download of the meta is
     * left. Returns that saved meta when it went (its artwork files go too).
     */
    suspend fun delete(id: Long): SavedMeta? = db.write {
        val row = q.downloadQueries.select(id).executeAsOneOrNull() ?: return@write null
        q.downloadQueries.delete(id)
        q.downloadQueries.deleteOwners(id)
        q.downloadQueries.deleteSubtitles(id)
        if (q.downloadQueries.selectForMeta(row.meta_id).executeAsList().isNotEmpty()) return@write null
        val meta = q.downloadQueries.selectMeta(row.meta_id).executeAsOneOrNull()?.toModel()
        q.downloadQueries.deleteMeta(row.meta_id)
        meta
    }

    /** Settings → Delete all: every download loses its owners (the manager then deletes each) and every rule goes. */
    suspend fun disownAll() {
        db.write { dropAllDownloadOwners() }
    }

    suspend fun savedMeta(metaId: String): SavedMeta? = db.read { downloadQueries.selectMeta(metaId).executeAsOneOrNull()?.toModel() }

    /** Whether [metaId] has a saved meta, without reading it. */
    suspend fun hasSavedMeta(metaId: String): Boolean = db.read { downloadQueries.countMeta(metaId).executeAsOne() > 0 }

    /** Stores [meta] as [metaId]'s saved meta, keeping the artwork already saved for it. */
    suspend fun saveMeta(metaId: String, meta: MetaItem, locationId: String) {
        db.write { storeMeta(metaId, meta, locationId) }
    }

    private fun Tx.storeMeta(metaId: String, meta: MetaItem, locationId: String) {
        val existing = q.downloadQueries.selectMeta(metaId).executeAsOneOrNull()
        q.downloadQueries.upsertMeta(
            Download_meta(
                meta_id = metaId,
                type = meta.type,
                meta_json = AddonJson.encodeToString(MetaItem.serializer(), meta),
                location_id = existing?.location_id ?: locationId,
                artwork_json = existing?.artwork_json ?: ArtworkJson.encode(emptyMap()),
                saved_at = now(),
            ),
        )
    }

    /** Adds [saved] (addon URL → path) to [metaId]'s saved artwork. */
    suspend fun addArtwork(metaId: String, saved: Map<String, String>) {
        if (saved.isEmpty()) return
        db.write {
            val existing = q.downloadQueries.selectMeta(metaId).executeAsOneOrNull() ?: return@write
            q.downloadQueries.setArtwork(ArtworkJson.encode(ArtworkJson.decode(metaId, existing.artwork_json) + saved), metaId)
        }
    }

    suspend fun subtitles(id: Long): List<SavedSubtitle> =
        db.read { downloadQueries.selectSubtitles(id).executeAsList().map { SavedSubtitle(it.relative_path, it.title, it.language) } }

    suspend fun addSubtitle(id: Long, subtitle: SavedSubtitle) {
        db.write { q.downloadQueries.insertSubtitle(Download_subtitle(id, subtitle.relativePath, subtitle.title, subtitle.language)) }
    }

    /** [profileId]'s rules by their series' name, with the series' saved artwork. */
    fun observeRules(profileId: String): Flow<List<SyncRule>> =
        db.observeList(db.database.downloadQueries.selectRules(profileId)).map { rows -> rows.map { it.toModel() } }

    suspend fun rules(): List<SyncRule> = db.read { downloadQueries.selectAllRules().executeAsList().map { it.toModel() } }

    suspend fun rule(profileId: String, metaId: String): SyncRule? =
        db.read { downloadQueries.selectRule(profileId, metaId).executeAsOneOrNull()?.toModel() }

    /** Sets [profileId]'s rule for [metaId] running (not paused), keeping when it last ran. */
    suspend fun setRule(profileId: String, metaId: String, type: String, episodes: Int?, title: String, poster: String?) {
        db.write {
            val lastRun = q.downloadQueries.selectRule(profileId, metaId).executeAsOneOrNull()?.last_run
            q.downloadQueries.upsertRule(Download_sync_rule(profileId, metaId, type, episodes?.toLong(), lastRun, false, title, poster))
        }
    }

    suspend fun setRulePaused(profileId: String, metaId: String, paused: Boolean) {
        db.write { q.downloadQueries.setRulePaused(paused, profileId, metaId) }
    }

    /** The series' [title] and [poster] on every rule for [metaId]. */
    suspend fun setRuleTitle(metaId: String, title: String, poster: String?) {
        db.write { q.downloadQueries.setRuleTitle(title, poster, metaId) }
    }

    suspend fun deleteRule(profileId: String, metaId: String) {
        db.write { q.downloadQueries.deleteRule(profileId, metaId) }
    }

    suspend fun setRuleRun(profileId: String, metaId: String, at: Long) {
        db.write { q.downloadQueries.setRuleRun(at, profileId, metaId) }
    }

    /** The watch state of every (profile, item) a download or a rule follows. */
    fun observeWatchStates(): Flow<Map<Pair<String, String>, WatchState>> =
        db.observeList(db.database.downloadQueries.selectWatchStates()).map { rows ->
            rows.associate { (it.profile_id to it.item_id) to WatchState(it.watched, it.times_watched, it.flagged_watched) }
        }
}

/** Removes what this device keeps for a deleted profile's downloads: its ownerships (the manager deletes downloads left without owners) and its Keep synced rules. */
internal fun Tx.dropProfileDownloads(profileId: String) {
    q.downloadQueries.deleteOwnersForProfile(profileId)
    q.downloadQueries.deleteRulesForProfile(profileId)
}

/** The account's data is dropped: every download loses its owners (the manager deletes them, files included) and every rule goes. */
internal fun Tx.dropAllDownloadOwners() {
    q.downloadQueries.deleteAllOwners()
    q.downloadQueries.deleteAllRules()
}

private fun Row.toModel() = DownloadRow(
    id = id,
    type = type,
    metaId = meta_id,
    videoId = video_id,
    title = title,
    year = year?.toInt(),
    episodeTitle = episode_title,
    season = season?.toInt(),
    episode = episode?.toInt(),
    poster = poster,
    thumbnail = thumbnail,
    status = statusOf(status),
    failure = failureOf(failure, http_status),
    stream = streamOf(id, stream_json, addon_url, addon_name),
    locationId = location_id,
    relativePath = relative_path,
    totalBytes = total_bytes,
    downloadedBytes = downloaded_bytes,
    validator = validator,
    // Rows saved before stream hints were checked at decoding can hold an addon's hash of another form.
    videoHash = video_hash?.let(OpenSubtitlesHash::parse),
    filename = filename,
    addedAt = added_at,
    completedAt = completed_at,
    attempts = attempts.toInt(),
    keepSynced = keep_synced,
    transferring = transferring,
    artworkSaved = artwork_saved,
    subtitlesSaved = subtitles_saved,
)

/** The rows of [DownloadRepository.downloads]: each meta's artwork decoded once, for all of its downloads. */
private fun List<SelectDownloads>.toModels(): List<DownloadRow> {
    val artwork = HashMap<String, Map<String, String>>()
    return map { it.toModel(artwork) }
}

/**
 * The table's columns read as [Row.toModel] reads them, with the owners and the meta's artwork the projection adds
 * ([artwork]: the metas' artwork decoded so far, by meta id).
 */
private fun SelectDownloads.toModel(artwork: MutableMap<String, Map<String, String>>) = Row(
    id, type, meta_id, video_id, title, year, episode_title, season, episode, poster, thumbnail, status, failure,
    http_status, stream_json, addon_url, addon_name, location_id, relative_path, total_bytes, downloaded_bytes,
    validator, video_hash, filename, added_at, completed_at, attempts, keep_synced, transferring, artwork_saved,
    subtitles_saved,
).toModel().copy(
    owners = owners.split(',').filter { it.isNotEmpty() }.toSet(),
    artwork = artwork_json?.let { json -> artwork.getOrPut(meta_id) { ArtworkJson.decode(meta_id, json) } }.orEmpty(),
    artworkLocationId = artwork_location_id,
)

/** A download's stored stream; one that doesn't decode is logged (the redacted error, never the JSON: it holds keys). */
private fun streamOf(id: Long, json: String?, addonUrl: String?, addonName: String?): DownloadStream {
    json ?: return DownloadStream.None
    return try {
        val stream = AddonJson.decodeFromString(Stream.serializer(), json)
        DownloadStream.Picked(StreamChoice(stream, requireNotNull(addonUrl) { "a stream without its addon" }, addonName.orEmpty()))
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, e) { "download $id: its stored stream doesn't decode" }
        DownloadStream.Unreadable
    }
}

private fun Download_meta.toModel() = SavedMeta(meta_id, type, metaOf(meta_id, meta_json), location_id, ArtworkJson.decode(meta_id, artwork_json))

private fun metaOf(metaId: String, json: String): MetaItem? = try {
    AddonJson.decodeFromString(MetaItem.serializer(), json)
} catch (e: IllegalArgumentException) {
    Log.w(TAG, e) { "the saved meta of $metaId doesn't decode" }
    null
}

private fun Download_sync_rule.toModel() = SyncRule(profile_id, meta_id, type, episodes?.toInt(), last_run, paused, title, poster)

private fun SelectRules.toModel() = SyncRule(
    profileId = profile_id,
    metaId = meta_id,
    type = type,
    episodes = episodes?.toInt(),
    lastRun = last_run,
    paused = paused,
    title = title,
    poster = poster,
    artwork = artwork_json?.let { ArtworkJson.decode(meta_id, it) }.orEmpty(),
    artworkLocationId = artwork_location_id,
)

/** An unknown name (a later version's) reads as failed, so it can be retried or deleted. */
private fun statusOf(name: String): DownloadStatus = DownloadStatus.entries.firstOrNull { it.name == name } ?: DownloadStatus.FAILED

private fun failureOf(name: String?, httpStatus: Long?): DownloadFailure? {
    name ?: return null
    val kind = DownloadFailureKind.entries.firstOrNull { it.name == name } ?: DownloadFailureKind.NETWORK
    return DownloadFailure(kind, httpStatus?.toInt())
}

/** `download_meta.artwork_json`: the addon's image URL → the saved file's path under the location. */
internal object ArtworkJson {
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun encode(artwork: Map<String, String>): String = Json.encodeToString(serializer, artwork)

    /** [metaId]'s saved artwork; an unreadable value is logged and reads as nothing saved (the artwork is fetched again). */
    fun decode(metaId: String, json: String): Map<String, String> = try {
        Json.decodeFromString(serializer, json)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, e) { "the saved artwork of $metaId doesn't decode; it is fetched again" }
        emptyMap()
    }
}

private const val TAG = "downloads"
