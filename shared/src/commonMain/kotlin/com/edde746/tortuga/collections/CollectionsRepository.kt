package com.edde746.tortuga.collections

import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.data.settings.settingsJson
import com.edde746.tortuga.data.sync.Hlc
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.data.sync.SettingBody
import com.edde746.tortuga.data.sync.SyncRecord
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.presentation.board.collectionHomeKey
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.sync.engine.utf8Length
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [collection] is too large to sync ([CollectionsRepository.fitsSync]); [message] says so, naming it. */
class CollectionTooLargeException(val collection: Collection) : Exception(T.collections.tooLarge(title = collection.title))

/**
 * What an import did: the ids it [added] (new to the profile) and [replaced] (the profile had them), and the
 * collections it left out because they are too large to sync ([tooLarge]).
 */
data class ImportResult(val added: List<String>, val replaced: List<String>, val tooLarge: List<Collection>)

/**
 * A profile's collections: each one its own per-profile setting `collection.<id>` ([collectionSettingKey]), synced as
 * its own record, its stored value the collection's JSON. Their place and visibility on Home are Home's layout's
 * ([ProfileSetting.HomeRows], under [collectionHomeKey]): a new collection goes first there (after Continue Watching),
 * also while the profile has Home's default layout, one that is replaced keeps its place and visibility, and one that
 * is deleted leaves the layout. Home's default (an empty layout: Reset to default) shows the collections first, in id
 * order.
 *
 * A collection whose record would be over what sync pushes ([fitsSync]) is never stored: it would stay on this device
 * alone.
 */
class CollectionsRepository internal constructor(private val settings: SettingsRepository) {
    /** The profile's collections, in id order; again on every change. One whose value doesn't read is left out (logged). */
    fun observe(profileId: String): Flow<List<Collection>> =
        settings.observeValues(profileId, COLLECTION_SETTING_PREFIX).map { it.toCollections() }

    suspend fun collections(profileId: String): List<Collection> =
        settings.values(profileId, COLLECTION_SETTING_PREFIX).toCollections()

    /**
     * Stores [collection]: replaces the one with its id, or adds it first on Home.
     *
     * @throws CollectionTooLargeException when it is too large to sync.
     */
    suspend fun save(profileId: String, collection: Collection) {
        if (!fitsSync(profileId, collection)) throw CollectionTooLargeException(collection)
        settings.edit(profileId) { store(collection) }
    }

    /** Deletes the collection [id] on every device (a tombstone) and drops it from Home's layout. */
    suspend fun delete(profileId: String, id: String) {
        val key = collectionHomeKey(id)
        settings.edit(profileId) {
            remove(collectionSettingKey(id))
            update(ProfileSetting.HomeRows) { layout -> layout.copy(order = layout.order - key, hidden = layout.hidden - key) }
        }
    }

    /**
     * Stores [collections] in one transaction: each replaces the profile's collection with its id (keeping its place
     * and visibility on Home), or is added first on Home, in their order. Nothing else changes. One too large to sync
     * is left out ([ImportResult.tooLarge]).
     */
    suspend fun import(profileId: String, collections: List<Collection>): ImportResult {
        val (fit, tooLarge) = collections.partition { fitsSync(profileId, it) }
        return settings.edit(profileId) {
            val replaced = fit.filter { value(collectionSettingKey(it.id)) != null }.map { it.id }
            fit.asReversed().forEach { store(it) }
            ImportResult(added = fit.map { it.id } - replaced.toSet(), replaced = replaced, tooLarge = tooLarge)
        }
    }

    /**
     * Whether [collection]'s record fits what sync pushes: its payload, measured as the sync engine measures one, at
     * most [SyncProtocol.MAX_RECORD_BYTES], and its record id a valid one.
     */
    fun fitsSync(profileId: String, collection: Collection): Boolean {
        val key = RecordKey.Setting(profileId, collectionSettingKey(collection.id))
        // Every clock reading has the same length.
        val payload = SyncRecord(key, Hlc.ZERO, SettingBody(encode(collection))).encode()
        return SyncProtocol.isValidRecordId(key.recordId()) && utf8Length(payload) <= SyncProtocol.MAX_RECORD_BYTES
    }

    /**
     * Stores [collection]; a new one goes first in Home's layout. On the default layout (an empty order) the order
     * becomes the new one, then the profile's other collections in their default (id) order: the rows it doesn't list
     * (addons, trackers) come after them, as by default ([com.edde746.tortuga.data.settings.HomeLayout.arrange]).
     */
    private fun SettingsRepository.SettingsEdit.store(collection: Collection) {
        val settingKey = collectionSettingKey(collection.id)
        val added = value(settingKey) == null
        set(settingKey, encode(collection))
        if (added) {
            val key = collectionHomeKey(collection.id)
            update(ProfileSetting.HomeRows) { layout ->
                val order = layout.order.ifEmpty {
                    values(COLLECTION_SETTING_PREFIX).keys.map { collectionHomeKey(it.removePrefix(COLLECTION_SETTING_PREFIX)) }
                }
                layout.copy(order = listOf(key) + (order - key))
            }
        }
    }

    private fun Map<String, String>.toCollections(): List<Collection> = mapNotNull { (key, json) ->
        try {
            settingsJson.decodeFromString(Collection.serializer(), json)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, e) { "the stored collection $key doesn't decode: it is left out" }
            null
        }
    }

    private fun encode(collection: Collection): String = settingsJson.encodeToString(Collection.serializer(), collection)

    private companion object {
        const val TAG = "collections"
    }
}

/** What every collection's setting key starts with. */
internal const val COLLECTION_SETTING_PREFIX = "collection."

/** The per-profile setting that holds the collection [id]. */
internal fun collectionSettingKey(id: String): String = "$COLLECTION_SETTING_PREFIX$id"
