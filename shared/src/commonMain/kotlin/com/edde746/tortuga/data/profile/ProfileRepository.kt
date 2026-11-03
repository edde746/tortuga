package com.edde746.tortuga.data.profile

import com.edde746.tortuga.addons.DefaultAddons
import com.edde746.tortuga.data.Db
import com.edde746.tortuga.data.Tx
import com.edde746.tortuga.data.downloads.dropProfileDownloads
import com.edde746.tortuga.data.settings.clearActiveProfileIf
import com.edde746.tortuga.data.settings.forgetManifestChecks
import com.edde746.tortuga.data.settings.forgetRecentRooms
import com.edde746.tortuga.data.settings.forgetRecentSearches
import com.edde746.tortuga.data.settings.forgetSavedNavigation
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.data.sync.SyncRecord
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.Extras
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.uuid.Uuid
import com.edde746.tortuga.data.db.Profile as ProfileRow

/** A profile of the account. Its preferences are in [com.edde746.tortuga.data.settings.SettingsRepository]. */
data class Profile(
    val id: String,
    val name: String,
    /** The chosen avatar's id, or none. */
    val avatar: String?,
    val hasPin: Boolean,
    /** Epoch milliseconds. */
    val createdAt: Long,
    /**
     * A kids profile: every title it shows has to pass its rating settings, and leaving it takes the PIN of another
     * profile that isn't one. Only a new profile becomes one, and only while such a profile with a PIN exists.
     */
    val kids: Boolean,
    /**
     * The Home rows a kids profile starts with, in the order they were picked; optional, and titles in them still have
     * to pass the rating. Empty for any other profile.
     */
    val homeCatalogs: List<HomeCatalog>,
)

/**
 * A Home row a kids profile starts with, named as Home names its rows ([com.edde746.tortuga.data.settings.HomeLayout]):
 * the addon's transport URL, the catalog's type and id, and the extra it is asked with (`genre=Family`). A preset, not
 * a limit: titles in it still have to pass the profile's rating. The URL may carry the addon's configuration keys: it
 * is synced with the profile, like the addon list, and never shown or logged.
 */
@Serializable
data class HomeCatalog(val addonUrl: String, val type: String, val id: String, val extra: List<ExtraValue> = emptyList()) {
    companion object {
        /** What a new kids profile's Home starts with: Cinemeta's Popular movies and series in its Family genre. */
        val family: List<HomeCatalog> = listOf("movie", "series").map { type ->
            HomeCatalog(DefaultAddons.CINEMETA_URL, type, "top", listOf(ExtraValue(Extras.GENRE, "Family")))
        }
    }
}

/**
 * The account's profiles. Synced: every change is a new write of the profile record, PIN
 * included ([PinHash]), so a PIN set on one device locks the profile on every other.
 */
class ProfileRepository internal constructor(private val db: Db) {
    /** Live profiles in creation order. */
    fun observeProfiles(): Flow<List<Profile>> =
        db.observeList(db.database.profileQueries.selectAllLive()).map { rows -> rows.map { it.toModel() } }

    fun observeProfile(profileId: String): Flow<Profile?> =
        db.observeOneOrNull(db.database.profileQueries.selectLive(profileId)).map { it?.toModel() }

    suspend fun profiles(): List<Profile> = db.read { profileQueries.selectAllLive().executeAsList().map { it.toModel() } }

    suspend fun profile(profileId: String): Profile? =
        db.read { profileQueries.selectLive(profileId).executeAsOneOrNull()?.toModel() }

    /**
     * Creates a profile without a PIN; a kids profile ([Profile.kids]) when [kids], whose Home starts with
     * [homeCatalogs].
     * @throws IllegalArgumentException for a blank [name].
     */
    @Throws(IllegalArgumentException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun create(name: String, avatar: String?, kids: Boolean = false, homeCatalogs: List<HomeCatalog> = emptyList()): Profile {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Profile name must not be blank" }
        return db.write {
            val id = Uuid.random().toString()
            val row = ProfileRow(
                profile_id = id,
                name = trimmed,
                avatar = avatar,
                pin_hash = null,
                created_at = now(),
                kids = kids,
                home_catalogs = encodeHomeCatalogs(if (kids) homeCatalogs else emptyList()),
                record_id = RecordKey.Profile(id).recordId(),
                hlc = tick().toString(),
                server_version = null,
                dirty = true,
                deleted = false,
            )
            q.profileQueries.upsert(row)
            row.toModel()
        }
    }

    /** @throws IllegalArgumentException for a blank [name]. */
    @Throws(IllegalArgumentException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun rename(profileId: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Profile name must not be blank" }
        update(profileId) { it.copy(name = trimmed) }
    }

    suspend fun setAvatar(profileId: String, avatar: String?) {
        update(profileId) { it.copy(avatar = avatar) }
    }

    /** Sets the PIN, or removes it for `null`. @throws IllegalArgumentException for an empty [pin]. */
    @Throws(IllegalArgumentException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun setPin(profileId: String, pin: String?) {
        require(pin == null || pin.isNotEmpty()) { "A PIN must not be empty; pass null to remove it" }
        val hash = pin?.let(PinHash::hash)
        update(profileId) { it.copy(pin_hash = hash) }
    }

    /** Makes the profile a kids profile whose Home starts with [homeCatalogs], or, for false, an adult one. */
    suspend fun setKids(profileId: String, kids: Boolean, homeCatalogs: List<HomeCatalog>) {
        update(profileId) { it.copy(kids = kids, home_catalogs = encodeHomeCatalogs(if (kids) homeCatalogs else emptyList())) }
    }

    /** Whether [pin] is the profile's PIN. False for a profile without a PIN and a missing profile. */
    suspend fun verifyPin(profileId: String, pin: String): Boolean {
        val hash = db.read { profileQueries.selectLive(profileId).executeAsOneOrNull()?.pin_hash } ?: return false
        return PinHash.matches(hash, pin)
    }

    /**
     * Deletes the profile everywhere: tombstones it and every synced record it owns (addon list, library, stream
     * history, settings, stream picks), and drops what this device keeps for it (tracker accounts and their write
     * queue, its recent searches, the active-profile choice if it pointed here, its downloads' ownerships and Keep
     * synced rules).
     */
    suspend fun delete(profileId: String) {
        db.write {
            val row = q.profileQueries.selectLive(profileId).executeAsOneOrNull() ?: return@write
            val hlc = tick().toString()
            q.profileQueries.upsert(row.copy(hlc = hlc, dirty = true, deleted = true))
            q.addonQueries.selectList(profileId).executeAsOneOrNull()?.takeIf { !it.deleted }?.let {
                q.addonQueries.upsertList(it.copy(hlc = hlc, dirty = true, deleted = true))
            }
            q.libraryItemQueries.tombstoneProfile(hlc, profileId)
            q.streamHistoryQueries.tombstoneProfile(hlc, profileId)
            q.settingQueries.tombstoneProfile(hlc, profileId)
            q.streamPickQueries.tombstoneProfile(hlc, profileId)
            dropProfileLocalData(profileId)
        }
    }

    private suspend fun update(profileId: String, change: (ProfileRow) -> ProfileRow) {
        db.write {
            val row = q.profileQueries.selectLive(profileId).executeAsOneOrNull() ?: return@write
            val changed = change(row)
            if (changed.sameContent(row)) return@write
            q.profileQueries.upsert(changed.copy(hlc = tick().toString(), dirty = true))
        }
    }
}

/**
 * Removes what this device keeps for a deleted profile. Runs for a local delete and for a profile tombstone pulled
 * from another device; the synced records arrive as their own tombstones. Stream picks the other device didn't have
 * when it deleted the profile (made here meanwhile) become tombstones here, so their records don't outlive the profile
 * on the server. Downloads it owned alone go with their files once the download manager sees them without owners.
 */
internal fun Tx.dropProfileLocalData(profileId: String) {
    q.addonQueries.deleteAddons(profileId)
    q.trackerQueries.deleteAccountsForProfile(profileId)
    q.trackerQueries.deleteQueuedForProfile(profileId)
    q.streamPickQueries.tombstoneProfile(tick().toString(), profileId)
    dropProfileDownloads(profileId)
    clearActiveProfileIf(profileId)
    forgetRecentSearches(profileId)
    forgetRecentRooms(profileId)
    forgetSavedNavigation(profileId)
    forgetManifestChecks(profileId)
}

internal fun ProfileRow.toModel() =
    Profile(profile_id, name, avatar, pin_hash != null, created_at, kids, decodeHomeCatalogs(home_catalogs))

private val homeCatalogsSerializer = ListSerializer(HomeCatalog.serializer())

/** [catalogs] as the profile row keeps them: the JSON array the synced profile record carries too. */
internal fun encodeHomeCatalogs(catalogs: List<HomeCatalog>): String =
    SyncRecord.json.encodeToString(homeCatalogsSerializer, catalogs)

internal fun decodeHomeCatalogs(text: String): List<HomeCatalog> =
    SyncRecord.json.decodeFromString(homeCatalogsSerializer, text)

// ByteArray fields make the generated equals() compare identity; compare what a user can change.
private fun ProfileRow.sameContent(other: ProfileRow) =
    name == other.name && avatar == other.avatar && created_at == other.created_at && kids == other.kids &&
        home_catalogs == other.home_catalogs && pin_hash.contentEqualsNullable(other.pin_hash)

private fun ByteArray?.contentEqualsNullable(other: ByteArray?) =
    if (this == null || other == null) this === other else contentEquals(other)
