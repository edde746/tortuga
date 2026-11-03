package com.edde746.tortuga.data.transfer

import com.edde746.tortuga.data.Db
import com.edde746.tortuga.data.Tx
import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.data.addon.toDescriptor
import com.edde746.tortuga.data.addon.writeAddonList
import com.edde746.tortuga.data.db.Setting
import com.edde746.tortuga.data.db.Stream_history
import com.edde746.tortuga.data.library.BitField8
import com.edde746.tortuga.data.library.WatchedField
import com.edde746.tortuga.data.library.mergeLibraryItem
import com.edde746.tortuga.data.library.toBody
import com.edde746.tortuga.data.library.toRow
import com.edde746.tortuga.data.profile.encodeHomeCatalogs
import com.edde746.tortuga.data.settings.kidsSettingKeys
import com.edde746.tortuga.data.sync.AddonEntry
import com.edde746.tortuga.data.sync.AddonListBody
import com.edde746.tortuga.data.sync.ClockDriftException
import com.edde746.tortuga.data.sync.Hlc
import com.edde746.tortuga.data.sync.LibraryItemBody
import com.edde746.tortuga.data.sync.LibraryStateBody
import com.edde746.tortuga.data.sync.ProfileBody
import com.edde746.tortuga.data.sync.RecordBody
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.data.sync.SettingBody
import com.edde746.tortuga.data.sync.StreamHistoryBody
import com.edde746.tortuga.data.sync.StreamPickBody
import com.edde746.tortuga.data.sync.SyncRecord
import com.edde746.tortuga.log.Log
import kotlin.time.Instant
import kotlin.uuid.Uuid
import com.edde746.tortuga.data.db.Profile as ProfileRow

/** Where an imported profile's data goes: an existing profile of this account, or a new one. */
internal sealed interface ImportTarget {
    data class Existing(val profileId: String) : ImportTarget

    /** A new profile with the file profile's name and avatar. */
    data object New : ImportTarget
}

/**
 * What an import changed, counted as the user sees it: per category (none at zero) the addons added, the titles added
 * to the library or changed there, the titles whose watch progress changed, the settings and the streams; the profiles
 * created or renamed; and the ids of those created.
 */
internal data class ImportOutcome(val counts: Map<DataCategory, Int>, val profiles: Int, val createdProfileIds: List<String>)

/**
 * Export and import of a profile's synced data as Tortuga's own file ([TransferFile]).
 *
 * An import is a merge, one local write per record that changes, so sync pushes it like any other: per record the
 * newer side by HLC wins (sync's rule), nothing local is deleted and nothing local is unwatched. Addon lists join
 * (local order first, the file's new addons after them in its order); library items take the newer side's library
 * fields and progress by sync's progress rule plus the watched marks and play counts of both sides; a title in the
 * library here stays there. Settings and stream history: newer per key. Every changed record gets a new HLC: it is
 * this device's write now, which other devices' older and newer writes meet like any other.
 *
 * Never in the file: the PIN, a kids profile's flag and Home rows (an import creates no kids profile and
 * changes none), tracker accounts and tokens, the account session, the sync state, downloads, this device's settings,
 * stream picks ([TransferFile.decode] refuses a file that holds one), tombstones.
 * A kids profile's settings ([kidsSettingKeys]: its rating cap, unrated titles, the titles a parent allowed or blocked)
 * aren't either: exported they'd leave a parent's choices in a file, imported a file could loosen them.
 */
internal class DataTransfer(private val db: Db) {
    /** The export of [categories] for [profileIds] (live profiles only), written by app version [appVersion] at [nowMillis]. */
    internal suspend fun export(
        profileIds: Set<String>,
        categories: Set<DataCategory>,
        appVersion: String,
        nowMillis: Long,
    ): TransferFile = db.read {
        val profiles = profileQueries.selectAllLive().executeAsList().filter { it.profile_id in profileIds }
        val records = buildList {
            for (profile in profiles) {
                add(record(RecordKey.Profile(profile.profile_id), profile.hlc, ProfileBody(profile.name, profile.avatar, null, profile.created_at)))
            }
            for (profile in profiles) {
                val id = profile.profile_id
                if (DataCategory.ADDONS in categories) {
                    addonQueries.selectList(id).executeAsOneOrNull()?.takeIf { !it.deleted }?.let { list ->
                        val addons = addonQueries.selectAddons(id).executeAsList().map { AddonEntry(it.transport_url, it.manifest_json, it.enabled, it.watch_activity) }
                        add(record(RecordKey.AddonList(id), list.hlc, AddonListBody(addons)))
                    }
                }
                val library = DataCategory.LIBRARY in categories
                val progress = DataCategory.PROGRESS in categories
                if (library || progress) {
                    for (row in libraryItemQueries.selectAllLive(id).executeAsList()) {
                        val body = row.toBody()
                        val carried = when {
                            library && progress -> body
                            library -> body.copy(state = LibraryStateBody())
                            // Without the library, an item is carried as Continue Watching keeps one outside it.
                            else -> body.copy(removed = true, temp = true)
                        }
                        if ((library && body.inLibrary) || (progress && body.hasProgress)) {
                            add(record(RecordKey.LibraryItem(id, row.item_id), row.hlc, carried))
                        }
                    }
                }
                if (DataCategory.SETTINGS in categories) {
                    for (row in settingQueries.selectLiveForProfile(id).executeAsList()) {
                        if (row.setting_key in kidsSettingKeys) continue
                        add(record(RecordKey.Setting(id, row.setting_key), row.hlc, SettingBody(row.value_json)))
                    }
                }
                if (DataCategory.STREAMS in categories) {
                    for (row in streamHistoryQueries.selectAllLive(id).executeAsList()) {
                        add(
                            record(
                                RecordKey.StreamHistory(id, row.meta_id, row.video_id),
                                row.hlc,
                                StreamHistoryBody(row.stream_json, row.transport_url, row.updated_at),
                            ),
                        )
                    }
                }
            }
        }
        TransferFile(
            format = TransferFile.FORMAT_NAME,
            version = TransferFile.VERSION,
            app = appVersion,
            exportedAt = Instant.fromEpochMilliseconds(nowMillis).toString(),
            categories = DataCategory.entries.filter { it in categories },
            records = records,
        )
    }

    /**
     * Merges [categories] of the file profiles in [targets] (file profile id to where it goes) into this database, in
     * one transaction. A file profile whose id is a live profile here always goes to it, whatever [targets] says.
     *
     * First this device's clock takes in the file's timestamps, as a pull does: the import's writes then come after
     * every record they merged, so a merged record outlives the copies it merged (the server's included).
     *
     * @throws ClockDriftException when the file's newest timestamp is past the clock-skew guard (sync refuses such a
     *   record the same way); nothing is written.
     */
    internal suspend fun import(file: TransferFile, categories: Set<DataCategory>, targets: Map<String, ImportTarget>): ImportOutcome =
        db.write {
            file.records.maxOfOrNull { it.hlc }?.let { receive(it) }
            val applied = categories.intersect(file.categories.toSet())
            val created = mutableListOf<String>()
            var profiles = 0
            val counts = IntArray(DataCategory.entries.size)
            val profileOf = mutableMapOf<String, String>()
            for ((fileId, body) in file.profiles) {
                val target = targets[fileId] ?: continue
                val local = q.profileQueries.selectLive(fileId).executeAsOneOrNull()
                val record = file.records.first { it.key == RecordKey.Profile(fileId) }
                profileOf[fileId] = when {
                    local != null -> {
                        if (mergeProfile(local, body, record.hlc)) profiles++
                        fileId
                    }
                    target is ImportTarget.Existing && q.profileQueries.selectLive(target.profileId).executeAsOneOrNull() != null -> target.profileId
                    else -> createProfile(body).also {
                        created += it
                        profiles++
                    }
                }
            }
            for (record in file.records) {
                val profileId = profileOf[record.key.profileId] ?: continue
                val body = record.body ?: continue
                when (body) {
                    is AddonListBody -> if (DataCategory.ADDONS in applied) counts[DataCategory.ADDONS.ordinal] += importAddons(profileId, body)
                    is LibraryItemBody -> importLibraryItem(
                        profileId,
                        (record.key as RecordKey.LibraryItem).itemId,
                        body,
                        record.hlc,
                        library = DataCategory.LIBRARY in applied,
                        progress = DataCategory.PROGRESS in applied,
                        counts,
                    )
                    is SettingBody -> if (
                        DataCategory.SETTINGS in applied &&
                        (record.key as RecordKey.Setting).key !in kidsSettingKeys &&
                        importSetting(profileId, record.key, body, record.hlc)
                    ) {
                        counts[DataCategory.SETTINGS.ordinal]++
                    }
                    is StreamHistoryBody -> if (DataCategory.STREAMS in applied && importStream(profileId, record.key as RecordKey.StreamHistory, body, record.hlc)) {
                        counts[DataCategory.STREAMS.ordinal]++
                    }
                    // A pick never gets here: TransferFile.decode refuses a file that holds one.
                    is ProfileBody, is StreamPickBody -> Unit
                }
            }
            ImportOutcome(DataCategory.entries.filter { counts[it.ordinal] > 0 }.associateWith { counts[it.ordinal] }, profiles, created)
        }

    /** The file's name and avatar when its profile record is newer. The PIN and creation time stay. */
    private fun Tx.mergeProfile(local: ProfileRow, body: ProfileBody, hlc: Hlc): Boolean {
        if (hlc <= Hlc.parse(local.hlc) || (body.name == local.name && body.avatar == local.avatar)) return false
        q.profileQueries.upsert(local.copy(name = body.name, avatar = body.avatar, hlc = tick().toString(), dirty = true))
        return true
    }

    private fun Tx.createProfile(body: ProfileBody): String {
        val id = Uuid.random().toString()
        q.profileQueries.upsert(
            ProfileRow(
                profile_id = id,
                name = body.name,
                avatar = body.avatar,
                pin_hash = null,
                created_at = now(),
                kids = false,
                home_catalogs = encodeHomeCatalogs(emptyList()),
                record_id = RecordKey.Profile(id).recordId(),
                hlc = tick().toString(),
                server_version = null,
                dirty = true,
                deleted = false,
            ),
        )
        return id
    }

    /**
     * The local list, then the file's addons it lacks, in the file's order; a local addon keeps its manifest. An added
     * addon keeps its switch but not an agreement to receive watch activity: a file can come from anywhere, so the user
     * agrees again here. Returns how many it added.
     */
    private fun Tx.importAddons(profileId: String, body: AddonListBody): Int {
        val list = q.addonQueries.selectList(profileId).executeAsOneOrNull()?.takeIf { !it.deleted }
        val local = if (list == null) emptyList() else q.addonQueries.selectAddons(profileId).executeAsList().map { it.toDescriptor() }
        val installed = local.mapTo(HashSet()) { it.transportUrl }
        val added = body.addons.distinctBy { it.transportUrl }.filter { it.transportUrl !in installed }
        if (added.isEmpty()) return 0
        writeAddonList(profileId, local + added.map { AddonDescriptor(it.transportUrl, it.manifest, it.enabled) }, tick(), dirty = true, serverVersion = null)
        return added.size
    }

    /** Adds one to [counts] at [DataCategory.LIBRARY] when the item's library part changed, at [DataCategory.PROGRESS] when its watch state did. */
    private fun Tx.importLibraryItem(
        profileId: String,
        itemId: String,
        file: LibraryItemBody,
        hlc: Hlc,
        library: Boolean,
        progress: Boolean,
        counts: IntArray,
    ) {
        val takeLibrary = library && file.inLibrary
        val takeProgress = progress && file.hasProgress
        if (!takeLibrary && !takeProgress) return
        val row = q.libraryItemQueries.select(profileId, itemId).executeAsOneOrNull()
        // Deleted here after the file's write: the delete is newer.
        if (row != null && row.deleted && Hlc.parse(row.hlc) >= hlc) return
        val local = row?.takeIf { !it.deleted }?.toBody()
        val merged = if (local == null) {
            // New here: the file's item as it is, less the categories not imported.
            val base = if (takeLibrary) file else file.copy(removed = true, temp = true)
            base.copy(state = if (progress) file.state else LibraryStateBody())
        } else {
            mergeItem(local, file, fileNewer = hlc > Hlc.parse(row.hlc), takeLibrary, takeProgress)
        }
        if (merged == local) return
        val recordId = row?.record_id ?: RecordKey.LibraryItem(profileId, itemId).recordId()
        q.libraryItemQueries.upsert(merged.toRow(profileId, itemId, recordId, tick().toString(), row?.server_version, dirty = true))
        val libraryChanged = if (local == null) takeLibrary else merged.copy(state = local.state, mtime = local.mtime) != local
        val progressChanged = if (local == null) takeProgress else merged.state != local.state
        if (libraryChanged) counts[DataCategory.LIBRARY.ordinal]++
        if (progressChanged) counts[DataCategory.PROGRESS.ordinal]++
    }

    private fun Tx.importSetting(profileId: String, key: RecordKey.Setting, body: SettingBody, hlc: Hlc): Boolean {
        val row = q.settingQueries.select(profileId, key.key).executeAsOneOrNull()
        if (row != null && (Hlc.parse(row.hlc) >= hlc || (!row.deleted && row.value_json == body.value))) return false
        q.settingQueries.upsert(
            Setting(
                profile_id = profileId,
                setting_key = key.key,
                value_json = body.value,
                record_id = row?.record_id ?: RecordKey.Setting(profileId, key.key).recordId(),
                hlc = tick().toString(),
                server_version = row?.server_version,
                dirty = true,
                deleted = false,
            ),
        )
        return true
    }

    private fun Tx.importStream(profileId: String, key: RecordKey.StreamHistory, body: StreamHistoryBody, hlc: Hlc): Boolean {
        val row = q.streamHistoryQueries.select(profileId, key.metaId, key.videoId).executeAsOneOrNull()
        val same = row != null && !row.deleted && row.stream_json == body.stream && row.transport_url == body.transportUrl
        if (row != null && (Hlc.parse(row.hlc) >= hlc || same)) return false
        q.streamHistoryQueries.upsert(
            Stream_history(
                profile_id = profileId,
                meta_id = key.metaId,
                video_id = key.videoId,
                stream_json = body.stream,
                transport_url = body.transportUrl,
                updated_at = body.updatedAt,
                record_id = row?.record_id ?: RecordKey.StreamHistory(profileId, key.metaId, key.videoId).recordId(),
                hlc = tick().toString(),
                server_version = row?.server_version,
                dirty = true,
                deleted = false,
            ),
        )
        return true
    }
}

/**
 * One library item from here and from a file: the newer side's library fields (a title in the library here stays
 * there) when [takeLibrary], and when [takeProgress] the newer side's watch state with sync's progress rule
 * ([mergeLibraryItem]), the higher play counts and the watched marks of both.
 */
internal fun mergeItem(local: LibraryItemBody, file: LibraryItemBody, fileNewer: Boolean, takeLibrary: Boolean, takeProgress: Boolean): LibraryItemBody {
    var merged = local
    if (takeLibrary && fileNewer) {
        merged = merged.copy(
            type = file.type,
            name = file.name,
            poster = file.poster,
            posterShape = file.posterShape,
            ctime = file.ctime ?: local.ctime,
            removed = if (local.inLibrary) local.removed else file.removed,
            temp = if (local.inLibrary) local.temp else file.temp,
        )
    }
    if (takeProgress) {
        val (winner, loser) = if (fileNewer) file to local else local to file
        val w = winner.state
        val l = loser.state
        merged = merged.copy(
            state = mergeLibraryItem(winner, loser).state.copy(
                timesWatched = maxOf(w.timesWatched, l.timesWatched),
                flaggedWatched = if (w.videoId == l.videoId) maxOf(w.flaggedWatched, l.flaggedWatched) else w.flaggedWatched,
                watched = joinWatched(w.watched, l.watched),
            ),
        )
    }
    return if (fileNewer && merged != local) merged.copy(mtime = maxOf(local.mtime, file.mtime)) else merged
}

/**
 * The watched marks of two of Stremio's watched fields ([com.edde746.tortuga.data.library.WatchedBitField]) together:
 * [winner]'s, plus every video [loser] marks. With the same anchor video the bits line up through it exactly; with
 * different anchors they are joined position by position, which is exact while the title's video list is the same
 * on both sides. The file's fields were checked as it was read ([TransferFile.decode]); a field stored here before such
 * fields were refused can still fail to read, and counts as no marks (logged). Returns [winner] unchanged when [loser]
 * adds nothing.
 */
internal fun joinWatched(winner: String?, loser: String?): String? {
    val w = winner?.let(::readWatched)
    val l = loser?.let(::readWatched)
    if (w == null) return if (l != null) loser else winner
    if (l == null) return winner
    val shift = if (w.anchor == l.anchor) w.anchorLength - l.anchorLength else 0
    val bits = BitField8.withValues(w.bits.values, null)
    var added = false
    for (index in 0 until l.bits.length) {
        val target = index + shift
        if (l.bits[index] && target >= 0 && !bits[target]) {
            bits[target] = true
            added = true
        }
    }
    return if (added) WatchedField(w.anchor, w.anchorLength, bits).serialize() else winner
}

/** [text] as a watched field; null when it isn't one, logged. */
private fun readWatched(text: String): WatchedField? = try {
    WatchedField.parse(text)
} catch (e: IllegalArgumentException) {
    Log.w("transfer", e) { "a stored watched field doesn't read: its marks count as none in the import" }
    null
}

private fun record(key: RecordKey, hlc: String, body: RecordBody) = SyncRecord(key, Hlc.parse(hlc), body)
