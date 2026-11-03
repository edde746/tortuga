package com.edde746.tortuga.data.addon

import com.edde746.tortuga.data.Db
import com.edde746.tortuga.data.Tx
import com.edde746.tortuga.data.db.Addon
import com.edde746.tortuga.data.db.Addon_list
import com.edde746.tortuga.data.sync.Hlc
import com.edde746.tortuga.data.sync.RecordKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * An installed addon as stored: the transport URL it was installed from and its manifest, both opaque here (the
 * addon registry parses them), whether it is [enabled] and whether the user agreed it receives their
 * [watchActivity]. The URL can embed an API key; never log it.
 */
data class InstalledAddon(
    val transportUrl: String,
    val manifestJson: String,
    val position: Int,
    val enabled: Boolean = true,
    val watchActivity: Boolean = false,
)

/** What [AddonRepository.replaceAll] installs: an addon without a position, which the list order gives. */
data class AddonDescriptor(
    val transportUrl: String,
    val manifestJson: String,
    val enabled: Boolean = true,
    val watchActivity: Boolean = false,
)

/**
 * A profile's installed addons in the user's order. Every change writes the whole list as one
 * synced record, so installs, removals and reorders on two devices never interleave into an order neither made.
 */
class AddonRepository internal constructor(private val db: Db) {
    fun observeAddons(profileId: String): Flow<List<InstalledAddon>> =
        db.observeList(db.database.addonQueries.selectAddons(profileId)).map { rows -> rows.map { it.toModel() } }

    suspend fun addons(profileId: String): List<InstalledAddon> =
        db.read { addonQueries.selectAddons(profileId).executeAsList().map { it.toModel() } }

    /**
     * Installs an addon at the end of the list, or, when [transportUrl] is already installed, replaces its manifest
     * in place (an upgrade keeps its position, its switch and the user's agreement).
     */
    suspend fun install(profileId: String, transportUrl: String, manifestJson: String) {
        change(profileId) { addons ->
            val index = addons.indexOfFirst { it.transportUrl == transportUrl }
            if (index < 0) {
                addons + AddonDescriptor(transportUrl, manifestJson)
            } else {
                addons.toMutableList().apply { set(index, get(index).copy(manifestJson = manifestJson)) }
            }
        }
    }

    /** Removes an addon. Returns whether it was installed. */
    suspend fun remove(profileId: String, transportUrl: String): Boolean {
        var removed = false
        change(profileId) { addons ->
            addons.filterNot { it.transportUrl == transportUrl }.also { removed = it.size != addons.size }
        }
        return removed
    }

    /**
     * Puts the installed addons in the order of [transportUrls].
     *
     * @throws IllegalArgumentException unless [transportUrls] names every installed addon exactly once.
     */
    @Throws(IllegalArgumentException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun reorder(profileId: String, transportUrls: List<String>) {
        change(profileId) { addons ->
            val byUrl = addons.associateBy { it.transportUrl }
            require(transportUrls.size == addons.size && transportUrls.toSet() == byUrl.keys) {
                "The new order must name every installed addon exactly once"
            }
            transportUrls.map { byUrl.getValue(it) }
        }
    }

    /**
     * Replaces the whole list, e.g. with the registry's defaults for a new profile.
     *
     * @throws IllegalArgumentException when a transport URL appears twice.
     */
    @Throws(IllegalArgumentException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun replaceAll(profileId: String, addons: List<AddonDescriptor>) {
        require(addons.distinctBy { it.transportUrl }.size == addons.size) { "Duplicate transport URL" }
        change(profileId) { addons }
    }

    private suspend fun change(profileId: String, transform: (List<AddonDescriptor>) -> List<AddonDescriptor>) {
        db.write {
            val current = q.addonQueries.selectAddons(profileId).executeAsList().map { it.toDescriptor() }
            val next = transform(current)
            if (next == current) return@write
            writeAddonList(profileId, next, tick(), dirty = true, serverVersion = null)
        }
    }
}

/**
 * Stores [addons] as the profile's list and stamps the list record. [serverVersion] `null` keeps the stored one (a
 * local change is still based on it).
 */
internal fun Tx.writeAddonList(profileId: String, addons: List<AddonDescriptor>, hlc: Hlc, dirty: Boolean, serverVersion: Long?) {
    val existing = q.addonQueries.selectList(profileId).executeAsOneOrNull()
    q.addonQueries.deleteAddons(profileId)
    addons.forEachIndexed { position, addon ->
        q.addonQueries.insertAddon(Addon(profileId, addon.transportUrl, addon.manifestJson, position.toLong(), addon.enabled, addon.watchActivity))
    }
    q.addonQueries.upsertList(
        Addon_list(
            profile_id = profileId,
            record_id = existing?.record_id ?: RecordKey.AddonList(profileId).recordId(),
            hlc = hlc.toString(),
            server_version = serverVersion ?: existing?.server_version,
            dirty = dirty,
            deleted = false,
        ),
    )
}

private fun Addon.toModel() = InstalledAddon(transport_url, manifest_json, position.toInt(), enabled, watch_activity)

/** The stored row as [writeAddonList] takes it back. */
internal fun Addon.toDescriptor() = AddonDescriptor(transport_url, manifest_json, enabled, watch_activity)
