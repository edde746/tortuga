package com.edde746.tortuga.data.settings

import com.edde746.tortuga.data.Db
import com.edde746.tortuga.data.Tx
import com.edde746.tortuga.data.db.Setting
import com.edde746.tortuga.data.sync.RecordKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Per-profile settings, synced per key: two devices changing different settings both keep theirs. Besides the
 * [ProfileSetting]s, a setting may be one of a family of keys sharing a prefix (`collection.<id>`), read and written
 * as stored JSON ([observeValues], [edit]).
 */
class SettingsRepository internal constructor(private val db: Db) {
    fun observe(profileId: String): Flow<ProfileSettings> =
        db.observeList(db.database.settingQueries.selectLiveForProfile(profileId)).map { it.toSettings() }

    suspend fun settings(profileId: String): ProfileSettings =
        db.read { settingQueries.selectLiveForProfile(profileId).executeAsList().toSettings() }

    /** The stored JSON of [profileId]'s settings whose key starts with [prefix], by key in key order; again on change. */
    internal fun observeValues(profileId: String, prefix: String): Flow<Map<String, String>> =
        db.observeList(db.database.settingQueries.selectLiveForProfile(profileId)).map { it.withPrefix(prefix) }.distinctUntilChanged()

    /** The stored JSON of [profileId]'s settings whose key starts with [prefix], by key in key order. */
    internal suspend fun values(profileId: String, prefix: String): Map<String, String> =
        db.read { settingQueries.selectLiveForProfile(profileId).executeAsList().withPrefix(prefix) }

    /** Stores [value] (cleaned by the setting's rules, e.g. clamped to its range). */
    suspend fun <T> set(profileId: String, setting: ProfileSetting<T>, value: T) {
        val json = setting.encodeSetting(value)
        db.write { store(profileId, setting.key, q.settingQueries.select(profileId, setting.key).executeAsOneOrNull(), json) }
    }

    /**
     * Stores what [change] makes of the stored value (the default while unset, and while it can't be read), read in the
     * same transaction: edits made one after another (two quick steps, two list edits) each start from the one before.
     * Nothing is written when the value stays the same, so a value that can't be read stays stored until a change
     * replaces it.
     */
    suspend fun <T> update(profileId: String, setting: ProfileSetting<T>, change: (T) -> T) {
        edit(profileId) { update(setting, change) }
    }

    /** Runs [block]'s reads and changes of [profileId]'s settings as one transaction: all of them are stored, or none. */
    internal suspend fun <R> edit(profileId: String, block: SettingsEdit.() -> R): R = db.write { SettingsEdit(this, profileId).block() }

    /** Back to the default on every device (a tombstone, so a later default change applies to this profile too). */
    suspend fun reset(profileId: String, setting: ProfileSetting<*>) {
        edit(profileId) { remove(setting.key) }
    }

    /** One profile's settings inside a transaction ([edit]). */
    internal class SettingsEdit(private val tx: Tx, private val profileId: String) {
        /** The stored JSON of the setting [key]; null while unset. */
        fun value(key: String): String? = row(key)?.takeUnless { it.deleted }?.value_json

        /** [SettingsRepository.values] in this transaction. */
        fun values(prefix: String): Map<String, String> =
            tx.q.settingQueries.selectLiveForProfile(profileId).executeAsList().withPrefix(prefix)

        /** Stores [json] as the setting [key]'s value; the caller checked it ([requireSettingValue]). */
        fun set(key: String, json: String) {
            tx.store(profileId, key, row(key), json)
        }

        /** Unsets the setting [key] on every device (a tombstone); nothing when it is unset. */
        fun remove(key: String) {
            val stored = row(key) ?: return
            if (stored.deleted) return
            tx.q.settingQueries.upsert(stored.copy(hlc = tx.tick().toString(), dirty = true, deleted = true))
        }

        /** [SettingsRepository.update] in this transaction. */
        fun <T> update(setting: ProfileSetting<T>, change: (T) -> T) {
            val stored = row(setting.key)
            val current = setting.decodeSetting(stored?.takeUnless { it.deleted }?.value_json)
            val next = setting.normalize(change(current))
            if (next != current) tx.store(profileId, setting.key, stored, setting.encodeSetting(next))
        }

        private fun row(key: String): Setting? = tx.q.settingQueries.select(profileId, key).executeAsOneOrNull()
    }
}

/** Writes [json] as the setting [key]'s value unless [stored] (its row, if any) already has it. */
private fun Tx.store(profileId: String, key: String, stored: Setting?, json: String) {
    if (stored != null && !stored.deleted && stored.value_json == json) return
    q.settingQueries.upsert(
        Setting(
            profile_id = profileId,
            setting_key = key,
            value_json = json,
            record_id = stored?.record_id ?: RecordKey.Setting(profileId, key).recordId(),
            hlc = tick().toString(),
            server_version = stored?.server_version,
            dirty = true,
            deleted = false,
        ),
    )
}

private fun List<Setting>.toSettings() = ProfileSettings.fromStored(associate { it.setting_key to it.value_json })

private fun List<Setting>.withPrefix(prefix: String): Map<String, String> =
    filter { it.setting_key.startsWith(prefix) }.sortedBy { it.setting_key }.associate { it.setting_key to it.value_json }
