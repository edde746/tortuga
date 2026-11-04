package com.edde746.tortuga.data.sync

import com.edde746.tortuga.data.Db
import com.edde746.tortuga.data.Tx
import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.data.addon.writeAddonList
import com.edde746.tortuga.data.db.Addon_list
import com.edde746.tortuga.data.db.Library_item
import com.edde746.tortuga.data.db.Setting
import com.edde746.tortuga.data.db.Stream_history
import com.edde746.tortuga.data.db.Stream_pick
import com.edde746.tortuga.data.db.TortugaDatabase
import com.edde746.tortuga.data.downloads.dropAllDownloadOwners
import com.edde746.tortuga.data.library.mergeLibraryItem
import com.edde746.tortuga.data.library.toBody
import com.edde746.tortuga.data.library.toRow
import com.edde746.tortuga.data.profile.decodeHomeCatalogs
import com.edde746.tortuga.data.profile.dropProfileLocalData
import com.edde746.tortuga.data.profile.encodeHomeCatalogs
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.forgetAllManifestChecks
import com.edde746.tortuga.data.settings.forgetAllPinsAndPrompts
import com.edde746.tortuga.data.settings.forgetAllRecentRooms
import com.edde746.tortuga.data.settings.forgetAllRecentSearches
import com.edde746.tortuga.data.settings.forgetAllSavedNavigation
import com.edde746.tortuga.data.streams.prunePicks
import com.edde746.tortuga.data.streams.toBody
import com.edde746.tortuga.data.streams.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import com.edde746.tortuga.data.db.Profile as ProfileRow

/** A local change the server doesn't have yet. */
class DirtyRecord internal constructor(
    internal val key: RecordKey,
    val recordId: String,
    /** The server version this change is based on; `null` when the server has never had the record. */
    val baseVersion: Long?,
    val hlc: Hlc,
    val deleted: Boolean,
    /** The record's JSON (HLC inside), the payload the sync engine pushes. */
    val payload: String,
)

/** The server stored the push of [recordId] at [hlc] as [serverVersion]. */
data class PushAck(val recordId: String, val hlc: Hlc, val serverVersion: Long)

/** A record from the server: pulled, or the current version returned for a stale push. */
class RemoteRecord(val recordId: String, val serverVersion: Long, val payload: JsonObject)

/**
 * A remote record [SyncStore.applyRemote] didn't apply.
 *
 * @property acceptedFromMillis for [Reason.CLOCK_DRIFT], the wall clock reading of this device from which the record
 *   is no longer too far ahead ([ClockDriftException.acceptedFromMillis]).
 */
data class RejectedRecord(val recordId: String, val reason: Reason, val message: String, val acceptedFromMillis: Long? = null) {
    enum class Reason {
        /** Not a record this build can read: malformed, an unknown kind, or content [SyncRecord.decode] refuses. */
        UNREADABLE,

        /** A payload format newer than this build reads ([SyncRecord.FORMAT]): an update of the app reads it. */
        NEWER_FORMAT,

        /** The payload's natural key isn't the record id it arrived under. */
        ID_MISMATCH,

        /**
         * Its HLC is too far ahead of this device's clock ([ClockDriftException]); one of the two clocks is wrong. The
         * sync engine pulls it again once this device's clock reaches [acceptedFromMillis].
         */
        CLOCK_DRIFT,
    }
}

/**
 * The sync engine's view of local storage: the local changes to push, the acknowledgements that clear
 * them, and the merge of records from other devices. Records are profiles, addon lists (one per profile), library
 * items, stream history entries, settings and stream picks; everything else stays on the device.
 *
 * Merge rules ([applyRemote]): last writer wins per record by HLC; for library items, progress keeps the furthest
 * `timeWatched` and the latest `lastWatched`. Deletes are tombstones and win or lose by HLC like any write.
 */
class SyncStore internal constructor(private val db: Db) {
    /**
     * The HLC of the newest local change that awaits a push, or null when none does. Emits when it changes: on every
     * new local change (each has a newer HLC than any before), also while older changes stay unpushed.
     */
    fun observeNewestDirty(): Flow<Hlc?> = db.observeOne(db.database.syncStateQueries.newestDirty())
        .map { hlc -> hlc.takeIf { it.isNotEmpty() }?.let(Hlc::parse) }
        .distinctUntilChanged()

    /**
     * Up to [limit] local changes after [after] (all when `null`), oldest first: by HLC, then by record id, since a
     * profile's deletion stamps all its records with one HLC. Page with the last record returned; a record written
     * again after being read comes back in a later call. Each table gives its first [limit] rows; only those of the
     * page are encoded.
     */
    suspend fun dirtyRecords(after: DirtyRecord?, limit: Int): List<DirtyRecord> = db.read {
        val hlc = (after?.hlc ?: Hlc.ZERO).toString()
        val id = after?.recordId.orEmpty()
        val max = limit.toLong()
        buildList {
            profileQueries.selectDirty(hlc, id, max).executeAsList().forEach { add(Dirty(it.hlc, it.record_id) { it.toDirty() }) }
            addonQueries.selectDirty(hlc, id, max).executeAsList().forEach { add(Dirty(it.hlc, it.record_id) { it.toDirty(this@read) }) }
            libraryItemQueries.selectDirty(hlc, id, max).executeAsList().forEach { add(Dirty(it.hlc, it.record_id) { it.toDirty() }) }
            streamHistoryQueries.selectDirty(hlc, id, max).executeAsList().forEach { add(Dirty(it.hlc, it.record_id) { it.toDirty() }) }
            settingQueries.selectDirty(hlc, id, max).executeAsList().forEach { add(Dirty(it.hlc, it.record_id) { it.toDirty() }) }
            streamPickQueries.selectDirty(hlc, id, max).executeAsList().forEach { add(Dirty(it.hlc, it.record_id) { it.toDirty() }) }
        }.sortedWith(DIRTY_ORDER).take(limit).map { it.encode() }
    }

    /** A dirty row by its place in [dirtyRecords]'s order, [encode]d only once it makes the page. */
    private class Dirty(hlc: String, val recordId: String, val encode: () -> DirtyRecord) {
        val hlc: Hlc = Hlc.parse(hlc)
    }

    /** How many local changes await a push: what [dropSyncedData] would delete that the server doesn't have. */
    suspend fun unsyncedCount(): Long = db.read { syncStateQueries.countDirty().executeAsOne() }

    /**
     * Records the server's versions for pushed changes. A record written again since the push stays dirty, now based
     * on the new version.
     */
    suspend fun markPushed(acks: List<PushAck>) {
        db.write {
            for (ack in acks) {
                val hlc = ack.hlc.toString()
                q.profileQueries.markPushed(ack.serverVersion, ack.recordId, hlc)
                q.addonQueries.markPushed(ack.serverVersion, ack.recordId, hlc)
                q.libraryItemQueries.markPushed(ack.serverVersion, ack.recordId, hlc)
                q.streamHistoryQueries.markPushed(ack.serverVersion, ack.recordId, hlc)
                q.settingQueries.markPushed(ack.serverVersion, ack.recordId, hlc)
                q.streamPickQueries.markPushed(ack.serverVersion, ack.recordId, hlc)
            }
        }
    }

    /**
     * Merges [records] from the server into local storage, in one transaction, and advances this device's clock past
     * each record's HLC. A record that loses to a local change leaves that change dirty, now based on the record's
     * server version, so the next push supersedes it; a merge that combines both sides is stored as a new local
     * change. Returns the records it didn't apply for what the server sent: one this build can't read, of a newer
     * format, under another id, or too far ahead of this device's clock; the others still apply. A local row that a
     * record merges with and that can't be read (its HLC, its content) fails the call with its exception and stores
     * nothing: that is this device's fault, not the record's. Profiles that picks arrived for are pruned to what they
     * keep ([prunePicks]).
     */
    suspend fun applyRemote(records: List<RemoteRecord>): List<RejectedRecord> = db.write {
        val picked = mutableSetOf<String>()
        val rejected = records.mapNotNull { remote -> applyOne(remote, picked) }
        picked.forEach { prunePicks(it) }
        rejected
    }

    /**
     * Drops every synced record, tombstones and unpushed changes included, the pull cursors with the account the data
     * belonged to, and what this device keeps for the account's profiles (tracker connections and their write queue,
     * the active-profile choice, recent searches, recent watch-together rooms, each profile's back stacks, the PIN
     * lockouts, the "Finished watching?" prompts, the downloads' ownerships and Keep synced rules: the download
     * manager then deletes every download, files included, as their stream JSON can hold the account's API keys), in
     * one transaction: a drop cut short leaves no cursor that would skip the records on the next sign-in. For when the
     * records belong to nobody here: the account signed out or was deleted, or another account signs in. Other device
     * settings stay.
     */
    suspend fun dropSyncedData() {
        db.write {
            q.syncStateQueries.deleteAll()
            q.profileQueries.deleteAll()
            q.addonQueries.deleteAllAddons()
            q.addonQueries.deleteAllLists()
            q.libraryItemQueries.deleteAll()
            q.streamHistoryQueries.deleteAll()
            q.settingQueries.deleteAll()
            q.streamPickQueries.deleteAll()
            q.trackerQueries.deleteAllAccounts()
            q.trackerQueries.deleteAllQueued()
            dropAllDownloadOwners()
            q.deviceSettingQueries.delete(DeviceSetting.ActiveProfileId.key)
            forgetAllRecentSearches()
            forgetAllRecentRooms()
            forgetAllSavedNavigation()
            forgetAllManifestChecks()
            forgetAllPinsAndPrompts()
        }
    }

    /**
     * Forgets which server versions every record (tombstones included) was pushed at or pulled from and marks it
     * dirty, based on no version. For a server restored from a backup, which lost writes this device saw: pull from
     * cursor 0 first, and [applyRemote] settles each record the server still has (the same write comes back clean,
     * with its restored version); what is left dirty is pushed as new.
     */
    suspend fun forgetServerVersions() {
        db.write {
            q.profileQueries.forgetServerVersions()
            q.addonQueries.forgetServerVersions()
            q.libraryItemQueries.forgetServerVersions()
            q.streamHistoryQueries.forgetServerVersions()
            q.settingQueries.forgetServerVersions()
            q.streamPickQueries.forgetServerVersions()
        }
    }

    /** Applies [remote]; adds the profile of a pick it stores to [picked]. */
    private fun Tx.applyOne(remote: RemoteRecord, picked: MutableSet<String>): RejectedRecord? {
        val record = try {
            SyncRecord.decode(remote.payload)
        } catch (e: NewerFormatException) {
            return RejectedRecord(remote.recordId, RejectedRecord.Reason.NEWER_FORMAT, e.message ?: "Newer format")
        } catch (e: IllegalArgumentException) {
            return RejectedRecord(remote.recordId, RejectedRecord.Reason.UNREADABLE, e.message ?: "Unreadable record")
        }
        if (record.key.recordId() != remote.recordId) {
            return RejectedRecord(remote.recordId, RejectedRecord.Reason.ID_MISMATCH, "Payload key doesn't match the record id")
        }
        try {
            receive(record.hlc)
        } catch (e: ClockDriftException) {
            return RejectedRecord(remote.recordId, RejectedRecord.Reason.CLOCK_DRIFT, e.message ?: "Clock drift", e.acceptedFromMillis)
        }
        when (val key = record.key) {
            is RecordKey.Profile -> applyProfile(key, record, remote)
            is RecordKey.AddonList -> applyAddonList(key, record, remote)
            is RecordKey.LibraryItem -> applyLibraryItem(key, record, remote)
            is RecordKey.StreamHistory -> applyStreamHistory(key, record, remote)
            is RecordKey.Setting -> applySetting(key, record, remote)
            is RecordKey.StreamPick -> if (applyStreamPick(key, record, remote)) picked += key.profileId
        }
        return null
    }

    private fun Tx.applyProfile(key: RecordKey.Profile, record: SyncRecord, remote: RemoteRecord) {
        val row = q.profileQueries.select(key.profileId).executeAsOneOrNull()
        val resolved = resolve(row?.let { Local(Hlc.parse(it.hlc), if (it.deleted) null else it.toBody()) }, record) ?: return
        val body = resolved.body as ProfileBody?
        val next = if (body != null) {
            ProfileRow(
                profile_id = key.profileId,
                name = body.name,
                avatar = body.avatar,
                pin_hash = body.pinHash?.let { Base64.decode(it) },
                created_at = body.createdAt,
                kids = body.kids,
                home_catalogs = encodeHomeCatalogs(body.homeCatalogs),
                record_id = remote.recordId,
                hlc = resolved.hlc.toString(),
                server_version = remote.serverVersion,
                dirty = resolved.dirty,
                deleted = false,
            )
        } else {
            checkNotNull(row).copy(hlc = resolved.hlc.toString(), server_version = remote.serverVersion, dirty = resolved.dirty, deleted = true)
        }
        q.profileQueries.upsert(next)
        if (body == null && row?.deleted == false) dropProfileLocalData(key.profileId)
    }

    private fun Tx.applyAddonList(key: RecordKey.AddonList, record: SyncRecord, remote: RemoteRecord) {
        val list = q.addonQueries.selectList(key.profileId).executeAsOneOrNull()
        val local = list?.let { Local(Hlc.parse(it.hlc), if (it.deleted) null else q.addonListBody(key.profileId)) }
        val resolved = resolve(local, record) ?: return
        val body = resolved.body as AddonListBody?
        if (body != null) {
            val addons = body.addons.distinctBy { it.transportUrl }.map { AddonDescriptor(it.transportUrl, it.manifest, it.enabled, it.watchActivity) }
            writeAddonList(key.profileId, addons, resolved.hlc, resolved.dirty, remote.serverVersion)
        } else {
            q.addonQueries.deleteAddons(key.profileId)
            q.addonQueries.upsertList(
                checkNotNull(list).copy(hlc = resolved.hlc.toString(), server_version = remote.serverVersion, dirty = resolved.dirty, deleted = true),
            )
        }
    }

    private fun Tx.applyLibraryItem(key: RecordKey.LibraryItem, record: SyncRecord, remote: RemoteRecord) {
        val row = q.libraryItemQueries.select(key.profileId, key.itemId).executeAsOneOrNull()
        val local = row?.let { Local(Hlc.parse(it.hlc), if (it.deleted) null else it.toBody()) }
        val resolved = resolve(local, record) { winner, loser ->
            mergeLibraryItem(winner as LibraryItemBody, loser as LibraryItemBody)
        } ?: return
        val body = resolved.body as LibraryItemBody?
        val next = body?.toRow(key.profileId, key.itemId, remote.recordId, resolved.hlc.toString(), remote.serverVersion, resolved.dirty)
            ?: checkNotNull(row).copy(hlc = resolved.hlc.toString(), server_version = remote.serverVersion, dirty = resolved.dirty, deleted = true)
        q.libraryItemQueries.upsert(next)
    }

    private fun Tx.applyStreamHistory(key: RecordKey.StreamHistory, record: SyncRecord, remote: RemoteRecord) {
        val row = q.streamHistoryQueries.select(key.profileId, key.metaId, key.videoId).executeAsOneOrNull()
        val local = row?.let { Local(Hlc.parse(it.hlc), if (it.deleted) null else it.toBody()) }
        val resolved = resolve(local, record) ?: return
        val body = resolved.body as StreamHistoryBody?
        val next = if (body != null) {
            Stream_history(
                profile_id = key.profileId,
                meta_id = key.metaId,
                video_id = key.videoId,
                stream_json = body.stream,
                transport_url = body.transportUrl,
                updated_at = body.updatedAt,
                record_id = remote.recordId,
                hlc = resolved.hlc.toString(),
                server_version = remote.serverVersion,
                dirty = resolved.dirty,
                deleted = false,
            )
        } else {
            checkNotNull(row).copy(hlc = resolved.hlc.toString(), server_version = remote.serverVersion, dirty = resolved.dirty, deleted = true)
        }
        q.streamHistoryQueries.upsert(next)
    }

    private fun Tx.applySetting(key: RecordKey.Setting, record: SyncRecord, remote: RemoteRecord) {
        val row = q.settingQueries.select(key.profileId, key.key).executeAsOneOrNull()
        val local = row?.let { Local(Hlc.parse(it.hlc), if (it.deleted) null else SettingBody(it.value_json)) }
        val resolved = resolve(local, record) ?: return
        val body = resolved.body as SettingBody?
        val next = if (body != null) {
            Setting(
                profile_id = key.profileId,
                setting_key = key.key,
                value_json = body.value,
                record_id = remote.recordId,
                hlc = resolved.hlc.toString(),
                server_version = remote.serverVersion,
                dirty = resolved.dirty,
                deleted = false,
            )
        } else {
            checkNotNull(row).copy(hlc = resolved.hlc.toString(), server_version = remote.serverVersion, dirty = resolved.dirty, deleted = true)
        }
        q.settingQueries.upsert(next)
    }

    /** Returns whether a live pick was stored. */
    private fun Tx.applyStreamPick(key: RecordKey.StreamPick, record: SyncRecord, remote: RemoteRecord): Boolean {
        val row = q.streamPickQueries.select(key.pickId).executeAsOneOrNull()
        val local = row?.let { Local(Hlc.parse(it.hlc), if (it.deleted) null else it.toBody()) }
        val resolved = resolve(local, record) ?: return false
        val body = resolved.body as StreamPickBody?
        val next = body?.toRow(key, remote.recordId, resolved.hlc.toString(), remote.serverVersion, resolved.dirty)
            ?: checkNotNull(row).copy(candidates_json = "[]", hlc = resolved.hlc.toString(), server_version = remote.serverVersion, dirty = resolved.dirty, deleted = true)
        q.streamPickQueries.upsert(next)
        return body != null
    }

    /** A stored record's HLC and content; `null` content is a tombstone. */
    private class Local(val hlc: Hlc, val body: RecordBody?)

    /** What to store: the content (`null`: a tombstone), its HLC, and whether it still has to be pushed. */
    private class Resolution(val body: RecordBody?, val hlc: Hlc, val dirty: Boolean)

    /**
     * Last writer wins between [local] and [remote], with [merge] combining two live versions (winner first).
     * Returns `null` when there is nothing to store: a tombstone for a record this device never had.
     */
    private fun Tx.resolve(
        local: Local?,
        remote: SyncRecord,
        merge: (RecordBody, RecordBody) -> RecordBody = { winner, _ -> winner },
    ): Resolution? {
        if (local == null) return remote.body?.let { Resolution(it, remote.hlc, dirty = false) }
        val order = remote.hlc.compareTo(local.hlc)
        // The same write: this device's own, echoed back, or one applied before.
        if (order == 0) return Resolution(local.body, local.hlc, dirty = false)
        val remoteWins = order > 0
        val winner = if (remoteWins) remote.body else local.body
        val loser = if (remoteWins) local.body else remote.body
        val body = if (winner != null && loser != null) merge(winner, loser) else winner
        return when {
            remoteWins && body == remote.body -> Resolution(body, remote.hlc, dirty = false)
            // The server's copy is older than this device's: push this one over it.
            !remoteWins && body == local.body -> Resolution(body, local.hlc, dirty = true)
            // Content from both sides: a new write that every device will converge on.
            else -> Resolution(body, tick(), dirty = true)
        }
    }

    private companion object {
        /**
         * The `selectDirty` queries' order, `(hlc, record_id)`: an HLC's text sorts as the HLC does, and SQLite compares
         * text by its UTF-8 bytes, which is code point order ([compareCodePoints]), not [String.compareTo]'s UTF-16.
         */
        val DIRTY_ORDER = Comparator<Dirty> { a, b ->
            a.hlc.compareTo(b.hlc).takeIf { it != 0 } ?: compareCodePoints(a.recordId, b.recordId)
        }

        /**
         * [a] against [b] by code point. UTF-16 order differs from it only where a surrogate meets U+E000–U+FFFF, so the
         * first differing unit decides, surrogates ranked above that range.
         */
        fun compareCodePoints(a: String, b: String): Int {
            for (i in 0 until minOf(a.length, b.length)) {
                if (a[i] != b[i]) return rank(a[i]) - rank(b[i])
            }
            return a.length - b.length
        }

        private fun rank(c: Char): Int = when {
            c.code >= 0xE000 -> c.code - 0x800
            c.code >= 0xD800 -> c.code + 0x2000
            else -> c.code
        }
    }
}

private fun ProfileRow.toBody() =
    ProfileBody(name, avatar, pin_hash?.let { Base64.encode(it) }, created_at, kids, decodeHomeCatalogs(home_catalogs))

private fun Stream_history.toBody() = StreamHistoryBody(stream_json, transport_url, updated_at)

private fun TortugaDatabase.addonListBody(profileId: String) =
    AddonListBody(addonQueries.selectAddons(profileId).executeAsList().map { AddonEntry(it.transport_url, it.manifest_json, it.enabled, it.watch_activity) })

private fun dirty(key: RecordKey, recordId: String, hlc: String, serverVersion: Long?, body: RecordBody?): DirtyRecord {
    val parsed = Hlc.parse(hlc)
    return DirtyRecord(key, recordId, serverVersion, parsed, body == null, SyncRecord(key, parsed, body).encode())
}

private fun ProfileRow.toDirty() =
    dirty(RecordKey.Profile(profile_id), record_id, hlc, server_version, if (deleted) null else toBody())

private fun Addon_list.toDirty(database: TortugaDatabase) =
    dirty(RecordKey.AddonList(profile_id), record_id, hlc, server_version, if (deleted) null else database.addonListBody(profile_id))

private fun Library_item.toDirty() =
    dirty(RecordKey.LibraryItem(profile_id, item_id), record_id, hlc, server_version, if (deleted) null else toBody())

private fun Stream_history.toDirty() =
    dirty(RecordKey.StreamHistory(profile_id, meta_id, video_id), record_id, hlc, server_version, if (deleted) null else toBody())

private fun Setting.toDirty() =
    dirty(RecordKey.Setting(profile_id, setting_key), record_id, hlc, server_version, if (deleted) null else SettingBody(value_json))

private fun Stream_pick.toDirty() =
    dirty(RecordKey.StreamPick(profile_id, pick_id), record_id, hlc, server_version, if (deleted) null else toBody())
